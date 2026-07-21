# Stage-3 implementation evidence

> Status: **P3-10 production store cutover GREEN; P3-11 exit gates PARTIAL (2026-07-21 honesty
> rework).** Host floors + durable `just perf` double-pass GREEN; packaged-ABI `just device-smoke`
> GREEN on API 36 x86_64 AVD only. **API ≥ 26 arm64 hard device gate OPEN / `pending_env`.** Full
> P3-11 / stage-3 formal exit is **not** claimed. Production dual-stack remains forbidden.
>
> Entry prerequisite: `STAGE2-EVIDENCE.md` **Stage 2 closed (2026-07-20 durable 100k)** — stage-2
> formal exit is the hard gate for P3 entry.

## First principles (P3 scaffolding)

1. **Invariant:** when production is switched, one local-data authority owns SQLite projections,
   search, memo transactions, `.lomo` durability, rebuild, and reminder business state. Markdown/
   media/`.lomo` remain durable facts; SQLite is rebuildable. Dark-build must not create a second
   production persistence authority beside Room before atomic cutover.
2. **Axiom violation:** without versioned STAGE3 contract/evidence and fail-closed architecture
   gates, implementers can mis-claim GREEN, skip stage-2 formal exit, ship an empty marker crate as
   `lomo-store`, or dual-wire Room + Rust via feature flags.
3. **Rebuild from truth:** versioned contract + evidence + architecture scaffolding that fails when
   STAGE3 files, the real `lomo-store` owner, stage-2 formal exit, or dual-stack wiring are wrong;
   production cutover remains a single atomic wave (P3-10) with Room tail deletion only then.
4. **Edge enforcement:** missing STAGE3 files / missing owner crate / production dual-stack store
   DI / stage-3 GREEN claims without stage-2 formal exit → architecture fail.
5. **Tail deletion:** no empty marker crate, no production dual-stack wiring, no dual-write feature
   flags, no fictional GREEN. Room tail deletion is **not** performed in P3-00.

## Package status overview

| Package | Status | Notes |
| --- | --- | --- |
| P3-00 | **GREEN** (host architecture + owner scaffold) | Contract, evidence, arch tests, real `lomo-store` owner identity |
| P3-01 | **GREEN** (host) | SQLite open + schema v1 constants/DDL |
| P3-02 | **GREEN** (host) | Pure Rust tokenizer + FTS5 external-content |
| P3-03 | **GREEN** (host) | `query_memos` / filters / `PageCursor` / stats |
| P3-04 | **GREEN** (host) | `.lomo/` durable codecs |
| P3-05 | **GREEN** (host) | Memo transaction nine-step machine + crash recovery |
| P3-06 | **GREEN** (host) | Rebuild state machine + gate rejects mutations |
| P3-07 | **GREEN** (host) | Reminder core: DST/catch-up/snooze + mark-done/record-fired |
| P3-08 | **GREEN** (host Kotlin unit) | AlarmManager pure schedule/cancel port + rolling window |
| P3-09 | **GREEN** (host dark-build) | BoltFFI store facade + data adapters/Paging (no production DI) |
| P3-10 | **GREEN** (host + device architecture cutover) | Production DI → store; Room tail deletion; arch unique-owner |
| P3-11 | **PARTIAL** (host + x86_64 AVD smoke; arm64 hard gate OPEN) | packaging Room tails deleted; undrained-outbox host fail-closed; C1 host StorePort/StoreMemo/AlarmSchedule; **`just check`/`just ci` GREEN**; **`just perf` durable double-pass GREEN** (both `conclusion: Pass` EXIT 0); **x86_64 AVD packaged-ABI `just device-smoke` GREEN** (`device smoke passed` on API 36 x86_64 AVD only); **API ≥ 26 arm64 hard device gate OPEN / `pending_env`** (no post-cutover arm64 `device smoke passed`) |

## P3-00 stage entry, contract, architecture scaffolding

### First principles

1. **Invariant:** stage 3 has a versioned contract/evidence pair and a real `lomo-store` owner crate
   (non-empty sources + external behavior tests), not a hollow marker.
2. **Axiom violation:** architecture suite had no stage_three gates; no STAGE3 fixtures; no
   `rust/store` workspace member — implementers could claim local-data ownership without a crate.
