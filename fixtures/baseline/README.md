# Size and performance baselines

| File | Contents |
| --- | --- |
| `size-baseline.v1.json` | Exact `.so` / APK byte sizes and +15% hard gate |
| `candidate-matrix.v1.md` | P0-11 dependency/feature/volume decision matrix |

Time and RSS baselines are produced by `just perf` (two rounds, 21 samples, 10% relative
stability with a 1 ms absolute floor). Established metrics land in `size-baseline.v1.json`
under `performance.metrics`. They are **relative host/device** numbers, not product SLA.

I/O-heavy probes (HTTPS fixture, git bare transport) may be excluded in a given run when
cross-round variance exceeds the stability gate; size zero-points and stable CPU-bound
metrics still form the stage-0 baseline.
