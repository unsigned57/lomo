# Architecture

This document defines the immutable architecture and authority model for Lomo. It is deliberately free of migration stages, transient implementation details, and feature-specific workflows.

## Module Authority Matrix

### Applications
- `apps/android`: Android composition root (Compose UI, SAF executor, WorkManager, JNI session adapter).
- `apps/tui` (`lomo-tui`, binary `lomo`): Cross-platform desktop terminal composition root (Linux, macOS, Windows). Owns TEA state, responsive layout, key dispatch, external editor/process spawning, terminal graphics/clipboard/player capability reporting, and per-OS private paths (XDG, `~/Library`, `%APPDATA%`/`%LOCALAPPDATA%`). It injects `lomo-platform-fs` into `lomo-application` and owns lightweight multiline quick capture for new memos. Existing memo editing runs in an external editor. All durable memo writes go through `lomo-application`; the TUI must not write workspace business files itself.

### Kotlin Modules (`apps/android/`)
- `domain`: Platform-neutral contracts, use cases, and pure models. Zero Android, persistence, network, DI, or FFI dependencies.
- `data`: Platform executor and persistence implementations (SAF, Keystore, WorkManager, engine session lifecycle). Sole production consumer of generated `native-bindings`.
- `app`: UI screens, navigation, and state presentation. Business code consumes domain contracts; reusable UI comes from `ui-components`. The composition root packages `data` as a runtime-only dependency, without a compile-time dependency on its implementations. UI/framework dependencies do not confer domain or storage authority.
- `ui-components`: Pure, reusable presentation components without business logic or platform integration.
- `native-bindings`: Generated Kotlin/JNI declarations. Build output only; never committed.

### Rust Crates (`crates/`)
- `lomo-application`: Sole authority for workspace session orchestration, durable document write transactions, dual-mode retrieval, task aggregation, statistics, daily review, reminder planning, history/trash/archive lifecycle, platform action staging via exchange tokens, operation idempotency journals, private state/cache isolation, and full/incremental SQLite query projection rebuilds.
- `lomo-core`: Platform-independent engine runtime, identity, concurrency locks, journals, and execution protocols.
- `lomo-workspace`: Sole authority for Markdown, document rendering/patching, durable memo identity mappings and conflict evidence, trash records, and workspace record codecs. Durable `MemoId` values are independent of source-version `MemoLocator` addresses.
- `lomo-store`: Sole local query-projection authority (SQLite). Purely rebuildable from workspace facts; never the primary authority for documents.
- `lomo-sync` / `lomo-git`: Sole remote synchronization planner (Git, WebDAV, S3) and provider adapters.
- `lomo-lan`: Local network discovery, pairing, trust, and peer-to-peer transfer protocols.
- `lomo-media`: Media identity and lifecycle operations.
- `lomo-platform-fs`: Host filesystem implementation of the core `PlatformActionExecutor` protocol for desktop targets (Linux, macOS, Windows). Owns root capabilities bound to pinned directory anchors, SHA-256 checked atomic file I/O, process file locks, and directory change observation. Per-OS mechanisms live in this crate; its domain evidence contract stays shared. It contains no application, SQLite, UI, or network policy.
- `lomo-native`: Sole business FFI facade and JNI boundary. Converts foreign DTOs onto `lomo-application` and owner crates; it does not own document, projection, or network policy.
- `boltffi-facade` (package `boltffi`): Repository-owned facade over `boltffi_core` controlling macro expansion without enabling codec features.
- `lomo-feasibility`: Corpus extraction, redaction, and offline feasibility analysis tooling.
- `lomo-xtask`: Build, packaging, and quality orchestration tooling.
- `lomo-architecture-tests`: Repository and cross-language architecture locks.

## Irreducible Architectural Invariants

External library choices and versions are implementation facts, not immutable per-module name
allowlists. [Dependency Selection](quality/dependencies.md#capability-boundaries-instead-of-library-allowlists)
defines how the shared capability catalogue is maintained. The executable owner policies admit
capabilities within the boundaries below and retain explicit first-party module/path edges.
Selecting a different library does not grant it another module's business authority.

1. **Inward Dependency Direction**: Platform-neutral domain and planning code must not acquire Android, JNI or UI dependencies or take over driver authority. SQLite and remote-provider drivers belong to the storage and synchronization owners above; platform execution belongs to its adapters. JNI is confined to the native facade and Android binding boundary. Generated bindings and native binaries are build outputs, not versioned facts.
2. **Exclusive Domain Authority**: Each domain subsystem has exactly one authoritative owner crate. Any secondary store (e.g. SQLite query index) is a disposable, derived projection rebuildable from primary facts.
3. **Core Planning vs Platform Execution**: Durable workspace business rules, transactions and protocols are owned by Rust. Kotlin owns presentation and platform-resource state machines (SAF I/O, OS integration and rendering), without duplicating durable domain rules. The [UDF contract](quality/udf-contract.md) defines the presentation interface.
4. **Strict Boundary Facade**: Cross-language interactions flow strictly through the single native facade (`lomo-native` -> `native-bindings` -> `data`). No component may bypass this boundary.
5. **Linux Host Independence**: The host crate closure (`lomo-application`, `lomo-core`, `lomo-workspace`, `lomo-store`, `lomo-media`, `lomo-platform-fs`, `lomo-tui`, `lomo-architecture-tests`, `lomo-xtask`) must have zero dependencies on Android SDK, NDK, JNI, or Android-specific runtime artifacts. Desktop OS backends remain within their owning adapters. The `host_dependency_closure_is_free_from_android_and_jni` architecture test enforces the Android/JNI exclusion in every verification gate.

## Source layout

Kotlin modules use Amper `src/`, `test/` and resource roots without Maven/Java or common package-root
directories; package declarations remain `com.lomo.*`. Test conventions belong to the language
guides. The sole source-layout exception is the pair of Android baseline profile inputs:
`apps/android/app/src/main/baseline-prof.txt` and
`apps/android/app/src/main/baselineProfiles/generated.txt`. Regeneration and validation belong to
the [Quality Contract](quality/README.md#generated-state-and-failure-triage).