3. **Rebuild from truth:** STAGE3-CONTRACT/EVIDENCE + architecture tests that fail closed on missing
   artifacts/owner + minimal public owner-identity surface on `lomo-store`.
4. **Edge enforcement:** missing STAGE3 files, missing/empty owner, wrong package identity, tooling
   deps on owner, production dual-stack wiring, or stage-3 GREEN without stage-2 formal exit fail
   architecture tests.
5. **Tail deletion:** no empty marker, no production DI wiring of store, no Room deletion at entry.

### RED (before STAGE3 files / owner crate)

- RED command:
  `cd rust && cargo test -p lomo-architecture-tests --locked -- --nocapture stage_three`
- Observed RED (2026-07-21, after stage_three tests landed, before STAGE3 files / `lomo-store`):
  **2 passed / 2 failed** among filtered tests:
  - `stage_three_contract_and_evidence_files_exist` —
    `stage 3 requires versioned fixtures/baseline/STAGE3-CONTRACT.md`
  - `stage_three_requires_lomo_store_owner` —
    `stage 3 requires the real lomo-store owner crate`
  - `stage_two_formal_exit_is_recorded_before_stage_three_green_claims` — ok (stage-2 exit present)
  - `stage_three_dark_build_must_not_wire_production_dual_stack` — ok (Room sole production; no
    premature store DI)
- Why it proves absence: no versioned stage-3 contract/evidence and no real store owner existed, so
  implementers could not be fail-closed against mis-claimed GREEN or hollow markers.
- Log: captured under implementer scratch `gates/p3-00-arch-red.log`.

### GREEN (after contract, evidence, owner crate, gates)

- GREEN command:
  `cd rust && cargo test -p lomo-architecture-tests --locked`
- GREEN result (2026-07-21): **25 passed / 0 failed** (includes all stage_three scaffolding tests
  and pre-existing stage-one/two/governance suite).
- Companion package gates (P3-00 owner scaffold only):
  - `cd rust && cargo test -p lomo-store --locked` → **2 passed / 0 failed**
    (`owner_identity_contract`: current identity + forged fail-closed)
  - `cd rust && cargo clippy -p lomo-store --all-targets --locked -- -D warnings` → **exit 0**
  - `cd rust && cargo clippy -p lomo-architecture-tests --all-targets --locked -- -D warnings` →
    **exit 0**
- Owner crate path: directory `rust/store`, package name `lomo-store`; depends inward on `lomo-core`;
  free of boltffi/native/sync/feasibility/xtask. Schema open/rusqlite surface is **P3-01**, not
  claimed here.
- Logs: implementer scratch `gates/p3-00-arch-green.log`, `gates/p3-00-store-test-green.log`,
  `gates/p3-00-store-clippy-green.log`, `gates/p3-00-arch-clippy-green.log`.
- Intentionally **not** done in P3-00: SQLite open, FTS, query, transactions, rebuild, reminder
  product logic, FFI, Room cutover, device-smoke, `just ci` stage-exit claims.

## P3-01..P3-06 local store closed loop (pure Rust)

### First principles

1. **Invariant:** Markdown/media/`.lomo` are durable facts; SQLite is a rebuildable projection.
   Mutations are complete-recoverable or uncommitted; rebuild rejects write/sync; multi-char CJK never
   expands to unbounded unigram-OR; `PageCursor` mismatches return `stale_cursor`; `.lomo`
   checksum/unknown schema fail closed; SQLite damage never deletes `.lomo`.
2. **Axiom violation:** without a real open/schema/tokenizer/query/codec/txn/rebuild surface,
   stage-3 could claim ownership while Room remains the only working path and hollow APIs ship.
3. **Rebuild from truth:** schema v1 open contract; pure-Rust Unicode tokenizer feeding FTS5
   external-content; keyset `PageCursor`; framed `.lomo` codec; nine-step memo SM with crash points;
   rebuild SM with checkpoint resume.
4. **Edge enforcement:** unknown schema / bad checksum / stale cursor / stale snapshot / rebuild
   gate → structured `LomoError`; no silent empty fallbacks.
5. **Tail deletion:** no production DI dual-stack; no Room deletion; no JVM tokenizer authority in
   this wave; dark-build only.

### Architecture Impact

