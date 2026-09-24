# Quality Tooling

`Justfile` is the human command menu. Every public recipe delegates to the Rust `lomo-xtask`, so
local development, hooks, pull requests, and releases share one build graph.
The remaining scripts in `quality/scripts/` are single-purpose Kotlin policy checks invoked by
xtask; they are not public quality orchestrators.

## Start Here

| Goal | Command |
| --- | --- |
| Install pinned Rust tools, targets, and NDK | `just bootstrap` |
| Format staged/all sources or check formatting | `just fmt staged`, `just fmt all`, `just fmt check` |
| Path-aware worktree iteration gate (`--tests-only` skips static analysis) | `just dev` |
| Iterative repository gate (handoff) | `just check` |
| Generate four-ABI release native outputs and bindings | `just native` |
| Build Android debug or signed release APK | `just android debug`, `just android release` |
| Full local handoff gate | `just ci` |
| Check or update dependencies | `just deps check`, `just deps update` |
| Workspace/store/sync performance diagnostics | `just perf` |
| Show, audit, prune, or clean generated state | `just cache paths`, `just cache audit`, `just cache prune`, `just cache clean` |
| Regenerate Kotlin bindings only | `just bindings` |
| Run the TUI / a full mutation sweep | `cargo run -p lomo-tui`, `cargo mutants` |

Run commands from the repository root. `just android release` requires complete signing
configuration through `apps/android/app/keystore.properties` or `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD`; missing or partial configuration is an error.

## Gate Contract

| Gate | Includes | Intentionally omits |
| --- | --- | --- |
| pre-commit hook | `just fmt staged`, staged meaningful-test contracts | all compile/test/native gates (so multi-commit stacks stay cheap) |
| `just dev` | Path-aware subset of the worktree diff (staged, unstaged, untracked, deleted) for iteration: touched Rust crates run diff-scoped `cargo mutants` after their suites pass. `--tests-only` keeps only the test tasks | coverage, fat-LTO |
| `just _preflight` (pre-push hook) | Same DAG over pushed commits vs the remote base: rust-only pushes skip the Kotlin surface; a missing remote base falls back to the full iterative surface without mutation tasks | coverage, fat-LTO; not a public recipe |
| `just check` | Rust fmt, strict Clippy, nextest/doc tests, architecture tests, machete; generated dev bindings/native graph; Kotlin model/build, Detekt, test style, Android Lint, shell contracts, host tests | cargo-deny, Rust/Kotlin coverage, Compose static, fat-LTO release native, APK install |
| `just ci` | `check` surface plus cargo-deny, Rust LLVM coverage, Kotlin JaCoCo coverage, Compose static, four ABI fat-LTO release native generation, APK contents/ELF/dependency validation | device execution |

### Format corpora

Small golden **format** fixtures live under repository-root `fixtures/` (not under `quality/`,
which only owns gates and scripts). Large seeded corpora are generated into gitignored
`target/lomo/corpora/` via:

```bash
cargo run --manifest-path Cargo.toml --locked -p lomo-xtask -- perf
```

`just perf` measures only current workspace, store and sync owners. Provider networking is an
optional diagnostic (`cargo test -p lomo-sync --test provider_smoke -- --ignored`, and the
matching `lomo-git` target).

### Iteration and completion

