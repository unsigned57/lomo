//! Vendored `libgit2` feasibility for local commit/history and bare-remote push/fetch/rebase.
//!
//! Stage-0 evidence uses a **local bare** remote (filesystem transport). HTTPS smart-HTTP is an
//! explicit stage-4 entry precondition on the same `git2` stack; see `fixtures/git/EVIDENCE.md`.

use std::fs;
use std::path::Path;

use git2::{
    BranchType, RebaseOptions, Repository, RepositoryInitOptions, Signature, build::CheckoutBuilder,
};
use thiserror::Error;

/// Git probe failures.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum GitProbeError {
    #[error("git error: {detail}")]
    Git { detail: String },
    #[error("I/O failure: {detail}")]
    Io { detail: String },
    #[error("unexpected repository state: {detail}")]
    Unexpected { detail: String },
    #[error("push rejected: {detail}")]
    PushRejected { detail: String },
    #[error("rebase conflict: {detail}")]
    RebaseConflict { detail: String },
}

/// Flags proven by the local bare-remote path.
///
/// Each field is an independent evidence checklist bit, not a multi-state machine.
#[derive(Clone, Debug, Eq, PartialEq)]
#[expect(
    clippy::struct_excessive_bools,
    reason = "stage-0 probe report is a flat checklist of independent evidence flags"
)]
pub struct GitProbeRemoteFlags {
    pub pushed_to_bare: bool,
    pub fetched_from_bare: bool,
    pub rebase_conflict_observed: bool,
    pub has_diverged_branch: bool,
}

/// Observable local-git feasibility result.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GitProbeReport {
    pub commit_count: usize,
    pub head_message: String,
    pub remote: GitProbeRemoteFlags,
}

/// Initialize repos, push to a bare remote, fetch, and observe a rebase conflict.
///
/// # Errors
///
/// Returns [`GitProbeError`] when libgit2 operations fail.
pub fn run_local_git_probe(root: &Path) -> Result<GitProbeReport, GitProbeError> {
    prepare_root(root)?;
    let work = root.join("work");
    let bare = root.join("remote.git");
    fs::create_dir_all(&work).map_err(io_err)?;

    init_bare(&bare)?;
    let repository = init_worktree(&work)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    seed_history(&repository, &signature)?;
    push_main_to_bare(&repository, &bare)?;
    create_diverged_feature(&repository, &signature)?;
    fetch_origin_main(&repository)?;
    let rebase_conflict_observed = rebase_feature_onto_main(&repository, &signature)?;
    summarize(&repository, rebase_conflict_observed)
}

fn prepare_root(root: &Path) -> Result<(), GitProbeError> {
    if root.exists() {
        fs::remove_dir_all(root).map_err(io_err)?;
    }
    fs::create_dir_all(root).map_err(io_err)
}

fn init_bare(bare: &Path) -> Result<(), GitProbeError> {
    let mut bare_opts = RepositoryInitOptions::new();
    bare_opts.bare(true);
    bare_opts.initial_head("main");
    Repository::init_opts(bare, &bare_opts).map_err(git_err)?;
    Ok(())
}

fn init_worktree(work: &Path) -> Result<Repository, GitProbeError> {
    let mut options = RepositoryInitOptions::new();
    options.initial_head("main");
    Repository::init_opts(work, &options).map_err(git_err)
}

fn seed_history(repository: &Repository, signature: &Signature<'_>) -> Result<(), GitProbeError> {
    write_and_commit(
        repository,
        signature,
        "memo/2024-01-02.md",
        b"- 10:00:00\nfirst\n",
        "initial memo",
    )?;
    write_and_commit(
        repository,
        signature,
        "memo/2024-01-02.md",
        b"- 10:00:00\nfirst\n- 11:00:00\nsecond\n",
        "append memo",
    )
}

fn push_main_to_bare(repository: &Repository, bare: &Path) -> Result<(), GitProbeError> {
    let url = bare.to_str().ok_or_else(|| GitProbeError::Unexpected {
        detail: "bare path is not UTF-8".to_owned(),
    })?;
    let mut remote = repository.remote("origin", url).map_err(git_err)?;
    remote
        .push(&["refs/heads/main:refs/heads/main"], None)
        .map_err(|error| GitProbeError::PushRejected {
            detail: error.to_string(),
        })
}

fn create_diverged_feature(
    repository: &Repository,
    signature: &Signature<'_>,
) -> Result<(), GitProbeError> {
    let main = repository
        .find_branch("main", BranchType::Local)
        .map_err(git_err)?;
    let main_commit = main.get().peel_to_commit().map_err(git_err)?;
    repository
        .branch("feature", &main_commit, true)
        .map_err(git_err)?;
    checkout_branch(repository, "refs/heads/feature")?;
    write_and_commit(
        repository,
        signature,
        "memo/2024-01-02.md",
        b"- 10:00:00\nfeature-side\n",
        "feature rewrite",
    )?;
    checkout_branch(repository, "refs/heads/main")?;
    write_and_commit(
        repository,
        signature,
        "memo/2024-01-02.md",
        b"- 10:00:00\nmain-side\n",
        "main rewrite",
    )
}