- **Owner:** `lomo-store` (`rust/store`) for local projection/query/txn/rebuild; `lomo-core` exports
  `InvalidationScope` + `event_sequence_requires_full_invalidate` + public `CoreRevision`/`EventSequence`
  constructors for commit publication.
- **Boundary effect:** store depends on `lomo-core` + workspace `rusqlite` (`bundled`+`backup`) +
  `sha2` + `serde`/`serde_json`. SQLite under `.lomo-sqlite/` only. No BoltFFI/native/Room wiring.
- **Exception:** none for unsafe; no new dependency policy beyond approved `rusqlite` features.

### RED (before P3-01..P3-06 implementation)

- RED command (illustrative host absence after contract tests landed, before production modules):
  `cd rust && cargo test -p lomo-store --locked -- --nocapture`
- Observed RED (2026-07-21, pre-implementation compile of contract surface against scaffold-only
  crate): unresolved imports / missing symbols for `Store::open`, `query_plan`, `PageCursor`,
  `encode_record`/`decode_record`, `apply_memo_command`, `run_rebuild` — proving the closed-loop
  capability was absent under P3-00 owner scaffold (`STORE_SCHEMA_VERSION = 0`, identity only).
- Why it proves absence: contract tests drive shipped APIs; scaffold identity alone cannot open WAL
  SQLite, tokenize CJK, page, encode `.lomo`, recover crash points, or rebuild.

### GREEN (after P3-01..P3-06)

- GREEN command:
  `cd rust && cargo test -p lomo-store --locked`
- GREEN result (2026-07-21 initial): **14 passed / 0 failed** across contract suites.
- Companion gates (initial):
  - `cd rust && cargo clippy -p lomo-store --all-targets --locked -- --no-deps -D warnings` → **exit 0**
  - `cd rust && cargo test -p lomo-architecture-tests --locked` → **25 passed / 0 failed**
  - `cd rust && cargo test -p lomo-core --test types_contract --locked` → **6 passed / 0 failed**
    (includes `InvalidationScope` / EventSequence gap → full invalidate)
- Logs: implementer scratch `gates/p3-01-06-store-test-green.log`,
  `gates/p3-01-06-store-clippy-green.log`, `gates/p3-01-06-arch-test.log`,
  `gates/p3-01-06-core-types-test.log`.

### RED → GREEN rework (adversarial audit C1–C3 + must-fix majors)

Independent adversarial audit (2026-07-21) **FAIL**ed the initial GREEN on three critical
invariants plus incomplete crash/resume coverage:

| ID | RED finding | Fix (first principles) | Behavior test |
| --- | --- | --- | --- |
| **C1** | `phase=replacing` + temp gone deleted good live DB → stuck `RebuildingReadOnly` | Crash-safe replace: `live→bak`, `temp→live`, drop bak; if temp missing and live integrity-OK, complete as success | `replacing_phase_with_temp_gone_does_not_destroy_live_db` |
| **C2** | Tags SQLite-only; wipe+rebuild lost tag filter | Tags durable in `.lomo` `StateBody.tags`; rebuild rehydrates `tag`/`memo_tag` | `rebuild_rehydrates_tags_after_sqlite_wipe` |
| **C3** | Pin wrote full state with `trashed=false`, clobbering trash | `merge_write_state` read-merges pin/trash/tags | `delete_then_pin_merges_durable_state_without_clobber` + rebuild pin+trash |
| **M1** | `AfterCommittedMark` left revision unpublished forever | Publish plan durable on intent, meta set, then mark `Committed` | `crash_point_matrix_recovers_complete_once` (all crash points) |
| **M3** | Mid-indexing resume claimed, not tested | Real mid-index checkpoint + partial temp resume test | `mid_indexing_checkpoint_resumes_without_duplicate_or_stuck_gate` |
| **M2** | `corrupt_lomo_isolated` always 0 | Isolated count stored on checkpoint and returned | rebuild result field |

- GREEN rework command: `cd rust && cargo test -p lomo-store --locked`
- GREEN rework result (2026-07-21): **19 passed / 0 failed** (was 14; +5 adversarial contract tests).
- Companion rework gates:
  - `cargo clippy -p lomo-store --all-targets --locked -- --no-deps -D warnings` → **exit 0**
  - `cargo test -p lomo-architecture-tests --locked` → **25 passed / 0 failed**
  - `cargo test -p lomo-core --test types_contract --locked` → **6 passed / 0 failed**