Use the narrow crate/spec command while editing; the package and handoff requirements are stated
once in [AGENTS.md](../AGENTS.md#4-verify-at-the-appropriate-boundary). A full release/coverage run is
not required after every edit. `just dev` is the iteration aid, while `just check` and
`just ci` keep the contents in the gate table above. Source compilation can fail and is a dependency
of Clippy/tests; packaging and coverage are separate responsibilities.

### GitHub Actions PR surface

- Path filter decides which of Rust host, four-ABI native, and Android/Kotlin must run.
- PR native builds use the thin-LTO `release-ci` profile (`ci-native release-ci <abi>`).
- PR Rust/Android gates use `ci-rust fast` / `ci-android fast` (no instrumented coverage).
- Shipping APKs still use fat `release` via `just android release` / local `just ci`.
- A final job named `quality` aggregates only the required job results for branch protection.
- Tag releases call the same xtask Android release path.

## Pinned Build Facts

Current production native transport is BoltFFI/JNI. ABI, ELF, DT_NEEDED, legacy-library absence
and packaging completeness are checked directly from generated outputs; no historical size ceiling
is a quality input.

- Rust: channel from `rust-toolchain.toml` (currently `1.98`), matching
  `workspace.package.rust-version`, Edition 2024, components `rustfmt`, `clippy`,
  `llvm-tools-preview`, `rust-src`. Bump with `just rust-toolchain-bump <x.y|x.y.z>`
  then `just bootstrap` and quality gates (the bump recipe rewrites pin sites only).
- Android NDK: `29.0.14206865`; native API/minSdk: `26`.
- Android ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- Native facade: `lomo-native` (`staticlib` + `rlib`); packaged library: `liblomo_native_jni.so`.
- Generated Kotlin module/package/owner: `apps/android/native-bindings` / `com.lomo.nativebridge` /
  `LomoNativeBridge.kt`.
- BoltFFI CLI: exact pin in `tools.toml` (`boltffi_cli` / `boltffi`); runtime uses the
  repository-owned `crates/boltffi-facade` over exact-pinned `boltffi_core` with default features
  disabled.
- Shipping Android pack profile: `release-android` (`opt-level = "z"`, fat LTO) plus pack-path
  `immediate-abort` + `build-std` size policy owned by xtask.
- Cargo tools: exact versions in `tools.toml`, installed under `LOMO_CARGO_TOOLS_DIR` or the
  standard XDG cache (`$XDG_CACHE_HOME/lomo/cargo-tools`).

`lomo-xtask` is the only public orchestrator. No floating branch, automatic mutation, production
dual stack, compatibility alias, or UniFFI fallback is permitted.

`Cargo.lock`, `tools.toml`, `rust-toolchain.toml`, and source/configuration files are
versioned facts. `apps/android/native-bindings/src` and `apps/android/app/jniLibs` are ignored outputs
regenerated by xtask. A clean checkout is therefore the expected build input.

## Rust Governance

The workspace denies warnings, unsafe code, unused must-use values, Clippy `all`, `pedantic`,
`nursery`, and selected structural lints. There is no lint baseline. `cargo-deny` blocks yanked
advisories, unknown registries/git sources, and multiple dependency versions. `cargo machete`
blocks unused direct dependencies. The pinned `cargo-mutants` runs diff-scoped mutation testing
(`--in-diff`, nextest runner) on every touched Rust crate inside the `dev`/`_preflight` DAG — the
pre-push hook exercises it automatically — while a full sweep is `cargo mutants` directly.
Missed mutants fail the task; `mutants.toml` owns equivalent-mutant exclusions.

Release native Android packaging uses profile `release-android` (`opt-level = "z"`, fat LTO, one
codegen unit, stripped). The pack path additionally rebuilds std with `panic=immediate-abort` so
backtrace/gimli weight never ships. Host iterative checks use the development profile. Rust
coverage excludes `lomo-xtask` and `lomo-architecture-tests`; the fail-under threshold is fixed in
`crates/lomo-xtask/src/quality.rs` (`RUST_COVERAGE_MINIMUM`, currently **70%** per product decision
2026-07-22). Raise only after a measured green run; do not grind tests solely to climb an
arbitrary higher bar.

Architecture locks live in `lomo-architecture-tests/tests`: Cargo metadata checks all declared
production/build dependencies (including aliases, optional and target-specific edges); parsed Amper
YAML checks module scopes; Rust syntax checks include untracked sources, macros and conditional
attributes. Parser dependencies are dev-only. Unknown owners and malformed policy input fail closed.

See [AI Rust Test Style](testing/ai-rust-test-style.md) before writing or editing Rust tests.

## Kotlin Policy Scripts

The retained scripts have one policy responsibility each:

- `kotlin_detekt_check.sh` and `kotlin_test_style_check.sh`
- `kotlin_android_lint_check.sh` and `kotlin_compose_static_analysis.sh`
- `kotlin_coverage_check.sh`
- `check_meaningful_tests.sh`, `check_string_resource_parity.sh`, and fixture/contract tests
- `generate_static_baseline_profile.py`

Production Detekt runs a real CLI activation contract with legal and forbidden fixtures before
analyzing source. `NoSourceSuppressions` also runs after annotation suppression, so a file cannot
silence that rule with `@file:Suppress("all")`. Source baselines and path exceptions cannot bypass
ownership checks. Mutable Flow writers stay private in every layer, including state holders and
platform facades.

The current CLI uses **light** analysis (syntax only). Type-dependent built-in checks require full
analysis with the correct per-module compile classpath; listing a rule in YAML does not make that
analysis happen. Do not claim full semantic coverage from the light gate.

xtask preserves the caller's standard `HOME`, XDG, `GRADLE_USER_HOME`,
`KOTLIN_CLI_BOOTSTRAP_CACHE_DIR`, `CARGO_HOME`, `CARGO_TARGET_DIR`, and Cargo wrapper configuration.
`LOMO_KOTLIN_*` variables remain explicit higher-priority overrides. Every Kotlin gate and Android
build uses one `LOMO_KOTLIN_BUILD_DIR`, defaulting to `.kotlin/toolchain-build/shared`; task names
must never create parallel build trees. Do not add another environment bootstrap or quality
gradient script.

### Audit-derived invariants

The recurring audit defect classes are internalized as first-principles source invariants. Each
gate below names the audit packages it closes; rule descriptions carry the same anchors so the
gitignored `audit/` notes are not the durable record.

| Invariant | Gate | Enforcement |
| --- | --- | --- |
| I1 Identity cannot be forged — ids, tokens, generations, epochs, fences, revisions and nonces arrive from their owner; content digests may measure content but never mint sequencing identity | `NoMintedIdentity` (Detekt), `rust-identity-sentinel` | `apps/android/quality/detekt-rules/src/AuditInvariantRules.kt`, `crates/lomo-architecture-tests/tests/policy/rust_invariants.rs` (A04, B03, B04, C09) |
| I2 Failure is typed — codes and sealed states drive control flow, never message text; corruption surfaces instead of silently resetting to empty state | `NoErrorMessageControlFlow`, `NoCorruptionEmptyReset` (Detekt), `rust-error-sniff` | same files (B01, B14, C15) |
| I3 One fact, one exit — a business fact is published/mutated by a declared owner file | `kotlin_single_exit_authority_holds` | `crates/lomo-architecture-tests/tests/policy/authority.rs` authority table (A02, A03, B03, B05) |
| I4 No placeholders or seams — capabilities are required collaborators, fail-fast at resolution; constant status fields are dead surfaces | `NoPlaceholderCollaborator`, `NoCapabilitySeam`, `NoConstantStatusValue` (Detekt) | `AuditInvariantRules.kt` (B05, B11, C15) |
| I5 Byte budget before read — boundary I/O goes through the `read_bounded` owner; remote transports are never inside loops | `rust-bounded-io`, `rust-loop-boundary-io` | `rust_invariants.rs`, `lomo-core::io::read_bounded` (A07, A10, B04, B07, B11) |
| I6 Secrets stay in the safe domain — work payloads carry field-name handles, never plaintext secret values | `NoSecretInWorkPayload` (Detekt) | `AuditInvariantRules.kt` (B15) |
| I7 Wire input enters through checked constructors — public `*Request/*Command/*Intent/*Envelope` deserializable types name a `try_from`/`from`/`remote` entry | `rust-checked-wire` | `rust_invariants.rs` (T18 pattern generalized) |
| I8 Destructive selection uses exact identity — fuzzy name predicates cannot pick deletion targets | `NoNamePredicateDelete` (Detekt) | `AuditInvariantRules.kt` (A05) |
| Domain time is an injected seam — domain code never reads the platform wall clock | `NoDomainClock` (Detekt) | `AuditInvariantRules.kt` (C09) |
| UDF state is single-owner — a state payload reaching the UI is an immutable snapshot, and a flow-producing derivation is a pure projection that cannot hide a state write | `NoMutableStatePayload`, `NoWriteInFlowDerivation`, `NoInferredMutableStatePayload` (Detekt) | `apps/android/quality/detekt-rules/src/StatePayloadImmutabilityRules.kt`, `quality/udf-contract.md` |

Intentional exceptions carry a declared `// behavior-contract: <id>: <reason>` marker on the owning
declaration or the line above the call; the marker vocabulary itself is gate-checked
(`behavior_contract_markers_are_declared_and_reasoned`), so inventing an id cannot open a hole.
Config parity is locked by `detekt_rule_registry_matches_module_configs` and
`production_detekt_configs_keep_ownership_checks_active` — a rule that exists but is not activated
in every module config fails the architecture suite.

Not mechanized on purpose: cryptographic nonce/AAD domains, crash-recovery orderings, mount/GC
TOCTOU proofs, lock-held network IO and peak-byte budgets remain per-task contract tests.

## Generated State

Source-specific generated state lives under `target/lomo/` inside the configured Cargo target
directory (`apk/`, `dist/`, `reports/`, `corpora/`, `tmp/`), the configured shared Kotlin build
directory, `.kotlin/artifacts`, `native-bindings/src`, and `app/jniLibs`. Cargo's own `debug/` and
`release/` graphs stay beside that namespace. Dependency caches stay in the
caller's standard Cargo, Gradle, Kotlin, and
XDG homes so successive gates and checkouts can reuse them. `just cache paths` reports the resolved
paths. `just cache prune` reclaims stale Cargo artifacts — files untouched for more than seven
days, packed debug-symbol (`.dwp`) files, and `llvm-cov` instrumented build output — while keeping
fresh build state and `target/lomo` release outputs; the pre-push gate runs it automatically.
`just cache clean` removes only allowlisted repository outputs and never deletes caller-owned
global caches.

The Kotlin Toolchain may use an internal Gradle/AGP bridge for Android packaging. That is an
implementation detail, not an additional project build entrypoint. Baseline profile sources remain
under `app/src/main/baselineProfiles/` and `app/src/main/baseline-prof.txt` as the documented
packaging exception. The handoff gate's `baseline-profile` node regenerates the profile with
`quality/scripts/generate_static_baseline_profile.py --build-dir <shared Kotlin build>` from the
same build directory the app classes compiled into; `baselineProfiles/generated.txt` is never
hand-edited.

## Failure Triage

Read the first failing command. Tool/version/NDK/BoltFFI/generated-output failures are boundary errors
and should be fixed at xtask or its pinned inputs. Do not add a fallback, compatibility recipe,
tracked generated artifact, or second workflow path.
