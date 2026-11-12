//! Durable logical edits converge through one bounded, persistent workspace applier.
//! Consecutive edits may plan from already journaled bytes; commands needing physical bytes
//! acquire a settled barrier. Every batch is reconstructible from its original pending records.

use crate::{
    config::WorkspaceSessionConfig,
    error::{conflict, corruption, storage},
    intent::{IntentJournal, OperationIntentRecord},
    lock::TransactionLock,
    transaction::{CurrentState, PlannedFile},
    workspace_io::WorkspaceIo,
};
use lomo_core::{LomoError, OperationId, PlatformActionExecutor, RelativeWorkspacePath};
use lomo_store::SafProjectionCommitResult;
use std::{
    collections::{BTreeSet, VecDeque},
    sync::{Arc, Condvar, Mutex, MutexGuard},
    thread::JoinHandle,
    time::{Duration, Instant},
};

const LOCK_ATTEMPTS: u32 = 50;
const LOCK_RETRY: Duration = Duration::from_millis(20);
const EDIT_QUIET_WINDOW: Duration = Duration::from_millis(4);
const EDIT_MAX_WINDOW: Duration = Duration::from_millis(64);

pub struct QueuedIntent {
    pub record: OperationIntentRecord,
    pub receipt: SafProjectionCommitResult,
    pub staging: Vec<lomo_media::PromotePlan>,
}
struct EnqueuedIntent {
    since: Instant,
    intent: QueuedIntent,
}
enum ReplayState {
    Healthy,
    Unreported(LomoError),
    Reported(LomoError),
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum WorkerLife {
    Serving,
    Closing,
}
struct Queue {
    intents: VecDeque<EnqueuedIntent>,
    worker: Option<JoinHandle<Result<(), LomoError>>>,
    replay: ReplayState,
    life: WorkerLife,
}
struct Shared {
    config: WorkspaceSessionConfig,
    executor: Arc<dyn PlatformActionExecutor>,
    journal: Arc<IntentJournal>,
    gate: Mutex<()>,
    queue: Mutex<Queue>,
    wake: Condvar,
}
pub struct WriteBehind {
    shared: Arc<Shared>,
}
/// Workspace exclusion. `exclusive` settles older writes; `document_edits` retains a
/// compatible committed read view until the common submission boundary checks its budget.
pub struct Exclusive<'a> {
    _lock: TransactionLock,
    _gate: MutexGuard<'a, ()>,
}

pub enum PendingSource {
    Physical,
    Written {
        operation_id: OperationId,
        bytes: Vec<u8>,
    },
    Removed,
}

