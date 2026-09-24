//! Behavior Contract (P5-07 `lomo-git` adapter — host hermetic bare-repo slice)
//!
//! Capability: public [`RemoteSyncPort`] Git adapter compiles path intents into tree/commit +
//! non-force CAS push (`WholeBatchRef`); sole `git2` production-graph owner. Core owns direction/
//! conflict/baseline/tombstone/retry. Hermetic local bare remotes only (no public network).
//!
//! Scenarios:
//! - Given an empty bare remote + app-private mirror, when `EnsurePresent` publishes, then a
//!   non-force CAS push lands and `list_remote`/`verify` observe the path digest.
//! - Given a concurrent remote tip change, when publish runs with stale expected token, then
//!   `PreconditionFailed` (no force push).
//! - Given a non-fast-forward remote tip, when non-force push runs, then push is rejected /
//!   `PreconditionFailed`.
//! - Given SSH-style URL, when endpoint parses, then validation `git_ssh_not_supported`.
//! - Given credentials Debug, when formatted, then username/token are redacted.
//! - Given a diagnostic with URL userinfo / token=, when redacted, then secrets are stripped.
//! - Given index.lock with dead owner PID older than threshold, when reclaim runs, then Reclaimed;
//!   live owner or young lock remains Held / Busy.
//! - Given app-private mirror rebuild, when rebuild runs, then only mirror objects/cache are
//!   deleted and re-inited bare (not user workspace).
//! - Given `PerPath` batch atomicity, when publish runs, then validation `git_batch_atomicity`.
//! - Given a planner-shaped `WholeBatchRef` batch whose path tokens are blob OIDs and whose
//!   snapshot token is the branch tip, when publish runs against a non-empty remote, then Applied
//!   (CAS compares tip to tip).
//! - Given a remote tree larger than the 512-item page, when `list_remote_pages` runs, then a
//!   Complete multi-page stream; `list_remote` stays one Incomplete page.
//! - Given diverged local HEAD and remote tip (conflict-resolve shape), when `KeepLocal` body
//!   publishes, then the resulting commit is single-parent on the observed remote tip and the
//!   tree carries the resolved body; non-force CAS still applies.
//! - Given one adapter cycle, when list/verify/resolve run, then one fetch serves all calls and
//!   the pinned remote tip is shared (cycle stats observable).
//! - Given objects beyond the per-object / per-batch / mirror disk budgets, when publish runs,
//!   then resource-limit failures reject before unbounded reads or ODB growth.
//! - Given a crashed publish leaving `refs/lomo/push/*` staging refs, when connect runs, then
//!   reconciliation reclaims them; success and failed pushes leave no staging refs.
//! - Given a half-initialized (corrupt) app-private mirror, when open runs, then the tree is
//!   quarantined intact and rebuilt exactly once.
//! - Given a zero network timeout, when a remote operation runs, then `git_deadline_exceeded`.
//!
//! Observable outcomes: [`PathPublishStatus`], [`SnapshotCompleteness`], redacted diagnostics,
//! lock reclaim outcomes, non-force push only, single-parent publish on observed remote tip,
//! bounded ref lifecycle, quarantined mirror evidence, cycle fetch stats.
//! Excludes: production DI, GitHub/GitLab real HTTPS smoke, force push, checkout/reset user files,
//! arm64 device, dual DI.
//! Host-hermetic bare-repo matrix only.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::cast_possible_wrap,
    reason = "contract tests fail closed with panics; hermetic bare-repo setup is dense by nature"
)]
mod tests {
    use std::fs;
    use std::path::Path;
    use std::time::{Duration, SystemTime};

