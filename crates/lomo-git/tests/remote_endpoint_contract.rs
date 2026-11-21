//! SCP forms without userinfo must be rejected by both precheck and endpoint parsing.

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
