use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    ActionId, ActionOutcome, BatchId, CapabilityToken, CoreRevision, DriverAdvance, EventSequence,
    JobDriverContext, JobDriverKind, JobDriverRegistry, JobId, LomoError, NativeTaskCompletion,
    NativeTaskDispatch, NativeTaskOutcome, NativeTaskWorkerPool, NativeWorkerAttach, PageSize,
    PendingEffect, PlatformAction, PlatformActionBatch, PlatformBatchResult, RetryDisposition,
    SecretLeaseId, WorkspaceDescriptor, WorkspaceId,
};

const JOURNAL_MAGIC: &str = "LOMO_ENGINE";
/// Journal envelope schema. v1 journals (platform-only jobs) migrate in-memory on open; unknown
/// schemas fail closed as corruption → callers observe open failure (no clean slate).
const JOURNAL_SCHEMA: u32 = 2;
const JOURNAL_SCHEMA_V1: u32 = 1;
const COMMAND_CAPACITY: usize = 256;
const EVENT_CAPACITY: usize = 256;
const MAX_ACTIVE_JOBS: usize = 64;
const MAX_TERMINAL_JOBS: usize = 256;
const DEFAULT_BOOTSTRAP_DEADLINE: Duration = Duration::from_mins(5);
const MAX_BOOTSTRAP_DEADLINE: Duration = Duration::from_hours(24);
const WORKSPACE_LOCK_INITIALIZATION_GRACE: Duration = Duration::from_secs(30);
const WORKSPACE_LOCK_OWNER_FILE: &str = "owner.json";
const WORKSPACE_RECLAIM_CLAIM_FILE: &str = "engine.lock.reclaim";

#[derive(Clone, Debug)]
pub struct EngineConfig {
    control_root: PathBuf,
    exchange_root: PathBuf,
    workspace: Option<WorkspaceDescriptor>,
    bootstrap_deadline: Duration,
    drivers: JobDriverRegistry,
    /// Optional dark-build / host-test attachment of a bounded native worker pool.
    ///
    /// Production DI cutover remains deferred (P5-13). Absent attachment keeps the historical
    /// host-submit path (`submit_native_task_result`) for unit contracts.
    native_worker: Option<NativeWorkerAttach>,
}

impl PartialEq for EngineConfig {
    fn eq(&self, other: &Self) -> bool {
        self.control_root == other.control_root
            && self.exchange_root == other.exchange_root
            && self.workspace == other.workspace
            && self.bootstrap_deadline == other.bootstrap_deadline
    }
}

impl Eq for EngineConfig {}

impl EngineConfig {
    /// Validates application-private control and exchange roots for an optional workspace.
    ///
    /// The bootstrap deadline defaults to five minutes and may be replaced explicitly with
    /// [`Self::with_bootstrap_deadline`].
    ///
    /// # Errors
    ///
    /// Returns a structured error when either application-private root cannot be canonicalized or
    /// is not a directory.
    pub fn new(
        control_root: impl AsRef<Path>,
        exchange_root: impl AsRef<Path>,
        workspace: Option<WorkspaceDescriptor>,
    ) -> Result<Self, LomoError> {
        let control_root = canonical_directory(control_root.as_ref(), "control_root_unavailable")?;
        let exchange_root =
            canonical_directory(exchange_root.as_ref(), "exchange_root_unavailable")?;
        Ok(Self {
            control_root,
            exchange_root,
            workspace,
            bootstrap_deadline: DEFAULT_BOOTSTRAP_DEADLINE,
            drivers: JobDriverRegistry::default(),
            native_worker: None,
        })
    }

    /// Registers multi-phase user job drivers (document scan/command, etc.).
    #[must_use]
    pub fn with_drivers(mut self, drivers: JobDriverRegistry) -> Self {
        self.drivers = drivers;
        self
    }

    #[must_use]
    pub const fn drivers(&self) -> &JobDriverRegistry {
        &self.drivers
    }

    /// Attaches a bounded native worker pool for dark-build / host contract tests.
    ///
    /// When set, `start_native_task_job` enqueues work on the pool and the actor drains completions
    /// on its idle path. Without this attachment, hosts must call [`LomoEngine::submit_native_task_result`].
    #[must_use]
    pub fn with_native_worker(mut self, attach: NativeWorkerAttach) -> Self {
        self.native_worker = Some(attach);
        self
    }

    #[must_use]
    pub const fn native_worker(&self) -> Option<&NativeWorkerAttach> {
        self.native_worker.as_ref()
    }

    /// Replaces the explicit bootstrap deadline policy.
    ///
    /// # Errors
    ///
    /// Returns a validation error unless the deadline is within 1 millisecond..=24 hours.
    pub fn with_bootstrap_deadline(mut self, deadline: Duration) -> Result<Self, LomoError> {
        if deadline < Duration::from_millis(1) || deadline > MAX_BOOTSTRAP_DEADLINE {
            return Err(LomoError::validation(
                "invalid_bootstrap_deadline",
                "bootstrap deadline must be within 1 millisecond..=24 hours",
            ));
        }
        self.bootstrap_deadline = deadline;
        Ok(self)
    }

    #[must_use]
    pub const fn workspace(&self) -> Option<&WorkspaceDescriptor> {
        self.workspace.as_ref()
    }

    #[must_use]
    pub fn exchange_root(&self) -> &Path {
        &self.exchange_root
    }

