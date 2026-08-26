# Architecture

This document defines the immutable architecture and authority model for Lomo. It is deliberately free of migration stages, transient implementation details, and feature-specific workflows.

## Module Authority Matrix

### Kotlin Modules
- `domain`: Platform-neutral contracts, use cases, and pure models. Zero Android, persistence, network, DI, or FFI dependencies.
- `data`: Platform executor and persistence implementations (SAF, Keystore, WorkManager, engine session lifecycle). Sole production consumer of generated `native-bindings`.
- `app`: UI screens, navigation, and state presentation. Depends exclusively on domain contracts.
- `ui-components`: Pure, reusable presentation components without business logic or platform integration.
- `native-bindings`: Generated Kotlin/JNI declarations. Build output only; never committed.

### Rust Crates
- `lomo-core`: Platform-independent engine runtime, identity, concurrency locks, journals, and execution protocols.
- `lomo-workspace`: Sole authority for Markdown, document rendering/patching, durable trash records, and workspace files.
- `lomo-store`: Sole local query-projection authority (SQLite). Purely rebuildable from workspace facts; never the primary authority for documents.
- `lomo-sync` / `lomo-git`: Sole remote synchronization planner (Git, WebDAV, S3) and provider adapters.
- `lomo-lan`: Local network discovery, pairing, trust, and peer-to-peer transfer protocols.
- `lomo-media`: Media identity and lifecycle operations.
- `lomo-native`: Sole business FFI facade and JNI boundary.
- `lomo-xtask`: Build, packaging, and quality orchestration tooling.

## Irreducible Architectural Invariants

1. **Inward Dependency Direction**: Inner domain and Rust crates never depend on Android SDK, JNI, UI frameworks, or specific storage/network drivers. Generated bindings and native binaries are build outputs, not versioned facts.
2. **Exclusive Domain Authority**: Each domain subsystem has exactly one authoritative owner crate. Any secondary store (e.g. SQLite query index) is a disposable, derived projection rebuildable from primary facts.
3. **Core Planning vs Platform Execution**: Business logic, state machines, and protocols are owned entirely by the Rust core. The Kotlin layer acts strictly as a platform shell (executing SAF I/O, OS integration, and rendering UI) without duplicating domain rules.
4. **Strict Boundary Facade**: Cross-language interactions flow strictly through the single native facade (`lomo-native` -> `native-bindings` -> `data`). No component may bypass this boundary.


