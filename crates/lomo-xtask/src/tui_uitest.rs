// ---- PTY-driven TUI interaction flows ----
//
// `just tuitest` drives the real `lomo` binary through a pseudo-terminal: each committed
// YAML flow under `apps/tui/uitest/flows` runs the binary in a 100x36 PTY with an
// isolated HOME/XDG/workspace, then steps the screen through declared key input. Screen
// state is rebuilt by a `vte` parser feeding a character grid — `expect` asserts on the
// visible grid (screen semantics like Maestro's assertVisible, not the raw stream).

use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use portable_pty::{CommandBuilder, PtySize, native_pty_system};
use serde_json::Value;
use unicode_width::UnicodeWidthChar;
use yaml_rust2::{Yaml, YamlLoader};

use crate::{
    android::resolve_flow_files,
    tui::{TuiProfile, build},
    util::emit_stderr,
    workspace::Workspace,
};

/// Committed flows live here; every file is walked by `tui_uitest_contract`.
pub const TUI_UITEST_FLOW_DIR: &str = "apps/tui/uitest/flows";
const TUI_UITEST_REPORT_DIR: &str = "tui-uitest";
const TUI_UITEST_REPORT: &str = "tui-uitest.v1.json";
const PTY_ROWS: u16 = 36;
const PTY_COLS: u16 = 100;
const EXPECT_TIMEOUT: Duration = Duration::from_secs(15);
const EXIT_TIMEOUT: Duration = Duration::from_secs(5);
const STEP_SETTLE: Duration = Duration::from_millis(120);

/// Options for `just tuitest`.
#[derive(Default)]
pub struct TuiUitestOptions {
    /// Reuse the published `artifacts/tui/debug/lomo` instead of rebuilding.
    pub skip_build: bool,
    /// Flow selectors: existing paths, or names resolved under `apps/tui/uitest/flows`.
    pub flows: Vec<String>,
}

/// One declared interaction step inside a flow file.
#[derive(Debug)]
enum Step {
    /// Wait until the screen grid contains the substring (default 15s deadline).
    Expect {
        text: String,
        timeout_ms: Option<u64>,
    },
    /// Write literal text to the PTY.
    Send(String),
    /// Write a named key's escape bytes (`enter`, `esc`, arrows, `f5`, `ctrl+<ch>`).
    Key(String),
    /// Fixed settle delay — last resort only; `expect` is the real synchronizer.
    Sleep(u64),
    /// Write the current screen text to `<flow>/<name>.txt`.
    Snap(String),
    /// Wait until the process exits (default 5s deadline) — asserts clean quit.
    ExpectExit { timeout_ms: Option<u64> },
}

/// Resolves committed TUI flow selectors; mirrors `uitest_flow_files`.
///
/// # Errors
/// Missing flow directory, empty suite or unknown selector all fail closed.
pub fn tui_flow_files(root: &Path, selectors: &[String]) -> Result<Vec<PathBuf>> {
    resolve_flow_files(root, TUI_UITEST_FLOW_DIR, selectors)
}

