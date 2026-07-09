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

| Situation | Command |
| --- | --- |
| Validate model, build, Detekt, Lint, shell contracts, tests | `quality/scripts/kotlin_static_quality_check.sh` |
| Final handoff / pre-merge / pre-commit gate | `quality/scripts/kotlin_quality_check.sh` |
| Architecture Detekt only | `quality/scripts/kotlin_detekt_check.sh` |
| Test-style Detekt only | `quality/scripts/kotlin_test_style_check.sh` |
| Android Lint only | `quality/scripts/kotlin_android_lint_check.sh` |
| Compose static (compiler metrics + Lint) | `quality/scripts/kotlin_compose_static_analysis.sh` |
| Coverage only (Kover CLI, min 70%) | `quality/scripts/kotlin_coverage_check.sh` |
| Format staged/all Kotlin | `quality/scripts/kotlin_detekt_format.sh staged\|all` |
| Build all modules directly | `source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env manual-build && lomo_kotlin_run build` |
| Run tests directly | `source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env manual-test && lomo_kotlin_run test` |
| Inspect Toolchain modules | `source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env manual-model && lomo_kotlin_run show modules` |
| Local dependency and release packaging maintenance | `quality/scripts/ai_local_maintenance_check.sh` |
| Regenerate static baseline profile | `quality/scripts/generate_static_baseline_profile.py --build-dir <toolchain-build-dir>` |
| Release resource key parity only | `quality/scripts/check_string_resource_parity.sh` |

There is a **single quality CLI family**: `quality/scripts/kotlin_*.sh`. Gradle entrypoints and
legacy `ai_quality` / `ai_static` / `ai_fast` / `ai_compose` shims are not part of this repository.

## Old Gradle → Toolchain Parity Matrix

| Old Gradle task | Toolchain attachment |
| --- | --- |
| `compileGateCheck` / module compile | `lomo_kotlin_run build` |
| `unitTestCheck` | `lomo_kotlin_run test` (host modules; no permanent class excludes) |
| `architectureCheck` / module `detekt` | `quality/scripts/kotlin_detekt_check.sh` |
| `testStyleCheck` | `quality/scripts/kotlin_test_style_check.sh` |
| `detektFormat` / `detektFormatStaged` | `quality/scripts/kotlin_detekt_format.sh` |
| `androidLintCheck` | `quality/scripts/kotlin_android_lint_check.sh` |
| `composeStaticAnalysisCheck` | `quality/scripts/kotlin_compose_static_analysis.sh` |
| `coverageCheck` / `koverVerifyQuality` | `quality/scripts/kotlin_coverage_check.sh` (min 70%) |
| `meaningfulTestCheck` | `quality/scripts/check_meaningful_tests.sh` |
| `stringResourceParityCheck` | `quality/scripts/check_string_resource_parity.sh` |
| `qualityWorkflowContractCheck` | `quality/scripts/test/kotlin_quality_check_contract_test.sh` |
| `staticQualityCheck` | `quality/scripts/kotlin_static_quality_check.sh` |
| `qualityCheck` / `fullQualityCheck` | `quality/scripts/kotlin_quality_check.sh` |
| dependency update / release packaging | `quality/scripts/ai_local_maintenance_check.sh` |

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
6. `lomo_kotlin_run test`
7. Kover coverage verification (min 70%)

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
