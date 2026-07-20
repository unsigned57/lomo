# Size and performance baselines

> Stage-0 UniFFI/JNA numbers remain the immutable pre-migration baseline. BoltFFI/JNI migration
> evidence must be recorded separately under `STAGE1-EVIDENCE.md` and cannot overwrite these files.

| File | Contents |
| --- | --- |
| `STAGE0-STATUS.md` | **Authoritative** stage-0 status (must match reality; other docs defer to it) |
| `size-baseline.v1.json` | Exact `.so` / APK byte sizes, +15% hard gate, performance metrics |
| `ffi-transport-baseline.v1.json` | Pre-BoltFFI UniFFI/JNA transport baseline + BoltFFI pin decision |
| `STAGE1-EVIDENCE.md` | Stage-1 / BoltFFI migration RED/GREEN evidence ledger |
| `candidate-matrix.v1.md` | Dependency/feature decisions with honest scope limits |
| `feasibility-device-size.v1.json` | Combined candidate SO sizes + exact LOMO retention markers |

## Size gate

- Canonical APK and `.so` numbers live **only** in `size-baseline.v1.json`.
- Hard gate: final compressed universal APK ≤ `debug_universal_compressed_bytes × 1.15`.

## Performance gate (`just perf`)

- Two rounds, stability: `|Δp50| ≤ max(10% of max p50, 1 ms)`.
- Required host metrics: planner trio, sqlite, markdown fixture set, `markdown_scale_100k_memo_parse`.
- Required **non-scale** metrics measure in **quiet rounds** (no optional HTTPS/git/device cold-start interleaved).
- Scale metric is measured **after** non-scale required rounds, in **consecutive isolated**
  production-owner processes (`lomo-workspace` `workspace_scale_benchmark`) so planner/sqlite/fixture
  work cannot thrash page cache between the two scale p50 observations. Same 10% gate; up to one
  extra dual-round attempt under that bar (never invents Pass from a noisy pair).
- Scale metric records:
  - full-corpus p50/p95 (throughput; two untimed full warmups then timed samples; more samples than
    microbenchmarks so median is host-noise resistant)
  - `peak_rss_bytes` (process VmHWM)
  - `result_count` (memo files parsed; must be 100000)
  - `warm_path_p50_ms` (single-memo warm path)
- `just perf` exits **non-zero** when conclusion is `Inconclusive` or `Fail` (exit 0 = product-pass).
- Time numbers are relative host/device baselines, not absolute product SLA hardware claims.

## Stage-0 status

**Authoritative status is `STAGE0-STATUS.md`.** Do not mark overall closed here if that file is partial.
