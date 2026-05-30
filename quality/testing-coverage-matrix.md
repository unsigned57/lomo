# Testing Coverage Matrix

This matrix is the reviewable owner map for the repository's Kotlin test-quality policy. The
policy remains broader than the currently executable checks: every touched Kotlin test must follow
`quality/testing/ai-meaningful-tests.md` and `quality/testing/ai-kotlin-test-style.md`, while the
automation below explains which source sets are full-gate enforced and which checks are changed
files / diff guards.

## Gate Semantics

| Gate | Scope | Enforcement mode | Full gate? |
| --- | --- | --- | --- |
| `testStyleCheck` | Runs `appTestStyleDetekt`, `domainTestStyleDetekt`, `dataTestStyleDetekt`, and `ui-componentsTestStyleDetekt` over each module's `src/test` and `src/androidTest` directories, then applies `quality/detekt/config/test-style.yml`. | Full scan of configured source directories, reduced by configured excludes. | Yes. Included in `staticQualityCheck`, `fullQualityCheck`, and `qualityCheck`. |
| `meaningfulTestCheck` | Runs `quality/scripts/check_meaningful_tests.sh`. By default it collects changed test files from staged, CI, or working-tree diff depending on `MEANINGFUL_TEST_CHECK_MODE`. | Changed files / diff guard by default. `MEANINGFUL_TEST_CHECK_ALL=true` or `--all` scans all `*Test.kt` / `*Test.kts` files under `app`, `domain`, `data`, and `ui-components`, including `androidTest` where present. | Partly. The gate is in `staticQualityCheck`, `fastQualityCheck`, and `qualityCheck`, but its default repository run is diff-scoped. |
| `staticQualityCheck` | Compile gates, architecture Detekt, `testStyleCheck`, Android Lint, and `meaningfulTestCheck`. | Full scan for Detekt/Lint/compile tasks; changed files for meaningful-test metadata unless all-mode is requested. | Yes. |
| `qualityCheck` | `fullQualityCheck`: `staticQualityCheck`, `unitTestCheck`, and `coverageCheck`. | Final integrated full gate for compile, tests, static checks, and coverage floor. | Yes. |
| `quality/scripts/test/check_meaningful_tests_smoke_test.sh` | Fixture-level shell smoke tests for meaningful-test diff behavior. | Executable script contract, manual unless invoked by a maintainer or targeted verification. | No. |
| `quality/scripts/test/testing_coverage_matrix_contract_test.sh` | Checks this matrix names configured Detekt excludes, diff/all meaningful-test modes, and Gradle gate owners. | Executable script contract, manual unless invoked by a maintainer or targeted verification. | No. |

## Source-Set Matrix

| Source set | Unit-test execution | `testStyleCheck` coverage | `meaningfulTestCheck` coverage | Notes |
| --- | --- | --- | --- | --- |
| `app/src/test` | Included in `unitTestCheck` and `qualityCheck` via the app JVM unit-test task. | Full-gate Detekt test-style coverage. Current path-specific legacy app test exclusions have been removed from `test-style.yml`. | Changed files by default; all-mode covers `app/src/test/**/*Test.kt` and `*Test.kts` except `src/test/.../testing` support helpers. | Primary P1 ViewModel/coordinator/UI policy tests. |
| `app/src/androidTest` | Not part of JVM `unitTestCheck`; device/instrumentation execution is outside the default quality gate. | Excluded by `**/androidTest/**`. | Changed files by default; all-mode covers `app/src/androidTest/**/*Test.kt` and `*Test.kts` except `src/androidTest/.../testing` support helpers. | Must still follow policy when touched. |
| `data/src/test` | Included in `unitTestCheck` and `qualityCheck` via the data JVM unit-test task. | Excluded by `**/data/src/test/**`. | Changed files by default; all-mode covers `data/src/test/**/*Test.kt` and `*Test.kts` except `src/test/.../testing` support helpers. | High-risk P0 repository/sync tests are protected by meaningful-test metadata on changed files but not by full-gate Detekt test-style yet. |
| `data/src/androidTest` | Not part of JVM `unitTestCheck`; device/instrumentation execution is outside the default quality gate. | Excluded by `**/androidTest/**`. | Changed files by default; all-mode covers `data/src/androidTest/**/*Test.kt` and `*Test.kts` except `src/androidTest/.../testing` support helpers. | Room/integration tests need manual policy review when touched. |
| `domain/src/test` | Included in `unitTestCheck` and `qualityCheck` via the domain JVM unit-test task. | Excluded by `**/domain/src/test/**`. | Changed files by default; all-mode covers `domain/src/test/**/*Test.kt` and `*Test.kts` except `src/test/.../testing` support helpers. | P0 business behavior tests; fake-first policy is mandatory on review even before Detekt coverage is widened. |
| `domain/src/androidTest` | No current source-set directory. | Would be excluded by `**/androidTest/**` if added. | Changed files by default if a file is added under `src/androidTest`; all-mode would also scan it because the script enumerates module-wide `*Test.kt` / `*Test.kts` files. | Adding this source set should update this matrix and gate wiring. |
| `ui-components/src/test` | Included in `unitTestCheck` and `qualityCheck` via the ui-components JVM unit-test task. | Excluded by `**/ui-components/src/test/**`. | Changed files by default; all-mode covers `ui-components/src/test/**/*Test.kt` and `*Test.kts` except `src/test/.../testing` support helpers. | Component policy tests remain review-policy enforced; Detekt migration is pending. |
| `ui-components/src/androidTest` | Not part of JVM `unitTestCheck`; device/instrumentation execution is outside the default quality gate. | Excluded by `**/androidTest/**`. | Changed files by default; all-mode covers `ui-components/src/androidTest/**/*Test.kt` and `*Test.kts` except `src/androidTest/.../testing` support helpers. | Compose/device tests need manual policy review when touched. |
| `buildSrc/src/test` | Included only if Gradle build logic test tasks are wired into the selected Gradle gate. Current `testStyleCheck` and meaningful-test all-mode do not enumerate `buildSrc`. | Not covered by `testStyleCheck`; `detektProjects` is `app`, `domain`, `data`, and `ui-components`. | Default diff-mode covered when touched; all-mode does not enumerate `buildSrc`. | Build logic tests should carry Behavior Contract metadata on review; adding all-mode coverage requires script scope expansion. |
| `quality/detekt-rules/src/test` | Covered when `:detekt-rules:test` or a gate depending on detekt-rules tests is run; not part of `testStyleCheck`. | Not covered by `testStyleCheck`; the Detekt test-style task scans product modules, not the rule module. | Default diff-mode covered when touched; all-mode does not enumerate `quality/detekt-rules`. | Rule tests are meaningful-test checked when changed, but historical all-file sweeps require script scope expansion. |
| `quality/scripts/test` | Shell smoke/contract tests, not Kotlin tests. | Not applicable. | Not applicable, except fixture trees under `quality/scripts/test/check_meaningful_tests_fixtures/*` are explicitly ignored by `check_meaningful_tests.sh`. | Shell tests should state their own Behavior Contract when they enforce policy behavior. |

