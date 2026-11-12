//! Application transactions replay immutable before/after bytes, never rerun append planning.

use lomo_core::{
    DocumentKind, DocumentMetadata, ExpectedFingerprint, LomoError, OperationId,
    RelativeWorkspacePath, StagedArtifactSource,
};
use lomo_store::{DocumentPublication, SafProjectionCommitResult};
use lomo_workspace::{MemoId, SourceFingerprint};
use serde::{Deserialize, Serialize};

use crate::{
    error::{conflict, corruption, expired, validation},
    intent::{JournalEntry, OperationIntentRecord},
    session::WorkspaceSession,
    workspace_io::{FileSnapshot, WorkspaceIo, epoch_millis},
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(tag = "action", deny_unknown_fields)]
pub enum PlannedFile {
    Write {
        path: RelativeWorkspacePath,
        before: Option<Vec<u8>>,
        after: Vec<u8>,
    },
    Delete {
        path: RelativeWorkspacePath,
        before: Vec<u8>,
    },
    /// Media identity plus a recoverable staged source. The bytes never enter the plan: the
    /// executor streams the retained source to `path`, re-verifying digest and length.
    ArtifactWrite {
        path: RelativeWorkspacePath,
        source: StagedArtifactSource,
    },
}

/// What the workspace currently holds for a planned target. Artifact plans observe evidence
/// only, so media bytes never route through recovery memory.
pub enum CurrentState {
    Bytes(Option<FileSnapshot>),
    Evidence(Option<DocumentMetadata>),
    /// Create plans delegate their conflict and replay arbitration to the write's own platform
    /// observation: `before` is absent, so the strongest baseline the write can cite is absence
    /// plus the artifact digest — exactly what `ExpectedFingerprint::Absent` enforces inside the
    /// fused write observation. Re-reading the target here would only duplicate that one check.
    Unobserved,
}

impl PlannedFile {
    pub fn new(path: RelativeWorkspacePath, before: Option<&FileSnapshot>, after: Vec<u8>) -> Self {
        Self::Write {
            path,
            before: before.map(|snapshot| snapshot.bytes.clone()),
            after,
        }
    }

    pub fn delete(path: RelativeWorkspacePath, before: &FileSnapshot) -> Self {
        Self::Delete {
            path,
            before: before.bytes.clone(),
        }
    }

    #[must_use]
    pub const fn path(&self) -> &RelativeWorkspacePath {
        match self {
            Self::Write { path, .. }
            | Self::Delete { path, .. }
            | Self::ArtifactWrite { path, .. } => path,
        }
    }

    pub fn before(&self) -> Option<&[u8]> {
        match self {
            Self::Write { before, .. } => before.as_deref(),
            Self::Delete { before, .. } => Some(before),
            Self::ArtifactWrite { .. } => None,
        }
    }

    pub fn after(&self) -> Option<&[u8]> {
        match self {
            Self::Write { after, .. } => Some(after),
            Self::Delete { .. } | Self::ArtifactWrite { .. } => None,
        }
    }

    pub(crate) fn observe(&self, io: &WorkspaceIo<'_>) -> Result<CurrentState, LomoError> {
        match self {
            Self::Write { before: None, .. } => Ok(CurrentState::Unobserved),
            Self::Write { .. } | Self::Delete { .. } => {
                Ok(CurrentState::Bytes(io.read(self.path())?))
            }
            Self::ArtifactWrite { path, .. } => Ok(CurrentState::Evidence(io.stat(path)?)),
        }
    }

