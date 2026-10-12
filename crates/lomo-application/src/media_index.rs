//! Global attachment references across active bodies, trash, history, drafts,
//! pending transactions, and the durable media stage ledger.
//!
//! The index is the single reference authority for media garbage collection: an object under
//! `media/` may be reclaimed only when no live or trash body, in-window history revision, conflict
//! draft, frozen transaction, or stage-ledger lease names its canonical relative path or content
//! digest. The read-side index is memoized on one unified stamp — the committed projection
//! revision plus `(name, size, mtime)` file tokens for pending intent records, retained draft
//! evidence, and the stage ledger — so a second call at unchanged state pays zero rebuild while
//! any deviation rebuilds fail-closed. The sweep's under-lock recheck
//! ([`WorkspaceSession::attachment_index_guarding`]) always collects fresh so it observes
//! references created after candidate enumeration. Conflict-evidence drafts are enumerated by
//! file token inside a build: a draft whose name, size and modification time still match its
//! retained projection is never re-read or re-decoded; only a changed token pays the full
//! fail-closed decode.

use std::collections::{BTreeMap, BTreeSet};

use lomo_core::{LomoError, OperationId};
use lomo_media::{ReferenceSource, StageLedger};
use lomo_store::{DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS, StoreReader, project_content_facts};
use lomo_workspace::canonical_attachment_path;

use crate::{
    draft::{DraftFileEvidence, GuardedDraftBody},
    error::{storage, validation},
    session::WorkspaceSession,
    transaction::PlannedFile,
};

/// One observed attachment reference with its owning protection source.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AttachmentObservation {
    /// Canonical workspace-relative path exactly as the body or lease names it.
    pub relative_path: String,
    /// Which protection source observed the reference.
    pub source: ReferenceSource,
    /// Opaque owner identity for diagnostics (`memo_id`, `memo@rN`, `op:<id>`, `lease:<kind>:<id>`).
    pub owner_key: String,
}

/// The complete protection set: which relative paths and content digests stay live.
#[derive(Clone, Debug, Default)]
pub struct AttachmentIndex {
    observations: Vec<AttachmentObservation>,
    paths: BTreeSet<String>,
    digests: BTreeSet<String>,
}

impl AttachmentIndex {
    /// Every observed reference, in collection order.
    #[must_use]
    pub fn observations(&self) -> &[AttachmentObservation] {
        &self.observations
    }

    /// True when any protection source still names this canonical relative path.
    #[must_use]
    pub fn protects_path(&self, relative_path: &str) -> bool {
        // The query uses the same canonical key the index stores, so an alternate spelling of a
        // protected file (`media/./x`, `media//x`) still answers true.
        canonical_attachment_path(relative_path)
            .is_some_and(|canonical| self.paths.contains(&canonical))
    }

    /// True when a stage-ledger lease or pending artifact write still claims this content digest.
    #[must_use]
    pub fn protects_digest(&self, digest_hex: &str) -> bool {
        self.digests.contains(digest_hex)
    }

    /// First observation protecting `relative_path`, for sweep protection reporting.
    #[must_use]
    pub fn protection_for(&self, relative_path: &str) -> Option<&AttachmentObservation> {
        let canonical = canonical_attachment_path(relative_path)?;
        self.observations
            .iter()
            .find(|item| item.relative_path == canonical)
    }

    fn observe(&mut self, relative_path: &str, source: ReferenceSource, owner_key: String) {
        // One canonical representation: a destination that cannot name a workspace file
        // (external URL, empty, escapes the root) protects nothing and records no key.
        let Some(relative_path) = canonical_attachment_path(relative_path) else {
            return;
        };
        self.paths.insert(relative_path.clone());
        self.observations.push(AttachmentObservation {
            relative_path,
            source,
            owner_key,
        });
    }
}

/// Unified invalidation stamp for the memoized attachment index.
///
/// One token per fact source a build consumes, each the cheapest fail-closed observation of that
/// source: the committed high-water revision covers projected live/trash and in-window history
/// rows, per-file `(name, size, mtime)` tokens cover pending intent records and retained draft
/// evidence, and the stage ledger contributes its own file token. A build whose stamp still
/// matches is complete; any deviation rebuilds.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct IndexStamp {
    revision: u64,
    pending: Vec<DraftFileEvidence>,
    drafts: Vec<DraftFileEvidence>,
    stage_ledger: Option<DraftFileEvidence>,
}

