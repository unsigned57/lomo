//! Behavior Contract
//!
//! Capability: prove vendored `libgit2` local bare transport and HTTPS smart-HTTP (credentials,
//! certificate rejection, push rejection, lock recovery) without public network.
//!
//! Scenarios:
//! - Given an empty directory, when the local git probe runs, then commits exist on `main`, a
//!   `feature` branch tip is present, bare push/fetch succeed, and a rebase conflict is observed.
//! - Given a local HTTPS smart-HTTP fixture, when clone/push/fetch/cert/non-ff/lock probes run,
//!   then each capability flag is true.
//!
//! Observable outcomes: bare report flags; smart-HTTP report flags all true.
//! Excludes: public network remotes, `JGit`, production DI.

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use std::time::{SystemTime, UNIX_EPOCH};

    use lomo_feasibility::{run_local_git_probe, run_smart_http_git_probe};

    #[test]
    fn vendored_libgit2_push_fetch_and_rebase_conflict_locally() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("lomo-git-probe-{nanos}"));
        let report = run_local_git_probe(&root).expect("git probe");
        assert!(report.commit_count >= 2);
        assert!(report.remote.has_diverged_branch);
        assert!(report.remote.pushed_to_bare);
        assert!(report.remote.fetched_from_bare);
        assert!(report.remote.rebase_conflict_observed);
        assert!(!report.head_message.is_empty());
        drop(std::fs::remove_dir_all(root));
    }

    #[test]
    fn smart_http_git_matrix_covers_p0_09_capabilities() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("lomo-git-http-{nanos}"));
        let report = run_smart_http_git_probe(&root).expect("smart-http git probe");
        assert!(report.cloned);
        assert!(report.pushed);
        assert!(report.fetched);
        assert!(report.credential_accepted);
        assert!(report.certificate_rejected);
        assert!(report.non_fast_forward_rejected);
        assert!(report.lock_recovery);
        drop(std::fs::remove_dir_all(root));
    }
}
