//! Behavior Contract
//! Capability: `LOMO_LOG` opt-in file logging under the state dir.
//! Scenarios: no filter wires nothing; a filter captures events into `lomo.log` after the
//! writer guard flushes, without ANSI escapes.
//! Observable outcomes: `$XDG_STATE_HOME/lomo/lomo.log` contents.
//! TDD proof: no diagnostics channel existed for field reports.
//! Excludes: log rotation policy and global subscriber idempotency across tests.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on log artifacts"
)]
mod tests {
    use lomo_tui::logging::{file_dispatch, init_logging};
    use tempfile::tempdir;

    #[test]
    fn missing_filter_disables_logging() {
        let dir = tempdir().expect("tmp");
        assert!(init_logging(dir.path(), None).is_none());
        assert!(!dir.path().join("lomo.log").exists());
    }

    #[test]
    fn enabled_filter_captures_events_in_lomo_log() {
        let dir = tempdir().expect("tmp");
        let (dispatch, guard) = file_dispatch(dir.path(), "info").expect("dispatch");
        tracing::dispatcher::with_default(&dispatch, || {
            tracing::info!(answer = 42, "contract event");
        });
        drop(guard);
        let log = std::fs::read_to_string(dir.path().join("lomo.log")).expect("log file");
        assert!(log.contains("contract event"), "log missing event: {log}");
        assert!(log.contains("answer"), "log missing fields: {log}");
        assert!(
            !log.contains('\u{1b}'),
            "file logs must not carry ANSI escapes: {log:?}"
        );
    }
}
