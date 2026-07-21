//! Behavior Contract
//!
//! Capability: linked feasibility-device bundle executes `SQLite` + Markdown and retains
//! git2 + reqwest/Rustls via live call markers.
//!
//! Scenarios:
//! - Given a clean host, when `run_feasibility_device_bundle` runs, then `sqlite_ok` is true,
//!   `markdown_events` is positive, and detail includes `git2:` and `reqwest-rustls:` markers.
//!
//! Observable outcomes: report fields.
//! Excludes: full HTTP/Git network fixtures, production packaging.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_feasibility::{MARKER_GIT2, MARKER_REQWEST_RUSTLS, MARKER_SQLITE};
    use lomo_feasibility_device::run_feasibility_device_bundle;

    #[test]
    fn linked_bundle_runs_sqlite_markdown_and_link_markers() {
        let report = run_feasibility_device_bundle().expect("bundle");
        assert!(report.sqlite_ok);
        assert!(report.markdown_events > 0);
        for marker in [MARKER_SQLITE, MARKER_GIT2, MARKER_REQWEST_RUSTLS] {
            assert!(
                report.detail.contains(marker),
                "missing retention marker {marker}: {}",
                report.detail
            );
        }
    }
}