- Adversarial probe re-run (scratch): C1 live survives + gate Ready; C2 `after_tag_hits=1`;
  C3 `durable_state pinned=true trashed=true`; M1 `store_hw>=1` after AfterCommittedMark recover.
- Logs: `gates/p3-01-06-rework-store-test.log`, `gates/p3-01-06-rework-store-clippy.log`,
  `gates/p3-01-06-rework-arch-test.log`, `gates/p3-01-06-rework-adversarial-probes.log`.
- Residual (honest, non-critical): FTS keyset cursor does not encode bm25 rank (multi-page FTS
  order incomplete); tag SQL still string-literal validated charset; full-deps store clippy may
  still fail on concurrent dirty `lomo-core` production (`indexing_slicing`) — use `--no-deps`;
  simplified `memos/{id}.md` model (not full workspace document patch).
- Intentionally **not** done: P3-07 reminder core, P3-08 AlarmManager port, P3-09 BoltFFI production
  adapters, P3-10 Room cutover, P3-11 perf/device/`just ci` stage exit. Production DI still Room-only.

## P3-07 reminder core (Rust owner)

### First principles

1. **Invariant:** Rust owns recurrence/fired/done/next-trigger and floating local wall-time policy
   (gap → first valid; overlap → earlier). Catch-up ≤1 fire per reminder session. Snooze is
   app-private, bound to workspace generation + opaque id + memo revision, never under `.lomo`.
2. **Axiom violation:** Kotlin AlarmManager coordinator owned schedule timing and multi-miss
   recovery without a pure owner plan → storm risk and DST ambiguity.
3. **Rebuild from truth:** pure `lomo-store::reminder` with platform zone transitions as input;
   mark-done/record-fired plan Markdown tokens via workspace mutation; snooze mutates app-private only.
4. **Edge enforcement:** stale revision → `stale_snapshot`; snooze under `.lomo` → fail closed.
5. **Tail deletion:** no snooze in `.lomo`/sync/archive; no multi catch-up storm.

### RED → GREEN

- RED: `cargo test -p lomo-store --locked --test reminder_core_contract` → no test target
  `reminder_core_contract` (pre-P3-07).
- GREEN: `cargo test -p lomo-store --locked` → **29 passed / 0 failed** (includes 10 reminder
  contract tests: DST gap/overlap, catch-up storm, snooze binding, commands).
- Companion: `cargo clippy -p lomo-store --all-targets --locked -- --no-deps -D warnings` → **exit 0**.
- Logs: implementer scratch `gates/p3-07-09-store-test*.log`, `gates/p3-07-09-store-clippy.log`.

## P3-08 AlarmManager pure port (Kotlin)

### First principles

1. **Invariant:** Kotlin only schedule/cancel + capability/mode/error reporting; plan semantics stay
   in Rust.
2. **Axiom violation:** schedule logic embedded in coordinator with AlarmManager + recurrence
   arithmetic mixed.
3. **Rebuild from truth:** `AlarmSchedulePort` / `AndroidAlarmSchedulePort` /
   `ReminderRollingWindowScheduler`; production scheduler routes through the port.
4. **Edge enforcement:** capability and platform errors are observable results, not swallowed.
5. **Tail deletion:** direct AlarmManager calls removed from coordinator body (port only).

### Residual tails (documented, not P3-10)

- markDone/recordFired still rewrite Markdown via domain repos until production cutover.
- Camera/share/widget paths must continue to submit commands only (no private full rewrite here).
- Production rebuildAll still builds plans from Room-era markers until P3-10 feeds Rust plans.

### RED → GREEN

- RED: missing `AlarmSchedulePort` / `ReminderRollingWindowScheduler` types (compile fail).
- GREEN: `./kotlin test --include-module=data --include-classes='com.lomo.data.reminder.AlarmSchedulePortTest'`
  → **4 passed / 0 failed**.
- Log: `gates/p3-08-kotlin-alarm.log`.

## P3-09 BoltFFI facade + dark-build data adapters

### First principles

1. **Invariant:** conversion-only native surface for store/reminder/rebuild; production DI remains
   Room-only until P3-10.