    use git2::{Repository, RepositoryInitOptions, Signature};
    use lomo_git::{
        GitCredentials, GitLocalMode, LockReclaimOutcome, MapGitConnectParams, MapGitObjectSource,
        connect_map_git_source, process_alive, rebuild_app_private_mirror, redact_diagnostic,
        try_reclaim_stale_index_lock, write_index_lock,
    };
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ContentDigest, LocalPathEntry, LocalSnapshot,
        MAX_ACTION_PAGE_ITEMS, PathPublishStatus, PreparedRemoteBatch, ProviderNeutralIntent,
        RemoteDigestFact, RemotePublishContract, RemoteSyncPort, SessionKind, SyncIdentityFence,
        SyncPath, TombstoneSet, VerifyExpectation, plan_intents_with_atomicity,
    };
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::parse(&format!("{:x}", Sha256::digest(bytes))).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn init_bare(path: &Path) {
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(path, &opts).expect("init bare");
    }

    fn seed_remote_with_file(bare: &Path, relative: &str, bytes: &[u8]) -> String {
        let tmp = tempdir().expect("tmp");
        let work = tmp.path().join("seed");
        fs::create_dir_all(&work).expect("work");
        let mut opts = RepositoryInitOptions::new();
        opts.initial_head("main");
        let repo = Repository::init_opts(&work, &opts).expect("work init");
        let sig = Signature::now("seed", "seed@lomo.local").expect("sig");
        let file = work.join(relative);
        if let Some(parent) = file.parent() {
            fs::create_dir_all(parent).expect("parent");
        }
        fs::write(&file, bytes).expect("write");
        let mut index = repo.index().expect("index");
        index.add_path(Path::new(relative)).expect("add");
        index.write().expect("index write");
        let tree_id = index.write_tree().expect("tree");
        let tree = repo.find_tree(tree_id).expect("find tree");
        repo.commit(Some("HEAD"), &sig, &sig, "seed", &tree, &[])
            .expect("commit");
        let url = bare.to_str().expect("utf8 bare").to_owned();
        let mut remote = repo.remote("origin", &url).expect("remote");
        remote
            .push(&["refs/heads/main:refs/heads/main"], None)
            .expect("push seed");
        // Return tip oid
        let tip = repo.refname_to_id("refs/heads/main").expect("tip");
        tip.to_string()
    }

    fn adapter_mirror(
        bare: &Path,
        mirror: &Path,
        objects: MapGitObjectSource,
    ) -> lomo_git::GitAdapter<MapGitObjectSource> {
        connect_map_git_source(MapGitConnectParams {
            remote_url: bare.to_str().expect("utf8"),
            branch: "main",
            local: GitLocalMode::AppPrivateBareMirror {
                mirror_dir: mirror.to_path_buf(),
            },
            credentials: GitCredentials::anonymous(),
            objects,
            timeout: Duration::from_secs(5),
            author_name: "lomo-git",
            author_email: "git@lomo.local",
        })
        .expect("adapter")
    }

    fn whole_batch(
        intents: Vec<ProviderNeutralIntent>,
        expected_snapshot_token: Option<String>,
    ) -> PreparedRemoteBatch {
        PreparedRemoteBatch::with_snapshot_token(
            BatchAtomicity::WholeBatchRef,
            intents,
            expected_snapshot_token,
        )
        .expect("batch")
    }

    #[test]
    fn credentials_debug_redacts_secrets() {
        let creds = GitCredentials::new("user", "super-secret-token").expect("creds");
        let debug = format!("{creds:?}");
        assert!(debug.contains("<redacted>"));
        assert!(!debug.contains("super-secret-token"));
        // Username field is redacted; struct name still contains "GitCredentials".
        assert!(
            debug.contains("username: \"<redacted>\"") || debug.contains("username: <redacted>")
        );
    }

    #[test]
    fn redaction_strips_url_userinfo_and_token_kv() {
        let raw = "failed https://alice:s3cr3t@github.com/org/repo.git token=ghp_abc123 password=x";
        let redacted = redact_diagnostic(raw);
        assert!(!redacted.contains("s3cr3t"));
        assert!(!redacted.contains("ghp_abc123"));
        assert!(redacted.contains("***@"));
        assert!(redacted.contains("token=<redacted>"));
        assert!(redacted.contains("password=<redacted>"));
    }

    #[test]
    fn endpoint_rejects_ssh_urls() {
        let dir = tempdir().expect("tmp");
        for url in [
            "git@github.com:org/repo.git",
            "ssh://git@github.com/org/repo.git",
            "alice@git.example.com:org/repo.git",
        ] {
            let err = lomo_git::GitEndpoint::parse(
                url,
                "main",
                GitLocalMode::AppPrivateBareMirror {
                    mirror_dir: dir.path().join("m"),
                },
            )
            .expect_err("ssh-shaped url must fail");
            assert_eq!(err.code(), "git_ssh_not_supported", "url={url}");
        }
    }

    #[test]
    fn endpoint_rejects_url_userinfo_and_non_https_schemes() {
        let dir = tempdir().expect("tmp");
        let local = |name: &str| GitLocalMode::AppPrivateBareMirror {
            mirror_dir: dir.path().join(name),
        };
        for url in [
            "https://alice:s3cr3t@github.com/org/repo.git",
            "https://alice@github.com/org/repo.git",
            "file://alice@host/repo.git",
        ] {
            let err = lomo_git::GitEndpoint::parse(url, "main", local("m")).expect_err("userinfo");
            assert_eq!(err.code(), "git_url_userinfo_rejected", "url={url}");
        }
        for url in [
            "http://github.com/org/repo.git",
            "ftp://github.com/org/repo.git",
            "git://github.com/org/repo.git",
        ] {
            let err = lomo_git::GitEndpoint::parse(url, "main", local("m")).expect_err("scheme");
            assert_eq!(err.code(), "git_scheme_unsupported", "url={url}");
        }
    }

    #[test]
    fn endpoint_accepts_https_and_local_hermetic_urls() {
        let dir = tempdir().expect("tmp");
        let local = |name: &str| GitLocalMode::AppPrivateBareMirror {
            mirror_dir: dir.path().join(name),
        };
        for url in [
            "https://github.com/org/repo.git",
            "file:///srv/remotes/repo.git",
            "/srv/remotes/repo.git",
        ] {
            lomo_git::GitEndpoint::parse(url, "main", local("m")).expect("valid endpoint");
        }
    }

    #[test]
    fn endpoint_rejects_invalid_branch_names() {
        let dir = tempdir().expect("tmp");
        for branch in [
            "",
            "feat/x",
            "-checkout",
            ".hidden",
            "a..b",
            "tip.lock",
            "a~b",
        ] {
            let err = lomo_git::GitEndpoint::parse(
                "https://github.com/org/repo.git",
                branch,
                GitLocalMode::AppPrivateBareMirror {
                    mirror_dir: dir.path().join("m"),
                },
            )
            .expect_err("invalid branch must fail");
            assert_eq!(err.code(), "git_branch_invalid", "branch={branch}");
        }
    }

    #[test]
    fn validate_remote_url_precheck_shares_endpoint_rule() {
        assert_eq!(
            lomo_git::validate_git_remote_url("https://a:b@h/r.git")
                .expect_err("userinfo")
                .code(),
            "git_url_userinfo_rejected"
        );
        assert_eq!(
            lomo_git::validate_git_remote_url("git@h:r.git")
                .expect_err("scp ssh")
                .code(),
            "git_ssh_not_supported"
        );
        assert_eq!(
            lomo_git::validate_git_remote_url("http://h/r.git")
                .expect_err("http")
                .code(),
            "git_scheme_unsupported"
        );
        assert_eq!(
            lomo_git::validate_git_remote_url("")
                .expect_err("empty")
                .code(),
            "git_remote_url_empty"
        );
        lomo_git::validate_git_remote_url("https://github.com/org/repo.git").expect("https ok");
        lomo_git::validate_git_remote_url("/srv/repo.git").expect("plain path ok");
    }

    #[test]
    fn per_path_batch_is_rejected() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("a.md"),
                digest: digest_of(b"x"),
                expected_remote_token: None,
            }],
        )
        .expect("batch");
        let err = adapter.publish(&batch).expect_err("atomicity");
        assert_eq!(err.code(), "git_batch_atomicity");
    }

    #[test]
    fn whole_batch_ref_publish_list_and_verify_round_trip() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let body = b"- 10:00:00\nhello from git adapter\n";
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/2024-01-02.md".to_owned(), body.to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);

        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/2024-01-02.md"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(receipt.path_results[0].1, PathPublishStatus::Applied { .. }),
            "{:?}",
            receipt.path_results[0].1
        );

        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.entries.len(), 1);
        assert_eq!(snap.entries[0].path.as_str(), "memo/2024-01-02.md");
        // Metadata-only listing: digest resolves on demand; blob OID is the validator.
        assert_eq!(snap.entries[0].digest, RemoteDigestFact::Unresolved);
        let resolved = adapter
            .resolve_remote_object(&snap.entries[0].path)
            .expect("resolve")
            .expect("present");
        assert_eq!(resolved.digest.as_str(), digest_of(body).as_str());

        let verified = adapter
            .verify(&[
                VerifyExpectation {
                    path: path("memo/2024-01-02.md"),
                    expected_digest: Some(digest_of(body)),
                    expected_token: None,
                },
                VerifyExpectation {
                    path: path("memo/missing.md"),
                    expected_digest: None,
                    expected_token: None,
                },
            ])
            .expect("verify");
        assert!(matches!(
            verified.results[0],
            lomo_sync::VerifyStatus::Verified { .. }
        ));
        assert!(matches!(
            verified.results[1],
            lomo_sync::VerifyStatus::AbsentVerified { .. }
        ));
    }

    #[test]
    fn stale_expected_token_is_precondition_failed_without_force() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let _tip = seed_remote_with_file(&bare, "memo/a.md", b"remote-a\n");

        let body = b"local-new\n";
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), body.to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);

        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(body),
                expected_remote_token: Some("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef".to_owned()),
            }],
            Some("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef".to_owned()),
        );
        let receipt = adapter.publish(&batch).expect("publish receipt");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::PreconditionFailed
            ),
            "{:?}",
            receipt.path_results[0].1
        );
    }

    #[test]
    fn non_fast_forward_push_is_rejected() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let seed_tip = seed_remote_with_file(&bare, "memo/a.md", b"v1\n");

        // First adapter publishes an update.
        let body2 = b"v2\n";
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), body2.to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(body2),
                expected_remote_token: None,
            }],
            Some(seed_tip),
        );
        let receipt = adapter.publish(&batch).expect("publish v2");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        let applied_token = match &receipt.path_results[0].1 {
            PathPublishStatus::Applied { new_token } => new_token.clone(),
            PathPublishStatus::PreconditionFailed
            | PathPublishStatus::Failed { .. }
            | PathPublishStatus::Skipped => panic!("expected applied"),
        };

        // Diverge bare remote: commit + force update via a second seed repo that uses force
        // only in the *test fixture* (not lomo-git) to place concurrent history on the bare tip.
        // Use `+` refspec here only to create an environment where adapter's non-force CAS fails.
        diverge_bare_with_force(&bare, "memo/a.md", b"concurrent\n");

        // Concurrent tip already on remote; expected token is old commit → PreconditionFailed.
        let body3 = b"v3\n";
        let mut objects3 = MapGitObjectSource::default();
        objects3
            .objects
            .insert("memo/a.md".to_owned(), body3.to_vec());
        let adapter3 = adapter_mirror(&bare, &root.path().join("mirror3.git"), objects3);
        let batch3 = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(body3),
                expected_remote_token: Some(applied_token.clone()),
            }],
            Some(applied_token),
        );
        let receipt3 = adapter3.publish(&batch3).expect("publish3");
        assert!(
            matches!(
                receipt3.path_results[0].1,
                PathPublishStatus::PreconditionFailed
            ),
            "stale commit token must not force-push: {:?}",
            receipt3.path_results[0].1
        );
    }

    /// Test-fixture only: force-updates bare remote tip to create a concurrent history.
    /// Production `lomo-git` never force-pushes.
    fn diverge_bare_with_force(bare: &Path, relative: &str, bytes: &[u8]) {
        let tmp = tempdir().expect("tmp");
        let work = tmp.path().join("diverge");
        fs::create_dir_all(&work).expect("work");
        let mut opts = RepositoryInitOptions::new();
        opts.initial_head("main");
        let repo = Repository::init_opts(&work, &opts).expect("work init");
        let sig = Signature::now("diverge", "diverge@lomo.local").expect("sig");
        let file = work.join(relative);
        if let Some(parent) = file.parent() {
            fs::create_dir_all(parent).expect("parent");
        }
        fs::write(&file, bytes).expect("write");
        let mut index = repo.index().expect("index");
        index.add_path(Path::new(relative)).expect("add");
        index.write().expect("index write");
        let tree_id = index.write_tree().expect("tree");
        let tree = repo.find_tree(tree_id).expect("find tree");
        repo.commit(Some("HEAD"), &sig, &sig, "diverge", &tree, &[])
            .expect("commit");
        let url = bare.to_str().expect("utf8 bare");
        let mut remote = repo.remote("origin", url).expect("remote");
        // Force refspec is fixture-only to simulate concurrent remote rewrite.
        remote
            .push(&["+refs/heads/main:refs/heads/main"], None)
            .expect("force push fixture");
    }

    /// Test-fixture only: appends a sibling commit on bare remote based on current tip
    /// (shared merge-base with local side). Production `lomo-git` never force-pushes.
    fn diverge_bare_sibling(bare: &Path, relative: &str, bytes: &[u8]) -> String {
        let tmp = tempdir().expect("tmp");
        let work = tmp.path().join("diverge-sib");
        fs::create_dir_all(&work).expect("work");
        let mut opts = RepositoryInitOptions::new();
        opts.initial_head("main");
        let repo = Repository::init_opts(&work, &opts).expect("work init");
        let url = bare.to_str().expect("utf8 bare");
        {
            let mut remote = repo.remote("origin", url).expect("remote");
            remote
                .fetch(&["+refs/heads/main:refs/remotes/origin/main"], None, None)
                .expect("fetch tip");
        }
        let tip = repo
            .refname_to_id("refs/remotes/origin/main")
            .expect("tracking tip");
        let parent = repo.find_commit(tip).expect("parent");
        let parent_tree = parent.tree().expect("parent tree");
        let sig = Signature::now("diverge", "diverge@lomo.local").expect("sig");
        let blob = repo.blob(bytes).expect("blob");
        let mut updater = git2::build::TreeUpdateBuilder::new();
        updater.upsert(relative, blob, git2::FileMode::Blob);
        let tree_oid = updater
            .create_updated(&repo, &parent_tree)
            .expect("sib tree");
        let tree = repo.find_tree(tree_oid).expect("tree");
        let commit = repo
            .commit(None, &sig, &sig, "remote sibling", &tree, &[&parent])
            .expect("sib commit");
        repo.reference("refs/heads/main", commit, true, "sib head")
            .expect("local ref");
        let mut remote = repo.find_remote("origin").expect("origin");
        // Non-force is fine: sibling is FF from tip.
        remote
            .push(&["refs/heads/main:refs/heads/main"], None)
            .expect("push sibling");
        commit.to_string()
    }

    #[test]
    fn stale_lock_reclaim_only_when_owner_gone_and_frozen() {
        let root = tempdir().expect("tmp");
        let git_dir = root.path().join("repo.git");
        fs::create_dir_all(&git_dir).expect("dir");
        // Dead PID: use a very large pid unlikely to exist.
        let dead_pid = 2_147_483_646_i32;
        assert!(
            !process_alive(dead_pid),
            "test requires dead pid {dead_pid}"
        );
        write_index_lock(&git_dir, dead_pid).expect("lock");
        // Age the lock by setting mtime in the past via filetime if available; otherwise
        // use zero threshold so age check passes immediately for dead owner.
        let outcome =
            try_reclaim_stale_index_lock(&git_dir, Duration::from_secs(0), SystemTime::now())
                .expect("reclaim");
        assert_eq!(outcome, LockReclaimOutcome::Reclaimed);
        assert!(!git_dir.join("index.lock").exists());

        // Live owner (this process) must not reclaim even with zero threshold.
        write_index_lock(&git_dir, std::process::id() as i32).expect("lock live");
        let held =
            try_reclaim_stale_index_lock(&git_dir, Duration::from_secs(0), SystemTime::now())
                .expect("held");
        assert_eq!(held, LockReclaimOutcome::Held);
    }

    #[test]
    fn rebuild_app_private_mirror_only_touches_mirror_path() {
        let root = tempdir().expect("tmp");
        let mirror = root.path().join("mirror.git");
        let user = root.path().join("user-workspace");
        fs::create_dir_all(&user).expect("user");
        fs::write(user.join("memo.md"), b"keep-me").expect("user file");
        init_bare(&mirror);
        fs::write(mirror.join("extra-marker"), b"gone").expect("marker");

        let mode = GitLocalMode::AppPrivateBareMirror {
            mirror_dir: mirror.clone(),
        };
        let _repo = rebuild_app_private_mirror(&mode).expect("rebuild");
        assert!(mirror.exists());
        assert!(!mirror.join("extra-marker").exists());
        assert_eq!(
            fs::read(user.join("memo.md")).expect("read user"),
            b"keep-me"
        );
    }

    #[test]
    fn object_source_digest_mismatch_fails_closed() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let mut objects = MapGitObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"bytes".to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("a.md"),
                digest: digest_of(b"other"),
                expected_remote_token: None,
            }],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::Failed { ref code }
                    if code == "git_object_source_digest_mismatch"
            ),
            "{:?}",
            receipt.path_results[0].1
        );
    }

    #[test]
    fn ensure_absent_removes_path_on_remote() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let _seed: String = seed_remote_with_file(&bare, "memo/gone.md", b"delete-me\n");
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.entries.len(), 1);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsureAbsent {
                path: path("memo/gone.md"),
                expected_remote_token: String::new(),
            }],
            snap.snapshot_revision,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(receipt.path_results[0].1, PathPublishStatus::Applied { .. }),
            "{:?}",
            receipt.path_results[0].1
        );
        let snap2 = adapter.list_remote().expect("list2");
        assert!(
            snap2.entries.is_empty(),
            "path must be removed: {:?}",
            snap2.entries
        );
    }

    /// Compile-time / API lock: force-push API surface is not exposed by lomo-git.
    #[test]
    fn force_push_and_reset_apis_are_absent_from_public_surface() {
        // Source-level architecture-style lock in-crate: public modules must not name force push.
        let lib = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"));
        let adapter = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/adapter.rs"));
        for forbidden in [
            "force_push",
            "ForcePush",
            "reset_to_remote",
            "CheckoutBuilder",
            ".force()",
        ] {
            assert!(
                !lib.contains(forbidden),
                "lib.rs must not expose {forbidden}"
            );
            // adapter may mention non-force in comments; ban only API-shaped tokens.
            if forbidden == ".force()" || forbidden == "CheckoutBuilder" {
                assert!(
                    !adapter.contains(forbidden),
                    "adapter must not use {forbidden}"
                );
            }
        }
        assert!(adapter.contains("Non-force") || adapter.contains("non-force"));
    }

    #[test]
    fn empty_remote_list_is_complete() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        let snap = adapter.list_remote().expect("list");
        assert!(matches!(
            snap.completeness,
            lomo_sync::SnapshotCompleteness::Complete
        ));
        assert!(snap.entries.is_empty());
    }

    /// Given an unrelated local HEAD in the mirror, when publish runs, then the local HEAD is
    /// ignored: the publish is a single-parent commit on the observed remote tip (merge-base
    /// between local and remote is no longer part of the publish contract).
    #[test]
    fn unrelated_local_head_does_not_block_single_parent_publish() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let seed_tip: String = seed_remote_with_file(&bare, "memo/a.md", b"remote-only\n");

        // Build app-private bare mirror, then plant an *unrelated* local HEAD commit; the
        // single-parent publish contract does not merge local HEAD, so it must be ignored.
        init_bare(&mirror);
        {
            let repo = Repository::open_bare(&mirror).expect("open mirror");
            let sig = Signature::now("local", "local@lomo.local").expect("sig");
            let blob = repo.blob(b"local-unrelated\n").expect("blob");
            let mut builder = repo.treebuilder(None).expect("builder");
            builder
                .insert("local-only.md", blob, 0o100_644)
                .expect("insert");
            let tree_id = builder.write().expect("tree");
            let tree = repo.find_tree(tree_id).expect("find tree");
            let commit = repo
                .commit(None, &sig, &sig, "unrelated local root", &tree, &[])
                .expect("commit");
            repo.reference("refs/heads/main", commit, true, "plant unrelated head")
                .expect("ref");
            // Also set HEAD to main so peel_to_commit succeeds.
            repo.set_head("refs/heads/main").expect("head");
        }

        let body = b"publish-attempt\n";
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), body.to_vec());
        let adapter = connect_map_git_source(MapGitConnectParams {
            remote_url: bare.to_str().expect("utf8"),
            branch: "main",
            local: GitLocalMode::AppPrivateBareMirror { mirror_dir: mirror },
            credentials: GitCredentials::anonymous(),
            objects,
            timeout: Duration::from_secs(5),
            author_name: "lomo-git",
            author_email: "git@lomo.local",
        })
        .expect("adapter");

        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(body),
                expected_remote_token: None,
            }],
            Some(seed_tip.clone()),
        );
        let receipt = adapter.publish(&batch).expect("publish");
        let new_token = match &receipt.path_results[0].1 {
            PathPublishStatus::Applied { new_token } => new_token.clone(),
            PathPublishStatus::PreconditionFailed
            | PathPublishStatus::Failed { .. }
            | PathPublishStatus::Skipped => {
                panic!("expected Applied, got {:?}", receipt.path_results[0].1)
            }
        };
        let bare_repo = Repository::open_bare(&bare).expect("bare");
        let tip = bare_repo
            .refname_to_id("refs/heads/main")
            .expect("bare tip");
        assert_eq!(tip.to_string(), new_token);
        let commit = bare_repo.find_commit(tip).expect("tip commit");
        assert_eq!(
            commit.parent_count(),
            1,
            "publish must be single-parent on the observed remote tip"
        );
        assert_eq!(
            commit.parent_id(0).expect("parent").to_string(),
            seed_tip,
            "sole parent must be the remote tip observed for the publish"
        );
    }

    /// Plants a local HEAD commit on the app-private bare mirror based on `base_oid` with
    /// `relative` body bytes. Returns the local commit oid.
    fn plant_local_resolved_head(
        mirror: &Path,
        base_oid: git2::Oid,
        relative: &str,
        body: &[u8],
    ) -> git2::Oid {
        let repo = Repository::open_bare(mirror).expect("open mirror");
        let base_commit = repo.find_commit(base_oid).expect("base commit");
        let base_tree = base_commit.tree().expect("base tree");
        let sig = Signature::now("local", "local@lomo.local").expect("sig");
        let blob = repo.blob(body).expect("blob");
        let mut updater = git2::build::TreeUpdateBuilder::new();
        updater.upsert(relative, blob, git2::FileMode::Blob);
        let tree_oid = updater
            .create_updated(&repo, &base_tree)
            .expect("local tree");
        let tree = repo.find_tree(tree_oid).expect("tree");
        let local_commit = repo
            .commit(
                None,
                &sig,
                &sig,
                "local side after conflict",
                &tree,
                &[&base_commit],
            )
            .expect("local commit");
        repo.reference("refs/heads/main", local_commit, true, "plant local head")
            .expect("ref");
        repo.set_head("refs/heads/main").expect("head");
        local_commit
    }

    /// Given diverged local HEAD + remote tip (`KeepLocal` resolve shape), when publish runs,
    /// then the pushed commit is single-parent on the observed remote tip and the tree carries
    /// the resolved local body. Non-force CAS still applies; local HEAD is not merged Git-side.
    #[test]
    fn single_parent_publish_after_resolve_publishes_local_body() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);

        // Common ancestor on remote.
        let base_tip = seed_remote_with_file(&bare, "memo/a.md", b"base\n");
        let base_oid = git2::Oid::from_str(&base_tip).expect("base oid");

        // Open mirror and fetch remote tip into tracking ref by listing.
        let adapter_fetch = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        let _snap = adapter_fetch.list_remote().expect("fetch via list");
        drop(adapter_fetch);

        // Plant local HEAD as a sibling of a future remote tip (based on common ancestor).
        let local_body = b"local-resolved\n";
        {
            let repo = Repository::open_bare(&mirror).expect("open mirror");
            let remote_tip = repo
                .refname_to_id("refs/remotes/origin/main")
                .expect("tracking");
            assert_eq!(remote_tip, base_oid);
        }
        let _local_oid = plant_local_resolved_head(&mirror, base_oid, "memo/a.md", local_body);

        // Concurrent remote tip: sibling with remote body (fixture force only).
        let remote_tip_str = diverge_bare_sibling(&bare, "memo/a.md", b"remote-side\n");
        let remote_tip_oid = git2::Oid::from_str(&remote_tip_str).expect("remote tip");

        // Resolve shape: publish KeepLocal body via ObjectSource.
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), local_body.to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("memo/a.md"),
                digest: digest_of(local_body),
                expected_remote_token: None,
            }],
            Some(remote_tip_str),
        );
        let receipt = adapter.publish(&batch).expect("publish merge resolve");
        let new_token = match &receipt.path_results[0].1 {
            PathPublishStatus::Applied { new_token } => new_token.clone(),
            PathPublishStatus::PreconditionFailed
            | PathPublishStatus::Failed { .. }
            | PathPublishStatus::Skipped => {
                panic!(
                    "expected Applied single-parent publish, got {:?}",
                    receipt.path_results[0].1
                )
            }
        };

        // Inspect bare remote tip: single parent = observed remote tip + resolved body.
        let bare_repo = Repository::open_bare(&bare).expect("bare");
        let tip = bare_repo
            .refname_to_id("refs/heads/main")
            .expect("bare tip");
        assert_eq!(tip.to_string(), new_token, "Applied token must be new tip");
        let commit = bare_repo.find_commit(tip).expect("tip commit");
        assert_eq!(
            commit.parent_count(),
            1,
            "resolve publish must be a single-parent commit, parents={}",
            commit.parent_count()
        );
        let p0 = commit.parent_id(0).expect("p0");
        assert_eq!(
            p0, remote_tip_oid,
            "sole parent must be the concurrent remote tip (CAS mainline)"
        );

        let tree = commit.tree().expect("tree");
        let entry = tree.get_path(Path::new("memo/a.md")).expect("path in tree");
        let blob = bare_repo.find_blob(entry.id()).expect("blob");
        assert_eq!(
            blob.content(),
            local_body,
            "single-parent tree must carry KeepLocal resolved body"
        );

        // list/verify observe local digest.
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.entries.len(), 1);
        let resolved = adapter
            .resolve_remote_object(&snap.entries[0].path)
            .expect("resolve")
            .expect("present");
        assert_eq!(resolved.digest.as_str(), digest_of(local_body).as_str());
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence {
            workspace_generation: "ab".repeat(32),
            remote_dataset_id: "ds".to_owned(),
            remote_identity_digest: "cd".repeat(32),
        }
    }

    #[test]
    fn planner_batch_with_blob_oid_intents_publishes_against_tip_cas() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let original = b"remote-a\n";
        let local_body = b"local-new\n";
        let _tip = seed_remote_with_file(&bare, "memo/a.md", original);

        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("memo/a.md".to_owned(), local_body.to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);
        let snap = adapter.list_remote().expect("list");
        assert_eq!(snap.entries.len(), 1);
        let blob_oid = snap.entries[0]
            .validator
            .strong_token()
            .expect("git listing carries blob OID validator")
            .to_owned();
        let tip = snap
            .snapshot_revision
            .clone()
            .expect("git listing carries tip");
        assert_ne!(
            blob_oid, tip,
            "path token is blob OID; snapshot CAS is branch tip"
        );

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &digest_of(original), blob_oid.clone());
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: digest_of(local_body),
            }],
            workspace_generation: None,
        };
        let batch = plan_intents_with_atomicity(
            SessionKind::Incremental,
            &local,
            &snap,
            &baseline,
            &TombstoneSet::empty(),
            RemotePublishContract::whole_batch(Some(tip.clone())),
        )
        .expect("plan");
        assert_eq!(batch.atomicity, BatchAtomicity::WholeBatchRef);
        assert_eq!(batch.expected_snapshot_token.as_deref(), Some(tip.as_str()));
        match &batch.intents[..] {
            [
                ProviderNeutralIntent::EnsurePresent {
                    expected_remote_token: Some(token),
                    ..
                },
            ] => assert_eq!(token, &blob_oid),
            other => panic!("expected EnsurePresent with blob oid, got {other:?}"),
        }

        let receipt = adapter.publish(&batch).expect("publish planner batch");
        assert!(
            matches!(receipt.path_results[0].1, PathPublishStatus::Applied { .. }),
            "blob-OID path tokens must not CAS against branch tip: {:?}",
            receipt.path_results[0].1
        );
    }

    #[test]
    fn list_remote_pages_covers_complete_tree_past_page_ceiling() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let count = MAX_ACTION_PAGE_ITEMS + 1;
        seed_remote_with_many_markdown(&bare, count);
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());

        let snap = adapter.list_remote().expect("single-shot list");
        assert!(
            matches!(
                snap.completeness,
                lomo_sync::SnapshotCompleteness::Incomplete
            ),
            "list_remote is a page, not a complete-tree budget"
        );
        assert_eq!(snap.entries.len(), MAX_ACTION_PAGE_ITEMS);

        let pages = adapter.list_remote_pages().expect("pages");
        assert!(matches!(
            pages.overall_completeness,
            lomo_sync::SnapshotCompleteness::Complete
        ));
        let total: usize = pages.pages.iter().map(Vec::len).sum();
        assert_eq!(total, count);
        assert!(
            pages.pages.len() >= 2,
            "tree larger than one page must stream"
        );
        assert!(pages.snapshot_revision.is_some());
    }

    fn seed_remote_with_many_markdown(bare: &Path, count: usize) {
        let tmp = tempdir().expect("tmp");
        let work = tmp.path().join("seed");
        fs::create_dir_all(&work).expect("work");
        let mut opts = RepositoryInitOptions::new();
        opts.initial_head("main");
        let repo = Repository::init_opts(&work, &opts).expect("work init");
        let sig = Signature::now("seed", "seed@lomo.local").expect("sig");
        let mut index = repo.index().expect("index");
        for i in 0..count {
            let relative = format!("memo/p{i:04}.md");
            let file = work.join(&relative);
            if let Some(parent) = file.parent() {
                fs::create_dir_all(parent).expect("parent");
            }
            fs::write(&file, format!("- 10:00:00\nbody-{i}\n")).expect("write");
            index.add_path(Path::new(&relative)).expect("add");
        }
        index.write().expect("index write");
        let tree_id = index.write_tree().expect("tree");
        let tree = repo.find_tree(tree_id).expect("find tree");
        repo.commit(Some("HEAD"), &sig, &sig, "seed many", &tree, &[])
            .expect("commit");
        let url = bare.to_str().expect("utf8 bare").to_owned();
        let mut remote = repo.remote("origin", &url).expect("remote");
        remote
            .push(&["refs/heads/main:refs/heads/main"], None)
            .expect("push seed");
    }

    /// Given one adapter cycle, when list/verify/resolve run, then a single fetch serves all
    /// calls, the pinned tip is shared, and listing walks trees without hashing blob bodies.
    #[test]
    fn cycle_reuses_one_fetch_and_listing_stays_metadata_only() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let _tip = seed_remote_with_file(&bare, "memo/a.md", b"remote-a\n");
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());

        let snap = adapter.list_remote().expect("list");
        let _verified = adapter
            .verify(&[VerifyExpectation {
                path: path("memo/a.md"),
                expected_digest: Some(digest_of(b"remote-a\n")),
                expected_token: None,
            }])
            .expect("verify");
        let _pages = adapter.list_remote_pages().expect("pages");

        let stats = adapter.stats();
        assert_eq!(
            stats.fetches, 1,
            "one cycle must fetch once, not per port call: {stats:?}"
        );
        assert_eq!(
            stats.object_bytes, 0,
            "list/verify must not stream workspace objects: {stats:?}"
        );
        assert!(snap.snapshot_revision.is_some());
    }

    /// Given an object larger than the per-object budget, when publish runs, then the intent
    /// fails with `git_object_exceeds_budget` before a single byte is read into the ODB.
    #[test]
    fn per_object_budget_rejects_before_streaming() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let mut objects = MapGitObjectSource::default();
        objects
            .objects
            .insert("big.md".to_owned(), b"0123456789".to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects).with_object_budget(4);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("big.md"),
                digest: digest_of(b"0123456789"),
                expected_remote_token: None,
            }],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::Failed { ref code }
                    if code == "git_object_exceeds_budget"
            ),
            "{:?}",
            receipt.path_results[0].1
        );
        assert_eq!(
            adapter.stats().object_bytes,
            0,
            "over-budget object must reject before streaming bytes"
        );
    }

    /// Given intents whose combined bytes exceed the batch budget, when publish runs, then the
    /// overflow intent fails with `git_batch_object_budget_exceeded`.
    #[test]
    fn batch_object_budget_rejects_across_intents() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let mut objects = MapGitObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"aaa".to_vec());
        objects.objects.insert("b.md".to_owned(), b"bbb".to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects).with_batch_object_budget(4);
        let batch = whole_batch(
            vec![
                ProviderNeutralIntent::EnsurePresent {
                    path: path("a.md"),
                    digest: digest_of(b"aaa"),
                    expected_remote_token: None,
                },
                ProviderNeutralIntent::EnsurePresent {
                    path: path("b.md"),
                    digest: digest_of(b"bbb"),
                    expected_remote_token: None,
                },
            ],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::Failed { ref code }
                    if code == "git_batch_object_budget_exceeded"
            ),
            "{:?}",
            receipt.path_results[0].1
        );
    }

    /// Given a mirror whose disk usage would exceed the disk budget, when publish streams, then
    /// the intent fails with `git_mirror_disk_budget_exceeded`.
    #[test]
    fn mirror_disk_budget_rejects_object_writes() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let mut objects = MapGitObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"aaa".to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects).with_mirror_disk_budget(1);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("a.md"),
                digest: digest_of(b"aaa"),
                expected_remote_token: None,
            }],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(
            matches!(
                receipt.path_results[0].1,
                PathPublishStatus::Failed { ref code }
                    if code == "git_mirror_disk_budget_exceeded"
            ),
            "{:?}",
            receipt.path_results[0].1
        );
    }

    /// Given a crashed publish that left `refs/lomo/push/*` staging refs in the mirror, when a
    /// new adapter connects, then startup reconciliation reclaims every leftover.
    #[test]
    fn push_staging_refs_are_reclaimed_at_connect() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        init_bare(&mirror);
        {
            let repo = Repository::open_bare(&mirror).expect("open mirror");
            let blob = repo.blob(b"orphan").expect("blob");
            repo.reference("refs/lomo/push/dead", blob, true, "plant orphan")
                .expect("plant ref");
            repo.reference("refs/lomo/push/beef", blob, true, "plant orphan")
                .expect("plant ref");
        }
        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        drop(adapter);
        let repo = Repository::open_bare(&mirror).expect("reopen");
        let leftover = repo
            .references_glob("refs/lomo/push/*")
            .expect("glob")
            .count();
        assert_eq!(leftover, 0, "staging refs must be reclaimed at connect");
    }

    /// Given a successful publish, when the push finishes, then no `refs/lomo/push/*` staging
    /// refs remain in the mirror.
    #[test]
    fn successful_publish_leaves_no_staging_refs() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let mut objects = MapGitObjectSource::default();
        objects.objects.insert("a.md".to_owned(), b"body".to_vec());
        let adapter = adapter_mirror(&bare, &mirror, objects);
        let batch = whole_batch(
            vec![ProviderNeutralIntent::EnsurePresent {
                path: path("a.md"),
                digest: digest_of(b"body"),
                expected_remote_token: None,
            }],
            None,
        );
        let receipt = adapter.publish(&batch).expect("publish");
        assert!(matches!(
            receipt.path_results[0].1,
            PathPublishStatus::Applied { .. }
        ));
        let repo = Repository::open_bare(&mirror).expect("reopen");
        let leftover = repo
            .references_glob("refs/lomo/push/*")
            .expect("glob")
            .count();
        assert_eq!(leftover, 0, "staging lease must be cleaned after push");
    }

    /// Given a half-initialized (corrupt) app-private mirror dir, when the adapter connects,
    /// then the tree is quarantined intact under `*.corrupt-*` and a valid bare repo is rebuilt.
    #[test]
    fn corrupt_mirror_is_quarantined_and_rebuilt_once() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        fs::create_dir_all(&mirror).expect("mirror dir");
        fs::write(mirror.join("junk-marker"), b"not a repo").expect("junk");

        let adapter = adapter_mirror(&bare, &mirror, MapGitObjectSource::default());
        drop(adapter);

        let rebuilt = Repository::open_bare(&mirror).expect("mirror must be rebuilt valid");
        assert!(rebuilt.is_bare());
        let quarantined: Vec<_> = fs::read_dir(root.path())
            .expect("list root")
            .flatten()
            .filter(|entry| entry.file_name().to_string_lossy().contains(".corrupt-"))
            .collect();
        assert_eq!(
            quarantined.len(),
            1,
            "corrupt mirror must be quarantined exactly once"
        );
        assert_eq!(
            fs::read(quarantined[0].path().join("junk-marker")).expect("evidence"),
            b"not a repo",
            "quarantine must preserve the original tree"
        );
    }

    /// Given a zero network timeout, when a remote operation runs, then the deadline is
    /// observed and fails the call with `git_deadline_exceeded`.
    #[test]
    fn zero_timeout_fails_remote_operation() {
        let root = tempdir().expect("tmp");
        let bare = root.path().join("remote.git");
        let mirror = root.path().join("mirror.git");
        init_bare(&bare);
        let adapter = connect_map_git_source(MapGitConnectParams {
            remote_url: bare.to_str().expect("utf8"),
            branch: "main",
            local: GitLocalMode::AppPrivateBareMirror { mirror_dir: mirror },
            credentials: GitCredentials::anonymous(),
            objects: MapGitObjectSource::default(),
            timeout: Duration::ZERO,
            author_name: "lomo-git",
            author_email: "git@lomo.local",
        })
        .expect("adapter");
        let err = adapter.list_remote().expect_err("deadline must fail");
        assert_eq!(err.code(), "git_deadline_exceeded");
    }
}
