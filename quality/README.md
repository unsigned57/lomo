# Quality Tooling

`Justfile` is the human command menu. Every public recipe delegates to the Rust `lomo-xtask`, so
local development, hooks, pull requests, releases, and device smoke tests share one build graph.
The remaining scripts in `quality/scripts/` are single-purpose Kotlin policy checks invoked by
xtask; they are not public quality orchestrators.

## Start Here

| Goal | Command |
| --- | --- |
| Install pinned Rust tools, targets, and NDK | `just bootstrap` |
| Format staged/all sources or check formatting | `just fmt staged`, `just fmt all`, `just fmt check` |
| Run Rust and Kotlin host tests | `just test` |
| Path-aware iterative gate (manual) | `just preflight` |
| Iterative repository gate (pre-push) | `just check` |
| Generate four-ABI release native outputs and bindings | `just native` |
| Build Android debug or signed release APK | `just android debug`, `just android release` |
| Full local handoff gate | `just ci` |
| Real API ≥ 26 device native load/engine smoke (arm64 or x86_64) | `just device-smoke` |
| Real remote provider round trip (credential-gated) | `just sync-provider-smoke [line]` |
| Check or update dependencies | `just deps check`, `just deps update` |
| Planner/size/LLVM diagnostics | `just perf` |
| Audit or clean generated state | `just cache audit`, `just cache clean` |

Run commands from the repository root. `just android release` requires complete signing
configuration through `app/keystore.properties` or `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD`; missing or partial configuration is an error.

## Gate Contract

| Gate | Includes | Intentionally omits |
| --- | --- | --- |
| pre-commit hook | `just fmt staged`, staged meaningful-test contracts | all compile/test/native gates (so multi-commit stacks stay cheap) |
| `just preflight` | Path-aware subset for manual iteration: Rust-only staged changes run the Rust fast gate; Kotlin/native/quality-infra changes add matching native generation and Kotlin surfaces | coverage, fat-LTO, device smoke; not attached to every commit |
| `just test` | Cargo nextest + doc tests; Kotlin host tests | static analysis, coverage, native/APK validation |
| `just check` | Rust fmt, strict Clippy, nextest/doc tests, architecture tests, machete; generated dev bindings/native graph; Kotlin model/build, Detekt, test style, Android Lint, shell contracts, host tests | cargo-deny, Rust/Kotlin coverage, Compose static, fat-LTO release native, APK/device smoke |
| `just ci` | `check` surface plus cargo-deny, Rust LLVM coverage, Kotlin JaCoCo coverage, Compose static, four ABI fat-LTO release native generation, APK contents/ELF/dependency validation | device execution |
| `just device-smoke` | Builds a minimal smoke APK, installs it on an attached API ≥ 26 device with a packaged ABI (`arm64-v8a` or `x86_64`), loads `liblomo_native_jni.so`, and exercises the formal engine/planner smoke surface. Stage-1 hard gate on this line is arm64 API ≥ 26; API 26 AVD is optional non-claim | the rest of the quality gate |
| `just sync-provider-smoke` | Six locked Stage-5 provider lines (Nutstore, Nextcloud, AWS S3, Cloudflare R2, GitHub, GitLab). Each line resolves its `LOMO_SMOKE_*` credentials, then drives the production remote port through an isolated snapshot → publish → verify → conditional delete → verify-absent round trip. Lines without credentials stay `OPEN / pending_env` and the command exits non-zero | everything else; never invoked by `just check` / `just ci` (the smoke targets are `#[ignore]`d so a credential-less run can never report a provider pass) |

### Format corpora

Small golden **format** fixtures live under repository-root `fixtures/` (not under `quality/`,
which only owns gates and scripts). Large seeded corpora are generated into gitignored
`build/corpora/` via:

```bash
cargo run --manifest-path rust/Cargo.toml --locked -p lomo-feasibility -- \
  generate --mode quick|scale|capacity --seed 1 --out build/corpora/<mode> \
  --fixtures fixtures
```

`just perf` regenerates the quick corpus as part of feasibility evidence collection.

### Local hooks

| Hook | Runs | Why |
| --- | --- | --- |
| pre-commit | `just fmt staged`, staged meaningful-test policy | Cheap per-commit feedback; does **not** re-run compile/test/native for every commit in a stack |
| pre-push | `just check` | One iterative gate before remote update |
| merge / handoff | `just ci` (local) + GitHub PR workflow | Coverage, fat-LTO release semantics, path-filtered remote jobs |

Use `just preflight` while iterating when you want a path-aware subset without waiting for a full
`just check`. Splitting a branch into several commits should only multiply the lightweight
pre-commit surface, not N full quality gates.

### Agent / AI completion rule

Agents and automated editors must treat quality gates as part of the implementation, not a later
optional step:

| Change class | Must run before claiming done |
| --- | --- |
| Single Rust crate behavior | `cargo clippy -p <crate> --all-targets --locked -- -D warnings` + targeted `cargo test -p <crate> … --locked` |
| Single Kotlin module behavior | targeted `./kotlin test --include-module=<module> --include-classes='…'` (or module suite) |
| Native / engine / packaging | package surface above + `just device-smoke` when a device is available |
| Anything leaving the working tree for push/review | `just check` |
| Merge / shared-branch handoff | `just ci` (+ device-smoke when stage evidence requires it) |

`just preflight` is only for manual mid-iteration speed. It does **not** close a package, a PR, or
STAGE evidence. If a gate cannot run, leave the work open and record the blocker; do not invent
GREEN.

