# Stage-0 (data-core feasibility) audit status

This file is the **versioned** stage-0 completion surface (must be committed with evidence
changes). Local planning notes (`ROADMAP.MD`, `plan.md`) are gitignored drafts and must **not**
contradict this file when they mention stage-0; if they drift, **this file wins**.

## Overall

| Field | Value |
| --- | --- |
| Status | **closed** |
| Evidence owner | `fixtures/baseline/`, hermetic probe contracts, `native-smoke`, characterization |

## Workstream map

| ID | Intent | Status | Evidence / honest boundary |
| --- | --- | --- | --- |
| P0-01..03, P0-05 | Fixtures, corpus, size zero-point, dual packaging | **closed** | fixtures + dual jniLibs + size zero-point |
| P0-04 | Kotlin external behavior characterization | **closed** | Room **query result** goldens (`room-query/*`, capability names only — no Entity/DAO types). UI semantic goldens (`semantic-ui/*`). Storage parse goldens (`markdown/*`). Schema-surface inventory is internal-only |
| P0-06 | SQLite feasibility | **closed** | host probe + linked four-ABI feasibility-device SO |
| P0-07 | Markdown write-back + scale perf | **closed** | Unedited write-back preserves open-file **bytes** (BOM + CRLF/LF). Scale: isolated process with peak_rss + result_count + warm_path_p50_ms |
| **P0-08** | HTTP/S3/WebDAV wire | **closed (host)** | wire matrix + request-scoped cancel |
| **P0-09** | Git smart-HTTP | **closed (host)** | smart-HTTP matrix + fail-closed cancel |
| **P0-10** | UniFFI/SAF recovery | **closed (tooling)** | journal + SAF CRUD/move; device-smoke passed |
| P0-11 | Combined candidate volume | **closed (linked volume)** | Four-ABI SO + exact `LOMO_LINK_MARKER_*`. **Acceptance:** combined candidate crate volume after LTO (feature unification / TLS budget). Full smart-HTTP/push/rebase remain host matrices (P0-08/09) |
| P0-12 | Perf gates | **closed (host required set)** | `just perf` Pass; scale metric includes peak_rss_bytes, result_count, warm_path_p50_ms |

## Product decisions (selection)

1. S3: reqwest/Rustls + SigV4 shape (no full AWS SDK for stage-0 volume).
2. Git: vendored `git2`; JGit dual-stack / gix primary rejected.
3. SQLite/Markdown: rusqlite bundled + pulldown-cmark.
4. Production packaging: `lomo-native` → `lomo-sync-core` until ownership stages.

## Closed checklist

- [x] CRLF/BOM unedited open-file byte stability
- [x] Room query result goldens (language-neutral capabilities)
- [x] Scale isolated peak_rss + result_count + warm_path
- [x] Evidence docs aligned to this file
- [x] P0-11 formal acceptance: linked combined-candidate volume (not full smart-HTTP in SO)

## Non-claims

- Feasibility deps not in production `app/jniLibs`.
- P0-11 SO does **not** embed full smart-HTTP/push/rebase execution; host matrices own those capabilities.
- Perf numbers are relative baselines, not product SLA.
- Room schema-surface (entity/DAO names) is **not** the P0-04 golden contract.
