//! Session-owned two-phase media orphan sweep.
//!
//! Phase one enumerates `media/` candidates and collects the protection set outside the
//! transaction lock (streaming digests only — media bytes never enter memory). Phase two acquires
//! [`TransactionLock`], recomputes the protection set, re-verifies every candidate's evidence, and
//! only then moves unreferenced objects into `.lomo-media-trash` or purges expired trash through
//! verified platform actions. A reference created between enumeration and commit blocks deletion
//! because the keep-set is rebuilt inside the same write isolation every mutation uses.

use std::path::PathBuf;

use lomo_core::{
    ActionId, DocumentKind, DocumentMetadata, ExpectedFingerprint, LomoError, PageSize,
    PlatformAction, PlatformActionOutput, RelativeWorkspacePath, WorkspaceTarget,
};
use lomo_media::{
    ContentDigest, MEDIA_DELETE_INTENT_DIR_NAME, MEDIA_TRASH_DIR_NAME, MediaDeleteIntent,
    MediaTrashEntry,
};

use crate::{
    csprng::generate_hex_token,
    error::{corruption, storage, validation},
    lock::TransactionLock,
    media_index::AttachmentIndex,
    session::WorkspaceSession,
    workspace_io::WorkspaceIo,
};

/// Committed media directory at the workspace root.
const MEDIA_DIR: &str = "media";

/// Why a candidate survived the sweep.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MediaSweepProtection {
    pub relative_path: String,
    pub source: lomo_media::ReferenceSource,
    pub owner_key: String,
}

/// One candidate or trash entry the sweep could not safely resolve.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MediaSweepFailure {
    pub relative_path: String,
    pub code: String,
    pub message: String,
}

/// Observable result of one sweep: candidates, protections, moves, purges, and failures.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MediaSweepReport {
    /// Committed files under `media/` examined this run.
    pub candidates: u64,
    /// Candidates kept because a protection source still references them.
    pub protections: Vec<MediaSweepProtection>,
    /// Unreferenced candidates moved into `.lomo-media-trash`.
    pub moved_to_trash: Vec<MediaTrashEntry>,
    /// Expired trash entries permanently deleted after journaling a delete intent.
    pub permanently_deleted: Vec<MediaDeleteIntent>,
    /// Candidates or trash entries the sweep refused to touch, with the reason.
    pub failures: Vec<MediaSweepFailure>,
}

struct SweepCandidate {
    path: RelativeWorkspacePath,
    metadata: DocumentMetadata,
}

struct TrashCandidate {
    entry: MediaTrashEntry,
    path: RelativeWorkspacePath,
    metadata: DocumentMetadata,
}

/// Locked-phase execution state shared by candidate moves and trash purges.
struct SweepRun<'a> {
    io: &'a WorkspaceIo<'a>,
    index: &'a AttachmentIndex,
    now_ms: u64,
    recovery_window_ms: u64,
    trash_ready: bool,
    report: MediaSweepReport,
}

impl WorkspaceSession {
    /// Runs the two-phase orphan sweep over committed `media/` objects.
    ///
    /// Unreferenced candidates move to `.lomo-media-trash`; expired trash entries are journaled
    /// into `.lomo-media-delete-intents` and then permanently deleted. Per-candidate problems are
    /// reported in [`MediaSweepReport::failures`] instead of being silently dropped.
    ///
    /// # Errors
    ///
    /// Protection-set collection, listing, or lock failures abort the sweep before any mutation.
    pub fn media_orphan_sweep(
        &self,
        now_ms: u64,
        recovery_window_ms: u64,
    ) -> Result<MediaSweepReport, LomoError> {
        let io = self.io();
        // Phase one: enumerate candidates and trash outside the mutation lock.
        let mut report = MediaSweepReport::default();
        let candidates = Self::sweep_candidates(&io, &mut report)?;
        let trash = Self::trash_entries(&io, recovery_window_ms, &mut report)?;
        report.candidates = u64::try_from(candidates.len())
            .map_err(|error| resource_overflow("media_sweep_candidates", error.to_string()))?;

        // Phase two: inside the write lock recompute protection and re-verify every candidate.
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let index = self.attachment_index()?;
        let mut run = SweepRun {
            io: &io,
            index: &index,
            now_ms,
            recovery_window_ms,
            trash_ready: false,
            report,
        };
        for candidate in &candidates {
            self.sweep_one(candidate, &mut run);
        }
        for item in &trash {
            self.purge_one(item, &mut run);
        }
        Ok(run.report)
    }