    pub(crate) fn check_current(
        &self,
        current: &CurrentState,
        issued: bool,
    ) -> Result<(), LomoError> {
        match (self, current) {
            (Self::Write { before, after, .. }, CurrentState::Bytes(snapshot)) => {
                let bytes = snapshot.as_ref().map(|snapshot| snapshot.bytes.as_slice());
                if bytes == before.as_deref() || bytes == Some(after.as_slice()) {
                    Ok(())
                } else {
                    Err(conflict(
                        "stale_transaction_baseline",
                        "disk differs from the frozen baseline and witnessed result",
                    ))
                }
            }
            (Self::Delete { before, .. }, CurrentState::Bytes(snapshot)) => {
                let bytes = snapshot.as_ref().map(|snapshot| snapshot.bytes.as_slice());
                if bytes == Some(before.as_slice()) || (bytes.is_none() && issued) {
                    Ok(())
                } else {
                    Err(conflict(
                        "stale_transaction_baseline",
                        "disk differs from the frozen baseline and witnessed result",
                    ))
                }
            }
            (Self::ArtifactWrite { source, .. }, CurrentState::Evidence(observed)) => {
                match observed {
                    // Absent target means the publish never landed or was lost; replay streams
                    // the retained source again.
                    None => Ok(()),
                    Some(metadata) if artifact_evidence_matches(metadata, source) => Ok(()),
                    Some(_) => Err(conflict(
                        "stale_transaction_baseline",
                        "disk differs from the frozen baseline and witnessed result",
                    )),
                }
            }
            // The create baseline is delegated to the write's own arbitration: the platform
            // proves absent/identical/foreign inside the fused write observation and answers
            // already-satisfied or postcondition-mismatch from that same evidence.
            (Self::Write { before: None, .. }, CurrentState::Unobserved) => Ok(()),
            (
                Self::Write {
                    before: Some(_), ..
                }
                | Self::Delete { .. },
                CurrentState::Unobserved,
            )
            | (Self::Write { .. } | Self::Delete { .. }, CurrentState::Evidence(_))
            | (Self::ArtifactWrite { .. }, CurrentState::Bytes(_) | CurrentState::Unobserved) => {
                Err(corruption(
                    "invalid_operation_plan",
                    "planned file shape does not match its observation",
                ))
            }
        }
    }

    pub(crate) fn is_satisfied(&self, current: &CurrentState) -> bool {
        match (self, current) {
            (Self::Write { after, .. }, CurrentState::Bytes(snapshot)) => {
                snapshot.as_ref().map(|snapshot| snapshot.bytes.as_slice())
                    == Some(after.as_slice())
            }
            (Self::Delete { .. }, CurrentState::Bytes(snapshot)) => snapshot.is_none(),
            (Self::ArtifactWrite { source, .. }, CurrentState::Evidence(observed)) => observed
                .as_ref()
                .is_some_and(|metadata| artifact_evidence_matches(metadata, source)),
            // An unobserved create cannot know satisfaction locally — the write action itself
            // reports AlreadySatisfied without mutating when the durable goal already holds.
            _ => false,
        }
    }

    pub fn apply(&self, io: &WorkspaceIo<'_>, current: &CurrentState) -> Result<(), LomoError> {
        self.check_current(current, false)?;
        match (self, current) {
            (Self::Write { path, after, .. }, CurrentState::Bytes(snapshot)) => {
                io.write(path, snapshot.as_ref(), after)?;
            }
            (Self::Write { path, after, .. }, CurrentState::Unobserved) => {
                // Absence plus the artifact digest are the entire create baseline: the write
                // action cites ExpectedFingerprint::Absent and the platform arbitration
                // resolves satisfy/conflict/proceed inside its own observation.
                io.write(path, None, after)?;
            }
            (Self::Delete { path, .. }, CurrentState::Bytes(snapshot)) => {
                let snapshot = snapshot.as_ref().ok_or_else(|| {
                    conflict(
                        "stale_transaction_baseline",
                        "a new deletion requires its existing source",
                    )
                })?;
                io.delete(path, snapshot)?;
            }
            (Self::ArtifactWrite { path, source }, CurrentState::Evidence(observed)) => {
                io.write_artifact(
                    path,
                    source,
                    observed
                        .as_ref()
                        .map_or_else(ExpectedFingerprint::absent, |metadata| {
                            ExpectedFingerprint::matching(metadata.evidence().clone())
                        }),
                )?;
            }
            _ => {
                return Err(corruption(
                    "invalid_operation_plan",
                    "planned file shape does not match its observation",
                ));
            }
        }
        Ok(())
    }
}

fn artifact_evidence_matches(metadata: &DocumentMetadata, source: &StagedArtifactSource) -> bool {
    metadata.kind() == DocumentKind::File
        && metadata.evidence().length() == source.length()
        && metadata.evidence().verified_digest() == Some(source.digest())
}

pub struct TransactionInput {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub path: RelativeWorkspacePath,
    pub payload_digest: String,
    pub files: Vec<PlannedFile>,
    pub mutations: Vec<DocumentPublication>,
    /// Private staging leases released once the bytes reached the workspace.
    pub staging: Vec<lomo_media::PromotePlan>,
}

