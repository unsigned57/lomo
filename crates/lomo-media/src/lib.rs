//! Media identity and lifecycle owner (`lomo-media`).
//!
//! Owns content digest/mime/path policy, stage→verify→commit, durable stage-lease records,
//! reference provenance, media-trash wire format, and recording allocate/finalize. Does not
//! depend on store/native/Android.
//!
//! Mutation is session-owned: `lomo-application` runs the two-phase orphan sweep and commits
//! through verified platform actions; this crate keeps deterministic formats and records.

#![deny(unsafe_code)]

mod commit;
mod error;
mod identity;
mod lease;
mod orphan;
mod path;
mod reference;
mod stage;

pub use commit::PromotePlan;
pub use error::{
    conflict as media_conflict, corruption as media_corruption, storage as media_storage,
    validation as media_validation,
};
pub use identity::{
    ContentDigest, DIGEST_STREAM_CHUNK_BYTES, ExtensionClass, MediaMime, classify_extension,
    read_magic_header, write_bytes_for_tests,
};
pub use lease::{
    ArtifactId, STAGE_LEDGER_FILE, StageLease, StageLedger, StageOwnerKind, StageRecord,
    StageRelease, stage_directory, stage_directory_of,
};
pub use orphan::{
    DEFAULT_RECOVERY_WINDOW_MS, MEDIA_DELETE_INTENT_DIR_NAME, MEDIA_TRASH_DIR_NAME,
    MediaDeleteIntent, MediaTrashEntry, parse_trash_entry_name, trash_entry_name, wall_clock_ms,
};
pub use path::{MediaRelativePath, suggest_human_relative_path};
pub use reference::ReferenceSource;
pub use stage::{
    MediaSource, MediaStaged, STAGE_DIR_NAME, allocate_recording_target, discard_staged,
    finalize_recording, resolve_received_final_relative_path, stage_media, stream_buffer_capacity,
};

use lomo_core::{ErrorCategory, LomoError};

/// Ensures structured errors expose a stable category helper for tests.
#[must_use]
pub const fn error_category(error: &LomoError) -> ErrorCategory {
    error.category()
}