## Meaningful-Test Modes

`meaningfulTestCheck` is intentionally a changed files / diff guard in normal gate runs:

- `MEANINGFUL_TEST_CHECK_MODE=staged` checks staged changes.
- `MEANINGFUL_TEST_CHECK_MODE=working-tree` checks `HEAD` plus untracked files.
- `MEANINGFUL_TEST_CHECK_MODE=ci` checks the CI base branch or `HEAD~1`.
- `MEANINGFUL_TEST_DIFF_BASE=<sha>` checks a caller-specified merge-base range.
- Default changed-file mode matches touched Kotlin tests in any module when the path is under
  `*/src/test/` or `*/src/androidTest/` and ends in `.kt` or `.kts`.
- `MEANINGFUL_TEST_CHECK_ALL=true` or `quality/scripts/check_meaningful_tests.sh --all` scans all
  app/domain/data/ui-components `*Test.kt` / `*Test.kts` files, including `androidTest` where present.
- All-mode is narrower: it enumerates only `app`, `domain`, `data`, and `ui-components`.

Risk: the default gate prevents newly touched weak tests, but it is not a historical proof that every
existing test file already has a complete Behavior Contract, Given/When/Then scenario text, TDD
proof, and Test Change Justification.

Migration plan: use all-mode for focused cleanup batches, migrate touched legacy tests opportunistically,
and keep the diff guard strict so new or modified test files cannot bypass the policy.

## Retained Exclusions

### Detekt Config Validation Exclusions

- `lomo-architecture.*`
- `lomo-architecture>.*>.*`

Risk: these are Detekt configuration-key exclusions, not source-set exclusions. If the architecture
rule-set names change, config validation might stop protecting typos in those custom keys.

Migration plan: keep these entries only while Detekt config validation needs custom rule-set key
allowlisting. Revisit when Detekt exposes a cleaner custom rule-set schema path for this project.

### Test-Style Source Exclusions

- `**/androidTest/**`

Risk: instrumentation tests can keep relaxed mocks, direct dispatcher replacement, interaction-only
assertions, or weak style when they are not touched by review. Changed androidTest files and all-mode
meaningful-test runs still check metadata, but Detekt test-style rules do not full-gate these files.

Migration plan: first make representative app/data/ui-components androidTest files pass the custom
rules locally, then replace the broad exclude with narrow fixture/helper exceptions or split rule
configuration for instrumentation-only framework seams.

- `**/data/src/test/**`

Risk: repository, sync, parser, and persistence tests are high-value P0 coverage but are not full-gate
protected by fake-first and anti-pattern Detekt rules. Changed files still receive meaningful-test
metadata checks.

Migration plan: run `dataTestStyleDetekt` without this exclude in a cleanup branch, migrate failures
by package, and remove the module-wide exclude once stateful collaborator fakes and observable
assertions are in place.

- `**/domain/src/test/**`

Risk: use-case and model contract tests are not full-gate protected by the Detekt fake-first and
assertion-style rules, even though domain behavior is the preferred first layer for new business
logic.

Migration plan: domain should be the first module-wide removal candidate because it has the fewest
Android/framework seams. Dry-run the removal, fix any legacy assertion or mock patterns, then delete
this exclude before widening data/ui-components.

- `**/ui-components/src/test/**`

Risk: component policy and rendering-policy tests are not full-gate protected by Detekt test-style
rules. This can allow interaction-only tests or implementation-token assertions to persist outside
reviewed diffs.

Migration plan: migrate non-Compose policy tests first, then decide whether Compose-specific tests
need narrow framework-seam exceptions instead of a module-wide path exclusion.

## Review Rules Until Migration Completes

- Treat this matrix as the automation boundary, not a relaxation of `AGENTS.md`.
- Touched Kotlin tests in excluded source sets still need a Behavior Contract, real TDD proof, and
  fake-first collaborators.
- Do not add new path excludes without updating this matrix and the contract test in the same change.
- Prefer narrowing exclusions after a successful targeted `testStyleCheck` or module-specific Detekt
  run; do not remove broad exclusions in this shared worktree without verifying existing failures.
