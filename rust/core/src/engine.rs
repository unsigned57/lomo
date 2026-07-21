use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
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
    JobDriverContext, JobDriverKind, JobDriverRegistry, JobId, LomoError, PageSize, PlatformAction,
    PlatformActionBatch, PlatformBatchResult, RetryDisposition, WorkspaceDescriptor, WorkspaceId,
};

const JOURNAL_MAGIC: &str = "LOMO_ENGINE";
const JOURNAL_SCHEMA: u32 = 1;
const COMMAND_CAPACITY: usize = 256;
const EVENT_CAPACITY: usize = 256;
const MAX_ACTIVE_JOBS: usize = 64;
const MAX_TERMINAL_JOBS: usize = 256;
const DEFAULT_BOOTSTRAP_DEADLINE: Duration = Duration::from_mins(5);
const MAX_BOOTSTRAP_DEADLINE: Duration = Duration::from_hours(24);

#[derive(Clone, Debug)]
pub struct EngineConfig {
    control_root: PathBuf,
    exchange_root: PathBuf,
    workspace: Option<WorkspaceDescriptor>,
    bootstrap_deadline: Duration,
    drivers: JobDriverRegistry,
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
    NeedsPlatformBatch { batch: PlatformActionBatch },
    BlockedByConflict { error: LomoError },
    Completed,
    Failed { error: LomoError },
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
        let runtime = ActorRuntime {
            journal_path: prepared.journal_path,
            journal: prepared.journal,
            state: Arc::clone(&state),
            events: event_sender,
            monotonic_deadlines: prepared.monotonic_deadlines,
            exchange_root: config.exchange_root().to_path_buf(),
            workspace: config.workspace().cloned(),
            drivers: config.drivers().clone(),
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
        Ok(Arc::new(Self {
            config,
            state,
            commands,
            listeners,
            next_listener_id: AtomicU64::new(1),
            actor: Mutex::new(Some(actor)),
        }))
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
    ReadJobResult {
        job_id: JobId,
        reply: mpsc::Sender<Result<Option<String>, LomoError>>,
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
        Command::ReadJobResult { job_id, reply } => {
            let response = read_job_result(runtime.journal.as_ref(), &job_id);
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
        PersistedJobStatus::WaitingPlatform => {}
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
        candidate
            .jobs
            .get_mut(job_index)
            .ok_or_else(job_missing)?
            .status = PersistedJobStatus::Failed(error);
    } else {
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
        } else {
            let driver_kind = candidate
                .jobs
                .get(job_index)
                .ok_or_else(job_missing)?
                .driver_kind
                .clone();
            if let Some(driver_kind) = driver_kind {
                if let Err(error) =
                    apply_driver_advance(runtime, &mut candidate, job_index, &driver_kind, result)
                {
                    candidate
                        .jobs
                        .get_mut(job_index)
                        .ok_or_else(job_missing)?
                        .status = PersistedJobStatus::Failed(error);
                }
            } else {
                candidate
                    .jobs
                    .get_mut(job_index)
                    .ok_or_else(job_missing)?
                    .status = PersistedJobStatus::Completed;
            }
        }
    }

    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    if !matches!(
        poll_job(runtime.journal.as_ref(), job_id)?,
        JobStep::NeedsPlatformBatch { .. }
    ) {
        runtime.monotonic_deadlines.remove(job_id);
    }
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
        }
        DriverAdvance::Done { result_json } => {
            job.driver_state_json = None;
            job.result_json = Some(result_json);
            job.status = PersistedJobStatus::Completed;
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
    let active_count = current
        .jobs
        .iter()
        .filter(|job| matches!(job.status, PersistedJobStatus::WaitingPlatform))
        .count();
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
    });

    if !completed_immediately {
        runtime
            .monotonic_deadlines
            .insert(job_id.clone(), Instant::now() + deadline);
    }
    commit_candidate(runtime, candidate, Some(job_id.clone()))?;
    Ok(job_id)
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
                && matches!(job.status, PersistedJobStatus::WaitingPlatform)
            {
                job.status = PersistedJobStatus::Failed(deadline_error());
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
}

