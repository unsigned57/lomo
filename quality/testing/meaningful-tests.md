# Meaningful Tests

A test earns its place by protecting behavior that would be costly to lose. Line coverage, process
rituals, and internal implementation shape are not reasons to write one. Production authority lives
in [ARCHITECTURE.md](../../ARCHITECTURE.md); gates and command evidence live in
[Quality](../README.md); language conventions live in [Kotlin](kotlin-test-style.md) and
[Rust](rust-test-style.md).

## Core principles

- **Test at the owner**: assert durable rules in the owning Rust crate; test Kotlin adapters where
  Kotlin adds failure mapping, state transitions, or platform lifecycle behavior.
- **Black-box observable outcomes**: assert returned values, emitted state/event sequences, persisted
  bytes, typed errors, and ordering contracts — never private helper existence, constants, trivial
  delegation, getters, pure rendering, or source tokens (except architecture/policy tests whose real
  input is source/manifests/CLI output).
- **Determinism**: use fake clocks, controlled coroutine/channel signals, barriers, and seeded inputs;
  never depend on wall-clock sleeps (`Thread.sleep`) or ambient external state.
- **Hermetic isolation**: use temporary directories (`tempfile::tempdir()`, `Files.createTempDirectory`)
  and `LOMO_REPORTS_DIR` / `LOMO_GENERATED_ROOT` overrides; tests must never write artifacts into the
  repository tree or shared `reports/`.

## Decide what the change owes

Choose evidence proportional to the consequence that could change. Use the first applicable row:

| Change | Examples | Evidence |
| --- | --- | --- |
| Changes a critical guarantee | Durable data, on-disk/wire compatibility, deletion identity, sync/recovery, credentials/security, concurrency or resource bounds with material consequences | Test first at the owner: reuse an existing scenario or add the smallest failing test, observe the intended failure, then verify the fix |
| Changes consequential behavior or tricky logic | User-visible state transitions, date/locale arithmetic, diffing, paging, gesture geometry | Exercise via an existing scenario or direct reproduction; extend the owning spec only if a meaningful regression would otherwise be missed |
| Routine or mechanical change | Layout, copy, tokens, motion, theming, logging, simple wiring, trivial delegation, behavior-preserving refactor | Focused inspection, compile check, smoke scenario, or visual check when it establishes the result |

For validator/policy rules, always pair forbidden-input checks with legal controls, and test the real
runner when registration, configuration, or suppression could disable the rule. If test-first
evidence is unavailable, record the actual observation and gap; compilation failure, zero
discovered tests, and already-passing runs do not establish behavioral failure.

## Placement and clarity

- Extend the existing spec/target covering that unit; add a new test file only for an uncovered unit.
- Do not extract a one-line rule, widen visibility, or add production hooks solely to test something.
- Name tests as clear scenarios (*given a state, when an action, then an observable outcome*).
  Comment only what the name cannot express (e.g., pinned bug or excluded boundary).
- When production code uses an intentional default, capability degradation, or
  `// behavior-contract:` exception, back it with a test asserting that allowed behavior.
- Do **not** add ceremonial `Behavior Contract`, `TDD proof`, or `Test Change Justification` docblock
  headers to test files, and do **not** create standalone `.contract.md` sidecar files. Put TDD
  red/green proof and change rationale in the commit message, PR description, or task handoff.
- For xtask evidence, inspect JSON `status`, task `results`, and scope (`--plan` is not a test run).
  Never pad tests to satisfy coverage floors; report coverage gaps honestly.

## Audit, update and retire tests

1. Audit one owner or journey at a time using `cargo metadata --no-deps --locked --format-version=1`,
   Kotlin module test roots, or quality script test directories, prioritizing slow or redundant targets.
2. Shortlist tests that only check mock delegation, private constants, source-string proxies for
   product behavior, finished migrations, or duplicated assertions across layers.
3. Keep the clearest assertion of each important outcome at the owning layer, folding useful variants
   into it. Retire assertions with no meaningful consequence or a named surviving check (verify disputed
   coverage by temporarily reintroducing the regression if unsure).
4. **Update assertions deliberately**: when an existing test or golden vector fails, suspect the
   implementation first. Change an assertion only for an intentional domain contract change, a
   factually wrong assertion, nondeterminism, or a mechanical shape migration — and state why in the
   commit/PR.
5. **Delete tests that protect nothing**: dead callers (delete the dead code too), trivial getters/
   constants/defaults, duplicate checks already asserted at the owner, finished-migration guards, or
   wall-clock sleep tests. Never retire golden vectors, real bug regressions, or the sole test of
   non-trivial logic — rewrite clumsy tests instead.
6. After cleanup, run the surviving owning suite and any cited covering scenarios.
