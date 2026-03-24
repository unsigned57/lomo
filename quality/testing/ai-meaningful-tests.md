# AI Meaningful Tests

This repository does not treat raw line coverage as the goal. The goal is to raise coverage by proving behavior in code that carries product, sync, or state risk.

## Coverage Ratchet

`qualityCheck` reads the merged Kover threshold from `COVERAGE_GATE_STAGE` or `-PcoverageGateStage=...`.

- `baseline` -> `21%`
- `m1` -> `35%`
- `m2` -> `50%`
- `m3` -> `65%`
- `m4` -> `80%`

Use `./gradlew coverageGatePlan` to print the current stage plan.

## Target Order

- `P0`: `domain` use cases and `data` sync or repository orchestration that decides ordering, fallback, conflict handling, cancellation, DB or file reconciliation, or error mapping.
- `P1`: `app` ViewModel or coordinator state machines with user-visible state transitions or command dispatch.
- `P2`: utility classes that normalize, parse, or transform user data in a way that can regress behavior.

Avoid spending AI time on:

- pure Compose rendering and previews
- activity or navigation wiring with no branch-heavy behavior
- generated code, DI glue, and trivial delegation
- tests that only mirror implementation details or mocked call counts without an externally visible outcome

If a file is mostly rendering or wiring but hides real branching, extract the branching logic first and test the extracted unit.

## Required AI Preflight

Before AI writes, edits, or proposes any test file in this repository, it must read this document first.

The AI response must begin with a short preflight summary covering:

1. target class and owning layer
2. why the target is meaningful under the `P0` / `P1` / `P2` priority model
3. what will not be tested

If the AI cannot explain those three points after reading this file, it must stop and ask for a better target instead of writing tests.

## Required AI Workflow

After the required preflight, and before AI writes a new or modified test file, it must produce a scenario matrix for the target class:

1. Happy path
2. Boundary path
3. Failure, cancellation, or conflict path
4. Must-not-happen path

Every generated test must prove one of the following:

- a business rule
- a state transition
- a side-effect ordering rule
- an error classification or propagation rule
- a parser or formatter contract with observable output

Reject the test if it only proves:

- that a mock was called without any meaningful outcome
- that private implementation detail exists
- that a Compose tree contains a constant without a product contract
- that a trivial getter or setter returns the value just assigned
- that the AI skipped the required preflight and went straight to code

## Required Contract Metadata

Every changed test file must contain either a file header comment or an adjacent markdown contract file.

Preferred in-file header format:

```kotlin
/*
 * Test Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Behavior focus: refresh ordering, failure propagation, force vs best-effort sync.
 * - Observable outcomes: thrown exception type, refresh execution, collaborator call ordering.
 * - Excludes: repository internals, transport implementation details, UI rendering.
 */
```

Accepted adjacent file format: `MyTest.contract.md`

Required sections:

- `Test Contract`
- `Observable outcomes`
- `Excludes`

CI and pre-commit enforce this for changed test files.

## Review Standard

When reviewing AI-authored tests, reject the change if any of these are true:

- the AI did not show evidence that it read this document before writing tests
- the assertion could stay green after breaking the user-facing behavior
- the test only validates current implementation structure
- no failure or edge-path coverage was added for a branch-heavy target
- the target class is low-signal and logic extraction would have been the better move
