# Stage-5 four-ABI feasibility spike (P5-00)

> Tooling evidence only. Production `app/jniLibs` must never package feasibility-only graphs.

## Scope

Stage 5 production will eventually link:

| Stack | Intended crate | Status at P5-00 |
| --- | --- | --- |
| HTTPS / WebDAV | `reqwest` + `rustls` (aws-lc) | **Inherited GREEN** four-ABI linked volume via `lomo-feasibility-device` |
| Git | `git2` + vendored libgit2/OpenSSL | **Inherited GREEN** four-ABI linked volume |
| SQLite (store projection) | `rusqlite` bundled | **Inherited GREEN** four-ABI linked volume |
| S3 | AWS Rust SDK S3 **minimal features** | **OPEN spike** — host pin + size estimate required before P5-06 production graph |
| rclone crypt | Audited pure/crypto primitives + golden vectors | **Host golden ready** (`fixtures/remote/rclone-crypt-vectors.json`); full four-ABI retention marker deferred to crypt owner package |

## Inherited four-ABI linked evidence (Stage 0)

Source: `fixtures/baseline/feasibility-android-targets.v1.md` + `feasibility-device-size.v1.json`.

Exact retention markers (strings of the SO):

- `LOMO_LINK_MARKER_GIT2_v1`
- `LOMO_LINK_MARKER_REQWEST_RUSTLS_v1`
- `LOMO_LINK_MARKER_SQLITE_v1`

| ABI | Result | `.so` bytes (feasibility-device) |
| --- | --- | --- |
| `arm64-v8a` | pass | 11731528 |
| `armeabi-v7a` | pass | 8328308 |
| `x86_64` | pass | 13322968 |
| `x86` | pass | 10968552 |

**Proves:** constructor/version retention after LTO for git2/reqwest/sqlite.  
**Does not prove:** full smart-HTTP push/rebase, full WebDAV Multi-Status matrices, AWS SDK S3, or rclone crypt inside this SO.

## AWS Rust SDK spike notes

- Pin a Rust **1.97**-compatible AWS SDK S3 crate set with **minimal features** (no SigV4a unless required).
- Feature selection must be recorded in Cargo.lock at first production-graph introduction (P5-06), not earlier into `lomo-native`.
- Four-ABI ELF + license + advisory gates apply via existing xtask native pipeline when the crate enters a linked SO.
- Until a measured four-ABI SO including AWS S3 exists, APK impact remains an **estimate**; hard gate stays Stage 0 × 1.15.

## rclone crypto spike notes

- Compatibility matrix (product decision): password/password2; filename standard/obfuscate/off; base32/base64/base32768; directory-name encryption; data encryption on/off; suffix `.bin` / `none` / custom.
- Golden vectors: `fixtures/remote/rclone-crypt-vectors.json` (standard + dir encryption subset verified).
- Implementation must use audited primitives only; custom stream ciphers forbidden.
- Bidirectional hermetic verification against vectors is a P5-06 exit bar, not a P5-00 claim.

## Hard size gates (must not raise)

- Universal compressed APK hard gate: `size-baseline.v1.json` →
  `debug_universal_compressed_bytes * hard_gate_multiplier` = **112467804 × 1.15 = 129337975**.
- Stage-5 stage-specific native ceiling fixture: `stage5-native-size-ceiling.v1.json`.
  - Freezes the **method** (current non-native APK occupancy + four-ABI measured native + 10% margin).
  - Measured stage-5 native sums are **OPEN** until P5-06/P5-07/P5-11 produce real four-ABI numbers.

## Non-claims

- P5-00 does not claim AWS SDK four-ABI link GREEN.
- P5-00 does not package any new feasibility SO into production.
- P5-00 does not raise the Stage 0 APK hard gate.
