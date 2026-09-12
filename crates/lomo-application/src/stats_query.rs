//! Session-facing statistics: project memo facts then run the pure aggregator.

use lomo_core::LomoError;
use lomo_store::{count_characters, count_words};

use crate::{
    error::validation,
    paging::{collect_summaries, default_query},
    session::WorkspaceSession,
    statistics::{MemoStatistics, StatisticsMemoFact, StatisticsSnapshot, calculate_statistics},
};

impl WorkspaceSession {
    /// Aggregates heatmap and word statistics from the rebuildable projection.
    ///
    /// # Errors
    /// Projection access, calendar, and overflow failures.
    pub fn statistics(&self, snapshot: &StatisticsSnapshot) -> Result<MemoStatistics, LomoError> {
        let mut facts = Vec::new();
        for summary in collect_summaries(self, &default_query())? {
            let body = self
                .with_store(|store| store.get_projected_memo(&summary.memo_id))?
                .ok_or_else(|| validation("memo_not_found", "statistics memo disappeared"))?
                .body;
            let word_count = u64::try_from(count_words(&body).max(0))
                .map_err(|error| validation("statistics_overflow", error.to_string()))?;
            let char_count = u64::try_from(count_characters(&body).max(0))
                .map_err(|error| validation("statistics_overflow", error.to_string()))?;
            facts.push(StatisticsMemoFact::new(
                summary.created_at_ms,
                word_count,
                char_count,
                summary.tags,
            ));
        }
        Ok(calculate_statistics(snapshot, &facts)?)
    }
}
