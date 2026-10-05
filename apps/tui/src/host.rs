//! Terminal lifecycle and background execution of application effects.
//!
//! The UI thread never blocks on execution: effects are admitted to the
//! lane `Scheduler` by try-send, refused or coalesced work resolves as a
//! `Failed` receipt, and every reply drains through one outbox. Shutdown is
//! bounded — `CloseGate` forces the exit on a second quit keystroke and
//! every worker join carries the same budget (F-06).
use crate::{
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    event::{Command, command_from_key, command_from_paste},
    executor::{Refusal, Scheduler, Submit},
    model::{AppModel, BadgeClass, InputMode, Notice, Severity},
};
use crossterm::{
    event::{
        self, DisableBracketedPaste, DisableFocusChange, DisableMouseCapture, EnableBracketedPaste,
        EnableFocusChange, EnableMouseCapture, Event, MouseEventKind,
    },
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use ratatui::{Terminal, backend::CrosstermBackend};
use std::{
    collections::BTreeMap,
    io::{self, stdout},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

type HostTerminal = Terminal<CrosstermBackend<io::Stdout>>;

/// Terminal features the reported terminal can honor. A missing or `dumb`
/// `TERM` enables nothing; feature enables are never emitted on faith.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct TerminalCapabilities {
    pub mouse: bool,
    pub paste: bool,
    pub focus: bool,
}

impl TerminalCapabilities {
    #[must_use]
    pub fn detect(env: &BTreeMap<String, String>) -> Self {
        let term = env.get("TERM").map_or("", String::as_str);
        let capable = !term.is_empty() && term != "dumb";
        Self {
            mouse: capable,
            paste: capable,
            focus: capable,
        }
    }
}

/// The quit handshake is bounded (F-06): `Quit` begins it, `QuitReady` ends
/// it, and a wedged worker may hold it open at most this long before the
/// loop leaves anyway. A second quit keystroke — Ctrl+C included — forces
/// the exit immediately; `closing` never swallows the force path.
const CLOSE_BUDGET: Duration = Duration::from_millis(1500);

/// Media orphan sweep cadence (D-12): the first run waits for startup work
/// to settle, subsequent runs keep housekeeping low-priority on the Maint
/// lane — a sweep never delays an interactive query.
const MEDIA_SWEEP_INITIAL_DELAY: Duration = Duration::from_secs(30);
const MEDIA_SWEEP_INTERVAL: Duration = Duration::from_mins(30);

/// The probe owns stdin only inside a bounded window: its own query/response
/// parses with a ~1s budget, so a thread that neither reports nor dies
/// inside `PROBE_BUDGET` is wedged — the loop, which owns the gate, lands
/// the failure verdict itself (F-IMG-3).
const PROBE_BUDGET: Duration = Duration::from_secs(5);

/// The closing handshake as an explicit, probe-able state machine: while
/// `begin` has run and `reopen` has not, every further quit command is a
/// force-exit and the handshake expires after `CLOSE_BUDGET`.
#[derive(Clone, Debug, Default)]
pub struct CloseGate {
    since: Option<Instant>,
}

impl CloseGate {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }
    /// Whether the session is draining toward exit.
    #[must_use]
    pub const fn is_closing(&self) -> bool {
        self.since.is_some()
    }
    /// Begin the handshake — the next `QuitReady` ends the loop.
    pub fn begin(&mut self) {
        self.since = Some(Instant::now());
    }
    /// The handshake aborted (a draft persist failed): the session reopens
    /// instead of exiting past the failure.
    pub const fn reopen(&mut self) {
        self.since = None;
    }
    /// A second quit command while closing is the operator forcing the exit —
    /// it must leave immediately, not queue behind the handshake (F-06).
    #[must_use]
    pub fn forces_exit(&self, command: &Command) -> bool {
        self.is_closing() && *command == Command::Quit
    }
    /// Whether the handshake budget is spent — a wedged quit may not suspend
    /// the loop past this bound.
    #[must_use]
    pub fn expired(&self, now: Instant) -> bool {
        self.since
            .is_some_and(|since| now.saturating_duration_since(since) >= CLOSE_BUDGET)
    }
}

/// The watcher's accumulated observation: workspace-relative changed paths, or
/// `full` once any event forfeits provable coverage (rescan, invalidation, or a
/// path that cannot be mapped back into the workspace).
#[derive(Default)]
struct ObservedPaths {
    paths: std::collections::BTreeSet<String>,
    full: bool,
}

impl ObservedPaths {
    /// One drained batch becomes the next `FsChanged` payload: `None` attests
    /// only that something changed and forces the full scan.
    fn take(&mut self) -> Option<Vec<lomo_core::RelativeWorkspacePath>> {
        if self.full {
            self.full = false;
            self.paths.clear();
            return None;
        }
        let mut paths = Vec::with_capacity(self.paths.len());
        for raw in std::mem::take(&mut self.paths) {
            // `absorb` only stores strings it already parsed — a failure here
            // means a canonical-path rule changed, so coverage is forfeited
            // rather than silently dropped.
            let Ok(path) = lomo_core::RelativeWorkspacePath::parse(&raw) else {
                self.paths.clear();
                return None;
            };
            paths.push(path);
        }
        Some(paths)
    }

