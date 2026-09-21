//! Pending metadata is the recovery index. Completed operations retain only identity and receipt.

use crate::{
    error::{conflict, corruption, expired, resource_limit, storage},
    intent_payload::{PayloadRef, PayloadStore, StoredFile},
    private_io::{read_optional, remove_durable, remove_if_present, write_atomic},
    resource::{MAX_TRANSACTION_BYTES, MAX_TRANSACTION_FILES},
    transaction::PlannedFile,
};
use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::{DocumentPublication, ProjectionClock, SafProjectionCommitResult};
use lomo_workspace::{
    LomoPayload, LomoRecordKind, MemoId, SourceFingerprint, decode_record, encode_record,
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::{
    collections::BTreeSet,
    path::{Path, PathBuf},
    sync::Mutex,
};

/// Durable witness that keeps a retired operation from being re-executed as a fresh command.
const LIFECYCLE_SCHEMA: u32 = 1;
/// Retired epochs retained as retry witnesses. Anything older cannot still be in flight.
const MAX_RETIRED_EPOCHS: usize = 2;
/// Upper bound on witnessed retired operations across the retained epochs.
const MAX_RETIRED_OPERATIONS: usize = 4096;
/// Flat journal records that are lifecycle metadata, never legacy operations.
const RESERVED_RECORD_NAMES: [&str; 2] = ["clock", "lifecycle"];

/// Durable admission state of the current operation epoch.
///
/// A retry that names an operation retired by a closed epoch must fail closed instead of
/// re-executing, so the witness outlives the receipt it replaces.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum JournalEpochState {
    /// New operations are admitted into the current epoch.
    Accepting,
    /// No new admission; the epoch is waiting for its pending operations to reach a terminal
    /// state before the retirement witness is written.
    Draining,
    /// The retirement witness is durable; receipts may be deleted and the next epoch opened.
    Retired,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct RetiredEpoch {
    epoch: u64,
    operations: Vec<OperationId>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct JournalLifecycle {
    schema: u32,
    epoch: u64,
    state: JournalEpochState,
    /// Highest epoch whose retirement witness is durable.
    retired_through: u64,
    retired: Vec<RetiredEpoch>,
}

impl Default for JournalLifecycle {
    fn default() -> Self {
        Self {
            schema: LIFECYCLE_SCHEMA,
            epoch: 1,
            state: JournalEpochState::Accepting,
            retired_through: 0,
            retired: Vec::new(),
        }
    }
}

impl JournalLifecycle {
    fn validate(&self) -> Result<(), LomoError> {
        if self.schema != LIFECYCLE_SCHEMA || self.epoch == 0 {
            return Err(corruption(
                "invalid_journal_lifecycle",
                "journal lifecycle violates its identity contract",
            ));
        }
        let witnessed = self.retired.last().map_or(0, |retired| retired.epoch);
        if self.retired_through > self.epoch || self.retired_through != witnessed {
            return Err(corruption(
                "invalid_journal_lifecycle",
                "retired watermark disagrees with the durable witness",
            ));
        }
        Ok(())
    }

    fn is_retired(&self, id: &OperationId) -> bool {
        self.retired
            .iter()
            .any(|retired| retired.operations.iter().any(|known| known == id))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OperationIntentRecord {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub path: RelativeWorkspacePath,
    pub payload_digest: String,
    pub created_at_ms: i64,
    pub clock_before: ProjectionClock,
    pub started_files: usize,
    pub files: Vec<PlannedFile>,
    pub mutations: Vec<DocumentPublication>,
}

impl OperationIntentRecord {
    fn validate(&self) -> Result<(), LomoError> {
        if self.files.is_empty()
            || self.files.len() > MAX_TRANSACTION_FILES
            || self.mutations.is_empty()
            || self.started_files > self.files.len()
        {
            return Err(corruption(
                "invalid_operation_plan",
                "incomplete or over-budget frozen transaction",
            ));
        }
        SourceFingerprint::parse(&self.payload_digest)?;
        let mut paths = BTreeSet::new();
        let mut bytes = 0_u64;
        for file in &self.files {
            if !paths.insert(file.path().as_str()) {
                return Err(corruption(
                    "invalid_operation_plan",
                    "transaction repeats a file path",
                ));
            }
            for data in file.before().into_iter().chain(file.after()) {
                bytes = bytes
                    .checked_add(u64::try_from(data.len()).map_err(|error| {
                        resource_limit("transaction_too_large", error.to_string())
                    })?)
                    .ok_or_else(|| {
                        resource_limit("transaction_too_large", "transaction byte count overflow")
                    })?;
            }
        }
        if bytes > MAX_TRANSACTION_BYTES {
            return Err(resource_limit(
                "transaction_too_large",
                "transaction exceeds its retained byte budget",
            ));
        }
        for publication in &self.mutations {
            if publication.mutation.memo_id != self.memo_id.as_str() {
                return Err(corruption(
                    "invalid_operation_plan",
                    "projection targets another memo",
                ));
            }
            publication.validate()?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommittedOperation {
    schema: u32,
    operation_id: OperationId,
    pub payload_digest: String,
    pub receipt: SafProjectionCommitResult,
}

pub enum JournalEntry {
    Pending(OperationIntentRecord),
    Committed(CommittedOperation),
    /// The operation was committed in a closed epoch whose receipt is already deleted.
    Retired,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PendingOperation {
    schema: u32,
    operation_id: OperationId,
    memo_id: MemoId,
    path: RelativeWorkspacePath,
    payload_digest: String,
    created_at_ms: i64,
    clock_before: ProjectionClock,
    mutation_count: u64,
    started_files: usize,
    files: Vec<StoredFile>,
    mutations: PayloadRef,
}

impl PendingOperation {
    fn references(&self) -> BTreeSet<String> {
        self.files
            .iter()
            .flat_map(StoredFile::references)
            .chain(std::iter::once(&self.mutations))
            .map(|reference| reference.name().to_owned())
            .collect()
    }
    fn clock_after(&self) -> Result<ProjectionClock, LomoError> {
        self.clock_before.advance(self.mutation_count)
    }
}

#[derive(Debug)]
pub struct IntentJournal {
    directory: PathBuf,
    payloads: PayloadStore,
    lifecycle: Mutex<JournalLifecycle>,
}

impl IntentJournal {
    pub fn new(state_dir: &Path) -> Result<Self, LomoError> {
        let journal = Self {
            directory: state_dir.join("intents"),
            payloads: PayloadStore::new(state_dir),
            lifecycle: Mutex::new(JournalLifecycle::default()),
        };
        for name in ["pending", "committed"] {
            std::fs::create_dir_all(journal.directory.join(name))
                .map_err(|error| storage("intents_directory_failed", error.to_string()))?;
        }
        if read_optional(&journal.directory.join("clock.rec"))?.is_none() {
            if !record_ids(&journal.directory.join("committed"))?.is_empty() {
                return Err(corruption(
                    "intent_checkpoint_missing",
                    "completed journal lost its durable clock",
                ));
            }
            journal.save_clock(ProjectionClock::default())?;
        }
        journal.load_lifecycle()?;
        journal.upgrade_flat_records()?;
        // A crash can occur after a receipt is durable but before its pending index is removed.
        // Only pending metadata is inspected here; completed historical payloads are never read.
        for id in record_ids(&journal.directory.join("pending"))? {
            let pending = journal
                .pending_metadata(&id)?
                .ok_or_else(|| corruption("operation_plan_missing", "pending index disappeared"))?;
            let mut floor = pending.clock_after()?;
            if let Some(committed) = journal.committed(&id)? {
                floor = floor.max(receipt_clock(&committed.receipt));
                journal.advance_clock(floor)?;
                remove_durable(&journal.path("pending", &id))?;
                journal.reclaim(&pending.references())?;
            } else {
                journal.advance_clock(floor)?;
            }
        }
        // An epoch that was interrupted by a crash either had not yet published its retirement
        // witness (reopened as Accepting) or did publish it (finished and advanced here).
        journal.resume_interrupted_epoch()?;
        Ok(journal)
    }

    fn lifecycle_snapshot(&self) -> Result<JournalLifecycle, LomoError> {
        self.lifecycle
            .lock()
            .map(|guard| guard.clone())
            .map_err(|_poison| corruption("journal_lifecycle_poisoned", "epoch lock was poisoned"))
    }

    fn load_lifecycle(&self) -> Result<(), LomoError> {
        let path = self.directory.join("lifecycle.rec");
        let lifecycle =
            if let Some(lifecycle) = read_record::<JournalLifecycle>(&path, "lifecycle")? {
                lifecycle
            } else {
                let default = JournalLifecycle::default();
                save_record(&path, "lifecycle", &default)?;
                default
            };
        lifecycle.validate()?;
        *self.lifecycle.lock().map_err(|_poison| {
            corruption("journal_lifecycle_poisoned", "epoch lock was poisoned")
        })? = lifecycle;
        Ok(())
    }

    fn save_lifecycle(&self, lifecycle: &JournalLifecycle) -> Result<(), LomoError> {
        save_record(
            &self.directory.join("lifecycle.rec"),
            "lifecycle",
            lifecycle,
        )?;
        *self.lifecycle.lock().map_err(|_poison| {
            corruption("journal_lifecycle_poisoned", "epoch lock was poisoned")
        })? = lifecycle.clone();
        Ok(())
    }

    /// Completes a crash-interrupted seal without re-executing any retired operation.
    fn resume_interrupted_epoch(&self) -> Result<(), LomoError> {
        let lifecycle = self.lifecycle_snapshot()?;
        match lifecycle.state {
            JournalEpochState::Accepting => Ok(()),
            JournalEpochState::Draining => {
                // No witness was published, so the same epoch reopens for admission.
                self.save_lifecycle(&JournalLifecycle {
                    state: JournalEpochState::Accepting,
                    ..lifecycle
                })
            }
            JournalEpochState::Retired => {
                self.delete_witnessed_receipts(&lifecycle)?;
                self.save_lifecycle(&JournalLifecycle {
                    epoch: lifecycle.epoch + 1,
                    state: JournalEpochState::Accepting,
                    ..lifecycle
                })
            }
        }
    }

    fn delete_witnessed_receipts(&self, lifecycle: &JournalLifecycle) -> Result<(), LomoError> {
        for retired in &lifecycle.retired {
            for id in &retired.operations {
                remove_if_present(&self.path("pending", id))?;
                remove_if_present(&self.path("committed", id))?;
            }
        }
        Ok(())
    }

    /// Closes the current epoch: drains, witnesses retirement durably, then deletes receipts.
    ///
    /// Returns `false` when pending operations still block retirement; the epoch stays
    /// `Draining` and the next open resumes it. A retry of a retired operation never
    /// re-executes: [`JournalEntry::Retired`] makes the caller fail closed with
    /// `operation_expired`.
    pub fn seal(&self) -> Result<bool, LomoError> {
        let lifecycle = self.lifecycle_snapshot()?;
        if lifecycle.state == JournalEpochState::Retired {
            return self.resume_interrupted_epoch().map(|()| true);
        }
        let committed = record_ids(&self.directory.join("committed"))?;
        let pending = record_ids(&self.directory.join("pending"))?;
        if !pending.is_empty() {
            if lifecycle.state != JournalEpochState::Draining {
                self.save_lifecycle(&JournalLifecycle {
                    state: JournalEpochState::Draining,
                    ..lifecycle
                })?;
            }
            return Ok(false);
        }
        let mut retired = lifecycle.retired.clone();
        retired.push(RetiredEpoch {
            epoch: lifecycle.epoch,
            operations: committed,
        });
        while retired.len() > MAX_RETIRED_EPOCHS {
            retired.remove(0);
        }
        let witnessed: usize = retired
            .iter()
            .map(|epoch| epoch.operations.len())
            .sum::<usize>();
        if witnessed > MAX_RETIRED_OPERATIONS {
            return Err(resource_limit(
                "operation_witness_budget_exhausted",
                "retired operation witness exceeded its durable budget; reconcile before sealing",
            ));
        }
        // The witness is durable before any receipt disappears, so a crash here still expires.
        self.save_lifecycle(&JournalLifecycle {
            state: JournalEpochState::Retired,
            retired_through: lifecycle.epoch,
            retired,
            ..lifecycle
        })?;
        self.resume_interrupted_epoch().map(|()| true)
    }

    fn path(&self, directory: &str, id: &OperationId) -> PathBuf {
        self.directory
            .join(directory)
            .join(format!("{}.rec", id.as_str()))
    }

    pub fn lookup(&self, id: &OperationId) -> Result<Option<JournalEntry>, LomoError> {
        if let Some(record) = self.committed(id)? {
            return Ok(Some(JournalEntry::Committed(record)));
        }
        if let Some(record) = self.pending_metadata(id)? {
            return self
                .thaw(record)
                .map(|record| Some(JournalEntry::Pending(record)));
        }
        if self.lifecycle_snapshot()?.is_retired(id) {
            return Ok(Some(JournalEntry::Retired));
        }
        Ok(None)
    }

    fn committed(&self, id: &OperationId) -> Result<Option<CommittedOperation>, LomoError> {
        let record: Option<CommittedOperation> =
            read_record(&self.path("committed", id), id.as_str())?;
        if let Some(record) = &record {
            if record.schema != 2 || record.operation_id != *id {
                return Err(corruption(
                    "invalid_operation_receipt",
                    "receipt identity or schema mismatch",
                ));
            }
            SourceFingerprint::parse(&record.payload_digest)?;
            MemoId::parse(&record.receipt.memo_id)?;
        }
        Ok(record)
    }

    fn pending_metadata(&self, id: &OperationId) -> Result<Option<PendingOperation>, LomoError> {
        let pending: Option<PendingOperation> =
            read_record(&self.path("pending", id), id.as_str())?;
        if let Some(record) = &pending {
            if record.schema != 2
                || record.operation_id != *id
                || record.files.is_empty()
                || record.files.len() > MAX_TRANSACTION_FILES
                || record.started_files > record.files.len()
                || !(1..=128).contains(&record.mutation_count)
            {
                return Err(corruption(
                    "invalid_operation_plan",
                    "pending metadata violates its identity or resource contract",
                ));
            }
            SourceFingerprint::parse(&record.payload_digest)?;
            let bytes = record
                .files
                .iter()
                .flat_map(StoredFile::references)
                .chain(std::iter::once(&record.mutations))
                .try_fold(0_u64, |total, reference| {
                    total.checked_add(reference.length()).ok_or_else(|| {
                        resource_limit("transaction_too_large", "pending payload lengths overflow")
                    })
                })?;
            if bytes > MAX_TRANSACTION_BYTES {
                return Err(resource_limit(
                    "transaction_too_large",
                    "pending payloads exceed the allocation budget",
                ));
            }
        }
        Ok(pending)
    }

    pub fn record_pending(&self, record: &OperationIntentRecord) -> Result<(), LomoError> {
        record.validate()?;
        match self.lookup(&record.operation_id)? {
            Some(JournalEntry::Retired) => {
                return Err(expired(
                    "operation_expired",
                    "operation was retired by a closed epoch and is not re-executed",
                ));
            }
            Some(_) => {
                return Err(conflict(
                    "operation_already_reserved",
                    "replay the existing operation instead of replanning",
                ));
            }
            None => {}
        }
        let lifecycle = self.lifecycle_snapshot()?;
        if lifecycle.state != JournalEpochState::Accepting {
            return Err(conflict(
                "operation_epoch_not_accepting",
                "the operation journal is retiring its epoch and refuses new admissions",
            ));
        }
        let mutations = serde_json::to_vec(&record.mutations)
            .map_err(|error| corruption("intent_encode_failed", error.to_string()))?;
        let pending = PendingOperation {
            schema: 2,
            operation_id: record.operation_id.clone(),
            memo_id: record.memo_id.clone(),
            path: record.path.clone(),
            payload_digest: record.payload_digest.clone(),
            created_at_ms: record.created_at_ms,
            clock_before: record.clock_before,
            mutation_count: u64::try_from(record.mutations.len())
                .map_err(|error| corruption("invalid_operation_plan", error.to_string()))?,
            started_files: record.started_files,
            files: record
                .files
                .iter()
                .map(|file| self.payloads.freeze(file))
                .collect::<Result<_, _>>()?,
            mutations: self.payloads.store(&mutations)?,
        };
        save_record(
            &self.path("pending", &record.operation_id),
            record.operation_id.as_str(),
            &pending,
        )?;
        self.advance_clock(pending.clock_after()?)
    }

    pub fn mark_started(&self, id: &OperationId, started_files: usize) -> Result<(), LomoError> {
        let mut pending = self
            .pending_metadata(id)?
            .ok_or_else(|| corruption("operation_plan_missing", "operation has no pending plan"))?;
        if started_files > pending.files.len() {
            return Err(corruption(
                "invalid_operation_progress",
                "file witness exceeds the frozen plan",
            ));
        }
        pending.started_files = pending.started_files.max(started_files);
        save_record(&self.path("pending", id), id.as_str(), &pending)
    }

    pub fn mark_committed(
        &self,
        id: &OperationId,
        mut receipt: SafProjectionCommitResult,
    ) -> Result<(), LomoError> {
        receipt.idempotent_replay = false;
        if let Some(existing) = self.committed(id)? {
            if existing.receipt != receipt {
                return Err(corruption(
                    "operation_receipt_mismatch",
                    "a completed operation changed its receipt",
                ));
            }
            return Ok(());
        }
        let pending = self.pending_metadata(id)?.ok_or_else(|| {
            corruption(
                "operation_plan_missing",
                "cannot commit without a frozen plan",
            )
        })?;
        self.advance_clock(receipt_clock(&receipt).max(pending.clock_after()?))?;
        let committed = CommittedOperation {
            schema: 2,
            operation_id: id.clone(),
            payload_digest: pending.payload_digest.clone(),
            receipt,
        };
        save_record(&self.path("committed", id), id.as_str(), &committed)?;
        remove_durable(&self.path("pending", id))?;
        self.reclaim(&pending.references())
    }

    /// Bounded metadata enumeration of recoverable operations.
    ///
    /// Returns identities only: bodies are thawed one record at a time by
    /// [`Self::pending_record`] so recovery never holds every frozen payload in memory.
    pub fn pending_ids(&self) -> Result<Vec<OperationId>, LomoError> {
        let mut ids = Vec::new();
        for id in record_ids(&self.directory.join("pending"))? {
            if self.committed(&id)?.is_some() {
                continue;
            }
            let pending = self
                .pending_metadata(&id)?
                .ok_or_else(|| corruption("operation_plan_missing", "pending index disappeared"))?;
            pending.clock_after()?;
            ids.push(pending.operation_id);
        }
        ids.sort_by(|a, b| a.as_str().cmp(b.as_str()));
        Ok(ids)
    }

    /// Thaws exactly one recoverable operation.
    pub fn pending_record(
        &self,
        id: &OperationId,
    ) -> Result<Option<OperationIntentRecord>, LomoError> {
        if self.committed(id)?.is_some() {
            return Ok(None);
        }
        let Some(pending) = self.pending_metadata(id)? else {
            return Ok(None);
        };
        self.thaw(pending).map(Some)
    }

    pub fn clock_floor(&self) -> Result<ProjectionClock, LomoError> {
        read_record(&self.directory.join("clock.rec"), "clock")?.ok_or_else(|| {
            corruption(
                "intent_checkpoint_missing",
                "journal clock checkpoint is missing",
            )
        })
    }

    fn save_clock(&self, clock: ProjectionClock) -> Result<(), LomoError> {
        save_record(&self.directory.join("clock.rec"), "clock", &clock)
    }
    fn advance_clock(&self, clock: ProjectionClock) -> Result<(), LomoError> {
        let before = self.clock_floor()?;
        let after = before.max(clock);
        if before != after {
            self.save_clock(after)?;
        }
        Ok(())
    }

    fn thaw(&self, pending: PendingOperation) -> Result<OperationIntentRecord, LomoError> {
        let mutations: Vec<DocumentPublication> =
            serde_json::from_slice(&self.payloads.load(&pending.mutations)?)
                .map_err(|error| corruption("invalid_operation_plan", error.to_string()))?;
        if u64::try_from(mutations.len())
            .map_err(|error| corruption("invalid_operation_plan", error.to_string()))?
            != pending.mutation_count
        {
            return Err(corruption(
                "invalid_operation_plan",
                "publication count changed",
            ));
        }
        let record = OperationIntentRecord {
            operation_id: pending.operation_id,
            memo_id: pending.memo_id,
            path: pending.path,
            payload_digest: pending.payload_digest,
            created_at_ms: pending.created_at_ms,
            clock_before: pending.clock_before,
            started_files: pending.started_files,
            files: pending
                .files
                .iter()
                .map(|file| self.payloads.thaw(file))
                .collect::<Result<_, _>>()?,
            mutations,
        };
        record.validate()?;
        Ok(record)
    }

    fn reclaim(&self, candidates: &BTreeSet<String>) -> Result<(), LomoError> {
        let mut retained = BTreeSet::new();
        for id in record_ids(&self.directory.join("pending"))? {
            let pending = self.pending_metadata(&id)?.ok_or_else(|| {
                corruption(
                    "operation_plan_missing",
                    "pending index disappeared during collection",
                )
            })?;
            retained.extend(pending.references());
        }
        self.payloads.reclaim(candidates, &retained)
    }

    fn upgrade_flat_records(&self) -> Result<(), LomoError> {
        for id in record_ids(&self.directory)?
            .into_iter()
            .filter(|id| !RESERVED_RECORD_NAMES.contains(&id.as_str()))
        {
            let path = self.directory.join(format!("{}.rec", id.as_str()));
            let legacy: LegacyOperation = read_record(&path, id.as_str())?
                .ok_or_else(|| corruption("operation_plan_missing", "legacy intent disappeared"))?;
            if legacy.schema != 1 || legacy.operation_id != id {
                return Err(corruption(
                    "invalid_operation_plan",
                    "unsupported journal input",
                ));
            }
            if self.lookup(&id)?.is_none() {
                let files = legacy
                    .files
                    .into_iter()
                    .map(LegacyFile::into_plan)
                    .collect::<Result<_, _>>()?;
                let record = OperationIntentRecord {
                    operation_id: legacy.operation_id,
                    memo_id: legacy.memo_id,
                    path: legacy.path,
                    payload_digest: legacy.payload_digest,
                    created_at_ms: legacy.created_at_ms,
                    clock_before: legacy.clock_before,
                    started_files: 0,
                    files,
                    mutations: legacy.mutations,
                };
                self.record_pending(&record)?;
                if let LegacyStatus::Committed(receipt) = legacy.status {
                    self.mark_committed(&id, receipt)?;
                }
            }
            remove_durable(&path)?;
        }
        Ok(())
    }
}

const fn receipt_clock(receipt: &SafProjectionCommitResult) -> ProjectionClock {
    ProjectionClock {
        core_revision: receipt.core_revision,
        event_sequence: receipt.event_sequence,
    }
}

fn record_ids(directory: &Path) -> Result<Vec<OperationId>, LomoError> {
    let mut ids = Vec::new();
    for entry in std::fs::read_dir(directory)
        .map_err(|error| storage("intent_list_failed", error.to_string()))?
    {
        let path = entry
            .map_err(|error| storage("intent_list_failed", error.to_string()))?
            .path();
        if path.extension().is_none_or(|extension| extension != "rec") {
            continue;
        }
        let name = path
            .file_stem()
            .and_then(|name| name.to_str())
            .ok_or_else(|| corruption("invalid_operation_plan", "intent filename is not UTF-8"))?;
        ids.push(OperationId::parse(name)?);
    }
    Ok(ids)
}

fn read_record<T: DeserializeOwned>(path: &Path, id: &str) -> Result<Option<T>, LomoError> {
    let Some(bytes) = read_optional(path)? else {
        return Ok(None);
    };
    let record = decode_record(&bytes)?;
    if record.payload.kind != LomoRecordKind::Operation || record.payload.record_id != id {
        return Err(corruption(
            "invalid_operation_plan",
            "journal envelope identity mismatch",
        ));
    }
    serde_json::from_str(&record.payload.body_json)
        .map(Some)
        .map_err(|error| corruption("invalid_operation_plan", error.to_string()))
}

fn save_record<T: Serialize>(path: &Path, id: &str, record: &T) -> Result<(), LomoError> {
    let body_json = serde_json::to_string(record)
        .map_err(|error| corruption("intent_encode_failed", error.to_string()))?;
    write_atomic(
        path,
        &encode_record(&LomoPayload {
            kind: LomoRecordKind::Operation,
            record_id: id.to_owned(),
            body_json,
        })?,
    )
}

// One-way import of the previous on-disk journal. Files are removed only after their canonical
// pending index/checkpoint/receipt is durable; normal lookup and execution never use this codec.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LegacyOperation {
    schema: u32,
    operation_id: OperationId,
    memo_id: MemoId,
    path: RelativeWorkspacePath,
    payload_digest: String,
    status: LegacyStatus,
    created_at_ms: i64,
    clock_before: ProjectionClock,
    files: Vec<LegacyFile>,
    mutations: Vec<DocumentPublication>,
}
#[derive(Deserialize)]
enum LegacyStatus {
    Pending,
    Committed(SafProjectionCommitResult),
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LegacyFile {
    path: RelativeWorkspacePath,
    before: Option<Vec<u8>>,
    after: Vec<u8>,
    #[serde(default)]
    delete: bool,
}
impl LegacyFile {
    fn into_plan(self) -> Result<PlannedFile, LomoError> {
        if self.delete {
            if !self.after.is_empty() {
                return Err(corruption(
                    "invalid_operation_plan",
                    "legacy deletion retained write bytes",
                ));
            }
            Ok(PlannedFile::Delete {
                path: self.path,
                before: self.before.ok_or_else(|| {
                    corruption("invalid_operation_plan", "legacy deletion has no baseline")
                })?,
            })
        } else {
            Ok(PlannedFile::Write {
                path: self.path,
                before: self.before,
                after: self.after,
            })
        }
    }
}