### GitHub Actions PR surface

- Path filter decides which of Rust host, four-ABI native, Android/Kotlin, and API 26 smoke must run.
- PR native builds use the thin-LTO `release-ci` profile (`ci-native release-ci <abi>`).
- PR Rust/Android gates use `ci-rust fast` / `ci-android fast` (no instrumented coverage).
- Nightly `quality_nightly.yml` runs `ci-rust coverage` and `ci-android coverage`.
- Shipping APKs still use fat `release` via `just android release` / local `just ci`.
- A final job named `quality` aggregates only the required job results for branch protection.
- Tag releases call the same xtask Android release path.

## Pinned Build Facts

Current production native transport is BoltFFI/JNI. Historical UniFFI/JNA numbers remain
immutable in `fixtures/baseline/ffi-transport-baseline.v1.json` and
`fixtures/baseline/size-baseline.v1.json`. Host size gates and arm64 `just device-smoke` are GREEN
per `fixtures/baseline/STAGE1-EVIDENCE.md` and
`fixtures/baseline/size-baseline.v1.json`; UniFFI is not restored for any open residual product work.

- Rust: channel from `rust/rust-toolchain.toml` (currently `1.97`), matching
  `workspace.package.rust-version`, Edition 2024, components `rustfmt`, `clippy`,
  `llvm-tools-preview`, `rust-src`. Bump with `just rust-toolchain-bump <x.y|x.y.z>`
  then `just bootstrap` and quality gates (the bump recipe rewrites pin sites only).
- Android NDK: `29.0.14206865`; native API/minSdk: `26`.
- Android ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- Native facade: `lomo-native` (`staticlib` + `rlib`); packaged library: `liblomo_native_jni.so`.
- Generated Kotlin module/package/owner: `native-bindings` / `com.lomo.nativebridge` /
  `LomoNativeBridge.kt`.
- BoltFFI CLI: exact pin in `rust/tools.toml` (`boltffi_cli` / `boltffi`); runtime uses the
  repository-owned `rust/boltffi-facade` over exact-pinned `boltffi_core` with default features
  disabled.
- Shipping Android pack profile: `release-android` (`opt-level = "z"`, fat LTO) plus pack-path
  `immediate-abort` + `build-std` size policy owned by xtask.
- Cargo tools: exact versions in `rust/tools.toml`, installed under `.cache/cargo-tools`.

`lomo-xtask` is the only public orchestrator. No floating branch, automatic mutation, production
dual stack, compatibility alias, or UniFFI fallback is permitted.

`rust/Cargo.lock`, `rust/tools.toml`, `rust/rust-toolchain.toml`, and source/configuration files are
versioned facts. `native-bindings/src`, `app/jniLibs`, and `native-smoke/jniLibs` are ignored outputs
regenerated by xtask. A clean checkout is therefore the expected build input.

## Rust Governance

The workspace denies warnings, unsafe code, unused must-use values, Clippy `all`, `pedantic`,
`nursery`, and selected structural lints. There is no lint baseline. `cargo-deny` blocks yanked
advisories, unknown registries/git sources, and multiple dependency versions. `cargo machete`
blocks unused direct dependencies.

Release native Android packaging uses profile `release-android` (`opt-level = "z"`, fat LTO, one
codegen unit, stripped). The pack path additionally rebuilds std with `panic=immediate-abort` so
backtrace/gimli weight never ships. Host iterative checks use the development profile. Rust
coverage excludes `lomo-xtask` and `lomo-architecture-tests`; the fail-under threshold is fixed in
`rust/xtask/src/quality.rs` (`RUST_COVERAGE_MINIMUM`, currently **70%** per product decision
2026-07-22). Raise only after a measured green run; do not grind tests solely to climb an
arbitrary higher bar.

See [AI Rust Test Style](testing/ai-rust-test-style.md) before writing or editing Rust tests.

## Kotlin Policy Scripts

The retained scripts have one policy responsibility each:

- `kotlin_detekt_check.sh` and `kotlin_test_style_check.sh`
- `kotlin_android_lint_check.sh` and `kotlin_compose_static_analysis.sh`
- `kotlin_coverage_check.sh`
- `check_meaningful_tests.sh`, `check_string_resource_parity.sh`, and fixture/contract tests
- `generate_static_baseline_profile.py`

xtask supplies all repository-local homes, Android SDK paths, Kotlin wrapper paths, build
directories, and test-module arguments. Do not add another environment bootstrap or quality
gradient script.

## Generated State

Repository-owned generated state lives under `.cache`, `.gradle/kotlin-toolchain`, `.kotlin`,
`.kotlin-cli`, `rust/target`, `native-bindings/src`, `app/jniLibs`, and `native-smoke/jniLibs`.
Use `just cache audit` before cleanup and `just cache clean` to remove only the allowlisted
recreatable outputs.

The Kotlin Toolchain may use an internal Gradle/AGP bridge for Android packaging. That is an
implementation detail, not an additional project build entrypoint. Baseline profile sources remain
under `app/src/main/baselineProfiles/` and `app/src/main/baseline-prof.txt` as the documented
packaging exception.

## Failure Triage

Read the first failing command. Tool/version/NDK/BoltFFI/generated-output failures are boundary errors
and should be fixed at xtask or its pinned inputs. Do not add a fallback, compatibility recipe,
tracked generated artifact, or second workflow path.