2. **Axiom violation:** store owner with no FFI/adapters cannot be cut over later without dual-stack.
3. **Rebuild from truth:** `lomo-native` store FFI methods + `DarkBuildStorePort` /
   `DarkBuildStorePagingSource` (unbound in production modules).
4. **Edge enforcement:** architecture test requires native→store dep and forbids production dual DI.
5. **Tail deletion:** no production MemoRepositoryModule binding to store; no dual-write flags.

### Architecture Impact

- **Owner:** `lomo-store` reminder + query; `lomo-native` conversion; Kotlin data dark-build adapters.
- **Boundary effect:** native depends on store; CoreEvent FFI gains `scopes: Vec<String>` (empty from
  legacy core events today).
- **Exception:** production DI still Room; dark-build adapters not production-wired.

### RED → GREEN

- RED: architecture forbade native→store; no `query_memos`/reminder/rebuild on LomoEngine.
- GREEN host:
  - `cargo test -p lomo-native --test store_ffi_contract --locked` → **5 passed / 0 failed**
  - `cargo clippy -p lomo-native --locked -- --no-deps -D warnings` → **exit 0** (lib surface)
  - `cargo test -p lomo-architecture-tests --locked -- stage_three` → **4 passed / 0 failed**
  - `./kotlin test --include-module=data --include-classes='com.lomo.data.engine.store.DarkBuildStorePagingSourceTest'`
    → **3 passed / 0 failed**
- Logs: `gates/p3-09-store-ffi*.log`, `gates/p3-07-09-native-clippy.log`,
  `gates/p3-07-09-arch-stage3.log`, `gates/p3-09-kotlin-paging.log`.

### Intentionally not done (superseded by P3-10)

- Production DI cutover / Room tail deletion — **done in P3-10 below**.

## P3-10 production atomic cutover + Room tail deletion

### First principles

1. **Invariant:** one local-data authority (`lomo-store`) owns SQLite projections, query/FTS,
   memo transactions, rebuild, and reminder business state. Kotlin never opens SQLite for memo
   index. Production dual-stack is forbidden.
2. **Axiom violation:** Room remained sole production persistence; dark-build adapters were unbound.
3. **Rebuild from truth:** freeze → fail-closed outbox drain gate → rebuild via store → delete
   legacy Room file → DI binds `StorePort` / `StoreMemo*` → same-wave Room family + tokenizer +
   outbox + version journal + FTS tail deletion.
4. **Edge enforcement:** architecture tests fail if Room/`androidx.room3`/`SearchTokenizer` remain
   after cutover; undrained outbox fails closed (never discard).
5. **Tail deletion:** Room DB/entity/DAO/migration/KSP/runtime/paging; Kotlin memo outbox;
   SearchTokenizer/IndexedTextLines; MemoFtsQueryBuilder; MemoQuery/SearchRepositoryImpl;
   MemoVersionJournal*; destructive Room paths; DarkBuild dual-path names.

### Architecture Impact

- **Owner:** `lomo-store` (+ `lomo-workspace`) sole local data loop; `lomo-native` conversion-only;
  Kotlin `data` adapts store + Android ports only.
- **Boundary effect:** Room absent from production dependency graph; memo repos are store-backed;
  sync metadata interim file-backed clean-slate (stage 5 owns sync redesign).
- **Exception:** DataStore preferences; AlarmManager schedule/cancel; interim file sync tables.

### RED (before cutover)

- RED command:
  `cd rust && cargo test -p lomo-architecture-tests --locked -- --nocapture stage_three_production_store_owner_is_unique_after_cutover`
  (or flip of dark-build Room requirement).
- Observed RED (pre-cutover contract): production `DatabaseModule` required `Room.databaseBuilder`;
  Room residual ban not active until cutover detector true.
- Why it proves absence: production graph still owned Room; store adapters not sole DI path.

### GREEN (after cutover)

