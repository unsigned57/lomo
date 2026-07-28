//! Stage-5 real provider smoke (Git HTTPS lines) — credential-gated, never part of `just ci`.
//!
//! # Behavior Contract
//!
//! **Capability:** prove that the production `lomo-git` adapter completes a real
//! `snapshot → EnsurePresent (tree/commit + non-force CAS push) → verify → EnsureAbsent →
//! verify-absent` round trip against a real Git HTTPS remote, on an isolated branch, using Unicode
//! Markdown and binary media paths.
//!
//! **Given/When/Then:**
//! - Given a real HTTPS repository URL plus a username/token for one locked line, when the smoke
//!   publishes an isolated branch containing a Unicode Markdown object and a binary media object,
//!   then verify observes both with the SHA-256 digests the planner intended.
//! - Given the verified branch state, when the smoke issues conditional `EnsureAbsent` intents,
//!   then verify observes `AbsentVerified` for both paths.
//! - Given a missing or blank credential, when the smoke resolves its configuration, then it panics
//!   rather than reporting a silent pass.
//!
//! **Observable outcomes:** publish receipt statuses, verified digests/tokens, absent verification.
//!
//! **Exclusions:** push-reject/divergence, stale lock reclaim, shallow deepen and merge-commit
//! resolution stay in `git_adapter_contract`; this target only proves the real HTTPS round trip.
//! No force push, no reset, no user worktree checkout is reachable from this target.
//!
//! **TDD proof:** every test is `#[ignore]`d, so `just check` / `just ci` never execute or "pass"
//! them. `just sync-provider-smoke` resolves credentials first and only then runs the matching
//! `--ignored --exact` test; unresolved lines stay `OPEN / pending_env`.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::panic,
    reason = "smoke tests fail closed with panics; a missing credential must never look like a pass"
)]
mod tests {
    use std::path::Path;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    use lomo_git::{WorkspaceFileGitObjectSource, connect_workspace_git};
    use lomo_sync::{
        BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, PublishReceipt, RemoteSyncPort, SyncPath, VerifiedRemoteState,
        VerifyStatus,
    };

    const SMOKE_TIMEOUT: Duration = Duration::from_mins(2);
    const MARKDOWN_BODY: &[u8] = "# 烟雾测试 memo\n\n- git provider round trip\n".as_bytes();
    const MEDIA_BODY: &[u8] = &[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0xfe, 0x01];

    /// Reads a required smoke credential, failing closed on missing or blank values.
    fn required_env(key: &str) -> String {
        let value = std::env::var(key).unwrap_or_else(|_| {
            panic!("provider smoke requires {key}; run via `just sync-provider-smoke`")
        });
        assert!(
            !value.trim().is_empty(),
            "provider smoke requires a non-blank {key}"
        );
        value
    }

    /// Builds a run-unique branch/prefix segment so concurrent smoke runs never collide.
    fn run_id() -> String {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock is after the unix epoch")
            .as_nanos();
        format!("lomo-smoke-{nanos}")
    }

    fn probe_paths(run: &str) -> (SyncPath, SyncPath) {
        let markdown = SyncPath::parse(&format!("memo/{run}/烟雾测试.md"))
            .expect("unicode markdown probe path is canonical");
        let media = SyncPath::parse(&format!("media/{run}/烟雾-probe.bin"))
            .expect("unicode media probe path is canonical");
        (markdown, media)
    }

    /// Materializes the two probe objects inside a temporary workspace root.
    fn stage_probe_workspace(root: &Path, markdown: &SyncPath, media: &SyncPath) {
        for (path, body) in [(markdown, MARKDOWN_BODY), (media, MEDIA_BODY)] {
            let absolute = root.join(path.as_str());
            let parent = absolute
                .parent()
                .expect("probe path always has a parent directory");
            std::fs::create_dir_all(parent).expect("probe parent directory is creatable");
            std::fs::write(&absolute, body).expect("probe body is writable");
        }
    }

    fn assert_all_applied(receipt: &PublishReceipt, expected: usize) {
        assert_eq!(
            receipt.path_results.len(),
            expected,
            "publish receipt must report one row per intent: {:?}",
            receipt.path_results
        );
        for (path, status) in &receipt.path_results {
            assert!(
                matches!(status, PathPublishStatus::Applied { .. }),
                "publish of {} must be applied, got {status:?}",
                path.as_str()
            );
        }
    }

    fn assert_verified_present(state: &VerifiedRemoteState, expected: &[(&SyncPath, &[u8])]) {
        assert!(
            state.all_verified(),
            "verify must observe every probe path: {:?}",
            state.results
        );
        for (path, body) in expected {
            let digest = ContentDigest::from_bytes(body);
            let found = state.results.iter().any(|result| match result {
                VerifyStatus::Verified {
                    path: seen,
                    digest: seen_digest,
                    ..
                } => seen.as_str() == path.as_str() && seen_digest == &digest,
                VerifyStatus::AbsentVerified { .. } | VerifyStatus::Failed { .. } => false,
            });
            assert!(
                found,
                "verify must report {} present with digest {}: {:?}",
                path.as_str(),
                digest.as_str(),
                state.results
            );
        }
    }

