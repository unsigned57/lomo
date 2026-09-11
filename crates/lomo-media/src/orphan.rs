//! Deterministic orphan sweep + media-trash lifecycle.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::{storage, validation};
use crate::identity::ContentDigest;
use crate::reference::{AttachmentRef, build_refcounts};

/// Media-trash directory under media root.
pub const MEDIA_TRASH_DIR_NAME: &str = ".lomo-media-trash";

/// Default recovery window: 30 days in milliseconds.
pub const DEFAULT_RECOVERY_WINDOW_MS: u64 = 30 * 24 * 60 * 60 * 1000;

/// Intent recorded before permanent delete.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
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

/// Result of one deterministic sweep.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct OrphanSweepResult {
    pub moved_to_trash: Vec<MediaTrashEntry>,
    pub permanently_deleted: Vec<MediaDeleteIntent>,
    pub kept_live: u64,
}

/// Loads media-trash entries from the on-disk trash directory.
///
/// Trash basenames are `{digest}_{trashed_at_ms}_{original_name}` written by [`sweep_orphans`].
/// Expiry is `trashed_at + recovery_window` so permanent delete remains durable across restarts
/// without a host-side trash index.
///
/// # Errors
///
/// Returns storage when the trash directory cannot be listed.
pub fn list_trash_entries(
    media_root: &Path,
    recovery_window_ms: u64,
    _now_ms: u64,
) -> Result<Vec<MediaTrashEntry>, LomoError> {
    let trash_dir = media_root.join(MEDIA_TRASH_DIR_NAME);
    if !trash_dir.is_dir() {
        return Ok(Vec::new());
    }
    let read = fs::read_dir(&trash_dir).map_err(|error| {
        storage(
            "media_trash_list_failed",
            &format!("failed to list media-trash dir: {error}"),
        )
    })?;
    let mut out = Vec::new();
    for entry in read {
        let entry = entry.map_err(|error| {
            storage(
                "media_trash_list_entry_failed",
                &format!("failed to read media-trash entry: {error}"),
            )
        })?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        // Durable wire: `{digest}_{trashed_at_ms}_{original_name}`.
        let mut parts = name.splitn(3, '_');
        let Some(digest_hex) = parts.next() else {
            continue;
        };
        let Some(trashed_raw) = parts.next() else {
            continue;
        };
        let Some(_original) = parts.next() else {
            continue;
        };
        let Ok(digest) = ContentDigest::parse(digest_hex) else {
            continue;
        };
        let Ok(trashed_at_ms) = trashed_raw.parse::<u64>() else {
            continue;
        };
        out.push(MediaTrashEntry {
            digest,
            trash_path: path,
            trashed_at_ms,
            expires_at_ms: trashed_at_ms.saturating_add(recovery_window_ms),
        });
    }
    Ok(out)
}

