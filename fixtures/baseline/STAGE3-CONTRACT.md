# Stage-3 local data loop behavior contract

> Status: **locked; post P3-10 production store cutover; dual-stack forbidden; stage exit open until P3-11**
>
> This document fixes the behavior and evidence required to close stage 3. It is not evidence that
> every exit bar is green. Actual RED/GREEN commands and results are recorded in
> `STAGE3-EVIDENCE.md` alongside the implementation that produces them.
>
> **Stage-2 formal exit is a hard entry prerequisite** (`STAGE2-EVIDENCE.md` status **Stage 2
> closed**). Stage-3 packages must not claim GREEN while that exit is unrecorded.

## Behavior Contract

- **Unit under test:** `lomo-store` SQLite query projections, FTS5 + pure-Rust CJK tokenizer,
  memo transaction state machine, `.lomo/` durable format, rebuild state machine, reminder business
  state, BoltFFI/query adapters, and the atomic Room → Rust production cutover with Room tail
  deletion.
- **Owning layer:** `lomo-store` for SQLite projections, search/query/cursor, transactions, history
  projection, rebuild, and reminder business state; `lomo-workspace` for Markdown/media semantic
  facts and document patch; `lomo-core` for engine revision/events/jobs; `lomo-native` for FFI
  conversion only; Kotlin `data` for Android platform ports (Paging adapter, AlarmManager
  schedule/cancel, SAF, notifications) and the sole generated-binding adapter until cutover retires
  Room.
- **Priority tier:** P0 for ownership/invariant packages; P1 for presentation and performance.
- **Capability:** own the full local data loop in Rust so Markdown/media/`.lomo` remain durable
  facts, SQLite stays rebuildable, mutations never half-succeed, rebuild forbids write/sync, Kotlin
  never opens SQLite, tokenizer is pure Rust (no JVM `UnicodeBlock`), multi-char CJK never degrades
  to unbounded unigram-OR, `PageCursor` returns stale on mismatch, and production uses a single
  stack (no Room dual ownership / dual-write / feature-flag parallel path) after atomic cutover.

## Fundamental invariants

1. **Durable facts:** Markdown workspace files, media, and `.lomo/` records are durable facts.
   SQLite is a rebuildable query projection only. Database corruption deletes/rebuilds SQLite only
   — never `.lomo/` with it.
2. **No half-success mutation:** each create/update/delete/restore/pin/history-restore is one
   operation-id–idempotent nine-step machine. On crash/recovery the mutation is either fully
   recoverable once or remains explicitly uncommitted. Catch-and-return-empty is forbidden.
3. **Rebuild is read-only for writers:** while `RebuildingReadOnly`, write and sync are rejected.
   Old index may display the last verified snapshot but must not accept writes or dual-write.
4. **Kotlin never opens SQLite:** production Kotlin must not open or own the store database.
   Query/paging/reminder ports consume Rust surfaces only after cutover; during dark-build Room
   remains the sole live production persistence authority.
5. **Pure Rust tokenizer:** CJK/emoji/script detection is pure Rust Unicode. JVM
   `Character.UnicodeBlock` / production `SearchTokenizer` must not remain as authority after
   cutover.
6. **Multi-char CJK query:** multi-character CJK segments must use adjacent bigram phrase / NEAR
   ordered query plans — never unbounded unigram-OR that explodes recall and ranking.
7. **PageCursor stale-on-mismatch:** `PageCursor` encodes query fingerprint, sort key, high-water
   revision, and tokenizer version. Mismatch with current query/revision returns `stale_cursor`;
   no silent offset full-table scan fallback.
8. **Single production stack:** production dual-stack is forbidden. DI must not bind Room and
   `lomo-store` together; no feature flag dual-write; no progressive consumer-by-consumer production
   switch. Dark-build until atomic cutover (P3-10). Room tail deletion only at the cutover wave.
9. **Reminder ownership:** Rust owns recurrence/fired/done/snooze semantics and next-trigger plan.
   Kotlin `AlarmManager` is schedule/cancel (plus capability/error reporting) only.
