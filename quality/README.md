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

## AI Verification

- `quality/scripts/ai_quality_check.sh`
  - Canonical AI post-edit verifier.
  - Runs `./gradlew qualityCheck` with a repo-local `GRADLE_USER_HOME` that is writable inside the agent sandbox.
  - Intended to be invoked by agents after each coherent code-edit batch, not only at the end of the task.

## Dead-Code Guardrails

- Production Kotlin compile tasks treat warnings as errors so compiler-detected unreachable code and constant conditions fail the build.
- `quality/detekt-rules` adds repo-specific dead-code checks for:
  - constant branch conditions in production source
  - unreachable statements after unconditional control transfer
  - redundant `else` branches in exhaustive Boolean `when`
