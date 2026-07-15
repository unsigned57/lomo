//! Behavior Contract
//!
//! Capability: prove vendored `libgit2` can open a local repository, commit, push/fetch a bare
//! remote, and observe a rebase conflict without public network.
//!
//! Scenarios:
//! - Given an empty directory, when the local git probe runs, then commits exist on `main`, a
//!   `feature` branch tip is present, bare push/fetch succeed, and a rebase conflict is observed.
//!
//! Observable outcomes: `commit_count` >= 2, remote flags set, rebase conflict observed.
//! Excludes: public network remotes, HTTPS smart-HTTP, `JGit`.

#[cfg(test)]
mod tests {
    use std::time::{SystemTime, UNIX_EPOCH};

    use lomo_feasibility::run_local_git_probe;

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
}
