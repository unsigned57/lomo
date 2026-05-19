# AI Meaningful Tests

This repository does not treat raw line coverage as the goal. Tests must prove behavior that can regress product, sync, state, persistence, parsing, or error handling.

The default AI workflow is **BDD + TDD**:

- BDD states the externally visible behavior before code changes.
- TDD proves that behavior with a red-green-refactor loop.
- Static checks enforce the repeatable shape; this document explains the intent.

## Target Order

- `P0`: `domain` use cases and `data` sync or repository orchestration that decides ordering, fallback, conflict handling, cancellation, DB/file reconciliation, or error mapping.
- `P1`: `app` ViewModel or coordinator state machines with user-visible state transitions or command dispatch.
- `P2`: utility classes that normalize, parse, or transform user data in a way that can regress behavior.

Avoid spending AI time on pure rendering/previews, DI glue, generated code, trivial delegation, or tests that only mirror implementation details. If a rendering or wiring file hides real branching, extract the policy first and test that unit.

## BDD + TDD Loop

For features, bug fixes, behavior changes, and contract changes:

1. Write the `Behavior Contract` before production edits.
2. Add or update the test for the relevant Given/When/Then scenario.
3. Run the narrowest relevant test and confirm RED.
4. Implement the minimum production change to turn the scenario GREEN.
5. Refactor only while the scenario remains GREEN.

Red proof must include the command, the failing assertion/exception/symptom, and why that failure proves the behavior is missing or broken. A green first run means the test is too weak.

For pure test migrations that do not change production behavior, keep production files untouched and record `Not applicable - test-only migration; no production change.` in the TDD proof.

## Behavior Contract

Every changed Kotlin test file must contain an in-file header comment or adjacent `*.contract.md` file with these sections:

```kotlin
/*
 * Behavior Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: refresh local memo state after remote sync decisions.
 *
 * Scenarios:
 * - Given remote sync succeeds, when rebuild runs, then local memo state is refreshed.
 * - Given no remote work is needed, when rebuild runs, then local memo state is still refreshed.
 * - Given sync fails, when rebuild runs, then the failure is observable and refresh behavior is explicit.
 * - Given an unreachable sync branch would skip refresh silently, when tests run, then the branch is exposed.
 *
 * Observable outcomes:
 * - returned value, thrown exception type, emitted state/event, persisted state, or explicit ordering contract.
 *
 * TDD proof:
 * - Fails before the fix when refresh is skipped after a successful sync.
 *
 * Excludes:
 * - repository internals, transport implementation details, UI rendering.
 */
```

Required sections:

- `Behavior Contract`
- `Scenarios`
- `Observable outcomes`
- `TDD proof`
- `Excludes`

Existing legacy `Test Contract` headers are migration debt. When a test file is touched, upgrade the header to `Behavior Contract` unless the change is a narrow mechanical format pass already covered by a dedicated migration.

## Meaningful Assertions

Every generated or touched test must prove at least one observable outcome:

- business rule
- state transition
- side-effect ordering rule
- error classification or propagation rule
- parser/formatter result
- persisted file, database, or preference state

Reject tests that only prove a private helper exists, a mock was called, a constant is present in Compose output, a getter returns the value just assigned, or a Kotlin source token appears in a file.

When production code adds or reshapes branching (`if`, `when`, `catch`, Elvis fallback, early return), the tests must prove that the branch is reachable or that removing it preserves reachable behavior.

## Locked Existing Tests

Pre-existing tests are behavior locks by default.

- Prefer adding a companion behavior test over rewriting an old regression test.
- When an old test fails after a production edit, assume the implementation is wrong first.
- Do not delete, weaken, or narrow old assertions unless the product/domain contract changed, the old assertion is factually wrong, the old test is nondeterministic, or a mechanical refactor requires a shape change.

If a touched test changes an existing assertion or scenario, include a `Test Change Justification` in the response or adjacent note:

- `Reason category`
- `Old behavior/assertion being replaced`
- `Why old assertion is no longer correct`
- `Coverage preserved by`
- `Why this is not fitting the test to the implementation`

## Automation Boundary

Static checks should carry the detailed anti-pattern burden. Current and planned checks cover:

- missing behavior contract metadata
- production changes with `TDD proof: Not applicable`
- multiple Kotest `init` blocks
- forbidden JUnit/Mockito/AssertK/Strikt imports
- direct `Dispatchers.setMain/resetMain`
- source-string behavior tests outside architecture-boundary exceptions
- `mockk(relaxed = true)` and stateful collaborators mocked instead of faked
- interaction-only tests with no observable assertion

When a static check fails, fix the code or test shape. Do not suppress the rule.

## Samples

Use the samples for shape, not as extra required reading:

- `quality/testing/samples/usecase-golden-sample.md`
- `quality/testing/samples/viewmodel-golden-sample.md`
- `quality/testing/samples/property-based-golden-sample.md`

`qualityCheck` enforces the repository coverage floor and quality gates. The meaningful-test policy decides whether the coverage is worth having.