10. **Entry gate:** stage-2 formal exit (`STAGE2-EVIDENCE.md` **Stage 2 closed**) is required before
    any stage-3 GREEN claim.

## Resource limits (stage-3 store surface)

| Surface | Limit / rule | Failure |
| --- | --- | --- |
| Query page | bounded page only (UI Paging adapter) | `resource_limit` / validation |
| Cross-FFI list | no full list transfer | architecture / contract fail |
| Full body cache | no whole-workspace body cache | architecture / contract fail |
| Unknown schema | reject open; no destructive downgrade | fail closed |
| `.lomo` record | magic + schema + length + checksum | fail closed; isolate; no auto-delete |

Over-limit handling never clamps, truncates, or returns partial success.

## Scenarios

GWT form below uses explicit Given / When / Then tokens so architecture locks can verify the
scenario contract without relying on prose alone.

### Stage entry and scaffolding (P3-00)

- Given stage-2 formal exit is unrecorded, When implementers claim stage-3 GREEN, Then architecture
  tests fail closed.
- Given `fixtures/baseline/STAGE3-CONTRACT.md` or `STAGE3-EVIDENCE.md` is missing, When architecture
  tests run, Then they fail with a named missing invariant.
- Given the real `lomo-store` owner crate is missing, empty, or not a workspace member, When
  architecture tests run, Then they fail.
- Given dark-build `lomo-store` sources exist, When production Kotlin DI is inspected before P3-10,
  Then Room remains the sole live persistence authority and production dual-stack store DI /
  dual-write feature flags are absent.

### SQLite open and schema (P3-01)

- Given a store database open, When prerequisites run, Then `foreign_keys=ON`, WAL, busy timeout,
  schema/user_version check, and integrity fast-check execute.
- Given an unknown higher schema version, When open is attempted, Then open fails closed without
  destructive downgrade.

### Tokenizer and FTS (P3-02 / P3-03)

- Given CJK/emoji/latin text, When index tokens are produced, Then tokenization is pure Rust and
  does not call JVM `UnicodeBlock`.
- Given a multi-character CJK query segment, When a query plan is built, Then it is adjacent
  bigram/NEAR ordered — not unbounded unigram-OR.
- Given page cursor and query/revision mismatch, When the next page is requested, Then the result is
  `stale_cursor` (PageCursor stale).

### Durable `.lomo` and transactions (P3-04 / P3-05)

- Given mutation killed mid-step, When the engine recovers, Then state either completes once or stays
  explicitly pending — never half-success.
- Given checksum/schema damage in `.lomo`, When open/decode runs, Then fail closed, isolate the
  record, do not auto-delete.
- Given a successful memo commit, When `CoreRevision` is published, Then the event carries a bounded
  `InvalidationScope` set (no row payloads).
- Given an `EventSequence` gap (or regression) at a consumer, When the next event is observed, Then
  the consumer must full-invalidate (`InvalidationScope::Full` / full snapshot re-fetch) rather than
  apply partial scopes against a lost sequence.

### Rebuild (P3-06)

- Given rebuild is active, When write or sync is submitted, Then both are rejected.
- Given SQLite corrupt and workspace intact, When rebuild completes, Then projections restore from
  Markdown/media/`.lomo` without deleting durable facts.

### Reminder ports (P3-07 / P3-08)

- Given DST gap/overlap or zone change, When the plan rebuilds, Then floating local-time policy
  applies (gap → first valid instant; overlap → earlier instant).
- Given Kotlin AlarmManager adapter, When schedule/cancel is invoked, Then Rust owns semantics and
  Kotlin only executes the port.

### Production switch and Room tail deletion (P3-10)

- Given all prior packages GREEN and outbox drained, When P3-10 lands, Then production DI binds
  Rust store only, Room is removed from the production graph, and dual-stack paths are gone.
- Given Room remains after claimed cutover, When architecture tests run, Then the build fails.

## Observable outcomes