- GREEN commands / results (2026-07-21):
  - `cargo test -p lomo-architecture-tests --locked -- stage_three` → **5 passed / 0 failed**
    (includes `stage_three_production_store_owner_is_unique_after_cutover`)
  - `cargo test -p lomo-architecture-tests --locked` → **26 passed / 0 failed**
  - `cargo test -p lomo-store --locked` → **29 passed / 0 failed**
  - `cargo clippy -p lomo-store --all-targets --locked -- --no-deps -D warnings` → **exit 0**
  - `cargo clippy -p lomo-native --locked -- --no-deps -D warnings` → **exit 0**
  - `cargo test -p lomo-native --test store_ffi_contract --locked` → **5 passed / 0 failed**
  - `./kotlin test --include-module=data --include-classes='com.lomo.data.engine.store.StorePagingSourceTest'`
    → **3 passed / 0 failed**
  - `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.RoomCutoverTest'`
    → **2 passed / 0 failed** (later expanded under P3-11 exit close)
  - Tail scan: no production `androidx.room3` / `SearchTokenizer` / dual-stack flags
    (`/tmp/grok-goal-4667e0973d7c/implementer/tail-scan.txt`)
- Clean-slate documented: old Room pin/history/sync metadata not migrated; re-scan rebuild only.
- Logs: implementer scratch `gates/p3-10-11-*.log`.

### Host suite residual (non-gate)

- Full product Kotlin suite GREEN was not re-asserted as a separate package beyond `just check` /
  `just ci` host floors (Room-era tests deleted; targeted compile/tests + suite floors).
- `just ci` coverage ≥80% lines and `just perf` durable double-pass are recorded under P3-11.

## P3-11 exit gates (PARTIAL 2026-07-21 — not formal full exit)

Honesty rework (adversarial audit C-ARM64, 2026-07-21): prior wording labeled full P3-11 / exit
surface GREEN from x86_64 AVD smoke alone. Contract hard device gate remains **API ≥ 26 arm64**.
x86_64 AVD GREEN authenticates **that packaged-ABI line only** and does **not** close the arm64
hard gate or stage formal exit.

### Device smoke — GREEN on x86_64 AVD packaged-ABI line only; arm64 hard gate OPEN

Adversarial audit C2: prior evidence claimed GREEN with log path `gates/p3-10-11-device-smoke.log`,
but that log was **missing**. Later rework re-ran smoke and archives **real** logs only.

- Command: `just device-smoke`
- Prior RED history (not claimed GREEN):
  - Device attempt 1: API **36** arm64 `RFCX911Z9PL` — smoke APK missing
    `lib/…/liblomo_native_jni.so` → runtime `UnsatisfiedLinkError`. Log:
    `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-device-smoke.log`.
  - Structural fix: `device_smoke` calls `validate_built_apk` **before** install so a dex-only APK
    cannot be claimed as smoke GREEN.
  - Attempt 2 (clean rebuild): APK packaging **validated** then **RED** — `no ready adb device`
    (physical device disconnected mid-wave). **No post-cutover arm64 run ends with
    `device smoke passed`.**
- **GREEN on x86_64 AVD line only (2026-07-21):** API **36** `x86_64` AVD `emulator-5554`
  (`sdk_gphone64_x86_64`, packaged ABI). APK validated (four-ABI `liblomo_native_jni.so` present),
  install Success, activity start ok, durable recovery relaunch, log ends with
  **`xtask: device smoke passed`** and process **EXIT 0**.
- Log: `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-device-smoke-retry.log`.
- **Arm64 hard device gate (plan/STAGE3-CONTRACT/AGENTS §5):** **OPEN / `pending_env`**. Live
  `adb devices` at honesty rework: only `emulator-5554` (x86_64). No arm64 device attached; do
  **not** invent arm64 GREEN. Re-run `just device-smoke` on API ≥ 26 arm64 when attached and
  archive as `gates/p3-11-device-smoke-arm64.log` before claiming full P3-11 exit.

### Packaging residual tails — GREEN (host packaging hygiene)

- Removed Room ProGuard keeps from `app/proguard-rules.pro`.
- Stripped Room `_Impl` / RoomDatabase / deleted-file IDs from `data/detekt-baseline*.xml`.
- Stripped Room-era symbols from `app/src/main/baselineProfiles/generated.txt` (no Room FTS /
  MemoDatabase / RoomBacked / `_Impl` residuals).
- Residual scan: proguard/detekt/baselineProfiles Room packaging tails **absent**
  (`/tmp/grok-goal-4667e0973d7c/implementer/tail-scan.txt`).

### Undrained MemoFileOutbox host fail-closed — GREEN

