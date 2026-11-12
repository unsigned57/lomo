# Android performance tooling decision

The performance workflow measures startup and real list scrolling, publishes device-collected
compilation profiles, and preserves enough provenance to distinguish Debug, release and emulator
results. Command applicability and verification remain owned by [Quality](../../quality/README.md).

## Dependency decision

Requirements: work with the existing Amper application and standalone AndroidX benchmark harness;
identify Compose work in traces; produce Baseline/Startup Profile packaging inputs; compare the
same populated workspace across compilation modes. Business rules and JNI ownership stay intact.

Research scope: official Android performance/Compose documentation, AndroidX release notes,
resolved Maven metadata/bytecode, and the installed Android SDK tools (2026-10-28).

| Candidate | Decision and evidence |
| --- | --- |
| AndroidX Macrobenchmark 1.5.0 | Retain the existing harness. It already supplies UIAutomator journeys, profile collection, compilation-mode control, JSON metrics and Perfetto artifacts. |
| Compose runtime-tracing 1.13.0-alpha02 | Add as an app runtime-only dependency, aligned with the existing Compose UI version. The resolved artifact uses the AndroidX in-process tracing driver; its initializer installs the composition tracer. An actual required-profile release trace contains MemoCard and paging composable slices, verified with the existing AndroidX Trace Processor. |
| SDK profgen | Use the official HRF parser rather than maintaining a profile grammar. A real malformed-profile probe returned exit 0 plus stderr diagnostics; the adapter rejects those diagnostics and comment-only input. No separate tool version pin is introduced. |
| JankStats 1.0.0 | Defer: its frame/context monitoring is useful for field observations, but does not supply composition-call attribution or compilation profiles. |
| Tracing-perfetto/binary 1.0.1 | Do not add the legacy native binary to the shipping app. The selected runtime-tracing artifact uses the newer in-process driver, which the existing Macrobenchmark 1.5 supports. |

Sources: [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[composition tracing](https://developer.android.com/develop/ui/compose/tooling/tracing),
[Compose Runtime releases](https://developer.android.com/jetpack/androidx/releases/compose-runtime),
[Tracing releases](https://developer.android.com/jetpack/androidx/releases/tracing),
[SDK profile tooling](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure),
[JankStats](https://developer.android.com/topic/performance/jankstats).

## Profile and measurement ownership

`profile` collects unminified paths. `--write` publishes both measured profiles and a receipt
containing their complete hashes, the captured APK hash, corpus digest, device fingerprint and
source revision. The gate checks receipt integrity and official parser acceptance. It does not
replace sampled paths with every runtime method or claim performance benefit from syntax alone.
The old manual profile and glob-based method enumeration are removed.
Each collection resets the fixed test-owned SAF corpus before seeding; prior journey-created
documents cannot change the next capture's initial workspace.

`release` is an independent measurement plane. It requires a deterministic SAF corpus and API
29+ so the signed profileable release can expose semantic benchmark anchors. Each signature swap
regrants the test-owned workspace through DocumentsUI. The sweep covers cold/warm/hot startup and
scroll under none/required-profile/full compilation, then restores Debug and its workspace grant.
Release packaging verifies that the APK is not debuggable and includes compiled profile assets.
The first actual release build also passed an R8 metadata/DEX checksum check: `classes.dex`
reports `startup: true`, with obfuscation, optimization and shrinking enabled. This build-time
evidence is distinct from the required-profile mode's on-device installation check.

A scroll needs a materialized card and must move that card offscreen. Seeded writes carry a unique
content marker; pending cards use a distinct anchor, so only the committed projection acknowledges
the submission. A missing snackbar no longer causes duplicate writes or a refresh/retry loop.

Validation: admission RED/GREEN tests, parser-driver/receipt rejection tests, the real SDK parser,
host package checks and the actual device collection/sweep. Device evidence lives under
`reports/manual/performance-mechanism/`. Collection, all 12 release scenario/mode
combinations and trace queries passed. Both collectors reached the maximum iteration count
without reporting convergence. Full-worktree verification remains blocked by the preexisting
MemoCopyContent Detekt finding; dependency audit also rejects the yanked clipboard-win 5.4.1
on the TUI chain. These are not waived by the passing device workflow.
Redroid lacks display frame completion data, so application slices and semantic
readiness must remain separate from FPS, frame-overrun, TTID and TTFD claims. Quantitative release
benefit still requires a representative physical device.

Architecture Impact: `lomo-xtask` owns measurement/provenance and SDK validation; `app` owns
profileable OS configuration and state-dependent benchmark semantics. Domain, storage and FFI
authority remain with their existing owners; no architectural exception is introduced.

## Startup and scroll workload bounds

The application starts security restoration and theme application immediately. A process-scoped
coroutine completion signal from the Activity's first real draw admits maintenance, shortcuts,
update checks, foreground sync/reminder registration and LAN discovery. Task factories remain
lazy until that boundary. Lock-screen draws also release the signal; it does not depend on the
user unlocking or on TTFD. Widget privacy/mount observation and foreground authority resnapshot
remain immediate. Android's draw listener is removed after traversal, before deferred work is
released, rather than mutating its observer during drawing.

Memo body size animation belongs to the reusable card body. The app wrapper adds no second
height animation; list position and enter/exit motion retain their own owners. A bounded
256-entry UI-model cache reuses the existing Markdown document and presentation plan when only
metadata changes. Body, preview/full kind, media context and expansion-affordance inputs still
invalidate rendering. Current metadata is copied onto the new UI row, never reused as stale facts.

Image prefetch prioritizes visible rows, follows viewport travel in either direction and expands
its candidate window for larger row jumps. Stationary updates retain direction; reset clears
travel history. The existing request budget remains the upper bound, stale requests are cancelled,
and invalid/overflowing viewport coordinates are rejected before planning. No new dependency is
needed for these changes. Round-two evidence lives under
`reports/manual/startup-scroll-round2/`.
