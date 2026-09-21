//! Application transactions replay immutable before/after bytes, never rerun append planning.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
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
            Self::Write { path, .. } | Self::Delete { path, .. } => path,
        }
    }

    pub fn before(&self) -> Option<&[u8]> {
        match self {
            Self::Write { before, .. } => before.as_deref(),
            Self::Delete { before, .. } => Some(before),
        }
    }

    pub fn after(&self) -> Option<&[u8]> {
        match self {
            Self::Write { after, .. } => Some(after),
            Self::Delete { .. } => None,
        }
    }

    fn check_current(&self, current: Option<&FileSnapshot>, issued: bool) -> Result<(), LomoError> {
        let bytes = current.map(|snapshot| snapshot.bytes.as_slice());
        if bytes == self.before() {
            return Ok(());
        }
        match self {
            Self::Write { after, .. } if bytes == Some(after.as_slice()) => Ok(()),
            Self::Delete { .. } if bytes.is_none() && issued => Ok(()),
            Self::Write { .. } | Self::Delete { .. } => Err(conflict(
                "stale_transaction_baseline",
                "disk differs from the frozen baseline and witnessed result",
            )),
        }
    }

    pub fn apply(
        &self,
        io: &WorkspaceIo<'_>,
        current: Option<&FileSnapshot>,
    ) -> Result<(), LomoError> {
        self.check_current(current, false)?;
        match self {
            Self::Write { path, after, .. } => {
                io.write(path, current, after)?;
            }
            Self::Delete { path, .. } => {
                let current = current.ok_or_else(|| {
                    conflict(
                        "stale_transaction_baseline",
                        "a new deletion requires its existing source",
                    )
                })?;
                io.delete(path, current)?;
            }
        }
        Ok(())
    }
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
        }
        Ok(())
    }

    fn apply_transaction_files(&self, record: &OperationIntentRecord) -> Result<(), LomoError> {
        let io = WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        };
        crate::record_plan::validate_workspace_layout(&io)?;
        let snapshots = record
            .files
            .iter()
            .map(|file| io.read(file.path()))
            .collect::<Result<Vec<_>, _>>()?;
        for (index, (file, snapshot)) in record.files.iter().zip(&snapshots).enumerate() {
            file.check_current(snapshot.as_ref(), index < record.started_files)?;
        }
        for (index, (file, snapshot)) in record.files.iter().zip(&snapshots).enumerate() {
            if snapshot.as_ref().map(|snapshot| snapshot.bytes.as_slice()) == file.after() {
                continue;
            }
            self.intent_journal
                .mark_started(&record.operation_id, index + 1)?;
            file.apply(&io, snapshot.as_ref())?;
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
        Ok(receipt)
    }
}
