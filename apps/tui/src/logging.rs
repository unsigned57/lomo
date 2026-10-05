//! Opt-in file logging: `LOMO_LOG=debug lomo` appends to `state_dir/lomo.log`.

use std::path::Path;

use tracing::Dispatch;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::{EnvFilter, fmt};

use crate::error::TuiError;

/// Builds a subscriber writing `lomo.log` under `state_dir`.
///
/// # Errors
/// An invalid `filter` expression.
pub fn file_dispatch(state_dir: &Path, filter: &str) -> Result<(Dispatch, WorkerGuard), TuiError> {
    let writer = tracing_appender::rolling::never(state_dir, "lomo.log");
    let (writer, guard) = tracing_appender::non_blocking(writer);
    let filter = EnvFilter::try_new(filter)
        .map_err(|error| TuiError::config(format!("invalid LOMO_LOG filter {filter}: {error}")))?;
    let subscriber = fmt::Subscriber::builder()
        .with_env_filter(filter)
        .with_writer(writer)
        .finish();
    Ok((Dispatch::new(subscriber), guard))
}

/// Wires global file logging when `filter` (`LOMO_LOG`) is set. Logging is a
/// diagnostic aid, not a gate: subscriber or file failures degrade to no logging.
///
/// An explicitly invalid filter is *not* silently treated as unset — the
/// diagnostic goes to stderr (the only channel guaranteed before the TUI takes
/// the terminal) and logging installs at `warn` so the request still produces
/// output. That keeps `LOMO_LOG='[[['` observably different from no `LOMO_LOG`.
#[must_use]
pub fn init_logging(state_dir: &Path, filter: Option<&str>) -> Option<WorkerGuard> {
    let filter = filter?;
    let (dispatch, guard) = match file_dispatch(state_dir, filter) {
        Ok(pair) => pair,
        Err(error) => {
            // stderr is deliberate: the TUI owns the terminal after this, and
            // tracing is exactly what is broken — this is the only channel
            // guaranteed to surface the diagnostic before the handoff.
            use std::io::Write as _;
            let _written = writeln!(
                std::io::stderr(),
                "lomo: {error}; logging at 'warn' instead"
            );
            match file_dispatch(state_dir, "warn") {
                Ok(pair) => pair,
                Err(fallback_error) => {
                    // behavior-contract: silent-result-ok: `LOMO_LOG` was
                    // requested but even warn-level file logging cannot be
                    // installed (e.g. the state dir is unwritable). The
                    // diagnostic goes to stderr — the only channel that still
                    // exists before the TUI owns the terminal — and the
                    // session continues with no log file rather than dying.
                    let _written = writeln!(
                        std::io::stderr(),
                        "lomo: {fallback_error}; file logging unavailable"
                    );
                    return None;
                }
            }
        }
    };
    if let Err(error) = tracing::dispatcher::set_global_default(dispatch) {
        // behavior-contract: silent-result-ok: another subscriber is already
        // installed (embedding/test harness) — file logging defers to it,
        // reports on stderr, and the session continues.
        use std::io::Write as _;
        let _written = writeln!(std::io::stderr(), "lomo: logging not installed: {error}");
        return None;
    }
    Some(guard)
}