/// Runs the committed TUI interaction flows against the real `lomo` binary.
///
/// Every flow spawns the binary inside a 100x36 PTY with a fresh isolated HOME —
/// config, XDG dirs and the workspace argument all point into a per-flow temporary
/// directory so flows carry no state and exercise the first-run wizard when it
/// appears. A nonzero-assert flow is recorded `failed`; the suite keeps running so
/// the report carries evidence for every flow.
///
/// # Errors
/// Missing binary/flow files, an `expect` timeout, or any failed step propagate;
/// a partially executed suite is reported but never claimed as passed.
pub fn tui_uitest(workspace: &Workspace, options: &TuiUitestOptions) -> Result<Value> {
    let flows = tui_flow_files(&workspace.root, &options.flows)?;
    let report_dir = workspace.reports_dir().join(TUI_UITEST_REPORT_DIR);
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)
            .with_context(|| format!("failed to reset {}", report_dir.display()))?;
    }
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let binary = if options.skip_build {
        let binary = workspace
            .tui_output_dir("debug")
            .join(format!("lomo{}", std::env::consts::EXE_SUFFIX));
        ensure!(
            binary.is_file(),
            "--skip-build requires an existing binary at {}; run without it to rebuild",
            binary.display()
        );
        binary
    } else {
        build(workspace, TuiProfile::Debug)?
    };
    emit_stderr(format_args!("xtask: tuitest driving {}", binary.display()));

    let temp_root = workspace.temp_dir("tui-uitest")?;
    let mut results = Vec::with_capacity(flows.len());
    let mut failed = Vec::new();
    for flow in &flows {
        let outcome = run_tui_flow(&binary, flow, &report_dir, &temp_root)?;
        if outcome.get("status").and_then(Value::as_str) != Some("passed")
            && let Some(name) = outcome.get("name").and_then(Value::as_str)
        {
            failed.push(name.to_owned());
        }
        results.push(outcome);
    }

    let report = serde_json::json!({
        "schema_version": 1,
        "kind": "pty_uitest",
        "git_revision": crate::android::git_revision(workspace)?,
        "binary": binary,
        "pty": { "rows": PTY_ROWS, "cols": PTY_COLS },
        "options": { "skip_build": options.skip_build },
        "flows": results,
    });
    let report_path = report_dir.join(TUI_UITEST_REPORT);
    let pretty =
        serde_json::to_string_pretty(&report).context("failed to serialize tui uitest report")?;
    fs::write(&report_path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", report_path.display()))?;
    emit_stderr(format_args!("xtask: wrote {}", report_path.display()));
    ensure!(
        failed.is_empty(),
        "tui uitest failed flows: {} (see {})",
        failed.join(", "),
        report_dir.display()
    );
    Ok(report)
}

/// Executes one flow file: spawn → drive steps → capture snapshots and transcript.
/// A step failure fails that flow (with the screen dumped into the error evidence)
/// while the suite continues.
fn run_tui_flow(binary: &Path, flow: &Path, report_dir: &Path, temp_root: &Path) -> Result<Value> {
    let stem = flow
        .file_stem()
        .and_then(|stem| stem.to_str())
        .context("flow file name must be UTF-8")?;
    let flow_dir = report_dir.join(stem);
    fs::create_dir_all(&flow_dir)
        .with_context(|| format!("failed to create {}", flow_dir.display()))?;
    let steps = parse_flow(
        &fs::read_to_string(flow).with_context(|| format!("failed to read {}", flow.display()))?,
    )?;

    let mut session = PtySession::spawn(binary, &temp_root.join(stem))?;
    let mut log_lines = vec![format!("flow {stem}: {} steps", steps.len())];
    let mut step_error = None;
    for (index, step) in steps.iter().enumerate() {
        match session.drive(step, &flow_dir) {
            Ok(note) => log_lines.push(format!("step {} {step:?}: {note}", index + 1)),
            Err(error) => {
                let screen = session.screen_text();
                if let Err(write_error) = fs::write(flow_dir.join("failed-screen.txt"), &screen) {
                    emit_stderr(format_args!(
                        "xtask: warning: failed to write failed screen for {stem}: {write_error}"
                    ));
                }
                log_lines.push(format!("step {} {step:?} FAILED: {error:#}", index + 1));
                step_error = Some(format!("{error:#}"));
                break;
            }
        }
        thread::sleep(STEP_SETTLE);
    }
    session.kill();
    fs::write(
        report_dir.join(format!("{stem}.log")),
        format!("{}\n", log_lines.join("\n")),
    )
    .with_context(|| format!("failed to write {stem}.log"))?;
    fs::write(flow_dir.join("final.txt"), session.screen_text())
        .with_context(|| format!("failed to write final screen for {stem}"))?;

    Ok(serde_json::json!({
        "name": stem,
        "status": if step_error.is_none() { "passed" } else { "failed" },
        "error": step_error,
        "artifacts": {
            "dir": flow_dir,
            "log": report_dir.join(format!("{stem}.log")),
        },
    }))
}

