//! Frozen transaction plans are private recovery authority; portable identity lives in .lomo.

use std::path::{Path, PathBuf};

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::{DocumentPublication, ProjectionClock, SafProjectionCommitResult};
use lomo_workspace::{
    LomoPayload, LomoRecordKind, MemoId, SourceFingerprint, decode_record, encode_record,
};
use serde::{Deserialize, Serialize};

use crate::{
    error::{conflict, corruption, storage},
    private_io::{read_optional, write_atomic},
    transaction::PlannedFile,
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum IntentStatus {
    Pending,
    Committed(SafProjectionCommitResult),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OperationIntentRecord {
    pub schema: u32,
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub path: RelativeWorkspacePath,
    pub payload_digest: String,
    pub status: IntentStatus,
    pub created_at_ms: i64,
    pub clock_before: ProjectionClock,
    pub(crate) files: Vec<PlannedFile>,
    pub(crate) mutations: Vec<DocumentPublication>,
}

impl OperationIntentRecord {
    fn validate(&self) -> Result<(), LomoError> {
        if self.schema != 1 || self.files.is_empty() || self.mutations.is_empty() {
            return Err(corruption(
                "invalid_operation_plan",
                "unsupported or incomplete frozen transaction",
            ));
        }
        SourceFingerprint::parse(&self.payload_digest)?;
        if self
            .mutations
            .iter()
            .any(|mutation| mutation.mutation.memo_id != self.memo_id.as_str())
        {
            return Err(corruption(
                "invalid_operation_plan",
                "projection targets another memo",
            ));
        }
        for publication in &self.mutations {
            publication.validate()?;
        }
        Ok(())
    }
}

#[derive(Debug)]
pub struct IntentJournal {
    intents_dir: PathBuf,
}

impl IntentJournal {
    /// Opens the private, durable intent directory.
    ///
    /// # Errors
    /// Propagates directory creation failure.
    pub fn new(state_dir: &Path) -> Result<Self, LomoError> {
        let intents_dir = state_dir.join("intents");
        std::fs::create_dir_all(&intents_dir)
            .map_err(|error| storage("intents_directory_failed", error.to_string()))?;
        Ok(Self { intents_dir })
    }

    /// Reads and validates one checksummed transaction plan.
    ///
    /// # Errors
    /// Corrupt, unsupported or incorrectly named records fail closed.
    pub fn lookup(
        &self,
        operation_id: &OperationId,
    ) -> Result<Option<OperationIntentRecord>, LomoError> {
        let path = self
            .intents_dir
            .join(format!("{}.rec", operation_id.as_str()));
        let Some(bytes) = read_optional(&path)? else {
            return Ok(None);
        };
        let envelope = decode_record(&bytes)?;
        if envelope.payload.kind != LomoRecordKind::Operation
            || envelope.payload.record_id != operation_id.as_str()
        {
            return Err(corruption(
                "invalid_operation_plan",
                "intent envelope does not match its operation",
            ));
        }
        let record: OperationIntentRecord = serde_json::from_str(&envelope.payload.body_json)
            .map_err(|error| corruption("invalid_operation_plan", error.to_string()))?;
        record.validate()?;
        if &record.operation_id != operation_id {
            return Err(corruption(
                "invalid_operation_plan",
                "intent operation does not match its filename",
            ));
        }
        Ok(Some(record))
    }

    /// Durably reserves a complete plan before its first workspace write.
    ///
    /// # Errors
    /// An operation ID cannot be assigned another payload or plan.
    pub fn record_pending(&self, record: &OperationIntentRecord) -> Result<(), LomoError> {
        if let Some(existing) = self.lookup(&record.operation_id)? {
            if existing != *record {
                return Err(conflict(
                    "operation_payload_mismatch",
                    "operation already owns a different frozen plan",
                ));
            }
            return Ok(());
        }
        self.save(record)
    }

    /// Atomically publishes a receipt after all physical writes and projection commits succeed.
    ///
    /// # Errors
    /// Missing plans, corrupt data and private I/O failures are surfaced.
    pub fn mark_committed(
        &self,
        operation_id: &OperationId,
        result: SafProjectionCommitResult,
    ) -> Result<(), LomoError> {
        let mut record = self.lookup(operation_id)?.ok_or_else(|| {
            corruption(
                "operation_plan_missing",
                "cannot commit an operation with no frozen plan",
            )
        })?;
        record.status = IntentStatus::Committed(result);
        self.save(&record)
    }

    fn records(&self) -> Result<Vec<OperationIntentRecord>, LomoError> {
        let mut records = Vec::new();
        for entry in std::fs::read_dir(&self.intents_dir)
            .map_err(|error| storage("intent_list_failed", error.to_string()))?
        {
            let path = entry
                .map_err(|error| storage("intent_list_failed", error.to_string()))?
                .path();
            if path.extension().is_none_or(|extension| extension != "rec") {
                continue;
            }
            let stem = path
                .file_stem()
                .and_then(|stem| stem.to_str())
                .ok_or_else(|| {
                    corruption("invalid_operation_plan", "intent filename is not UTF-8")
                })?;
            let record = self.lookup(&OperationId::parse(stem)?)?.ok_or_else(|| {
                corruption(
                    "operation_plan_missing",
                    "intent disappeared during recovery",
                )
            })?;
            records.push(record);
        }
        records.sort_by(|a, b| {
            (a.created_at_ms, &a.operation_id).cmp(&(b.created_at_ms, &b.operation_id))
        });
        Ok(records)
    }

    pub(crate) fn pending(&self) -> Result<Vec<OperationIntentRecord>, LomoError> {
        Ok(self
            .records()?
            .into_iter()
            .filter(|record| matches!(record.status, IntentStatus::Pending))
            .collect())
    }

    pub(crate) fn clock_floor(&self) -> Result<ProjectionClock, LomoError> {
        let mut floor = ProjectionClock::default();
        for record in self.records()? {
            let count = u64::try_from(record.mutations.len())
                .map_err(|error| corruption("invalid_operation_plan", error.to_string()))?;
            floor = floor.max(record.clock_before.advance(count)?);
            if let IntentStatus::Committed(receipt) = record.status {
                floor = floor.max(ProjectionClock {
                    core_revision: receipt.core_revision,
                    event_sequence: receipt.event_sequence,
                });
            }
        }
        Ok(floor)
    }

    fn save(&self, record: &OperationIntentRecord) -> Result<(), LomoError> {
        record.validate()?;
        let body_json = serde_json::to_string(record)
            .map_err(|error| corruption("intent_encode_failed", error.to_string()))?;
        let bytes = encode_record(&LomoPayload {
            kind: LomoRecordKind::Operation,
            record_id: record.operation_id.as_str().to_owned(),
            body_json,
        })?;
        write_atomic(
            &self
                .intents_dir
                .join(format!("{}.rec", record.operation_id.as_str())),
            &bytes,
        )
    }
}
