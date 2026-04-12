# Quality Tooling

This directory is the repository entrypoint for quality tasks, scripts, and testing policy.

## Read This When

- choosing the right verification command
- debugging a red quality gate
- changing quality scripts, Detekt rules, Lint policy, or prompt-policy docs
- deciding which AI verification path applies to the current change

## Do Not Read This When

- the task is clearly scoped to a feature/module and you already know the relevant code path
- you are not selecting or debugging a verification command
- you are only navigating business logic and no quality/build/prompt decision is involved

## Next Documents

- `testing/ai-meaningful-tests.md`
  - Read only when writing, editing, or reviewing tests.
- `scripts/`
  - Read scripts directly when debugging shell behavior or environment setup.
- `detekt-rules/`
  - Read rule code directly when modifying architecture checks or custom Detekt behavior.

## Read This For

- choosing the right verification command
- understanding the staged quality pipeline
- triaging red `qualityCheck` runs
- running dependency-update checks

## Which Command Should I Run?

| Situation | Command |
| --- | --- |
| Normal app/domain/data/ui iteration | `./gradlew fastQualityCheck` |
| AI iteration after `src/main`, Gradle, quality, detekt, workflow, or AGENTS/prompt changes | `quality/scripts/ai_static_quality_check.sh` |
| AI iteration for pure test/docs changes | `quality/scripts/ai_fast_quality_check.sh` |
| I want Compose-focused static hotspot signals | `./gradlew composeStaticAnalysisCheck` |
| I want JVM tests only | `./gradlew unitTestCheck` |
| I changed static rules or want compile + detekt + lint without coverage | `./gradlew staticQualityCheck` |
| I changed build logic, quality scripts, coverage wiring, or dependency/plugin wiring | `./gradlew qualityCheck` |
| I want dependency usage advice | `./gradlew dependencyAnalysisCheck` |
| I want CVE scanning for dependencies | `./gradlew dependencyVulnerabilityCheck` |
| Final handoff or pre-merge gate | `./gradlew qualityCheck` |

AI agents inside the sandbox should normally use:

- Iteration for production/build/quality/workflow changes:
  - `quality/scripts/ai_static_quality_check.sh`
- Iteration for pure test/docs changes:
  - `quality/scripts/ai_fast_quality_check.sh`
- Optional Compose hotspot pass:
  - `quality/scripts/ai_compose_static_analysis.sh`
- Final handoff:
  - `quality/scripts/ai_quality_check.sh`

Policy notes:

- AGENTS/prompt/doc-policy changes belong on the static AI verification path, not the fast test/docs path.
- Docs-only work may intentionally skip verification, but the final summary must say so explicitly.

Execution defaults:

- Run quality commands from the repository root.
- If a tool launches Gradle from another working directory, pass `--project-dir "$repo_root"` explicitly.
- Prefer repo-local Gradle state such as `GRADLE_USER_HOME="$PWD/.gradle/task-inspect"` so wrapper downloads and caches are reused instead of being recreated under `/tmp` or another ephemeral directory.
- The AI quality scripts already enforce repo-root execution and repo-local `GRADLE_USER_HOME`; use them when possible.
- The AI quality scripts also set repo-local `HOME`, `ANDROID_USER_HOME`, and `XDG_*` paths so Kotlin daemon and Android tooling do not fall back to read-only global directories.
- `ai_fast_quality_check.sh` intentionally exits non-zero when the working tree contains production/build/quality/workflow changes, so AI cannot silently skip detekt-capable verification.

## Staged Quality Pipeline

The quality chain is intentionally staged so the first real failure appears earlier:

1. `compileGateCheck`
2. `unitTestCheck`
3. `staticQualityCheck`
4. `qualityCheck`

Task roles:

- `compileGateCheck`
  - Runs source compile gates first so Kotlin/Java warning-as-error failures surface early.
- `unitTestCheck`
  - Runs JVM unit tests across modules after compile gates pass.
- `fastQualityCheck`
  - Default iterative gate: compile gates, meaningful-test metadata, and JVM unit tests.
- `aiStaticQualityCheck`
  - AI-oriented static gate via `quality/scripts/ai_static_quality_check.sh`, which runs `staticQualityCheck`.
- `staticQualityCheck`
  - Compile gates, architecture checks, Android Lint, and meaningful-test metadata without coverage.
- `fullQualityCheck`
  - Internal staged full gate that backs `qualityCheck`.
- `qualityCheck`
  - Final integrated repository gate.
- `dependencyAnalysisCheck`
  - Runs the dependency-analysis plugin to report unused, mis-scoped, and undeclared dependencies.
  - Experimental under the current AGP 9.2 alpha toolchain; use it as an advisory task, not a merge gate.
- `dependencyVulnerabilityCheck`
  - Runs OWASP Dependency-Check and fails on known vulnerabilities at CVSS 7.0 or higher.
- `architectureCheck`
  - Detekt-based architecture guardrails.
