# Meaningful Tests

A test earns its place by protecting behavior that would be costly to lose. Line coverage, a
process step and implementation shape are not reasons to write one. Production authority remains in
[ARCHITECTURE.md](../../ARCHITECTURE.md); command output and gates remain in
[Quality Contract](../README.md). After this file, read only the relevant
[Kotlin](ai-kotlin-test-style.md) or [Rust](ai-rust-test-style.md) conventions.

## Decide what the change owes

Classify a change by the code it touches, not by its size: a one-line codec change is tier 1 and
a two-hundred-line layout rework is tier 3.

| Tier | The change touches | It owes |
| --- | --- | --- |
| 1 | Durable data and its guarantees: parsing, serialization, persistence, transactions, migration, sync, conflict and recovery, credentials and other security boundaries, ordering, cancellation, concurrency, failure classification, cross-language wire contracts and resource bounds | A test written first. Add the narrowest scenario, see it fail for the reason the change exists, then implement |
| 2 | A user-visible state machine with real branching (ViewModel, coordinator, TEA update), or an algorithm a careful reader could get wrong (date and locale math, text layout, diffing, gesture and scroll geometry) | Exercise the affected path using an existing scenario if its assertion would catch the regression. Extend the owning spec when coverage is missing. Test-first is optional |
| 3 | Everything else: layout, copy, tokens, motion, theming, wiring, delegation, logging, build and tooling configuration, behavior-preserving refactors | No new test. Keep the existing suite green and show the result directly: a screenshot or UI flow for something visible, command output for tooling |

A bug fix takes the tier of the code that was wrong. For tier 1, reuse an existing test if it
reproduces the failure. An unrelated compilation error, zero discovered tests and a green run
are not failure observations. For tier 2, an existing test or UI flow that exercises and asserts
the corrected behavior can supply the evidence.

Between two tiers, take the stricter one for data a user cannot recreate and the lighter one for
anything they can see and retry. A lint, policy check or validator is tier 1 for the inputs it
judges: show a forbidden input it rejects and a legal one it accepts.

## Put the test where the behavior lives

- Extend the spec that already covers the unit. One more scenario is one more test case, not one
  more file; create a test file only for a unit that has none.
- Test at the owner. Rust owns durable rules, so assert them in the owning crate. A Kotlin test
  that reaches a Rust rule through a fake returning the answer proves the fake.
- Do not extract a one-line rule, widen visibility or add a production hook so that a test has
  something to call, and do not test code that no production path calls: delete that code.

## Test the consequence

Test returned values, emitted sequences, persisted bytes, classified failures and explicit ordering
contracts. Reach new branches, or prove they are unreachable without them. Keep deterministic
failure, boundary and cancellation cases.

Do not write tests for trivial delegation, DI glue, constants, tokens, defaults, getters or pure
rendering. Do not assert private helper existence, a value just assigned, mock call counts alone,
or source tokens as a proxy for product behavior.
Architecture and quality tests may inspect dependency graphs, parsed source, manifests and CLI
results: those are their actual inputs and outputs. Pair forbidden-input tests with legal
controls; test the real runner when registration, configuration, exclusion or suppression can
disable a rule.

## State the evidence once

Test names carry the scenario: given a state, when an action, then an observable result. A test
file needs no header. Comment only what names cannot say, such as the bug a regression pins or a
boundary the test leaves out.

A Behavior Contract is that statement written out at the top of a test: capability, scenarios,
observable outcomes and exclusions. Write one where another contract asks for it: a production
`// behavior-contract:` exception, an intentional default or a capability degradation must be
backed by a test that states the allowed behavior. Elsewhere it is optional. Existing headers
may be shortened or dropped when a file is edited; no file needs an edit only for that.

Test-first evidence belongs where the change is reviewed: the commit, pull request or
handoff names the command, the failing assertion before the change and the passing run after it.
Do not copy that record into the test file. It describes one moment and goes stale.

For xtask evidence, inspect the JSON status, task results and scope; a successful `--plan` is not
a test run. A passing lint or a coverage number does not prove semantic correctness. The coverage
floors in the [Quality Contract](../README.md) watch the codebase as a whole: never write a test
whose purpose is to execute lines, and when a floor is the only failing check, report it instead
of padding.

## Screen the existing suite

1. Inventory Rust targets from the Cargo manifests, Kotlin specs from module test roots, and
   script, instrumentation and UI tests from their runners. Keep support files and fixtures
   distinct from executable cases.
2. Start with the owning suite you are changing. Shortlist assertions on getters, mock calls
   alone, source text for product behavior, completed migrations and overlapping scenarios.
   If timings are available, review expensive candidates first.
3. For each candidate, name one realistic regression the assertion would catch and check the
   owning layer for another test of the same consequence. Delete only after this review. If the
   input matters but duplicates another spec, move it into the owning spec's scenario table.
   Keep uncertain cases for a later review.
4. Run the remaining owning suite and record which consequence survives and where. Where a
   behavior lock is disputed, observe the retained test fail against the corresponding
   regression. A green suite alone establishes only that the remaining assertions pass.

## Change and retire tests deliberately

Existing assertions and sync v1 golden vectors are behavior locks. When an old test fails after a
change, suspect the implementation first. Change an assertion only when the domain contract
changed, the assertion was factually wrong, the test is nondeterministic or a mechanical
migration needs a different shape, and say which in the commit or handoff.

Delete a test that protects nothing, in the change that makes that true or whenever you find one:

- its subject has no production caller: delete the dead code with it;
- it only proves delegation, a constant, a default or a getter;
- another test at the owning layer asserts the same consequence: name that test;
- it locks a finished migration, or the absence of an identifier that cannot come back;
- it asserts wall-clock time or sleeps in place of a contract.

Never retire golden vectors, fixture-backed format tests, a regression that pins a real bug, or
the only test of non-trivial logic. Rewrite a clumsy one instead.
