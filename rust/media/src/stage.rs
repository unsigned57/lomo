//! Stage → verify lifecycle. Paths only — no full media byte APIs.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::{storage, validation};
use crate::identity::{ContentDigest, DIGEST_STREAM_CHUNK_BYTES, MediaMime, read_magic_header};
use crate::path::suggest_human_relative_path;

/// Directory name for pending staged media under a media root.
pub const STAGE_DIR_NAME: &str = ".lomo-media-stage";

/// Source of media bytes for staging (always a filesystem path).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MediaSource {
    /// Rust opens and streams the path directly.
    DirectPath { path: PathBuf },
    /// Kotlin/SAF already copied into a private temp path owned by Rust staging cleanup.
    StagedTemp { path: PathBuf },
}

/// Result of stage+verify before memo promote.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MediaStaged {
    pub digest: ContentDigest,
    pub size: u64,
    pub mime: MediaMime,
    pub staging_path: PathBuf,
    /// Human filename stem hint (not identity); may be empty.
    pub human_name_hint: String,
    /// Owner-suggested final workspace-relative path (`media/...`) for promote.
    /// Hosts must not invent basenames from digest prefixes; they promote this path or
    /// another path that still passes [`crate::path::MediaRelativePath`] validation.
    pub suggested_final_relative_path: String,
}

/// Stages media by streaming digest + magic verify into the stage directory.
///
/// # Errors
///
/// Returns storage/validation when open, magic, or copy fails.
pub fn stage_media(
    media_root: &Path,
    source: MediaSource,
    human_name_hint: &str,
) -> Result<MediaStaged, LomoError> {
    let source_path = match &source {
        MediaSource::DirectPath { path } | MediaSource::StagedTemp { path } => path.clone(),
    };
    if !source_path.is_file() {
        return Err(validation(
            "media_source_not_file",
            "media source path must be an existing regular file",
        ));
    }

    let header = read_magic_header(&source_path)?;
    let ext_hint = source_path
        .extension()
        .and_then(|ext| ext.to_str())
        .or_else(|| {
            Path::new(human_name_hint)
                .extension()
                .and_then(|ext| ext.to_str())
        });
    let mime = MediaMime::detect(&header, ext_hint)?;
    let (digest, size) = ContentDigest::stream_from_path(&source_path)?;

    let stage_dir = media_root.join(STAGE_DIR_NAME);
    fs::create_dir_all(&stage_dir).map_err(|error| {
        storage(
            "media_stage_dir_create_failed",
            &format!("failed to create stage dir: {error}"),
        )
    })?;

    let staging_name = format!("{}.{}", digest.as_str(), mime.preferred_extension());
    let staging_path = stage_dir.join(staging_name);

    // If same digest already staged, reuse path (dedup pending).
    if staging_path.exists() {
        if matches!(source, MediaSource::StagedTemp { .. }) && source_path.exists() {
            // StagedTemp ownership: consume the temp path so hosts cannot double-stage it.
            fs::remove_file(&source_path).map_err(|error| {
                storage(
                    "media_stage_temp_consume_failed",
                    &format!("failed to consume StagedTemp after digest-dedup stage: {error}"),
                )
            })?;
        }
        return build_staged(digest, size, mime, staging_path, human_name_hint);
    }

    match source {
        MediaSource::DirectPath { path } => {
            stream_copy(&path, &staging_path)?;
        }
        MediaSource::StagedTemp { path } => {
            // Move when possible; fall back to copy+remove.
            if fs::rename(&path, &staging_path).is_err() {
                stream_copy(&path, &staging_path)?;
                if path.exists() {
                    fs::remove_file(&path).map_err(|error| {
                        storage(
                            "media_stage_temp_consume_failed",
                            &format!("failed to consume StagedTemp after stage copy: {error}"),
                        )
                    })?;
                }
            }
        }
    }

    // Re-verify digest of staged file.
    let (staged_digest, staged_size) = ContentDigest::stream_from_path(&staging_path)?;
    if staged_digest != digest || staged_size != size {
        if staging_path.exists() {
            // behavior-contract: silent-result-ok: best-effort cleanup of corrupt stage before
            // returning the authoritative digest-mismatch error; a leftover corrupt stage is
            // still unpromoted and discarded by recovery, so cleanup failure must not mask the
            // mismatch.
            drop(fs::remove_file(&staging_path));
        }
        return Err(corruption_mismatch());
    }

    build_staged(digest, size, mime, staging_path, human_name_hint)
}

/// Resolves the stable final path for received media without overwriting different bytes.
///
/// The human suggested path is reused when absent or when it already contains the same digest and
/// size. A different occupant produces `_1`, `_2`, ... before the extension. This remains stable
/// across operation recovery because a path promoted by the first attempt is recognized by digest.
///
/// # Errors
///
/// Storage when an occupied candidate cannot be inspected; validation when the staged suggestion
/// cannot be represented as a canonical suffixed media path.
pub fn resolve_received_final_relative_path(
    workspace_root: &Path,
    staged: &MediaStaged,
) -> Result<crate::path::MediaRelativePath, LomoError> {
    let suggested = crate::path::MediaRelativePath::parse(&staged.suggested_final_relative_path)?;
    if candidate_is_available_or_same(workspace_root, &suggested, staged)? {
        return Ok(suggested);
    }

    let path = Path::new(&staged.suggested_final_relative_path);
    let parent = path.parent().ok_or_else(|| {
        validation(
            "media_received_path_invalid",
            "received media suggestion has no relative parent",
        )
    })?;
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .ok_or_else(|| {
            validation(
                "media_received_path_invalid",
                "received media suggestion has no UTF-8 filename stem",
            )
        })?;
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .ok_or_else(|| {
            validation(
                "media_received_path_invalid",
                "received media suggestion has no UTF-8 extension",
            )
        })?;
    for suffix in 1_u32..=u32::MAX {
        let raw = parent
            .join(format!("{stem}_{suffix}.{extension}"))
            .to_str()
            .ok_or_else(|| {
                validation(
                    "media_received_path_invalid",
                    "received media destination is not valid UTF-8",
                )
            })?
            .to_owned();
        let candidate = crate::path::MediaRelativePath::parse(&raw)?;
        if candidate_is_available_or_same(workspace_root, &candidate, staged)? {
            return Ok(candidate);
        }
    }
    Err(validation(
        "media_received_path_exhausted",
        "received media destination suffix range is exhausted",
    ))
}

