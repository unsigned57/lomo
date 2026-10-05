//! Effect execution lanes: per-lane worker threads with nonblocking
//! admission, pending-bound cancellation and supervision (I3).
//!
//! The UI thread never waits on execution: `Scheduler::submit` is a try-send
//! that coalesces same-class queued work or refuses when the lane is full —
//! the caller turns a refusal into a `Failed` receipt so the pending intent
//! resolves visibly instead of wedging. Every job carries the `CancelToken`
//! minted by `Pending::register`; a request revoked while queued — or while
//! running — never reaches its executor, so stale work is dropped before
//! execution rather than discarded after.
//!
//! Supervision: a job panic is answered with a `Failed` receipt, then the
//! lane dies loudly — `WorkerDied` lands on the UI at bootstrap severity and
//! queued work is drained as `Failed` receipts rather than abandoned in a
//! zombie queue (F-06).
use std::{
    collections::VecDeque,
    io::Write as _,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc, Condvar, Mutex, MutexGuard, PoisonError,
        mpsc::{self, Receiver, SyncSender},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use crate::{
    effects::{Effect, Lane, MergeKey, RuntimeMessage},
    error::TuiError,
    model::{CancelToken, Pending},
    ops::{RuntimeSlot, SlotWait, TuiRuntime},
};

/// Per-lane admission depth. Bounded queues apply backpressure as refusals —
/// never as blocking sends on the UI path.
pub const LANE_QUEUE_DEPTH: usize = 256;
/// Producer observations (watcher/player/supervision) retry delivery up to
/// this bound, then leave a stderr trace — they are never silently dropped
/// (F-08).
const REPORT_TIMEOUT: Duration = Duration::from_millis(250);
/// Poll granularity while a bounded join waits on a finishing worker.
const JOIN_STEP: Duration = Duration::from_millis(5);

/// The work one lane job performs — `ops::execute` in production; injected in
/// tests so scheduling is provable without a live workspace.
pub type Runner = Arc<
    dyn Fn(&TuiRuntime, &Effect, &Outbox, &CancelToken) -> Result<RuntimeMessage, TuiError>
        + Send
        + Sync,
>;

/// Every producer→UI message flows through the outbox: worker replies and
/// producer observations share one bounded channel so the event loop drains
/// exactly one inbox.
#[derive(Clone)]
pub struct Outbox {
    sender: SyncSender<RuntimeMessage>,
}

impl Outbox {
    #[must_use]
    pub const fn new(sender: SyncSender<RuntimeMessage>) -> Self {
        Self { sender }
    }

    /// A receipt reply: bounded backpressure is honest here — the event loop
    /// drains every pass, so a live UI always makes room and a dead one ends
    /// the producer. Returns `false` when the UI receiver is gone — the
    /// producer should stop.
    #[must_use]
    pub fn reply(&self, message: RuntimeMessage) -> bool {
        self.sender.send(message).is_ok()
    }

    /// An observational report (watcher signal, player exit, worker death):
    /// retries `try_send` up to `REPORT_TIMEOUT` for drain room, then leaves
    /// a stderr trace. Returns `false` when the message could not be
    /// delivered — the report is traced, never silently dropped (F-08).
    #[must_use]
    pub fn report(&self, message: RuntimeMessage) -> bool {
        let deadline = Instant::now() + REPORT_TIMEOUT;
        let mut message = message;
        loop {
            match self.sender.try_send(message) {
                Ok(()) => return true,
                Err(mpsc::TrySendError::Full(returned)) => {
                    message = returned;
                    if Instant::now() >= deadline {
                        drop(writeln!(
                            std::io::stderr(),
                            "lomo: observational report could not be delivered: inbox saturated"
                        ));
                        return false;
                    }
                    thread::yield_now();
                }
                Err(mpsc::TrySendError::Disconnected(_)) => {
                    drop(writeln!(
                        std::io::stderr(),
                        "lomo: observational report could not be delivered: UI receiver gone"
                    ));
                    return false;
                }
            }
        }
    }
}

/// A producer destroyed while its thread unwinds a panic never reached its
/// `report`. The outbox's last act delivers the one observation whose
/// absence permanently wedges the UI — the graphics verdict that gates
/// stdin (F-IMG-3): a dead probe means fail-closed `Unsupported`, so the
/// gate always lifts. `thread::panicking` scopes this to a real thread
/// death; an ordinary drop — clone teardown, worker exit, shutdown —
/// sends nothing.
///
/// The diagnostic is the shared
/// [`PROBE_DIED_DIAGNOSTIC`](crate::graphics::PROBE_DIED_DIAGNOSTIC): ANY
/// holder's unwind fabricates it — a lane tail, the watcher, the detached
/// player monitor — so the message seam treats it as a stand-in for a
/// missing first answer, never as terminal truth: it lands fail-closed on a
/// `Probing` gate, yields to the surviving probe's real answer, and
/// degrades once a verdict has already landed (13-T-01).
impl Drop for Outbox {
    fn drop(&mut self) {
        if thread::panicking() {
            let _delivered = self.report(RuntimeMessage::GraphicsDetected {
                verdict: crate::graphics::GraphicsVerdict::Unsupported {
                    diagnostic: crate::graphics::PROBE_DIED_DIAGNOSTIC.to_owned(),
                },
            });
        }
    }
}

/// The admission outcome for one submitted effect.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Submit {
    /// The job is queued and will run while its request stays live.
    Queued,
    /// The request was already revoked — the job was dropped before
    /// execution, which is exactly what a dead request means.
    Dropped,
    /// No worker can take the job: the lane is saturated or dead. The caller
    /// must answer the request itself so its pending intent resolves as a
    /// visible failure rather than wedging.
    Refused(Refusal),
}

