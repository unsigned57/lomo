# Meaningful Tests

Tests protect observable behavior, not line coverage or implementation shape. Read this contract
once for a test task, then the relevant language guide. Production authority remains in
[ARCHITECTURE.md](../../ARCHITECTURE.md); command output and gates remain in
[Quality Contract](../README.md). After this file, read only the relevant
[Kotlin](ai-kotlin-test-style.md) or [Rust](ai-rust-test-style.md) conventions.

## Contract and RED/GREEN

For a feature, bug fix or change to executable behavior or constraints:

1. State the capability, Given/When/Then scenarios, outcomes and exclusions.
2. Add the narrowest regression test and run it before changing the implementation.
3. Record the actual failing assertion/exception and why it exposes the missing behavior.
4. Implement GREEN, then refactor under GREEN. Run the changed surface before handoff.

Compilation failure unrelated to the scenario, zero discovered tests and a green first run do not
prove a regression. Pure documentation, mechanical edits and behavior-preserving refactors do not
need an invented RED; use content checks or existing regression evidence. A pure test migration may
record `Not applicable - test-only migration; no production change within the declared scope.`
Do not use that explanation for changed behavior or production branches covered by the test.
For a behavior-preserving refactor, record `TDD proof: Regression preservation - <before/after
test commands and observed outcomes>` instead. This provides regression evidence without claiming
that production files were unchanged or inventing a new failing behavior.

Every changed Kotlin test file needs an in-file Behavior Contract or its adjacent
`<TestFile>.contract.md`; keep the Behavior Contract in one place. A helper without policy is not
required to invent a separate test or contract. Contract-only edits are checked through their test.
Rust test contracts use the same structure. Keep the contract specific and short:

```text
Behavior Contract:
Capability: <observable capability>; owning layer: <owner>; priority: P0/P1/P2.
Scenarios:
- Given <state/input>, when <action>, then <observable result>.
- Given <failure/cancellation/conflict>, when <action>, then <explicit result>.
Observable outcomes: <returned value, emitted state, error, bytes or durable state>.
TDD proof: <real RED command/failure and GREEN command, or a link to their evidence>.
Excludes: <boundaries this test does not claim to prove>.
```

In a mixed change set, establish a test's production scope when claiming its behavior is unaffected:

```text
Production scope: apps/android/domain/src/usecase/LoadEditableMemoUseCase.kt
```

Repeat the field for each relevant production file, including collaborators whose behavior the
scenarios cover. Use exact repository-relative `src/` paths, not globs, directories or traversal.
Place these fields in the Behavior Contract. A nonexistent path is invalid unless it is part of
the current production diff (for example, a deletion). If a declared file changed, the test-only
`Not applicable` claim is rejected. Without a declared scope, the checker cannot establish separation
from a mixed production diff; provide scope or the applicable behavior/regression evidence instead.
Scope declarations are reviewable evidence, not a
proof of semantic completeness; do not omit a changed collaborator to evade a behavior lock.

Replace legacy `Test Contract` headers when touching a test, except in a documented mechanical
format pass. Avoid duplicate generic headers that say only “tests pass”.

## Test the consequence

Prioritize correctness of transactions, parsing, recovery, ordering, conflict handling and
cancellation (P0), user-visible state machines (P1), and data transformations (P2).
Test returned values, emitted sequences, persisted bytes, classified failures and explicit ordering
contracts. Reach new branches, or prove they are unreachable without them.

Do not add tests for trivial delegation, DI glue or pure rendering unless they contain policy that
can regress. Do not assert private helper existence, a value just assigned to a getter, mock call
counts alone, or source tokens as a proxy for product behavior.
Architecture/quality tests may inspect dependency graphs, parsed source, manifests and CLI results:
those are their actual inputs and outputs. Pair forbidden-input tests with legal controls; test the
real runner when registration, configuration, exclusion or suppression can disable a rule.

## Preserve behavior locks

Keep existing assertions and sync v1 golden vectors unless the domain contract changed, an assertion
is factually wrong, a test is nondeterministic, or a mechanical migration requires a shape change.
When an old test fails, investigate the implementation first.

If changing an existing assertion, record **Test Change Justification:** in the test or its adjacent
`<TestFile>.contract.md`; the handoff may link to it but cannot replace machine-readable evidence.
Use these exact labels: `Reason category:`, `Old behavior/assertion being replaced:`,
`Why old assertion is no longer correct:`, `Coverage preserved by:`, and
`Why this is not fitting the test to the implementation:`. New tests normally accompany an existing
regression lock.

The Kotlin checker reads the in-file contract within the first 200 lines, an adjacent contract
within the first 220 lines, and scope/justification fields within the first 250 lines. It accepts
justification in either location even when the Behavior Contract is in the other. It conservatively
requires justification for a modified existing test with changed or unspecified production scope;
it does not infer assertion semantics or code dependencies from filenames. Pure renames and test
support files retain their mechanical/support treatment. Unrelated worktree changes do not invalidate
a documented test-only claim whose complete production scope is unchanged.

For xtask evidence, inspect the JSON status, task results and scope; a successful `--plan` is not
a test run. Capture command, failing/passing observation and report/log paths when present.
A successful lint or coverage number cannot prove semantic correctness. Keep deterministic failure,
boundary and cancellation cases. Static checks enforce repeatable conventions; they do not replace
these observable contracts.
