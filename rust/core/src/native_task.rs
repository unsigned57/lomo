//! Actor-external native task dispatch fence (stage-5 P5-02 host slice).
//!
//! Long network-style work runs on a bounded worker pool outside the single-writer actor.
//! Completions carry job/task/attempt/`dispatch_generation` fences; the actor rejects stale
//! results. Secrets are resolved only via ephemeral leases — never embedded in journal bytes.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::secret::{SecretLeaseId, SharedSecretVault};
use crate::{JobId, LomoError};

/// Maximum concurrent native worker threads for one engine generation.
pub const MAX_NATIVE_WORKERS: usize = 4;

/// Maximum queued native tasks before dispatch fails closed.
pub const MAX_NATIVE_QUEUE: usize = 64;

/// Kind of pending durable effect for a job (journal-safe; no secrets).
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PendingEffect {
    /// Platform SAF/Direct batch (existing path).
    #[default]
    PlatformBatch,
    /// Actor-external native task (network / sync adapter work).
    NativeTask {
        task_kind: String,
        /// Opaque request payload for the executor (must not contain secrets).
        request_json: String,
        attempt: u32,
        dispatch_generation: u64,
        /// Opaque lease id only — never secret bytes.
        #[serde(default)]
        secret_lease_id: Option<SecretLeaseId>,
    },
    /// Durable conflict wait (session authority outside the job journal).
    BlockedByConflict,
    /// Terminal success effect marker (`result_json` on the job record).
    Done,
}

/// Request dispatched to a bounded external worker.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct NativeTaskDispatch {
    pub job_id: JobId,
    pub task_kind: String,
    pub request_json: String,
    pub attempt: u32,
    pub dispatch_generation: u64,
    pub secret_lease_id: Option<SecretLeaseId>,
}

/// Completion returned from an external worker (fence fields mandatory).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct NativeTaskCompletion {
    pub job_id: JobId,
    pub attempt: u32,
    pub dispatch_generation: u64,
    pub outcome: NativeTaskOutcome,
}

/// Observable worker outcome (no secret material).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum NativeTaskOutcome {
    Success { result_json: String },
    Failed { error: LomoError },
    Cancelled,
}

/// Host-side executor invoked on the worker thread (no Tokio in `lomo-core`).
pub trait NativeTaskExecutor: Send + Sync + 'static {
    /// Executes one native task. Must not block the engine actor.
    ///
    /// When a lease id is present, resolve secrets only through the vault for the duration of the
    /// call; never return secret bytes in [`NativeTaskOutcome`].
    fn execute(
        &self,
        dispatch: &NativeTaskDispatch,
        vault: &SharedSecretVault,
    ) -> NativeTaskOutcome;
}

/// Bounded external worker pool with a completion channel back to the actor.
pub struct NativeTaskWorkerPool {
    shutdown: Arc<AtomicBool>,
    queue_tx: SyncSender<NativeTaskDispatch>,
    workers: Vec<JoinHandle<()>>,
    dispatch_counter: AtomicU64,
}

/// Host-only attachment for dark-build / contract tests: bounded workers + vault.
///
/// Production DI cutover is deferred (P5-13). This never registers Kotlin `WorkManager` or `BoltFFI`
/// production paths; it only proves actor-external execution and completion fences.
#[derive(Clone)]
pub struct NativeWorkerAttach {
    pub executor: Arc<dyn NativeTaskExecutor>,
    pub vault: SharedSecretVault,
    pub worker_count: usize,
    pub queue_capacity: usize,
}

impl std::fmt::Debug for NativeWorkerAttach {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("NativeWorkerAttach")
            .field("worker_count", &self.worker_count)
            .field("queue_capacity", &self.queue_capacity)
            .finish_non_exhaustive()
    }
}

