//! Behavior Contract
//! Capability: a panicking TUI leaves a readable terminal and a written crash report.
//! Scenarios: rendering carries version, thread, message, location and backtrace; writing
//! persists a timestamped file under `crash/` and never overwrites a sibling report.
//! Observable outcomes: report file bytes under `$XDG_STATE_HOME/lomo/crash/`.
//! TDD proof: the TUI previously panicked with no report and a mangled terminal.
//! Excludes: exercising the global panic hook itself (process-wide side effect).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on crash artifacts"
)]
mod tests {
    use lomo_tui::crash::{format_report, write_report};
    use tempfile::tempdir;

    #[test]
    fn report_contains_version_thread_message_location_and_backtrace() {
        let report = format_report(
            "index out of bounds",
            Some("src/ui.rs:42:9"),
            "main",
            "stack backtrace: frame one",
        );
        for needle in [
            env!("CARGO_PKG_VERSION"),
            "index out of bounds",
            "src/ui.rs:42:9",
            "main",
            "frame one",
        ] {
            assert!(report.contains(needle), "report missing {needle}: {report}");
        }
        let no_location = format_report("boom", None, "worker", "bt");
        assert!(no_location.contains("boom"));
    }

    #[test]
    fn write_report_persists_under_crash_dir() {
        let dir = tempdir().expect("tmp");
        let path = write_report(dir.path(), "report body").expect("write");
        assert!(path.starts_with(dir.path().join("crash")));
        let persisted = std::fs::read_to_string(&path).expect("read");
        assert!(persisted.contains("report body"));
        let second = write_report(dir.path(), "another").expect("second");
        assert_ne!(path, second, "report files must not overwrite each other");
    }
}
