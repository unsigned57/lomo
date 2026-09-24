//! Task items are projected from Markdown task lists; completion writes through shared transactions.

use lomo_core::{LomoError, OperationId};
use lomo_store::{MemoFilters, MemoQuery};
use lomo_workspace::MemoId;
use serde::{Deserialize, Serialize};

use crate::{
    error::validation,
    paging::collect_summaries,
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
    /// # Errors
    /// Projection access failures.
    pub fn list_tasks(&self) -> Result<Vec<TaskItem>, LomoError> {
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                has_todo: Some(true),
                ..MemoFilters::default()
            },
            sort: lomo_store::MemoSort::default(),
        };
        let summaries = collect_summaries(self, &query)?;
        let memo_ids = summaries
            .iter()
            .map(|summary| summary.memo_id.clone())
            .collect::<Vec<_>>();
        let mut snapshots = self
            .with_reader(|store| store.get_projected_memos(&memo_ids))?
            .into_iter()
            .map(|snapshot| (snapshot.summary.memo_id.clone(), snapshot))
            .collect::<std::collections::BTreeMap<_, _>>();
        let mut tasks = Vec::new();
        for summary in summaries {
            let snapshot = snapshots
                .remove(&summary.memo_id)
                .ok_or_else(|| validation("memo_not_found", "task memo disappeared"))?;
            tasks.extend(parse_tasks(
                &summary.memo_id,
                &summary.source_path,
                &snapshot.body,
            ));
        }
        Ok(tasks)
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

fn parse_tasks(memo_id: &str, source_path: &str, body: &str) -> Vec<TaskItem> {
    body.lines()
        .enumerate()
        .filter_map(|(index, line)| {
            let Ok(line_index) = u32::try_from(index) else {
                return None;
            };
            let parsed = parse_task_line(line)?;
            Some(TaskItem {
                memo_id: memo_id.to_owned(),
                line_index,
                done: parsed.0,
                text: parsed.1,
                source_path: source_path.to_owned(),
            })
        })
        .collect()
}

fn parse_task_line(line: &str) -> Option<(bool, String)> {
    let trimmed = line.trim_start();
    let (done, tail) = if let Some(tail) = trimmed.strip_prefix("- [ ]") {
        (false, tail)
    } else {
        let tail = trimmed
            .strip_prefix("- [x]")
            .or_else(|| trimmed.strip_prefix("- [X]"))?;
        (true, tail)
    };
    Some((done, tail.trim().to_owned()))
}

fn rewrite_task(body: &str, line_index: u32, done: bool) -> Result<String, LomoError> {
    let target = usize::try_from(line_index)
        .map_err(|error| validation("invalid_task_line", error.to_string()))?;
    let mut out = String::new();
    let mut found = false;
    for (index, line) in body.lines().enumerate() {
        if !out.is_empty() {
            out.push('\n');
        }
        if index == target {
            let (_, text) = parse_task_line(line).ok_or_else(|| {
                validation("task_line_missing", "addressed line is not a task item")
            })?;
            found = true;
            let indent = line.len().saturating_sub(line.trim_start().len());
            let marker = if done { "- [x] " } else { "- [ ] " };
            out.push_str(&" ".repeat(indent));
            out.push_str(marker);
            out.push_str(&text);
        } else {
            out.push_str(line);
        }
    }
    if !found {
        return Err(validation(
            "task_line_missing",
            "task line is outside the memo",
        ));
    }
    if body.ends_with('\n') {
        out.push('\n');
    }
    Ok(out)
}
