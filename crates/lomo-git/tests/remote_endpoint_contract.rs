// adversarial-audit: validate_git_remote_url rejects every SSH/SCP form, not only `user@host:path`
//
// Probe: `is_scp_like_ssh` in `crates/lomo-git/src/endpoint.rs` only rejects SCP syntax when the
// pre-colon segment contains `@`. `host:path` (SCP without userinfo) carries no `://`, falls
// through `validate_remote_url_shape`, and is classified as a "plain local path" — then the same
// string is handed verbatim to `repo.remote("origin", url)` (`adapter.rs::origin_remote`), where
// libgit2 interprets `host:path` as SSH transport syntax. The HTTPS-only boundary claim is
// therefore enforced by accident of the missing `ssh` git2 feature, not by validation.
//
// These tests assert the *claimed* contract (spec requirement: 拒绝 SCP 形态). A failure documents the
// residual hole; controls pin the rejections that do work.
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial probes fail closed on missing facts"
)]
mod tests {
    use lomo_git::{GitEndpoint, GitLocalMode, validate_git_remote_url};

    fn endpoint_parse(url: &str) -> Result<GitEndpoint, lomo_core::LomoError> {
        GitEndpoint::parse(
            url,
            "main",
            GitLocalMode::AppPrivateBareMirror {
                mirror_dir: std::path::PathBuf::from("/nonexistent-mirror"),
            },
        )
    }

    // --- controls: rejections that must hold -----------------------------------

    #[test]
    fn control_scp_with_userinfo_is_rejected() {
        let err = validate_git_remote_url("git@example.com:org/repo.git")
            .expect_err("user@host:path must stay rejected");
        assert_eq!(err.code(), "git_ssh_not_supported");
    }

    #[test]
    fn control_ssh_scheme_is_rejected() {
        let err =
            validate_git_remote_url("ssh://git@example.com/org/repo.git").expect_err("ssh://");
        assert_eq!(err.code(), "git_ssh_not_supported");
    }

    #[test]
    fn control_https_userinfo_is_rejected() {
        let err = validate_git_remote_url("https://user:pass@example.com/org/repo.git")
            .expect_err("userinfo must be rejected");
        assert_eq!(err.code(), "git_url_userinfo_rejected");
    }

    #[test]
    fn control_https_remote_is_accepted() {
        validate_git_remote_url("https://example.com/org/repo.git").expect("https remote ok");
        endpoint_parse("https://example.com/org/repo.git").expect("endpoint https ok");
    }

    // --- probes: SCP forms without `@` ------------------------------------------

    /// `host:path` is SCP SSH syntax to git/libgit2. It must be rejected at the boundary.
    #[test]
    fn probe_scp_without_userinfo_must_be_rejected() {
        let err = validate_git_remote_url("example.com:org/repo.git")
            .expect_err("host:path SCP syntax passes validation unchallenged");
        assert_eq!(err.code(), "git_ssh_not_supported");
    }

    /// The same hole reaches `GitEndpoint::parse` — the adapter trusts the stored URL.
    #[test]
    fn probe_endpoint_parse_rejects_scp_without_userinfo() {
        let err = endpoint_parse("example.com:org/repo.git")
            .expect_err("GitEndpoint::parse accepts SCP host:path");
        assert_eq!(err.code(), "git_ssh_not_supported");
    }

    /// `host:port/path`-flavoured SCP (`ssh -p` equivalent) shares the same hole.
    #[test]
    fn probe_scp_port_form_must_be_rejected() {
        let err = validate_git_remote_url("example.com:2222/org/repo.git")
            .expect_err("host:port/path form passes validation");
        assert_eq!(err.code(), "git_ssh_not_supported");
    }
}
