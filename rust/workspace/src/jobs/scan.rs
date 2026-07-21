//! Workspace scan multi-phase job driver.
//!
//! Flow: list children (bounded) → read each markdown file to exchange → parse → publish page
//! (max 256 memo items) with an opaque Rust-owned cursor. Large file bodies never cross FFI.

use lomo_core::{
    DriverAdvance, DriverStart, ExpectedFingerprint, JobDriver, JobDriverContext, LomoError,
    PageSize, PlatformAction, PlatformActionBatch, PlatformBatchResult, WorkspaceTarget,
};
use serde::{Deserialize, Serialize};

use crate::WorkspaceMemo;
use crate::limits::{ResourceBudget, corruption, validation};
use crate::parse::parse_workspace_document;
use crate::reminder::ReminderReference;
use crate::source::SourceBytes;
use crate::types::WorkspaceRelativePath;

use super::shared::{
    FileMemoCursor, ScanCursorV2, exchange_token_for, filename_stem, first_applied_output,
    is_file_metadata, is_markdown_file, listed_page, plan_read, read_exchange_bytes,
    read_to_exchange_output, source_fingerprint_of, to_core_path,
};

pub const SCAN_DRIVER_KIND: &str = "workspace-scan-v1";

/// Public scan request accepted by the engine driver (JSON).
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct WorkspaceScanRequest {
    pub page_size: u32,
    pub cursor: Option<String>,
    /// Optional subdirectory relative path; omit/null means workspace root.
    pub root_path: Option<String>,
}

/// Typed reference to complete memo content in the application-private exchange directory.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct WorkspaceMemoContentReference {
    pub exchange_token: String,
    pub length: u64,
    pub digest: String,
}

/// One memo summary published by a scan page (no full body across FFI).
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct WorkspaceMemoSummary {
    pub path: String,
    pub identity: String,
    pub time_part: String,
    pub fingerprint: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<ReminderReference>,
    /// Task-list presence projected from the same parse as tags/attachments/render IR.
    pub has_todo: bool,
    /// External URL presence projected from the same parse as tags/attachments/render IR.
    pub has_url: bool,
    pub content: WorkspaceMemoContentReference,
    pub body_start: u64,
    pub body_end: u64,
    pub start_line: u32,
    pub end_line: u32,
}

/// Bounded scan page result.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct WorkspaceScanPage {
    pub items: Vec<WorkspaceMemoSummary>,
    pub next_cursor: Option<String>,
}

/// Opaque cursor wrapper type for API documentation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WorkspaceScanCursor(String);

