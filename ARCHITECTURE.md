# Architecture Overview

This document is the stable architecture entrypoint for the repository. It describes module boundaries and dependency direction only. It intentionally avoids volatile directory inventories so it stays useful as the tree changes.

## Modules

### `domain`

- Pure business layer.
- Owns domain models, repository contracts, and use cases.
- Owns the platform-neutral `EngineReadiness` write-authority contract. Only `Ready` is writable.
- Must stay free of Android, Compose, Lifecycle, Room, Koin (or any DI framework), Ktor, JGit, and any `com.lomo.data.*` type.

### `data`

- Infrastructure and integration layer.
- Implements `domain` contracts and owns persistence, sync engines, file access, network/storage glue, and background work support.
- Sole production consumer of generated `native-bindings` / `com.lomo.nativebridge`.
- Owns the BoltFFI lifecycle lease, bounded callback-invalidation queue, capability registry,
  exchange resolver, DocumentsContract platform-action gateway, platform-batch runner, and the
  process-owned `ManagedEngineSession`. The session implements domain-facing
  `EngineReadinessRepository` and owns the production render/scan/document-command route through
  the same active `RustEngineAdapter` / `BoltFfiNativeEnginePort`; workspace switch and close take
  an exclusive session lease after all in-flight capability calls release. There is no second
  workspace engine/adapter owner and no dual-stack Markdown DI (Kotlin parser + Rust) after cutover.
  Cold start opens with no workspace; Direct/SAF selection (or cold restore) activates a candidate
  engine and only then closes the previous engine. Callbacks enqueue invalidations only and must
  never re-enter FFI.
- Memo mutation/trash repository implementations fail closed unless the engine is `Ready` and writes
  are not frozen.
- Owns process-local `WorkspaceWriteAuthority` as the shared Ready+!freeze choke for workspace file
  mutations. Markdown/media storage delegates, `DefaultWorkspaceMediaAccess` write/delete paths, and
  memo outbox drain all consult it so process-start drain, migration import, and remote sync apply
  cannot write outside Ready+!freeze.
- New repository implementations belong here, typically under `data/repository`.

### `native-bindings`

- Ignored, generated Android/Kotlin binding layer for repository-owned Rust infrastructure.
- Owns BoltFFI-generated Kotlin/JNI declarations only; business rules and Android orchestration do not belong here.
- Package is fixed as `com.lomo.nativebridge`; the packaged library identity is `liblomo_native_jni.so`.
- `lomo-xtask` regenerates this module before any Kotlin build; generated source is never a versioned fact.
- May be consumed by `data`; must not be imported by `domain`, `app`, or `ui-components`.

### `rust/lomo-sync-core`

- Pure platform-independent sync v1 core.
- Owns the provider-neutral model, validation boundary, binary protocol, and deterministic planner.
- Must not depend on Android, BoltFFI, UniFFI, Kotlin, JNI, JNA, xtask, or architecture-test crates.
- Frozen production planner until stage 5; stage 1 must not grow new consumers of its wire surface.

### `rust/lomo-core`

- Pure platform-independent application-kernel owner.
- Owns constrained engine/job/platform protocol types, workspace identity, the OS-backed
  single-instance lock, checksummed control journal, bounded single-writer actor, cancellation,
  deadlines, snapshots, and loss-detectable events.
- Must not depend on Android, BoltFFI, UniFFI, JNI, SQLite, networking backends, sync-v1 wire types, or tooling
  crates. Its journal under application-private storage is distinct from future workspace mutation
  facts under `.lomo/operations`.
- Workspace exclusivity uses a pure-safe atomic `create_dir` lock with pid-based stale reclaim
  via `/proc` (no first-party `unsafe`; not OS flock; std `File::try_lock` is stubbed on Android
  and is not used).

### `rust/workspace` (package `lomo-workspace`)

- Pure platform-independent workspace document and Markdown semantic owner for stage 2.
- Owns constrained source bytes / path / fingerprint / span / resource-limit types, memo identity
  (`${dateKey}_${timePart}_${ordinal}`), same-parse storage analysis, `RenderDocumentV1` /
  `render_markdown`, and pure document patch planning (`plan_document_patch`). Depends inward on
  `lomo-core` only for shared error/protocol types.
- May depend on pinned `pulldown-cmark 0.13.4` (`default-features = false`); non-owner production
  crates must not.
- Must not depend on Android, BoltFFI, UniFFI, JNI, SQLite, networking backends, sync-v1 wire, or
  tooling crates.
- **Production ownership (post P2-09 cutover):** document model + Render IR + pure patch planner
  + multi-phase scan / document-command job drivers are the sole Markdown semantic authority.
  `lomo-native` depends on this crate for **conversion-only** BoltFFI DTO mapping. Kotlin production
  consumes domain IR / workspace session adapters only — it must not reintroduce Kotlin
  `MarkdownParser`, JetBrains AST, or parallel semantic regex authorities. Presentation
  spacing helpers may collapse plain text; they must not re-parse Markdown structure.

### `rust/lomo-native`

- The only production native facade (`staticlib` + `rlib`).
- Linked with generated BoltFFI JNI glue into the only packaged Android library,
  `liblomo_native_jni.so`, package surface `com.lomo.nativebridge`.
