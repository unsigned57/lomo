//! Bounded scan of durable workspace trash records.
//!
//! Record envelopes and payloads are decoded only by `lomo-workspace`. Complete bodies remain in
//! the app-private exchange directory and cross FFI as typed references, matching active scans.

use lomo_core::{
    DriverAdvance, DriverStart, ExchangeToken, ExpectedFingerprint, JobDriver, JobDriverContext,
    LomoError, PageSize, PlatformAction, PlatformActionBatch, PlatformActionOutput,
    PlatformBatchResult, WorkspaceTarget,
};
use serde::{Deserialize, Serialize};

use crate::limits::{ResourceBudget, corruption, validation};
use crate::trash::{TRASH_RECORD_DIRECTORY, decode_trash_record, trash_record_relative_path};
use crate::types::WorkspaceRelativePath;

use super::scan::WorkspaceMemoContentReference;
use super::shared::{
    ListedDocument, exchange_token_for, first_applied_output, is_file_metadata, listed_page,
    plan_listed_read, read_exchange_bytes, read_to_exchange_output, remove_exchange_artifact,
    to_core_path, write_exchange_bytes,
};

pub const TRASH_SCAN_DRIVER_KIND: &str = "workspace-trash-scan-v1";

/// Public trash scan request accepted by the engine driver (JSON).
///
/// Deserialization and host construction share [`TrashScanRequest::validate`]: an over-budget
/// page size cannot become a scan request.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(try_from = "TrashScanRequestJson")]
pub struct TrashScanRequest {
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[derive(Deserialize)]
struct TrashScanRequestJson {
    page_size: u32,
    cursor: Option<String>,
}

impl TryFrom<TrashScanRequestJson> for TrashScanRequest {
    type Error = LomoError;