/// Why a lane refused a job.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Refusal {
    /// The bounded queue is full — same-class work was already coalesced and
    /// the backlog still has no room.
    Saturated,
    /// The lane's worker is gone; queued work was failed at death and new
    /// work is refused outright.
    Dead,
}

/// One queued unit of lane work: the effect plus the cancellation flag the
/// pending registry gave it, and the supersede class a newer admission uses
/// to drop it before it runs.
struct Job {
    effect: Effect,
    key: Option<MergeKey>,
    token: CancelToken,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum LaneState {
    Open,
    Closed,
    Dead,
}

struct LaneInner {
    queue: VecDeque<Job>,
    state: LaneState,
}

/// One lane's bounded job queue plus its wake edge.
struct LaneQueue {
    inner: Mutex<LaneInner>,
    ready: Condvar,
}

impl LaneQueue {
    const fn new() -> Self {
        Self {
            inner: Mutex::new(LaneInner {
                queue: VecDeque::new(),
                state: LaneState::Open,
            }),
            ready: Condvar::new(),
        }
    }

    fn lock(&self) -> MutexGuard<'_, LaneInner> {
        // A poisoned queue mutex is still readable state — a worker that
        // panicked while holding it must not take the lane down a second way.
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Admit one job: sweep dead tokens, drop same-class queued work (the
    /// newest request always wins), refuse a full queue — never block.
    ///
    /// Reconcile is the exception to newest-wins: its queued job survives and
    /// absorbs the incoming observed-path set, because dropping the older path
    /// set would leave an attested change unverified. The newer request's
    /// intent is cancelled instead — one union reconcile answers both.
    fn enqueue(&self, job: Job, pending: &mut Pending) -> Submit {
        let mut inner = self.lock();
        if inner.state != LaneState::Open {
            return Submit::Refused(Refusal::Dead);
        }
        let mut queue = VecDeque::with_capacity(inner.queue.len() + 1);
        let mut merged = false;
        for mut queued in inner.queue.drain(..) {
            if queued.token.is_cancelled() {
                continue;
            }
            if job.key.is_some() && queued.key == job.key {
                if job.key == Some(MergeKey::Reconcile) {
                    if let (
                        Effect::Reconcile { observed: keep, .. },
                        Effect::Reconcile {
                            observed: incoming, ..
                        },
                    ) = (&mut queued.effect, &job.effect)
                    {
                        *keep = merge_observed(keep.take(), incoming.as_deref());
                    }
                    queue.push_back(queued);
                    // The absorbed request's intent dies here; its coverage
                    // lives on inside the queued job's union.
                    drop(pending.cancel(job.effect.req()));
                    merged = true;
                    continue;
                }
                // Superseded before it ever ran — revoke the intent so the
                // pending registry and the lane agree the request is dead.
                drop(pending.cancel(queued.effect.req()));
                continue;
            }
            queue.push_back(queued);
        }
        if !merged {
            if queue.len() >= LANE_QUEUE_DEPTH {
                inner.queue = queue;
                return Submit::Refused(Refusal::Saturated);
            }
            queue.push_back(job);
        }
        inner.queue = queue;
        drop(inner);
        self.ready.notify_one();
        Submit::Queued
    }

    /// The worker's next job; `None` once the lane is closed and drained.
    fn pop(&self) -> Option<Job> {
        let mut inner = self.lock();
        loop {
            if let Some(job) = inner.queue.pop_front() {
                return Some(job);
            }
            if inner.state != LaneState::Open {
                return None;
            }
            inner = self
                .ready
                .wait(inner)
                .unwrap_or_else(PoisonError::into_inner);
        }
    }

    /// Worker exit edge: mark the lane dead and hand back everything still
    /// queued so each request gets its `Failed` receipt instead of being
    /// abandoned silently.
    fn mark_dead(&self) -> Vec<Job> {
        let mut inner = self.lock();
        inner.state = LaneState::Dead;
        inner.queue.drain(..).collect()
    }

    /// Shutdown edge: queued work drains, then the worker exits.
    fn close(&self) {
        let mut inner = self.lock();
        inner.state = LaneState::Closed;
        drop(inner);
        self.ready.notify_all();
    }
}

struct Lanes {
    query: Arc<LaneQueue>,
    mutate: Arc<LaneQueue>,
    maint: Arc<LaneQueue>,
    io: Arc<LaneQueue>,
}

impl Lanes {
    fn new() -> Self {
        Self {
            query: Arc::new(LaneQueue::new()),
            mutate: Arc::new(LaneQueue::new()),
            maint: Arc::new(LaneQueue::new()),
            io: Arc::new(LaneQueue::new()),
        }
    }

