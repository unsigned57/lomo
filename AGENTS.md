# Lomo Agent Guide

Work from the current tree and preserve existing edits. This file owns task scope, autonomy,
invariants, workspace hygiene, and completion. Read only the contract for the task at hand.

## Read only the relevant contract

| Task | Read next |
| --- | --- |
| Architecture, module authority, or cross-language boundary | [Architecture](ARCHITECTURE.md) |
| New dependency, replacement, or substantial technical redesign | [Dependency selection](quality/dependencies.md), then the owning boundary |
| Build, lint, coverage, generated outputs, gates, or failure triage | [Quality](quality/README.md) |
| Project directories, artifacts, caches, and reports | [Project layout](docs/project-layout.md) |
| Test authoring, editing, or suite cleanup | [Meaningful tests](quality/testing/meaningful-tests.md), then [Kotlin](quality/testing/kotlin-test-style.md) or [Rust](quality/testing/rust-test-style.md) |
| ViewModel state, events, or paging presentation | [UDF contract](quality/udf-contract.md) |
| Device-free Android UI flows or screen snapshots | [Android UI test](quality/android-uitest.md) |
| Android device benchmarks, perf planes, or baseline profiles | [Android performance](quality/android-performance.md) |
| TUI PTY interaction flows | [TUI UI test](quality/tui-uitest.md) |
| Release signing, validation, or publishing | [Release](quality/release.md) |
| Release-note copy | [Release notes](docs/release_notes_format_guide.md) |
| Fixture semantics or golden expectations | [Fixtures](fixtures/README.md), then the affected contract |

`ARCHITECTURE.md` alone defines module authority and dependency direction. Fix ambiguous or
outdated wording without inventing a boundary change; change boundaries only when the task
requires it, and never turn it into a migration ledger. An architecture-sensitive handoff includes an
**Architecture Impact** note naming the owner, boundary effect, and any explicit exception.

Use `rg` to verify paths, APIs, and callers; code and manifests own implementation facts. Historical
reports and design proposals are evidence, not current instructions or authorization to execute
their recommendations. Do not reopen an unchanged contract already read in this task.

## Work autonomously within the authorized task

A request to implement or fix something authorizes the necessary investigation, reversible local
edits, tests, and task-related dependency changes under the owning contracts. Continue through
implementation and applicable verification without stopping at a plan or offering to continue.
An existing dirty worktree is not a reason to require cleanup or a commit.

Pre-edit explanations, dependency decisions, and progress updates inform the user; they do not
require approval before continuing. Reuse authorization already given in the session. Ask for
clarification only when missing information materially affects correctness, irreversible outcomes,
or authority and cannot be resolved from context. Continue independent work while awaiting an answer.
An explicit user review-before-edit, approval step, pause, or scope limit remains binding until changed.

Publishing, pushing release tags, installing system packages, and destructive actions require
applicable task authorization; documentation examples and credential possession do not supply it.
When approval is required, finish authorized preparation first so the user can review the concrete
result, then name the exact action needed.

## Explain the invariant before editing

Before introducing or changing a domain invariant, state machine, ownership boundary, or resource
bound, explain these five points concisely:

1. **Fundamental invariant** — the type law, state transition, domain constraint, or resource bound.
2. **Axiom violation** — the input, boundary, or path that can break it.
3. **Rebuild from truth** — the type, parser, state machine, or canonical workflow that prevents it.
4. **Edge enforcement** — where invalid input is rejected before domain logic.
5. **Tail deletion** — old fallbacks, flags, duplicate checks, compatibility paths, and ambiguous
   null/empty states removed in the same change.

Routine layout, copy, wiring, and mechanical edits need no invariant checklist. Do not add
compensating conditionals, duplicate helpers, compatibility overloads, feature flags, TODO
migrations, parallel implementations, or `NoOp`/`Disabled`/`Empty` placeholders for undefined state.
Do not suppress structural failures with `@Suppress`, `@SuppressLint`, or `@SuppressWarnings`.

Model, reject, or surface invalid upstream state. Never silently swallow `Throwable`, discarded
`runCatching`, `getOrNull`, zero/empty `getOrDefault`/`getOrElse`, or Elvis fallbacks. Defaults and
intentional capability degradation must be real domain states backed by a test, not silent error
hiding. An intentional silent `runCatching` requires `// behavior-contract: silent-result-ok: <reason>`.
No first-party `unsafe` or `#[allow(unsafe_code)]` without an explicit architecture exception and a
same-change removal plan.

Prefer technically advanced, actively maintained dependencies and measured performance over project
age or familiarity. Reuse general-purpose libraries; write custom infrastructure only for proven
gaps under [Dependency selection](quality/dependencies.md).

## Implement with workspace hygiene

Start from the observable outcome that could regress using
[Meaningful tests](quality/testing/meaningful-tests.md). Follow
[source layout](ARCHITECTURE.md#source-layout) and update both `values` and `values-zh-rCN` for
Android string resources. Read tool/dependency pins from their manifests; never add duplicate pins
or orchestration entrypoints.

Keep the repository clean:

- **No handoff or audit files in the repository**: report changes, verification evidence, and open
  items in your response, commit message, or PR description — never write markdown summaries,
  handoff notes, scratch logs, or `reports/manual/` directories into `reports/` or anywhere in the tree.
- **`reports/` is strictly tool-owned**: only `just` / xtask and `quality/scripts/` write there,
  under bounded retention. Temporary scratch files belong in OS temp or `target/lomo/tmp/<command>/`.

## Complete the task and report evidence

Use the [Quality matrix](quality/README.md#gate-selection-and-evidence) from the repository root.
Report actual status, scope, and results; a plan or `--plan` is not execution evidence, and scoped
runs establish results only within their scope. Keep stdout JSON separate from stderr logs.

A read-only review is complete with findings. A code change with a failed required gate is not fully
verified or ready to merge; report the blocker and remaining work without labeling that gate green.
Investigate failures at their owner without silently expanding into unrelated repairs.

If a required command cannot write its standard toolchain, cache, daemon, or telemetry path, use the
environment's escalation mechanism for that command when permitted. If unavailable, report the
constraint and continue independent work. Never redirect `HOME`, `XDG_*`, Gradle, Cargo, or Kotlin
caches into the repository to bypass a permission failure; remove any accidental local cache directory
if created. Reuse standard dependency caches and canonical shared build outputs.
