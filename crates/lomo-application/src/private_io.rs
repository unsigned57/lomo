//! Atomic persistence for device-private records and editing baselines.

use std::{fs, io::Write, path::Path};

use lomo_core::{ErrorCategory, LomoError};
use lomo_workspace::SourceFingerprint;

use crate::{
    csprng::generate_hex_token,
    error::{corruption, storage, validation},
    sysfs::{open_read_nofollow, private_create_options, sync_parent_dir},
};

pub fn read_optional(path: &Path) -> Result<Option<Vec<u8>>, LomoError> {
    if !path.exists() {
        return Ok(None);
    }
    let file = open_read_nofollow(path).map_err(|error| {
        if error.category() == ErrorCategory::Permission {
            error
        } else {
            storage("private_read_failed", error.to_string())
        }
    })?;
    crate::resource::read_bounded(file, crate::resource::MAX_FILE_BYTES).map(Some)
}

pub fn remove_durable(path: &Path) -> Result<(), LomoError> {
    remove_file_synced(path, false)
}

/// Removes a durable record when it exists; an already-absent record is a completed retirement.
pub fn remove_if_present(path: &Path) -> Result<(), LomoError> {
    remove_file_synced(path, true)
}

fn remove_file_synced(path: &Path, allow_missing: bool) -> Result<(), LomoError> {
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if allow_missing && error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(());
        }
        Err(error) => return Err(storage("private_remove_failed", error.to_string())),
    }
    sync_parent_dir(path)
}

pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let parent = path
        .parent()
        .ok_or_else(|| validation("private_path_invalid", "missing parent"))?;
    fs::create_dir_all(parent)
        .map_err(|error| storage("private_directory_failed", error.to_string()))?;
    let temporary = parent.join(format!(".tmp-{}", generate_hex_token(16)?));
    let result = (|| {
        let mut file = private_create_options()
            .open(&temporary)
            .map_err(|error| storage("private_open_failed", error.to_string()))?;
        file.write_all(bytes)
            .map_err(|error| storage("private_write_failed", error.to_string()))?;
        file.sync_all()
            .map_err(|error| storage("private_sync_failed", error.to_string()))?;
        drop(file);
        fs::rename(&temporary, path)
            .map_err(|error| storage("private_rename_failed", error.to_string()))?;
        sync_parent_dir(path)
    })();
    if let Err(original) = result {
        match fs::remove_file(&temporary) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(storage(
                    "private_cleanup_failed",
                    format!("{original}; {error}"),
                ));
            }
        }
        return Err(original);
    }
    Ok(())
}

/// Persists baseline bytes under the session `baselines/` directory
/// ([`lomo_workspace::SessionStateLayout::baselines`]), keyed by content fingerprint.
pub fn remember_baseline(baselines_dir: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let fingerprint = SourceFingerprint::of_bytes(bytes);
    let path = baselines_dir.join(fingerprint.as_str());
    match read_optional(&path)? {
        Some(previous) if previous == bytes => Ok(()),
        Some(_) => Err(corruption(
            "baseline_corrupt",
            "private baseline bytes no longer match their SHA-256",
        )),
        None => write_atomic(&path, bytes),
    }
}

/// Reads baseline bytes under the session `baselines/` directory by fingerprint.
pub fn baseline_bytes(baselines_dir: &Path, digest: &str) -> Result<Option<Vec<u8>>, LomoError> {
    let fingerprint = SourceFingerprint::parse(digest)?;
    let bytes = read_optional(&baselines_dir.join(fingerprint.as_str()))?;
    if bytes
        .as_ref()
        .is_some_and(|bytes| SourceFingerprint::of_bytes(bytes) != fingerprint)
    {
        return Err(corruption(
            "baseline_corrupt",
            "saved original bytes fail their SHA-256 checksum",
        ));
    }
    Ok(bytes)
}

/// Evicts baseline blobs whose fingerprint is not in `keep` — the invalidation criterion is
/// supplied by the caller (retained draft evidence ∪ currently committed document
/// fingerprints). Filenames that are not valid fingerprints are left alone: a foreign file is
/// not evidence the CAS wrote, so eviction never reaches for it.
///
/// # Errors
/// Propagates directory listing or removal failures; a mid-pass error leaves already-removed
/// blobs removed and reports the failure.
pub fn evict_unreferenced_baselines(
    baselines_dir: &Path,
    keep: &std::collections::BTreeSet<String>,
) -> Result<u64, LomoError> {
    if !baselines_dir.exists() {
        return Ok(0);
    }
    let mut evicted = 0u64;
    let mut last_removed = None;
    let entries = fs::read_dir(baselines_dir).map_err(|error| {
        storage(
            "baseline_dir_unavailable",
            format!("failed to list baselines directory: {error}"),
        )
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| {
            storage(
                "baseline_dir_unavailable",
                format!("failed to read baselines directory entry: {error}"),
            )
        })?;
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        // A malformed filename cannot collide with a fingerprint key; it is foreign matter.
        if SourceFingerprint::parse(name).is_err() || keep.contains(name) {
            continue;
        }
        fs::remove_file(&path)
            .map_err(|error| storage("private_remove_failed", error.to_string()))?;
        evicted += 1;
        last_removed = Some(path);
    }
    // One directory sync durably records the whole pass: a per-file fsync would
    // multiply the same journal flush by the blob count, and eviction is
    // idempotent — a crash before the sync simply re-evicts survivors next open.
    if let Some(path) = last_removed {
        sync_parent_dir(&path)?;
    }
    Ok(evicted)
}
