//! Daily review candidates are timezone-sliced and exclude locally completed notes.

use std::{collections::BTreeSet, path::PathBuf};

use lomo_core::LomoError;
use lomo_store::{MemoFilters, MemoQuery, MemoSummary};
use lomo_workspace::MemoId;

use crate::{
    calendar::{CivilDate, DateFormat, day_bounds, format_date_key},
    error::{storage, validation},
    paging::{NEVER_CANCELLED, collect_summaries},
    private_io::{read_optional, write_atomic},
    session::WorkspaceSession,
};

impl WorkspaceSession {
    /// Returns the day-sliced memo summaries in `zone`, minus completed review IDs.
    ///
    /// Callers get the projection summary itself — preview, identity and path —
    /// so a review surface renders without hydrating bodies. The completed set
    /// is device-private state outside the projection: it is re-loaded and
    /// re-filtered on every call even when the shared walk is served from the
    /// revision-keyed cache, so completion can never freeze into a snapshot.
    ///
    /// # Errors
    /// Invalid zones/dates and projection failures.
    pub fn review_candidates(
        &self,
        zone: &str,
        date: CivilDate,
    ) -> Result<Vec<MemoSummary>, LomoError> {
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
        let walk = collect_summaries(self, &query, NEVER_CANCELLED)?;
        let mut out = Vec::new();
        for summary in walk.items.iter() {
            if completed.contains(&summary.memo_id) {
                continue;
            }
            out.push(summary.clone());
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