    #[must_use]
    pub fn journal_path(&self) -> Option<PathBuf> {
        self.workspace.as_ref().map(|workspace| {
            workspace_control_directory(&self.control_root, workspace.identity())
                .join("journal.json")
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum EngineState {
    AwaitingWorkspaceSelection,
    Opening {
        job_id: JobId,
    },
    Ready {
        core_revision: CoreRevision,
        event_sequence: EventSequence,
    },
    ReadOnlyRecovery {
        error: LomoError,
    },
    ShuttingDown,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum JobStep {
    Running,
    NeedsPlatformBatch {
        batch: PlatformActionBatch,
    },
    /// Actor-external native task is queued or running outside the writer (dispatch fence active).
    RunningNative {
        task_kind: String,
        attempt: u32,
        dispatch_generation: u64,
    },
    BlockedByConflict {
        error: LomoError,
    },
    Completed,
    Failed {
        error: LomoError,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CancelOutcome {
    Accepted,
    AlreadyCancelled,
    AlreadyCompleted,
    UnknownJob,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ShutdownDeadline(Duration);

impl ShutdownDeadline {
    /// Creates a bounded shutdown deadline.
    ///
    /// # Errors
    ///
    /// Returns a validation error unless the deadline is within 1 millisecond..=30 seconds.
    pub fn new(duration: Duration) -> Result<Self, LomoError> {
        if duration < Duration::from_millis(1) || duration > Duration::from_secs(30) {
            return Err(LomoError::validation(
                "invalid_shutdown_deadline",
                "shutdown deadline must be within 1 millisecond..=30 seconds",
            ));
        }
        Ok(Self(duration))
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShutdownOutcome {
    Completed,
    DeadlineExceeded,
    AlreadyShutdown,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CoreEvent {
    event_sequence: EventSequence,
    core_revision: CoreRevision,
    job_id: Option<JobId>,
}

impl CoreEvent {
    #[must_use]
    pub const fn event_sequence(&self) -> EventSequence {
        self.event_sequence
    }

    #[must_use]
    pub const fn core_revision(&self) -> CoreRevision {
        self.core_revision
    }

    #[must_use]
    pub const fn job_id(&self) -> Option<&JobId> {
        self.job_id.as_ref()
    }
}

pub trait CoreEventListener: Send + Sync + 'static {
    /// Receives one loss-detectable invalidation event outside the engine actor.
    ///
    /// # Errors
    ///
    /// A foreign listener may return a structured callback error. The engine records notification
    /// loss through `EventSequence`; callback failure never rolls back durable state.
    fn on_event(&self, event: CoreEvent) -> Result<(), LomoError>;
}

type ListenerRegistry = Arc<Mutex<BTreeMap<u64, Arc<dyn CoreEventListener>>>>;

pub struct Subscription {
    id: u64,
    listeners: ListenerRegistry,
    closed: AtomicBool,
}

impl Subscription {
    /// Explicitly unregisters this subscription.
    ///
    /// # Panics
    ///
    /// Panics only if an engine-internal listener registry operation previously panicked while
    /// holding its lock. Foreign callbacks never execute under this lock.
    #[must_use]
    pub fn close(&self) -> bool {
        if self.closed.swap(true, Ordering::AcqRel) {
            return false;
        }
        let Ok(mut listeners) = self.listeners.lock() else {
            std::process::abort();
        };
        listeners.remove(&self.id).is_some()
    }
}

impl Drop for Subscription {
    fn drop(&mut self) {
        let _removed = self.close();
    }
}

pub struct LomoEngine {
    config: EngineConfig,
    state: Arc<RwLock<EngineState>>,
    commands: SyncSender<Command>,
    listeners: ListenerRegistry,
    next_listener_id: AtomicU64,
    actor: Mutex<Option<JoinHandle<()>>>,
}

impl fmt::Debug for LomoEngine {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("LomoEngine")
            .field("state", &self.state())
            .finish_non_exhaustive()
    }
}

impl LomoEngine {
    /// Opens the application kernel, recovers its journal, and starts the bounded single writer.
    ///
    /// # Errors
    ///
    /// Returns a structured busy, storage, or corruption error when exclusive ownership or
    /// fail-closed recovery cannot be established.
    pub fn open(config: EngineConfig) -> Result<Arc<Self>, LomoError> {
        let prepared = prepare_runtime(&config)?;
        let state = Arc::new(RwLock::new(prepared.state));
        let listeners: ListenerRegistry = Arc::new(Mutex::new(BTreeMap::new()));
        let (event_sender, event_receiver) = mpsc::sync_channel(EVENT_CAPACITY);
        spawn_event_dispatcher(event_receiver, Arc::clone(&listeners));
        let (commands, receiver) = mpsc::sync_channel(COMMAND_CAPACITY);
        let (native_pool, native_completions) = match config.native_worker() {
            Some(attach) => {
                // Capacity bounds in-flight worker results so a slow actor cannot grow unbounded.
                let (completion_tx, completion_rx) = mpsc::sync_channel(crate::MAX_NATIVE_QUEUE);
                let pool = NativeTaskWorkerPool::start(
                    attach.worker_count,
                    attach.queue_capacity,
                    Arc::clone(&attach.executor),
                    Arc::clone(&attach.vault),
                    completion_tx,
                )?;
                (Some(pool), Some(completion_rx))
            }
            None => (None, None),
        };
        let runtime = ActorRuntime {
            journal_path: prepared.journal_path,
            journal: prepared.journal,
            state: Arc::clone(&state),
            events: event_sender,
            monotonic_deadlines: prepared.monotonic_deadlines,
            exchange_root: config.exchange_root().to_path_buf(),
            workspace: config.workspace().cloned(),
            drivers: config.drivers().clone(),
            native_pool,
            native_completions,
            _workspace_lock: prepared.workspace_lock,
        };
        let actor = std::thread::Builder::new()
            .name("lomo-engine-writer".to_owned())
            .spawn(move || actor_loop(runtime, &receiver))
            .map_err(|error| {
                LomoError::storage(
                    "engine_actor_start_failed",
                    format!("single-writer thread could not start: {error}"),
                )
            })?;
        let engine = Arc::new(Self {
            config,
            state,
            commands,
            listeners,
            next_listener_id: AtomicU64::new(1),
            actor: Mutex::new(Some(actor)),
        });
        // When a pool is attached, re-enqueue QueuedNative (post-crash recovery) with a fresh
        // non-zero dispatch_generation so work is fully replayable without a host submit.
        // Without a pool, hosts call `redispatch_queued_native_jobs` or submit completions.
        if engine.config.native_worker().is_some() {
            // behavior-contract: silent-result-ok: open already recovered durable state; a failed
            // redispatch leaves QueuedNative with gen=0 (stale completions still rejected) for host
            // retry via redispatch_queued_native_jobs.
            drop(engine.redispatch_queued_native_jobs());
        }
        Ok(engine)
    }

    #[must_use]
    /// Returns the current read snapshot without entering the single-writer queue.
    ///
    /// # Panics
    ///
    /// Panics only if the engine's sole snapshot publisher previously panicked while holding the
    /// snapshot lock.
    pub fn state(&self) -> EngineState {
        let Ok(state) = self.state.read() else {
            std::process::abort();
        };
        state.clone()
    }

    #[must_use]
    pub const fn config(&self) -> &EngineConfig {
        &self.config
    }

    /// Registers an explicitly closeable event listener.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error after 256 live subscriptions.
    pub fn subscribe(
        &self,
        listener: Arc<dyn CoreEventListener>,
    ) -> Result<Subscription, LomoError> {
        let mut listeners = self.listeners.lock().map_err(|_poison| {
            LomoError::internal(
                "listener_registry_failed",
                "listener registry is unavailable",
            )
        })?;
        if listeners.len() >= EVENT_CAPACITY {
            return Err(LomoError::resource_limit(
                "listener_limit_exceeded",
                "engine supports at most 256 live listeners",
            ));
        }
        let id = self.next_listener_id.fetch_add(1, Ordering::Relaxed);
        listeners.insert(id, listener);
        drop(listeners);
        Ok(Subscription {
            id,
            listeners: Arc::clone(&self.listeners),
            closed: AtomicBool::new(false),
        })
    }

    /// Returns the latest durable step for one job.
    ///
    /// # Errors
    ///
    /// Returns an unknown-job or engine-closed error.
    pub fn poll_job(&self, job_id: &JobId) -> Result<JobStep, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::Poll {
            job_id: job_id.clone(),
            reply,
        })?;
        receive_response(&response)
    }

    /// Durably accepts a validated platform result prefix.
    ///
    /// # Errors
    ///
    /// Returns a validation, journal, unknown-job, or engine-closed error.
    pub fn submit_platform_result(
        &self,
        job_id: &JobId,
        result: PlatformBatchResult,
    ) -> Result<JobStep, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::Submit {
            job_id: job_id.clone(),
            result,
            reply,
        })?;
        receive_response(&response)
    }

    /// Durably arbitrates cancellation against completion.
    ///
    /// # Errors
    ///
    /// Returns a journal or engine-closed error.
    pub fn cancel_job(&self, job_id: &JobId) -> Result<CancelOutcome, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::Cancel {
            job_id: job_id.clone(),
            reply,
        })?;
        receive_response(&response)
    }

    /// Starts a multi-phase user job through a registered driver.
    ///
    /// # Errors
    ///
    /// Returns validation, resource-limit, unknown-driver, not-ready, journal, or engine-closed errors.
    pub fn start_user_job(
        &self,
        driver_kind: &str,
        request_json: &str,
        deadline: Duration,
    ) -> Result<JobId, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::StartUserJob {
            driver_kind: driver_kind.to_owned(),
            request_json: request_json.to_owned(),
            deadline,
            reply,
        })?;
        receive_response(&response)
    }

    /// Starts a native (actor-external) task job with dispatch fence and optional secret lease id.
    ///
    /// The job enters `QueuedNative` then `RunningNative` when a completion is not yet known. Host
    /// tests and dark-build workers call [`submit_native_task_result`] with matching fences.
    ///
    /// # Errors
    ///
    /// Validation / resource-limit / not-ready / journal errors.
    pub fn start_native_task_job(
        &self,
        task_kind: &str,
        request_json: &str,
        secret_lease_id: Option<SecretLeaseId>,
        deadline: Duration,
    ) -> Result<JobId, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::StartNativeTask {
            task_kind: task_kind.to_owned(),
            request_json: request_json.to_owned(),
            secret_lease_id,
            deadline,
            reply,
        })?;
        receive_response(&response)
    }

    /// Applies a native task completion. Stale attempt/`dispatch_generation` fences are ignored.
    ///
    /// # Errors
    ///
    /// Unknown-job or journal errors. Cancelled jobs return their durable cancelled step.
    pub fn submit_native_task_result(
        &self,
        completion: &NativeTaskCompletion,
    ) -> Result<JobStep, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::SubmitNative {
            completion: completion.clone(),
            reply,
        })?;
        receive_response(&response)
    }

    /// Re-dispatches durable `QueuedNative` jobs that still need a non-zero dispatch fence.
    ///
    /// Crash recovery leaves recovered work as `QueuedNative` with `dispatch_generation = 0` so
    /// stale pre-crash completions cannot win. Hosts with an attached pool (or this explicit API)
    /// must re-enqueue with a fresh non-zero generation before work can complete.
    ///
    /// When a pool is attached, open already performs this re-dispatch once; this API is for
    /// host-driven recovery and tests without relying on open timing.
    ///
    /// # Errors
    ///
    /// Not-ready / journal / resource-limit errors from fence allocation or pool enqueue.
    pub fn redispatch_queued_native_jobs(&self) -> Result<u32, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::RedispatchQueuedNative { reply })?;
        receive_response(&response)
    }

    /// Returns the latest durable job result payload published by a multi-phase driver.
    ///
    /// # Errors
    ///
    /// Returns unknown-job or engine-closed errors. Missing result is `Ok(None)`.
    pub fn read_job_result(&self, job_id: &JobId) -> Result<Option<String>, LomoError> {
        let (reply, response) = mpsc::channel();
        self.send(Command::ReadJobResult {
            job_id: job_id.clone(),
            reply,
        })?;
        receive_response(&response)
    }

    /// Requests bounded, explicit actor shutdown.
    ///
    /// # Errors
    ///
    /// Returns an engine-closed error when the actor is unavailable.
    pub fn shutdown(&self, deadline: ShutdownDeadline) -> Result<ShutdownOutcome, LomoError> {
        let (reply, response) = mpsc::channel();
        if self.actor.lock().map_err(actor_handle_error)?.is_none() {
            return Ok(ShutdownOutcome::AlreadyShutdown);
        }
        self.send(Command::Shutdown { reply })?;
        match response.recv_timeout(deadline.0) {
            Ok(result) => {
                let outcome = result?;
                self.join_actor()?;
                Ok(outcome)
            }
            Err(mpsc::RecvTimeoutError::Timeout) => Ok(ShutdownOutcome::DeadlineExceeded),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(engine_closed_error()),
        }
    }

    fn send(&self, command: Command) -> Result<(), LomoError> {
        self.commands
            .try_send(command)
            .map_err(|error| match error {
                TrySendError::Full(_command) => LomoError::resource_limit(
                    "engine_command_queue_full",
                    "single-writer command queue reached its 256-command bound",
                ),
                TrySendError::Disconnected(_command) => engine_closed_error(),
            })
    }

    fn join_actor(&self) -> Result<(), LomoError> {
        let actor = self.actor.lock().map_err(actor_handle_error)?.take();
        if let Some(actor) = actor {
            actor.join().map_err(|_panic| {
                LomoError::internal("engine_actor_panicked", "single-writer thread panicked")
            })?;
        }
        Ok(())
    }
}

impl Drop for LomoEngine {
    fn drop(&mut self) {
        if self.actor.get_mut().is_ok_and(|actor| actor.is_some()) {
            let _send_result = self.commands.send(Command::Terminate);
            if let Ok(actor) = self.actor.get_mut()
                && let Some(actor) = actor.take()
            {
                let _join_result = actor.join();
            }
        }
    }
}

enum Command {
    Poll {
        job_id: JobId,
        reply: mpsc::Sender<Result<JobStep, LomoError>>,
    },
    Submit {
        job_id: JobId,
        result: PlatformBatchResult,
        reply: mpsc::Sender<Result<JobStep, LomoError>>,
    },
    SubmitNative {
        completion: NativeTaskCompletion,
        reply: mpsc::Sender<Result<JobStep, LomoError>>,
    },
    Cancel {
        job_id: JobId,
        reply: mpsc::Sender<Result<CancelOutcome, LomoError>>,
    },
    StartUserJob {
        driver_kind: String,
        request_json: String,
        deadline: Duration,
        reply: mpsc::Sender<Result<JobId, LomoError>>,
    },
    StartNativeTask {
        task_kind: String,
        request_json: String,
        secret_lease_id: Option<SecretLeaseId>,
        deadline: Duration,
        reply: mpsc::Sender<Result<JobId, LomoError>>,
    },
    ReadJobResult {
        job_id: JobId,
        reply: mpsc::Sender<Result<Option<String>, LomoError>>,
    },
    RedispatchQueuedNative {
        reply: mpsc::Sender<Result<u32, LomoError>>,
    },
    Shutdown {
        reply: mpsc::Sender<Result<ShutdownOutcome, LomoError>>,
    },
    Terminate,
}

struct PreparedRuntime {
    state: EngineState,
    journal_path: Option<PathBuf>,
    journal: Option<JournalState>,
    monotonic_deadlines: BTreeMap<JobId, Instant>,
    workspace_lock: Option<WorkspaceLock>,
}