    fn assert_all_absent(state: &VerifiedRemoteState, paths: &[&SyncPath]) {
        for path in paths {
            let gone = state.results.iter().any(|result| match result {
                VerifyStatus::AbsentVerified { path: seen } => seen.as_str() == path.as_str(),
                VerifyStatus::Verified { .. } | VerifyStatus::Failed { .. } => false,
            });
            assert!(
                gone,
                "verify must report {} absent after conditional delete: {:?}",
                path.as_str(),
                state.results
            );
        }
    }

    fn ensure_present_batch(markdown: &SyncPath, media: &SyncPath) -> PreparedRemoteBatch {
        PreparedRemoteBatch::new(
            BatchAtomicity::WholeBatchRef,
            vec![
                ProviderNeutralIntent::EnsurePresent {
                    path: markdown.clone(),
                    digest: ContentDigest::from_bytes(MARKDOWN_BODY),
                    expected_remote_token: None,
                },
                ProviderNeutralIntent::EnsurePresent {
                    path: media.clone(),
                    digest: ContentDigest::from_bytes(MEDIA_BODY),
                    expected_remote_token: None,
                },
            ],
        )
        .expect("two-intent probe batch is within the action page ceiling")
    }

    /// Builds conditional `EnsureAbsent` intents from the tokens verify actually observed.
    fn ensure_absent_batch(
        state: &VerifiedRemoteState,
        paths: &[&SyncPath],
    ) -> PreparedRemoteBatch {
        let tokens = state.verified_present();
        let intents = paths
            .iter()
            .map(|path| {
                let token = tokens
                    .iter()
                    .find(|(seen, _digest, _token)| seen.as_str() == path.as_str())
                    .map_or_else(
                        || panic!("verify must expose a remote token for {}", path.as_str()),
                        |(_seen, _digest, token)| token.clone(),
                    );
                ProviderNeutralIntent::EnsureAbsent {
                    path: (*path).clone(),
                    expected_remote_token: token,
                }
            })
            .collect();
        PreparedRemoteBatch::new(BatchAtomicity::WholeBatchRef, intents)
            .expect("two-intent cleanup batch is within the action page ceiling")
    }

    fn git_round_trip(url_key: &str, username_key: &str, token_key: &str) {
        let run = run_id();
        let (markdown, media) = probe_paths(&run);
        let workspace = tempfile::tempdir().expect("probe workspace root is creatable");
        stage_probe_workspace(workspace.path(), &markdown, &media);
        let mirror = tempfile::tempdir().expect("app-private bare mirror root is creatable");

        let adapter = connect_workspace_git(
            &required_env(url_key),
            &run,
            mirror.path().join("mirror.git"),
            &required_env(username_key),
            &required_env(token_key),
            WorkspaceFileGitObjectSource::new(workspace.path()),
            "Lomo Provider Smoke",
            "smoke@lomo.invalid",
            SMOKE_TIMEOUT,
        )
        .expect("git adapter must connect with the supplied smoke credentials");

        adapter
            .list_remote()
            .expect("read-only snapshot must succeed before any write");

        let publish = adapter
            .publish(&ensure_present_batch(&markdown, &media))
            .expect("non-force CAS push must succeed on the isolated smoke branch");
        assert_all_applied(&publish, 2);

        let verified = adapter
            .verify(&[markdown.clone(), media.clone()])
            .expect("verify must re-read the pushed ref/tree");
        assert_verified_present(
            &verified,
            &[(&markdown, MARKDOWN_BODY), (&media, MEDIA_BODY)],
        );

        let cleanup = adapter
            .publish(&ensure_absent_batch(&verified, &[&markdown, &media]))
            .expect("conditional ensure-absent push must succeed");
        assert_all_applied(&cleanup, 2);

        let absent = adapter
            .verify(&[markdown.clone(), media.clone()])
            .expect("verify must re-read the ref/tree after delete");
        assert_all_absent(&absent, &[&markdown, &media]);
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke github`"]
    fn github_https_round_trip_publishes_verifies_and_deletes() {
        git_round_trip(
            "LOMO_SMOKE_GITHUB_URL",
            "LOMO_SMOKE_GITHUB_USERNAME",
            "LOMO_SMOKE_GITHUB_TOKEN",
        );
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke gitlab`"]
    fn gitlab_https_round_trip_publishes_verifies_and_deletes() {
        git_round_trip(
            "LOMO_SMOKE_GITLAB_URL",
            "LOMO_SMOKE_GITLAB_USERNAME",
            "LOMO_SMOKE_GITLAB_TOKEN",
        );
    }
}
