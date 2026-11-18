# Documentation map

This is a navigation index, not another source of task rules. Each contract below owns its named
subject. Translations serve different readers and share the same product facts.

## Agent and engineering contracts

| Document | Owns |
| --- | --- |
| [AGENTS](../AGENTS.md) | Task scope, autonomy, clarification, approval and completion |
| [Architecture](../ARCHITECTURE.md) | Module authority, dependency boundaries and source layout |
| [Quality](../quality/README.md) | Applicable gates, command evidence, build inputs and failure triage |
| [Project directories](project-layout.md) | Source directory map and where to find packages, build state and reports |
| [Dependency Selection](../quality/dependencies.md) | Advanced, actively maintained dependencies, research and custom-code justification |
| [Dependency capabilities](../quality/dependency-capabilities.toml) | Version-independent library capability data consumed by architecture checks; no per-module library inventory |
| [Meaningful Tests](../quality/testing/ai-meaningful-tests.md) | Which changes owe a test, shared evidence, and changing or retiring tests |
| [Kotlin Tests](../quality/testing/ai-kotlin-test-style.md) | Kotlin stack, isolation, async testing and assertions |
| [Rust Tests](../quality/testing/ai-rust-test-style.md) | Rust test layout, dependencies and execution conventions |
| [UDF](../quality/udf-contract.md) | Kotlin presentation state, events and bounded exceptions |
| [Release](../quality/release.md) | Signing, artifact validation, publishing and installation boundaries |
| [PR template](../.github/pull_request_template.md) | Evidence for one change; no independent architecture rules |

Local `CLAUDE.md` and `GEMINI.md` aliases, when present, refer to AGENTS rather than maintaining
separate copies. Product confirmation flows and historical approvals do not authorize agent actions.

## Product documentation

| Document | Owns |
| --- | --- |
| [English README](../README.md), [Chinese README](../README_CN.md) | Product introduction, installation and quick start in each language |
| [Release-note format](release_notes_format_guide.md) | Bilingual user-facing release copy |
| [Sponsor (中文)](sponsor.md), [Sponsor (English)](sponsor_en.md) | Sponsorship information in each language |
| [License](../LICENSE) | Legal licensing terms |
| [Native binding notice](../apps/android/native-bindings/NOTICE) | Generated-source editing boundary |

## Test data and historical evidence

[Workspace indexing performance](development/workspace-indexing-performance.md) records the measured directory
pagination mechanism, history batching, reproducible corpora and evidence limits.

[Fixtures](../fixtures/README.md) indexes the workspace, SQLite projection, sync, LAN and shell
capability contracts. [Characterization](../fixtures/characterization/README.md) owns golden layout
and validation; its [decisions](../fixtures/characterization/DECISIONS.md) preserve semantic rationale.
The [TUI reading contract](../apps/tui/tests/reading_contract.md) covers a specific cross-test capability.
Small helper descriptions live next to their helper. Markdown corpus and quality-checker fixture
files are test inputs, not instructions; baseline profile text is a build artifact/input.

[SQLite dependency upgrade](development/sqlite-upgrade.md) records the patched database chain.

[Build cache cleanup](development/cache-cleanup.md) records tool selection, retention and limits.

Current, untracked verification evidence lives in repository-root `reports/`, grouped by tool or
investigation. This directory survives build cleanup. Historical audits and temporary design
scaffolds are not product documentation; do not add backup copies or generated reports here.

[Android performance tooling](development/android-performance-tooling.md) records the benchmark
and profile tooling decision. Static product images live in `assets/`; sponsorship images remain
beside the sponsorship content under `sponsor/`.
