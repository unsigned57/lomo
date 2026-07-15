# Size and performance baselines

| File | Contents |
| --- | --- |
| `STAGE0-STATUS.md` | **Authoritative** stage-0 status (must match reality; other docs defer to it) |
| `size-baseline.v1.json` | Exact `.so` / APK byte sizes, +15% hard gate, performance metrics |
| `candidate-matrix.v1.md` | Dependency/feature decisions with honest scope limits |
| `feasibility-device-size.v1.json` | Combined candidate SO sizes + exact LOMO retention markers |

## Size gate

- Canonical APK and `.so` numbers live **only** in `size-baseline.v1.json`.
- Hard gate: final compressed universal APK ≤ `debug_universal_compressed_bytes × 1.15`.

## Performance gate (`just perf`)

- Two rounds, stability: `|Δp50| ≤ max(10% of max p50, 1 ms)`.
- Required host metrics: planner trio, sqlite, markdown fixture set, `markdown_scale_100k_memo_parse`.
- Scale metric must be measured in an **isolated** `lomo-feasibility scale-markdown-bench` process and record:
  - full-corpus p50/p95 (throughput)
  - `peak_rss_bytes` (process VmHWM)
  - `result_count` (memo files parsed)
  - `warm_path_p50_ms` (single-memo warm path)
- Time numbers are relative host/device baselines, not product SLA.

## Stage-0 status

**Authoritative status is `STAGE0-STATUS.md`.** Do not mark overall closed here if that file is partial.
