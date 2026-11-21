# Rust Tests

Use [Meaningful tests](meaningful-tests.md) to decide what evidence a change needs.

## Layout and dependencies

- **No `#[cfg(test)]` in `src/`**: place behavioral integration tests in the owning crate's `tests/`,
  cross-crate boundary locks in `crates/lomo-architecture-tests/tests/`, and diagnostics/probes in
  `examples/` or benchmark targets. This also applies to tooling crates.
- Wrap integration tests in `#[cfg(test)] mod tests { ... }` so `tests_outside_test_module` passes.
  If a crate sets `autotests = false`, declare every test target in `Cargo.toml`.
- Keep test helpers and parser fixtures in `[dev-dependencies]`. Never add test-only feature flags,
  production test hooks, or test dependencies to the production graph.
- Add scenarios to the existing capability target when testing the same entry point or invariant
  (use nested modules or clear test names). Each integration file compiles a separate binary — add a
  new target only for distinct build conditions, runner isolation, or a separate capability, and verify
  compile/link vs runtime tradeoffs when consolidating targets.

## Conventions and isolation

- Assert plans, typed error variants, bytes, persisted artifacts, state transitions, dependency graphs,
  or CLI JSON/exit codes — never source tokens as a proxy for product behavior.
- Keep each case focused on one guarantee with setup and assertions inline, reusing small fixture
  builders. Use input tables for equivalent cases, and seeded property tests for broad parser/codec laws.
- Isolate filesystem and report side effects using `tempfile::tempdir()` and explicit env overrides
  (`LOMO_GENERATED_ROOT`, `LOMO_REPORTS_DIR`, `CARGO_TARGET_DIR`) so tests never mutate the repo tree.
- Use deterministic fake state, seeded corpora, explicit failure injection, and controlled ordering
  (failpoints, barriers, channels) for concurrency tests. Measure throughput/latency in the owning
  benchmark/example target.
- Surface ignored `Result` values (`?` or explicit match), and never add first-party `unsafe`, lint
  `#[allow(...)]` waivers, or committed native/Kotlin binaries as byte oracles.

## Focused local runs

- Single target/case: `cargo test -p <crate> --locked --test <target> <case-filter>`
- Rustdoc examples: `cargo test -p <crate> --locked --doc`
- Broader crate/workspace runs: use `just dev --scope <crate>` or nextest per
  [Quality](../README.md#gate-selection-and-evidence).
