# Documentation map

This is a navigation index, not another source of task rules. Each file below owns its named subject.

## Agent and engineering contracts

| Document | Owns |
| --- | --- |
| [AGENTS](../AGENTS.md) | Task scope, autonomy, invariants, workspace hygiene, and completion |
| [Architecture](../ARCHITECTURE.md) | Module authority, dependency boundaries, and source layout |
| [Quality](../quality/README.md) | Command protocol, gate selection, build inputs, and failure triage |
| [Project layout](project-layout.md) | Source directories, build artifacts, caches, and tool-owned `reports/` |
| [Dependency selection](../quality/dependencies.md) | Library research, version changes, and custom-code justification |
| [Dependency capabilities](../quality/dependency-capabilities.toml) | Version-independent capability catalogue consumed by architecture checks |
| [Meaningful tests](../quality/testing/meaningful-tests.md) | Evidence selection by regression risk, hermeticity, and test retirement |
| [Kotlin tests](../quality/testing/kotlin-test-style.md) | Kotest/MockK/Coroutine stack, isolation, and focused Kotlin runs |
| [Rust tests](../quality/testing/rust-test-style.md) | Rust test layout, dependencies, isolation, and execution |
| [UDF contract](../quality/udf-contract.md) | Kotlin presentation state, events, and bounded exceptions |
| [Android UI test](../quality/android-uitest.md) | Device-free Maestro flows, `redroid` container, `uishot`, and LAN journeys |
| [Android performance](../quality/android-performance.md) | Macrobenchmark, `just perf device` planes, and Baseline/Startup Profiles |
| [TUI UI test](../quality/tui-uitest.md) | PTY-driven TUI interaction flows and snapshot evidence |
| [Release](../quality/release.md) | Release signing, artifact verification, and publishing workflows |
| [PR template](../.github/pull_request_template.md) | Review checklist for one change |

## Product documentation

| Document | Owns |
| --- | --- |
| [English README](../README.md), [Chinese README](../README_CN.md) | Product introduction, installation, and quick start |
| [Release-note format](release_notes_format_guide.md) | Bilingual user-facing release copy rules |
| [Release v2.0.3](releases/v2.0.3.md) | Release v2.0.3 notes |
| [Release v2.0.2](releases/v2.0.2.md) | Release v2.0.2 notes |
| [Release v2.0.1](releases/v2.0.1.md) | Published v2.0.1 release notes |
| [Release v2.0.0](releases/v2.0.0.md) | Published v2.0.0 release notes |
| [Sponsor (中文)](sponsor.md), [Sponsor (English)](sponsor_en.md) | Sponsorship details |
| [License](../LICENSE) | Legal licensing terms |
| [Native binding notice](../apps/android/native-bindings/NOTICE) | Generated-source editing boundary |

## Fixtures and engineering notes

| Document | Owns |
| --- | --- |
| [Fixtures](../fixtures/README.md) | Capability contracts, sync baselines, and Markdown/remote test vectors |
| [Characterization](../fixtures/characterization/README.md), [Decisions](../fixtures/characterization/DECISIONS.md) | Golden layout, validation, and expected external-behavior rationale |
| [TUI reading contract](../apps/tui/tests/reading_contract.md) | Cross-test TUI feed, reader, and quick-capture behavioral spec |
| [Workspace indexing performance](development/workspace-indexing-performance.md) | Bounded directory observation, history batching, and host/SAF measurements |
| [Android performance tooling](development/android-performance-tooling.md) | Benchmark/tracing dependency decision and startup/scroll workload bounds |
| [Build cache cleanup](development/cache-cleanup.md) | Tool selection, lock-safe retention, and cleanup boundaries |

Static product images live in `assets/` and sponsorship assets in `sponsor/`. Machine-written
verification and diagnostic reports live under repository-root `reports/` (gitignored, bounded
retention); never place handoff markdown or manual audit archives there.