- `androidLintCheck`
  - Android Lint for the configured app and library modules.
- `composeCompilerAnalysisCheck`
  - Generates Compose compiler metrics and reports for `app` and `ui-components`.
- `composeStaticAnalysisCheck`
  - Runs Android Lint plus Compose compiler metrics/reports for AI-readable static hotspot analysis.
- `meaningfulTestCheck`
  - Test metadata contract enforcement.
- `coverageCheck`
  - Verifies merged JVM unit-test coverage against the fixed `70%` minimum.

## Failure Triage

- Read the first compile, test, Detekt, or Lint failure before investigating later Gradle exceptions.
- Treat `qualityCheck` as the final integrated gate, not the fastest edit-loop command.
- Later file-system or test-results errors are often secondary symptoms after an earlier test failure.
- Environment warnings such as read-only Android metrics or JDK `Unsafe` notices may appear in logs, but they are usually not the root cause of a red build.

## Layout

- `detekt/config/`
  - Shared and per-module Detekt configuration.
- `detekt-rules/`
  - Custom Detekt rule module backing architecture guardrails.
- `scripts/`
  - Repository-level quality scripts used by hooks and AI verification.
- `testing/`
  - Meaningful-test policy and AI test authoring contracts.

## Compose Static Analysis Outputs

- `composeStaticAnalysisCheck` writes Android Lint reports to the existing module `build/reports/lint-results-*` locations.
- Compose compiler metrics and reports are aggregated under `build/reports/compose-compiler/<module>/`.
- Use these outputs for static hotspot triage:
  - lint issues are explicit problems or risky patterns
  - compiler reports highlight unstable or non-skippable composables that may deserve inspection

## Dependency Updates

- Dependency and plugin versions live in `gradle/libs.versions.toml`.
- The root build uses `nl.littlerobots.version-catalog-update`.
- Preferred workflow is command-driven:
  - `./gradlew versionCatalogUpdate --check`
  - `./gradlew versionCatalogUpdate`
  - review the generated diff in `gradle/libs.versions.toml`
  - rerun `./gradlew qualityCheck`
- Avoid hand-editing the catalog except for intentional keeps, constraints, or overrides that the update plugin cannot infer.
- Check for updates without modifying files:
  - `./gradlew versionCatalogUpdate --check`
- Review available updates before applying them:
  - `./gradlew versionCatalogUpdate --interactive`
  - inspect `gradle/libs.versions.updates.toml`
  - `./gradlew versionCatalogApplyUpdates`
- Apply updates directly to the catalog:
  - `./gradlew versionCatalogUpdate`
- Reformat the catalog without changing versions:
  - `./gradlew versionCatalogFormat`
- After dependency changes, run `./gradlew qualityCheck`.

## Supply-Chain Guardrails

- Gradle dependency verification is enforced by the checked-in `gradle/verification-metadata.xml`.
- Verification metadata lives in `gradle/verification-metadata.xml`.
- Refresh metadata intentionally after dependency or plugin changes:
  - `./gradlew --write-verification-metadata sha256 help`
- Weekly dependency hygiene runs live in `.github/workflows/dependency_hygiene.yml`.

## Warning Escalation Matrix

- `compileGateCheck`
  - Yes for Kotlin and Java compiler warnings.
  - Root build sets Kotlin `allWarningsAsErrors = true` for compile tasks and Java `-Werror`.
- `unitTestCheck`
  - No generic warning promotion.
  - JVM stderr noise from test/runtime agents is not treated as a build warning channel.
- `architectureCheck`
  - Partially.
  - Detekt findings fail the build because `ignoreFailures = false`, but generic runtime stderr warnings are outside Detekt.
  - Detekt config validation is enabled, but the repo does not separately promote every Detekt warning string to an error.
- `androidLintCheck`
  - Yes for Android Lint issues.
  - Module `lint { warningsAsErrors = true; abortOnError = true }` upgrades Lint warnings to failures.
- `meaningfulTestCheck`
  - No warning concept.
  - This is a shell contract check that passes or fails by exit code only.
- `coverageCheck`
  - No warning promotion.
  - Kover fails only when verification rules fail, such as `minBound` thresholds.
- `qualityCheck`
  - Inherits the behavior above.
  - Compiler warnings and Lint warnings can already fail the build; runtime/JDK agent warnings cannot.

Examples of warnings that are not promoted by the current pipeline:

- JDK 26 reflective-final-field warnings from MockK/Byte Buddy agents
- `sun.misc.Unsafe` deprecation warnings from runtime instrumentation
- Android SDK metrics initialization warnings printed outside compiler/Lint/Detekt issue channels

## Dead-Code Guardrails

- Production Kotlin compile tasks treat warnings as errors, so compiler-detected unreachable code and constant conditions fail the build.
- `quality/detekt-rules` adds repo-specific dead-code checks for:
  - constant branch conditions in production source
  - unreachable statements after unconditional control transfer
  - redundant `else` branches in exhaustive Boolean `when`
