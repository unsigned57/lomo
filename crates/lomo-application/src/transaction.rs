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

    fn observe(&self, io: &WorkspaceIo<'_>) -> Result<CurrentState, LomoError> {
        match self {
            Self::Write { .. } | Self::Delete { .. } => {
                Ok(CurrentState::Bytes(io.read(self.path())?))
            }
            Self::ArtifactWrite { path, .. } => Ok(CurrentState::Evidence(io.stat(path)?)),
        }
    }

    fn check_current(&self, current: &CurrentState, issued: bool) -> Result<(), LomoError> {
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
            (Self::Write { .. } | Self::Delete { .. }, CurrentState::Evidence(_))
            | (Self::ArtifactWrite { .. }, CurrentState::Bytes(_)) => Err(corruption(
                "invalid_operation_plan",
                "planned file shape does not match its observation",
            )),
        }
    }

    fn is_satisfied(&self, current: &CurrentState) -> bool {
        match (self, current) {
            (Self::Write { after, .. }, CurrentState::Bytes(snapshot)) => {
                snapshot.as_ref().map(|snapshot| snapshot.bytes.as_slice())
                    == Some(after.as_slice())
            }
            (Self::Delete { .. }, CurrentState::Bytes(snapshot)) => snapshot.is_none(),
            (Self::ArtifactWrite { source, .. }, CurrentState::Evidence(observed)) => observed
                .as_ref()
                .is_some_and(|metadata| artifact_evidence_matches(metadata, source)),
            _ => false,
        }
    }

    pub fn apply(&self, io: &WorkspaceIo<'_>, current: &CurrentState) -> Result<(), LomoError> {
        self.check_current(current, false)?;
        match (self, current) {
            (Self::Write { path, after, .. }, CurrentState::Bytes(snapshot)) => {
                io.write(path, snapshot.as_ref(), after)?;
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
}

pub fn payload_digest(request: &impl Serialize) -> Result<String, LomoError> {
    let bytes = serde_json::to_vec(request)
        .map_err(|error| validation("invalid_request", error.to_string()))?;
    Ok(SourceFingerprint::of_bytes(&bytes).as_str().to_owned())
}

impl WorkspaceSession {
    pub(crate) fn replay(
        &self,
        operation_id: &OperationId,
        digest: &str,
    ) -> Result<Option<SafProjectionCommitResult>, LomoError> {
        match self.intent_journal.lookup(operation_id)? {
            None => Ok(None),
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
                self.finish_transaction(&record).map(Some)
            }
            Some(JournalEntry::Retired) => Err(expired(
                "operation_expired",
                "operation was retired by a closed epoch and is not re-executed",
            )),
        }
    }

    pub(crate) fn commit_transaction(
        &self,
        input: TransactionInput,
    ) -> Result<SafProjectionCommitResult, LomoError> {
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
        self.intent_journal.record_pending(&record)?;
        self.finish_transaction(&record)
    }

    pub(crate) fn recover_pending(&self) -> Result<(), LomoError> {
        crate::record_plan::validate_workspace_layout(&WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        })?;
        for id in self.intent_journal.pending_ids()? {
            let Some(record) = self.intent_journal.pending_record(&id)? else {
                continue;
            };
            self.finish_transaction(&record)?;
        }
        Ok(())
    }

    pub(crate) fn recover_files_for_mount(&self) -> Result<Vec<OperationId>, LomoError> {
        crate::record_plan::validate_workspace_layout(&WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        })?;
        let ids = self.intent_journal.pending_ids()?;
        for id in &ids {
            let Some(record) = self.intent_journal.pending_record(id)? else {
                continue;
            };
            self.apply_transaction_files(&record)?;
        }
        Ok(ids)
    }

    pub(crate) fn acknowledge_mounted_operations(
        &self,
        records: &[OperationId],
    ) -> Result<(), LomoError> {
        for id in records {
            let Some(record) = self.intent_journal.pending_record(id)? else {
                continue;
            };
            let mut clock = record.clock_before;
            let mut receipt = None;
            for publication in &record.mutations {
                clock = clock.advance(1)?;
                receipt = Some(self.with_store_mut(|store| {
                    store.acknowledge_rebuilt_publication(publication, clock)
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
        let io = WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        };
        crate::record_plan::validate_workspace_layout(&io)?;
        let states = record
            .files
            .iter()
            .map(|file| file.observe(&io))
            .collect::<Result<Vec<_>, _>>()?;
        for (index, (file, state)) in record.files.iter().zip(&states).enumerate() {
            file.check_current(state, index < record.started_files)?;
        }
        for (index, (file, state)) in record.files.iter().zip(&states).enumerate() {
            if file.is_satisfied(state) {
                continue;
            }
            self.intent_journal
                .mark_started(&record.operation_id, index + 1)?;
            file.apply(&io, state)?;
        }
        Ok(())
    }

    fn finish_transaction(
        &self,
        record: &OperationIntentRecord,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        self.apply_transaction_files(record)?;
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
        self.intent_journal
            .mark_committed(&record.operation_id, receipt.clone())?;
        crate::media_plan::release_committed_artifacts(&record.operation_id, &record.files);
        Ok(receipt)
    }
}
