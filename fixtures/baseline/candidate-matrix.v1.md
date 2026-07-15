# Stage-0 candidate matrix (P0-11)

Machine-readable companion: `size-baseline.v1.json`. Evidence sources are named under each row.

## Decision summary

| Capability | Candidate | Version | Features (pinned) | Stage-0 verdict |
| --- | --- | --- | --- | --- |
| SQLite | `rusqlite` + bundled SQLite | **0.40.1** | `default-features=false`, `bundled`, `backup` | **adopt** for stages 1–3 |
| Markdown parse | `pulldown-cmark` | **0.13.4** | `default-features=false`; enable tables/tasklists/strikethrough in probe | **adopt** for stage 2 foundation |
| HTTPS client | `reqwest` + `rustls` | **0.13.4** / **0.23.37** | reqwest: `rustls`,`blocking`; rustls: `aws-lc-rs`,`std`,`tls12`; **no native TLS** | **adopt** for stage 4 HTTP/WebDAV |
| S3-shaped wire | Hermetic HTTPS fixture (reqwest) | same as HTTP | List pagination + conditional PUT | **adopt path-style/custom-endpoint shape**; full AWS SDK deferred until volume budget allows |
| Git | `git2` + vendored libgit2/OpenSSL | **0.21.0** | `vendored-libgit2`,`https`,`vendored-openssl` | **adopt** for stage 4; smart-HTTP TLS is stage-4 entry work (see `fixtures/git/EVIDENCE.md`) |
| FFI | UniFFI via `lomo-native` | **0.32.0** | production default empty; tooling `feasibility-probe` | **adopt** single facade |
| async runtime | Tokio | not yet in production graph | — | **hold** until stage 4 network ownership; probes use blocking reqwest |
| secret wipe | `zeroize` | not yet in production graph | — | **hold** until credential port lands |

## Verified abilities

| Area | Evidence | Result |
| --- | --- | --- |
| WAL / FK / FTS5 / backup / reopen | `rust/feasibility/tests/sqlite_probe_contract.rs` | pass |
| Offset events, GFM basics, invalid UTF-8 fail-closed | `markdown_probe_contract` + `fixtures/markdown/*` | pass |
| Local HTTPS, timeout, S3 list paging, conditional PUT | `http_probe_contract` | pass |
| Bare push/fetch + rebase conflict | `git_probe_contract` + `fixtures/git/EVIDENCE.md` | pass |
| FeasibilityProbe object / page bound / cancel / batch replay / close | `native-smoke` + feature `feasibility-probe` | pass (device-smoke) |
| SAF create/read/replace/rename/delete + metadata page | `FeasibilityDocumentsProvider` | pass (device-smoke) |
| Production isolation of probe | architecture test + dual jniLibs packaging | pass |
| License / advisory | `rust/deny.toml` via xtask deps check | pass when CI green |
| Four ABI + ELF API 26 packaging | `lomo-xtask` native verify | pass |

## Explicit non-claims

- Emulator **time/RSS p50/p95** baselines remain `pending_emulator` in `size-baseline.v1.json` (host-relative size numbers only).
- Full **AWS SDK for Rust** not combined into production native; S3 shape proven via fixture. Revisit before stage 4 if APK budget allows.
- **HTTPS smart-HTTP Git** not stage-0 complete; same `git2` stack required at stage-4 entry.
- **Listener callbacks** on FeasibilityProbe not exported; revision + cancel/batch cover recovery contracts needed for stage 1 design.
- No production DI wiring of probes; stage 1 engine must delete `FeasibilityProbe` after formal contracts land.

## Size budget (APK +15% hard gate)

Baseline zero point (`fixtures/baseline/size-baseline.v1.json`):

| Metric | Bytes | Notes |
| --- | --- | --- |
| Debug universal APK (host relative) | **119,054,148** | `.kotlin/toolchain-build/check` capture |
| `liblomo_native.so` arm64-v8a | **445,064** | production packaging (no probe) |
| `liblomo_native.so` armeabi-v7a | **308,228** | |
| `liblomo_native.so` x86_64 | **494,056** | |
| `liblomo_native.so` x86 | **500,996** | |
| `libjnidispatch.so` (sum reference) | per-ABI in baseline JSON | JNA contribution |

Hard gate: final compressed universal APK ≤ **baseline × 1.15**.

Do **not** pre-count Kotlin dependency deletions that have not shipped. Budget allocation for later stages (planning only; remeasure after each ownership switch):

| Stage addition | Planning envelope | Constraint |
| --- | --- | --- |
| Markdown / workspace parse | ≤ 20% of remaining +15% headroom | pulldown-cmark + IR |
| Store / SQLite | ≤ 45% of remaining headroom | bundled SQLite dominates |
| Sync HTTP + Git | ≤ 35% of remaining headroom | Git/OpenSSL is largest risk; measure with thin LTO CI vs fat release |

Combined tooling probe graph (feasibility crate) is **not** packaged into production APK. Production graph stays `lomo-native` → `lomo-sync-core` until stage ownership moves.

## Feature unification notes

- `feasibility-probe` is **off** by default on `lomo-native`; only `native-smoke/jniLibs` builds enable it.
- Generated Kotlin bindings may include probe types for tooling; production Kotlin must not import `FeasibilityProbe` (architecture test).
- Dual TLS stacks must not appear in production: probes use Rustls only; git2 brings vendored OpenSSL for libgit2 HTTPS — accepted stage-0 exception, measure combined size before stage-4 switch.
- No second native facade; no production feature flag dual-write.

## Stage-0 exit checklist (P0-11 acceptance)

- [x] Database / Markdown / HTTP shape / Git candidate / FFI have no open library search
- [x] Size zero-point recorded; +15% remains hard
- [x] Probe isolation and dual packaging defined
- [x] `just preflight` green (fmt/clippy/deny/tests/native/Kotlin compile)
- [x] `just device-smoke` green (physical device; API 26 emulator still accepted policy)
- [x] `just perf` green — established host/device relative metrics in `fixtures/baseline/size-baseline.v1.json` and `build/reports/feasibility/`
- [x] Full `just ci` green (coverage + fat-LTO release native + APK ELF validation)
