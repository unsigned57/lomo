//! Stage → verify lifecycle. Path sources plus the bounded in-memory `Bytes`
//! lane — never a byte-loading API: the byte budget is the producing
//! boundary's (clipboard), and staging never materializes a temp source file
//! for a byte payload it already holds.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::{storage, validation};
use crate::identity::{ContentDigest, DIGEST_STREAM_CHUNK_BYTES, MediaMime, read_magic_header};
use crate::lease::{ArtifactId, StageLedger, stage_directory_of};
use crate::path::suggest_human_relative_path;

/// Directory name for pending staged media under a media root.
pub const STAGE_DIR_NAME: &str = ".lomo-media-stage";

/// Source of media bytes for staging.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MediaSource {
    /// Rust opens and streams the path directly.
    DirectPath { path: PathBuf },
    /// Kotlin/SAF already copied into a private temp path owned by Rust staging cleanup.
    StagedTemp { path: PathBuf },
    /// Bytes the host already holds (e.g. a clipboard payload). The byte
    /// budget lives at the producing boundary — this is a source shape, not
    /// a byte-loading API, and staging never lands a temp source file for it.
    Bytes { bytes: Vec<u8> },
}

impl MediaSource {
    /// The source's own extension witness for MIME disambiguation — a path's
    /// extension; a byte source carries none, so the human hint rules there.
    fn extension_hint(&self) -> Option<&str> {
        match self {
            Self::DirectPath { path } | Self::StagedTemp { path } => {
                path.extension().and_then(|ext| ext.to_str())
            }
            Self::Bytes { .. } => None,
        }
    }

    /// The magic-detection header — path sources stream the first bytes off
    /// disk; a byte source slices what it already holds.
    fn read_header(&self) -> Result<Vec<u8>, LomoError> {
        match self {
            Self::DirectPath { path } | Self::StagedTemp { path } => {
                if !path.is_file() {
                    return Err(validation(
                        "media_source_not_file",
                        "media source path must be an existing regular file",
                    ));
                }
                read_magic_header(path)
            }
            Self::Bytes { bytes } => Ok(bytes.get(..bytes.len().min(64)).unwrap_or(&[]).to_vec()),
        }
    }

    /// Content digest + size — paths stream the digest; a byte source hashes
    /// the slice it already holds.
    fn measure(&self) -> Result<(ContentDigest, u64), LomoError> {
        match self {
            Self::DirectPath { path } | Self::StagedTemp { path } => {
                ContentDigest::stream_from_path(path)
            }
            Self::Bytes { bytes } => Ok((
                ContentDigest::of_slice(bytes),
                u64::try_from(bytes.len()).unwrap_or(u64::MAX),
            )),
        }
    }

    /// Consumes an owned temp source once staging adopted its bytes —
    /// `StagedTemp` deletes so hosts cannot double-stage it; the other
    /// sources own nothing on disk to consume.
    fn consume(&self) -> Result<(), LomoError> {
        let Self::StagedTemp { path } = self else {
            return Ok(());
        };
        if path.exists() {
            fs::remove_file(path).map_err(|error| {
                storage(
                    "media_stage_temp_consume_failed",
                    &format!("failed to consume StagedTemp after digest-dedup stage: {error}"),
                )
            })?;
        }
        Ok(())
    }