    /// Re-claims a payload whose `FsChanged` could not be delivered — the
    /// thread exits after that, but restoring keeps the failure honest if
    /// the loop is ever allowed to continue.
    fn restore(&mut self, payload: Option<Vec<lomo_core::RelativeWorkspacePath>>) {
        match payload {
            Some(paths) => self
                .paths
                .extend(paths.iter().map(|path| path.as_str().to_owned())),
            None => self.full = true,
        }
    }

    fn absorb(
        &mut self,
        root: &std::path::Path,
        events: &[lomo_platform_fs::DirectoryChangeEvent],
    ) {
        use lomo_platform_fs::ChangeKind;
        for event in events {
            match event.kind {
                ChangeKind::Rescan | ChangeKind::Invalidated => self.full = true,
                ChangeKind::Created
                | ChangeKind::Modified
                | ChangeKind::Deleted
                | ChangeKind::Renamed => {
                    // Watcher paths are absolute; only a workspace-relative
                    // rendering can attest coverage — anything else forfeits it.
                    let Ok(relative) = event.path.strip_prefix(root) else {
                        self.full = true;
                        continue;
                    };
                    let Some(raw) = relative.to_str() else {
                        self.full = true;
                        continue;
                    };
                    let Ok(path) = lomo_core::RelativeWorkspacePath::parse(raw) else {
                        self.full = true;
                        continue;
                    };
                    self.paths.insert(path.as_str().to_owned());
                }
            }
        }
    }
}

/// Workspace observation runs on its own thread; every drained batch becomes
/// one `FsChanged` carrying its attested path set, never one message per event.
/// `pending` coalesces batches while the UI has not consumed the previous one —
/// the accumulator keeps the union, so a queued flag is the whole backlog.
///
/// Every signal is a `report`, not a dropped send: a saturated inbox leaves
/// a trace (F-08), and an undeliverable channel stops the thread — the
/// periodic `is_finished` check in `tick` engages the reconcile fallback
/// (F-07) either way.
struct Watcher {
    stop: Arc<AtomicBool>,
    handle: thread::JoinHandle<()>,
}
impl Watcher {
    /// One watcher thread observes both the workspace (→ `FsChanged`) and the
    /// config directory (→ `ConfigChanged`), so config.toml edits land through
    /// the same request/reply machinery as workspace changes.
    fn spawn(
        workspace: &std::path::Path,
        config_dir: &std::path::Path,
        outbox: crate::executor::Outbox,
        pending: Arc<AtomicBool>,
        config_pending: Arc<AtomicBool>,
    ) -> Result<Self, TuiError> {
        let stop = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stop);
        let root = workspace.to_path_buf();
        let config_root = config_dir.to_path_buf();
        let handle = thread::Builder::new()
            .name("lomo-bg-watcher".to_owned())
            .spawn(move || {
                let ran = catch_unwind(AssertUnwindSafe(|| {
                    watch_loop(
                        &root,
                        &config_root,
                        &outbox,
                        &pending,
                        &config_pending,
                        &flag,
                    );
                }));
                if let Err(payload) = ran {
                    // `false` is traced inside `report`; the thread is exiting.
                    let _delivered = outbox.report(RuntimeMessage::WatcherUnavailable {
                        diagnostic: format!("workspace watcher panicked: {payload:?}"),
                    });
                }
            })
            .map_err(TuiError::from)?;
        Ok(Self { stop, handle })
    }
    /// Whether the watcher thread is still running — the tick-level
    /// supervision edge that engages the reconcile fallback when the watcher
    /// dies without (or after) reporting.
    fn alive(&self) -> bool {
        !self.handle.is_finished()
    }
    fn finish(self, budget: Duration) -> Result<(), TuiError> {
        self.stop.store(true, Ordering::Relaxed);
        let deadline = Instant::now() + budget;
        while !self.handle.is_finished() {
            if Instant::now() >= deadline {
                return Err(TuiError::io(
                    "workspace watcher did not stop within the shutdown budget",
                ));
            }
            thread::sleep(Duration::from_millis(5));
        }
        self.handle
            .join()
            .map_err(|error| TuiError::io(format!("workspace watcher panicked: {error:?}")))
    }
}