impl WriteBehind {
    pub fn new(
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
        journal: Arc<IntentJournal>,
    ) -> Self {
        Self {
            shared: Arc::new(Shared {
                config,
                executor,
                journal,
                gate: Mutex::new(()),
                queue: Mutex::new(Queue {
                    intents: VecDeque::new(),
                    worker: None,
                    replay: ReplayState::Healthy,
                    life: WorkerLife::Serving,
                }),
                wake: Condvar::new(),
            }),
        }
    }
    fn lock_workspace(&self) -> Result<Exclusive<'_>, LomoError> {
        let gate = self
            .shared
            .gate
            .lock()
            .map_err(|error| storage("write_gate_poisoned", error.to_string()))?;
        let lock = TransactionLock::acquire(&self.shared.config.runtime_dir)?;
        Ok(Exclusive {
            _lock: lock,
            _gate: gate,
        })
    }
    pub fn exclusive(&self) -> Result<Exclusive<'_>, LomoError> {
        let held = self.lock_workspace()?;
        self.shared.report_failure()?;
        self.shared.drain_for_caller()?;
        Ok(held)
    }
    pub fn document_edits(&self, path: &RelativeWorkspacePath) -> Result<Exclusive<'_>, LomoError> {
        let held = self.lock_workspace()?;
        self.shared.report_failure()?;
        let barrier = {
            let queue = self.shared.lock_queue()?;
            !matches!(queue.replay, ReplayState::Healthy)
                || queue.intents.iter().any(|entry| {
                    entry.intent.record.path != *path
                        || !crate::write_batch::is_document_edit(&entry.intent.record)
                })
        };
        if barrier {
            self.shared.drain_for_caller()?;
        }
        Ok(held)
    }
    /// The bound is checked before the new journal entry is admitted. Flushing an older batch
    /// cannot invalidate the new frozen plan: its before bytes are that older batch's result.
    pub fn prepare_submission(
        &self,
        _held: &Exclusive<'_>,
        record: &OperationIntentRecord,
    ) -> Result<(), LomoError> {
        let flush = {
            let queue = self.shared.lock_queue()?;
            !queue.intents.is_empty()
                && !crate::write_batch::accepts(
                    queue.intents.iter().map(|entry| &entry.intent.record),
                    record,
                )?
        };
        if flush {
            self.shared.drain_for_caller()?;
        }
        Ok(())
    }
    pub fn submit(&self, intent: QueuedIntent) -> Result<(), LomoError> {
        let mut queue = self.shared.lock_queue()?;
        queue.intents.push_back(EnqueuedIntent {
            since: Instant::now(),
            intent,
        });
        if queue.worker.as_ref().is_some_and(JoinHandle::is_finished) {
            let worker = queue
                .worker
                .take()
                .ok_or_else(|| corruption("write_worker_missing", "finished worker disappeared"))?;
            worker.join().map_err(|payload| {
                let message = match (
                    payload.downcast_ref::<&str>(),
                    payload.downcast_ref::<String>(),
                ) {
                    (Some(message), _) => (*message).to_owned(),
                    (_, Some(message)) => message.clone(),
                    (None, None) => {
                        format!("non-text panic payload: {:?}", payload.as_ref().type_id())
                    }
                };
                storage("write_worker_panicked", message)
            })??;
        }
        if queue.worker.is_none() {
            let shared = Arc::clone(&self.shared);
            queue.worker = Some(
                std::thread::Builder::new()
                    .name("lomo-write-behind".to_owned())
                    .spawn(move || shared.run_worker())
                    .map_err(|error| storage("write_behind_spawn_failed", error.to_string()))?,
            );
        }
        drop(queue);
        self.shared.wake.notify_all();
        Ok(())
    }
    pub fn drain_held(&self, _held: &Exclusive<'_>) -> Result<(), LomoError> {
        self.shared.drain_for_caller()
    }
    pub fn settle(&self) -> Result<(), LomoError> {
        drop(self.exclusive()?);
        Ok(())
    }
    pub fn convergence(&self) -> Result<WriteConvergence, LomoError> {
        let queue = self.shared.lock_queue()?;
        Ok(WriteConvergence {
            backlog: queue.intents.len(),
            stalled: match &queue.replay {
                ReplayState::Healthy => None,
                ReplayState::Unreported(error) | ReplayState::Reported(error) => {
                    Some(error.clone())
                }
            },
        })
    }
    pub fn owned_ids(&self) -> Result<BTreeSet<OperationId>, LomoError> {
        Ok(self
            .shared
            .lock_queue()?
            .intents
            .iter()
            .map(|entry| entry.intent.record.operation_id.clone())
            .collect())
    }
    pub fn receipt(
        &self,
        id: &OperationId,
        digest: &str,
    ) -> Result<Option<SafProjectionCommitResult>, LomoError> {
        let queue = self.shared.lock_queue()?;
        let Some(entry) = queue
            .intents
            .iter()
            .find(|entry| entry.intent.record.operation_id == *id)
        else {
            return Ok(None);
        };
        if entry.intent.record.payload_digest != digest {
            return Err(conflict(
                "operation_payload_mismatch",
                "operation ID already owns another payload",
            ));
        }
        let mut receipt = entry.intent.receipt.clone();
        drop(queue);
        receipt.idempotent_replay = true;
        Ok(Some(receipt))
    }
    /// One queue lock observes a coherent source pair even while the applier finishes a batch.
    pub fn sources(
        &self,
        paths: &[RelativeWorkspacePath],
    ) -> Result<Vec<PendingSource>, LomoError> {
        let queue = self.shared.lock_queue()?;
        paths
            .iter()
            .map(|path| {
                let pending = queue.intents.iter().rev().find_map(|entry| {
                    entry
                        .intent
                        .record
                        .files
                        .iter()
                        .find(|file| file.path() == path)
                        .map(|file| (&entry.intent.record.operation_id, file))
                });
                match pending {
                    None => Ok(PendingSource::Physical),
                    Some((operation_id, PlannedFile::Write { after, .. })) => {
                        Ok(PendingSource::Written {
                            operation_id: operation_id.clone(),
                            bytes: after.clone(),
                        })
                    }
                    Some((_, PlannedFile::Delete { .. })) => Ok(PendingSource::Removed),
                    Some((_, PlannedFile::ArtifactWrite { .. })) => Err(storage(
                        "media_not_converged",
                        "staged media must converge before a source read",
                    )),
                }
            })
            .collect()
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WriteConvergence {
    pub backlog: usize,
    pub stalled: Option<LomoError>,
}

impl Drop for WriteBehind {
    fn drop(&mut self) {
        let worker = match self.shared.queue.lock() {
            Ok(mut queue) => {
                queue.life = WorkerLife::Closing;
                queue.worker.take()
            }
            Err(_) => return, // Pending intents remain durable when a poisoned queue cannot close.
        };
        self.shared.wake.notify_all();
        if let Some(worker) = worker {
            // behavior-contract: silent-result-ok: shutdown cannot return an error; every unfinished member remains in the recovery journal
            drop(worker.join());
        }
        if let Ok(gate) = self.shared.gate.lock() {
            if let Ok(lock) = TransactionLock::acquire(&self.shared.config.runtime_dir) {
                // behavior-contract: silent-result-ok: failed final convergence leaves pending records for the next mount
                drop(self.shared.drain());
                drop(lock);
            }
            drop(gate);
        }
    }
}
impl Shared {
    fn lock_queue(&self) -> Result<MutexGuard<'_, Queue>, LomoError> {
        self.queue
            .lock()
            .map_err(|error| storage("write_queue_poisoned", error.to_string()))
    }
    fn report_failure(&self) -> Result<(), LomoError> {
        let mut queue = self.lock_queue()?;
        if let ReplayState::Unreported(error) = &queue.replay {
            let error = error.clone();
            queue.replay = ReplayState::Reported(error.clone());
            drop(queue);
            return Err(error);
        }
        drop(queue);
        Ok(())
    }
    fn drain_for_caller(&self) -> Result<(), LomoError> {
        match self.drain() {
            Ok(()) => Ok(()),
            Err(error) => {
                self.lock_queue()?.replay = ReplayState::Reported(error.clone());
                Err(error)
            }
        }
    }
    fn drain(&self) -> Result<(), LomoError> {
        loop {
            let records = {
                let queue = self.lock_queue()?;
                queue
                    .intents
                    .iter()
                    .map(|entry| entry.intent.record.clone())
                    .collect::<Vec<_>>()
            };
            if records.is_empty() {
                self.lock_queue()?.replay = ReplayState::Healthy;
                return Ok(());
            }
            let applied = crate::write_batch::apply_queued_prefix(
                &WorkspaceIo::physical(&self.config, &self.executor),
                &self.journal,
                &records,
            )?;
            for _ in 0..applied {
                // Keep ownership queued until its durable receipt exists. A failed receipt write
                // must not lose the member whose already-converged bytes recovery will verify.
                let mut queue = self.lock_queue()?;
                let entry = queue.intents.front().ok_or_else(|| {
                    corruption(
                        "write_behind_intent_missing",
                        "applied queue member disappeared",
                    )
                })?;
                self.journal.mark_committed(
                    &entry.intent.record.operation_id,
                    entry.intent.receipt.clone(),
                )?;
                crate::media_plan::release_committed_artifacts(
                    &entry.intent.record.operation_id,
                    &entry.intent.record.files,
                );
                crate::media_plan::discard_private_staging(&entry.intent.staging);
                queue.intents.pop_front();
            }
        }
    }
    fn ready_at(queue: &Queue) -> Result<Instant, LomoError> {
        let first = queue
            .intents
            .front()
            .ok_or_else(|| corruption("write_queue_empty", "empty queue has no deadline"))?;
        let last = queue
            .intents
            .back()
            .ok_or_else(|| corruption("write_queue_empty", "empty queue has no deadline"))?;
        let maximum = first
            .since
            .checked_add(EDIT_MAX_WINDOW)
            .ok_or_else(|| storage("write_clock_overflow", "batch deadline overflow"))?;
        let quiet = last
            .since
            .checked_add(EDIT_QUIET_WINDOW)
            .ok_or_else(|| storage("write_clock_overflow", "batch deadline overflow"))?;
        Ok(maximum.min(quiet))
    }
    fn wait_for_work(&self) -> Result<bool, LomoError> {
        let mut queue = self.lock_queue()?;
        loop {
            let healthy = matches!(queue.replay, ReplayState::Healthy);
            if queue.life == WorkerLife::Closing {
                return Ok(healthy && !queue.intents.is_empty());
            }
            if queue.intents.is_empty() || !healthy {
                queue = self
                    .wake
                    .wait(queue)
                    .map_err(|error| storage("write_queue_poisoned", error.to_string()))?;
                continue;
            }
            let wait = Self::ready_at(&queue)?.saturating_duration_since(Instant::now());
            if wait.is_zero() {
                return Ok(true);
            }
            let (next, _) = self
                .wake
                .wait_timeout(queue, wait)
                .map_err(|error| storage("write_queue_poisoned", error.to_string()))?;
            queue = next;
        }
    }
    fn acquire_worker_lock(&self) -> Result<TransactionLock, LomoError> {
        for _ in 0..LOCK_ATTEMPTS {
            match TransactionLock::acquire(&self.config.runtime_dir) {
                Ok(lock) => return Ok(lock),
                Err(error) if error.code() == "transaction_lock_held" => {
                    std::thread::sleep(LOCK_RETRY);
                }
                Err(error) => return Err(error),
            }
        }
        Err(conflict(
            "transaction_lock_held",
            "another process holds the workspace transaction lock",
        ))
    }
    fn run_worker(self: Arc<Self>) -> Result<(), LomoError> {
        while self.wait_for_work()? {
            let gate = self
                .gate
                .lock()
                .map_err(|error| storage("write_gate_poisoned", error.to_string()))?;
            let ready = {
                let queue = self.lock_queue()?;
                !queue.intents.is_empty()
                    && matches!(queue.replay, ReplayState::Healthy)
                    && (queue.life == WorkerLife::Closing
                        || Instant::now() >= Self::ready_at(&queue)?)
            };
            if !ready {
                drop(gate);
                continue;
            }
            let outcome = match self.acquire_worker_lock() {
                Ok(lock) => {
                    let result = self.drain();
                    drop(lock);
                    result
                }
                Err(error) => Err(error),
            };
            if let Err(error) = outcome {
                self.lock_queue()?.replay = ReplayState::Unreported(error);
            }
            drop(gate);
        }
        Ok(())
    }
}

/// Validates all baselines before any member of a frozen file plan can mutate bytes.
pub fn prepare_files(
    io: &WorkspaceIo<'_>,
    files: &[PlannedFile],
    started_files: usize,
) -> Result<Vec<CurrentState>, LomoError> {
    crate::record_plan::validate_workspace_layout(io)?;
    let states = files
        .iter()
        .map(|file| file.observe(io))
        .collect::<Result<Vec<_>, _>>()?;
    for (index, (file, state)) in files.iter().zip(&states).enumerate() {
        file.check_current(state, index < started_files)?;
    }
    Ok(states)
}
pub fn apply_intent_files(
    io: &WorkspaceIo<'_>,
    journal: &IntentJournal,
    record: &OperationIntentRecord,
) -> Result<(), LomoError> {
    let states = prepare_files(io, &record.files, record.started_files)?;
    for (index, (file, state)) in record.files.iter().zip(&states).enumerate() {
        if file.is_satisfied(state) {
            continue;
        }
        journal.mark_started(&record.operation_id, index + 1)?;
        file.apply(io, state)?;
    }
    Ok(())
}
