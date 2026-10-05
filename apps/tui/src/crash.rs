//! Panic recovery: restore the terminal and persist a report under `state_dir/crash/`.

use std::{
    backtrace::Backtrace,
    fs,
    io::{self, Write},
    panic::PanicHookInfo,
    path::{Path, PathBuf},
    thread,
    time::{SystemTime, UNIX_EPOCH},
};

const CRASH_DIR: &str = "crash";

/// Installs the process panic hook. Call before entering the alternate screen.
pub fn install_panic_hook(state_dir: PathBuf) {
    std::panic::set_hook(Box::new(move |info| {
        // A lane/watcher/monitor panic is caught by its supervisor and
        // reported through the outbox — only the UI thread owns the terminal,
        // so restoring it from a background thread would corrupt the live
        // frame instead of helping (F-06 supervision keeps the failure loud).
        let background = thread::current()
            .name()
            .is_some_and(|name| name.starts_with("lomo-bg"));
        if !background {
            restore_terminal();
        }
        let report = render_report(info);
        let notice = match write_report(&state_dir, &report) {
            Ok(path) => format!("lomo crashed; report written to {}", path.display()),
            Err(write_error) => {
                format!("lomo crashed; could not write report ({write_error})\n{report}")
            }
        };
        drop(writeln!(io::stderr(), "{notice}"));
    }));
}

/// Renders the report body for a live panic payload.
fn render_report(info: &PanicHookInfo<'_>) -> String {
    let message = info.payload().downcast_ref::<&str>().map_or_else(
        || {
            info.payload()
                .downcast_ref::<String>()
                .map_or("non-string panic payload", String::as_str)
        },
        |value| *value,
    );
    let location = info
        .location()
        .map(|place| format!("{}:{}:{}", place.file(), place.line(), place.column()));
    let thread = thread::current();
    let thread_name = thread.name().unwrap_or("unnamed");
    format_report(
        message,
        location.as_deref(),
        thread_name,
        &Backtrace::force_capture().to_string(),
    )
}

/// Renders a crash report body from extracted fields.
#[must_use]
pub fn format_report(
    message: &str,
    location: Option<&str>,
    thread: &str,
    backtrace: &str,
) -> String {
    let location = location.map_or_else(String::new, |place| format!("location: {place}\n"));
    format!(
        "lomo {} crash report\nthread: {thread}\nmessage: {message}\n{location}backtrace:\n{backtrace}\n",
        env!("CARGO_PKG_VERSION")
    )
}

/// Persists a report under `state_dir/crash/`, returning the written path.
///
/// # Errors
/// Directory creation or file write failures.
pub fn write_report(state_dir: &Path, report: &str) -> io::Result<PathBuf> {
    let dir = state_dir.join(CRASH_DIR);
    fs::create_dir_all(&dir)?;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |span| span.as_nanos());
    let path = dir.join(format!("crash-{nanos}-{}.log", std::process::id()));
    fs::write(&path, report)?;
    Ok(path)
}

/// Best-effort terminal restore. stderr is used so a stdout lock held by the
/// panicking thread cannot deadlock the hook.
fn restore_terminal() {
    use crossterm::{
        cursor::Show,
        event::{DisableBracketedPaste, DisableFocusChange, DisableMouseCapture},
        execute,
        terminal::{LeaveAlternateScreen, disable_raw_mode},
    };
    drop(disable_raw_mode());
    let mut out = io::stderr();
    // Expire transmitted kitty image data before leaving the screen — a
    // crashed session must not strand pixels on the restored display (D-09).
    // The APC is ignored by terminals without kitty support.
    drop(out.write_all(b"\x1b_Ga=d,d=A,q=2\x1b\\"));
    drop(execute!(
        out,
        DisableMouseCapture,
        DisableBracketedPaste,
        DisableFocusChange,
        LeaveAlternateScreen,
        Show
    ));
}
