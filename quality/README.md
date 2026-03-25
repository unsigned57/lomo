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