fn fetch_origin_main(repository: &Repository) -> Result<(), GitProbeError> {
    let mut remote = repository.find_remote("origin").map_err(git_err)?;
    remote
        .fetch(&["refs/heads/main:refs/remotes/origin/main"], None, None)
        .map_err(git_err)
}

fn checkout_branch(repository: &Repository, head: &str) -> Result<(), GitProbeError> {
    repository.set_head(head).map_err(git_err)?;
    repository
        .checkout_head(Some(CheckoutBuilder::default().force()))
        .map_err(git_err)
}

fn summarize(
    repository: &Repository,
    rebase_conflict_observed: bool,
) -> Result<GitProbeReport, GitProbeError> {
    let mut revwalk = repository.revwalk().map_err(git_err)?;
    revwalk.push_head().map_err(git_err)?;
    let commit_count = revwalk.count();
    let head = repository.head().map_err(git_err)?;
    let head_commit = head.peel_to_commit().map_err(git_err)?;
    let head_message = head_commit
        .summary()
        .map_err(git_err)?
        .unwrap_or("")
        .to_owned();
    let has_diverged_branch = repository.find_branch("feature", BranchType::Local).is_ok();

    if commit_count < 2 {
        return Err(GitProbeError::Unexpected {
            detail: format!("commit_count={commit_count}"),
        });
    }
    if !has_diverged_branch {
        return Err(GitProbeError::Unexpected {
            detail: "feature branch missing".to_owned(),
        });
    }
    if !rebase_conflict_observed {
        return Err(GitProbeError::Unexpected {
            detail: "expected rebase conflict was not observed".to_owned(),
        });
    }

    Ok(GitProbeReport {
        commit_count,
        head_message,
        remote: GitProbeRemoteFlags {
            pushed_to_bare: true,
            fetched_from_bare: true,
            rebase_conflict_observed,
            has_diverged_branch,
        },
    })
}

fn rebase_feature_onto_main(
    repository: &Repository,
    signature: &Signature<'_>,
) -> Result<bool, GitProbeError> {
    let feature = repository
        .find_branch("feature", BranchType::Local)
        .map_err(git_err)?;
    let feature_commit = feature.get().peel_to_commit().map_err(git_err)?;
    let main = repository
        .find_branch("main", BranchType::Local)
        .map_err(git_err)?;
    let main_commit = main.get().peel_to_commit().map_err(git_err)?;
    let annotated_feature = repository
        .find_annotated_commit(feature_commit.id())
        .map_err(git_err)?;
    let annotated_main = repository
        .find_annotated_commit(main_commit.id())
        .map_err(git_err)?;

    checkout_branch(repository, "refs/heads/feature")?;

    let mut rebase = repository
        .rebase(
            Some(&annotated_feature),
            Some(&annotated_main),
            None,
            Some(&mut RebaseOptions::new()),
        )
        .map_err(git_err)?;

    let mut saw_conflict = false;
    loop {
        match rebase.next() {
            Some(Ok(_operation)) => {
                let index = repository.index().map_err(git_err)?;
                if index.has_conflicts() {
                    saw_conflict = true;
                    rebase.abort().map_err(git_err)?;
                    break;
                }
                rebase.commit(None, signature, None).map_err(git_err)?;
            }
            Some(Err(error)) => {
                let detail = error.to_string();
                if detail.to_ascii_lowercase().contains("conflict") {
                    saw_conflict = true;
                    rebase.abort().map_err(git_err)?;
                    break;
                }
                return Err(git_err(error));
            }
            None => {
                rebase.finish(Some(signature)).map_err(git_err)?;
                break;
            }
        }
    }
    if !saw_conflict {
        return Err(GitProbeError::RebaseConflict {
            detail: "rebase completed without conflict; expected divergent rewrite conflict"
                .to_owned(),
        });
    }
    Ok(true)
}

fn write_and_commit(
    repository: &Repository,
    signature: &Signature<'_>,
    relative: &str,
    bytes: &[u8],
    message: &str,
) -> Result<(), GitProbeError> {
    let path = repository
        .workdir()
        .ok_or_else(|| GitProbeError::Unexpected {
            detail: "bare repository".to_owned(),
        })?
        .join(relative);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(io_err)?;
    }
    fs::write(&path, bytes).map_err(io_err)?;
    let mut index = repository.index().map_err(git_err)?;
    index.add_path(Path::new(relative)).map_err(git_err)?;
    index.write().map_err(git_err)?;
    let tree_id = index.write_tree().map_err(git_err)?;
    let tree = repository.find_tree(tree_id).map_err(git_err)?;
    let parents = match repository.head() {
        Ok(head) => {
            let commit = head.peel_to_commit().map_err(git_err)?;
            vec![commit]
        }
        Err(_) => Vec::new(),
    };
    let parent_refs: Vec<&git2::Commit<'_>> = parents.iter().collect();
    repository
        .commit(
            Some("HEAD"),
            signature,
            signature,
            message,
            &tree,
            &parent_refs,
        )
        .map_err(git_err)?;
    Ok(())
}

fn git_err(error: impl std::fmt::Display) -> GitProbeError {
    GitProbeError::Git {
        detail: error.to_string(),
    }
}

fn io_err(error: impl std::fmt::Display) -> GitProbeError {
    GitProbeError::Io {
        detail: error.to_string(),
    }
}
