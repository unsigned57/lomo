//! Media identity and lifecycle owner for stage-4 dark-build (`lomo-media`).
//!
//! Owns content digest/mime/path policy, stage→verify→commit, reference/orphan models,
//! media-trash, and recording allocate/finalize. Does not depend on store/native/Android.
//!
//! Production dual-stack with Kotlin media repositories is forbidden after Wave A cutover;
//! dark-build until atomic P4-10A.

#![deny(unsafe_code)]

mod commit;
mod error;
mod identity;
mod orphan;
mod path;
mod reference;
mod stage;

pub use commit::{PromoteCrashPoint, PromotePlan, PromoteResult, promote_staged};
pub use error::{
    conflict as media_conflict, corruption as media_corruption, storage as media_storage,
    validation as media_validation,
};
pub use identity::{
    ContentDigest, DIGEST_STREAM_CHUNK_BYTES, MediaMime, read_magic_header, write_bytes_for_tests,
};
pub use orphan::{
    DEFAULT_RECOVERY_WINDOW_MS, MEDIA_DELETE_INTENT_DIR_NAME, MEDIA_TRASH_DIR_NAME,
    MediaDeleteIntent, MediaTrashEntry, OrphanSweepResult, list_trash_entries, restore_from_trash,
    sweep_orphans, wall_clock_ms,
};
pub use path::{MediaRelativePath, suggest_human_relative_path};
pub use reference::{AttachmentRef, DigestRefcount, ReferenceSource, build_refcounts};
pub use stage::{
    MediaSource, MediaStaged, STAGE_DIR_NAME, allocate_recording_target, discard_staged,
    finalize_recording, resolve_received_final_relative_path, stage_media, stream_buffer_capacity,
};

use lomo_core::{ErrorCategory, LomoError};

/// Crate package identity for architecture ownership locks.
pub const MEDIA_CRATE_NAME: &str = "lomo-media";

/// Owner identity document for stage-4 ownership locks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MediaOwnerIdentity {
    /// Package name of the media owner crate.
    pub crate_name: &'static str,
}

impl MediaOwnerIdentity {
    /// Returns the current owner identity constants.
    #[must_use]
    pub const fn current() -> Self {
        Self {
            crate_name: MEDIA_CRATE_NAME,
        }
    }

    /// Validates crate identity.
    ///
    /// # Errors
    ///
    /// Returns validation when the crate name is not `lomo-media`.
    pub fn validate(self) -> Result<(), LomoError> {
        if self.crate_name != MEDIA_CRATE_NAME {
            return Err(error::validation(
                "invalid_media_owner",
                "media owner crate name must be lomo-media",
            ));
        }
        Ok(())
    }
}

/// Ensures structured errors expose a stable category helper for tests.
#[must_use]
pub const fn error_category(error: &LomoError) -> ErrorCategory {
    error.category()
}