- Depends inward on `lomo-core`, the frozen `lomo-sync-core`, and (from P2-06) `lomo-workspace` for
  conversion-only render/scan/document-command DTO mapping. It must not re-implement Markdown rules
  (`parse_workspace_document` / `plan_document_patch` / pulldown remain workspace-owned).
- Does not implement business rules; conversion, panic/error isolation, and lifecycle only.
  Production dual-stack DI is forbidden: after cutover only the workspace owner is live.

### `rust/lomo-xtask`, `rust/lomo-architecture-tests`, and `rust/lomo-feasibility`

- Tooling-only crates that own orchestration, structural enforcement, and stage-0 evidence.
- Must never appear in a production dependency graph.
- `lomo-xtask` is the sole owner of pinned tools, NDK acquisition, generated bindings/native
  libraries, Kotlin Toolchain execution, APK validation, release signing, cache policy, and smoke runs.
  It packages the same formal engine library into production `app/jniLibs` and tooling
  `native-smoke/jniLibs` (no UniFFI/JNA dual stack).
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
- Loads packaged `liblomo_native_jni.so`, exercises the formal BoltFFI engine (open/state/subscribe/
  cancel/shutdown, durable journal seed→kill→relaunch, concurrent close/use), the frozen sync v1
  planner, and a deterministic in-app `DocumentsProvider` SAF surface, then emits an observable
  PASS/FAIL result.
- Must not be packaged into or depended on by the production application.
- Permanent tooling fixture: the smoke DocumentsProvider remains as a SAF harness without product
  policy. `FeasibilityProbe` is deleted.

### `app`

- Android application and feature orchestration layer.
- Owns screens, navigation, ViewModels, settings orchestration, widgets, and app startup behavior.
- Owns the production APK packaging boundary for ignored Rust native libraries under `app/jniLibs`;
  native business behavior remains in Rust.
- Consumes `domain` contracts and shared UI from `ui-components`.
- Must not import `com.lomo.data.*` or `com.lomo.nativebridge`.
- May package `data` as a runtime-only module for application composition, but must not expose `data` on the app compile surface.

### `ui-components`

- Shared UI infrastructure layer.
- Owns reusable Compose components, markdown/text rendering, input surfaces, and theme primitives.
- Must not import `com.lomo.data.*` or `com.lomo.nativebridge`.
- Keep feature orchestration and repository logic out of this module.

## Dependency Direction

- `domain` sits at the center and should not depend on other project modules.
- `data` depends on `domain`.
- `data` may depend on `native-bindings` for generated native calls.
- `native-bindings` is generated from `lomo-native`; `lomo-native` depends inward on `lomo-core` and the
  frozen `lomo-sync-core` facade.
- `app` depends on `domain` and `ui-components`.
- `native-smoke`, `lomo-xtask`, `lomo-architecture-tests`, and `lomo-feasibility` are tooling leaves
  and are never production dependencies.
- `ui-components` may depend on stable presentation-safe types, but must not depend on `data` and should avoid feature/business orchestration concerns.

## Current FFI Transport — BoltFFI/JNI

Stage-0 UniFFI/JNA evidence remains immutable history under `fixtures/baseline/`. Production transport
is BoltFFI/JNI after the migration gates recorded in `fixtures/baseline/STAGE1-EVIDENCE.md`:

- `lomo-native` is a `staticlib` + `rlib` facade linked with generated BoltFFI JNI glue into
  `liblomo_native_jni.so` only (per ABI).
- Generated bindings identity is `native-bindings` / `com.lomo.nativebridge`.
- `data` owns lifecycle leases, invalidation queues, capability/exchange/SAF execution, and domain
  readiness publication.
- UniFFI, JNA, `libjnidispatch.so`, `FeasibilityProbe`, `rust-bindings`, and `com.lomo.rust` are not
  production identities.
- Four-ABI shipping size is under the frozen UniFFI+JNA native sum gate (see STAGE1-EVIDENCE BF-02/05).
- Device smoke on API ≥ 26 arm64 (and emulator when available) exercises formal engine recovery.

Runtime p95 comparison against the frozen UniFFI host samples remains recorded as host-relative when
the same-host UniFFI binary is no longer present; size and device lifecycle gates are the hard
shipping proof after cutover.

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

- Owning layer: `lomo-core` owns the formal application kernel; `lomo-native` only converts the
  BoltFFI boundary; Kotlin `data` is the only production consumer of generated `native-bindings`.
- Boundary effect: `app` remains the production native packaging boundary; `native-smoke` remains
  an isolated CI-only packaging boundary. Domain/app readiness contracts must not expose Rust DTOs.
- Write authority: domain `EngineReadiness` is the global hard gate; create/switch use cases and
  data mutation repositories enforce Ready + write-freeze at shared choke points. Workspace switch
  is validate → freeze → persist → activate engine → rebuild → unfreeze. There is no old-Kotlin-core
  write fallback.
- Permanent tooling fixture: smoke `DocumentsProvider` remains for SAF harnesses without product
  policy; production modules must not import it.

## Documentation Trust Model

- Treat this file and `AGENTS.md` as the durable architecture source.
- `quality/README.md` owns executable quality-gate documentation; `quality/release.md` owns release
  and signing documentation; language-specific test rules live under `quality/testing`.
- Concrete paths, APIs, and implementation details are code facts. Verify them against the tree
  instead of maintaining module file inventories.
