# Stage-7 implementation evidence

> Status: **Stage-7 production convergence and non-performance gates GREEN (2026-08-03)**.
> Rust is the sole owner of migrated core behavior; Kotlin retains the documented Android/UI and
> peripheral adapters. Recovery, transitional-tail deletion, architecture locks, unified host
> gates, the release SO size ceiling and API 36 arm64 device smoke are verified. Runtime
> performance, capacity, soak and real-provider lifecycle measurements remain explicitly outside
> this closure.
>
> Behavior contract: `fixtures/baseline/STAGE7-CONTRACT.md`.

## RED

Real architecture RED observed on 2026-08-01:

```text
$ rustup run 1.97 cargo test --manifest-path rust/Cargo.toml \
    -p lomo-architecture-tests --test architecture --locked \
    stage_seven_shell_convergence_and_recovery_are_structurally_locked
running 1 test
test tests::stage_seven_shell_convergence_and_recovery_are_structurally_locked ... FAILED
stage 7 requires versioned fixtures/baseline/STAGE7-CONTRACT.md
test result: FAILED. 0 passed; 1 failed
```

Recovery behavior RED was separately observed before implementation: domain tests failed on the
missing `toDiagnosticReport` / `RecoveryWorkspaceKind` surface.

## GREEN

Real targeted and repository runs on 2026-08-03:

```text
$ rustup run 1.97 cargo test --manifest-path rust/Cargo.toml \
    -p lomo-architecture-tests --test architecture --locked \
    stage_seven_shell_convergence_and_recovery_are_structurally_locked
# EXIT:0 — Stage-7 docs/profile, typed recovery and transitional-tail locks passed.

$ ./kotlin test --include-module=domain \
    --include-classes='com.lomo.domain.model.EngineRecoveryDiagnosticReportTest'
# EXIT:0 — 2/2; fixed-schema bounded export excludes raw diagnostic, path, memo body,
# remote secret and capability token.

$ ./kotlin test --include-module=data \
    --include-classes='com.lomo.data.engine.ManagedEngineSessionTest'
# EXIT:0 — 23/23; restricted SQLite rebuild candidate, same-workspace Ready reopen,
# permission-revoked candidate rejection and previous-owner retention.

$ ./kotlin test --include-module=app \
    --include-classes='com.lomo.app.feature.main.MainViewModelTest'
# EXIT:0 — 44/44; secret-free diagnostic export and Ready-after-rebuild UI state.

$ rustup run 1.97 cargo test --manifest-path rust/Cargo.toml \
    -p lomo-store --test transaction_contract \
    disk_full_during_markdown_write_fails_closed_and_resumes_once --locked
# EXIT:0 — Linux /dev/full produced typed memo_temp_write_failed; no body/revision/event
# published; removing the fault let the same operation resume and replay exactly once.

$ just check
# EXIT:0 — strict Rust Clippy/tests/docs/property fuzz, four-ABI dev native, Kotlin compile,
# Detekt/test-style/Android lint/architecture/string parity and host tests; app 613/613.

$ just ci
# EXIT:0 — Rust coverage + cargo deny, four-ABI release pack, Kotlin/Android full gate,
# Kotlin coverage 71.56% (13346 covered, 5303 missed; minimum 70%) and APK ABI validation;
# four-ABI release SO total 43,654,804 bytes <= 46,530,532-byte ceiling.

$ just device-smoke
# EXIT:0 — SM_S9110, API 36, arm64-v8a; APK install, launch and durable-recovery relaunch passed.
```

The corruption/replay matrix is additionally covered by `lomo-store` rebuild/transaction tests,
`lomo-core` engine recovery tests and `lomo-lan` journal/session/commit tests: corrupt `.lomo` and
journal bytes fail closed or are isolated without deleting the tree; crash/replay paths publish at
most once; permission denial is typed before LAN bind and revoked workspace candidates never
replace the previous Ready owner.

## Residual OPEN

| Gate | Status |
| --- | --- |
| Stage-7 architecture test after docs/profile regeneration | **GREEN** |
| Kotlin formatting, static analysis and affected suites | **GREEN** |
| Kotlin domain/data transitional-tail audit and deletion | **GREEN** |
| Disk-full, permission-revoke, SQLite/`.lomo` corruption and replay fault matrix | **GREEN (host)** |
| API >= 26 arm64 `just device-smoke` | **GREEN** (SM_S9110, API 36) |
| `just check` / `just ci` | **GREEN** (2026-08-03) |
| Real SAF provider grant-revoke/restore lifecycle | **OPEN / `pending_env`** |
| 100,000 memo / 20 GB / 10,000-change capacity and long soak | **OPEN (excluded)** |
| Four-ABI release SO shipping-size gate | **GREEN** (43,654,804 <= 46,530,532 bytes) |
| Startup/query/parse budgets | **OPEN (excluded)** |

## Non-claims

- Device load/recovery, four-ABI packaging and its release SO ceiling are claimed only from their
  real gates above; no physical storage-quota, runtime performance, capacity or soak result is
  inferred.
- A derived-index rebuild is not evidence that `.lomo` corruption can be repaired; that state must
  remain read-only.
- A successful document-export unit test is not evidence of a real SAF provider grant lifecycle.
- The real SAF grant-revoke/restore provider lifecycle remains `pending_env`; host permission tests
  prove fail-closed ownership and typed outcomes, not provider-specific Android behavior.
- This evidence closes Stage 7 under the requested non-performance scope. It does not rewrite the
  roadmap's performance/capacity exit criteria as passed.