impl WorkspaceScanCursor {
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct ScanState {
    page_size: u32,
    root_path: Option<String>,
    list_cursor: Option<String>,
    pending_paths: Vec<String>,
    pending_index: usize,
    current_file: Option<FileMemoCursor>,
    phase: ScanPhase,
    exchange_token: Option<String>,
    current_path: Option<String>,
    accumulated: Vec<WorkspaceMemoSummary>,
    emitted_total: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
enum ScanPhase {
    List,
    ReadFile,
    Done,
}

pub struct ScanDriver;

impl JobDriver for ScanDriver {
    fn kind(&self) -> &'static str {
        SCAN_DRIVER_KIND
    }

    fn start(
        &self,
        ctx: &mut JobDriverContext<'_>,
        request_json: &str,
    ) -> Result<DriverStart, LomoError> {
        let request: WorkspaceScanRequest =
            serde_json::from_str(request_json).map_err(|_error| {
                validation(
                    "invalid_workspace_scan_request",
                    "workspace scan request JSON is invalid",
                )
            })?;
        ResourceBudget::check_workspace_scan_page_size(request.page_size)?;
        if let Some(path) = request.root_path.as_deref() {
            let _root_path = WorkspaceRelativePath::parse(path)?;
        }

        let (list_cursor, pending_paths, pending_index, current_file, emitted_total) =
            if let Some(raw) = request.cursor.as_deref() {
                let cursor = ScanCursorV2::decode(raw)?;
                if cursor.root_path != request.root_path {
                    return Err(validation(
                        "workspace_scan_cursor_scope_mismatch",
                        "workspace scan cursor belongs to a different root path",
                    ));
                }
                (
                    cursor.list_cursor,
                    cursor.pending_paths,
                    cursor.pending_index,
                    cursor.current_file,
                    cursor.emitted,
                )
            } else {
                (None, Vec::new(), 0, None, 0)
            };

        let state = ScanState {
            page_size: request.page_size,
            root_path: request.root_path,
            list_cursor,
            pending_paths,
            pending_index,
            current_file,
            phase: ScanPhase::List,
            exchange_token: None,
            current_path: None,
            accumulated: Vec::new(),
            emitted_total,
        };

        if state.current_file.is_some() || state.pending_index < state.pending_paths.len() {
            // Resume mid-pending list without re-listing.
            let mut resumed = state;
            return plan_next_read(ctx, &mut resumed);
        }

        let actions = vec![list_action(ctx, &state)?];
        Ok(DriverStart {
            state_json: serde_json::to_string(&state).map_err(|_error| {
                validation(
                    "scan_state_encode_failed",
                    "workspace scan state cannot be serialized",
                )
            })?,
            actions,
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
        let mut state: ScanState = serde_json::from_str(state_json).map_err(|_error| {
            validation(
                "invalid_scan_driver_state",
                "workspace scan driver state is corrupt",
            )
        })?;

        match state.phase {
            ScanPhase::List => advance_after_list(ctx, &mut state, batch, result),
            ScanPhase::ReadFile => advance_after_read(ctx, &mut state, batch, result),
            ScanPhase::Done => Err(validation(
                "scan_already_done",
                "scan driver cannot advance a completed job",
            )),
        }
    }
}

fn advance_after_list(
    ctx: &mut JobDriverContext<'_>,
    state: &mut ScanState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let output = first_applied_output(batch, result, 0)?;
    let page = listed_page(output)?;
    let paths = page
        .items()
        .iter()
        .filter(|item| is_file_metadata(item))
        .filter_map(|item| match item.target() {
            WorkspaceTarget::Relative(path) if is_markdown_file(path.as_str()) => {
                Some(path.as_str().to_owned())
            }
            WorkspaceTarget::Root | WorkspaceTarget::Relative(_) => None,
        })
        .collect();
    state.list_cursor = page.next_cursor().map(|cursor| cursor.as_str().to_owned());
    state.pending_paths = paths;
    state.pending_index = 0;
    state.current_file = None;
    driver_start_to_advance(plan_next_read(ctx, state)?)
}

fn advance_after_read(
    ctx: &mut JobDriverContext<'_>,
    state: &mut ScanState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let output = first_applied_output(batch, result, 0)?;
    let (_metadata, artifact) = read_to_exchange_output(output)?;
    let path = state.current_path.clone().ok_or_else(|| {
        validation(
            "scan_missing_current_path",
            "scan read phase is missing the current path",
        )
    })?;
    let token = state.exchange_token.clone().ok_or_else(|| {
        validation(
            "scan_missing_exchange_token",
            "scan read phase is missing the exchange token",
        )
    })?;
    if artifact.token().as_str() != token {
        return Err(validation(
            "scan_exchange_token_mismatch",
            "read-to-exchange token does not match the planned token",
        ));
    }
    let bytes = read_exchange_bytes(ctx.exchange_root, &token)?;
    project_file_page(ctx, state, &path, bytes)?;
    state.exchange_token = None;
    state.current_path = None;
    driver_start_to_advance(plan_next_read(ctx, state)?)
}

fn project_file_page(
    ctx: &JobDriverContext<'_>,
    state: &mut ScanState,
    path: &str,
    bytes: Vec<u8>,
) -> Result<(), LomoError> {
    if bytes.is_empty() {
        state.pending_index = state.pending_index.saturating_add(1);
        return Ok(());
    }
    let source = SourceBytes::try_from_bytes(bytes)?;
    let stem = filename_stem(path)?;
    let document = parse_workspace_document(&source, &stem)?;
    let fingerprint = source_fingerprint_of(source.as_bytes()).as_str().to_owned();
    let resume_index = resume_index(state.current_file.as_ref(), path, &fingerprint)?;
    if resume_index > document.memos().len() {
        return Err(validation(
            "stale_snapshot",
            "workspace scan cursor memo offset is outside the source revision",
        ));
    }
    let accumulated = u32::try_from(state.accumulated.len()).map_err(|_error| {
        validation(
            "invalid_workspace_scan_page_size",
            "workspace scan accumulation exceeds the bounded page representation",
        )
    })?;
    let remaining_capacity =
        usize::try_from(state.page_size.saturating_sub(accumulated)).map_err(|_error| {
            validation(
                "invalid_workspace_scan_page_size",
                "workspace scan page size cannot be represented",
            )
        })?;
    let remaining = document.memos().get(resume_index..).unwrap_or(&[]);
    let selected = remaining.iter().take(remaining_capacity);
    let accumulated_count = u64::try_from(state.accumulated.len()).map_err(|_error| {
        validation(
            "scan_page_count_overflow",
            "workspace scan page count cannot be represented",
        )
    })?;
    let first_content_ordinal = state
        .emitted_total
        .checked_add(accumulated_count)
        .ok_or_else(|| {
            validation(
                "scan_emitted_count_overflow",
                "workspace scan emitted count cannot advance",
            )
        })?;
    let mut projected = Vec::with_capacity(remaining.len().min(remaining_capacity));
    for (offset, memo) in selected.enumerate() {
        let offset = u64::try_from(offset).map_err(|_error| {
            validation(
                "scan_page_count_overflow",
                "workspace scan page count cannot be represented",
            )
        })?;
        let content_ordinal = first_content_ordinal.checked_add(offset).ok_or_else(|| {
            validation(
                "scan_emitted_count_overflow",
                "workspace scan emitted count cannot advance",
            )
        })?;
        projected.push(project_memo_summary(
            ctx,
            path,
            &fingerprint,
            memo,
            content_ordinal,
        )?);
    }
    state.accumulated.extend(projected);
    let next_memo_index = resume_index.saturating_add(remaining.len().min(remaining_capacity));
    if next_memo_index < document.memos().len() {
        state.current_file = Some(FileMemoCursor {
            path: path.to_owned(),
            source_fingerprint: fingerprint,
            next_memo_index,
        });
    } else {
        state.current_file = None;
        state.pending_index = state.pending_index.saturating_add(1);
    }
    Ok(())
}

fn project_memo_summary(
    ctx: &JobDriverContext<'_>,
    path: &str,
    fingerprint: &str,
    memo: &WorkspaceMemo,
    content_ordinal: u64,
) -> Result<WorkspaceMemoSummary, LomoError> {
    ResourceBudget::check_editable_memo_chars(memo.content().chars().count())?;
    let content_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        &format!("memo-{content_ordinal}"),
    );
    let (length, digest) = super::shared::write_exchange_bytes(
        ctx.exchange_root,
        &content_token,
        memo.content().as_bytes(),
    )?;
    let body_start = u64::try_from(memo.body_span().start()).map_err(|_error| {
        validation(
            "scan_body_span_overflow",
            "memo body start cannot be represented at the scan boundary",
        )
    })?;
    let body_end = u64::try_from(memo.body_span().end()).map_err(|_error| {
        validation(
            "scan_body_span_overflow",
            "memo body end cannot be represented at the scan boundary",
        )
    })?;
    Ok(WorkspaceMemoSummary {
        path: path.to_owned(),
        identity: memo.identity().as_str().to_owned(),
        time_part: memo.time_part().to_owned(),
        fingerprint: fingerprint.to_owned(),
        tags: memo.tags().to_vec(),
        attachments: memo.attachments().to_vec(),
        reminders: memo
            .reminders()
            .iter()
            .map(ReminderReference::from)
            .collect(),
        has_todo: memo.has_todo(),
        has_url: memo.has_url(),
        content: WorkspaceMemoContentReference {
            exchange_token: content_token,
            length,
            digest,
        },
        body_start,
        body_end,
        start_line: memo.start_line(),
        end_line: memo.end_line(),
    })
}

fn resume_index(
    cursor: Option<&FileMemoCursor>,
    path: &str,
    fingerprint: &str,
) -> Result<usize, LomoError> {
    let Some(cursor) = cursor else {
        return Ok(0);
    };
    if cursor.path != path || cursor.source_fingerprint != fingerprint {
        return Err(validation(
            "stale_snapshot",
            "workspace scan cursor source revision changed before resume",
        ));
    }
    Ok(cursor.next_memo_index)
}

fn list_action(
    ctx: &mut JobDriverContext<'_>,
    state: &ScanState,
) -> Result<PlatformAction, LomoError> {
    let action_id = ctx.next_action_id("scan-list")?;
    let capability = ctx.capability();
    let page_size = PageSize::new(256)?;
    match state.root_path.as_deref() {
        Some(path) => {
            let relative = to_core_path(&WorkspaceRelativePath::parse(path)?)?;
            Ok(PlatformAction::list_children(
                action_id,
                capability,
                relative,
                state.list_cursor.clone(),
                page_size,
            ))
        }
        None => Ok(PlatformAction::list_root(
            action_id,
            capability,
            state.list_cursor.clone(),
            page_size,
        )),
    }
}

fn plan_next_read(
    ctx: &mut JobDriverContext<'_>,
    state: &mut ScanState,
) -> Result<DriverStart, LomoError> {
    // Page full → publish and stop (or continue later via cursor).
    let page_size = usize::try_from(state.page_size).map_err(|_error| {
        validation(
            "invalid_workspace_scan_page_size",
            "workspace scan page size cannot be represented",
        )
    })?;
    if state.accumulated.len() >= page_size {
        return finish_page(state);
    }

    if state.pending_index < state.pending_paths.len() {
        let path = match state.current_file.as_ref() {
            Some(current) => current.path.clone(),
            None => state
                .pending_paths
                .get(state.pending_index)
                .cloned()
                .ok_or_else(|| {
                    corruption(
                        "scan_pending_path_missing",
                        "workspace scan pending path index is out of range",
                    )
                })?,
        };
        let memo_offset = state
            .current_file
            .as_ref()
            .map_or(0, |current| current.next_memo_index);
        let token = exchange_token_for(
            ctx.workspace.identity().as_str(),
            ctx.job_id.as_str(),
            &format!("scan-{}-{memo_offset}", state.pending_index),
        );
        let relative = to_core_path(&WorkspaceRelativePath::parse(&path)?)?;
        let action = plan_read(
            ctx.next_action_id("scan-read")?,
            ctx.capability(),
            relative,
            &token,
            ExpectedFingerprint::absent(),
        )?;
        state.phase = ScanPhase::ReadFile;
        state.exchange_token = Some(token);
        state.current_path = Some(path);
        return Ok(DriverStart {
            state_json: encode_state(state)?,
            actions: vec![action],
            result_json: None,
        });
    }

    // Pending exhausted.
    if state.list_cursor.is_some() {
        // More listing available — either publish partial page or continue listing if empty.
        if !state.accumulated.is_empty() {
            return finish_page(state);
        }
        state.phase = ScanPhase::List;
        let action = list_action(ctx, state)?;
        return Ok(DriverStart {
            state_json: encode_state(state)?,
            actions: vec![action],
            result_json: None,
        });
    }

    // Fully drained.
    finish_page(state)
}

fn finish_page(state: &mut ScanState) -> Result<DriverStart, LomoError> {
    let has_more = state.current_file.is_some()
        || state.pending_index < state.pending_paths.len()
        || state.list_cursor.is_some();
    let next_cursor = if has_more {
        let emitted = u64::try_from(state.accumulated.len()).map_err(|_error| {
            validation(
                "scan_page_count_overflow",
                "workspace scan page count cannot be represented",
            )
        })?;
        let cursor = ScanCursorV2 {
            v: ScanCursorV2::VERSION,
            root_path: state.root_path.clone(),
            list_cursor: state.list_cursor.clone(),
            pending_paths: state.pending_paths.clone(),
            pending_index: state.pending_index,
            current_file: state.current_file.clone(),
            emitted: state.emitted_total.checked_add(emitted).ok_or_else(|| {
                validation(
                    "scan_emitted_count_overflow",
                    "workspace scan emitted count cannot advance",
                )
            })?,
        };
        Some(cursor.encode()?)
    } else {
        None
    };

    let page = WorkspaceScanPage {
        items: state.accumulated.clone(),
        next_cursor,
    };
    let result_json = serde_json::to_string(&page).map_err(|_error| {
        validation(
            "scan_page_encode_failed",
            "workspace scan page cannot be serialized",
        )
    })?;
    state.phase = ScanPhase::Done;
    state.accumulated.clear();
    Ok(DriverStart {
        state_json: encode_state(state)?,
        actions: Vec::new(),
        result_json: Some(result_json),
    })
}

fn encode_state(state: &ScanState) -> Result<String, LomoError> {
    serde_json::to_string(state).map_err(|_error| {
        validation(
            "scan_state_encode_failed",
            "workspace scan state cannot be serialized",
        )
    })
}

fn driver_start_to_advance(started: DriverStart) -> Result<DriverAdvance, LomoError> {
    if started.actions.is_empty() {
        let result_json = started.result_json.ok_or_else(|| {
            validation(
                "scan_result_missing",
                "completed workspace scan must contain a page result",
            )
        })?;
        Ok(DriverAdvance::Done { result_json })
    } else {
        Ok(DriverAdvance::NeedsBatch {
            state_json: started.state_json,
            actions: started.actions,
            result_json: started.result_json,
        })
    }
}