- Command:
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.RoomCutoverTest'`
- Result (2026-07-21): **7 passed / 0 failed** including real SQLite (JDBC) undrained-row path:
  assert fails with never-discard message; `cutoverDeleteIfDrained` never rebuilds/deletes.
- Log: `gates/p3-11-close-kotlin-cutover.log`.

### Optional cutover polish (same wave)

- `StoreWorkspaceTransitionRepository` no longer swallows `startRebuild` failures (fail closed).
- `StoreDatabaseInitializer` surfaces rebuild counters before legacy delete (rebuild integrity is
  the sole Room-vs-store compare after clean-slate).

### just check — GREEN (rework 2026-07-21 after C1 host tests)

- Command: `just check` from repository root.
- Result: **`xtask: check complete`** (exit 0); **726** Kotlin tests / 0 failed (was 711;
  +15 C1 host contracts).
- Store cutover invariants preserved: sole Rust store owner; no Room production return; no dual-stack.
- Log: `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-c1-c2-just-check.log`.

### C1 host coverage rework — GREEN (production owners executed on host)

Adversarial audit C1: zero-hit filter hid stage-3 production owners. Fix is **real host tests**,
not lowering `ZERO_HIT_MIN_LINES`.

- **Seam:** `StoreNativeBridge` (FFI edge); `BoltFfiStorePort(bridge=…)` maps bridge↔domain.
  Production DI: `BoltFfiStorePort(bridge = ManagedEngineSession)`.
- **Seam:** `AlarmPlatformGateway` + `AndroidAlarmManagerGateway`; `AndroidAlarmSchedulePort`
  mode/fallback policy host-tested via recording gateway (true AlarmManager stays device edge).
- **Tests (15 passed):**
  - `data/test/engine/store/BoltFfiStorePortTest.kt`
  - `data/test/repository/StoreMemoRepositoriesTest.kt` (Query/Mutation/Statistics)
  - `data/test/reminder/AndroidAlarmSchedulePortTest.kt`
- Targeted log: `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-c1-c2-kotlin-tests.log`.

### M2 Room schema export tail — GREEN (deleted)

- Deleted VCS `data/schemas/com.lomo.data.local.MemoDatabase/**` (~39 JSON exports).
- Production Room graph remains absent; no schema reintroduction.

### M4 shipping four-ABI ceiling — documented (not raised this wave)

- Ceiling remains **9_000_000** (`rust/xtask/src/native.rs`) for store+SQLite four-ABI stripped total.
- Prior `just ci` observed **8378120 ≤ 9000000**. Not silently raised further.
- Stage-0 compressed APK hard gate remains separate (`size-baseline.v1.json`). Prefer tighten toward
  ~8.6–8.8 MiB once sizes stabilize before stage close.

### just ci — GREEN (rework 2026-07-21 after C1 host tests)

- Command: `just ci` from repository root.
- Result: **`xtask: ci complete`** (exit 0).
- Rust llvm-cov TOTAL: **lines 80.02%** (regions 80.16%, functions 72.01%) — ≥80.
- Kotlin host JaCoCo filtered: **70.87%** (covered=20749 missed=8529, min 70).
- Stage-3 production owners no longer zero-hit excluded (JaCoCo class lines):
  - `BoltFfiStorePort` covered=74 missed=0
  - `StoreMemoQueryRepository` covered=19 missed=34
  - `StoreMemoMutationRepository` covered=19 missed=7
  - `StoreMemoStatisticsRepository` covered=8 missed=15
  - `AndroidAlarmSchedulePort` covered=36 missed=7
  - `AndroidAlarmManagerGateway` remains zero-hit (true AlarmManager edge; excluded OK)
- Four-ABI shipping size gate GREEN (**8378120 ≤ 9000000**).
- Log: `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-c1-c2-just-ci.log`.

### just perf — GREEN (durable double-pass; 2026-07-21)

Two consecutive `just perf` runs both reported **`conclusion: Pass`** and process **EXIT 0**.
Scale metric `markdown_scale_100k_memo_parse` established on both (100k memos / 909091 nodes,
`byte_stable=true`). Thresholds not weakened. Inconclusive was not re-labeled Pass.