fn watch_loop(
    root: &std::path::Path,
    config_root: &std::path::Path,
    outbox: &crate::executor::Outbox,
    pending: &Arc<AtomicBool>,
    config_pending: &Arc<AtomicBool>,
    flag: &Arc<AtomicBool>,
) {
    let mut watcher = match lomo_platform_fs::DirectoryWatcher::new(root) {
        Ok(watcher) => watcher,
        Err(error) => {
            // `false` is traced inside `report`; the thread is exiting.
            let _delivered = outbox.report(RuntimeMessage::WatcherUnavailable {
                diagnostic: error.to_string(),
            });
            return;
        }
    };
    // Config watching degrades independently: its absence disables live
    // reload but must never look like a workspace watch outage.
    let mut config_watcher = match lomo_platform_fs::DirectoryWatcher::new(config_root) {
        Ok(watcher) => Some(watcher),
        Err(error) => {
            let _delivered = outbox.report(RuntimeMessage::ConfigWatchUnavailable {
                diagnostic: error.to_string(),
            });
            None
        }
    };
    if !outbox.report(RuntimeMessage::WatcherReady) {
        return;
    }
    let mut observed = ObservedPaths::default();
    while !flag.load(Ordering::Relaxed) {
        match watcher.wait_events(Duration::from_millis(250)) {
            Ok(events) if events.is_empty() => {}
            Ok(events) => {
                observed.absorb(root, &events);
                if pending.swap(true, Ordering::AcqRel) {
                    // A delivered-but-unconsumed batch keeps accumulating —
                    // the next `FsChanged` carries the union.
                    continue;
                }
                let payload = observed.take();
                if !outbox.report(RuntimeMessage::FsChanged {
                    observed: payload.clone(),
                }) {
                    // The batch re-arms the accumulator for the next drain
                    // attempt; the thread exits — supervision engages the
                    // fallback either way (F-07/F-08).
                    observed.restore(payload);
                    pending.store(false, Ordering::Release);
                    return;
                }
            }
            Err(error) => {
                // `false` is traced inside `report`; the thread is exiting.
                let _delivered = outbox.report(RuntimeMessage::WatcherUnavailable {
                    diagnostic: error.to_string(),
                });
                return;
            }
        }
        // Drain config events after the workspace wait returns: a
        // `config.toml` touch reports once per batch, coalesced by the flag.
        let mut config_dead = false;
        if let Some(config_watch) = &mut config_watcher {
            match config_watch.wait_events(Duration::ZERO) {
                Ok(events) if events.is_empty() => {}
                Ok(events) => {
                    let touched = events.iter().any(|event| {
                        event
                            .path
                            .file_name()
                            .is_some_and(|name| name == "config.toml")
                    });
                    if touched
                        && !config_pending.swap(true, Ordering::AcqRel)
                        && !outbox.report(RuntimeMessage::ConfigChanged)
                    {
                        config_pending.store(false, Ordering::Release);
                        return;
                    }
                }
                Err(error) => {
                    let _delivered = outbox.report(RuntimeMessage::ConfigWatchUnavailable {
                        diagnostic: error.to_string(),
                    });
                    config_dead = true;
                }
            }
        }
        if config_dead {
            // The config watcher died — live reload is gone but manual
            // reload (F5 / Settings save) still works.
            config_watcher = None;
        }
    }
}

/// The session constants the loop borrows — bundling them keeps the loop
/// functions under the arity cap without dropping any one of them.
struct SessionCtx<'a> {
    slot: &'a crate::ops::RuntimeSlot,
    spec: &'a crate::ops::BootstrapSpec,
    capabilities: TerminalCapabilities,
}

struct LoopState {
    query: Option<(Instant, Effect)>,
    draft_changed: Instant,
    observed_revision: u64,
    /// The bounded quit handshake — closing is never a swallowed force-exit.
    close: CloseGate,
    /// The screen repaints only after an input or a reply may have changed the
    /// model — never unconditionally every poll interval.
    dirty: bool,
    /// The watcher lives here once spawned: eagerly for a `Ready` launch,
    /// on `RuntimeReady` for first-run setup (the workspace may not exist
    /// until the mint, so watching earlier would watch nothing).
    watcher: Option<Watcher>,
    /// Workspace root the (possibly deferred) watcher binds. `None` only while
    /// the first-run wizard is still open.
    watch_workspace: Option<std::path::PathBuf>,
    /// Config directory the watcher observes for `config.toml` changes.
    config_dir: std::path::PathBuf,
    /// Set by the watcher while an `FsChanged` is queued; cleared on delivery.
    fs_pending: Arc<AtomicBool>,
    /// Same coalescing flag for `ConfigChanged`.
    config_pending: Arc<AtomicBool>,
    /// Watcher supervision: death (reported or observed) engages the fallback.
    watcher_dead: bool,
    /// The fallback's dispatch clock — `None` arms an immediate reconcile.
    watcher_fallback_at: Option<Instant>,
    /// When the graphics probe thread started — the `tick` watchdog's clock:
    /// `Probing` surviving past `PROBE_BUDGET` lands an `Unsupported` verdict
    /// so a wedged probe can never hold stdin forever (F-IMG-3).
    probe_started: Instant,
    /// Media orphan sweep cadence (D-12): armed when the runtime lands;
    /// re-armed on every dispatch so housekeeping stays low-rate.
    sweep_at: Option<Instant>,
    /// The in-flight sweep's request — a second sweep never overlaps the
    /// first; cleared when its receipt lands.
    sweep_req: Option<crate::model::Req>,
}

