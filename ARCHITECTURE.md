# Architecture

This document defines the immutable architecture and authority model for Lomo. It is deliberately free of migration stages, transient implementation details, and feature-specific workflows.

## Module Authority Matrix

### Applications
- `apps/android`: Android composition root (Compose UI, SAF executor, WorkManager, JNI session adapter).
- `apps/tui` (`lomo-tui`, binary `lomo`): Linux terminal composition root. Owns TEA state, responsive layout, key dispatch, external editor/process spawning, terminal graphics/clipboard/player capability reporting, and XDG private paths. It injects `lomo-platform-fs` into `lomo-application` and owns lightweight multiline quick capture for new memos. Existing memo editing runs in an external editor. All durable memo writes go through `lomo-application`; the TUI must not write workspace business files itself.

### Kotlin Modules (`apps/android/`)
- `domain`: Platform-neutral contracts, use cases, and pure models. Zero Android, persistence, network, DI, or FFI dependencies.
- `data`: Platform executor and persistence implementations (SAF, Keystore, WorkManager, engine session lifecycle). Sole production consumer of generated `native-bindings`.
- `app`: UI screens, navigation, and state presentation. Depends exclusively on domain contracts.
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
- `lomo-platform-fs`: Linux POSIX implementation of the core `PlatformActionExecutor` protocol. Owns descriptor-bound root capabilities, SHA-256 checked atomic file I/O, process file locks, and directory change observation. It contains no application, SQLite, UI, or network policy.
- `lomo-native`: Sole business FFI facade and JNI boundary. Converts foreign DTOs onto `lomo-application` and owner crates; it does not own document, projection, or network policy.
- `lomo-xtask`: Build, packaging, and quality orchestration tooling.
- `lomo-architecture-tests`: Repository and cross-language architecture locks.
- `lomo-tui`: Linux TUI binary crate. Presentation and composition only; business writes go through `lomo-application`.

## Irreducible Architectural Invariants

1. **Inward Dependency Direction**: Inner domain and Rust crates never depend on Android SDK, JNI, UI frameworks, or specific storage/network drivers. Generated bindings and native binaries are build outputs, not versioned facts.
2. **Exclusive Domain Authority**: Each domain subsystem has exactly one authoritative owner crate. Any secondary store (e.g. SQLite query index) is a disposable, derived projection rebuildable from primary facts.
3. **Core Planning vs Platform Execution**: Business logic, state machines, and protocols are owned entirely by the Rust core. The Kotlin layer acts strictly as a platform shell (executing SAF I/O, OS integration, and rendering UI) without duplicating domain rules.
4. **Strict Boundary Facade**: Cross-language interactions flow strictly through the single native facade (`lomo-native` -> `native-bindings` -> `data`). No component may bypass this boundary.
5. **Linux Host Independence**: The host crate closure (`lomo-application`, `lomo-core`, `lomo-workspace`, `lomo-store`, `lomo-media`, `lomo-platform-fs`, `lomo-tui`, `lomo-architecture-tests`, `lomo-xtask`) must have zero dependencies on Android SDK, NDK, JNI, or platform-specific runtime artifacts. The Linux host quality gate (`just check-linux`) executes completely independently without calling any Android or Java toolchain components. Generic Linux x86_64 archives are produced by `just package-linux` without host-CPU tuning or personal toolchain paths.