struct ActorRuntime {
    journal_path: Option<PathBuf>,
    journal: Option<JournalState>,
    state: Arc<RwLock<EngineState>>,
    events: SyncSender<CoreEvent>,
    monotonic_deadlines: BTreeMap<JobId, Instant>,
    exchange_root: PathBuf,
    workspace: Option<WorkspaceDescriptor>,
    drivers: JobDriverRegistry,
    /// Present only when [`EngineConfig::with_native_worker`] was used (dark host path).
    native_pool: Option<NativeTaskWorkerPool>,
    native_completions: Option<Receiver<NativeTaskCompletion>>,
    _workspace_lock: Option<WorkspaceLock>,
}

fn prepare_runtime(config: &EngineConfig) -> Result<PreparedRuntime, LomoError> {
    let Some(workspace) = config.workspace() else {
        return Ok(PreparedRuntime {
            state: EngineState::AwaitingWorkspaceSelection,
            journal_path: None,
            journal: None,
            monotonic_deadlines: BTreeMap::new(),
            workspace_lock: None,
        });
    };
    let directory = workspace_control_directory(&config.control_root, workspace.identity());
    fs::create_dir_all(&directory).map_err(|error| {
        LomoError::storage(
            "control_directory_unavailable",
            format!("engine control directory cannot be created: {error}"),
        )
    })?;
    let workspace_lock = acquire_workspace_lock(&directory)?;
    let journal_path = directory.join("journal.json");
    let mut journal = if journal_path.exists() {
        read_journal(&journal_path, workspace.identity())?
    } else {
        JournalState::initial(workspace.identity().clone())
    };
    advance_generation(&mut journal)?;
    expire_wall_clock_jobs(&mut journal, epoch_millis()?);
    ensure_bootstrap(&mut journal, workspace, config.bootstrap_deadline)?;
    journal.event_sequence = checked_next_event(journal.event_sequence)?;
    journal.lifecycle = lifecycle_for(&journal);
    validate_journal_state(&journal, workspace.identity())?;
    write_journal(&journal_path, &journal)?;
    let state = snapshot_for(&journal)?;
    let monotonic_deadlines = monotonic_deadlines(&journal)?;
    Ok(PreparedRuntime {
        state,
        journal_path: Some(journal_path),
        journal: Some(journal),
        monotonic_deadlines,
        workspace_lock: Some(workspace_lock),
    })
}

