# Stage-0 candidate matrix (P0-11)

Machine-readable companion: `size-baseline.v1.json`.
**Audit status:** `STAGE0-STATUS.md` (overall **closed** under documented non-claims).

Evidence sources are named under each row. Host probe pass ≠ four-ABI device pass.

## Decision summary

| Capability | Candidate | Version | Features (pinned) | Stage-0 verdict |
| --- | --- | --- | --- | --- |
| SQLite | `rusqlite` + bundled SQLite | **0.40.1** | `default-features=false`, `bundled`, `backup` | **adopt intent** |
| Markdown parse | `pulldown-cmark` | **0.13.4** | `default-features=false` | **adopt intent** |
| HTTPS client | `reqwest` + `rustls` | **0.13.4** / **0.23.37** | rustls only, blocking | **adopt intent** |
| S3-shaped wire | Hermetic fixture + SigV4 shape | same as HTTP | no AWS SDK crate | **adopt** |
| Git | `git2` + vendored libgit2/OpenSSL | **0.21.0** | vendored-libgit2, https, vendored-openssl | **adopt intent** (host smart-HTTP) |
| FFI | UniFFI via `lomo-native` | **0.32.0** | feasibility-probe tooling only | **adopt** |

## Verified abilities (honest scope)

| Area | Evidence | Result | Scope limit |
| --- | --- | --- | --- |
| Room **query results** (language-neutral) | `characterization/room-query/*.json` + runtime test | **pass** | Capability names only; no Entity/DAO types in goldens |
| Unedited Markdown **open-file bytes** | `UneditedMemoWriteBackCharacterizationTest` | **pass** | BOM + CRLF/LF preserved |
| UI semantic parser | `characterization/semantic-ui/*` | **pass** | Real `parseMarkdownSemanticDocument` |
| HTTPS+WebDAV wire | `http_probe_contract` | **host pass** | Request-scoped cancel |
| Git smart-HTTP | `git_probe_contract` | **host pass** | Fail-closed cancel |
| SAF CRUD/move + journal | `just device-smoke` | **pass (tooling)** | — |
| Combined SO volume | `feasibility-device-size.v1.json` | **pass (linked volume)** | Exact LOMO markers; not full smart-HTTP in SO |
| Scale markdown perf fields | `size-baseline.v1.json` | **pass** | Isolated process: peak_rss + result_count + warm_path_p50_ms |

## Explicit non-claims

- Room **schema-surface** inventory (entity/DAO names) is internal tooling inventory, **not** the P0-04 exit golden contract.
- P0-11 linked volume ≠ executing full smart-HTTP/push/rebase inside the Android SO.
- Peak RSS for non-isolated metrics remains null.
- Production `app/jniLibs` does not package feasibility deps.

## Stage-0 exit checklist

See `STAGE0-STATUS.md` (**closed**).

| Gate | State |
| --- | --- |
| Host candidates chosen | **done** |
| Size zero-point +15% hard | **done** |
| P0-08 wire matrix | **done (host)** |
| P0-09 smart-HTTP | **done (host)** |
| P0-10 SAF/move device-smoke | **done** |
| P0-04 Room query + UI semantic goldens | **done** |
| P0-07 unedited byte write-back | **done** |
| P0-07/12 scale peak_rss + warm_path + result_count | **done** |
| P0-11 linked combined-candidate volume | **done** |
