//! Global attachment references across active bodies, trash, history, drafts,
//! pending transactions, and the durable media stage ledger.
//!
//! The index is the single reference authority for media garbage collection: an object under
//! `media/` may be reclaimed only when no live or trash body, in-window history revision, conflict
//! draft, frozen transaction, or stage-ledger lease names its canonical relative path or content
//! digest. All facts are recomputed on every call so a sweep that rechecks under the write lock
//! observes references created after candidate enumeration.

use std::collections::BTreeSet;

use lomo_core::LomoError;
use lomo_media::{ReferenceSource, StageLedger};
use lomo_store::{DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS, StoreReader, project_content_facts};
use lomo_workspace::canonical_attachment_path;

use crate::{draft::GuardedDraftBody, session::WorkspaceSession, transaction::PlannedFile};

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

impl WorkspaceSession {
    /// Collects the full protection set in bounded bulk reads.
    ///
    /// Sources: projected `attachment_ref` rows for live and trashed memos, in-window history
    /// revision bodies, pending intent-journal transactions (planned bodies, history writes, and
    /// artifact targets), conflict-evidence drafts, and stage-ledger leases held by drafts,
    /// pending operations, incoming transfers, or committed references.
    ///
    /// # Errors
    ///
    /// Projection, journal, draft, or stage-ledger read failures. A corrupt or incomplete
    /// projection surfaces as an error rather than a silently empty keep-set.
    pub fn attachment_index(&self) -> Result<AttachmentIndex, LomoError> {
        self.attachment_index_guarding(&[])
    }

    /// Collects the protection set, also guarding externally held draft bodies.
    ///
    /// A host that keeps its own draft state outside the Rust `DraftStore` (for example an
    /// editor buffer never persisted as conflict evidence) passes those bodies here so their
    /// attachment references extend the keep-set exactly like internal drafts. The bodies are
    /// used for this computation only and are never persisted.
    ///
    /// # Errors
    ///
    /// Same as [`Self::attachment_index`]; an external body the render owner cannot project
    /// fails the whole collection rather than silently dropping the draft's protection.
    pub fn attachment_index_guarding(
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
        for draft in self.draft_store.list_draft_bodies()? {
            let owner = format!("draft:{}", draft.operation_id.as_str());
            for relative_path in project_content_facts(&draft.draft_content)?.attachment_paths {
                index.observe(&relative_path, ReferenceSource::Draft, owner.clone());
            }
        }
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