/// Moves unreferenced committed media into media-trash; purges expired trash.
///
/// `committed` maps digest → absolute path of committed files.
/// `now_ms` is the sweep clock; recovery window is exclusive after expiry.
/// When `existing_trash` is empty, on-disk media-trash is auto-listed so permanent
/// delete still runs across process restarts without a host-side trash index.
///
/// # Errors
///
/// Returns storage when filesystem moves/deletes fail.
pub fn sweep_orphans(
    media_root: &Path,
    committed: &BTreeMap<ContentDigest, PathBuf>,
    refs: &[AttachmentRef],
    existing_trash: &[MediaTrashEntry],
    now_ms: u64,
    recovery_window_ms: u64,
) -> Result<OrphanSweepResult, LomoError> {
    let refcounts = build_refcounts(refs);
    let trash_dir = media_root.join(MEDIA_TRASH_DIR_NAME);
    fs::create_dir_all(&trash_dir).map_err(|error| {
        storage(
            "media_trash_dir_create_failed",
            &format!("failed to create media-trash dir: {error}"),
        )
    })?;

    let loaded_trash;
    let trash_slice: &[MediaTrashEntry] = if existing_trash.is_empty() {
        loaded_trash = list_trash_entries(media_root, recovery_window_ms, now_ms)?;
        &loaded_trash
    } else {
        existing_trash
    };

    let mut result = OrphanSweepResult::default();
    let trash_digests: BTreeMap<_, _> = trash_slice
        .iter()
        .map(|entry| (entry.digest.clone(), entry.clone()))
        .collect();

    for (digest, path) in committed {
        if refcounts
            .get(digest)
            .is_some_and(super::reference::DigestRefcount::is_live)
        {
            result.kept_live = result.kept_live.saturating_add(1);
            continue;
        }
        if trash_digests.contains_key(digest) {
            continue;
        }
        if !path.exists() {
            continue;
        }
        let file_name = path
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or_else(|| {
                validation(
                    "invalid_media_trash_name",
                    "committed media path has no UTF-8 file name",
                )
            })?;
        let trash_path = trash_dir.join(format!("{}_{now_ms}_{file_name}", digest.as_str()));
        fs::rename(path, &trash_path).map_err(|error| {
            storage(
                "media_trash_move_failed",
                &format!("failed to move orphan to media-trash: {error}"),
            )
        })?;
        result.moved_to_trash.push(MediaTrashEntry {
            digest: digest.clone(),
            trash_path,
            trashed_at_ms: now_ms,
            expires_at_ms: now_ms.saturating_add(recovery_window_ms),
        });
    }

    for entry in trash_slice {
        if entry.expires_at_ms > now_ms {
            continue;
        }
        if entry.trash_path.exists() {
            let intent = MediaDeleteIntent {
                digest: entry.digest.clone(),
                path: entry.trash_path.clone(),
                recorded_at_ms: now_ms,
                reason: "recovery_window_elapsed".to_owned(),
            };
            // Durable delete-intent before permanent delete (fail closed if journal write fails).
            persist_delete_intent(media_root, &intent)?;
            fs::remove_file(&entry.trash_path).map_err(|error| {
                storage(
                    "media_trash_permanent_delete_failed",
                    &format!("failed permanent media-trash delete: {error}"),
                )
            })?;
            result.permanently_deleted.push(intent);
        }
    }

    Ok(result)
}

/// Journal directory for permanent-delete intents under media root.
pub const MEDIA_DELETE_INTENT_DIR_NAME: &str = ".lomo-media-delete-intents";

fn persist_delete_intent(media_root: &Path, intent: &MediaDeleteIntent) -> Result<(), LomoError> {
    let dir = media_root.join(MEDIA_DELETE_INTENT_DIR_NAME);
    fs::create_dir_all(&dir).map_err(|error| {
        storage(
            "media_delete_intent_dir_failed",
            &format!("failed to create media delete-intent dir: {error}"),
        )
    })?;
    let name = format!("{}_{}.json", intent.recorded_at_ms, intent.digest.as_str());
    let path = dir.join(name);
    let body = serde_json::to_vec(intent).map_err(|error| {
        validation(
            "media_delete_intent_encode_failed",
            &format!("cannot encode media delete intent: {error}"),
        )
    })?;
    fs::write(&path, body).map_err(|error| {
        storage(
            "media_delete_intent_write_failed",
            &format!("failed to journal media delete intent before permanent delete: {error}"),
        )
    })?;
    Ok(())
}

/// Restores a media-trash entry back to `dest` when still inside the window.
///
/// # Errors
///
/// Returns validation when expired or missing; storage on rename failure.
pub fn restore_from_trash(
    entry: &MediaTrashEntry,
    dest: &Path,
    now_ms: u64,
) -> Result<(), LomoError> {
    if entry.expires_at_ms <= now_ms {
        return Err(validation(
            "media_trash_expired",
            "media-trash recovery window has elapsed",
        ));
    }
    if !entry.trash_path.exists() {
        return Err(validation(
            "media_trash_missing",
            "media-trash entry file is missing",
        ));
    }
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "media_restore_dir_failed",
                &format!("failed to create restore destination dir: {error}"),
            )
        })?;
    }
    fs::rename(&entry.trash_path, dest).map_err(|error| {
        storage(
            "media_trash_restore_failed",
            &format!("failed to restore media from trash: {error}"),
        )
    })?;
    Ok(())
}

/// Wall-clock helper for hosts without injected clocks (tests inject `now_ms` explicitly).
#[must_use]
pub fn wall_clock_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
}
