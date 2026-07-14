# Testing Coverage Matrix

This matrix is the reviewable owner map for the repository's Kotlin test-quality policy. The
policy remains broader than the currently executable checks: every touched Kotlin test must follow
`quality/testing/ai-meaningful-tests.md` and `quality/testing/ai-kotlin-test-style.md`, while the
automation below explains which source sets are full-gate enforced and which checks are changed
files / diff guards.

## Gate Semantics

| Gate | Scope | Enforcement mode | Full gate? |
| --- | --- | --- | --- |
| `quality/scripts/kotlin_fast_quality_check.sh` | Model + build + host tests only. | Toolchain orchestration for lightweight iteration. | No; intentionally omits Detekt/Lint/coverage. |
| `quality/scripts/kotlin_static_quality_check.sh` | Model + build + architecture Detekt + test-style Detekt + Android Lint + shell contracts + host tests. | Toolchain + CLI quality orchestration. | Yes for iterative static gate. |
| `quality/scripts/kotlin_quality_check.sh` | Static surface plus Compose static analysis and a single host-test flight under JaCoCo coverage (min 70%). | Full Toolchain quality parity gate. | Yes for pre-commit / pre-merge. |
| `quality/scripts/kotlin_detekt_check.sh` / `kotlin_test_style_check.sh` | Product `src/` and host `test/` roots via detekt CLI + custom rules jar. | Always-on architecture and test-style enforcement. | Yes when invoked by static/full gates. |
| `quality/scripts/check_meaningful_tests.sh` | By default collects changed test files from staged, CI, or working-tree diff depending on `MEANINGFUL_TEST_CHECK_MODE`. | Changed files / diff guard by default. `MEANINGFUL_TEST_CHECK_ALL=true` or `--all` scans all `*Test.kt` / `*Test.kts` files under `app`, `domain`, `data`, and `ui-components`, including `test@android` where present. | Partly. It is included in `quality/scripts/kotlin_quality_check.sh`, but its default repository run is diff-scoped. |
| `quality/scripts/test/check_meaningful_tests_smoke_test.sh` | Fixture-level shell smoke tests for meaningful-test diff behavior. | Executable script contract, manual unless invoked by a maintainer or targeted verification. | No. |
| `quality/scripts/test/testing_coverage_matrix_contract_test.sh` | Checks this matrix names configured Detekt excludes, diff/all meaningful-test modes, and Kotlin Toolchain gate owners. | Executable script contract, manual unless invoked by a maintainer or targeted verification. | No. |

## Source-Set Matrix

| Source set | Unit-test execution | `testStyleCheck` coverage | `meaningfulTestCheck` coverage | Notes |
| --- | --- | --- | --- | --- |
| `app/test` | Included when Kotlin Toolchain runs app host tests. | Covered by `kotlin_test_style_check.sh` on `app/test`. | Changed files by default; all-mode covers `app/test/**/*Test.kt` and `*Test.kts` except `test/.../testing` support helpers. | Primary P1 ViewModel/coordinator/UI policy tests. |
| `data/test` | Included when Kotlin Toolchain runs data host tests. | Covered by `kotlin_test_style_check.sh` on `data/test`. | Changed files by default; all-mode covers `data/test/**/*Test.kt` and `*Test.kts` except `test/.../testing` support helpers. | High-risk P0 repository/sync tests. |
| `domain/test` | Included when Kotlin Toolchain runs domain host tests. | Covered by `kotlin_test_style_check.sh` on `domain/test`. | Changed files by default; all-mode covers `domain/test/**/*Test.kt` and `*Test.kts` except `test/.../testing` support helpers. | P0 business behavior tests; fake-first policy is mandatory. |
| `ui-components/test` | Included when Kotlin Toolchain runs ui-components host tests. | Covered by `kotlin_test_style_check.sh` on `ui-components/test`. | Changed files by default; all-mode covers `ui-components/test/**/*Test.kt` and `*Test.kts` except `test/.../testing` support helpers. | Component policy tests. |
| `*/test@android` | **Removed** from the repository. Former device/instrumentation suites were deleted or rewritten as host tests under `*/test`. | N/A | N/A | Do not reintroduce permanent device-only suites without a Toolchain device gate and this matrix update. |
| `quality/detekt-rules/test` | Covered when Kotlin Toolchain runs the detekt-rules JVM tests; not part of product test-style coverage. | Not covered by product test-style checks. | Default diff-mode covered when touched; all-mode does not enumerate `quality/detekt-rules`. | Rule tests are meaningful-test checked when changed, but historical all-file sweeps require script scope expansion. |
| `quality/scripts/test` | Shell smoke/contract tests, not Kotlin tests. | Not applicable. | Not applicable, except fixture trees under `quality/scripts/test/check_meaningful_tests_fixtures/*` are explicitly ignored by `check_meaningful_tests.sh`. | Shell tests should state their own Behavior Contract when they enforce policy behavior. |

## Meaningful-Test Modes

`meaningfulTestCheck` is intentionally a changed files / diff guard in normal gate runs:

- `MEANINGFUL_TEST_CHECK_MODE=staged` checks staged changes.
- `MEANINGFUL_TEST_CHECK_MODE=working-tree` checks `HEAD` plus untracked files.
- `MEANINGFUL_TEST_CHECK_MODE=ci` checks the CI base branch or `HEAD~1`.
- `MEANINGFUL_TEST_DIFF_BASE=<sha>` checks a caller-specified merge-base range.
- Default changed-file mode matches touched Kotlin tests in any module when the path is under
  `*/test/` or `*/test@android/` and ends in `.kt` or `.kts`.
- `MEANINGFUL_TEST_CHECK_ALL=true` or `quality/scripts/check_meaningful_tests.sh --all` scans all
  app/domain/data/ui-components `*Test.kt` / `*Test.kts` files, including `test@android` where present.
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

- none for product host tests (`app/test`, `data/test`, `domain/test`, `ui-components/test` are scanned)

Former `**/test@android/**` device suites were removed. If a new instrumentation source set is added,
it must get an explicit gate owner in this matrix before merge.

- `**/data/test/**`

Risk: repository, sync, parser, and persistence tests are high-value P0 coverage but are not full-gate
protected by fake-first and anti-pattern Detekt rules. Changed files still receive meaningful-test
metadata checks.

Migration plan: run `dataTestStyleDetekt` without this exclude in a cleanup branch, migrate failures
by package, and remove the module-wide exclude once stateful collaborator fakes and observable
assertions are in place.

- `**/domain/test/**`

Risk: use-case and model contract tests are not full-gate protected by the Detekt fake-first and
assertion-style rules, even though domain behavior is the preferred first layer for new business
logic.

Migration plan: domain should be the first module-wide removal candidate because it has the fewest
Android/framework seams. Dry-run the removal, fix any legacy assertion or mock patterns, then delete
this exclude before widening data/ui-components.

- `**/ui-components/test/**`

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
