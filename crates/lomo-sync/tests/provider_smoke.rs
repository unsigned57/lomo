//! Stage-5 real provider smoke (`WebDAV` + S3 lines) — credential-gated, never part of `just ci`.
//!
//! # Behavior Contract
//!
//! **Capability:** prove that the production `lomo-sync` adapter completes a real
//! `snapshot → EnsurePresent → verify → EnsureAbsent → verify-absent` round trip against a real
//! remote provider under an isolated prefix, using Unicode Markdown and binary media paths.
//!
//! **Given/When/Then:**
//! - Given a real provider endpoint plus credentials for one locked line, when the smoke publishes
//!   an isolated Unicode Markdown object and a binary media object, then verify observes both with
//!   the SHA-256 digests the planner intended.
//! - Given the verified remote tokens, when the smoke issues conditional `EnsureAbsent` intents,
//!   then verify observes `AbsentVerified` for both paths and the isolated prefix is left clean.
//! - Given a missing or blank credential, when the smoke resolves its configuration, then it panics
//!   rather than reporting a silent pass (a credential-less run can never look GREEN).
//!
//! **Observable outcomes:** publish receipt statuses, verified digests/tokens, absent verification.
//!
//! **Exclusions:** conflict sessions, process-death resume, rclone mode matrix, and scale budgets
//! stay in the hermetic contracts; this target only proves the real-wire provider round trip.
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

    use lomo_sync::{
        BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, RemoteSyncPort, S3WorkspaceFileObjectSource, SyncPath, VerifyStatus,
        WebDavWorkspaceFileObjectSource, connect_workspace_s3, connect_workspace_webdav,
    };

    const SMOKE_TIMEOUT: Duration = Duration::from_mins(1);
    const MARKDOWN_BODY: &[u8] = "# 烟雾测试 memo\n\n- provider round trip\n".as_bytes();
    const MEDIA_BODY: &[u8] = &[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0xff, 0x00];

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

    fn optional_env(key: &str, fallback: &str) -> String {
        match std::env::var(key) {
            Ok(value) if !value.trim().is_empty() => value,
            Ok(_) | Err(_) => fallback.to_owned(),
        }
    }

    /// Builds a run-unique prefix segment so concurrent smoke runs never collide.
    fn run_id() -> String {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock is after the unix epoch")
            .as_nanos();
        format!("lomo-smoke-{nanos}")
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

    fn probe_paths(run: &str) -> (SyncPath, SyncPath) {
        let markdown = SyncPath::parse(&format!("memo/{run}/烟雾测试.md"))
            .expect("unicode markdown probe path is canonical");
        let media = SyncPath::parse(&format!("media/{run}/烟雾-probe.bin"))
            .expect("unicode media probe path is canonical");
        (markdown, media)
    }

    fn ensure_present_batch(markdown: &SyncPath, media: &SyncPath) -> PreparedRemoteBatch {
        PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
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

    fn assert_all_applied(receipt: &lomo_sync::PublishReceipt, expected: usize) {
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

    /// Asserts the two probe paths verified present with the digests the planner intended.
    fn assert_verified_present(
        state: &lomo_sync::VerifiedRemoteState,
        expected: &[(&SyncPath, &[u8])],
    ) {
        assert!(
            state.all_verified(),
            "verify must observe every probe path: {:?}",
            state.results
        );
        for (path, body) in expected {
            let digest = ContentDigest::from_bytes(body);
            let found = state.results.iter().any(|result| match result {
                VerifyStatus::Verified {
                    path: verified,
                    digest: verified_digest,
                    ..
                } => verified.as_str() == path.as_str() && verified_digest == &digest,
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

    fn assert_all_absent(state: &lomo_sync::VerifiedRemoteState, paths: &[&SyncPath]) {
        for path in paths {
            let absent = state.results.iter().any(|result| match result {
                VerifyStatus::AbsentVerified { path: verified } => {
                    verified.as_str() == path.as_str()
                }
                VerifyStatus::Verified { .. } | VerifyStatus::Failed { .. } => false,
            });
            assert!(
                absent,
                "verify must report {} absent after conditional delete: {:?}",
                path.as_str(),
                state.results
            );
        }
    }

    /// Builds conditional `EnsureAbsent` intents from the tokens verify actually observed.
    fn ensure_absent_batch(
        state: &lomo_sync::VerifiedRemoteState,
        paths: &[&SyncPath],
    ) -> PreparedRemoteBatch {
        let tokens = state.verified_present();
        let intents = paths
            .iter()
            .map(|path| {
                let token = tokens
                    .iter()
                    .find(|(verified, _digest, _token)| verified.as_str() == path.as_str())
                    .map_or_else(
                        || panic!("verify must expose a remote token for {}", path.as_str()),
                        |(_verified, _digest, token)| token.clone(),
                    );
                ProviderNeutralIntent::EnsureAbsent {
                    path: (*path).clone(),
                    expected_remote_token: token,
                }
            })
            .collect();
        PreparedRemoteBatch::new(BatchAtomicity::PerPath, intents)
            .expect("two-intent cleanup batch is within the action page ceiling")
    }

    /// Runs the full round trip against any adapter implementing the production remote port.
    fn run_round_trip(port: &impl RemoteSyncPort, markdown: &SyncPath, media: &SyncPath) {
        port.list_remote()
            .expect("read-only snapshot must succeed before any write");

        let publish = port
            .publish(&ensure_present_batch(markdown, media))
            .expect("conditional ensure-present publish must succeed");
        assert_all_applied(&publish, 2);

        let verified = port
            .verify(&[markdown.clone(), media.clone()])
            .expect("verify must re-read both probe paths");
        assert_verified_present(&verified, &[(markdown, MARKDOWN_BODY), (media, MEDIA_BODY)]);

        let cleanup = port
            .publish(&ensure_absent_batch(&verified, &[markdown, media]))
            .expect("conditional ensure-absent publish must succeed");
        assert_all_applied(&cleanup, 2);

        let absent = port
            .verify(&[markdown.clone(), media.clone()])
            .expect("verify must re-read both probe paths after delete");
        assert_all_absent(&absent, &[markdown, media]);
    }

    fn webdav_round_trip(url_key: &str, user_key: &str, password_key: &str, root_key: &str) {
        let run = run_id();
        let (markdown, media) = probe_paths(&run);
        let workspace = tempfile::tempdir().expect("probe workspace root is creatable");
        stage_probe_workspace(workspace.path(), &markdown, &media);
        let exchange = tempfile::tempdir().expect("probe exchange root is creatable");

        let base = required_env(url_key);
        let root = optional_env(root_key, "");
        let endpoint = if root.is_empty() {
            base
        } else {
            format!("{}/{}", base.trim_end_matches('/'), root.trim_matches('/'))
        };
        let adapter = connect_workspace_webdav(
            &endpoint,
            &required_env(user_key),
            &required_env(password_key),
            exchange.path(),
            WebDavWorkspaceFileObjectSource::new(workspace.path()),
            SMOKE_TIMEOUT,
        )
        .expect("webdav adapter must connect with the supplied smoke credentials");

        adapter
            .preflight()
            .expect("read-only preflight must report remote capabilities before any write");
        run_round_trip(&adapter, &markdown, &media);
    }

    fn s3_round_trip(prefix_env: &str) {
        let run = run_id();
        let (markdown, media) = probe_paths(&run);
        let workspace = tempfile::tempdir().expect("probe workspace root is creatable");
        stage_probe_workspace(workspace.path(), &markdown, &media);
        let exchange = tempfile::tempdir().expect("probe exchange root is creatable");

        let adapter = connect_workspace_s3(
            &required_env(&format!("{prefix_env}_ENDPOINT")),
            &required_env(&format!("{prefix_env}_BUCKET")),
            &optional_env(&format!("{prefix_env}_PREFIX"), "lomo-smoke"),
            &required_env(&format!("{prefix_env}_REGION")),
            &required_env(&format!("{prefix_env}_ACCESS_KEY_ID")),
            &required_env(&format!("{prefix_env}_SECRET_ACCESS_KEY")),
            exchange.path(),
            S3WorkspaceFileObjectSource::new(workspace.path()),
            SMOKE_TIMEOUT,
        )
        .expect("s3 adapter must connect with the supplied smoke credentials");

        run_round_trip(&adapter, &markdown, &media);
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke nutstore`"]
    fn nutstore_webdav_round_trip_publishes_verifies_and_deletes() {
        webdav_round_trip(
            "LOMO_SMOKE_NUTSTORE_URL",
            "LOMO_SMOKE_NUTSTORE_USERNAME",
            "LOMO_SMOKE_NUTSTORE_PASSWORD",
            "LOMO_SMOKE_NUTSTORE_ROOT",
        );
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke nextcloud`"]
    fn nextcloud_webdav_round_trip_publishes_verifies_and_deletes() {
        webdav_round_trip(
            "LOMO_SMOKE_NEXTCLOUD_URL",
            "LOMO_SMOKE_NEXTCLOUD_USERNAME",
            "LOMO_SMOKE_NEXTCLOUD_PASSWORD",
            "LOMO_SMOKE_NEXTCLOUD_ROOT",
        );
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke aws-s3`"]
    fn aws_s3_round_trip_publishes_verifies_and_deletes() {
        s3_round_trip("LOMO_SMOKE_AWS");
    }

    #[test]
    #[ignore = "real provider credentials; run via `just sync-provider-smoke cloudflare-r2`"]
    fn cloudflare_r2_round_trip_publishes_verifies_and_deletes() {
        s3_round_trip("LOMO_SMOKE_R2");
    }
}