fn actor_loop(mut runtime: ActorRuntime, receiver: &Receiver<Command>) {
    loop {
        // Drain worker completions before taking the next command so long network work never
        // monopolizes the writer: completions only arrive after external workers finish.
        drain_native_completions(&mut runtime);
        match receiver.recv_timeout(Duration::from_millis(10)) {
            Ok(command) => {
                if !handle_command(&mut runtime, command) {
                    break;
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => expire_monotonic_jobs(&mut runtime),
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    // Drop completion receiver first so workers can observe disconnect on shutdown.
    runtime.native_completions.take();
    if let Some(pool) = runtime.native_pool.take() {
        pool.shutdown();
    }
}

/// Applies any queued native completions without blocking the actor.
fn drain_native_completions(runtime: &mut ActorRuntime) {
    loop {
        let Some(receiver) = runtime.native_completions.as_ref() else {
            return;
        };
        match receiver.try_recv() {
            Ok(completion) => {
                // behavior-contract: silent-result-ok: drain applies best-effort; durable fence
                // rejection / cancel races are handled inside submit_native_completion.
                drop(submit_native_completion(runtime, &completion));
            }
            Err(mpsc::TryRecvError::Empty | mpsc::TryRecvError::Disconnected) => return,
        }
    }
}

fn handle_command(runtime: &mut ActorRuntime, command: Command) -> bool {
    match command {
        Command::Poll { job_id, reply } => {
            let _reply_result = reply.send(poll_job(runtime.journal.as_ref(), &job_id));
        }
        Command::Submit {
            job_id,
            result,
            reply,
        } => {
            let response = submit_result(runtime, &job_id, &result);
            let _reply_result = reply.send(response);
        }
        Command::SubmitNative { completion, reply } => {
            let response = submit_native_completion(runtime, &completion);
            let _reply_result = reply.send(response);
        }
        Command::Cancel { job_id, reply } => {
            let response = cancel_job(runtime, &job_id);
            let _reply_result = reply.send(response);
        }
        Command::StartUserJob {
            driver_kind,
            request_json,
            deadline,
            reply,
        } => {
            let response = start_user_job(runtime, &driver_kind, &request_json, deadline);
            let _reply_result = reply.send(response);
        }
        Command::StartNativeTask {
            task_kind,
            request_json,
            secret_lease_id,
            deadline,
            reply,
        } => {
            let response = start_native_task_job(
                runtime,
                &task_kind,
                &request_json,
                secret_lease_id,
                deadline,
            );
            let _reply_result = reply.send(response);
        }
        Command::ReadJobResult { job_id, reply } => {
            let response = read_job_result(runtime.journal.as_ref(), &job_id);
            let _reply_result = reply.send(response);
        }
        Command::RedispatchQueuedNative { reply } => {
            let response = redispatch_queued_native_jobs(runtime);
            let _reply_result = reply.send(response);
        }
        Command::Shutdown { reply } => {
            let response = transition_to_shutdown(runtime);
            let should_stop = response.is_ok();
            let _reply_result = reply.send(response);
            return !should_stop;
        }
        Command::Terminate => return false,
    }
    true
}

fn poll_job(journal: Option<&JournalState>, job_id: &JobId) -> Result<JobStep, LomoError> {
    let journal = journal.ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;
    journal
        .jobs
        .iter()
        .find(|job| &job.job_id == job_id)
        .map(JobRecord::step)
        .ok_or_else(|| unknown_job_error(job_id))
}

fn cancel_job(runtime: &mut ActorRuntime, job_id: &JobId) -> Result<CancelOutcome, LomoError> {
    let Some(current) = runtime.journal.as_ref() else {
        return Ok(CancelOutcome::UnknownJob);
    };
    let Some(job) = current.jobs.iter().find(|job| &job.job_id == job_id) else {
        return Ok(CancelOutcome::UnknownJob);
    };
    match &job.status {
        PersistedJobStatus::Failed(error)
            if error.category() == crate::ErrorCategory::Cancelled =>
        {
            return Ok(CancelOutcome::AlreadyCancelled);
        }
        PersistedJobStatus::Completed | PersistedJobStatus::Failed(_) => {
            return Ok(CancelOutcome::AlreadyCompleted);
        }
        PersistedJobStatus::WaitingPlatform
        | PersistedJobStatus::QueuedNative
        | PersistedJobStatus::RunningNative
        | PersistedJobStatus::BlockedByConflict => {}
    }
    let mut candidate = current.clone();
    let job = candidate
        .jobs
        .iter_mut()
        .find(|job| &job.job_id == job_id)
        .ok_or_else(|| {
            LomoError::corruption(
                "job_generation_changed",
                "job disappeared while cloning one journal generation",
            )
        })?;
    job.status = PersistedJobStatus::Failed(LomoError::cancelled(
        "job_cancelled",
        "job cancellation durably won the terminal-state race",
    ));
    job.pending_effect = PendingEffect::Done;
    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    runtime.monotonic_deadlines.remove(job_id);
    Ok(CancelOutcome::Accepted)
}

fn submit_result(
    runtime: &mut ActorRuntime,
    job_id: &JobId,
    result: &PlatformBatchResult,
) -> Result<JobStep, LomoError> {
    let current = runtime
        .journal
        .as_ref()
        .ok_or_else(|| unknown_job_error(job_id))?;
    let record = current
        .jobs
        .iter()
        .find(|job| &job.job_id == job_id)
        .ok_or_else(|| unknown_job_error(job_id))?;
    let prefix = result.validate_against(&record.batch)?;
    if !matches!(record.status, PersistedJobStatus::WaitingPlatform) {
        // Stale platform completion after cancel/native transition: ignore, return durable step.
        return Ok(record.step());
    }

    let mut candidate = current.clone();
    let job_index = candidate
        .jobs
        .iter()
        .position(|job| &job.job_id == job_id)
        .ok_or_else(|| {
            LomoError::corruption(
                "job_generation_changed",
                "job disappeared while cloning one journal generation",
            )
        })?;
    apply_platform_batch_outcome(runtime, &mut candidate, job_index, result, prefix)?;
    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    if !matches!(
        poll_job(runtime.journal.as_ref(), job_id)?,
        JobStep::NeedsPlatformBatch { .. } | JobStep::RunningNative { .. }
    ) {
        runtime.monotonic_deadlines.remove(job_id);
    }
    poll_job(runtime.journal.as_ref(), job_id)
}

fn apply_platform_batch_outcome(
    runtime: &ActorRuntime,
    candidate: &mut JournalState,
    job_index: usize,
    result: &PlatformBatchResult,
    prefix: usize,
) -> Result<(), LomoError> {
    let job_missing = || {
        LomoError::corruption(
            "job_generation_changed",
            "job disappeared while updating one journal generation",
        )
    };
    if let Some(error) = result.action_results().iter().find_map(|action| {
        if let ActionOutcome::Failed(error) = action.outcome() {
            Some(error.clone())
        } else {
            None
        }
    }) {
        let job = candidate.jobs.get_mut(job_index).ok_or_else(job_missing)?;
        job.status = PersistedJobStatus::Failed(error);
        job.pending_effect = PendingEffect::Done;
        return Ok(());
    }
    let remaining = candidate
        .jobs
        .get(job_index)
        .ok_or_else(job_missing)?
        .batch
        .remaining_after(prefix);
    if let Some(remaining) = remaining {
        candidate
            .jobs
            .get_mut(job_index)
            .ok_or_else(job_missing)?
            .batch = remaining;
        return Ok(());
    }
    let driver_kind = candidate
        .jobs
        .get(job_index)
        .ok_or_else(job_missing)?
        .driver_kind
        .clone();
    if let Some(driver_kind) = driver_kind {
        if let Err(error) =
            apply_driver_advance(runtime, candidate, job_index, &driver_kind, result)
        {
            let job = candidate.jobs.get_mut(job_index).ok_or_else(job_missing)?;
            job.status = PersistedJobStatus::Failed(error);
            job.pending_effect = PendingEffect::Done;
        }
    } else {
        let job = candidate.jobs.get_mut(job_index).ok_or_else(job_missing)?;
        job.status = PersistedJobStatus::Completed;
        job.pending_effect = PendingEffect::Done;
    }
    Ok(())
}

/// Applies a native task completion with dispatch fence. Stale or post-cancel completions are rejected.
fn submit_native_completion(
    runtime: &mut ActorRuntime,
    completion: &NativeTaskCompletion,
) -> Result<JobStep, LomoError> {
    let job_id = &completion.job_id;
    let current = runtime
        .journal
        .as_ref()
        .ok_or_else(|| unknown_job_error(job_id))?;
    let record = current
        .jobs
        .iter()
        .find(|job| &job.job_id == job_id)
        .ok_or_else(|| unknown_job_error(job_id))?;

    // Cancel/terminal already won: ignore late worker results (stale completion rejection).
    if matches!(
        record.status,
        PersistedJobStatus::Completed
            | PersistedJobStatus::Failed(_)
            | PersistedJobStatus::BlockedByConflict
            | PersistedJobStatus::WaitingPlatform
    ) {
        return Ok(record.step());
    }

    let expected = match &record.pending_effect {
        PendingEffect::NativeTask {
            attempt,
            dispatch_generation,
            ..
        } => (*attempt, *dispatch_generation),
        PendingEffect::PlatformBatch | PendingEffect::BlockedByConflict | PendingEffect::Done => {
            return Ok(record.step());
        }
    };
    if completion.attempt != expected.0 || completion.dispatch_generation != expected.1 {
        // Stale fence: do not mutate durable state.
        return Ok(record.step());
    }
    // Generation 0 is reserved for post-crash "not yet redispatched" state and is never a live fence.
    if completion.dispatch_generation == 0 {
        return Ok(record.step());
    }
    if !matches!(
        record.status,
        PersistedJobStatus::RunningNative | PersistedJobStatus::QueuedNative
    ) {
        return Ok(record.step());
    }

    let mut candidate = current.clone();
    let job = candidate
        .jobs
        .iter_mut()
        .find(|job| &job.job_id == job_id)
        .ok_or_else(|| {
            LomoError::corruption(
                "job_generation_changed",
                "job disappeared while applying native completion",
            )
        })?;

    match &completion.outcome {
        NativeTaskOutcome::Success { result_json } => {
            job.result_json = Some(result_json.clone());
            job.status = PersistedJobStatus::Completed;
            job.pending_effect = PendingEffect::Done;
            job.driver_state_json = None;
        }
        NativeTaskOutcome::Failed { error } => {
            job.status = PersistedJobStatus::Failed(error.clone());
            job.pending_effect = PendingEffect::Done;
        }
        NativeTaskOutcome::Cancelled => {
            job.status = PersistedJobStatus::Failed(LomoError::cancelled(
                "native_task_cancelled",
                "native task was cancelled before completion",
            ));
            job.pending_effect = PendingEffect::Done;
        }
    }

    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    runtime.monotonic_deadlines.remove(job_id);
    poll_job(runtime.journal.as_ref(), job_id)
}

fn apply_driver_advance(
    runtime: &ActorRuntime,
    candidate: &mut JournalState,
    job_index: usize,
    driver_kind: &str,
    result: &PlatformBatchResult,
) -> Result<(), LomoError> {
    let driver = runtime.drivers.get(driver_kind).ok_or_else(|| {
        LomoError::validation(
            "unknown_job_driver",
            "job driver is not registered with the engine",
        )
    })?;
    let workspace = runtime.workspace.as_ref().ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;
    let job = candidate.jobs.get(job_index).ok_or_else(|| {
        LomoError::corruption(
            "job_generation_changed",
            "job disappeared while applying driver advance",
        )
    })?;
    let state_json = job.driver_state_json.clone().ok_or_else(|| {
        LomoError::corruption(
            "missing_job_driver_state",
            "multi-phase job is missing durable driver state",
        )
    })?;
    let mut next_counter = candidate.next_id;
    let mut ctx = JobDriverContext {
        job_id: &job.job_id,
        exchange_root: &runtime.exchange_root,
        workspace,
        deadline_epoch_millis: job.deadline_epoch_millis,
        attempt: job.batch.attempt().saturating_add(1),
        next_counter: &mut next_counter,
    };
    // Rebuild batch identity for advance against the fully applied batch.
    let applied_batch = job.batch.clone();
    let advance = driver.advance(&mut ctx, &state_json, &applied_batch, result)?;
    candidate.next_id = next_counter;
    let job = candidate.jobs.get_mut(job_index).ok_or_else(|| {
        LomoError::corruption(
            "job_generation_changed",
            "job disappeared while recording driver advance",
        )
    })?;
    match advance {
        DriverAdvance::NeedsBatch {
            state_json,
            actions,
            result_json,
        } => {
            let batch_id = {
                let counter = candidate.next_id;
                candidate.next_id = counter.checked_add(1).ok_or_else(|| {
                    LomoError::corruption(
                        "identifier_counter_overflow",
                        "journal identifier counter cannot advance",
                    )
                })?;
                BatchId::parse(&format!("batch-{counter}"))?
            };
            let attempt = job.batch.attempt().saturating_add(1).max(1);
            job.batch = PlatformActionBatch::new(
                job.job_id.clone(),
                batch_id,
                attempt,
                job.deadline_epoch_millis,
                actions,
            )?;
            job.driver_state_json = Some(state_json);
            if let Some(payload) = result_json {
                job.result_json = Some(payload);
            }
            job.status = PersistedJobStatus::WaitingPlatform;
            job.pending_effect = PendingEffect::PlatformBatch;
        }
        DriverAdvance::Done { result_json } => {
            job.driver_state_json = None;
            job.result_json = Some(result_json);
            job.status = PersistedJobStatus::Completed;
            job.pending_effect = PendingEffect::Done;
        }
    }
    Ok(())
}

fn start_user_job(
    runtime: &mut ActorRuntime,
    driver_kind: &str,
    request_json: &str,
    deadline: Duration,
) -> Result<JobId, LomoError> {
    let current = runtime.journal.as_ref().ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;
    if !matches!(current.lifecycle, PersistedLifecycle::Ready) {
        return Err(LomoError::validation(
            "engine_not_ready",
            "user jobs require a Ready engine",
        ));
    }
    let active_count = current.jobs.iter().filter(|job| job.is_active()).count();
    if active_count >= MAX_ACTIVE_JOBS {
        return Err(LomoError::resource_limit(
            "active_job_limit_exceeded",
            "engine supports at most 64 active jobs",
        ));
    }
    let driver = runtime.drivers.get(driver_kind).ok_or_else(|| {
        LomoError::validation(
            "unknown_job_driver",
            "job driver is not registered with the engine",
        )
    })?;
    let workspace = runtime.workspace.as_ref().ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;

    if deadline < Duration::from_millis(1) || deadline > MAX_BOOTSTRAP_DEADLINE {
        return Err(LomoError::validation(
            "invalid_job_deadline",
            "job deadline must be within 1 millisecond..=24 hours",
        ));
    }

    let (mut candidate, job_id, operation_id) = allocate_user_job(current)?;
    let deadline_epoch_millis = checked_deadline_epoch(deadline)?;
    let mut next_counter = candidate.next_id;
    let mut ctx = JobDriverContext {
        job_id: &job_id,
        exchange_root: &runtime.exchange_root,
        workspace,
        deadline_epoch_millis,
        attempt: 1,
        next_counter: &mut next_counter,
    };
    let started = driver.start(&mut ctx, request_json)?;
    candidate.next_id = next_counter;
    let capability = ctx_capability(workspace);

    let completed_immediately = started.actions.is_empty();
    let batch_id = allocate_batch_id(&mut candidate)?;
    let actions = if completed_immediately {
        // PlatformActionBatch rejects empty actions; keep a durable sentinel for journal shape.
        vec![PlatformAction::stat_root(
            ActionId::parse(&format!("action-complete-{}", batch_id.as_str()))?,
            capability,
        )]
    } else {
        started.actions
    };
    let batch =
        PlatformActionBatch::new(job_id.clone(), batch_id, 1, deadline_epoch_millis, actions)?;

    let kind = JobDriverKind::parse(driver_kind)?;
    candidate.jobs.push(JobRecord {
        job_id: job_id.clone(),
        operation_id,
        deadline_epoch_millis,
        batch,
        status: if completed_immediately {
            PersistedJobStatus::Completed
        } else {
            PersistedJobStatus::WaitingPlatform
        },
        driver_kind: Some(kind.as_str().to_owned()),
        driver_state_json: if completed_immediately {
            None
        } else {
            Some(started.state_json)
        },
        result_json: started.result_json,
        is_bootstrap: false,
        pending_effect: if completed_immediately {
            PendingEffect::Done
        } else {
            PendingEffect::PlatformBatch
        },
    });

    if !completed_immediately {
        runtime
            .monotonic_deadlines
            .insert(job_id.clone(), Instant::now() + deadline);
    }
    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    Ok(job_id)
}

fn start_native_task_job(
    runtime: &mut ActorRuntime,
    task_kind: &str,
    request_json: &str,
    secret_lease_id: Option<SecretLeaseId>,
    deadline: Duration,
) -> Result<JobId, LomoError> {
    let current = runtime.journal.as_ref().ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;
    if !matches!(current.lifecycle, PersistedLifecycle::Ready) {
        return Err(LomoError::validation(
            "engine_not_ready",
            "user jobs require a Ready engine",
        ));
    }
    validate_native_task_request(task_kind, request_json, deadline, current)?;

    let (mut candidate, job_id, operation_id) = allocate_user_job(current)?;
    let deadline_epoch_millis = checked_deadline_epoch(deadline)?;
    let dispatch_generation = candidate.next_id;
    candidate.next_id = checked_next_identifier(dispatch_generation)?;
    let batch_id = allocate_batch_id(&mut candidate)?;
    let capability = runtime
        .workspace
        .as_ref()
        .map(ctx_capability)
        .ok_or_else(|| {
            LomoError::validation(
                "workspace_not_selected",
                "jobs are unavailable until a workspace is selected",
            )
        })?;
    // Sentinel batch so journal shape stays valid for schema-compatible tools.
    let batch = PlatformActionBatch::new(
        job_id.clone(),
        batch_id,
        1,
        deadline_epoch_millis,
        vec![PlatformAction::stat_root(
            ActionId::parse(&format!("action-native-{}", job_id.as_str()))?,
            capability,
        )],
    )?;

    candidate.jobs.push(JobRecord {
        job_id: job_id.clone(),
        operation_id,
        deadline_epoch_millis,
        batch,
        status: PersistedJobStatus::RunningNative,
        driver_kind: Some("native-task".to_owned()),
        driver_state_json: None,
        result_json: None,
        is_bootstrap: false,
        pending_effect: PendingEffect::NativeTask {
            task_kind: task_kind.to_owned(),
            request_json: request_json.to_owned(),
            attempt: 1,
            dispatch_generation,
            secret_lease_id: secret_lease_id.clone(),
        },
    });
    runtime
        .monotonic_deadlines
        .insert(job_id.clone(), Instant::now() + deadline);
    // Commit fence first so a worker completion after a failed enqueue cannot invent state.
    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    enqueue_native_if_attached(
        runtime,
        &job_id,
        task_kind,
        request_json,
        1,
        dispatch_generation,
        secret_lease_id,
    )?;
    Ok(job_id)
}

fn validate_native_task_request(
    task_kind: &str,
    request_json: &str,
    deadline: Duration,
    current: &JournalState,
) -> Result<(), LomoError> {
    if task_kind.is_empty() || task_kind.len() > 128 {
        return Err(LomoError::validation(
            "invalid_native_task_kind",
            "native task kind must be 1..=128 bytes",
        ));
    }
    // Fail closed: request_json must not smuggle secrets into the journal.
    if request_json.contains("\"password\"")
        || request_json.contains("\"secret_value\"")
        || request_json.contains("Bearer ")
    {
        return Err(LomoError::validation(
            "native_request_contains_secret",
            "native task request_json must not contain secret material; use secret lease ids only",
        ));
    }
    let active_count = current.jobs.iter().filter(|job| job.is_active()).count();
    if active_count >= MAX_ACTIVE_JOBS {
        return Err(LomoError::resource_limit(
            "active_job_limit_exceeded",
            "engine supports at most 64 active jobs",
        ));
    }
    if deadline < Duration::from_millis(1) || deadline > MAX_BOOTSTRAP_DEADLINE {
        return Err(LomoError::validation(
            "invalid_job_deadline",
            "job deadline must be within 1 millisecond..=24 hours",
        ));
    }
    Ok(())
}

/// Enqueues to the external pool when attached (dark host path). Without a pool the host must
/// call [`LomoEngine::submit_native_task_result`] explicitly.
fn enqueue_native_if_attached(
    runtime: &mut ActorRuntime,
    job_id: &JobId,
    task_kind: &str,
    request_json: &str,
    attempt: u32,
    dispatch_generation: u64,
    secret_lease_id: Option<SecretLeaseId>,
) -> Result<(), LomoError> {
    let Some(pool) = runtime.native_pool.as_ref() else {
        return Ok(());
    };
    if let Err(error) = pool.enqueue(NativeTaskDispatch {
        job_id: job_id.clone(),
        task_kind: task_kind.to_owned(),
        request_json: request_json.to_owned(),
        attempt,
        dispatch_generation,
        secret_lease_id,
    }) {
        // Durable job already committed: mark failed so it cannot hang RunningNative forever.
        let fail_error = error.clone();
        if let Some(journal) = runtime.journal.as_ref() {
            let mut failed = journal.clone();
            if let Some(job) = failed.jobs.iter_mut().find(|job| &job.job_id == job_id) {
                job.status = PersistedJobStatus::Failed(error);
                job.pending_effect = PendingEffect::Done;
                // behavior-contract: silent-result-ok: secondary fail-commit after enqueue
                // failure must not mask the original resource-limit/shutdown error.
                drop(commit_candidate(runtime, failed, Some(job_id.clone())));
            }
        }
        runtime.monotonic_deadlines.remove(job_id);
        return Err(fail_error);
    }
    Ok(())
}

/// Assigns a fresh non-zero `dispatch_generation` to each recoverable `QueuedNative` job and
/// enqueues when a pool is attached. Without a pool, only the durable fence is refreshed so a
/// host may submit matching completions.
fn redispatch_queued_native_jobs(runtime: &mut ActorRuntime) -> Result<u32, LomoError> {
    let Some(current) = runtime.journal.as_ref() else {
        return Ok(0);
    };
    if !matches!(current.lifecycle, PersistedLifecycle::Ready) {
        return Err(LomoError::validation(
            "engine_not_ready",
            "native redispatch requires a Ready engine",
        ));
    }

    let mut candidate = current.clone();
    let mut to_enqueue: Vec<(JobId, String, String, u32, u64, Option<SecretLeaseId>)> = Vec::new();

    for job in &mut candidate.jobs {
        if !matches!(job.status, PersistedJobStatus::QueuedNative) {
            continue;
        }
        let PendingEffect::NativeTask {
            task_kind,
            request_json,
            attempt,
            dispatch_generation,
            secret_lease_id,
        } = &mut job.pending_effect
        else {
            continue;
        };
        // Only jobs still waiting for a post-recovery fence (gen == 0). Fresh start_native uses
        // RunningNative with a non-zero gen and must not be re-fenced here.
        if *dispatch_generation != 0 {
            continue;
        }
        let new_gen = candidate.next_id;
        candidate.next_id = checked_next_identifier(new_gen)?;
        *dispatch_generation = new_gen;
        job.status = PersistedJobStatus::RunningNative;
        to_enqueue.push((
            job.job_id.clone(),
            task_kind.clone(),
            request_json.clone(),
            *attempt,
            new_gen,
            secret_lease_id.clone(),
        ));
    }

    if to_enqueue.is_empty() {
        return Ok(0);
    }

    commit_candidate(
        runtime,
        candidate,
        to_enqueue.first().map(|row| row.0.clone()),
    )?;

    let mut redispatched = 0_u32;
    for (job_id, task_kind, request_json, attempt, dispatch_generation, secret_lease_id) in
        to_enqueue
    {
        enqueue_native_if_attached(
            runtime,
            &job_id,
            &task_kind,
            &request_json,
            attempt,
            dispatch_generation,
            secret_lease_id,
        )?;
        redispatched = redispatched.saturating_add(1);
    }
    Ok(redispatched)
}

fn allocate_user_job(
    current: &JournalState,
) -> Result<(JournalState, JobId, crate::OperationId), LomoError> {
    let mut candidate = current.clone();
    let counter = candidate.next_id;
    candidate.next_id = checked_next_identifier(counter)?;
    let job_id = JobId::parse(&format!("job-{counter}"))?;
    let operation_id = crate::OperationId::parse(&format!("operation-{counter}"))?;
    Ok((candidate, job_id, operation_id))
}

fn checked_deadline_epoch(deadline: Duration) -> Result<u64, LomoError> {
    let millis = u64::try_from(deadline.as_millis()).map_err(|_error| {
        LomoError::validation(
            "invalid_job_deadline",
            "job deadline is not representable in milliseconds",
        )
    })?;
    epoch_millis()?.checked_add(millis).ok_or_else(|| {
        LomoError::validation(
            "invalid_job_deadline",
            "job deadline overflows wall-clock representation",
        )
    })
}

fn allocate_batch_id(candidate: &mut JournalState) -> Result<BatchId, LomoError> {
    let counter = candidate.next_id;
    candidate.next_id = checked_next_identifier(counter)?;
    BatchId::parse(&format!("batch-{counter}"))
}

fn checked_next_identifier(counter: u64) -> Result<u64, LomoError> {
    counter.checked_add(1).ok_or_else(|| {
        LomoError::corruption(
            "identifier_counter_overflow",
            "journal identifier counter cannot advance",
        )
    })
}

fn ctx_capability(workspace: &WorkspaceDescriptor) -> CapabilityToken {
    match workspace {
        WorkspaceDescriptor::Saf { capability, .. } => capability.clone(),
        WorkspaceDescriptor::Direct { .. } => CapabilityToken::direct_root(),
    }
}

fn read_job_result(
    journal: Option<&JournalState>,
    job_id: &JobId,
) -> Result<Option<String>, LomoError> {
    let journal = journal.ok_or_else(|| {
        LomoError::validation(
            "workspace_not_selected",
            "jobs are unavailable until a workspace is selected",
        )
    })?;
    let job = journal
        .jobs
        .iter()
        .find(|job| &job.job_id == job_id)
        .ok_or_else(|| unknown_job_error(job_id))?;
    Ok(job.result_json.clone())
}

fn commit_candidate(
    runtime: &mut ActorRuntime,
    mut candidate: JournalState,
    job_id: Option<JobId>,
) -> Result<(), LomoError> {
    candidate.event_sequence = checked_next_event(candidate.event_sequence)?;
    candidate.lifecycle = lifecycle_for(&candidate);
    retain_bounded_terminals(&mut candidate);
    let path = runtime.journal_path.as_ref().ok_or_else(|| {
        LomoError::internal(
            "journal_path_missing",
            "active workspace has no journal path",
        )
    })?;
    write_journal(path, &candidate)?;
    let event = CoreEvent {
        event_sequence: EventSequence::from_persisted(candidate.event_sequence),
        core_revision: CoreRevision::from_persisted(candidate.core_revision),
        job_id,
    };
    publish_snapshot(&runtime.state, snapshot_for(&candidate)?);
    runtime.journal = Some(candidate);
    match runtime.events.try_send(event) {
        Ok(()) | Err(TrySendError::Full(_) | TrySendError::Disconnected(_)) => {}
    }
    Ok(())
}

fn transition_to_shutdown(runtime: &mut ActorRuntime) -> Result<ShutdownOutcome, LomoError> {
    if let Some(journal) = runtime.journal.as_ref() {
        let mut candidate = journal.clone();
        candidate.event_sequence = checked_next_event(candidate.event_sequence)?;
        candidate.lifecycle = PersistedLifecycle::ShuttingDown;
        let path = runtime.journal_path.as_ref().ok_or_else(|| {
            LomoError::internal(
                "journal_path_missing",
                "active workspace has no journal path",
            )
        })?;
        write_journal(path, &candidate)?;
        runtime.journal = Some(candidate);
    }
    publish_snapshot(&runtime.state, EngineState::ShuttingDown);
    Ok(ShutdownOutcome::Completed)
}

fn expire_monotonic_jobs(runtime: &mut ActorRuntime) {
    let now = Instant::now();
    let expired = runtime
        .monotonic_deadlines
        .iter()
        .filter(|(_job, deadline)| **deadline <= now)
        .map(|(job, _deadline)| job.clone())
        .collect::<Vec<_>>();
    for job_id in expired {
        if let Some(current) = runtime.journal.as_ref() {
            let mut candidate = current.clone();
            if let Some(job) = candidate.jobs.iter_mut().find(|job| job.job_id == job_id)
                && job.is_active()
            {
                job.status = PersistedJobStatus::Failed(deadline_error());
                job.pending_effect = PendingEffect::Done;
                let _commit_result = commit_candidate(runtime, candidate, Some(job_id.clone()));
            }
        }
        runtime.monotonic_deadlines.remove(&job_id);
    }
}

fn spawn_event_dispatcher(receiver: Receiver<CoreEvent>, listeners: ListenerRegistry) {
    let _dispatcher = std::thread::Builder::new()
        .name("lomo-engine-events".to_owned())
        .spawn(move || {
            while let Ok(event) = receiver.recv() {
                let snapshot = {
                    let Ok(registry) = listeners.lock() else {
                        std::process::abort();
                    };
                    registry.values().cloned().collect::<Vec<_>>()
                };
                for listener in snapshot {
                    // behavior-contract: silent-result-ok: callback failure is represented by the
                    // sequence gap/resnapshot contract and cannot roll back durable actor state.
                    drop(listener.on_event(event.clone()));
                }
            }
        });
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct JournalEnvelope {
    magic: String,
    schema: u32,
    payload: String,
    checksum: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct JournalState {
    workspace_id: WorkspaceId,
    engine_generation: u64,
    next_id: u64,
    core_revision: u64,
    event_sequence: u64,
    lifecycle: PersistedLifecycle,
    jobs: Vec<JobRecord>,
}

impl JournalState {
    const fn initial(workspace_id: WorkspaceId) -> Self {
        Self {
            workspace_id,
            engine_generation: 0,
            next_id: 1,
            core_revision: 0,
            event_sequence: 0,
            lifecycle: PersistedLifecycle::Opening,
            jobs: Vec::new(),
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
enum PersistedLifecycle {
    Opening,
    Ready,
    ReadOnlyRecovery,
    ShuttingDown,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct JobRecord {
    job_id: JobId,
    operation_id: crate::OperationId,
    deadline_epoch_millis: u64,
    batch: PlatformActionBatch,
    status: PersistedJobStatus,
    #[serde(default)]
    driver_kind: Option<String>,
    #[serde(default)]
    driver_state_json: Option<String>,
    #[serde(default)]
    result_json: Option<String>,
    #[serde(default)]
    is_bootstrap: bool,
    /// Pending durable effect (platform batch by default for schema v1 recovery).
    #[serde(default)]
    pending_effect: PendingEffect,
}

impl JobRecord {
    fn step(&self) -> JobStep {
        match &self.status {
            PersistedJobStatus::WaitingPlatform => JobStep::NeedsPlatformBatch {
                batch: self.batch.clone(),
            },
            PersistedJobStatus::QueuedNative | PersistedJobStatus::RunningNative => {
                match &self.pending_effect {
                    PendingEffect::NativeTask {
                        task_kind,
                        attempt,
                        dispatch_generation,
                        ..
                    } => JobStep::RunningNative {
                        task_kind: task_kind.clone(),
                        attempt: *attempt,
                        dispatch_generation: *dispatch_generation,
                    },
                    PendingEffect::PlatformBatch
                    | PendingEffect::BlockedByConflict
                    | PendingEffect::Done => JobStep::Running,
                }
            }
            PersistedJobStatus::BlockedByConflict => JobStep::BlockedByConflict {
                error: LomoError::validation(
                    "job_blocked_by_conflict",
                    "job is blocked by a durable conflict session",
                ),
            },
            PersistedJobStatus::Completed => JobStep::Completed,
            PersistedJobStatus::Failed(error) => JobStep::Failed {
                error: error.clone(),
            },
        }
    }

    const fn is_active(&self) -> bool {
        matches!(
            self.status,
            PersistedJobStatus::WaitingPlatform
                | PersistedJobStatus::QueuedNative
                | PersistedJobStatus::RunningNative
                | PersistedJobStatus::BlockedByConflict
        )
    }

    /// Crash recovery: `RunningNative` requeues as `QueuedNative` for idempotent replay.
    fn recover_native_on_open(&mut self) {
        if matches!(self.status, PersistedJobStatus::RunningNative) {
            self.status = PersistedJobStatus::QueuedNative;
            if let PendingEffect::NativeTask {
                attempt,
                dispatch_generation,
                ..
            } = &mut self.pending_effect
            {
                // Bump attempt so stale in-flight completions from the dead process are rejected.
                *attempt = attempt.saturating_add(1).max(1);
                *dispatch_generation = 0;
            }
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
enum PersistedJobStatus {
    WaitingPlatform,
    QueuedNative,
    RunningNative,
    BlockedByConflict,
    Completed,
    Failed(LomoError),
}

fn ensure_bootstrap(
    journal: &mut JournalState,
    workspace: &WorkspaceDescriptor,
    deadline: Duration,
) -> Result<(), LomoError> {
    if !journal.jobs.is_empty() {
        return Ok(());
    }
    let counter = journal.next_id;
    journal.next_id = counter.checked_add(1).ok_or_else(|| {
        LomoError::corruption(
            "identifier_counter_overflow",
            "journal identifier counter cannot advance",
        )
    })?;
    let job_id = JobId::parse(&format!("job-{counter}"))?;
    let operation_id = crate::OperationId::parse(&format!("operation-{counter}"))?;
    let batch_id = BatchId::parse(&format!("batch-{counter}"))?;
    let deadline_epoch_millis = epoch_millis()?
        .checked_add(u64::try_from(deadline.as_millis()).map_err(|_error| {
            LomoError::validation(
                "invalid_bootstrap_deadline",
                "bootstrap deadline is not representable in milliseconds",
            )
        })?)
        .ok_or_else(|| {
            LomoError::validation(
                "invalid_bootstrap_deadline",
                "bootstrap deadline overflows wall-clock representation",
            )
        })?;
    let (actions, status) = match workspace {
        WorkspaceDescriptor::Saf { capability, .. } => (
            vec![
                PlatformAction::stat_root(ActionId::parse("action-root-stat")?, capability.clone()),
                PlatformAction::list_root(
                    ActionId::parse("action-root-list")?,
                    capability.clone(),
                    None,
                    PageSize::new(256)?,
                ),
            ],
            PersistedJobStatus::WaitingPlatform,
        ),
        WorkspaceDescriptor::Direct { .. } => (
            vec![PlatformAction::stat_root(
                ActionId::parse("action-direct-root")?,
                CapabilityToken::parse("direct-root")?,
            )],
            PersistedJobStatus::Completed,
        ),
    };
    let pending_effect = if matches!(status, PersistedJobStatus::Completed) {
        PendingEffect::Done
    } else {
        PendingEffect::PlatformBatch
    };
    journal.jobs.push(JobRecord {
        job_id: job_id.clone(),
        operation_id,
        deadline_epoch_millis,
        batch: PlatformActionBatch::new(job_id, batch_id, 1, deadline_epoch_millis, actions)?,
        status,
        driver_kind: None,
        driver_state_json: None,
        result_json: None,
        is_bootstrap: true,
        pending_effect,
    });
    Ok(())
}

fn lifecycle_for(journal: &JournalState) -> PersistedLifecycle {
    // Bootstrap jobs have no driver_kind (including journals recovered from schema v1).
    let is_bootstrap = |job: &JobRecord| job.is_bootstrap || job.driver_kind.is_none();
    let bootstrap_waiting = journal
        .jobs
        .iter()
        .any(|job| is_bootstrap(job) && matches!(job.status, PersistedJobStatus::WaitingPlatform));
    if bootstrap_waiting {
        PersistedLifecycle::Opening
    } else if journal.jobs.iter().any(|job| {
        is_bootstrap(job)
            && matches!(
                job.status,
                PersistedJobStatus::Failed(ref error)
                    if matches!(
                        error.category(),
                        crate::ErrorCategory::Cancelled | crate::ErrorCategory::Timeout
                    )
            )
    }) {
        PersistedLifecycle::ReadOnlyRecovery
    } else {
        PersistedLifecycle::Ready
    }
}

fn snapshot_for(journal: &JournalState) -> Result<EngineState, LomoError> {
    Ok(match journal.lifecycle {
        PersistedLifecycle::Opening => {
            let job_id = journal
                .jobs
                .iter()
                .find(|job| {
                    (job.is_bootstrap || job.driver_kind.is_none())
                        && matches!(job.status, PersistedJobStatus::WaitingPlatform)
                })
                .ok_or_else(|| {
                    LomoError::corruption(
                        "opening_job_missing",
                        "opening lifecycle requires one active bootstrap job",
                    )
                })?
                .job_id
                .clone();
            EngineState::Opening { job_id }
        }
        PersistedLifecycle::Ready => EngineState::Ready {
            core_revision: CoreRevision::from_persisted(journal.core_revision),
            event_sequence: EventSequence::from_persisted(journal.event_sequence),
        },
        PersistedLifecycle::ReadOnlyRecovery => {
            let error = journal
                .jobs
                .iter()
                .rev()
                .find_map(|job| {
                    if let PersistedJobStatus::Failed(error) = &job.status {
                        Some(error.clone())
                    } else {
                        None
                    }
                })
                .ok_or_else(|| {
                    LomoError::corruption(
                        "recovery_error_missing",
                        "read-only recovery lifecycle requires one failed job",
                    )
                })?;
            EngineState::ReadOnlyRecovery { error }
        }
        PersistedLifecycle::ShuttingDown => EngineState::ShuttingDown,
    })
}

fn monotonic_deadlines(journal: &JournalState) -> Result<BTreeMap<JobId, Instant>, LomoError> {
    let now_wall = epoch_millis()?;
    let now = Instant::now();
    journal
        .jobs
        .iter()
        .filter(|job| job.is_active())
        .map(|job| {
            let remaining = job.deadline_epoch_millis.saturating_sub(now_wall);
            Ok((job.job_id.clone(), now + Duration::from_millis(remaining)))
        })
        .collect()
}

fn expire_wall_clock_jobs(journal: &mut JournalState, now: u64) {
    for job in &mut journal.jobs {
        if job.is_active() && job.deadline_epoch_millis <= now {
            job.status = PersistedJobStatus::Failed(deadline_error());
            job.pending_effect = PendingEffect::Done;
        }
    }
    journal.lifecycle = lifecycle_for(journal);
}

fn retain_bounded_terminals(journal: &mut JournalState) {
    let terminal_count = journal.jobs.iter().filter(|job| !job.is_active()).count();
    let mut to_remove = terminal_count.saturating_sub(MAX_TERMINAL_JOBS);
    journal.jobs.retain(|job| {
        if to_remove > 0 && !job.is_active() {
            to_remove -= 1;
            false
        } else {
            true
        }
    });
}

fn validate_journal_state(
    state: &JournalState,
    expected_workspace: &WorkspaceId,
) -> Result<(), LomoError> {
    let active_count = state.jobs.iter().filter(|job| job.is_active()).count();
    let terminal_count = state.jobs.len().saturating_sub(active_count);
    let ids = state
        .jobs
        .iter()
        .map(|job| &job.job_id)
        .collect::<BTreeSet<_>>();
    if &state.workspace_id != expected_workspace
        || state.next_id == 0
        || active_count > MAX_ACTIVE_JOBS
        || terminal_count > MAX_TERMINAL_JOBS
        || ids.len() != state.jobs.len()
    {
        return Err(LomoError::corruption(
            "journal_state_inconsistent",
            "engine journal identity, bounds, or job ids are inconsistent",
        ));
    }
    // Secrets must never appear as plaintext values in durable journal payload encodings.
    // Opaque lease ids (field name secret_lease_id) are allowed; plaintext markers are not.
    let payload = serde_json::to_string(state).map_err(|_error| {
        LomoError::corruption(
            "journal_payload_invalid",
            "engine journal payload is malformed",
        )
    })?;
    if payload.contains("\"password\"")
        || payload.contains("Bearer ")
        || payload.contains("super-secret")
    {
        return Err(LomoError::corruption(
            "journal_contains_secret_material",
            "engine journal payload must not contain secret material",
        ));
    }
    Ok(())
}

fn advance_generation(journal: &mut JournalState) -> Result<(), LomoError> {
    journal.engine_generation = journal.engine_generation.checked_add(1).ok_or_else(|| {
        LomoError::corruption(
            "engine_generation_overflow",
            "engine generation cannot advance without overflow",
        )
    })?;
    Ok(())
}

fn checked_next_event(current: u64) -> Result<u64, LomoError> {
    current.checked_add(1).ok_or_else(|| {
        LomoError::corruption(
            "event_sequence_overflow",
            "event sequence cannot advance without overflow",
        )
    })
}

fn canonical_directory(path: &Path, code: &'static str) -> Result<PathBuf, LomoError> {
    let canonical = path.canonicalize().map_err(|error| {
        LomoError::storage(
            code,
            format!("application-private directory cannot be canonicalized: {error}"),
        )
    })?;
    if !canonical.is_dir() {
        return Err(LomoError::validation(
            "application_private_root_not_directory",
            "application-private root must be a directory",
        ));
    }
    Ok(canonical)
}

fn workspace_control_directory(control_root: &Path, workspace_id: &WorkspaceId) -> PathBuf {
    control_root
        .join("lomo-engine")
        .join("v1")
        .join(workspace_id.as_str())
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
struct WorkspaceLockOwner {
    pid: u32,
    process_start_identity: String,
    nonce: String,
    created_unix_millis: u64,
}

impl WorkspaceLockOwner {
    fn current() -> Result<Self, LomoError> {
        let pid = std::process::id();
        let process_start_identity = process_start_identity(pid)?.ok_or_else(|| {
            LomoError::storage(
                "workspace_lock_identity_unavailable",
                "current process start identity is unavailable".to_owned(),
            )
        })?;
        Ok(Self {
            pid,
            process_start_identity,
            nonce: random_lock_nonce()?,
            created_unix_millis: epoch_millis()?,
        })
    }

    fn is_live(&self) -> Result<bool, LomoError> {
        process_start_identity(self.pid)
            .map(|identity| identity.is_some_and(|current| current == self.process_start_identity))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ExistingLockSnapshot {
    owner_bytes: Option<Vec<u8>>,
}

impl ExistingLockSnapshot {
    fn read(path: &Path) -> Result<Self, LomoError> {
        match fs::read(path.join(WORKSPACE_LOCK_OWNER_FILE)) {
            Ok(owner_bytes) => Ok(Self {
                owner_bytes: Some(owner_bytes),
            }),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                Ok(Self { owner_bytes: None })
            }
            Err(error) => Err(workspace_lock_error(
                "workspace lock owner cannot be read",
                &error,
            )),
        }
    }

    fn is_reclaimable(&self, path: &Path) -> Result<bool, LomoError> {
        let Some(bytes) = self.owner_bytes.as_ref() else {
            return path_is_older_than_initialization_grace(path);
        };
        match serde_json::from_slice::<WorkspaceLockOwner>(bytes) {
            Ok(owner) => owner.is_live().map(|live| !live),
            Err(_error) => path_is_older_than_initialization_grace(path),
        }
    }
}

/// Exclusive workspace ownership without first-party `unsafe`.
///
/// The directory is the atomic exclusion primitive. Its owner record pins PID + process-start
/// identity + nonce; incomplete/malformed initialization remains Busy for a grace period.
struct WorkspaceLock {
    path: PathBuf,
    nonce: String,
}

impl WorkspaceLock {
    fn acquire(control_directory: &Path) -> Result<Self, LomoError> {
        let path = control_directory.join("engine.lock");
        ensure_no_reclaim_in_progress(control_directory)?;
        let owner = WorkspaceLockOwner::current()?;
        match fs::create_dir(&path) {
            Ok(()) => finish_created_workspace_lock(path, &owner),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let expected = ExistingLockSnapshot::read(&path)?;
                if !expected.is_reclaimable(&path)? {
                    return Err(LomoError::busy(
                        "workspace_busy",
                        "workspace is already owned by another active engine",
                    ));
                }
                let _claim = WorkspaceReclaimClaim::acquire(control_directory)?;
                let current = ExistingLockSnapshot::read(&path)?;
                if current != expected || !current.is_reclaimable(&path)? {
                    return Err(LomoError::busy(
                        "workspace_busy",
                        "workspace ownership changed before stale reclaim",
                    ));
                }
                fs::remove_dir_all(&path).map_err(|remove_error| {
                    workspace_lock_error("stale workspace lock cannot be removed", &remove_error)
                })?;
                fs::create_dir(&path).map_err(|create_error| {
                    if create_error.kind() == std::io::ErrorKind::AlreadyExists {
                        LomoError::busy(
                            "workspace_busy",
                            "workspace was acquired by another engine during stale reclaim",
                        )
                    } else {
                        workspace_lock_error(
                            "workspace lock cannot be created after stale reclaim",
                            &create_error,
                        )
                    }
                })?;
                finish_created_workspace_lock(path, &owner)
            }
            Err(error) => Err(workspace_lock_error(
                "workspace lock cannot be created",
                &error,
            )),
        }
    }
}

impl Drop for WorkspaceLock {
    fn drop(&mut self) {
        release_workspace_lock_if_owned(&self.path, &self.nonce);
    }
}

struct WorkspaceReclaimClaim {
    path: PathBuf,
    nonce: String,
}

impl WorkspaceReclaimClaim {
    fn acquire(control_directory: &Path) -> Result<Self, LomoError> {
        ensure_no_reclaim_in_progress(control_directory)?;
        let owner = WorkspaceLockOwner::current()?;
        let path = control_directory.join(WORKSPACE_RECLAIM_CLAIM_FILE);
        match fs::create_dir(&path) {
            Ok(()) => {
                if let Err(error) = publish_workspace_lock_owner(&path, &owner) {
                    cleanup_failed_workspace_lock(&path, &owner.nonce);
                    return Err(error);
                }
                Ok(Self {
                    path,
                    nonce: owner.nonce,
                })
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                Err(LomoError::busy(
                    "workspace_busy",
                    "workspace stale reclaim is already active",
                ))
            }
            Err(error) => Err(workspace_lock_error(
                "workspace reclaim claim cannot be published",
                &error,
            )),
        }
    }
}

impl Drop for WorkspaceReclaimClaim {
    fn drop(&mut self) {
        release_workspace_lock_if_owned(&self.path, &self.nonce);
    }
}

fn finish_created_workspace_lock(
    path: PathBuf,
    owner: &WorkspaceLockOwner,
) -> Result<WorkspaceLock, LomoError> {
    if let Err(error) = publish_workspace_lock_owner(&path, owner) {
        cleanup_failed_workspace_lock(&path, &owner.nonce);
        return Err(error);
    }
    Ok(WorkspaceLock {
        path,
        nonce: owner.nonce.clone(),
    })
}

fn publish_workspace_lock_owner(path: &Path, owner: &WorkspaceLockOwner) -> Result<(), LomoError> {
    let candidate = path.join(format!("owner.{}.candidate", owner.nonce));
    write_owner_file(&candidate, owner)?;
    fs::rename(&candidate, path.join(WORKSPACE_LOCK_OWNER_FILE)).map_err(|error| {
        workspace_lock_error("workspace lock owner cannot be published", &error)
    })?;
    File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(|error| workspace_lock_error("workspace lock directory cannot be synced", &error))
}

fn write_owner_file(path: &Path, owner: &WorkspaceLockOwner) -> Result<(), LomoError> {
    let bytes = serde_json::to_vec(owner).map_err(|_error| {
        LomoError::internal(
            "workspace_lock_owner_invalid",
            "workspace lock owner record cannot be encoded",
        )
    })?;
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .map_err(|error| workspace_lock_error("workspace lock owner cannot be created", &error))?;
    file.write_all(&bytes)
        .map_err(|error| workspace_lock_error("workspace lock owner cannot be written", &error))?;
    file.sync_all().map_err(|error| {
        workspace_lock_error("workspace lock owner cannot be durably synced", &error)
    })?;
    Ok(())
}

fn ensure_no_reclaim_in_progress(control_directory: &Path) -> Result<(), LomoError> {
    let path = control_directory.join(WORKSPACE_RECLAIM_CLAIM_FILE);
    let metadata = match fs::metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => {
            return Err(workspace_lock_error(
                "workspace reclaim claim cannot be inspected",
                &error,
            ));
        }
    };
    if !metadata.is_dir() {
        if !path_is_older_than_initialization_grace(&path)? {
            return Err(reclaim_already_active());
        }
        return match fs::remove_file(&path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::IsADirectory => {
                Err(reclaim_already_active())
            }
            Err(error) => Err(workspace_lock_error(
                "stale workspace reclaim claim cannot be removed",
                &error,
            )),
        };
    }

    let owner_path = path.join(WORKSPACE_LOCK_OWNER_FILE);
    let bytes = match fs::read(&owner_path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            if !path_is_older_than_initialization_grace(&path)? {
                return Err(reclaim_already_active());
            }
            return remove_empty_reclaim_claim(&path);
        }
        Err(error) => {
            return Err(workspace_lock_error(
                "workspace reclaim owner cannot be read",
                &error,
            ));
        }
    };
    let reclaimable = match serde_json::from_slice::<WorkspaceLockOwner>(&bytes) {
        Ok(owner) => !owner.is_live()?,
        Err(_error) => path_is_older_than_initialization_grace(&path)?,
    };
    if !reclaimable {
        return Err(reclaim_already_active());
    }
    remove_reclaim_owner_if_unchanged(&owner_path, &bytes)?;
    remove_empty_reclaim_claim(&path)
}

fn remove_reclaim_owner_if_unchanged(path: &Path, expected: &[u8]) -> Result<(), LomoError> {
    match fs::read(path) {
        Ok(current) if current == expected => {}
        Ok(_) => return Err(reclaim_ownership_changed()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(reclaim_ownership_changed());
        }
        Err(error) => {
            return Err(workspace_lock_error(
                "workspace reclaim owner cannot be re-read",
                &error,
            ));
        }
    }
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            Err(reclaim_ownership_changed())
        }
        Err(error) => Err(workspace_lock_error(
            "stale workspace reclaim owner cannot be removed",
            &error,
        )),
    }
}

fn remove_empty_reclaim_claim(path: &Path) -> Result<(), LomoError> {
    match fs::remove_dir(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::DirectoryNotEmpty => {
            Err(reclaim_ownership_changed())
        }
        Err(error) => Err(workspace_lock_error(
            "stale workspace reclaim directory cannot be removed",
            &error,
        )),
    }
}

fn reclaim_already_active() -> LomoError {
    LomoError::busy(
        "workspace_busy",
        "workspace stale reclaim is already active",
    )
}

fn reclaim_ownership_changed() -> LomoError {
    LomoError::busy("workspace_busy", "workspace reclaim ownership changed")
}

fn cleanup_failed_workspace_lock(path: &Path, nonce: &str) {
    let candidate = path.join(format!("owner.{nonce}.candidate"));
    if candidate.exists() || owner_file_has_nonce(&path.join(WORKSPACE_LOCK_OWNER_FILE), nonce) {
        drop(fs::remove_dir_all(path));
    }
}

fn release_workspace_lock_if_owned(path: &Path, nonce: &str) {
    if owner_file_has_nonce(&path.join(WORKSPACE_LOCK_OWNER_FILE), nonce) {
        drop(fs::remove_dir_all(path));
    }
}

/// True only when the on-disk owner record proves this instance still owns the lock.
///
/// A missing, unreadable, or malformed record is deliberately not ownership: release must never
/// delete a lock directory it cannot prove it holds, or a crashed reclaim would remove the live
/// owner's lock and let two engines write the same workspace.
fn owner_file_has_nonce(path: &Path, nonce: &str) -> bool {
    let Ok(bytes) = fs::read(path) else {
        return false;
    };
    let Ok(owner) = serde_json::from_slice::<WorkspaceLockOwner>(&bytes) else {
        return false;
    };
    owner.nonce == nonce
}

fn path_is_older_than_initialization_grace(path: &Path) -> Result<bool, LomoError> {
    let modified = fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .map_err(|error| workspace_lock_error("workspace lock age cannot be read", &error))?;
    Ok(SystemTime::now()
        .duration_since(modified)
        .is_ok_and(|age| age >= WORKSPACE_LOCK_INITIALIZATION_GRACE))
}

fn process_start_identity(pid: u32) -> Result<Option<String>, LomoError> {
    if pid == 0 {
        return Ok(None);
    }
    let stat_path = Path::new("/proc").join(pid.to_string()).join("stat");
    let stat = match fs::read_to_string(stat_path) {
        Ok(stat) => stat,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(workspace_lock_error(
                "process start identity cannot be read",
                &error,
            ));
        }
    };
    let (_command, fields) = stat.rsplit_once(") ").ok_or_else(|| {
        LomoError::storage(
            "workspace_lock_identity_unavailable",
            "process stat record is malformed".to_owned(),
        )
    })?;
    let start_ticks = fields.split_whitespace().nth(19).ok_or_else(|| {
        LomoError::storage(
            "workspace_lock_identity_unavailable",
            "process stat record has no start time".to_owned(),
        )
    })?;
    let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .map_err(|error| workspace_lock_error("boot identity cannot be read", &error))?;
    Ok(Some(format!("{}:{start_ticks}", boot_id.trim())))
}

fn random_lock_nonce() -> Result<String, LomoError> {
    let mut bytes = [0_u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut source| source.read_exact(&mut bytes))
        .map_err(|error| {
            workspace_lock_error("workspace lock nonce cannot be generated", &error)
        })?;
    Ok(sha256_hex(&bytes))
}

fn workspace_lock_error(context: &str, error: &std::io::Error) -> LomoError {
    LomoError::storage("workspace_lock_unavailable", format!("{context}: {error}"))
}

fn acquire_workspace_lock(control_directory: &Path) -> Result<WorkspaceLock, LomoError> {
    WorkspaceLock::acquire(control_directory)
}

fn read_journal(path: &Path, expected_workspace: &WorkspaceId) -> Result<JournalState, LomoError> {
    let bytes = fs::read(path).map_err(|error| {
        LomoError::storage(
            "journal_read_failed",
            format!("engine journal cannot be read: {error}"),
        )
    })?;
    let envelope: JournalEnvelope = serde_json::from_slice(&bytes).map_err(|_error| {
        LomoError::corruption(
            "journal_envelope_invalid",
            "engine journal envelope is truncated or malformed",
        )
    })?;
    if envelope.magic != JOURNAL_MAGIC {
        return Err(LomoError::corruption(
            "journal_schema_unknown",
            "engine journal magic or schema is unknown",
        ));
    }
    // Unknown schema fails closed (corruption / ReadOnlyRecovery path for callers) — never clean slate.
    if envelope.schema != JOURNAL_SCHEMA && envelope.schema != JOURNAL_SCHEMA_V1 {
        return Err(LomoError::corruption(
            "journal_schema_unknown",
            "engine journal magic or schema is unknown",
        ));
    }
    if sha256_hex(envelope.payload.as_bytes()) != envelope.checksum {
        return Err(LomoError::corruption(
            "journal_checksum_mismatch",
            "engine journal checksum does not match its payload",
        ));
    }
    let mut state: JournalState = serde_json::from_str(&envelope.payload).map_err(|_error| {
        LomoError::corruption(
            "journal_payload_invalid",
            "engine journal payload is malformed",
        )
    })?;
    // Crash recovery: RunningNative → QueuedNative for idempotent replay after process death.
    for job in &mut state.jobs {
        job.recover_native_on_open();
        // Schema v1 jobs default pending_effect via serde; ensure platform jobs stay consistent.
        if matches!(job.status, PersistedJobStatus::WaitingPlatform)
            && matches!(job.pending_effect, PendingEffect::Done)
        {
            job.pending_effect = PendingEffect::PlatformBatch;
        }
    }
    validate_journal_state(&state, expected_workspace)?;
    Ok(state)
}

fn write_journal(path: &Path, state: &JournalState) -> Result<(), LomoError> {
    let payload = serde_json::to_string(state).map_err(|_error| {
        LomoError::internal(
            "journal_encode_failed",
            "engine journal could not be encoded",
        )
    })?;
    let envelope = JournalEnvelope {
        magic: JOURNAL_MAGIC.to_owned(),
        schema: JOURNAL_SCHEMA,
        checksum: sha256_hex(payload.as_bytes()),
        payload,
    };
    let bytes = serde_json::to_vec(&envelope).map_err(|_error| {
        LomoError::internal(
            "journal_encode_failed",
            "engine journal envelope could not be encoded",
        )
    })?;
    let candidate = path.with_extension("candidate");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&candidate)
        .map_err(|error| journal_write_error(&error))?;
    file.write_all(&bytes)
        .map_err(|error| journal_write_error(&error))?;
    file.sync_all()
        .map_err(|error| journal_write_error(&error))?;
    fs::rename(&candidate, path).map_err(|error| journal_write_error(&error))?;
    File::open(path.parent().ok_or_else(|| {
        LomoError::internal(
            "journal_parent_missing",
            "engine journal path has no parent",
        )
    })?)
    .and_then(|directory| directory.sync_all())
    .map_err(|error| journal_write_error(&error))?;
    Ok(())
}

fn publish_snapshot(state: &RwLock<EngineState>, snapshot: EngineState) {
    let Ok(mut state) = state.write() else {
        std::process::abort();
    };
    *state = snapshot;
}

fn epoch_millis() -> Result<u64, LomoError> {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_error| {
            LomoError::internal(
                "system_clock_before_epoch",
                "system wall clock is before the Unix epoch",
            )
        })?
        .as_millis();
    u64::try_from(millis).map_err(|_error| {
        LomoError::internal(
            "system_clock_overflow",
            "system wall clock does not fit the journal representation",
        )
    })
}

fn deadline_error() -> LomoError {
    LomoError::timeout(
        "job_deadline_exceeded",
        "persisted job deadline expired before bootstrap completed",
    )
}

fn unknown_job_error(job_id: &JobId) -> LomoError {
    LomoError::new(
        crate::ErrorCategory::Validation,
        "unknown_job",
        RetryDisposition::Never,
        format!("job {} is not retained by this engine", job_id.as_str()),
    )
}

fn engine_closed_error() -> LomoError {
    LomoError::internal(
        "engine_closed",
        "single-writer actor is no longer available",
    )
}

fn actor_handle_error<T>(_error: std::sync::PoisonError<T>) -> LomoError {
    LomoError::internal(
        "engine_actor_handle_failed",
        "engine actor handle is unavailable",
    )
}

fn receive_response<T>(receiver: &Receiver<Result<T, LomoError>>) -> Result<T, LomoError> {
    receiver.recv().map_err(|_error| engine_closed_error())?
}

fn journal_write_error(error: &std::io::Error) -> LomoError {
    LomoError::storage(
        "journal_publish_failed",
        format!("engine journal could not be durably published: {error}"),
    )
}

fn sha256_hex(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