/// A spawned `lomo` PTY session: reader thread feeds `vte` into the screen grid,
/// steps write to the master. The process is killed on `kill()`/drop completion.
struct PtySession {
    /// Held for RAII: dropping the master closes the PTY and orphans the child.
    _master: Box<dyn portable_pty::MasterPty + Send>,
    child: Box<dyn portable_pty::Child + Send + Sync>,
    /// Shared with the reader thread, which answers the app's terminal-capability
    /// queries (ratatui-image blocks stdin until the DSR reply arrives — a query
    /// left unanswered strands every later keystroke inside the probe's leaked
    /// reader thread).
    writer: Arc<Mutex<Box<dyn Write + Send>>>,
    term: Arc<Mutex<Term>>,
    reader: Option<thread::JoinHandle<()>>,
}

impl PtySession {
    fn spawn(binary: &Path, home: &Path) -> Result<Self> {
        let pair = native_pty_system()
            .openpty(PtySize {
                rows: PTY_ROWS,
                cols: PTY_COLS,
                pixel_width: 0,
                pixel_height: 0,
            })
            .context("openpty failed")?;
        let workspace = home.join("workspace");
        fs::create_dir_all(home.join(".config"))
            .and_then(|()| fs::create_dir_all(home.join(".local/share")))
            .and_then(|()| fs::create_dir_all(home.join(".run")))
            .and_then(|()| fs::create_dir_all(&workspace))
            .with_context(|| format!("failed to create isolated home {}", home.display()))?;
        let mut command = CommandBuilder::new(binary);
        command.arg(&workspace);
        command.cwd(home);
        // Hermetic environment: config/state/data all live inside the flow's temp
        // home so first-run wizard state never leaks between flows or the host.
        for (key, value) in [
            ("HOME", home.to_string_lossy().into_owned()),
            ("XDG_CONFIG_HOME", format!("{}/.config", home.display())),
            ("XDG_DATA_HOME", format!("{}/.local/share", home.display())),
            ("XDG_STATE_HOME", format!("{}/.local/state", home.display())),
            // Process locks live here — isolate or the flow races a running
            // host `lomo` for the transaction lock.
            ("XDG_RUNTIME_DIR", format!("{}/.run", home.display())),
            ("TERM", "xterm-256color".to_owned()),
            ("LANG", "C.UTF-8".to_owned()),
            (
                "PATH",
                std::env::var("PATH").unwrap_or_else(|_| String::new()),
            ),
        ] {
            command.env(key, value);
        }
        let child = pair
            .slave
            .spawn_command(command)
            .context("failed to spawn lomo in pty")?;
        drop(pair.slave);
        let mut reader = pair
            .master
            .try_clone_reader()
            .context("failed to clone pty reader")?;
        let writer = Arc::new(Mutex::new(
            pair.master
                .take_writer()
                .context("failed to take pty writer")?,
        ));
        let term = Arc::new(Mutex::new(Term::new(PTY_COLS, PTY_ROWS)));
        let sink = Arc::clone(&term);
        let reply_writer = Arc::clone(&writer);
        let reader = thread::spawn(move || {
            let mut buf = [0_u8; 8192];
            // Short carry-over so a query split across reads is still matched.
            let mut tail: Vec<u8> = Vec::new();
            loop {
                match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(count) => {
                        let Some(chunk) = buf.get(..count) else {
                            break;
                        };
                        answer_terminal_queries(&tail, chunk, &reply_writer);
                        tail.clear();
                        if let Some(end) = chunk.get(chunk.len().saturating_sub(8)..) {
                            tail.extend_from_slice(end);
                        }
                        if let Ok(mut term) = sink.lock() {
                            term.feed(chunk);
                        }
                    }
                }
            }
        });
        Ok(Self {
            _master: pair.master,
            child,
            writer,
            term,
            reader: Some(reader),
        })
    }

    fn drive(&mut self, step: &Step, flow_dir: &Path) -> Result<String> {
        let note = match step {
            Step::Expect { text, timeout_ms } => {
                let deadline =
                    Instant::now() + timeout_ms.map_or(EXPECT_TIMEOUT, Duration::from_millis);
                loop {
                    if self.screen_text().contains(text.as_str()) {
                        break format!("screen contains {text:?}");
                    }
                    if let Ok(status) = self.child.try_wait()
                        && status.is_some()
                    {
                        let screen = self.screen_text();
                        bail!("process exited while expecting {text:?}; screen:\n{screen}");
                    }
                    ensure!(
                        Instant::now() < deadline,
                        "expect {text:?} timed out; screen:\n{}",
                        self.screen_text()
                    );
                    thread::sleep(Duration::from_millis(50));
                }
            }
            Step::Send(text) => {
                write_pty(&self.writer, text.as_bytes())?;
                format!("sent {text:?}")
            }
            Step::Key(name) => {
                let bytes =
                    key_bytes(name).with_context(|| format!("unknown key name {name:?}"))?;
                write_pty(&self.writer, &bytes)?;
                format!("key {name}")
            }
            Step::Sleep(ms) => {
                thread::sleep(Duration::from_millis(*ms));
                format!("slept {ms}ms")
            }
            Step::Snap(name) => {
                let path = flow_dir.join(format!("{name}.txt"));
                fs::write(&path, self.screen_text())
                    .with_context(|| format!("failed to write {}", path.display()))?;
                format!("snap {}", path.display())
            }
            Step::ExpectExit { timeout_ms } => {
                let deadline =
                    Instant::now() + timeout_ms.map_or(EXIT_TIMEOUT, Duration::from_millis);
                loop {
                    match self.child.try_wait() {
                        Ok(Some(status)) => {
                            ensure!(
                                status.success(),
                                "process exited with {status} (expected clean exit)"
                            );
                            break format!("process exited with {status}");
                        }
                        Ok(None) if Instant::now() < deadline => {
                            thread::sleep(Duration::from_millis(50));
                        }
                        Ok(None) => {
                            let screen = self.screen_text();
                            bail!("process still running after exit wait; screen:\n{screen}");
                        }
                        Err(error) => bail!("failed to poll process exit: {error}"),
                    }
                }
            }
        };
        Ok(note)
    }

    fn screen_text(&self) -> String {
        // A poisoned screen mutex means the reader panicked — report an empty
        // screen so the flow's own evidence (or hang) is what surfaces.
        self.term
            .lock()
            .map_or_else(|_| String::new(), |term| term.screen.text())
    }

    fn kill(&mut self) {
        // A flow that asserted `expect_exit` already reaped the child —
        // signalling it again would only surface a spurious ESRCH warning.
        match self.child.try_wait() {
            Ok(Some(_)) => {}
            Ok(None) => {
                if let Err(error) = self.child.kill() {
                    emit_stderr(format_args!("xtask: warning: pty kill failed: {error}"));
                }
                if let Err(error) = self.child.wait() {
                    emit_stderr(format_args!("xtask: warning: pty wait failed: {error}"));
                }
            }
            Err(error) => {
                emit_stderr(format_args!(
                    "xtask: warning: pty status poll failed: {error}"
                ));
            }
        }
        if let Ok(mut writer) = self.writer.lock()
            && let Err(error) = writer.flush()
        {
            emit_stderr(format_args!("xtask: warning: pty flush failed: {error}"));
        }
        if let Some(reader) = self.reader.take()
            && let Err(error) = reader.join()
        {
            emit_stderr(format_args!(
                "xtask: warning: pty reader thread failed: {error:?}"
            ));
        }
    }
}