pub fn payload_digest(request: &impl Serialize) -> Result<String, LomoError> {
    let bytes = serde_json::to_vec(request)
        .map_err(|error| validation("invalid_request", error.to_string()))?;
    Ok(SourceFingerprint::of_bytes(&bytes).as_str().to_owned())
}

impl WorkspaceSession {
    pub(crate) fn replay(
        &self,
        held: &crate::write_behind::Exclusive<'_>,
        operation_id: &OperationId,
        digest: &str,
    ) -> Result<Option<SafProjectionCommitResult>, LomoError> {
        match self.intent_journal.lookup(operation_id)? {
            None => {
                // The committed-operation index is the terminal witness once the
                // journal's bounded retired window and the identity record's retained
                // tail have both evicted this id: the operation was consumed and must
                // expire here — before any planning or file mutation — instead of
                // re-entering as a fresh command.
                if self.with_store(|store| store.operation_was_committed(operation_id.as_str()))? {
                    return Err(expired(
                        "operation_expired",
                        "operation was committed before the retained witness window and is not re-executed",
                    ));
                }
                Ok(None)
            }
            Some(JournalEntry::Committed(record)) => {
                if record.payload_digest != digest {
                    return Err(conflict(
                        "operation_payload_mismatch",
                        "operation ID already owns another payload",
                    ));
                }
                let mut receipt = record.receipt;
                receipt.idempotent_replay = true;
                Ok(Some(receipt))
            }
            Some(JournalEntry::Pending(record)) => {
                if record.payload_digest != digest {
                    return Err(conflict(
                        "operation_payload_mismatch",
                        "operation ID already owns another payload",
                    ));
                }
                if let Some(receipt) = self.write_behind.receipt(operation_id, digest)? {
                    return Ok(Some(receipt));
                }
                self.write_behind.drain_held(held)?;
                self.finish_transaction(&record).map(Some)
            }
            Some(JournalEntry::Retired) => Err(expired(
                "operation_expired",
                "operation was retired by a closed epoch and is not re-executed",
            )),
        }
    }

    /// Commits a frozen intent: durable journal record, then projection publication, then the
    /// workspace bytes converge through the write-behind applier in submission order.
    ///
    /// The caller holds workspace exclusion. Baselines come from the committed journal view;
    /// admission drains an incompatible or full older batch before freezing this operation.
    pub(crate) fn commit_transaction(
        &self,
        held: &crate::write_behind::Exclusive<'_>,
        input: TransactionInput,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        self.require_verified_projection()?;
        let clock_before = self
            .with_store(|store| Ok(store.projection_clock()))?
            .max(self.intent_journal.clock_floor()?);
        self.with_store_mut(|store| store.restore_clock_floor(clock_before))?;
        let record = OperationIntentRecord {
            operation_id: input.operation_id,
            memo_id: input.memo_id,
            path: input.path,
            payload_digest: input.payload_digest,
            started_files: 0,
            created_at_ms: epoch_millis()?,
            clock_before,
            files: input.files,
            mutations: input.mutations,
        };
        self.write_behind.prepare_submission(held, &record)?;
        self.intent_journal.record_pending(&record)?;
        for file in &record.files {
            if let PlannedFile::Write { path, after, .. } = file
                && path == &record.path
            {
                crate::private_io::remember_baseline(
                    &lomo_workspace::SessionStateLayout::for_state_dir(&self.config.state_dir)
                        .baselines,
                    after,
                )?;
            }
        }
        let receipt = self.publish_intent(&record)?;
        // Attachment bytes are read directly by presentation (image loaders, players), never
        // through the projection, so a publication naming them waits for their convergence.
        let readable_now = record
            .files
            .iter()
            .any(|file| matches!(file, PlannedFile::ArtifactWrite { .. }));
        self.write_behind
            .submit(crate::write_behind::QueuedIntent {
                record,
                receipt: receipt.clone(),
                staging: input.staging,
            })?;
        if readable_now {
            // behavior-contract: silent-result-ok: the commit point is the journal plus the
            // publication; a convergence failure is recorded as the queue stall and surfaces to
            // the next exclusive caller or settle, exactly like a background replay failure.
            drop(self.write_behind.drain_held(held));
        }
        Ok(receipt)
    }

    fn publish_intent(
        &self,
        record: &OperationIntentRecord,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        let (first, rest) = record.mutations.split_first().ok_or_else(|| {
            corruption(
                "invalid_operation_plan",
                "transaction has no projection publication",
            )
        })?;
        let mut receipt = self.with_store_mut(|store| store.publish_document(first))?;
        for mutation in rest {
            receipt = self.with_store_mut(|store| store.publish_document(mutation))?;
        }
        Ok(receipt)
    }

