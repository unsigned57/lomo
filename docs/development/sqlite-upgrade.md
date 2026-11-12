# SQLite dependency decision

On 2026-11-09, the dependency gate rejected `libsqlite3-sys 0.38.1` under
[RUSTSEC-2026-0459](https://raw.githubusercontent.com/RustSec/advisory-db/main/crates/libsqlite3-sys/RUSTSEC-2026-0459.md).
The user authorized upgrading the affected chain after the failed audit.

Requirement: use a patched bundled SQLite while retaining the current Rust database API,
workspace feature selection, platform independence and the existing projection owner.

Candidates checked against upstream releases, manifests and crates.io metadata:

| Candidate | Decision |
| --- | --- |
| rusqlite 0.40.1 / 0.40.2 | Reject: their libsqlite3-sys line remains affected. |
| rusqlite 0.41.0 | Patched SQLite line, superseded by the following patch release. |
| [rusqlite 0.41.1](https://github.com/rusqlite/rusqlite/releases/tag/v0.41.1) | Select: includes the zero-argument deterministic scalar function fix; resolves libsqlite3-sys 0.39.1 with SQLite 3.54.1. |

The declared minimum compiler versions (Rust 1.89 for rusqlite, 1.88 for libsqlite3-sys)
fit the repository's Rust 1.98 toolchain. Keep `default-features = false`, `bundled` and `backup`;
module-local features remain owned by their manifests. The capability catalogue is unchanged.
No database migration, driver replacement or additional architectural exception is introduced.

RED evidence: `just deps check` failed with RUSTSEC-2026-0459 for libsqlite3-sys 0.38.1.
Validation uses the dependency gate, existing store/application tests, the full worktree check
and regenerated native packaging inputs. Command results live in ignored `reports/repository-maintenance/`;
this decision does not itself claim that an unexecuted gate passed.