    const fn queue(&self, lane: Lane) -> &Arc<LaneQueue> {
        match lane {
            Lane::Query => &self.query,
            Lane::Mutate => &self.mutate,
            Lane::Maint => &self.maint,
            Lane::Io => &self.io,
        }
    }

    fn for_each(&self, mut visit: impl FnMut(Lane, &Arc<LaneQueue>)) {
        for lane in Lane::ALL {
            visit(lane, self.queue(lane));
        }
    }
}

/// Why a lane worker's run loop ended.
enum Exit {
    /// Closed or the UI receiver is gone — an ordinary shutdown.
    Closed,
    /// A job panicked: the lane dies so the failure surfaces instead of a
    /// half-alive queue.
    Died(String),
}

fn panic_text(payload: &(dyn std::any::Any + Send)) -> &str {
    payload.downcast_ref::<&str>().map_or_else(
        || {
            payload
                .downcast_ref::<String>()
                .map_or("non-string panic payload", String::as_str)
        },
        |value| *value,
    )
}

/// Union two watcher-attested observed sets: `None` (unattested coverage)
/// dominates, and `Some ∪ Some` deduplicates by canonical path string.
fn merge_observed(
    keep: Option<Vec<lomo_core::RelativeWorkspacePath>>,
    incoming: Option<&[lomo_core::RelativeWorkspacePath]>,
) -> Option<Vec<lomo_core::RelativeWorkspacePath>> {
    match (keep, incoming) {
        (Some(mut left), Some(right)) => {
            left.extend(right.iter().cloned());
            left.sort_by(|a, b| a.as_str().cmp(b.as_str()));
            left.dedup();
            Some(left)
        }
        _ => None,
    }
}

/// Runs one lane job against the runtime slot: bootstrap fills the slot, quit
/// answers without a workspace (the closing handshake must not park behind an
/// unfinished open), and every other job waits for readiness — a revoked token
/// or a failed bootstrap resolves it before the runner ever sees stale work.
/// `Ok(None)` means the request died while parked and must not emit a receipt.
fn run_job(
    slot: &RuntimeSlot,
    job: &Job,
    runner: &Runner,
    outbox: &Outbox,
) -> Result<Option<RuntimeMessage>, TuiError> {
    // Bootstrap and Quit never wait on the slot — one creates the runtime,
    // the other must complete even after a failed open.
    if let Effect::Bootstrap { req, spec } = &job.effect {
        return crate::ops::bootstrap(slot, spec, *req, outbox).map(Some);
    }
    // `SetupConfirmed` is a UI-side signal: the host rewrites it into
    // `Bootstrap{launch: Mint}` before dispatch. One reaching a lane means a
    // dispatch bug — refuse loudly instead of parking on the slot forever.
    if let Effect::SetupConfirmed { .. } = &job.effect {
        return Err(TuiError::config(
            "setup confirmation reached a lane without its bootstrap spec",
        ));
    }
    if let Effect::Quit { req } = &job.effect {
        return Ok(Some(RuntimeMessage::QuitReady { req: *req }));
    }
    let runtime = match slot.wait_ready(&job.token) {
        SlotWait::Ready(runtime) => runtime,
        SlotWait::Cancelled => return Ok(None),
        SlotWait::Failed(diagnostic) => {
            return Err(TuiError::config(format!(
                "workspace runtime unavailable: {diagnostic}"
            )));
        }
    };
    runner(&runtime, &job.effect, outbox, &job.token).map(Some)
}

/// One lane worker: pop → skip revoked → run → skip revoked → reply. The
/// second token check drops the product of work cancelled mid-run — the
/// pending intent is already dead, so its reply would only degrade.
fn run_lane(
    lane: Lane,
    queue: &LaneQueue,
    slot: &RuntimeSlot,
    runner: &Runner,
    outbox: &Outbox,
) -> Exit {
    loop {
        let Some(job) = queue.pop() else {
            return Exit::Closed;
        };
        if job.token.is_cancelled() {
            continue;
        }
        let req = job.effect.req();
        let ran = catch_unwind(AssertUnwindSafe(|| run_job(slot, &job, runner, outbox)));
        let reply = match ran {
            Ok(Ok(Some(reply))) => reply,
            // The request died while parked on the runtime slot — no receipt.
            Ok(Ok(None)) => continue,
            Ok(Err(error)) => RuntimeMessage::Failed {
                req,
                diagnostic: error.to_string(),
            },
            Err(payload) => {
                if !job.token.is_cancelled() {
                    // `false` means the UI is gone — the lane is dying anyway.
                    let _delivered = outbox.reply(RuntimeMessage::Failed {
                        req,
                        diagnostic: format!(
                            "effect worker panicked in lane '{}': {}",
                            lane.label(),
                            panic_text(payload.as_ref())
                        ),
                    });
                }
                return Exit::Died(format!(
                    "effect lane '{}' panicked: {}",
                    lane.label(),
                    panic_text(payload.as_ref())
                ));
            }
        };
        if job.token.is_cancelled() {
            continue;
        }
        if !outbox.reply(reply) {
            return Exit::Closed;
        }
    }
}

/// Four supervised lanes sharing one bounded reply channel.
pub struct Scheduler {
    lanes: Lanes,
    replies: Receiver<RuntimeMessage>,
    outbox: Outbox,
    workers: Vec<(Lane, JoinHandle<()>)>,
}

impl Scheduler {
    /// Spawn the production lanes driven by `ops::execute`.
    ///
    /// The lanes resolve the workspace from `slot` per job: before bootstrap
    /// installs it they park, after a failed open they answer `Failed` — the
    /// UI thread never waits on either edge.
    ///
    /// # Errors
    /// Thread spawn failures.
    pub fn spawn(slot: &Arc<RuntimeSlot>) -> Result<Self, TuiError> {
        let runner: Runner = Arc::new(crate::ops::execute);
        Self::spawn_with(slot, &runner)
    }

