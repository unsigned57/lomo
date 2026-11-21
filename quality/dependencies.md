# Dependency Selection

This file owns dependency research, selection, and custom-infrastructure justification.
[AGENTS.md](../AGENTS.md) owns authorization and task scope; [Architecture](../ARCHITECTURE.md)
owns module boundaries; [Quality](README.md#gate-selection-and-evidence) owns verification.

## When to research

Research before adding a dependency, replacing one, or substantially redesigning a reusable
technical capability. Use standard-library/platform APIs and existing dependencies as the baseline.
Compare current alternatives even when an incumbent is usable: technical advancement, substantive
active maintenance, and relevant measured performance outweigh conservative stability, familiarity,
age, or popularity. Routine API usage or local fixes do not reopen library selection.

Verify candidate versions, APIs, and issues from package registries, official docs, and upstream
repositories (model memory alone is not research). Report unavailable tools or network access as
an evidence gap rather than defaulting to custom machinery.

## Selection and evidence

Define required behavior, target platforms, architectural constraints, and relevant resource
budgets. New libraries, pre-1.0 versions, and prereleases are eligible when actively maintained and
compatible with toolchain, licensing, security, transitive cost, and any prescribed stack (such as
the Kotlin test stack).

Before implementing, state a concise **Dependency Decision**: requirements, candidates/versions,
source links, rationale, and validation plan or gaps (record rejected/deferred candidates alongside).
If performance decides the choice, measure representative targets/inputs locally; upstream numbers
are context only. Report unmeasured targets explicitly. Remove discarded experiments and never keep
parallel production stacks.

## Custom implementation and integration

Custom infrastructure is permitted only when evaluated libraries cannot meet an explicit blocker
(missing capability, incompatible target/license, or measured budget failure). "Easier to write",
"more control", "fewer lines", and speculative speed do not justify custom code.

Add dependencies at the architectural owner with minimal features and thin boundary glue. Remove
displaced custom code, redundant wrappers, and unused dependencies in the same change without leaving
a parallel fallback.

## Capability boundaries instead of library allowlists

Manifests own concrete libraries, versions, and features.
[`dependency-capabilities.toml`](dependency-capabilities.toml) classifies each external package
once by capability so architecture tests enforce owner boundaries without maintaining per-module
library allowlists:

| Capability | Role |
| --- | --- |
| `portable` | Platform-neutral algorithms, codecs, collections, and models |
| `filesystem` | Filesystem handles, file IO, temporary files |
| `database` | Database drivers and query-projection persistence |
| `network` | Transport clients, remote-provider drivers, network protocol stacks |
| `ui` | Presentation frameworks, terminal/clipboard UI, media rendering |
| `platform` | OS services, Android APIs, platform execution |
| `ffi` | Foreign-language binding and JNI interfaces |
| `dependency-injection` | DI containers and runtime composition |

Use exact Cargo package names (even for renamed imports) or Maven `group:artifact` (unversioned).
Classify every required capability of the integration and transitive stack; unclassified
production/build dependencies fail closed. Never label a driver `portable` to bypass its owner;
genuine authority changes update [Architecture](../ARCHITECTURE.md) and its tests.

## Version changes

Task-scoped dependency additions, replacements, and version bumps are authorized within the task and
pinned via Cargo manifests/`Cargo.lock` or Amper `module.yaml`. Do not run unrelated bulk upgrades,
use floating `latest` versions, or duplicate version pins. Complete `just deps check` and affected
checks under [Quality](README.md#gate-selection-and-evidence).
