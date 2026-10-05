//! Durable private capture journal. Replayed submissions reuse the same operation and receipt.
use crate::{
    error::TuiError,
    input::TextBuffer,
    model::{Composer, SaveState},
    ops::{TuiRuntime, mint_operation_id},
};
use lomo_application::CreateMemoRequest;
use lomo_core::OperationId;
use lomo_workspace::MemoId;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Serialize, Deserialize)]
struct CaptureRecord {
    schema: u32,
    revision: u64,
    content: String,
    phase: CapturePhase,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case")]
enum CapturePhase {
    Editing,
    Pending {
        operation_id: String,
    },
    Saved {
        operation_id: String,
        memo_id: String,
    },
}

/// # Errors
/// Invalid workspace path or private-directory failure.
pub fn capture_path(runtime: &TuiRuntime) -> Result<PathBuf, TuiError> {
    let canonical = fs::canonicalize(&runtime.workspace)?;
    let identity = canonical
        .to_str()
        .ok_or_else(|| TuiError::config("workspace path must be UTF-8"))?;
    let digest = format!("{:x}", Sha256::digest(identity.as_bytes()));
    Ok(runtime
        .paths
        .drafts_dir
        .join(format!("capture-{digest}.json")))
}

fn read_capture(runtime: &TuiRuntime) -> Result<Option<CaptureRecord>, TuiError> {
    let path = capture_path(runtime)?;
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    parse_capture(&path, &bytes).map(Some)
}

/// Separates IO from validation so `load_capture` can tell a corrupt record
/// (recoverable: set aside, start empty) from an unreadable file (an IO error
/// to surface — renaming it aside would change nothing).
fn parse_capture(path: &Path, bytes: &[u8]) -> Result<CaptureRecord, TuiError> {
    let record: CaptureRecord = serde_json::from_slice(bytes).map_err(|error| {
        TuiError::config(format!("invalid capture draft {}: {error}", path.display()))
    })?;
    if record.schema != 1 {
        return Err(TuiError::config("unsupported capture draft schema"));
    }
    match &record.phase {
        CapturePhase::Pending { operation_id } => {
            OperationId::parse(operation_id)?;
        }
        CapturePhase::Saved {
            operation_id,
            memo_id,
        } => {
            OperationId::parse(operation_id)?;
            MemoId::parse(memo_id)?;
        }
        CapturePhase::Editing => {}
    }
    Ok(record)
}

/// The set-aside name for a corrupt capture: `<file>.corrupt`, or the first
/// free `.corrupt-N` when an earlier recovery already occupies the name —
/// every corruption keeps its own forensic copy; a fixed name would let a
/// second failure overwrite (POSIX) or refuse (Windows) the first backup.
fn corrupt_backup_path(path: &Path) -> PathBuf {
    let base = path.with_extension("json.corrupt");
    if !base.exists() {
        return base;
    }
    for n in 1..=u32::MAX {
        let candidate = path.with_extension(format!("json.corrupt-{n}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    // u32::MAX backups cannot exist in practice — a drafts dir would run out
    // of entries first; fall back to the base name rather than panic.
    base
}

/// The composer's start-of-session state plus recovery evidence.
///
/// When a corrupt draft was renamed aside, `recovered_corrupt` names the
/// backup so the caller can show the notice — the draft is never silently
/// discarded (F-09).
pub struct LoadedCapture {
    pub composer: Composer,
    pub recovered_corrupt: Option<PathBuf>,
}

/// # Errors
/// Unreadable draft IO or a corrupt draft that cannot even be renamed aside.
/// A parseable-but-invalid record is moved to `<file>.corrupt` and the
/// composer starts empty; interrupted saves stay editable.
pub fn load_capture(runtime: &TuiRuntime) -> Result<LoadedCapture, TuiError> {
    let path = capture_path(runtime)?;
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(LoadedCapture {
                composer: Composer::default(),
                recovered_corrupt: None,
            });
        }
        Err(error) => return Err(error.into()),
    };
    let record = match parse_capture(&path, &bytes) {
        Ok(record) => record,
        Err(error) => {
            let backup = corrupt_backup_path(&path);
            fs::rename(&path, &backup).map_err(|rename| {
                TuiError::config(format!(
                    "{error}; could not set the corrupt draft aside: {rename}"
                ))
            })?;
            return Ok(LoadedCapture {
                composer: Composer::default(),
                recovered_corrupt: Some(backup),
            });
        }
    };
    let mut composer = Composer {
        revision: record.revision,
        persisted_revision: record.revision,
        ..Composer::default()
    };
    match record.phase {
        CapturePhase::Editing => composer.text = TextBuffer::new(record.content),
        CapturePhase::Pending { .. } => {
            composer.text = TextBuffer::new(record.content);
            composer.save = SaveState::Failed {
                diagnostic: crate::i18n::UiStrings::detect()
                    .text(
                        "Previous save was interrupted · Ctrl+S retries the same submission",
                        "上次保存中断 · Ctrl+S 可重试同一次提交",
                    )
                    .to_owned(),
            };
        }
        CapturePhase::Saved { .. } => {
            composer.revision = composer.revision.saturating_add(1);
            composer.persisted_revision = composer.revision;
        }
    }
    Ok(LoadedCapture {
        composer,
        recovered_corrupt: None,
    })
}

fn validate_revision(
    previous: &CaptureRecord,
    revision: u64,
    content: &str,
) -> Result<(), TuiError> {
    if revision < previous.revision {
        return Err(TuiError::config("stale capture revision"));
    }
    if revision == previous.revision && content != previous.content {
        return Err(TuiError::config("capture revision has conflicting content"));
    }
    Ok(())
}

/// # Errors
/// Private journal writes fail visibly; stale revisions cannot replace newer draft bytes.
pub fn persist_capture(runtime: &TuiRuntime, revision: u64, content: &str) -> Result<(), TuiError> {
    let phase = if let Some(previous) = read_capture(runtime)? {
        validate_revision(&previous, revision, content)?;
        if previous.revision == revision {
            previous.phase
        } else {
            CapturePhase::Editing
        }
    } else {
        CapturePhase::Editing
    };
    write_record(
        runtime,
        &CaptureRecord {
            schema: 1,
            revision,
            content: content.to_owned(),
            phase,
        },
    )
}

fn write_record(runtime: &TuiRuntime, record: &CaptureRecord) -> Result<(), TuiError> {
    let bytes = serde_json::to_vec(record).map_err(|error| TuiError::config(error.to_string()))?;
    atomic_write(&capture_path(runtime)?, &bytes)
}

/// # Errors
/// Validation and commit errors retain the pending operation for an identical retry.
pub fn commit_capture(
    runtime: &TuiRuntime,
    revision: u64,
    content: &str,
) -> Result<MemoId, TuiError> {
    if content.trim().is_empty() {
        return Err(TuiError::config("memo content must not be empty"));
    }
    let previous = read_capture(runtime)?;
    if let Some(record) = &previous {
        validate_revision(record, revision, content)?;
    }
    let phase = previous
        .filter(|record| record.revision == revision)
        .map(|record| record.phase);
    let operation_id = match phase {
        Some(CapturePhase::Saved { memo_id, .. }) => {
            return MemoId::parse(&memo_id).map_err(TuiError::from);
        }
        Some(CapturePhase::Pending { operation_id }) => OperationId::parse(&operation_id)?,
        Some(CapturePhase::Editing) | None => mint_operation_id()?,
    };
    let operation = operation_id.as_str().to_owned();
    write_record(
        runtime,
        &CaptureRecord {
            schema: 1,
            revision,
            content: content.to_owned(),
            phase: CapturePhase::Pending {
                operation_id: operation.clone(),
            },
        },
    )?;
    let created = runtime.session.create_memo(CreateMemoRequest {
        operation_id,
        relative_path: None,
        time_token: None,
        content: content.to_owned(),
        expected_document_fingerprint: None,
        pinned: false,
        pending_promotes: Vec::new(),
        chronology_epoch_ms: None,
    })?;
    write_record(
        runtime,
        &CaptureRecord {
            schema: 1,
            revision,
            content: content.to_owned(),
            phase: CapturePhase::Saved {
                operation_id: operation,
                memo_id: created.memo_id.as_str().to_owned(),
            },
        },
    )?;
    Ok(created.memo_id)
}

/// # Errors
/// Private directory creation or permission changes fail visibly.
#[cfg(unix)]
pub fn private_directory(path: &Path) -> Result<(), TuiError> {
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
    fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(path)?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

/// # Errors
/// Private directory creation fails visibly. Non-Unix targets inherit the
/// per-user profile ACL, which is already private — no mode bits exist.
#[cfg(not(unix))]
pub fn private_directory(path: &Path) -> Result<(), TuiError> {
    fs::create_dir_all(path)?;
    Ok(())
}

/// # Errors
/// Parent creation, writes, fsync or rename failures.
pub fn write_draft(path: &Path, content: &str) -> Result<(), TuiError> {
    atomic_write(path, content.as_bytes())
}

/// Atomic durable write shared by drafts and `config.toml` installs: fsync the
/// temp file, rename over the target, fsync the directory.
pub(crate) fn atomic_write(path: &Path, bytes: &[u8]) -> Result<(), TuiError> {
    let parent = path
        .parent()
        .ok_or_else(|| TuiError::config("draft path has no parent"))?;
    private_directory(parent)?;
    let operation = mint_operation_id()?;
    let temp = path.with_extension(format!("{}.tmp", operation.as_str()));
    let mut file = private_file_options().open(&temp)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    fs::rename(&temp, path)?;
    sync_dir(parent)?;
    Ok(())
}

/// `create_new` + `write` open with owner-only permissions on Unix. Non-Unix
/// targets inherit the per-user profile ACL; no mode flag exists there.
fn private_file_options() -> OpenOptions {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options
}

/// Directory metadata durability. Windows has no directory-sync syscall —
/// NTFS journals directory metadata — so it resolves immediately there.
#[cfg(unix)]
fn sync_dir(path: &Path) -> Result<(), TuiError> {
    fs::File::open(path)?.sync_all()?;
    Ok(())
}

/// # Errors
/// Never fails: NTFS journals directory metadata; no sync primitive exists.
#[cfg(not(unix))]
fn sync_dir(path: &Path) -> Result<(), TuiError> {
    let _ = path;
    Ok(())
}

/// # Errors
/// Unexpected deletion or directory synchronization failures.
pub fn remove_draft(path: &Path) -> Result<(), TuiError> {
    match fs::remove_file(path) {
        Ok(()) => {
            if let Some(parent) = path.parent() {
                sync_dir(parent)?;
            }
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}