    /// Spawn lanes driven by an injected runner — the scheduling contract is
    /// provable without a live workspace. `slot` may be [`RuntimeSlot::ready`]
    /// or still `opening`; runtime-dependent jobs wait on its ready edge.
    ///
    /// # Errors
    /// Thread spawn failures.
    pub fn spawn_with(slot: &Arc<RuntimeSlot>, runner: &Runner) -> Result<Self, TuiError> {
        let (sender, replies) = mpsc::sync_channel(LANE_QUEUE_DEPTH);
        let outbox = Outbox::new(sender);
        let lanes = Lanes::new();
        let mut workers = Vec::with_capacity(Lane::ALL.len());
        for lane in Lane::ALL {
            let queue = Arc::clone(lanes.queue(lane));
            let slot = Arc::clone(slot);
            let outbox = outbox.clone();
            let runner = Arc::clone(runner);
            let name = format!("lomo-bg-lane-{}", lane.label());
            let handle = thread::Builder::new()
                .name(name)
                .spawn(move || {
                    let ran = catch_unwind(AssertUnwindSafe(|| {
                        run_lane(lane, &queue, &slot, &runner, &outbox)
                    }));
                    let died = match ran {
                        Ok(Exit::Closed) => None,
                        Ok(Exit::Died(diagnostic)) => Some(diagnostic),
                        Err(payload) => Some(format!(
                            "effect lane '{}' panicked: {}",
                            lane.label(),
                            panic_text(payload.as_ref())
                        )),
                    };
                    let Some(diagnostic) = died else {
                        return;
                    };
                    // `false` is already traced inside `report` — the UI is
                    // gone and there is no one left to tell.
                    let _delivered = outbox.report(RuntimeMessage::WorkerDied {
                        lane,
                        diagnostic: diagnostic.clone(),
                    });
                    for job in queue.mark_dead() {
                        if job.token.is_cancelled() {
                            continue;
                        }
                        // `false` is already traced inside `report` — the
                        // queued request still got its Failed receipt attempt.
                        let _delivered = outbox.report(RuntimeMessage::Failed {
                            req: job.effect.req(),
                            diagnostic: format!(
                                "{diagnostic} — queued request dropped with the lane"
                            ),
                        });
                    }
                })
                .map_err(TuiError::from)?;
            workers.push((lane, handle));
        }
        Ok(Self {
            lanes,
            replies,
            outbox,
            workers,
        })
    }

