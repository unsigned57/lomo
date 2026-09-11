//! Query publication includes the exact durable history object produced by the application.

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::validation;
use crate::{SafProjectionMutation, SafProjectionMutationKind, ScannedHistoryProjection};

/// Private publication counters survive replacement of the disposable query database.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct ProjectionClock {
    pub core_revision: u64,
    pub event_sequence: u64,
}

impl ProjectionClock {
    /// Reserves a checked publication range.
    ///
    /// # Errors
    /// Rejects exhausted revision or event counters.
    pub fn advance(self, count: u64) -> Result<Self, LomoError> {
        Ok(Self {
            core_revision: self
                .core_revision
                .checked_add(count)
                .ok_or_else(|| validation("revision_overflow", "publication revision overflow"))?,
            event_sequence: self.event_sequence.checked_add(count).ok_or_else(|| {
                validation(
                    "event_sequence_overflow",
                    "publication event sequence overflow",
                )
            })?,
        })
    }

    #[must_use]
    pub fn max(self, other: Self) -> Self {
        Self {
            core_revision: self.core_revision.max(other.core_revision),
            event_sequence: self.event_sequence.max(other.event_sequence),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DocumentPublication {
    pub mutation: SafProjectionMutation,
    pub history: Option<ScannedHistoryProjection>,
}

impl DocumentPublication {
    /// Validates the relationship between memo and durable history projection facts.
    ///
    /// # Errors
    /// Rejects missing or mismatched history and history supplied for state-only mutations.
    pub fn validate(&self) -> Result<(), LomoError> {
        let needs_history = matches!(
            self.mutation.kind,
            SafProjectionMutationKind::Create
                | SafProjectionMutationKind::Update
                | SafProjectionMutationKind::HistoryRestore
        );
        if needs_history != self.history.is_some() {
            return Err(validation(
                "publication_history_mismatch",
                "history presence does not match the mutation kind",
            ));
        }
        if let Some(history) = &self.history {
            let projection = self.mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "history publication requires memo projection facts",
                )
            })?;
            if history.memo_id != self.mutation.memo_id
                || history.content != projection.body
                || Some(history.revision) != self.mutation.expected_revision.checked_add(1)
                || history.record_id.is_empty()
                || history.created_at_ms <= 0
            {
                return Err(validation(
                    "publication_history_mismatch",
                    "history does not describe the committed memo revision",
                ));
            }
        }
        Ok(())
    }
}
