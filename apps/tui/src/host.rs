//! Terminal lifecycle and background execution of application effects.
use crate::{
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    event::{Command, command_from_key, command_from_paste},
    model::{AppModel, InputMode, SaveState},
    ops::TuiRuntime,
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
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
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

struct Worker {
    sender: mpsc::Sender<Effect>,
    receiver: mpsc::Receiver<RuntimeMessage>,
    handle: thread::JoinHandle<()>,
}
impl Worker {
    fn spawn(runtime: Arc<TuiRuntime>) -> Self {
        let (sender, jobs) = mpsc::channel();
        let (results, receiver) = mpsc::channel();
        let handle = thread::spawn(move || {
            while let Ok(effect) = jobs.recv() {
                let reply =
                    crate::ops::execute(&runtime, &effect, &results).unwrap_or_else(|error| {
                        RuntimeMessage::Failed {
                            target: effect.failure_target(),
                            diagnostic: error.to_string(),
                        }
                    });
                if results.send(reply).is_err() {
                    break;
                }
            }
        });
        Self {
            sender,
            receiver,
            handle,
        }
    }
    fn send(&self, effect: Effect) -> Result<(), TuiError> {
        self.sender
            .send(effect)
            .map_err(|error| TuiError::io(error.to_string()))
    }
    fn finish(self) -> Result<(), TuiError> {
        drop(self.sender);
        self.handle
            .join()
            .map_err(|error| TuiError::io(format!("application worker panicked: {error:?}")))
    }
}

/// Workspace observation runs on its own thread; every drained batch becomes
/// one `FsChanged`, never one message per event.
struct Watcher {
    stop: Arc<AtomicBool>,
    handle: thread::JoinHandle<()>,
}
impl Watcher {
    fn spawn(workspace: &std::path::Path, results: mpsc::Sender<RuntimeMessage>) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stop);
        let root = workspace.to_path_buf();
        let handle = thread::spawn(move || {
            let mut watcher = match lomo_platform_fs::DirectoryWatcher::new(&root) {
                Ok(watcher) => watcher,
                Err(error) => {
                    drop(results.send(RuntimeMessage::WatcherUnavailable {
                        diagnostic: error.to_string(),
                    }));
                    return;
                }
            };
            if results.send(RuntimeMessage::WatcherReady).is_err() {
                return;
            }
            while !flag.load(Ordering::Relaxed) {
                match watcher.wait_events(Duration::from_millis(250)) {
                    Ok(events) if events.is_empty() => {}
                    Ok(_) => {
                        if results.send(RuntimeMessage::FsChanged).is_err() {
                            return;
                        }
                    }
                    Err(error) => {
                        drop(results.send(RuntimeMessage::WatcherUnavailable {
                            diagnostic: error.to_string(),
                        }));
                        return;
                    }
                }
            }
        });
        Self { stop, handle }
    }
    fn finish(self) -> Result<(), TuiError> {
        self.stop.store(true, Ordering::Relaxed);
        self.handle
            .join()
            .map_err(|error| TuiError::io(format!("workspace watcher panicked: {error:?}")))
    }
}

struct LoopState {
    query: Option<(Instant, Effect)>,
    draft_changed: Instant,
    observed_revision: u64,
    closing: bool,
    images: crate::image_surface::ImageSurface,
}