    fn try_from(json: TrashScanRequestJson) -> Result<Self, LomoError> {
        let request = Self {
            page_size: json.page_size,
            cursor: json.cursor,
        };
        request.validate()?;
        Ok(request)
    }
}

impl TrashScanRequest {
    /// Validates the frozen scan request before the driver can run it.
    ///
    /// # Errors
    /// Resource-limit when the page size exceeds the scan budget.
    pub fn validate(&self) -> Result<(), LomoError> {
        ResourceBudget::check_workspace_scan_page_size(self.page_size)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct TrashMemoSummary {
    pub memo_id: String,
    pub source_path: String,
    pub time_part: String,
    pub source_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub trashed_at_ms: i64,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<crate::ReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
    pub content: WorkspaceMemoContentReference,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct TrashScanPage {
    pub items: Vec<TrashMemoSummary>,
    pub next_cursor: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct TrashScanState {
    page_size: u32,
    list_cursor: Option<String>,
    pending_documents: Vec<ListedDocument>,
    pending_index: usize,
    listed_once: bool,
    phase: TrashScanPhase,
    exchange_token: Option<String>,
    current_path: Option<String>,
    accumulated: Vec<TrashMemoSummary>,
    emitted_total: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
enum TrashScanPhase {
    EnsureDirectory,
    List,
    Read,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct TrashScanCursorV1 {
    version: u32,
    list_cursor: Option<String>,
    pending_documents: Vec<ListedDocument>,
    pending_index: usize,
    emitted_total: u64,
}

impl TrashScanCursorV1 {
    const VERSION: u32 = 1;

    fn encode(&self) -> Result<String, LomoError> {
        serde_json::to_string(self).map_err(|_error| {
            corruption(
                "trash_scan_cursor_encode_failed",
                "trash scan cursor cannot be serialized",
            )
        })
    }

    fn decode(raw: &str) -> Result<Self, LomoError> {
        let cursor: Self = serde_json::from_str(raw).map_err(|_error| {
            validation(
                "invalid_trash_scan_cursor",
                "trash scan cursor is not valid JSON",
            )
        })?;
        if cursor.version != Self::VERSION || cursor.pending_index > cursor.pending_documents.len()
        {
            return Err(validation(
                "invalid_trash_scan_cursor",
                "trash scan cursor version or pending index is invalid",
            ));
        }
        for document in &cursor.pending_documents {
            let _path = WorkspaceRelativePath::parse(&document.path)?;
            let _handle = lomo_core::DocumentHandle::parse(&document.document_handle)?;
        }
        Ok(cursor)
    }
}

pub struct TrashScanDriver;

impl JobDriver for TrashScanDriver {
    fn kind(&self) -> &'static str {
        TRASH_SCAN_DRIVER_KIND
    }

    fn recover_canonical_request_json(
        &self,
        state_json: &str,
    ) -> Result<Option<String>, LomoError> {
        let state = decode_state(state_json)?;
        if state.emitted_total != 0 {
            return Ok(None);
        }
        serde_json::to_string(&TrashScanRequest {
            page_size: state.page_size,
            cursor: None,
        })
        .map(Some)
        .map_err(|_error| {
            corruption(
                "trash_scan_state_invalid",
                "trash scan request identity cannot be reconstructed",
            )
        })
    }

    fn terminal_cleanup_exchange_artifacts(
        &self,
        state_json: &str,
    ) -> Result<Vec<ExchangeToken>, LomoError> {
        let state = decode_state(state_json)?;
        state
            .exchange_token
            .into_iter()
            .map(|token| ExchangeToken::parse(&token))
            .collect()
    }

    fn start(
        &self,
        ctx: &mut JobDriverContext<'_>,
        request_json: &str,
    ) -> Result<DriverStart, LomoError> {
        let request: TrashScanRequest = serde_json::from_str(request_json).map_err(|_error| {
            validation(
                "invalid_trash_scan_request",
                "workspace trash scan request JSON is invalid",
            )
        })?;
        let cursor = request
            .cursor
            .as_deref()
            .map(TrashScanCursorV1::decode)
            .transpose()?;
        let state = TrashScanState {
            page_size: request.page_size,
            list_cursor: cursor.as_ref().and_then(|value| value.list_cursor.clone()),
            pending_documents: cursor
                .as_ref()
                .map_or_else(Vec::new, |value| value.pending_documents.clone()),
            pending_index: cursor.as_ref().map_or(0, |value| value.pending_index),
            listed_once: false,
            phase: TrashScanPhase::EnsureDirectory,
            exchange_token: None,
            current_path: None,
            accumulated: Vec::new(),
            emitted_total: cursor.as_ref().map_or(0, |value| value.emitted_total),
        };
        let directory = WorkspaceRelativePath::parse(TRASH_RECORD_DIRECTORY)?;
        Ok(DriverStart {
            state_json: encode_state(&state)?,
            actions: vec![PlatformAction::ensure_directory(
                ctx.next_action_id("trash-scan-directory")?,
                ctx.capability(),
                to_core_path(&directory)?,
            )],
            result_json: None,
        })
    }

    fn advance(
        &self,
        ctx: &mut JobDriverContext<'_>,
        state_json: &str,
        batch: &PlatformActionBatch,
        result: &PlatformBatchResult,
    ) -> Result<DriverAdvance, LomoError> {
        let mut state = decode_state(state_json)?;
        match state.phase {
            TrashScanPhase::EnsureDirectory => {
                let output = first_applied_output(batch, result, 0)?;
                if !matches!(output, PlatformActionOutput::DirectoryReady { .. }) {
                    return Err(corruption(
                        "trash_scan_directory_unverified",
                        "trash scan directory action returned the wrong output",
                    ));
                }
                driver_start_to_advance(plan_next(ctx, &mut state)?)
            }
            TrashScanPhase::List => advance_after_list(ctx, &mut state, batch, result),
            TrashScanPhase::Read => advance_after_read(ctx, &mut state, batch, result),
        }
    }
}

fn advance_after_list(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashScanState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let page = listed_page(first_applied_output(batch, result, 0)?)?;
    state.pending_documents = page
        .items()
        .iter()
        .filter(|item| is_file_metadata(item))
        .filter_map(|item| match item.target() {
            WorkspaceTarget::Relative(path) if is_record_file(path.as_str()) => {
                Some(ListedDocument {
                    path: path.as_str().to_owned(),
                    document_handle: item.document_handle().as_str().to_owned(),
                })
            }
            WorkspaceTarget::Root | WorkspaceTarget::Relative(_) => None,
        })
        .collect();
    state.pending_index = 0;
    state.listed_once = true;
    state.list_cursor = page.next_cursor().map(|cursor| cursor.as_str().to_owned());
    driver_start_to_advance(plan_next(ctx, state)?)
}

fn advance_after_read(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashScanState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let (_metadata, artifact) = read_to_exchange_output(first_applied_output(batch, result, 0)?)?;
    let token = state.exchange_token.clone().ok_or_else(|| {
        corruption(
            "trash_scan_exchange_token_missing",
            "trash scan read phase has no exchange token",
        )
    })?;
    if artifact.token().as_str() != token {
        return Err(corruption(
            "trash_scan_exchange_token_mismatch",
            "trash scan read token does not match the planned token",
        ));
    }
    let path = state.current_path.clone().ok_or_else(|| {
        corruption(
            "trash_scan_current_path_missing",
            "trash scan read phase has no current path",
        )
    })?;
    let record_bytes = read_exchange_bytes(ctx.exchange_root, &token)?;
    let record = decode_trash_record(&record_bytes)?;
    let canonical_path = trash_record_relative_path(&record.memo_id)?;
    if canonical_path.as_str() != path {
        return Err(validation(
            "trash_record_path_mismatch",
            "trash record filename does not match its hashed memo identity",
        ));
    }
    let ordinal = state
        .emitted_total
        .checked_add(u64::try_from(state.accumulated.len()).map_err(|_error| {
            validation(
                "trash_scan_count_overflow",
                "trash scan page count cannot be represented",
            )
        })?)
        .ok_or_else(|| {
            validation(
                "trash_scan_count_overflow",
                "trash scan emitted count cannot advance",
            )
        })?;
    let content_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        &format!("trash-body-{ordinal}"),
    );
    let (length, digest) =
        write_exchange_bytes(ctx.exchange_root, &content_token, record.body.as_bytes())?;
    state.accumulated.push(TrashMemoSummary {
        memo_id: record.memo_id,
        source_path: record.source_path,
        time_part: record.time_part,
        source_fingerprint: record.source_fingerprint,
        chronology_epoch_ms: record.chronology_epoch_ms,
        trashed_at_ms: record.trashed_at_ms,
        tags: record.tags,
        attachments: record.attachments,
        reminders: record.reminders,
        has_todo: record.has_todo,
        has_url: record.has_url,
        content: WorkspaceMemoContentReference {
            exchange_token: content_token,
            length,
            digest,
        },
    });
    remove_exchange_artifact(ctx.exchange_root, &token)?;
    state.exchange_token = None;
    state.current_path = None;
    state.pending_index = state.pending_index.saturating_add(1);
    driver_start_to_advance(plan_next(ctx, state)?)
}

fn plan_next(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashScanState,
) -> Result<DriverStart, LomoError> {
    let page_size = usize::try_from(state.page_size).map_err(|_error| {
        validation(
            "invalid_trash_scan_page_size",
            "trash scan page size cannot be represented",
        )
    })?;
    if state.accumulated.len() >= page_size {
        return finish_page(state);
    }
    if state.pending_index < state.pending_documents.len() {
        let document = state
            .pending_documents
            .get(state.pending_index)
            .cloned()
            .ok_or_else(|| {
                corruption(
                    "trash_scan_pending_path_missing",
                    "trash scan pending path index is outside the listed page",
                )
            })?;
        let token = exchange_token_for(
            ctx.workspace.identity().as_str(),
            ctx.job_id.as_str(),
            &format!("trash-scan-read-{}", state.pending_index),
        );
        let action = plan_listed_read(
            ctx.next_action_id("trash-scan-read")?,
            ctx.capability(),
            to_core_path(&WorkspaceRelativePath::parse(&document.path)?)?,
            &document.document_handle,
            &token,
            ExpectedFingerprint::absent(),
        )?;
        state.phase = TrashScanPhase::Read;
        state.exchange_token = Some(token);
        state.current_path = Some(document.path);
        return Ok(DriverStart {
            state_json: encode_state(state)?,
            actions: vec![action],
            result_json: None,
        });
    }
    if !state.listed_once && state.accumulated.is_empty() {
        state.phase = TrashScanPhase::List;
        let directory = WorkspaceRelativePath::parse(TRASH_RECORD_DIRECTORY)?;
        return Ok(DriverStart {
            state_json: encode_state(state)?,
            actions: vec![PlatformAction::list_children(
                ctx.next_action_id("trash-scan-list")?,
                ctx.capability(),
                to_core_path(&directory)?,
                state.list_cursor.clone(),
                PageSize::new(MAX_TRASH_LIST_PAGE_SIZE)?,
            )],
            result_json: None,
        });
    }
    finish_page(state)
}

fn finish_page(state: &TrashScanState) -> Result<DriverStart, LomoError> {
    let has_more =
        state.pending_index < state.pending_documents.len() || state.list_cursor.is_some();
    let next_cursor = if has_more {
        let emitted = state
            .emitted_total
            .checked_add(u64::try_from(state.accumulated.len()).map_err(|_error| {
                validation(
                    "trash_scan_count_overflow",
                    "trash scan page count cannot be represented",
                )
            })?)
            .ok_or_else(|| {
                validation(
                    "trash_scan_count_overflow",
                    "trash scan emitted count cannot advance",
                )
            })?;
        Some(
            TrashScanCursorV1 {
                version: TrashScanCursorV1::VERSION,
                list_cursor: state.list_cursor.clone(),
                pending_documents: state.pending_documents.clone(),
                pending_index: state.pending_index,
                emitted_total: emitted,
            }
            .encode()?,
        )
    } else {
        None
    };
    let result_json = serde_json::to_string(&TrashScanPage {
        items: state.accumulated.clone(),
        next_cursor,
    })
    .map_err(|_error| {
        corruption(
            "trash_scan_page_encode_failed",
            "trash scan page cannot be serialized",
        )
    })?;
    Ok(DriverStart {
        state_json: encode_state(state)?,
        actions: Vec::new(),
        result_json: Some(result_json),
    })
}

fn driver_start_to_advance(start: DriverStart) -> Result<DriverAdvance, LomoError> {
    if start.actions.is_empty() {
        Ok(DriverAdvance::Done {
            result_json: start.result_json.ok_or_else(|| {
                corruption(
                    "trash_scan_result_missing",
                    "completed trash scan has no page result",
                )
            })?,
        })
    } else {
        Ok(DriverAdvance::NeedsBatch {
            state_json: start.state_json,
            actions: start.actions,
            result_json: start.result_json,
        })
    }
}

fn encode_state(state: &TrashScanState) -> Result<String, LomoError> {
    serde_json::to_string(state).map_err(|_error| {
        corruption(
            "trash_scan_state_invalid",
            "trash scan state cannot be serialized",
        )
    })
}

fn decode_state(state_json: &str) -> Result<TrashScanState, LomoError> {
    serde_json::from_str(state_json).map_err(|_error| {
        corruption(
            "trash_scan_state_invalid",
            "trash scan durable state is invalid",
        )
    })
}

fn is_record_file(path: &str) -> bool {
    path.rsplit('/').next().is_some_and(|name| {
        name.len() > 4
            && name
                .as_bytes()
                .get(name.len() - 4..)
                .is_some_and(|suffix| suffix.eq_ignore_ascii_case(b".rec"))
    })
}

const MAX_TRASH_LIST_PAGE_SIZE: u32 = 63;
