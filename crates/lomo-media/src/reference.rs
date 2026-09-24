//! Attachment reference provenance for the workspace protection set.

use serde::{Deserialize, Serialize};

/// Where an attachment reference was observed.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub enum ReferenceSource {
    /// A live memo body names the attachment.
    CurrentMemo,
    /// A trashed memo body still names the attachment.
    TrashMemo,
    /// An in-window durable history revision names the attachment.
    HistoryVersion,
    /// A conflict-evidence draft body names the attachment.
    Draft,
    /// A frozen, not-yet-committed transaction names or writes the attachment.
    PendingOperation,
    /// A durable stage-ledger lease claims the staged artifact or its final path.
    StageLease,
}
