# Architecture Overview

This document is the stable architecture entrypoint for the repository. It describes module boundaries and dependency direction only. It intentionally avoids volatile directory inventories so it stays useful as the tree changes.

## Modules

### `domain`

- Pure business layer.
- Owns domain models, repository contracts, and use cases.
- Must stay free of Android, Compose, Lifecycle, Room, Koin (or any DI framework), Ktor, JGit, and any `com.lomo.data.*` type.

### `data`

- Infrastructure and integration layer.
- Implements `domain` contracts and owns persistence, sync engines, file access, network/storage glue, and background work support.
- New repository implementations belong here, typically under `data/repository`.

### `rust-bindings`

- Ignored, generated Android/Kotlin binding layer for repository-owned Rust infrastructure.
- Owns UniFFI-generated Kotlin/JNA glue only; business rules and Android orchestration do not belong here.
- `lomo-xtask` regenerates this module before any Kotlin build; generated source is never a versioned fact.
- May be consumed by `data`; must not be imported by `domain`, `app`, or `ui-components`.

### `rust/lomo-sync-core`

- Pure platform-independent sync v1 core.
- Owns the provider-neutral model, validation boundary, binary protocol, and deterministic planner.
- Must not depend on Android, UniFFI, Kotlin, JNA, xtask, or architecture-test crates.

### `rust/lomo-native`

- The only production native facade and the only `cdylib`/UniFFI crate.
- Exposes repository-owned Rust infrastructure through `liblomo_native.so` and package `com.lomo.rust`.
- Depends inward on `lomo-sync-core`; future database, parsing, or cryptography crates attach here only
  after they own real behavior.
- Optional feature `feasibility-probe` exports tooling-only UniFFI types (`FeasibilityProbe`). The
  feature is **off** by default and must never be enabled for production `app/jniLibs` packaging.

### `rust/lomo-xtask`, `rust/lomo-architecture-tests`, and `rust/lomo-feasibility`

- Tooling-only crates that own orchestration, structural enforcement, and stage-0 evidence.
- Must never appear in a production dependency graph.
- `lomo-xtask` is the sole owner of pinned tools, NDK/JNA acquisition, generated bindings/native
  libraries, Kotlin Toolchain execution, APK validation, release signing, cache policy, and smoke runs.
  It dual-packages native libraries: production `app/jniLibs` without `feasibility-probe`, and
  `native-smoke/jniLibs` with the feature enabled for device lifecycle checks.
- `lomo-feasibility` owns versioned corpus/report schemas, redaction rules, feasibility exit codes,
  and hermetic probes (SQLite, Markdown, HTTPS, Git). Production crates must not depend on it.
- Repository-root `fixtures/` holds language-neutral golden format assets (`markdown/`, `remote/`,
  `git/`, `characterization/`, `baseline/`). Quality scripts do not own these; generators and
  characterization tests consume them. Large generated corpora stay under gitignored `build/corpora/`.
  Stage-0 reports belong under gitignored `build/reports/feasibility/`.
  Shared stage-0 audit status lives in `fixtures/baseline/STAGE0-STATUS.md`;
  tooling-only `lomo-feasibility-device` packages linked feasibility deps for four-ABI volume/ELF
  evidence and must never enter production `app/jniLibs`. Gitignored local planning notes are not
  provenance.

### `native-smoke`

- Tooling-only Android application used by CI and `just device-smoke`.
- Loads smoke-packaged JNA and `liblomo_native.so` (with `feasibility-probe`), exercises the sync v1
  planner, durable `FeasibilityProbe` journal recovery (seed + force-kill + relaunch), and a
  deterministic in-app `DocumentsProvider` SAF surface, then emits an observable PASS/FAIL result.
- Must not be packaged into or depended on by the production application.
- Temporary stage-0/1 exception: probe types and the smoke DocumentsProvider are deleted once the
  formal stage-1 engine and platform-batch contracts replace them.

### `app`

- Android application and feature orchestration layer.
- Owns screens, navigation, ViewModels, settings orchestration, widgets, and app startup behavior.
- Owns the production APK packaging boundary for ignored Rust/JNA native libraries under `app/jniLibs`;
  native business behavior remains in Rust.
- Consumes `domain` contracts and shared UI from `ui-components`.
- Must not import `com.lomo.data.*`.
- May package `data` as a runtime-only module for application composition, but must not expose `data` on the app compile surface.

### `ui-components`

- Shared UI infrastructure layer.
- Owns reusable Compose components, markdown/text rendering, input surfaces, and theme primitives.
- Must not import `com.lomo.data.*`.
- Keep feature orchestration and repository logic out of this module.

## Dependency Direction

- `domain` sits at the center and should not depend on other project modules.
- `data` depends on `domain`.
- `data` may depend on `rust-bindings` for generated native calls.
- `rust-bindings` calls only `lomo-native`; `lomo-native` depends on internal Rust infrastructure such
  as `lomo-sync-core`.
- `app` depends on `domain` and `ui-components`.
- `native-smoke`, `lomo-xtask`, `lomo-architecture-tests`, and `lomo-feasibility` are tooling leaves
  and are never production dependencies.
- `ui-components` may depend on stable presentation-safe types, but must not depend on `data` and should avoid feature/business orchestration concerns.

## Change Routing

- New business behavior starts in `domain`, then gets implemented in `data`, and finally consumed from `app`.
- New repository implementations belong in `data`.
- ViewModels should depend on `domain` contracts and use cases, never on DAO, RoomDatabase, repository implementations, sync engines, `DocumentFile`, or direct filesystem helpers.

## Enforcement

- `AGENTS.md` carries the AI workflow and architecture gate.
- `quality/detekt-rules` enforces repository-specific architecture rules.
- `quality/README.md` is the verification entrypoint for build, lint, detekt, coverage, and quality scripts.
- Rust architecture tests enforce the unique native facade, dependency matrix, inherited governance,
  generated-output ownership, Amper-native Kotlin layout, Markdown link integrity, physical test
  separation, and absence of first-party unsafe code.

## Architecture Impact

- Owning layer: Rust infrastructure remains an independent Cargo workspace; Kotlin `data` consumes it
  only through generated `rust-bindings`.
- Boundary effect: `app` remains the production native packaging boundary; `native-smoke` is an
  isolated CI-only packaging boundary with a separate jniLibs tree.
- Exception (temporary): `feasibility-probe` UniFFI surface and smoke `DocumentsProvider` exist only
  for stage-0/1 evidence. Production modules must not import them (architecture tests). Remove both
  when stage-1 formal engine contracts land; do not grow a permanent dual facade.

## Documentation Trust Model

- Treat this file and `AGENTS.md` as the durable architecture source.
- `quality/README.md` owns executable quality-gate documentation; `quality/release.md` owns release
  and signing documentation; language-specific test rules live under `quality/testing`.
- Concrete paths, APIs, and implementation details are code facts. Verify them against the tree
  instead of maintaining module file inventories.