    fn sweep_candidates(
        io: &WorkspaceIo<'_>,
        report: &mut MediaSweepReport,
    ) -> Result<Vec<SweepCandidate>, LomoError> {
        let mut candidates = Vec::new();
        let mut pending_dirs = vec![RelativeWorkspacePath::parse(MEDIA_DIR)?];
        while let Some(dir) = pending_dirs.pop() {
            for item in list_dir(io, &dir)? {
                let WorkspaceTarget::Relative(path) = item.target().clone() else {
                    continue;
                };
                match item.kind() {
                    DocumentKind::Directory => pending_dirs.push(path),
                    DocumentKind::File => {
                        if let Some(metadata) = verified_metadata(io, &path, item, report) {
                            candidates.push(SweepCandidate { path, metadata });
                        }
                    }
                }
            }
        }
        Ok(candidates)
    }

    fn trash_entries(
        io: &WorkspaceIo<'_>,
        recovery_window_ms: u64,
        report: &mut MediaSweepReport,
    ) -> Result<Vec<TrashCandidate>, LomoError> {
        let dir = RelativeWorkspacePath::parse(MEDIA_TRASH_DIR_NAME)?;
        let mut out = Vec::new();
        for item in list_dir(io, &dir)? {
            let WorkspaceTarget::Relative(path) = item.target().clone() else {
                continue;
            };
            if item.kind() != DocumentKind::File {
                continue;
            }
            let Some(name) = path.as_str().rsplit('/').next() else {
                continue;
            };
            match lomo_media::parse_trash_entry_name(name) {
                Ok((digest, trashed_at_ms)) => out.push(TrashCandidate {
                    entry: MediaTrashEntry {
                        digest,
                        trash_path: PathBuf::from(path.as_str()),
                        trashed_at_ms,
                        expires_at_ms: trashed_at_ms.saturating_add(recovery_window_ms),
                    },
                    path,
                    metadata: item,
                }),
                Err(error) => report.failures.push(MediaSweepFailure {
                    relative_path: path.as_str().to_owned(),
                    code: error.code().to_owned(),
                    message: error.to_string(),
                }),
            }
        }
        Ok(out)
    }