impl NativeTaskWorkerPool {
    /// Starts a bounded pool that posts completions to `completion_tx`.
    ///
    /// # Errors
    ///
    /// Storage when a worker thread cannot start.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "owned Arc/channel handles are cloned into worker threads; callers transfer ownership of the pool wiring"
    )]
    pub fn start(
        worker_count: usize,
        queue_capacity: usize,
        executor: Arc<dyn NativeTaskExecutor>,
        vault: SharedSecretVault,
        completion_tx: SyncSender<NativeTaskCompletion>,
    ) -> Result<Self, LomoError> {
        let workers_n = worker_count.clamp(1, MAX_NATIVE_WORKERS);
        let capacity = queue_capacity.clamp(1, MAX_NATIVE_QUEUE);
        let (queue_tx, queue_rx) = mpsc::sync_channel::<NativeTaskDispatch>(capacity);
        let queue_rx = Arc::new(Mutex::new(queue_rx));
        let shutdown = Arc::new(AtomicBool::new(false));
        let mut workers = Vec::with_capacity(workers_n);
        for index in 0..workers_n {
            let rx = Arc::clone(&queue_rx);
            let exec = Arc::clone(&executor);
            let vault_handle = Arc::clone(&vault);
            let completions = completion_tx.clone();
            let stop = Arc::clone(&shutdown);
            let handle = std::thread::Builder::new()
                .name(format!("lomo-native-worker-{index}"))
                .spawn(move || worker_loop(rx, exec, vault_handle, completions, stop))
                .map_err(|error| {
                    LomoError::storage(
                        "native_worker_start_failed",
                        format!("native worker thread could not start: {error}"),
                    )
                })?;
            workers.push(handle);
        }
        Ok(Self {
            shutdown,
            queue_tx,
            workers,
            dispatch_counter: AtomicU64::new(1),
        })
    }

    /// Allocates the next dispatch generation fence for a native task attempt.
    #[must_use]
    pub fn next_dispatch_generation(&self) -> u64 {
        self.dispatch_counter.fetch_add(1, Ordering::Relaxed)
    }

    /// Enqueues a dispatch. Fails closed when the queue is full or the pool is shut down.
    ///
    /// # Errors
    ///
    /// Resource limit when queue is full; internal when the pool is shut down.
    pub fn enqueue(&self, dispatch: NativeTaskDispatch) -> Result<(), LomoError> {
        if self.shutdown.load(Ordering::Acquire) {
            return Err(LomoError::validation(
                "native_worker_shutdown",
                "native worker pool is shut down",
            ));
        }
        self.queue_tx
            .try_send(dispatch)
            .map_err(|error| match error {
                TrySendError::Full(_dispatch) => LomoError::resource_limit(
                    "native_task_queue_full",
                    "bounded native task queue is full",
                ),
                TrySendError::Disconnected(_dispatch) => LomoError::validation(
                    "native_worker_shutdown",
                    "native worker pool is shut down",
                ),
            })
    }

    /// Signals shutdown and joins workers (best-effort; drops undrained queue).
    pub fn shutdown(self) {
        self.shutdown.store(true, Ordering::Release);
        drop(self.queue_tx);
        for worker in self.workers {
            // behavior-contract: silent-result-ok: worker join failure cannot roll back durable state.
            drop(worker.join());
        }
    }
}

#[expect(
    clippy::needless_pass_by_value,
    reason = "worker thread entry takes owned handles moved from the pool starter"
)]
fn worker_loop(
    queue_rx: Arc<Mutex<Receiver<NativeTaskDispatch>>>,
    executor: Arc<dyn NativeTaskExecutor>,
    vault: SharedSecretVault,
    completion_tx: SyncSender<NativeTaskCompletion>,
    shutdown: Arc<AtomicBool>,
) {
    loop {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        let dispatch = {
            let Ok(rx) = queue_rx.lock() else {
                break;
            };
            match rx.recv() {
                Ok(dispatch) => dispatch,
                Err(_disconnected) => break,
            }
        };
        let outcome = executor.execute(&dispatch, &vault);
        let completion = NativeTaskCompletion {
            job_id: dispatch.job_id,
            attempt: dispatch.attempt,
            dispatch_generation: dispatch.dispatch_generation,
            outcome,
        };
        // behavior-contract: silent-result-ok: full/disconnected completion channel means actor
        // shutdown or backpressure; durable state remains authoritative without this result.
        drop(completion_tx.try_send(completion));
    }
}

/// In-memory test executor that records dispatches and returns a fixed outcome.
#[derive(Debug)]
pub struct RecordingNativeExecutor {
    pub dispatches: Mutex<VecDeque<NativeTaskDispatch>>,
    pub outcome: Mutex<NativeTaskOutcome>,
    pub delay: Mutex<Duration>,
}

impl RecordingNativeExecutor {
    /// Builds a recording executor with a fixed outcome.
    #[must_use]
    #[expect(
        clippy::missing_const_for_fn,
        reason = "Mutex::new is not const on the current MSRV"
    )]
    pub fn new(outcome: NativeTaskOutcome) -> Self {
        Self {
            dispatches: Mutex::new(VecDeque::new()),
            outcome: Mutex::new(outcome),
            delay: Mutex::new(Duration::ZERO),
        }
    }

    pub fn set_delay(&self, delay: Duration) {
        if let Ok(mut guard) = self.delay.lock() {
            *guard = delay;
        }
    }

    pub fn set_outcome(&self, outcome: NativeTaskOutcome) {
        if let Ok(mut guard) = self.outcome.lock() {
            *guard = outcome;
        }
    }

    #[must_use]
    pub fn take_dispatches(&self) -> Vec<NativeTaskDispatch> {
        match self.dispatches.lock() {
            Ok(mut queue) => queue.drain(..).collect(),
            Err(_poison) => Vec::new(),
        }
    }
}

impl NativeTaskExecutor for RecordingNativeExecutor {
    fn execute(
        &self,
        dispatch: &NativeTaskDispatch,
        vault: &SharedSecretVault,
    ) -> NativeTaskOutcome {
        if let Ok(mut queue) = self.dispatches.lock() {
            queue.push_back(dispatch.clone());
        }
        if let Some(lease_id) = &dispatch.secret_lease_id
            && let Err(error) = vault.resolve(lease_id)
        {
            return NativeTaskOutcome::Failed { error };
        }
        let delay = self.delay.lock().map_or(Duration::ZERO, |d| *d);
        if !delay.is_zero() {
            std::thread::sleep(delay);
        }
        match self.outcome.lock() {
            Ok(outcome) => outcome.clone(),
            Err(_poison) => NativeTaskOutcome::Failed {
                error: LomoError::internal(
                    "native_executor_lock_poisoned",
                    "recording executor outcome lock poisoned",
                ),
            },
        }
    }
}