/// # Errors
/// Terminal, worker or foreground process failures.
///
/// The UI loop starts on the `Loading` shell and submits `Effect::Bootstrap`
/// before anything else: the first frame draws while workspace verification
/// still runs on its lane (I5). `slot` carries the runtime once ready.
pub fn run(
    slot: &Arc<crate::ops::RuntimeSlot>,
    spec: &crate::ops::BootstrapSpec,
    model: &mut AppModel,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    let mut terminal = setup_terminal(capabilities)?;
    let scheduler = Scheduler::spawn(slot)?;
    let boot_req = model.request(crate::model::PendingKind::Bootstrap);
    model.view = crate::model::View::Loading {
        screen: crate::model::Screen::Timeline,
        req: boot_req,
    };
    match &spec.launch {
        // First run: the wizard owns the bootstrap request until the user
        // confirms — nothing is dispatched, minted or created before then.
        crate::ops::LaunchConfig::Setup(setup) => {
            model.input = InputMode::Setup(crate::model::SetupState::new(
                setup.file.clone(),
                setup.proposal.clone(),
                boot_req,
                spec.paths.home_dir.clone(),
            ));
        }
        crate::ops::LaunchConfig::Ready(_) | crate::ops::LaunchConfig::Mint { .. } => {
            dispatch(
                model,
                &scheduler,
                Effect::Bootstrap {
                    req: boot_req,
                    spec: spec.clone(),
                },
            );
        }
    }
    let fs_pending = Arc::new(AtomicBool::new(false));
    let config_pending = Arc::new(AtomicBool::new(false));
    // Watching starts before the runtime is ready when the workspace is known:
    // events during the open accumulate and reconcile once, scoped, as soon as
    // the slot is live. A first-run `Setup` has no workspace to watch yet — the
    // watcher binds when `RuntimeReady` lands (see `deliver`).
    let (watch_workspace, watcher) = match &spec.launch {
        crate::ops::LaunchConfig::Ready(config) => (
            Some(config.workspace.clone()),
            Some(Watcher::spawn(
                &config.workspace,
                &spec.paths.config_dir,
                scheduler.outbox(),
                Arc::clone(&fs_pending),
                Arc::clone(&config_pending),
            )?),
        ),
        crate::ops::LaunchConfig::Setup(_) => (None, None),
        crate::ops::LaunchConfig::Mint { config, .. } => (Some(config.workspace.clone()), None),
    };
    // The capability probe owns stdin until it answers (D-03): it writes the
    // kitty/sixel/cell-size queries and parses the replies with its own
    // timeout. The event loop must not read stdin until `GraphicsDetected`
    // lands — the gate lives in `event_loop`. The verdict is guaranteed:
    // a probe that dies mid-panic reports through the outbox's dying
    // declaration (`Drop`), and a wedged one is answered by `tick`'s
    // `PROBE_BUDGET` watchdog (F-IMG-3).
    let probe_started = Instant::now();
    match thread::Builder::new()
        .name("lomo-bg-probe".to_owned())
        .spawn({
            let outbox = scheduler.outbox();
            move || {
                let verdict = crate::graphics::TerminalProber::probe(&crate::graphics::StdioProber);
                // `false` is traced inside `report`; the thread is exiting.
                let _delivered = outbox.report(RuntimeMessage::GraphicsDetected { verdict });
            }
        }) {
        Ok(_handle) => {}
        Err(error) => {
            // A thread that cannot spawn is an immediate verdict — the loop
            // would wait on a probe that never runs otherwise.
            model.graphics = crate::graphics::GraphicsVerdict::Unsupported {
                diagnostic: format!("graphics probe could not start: {error}"),
            };
        }
    }
    let ctx = SessionCtx {
        slot,
        spec,
        capabilities,
    };
    let mut state = LoopState {
        query: None,
        draft_changed: Instant::now(),
        observed_revision: model.draft.revision,
        close: CloseGate::new(),
        dirty: true,
        watcher,
        watch_workspace,
        config_dir: spec.paths.config_dir.clone(),
        fs_pending,
        config_pending,
        watcher_dead: false,
        watcher_fallback_at: None,
        sweep_at: None,
        sweep_req: None,
        probe_started,
    };
    let outcome = event_loop(model, &mut terminal, &scheduler, &mut state, &ctx);
    let restored = shutdown_terminal(&mut terminal, capabilities);
    // Every worker join is bounded by the same budget the closing handshake
    // carried — a wedged lane or watcher is detached, never awaited forever.
    let finished = scheduler.finish(CLOSE_BUDGET);
    outcome?;
    restored?;
    finished
}
/// The render/drain loop: `state` arrives fully built (watcher bound or
/// deferred) and leaves with the watcher joined inside the close budget.
fn event_loop(
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    scheduler: &Scheduler,
    state: &mut LoopState,
    ctx: &SessionCtx<'_>,
) -> Result<(), TuiError> {
    let outcome = drive_loop(model, terminal, scheduler, state, ctx);
    // The watcher must not outlive the session — same bounded stop as the
    // scheduler (I3): a wedged thread is reported, never awaited forever.
    let stopped = state
        .watcher
        .take()
        .map_or(Ok(()), |watcher| watcher.finish(CLOSE_BUDGET));
    outcome?;
    stopped
}