/// Cumulative memoization evidence for the read-side attachment index.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct AttachmentIndexStats {
    /// Full protection-index builds since session open.
    pub builds: u64,
    /// Reads answered by the memoized index at an unchanged stamp.
    pub hits: u64,
}

/// Stamp-keyed memoized attachment index behind the session lock.
pub(crate) struct AttachmentIndexCache {
    /// Last build's unified stamp and its index; `None` until the first build lands.
    pub current: Option<(IndexStamp, AttachmentIndex)>,
    /// Build/hit counters since session open.
    pub stats: AttachmentIndexStats,
}

impl AttachmentIndexCache {
    pub(crate) const fn new() -> Self {
        Self {
            current: None,
            stats: AttachmentIndexStats { builds: 0, hits: 0 },
        }
    }
}

/// `(name, size, mtime)` token for one evidence file — `None` when the file is absent; a
/// present file whose metadata cannot be read is an error, never an empty stamp.
fn file_token(path: &std::path::Path) -> Result<Option<DraftFileEvidence>, LomoError> {
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(storage(
                "attachment_stamp_read_failed",
                format!("failed to stat evidence {}: {error}", path.display()),
            ));
        }
    };
    if !metadata.is_file() {
        return Ok(None);
    }
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .map(str::to_owned)
        .ok_or_else(|| {
            validation(
                "invalid_evidence_name",
                format!("evidence file name is not UTF-8: {}", path.display()),
            )
        })?;
    Ok(Some(DraftFileEvidence {
        name,
        size: metadata.len(),
        modified: metadata.modified().map_err(|error| {
            storage(
                "attachment_stamp_read_failed",
                format!(
                    "failed to read evidence timestamp {}: {error}",
                    path.display()
                ),
            )
        })?,
    }))
}

/// Cumulative sweep-cost evidence for retained conflict-evidence drafts.
///
/// An attachment-index build never decodes a draft whose file token still matches the
/// retained projection: the draft's references cannot have changed, so re-reading and
/// re-decoding its bytes would recompute an identical protection set.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DraftEvidenceStats {
    /// Evidence files enumerated by token listing across all builds.
    pub files_seen: u64,
    /// Files whose token matched a retained projection; their bytes were not touched.
    pub token_hits: u64,
    /// Files whose token was new or changed and paid the full decode.
    pub decoded: u64,
}

/// Token-keyed decoded draft projections retained across attachment-index builds.
///
/// The sweep and every attachment-index consumer share this state: on each pass the
/// draft directory is enumerated by file token, only files whose token changed are
/// read and decoded, and projections for files that disappeared are evicted. Entries
/// are never trusted beyond their token — a changed size or timestamp always falls
/// back to the full fail-closed decode.
pub(crate) struct DraftRefCache {
    entries: BTreeMap<String, CachedDraftRefs>,
    pub(crate) stats: DraftEvidenceStats,
}

/// Decoded projection for one retained draft file, keyed by its observed token.
struct CachedDraftRefs {
    evidence: DraftFileEvidence,
    operation_id: OperationId,
    attachment_paths: Vec<String>,
}

impl DraftRefCache {
    pub(crate) const fn new() -> Self {
        Self {
            entries: BTreeMap::new(),
            stats: DraftEvidenceStats {
                files_seen: 0,
                token_hits: 0,
                decoded: 0,
            },
        }
    }
}

