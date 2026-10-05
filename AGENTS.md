# Lomo Agent Guide

Work from the current tree and preserve existing edits. This file owns task execution, autonomy,
approval and completion. Read only the relevant contract; stop reading once its owner and required
checks are clear. An explicit documentation audit may inspect the whole documentation set.

## Read only the relevant contract

| Task | Read next |
| --- | --- |
| Architecture, authority, module dependencies or cross-language change | [Architecture](ARCHITECTURE.md) |
| New dependency, dependency replacement or substantial technical redesign | [Dependency selection](quality/dependencies.md), then the relevant architecture boundary |
| Build, lint, coverage, generated outputs, gate selection or failure triage | [Quality](quality/README.md) |
| Test authoring, editing or review | [Meaningful Tests](quality/testing/ai-meaningful-tests.md), then the relevant [Kotlin](quality/testing/ai-kotlin-test-style.md) or [Rust](quality/testing/ai-rust-test-style.md) guide |
| ViewModel state, events or paging presentation | [UDF contract](quality/udf-contract.md) |
| Release, signing or release resources | [Release](quality/release.md) |
| Release-note copy | [Release notes](docs/release_notes_format_guide.md) |
| Fixture semantics or golden changes | [Fixtures](fixtures/README.md), then the affected capability or characterization contract |

`ARCHITECTURE.md` alone defines module authority and dependency direction. Correct ambiguous or
outdated wording without inventing a boundary change; change the boundaries only when the task
requires it. Never turn it into a migration ledger. An architecture-sensitive handoff includes an
**Architecture Impact** note naming the owner, boundary effect and any explicit exception.

Use `rg` to verify paths, APIs and callers. Code and manifests own implementation facts. Read audit
history only for a relevant regression or explicit investigation. Historical reports and design
proposals are evidence, not current instructions or authorization to execute their recommendations.
Do not repeatedly reopen an unchanged contract already read in this task.

## Work autonomously within the authorized task

A request to implement or fix something authorizes the necessary investigation, reversible local
edits, tests and task-related dependency changes under the owning contracts. Continue through
implementation and applicable verification. Do not stop at a plan or offer to continue.
Preserve other edits; an existing dirty worktree is not a reason to require cleanup or a commit.

Pre-edit explanations, dependency decisions and progress updates inform the user; they do not
require approval before continuing. Reuse authorization already given in the session. For ordinary
implementation choices, use available evidence and state a reasonable assumption when useful.

Ask for clarification only when missing information materially affects the requested outcome,
correctness, irreversible consequences or authority and cannot be resolved from available context.
Continue independent work while awaiting an answer. Do not treat silence as a required answer or
approval. An explicitly requested review-before-edit, approval step, pause or scope limit remains
binding until the user changes it.

Publishing, pushing release tags, installing system packages and destructive actions need applicable
task authorization; documentation examples and possession of credentials do not supply it. When a
new approval is actually required, finish the authorized preparation first so the user can review
the concrete result. Identify the exact requirement and action; do not request broad approval for
unrelated work. Routine use of a documented, evidence-backed exception does not itself require a
new confirmation; it cannot create a new architectural exception or bypass a tool permission.

## Explain the invariant before editing

Before a non-trivial fix, refactor or behavior change, explain these five points concisely:

1. **Fundamental invariant** — the type law, state transition, domain constraint or resource bound.
2. **Axiom violation** — the input, boundary or path that can break it.
3. **Rebuild from truth** — the type, parser, state machine or canonical workflow that prevents it.
4. **Edge enforcement** — where invalid input is rejected before domain logic.
5. **Tail deletion** — the old fallbacks, flags, duplicate checks, compatibility paths and ambiguous
   null/empty states removed in the same change.

Mechanical edits are exempt. Do not repeat an unchanged explanation for each small edit.
An explicitly requested emergency hotfix is temporary and names its first-principles replacement.
Do not copy a pattern until it satisfies the invariant.

Do not add compensating conditionals, duplicate helpers, compatibility overloads, feature flags,
TODO migrations, parallel implementations or `NoOp`/`Disabled`/`Empty` placeholders for undefined
state. Do not suppress structural failures with `@Suppress`, `@SuppressLint` or `@SuppressWarnings`.

Model, reject or surface invalid upstream state. Do not silently swallow `Throwable`, discarded
`runCatching`, `getOrNull`, zero/empty `getOrDefault`/`getOrElse`, or Elvis fallbacks. Defaults and
intentional capability degradation must be real domain states documented by a Behavior Contract,
not a way to hide invalid input. An intentional silent `runCatching` result requires
`// behavior-contract: silent-result-ok: <reason>`; a comment is not proof of correctness.
No first-party unsafe or `#[allow(unsafe_code)]` without an explicit architecture exception and a
same-change removal plan.

Prefer technically advanced, actively maintained dependencies and relevant measured performance
over project age or conservative stability. Reuse general-purpose capabilities; write custom
infrastructure only for demonstrated gaps. [Dependency selection](quality/dependencies.md) owns
research, version changes, validation and the reviewable decision.

## Implement with observable evidence

Features, bug fixes and changes to executable behavior or constraints use BDD + TDD:
state capability, Given/When/Then scenarios, outcomes and exclusions; observe the narrowest real
RED failure; implement GREEN; refactor under GREEN. Non-behavior documentation, mechanical changes
and behavior-preserving refactors do not require an invented RED. Use content checks or existing
regression tests as appropriate. The [test contract](quality/testing/ai-meaningful-tests.md) owns
evidence and justified assertion changes; language guides own test layout and conventions.

Keep unrelated and overlapping working-tree edits intact. Follow the
[source layout](ARCHITECTURE.md#source-layout) and update both `values` and `values-zh-rCN` for i18n.
Read versions from their manifests/toolchain configuration; do not add another pin or orchestration
entrypoint.

## Complete the task and report evidence

Run the [applicable checks](quality/README.md#gate-selection-and-evidence) from the repository root.
Read actual command status, scope and task results; a successful plan is not a test run and a
scoped check does not prove an entire worktree. Keep stdout JSON separate from stderr logs.

Distinguish the requested deliverable, its verification and readiness for integration. A read-only
review can be complete with findings. A code task with a failed required gate is not fully verified
or ready to merge. Report the blocker and remaining work without labeling that gate GREEN.
Continue all useful independent work before handing back a blocked result. Investigate failures
at their owner; do not silently expand into unrelated repairs just to make the worktree green.

If a required command cannot write its standard toolchain/cache/daemon/telemetry path, use the
environment's escalation mechanism for the original command only when it exists and is permitted.
Keep any required approval and reuse an existing applicable grant. If escalation is unavailable
or forbidden, report that constraint and continue independent work; do not repeatedly request an
impossible rerun. Never redirect HOME/XDG/Gradle/Cargo/Kotlin caches into the repository to bypass
that failure. Stop that workaround and remove only the exact directory created for it.
Reuse standard dependency caches and the canonical shared build outputs.