fn candidate_is_available_or_same(
    workspace_root: &Path,
    candidate: &crate::path::MediaRelativePath,
    staged: &MediaStaged,
) -> Result<bool, LomoError> {
    let absolute = workspace_root.join(candidate.as_str());
    if !absolute.exists() {
        return Ok(true);
    }
    if !absolute.is_file() {
        return Ok(false);
    }
    let (digest, size) = ContentDigest::stream_from_path(&absolute)?;
    Ok(digest == staged.digest && size == staged.size)
}

fn build_staged(
    digest: ContentDigest,
    size: u64,
    mime: MediaMime,
    staging_path: PathBuf,
    human_name_hint: &str,
) -> Result<MediaStaged, LomoError> {
    let stem = Path::new(human_name_hint)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or(human_name_hint);
    let suggested = suggest_human_relative_path(stem, mime)?;
    Ok(MediaStaged {
        digest,
        size,
        mime,
        staging_path,
        human_name_hint: human_name_hint.to_owned(),
        suggested_final_relative_path: suggested.as_str().to_owned(),
    })
}

fn corruption_mismatch() -> LomoError {
    crate::error::corruption(
        "media_stage_digest_mismatch",
        "staged media digest does not match source stream",
    )
}

fn stream_copy(from: &Path, to: &Path) -> Result<(), LomoError> {
    let mut input = fs::File::open(from).map_err(|error| {
        storage(
            "media_stage_open_failed",
            &format!("failed to open source for stage copy: {error}"),
        )
    })?;
    let mut output = fs::File::create(to).map_err(|error| {
        storage(
            "media_stage_create_failed",
            &format!("failed to create staged file: {error}"),
        )
    })?;
    let mut buffer = [0_u8; DIGEST_STREAM_CHUNK_BYTES];
    loop {
        use std::io::{Read, Write};
        let read = input.read(&mut buffer).map_err(|error| {
            storage(
                "media_stage_copy_read_failed",
                &format!("stage copy read failed: {error}"),
            )
        })?;
        if read == 0 {
            break;
        }
        let Some(chunk) = buffer.get(..read) else {
            return Err(validation(
                "media_stage_copy_chunk_out_of_bounds",
                "stage copy chunk bounds violated",
            ));
        };
        output.write_all(chunk).map_err(|error| {
            storage(
                "media_stage_copy_write_failed",
                &format!("stage copy write failed: {error}"),
            )
        })?;
    }
    Ok(())
}

/// Drops a staged file (draft discard / failed promote).
///
/// # Errors
///
/// Returns storage when delete fails for an existing path.
pub fn discard_staged(staged: &MediaStaged) -> Result<(), LomoError> {
    if staged.staging_path.exists() {
        fs::remove_file(&staged.staging_path).map_err(|error| {
            storage(
                "media_stage_discard_failed",
                &format!("failed to discard staged media: {error}"),
            )
        })?;
    }
    Ok(())
}

/// Allocates a recording target path under the stage directory (Kotlin recorder writes here).
///
/// # Errors
///
/// Returns storage when the stage directory cannot be created.
pub fn allocate_recording_target(media_root: &Path, extension: &str) -> Result<PathBuf, LomoError> {
    let ext = extension.trim_start_matches('.').to_ascii_lowercase();
    if ext.is_empty()
        || ext.contains('/')
        || ext.contains('\\')
        || ext.contains('\0')
        || ext.len() > 16
    {
        return Err(validation(
            "invalid_recording_extension",
            "recording extension must be a short safe token",
        ));
    }
    let stage_dir = media_root.join(STAGE_DIR_NAME);
    fs::create_dir_all(&stage_dir).map_err(|error| {
        storage(
            "media_stage_dir_create_failed",
            &format!("failed to create stage dir for recording: {error}"),
        )
    })?;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    let name = format!("recording-{nanos}.{ext}");
    Ok(stage_dir.join(name))
}

/// Finalizes a recording path into [`MediaStaged`] via the same verify path as import.
///
/// # Errors
///
/// Returns validation/storage when the recording path is missing or invalid media.
pub fn finalize_recording(
    media_root: &Path,
    recording_path: &Path,
    human_name_hint: &str,
) -> Result<MediaStaged, LomoError> {
    stage_media(
        media_root,
        MediaSource::StagedTemp {
            path: recording_path.to_path_buf(),
        },
        human_name_hint,
    )
}

/// Peak buffer size used by streaming digest/copy (for memory-bound tests).
#[must_use]
pub const fn stream_buffer_capacity() -> usize {
    DIGEST_STREAM_CHUNK_BYTES
}
