# Quality Tooling

This directory is the single entrypoint for repository quality tooling.

## Layout

- `detekt/config/`: shared and per-module Detekt configuration.
- `detekt-rules/`: custom Detekt rule module backing architecture guardrails.
- `scripts/`: repository-level quality scripts used by hooks and Gradle tasks.
- `testing/`: meaningful-test policy and AI test authoring contracts.

## Primary Tasks

- `./gradlew detektFormat`
- `./gradlew architectureCheck`
- `./gradlew meaningfulTestCheck`
- `./gradlew coverageGatePlan`
- `./gradlew qualityCheck`

## Notes

- `:detekt-rules` keeps its Gradle module name for compatibility, but its sources now live under `quality/detekt-rules/`.
- Architecture guardrails are enforced by `architectureCheck` and the custom Detekt rules collected here.
- The repo-root `architecture-test/` directory is treated as a local scratch area, not as part of the checked-in quality toolchain.
