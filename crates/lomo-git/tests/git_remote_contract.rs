// adversarial-audit: live-tip CAS must reject malformed/empty snapshot tokens;
//                    corrupt-mirror recovery semantics on repeated corruption
//
// Probes:
// 1. `GitAdapter::publish` CAS match treats `Some("")` as a malformed anchor, never "no
//   expectation": the empty-token arm fails closed as PreconditionFailed before any publish.
//   The planner never emits `Some("")` today (snapshot_revision is `Some(oid)` or `None`), so
//   this was a fail-open edge on malformed input — now fail-closed.
// 2. `open_or_recover_bare_mirror` quarantines to `{mirror}.corrupt-{epoch_ms}[-{n}]` and
//   rebuilds once per *call*. Repeated corruption still recovers per event and keeps evidence
//   per event, but retained `.corrupt-*` trees are now bounded to the newest few
//   (`MAX_MIRROR_QUARANTINE_DIRS`) so a repeatedly-corrupting mirror cannot grow without bound.
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial probes fail closed on missing facts"
)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::time::Duration;

    use git2::{Repository, RepositoryInitOptions, Signature};
    use lomo_git::{
        GitCredentials, GitLocalMode, MapGitConnectParams, MapGitObjectSource,
        connect_map_git_source,
    };
    use lomo_sync::{
        BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, RemoteSyncPort, SyncPath,
    };
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::parse(&format!("{:x}", Sha256::digest(bytes))).expect("digest")
    }

    fn init_bare(path: &Path) {
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(path, &opts).expect("init bare");
    }

    fn seed_remote_with_file(bare: &Path, relative: &str, bytes: &[u8]) {
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
        let tree = repo
            .find_tree(index.write_tree().expect("tree"))
            .expect("tree");
        repo.commit(Some("HEAD"), &sig, &sig, "seed", &tree, &[])
            .expect("commit");
        let mut remote = repo
            .remote("origin", bare.to_str().expect("utf8"))
            .expect("remote");
        remote
            .push(&["refs/heads/main:refs/heads/main"], None)
            .expect("push seed");
    }

    fn adapter(
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

    /// An empty-string snapshot CAS token must not silently satisfy the live-tip guard.
    /// Remote has a real tip; `Some("")` is a malformed expectation and must fail closed.
    #[test]
    fn empty_snapshot_token_must_not_bypass_live_tip_cas() {
        let tmp = tempdir().expect("tmp");
        let bare = tmp.path().join("remote.git");
        init_bare(&bare);
        seed_remote_with_file(&bare, "memo/existing.md", b"remote body");
        let mirror = tmp.path().join("mirror");
        let mut objects = MapGitObjectSource::default();
        let body = b"local overwrite attempt".to_vec();
        objects
            .objects
            .insert("memo/existing.md".to_owned(), body.clone());
        let adapter = adapter(&bare, &mirror, objects);

        let batch = PreparedRemoteBatch::with_snapshot_token(
            BatchAtomicity::WholeBatchRef,
            vec![ProviderNeutralIntent::EnsurePresent {
                path: SyncPath::parse("memo/existing.md").expect("path"),
                digest: digest_of(&body),
                expected_remote_token: None,
            }],
            Some(String::new()), // malformed empty CAS anchor
        )
        .expect("batch");

        let receipt = adapter.publish(&batch).expect("publish");
        let status = &receipt.path_results.first().expect("row").1;
        assert!(
            matches!(
                status,
                PathPublishStatus::PreconditionFailed | PathPublishStatus::Failed { .. }
            ),
            "empty snapshot token bypassed the live-tip CAS and published ({status:?})"
        );
    }

    /// Repeated corruption: each open quarantines + rebuilds again; evidence dirs accumulate
    /// but stay bounded (`MAX_MIRROR_QUARANTINE_DIRS` newest trees retained).
    #[test]
    fn second_corruption_quarantines_and_rebuilds_again() {
        let tmp = tempdir().expect("tmp");
        let bare = tmp.path().join("remote.git");
        init_bare(&bare);
        let mirror = tmp.path().join("mirror");

        // First corruption + recovery (a dir that is not a repo at all).
        fs::create_dir_all(&mirror).expect("mirror");
        fs::write(mirror.join("HEAD"), "garbage-not-a-ref").expect("poison");
        let objects = MapGitObjectSource::default();
        let _a1 = adapter(&bare, &mirror, objects);
        assert!(Repository::open_bare(&mirror).is_ok(), "mirror rebuilt");

        // Second corruption on the rebuilt mirror — remove `objects/` so open_bare fails again.
        fs::remove_dir_all(mirror.join("objects")).expect("poison 2");
        let objects2 = MapGitObjectSource::default();
        let _a2 = adapter(&bare, &mirror, objects2);
        assert!(
            Repository::open_bare(&mirror).is_ok(),
            "second corruption recovered again"
        );

        let quarantines: Vec<PathBuf> = fs::read_dir(tmp.path())
            .expect("read dir")
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with("mirror.corrupt-"))
            })
            .collect();
        // Semantics documented: one quarantine per corruption event, bounded to the newest
        // `MAX_MIRROR_QUARANTINE_DIRS` evidence trees — two events keep both.
        assert!(
            quarantines.len() >= 2,
            "expected accumulated corrupt-mirror evidence dirs, found {quarantines:?}"
        );
    }
}