impl WorkspaceSession {
    /// Returns the full protection index, memoized on the unified `IndexStamp`.
    ///
    /// Sources: projected `attachment_ref` rows for live and trashed memos, in-window history
    /// revision bodies, pending intent-journal transactions (planned bodies, history writes, and
    /// artifact targets), conflict-evidence drafts, and stage-ledger leases held by drafts,
    /// pending operations, incoming transfers, or committed references. A call whose stamp still
    /// matches the last build's returns the retained index without touching the projection,
    /// journal, drafts, or stage ledger again; any stamp deviation rebuilds fail-closed. The
    /// sweep's under-lock recheck intentionally bypasses this cache through
    /// [`Self::attachment_index_guarding`].
    ///
    /// # Errors
    ///
    /// Stamp-sampling, projection, journal, draft, or stage-ledger read failures. A corrupt or
    /// incomplete projection surfaces as an error rather than a silently empty keep-set.
    pub fn attachment_index(&self) -> Result<AttachmentIndex, LomoError> {
        let stamp = self.current_index_stamp()?;
        let hit = {
            let mut cache = self
                .attachment_index_cache
                .lock()
                .map_err(|error| storage("attachment_index_cache_poisoned", error.to_string()))?;
            let hit = match &cache.current {
                Some((cached_stamp, index)) if *cached_stamp == stamp => Some(index.clone()),
                _ => None,
            };
            if hit.is_some() {
                cache.stats.hits += 1;
            }
            drop(cache);
            hit
        };
        if let Some(index) = hit {
            return Ok(index);
        }
        let index = self.collect_attachment_index(&[])?;
        {
            let mut cache = self
                .attachment_index_cache
                .lock()
                .map_err(|error| storage("attachment_index_cache_poisoned", error.to_string()))?;
            cache.stats.builds += 1;
            cache.current = Some((stamp, index.clone()));
            drop(cache);
        }
        Ok(index)
    }

    /// Collects the protection set, also guarding externally held draft bodies.
    ///
    /// A host that keeps its own draft state outside the Rust `DraftStore` (for example an
    /// editor buffer never persisted as conflict evidence) passes those bodies here so their
    /// attachment references extend the keep-set exactly like internal drafts. The bodies are
    /// used for this computation only and are never persisted. This is the sweep's recheck
    /// path: it always collects fresh — caller-supplied bodies are not part of the unified
    /// stamp, so the memoized index cannot describe them.
    ///
    /// # Errors
    ///
    /// Same as [`Self::attachment_index`]; an external body the render owner cannot project
    /// fails the whole collection rather than silently dropping the draft's protection.
    pub fn attachment_index_guarding(
        &self,
        external_drafts: &[GuardedDraftBody],
    ) -> Result<AttachmentIndex, LomoError> {
        self.collect_attachment_index(external_drafts)
    }

    /// Samples the unified invalidation stamp — the committed revision plus one
    /// `(name, size, mtime)` file token per evidence source a build consumes.
    fn current_index_stamp(&self) -> Result<IndexStamp, LomoError> {
        let revision = self.with_store(|store| Ok(store.high_water_revision()))?;
        Ok(IndexStamp {
            revision,
            pending: self.intent_journal.pending_evidence()?,
            drafts: self.draft_store.list_draft_evidence()?,
            stage_ledger: file_token(
                &lomo_media::stage_directory(&self.config.media_stage_root)
                    .join(lomo_media::STAGE_LEDGER_FILE),
            )?,
        })
    }

    fn collect_attachment_index(
        &self,
        external_drafts: &[GuardedDraftBody],
    ) -> Result<AttachmentIndex, LomoError> {
        let mut index = AttachmentIndex::default();
        self.collect_projected_refs(&mut index)?;
        self.collect_history_refs(&mut index)?;
        self.collect_pending_refs(&mut index)?;
        self.collect_draft_refs(&mut index, external_drafts)?;
        self.collect_stage_lease_refs(&mut index)?;
        Ok(index)
    }

    /// Attachment paths from active memos, trash, in-window history, drafts, pending
    /// transactions, and stage leases.
    ///
    /// # Errors
    /// Projection and I/O failures.
    pub fn observe_attachments(&self) -> Result<Vec<AttachmentObservation>, LomoError> {
        Ok(self.attachment_index()?.observations)
    }

    /// True when any protection source still names this canonical relative path.
    ///
    /// # Errors
    /// Projection failures.
    pub fn attachment_is_protected(&self, relative_path: &str) -> Result<bool, LomoError> {
        Ok(self.attachment_index()?.protects_path(relative_path))
    }

    fn collect_projected_refs(&self, index: &mut AttachmentIndex) -> Result<(), LomoError> {
        for item in self.with_reader(StoreReader::list_projected_attachment_refs)? {
            let source = if item.is_trashed {
                ReferenceSource::TrashMemo
            } else {
                ReferenceSource::CurrentMemo
            };
            index.observe(&item.relative_path, source, item.memo_id);
        }
        Ok(())
    }

