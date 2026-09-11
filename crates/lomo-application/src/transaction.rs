//! Application transactions replay immutable before/after bytes, never rerun append planning.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::{DocumentPublication, SafProjectionCommitResult};
use lomo_workspace::{MemoId, SourceFingerprint};
use serde::{Deserialize, Serialize};

use crate::error::{conflict, corruption, validation};
use crate::intent::{IntentStatus, OperationIntentRecord};
use crate::session::WorkspaceSession;
use crate::workspace_io::{FileSnapshot, WorkspaceIo, epoch_millis};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PlannedFile {
    path: RelativeWorkspacePath,
    before: Option<Vec<u8>>,
    after: Vec<u8>,
    #[serde(default)]
    delete: bool,
}

impl PlannedFile {
    pub fn new(path: RelativeWorkspacePath, before: Option<&FileSnapshot>, after: Vec<u8>) -> Self {
        Self {
            path,
            before: before.map(|snapshot| snapshot.bytes.clone()),
            after,
            delete: false,
        }
    }

    pub fn delete(path: RelativeWorkspacePath, before: &FileSnapshot) -> Self {
        Self {
            path,
            before: Some(before.bytes.clone()),
            after: Vec::new(),
            delete: true,
        }
    }

    #[must_use]
    pub const fn path(&self) -> &RelativeWorkspacePath {
        &self.path
    }

    #[must_use]
    pub const fn is_delete(&self) -> bool {
        self.delete
    }

    #[must_use]
    pub const fn before_bytes(&self) -> Option<&Vec<u8>> {
        self.before.as_ref()
    }

    #[must_use]
    pub const fn after_bytes(&self) -> &Vec<u8> {
        &self.after
    }

    pub fn apply(&self, io: &WorkspaceIo<'_>) -> Result<(), LomoError> {
        let current = io.read(&self.path)?;
        if self.delete {
            let Some(snapshot) = current else {
                return if self.before.is_none() {
                    Ok(())
                } else {
                    Err(conflict(
                        "stale_transaction_baseline",
                        "file changed since the frozen write plan",
                    ))
                };
            };
            if Some(&snapshot.bytes) != self.before.as_ref() {
                return Err(conflict(
                    "stale_transaction_baseline",
                    "file changed since the frozen write plan",
                ));
            }
            io.delete(&self.path, &snapshot)?;
            return Ok(());
        }
        if current
            .as_ref()
            .is_some_and(|snapshot| snapshot.bytes == self.after)
        {
            return Ok(());
        }
        if current.as_ref().map(|snapshot| &snapshot.bytes) != self.before.as_ref() {
            return Err(conflict(
                "stale_transaction_baseline",
                "file changed since the frozen write plan",
            ));
        }
        io.write(&self.path, current.as_ref(), &self.after)?;
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
        let Some(record) = self.intent_journal.lookup(operation_id)? else {
            return Ok(None);
        };
        if record.payload_digest != digest {
            return Err(conflict(
                "operation_payload_mismatch",
                "operation ID already owns another payload",
            ));
        }
        self.finish_transaction(&record).map(Some)
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
            schema: 1,
            operation_id: input.operation_id,
            memo_id: input.memo_id,
            path: input.path,
            payload_digest: input.payload_digest,
            status: IntentStatus::Pending,
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
        for record in self.intent_journal.pending()? {
            self.finish_transaction(&record)?;
        }
        Ok(())
    }

    pub(crate) fn recover_files_for_mount(&self) -> Result<Vec<OperationIntentRecord>, LomoError> {
        crate::record_plan::validate_workspace_layout(&WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        })?;
        let records = self.intent_journal.pending()?;
        for record in &records {
            self.apply_transaction_files(record)?;
        }
        Ok(records)
    }

    pub(crate) fn acknowledge_mounted_operations(
        &self,
        records: &[OperationIntentRecord],
    ) -> Result<(), LomoError> {
        for record in records {
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
            .map(|file| io.read(&file.path))
            .collect::<Result<Vec<_>, _>>()?;
        for (file, snapshot) in record.files.iter().zip(&snapshots) {
            let current = snapshot.as_ref().map(|snapshot| &snapshot.bytes);
            if file.is_delete() {
                if current != file.before_bytes() {
                    return Err(conflict(
                        "stale_transaction_baseline",
                        "disk differs from the frozen transaction baseline and result",
                    ));
                }
                continue;
            }
            if current != Some(file.after_bytes()) && current != file.before_bytes() {
                return Err(conflict(
                    "stale_transaction_baseline",
                    "disk differs from the frozen transaction baseline and result",
                ));
            }
        }
        for (file, snapshot) in record.files.iter().zip(&snapshots) {
            if snapshot
                .as_ref()
                .is_some_and(|snapshot| !file.is_delete() && snapshot.bytes == *file.after_bytes())
            {
                continue;
            }
            file.apply(&io)?;
        }
        Ok(())
    }

    fn finish_transaction(
        &self,
        record: &OperationIntentRecord,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        if let IntentStatus::Committed(receipt) = &record.status {
            let mut replay = receipt.clone();
            replay.idempotent_replay = true;
            return Ok(replay);
        }
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
