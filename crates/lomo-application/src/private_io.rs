//! Atomic persistence for device-private records and editing baselines.

use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::Path,
};

use lomo_core::LomoError;
use lomo_workspace::SourceFingerprint;

use crate::{
    csprng::generate_hex_token,
    error::{corruption, storage, validation},
};

pub fn read_optional(path: &Path) -> Result<Option<Vec<u8>>, LomoError> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(storage("private_read_failed", error.to_string())),
    }
}

pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let parent = path
        .parent()
        .ok_or_else(|| validation("private_path_invalid", "missing parent"))?;
    fs::create_dir_all(parent)
        .map_err(|error| storage("private_directory_failed", error.to_string()))?;
    let temporary = parent.join(format!(".tmp-{}", generate_hex_token(16)?));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(|error| storage("private_open_failed", error.to_string()))?;
        file.write_all(bytes)
            .map_err(|error| storage("private_write_failed", error.to_string()))?;
        file.sync_all()
            .map_err(|error| storage("private_sync_failed", error.to_string()))?;
        drop(file);
        fs::rename(&temporary, path)
            .map_err(|error| storage("private_rename_failed", error.to_string()))?;
        File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(|error| storage("private_directory_sync_failed", error.to_string()))
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

pub fn remember_baseline(state: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let fingerprint = SourceFingerprint::of_bytes(bytes);
    let path = state.join("baselines").join(fingerprint.as_str());
    match read_optional(&path)? {
        Some(previous) if previous == bytes => Ok(()),
        Some(_) => Err(corruption(
            "baseline_corrupt",
            "private baseline bytes no longer match their SHA-256",
        )),
        None => write_atomic(&path, bytes),
    }
}

pub fn baseline_bytes(state: &Path, digest: &str) -> Result<Option<Vec<u8>>, LomoError> {
    let fingerprint = SourceFingerprint::parse(digest)?;
    let bytes = read_optional(&state.join("baselines").join(fingerprint.as_str()))?;
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