    fn collect_history_refs(&self, index: &mut AttachmentIndex) -> Result<(), LomoError> {
        // `history_attachment_ref` rows were parsed from revision bodies at projection time and
        // carry the same retention-window ranking `list_history_revision_bodies` uses, so media
        // protection reads projected rows instead of re-parsing every in-window body.
        let refs = self.with_reader(|reader| {
            reader.list_history_attachment_refs(DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS)
        })?;
        for reference in &refs {
            index.observe(
                &reference.relative_path,
                ReferenceSource::HistoryVersion,
                format!("{}@r{}", reference.memo_id, reference.revision),
            );
        }
        Ok(())
    }

    fn collect_pending_refs(&self, index: &mut AttachmentIndex) -> Result<(), LomoError> {
        for operation_id in self.intent_journal.pending_ids()? {
            let Some(record) = self.intent_journal.pending_record(&operation_id)? else {
                continue;
            };
            let owner = format!("op:{}", operation_id.as_str());
            for publication in &record.mutations {
                if let Some(projection) = &publication.mutation.projection {
                    for relative_path in &projection.attachment_paths {
                        index.observe(
                            relative_path,
                            ReferenceSource::PendingOperation,
                            owner.clone(),
                        );
                    }
                }
                if let Some(history) = &publication.history {
                    for relative_path in project_content_facts(&history.content)?.attachment_paths {
                        index.observe(
                            &relative_path,
                            ReferenceSource::PendingOperation,
                            owner.clone(),
                        );
                    }
                }
            }
            for file in &record.files {
                if let PlannedFile::ArtifactWrite { path, source } = file {
                    index.observe(
                        path.as_str(),
                        ReferenceSource::PendingOperation,
                        owner.clone(),
                    );
                    index.digests.insert(source.digest().as_str().to_owned());
                }
            }
        }
        Ok(())
    }

    fn collect_draft_refs(
        &self,
        index: &mut AttachmentIndex,
        external_drafts: &[GuardedDraftBody],
    ) -> Result<(), LomoError> {
        let evidence = self.draft_store.list_draft_evidence()?;
        let mut cache = self
            .draft_ref_cache
            .lock()
            .map_err(|error| storage("draft_ref_cache_poisoned", error.to_string()))?;
        let mut seen = BTreeSet::new();
        for file in &evidence {
            cache.stats.files_seen += 1;
            seen.insert(file.name.clone());
            if let Some(cached) = cache
                .entries
                .get(&file.name)
                .filter(|cached| cached.evidence == *file)
            {
                let owner = format!("draft:{}", cached.operation_id.as_str());
                for relative_path in &cached.attachment_paths {
                    index.observe(relative_path, ReferenceSource::Draft, owner.clone());
                }
                cache.stats.token_hits += 1;
                continue;
            }
            let (observed, draft) = self.draft_store.read_draft_body(&file.name)?;
            let attachment_paths = project_content_facts(&draft.draft_content)?.attachment_paths;
            let owner = format!("draft:{}", draft.operation_id.as_str());
            for relative_path in &attachment_paths {
                index.observe(relative_path, ReferenceSource::Draft, owner.clone());
            }
            cache.stats.decoded += 1;
            cache.entries.insert(
                file.name.clone(),
                CachedDraftRefs {
                    evidence: observed,
                    operation_id: draft.operation_id,
                    attachment_paths,
                },
            );
        }
        cache.entries.retain(|name, _cached| seen.contains(name));
        drop(cache);
        for draft in external_drafts {
            let owner = format!("draft:ext:{}", draft.owner_id);
            for relative_path in project_content_facts(&draft.content)?.attachment_paths {
                index.observe(&relative_path, ReferenceSource::Draft, owner.clone());
            }
        }
        Ok(())
    }

    fn collect_stage_lease_refs(&self, index: &mut AttachmentIndex) -> Result<(), LomoError> {
        let stage_dir = lomo_media::stage_directory(&self.config.media_stage_root);
        let ledger = StageLedger::load(&stage_dir)?;
        for record in ledger.records() {
            if record.leases.is_empty() {
                continue;
            }
            index.digests.insert(record.digest.as_str().to_owned());
            for lease in &record.leases {
                index.observe(
                    &record.suggested_final_relative_path,
                    ReferenceSource::StageLease,
                    format!("lease:{:?}:{}", lease.owner_kind, lease.owner_id),
                );
            }
        }
        Ok(())
    }
}
