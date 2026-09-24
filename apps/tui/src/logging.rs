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
#[must_use]
pub fn init_logging(state_dir: &Path, filter: Option<&str>) -> Option<WorkerGuard> {
    let filter = filter?;
    let Ok((dispatch, guard)) = file_dispatch(state_dir, filter) else {
        return None;
    };
    if tracing::dispatcher::set_global_default(dispatch).is_err() {
        return None;
    }
    Some(guard)
}