/// Writes bytes to the PTY master — the child's stdin.
fn write_pty(writer: &Mutex<Box<dyn Write + Send>>, bytes: &[u8]) -> Result<()> {
    let mut guard = writer
        .lock()
        .map_err(|error| anyhow!("pty writer lock poisoned: {error}"))?;
    guard.write_all(bytes).context("pty write failed")?;
    guard.flush().context("pty flush failed")
}

/// Answers the terminal-capability queries a well-behaved terminal replies to.
/// `lomo`'s graphics probe writes kitty/sixel/cell-size/DSR queries to the PTY
/// and blocks stdin until the DSR `\x1b[5n` is answered — without a reply the
/// probe's timed-out reader keeps consuming stdin and eats every keystroke.
/// We claim no graphics capability: `\x1b[0n` ends the probe fast, and the
/// cell-size reply `\x1b[6;<h>;<w>t` supplies a real font size.
fn answer_terminal_queries(tail: &[u8], chunk: &[u8], writer: &Mutex<Box<dyn Write + Send>>) {
    let mut window = Vec::with_capacity(tail.len() + chunk.len());
    window.extend_from_slice(tail);
    window.extend_from_slice(chunk);
    let replies: [(&[u8], &[u8]); 2] = [
        // 100x36 cells at a 10x20px cell — consistent with PTY_COLS/PTY_ROWS.
        (b"\x1b[16t", b"\x1b[6;720;1000t"),
        (b"\x1b[5n", b"\x1b[0n"),
    ];
    for (query, reply) in replies {
        if !contains_subslice(&window, query) {
            continue;
        }
        match writer.lock() {
            Ok(mut guard) => {
                if let Err(error) = guard.write_all(reply) {
                    emit_stderr(format_args!(
                        "xtask: warning: terminal query reply failed: {error}"
                    ));
                }
            }
            Err(error) => emit_stderr(format_args!(
                "xtask: warning: pty writer lock poisoned: {error}"
            )),
        }
    }
}

