//! Session-facing statistics: project materialized fact rows then run the pure aggregator.

use lomo_core::LomoError;

use crate::{
    error::validation,
    session::WorkspaceSession,
    statistics::{MemoStatistics, StatisticsMemoFact, StatisticsSnapshot, calculate_statistics},
};

impl WorkspaceSession {
    /// Aggregates heatmap and word statistics from the rebuildable projection.
    ///
    /// Reads the store's materialized statistics rows in one snapshot: word/character counts and
    /// tag facts are maintained at projection time, so no memo body is ever loaded here.
    ///
    /// # Errors
    /// Projection access, calendar, and overflow failures.
    pub fn statistics(&self, snapshot: &StatisticsSnapshot) -> Result<MemoStatistics, LomoError> {
        let rows = self.with_reader(lomo_store::StoreReader::memo_statistics_rows)?;
        let mut facts = Vec::with_capacity(rows.len());
        for row in rows {
            facts.push(StatisticsMemoFact::new(
                row.created_at_ms,
                u64::try_from(row.word_count)
                    .map_err(|error| validation("statistics_overflow", error.to_string()))?,
                u64::try_from(row.char_count)
                    .map_err(|error| validation("statistics_overflow", error.to_string()))?,
                row.tags,
            ));
        }
        Ok(calculate_statistics(snapshot, &facts)?)
    }
}
