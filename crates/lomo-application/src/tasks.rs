//! Task items are projected from Markdown task lists; completion writes through shared transactions.

use std::{collections::BTreeMap, sync::Arc};

use lomo_core::{LomoError, OperationId};
use lomo_store::{MemoFilters, MemoQuery, MemoSummary};
use lomo_workspace::MemoId;
use serde::{Deserialize, Serialize};

use crate::{
    error::{storage, validation},
    paging::{NEVER_CANCELLED, collect_summaries, verify_summary_version},
    session::WorkspaceSession,
    types::{UpdateMemoRequest, UpdateMemoResult},
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct TaskItem {
    pub memo_id: String,
    pub line_index: u32,
    pub done: bool,
    pub text: String,
    pub source_path: String,
}

/// One memo's task rows, reused only while its complete source/version key matches.
/// The projection stores rows once; the public list is materialized in current query order.
pub(crate) struct TaskMemoProjection {
    summary: MemoSummary,
    pub items: Vec<TaskItem>,
}

impl TaskMemoProjection {
    fn matches(&self, summary: &MemoSummary) -> bool {
        self.summary.memo_id == summary.memo_id
            && self.summary.source_path == summary.source_path
            && self.summary.content_revision == summary.content_revision
            && self.summary.file_fingerprint == summary.file_fingerprint
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct ToggleTaskRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub line_index: u32,
    pub done: bool,
}

impl WorkspaceSession {
    /// Aggregates checkbox tasks from active memos.
    ///
    /// The task view is a function of one projection revision: the shared
    /// summary walk selects todo-bearing memos. Existing task rows survive unrelated
    /// commits; only new or changed memo versions hydrate and parse their bodies.
    /// The current query order and membership own the published list.
    ///
    /// # Errors
    /// Projection access failures; `stale_projection` when a commit lands
    /// between the walk and body hydration.
    pub fn list_tasks(&self) -> Result<Vec<TaskItem>, LomoError> {
        let revision = self.with_store(|store| Ok(store.high_water_revision()))?;
        let cached = self
            .query_projection
            .lock()
            .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
            .tasks_at(revision);
        if let Some(items) = cached {
            return Ok(items);
        }

        let start = std::time::Instant::now();
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                has_todo: Some(true),
                ..MemoFilters::default()
            },
            sort: lomo_store::MemoSort::default(),
        };
        let walk = collect_summaries(self, &query, NEVER_CANCELLED)?;
        let previous: BTreeMap<_, _> = self
            .query_projection
            .lock()
            .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
            .previous_task_memos()
            .map(|memo| (memo.summary.memo_id.clone(), memo))
            .collect();
        let memo_ids = walk
            .items
            .iter()
            .filter(|summary| {
                !previous
                    .get(&summary.memo_id)
                    .is_some_and(|memo| memo.matches(summary))
            })
            .map(|summary| summary.memo_id.clone())
            .collect::<Vec<_>>();
        let mut snapshots = self
            .with_reader(|store| store.get_projected_memos(&memo_ids))?
            .into_iter()
            .map(|snapshot| (snapshot.summary.memo_id.clone(), snapshot))
            .collect::<BTreeMap<_, _>>();
        let mut memos = Vec::with_capacity(walk.items.len());
        for summary in walk.items.iter() {
            if let Some(memo) = previous
                .get(&summary.memo_id)
                .filter(|memo| memo.matches(summary))
            {
                memos.push(Arc::clone(memo));
            } else {
                let snapshot = snapshots
                    .remove(&summary.memo_id)
                    .ok_or_else(|| validation("memo_not_found", "task memo disappeared"))?;
                verify_summary_version(summary, &snapshot.summary)?;
                memos.push(Arc::new(TaskMemoProjection {
                    summary: summary.clone(),
                    items: parse_tasks(&summary.memo_id, &summary.source_path, &snapshot.body)?,
                }));
            }
        }
        // Reused rows are still bound to this walk. Refuse a commit during either hydration
        // or reuse rather than publishing a mixed revision under the new cache key.
        let current_revision = self.with_store(|store| Ok(store.high_water_revision()))?;
        if current_revision != walk.revision {
            return Err(validation(
                "stale_projection",
                "projection changed while deriving task rows",
            ));
        }
        let items = memos
            .iter()
            .flat_map(|memo| memo.items.iter().cloned())
            .collect();
        let micros = u64::try_from(start.elapsed().as_micros()).unwrap_or(u64::MAX);
        self.query_projection
            .lock()
            .map_err(|error| storage("query_projection_poisoned", error.to_string()))?
            .record_tasks(walk.revision, memos, micros, memo_ids.len());
        Ok(items)
    }

    /// Toggles a rendered task span through the same durable memo transaction as edits.
    ///
    /// # Errors
    /// Invalid source spans, missing memos, stale writes, or platform failures.
    pub fn toggle_task_marker(
        &self,
        operation_id: OperationId,
        memo_id: MemoId,
        start: u64,
        end: u64,
    ) -> Result<UpdateMemoResult, LomoError> {
        let current = self.current_memo(&memo_id)?;
        let content = rewrite_task_marker(&current.body, &current.summary.source_path, start, end)?;
        self.update_memo(UpdateMemoRequest {
            operation_id,
            memo_id,
            content,
            expected_document_fingerprint: current.summary.file_fingerprint,
            pending_promotes: Vec::new(),
        })
    }

    /// Rewrites one task marker through the shared memo update transaction.
    ///
    /// # Errors
    /// Missing lines, stale baselines and write failures.
    pub fn toggle_task(&self, request: ToggleTaskRequest) -> Result<UpdateMemoResult, LomoError> {
        let current = self.current_memo(&request.memo_id)?;
        let content = rewrite_task(&current.body, request.line_index, request.done)?;
        self.update_memo(UpdateMemoRequest {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            content,
            expected_document_fingerprint: current.summary.file_fingerprint,
            pending_promotes: Vec::new(),
        })
    }
}