fn contains_subslice(haystack: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty() && haystack.windows(needle.len()).any(|w| w == needle)
}

/// Named key → bytes written to the PTY master (`ctrl+<ch>` is the control
/// byte, a lone printable char maps to itself).
fn key_bytes(name: &str) -> Option<Vec<u8>> {
    Some(match name {
        "enter" => b"\r".to_vec(),
        "esc" => b"\x1b".to_vec(),
        "tab" => b"\t".to_vec(),
        "backspace" => b"\x7f".to_vec(),
        "up" => b"\x1b[A".to_vec(),
        "down" => b"\x1b[B".to_vec(),
        "right" => b"\x1b[C".to_vec(),
        "left" => b"\x1b[D".to_vec(),
        "home" => b"\x1b[H".to_vec(),
        "end" => b"\x1b[F".to_vec(),
        "pageup" => b"\x1b[5~".to_vec(),
        "pagedown" => b"\x1b[6~".to_vec(),
        "f5" => b"\x1b[15~".to_vec(),
        "space" => b" ".to_vec(),
        _ => {
            if let Some(rest) = name.strip_prefix("ctrl+") {
                return ctrl_byte(rest).map(|byte| vec![byte]);
            }
            // A single printable char is its own byte (`key: q` reads better
            // than `send: q` for a deliberate keypress).
            let mut chars = name.chars();
            return match (chars.next(), chars.next()) {
                (Some(c), None) if c.is_ascii_graphic() => Some(vec![c as u8]),
                _ => None,
            };
        }
    })
}

fn ctrl_byte(name: &str) -> Option<u8> {
    let mut chars = name.chars();
    match (chars.next(), chars.next()) {
        (Some(c @ 'a'..='z'), None) => Some(c as u8 - b'a' + 1),
        _ => None,
    }
}

/// Parses a flow YAML file into steps. Unknown keys, non-map steps and an empty
/// `steps` list all fail closed — a typo'd step must not silently become a no-op.
fn parse_flow(text: &str) -> Result<Vec<Step>> {
    let docs = YamlLoader::load_from_str(text).context("flow YAML failed to parse")?;
    let doc = docs.first().context("flow YAML is empty")?;
    let steps_yaml = doc["steps"]
        .as_vec()
        .context("flow must declare a `steps:` list")?;
    ensure!(
        !steps_yaml.is_empty(),
        "flow must declare at least one step"
    );
    let mut steps = Vec::with_capacity(steps_yaml.len());
    for (index, entry) in steps_yaml.iter().enumerate() {
        let hash = entry
            .as_hash()
            .with_context(|| format!("step {} must be a single-key map", index + 1))?;
        ensure!(
            hash.len() == 1,
            "step {} must be a single-key map",
            index + 1
        );
        let (key, value) = hash
            .iter()
            .next()
            .with_context(|| format!("step {} is empty", index + 1))?;
        let key = key
            .as_str()
            .with_context(|| format!("step {} key must be a string", index + 1))?;
        let step = match key {
            "expect" => Step::Expect {
                text: step_string(value, "expect", index)?,
                timeout_ms: None,
            },
            "send" => Step::Send(step_string(value, "send", index)?),
            "key" => {
                let name = step_string(value, "key", index)?;
                ensure!(
                    key_bytes(&name).is_some(),
                    "step {} names an unknown key {name:?}",
                    index + 1
                );
                Step::Key(name)
            }
            "sleep_ms" => Step::Sleep(step_millis(value, "sleep_ms", index)?),
            "snap" => Step::Snap(step_string(value, "snap", index)?),
            "expect_exit" => Step::ExpectExit {
                timeout_ms: match value {
                    Yaml::Boolean(true) | Yaml::Null => None,
                    Yaml::Integer(_) => Some(step_millis(value, "expect_exit", index)?),
                    Yaml::Real(_)
                    | Yaml::String(_)
                    | Yaml::Boolean(_)
                    | Yaml::Array(_)
                    | Yaml::Hash(_)
                    | Yaml::Alias(_)
                    | Yaml::BadValue => bail!(
                        "step {} expect_exit must be true or a millisecond integer",
                        index + 1
                    ),
                },
            },
            other => bail!("step {} uses unknown step key {other:?}", index + 1),
        };
        steps.push(step);
    }
    Ok(steps)
}

