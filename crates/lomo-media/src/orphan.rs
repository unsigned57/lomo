//! Media-trash wire format and lifecycle types.
//!
//! The session-owned sweep moves unreferenced committed media into `.lomo-media-trash` under
//! durable names `{digest}_{trashed_at_ms}_{original_name}`, journals a [`MediaDeleteIntent`]
//! before permanent deletion, and keeps the recovery window exclusive after expiry. Only the
//! wire format and record types live here; enumeration, verification, and mutation are performed
//! by the application session through verified platform actions.

use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::validation;
use crate::identity::ContentDigest;

/// Media-trash directory under the workspace root.
pub const MEDIA_TRASH_DIR_NAME: &str = ".lomo-media-trash";

/// Journal directory for permanent-delete intents under the workspace root.
pub const MEDIA_DELETE_INTENT_DIR_NAME: &str = ".lomo-media-delete-intents";

/// Default recovery window: 30 days in milliseconds.
pub const DEFAULT_RECOVERY_WINDOW_MS: u64 = 30 * 24 * 60 * 60 * 1000;

/// Intent recorded before permanent delete.
///
/// Write-only journal evidence: intents are appended for audit, never read back — no
/// `Deserialize` surface exists for this type.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct MediaDeleteIntent {
    pub digest: ContentDigest,
    pub path: PathBuf,
    pub recorded_at_ms: u64,
    pub reason: String,
}

/// A media-trash entry with expiry.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MediaTrashEntry {
    pub digest: ContentDigest,
    pub trash_path: PathBuf,
    pub trashed_at_ms: u64,
    pub expires_at_ms: u64,
}

/// Encodes one media-trash entry basename: `{digest}_{trashed_at_ms}_{original_name}`.
#[must_use]
pub fn trash_entry_name(digest_hex: &str, trashed_at_ms: u64, original_name: &str) -> String {
    format!("{digest_hex}_{trashed_at_ms}_{original_name}")
}

/// Decodes a media-trash basename into `(digest, trashed_at_ms)`.
///
/// # Errors
///
/// Returns validation when the name is not the durable `{digest}_{ms}_{name}` wire format.
pub fn parse_trash_entry_name(name: &str) -> Result<(ContentDigest, u64), LomoError> {
    let mut parts = name.splitn(3, '_');
    let digest_hex = parts.next().ok_or_else(invalid_name)?;
    let trashed_raw = parts.next().ok_or_else(invalid_name)?;
    let original = parts.next().ok_or_else(invalid_name)?;
    if original.is_empty() {
        return Err(invalid_name());
    }
    let digest = ContentDigest::parse(digest_hex).map_err(|_error| invalid_name())?;
    let trashed_at_ms = trashed_raw
        .parse::<u64>()
        .map_err(|_error| invalid_name())?;
    Ok((digest, trashed_at_ms))
}

fn invalid_name() -> LomoError {
    validation(
        "invalid_media_trash_name",
        "media-trash entry is not the durable {digest}_{ms}_{name} format",
    )
}

/// Wall-clock helper for hosts without injected clocks (tests inject `now_ms` explicitly).
#[must_use]
pub fn wall_clock_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
}
