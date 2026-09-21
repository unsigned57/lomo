# Rust Tests

Read `ai-meaningful-tests.md` first. This file owns Rust test conventions, not gate orchestration.

## Layout and dependencies

Production `src/` contains no `#[cfg(test)]` modules. Put integration behavior tests in the owning
crate's `tests/`, architecture locks in `lomo-architecture-tests/tests/`, and diagnostics/benchmarks
in `examples/` or real benchmark targets. This also applies to tooling crates.

Wrap integration test functions in `#[cfg(test)] mod tests { ... }` so
`tests_outside_test_module` stays enabled. If a crate uses `autotests = false`, register its test
target explicitly. Do not put tests in a file Cargo never discovers.

Test helpers and parser dependencies belong in `dev-dependencies`; no test-only feature flags,
public production hooks or dependencies added to the production graph for tests.

## Observable contracts

Keep capability, Given/When/Then scenarios, outcomes, RED/GREEN evidence and exclusions in the test
or an adjacent contract. Assert plans, returned error variants/messages, bytes, persisted artifacts,
state transitions, dependency boundaries or command results. Preserve existing sync v1 golden
vectors. Source-token assertions do not substitute for runtime behavior.

For sync, cover malformed input, golden protocol bytes, error classification, actions and pending
counts. For tooling, test parsers and command outcomes; durable source/dependency constraints belong
in the architecture crate. Include legal controls for every rejection policy.

Use deterministic fake state, seeded corpora and explicit failure injection. A concurrency test
needs a controlled ordering; wall-clock sleeping is not an ordering guarantee. Tests must surface
ignored Results and must not add first-party unsafe, lint allows or baselines.

## Commands

Run from the repository root:

```bash
cargo test -p <crate> --test <contract> --locked
cargo clippy -p <crate> --all-targets --locked -- -D warnings
```

Record a real narrow RED before implementation and GREEN after it. Package/handoff gates follow
`AGENTS.md` and `quality/README.md`. Do not commit generated Kotlin or native binaries as byte oracles.