    /// Admit one effect to its lane — a try-send that never blocks the caller.
    /// Same-class queued work is dropped (its pending intent cancelled) so the
    /// newest request always wins; a revoked request is dropped outright —
    /// `Pending` keeps the tripped token as the tombstone, so a request
    /// cancelled between issue and dispatch can never be re-minted live
    /// (09-F-03).
    pub fn submit(&self, pending: &mut Pending, effect: Effect) -> Submit {
        let req = effect.req();
        let token = pending.token(req).unwrap_or_else(CancelToken::live);
        if token.is_cancelled() {
            return Submit::Dropped;
        }
        let job = Job {
            key: effect.merge_key(),
            effect,
            token,
        };
        self.lanes.queue(job.effect.lane()).enqueue(job, pending)
    }

    /// The single drain point for worker replies and producer observations.
    #[must_use]
    pub const fn replies(&self) -> &Receiver<RuntimeMessage> {
        &self.replies
    }

    /// A producer handle for background threads (watcher, player monitors).
    #[must_use]
    pub fn outbox(&self) -> Outbox {
        self.outbox.clone()
    }

    /// Close every lane and join its worker within `budget`. Replies are
    /// drained while waiting so a worker blocked on a full channel still
    /// exits; a worker that misses the deadline is detached, not awaited
    /// forever (F-06).
    ///
    /// # Errors
    /// A worker panicked or failed to stop inside the budget.
    pub fn finish(self, budget: Duration) -> Result<(), TuiError> {
        let deadline = Instant::now() + budget;
        self.lanes.for_each(|_, queue| queue.close());
        let mut first_error = None;
        for (lane, handle) in self.workers {
            while !handle.is_finished() {
                if Instant::now() >= deadline {
                    if first_error.is_none() {
                        first_error = Some(TuiError::io(format!(
                            "effect lane '{}' did not stop within the shutdown budget",
                            lane.label()
                        )));
                    }
                    break;
                }
                // Keep the reply channel drained — a worker parked on a full
                // outbox would otherwise wedge the join.
                while self.replies.try_recv().is_ok() {}
                thread::sleep(JOIN_STEP);
            }
            if !handle.is_finished() {
                continue;
            }
            if let Err(error) = handle.join()
                && first_error.is_none()
            {
                first_error = Some(TuiError::io(format!(
                    "effect lane '{}' panicked: {error:?}",
                    lane.label()
                )));
            }
        }
        if let Some(error) = first_error {
            return Err(error);
        }
        Ok(())
    }
}