fn drive_loop(
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    scheduler: &Scheduler,
    state: &mut LoopState,
    ctx: &SessionCtx<'_>,
) -> Result<(), TuiError> {
    loop {
        let mut quit = false;
        while let Ok(reply) = scheduler.replies().try_recv() {
            quit |= deliver(model, scheduler, state, reply);
        }
        if quit {
            return Ok(());
        }
        if tick(model, scheduler, state) {
            return Ok(());
        }
        if state.dirty {
            if let Some(effect) = crate::navigation::hydrate_visible(model) {
                dispatch(model, scheduler, effect);
            }
            if let Some(effect) = crate::graphics::hydrate_images(model) {
                dispatch(model, scheduler, effect);
            }
            // Everything renders inside the frame: `ui::draw` emits the text
            // pass and the `StatefulImage` widgets write their prepared
            // protocol cells into the same buffer — ratatui's diff decides
            // what reaches the terminal, so an image placement change is a
            // cell diff, never a full clear (C-14/D-07).
            terminal.draw(|frame| crate::ui::draw(frame, model))?;
            state.dirty = false;
        }
        // While the capability probe owns stdin, `event::poll`/`event::read`
        // would race its query/response parse — the loop keeps drawing and
        // draining replies but never touches stdin until the verdict lands.
        // Keystrokes typed in this ≤1s window are consumed by the probe,
        // the unavoidable cost of sharing one input stream (D-03).
        if matches!(model.graphics, crate::graphics::GraphicsVerdict::Probing) {
            thread::sleep(Duration::from_millis(30));
            continue;
        }
        if !event::poll(Duration::from_millis(30))? {
            continue;
        }
        let event = event::read()?;
        if state.close.is_closing() {
            // The handshake swallows ordinary input but never the force exit:
            // a second quit — Ctrl+C included — leaves now (F-06).
            if let Some(command) = translate_event(model, event)
                && state.close.forces_exit(&command)
            {
                return Ok(());
            }
            continue;
        }
        // A system signal, not a user command (A-09): focus regain bypasses
        // `apply_command` entirely — no status clearing, no input-mode routing.
        if matches!(event, Event::FocusGained) {
            state.dirty = true;
            if let Some(effect) = crate::update::focus_reconcile(model) {
                submit(model, terminal, scheduler, state, effect, ctx)?;
            }
            continue;
        }
        // A resize mutates the model inside `translate_event` without a command.
        let resize = matches!(event, Event::Resize(..));
        if let Some(command) = translate_event(model, event) {
            state.dirty = true;
            if let Some(effect) = crate::update::apply_command(model, command) {
                submit(model, terminal, scheduler, state, effect, ctx)?;
            }
        } else if resize {
            state.dirty = true;
        }
    }
}
/// Applies one runtime reply; returns `true` when the session may close.
/// Observational variants are classified explicitly — a newly added message
/// must name its delivery side-effect here instead of landing in a wildcard.
fn deliver(
    model: &mut AppModel,
    scheduler: &Scheduler,
    state: &mut LoopState,
    reply: RuntimeMessage,
) -> bool {
    // Any reply may have changed the model; repaint once per drained batch.
    state.dirty = true;
    match &reply {
        RuntimeMessage::FsChanged { .. } => {
            // Consume the pending flag so the watcher may notify the next batch.
            state.fs_pending.store(false, Ordering::Release);
        }
        RuntimeMessage::ConfigChanged => {
            state.config_pending.store(false, Ordering::Release);
        }
        // `ConfigWatchUnavailable` needs no host bookkeeping — the model-side
        // `apply_message` owns the status text; the flag guard lives in
        // `ConfigChanged` above.
        RuntimeMessage::WatcherReady => {
            state.watcher_dead = false;
        }
        RuntimeMessage::WatcherUnavailable { .. } => {
            // Model state (`watcher_active`, status) belongs to
            // `apply_message` below; the host only tracks the death edge for
            // its reconcile-fallback gate.
            state.watcher_dead = true;
        }
        RuntimeMessage::QuitReady { .. } => return state.close.is_closing(),
        RuntimeMessage::RuntimeReady { .. } => {
            // Housekeeping starts once the runtime is live: the first sweep
            // is delayed so startup work settles, then it re-arms per run.
            state.sweep_at = Some(Instant::now() + MEDIA_SWEEP_INITIAL_DELAY);
            // First-run launch: watching could not start before the workspace
            // existed — it starts now that the mint opened it. A spawn failure
            // degrades to the same supervised fallback path as a dead watcher.
            if state.watcher.is_none()
                && let Some(root) = state.watch_workspace.clone()
            {
                match Watcher::spawn(
                    &root,
                    &state.config_dir,
                    scheduler.outbox(),
                    Arc::clone(&state.fs_pending),
                    Arc::clone(&state.config_pending),
                ) {
                    Ok(watcher) => state.watcher = Some(watcher),
                    Err(error) => {
                        state.watcher_dead = true;
                        model.watcher_active = false;
                        // The spawn failure is the same persistent outage as a
                        // later watcher death — badge it, do not toast-and-
                        // forget it (I9).
                        model.present(Notice::badge(
                            Severity::Warn,
                            BadgeClass::Watch,
                            crate::messages::watcher_outage_title(),
                            vec![error.to_string()],
                        ));
                    }
                }
            }
        }
        RuntimeMessage::Failed { req, .. }
            if matches!(
                model.pending.get(*req),
                Some(
                    crate::model::PendingKind::DraftPersist { .. }
                        | crate::model::PendingKind::DraftCommit { .. }
                )
            ) =>
        {
            // A draft write failed while closing — reopen the session instead
            // of exiting past the failure.
            state.close.reopen();
        }
        RuntimeMessage::MediaSweepDone { req, .. } | RuntimeMessage::Failed { req, .. } => {
            if state.sweep_req == Some(*req) {
                state.sweep_req = None;
            }
        }
        RuntimeMessage::Image { .. }
        | RuntimeMessage::View { .. }
        | RuntimeMessage::Page { .. }
        | RuntimeMessage::Bodies { .. }
        | RuntimeMessage::ReadMemo { .. }
        | RuntimeMessage::MemoGone { .. }
        | RuntimeMessage::DraftStored { .. }
        | RuntimeMessage::Saved { .. }
        | RuntimeMessage::Tags { .. }
        | RuntimeMessage::Message { .. }
        | RuntimeMessage::History { .. }
        | RuntimeMessage::Date { .. }
        | RuntimeMessage::Reconciled { .. }
        | RuntimeMessage::ConfigApplied { .. }
        | RuntimeMessage::ConfigWatchUnavailable { .. }
        | RuntimeMessage::BootPhase { .. }
        | RuntimeMessage::PlayerFinished { .. }
        | RuntimeMessage::GraphicsDetected { .. }
        | RuntimeMessage::WorkerDied { .. }
        | RuntimeMessage::Mutated { .. }
        | RuntimeMessage::Changed { .. } => {}
    }
    if let Some(effect) = crate::messages::apply_message(model, reply) {
        dispatch(model, scheduler, effect);
    }
    false
}
/// Admit an effect to its lane; a refusal answers the request immediately so
/// its pending intent resolves as a visible failure instead of wedging.
fn dispatch(model: &mut AppModel, scheduler: &Scheduler, effect: Effect) {
    let req = effect.req();
    match scheduler.submit(&mut model.pending, effect) {
        Submit::Queued | Submit::Dropped => {}
        Submit::Refused(refusal) => {
            let strings = crate::i18n::UiStrings::detect();
            let diagnostic = match refusal {
                Refusal::Saturated => strings
                    .text(
                        "System busy — the request was refused; try again",
                        "系统繁忙——请求被拒绝，请重试",
                    )
                    .to_owned(),
                Refusal::Dead => strings
                    .text(
                        "Internal worker stopped — restart required",
                        "内部工作线程已停止，需要重启",
                    )
                    .to_owned(),
            };
            if let Some(follow_up) =
                crate::messages::apply_message(model, RuntimeMessage::Failed { req, diagnostic })
            {
                // A refused follow-up lands its own failure receipt — the
                // pending registry, not a hidden buffer, carries the truth.
                dispatch(model, scheduler, follow_up);
            }
        }
    }
}
/// Returns `true` when the loop must leave now: the closing budget expired
/// or a forced quit already ran.
fn tick(model: &mut AppModel, scheduler: &Scheduler, state: &mut LoopState) -> bool {
    if state.close.expired(Instant::now()) {
        return true;
    }
    // The stdin gate is the loop's own invariant, so its deadline lives here:
    // a probe thread that never reports (wedged mid-query) cannot hold input
    // forever — the loop lands the fail-closed verdict it is owed (F-IMG-3).
    // A panicking probe reports from the outbox's dying declaration instead;
    // either way exactly one verdict lifts the gate.
    if matches!(model.graphics, crate::graphics::GraphicsVerdict::Probing)
        && state.probe_started.elapsed() >= PROBE_BUDGET
    {
        deliver(
            model,
            scheduler,
            state,
            RuntimeMessage::GraphicsDetected {
                verdict: crate::graphics::GraphicsVerdict::Unsupported {
                    diagnostic: crate::graphics::PROBE_EXPIRED_DIAGNOSTIC.to_owned(),
                },
            },
        );
    }
    if state
        .query
        .as_ref()
        .is_some_and(|(time, _)| time.elapsed() >= Duration::from_millis(150))
        && let Some((_, effect)) = state.query.take()
    {
        dispatch(model, scheduler, effect);
    }
    if state.observed_revision != model.draft.revision {
        state.observed_revision = model.draft.revision;
        state.draft_changed = Instant::now();
    }
    if !state.close.is_closing() {
        if model.draft.submitting_revision().is_none()
            && model.draft.revision != model.draft.persisted_revision
            && state.draft_changed.elapsed() >= Duration::from_millis(300)
        {
            let revision = model.draft.revision;
            let req = model.request(crate::model::PendingKind::DraftPersist { revision });
            dispatch(
                model,
                scheduler,
                Effect::PersistDraft {
                    req,
                    revision,
                    content: model.draft.text.text().to_owned(),
                },
            );
            state.draft_changed = Instant::now();
        }
        // Media orphan housekeeping (D-12): low-rate, on the Maint lane, and
        // never overlapping itself. The compose buffer rides along as a
        // guard so an unsaved draft's media is never swept under it; editor
        // draft files are guarded inside `ops::media_sweep` on the lane.
        if state.sweep_req.is_none() && state.sweep_at.is_some_and(|due| Instant::now() >= due) {
            let req = model.request(crate::model::PendingKind::Maintenance);
            state.sweep_req = Some(req);
            state.sweep_at = Some(Instant::now() + MEDIA_SWEEP_INTERVAL);
            let drafts = if model.draft.text.text().trim().is_empty() {
                Vec::new()
            } else {
                vec![lomo_application::GuardedDraftBody {
                    owner_id: "tui-compose".to_owned(),
                    content: model.draft.text.text().to_owned(),
                }]
            };
            dispatch(model, scheduler, Effect::MediaSweep { req, drafts });
        }
        // Watcher supervision (F-07): a thread that died without reporting —
        // or died reporting — engages the low-rate reconcile fallback, and
        // the outage hint re-arms while dead.
        // `None` means the watcher has not started yet (first-run setup is
        // still open) — absence is not an outage.
        if !state.watcher_dead
            && state
                .watcher
                .as_ref()
                .is_some_and(|watcher| !watcher.alive())
        {
            state.watcher_dead = true;
            model.watcher_active = false;
        }
        // No watcher yet (setup open) also means no runtime — a reconcile
        // would only park. Only a *dead* watcher engages the fallback.
        if let Some(effect) = crate::update::watcher_fallback(
            model,
            state.watcher_dead,
            &mut state.watcher_fallback_at,
            Instant::now(),
        ) {
            dispatch(model, scheduler, effect);
        }
    }
    false
}
fn submit(
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    scheduler: &Scheduler,
    state: &mut LoopState,
    effect: Effect,
    ctx: &SessionCtx<'_>,
) -> Result<(), TuiError> {
    if let Effect::SetupConfirmed { req, file, config } = effect {
        // The confirmation becomes a bootstrap whose launch mints first —
        // the lane, not the UI thread, performs the durable writes.
        state.watch_workspace = Some(config.workspace.clone());
        let spec = crate::ops::BootstrapSpec {
            paths: ctx.spec.paths.clone(),
            launch: crate::ops::LaunchConfig::Mint { file, config },
            width: ctx.spec.width,
            height: ctx.spec.height,
        };
        dispatch(model, scheduler, Effect::Bootstrap { req, spec });
        return Ok(());
    }
    if let Effect::Quit { req } = effect {
        state.query = None;
        state.close.begin();
        if model.draft.submitting_revision().is_none() {
            let revision = model.draft.revision;
            let persist = model.request(crate::model::PendingKind::DraftPersist { revision });
            dispatch(
                model,
                scheduler,
                Effect::PersistDraft {
                    req: persist,
                    revision,
                    content: model.draft.text.text().to_owned(),
                },
            );
        }
        dispatch(model, scheduler, Effect::Quit { req });
        return Ok(());
    }
    if let Effect::Edit { target, .. } = &effect {
        // The editor owns the terminal until it exits; the query worker
        // never sees this blocking call. `Edit` is only mintable from a live
        // memo view, so the slot is already `Ready` here — if it is not, the
        // request is refused on the status line rather than parking the UI.
        let Some(runtime) = ctx.slot.get() else {
            model.set_status(
                crate::i18n::UiStrings::detect()
                    .text("Workspace is still loading", "工作区仍在加载"),
            );
            return Ok(());
        };
        suspend_terminal(terminal, ctx.capabilities)?;
        let result = crate::edit_flow::complete_edit(
            &runtime,
            model,
            &crate::editor::StdCommandRunner,
            target,
            crate::xdg::env_nonempty("VISUAL").as_deref(),
            crate::xdg::env_nonempty("EDITOR").as_deref(),
        );
        resume_terminal(terminal, ctx.capabilities)?;
        match result {
            Ok(Some(follow_up)) => dispatch(model, scheduler, follow_up),
            Ok(None) => {}
            Err(error) => {
                // The edit handoff failed after the editor exited — an
                // operation failure, so it earns a persistent badge rather
                // than a toast the next keypress could bury (I9).
                model.present(Notice::badge(
                    Severity::Warn,
                    BadgeClass::Action,
                    crate::i18n::UiStrings::detect()
                        .text("Edit failed", "编辑失败")
                        .to_owned(),
                    vec![error.to_string()],
                ));
            }
        }
        return Ok(());
    }
    if matches!(effect, Effect::Query(_)) && matches!(model.input, InputMode::Search { .. }) {
        state.query = Some((Instant::now(), effect));
        return Ok(());
    }
    dispatch(model, scheduler, effect);
    Ok(())
}
fn translate_event(model: &mut AppModel, event: Event) -> Option<Command> {
    match event {
        Event::Resize(width, height) => {
            crate::update::apply_resize(model, width, height);
            // Cell metrics come from the probe verdict, not TIOCGWINSZ pixel
            // guesses — a resize changes cell counts, which `hydrate_images`
            // reconciles into new request identities (D-03).
            None
        }
        Event::Key(key) => command_from_key(key, model),
        Event::Paste(text) => command_from_paste(text, model),
        Event::Mouse(mouse) => match mouse.kind {
            MouseEventKind::ScrollDown => Some(Command::Scroll(3)),
            MouseEventKind::ScrollUp => Some(Command::Scroll(-1)),
            MouseEventKind::Down(event::MouseButton::Left) => {
                Some(Command::Click(mouse.column, mouse.row))
            }
            MouseEventKind::Down(_)
            | MouseEventKind::Up(_)
            | MouseEventKind::Drag(_)
            | MouseEventKind::Moved
            | MouseEventKind::ScrollLeft
            | MouseEventKind::ScrollRight => None,
        },
        // Focus regain is not a data change: the watcher owns observation and
        // focus only surfaces an outage hint or stays silent. The event loop
        // intercepts `FocusGained` before this command translation — a system
        // signal never becomes a user command.
        Event::FocusGained | Event::FocusLost => None,
    }
}
fn setup_terminal(capabilities: TerminalCapabilities) -> Result<HostTerminal, TuiError> {
    let mut enabled = TerminalCapabilities::default();
    let mut alternate = false;
    let terminal = (|| -> Result<HostTerminal, TuiError> {
        enable_raw_mode()?;
        let mut out = stdout();
        execute!(out, EnterAlternateScreen)?;
        alternate = true;
        if capabilities.mouse {
            execute!(out, EnableMouseCapture)?;
            enabled.mouse = true;
        }
        if capabilities.paste {
            execute!(out, EnableBracketedPaste)?;
            enabled.paste = true;
        }
        if capabilities.focus {
            execute!(out, EnableFocusChange)?;
            enabled.focus = true;
        }
        Terminal::new(CrosstermBackend::new(out)).map_err(TuiError::from)
    })();
    if terminal.is_err() {
        // A partial setup unwinds exactly what it enabled — alternate screen and
        // raw mode restored, feature escapes only where they were emitted.
        let mut out = stdout();
        if enabled.mouse {
            drop(execute!(out, DisableMouseCapture));
        }
        if enabled.paste {
            drop(execute!(out, DisableBracketedPaste));
        }
        if enabled.focus {
            drop(execute!(out, DisableFocusChange));
        }
        if alternate {
            drop(execute!(out, LeaveAlternateScreen));
        }
        drop(disable_raw_mode());
    }
    terminal
}
/// Suspend the UI for a foreground handoff (external editor/player).
///
/// No image-protocol teardown happens here on purpose: ratatui-image's
/// kitty virtual placements are bound to the alternate screen's cell grid
/// and reappear on resume from the still-transmitted image store; deleting
/// kitty images now would strand them, because the protocol's transmit-once
/// flag survives in `TerminalImage` state (D-09/D-13). iTerm2/sixel pixels
/// live in the same grid the alternate-screen switch saves and restores.
fn suspend_terminal(
    terminal: &mut HostTerminal,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    terminal.clear()?;
    if capabilities.mouse {
        execute!(terminal.backend_mut(), DisableMouseCapture)?;
    }
    if capabilities.paste {
        execute!(terminal.backend_mut(), DisableBracketedPaste)?;
    }
    if capabilities.focus {
        execute!(terminal.backend_mut(), DisableFocusChange)?;
    }
    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
    disable_raw_mode()?;
    Ok(())
}
/// Final teardown: expire the kitty image store before leaving the
/// alternate screen so transmitted data cannot outlive the session (D-09).
/// The APC is ignored by terminals without kitty support — there is no
/// protocol-specific branch because the verdict is gone by shutdown anyway.
fn shutdown_terminal(
    terminal: &mut HostTerminal,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    use std::io::Write;
    drop(terminal.backend_mut().write_all(b"\x1b_Ga=d,d=A,q=2\x1b\\"));
    suspend_terminal(terminal, capabilities)
}
fn resume_terminal(
    terminal: &mut HostTerminal,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    enable_raw_mode()?;
    execute!(terminal.backend_mut(), EnterAlternateScreen)?;
    if capabilities.mouse {
        execute!(terminal.backend_mut(), EnableMouseCapture)?;
    }
    if capabilities.paste {
        execute!(terminal.backend_mut(), EnableBracketedPaste)?;
    }
    if capabilities.focus {
        execute!(terminal.backend_mut(), EnableFocusChange)?;
    }
    terminal.clear()?;
    Ok(())
}
