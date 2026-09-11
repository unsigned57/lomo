//! Daily review candidates are timezone-sliced and exclude locally completed notes.

use std::collections::BTreeSet;
use std::path::PathBuf;

use lomo_core::LomoError;
use lomo_store::{MemoFilters, MemoQuery, MemoSummary};
use lomo_workspace::MemoId;
use serde::{Deserialize, Serialize};

use crate::calendar::{CivilDate, DateFormat, day_bounds, format_date_key};
use crate::error::{storage, validation};
use crate::paging::collect_summaries;
use crate::private_io::{read_optional, write_atomic};
use crate::session::WorkspaceSession;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ReviewCandidate {
    pub memo_id: String,
    pub created_at_ms: i64,
    pub body_preview: String,
    pub source_path: String,
}

impl WorkspaceSession {
    /// Returns memos whose chronology falls on `date` in `zone`, minus completed review IDs.
    ///
    /// # Errors
    /// Invalid zones/dates and projection failures.
    pub fn review_candidates(
        &self,
        zone: &str,
        date: CivilDate,
    ) -> Result<Vec<ReviewCandidate>, LomoError> {
        let (from, until) = day_bounds(date, zone)?;
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                date_from_inclusive_ms: Some(from),
                date_until_exclusive_ms: Some(until),
                ..MemoFilters::default()
            },
            sort: lomo_store::MemoSort::default(),
        };
        let completed = load_completed(&self.config.state_dir, zone, date)?;
        let mut out = Vec::new();
        for summary in collect_summaries(self, &query)? {
            if completed.contains(&summary.memo_id) {
                continue;
            }
            out.push(candidate(summary));
        }
        Ok(out)
    }

    /// Records that a memo was reviewed on the local civil date. Device-private; not synced.
    ///
    /// # Errors
    /// Private-state I/O failures.
    pub fn complete_review(
        &self,
        zone: &str,
        date: CivilDate,
        memo_id: &MemoId,
    ) -> Result<(), LomoError> {
        let mut completed = load_completed(&self.config.state_dir, zone, date)?;
        completed.insert(memo_id.as_str().to_owned());
        save_completed(&self.config.state_dir, zone, date, &completed)
    }
}

fn candidate(summary: MemoSummary) -> ReviewCandidate {
    ReviewCandidate {
        memo_id: summary.memo_id,
        created_at_ms: summary.created_at_ms,
        body_preview: summary.body_preview,
        source_path: summary.source_path,
    }
}

fn review_path(state_dir: &std::path::Path, zone: &str, date: CivilDate) -> PathBuf {
    let zone_key = zone.replace('/', "@");
    state_dir.join("review").join(zone_key).join(format!(
        "{}.json",
        format_date_key(date, DateFormat::YyyyMmDdUnderscore)
    ))
}

fn load_completed(
    state_dir: &std::path::Path,
    zone: &str,
    date: CivilDate,
) -> Result<BTreeSet<String>, LomoError> {
    let Some(bytes) = read_optional(&review_path(state_dir, zone, date))? else {
        return Ok(BTreeSet::new());
    };
    serde_json::from_slice(&bytes).map_err(|error| {
        validation(
            "invalid_review_state",
            format!("review completion record is corrupt: {error}"),
        )
    })
}

fn save_completed(
    state_dir: &std::path::Path,
    zone: &str,
    date: CivilDate,
    completed: &BTreeSet<String>,
) -> Result<(), LomoError> {
    let bytes = serde_json::to_vec(completed)
        .map_err(|error| storage("review_encode_failed", error.to_string()))?;
    write_atomic(&review_path(state_dir, zone, date), &bytes)
}