fn step_string(value: &Yaml, key: &str, index: usize) -> Result<String> {
    value
        .as_str()
        .map(str::to_owned)
        .with_context(|| format!("step {} {key} must be a string", index + 1))
}

fn step_millis(value: &Yaml, key: &str, index: usize) -> Result<u64> {
    match value {
        Yaml::Integer(ms) => u64::try_from(*ms)
            .with_context(|| format!("step {} {key} must be a positive integer", index + 1)),
        Yaml::Real(_)
        | Yaml::String(_)
        | Yaml::Boolean(_)
        | Yaml::Array(_)
        | Yaml::Hash(_)
        | Yaml::Alias(_)
        | Yaml::Null
        | Yaml::BadValue => {
            bail!("step {} {key} must be a positive integer", index + 1)
        }
    }
}

/// Feeding `vte` parse results into the visible character grid.
struct Term {
    parser: vte::Parser,
    screen: Screen,
}

impl Term {
    fn new(cols: u16, rows: u16) -> Self {
        Self {
            parser: vte::Parser::new(),
            screen: Screen::new(cols as usize, rows as usize),
        }
    }

    fn feed(&mut self, bytes: &[u8]) {
        // Split-borrow fields of self so the parser can drive the screen directly.
        let Self { parser, screen } = self;
        parser.advance(screen, bytes);
    }
}

/// A minimal visible-screen model: fixed `rows`×`cols` char grid + cursor +
/// scroll region + deferred wrap. `vte` drives it through `Perform`; SGR styling
/// and alt-screen bookkeeping are intentionally ignored — evidence is text-only.
struct Screen {
    cells: Vec<Vec<char>>,
    col: usize,
    row: usize,
    saved: (usize, usize),
    scroll_top: usize,
    scroll_bot: usize,
    pending_wrap: bool,
    wraparound: bool,
}

#[expect(
    clippy::indexing_slicing,
    reason = "grid invariants: cells is a fixed cols×rows matrix built in Screen::new, and every mutation path clamps the cursor/scroll region against cells.len()/row width before indexing — out-of-bounds is a construction bug, not reachable input"
)]
impl Screen {
    fn new(cols: usize, rows: usize) -> Self {
        Self {
            cells: vec![vec![' '; cols]; rows],
            col: 0,
            row: 0,
            saved: (0, 0),
            scroll_top: 0,
            scroll_bot: rows - 1,
            pending_wrap: false,
            wraparound: true,
        }
    }

    fn text(&self) -> String {
        self.cells
            .iter()
            .map(|row| row.iter().collect::<String>().trim_end().to_owned())
            .collect::<Vec<_>>()
            .join("\n")
    }

    fn put(&mut self, c: char) {
        if self.pending_wrap && self.wraparound {
            self.col = 0;
            self.line_feed();
        }
        self.pending_wrap = false;
        let width = UnicodeWidthChar::width(c).unwrap_or(1).clamp(1, 2);
        let Some(row) = self.cells.get_mut(self.row) else {
            return;
        };
        if self.col < row.len() {
            row[self.col] = c;
            if width == 2 && self.col + 1 < row.len() {
                row[self.col + 1] = ' ';
            }
        }
        if self.col + width >= self.cells[0].len() {
            self.pending_wrap = true;
            self.col = self.cells[0].len() - 1;
        } else {
            self.col += width;
        }
    }

