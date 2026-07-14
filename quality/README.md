# Quality Tooling

This directory is the repository entrypoint for Kotlin Toolchain verification,
quality scripts, and testing policy.

## Read This When

- choosing the right verification command
- debugging a red Toolchain quality gate
- changing quality scripts, Detekt rules, Lint policy, or prompt-policy docs
- deciding which AI verification path applies to the current change

## Next Documents

- `testing/ai-meaningful-tests.md`
  - Read only when writing, editing, or reviewing tests.
- `quality/testing-coverage-matrix.md`
  - Read when changing or reviewing test-quality gates, meaningful-test mode coverage, or retained
    source-set exclusions.
- `scripts/`
  - Read scripts directly when debugging shell behavior or environment setup.
- `detekt-rules/`
  - Read rule code directly when modifying architecture checks or custom Detekt behavior.

## Which Command Should I Run?

For human local development, start with `just --list`. The short commands wrap
the same scripts below without replacing them; for example, `just quality` runs
the full repository quality gate.

| Situation | Command |
| --- | --- |
| Lightweight model + build + host tests | `quality/scripts/kotlin_fast_quality_check.sh` (`just fast`) |
| Iterative gate: model, build, Detekt, Lint, shell contracts, host tests | `quality/scripts/kotlin_static_quality_check.sh` (`just static`) |
| Final handoff / pre-merge / pre-commit gate | `quality/scripts/kotlin_quality_check.sh` (`just quality`) |
| Architecture Detekt only | `quality/scripts/kotlin_detekt_check.sh` |
| Test-style Detekt only | `quality/scripts/kotlin_test_style_check.sh` |
| Android Lint only | `quality/scripts/kotlin_android_lint_check.sh` |
| Compose static (compile + lint-checks readiness; metrics soft) | `quality/scripts/kotlin_compose_static_analysis.sh` |
| Coverage only (JaCoCo agent + host tests, min 70%) | `quality/scripts/kotlin_coverage_check.sh` |
| Format staged/all Kotlin | `quality/scripts/kotlin_detekt_format.sh staged\|all` |
| Audit repo-local generated-state size | `quality/scripts/kotlin_cache_audit.sh` |
| Clean old repo-local generated state | `quality/scripts/kotlin_cache_cleanup.sh --dry-run`, then `--apply` |
| Build all modules directly | `source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env manual-build && lomo_kotlin_run build` |
| Run host tests (same modules as gates) | `just test` or source env + `kotlin_toolchain_test_args.sh` then `lomo_kotlin_run test "${toolchain_test_modules[@]}"` |
| Inspect Toolchain modules | `source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env manual-model && lomo_kotlin_run show modules` |
| Local dependency and release packaging maintenance | `quality/scripts/ai_local_maintenance_check.sh` |
| Regenerate static baseline profile | `quality/scripts/generate_static_baseline_profile.py --build-dir <toolchain-build-dir>` |
| Release resource key parity only | `quality/scripts/check_string_resource_parity.sh` |

There is a **single quality CLI family**: `quality/scripts/kotlin_*.sh`. Gradle entrypoints and
legacy `ai_quality` / `ai_static` / `ai_fast` / `ai_compose` shims are not part of this repository.
Human shortcuts live in `Justfile` and only wrap those scripts or the Toolchain env helpers.

## Command Ladder (semantic contract)

| Command | Includes | Omits |
| --- | --- | --- |
| `just fast` / `kotlin_fast_quality_check.sh` | model, build, host tests | Detekt, Lint, Compose, shell contracts, coverage |
| `just static` / `kotlin_static_quality_check.sh` | model, build, architecture + test-style Detekt, Android Lint, shell contracts, host tests | Compose static, coverage |
| `just quality` / `kotlin_quality_check.sh` | static surface + Compose static + host tests under JaCoCo + coverage min 70% | nothing in the quality surface |
| `just test` | host tests for the canonical module set | build/static analysis unless Toolchain needs compile |

Notes:

- `static` is **not** compile-only: it includes host tests. Prefer `fast` when you only need build + tests.
- Full `quality` runs host tests **once**, under coverage instrumentation (no separate uninstrumented re-run).
- Host test modules are centralized in `quality/scripts/kotlin_toolchain_test_args.sh` and shared by gates and `just test`.

## Old Gradle → Toolchain Attachment (with honesty notes)

