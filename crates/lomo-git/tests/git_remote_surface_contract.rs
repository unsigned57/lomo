// adversarial re-audit (round 2) of the 05-F2 git fixes (B06–B08): variants the first pass
// did not reach — the residual SSH/SCP/scheme surface (aliased schemes, case, empty-path
// SCP, generic and percent-encoded userinfo, whitespace-wrapped URLs), the whitespace CAS
// twin of the empty-token probe, and the *upper bound* of corrupt-mirror quarantine
// evidence (the existing probe proves accumulation, not boundedness).
// Any RED is a live regression, not an expectation edit.
//
//! Probes:
//! - `validate_git_remote_url`/`GitEndpoint::parse` fail closed on every scheme or authority
//!   shape that is not exactly https/file or a plain local path: `git+ssh://`, `SSH://`,
//!   `HTTPS://`, `git@host` bare, `host:`/`user@host:` empty-path SCP, generic and
//!   percent-encoded userinfo, and a whitespace-wrapped ssh:// URL.
//! - `GitAdapter::publish` treats a whitespace-only snapshot CAS token as malformed —
//!   `PreconditionFailed` before any tree work, never "no expectation".
//! - Repeated mirror corruption keeps at most `MAX_MIRROR_QUARANTINE_DIRS` (4) evidence
//!   trees: after more corruption events than the cap, old quarantine dirs are pruned and
//!   the mirror still rebuilds.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::time::Duration;

    use git2::{Repository, RepositoryInitOptions, Signature};
    use lomo_git::{
        GitCredentials, GitEndpoint, GitLocalMode, MapGitConnectParams, MapGitObjectSource,
        connect_map_git_source, validate_git_remote_url,
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

    fn endpoint_parse(url: &str) -> Result<GitEndpoint, lomo_core::LomoError> {
        GitEndpoint::parse(
            url,
            "main",
            GitLocalMode::AppPrivateBareMirror {
                mirror_dir: PathBuf::from("/nonexistent-mirror"),
            },
        )
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

    /// B06 — every residual endpoint form the first pass did not enumerate must fail closed
    /// on the same boundary: scheme aliases, case variants, empty-path SCP and *any* userinfo
    /// (not only `git@`). The table pins (url → code); a regression flips a row.
    #[test]
    fn remaining_ssh_scp_and_scheme_forms_all_fail_closed() {
        let rejected: &[(&str, &str)] = &[
            // Scheme aliases and case variants — https/file only, compared exactly.
            (
                "git+ssh://example.com/org/repo.git",
                "git_scheme_unsupported",
            ),
            ("ssh://example.com/org/repo.git", "git_ssh_not_supported"),
            ("SSH://example.com/org/repo.git", "git_scheme_unsupported"),
            ("HTTPS://example.com/org/repo.git", "git_scheme_unsupported"),
            ("ftp://example.com/org/repo.git", "git_scheme_unsupported"),
            // Userinfo in any form — including non-`git` users and percent-encoded payloads.
            (
                "https://alice@example.com/org/repo.git",
                "git_url_userinfo_rejected",
            ),
            (
                "https://alice:p%40ss%2Fword@example.com/org/repo.git",
                "git_url_userinfo_rejected",
            ),
            // SCP family beyond the covered trio: bare `git@host`, empty-path `host:` /
            // `user@host:`, and a whitespace-wrapped ssh:// (trimmed before checks).
            ("git@example.com", "git_ssh_not_supported"),
            ("example.com:", "git_ssh_not_supported"),
            ("alice@example.com:", "git_ssh_not_supported"),
            (
                "  ssh://example.com/org/repo.git  ",
                "git_ssh_not_supported",
            ),
            // A Windows-style `C:` drive path is still `authority:path` — rejected as SCP,
            // matching the Android-only product surface.
            ("C:\\workspace\\repo", "git_ssh_not_supported"),
        ];
        for (url, expected_code) in rejected {
            let Err(err) = validate_git_remote_url(url) else {
                panic!("{url:?} passed url validation")
            };
            assert_eq!(
                err.code(),
                *expected_code,
                "{url:?} rejected with the wrong failure class"
            );
            let Err(err) = endpoint_parse(url) else {
                panic!("{url:?} passed GitEndpoint::parse")
            };
            assert_eq!(err.code(), *expected_code, "{url:?} parse mismatch");
        }

        // Controls that must keep working: https, file, and a plain absolute path.
        validate_git_remote_url("https://example.com/org/repo.git").expect("https ok");
        validate_git_remote_url("file:///srv/git/remote.git").expect("file scheme ok");
        validate_git_remote_url("/srv/git/remote.git").expect("plain path ok");
    }

    /// B07 twin — a whitespace-only snapshot CAS token is the same malformed anchor as the
    /// empty string: `Some("   ")` must not satisfy the live-tip comparison, and publish must
    /// fail closed before touching the remote.
    #[test]
    fn whitespace_snapshot_token_fails_closed_on_git_publish() {
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
            Some("   ".to_owned()),
        )
        .expect("batch");

        let receipt = adapter.publish(&batch).expect("publish");
        let (_, status) = receipt.path_results.first().expect("row");
        assert!(
            matches!(
                status,
                PathPublishStatus::PreconditionFailed | PathPublishStatus::Failed { .. }
            ),
            "a whitespace CAS anchor bypassed the live-tip guard and published ({status:?})"
        );
    }

    /// B07 — the *bounded* half of the quarantine claim: after more corruption events than
    /// the cap, retained evidence trees stay at or under `MAX_MIRROR_QUARANTINE_DIRS` while
    /// every event still recovers the mirror.
    #[test]
    fn repeated_corruption_never_exceeds_the_quarantine_cap() {
        const MAX_MIRROR_QUARANTINE_DIRS: usize = 4;
        let tmp = tempdir().expect("tmp");
        let bare = tmp.path().join("remote.git");
        init_bare(&bare);
        let mirror = tmp.path().join("mirror");

        // More corruption events than the cap: each open must still quarantine + rebuild,
        // and the retained evidence set must never exceed the bound.
        for round in 0..(MAX_MIRROR_QUARANTINE_DIRS + 3) {
            if mirror.exists() {
                fs::remove_dir_all(mirror.join("objects")).expect("poison objects");
            } else {
                fs::create_dir_all(&mirror).expect("mirror dir");
                fs::write(mirror.join("HEAD"), "garbage-not-a-ref").expect("poison");
            }
            let _adapter = adapter(&bare, &mirror, MapGitObjectSource::default());
            assert!(
                Repository::open_bare(&mirror).is_ok(),
                "round {round}: corrupt mirror must rebuild"
            );
        }

        let quarantines: Vec<PathBuf> = fs::read_dir(tmp.path())
            .expect("read dir")
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with("mirror.corrupt-"))
            })
            .collect();
        assert!(
            !quarantines.is_empty(),
            "quarantine evidence must survive the newest corruptions"
        );
        assert!(
            quarantines.len() <= MAX_MIRROR_QUARANTINE_DIRS,
            "retained corrupt-mirror evidence exceeded the cap: {quarantines:?}"
        );
    }
}
