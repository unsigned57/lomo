# Android Device Performance and Profiles

Read this file for on-device Android benchmarks, performance probes, three-provider sync sweeps,
and Baseline/Startup Profile collection. [Quality](README.md) owns gates and the JSON command
protocol; [Android UI test](android-uitest.md) owns Maestro interaction flows;
[Architecture](../ARCHITECTURE.md) owns module authority.

## Evidence honesty and device selection

All `just build android benchmark` and `just perf device` commands require an attached adb device
(`--redroid` provisions the pinned `lomo-redroid` container when none is attached; see
[Android UI test](android-uitest.md#capability) for container prerequisites and serial selection).

Emulator and debuggable runs carry `suppressed_errors=EMULATOR,DEBUGGABLE` and establish relative
container regression evidence only — never physical-device release truth.
Redroid lacks display frame completion data, so `FrameTimingMetric` produces no samples on the
container (`application_trace` channel). The harness measures main-thread/RenderThread work via
`TraceSectionMetric`, process memory via `MemoryUsageMetric`, and launch readiness via semantic UI
traces. Physical devices with complete display timelines retain `display_frames`.
Never compare `application_trace` metrics directly against TTID, TTFD, or frame overrun.

## Macrobenchmark (`just build android benchmark`)

`just build android benchmark [scenario] [--redroid]` runs the standalone AndroidX Macrobenchmark
harness (`apps/android/benchmark`). Evidence lands in
`reports/android-benchmark/android-benchmark.v1.json` alongside raw `*-benchmarkData.json` and
referenced Perfetto traces (copied directly from scenario `profilerOutputs`, never re-decoded through
a shell trace decoder). Device stabilization (`stylus_handwriting_enabled=0`, animation scale `0`) is
deliberately skipped so frame/motion measurements remain valid.

## Device performance planes (`just perf device`)

```bash
just perf device [startup|cuj|engine|workspace|system|sync|profile|release|all]... \
  [--corpus device] [--backend saf|direct] [--redroid] [--trace]
```

Evidence lands in `reports/android-device-perf/device-perf.v1.json`. Runs fail closed on unknown
planes/flags, require `--corpus device` activation before measuring or updating baselines, and never
claim unexecuted scenarios.

- **Corpus (`--corpus device`)**: injects the deterministic workspace-shaped corpus (SAF under
  `/sdcard/Documents` granted via DocumentsUI, or Direct via `run-as` on debuggable builds;
  host-tier layouts are rejected because only dated `yyyy_MM_dd` documents reconcile).
- **Engine baseline (`engine`)**: persists trend snapshots in
  `reports/device-perf-engine-baseline.v2.json`, keyed independently by backend (`saf` / `direct`)
  and metric. Missing history reports `no_baseline`; malformed history or samples fail closed; fewer
  than 5 samples or p50 deltas >15% report `inconclusive`. Compare only on the same device and corpus.
  Dirty-rebuild probes modify the workspace before every sample (`engine-rebuild-dirty-each-sample`).
- **Workspace plane (`workspace`, explicit only — excluded from `all`)**: resets test-owned roots
  and measures 5 fresh-index samples, 5 cold reopens, 5 cross-workspace switches, and 5 reconciles
  (external append + new dated document), checking memo/task counts and markers. SAF first index
  measures grant confirmation through the first indexed card; Direct first index, switch, and
  reconcile measure data calls through `Ready`, with first-page reads reported separately.
- **Sync plane (`sync`, explicit — requires `--corpus device --backend direct`)**: resets an
  app-private corpus, starts host WebDAV and S3 HTTP fixtures over `adb reverse`, and stages a bare
  Git remote in-app (no personal credentials). Fails on empty snapshots, missing cycles, held
  transfers, or zero-upload sweeps; uses the mounted session's private projection. Covers plain HTTP
  and Git `file://` adapter upload/verification and conflict planning (excludes public HTTPS/SSH,
  SAF sync, and remote-only import proof).
- **Release plane (`release`, explicit)**: measures cold/warm/hot startup and scrolling on the signed,
  profileable release APK (`API 29+`) under `none`, `partialRequire`, and `full` compilation modes,
  regranting the test SAF folder across install swaps and restoring the debug APK afterwards. Fails
  if cards are missing or scroll never moves the first card offscreen.

## Baseline and Startup Profiles (`profile`)

`just perf device profile --corpus device --backend saf --write` is the sole publisher of measured
profile inputs:

- `apps/android/app/src/main/baselineProfiles/generated.txt`
- `apps/android/app/src/main/baselineProfiles/startup-prof.txt`
- `apps/android/app/baseline-profile.json` (receipt binding profile bytes to captured APK SHA-256,
  corpus digest, device fingerprint, and source revision)

Combine `profile release ... --write` to collect, publish, and measure in one invocation. Collection
resets the fixed test-owned SAF corpus before seeding. Never hand-edit measured profile inputs;
recollect when critical journeys, dependencies, or startup paths change.

`just check` / `just build android release` validates receipt hashes and HRF syntax via SDK
`profgen validate` (failing on empty profiles, >32 MiB files, or exit-0 error diagnostics). Before
claiming Startup Profile DEX layout coverage in a release build, verify R8 metadata reports
`dexFiles[].startup: true` matching the APK DEX checksum.