    fn sweep_one(&self, candidate: &SweepCandidate, run: &mut SweepRun<'_>) {
        let path_str = candidate.path.as_str().to_owned();
        let result = (|| -> Result<(), LomoError> {
            // Re-verify the candidate inside the lock: unchanged evidence means the bytes that
            // were enumerated are the bytes a move would touch.
            let current = run.io.stat(&candidate.path)?.ok_or_else(|| {
                storage("media_sweep_candidate_vanished", "candidate disappeared")
            })?;
            ensure_same_document(&candidate.metadata, &current)?;
            let digest_hex = current
                .evidence()
                .verified_digest()
                .map(|digest| digest.as_str().to_owned());
            if run.index.protects_path(&path_str)
                || digest_hex
                    .as_deref()
                    .is_some_and(|hex| run.index.protects_digest(hex))
            {
                let (source, owner_key) = run.index.protection_for(&path_str).map_or_else(
                    || {
                        (
                            lomo_media::ReferenceSource::StageLease,
                            "lease:digest".to_owned(),
                        )
                    },
                    |obs| (obs.source, obs.owner_key.clone()),
                );
                run.report.protections.push(MediaSweepProtection {
                    relative_path: path_str.clone(),
                    source,
                    owner_key,
                });
                return Ok(());
            }
            let digest_hex = digest_hex.ok_or_else(|| {
                corruption(
                    "media_sweep_digest_unverified",
                    "candidate digest is not independently verifiable",
                )
            })?;
            if !run.trash_ready {
                ensure_trash_dir(run.io)?;
                run.trash_ready = true;
            }
            let file_name = path_str.rsplit('/').next().ok_or_else(|| {
                validation("invalid_media_trash_name", "media path has no file name")
            })?;
            let trash_name = lomo_media::trash_entry_name(&digest_hex, run.now_ms, file_name);
            let target =
                RelativeWorkspacePath::parse(&format!("{MEDIA_TRASH_DIR_NAME}/{trash_name}"))?;
            let action = PlatformAction::move_path(
                ActionId::parse(&format!("sweep-move-{}", generate_hex_token(8)?))?,
                self.config.capability.clone(),
                candidate.path.clone(),
                target,
                ExpectedFingerprint::matching(current.evidence().clone()),
                ExpectedFingerprint::absent(),
            );
            let PlatformActionOutput::MoveComplete { .. } = run.io.execute(action)? else {
                return Err(corruption(
                    "invalid_move_output",
                    "expected a verified move receipt",
                ));
            };
            run.report.moved_to_trash.push(MediaTrashEntry {
                digest: ContentDigest::parse(&digest_hex)?,
                trash_path: PathBuf::from(format!("{MEDIA_TRASH_DIR_NAME}/{trash_name}")),
                trashed_at_ms: run.now_ms,
                expires_at_ms: run.now_ms.saturating_add(run.recovery_window_ms),
            });
            Ok(())
        })();
        match result {
            Ok(()) => {}
            Err(error) if error.code() == "media_sweep_candidate_vanished" => {
                // The file is already gone; nothing remains to collect.
            }
            Err(error) => run.report.failures.push(MediaSweepFailure {
                relative_path: path_str,
                code: error.code().to_owned(),
                message: error.to_string(),
            }),
        }
    }

    fn purge_one(&self, item: &TrashCandidate, run: &mut SweepRun<'_>) {
        if item.entry.expires_at_ms > run.now_ms {
            return;
        }
        let path_str = item.path.as_str().to_owned();
        let result = (|| -> Result<(), LomoError> {
            let current = run
                .io
                .stat(&item.path)?
                .ok_or_else(|| storage("media_sweep_trash_vanished", "trash entry disappeared"))?;
            ensure_same_document(&item.metadata, &current)?;
            let intent = MediaDeleteIntent {
                digest: item.entry.digest.clone(),
                path: item.entry.trash_path.clone(),
                recorded_at_ms: run.now_ms,
                reason: "recovery_window_elapsed".to_owned(),
            };
            let body = serde_json::to_vec(&intent).map_err(|error| {
                validation(
                    "media_delete_intent_encode_failed",
                    format!("cannot encode media delete intent: {error}"),
                )
            })?;
            // Durable delete-intent journal inside the workspace before the permanent delete.
            ensure_intent_dir(run.io)?;
            let intent_path = RelativeWorkspacePath::parse(&format!(
                "{MEDIA_DELETE_INTENT_DIR_NAME}/{}_{}.json",
                intent.recorded_at_ms,
                intent.digest.as_str()
            ))?;
            run.io.write(&intent_path, None, &body)?;
            let action = PlatformAction::delete(
                ActionId::parse(&format!("sweep-delete-{}", generate_hex_token(8)?))?,
                self.config.capability.clone(),
                item.path.clone(),
                ExpectedFingerprint::matching(current.evidence().clone()),
            );
            let PlatformActionOutput::DeleteComplete { .. } = run.io.execute(action)? else {
                return Err(corruption(
                    "invalid_delete_output",
                    "expected a verified delete receipt",
                ));
            };
            run.report.permanently_deleted.push(intent);
            Ok(())
        })();
        match result {
            Ok(()) => {}
            Err(error) if error.code() == "media_sweep_trash_vanished" => {
                // Already gone; the recovery goal is met.
            }
            Err(error) => run.report.failures.push(MediaSweepFailure {
                relative_path: path_str,
                code: error.code().to_owned(),
                message: error.to_string(),
            }),
        }
    }
}