/// # Errors
/// Terminal, worker or foreground process failures.
pub fn run(
    runtime: &Arc<TuiRuntime>,
    model: &mut AppModel,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    let mut terminal = setup_terminal(capabilities)?;
    let worker = Worker::spawn(Arc::clone(runtime));
    let (results, inbox) = mpsc::channel();
    let watcher = Watcher::spawn(&runtime.workspace, results);
    let outcome = event_loop(runtime, model, &mut terminal, &worker, &inbox, capabilities);
    let restored = suspend_terminal(&mut terminal, runtime.graphics, capabilities);
    let finished = worker.finish();
    let watch_joined = watcher.finish();
    outcome?;
    restored?;
    finished?;
    watch_joined
}
fn event_loop(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    worker: &Worker,
    inbox: &mpsc::Receiver<RuntimeMessage>,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    let mut state = LoopState {
        query: None,
        draft_changed: Instant::now(),
        observed_revision: model.draft.revision,
        closing: false,
        images: crate::image_surface::ImageSurface::default(),
    };
    loop {
        let mut quit = false;
        while let Ok(reply) = worker.receiver.try_recv() {
            quit |= deliver(
                runtime,
                model,
                terminal,
                worker,
                &mut state,
                capabilities,
                reply,
            )?;
        }
        while let Ok(reply) = inbox.try_recv() {
            quit |= deliver(
                runtime,
                model,
                terminal,
                worker,
                &mut state,
                capabilities,
                reply,
            )?;
        }
        if quit {
            return Ok(());
        }
        tick(model, worker, &mut state)?;
        if let Some(effect) = crate::navigation::hydrate_visible(model) {
            worker.send(effect)?;
        }
        if let Some(effect) = crate::graphics::hydrate_images(model) {
            worker.send(effect)?;
        }
        state.images.draw(terminal, model)?;
        if !event::poll(Duration::from_millis(30))? {
            continue;
        }
        let event = event::read()?;
        if state.closing {
            continue;
        }
        if let Some(command) = translate_event(model, event)
            && let Some(effect) = crate::update::apply_command(model, command)
        {
            submit(
                runtime,
                model,
                terminal,
                worker,
                &mut state,
                effect,
                capabilities,
            )?;
        }
    }
}
/// Applies one runtime reply; returns `true` when the session may close.
fn deliver(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    worker: &Worker,
    state: &mut LoopState,
    capabilities: TerminalCapabilities,
    reply: RuntimeMessage,
) -> Result<bool, TuiError> {
    if reply == RuntimeMessage::QuitReady {
        return Ok(state.closing);
    }
    if matches!(
        reply,
        RuntimeMessage::Failed {
            target: crate::effects::FailureTarget::DraftPersist(_)
                | crate::effects::FailureTarget::DraftCommit(_),
            ..
        }
    ) {
        state.closing = false;
    }
    if let Some(effect) = crate::messages::apply_message(model, reply) {
        submit(
            runtime,
            model,
            terminal,
            worker,
            state,
            effect,
            capabilities,
        )?;
    }
    Ok(false)
}
fn tick(model: &AppModel, worker: &Worker, state: &mut LoopState) -> Result<(), TuiError> {
    if state
        .query
        .as_ref()
        .is_some_and(|(time, _)| time.elapsed() >= Duration::from_millis(150))
        && let Some((_, effect)) = state.query.take()
    {
        worker.send(effect)?;
    }
    if state.observed_revision != model.draft.revision {
        state.observed_revision = model.draft.revision;
        state.draft_changed = Instant::now();
    }
    if !state.closing
        && !matches!(model.draft.save, SaveState::Submitting { .. })
        && model.draft.revision != model.draft.persisted_revision
        && state.draft_changed.elapsed() >= Duration::from_millis(300)
    {
        worker.send(Effect::PersistDraft {
            revision: model.draft.revision,
            content: model.draft.text.text().to_owned(),
        })?;
        state.draft_changed = Instant::now();
    }
    Ok(())
}
fn submit(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    terminal: &mut HostTerminal,
    worker: &Worker,
    state: &mut LoopState,
    effect: Effect,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    match effect {
        Effect::Quit => {
            state.query = None;
            state.closing = true;
            if !matches!(model.draft.save, SaveState::Submitting { .. }) {
                worker.send(Effect::PersistDraft {
                    revision: model.draft.revision,
                    content: model.draft.text.text().to_owned(),
                })?;
            }
            worker.send(Effect::Quit)
        }
        Effect::Edit(target) => {
            // The editor owns the terminal until it exits; the query worker
            // never sees this blocking call.
            state.images.reset();
            suspend_terminal(terminal, runtime.graphics, capabilities)?;
            let result = crate::edit_flow::complete_edit(
                runtime,
                model,
                &crate::editor::StdCommandRunner,
                &target,
                crate::xdg::env_nonempty("VISUAL").as_deref(),
                crate::xdg::env_nonempty("EDITOR").as_deref(),
            );
            resume_terminal(terminal, capabilities)?;
            match result {
                Ok(Some(effect)) => worker.send(effect),
                Ok(None) => Ok(()),
                Err(error) => {
                    model.set_status(&error.to_string());
                    Ok(())
                }
            }
        }
        query @ Effect::Query(_) if matches!(model.input, InputMode::Search { .. }) => {
            state.query = Some((Instant::now(), query));
            Ok(())
        }
        job @ (Effect::Query(_)
        | Effect::Navigate { .. }
        | Effect::Bodies { .. }
        | Effect::ReadMemo { .. }
        | Effect::PersistDraft { .. }
        | Effect::CommitDraft { .. }
        | Effect::LoadImage(_)
        | Effect::CommitEdit(_)
        | Effect::CaptureEdited { .. }
        | Effect::ToggleTask(_)
        | Effect::Pin { .. }
        | Effect::Delete { .. }
        | Effect::DeleteForever(_)
        | Effect::EmptyTrash
        | Effect::Restore(_)
        | Effect::RestoreRevision { .. }
        | Effect::History(_)
        | Effect::ImportClipboard
        | Effect::OpenAttachment(_)
        | Effect::Reconcile
        | Effect::Tags
        | Effect::Date { .. }
        | Effect::Refresh) => worker.send(job),
    }
}
fn translate_event(model: &mut AppModel, event: Event) -> Option<Command> {
    match event {
        Event::Resize(width, height) => {
            crate::update::apply_resize(model, width, height);
            match crossterm::terminal::window_size() {
                Ok(size) => {
                    model.cell_size = crate::graphics::CellSize::reported(
                        size.width,
                        size.height,
                        size.columns,
                        size.rows,
                    );
                }
                Err(error) => {
                    model.cell_size = None;
                    // Windows consoles always report `Unsupported` — expected,
                    // not worth a status line. Other platforms surface it.
                    #[cfg(unix)]
                    model.set_status(&format!("Terminal metrics: {error}"));
                    #[cfg(not(unix))]
                    let _ = error;
                }
            }
            None
        }
        Event::Key(key) => command_from_key(key, model),
        Event::Paste(text) => command_from_paste(text, model),
        Event::Mouse(mouse) => match mouse.kind {
            MouseEventKind::ScrollDown => Some(Command::Scroll(3)),
            MouseEventKind::ScrollUp => Some(Command::Scroll(-3)),
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
        // focus only surfaces an outage hint or stays silent.
        Event::FocusGained => Some(Command::FocusReconcile),
        Event::FocusLost => None,
    }
}
fn setup_terminal(capabilities: TerminalCapabilities) -> Result<HostTerminal, TuiError> {
    enable_raw_mode()?;
    let mut out = stdout();
    execute!(out, EnterAlternateScreen)?;
    if capabilities.mouse {
        execute!(out, EnableMouseCapture)?;
    }
    if capabilities.paste {
        execute!(out, EnableBracketedPaste)?;
    }
    if capabilities.focus {
        execute!(out, EnableFocusChange)?;
    }
    Terminal::new(CrosstermBackend::new(out)).map_err(TuiError::from)
}
fn suspend_terminal(
    terminal: &mut HostTerminal,
    graphics: crate::media::GraphicsProtocol,
    capabilities: TerminalCapabilities,
) -> Result<(), TuiError> {
    use std::io::Write;
    if graphics == crate::media::GraphicsProtocol::Kitty {
        terminal
            .backend_mut()
            .write_all(b"\x1b_Ga=d,d=A,q=2\x1b\\")?;
    }
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