    fn line_feed(&mut self) {
        if self.row == self.scroll_bot {
            // Scroll the region up by one: remove the top row, insert blank at bottom.
            if self.scroll_top < self.cells.len() && self.scroll_bot < self.cells.len() {
                let cols = self.cells[self.scroll_top].len();
                self.cells.remove(self.scroll_top);
                self.cells.insert(self.scroll_bot, vec![' '; cols]);
            }
        } else {
            self.row = (self.row + 1).min(self.cells.len() - 1);
        }
    }

    fn erase_line(&mut self, mode: usize) {
        let Some(row) = self.cells.get_mut(self.row) else {
            return;
        };
        match mode {
            1 => {
                let end = self.col.min(row.len() - 1);
                row[..=end].fill(' ');
            }
            2 => row.fill(' '),
            _ => row[self.col..].fill(' '),
        }
    }

    fn erase_display(&mut self, mode: usize) {
        match mode {
            1 => {
                for r in 0..self.row {
                    self.cells[r].fill(' ');
                }
                self.erase_line(1);
            }
            0 => {
                self.erase_line(0);
                for r in self.row + 1..self.cells.len() {
                    self.cells[r].fill(' ');
                }
            }
            _ => self.cells.iter_mut().for_each(|row| row.fill(' ')),
        }
    }

    fn clamp_cursor(&mut self) {
        let cols = self.cells.first().map_or(0, Vec::len);
        self.row = self.row.min(self.cells.len().saturating_sub(1));
        self.col = self.col.min(cols.saturating_sub(1));
        self.pending_wrap = false;
    }

    /// CSI cursor motion (CUP/CUU/CUD/CUF/CUB/CNL/CPL/CHA/VPA). `arg(i, d)`
    /// resolves parameter `i` with the ANSI default for a missing/zero value.
    fn csi_cursor(&mut self, action: char, arg: &dyn Fn(usize, usize) -> usize) {
        let bottom = self.cells.len().saturating_sub(1);
        let right = self.cells[0].len().saturating_sub(1);
        match action {
            'H' | 'f' => {
                self.row = arg(0, 1).saturating_sub(1);
                self.col = arg(1, 1).saturating_sub(1);
            }
            'A' => self.row = self.row.saturating_sub(arg(0, 1)),
            'B' => self.row = (self.row + arg(0, 1)).min(bottom),
            'C' => self.col = (self.col + arg(0, 1)).min(right),
            'D' => self.col = self.col.saturating_sub(arg(0, 1)),
            'E' => {
                self.row = (self.row + arg(0, 1)).min(bottom);
                self.col = 0;
            }
            'F' => {
                self.row = self.row.saturating_sub(arg(0, 1));
                self.col = 0;
            }
            'G' | '`' => self.col = arg(0, 1).saturating_sub(1).min(right),
            'd' => self.row = arg(0, 1).saturating_sub(1).min(bottom),
            _ => {}
        }
        self.clamp_cursor();
    }

    /// DECSET/DECRST per-subparameter handling: only DECAWM and alt-screen
    /// resets carry visible-screen meaning for this model.
    fn csi_modes(&mut self, params: &vte::Params, enable: bool) {
        for sub in params.iter().flatten() {
            match *sub {
                7 => self.wraparound = enable,
                1049 | 47 | 1048 if !enable => {
                    self.cells.iter_mut().for_each(|r| r.fill(' '));
                }
                _ => {}
            }
        }
    }

    /// SU/SD: scroll the region `count` lines up (toward top) or down.
    fn csi_scroll(&mut self, up: bool, count: usize) {
        let cols = self.cells[0].len();
        for _ in 0..count {
            if self.scroll_top < self.cells.len() && self.scroll_bot < self.cells.len() {
                if up {
                    self.cells.remove(self.scroll_top);
                    self.cells.insert(self.scroll_bot, vec![' '; cols]);
                } else {
                    self.cells.remove(self.scroll_bot);
                    self.cells.insert(self.scroll_top, vec![' '; cols]);
                }
            }
        }
    }

