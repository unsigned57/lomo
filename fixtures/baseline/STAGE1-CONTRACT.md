# Stage-1 Rust application-kernel behavior contract

> Status: **locked; implementation in progress; FFI transport rebased to BoltFFI/JNI**
>
> This document fixes the behavior and evidence required to close stage 1. It is not evidence that
> any scenario is implemented. Actual RED/GREEN commands and results are recorded in
> `STAGE1-EVIDENCE.md` alongside the implementation that produces them.
> Existing UniFFI RED/GREEN entries remain historical implementation evidence, but they are
> superseded for the FFI exit gate and cannot prove the approved BoltFFI transport.

## Behavior Contract

- **Unit under test:** Rust application kernel, its target BoltFFI/JNI facade, generated-binding
  boundary, and Android platform adapter.
- **Owning layer:** future `lomo-core`; `lomo-native` is the FFI edge and Kotlin `data` is the Android
  execution edge.
- **Priority tier:** P0.
- **Capability:** open exactly one recoverable engine per workspace identity; serialize durable job
  transitions; publish loss-detectable events; enforce cancellation; and execute versioned,
  idempotent platform-action batches without transferring workspace bytes through FFI.

## Fundamental invariants

1. One workspace identity has at most one active engine and one authoritative write sequence.
2. A job has exactly one durable terminal state; a late result cannot replace a durable cancellation.
3. A platform side effect is never reported as committed until its result is durably accepted by
   Rust; replay is accepted only when the declared postcondition is independently verified.
4. `CoreRevision` changes only for a durable domain-snapshot commit. `EventSequence` changes for
   every event and is the sole listener-loss detector.
5. Kotlin executes Android capabilities but cannot construct Rust revisions, advance jobs, or
   interpret opaque identifiers.
6. Unknown schema, corrupt journal, invalid path/capability, and inconsistent result fail closed.
7. A generated BoltFFI object is never used or closed outside the `data` lifecycle lease; close
   waits for in-flight readers, rejects new readers, and releases the native handle exactly once.
8. A foreign callback only enqueues a bounded invalidation. It never holds an engine lock, calls
   back into FFI, carries a full collection, or becomes an alternate source of state truth.

## Scenarios

### Engine lifecycle and workspace ownership

- Given no configured workspace, when the application starts, then the engine reports
  `AwaitingWorkspaceSelection` without creating a sentinel workspace.
- Given a valid direct or SAF workspace, when bootstrap completes, then exactly one engine becomes
  `Ready` and its control journal is scoped to that workspace identity.
- Given an engine already owns a workspace lock, when a second object or process opens the same
  workspace, then the second open fails with a structured busy error.
- Given the owning process dies, when the workspace is reopened, then the OS lock is released and
  recovery uses the last complete journal state.
- Given native load, lock, grant, journal, or bootstrap failure, when the main application enters its
  writable surface, then it is routed to structured read-only recovery and no old-core write path is
  available.

### Journal and state transitions

- Given a kill or injected I/O failure at any journal publish boundary, when the engine reopens,
  then it observes either the previous complete state or the next complete state, never a mixture.
- Given a truncated file, bad checksum, unknown schema, duplicate identifier, orphan action receipt,
  or conflicting terminal states, when open validates the journal, then it fails closed without
  replacing the journal with an empty state.
- Given more than 64 active jobs or 256 retained terminal jobs, when another job is created or
  retention runs, then active state is preserved and the bounded policy is observable.

### Events and snapshots

- Given the current event sequence is N, when one externally visible engine event occurs, then the
  event carries N+1 while `CoreRevision` remains unchanged unless a domain snapshot committed.
- Given Kotlin misses N+1 and next observes N+2, when the adapter handles that event, then it reloads
  state/job snapshots instead of merging an incomplete delta.
- Given a slow, re-entrant, failed, or closed foreign listener, when the actor commits another job
  transition, then the writer remains live and no state lock is held during the callback.
- Given an in-flight state or job call, when shutdown races with that call, then the lifecycle lease
  lets the call finish before one close and no generated object is accessed after close begins.
- Given callback pressure exceeds the bounded queue, when the adapter drains invalidations, then it
  coalesces to a required resnapshot without blocking Rust or re-entering FFI from the callback.

### Cancellation and deadlines

- Given cancellation durably commits before completion, when a background result later arrives,
  then the job remains cancelled.
- Given completion durably commits before cancellation, when cancellation is requested, then the
  outcome is `AlreadyCompleted`.
- Given a process dies before a job deadline, when it reopens after the persisted deadline, then the
  job becomes an explicit timeout rather than restarting with a fresh deadline.

### Platform-action batches

- Given a batch containing stat, bounded listing, directory creation, exchange read/write, move, or
  delete, when Kotlin executes it, then results preserve job/batch/attempt/action identity and do not
  transfer file content through FFI.
- Given a side effect completed but its result was not journaled, when the same action is replayed,
  then Kotlin returns `AlreadySatisfied` only after fingerprint/metadata/digest verification.
