# Dependency Selection

This file owns dependency research, selection and custom-infrastructure decisions.
[AGENTS.md](../AGENTS.md) owns authorization and task execution;
[Architecture](../ARCHITECTURE.md) owns module boundaries and
[Quality](README.md#gate-selection-and-evidence) owns verification.

## When to research

Research before selecting a new dependency, replacing one or substantially redesigning a reusable
technical capability. Standard-library/platform APIs and current dependencies form the baseline.
Compare current alternatives even when the incumbent is usable; technical advancement, frequent
substantive maintenance and workload performance take priority over conservative stability,
familiarity, project age or popularity.

Routine use of an existing API, a local fix or mechanical maintenance does not reopen library
selection. Reuse a traceable decision when requirements, supported targets and material maintenance
or compatibility facts have not changed.

Use package registries, official documentation and upstream repositories. Read current APIs,
releases and relevant issues; compare credible candidates for the specific capability, not the whole
ecosystem. Record sources, candidate versions and search scope. Model memory alone is not research.
Unavailable tools or network access leave an evidence gap, not proof that no library qualifies.
Continue work that does not depend on the unresolved selection; do not default to custom machinery.

## Selection and evidence

Define required behavior, target platforms, architecture constraints and relevant resource budgets.
Prefer modern designs and active maintenance, checking substantive releases/commits, issue response
and support for current ecosystem APIs. Release count alone does not establish quality. New libraries,
pre-1.0 releases and prereleases are eligible; age and a stable version label are not prerequisites.
Check correctness, API fit, toolchain compatibility, security, licensing, transitive cost and the
requirements of any specifically prescribed stack, such as the Kotlin test stack.

Before implementation, give a concise **Dependency Decision**: requirements, candidates/versions,
source links, reasons and the validation plan or evidence gaps. This is an explanation, not a new
approval step. A minimal local integration or benchmark experiment may precede the final selection.
Update the decision with results before adopting the production implementation; remove discarded
experiments and do not keep parallel production stacks.

Evaluate latency, throughput, peak memory, allocations, startup and binary size only as relevant.
When performance decides the choice or rejects a candidate, use reproducible measurements on
representative inputs and targets. Upstream benchmarks are context, not project measurements.
Report unmeasured targets or metrics as unverified; they block only conclusions that require them.

## Custom implementation and integration

Custom infrastructure is permitted only when the evaluated credible dependencies cannot meet an
explicit requirement. Identify concrete blockers, such as a missing capability, incompatible target
or license, or a measured budget failure. If no credible candidate is found, record the searched
sources and scope. "Easier to write", "more control", "fewer lines" and speculative speed do not
justify custom code. Lomo-specific domain rules and minimal boundary glue remain their owner's job.

Reuse supported library capabilities and implement only the proven gap. Add dependencies at the
architectural owner with the minimum required features and a thin adapter only where needed.
Remove displaced custom implementations, redundant wrappers and unused dependencies in the same
change. Do not keep a parallel custom fallback.

## Capability boundaries instead of library allowlists

Cargo/Amper manifests own the selected libraries, versions, features and scopes. The shared
[capability catalogue](dependency-capabilities.toml) classifies each external library identity once;
it is not an inventory of libraries that must be installed. Architecture tests check the required
capabilities against the consuming owner's boundary, rather than admitting library names per module.
Adding or replacing a library within that boundary does not require changing test code or asking
for a new approval. Updating an already classified library's version does not require a second pin.

| Capability | Selected integration role |
| --- | --- |
| `portable` | General algorithms, codecs, collections, models and other platform-neutral utilities |
| `filesystem` | Filesystem handles, file operations, temporary files or file-backed output |
| `database` | Database drivers and query-projection persistence |
| `network` | Transport clients, remote-provider drivers and network protocol stacks |
| `ui` | Presentation frameworks, terminal/clipboard presentation and media rendering |
| `platform` | OS services, Android APIs and platform execution mechanisms |
| `ffi` | Foreign-language binding and JNI interfaces |
| `dependency-injection` | Dependency injection containers and runtime composition |

Use actual Cargo package names even for renamed imports, or Maven `group:artifact` without a
version. List every capability needed by the selected integration, features and relevant transitive
stack. A library can require several capabilities; its owner must permit all of them. Unclassified
production/build dependencies and malformed classifications fail with an actionable diagnostic.
Test-only external support remains in test dependencies; internal module/path rules still apply.

For a new library, add its dependency and classification as one task-related change, then run the
owning behavior tests, architecture checks and applicable dependency checks. For replacement or
upgrade, recheck the classification if APIs, features or material transitive behavior changed.
Do not label a driver `portable` to bypass its owner. Classification is reviewed evidence, not
automatic semantic proof; the existing resolved host/JNI graph, source-boundary, supply-chain and
behavior checks remain necessary. A genuine change to module authority updates Architecture and
its boundary tests, not just the catalogue.

## Version changes

An authorized implementation task includes the evidenced dependency additions, replacements and
version adjustments needed for that task; do not ask again solely because a manifest changes.
This does not authorize unrelated bulk upgrades or changing an explicitly prescribed architecture
or stack outside the task. Pin selected versions through the existing manifest/lockfile workflow.
No floating "latest" references, unassessed automatic upgrades or duplicate pins.
Complete the [dependency and affected-surface checks](README.md#gate-selection-and-evidence).
