# AI Meaningful Tests

This repository does not treat raw line coverage as the goal. The goal is to raise coverage by proving behavior in code that carries product, sync, or state risk.

Default AI workflow is test-first. When a task adds a feature, changes production behavior, fixes a bug, or tightens a contract, the AI must write or update the test before touching production code and must prove the test is red before the fix.

## Coverage Ratchet

`qualityCheck` enforces a fixed merged Kover minimum of `70%`.

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

## Default Test-First Policy

- For new features, bug fixes, and contract changes, AI must write or modify the relevant test before related production code.
- For new features, bug fixes, and contract changes, AI must establish red proof with the narrowest relevant command before the production fix.
- Red proof must include:
  - the command that ran
  - the failing assertion, exception, or observable symptom
  - why the failure proves the target behavior is currently broken or missing
- If the request is truly test-only and no production code should change, the AI must state that explicitly and keep production files untouched.

## Locked Existing Tests Policy

Pre-existing tests are behavior locks by default, not disposable scaffolding.

- Prefer adding a new reproducer or contract test over rewriting an old test that already protects adjacent behavior.
- When an existing test fails after a production edit, assume the implementation is wrong first. Do not rewrite the test to match the implementation by default.
- AI must not:
  - delete an existing failing test to unblock a change
  - weaken assertions to broader or vaguer outcomes without proving the contract changed
  - remove boundary, failure, cancellation, or conflict coverage that previously existed
  - replace business-result assertions with collaborator-call-only assertions
  - change test inputs purely to avoid the scenario the old test covered
  - convert a behavior-changing task to `Red phase: Not applicable`
- AI may modify an existing test only when at least one of these is true:
  - the product or domain contract explicitly changed
  - the old assertion is factually incorrect
  - the old test is nondeterministic or coupled to an invalid environment assumption
  - the production code is being mechanically refactored with no behavior change and the test shape must follow
- If AI modifies an existing test, it must emit a `Test Change Justification` that includes:
  - reason category
  - the old behavior or assertion being replaced
  - why the old assertion is no longer correct
  - which retained or new test preserves the original risk coverage
  - why this is not “changing the test to fit the implementation”
- For bug fixes and contract changes, if a historical regression test already exists, keep it whenever possible. Add a new test for the new contract instead of overwriting the old risk boundary.

## Required AI Preflight

Before AI writes, edits, or proposes any test file in this repository, it must read this document first.

The AI response must begin with a short preflight summary covering:

1. target class and owning layer
2. why the target is meaningful under the `P0` / `P1` / `P2` priority model
3. what will not be tested
4. whether the task is test-only or will require a production change and, if so, where red proof will come from
5. whether any existing tests are likely contract locks that must be preserved unchanged
6. whether any new or changed production branch needs explicit reachability proof

If the AI cannot explain those points after reading this file, it must stop and ask for a better target instead of writing tests.

## Required AI Workflow

After the required preflight, and before AI writes a new or modified test file, it must produce a scenario matrix for the target class:

1. Happy path
2. Boundary path
3. Failure, cancellation, or conflict path
4. Must-not-happen path

After the scenario matrix, AI must perform an existing-test impact check:

1. which current tests already protect the same or adjacent behavior
2. whether each test will stay unchanged, gain a companion test, or require justified correction
3. whether any planned test edit risks weakening an existing contract
4. whether `Test Change Justification` is required

After the scenario matrix, follow this sequence:

1. Write or modify the reproducer test before any related production edit.
2. Add the required `Test Contract` metadata, including `Red phase`.
3. If changing an existing test, write `Test Change Justification` before editing it and prefer adding a companion test first.
4. If the task requires a production change:
   - run the narrowest relevant test task
   - capture the red-phase command and failure
   - stop and tighten the test if it does not fail for the expected reason
5. Only after red proof may AI modify production code.
6. Re-run the reproducer to green, then broaden validation if the change surface justifies it.
7. If no production change is required, state that the task is a test-only coverage lock-in and do not edit production code.

When a production change adds or reshapes branching (`if`, `when`, `catch`, Elvis fallback, early-return guard), the red proof must show why the branch is reachable or why removing it preserves reachable behavior. Do not accept tests that merely compile the branch or assert collaborator calls without proving an observable outcome.

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
- that the AI changed production code before establishing red proof for a behavior-changing task
- that a newly added production branch has no scenario proving it is reachable
- that the red phase is omitted, hand-waved, or marked not applicable even though production behavior changed
- that an old failing test was deleted or weakened so the new implementation could pass
- that a regression test was rewritten into a happy-path test without preserving the original risk boundary

## Required Contract Metadata

Every changed test file must contain either a file header comment or an adjacent markdown contract file.

Preferred in-file header format:

```kotlin
/*
 * Test Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Behavior focus: refresh ordering, failure propagation, force vs best-effort sync.
 * - Observable outcomes: thrown exception type, refresh execution, collaborator call ordering.
 * - Red phase: Fails before the fix when refresh is skipped after a sync exception.
 * - Excludes: repository internals, transport implementation details, UI rendering.
 */
```

Accepted adjacent file format: `MyTest.contract.md`

Required sections:

- `Test Contract`
- `Observable outcomes`
- `Red phase`
- `Excludes`

Use `Red phase` to record one of:

- how the test fails before the fix for behavior-changing work
- `Not applicable - test-only coverage addition; no production change.`

When AI edits an existing test file for any reason other than adding a new independent scenario, the response must also include a `Test Change Justification` section. The section may live in the response or in an adjacent markdown note, but it must contain:

- `Reason category`
- `Old behavior/assertion being replaced`
- `Why old assertion is no longer correct`
- `Coverage preserved by`
- `Why this is not fitting the test to the implementation`

CI and pre-commit currently enforce the base contract metadata sections for changed test files. `Test Change Justification` is still a repository policy requirement even when it is not yet machine-enforced.

## Review Standard

When reviewing AI-authored tests, reject the change if any of these are true:

- the AI did not show evidence that it read this document before writing tests
- the assertion could stay green after breaking the user-facing behavior
- the test could stay green before the claimed fix for a behavior-changing task
- the test only validates current implementation structure
- no failure or edge-path coverage was added for a branch-heavy target
- the red phase is undocumented or unconvincing for work that changed behavior
- the target class is low-signal and logic extraction would have been the better move
- an existing test was modified without a convincing `Test Change Justification`
- a behavior change was expressed only by rewriting old tests instead of adding or preserving an independent contract test