/// Lists one workspace directory; a missing directory is an empty listing, never an error.
fn list_dir(
    io: &WorkspaceIo<'_>,
    dir: &RelativeWorkspacePath,
) -> Result<Vec<DocumentMetadata>, LomoError> {
    let mut items = Vec::new();
    let mut cursor = None;
    loop {
        let action = PlatformAction::list_children(
            ActionId::parse(&format!("sweep-list-{}", generate_hex_token(8)?))?,
            io.config.capability.clone(),
            dir.clone(),
            cursor,
            PageSize::new(256)?,
        );
        let output = match io.execute(action) {
            Ok(output) => output,
            Err(error) if error.code() == "document_not_found" => return Ok(items),
            Err(error) => return Err(error),
        };
        let PlatformActionOutput::Listed { page } = output else {
            return Err(corruption("invalid_list_output", "expected Listed output"));
        };
        items.extend(page.items().iter().cloned());
        cursor = page.next_cursor().map(|value| value.as_str().to_owned());
        if cursor.is_none() {
            break;
        }
    }
    Ok(items)
}

/// Ensures the candidate carries a verified digest; falls back to a streaming stat when the
/// directory listing was not content-authoritative. Unverifiable digests become failures.
fn verified_metadata(
    io: &WorkspaceIo<'_>,
    path: &RelativeWorkspacePath,
    listed: DocumentMetadata,
    report: &mut MediaSweepReport,
) -> Option<DocumentMetadata> {
    if listed.evidence().verified_digest().is_some() {
        return Some(listed);
    }
    match io.stat(path) {
        Ok(Some(metadata)) if metadata.evidence().verified_digest().is_some() => Some(metadata),
        Ok(_) => {
            report.failures.push(MediaSweepFailure {
                relative_path: path.as_str().to_owned(),
                code: "media_sweep_digest_unverified".to_owned(),
                message: "candidate digest is not independently verifiable".to_owned(),
            });
            None
        }
        Err(error) => {
            report.failures.push(MediaSweepFailure {
                relative_path: path.as_str().to_owned(),
                code: error.code().to_owned(),
                message: error.to_string(),
            });
            None
        }
    }
}

/// Two observations describe the same document only when fingerprint, length, and any verified
/// digest agree; a mismatch means the file changed between enumeration and commit.
fn ensure_same_document(
    before: &DocumentMetadata,
    after: &DocumentMetadata,
) -> Result<(), LomoError> {
    let before_evidence = before.evidence();
    let after_evidence = after.evidence();
    let digest_matches = match (
        before_evidence.verified_digest(),
        after_evidence.verified_digest(),
    ) {
        (Some(left), Some(right)) => left == right,
        _ => true,
    };
    if before_evidence.fingerprint() != after_evidence.fingerprint()
        || before_evidence.length() != after_evidence.length()
        || !digest_matches
    {
        return Err(corruption(
            "media_sweep_candidate_changed",
            "candidate changed between enumeration and commit",
        ));
    }
    Ok(())
}

fn ensure_trash_dir(io: &WorkspaceIo<'_>) -> Result<(), LomoError> {
    ensure_dir(io, "sweep-mkdir", MEDIA_TRASH_DIR_NAME)
}

fn ensure_intent_dir(io: &WorkspaceIo<'_>) -> Result<(), LomoError> {
    ensure_dir(io, "sweep-intent-mkdir", MEDIA_DELETE_INTENT_DIR_NAME)
}

fn ensure_dir(io: &WorkspaceIo<'_>, action_prefix: &str, dir: &str) -> Result<(), LomoError> {
    let action = PlatformAction::ensure_directory(
        ActionId::parse(&format!("{action_prefix}-{}", generate_hex_token(8)?))?,
        io.config.capability.clone(),
        RelativeWorkspacePath::parse(dir)?,
    );
    let PlatformActionOutput::DirectoryReady { .. } = io.execute(action)? else {
        return Err(corruption(
            "invalid_ensure_directory_output",
            "expected a directory receipt",
        ));
    };
    Ok(())
}

fn resource_overflow(code: &'static str, message: String) -> LomoError {
    crate::error::resource_limit(code, message)
}