- Given a result with a wrong schema, job, batch, attempt, action order, action prefix, digest, or
  capability, when Rust validates it, then the result is rejected without advancing the job.
- Given a revoked SAF grant, escaped relative/exchange path, symlink escape, oversized page/path, or
  inconsistent target, when the executor handles the action, then it returns a structured error and
  performs no unverified fallback.

### Workspace switching

- Given workspace A is active and candidate B validates, when selection persistence succeeds, then B
  atomically becomes active and A closes afterward.
- Given candidate validation or selection persistence fails, when the switch returns, then A and its
  previous persisted selection remain authoritative.
- Given a switch is in progress, when a write is requested, then the write is rejected until one
  engine is durably active.

## Observable outcomes

- Exported engine state, job step, cancellation outcome, error category/code, core revision, event
  sequence, and listener invalidation.
- Durable journal bytes and recovery outcome after controlled kill/I/O failure.
- Workspace lock acquisition/release across objects and processes.
- SAF document metadata/content digest and absence of duplicate create/replace/move/delete effects.
- Main-app readiness route and the reachability of every user/background write command.
- Generated production APK symbol/feature surface and dependency direction.
- Deterministically normalized generated Kotlin with no `@Suppress`, warnings-as-errors success,
  and stable module/package/library identity.

## Public contract fixed for stage 1

```text
LomoEngine.open(EngineConfig) -> LomoEngine
LomoEngine.state() -> EngineState
LomoEngine.subscribe(CoreEventListener) -> Subscription
LomoEngine.poll_job(JobId) -> JobStep
LomoEngine.submit_platform_result(JobId, PlatformBatchResult) -> JobStep
LomoEngine.cancel_job(JobId) -> CancelOutcome
LomoEngine.shutdown(ShutdownDeadline) -> ShutdownOutcome
```

Platform action v1 contains `Stat`, `ListChildren`, `EnsureDirectory`, `ReadToExchange`,
`WriteFromExchange`, `Move`, and `Delete`. Credential and product behavior commands are not part of
stage 1.

## TDD proof

- **Current evidence:** in progress; see `STAGE1-EVIDENCE.md`. Only entries with an observed GREEN
  result are implemented claims; all remaining exit evidence is still pending.
- Every implementation workstream must record the narrowest command, the observed RED
  assertion/error, why it proves the capability is absent, and the subsequent GREEN result.
- A first-run GREEN test is insufficient and must be strengthened before production changes.
- Existing stage-0 `FeasibilityProbe` tests are feasibility evidence only and cannot be cited as
  stage-1 engine GREEN evidence.
- Existing stage-1 UniFFI facade/adapter GREEN entries prove core behavior exercised through the old
  transport only. The BoltFFI migration needs its own exact-surface RED, generated-code, lifecycle,
  callback, packaging, size, and performance GREEN entries.

## Excludes

- Markdown/storage parsing and render IR.
- SQLite, Room replacement, queries, Paging, memo CRUD, history, pin, trash, and rebuild.
- Media identity/lifecycle rules.
- WorkManager orchestration, Keystore credential supply, and S3/WebDAV/Git execution.
- A production fallback to the Kotlin core, dual write, compatibility engine, or placeholder API.

## Exit evidence required

- Narrow Rust/Kotlin/architecture tests with recorded RED/GREEN proof.
- `just preflight`, `just check`, and `just ci` all green.
- Complete engine/SAF kill-and-recovery smoke via `just device-smoke` on an attached device with
  **API ≥ 26** and a packaged ABI (`arm64-v8a` or `x86_64`). On this project line the hard device
  gate is **API ≥ 26 arm64 real device** (current evidence: API 36 arm64). A fixed API 26 x86_64
  AVD matrix is **not** required for stage-1 close or stage-2 entry when no such AVD exists; it is
  an optional `pending_env` / non-claim and must never be marked GREEN without a real run. Product
  `minSdk` / NDK API **26** and four-ABI build/ELF validation remain mandatory and are separate from
  the device-smoke matrix.
- Four-ABI release build, ELF/API validation, and proof that production packaging excludes tooling
  conformance symbols.
- Per-ABI native and compressed universal-APK deltas relative to the immutable stage-0 baseline.
- Exact target identities: generated module `native-bindings`, package `com.lomo.nativebridge`, and
  the only packaged Lomo library `liblomo_native_jni.so`.
- Generated Kotlin canonicalization is deterministic, contains no `@Suppress`, and compiles with
  warnings as errors; the lifecycle lease and callback non-reentry scenarios are GREEN.
- Relative to the same-environment UniFFI migration baseline, warm generation/packaging p50 and
  `state()`/planner p95 improve by at least 30%, callback p95 does not regress, and total four-ABI
  native bytes do not increase.
- Deletion of `FeasibilityProbe` and its protocol without deletion of the tooling-only deterministic
  SAF `DocumentsProvider`; deletion also covers UniFFI, JNA, `libjnidispatch.so`, old generated
  identities, and every dual-transport or compatibility path.