| Old Gradle task | Toolchain attachment | Parity notes |
| --- | --- | --- |
| `compileGateCheck` / module compile | `lomo_kotlin_run build` | Closest equivalent; no per-module task list. |
| `unitTestCheck` | `lomo_kotlin_run test` via `toolchain_test_modules` | Host modules only; device/instrumentation suites removed by design. |
| `fastQualityCheck` | `quality/scripts/kotlin_fast_quality_check.sh` | Restored as model + build + host tests. |
| `architectureCheck` / module `detekt` | `quality/scripts/kotlin_detekt_check.sh` | Detekt CLI + custom rules jar. |
| `testStyleCheck` | `quality/scripts/kotlin_test_style_check.sh` | Detekt CLI test-style config. |
| `detektFormat` / `detektFormatStaged` | `quality/scripts/kotlin_detekt_format.sh` | CLI format path. |
| `androidLintCheck` | `quality/scripts/kotlin_android_lint_check.sh` | SDK lint + Toolchain prepareAndroid bridge scrape (not AGP lint task). |
| `composeStaticAnalysisCheck` | `quality/scripts/kotlin_compose_static_analysis.sh` | Hard path is compose-lint-checks via Lint; compiler metrics soft unless `LOMO_REQUIRE_COMPOSE_COMPILER_REPORTS=true`. |
| `coverageCheck` / `koverVerifyQuality` | `quality/scripts/kotlin_coverage_check.sh` (min 70%) | JaCoCo agent + `*-jvm.jar` classfiles (not Kover plugin). |
| `meaningfulTestCheck` | `quality/scripts/check_meaningful_tests.sh` | Unchanged shell policy. |
| `stringResourceParityCheck` | `quality/scripts/check_string_resource_parity.sh` | Unchanged shell policy. |
| `qualityWorkflowContractCheck` | `quality/scripts/test/kotlin_quality_check_contract_test.sh` | Structural workflow contract. |
| `staticQualityCheck` | `quality/scripts/kotlin_static_quality_check.sh` | **Stronger than old static**: also runs host tests. |
| `qualityCheck` / `fullQualityCheck` | `quality/scripts/kotlin_quality_check.sh` | Coverage owns the single host-test pass. |
| dependency update / release packaging | `quality/scripts/ai_local_maintenance_check.sh` | Low-frequency maintenance (name retains `ai_` prefix). |

## Execution Defaults

- Run quality commands from the repository root.
- Prefer `quality/scripts/kotlin_static_quality_check.sh` and
  `quality/scripts/kotlin_quality_check.sh` because they set repo-local
  Toolchain, Android, and internal bridge state.
- Set `LOMO_KOTLIN_ANDROID_SDK` only when intentionally using another writable
  SDK location.
- Release packaging expects `app/keystore.properties` per the Kotlin Toolchain
  Android signing model.

The Kotlin Toolchain scripts create repo-local tooling state by default:

- `.android-sdk` for Android SDK packages and licenses
- `.android` for Android user state
- `.home`, `.cache`, `.config`, and `.local/share` for Toolchain and Android support files
- `.gradle/kotlin-toolchain` only for the Kotlin Toolchain Android app bridge
- `.cache/detekt` and `.cache/kover` for CLI fat jars used by quality scripts

Generated state has a bounded ownership model:

- `kotlin_fast_quality_check.sh` writes Toolchain build output to
  `.kotlin/toolchain-build/fast-gate` by default.
- `kotlin_static_quality_check.sh` writes Toolchain build output to
  `.kotlin/toolchain-build/static-gate` by default.
- `kotlin_quality_check.sh` writes Toolchain build output to
  `.kotlin/toolchain-build/quality-gate` by default.
- Standalone Lint and Coverage keep `lint-gate` and `coverage-gate` defaults,
  but inherit `LOMO_KOTLIN_BUILD_DIR` when orchestrated by a higher-level gate.
- `quality/scripts/kotlin_cache_cleanup.sh` removes old named Toolchain build
  directories and generated reports, while retaining dependency/tool downloads
  that are expensive to recreate. Canonical retained gates include
  `fast-gate`, `static-gate`, `quality-gate`, `lint-gate`, `coverage-gate`,
  and `local-maintenance-release`.

Current Kotlin Toolchain Android app packaging delegates part of `android/app`
preparation through an internal Gradle/AGP bridge. This is a Toolchain
implementation detail: the repository no longer keeps project Gradle DSL,
version catalogs, wrapper scripts, or wrapper properties as build entrypoints.

**Layout exception:** `app/src/main/baselineProfiles/` and `app/src/main/baseline-prof.txt`
remain under an AGP-shaped path for packaging. All product Kotlin sources use Amper roots
(`src/`, `test/`, `res/`, `composeResources/`).

## Staged Quality Pipeline

`kotlin_quality_check.sh` is the repository build contract:

1. `lomo_kotlin_run show modules`
2. `lomo_kotlin_run build`
3. architecture Detekt + test-style Detekt
4. Android Lint + Compose static analysis
5. shell contracts (meaningful tests, string parity, quality workflow contract)
6. host tests under JaCoCo + line coverage verification (min 70%) via
   `kotlin_coverage_check.sh` (single test flight; no separate uninstrumented re-run)

## Failure Triage

- Read the first compile, test, Detekt, Lint, or Toolchain bridge failure before
  investigating later exceptions.
- Android app packaging may mention Gradle in logs while the Toolchain bridge is
  active. Treat that as internal runner output, not as a project Gradle
  entrypoint.
- Environment warnings such as read-only Android metrics or JDK agent warnings
  may appear in logs, but they are usually not the root cause of a red build.

## Layout

- `detekt/config/`
  - Shared and per-module Detekt configuration.
- `detekt-rules/`
  - Custom Detekt rule module backing architecture guardrails (`layout: maven-like` JVM exception).
- `scripts/`
  - Repository-level quality scripts used by hooks and AI verification.
- `testing/`
  - Meaningful-test policy and AI test authoring contracts.

## Maintenance Gates

- `quality/scripts/ai_local_maintenance_check.sh` writes a local maintenance
  report, audits explicit `module.yaml` Maven coordinates against repository
  metadata, and runs an app release build through Kotlin Toolchain.
- `quality/scripts/generate_static_baseline_profile.py` regenerates
  `app/src/main/baselineProfiles/generated.txt` from Toolchain classpath jars
  after a release or debug app build has produced them.