    /// Lands the payload at `temp_path` inside the stage dir, consuming the
    /// source: a bounded stream for `DirectPath`, move-or-copy-then-consume
    /// for `StagedTemp`, one write of the held bytes for `Bytes`.
    fn land(self, temp_path: &Path) -> Result<(), LomoError> {
        match self {
            Self::DirectPath { path } => stream_copy(&path, temp_path),
            Self::StagedTemp { path } => {
                // Move when possible; fall back to copy+remove.
                if fs::rename(&path, temp_path).is_err() {
                    stream_copy(&path, temp_path)?;
                    if path.exists() {
                        fs::remove_file(&path).map_err(|error| {
                            storage(
                                "media_stage_temp_consume_failed",
                                &format!("failed to consume StagedTemp after stage copy: {error}"),
                            )
                        })?;
                    }
                }
                Ok(())
            }
            Self::Bytes { bytes } => fs::write(temp_path, bytes).map_err(|error| {
                storage(
                    "media_stage_write_failed",
                    &format!("failed to write staged bytes: {error}"),
                )
            }),
        }
    }
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

/// Stages media by digest + magic verify into the stage directory — path
/// sources stream; the `Bytes` lane stages the payload it already holds.
///
/// # Errors
///
/// Returns storage/validation when open, magic, or copy fails.
pub fn stage_media(
    media_root: &Path,
    source: MediaSource,
    human_name_hint: &str,
) -> Result<MediaStaged, LomoError> {
    let ext_hint = source.extension_hint().or_else(|| {
        Path::new(human_name_hint)
            .extension()
            .and_then(|ext| ext.to_str())
    });
    let header = source.read_header()?;
    let mime = MediaMime::detect(&header, ext_hint)?;
    let (digest, size) = source.measure()?;

    let stage_dir = media_root.join(STAGE_DIR_NAME);
    fs::create_dir_all(&stage_dir).map_err(|error| {
        storage(
            "media_stage_dir_create_failed",
            &format!("failed to create stage dir: {error}"),
        )
    })?;

    let staging_name = format!("{}.{}", digest.as_str(), mime.preferred_extension());
    let staging_path = stage_dir.join(staging_name);

    // The digest-derived name is only a claim about content: a torn file left by a crashed
    // or partial stage must prove its bytes before dedup may adopt it under this digest.
    if staging_path.exists() {
        let (existing_digest, existing_size) = ContentDigest::stream_from_path(&staging_path)?;
        if existing_digest == digest && existing_size == size {
            // Digest-dedup: the staged bytes are adopted and the source's own
            // ownership tail runs — `StagedTemp` is consumed so hosts cannot
            // double-stage it.
            source.consume()?;
            return build_staged(digest, size, mime, staging_path, human_name_hint);
        }
        // Replace the mismatched occupant: bytes under a digest-derived name must match the
        // declared digest, so torn content is removed before the valid bytes are staged.
        fs::remove_file(&staging_path).map_err(|error| {
            storage(
                "media_stage_torn_replace_failed",
                &format!("failed to replace mismatched staged bytes: {error}"),
            )
        })?;
    }

    // Stage through a unique temp name so the digest-derived path never exposes partially
    // copied bytes; committing the verified temp file is an atomic rename inside stage_dir.
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |span| span.as_nanos());
    let temp_path = stage_dir.join(format!(
        "{}.{}.stage-{}-{nanos}",
        digest.as_str(),
        mime.preferred_extension(),
        std::process::id()
    ));

    if let Err(error) = source.land(&temp_path) {
        drop(fs::remove_file(&temp_path));
        return Err(error);
    }

    // Re-verify the staged bytes before they adopt the digest-derived name.
    let (staged_digest, staged_size) = match ContentDigest::stream_from_path(&temp_path) {
        Ok(facts) => facts,
        Err(error) => {
            drop(fs::remove_file(&temp_path));
            return Err(error);
        }
    };
    if staged_digest != digest || staged_size != size {
        drop(fs::remove_file(&temp_path));
        return Err(corruption_mismatch());
    }
    if let Err(error) = fs::rename(&temp_path, &staging_path) {
        drop(fs::remove_file(&temp_path));
        return Err(storage(
            "media_stage_commit_failed",
            &format!("failed to commit staged bytes to the digest name: {error}"),
        ));
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
/// The durable [`StageLedger`] is the sole authority for deleting staged bytes: when any
/// holder still leases the artifact the bytes are kept, because a caller dropping its
/// [`MediaStaged`] facts must never destroy staged media another owner depends on. Deleting
/// without consulting the ledger would silently break that ownership.
///
/// # Errors
///
/// Returns storage when delete fails for an existing path, and corruption/storage when the
/// durable ledger cannot be consulted — deletion is refused while ownership is unknown.
pub fn discard_staged(staged: &MediaStaged) -> Result<(), LomoError> {
    if !staged.staging_path.exists() {
        return Ok(());
    }
    let stage_dir = stage_directory_of(&staged.staging_path)?;
    let ledger = StageLedger::load(&stage_dir)?;
    if ledger.holds_leases(&ArtifactId::of_digest(&staged.digest)) {
        return Ok(());
    }
    fs::remove_file(&staged.staging_path).map_err(|error| {
        storage(
            "media_stage_discard_failed",
            &format!("failed to discard staged media: {error}"),
        )
    })
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
