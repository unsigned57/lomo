# Android performance tooling decision

This file records the benchmark/tracing dependency decision and startup/scroll workload bounds.
Operational usage and device measurement rules live in
[Android performance](../../quality/android-performance.md); gates live in
[Quality](../../quality/README.md).

## Dependency decision

Requirements: work with the Amper application and standalone AndroidX benchmark harness; identify
Compose work in traces; produce Baseline/Startup Profile packaging inputs; compare the same
populated workspace across compilation modes while keeping business rules and JNI ownership intact.

Research scope: official Android performance/Compose documentation, AndroidX release notes,
resolved Maven metadata/bytecode, and installed Android SDK tools (2026-10-28).

| Candidate | Decision and evidence |
| --- | --- |
| AndroidX Macrobenchmark 1.5.0 | Retain the existing harness for UIAutomator journeys, profile collection, compilation-mode control, JSON metrics, and Perfetto artifacts. |
| Compose `runtime-tracing` 1.13.0-alpha02 | Add as an app runtime-only dependency aligned with Compose UI. Uses the AndroidX in-process tracing driver and records `MemoCard`/paging composable slices in release traces. |
| SDK `profgen` | Use the official HRF parser rather than maintaining a profile grammar. The adapter rejects stderr error diagnostics even when `profgen` exits `0`, and rejects comment-only input. |
| JankStats 1.0.0 | Defer: useful for field frame monitoring, does not provide composition attribution or compilation profiles. |
| `tracing-perfetto-binary` 1.0.1 | Rejected: `runtime-tracing` uses the newer in-process driver supported directly by Macrobenchmark 1.5. |

Sources: [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[composition tracing](https://developer.android.com/develop/ui/compose/tooling/tracing),
[Compose Runtime releases](https://developer.android.com/jetpack/androidx/releases/compose-runtime),
[Tracing releases](https://developer.android.com/jetpack/androidx/releases/tracing),
[SDK profile tooling](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure),
[JankStats](https://developer.android.com/topic/performance/jankstats).

Architecture Impact: `lomo-xtask` owns measurement/provenance and SDK `profgen` validation; `app`
owns profileable OS configuration and state-dependent benchmark anchors. Domain, storage, and FFI
authority are unchanged.

## Startup and scroll workload bounds

- **First-draw gating**: security restoration, theme setup, widget privacy/mount observation, and
  foreground authority resnapshot start immediately. Maintenance, shortcuts, update checks,
  foreground sync/reminder registration, and LAN discovery stay behind lazy factories until the
  Activity's first draw completes (including lock-screen draws), removing the draw listener before
  releasing deferred work.
- **Card and UI-model caching**: memo body size animation belongs solely to the reusable card body
  without duplicate app-wrapper height animation. A bounded 256-entry UI-model cache reuses parsed
  Markdown and presentation plans when only metadata changes, copying current metadata onto the new
  row while invalidating on body, preview/full mode, media context, or expansion changes.
- **Viewport image prefetch**: prioritizes visible rows, tracks scroll travel direction, widens its
  candidate window for larger row jumps, cancels stale requests, respects the request budget, and
  rejects invalid/overflowing viewport coordinates before planning.