- Architecture-test failures that name missing STAGE3 files, missing `lomo-store` owner, production
  dual-stack wiring, or stage-2 formal exit absence.
- Constrained open/schema/tokenizer/query/cursor/transaction/rebuild results with structured
  `LomoError` category/code (packages P3-01+).
- `PageCursor` stale vs stable page behavior; no offset full-scan fallback on mismatch.
- Device-smoke and performance numbers only when the corresponding package claims them; no fictional
  GREEN.

## RED/GREEN evidence format

Every implementation package must record in `STAGE3-EVIDENCE.md`:

1. **RED command** — narrowest command that should fail before the capability exists.
2. **Observed RED** — exact assertion/error text.
3. **Why it proves absence** — what capability is missing.
4. **GREEN command** — same or strengthened command after the fix.
5. **GREEN result** — real pass counts / exit status. First-run GREEN without a prior RED is invalid
   and must be strengthened.

Do not claim GREEN from compilation alone when behavior tests exist. Do not claim stage-3 closed,
P3-10 switched, Room tail deletion GREEN, or production dual-stack GREEN from dark-build packages.

## Device policy

- Product `minSdk` / NDK API remain **26**.
- Hard device gate for stage-3 product claims that require smoke: **API ≥ 26 arm64**
  `just device-smoke` (real device accepted; stage-2 evidence used API 36 arm64).
- Fixed API 26 x86_64 AVD is **non-claim** / `pending_env` when absent. It must never be marked
  GREEN without a real run and does not block stage-3 dark-build entry after stage-2 formal exit.
- Host unit tests are not a substitute for the arm64 hard device gate when a package claims device
  behavior.

## Production ownership (dark-build → post cutover)

| Phase | Required | Forbidden |
| --- | --- | --- |
| Dark-build (P3-00..P3-09) | Room sole live production persistence; `lomo-store` dark-build only | Production dual-stack DI, dual-write, feature-flag parallel path |
| Post cutover (P3-10+) | `lomo-store` sole local-data owner via data adapters | Room database/entity/DAO/migration in production graph |
| Always | Rust reminder semantics; Kotlin AlarmManager schedule/cancel only | Kotlin SQLite open; JVM UnicodeBlock tokenizer authority after cutover |

## TDD proof

- **Current evidence:** see `STAGE3-EVIDENCE.md`. Only entries with an observed GREEN result are
  implemented claims.
- After P3-10, production DI binds `StorePort` / `StoreMemo*` adapters; Room family is deleted from
  the production graph. Differential Room runtime is deleted with the cutover wave.
- Clean-slate re-scan: old Room pin/history/sync metadata are not migrated.

## Excludes

- Performance/100k durable gates and full stage-3 close claims while P3-11 rows remain open or
  `pending_env` (do not invent GREEN).
- Sync backend redesign (stage 5), media lifecycle ownership (stage 4).
- Production dual-stack, dual-write, progressive consumer migration, and feature-flag parallel paths.
- Fictional GREEN, empty marker crates, and silencing architecture RED without real types/tests.
- Engine actor dual-write of store commits into the existing `CoreEvent` bus (P3-05 publishes
  revision+scope types; full engine bus publish of store commits remains a later integration package).
- Dedicated `list_history` FFI pagination (version list may return empty until that surface lands;
  history restore uses store command).

## Exit evidence required (stage 3 close)

- Stage-2 entry gates remain GREEN under their own contract; stage-2 formal exit stays recorded.
- CRUD, search, stats, trash, pin, history, recovery, and rebuild are Rust-exclusive.
- Reminder semantics/state/plan are Rust-exclusive; Kotlin only executes Android alarm.
- Kotlin domain/data retain adapter/presentation only for migrated capabilities.
- Room is absent from the production dependency graph.
- `just check`, `just ci`, and API ≥ 26 arm64 device smoke have real command output in evidence.
- Performance gates (warm query / home page p95 ≤ baseline+10%, 100k rebuild, APK size) pass with
  durable evidence.
- `ARCHITECTURE.md` and this contract match code facts.
