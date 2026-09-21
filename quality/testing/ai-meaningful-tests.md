# Meaningful Tests

Tests protect observable behavior, not line coverage or implementation shape. Read this contract
once for a test task, then the relevant language guide. Production authority remains in
`ARCHITECTURE.md`; commands and gate contents remain in `quality/README.md`.

## Contract and RED/GREEN

For a feature, bug fix or contract change:

1. State the capability, Given/When/Then scenarios, outcomes and exclusions.
2. Add the narrowest regression test and run it before changing the implementation.
3. Record the actual failing assertion/exception and why it exposes the missing behavior.
4. Implement GREEN, then refactor under GREEN. Run the changed surface before handoff.

Compilation failure unrelated to the scenario, zero discovered tests and a green first run do not
prove a regression. A pure test migration without production changes may record
`Not applicable - test-only migration; no production change.` Do not use that explanation for
changed behavior or changed production branches.

Every changed Kotlin test file needs an in-file Behavior Contract or an adjacent `*.contract.md`.
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

If changing an existing assertion, record **Test Change Justification** in the test, an adjacent
note or the handoff. Use these exact labels: `Reason category:`, `Old behavior/assertion being replaced:`,
`Why old assertion is no longer correct:`, `Coverage preserved by:`, and
`Why this is not fitting the test to the implementation:`. New tests normally accompany an existing
regression lock.

A successful lint or coverage number cannot prove semantic correctness. Keep deterministic failure,
boundary and cancellation cases. Static checks enforce repeatable conventions; they do not replace
these observable contracts.