impl JobRecord {
    fn step(&self) -> JobStep {
        match &self.status {
            PersistedJobStatus::WaitingPlatform => JobStep::NeedsPlatformBatch {
                batch: self.batch.clone(),
            },
            PersistedJobStatus::Completed => JobStep::Completed,
            PersistedJobStatus::Failed(error) => JobStep::Failed {
                error: error.clone(),
            },
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
enum PersistedJobStatus {
    WaitingPlatform,
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
        .filter(|job| matches!(job.status, PersistedJobStatus::WaitingPlatform))
        .map(|job| {
            let remaining = job.deadline_epoch_millis.saturating_sub(now_wall);
            Ok((job.job_id.clone(), now + Duration::from_millis(remaining)))
        })
        .collect()
}

fn expire_wall_clock_jobs(journal: &mut JournalState, now: u64) {
    for job in &mut journal.jobs {
        if matches!(job.status, PersistedJobStatus::WaitingPlatform)
            && job.deadline_epoch_millis <= now
        {
            job.status = PersistedJobStatus::Failed(deadline_error());
        }
    }
    journal.lifecycle = lifecycle_for(journal);
}

fn retain_bounded_terminals(journal: &mut JournalState) {
    let terminal_count = journal
        .jobs
        .iter()
        .filter(|job| !matches!(job.status, PersistedJobStatus::WaitingPlatform))
        .count();
    let mut to_remove = terminal_count.saturating_sub(MAX_TERMINAL_JOBS);
    journal.jobs.retain(|job| {
        if to_remove > 0 && !matches!(job.status, PersistedJobStatus::WaitingPlatform) {
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
    let active_count = state
        .jobs
        .iter()
        .filter(|job| matches!(job.status, PersistedJobStatus::WaitingPlatform))
        .count();
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

/// Exclusive workspace ownership without first-party `unsafe`.
///
/// Rust std `File::try_lock` is stubbed on Android, so this uses an atomic `create_dir` lock
/// directory plus a pid owner record. Process death leaves the directory; the next open reclaims
/// it only when `/proc/<pid>` is gone, so recovery is possible without a permanent sentinel.
struct WorkspaceLock {
    path: PathBuf,
}

impl WorkspaceLock {
    fn acquire(control_directory: &Path) -> Result<Self, LomoError> {
        let path = control_directory.join("engine.lock");
        match try_create_lock_dir(&path) {
            Ok(()) => {
                write_lock_owner(&path)?;
                Ok(Self { path })
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                if !reclaim_stale_lock_dir(&path)? {
                    return Err(LomoError::busy(
                        "workspace_busy",
                        "workspace is already owned by another active engine",
                    ));
                }
                try_create_lock_dir(&path).map_err(|retry_error| {
                    if retry_error.kind() == std::io::ErrorKind::AlreadyExists {
                        LomoError::busy(
                            "workspace_busy",
                            "workspace is already owned by another active engine",
                        )
                    } else {
                        LomoError::storage(
                            "workspace_lock_unavailable",
                            format!(
                                "workspace lock cannot be created after reclaim: {retry_error}"
                            ),
                        )
                    }
                })?;
                write_lock_owner(&path)?;
                Ok(Self { path })
            }
            Err(error) => Err(LomoError::storage(
                "workspace_lock_unavailable",
                format!("workspace lock cannot be created: {error}"),
            )),
        }
    }
}

impl Drop for WorkspaceLock {
    fn drop(&mut self) {
        // Best-effort release; process death is recovered by stale reclaim on next open.
        drop(fs::remove_dir_all(&self.path));
    }
}

fn try_create_lock_dir(path: &Path) -> std::io::Result<()> {
    fs::create_dir(path)
}

fn write_lock_owner(path: &Path) -> Result<(), LomoError> {
    let owner = path.join("owner.pid");
    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&owner)
        .map_err(|error| {
            LomoError::storage(
                "workspace_lock_unavailable",
                format!("workspace lock owner cannot be written: {error}"),
            )
        })?;
    write!(file, "{}", std::process::id()).map_err(|error| {
        LomoError::storage(
            "workspace_lock_unavailable",
            format!("workspace lock owner cannot be written: {error}"),
        )
    })?;
    file.sync_all().map_err(|error| {
        LomoError::storage(
            "workspace_lock_unavailable",
            format!("workspace lock owner cannot be synced: {error}"),
        )
    })?;
    Ok(())
}

fn reclaim_stale_lock_dir(path: &Path) -> Result<bool, LomoError> {
    let owner_path = path.join("owner.pid");
    let raw = match fs::read_to_string(&owner_path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            // Incomplete lock from a crashed creator — safe to reclaim.
            fs::remove_dir_all(path).map_err(|remove_error| {
                LomoError::storage(
                    "workspace_lock_unavailable",
                    format!("stale workspace lock without owner cannot be removed: {remove_error}"),
                )
            })?;
            return Ok(true);
        }
        Err(error) => {
            return Err(LomoError::storage(
                "workspace_lock_unavailable",
                format!("workspace lock owner cannot be read: {error}"),
            ));
        }
    };
    let pid = raw.trim().parse::<u32>().map_err(|_error| {
        LomoError::storage(
            "workspace_lock_unavailable",
            "workspace lock owner pid is malformed".to_owned(),
        )
    })?;
    if process_is_alive(pid) {
        return Ok(false);
    }
    fs::remove_dir_all(path).map_err(|error| {
        LomoError::storage(
            "workspace_lock_unavailable",
            format!("stale workspace lock cannot be removed: {error}"),
        )
    })?;
    Ok(true)
}

fn process_is_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    // Linux/Android: presence of /proc/<pid> is a pure-safe liveness probe.
    Path::new("/proc").join(pid.to_string()).exists()
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
    if envelope.magic != JOURNAL_MAGIC || envelope.schema != JOURNAL_SCHEMA {
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
    let state: JournalState = serde_json::from_str(&envelope.payload).map_err(|_error| {
        LomoError::corruption(
            "journal_payload_invalid",
            "engine journal payload is malformed",
        )
    })?;
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
