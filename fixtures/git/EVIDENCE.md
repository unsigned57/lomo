# Git feasibility evidence (P0-09)

## Proven on host (vendored `git2` / libgit2)

| Capability | Status | How |
| --- | --- | --- |
| Open / init local worktree | **pass** | `run_local_git_probe` |
| Commit history (≥2) | **pass** | two sequential commits on `main` |
| Diverged branch | **pass** | `feature` rewrite vs `main` rewrite |
| Push to bare remote | **pass** | local filesystem bare (`remote.git`) via libgit2 push |
| Fetch from bare remote | **pass** | `refs/heads/main:refs/remotes/origin/main` |
| Rebase conflict observation | **pass** | divergent rewrite → conflict → abort |
| Four-ABI compile of vendored libgit2 | **pass** | cargo-ndk via `lomo-xtask` native packaging |
| License / advisory gate | **pass** | workspace `cargo-deny` (`deny.toml`) |

Contract test: `rust/feasibility/tests/git_probe_contract.rs`.

## Explicit evidence boundary (not claimed in stage 0)

| Capability | Status | Rationale |
| --- | --- | --- |
| HTTPS smart-HTTP push/fetch | **deferred** | Hermetic smart-HTTP+TLS fixture is not yet in the evidence graph. Local bare transport already exercises libgit2 push/fetch/rebase state machines without public network. Stage-4 Git work must add smart-HTTP over the same `git2` stack before production ownership. |
| Username/token credential callback | **deferred** | Requires smart-HTTP or authenticated remote; classification remains a stage-4 hard gate. |
| Certificate rejection classification | **partial** | Proven for HTTP probe (reqwest/Rustls fixture); not yet for git2 TLS path. |
| Non-force push rejection | **partial** | Push path exercised; force/non-force matrix is stage-4. |
| Process-interrupt / lock recovery | **deferred** | Crash-recovery of `.git/index.lock` is stage-4 with production journal. |
| Device-smoke real push/rebase | **deferred** | API 26 emulator path reuses host-proven libgit2; optional device git smoke when emulator budget allows. |

## Decision for stage 0 exit

- **Accept** vendored `git2 0.21.x` + vendored libgit2/OpenSSL as the Git candidate.
- **Reject** long-term JGit dual-stack and pure-Rust gitoxide/`gix` as production Git (push/rebase incomplete for product needs).
- **Do not block** stage 0 on smart-HTTP fixture; document it as a **stage-4 entry precondition** with the same dependency, not a new library search.
