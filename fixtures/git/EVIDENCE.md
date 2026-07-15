# Git feasibility evidence (P0-09)

**Stage-0 status for this workstream: partial (host).**  
See `fixtures/baseline/STAGE0-STATUS.md` (authoritative).

## Proven on host (vendored `git2` / libgit2)

| Capability | Status | How |
| --- | --- | --- |
| Open / init local worktree | **pass** | `run_local_git_probe` |
| Commit history (≥2) | **pass** | two sequential commits on `main` |
| Diverged branch | **pass** | `feature` rewrite vs `main` rewrite |
| Push to bare remote | **pass** | local filesystem bare via libgit2 push |
| Fetch from bare remote | **pass** | `refs/heads/main:refs/remotes/origin/main` |
| Rebase conflict observation | **pass** | divergent rewrite → conflict → abort |
| HTTPS smart-HTTP push/fetch | **pass (host)** | `run_smart_http_git_probe` + `git-http-backend` over TLS |
| Username/token credential callback | **pass (host)** | Basic auth via `Cred::userpass_plaintext` |
| Certificate pin to fixture leaf DER | **pass (host)** | `certificate_check` compares X.509 DER; mismatch rejects |
| Certificate rejection without pin | **pass (host)** | untrusted clone fails closed |
| Non-force push rejection | **pass (host)** | divergent non-ff push rejected |
| Index lock fail + recover after unlock | **pass (host)** | `.git/index.lock` present → commit fails; remove → succeed |
| Transfer-progress cancel | **pass only if cancel aborts** | `probe_transfer_cancel`: progress must request cancel **and** clone/fetch must error; no unconditional success |
| License / advisory gate | **pass when CI green** | `cargo-deny` |

Contract tests: `rust/feasibility/tests/git_probe_contract.rs`.

## Packaging / follow-on

| Capability | Status | Rationale |
| --- | --- | --- |
| Four-ABI **linked** feasibility graph | **partial** | Requires live `candidate_link_markers` (git2+reqwest) in device bundle; re-record sizes after rebuild |
| Device/emulator smart-HTTP push in production APK | **stage-4 ownership** | not production-packaged |

## Decision (candidate selection)

- **Select** vendored `git2 0.21.x` + vendored libgit2/OpenSSL as the intended Git candidate.
- **Reject** long-term JGit dual-stack and pure-Rust gitoxide/`gix` as the primary production path (push/rebase incomplete in gix).
- Host smart-HTTP matrix is **partial** until cancel evidence is re-certified green under the fail-closed cancel probe.
- Production adoption remains ownership-gated.