| Pass | Log | conclusion | EXIT | scale p50 (ms) | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-just-perf1.log` | **Pass** | **0** | 1116.39 | established attempt 1/2; device api=36 abi=arm64-v8a probes present |
| 2 | `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-just-perf2.log` | **Pass** | **0** | 883.33 | attempt 1/2 excluded (p50 unstable); established attempt 2/2; optional `git_bare_push_fetch_rebase` excluded (unstable); cold-start optional skipped mid-boot |

Required host metrics established on both runs; optional I/O metrics may be absent without inventing Pass.

### M1 cutover count/digest compare — GREEN (host; 2026-07-21)

Auditor major M1: cutover accepted `memosIndexed >= 0` (always true). Closed with real compare.

1. **Invariant:** before discarding legacy Room, workspace memo file count + aggregate content
   digests + attachment counts must equal store projection evidence; mismatch fails closed.
2. **Axiom violation:** Kotlin only checked non-negative rebuild counters; Room deleted without
   workspace↔store digest agreement at the cutover edge.
3. **Rebuild from truth:** `lomo-store` rebuild Compare phase computes sorted `(memo_id,
   fingerprint)` digests for workspace files vs store rows, requires exact memo/file/attachment
   count match, returns evidence on `RebuildResult`; Kotlin `RoomCutover.assertCutoverCompare`
   re-validates before `deleteLegacyRoomDatabase`.
4. **Edge enforcement:** mismatch throws; `cutoverDeleteIfDrained` never deletes on compare fail;
   undrained outbox still never rebuilds/deletes.
5. **Tail deletion:** weak `>= 0` counter check removed from `StoreDatabaseInitializer`.

- GREEN commands / results:
  - `cargo test -p lomo-store --locked --tests` → all GREEN (incl. rebuild digest fields)
  - `cargo clippy -p lomo-store --all-targets --locked -- -D warnings` → exit 0
  - `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.RoomCutoverTest'`
    → **9 passed** including digest mismatch → no delete
- Logs: `/tmp/grok-goal-4667e0973d7c/implementer/gates/p3-11-m1-m3-*.log`

### M3 tags/images projection — GREEN (host; 2026-07-21)

Auditor major M3: `StoreMemoSummary`→domain always `tags=emptyList()`, `imageUrls=emptyList()`;
`getTagCountsFlow` always empty.

1. **Invariant:** list/get/stats surfaces project durable + content-derived tags and non-audio
   attachment paths owned by the store (same workspace render tag/attachment law).
2. **Axiom violation:** FFI/domain mapping hard-coded empty lists; `attachment_ref` never written.
3. **Rebuild from truth:** content facts via `lomo_workspace::render_markdown` on write/rebuild;
   tags + `attachment_ref` projected on `query_memos`/`get_memo`; mapped through BoltFFI →
   `StorePort` → `toDomainMemo` / tag-count aggregation.
4. **Edge enforcement:** invalid tags fail closed on write; host tests assert non-empty tags/images
   and tag counts on the shipped adapter path.
5. **Tail deletion:** emptyList stubs removed from `StorePagingSource.toDomainMemo` and empty
   tag-count flow.

- GREEN commands / results:
  - `cargo test -p lomo-store --test query_cursor_contract --locked` → tags/images projection GREEN
  - `cargo test -p lomo-native --test store_ffi_contract --locked` → **8 passed**
  - Kotlin: `BoltFfiStorePortTest` + `StorePagingSourceTest` + `StoreMemoRepositoriesTest` GREEN
    (tags/images + tag counts on host)
- Logs: same `p3-11-m1-m3-*.log` set.

## Stage status

P3-10 cutover is **GREEN**. P3-11 exit surface is **PARTIAL** (2026-07-21 honesty rework) — **not**
formal stage-3 / full P3-11 exit:

| Surface | Status |
| --- | --- |
| C1 host owner tests | **GREEN** |
| `just check` / `just ci` | **GREEN** |
| `just perf` durable double-pass | **GREEN** (both Pass / EXIT 0; authenticated logs) |
| Packaged-ABI `just device-smoke` on API 36 x86_64 AVD | **GREEN** (that line only; retry log) |
| **API ≥ 26 arm64 hard device gate** | **OPEN / `pending_env`** |
| M1 count/digest compare | **CLOSED** |
| M3 tags/images projection | **CLOSED** |

Production dual-stack remains forbidden. Full P3-11 GREEN requires a real post-cutover arm64
`just device-smoke` with archived `device smoke passed`.
