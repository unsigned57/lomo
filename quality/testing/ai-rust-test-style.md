# AI Rust Test Style

Read this after `ai-meaningful-tests.md` when writing or editing Rust tests.

## Behavior Contract

State the capability, Given/When/Then scenarios, observable outcomes, TDD proof, and exclusions in
the test file or an adjacent contract document. Preserve existing sync v1 golden vectors and tests
as behavior locks unless the user explicitly changes the protocol.

## Physical Separation

- Production files under `src/` contain no `#[cfg(test)]` modules.
- Integration behavior tests live under each crate's `tests/` directory.
- Architecture tests live only in `lomo-architecture-tests/tests`.
- Benchmarks and diagnostics live under `examples/` or a real benchmark target, not production APIs.

Integration test files wrap tests in an explicit `#[cfg(test)] mod tests { ... }` so the strict
workspace `tests_outside_test_module` rule remains active without exceptions.

## Assertions

Assert observable bytes, plans, error variants/messages, filesystem artifacts, dependency
boundaries, or command results. Do not assert private helper existence, source tokens as a proxy for
product behavior, or interactions without an outcome.

For sync changes, cover malformed boundary input, golden request/plan bytes, error classification,
actions, and pending-change count. For xtask changes, prefer architecture tests for durable
structure and command-level tests for parsers/state transitions; do not make tooling a production
dependency.

## RED/GREEN

Run the narrowest failing test first and record the actual failure. Examples:

```bash
cd rust
cargo test -p lomo-sync --locked
cargo test -p lomo-architecture-tests --test architecture --locked
```

After GREEN, run strict Clippy and then `just check`. Final handoff requires `just ci`.

## Prohibited Tails

- no test-only feature flag on production behavior
- no duplicate facade or compatibility crate
- no ignored Result/error fallback
- no first-party unsafe code
- no lint allow or baseline to admit new debt
- no generated Kotlin or `.so` fixture committed as a byte-for-byte oracle
