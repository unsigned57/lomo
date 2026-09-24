# UDF Contract

This file defines the current Kotlin presentation state and event interface; module authority is
defined by `ARCHITECTURE.md`. Read it only when changing ViewModels, events, paging presentation or
the related rules; it maintains no class-name inventory and no migration progress.

## 1. State ownership

Durable business facts, write transactions and projections are decided by the Rust owner. Kotlin
keeps the screen session, input drafts, scroll position and platform resource lifecycle; it must not
copy a second business authority. Several consumers may read the same state, but write capability
stays inside its owner.

A screen ViewModel holds the session state its UI renders directly. A platform session facade only
relays lifecycles such as recording, update and discovery, and does not build a second screen
business state. Classification follows the state actually held: renaming a class or writing a
comment cannot change it.

## 2. Current presentation interface

1. A screen ViewModel builds at most one `StateFlow<XxxState>` screen-state machine; re-exposing a
   dependency-owned state as-is is delegation. At least one built or delegated main state must exist,
   and consuming a sealed state must exhaust its branches.
2. Every other business flow derives from the state it belongs to. Share upstreams to avoid duplicate
   queries; long-lived UI observation uses `appWhileSubscribed()`. Paging results must pass through
   `cachedIn(scope)`; exposing them afterwards with `stateIn`/`shareIn` still keeps the paging cache.
3. `MutableStateFlow`/`MutableSharedFlow` must not be exposed outside the owner as a property,
   constructor property or return type. The constraint also applies to plain state holders,
   repositories and platform facades, and a facade exemption cannot lift it. ViewModel business
   `var`s and Compose `MutableState` are additionally bounded by `ViewModelSingleStateFlow`; a `Job`
   cancellation handle may be mutable, and a temporary local variable is not part of an externally
   visible state interface.
4. One-shot UI events use `PendingUiEvent<T>` with `UiEventQueueCoordinator<T>`, consumed by
   acknowledged id; a second ViewModel `Channel`/`MutableSharedFlow` effect surface, or a bare
   `StateFlow<Event>`, is forbidden. Acknowledgement of an in-memory queue is not exactly-once
   execution across a crash; durable commands still use the Rust idempotent operation protocol.
5. The current action interface uses lambda-typed `val handler`. Do not add a parallel `onIntent`
   forwarding layer for the same action. When merging or refactoring an interface, delete the old
   entry in the same change; a name alone cannot prove the data flow is correct.
6. An edit submission takes its CAS baseline from the edit session, and must not re-read the latest
   version before saving to replace the version the user saw.

## 3. Rules and bounded exceptions

| Rule | Available marker | Constraint |
| --- | --- | --- |
| `NoMutableFlowExposure` | none | A writable `Flow` is not exposed across its owner |
| `ViewModelSingleStateFlow` | `session-facade-ok` | The screen-state machine, private mutable state, business `var` |
| `NoEventInStateFlow` | `state-event-ok` | A non-acknowledged event must not live in a `StateFlow` |
| `NoMultipleEffectChannels` | `multiple-channels-ok` | No second parallel event channel |
| `NoUnboundedFlowSharing` | `unbounded-flow-ok`, `lazy-flow-ok`, `eager-flow-ok` | UI sharing lifetime |
| `PagingDataCachedIn` | `uncached-paging-ok`, private intermediate values only | Checks the cache on the return chain, not a method name in a comment |
| `NoWriteOnlyStateFlow` | `write-only-flow-ok` | Checks real read references, not comments or strings |
| `NoCollaboratorDefaultArg` | `collaborator-default-ok` | Do not create an independent Bus/Registry/Coordinator inside a default argument |
| `NoInSituRevisionBypass` | `in-situ-read-ok` | Do not bypass the session CAS by re-reading in situ |
| `NoMutableStatePayload` | `mutable-payload-ok` | A state payload reaching the UI is an immutable snapshot; a ViewModel must not hold a mutable container or mutable holder type |
| `NoWriteInFlowDerivation` | `derivation-write-ok` | A flow-producing derivation is a pure projection; a write hidden inside it turns observation into mutation. Sink-terminated subscriptions are the legal event path |
| `NoInferredMutableStatePayload` | `mutable-payload-ok` | Resolved cross-file check of the same immutable-payload invariant on public flow signatures |

An exception must be a real comment next to the associated declaration or expression:

```kotlin
// behavior-contract: session-facade-ok: exposes the platform recording lifecycle only
class RecordingViewModel(/* dependencies */) : ViewModel()
```

A marker must be complete and carry a non-empty reason; strings, unrelated comments on descendant
members and empty reasons have no effect. A reason of "historical reasons" or "keep it this way for
now" is not acceptable. The rule can only verify the syntactic association — whether the reason
actually holds must be proven by the Behavior Contract and its tests. Delete the facade marker once
the facade starts holding screen state. Do not use a marker to relabel a registered defect as legal
behavior.

`UiEventQueueCoordinator`, `PendingUiEvent` and `appWhileSubscribed` are the existing single
implementations; search their current paths by symbol. Do not copy a second implementation. Queue
capacity, overflow result, event id, acknowledgement, cancellation and lifecycle each need behavior
tests; "it uses the named skeleton" does not make those properties hold by themselves.

Static analysis has explicit limits: light mode cannot prove cross-file type inference, every call
graph or concurrency timing. Prefer explicit read-only types in interfaces, and supply the missing
semantic proof with state-sequence, cancellation, retry and recovery tests.