    /// Finishes frozen operations. Layout authority is checked by `apply_transaction_files`, the
    /// only step here that touches workspace bytes, so an empty journal costs no workspace I/O.
    pub(crate) fn recover_pending(
        &self,
        held: &crate::write_behind::Exclusive<'_>,
    ) -> Result<(), LomoError> {
        let owned = self.write_behind.owned_ids()?;
        let ids = self.intent_journal.pending_ids(&owned)?;
        if !ids.is_empty() {
            self.write_behind.drain_held(held)?;
        }
        for id in ids {
            if let Some(record) = self.intent_journal.pending_record(&id)? {
                self.finish_transaction(&record)?;
            }
        }
        Ok(())
    }

    pub(crate) fn recover_files_for_mount(&self) -> Result<Vec<OperationId>, LomoError> {
        let ids = self
            .intent_journal
            .pending_ids(&std::collections::BTreeSet::new())?;
        let mut remaining = ids.as_slice();
        while !remaining.is_empty() {
            let applied = crate::write_batch::apply_pending_prefix(
                &self.io(),
                &self.intent_journal,
                remaining,
            )?;
            remaining = remaining.get(applied..).ok_or_else(|| {
                corruption(
                    "invalid_edit_batch",
                    "recovery prefix exceeds pending operations",
                )
            })?;
        }
        Ok(ids)
    }

    pub(crate) fn acknowledge_mounted_operations(
        &self,
        ids: &[OperationId],
    ) -> Result<(), LomoError> {
        let mut records = Vec::new();
        for id in ids {
            if let Some(record) = self.intent_journal.pending_record(id)? {
                records.push(record);
            }
        }
        let mut terminal_memos = std::collections::BTreeMap::new();
        let mut terminal_documents = std::collections::BTreeMap::new();
        for record in &records {
            if crate::write_batch::is_document_edit(record) {
                for publication in &record.mutations {
                    let projection = publication.mutation.projection.as_ref().ok_or_else(|| {
                        corruption("invalid_edit_batch", "edit projection is missing")
                    })?;
                    terminal_memos.insert(publication.mutation.memo_id.as_str(), publication);
                    terminal_documents
                        .insert(record.path.as_str(), projection.file_fingerprint.as_str());
                }
            }
        }
        for record in &records {
            let mut clock = record.clock_before;
            let mut receipt = None;
            for publication in &record.mutations {
                clock = clock.advance(1)?;
                let evidence = if crate::write_batch::is_document_edit(record) {
                    lomo_store::RebuiltPublicationEvidence::EditChain {
                        terminal_memo: terminal_memos
                            .get(publication.mutation.memo_id.as_str())
                            .ok_or_else(|| {
                                corruption("invalid_edit_batch", "terminal memo is missing")
                            })?,
                        document_fingerprint: terminal_documents
                            .get(record.path.as_str())
                            .ok_or_else(|| {
                                corruption("invalid_edit_batch", "terminal source is missing")
                            })?,
                    }
                } else {
                    lomo_store::RebuiltPublicationEvidence::Current
                };
                receipt = Some(self.with_store_mut(|store| {
                    store.acknowledge_rebuilt_publication(publication, clock, evidence)
                })?);
            }
            let receipt = receipt.ok_or_else(|| {
                corruption("invalid_operation_plan", "empty recovered publication")
            })?;
            self.intent_journal
                .mark_committed(&record.operation_id, receipt)?;
            crate::media_plan::release_committed_artifacts(&record.operation_id, &record.files);
        }
        Ok(())
    }

    fn apply_transaction_files(&self, record: &OperationIntentRecord) -> Result<(), LomoError> {
        crate::write_behind::apply_intent_files(&self.io(), &self.intent_journal, record)
    }

    /// Settles an intent left pending by an earlier session: its publication was never made in
    /// this projection, so bytes are applied first and the publication follows.
    fn finish_transaction(
        &self,
        record: &OperationIntentRecord,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        self.require_verified_projection()?;
        self.apply_transaction_files(record)?;
        let receipt = self.publish_intent(record)?;
        self.intent_journal
            .mark_committed(&record.operation_id, receipt.clone())?;
        crate::media_plan::release_committed_artifacts(&record.operation_id, &record.files);
        Ok(receipt)
    }
}