fn rewrite_task_marker(
    body: &str,
    source_path: &str,
    start: u64,
    end: u64,
) -> Result<String, LomoError> {
    let source = lomo_workspace::SourceBytes::try_from_str(body)?;
    let document = lomo_workspace::parse_workspace_document(&source, "task-body")?;
    // Durable IDs identify a memo across edits; source locators belong only to this parse.
    // Rendering a memo body gives one source-local memo, never a durable ID spelling.
    let identity = document
        .memos()
        .first()
        .ok_or_else(|| validation("task_body_missing", "memo body has no source memo"))?
        .identity()
        .clone();
    let rendered = lomo_workspace::project_markdown_facts(&source)?;
    let start_index = usize::try_from(start)
        .map_err(|error| validation("task_action_span_out_of_bounds", error.to_string()))?;
    let end_index = usize::try_from(end)
        .map_err(|error| validation("task_action_span_out_of_bounds", error.to_string()))?;
    if !rendered.semantic_facts().iter().any(|fact| {
        fact.kind() == lomo_workspace::SemanticFactKind::TaskItem
            && fact.source_span().start() == start_index
            && fact.source_span().end() == end_index
    }) {
        return Err(validation(
            "task_target_not_found",
            "span must address a rendered task marker",
        ));
    }
    let plan = lomo_workspace::plan_document_patch(
        &document,
        &lomo_workspace::DocumentPatchCommand::ToggleTask {
            path: lomo_workspace::WorkspaceRelativePath::parse(source_path)?,
            expected_fingerprint: source.fingerprint().clone(),
            identity,
            body_start: start,
            body_end: end,
        },
    )?;
    String::from_utf8(plan.result_bytes().to_vec())
        .map_err(|error| validation("invalid_task_body", error.to_string()))
}

fn parse_tasks(memo_id: &str, source_path: &str, body: &str) -> Result<Vec<TaskItem>, LomoError> {
    let source = lomo_workspace::SourceBytes::try_from_str(body)?;
    let document = lomo_workspace::project_markdown_facts(&source)?;
    document
        .semantic_facts()
        .iter()
        .filter(|fact| fact.kind() == lomo_workspace::SemanticFactKind::TaskItem)
        .map(|fact| {
            let prefix = source.slice(lomo_workspace::ByteSpan::try_new(
                0,
                fact.source_span().start(),
                body.len(),
            )?)?;
            let line_index = u32::try_from(prefix.bytes().filter(|byte| *byte == b'\n').count())
                .map_err(|error| validation("invalid_task_line", error.to_string()))?;
            let marker = source.slice(fact.source_span())?;
            let tail = body.get(fact.source_span().end()..).ok_or_else(|| {
                validation(
                    "task_action_span_out_of_bounds",
                    "task span is outside body",
                )
            })?;
            Ok(TaskItem {
                memo_id: memo_id.to_owned(),
                line_index,
                done: marker != "[ ]",
                text: tail
                    .split_once('\n')
                    .map_or(tail, |(line, _)| line)
                    .trim()
                    .to_owned(),
                source_path: source_path.to_owned(),
            })
        })
        .collect()
}

fn rewrite_task(body: &str, line_index: u32, done: bool) -> Result<String, LomoError> {
    let source = lomo_workspace::SourceBytes::try_from_str(body)?;
    let rendered = lomo_workspace::project_markdown_facts(&source)?;
    for fact in rendered
        .semantic_facts()
        .iter()
        .filter(|fact| fact.kind() == lomo_workspace::SemanticFactKind::TaskItem)
    {
        let prefix = body.get(..fact.source_span().start()).ok_or_else(|| {
            validation(
                "task_action_span_out_of_bounds",
                "task span is outside body",
            )
        })?;
        let line = u32::try_from(prefix.bytes().filter(|byte| *byte == b'\n').count())
            .map_err(|error| validation("invalid_task_line", error.to_string()))?;
        if line == line_index {
            let checked = source.slice(fact.source_span())? != "[ ]";
            if checked == done {
                return Ok(body.to_owned());
            }
            return rewrite_task_marker(
                body,
                "task.md",
                u64::try_from(fact.source_span().start()).map_err(|error| {
                    validation("task_action_span_out_of_bounds", error.to_string())
                })?,
                u64::try_from(fact.source_span().end()).map_err(|error| {
                    validation("task_action_span_out_of_bounds", error.to_string())
                })?,
            );
        }
    }
    Err(validation(
        "task_line_missing",
        "addressed line has no rendered task marker",
    ))
}