    /// IL/DL: insert or delete `count` lines at the cursor row inside the region.
    fn csi_insert_delete(&mut self, insert: bool, count: usize) {
        let count = count.min(self.scroll_bot - self.row.min(self.scroll_bot) + 1);
        let cols = self.cells[0].len();
        for _ in 0..count {
            if self.row <= self.scroll_bot && self.scroll_bot < self.cells.len() {
                if insert {
                    self.cells.remove(self.scroll_bot);
                    self.cells.insert(self.row, vec![' '; cols]);
                } else {
                    self.cells.remove(self.row);
                    self.cells.insert(self.scroll_bot, vec![' '; cols]);
                }
            }
        }
    }

    /// DCH/ECH/ICH: delete, erase or insert `count` cells at the cursor column.
    fn csi_cells(&mut self, action: char, count: usize) {
        let Some(row) = self.cells.get_mut(self.row) else {
            return;
        };
        let width = row.len();
        let end = (self.col + count).min(width);
        match action {
            'P' => {
                row.drain(self.col..end);
                row.resize(width, ' ');
            }
            'X' => row[self.col..end].fill(' '),
            '@' => {
                for _ in 0..count {
                    if self.col < row.len() {
                        row.insert(self.col, ' ');
                        row.truncate(width);
                    }
                }
            }
            _ => {}
        }
    }
}

#[expect(
    clippy::indexing_slicing,
    reason = "same grid invariants as impl Screen: cursor and scroll bounds are clamped before every index"
)]
impl vte::Perform for Screen {
    fn print(&mut self, c: char) {
        self.put(c);
    }

    fn execute(&mut self, byte: u8) {
        match byte {
            b'\r' => {
                self.col = 0;
                self.pending_wrap = false;
            }
            b'\n' | 0x0b | 0x0c => {
                self.pending_wrap = false;
                self.line_feed();
            }
            0x08 => {
                self.col = self.col.saturating_sub(1);
                self.pending_wrap = false;
            }
            b'\t' => {
                let next = (self.col / 8 + 1) * 8;
                self.col = next.min(self.cells[0].len() - 1);
                self.pending_wrap = false;
            }
            _ => {}
        }
    }

    fn csi_dispatch(
        &mut self,
        params: &vte::Params,
        _intermediates: &[u8],
        _ignore: bool,
        action: char,
    ) {
        let arg = |index: usize, default: usize| -> usize {
            params
                .iter()
                .nth(index)
                .and_then(|p| p.first().copied())
                .map_or(default, |v| if v == 0 { default } else { v as usize })
        };
        match action {
            'H' | 'f' | 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | '`' | 'd' => {
                self.csi_cursor(action, &arg);
            }
            'J' => self.erase_display(arg(0, 0)),
            'K' => self.erase_line(arg(0, 0)),
            's' => self.saved = (self.col, self.row),
            'u' => {
                let (col, row) = self.saved;
                self.col = col;
                self.row = row;
                self.clamp_cursor();
            }
            'r' => {
                let bottom = self.cells.len().saturating_sub(1);
                self.scroll_top = arg(0, 1).saturating_sub(1).min(bottom);
                self.scroll_bot = arg(1, self.cells.len()).saturating_sub(1).min(bottom);
                self.col = 0;
                self.row = 0;
                self.pending_wrap = false;
            }
            // DECAWM (7) toggles wrap; DECRST of 1049/47/1048 (alt screen + clear)
            // clears the grid — the TUI repaints a full frame on mode switch anyway.
            'h' | 'l' => self.csi_modes(params, action == 'h'),
            'S' | 'T' => self.csi_scroll(action == 'S', arg(0, 1)),
            'L' | 'M' => self.csi_insert_delete(action == 'L', arg(0, 1)),
            'P' | 'X' | '@' => self.csi_cells(action, arg(0, 1)),
            _ => {}
        }
    }

    fn esc_dispatch(&mut self, _intermediates: &[u8], _ignore: bool, byte: u8) {
        match byte {
            b'7' => self.saved = (self.col, self.row),
            b'8' => {
                let (col, row) = self.saved;
                self.col = col;
                self.row = row;
                self.clamp_cursor();
            }
            b'c' => *self = Self::new(self.cells[0].len(), self.cells.len()),
            _ => {}
        }
    }
}
