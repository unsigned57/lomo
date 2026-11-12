# Android UI Test (device-free)

Read this file for the container-driven UI debug loop: how to boot the app without a physical
device, drive interactions without manual tapping, reproduce UI bugs quickly, and author new
flows. [AGENTS.md](../AGENTS.md) owns authorization and task execution;
[Quality](README.md) owns gates — `uitest` is a diagnostic, not a required gate;
[Architecture](../ARCHITECTURE.md) owns module authority.

## Capability

One command drives the full loop on an attached device:

```bash
just uitest                      # build+install debug APK for the device ABI, run all committed flows
just uitest create_memo          # one flow (bare name, .yaml, repo-relative or absolute path)
just uitest --redroid            # provision the pinned redroid container when none is attached
just uitest --skip-build         # reuse target/lomo/artifacts/android/debug/<app>-<ver>-<abi>.apk
just uitest --no-install         # run flows against whatever is already installed
just uitest --clear-data         # pm clear first: exercises the fresh-install path
```

`--redroid` provisions `lomo-redroid` — the pinned
`docker.io/redroid/redroid:15.0.0_64only-250627` image run with `--privileged`,
tmpfs `/data`, `use_memfd=1`, host-GPU rendering and a fixed 1080×2400@420 display,
published over adb at `127.0.0.1:5555` (numeric IPv4: `localhost` resolves `::1`
first and fails on IPv6-disabled hosts). redroid shares the host kernel instead of
emulating one: adb becomes usable in seconds, and every container start is cold
state by construction.

Device selection: a serial is a *transport*, not a device — the container registers
both `emulator-5554` and `127.0.0.1:5555`, so serials are deduplicated by kernel
`boot_id` and the `host:port` form is canonical. `ANDROID_SERIAL=<serial>` selects
explicitly (it must name an attached device); more than one unique attached device
without it fails with the serial list instead of picking by `adb devices` row order.

Container lifecycle lives on `just redroid`:

```bash
just redroid status    # serials, unique devices, container/image state, gpu_mode
just redroid up        # provision if needed and wait for boot; also what --redroid does inline
just redroid down      # stop the container (full cold reset: --rm + tmpfs /data)
```

On GPU-less hosts set `LOMO_REDROID_GPU_MODE=guest` (SwiftShader) — `host` is the
default. `just bootstrap` prefetches the pinned image when the resolved engine can
actually run redroid (docker's rootful daemon, or non-rootless podman — a rootless
pull would land in user storage the container cannot use anyway).

Host prerequisites (verified on Arch/CachyOS): kernel binder/binderfs built in
(`CONFIG_ANDROID_BINDER_IPC=y`, `CONFIG_ANDROID_BINDERFS=y`), and the `loop` +
`dm_mod` modules loaded (`/etc/modules-load.d/` entry) — apexd loop/dm mounts
fail without them and init exits. The image is Android 15 on purpose: `16.0.0`
crash-loops surfaceflinger under SwiftShader (upstream redroid-doc#934) and its
SystemServer hard-opens `/dev/ashmem`, which kernels without `CONFIG_ASHMEM`
lack. A GPU-less host should set `LOMO_REDROID_GPU_MODE=guest`.

The one asymmetry: **container provisioning needs a rootful engine.** Docker reaches its
daemon socket directly; rootless podman accepts `podman run` but redroid's init dies
immediately (no binderfs in the user namespace) — xtask detects the dead container and
fails with the exact `sudo podman run …` line instead of hanging on the boot wait.
Once any rootful `lomo-redroid` runs, reuse needs no privileges: adb attach, flow
execution and `wait_booted` all operate rootlessly. For always-on setups the
committed template `apps/android/uitest/lomo-redroid.container` installs under
`/etc/containers/systemd/` (rootful quadlet: `[Install]` starts it at boot —
generated units are never `systemctl enable`d) and keeps the container up
permanently.

Evidence honesty: redroid is still emulator-grade, non-physical evidence — the same
`suppressed_errors`/debuggable declarations apply. Its image is AOSP, not the
`google_apis_playstore` AVD build, so pixel output and system UI details are
*not* bit-comparable across backends: pick one backend per golden baseline. Flows that
touch Google-only components would not exist on the container; committed flows only use
AOSP DocumentsUI, which ships in the redroid image.

Flags combine freely (`just uitest --redroid --clear-data repro.yaml`). The command
fails closed: no adb/maestro/device/flow file aborts before any state change;
an unknown selector or flag exits before any device work; every flow runs as its own
`maestro test` so one failure cannot hide other evidence.

## Two-device LAN sharing

The opt-in LAN journey uses two explicitly selected Android instances on the same LAN:

```bash
ANDROID_SERIAL=<sender-serial> just uitest lan_share --peer=<receiver-serial> --clear-data
```

Use **two dedicated test devices**: `--clear-data` clears Lomo's application data on both
selected devices so first-contact pairing is exercised every time. The command requires the
flag explicitly; it never clears data as an implicit default. It creates fresh, named test
workspaces under `/sdcard/Download/lomo-lan-<run>-a|b`, selects them through DocumentsUI and
creates memos through the app. Existing external workspaces are not deleted. The stages reuse
the existing English DocumentsUI/photo-picker selectors, so use English test devices.
Both apps must be debuggable. `--no-install` reuses those installs; `--skip-build` reuses the
published debug APK when both device ABIs match. Otherwise the normal build includes both ABIs.

This scenario is intentionally excluded from the default single-device suite. `--peer` is
rejected for ordinary flows, and `lan_share` cannot be mixed with other selectors. Both serials
must already be attached. Their Android IDs must differ: two adb aliases into one installation
cannot pass, while two redroid instances sharing a host kernel can be distinct endpoints.
There is no adb forwarding or injected discovery result. The production NSD/probe discovery
behavior must find the peer under the actual network and permission conditions.

The journey asserts:

1. Both discovery lists contain exactly one peer and exclude the local device.
2. Both first-contact dialogs expose the same six-digit pairing code before either is confirmed.
3. Rejecting an incoming batch produces a rejection on the sender and zero imported memo markers.
4. A sends a memo with the two committed image fixtures to B; B explicitly accepts it, and its
   workspace contains the memo and references to files matching both original SHA-256 hashes.
5. B sends its text memo to A using the established trust. Both receiver search results are checked.
6. After both processes restart, search still finds each received memo and pulled workspace bytes
   still contain exactly one copy of its unique marker. The image hashes are checked again on B.

Evidence lands in `reports/android-lan-uitest/`: per-stage Maestro screenshots,
JUnit and logs, discovery/pairing hierarchies, pulled test workspace snapshots, bounded logcat
captures and `android-lan-uitest.v1.json`. A failed stage stays failed with its completed evidence;
missing devices fail before install, reset or workspace changes. The host contract tests are:

```bash
cargo test -p lomo-xtask --test android_lan_uitest_contract --locked
cargo test -p lomo-lan --locked
```

Host tests cover diagnostic preconditions/evidence validation and the production protocol's
pairing, authentication, chunk, rejection and recovery contracts. They do not establish Android
multicast discovery or two-device UI success. `lanShareOpen` only measures opening the page;
it does not establish discovery or a transfer. The LAN journey uses `app` only for interaction
and `lomo-xtask` only for test coordination; domain/module ownership is unchanged.

## Three-provider sync on the attached virtual device

```bash
just perf device sync --corpus device --backend direct --redroid
```

This resets a diagnostic-owned app-private corpus, starts authenticated WebDAV and S3 HTTP
fixtures on the host, exposes their port with `adb reverse`, and stages a seeded bare Git remote
inside the app. No personal credentials or external remote are needed. Each provider gets its
own reset sync-control state. Reports include actual requests, upload intents, verification
outcomes, cycle records and retained raw probe payloads under `reports/android-device-perf/`.
An empty local snapshot, missing cycle, held transfer or zero-upload sweep fails the probe.
The production cycle reads the active session's private projection; opening a fresh projection
under the document root cannot substitute for the mounted session.

Coverage is protocol-adapter upload/verification plus the production cycle and conflict planner.
Remote-only pull intents are reported separately; an intent count alone does not prove local
import. The fixture uses plain HTTP and Git `file://`; public HTTPS/SSH authentication, real
cloud behavior, SAF sync and LAN discovery are outside this device sweep. Use the same command
without `--redroid` for any attached emulator or physical device.

## Prerequisites

- Android SDK with `platform-tools` (adb); `ANDROID_SDK_ROOT`/`ANDROID_HOME` resolves the SDK.
- A container engine for `--redroid`: rootful Docker or Podman (see the provisioning
  section above for the privilege split and kernel prerequisites).
- JDK on PATH (Maestro is a JVM program).
- Maestro is a standalone CLI, not a consumable build dependency — it cannot be added to
  Cargo/Amper manifests. Preferred install is the system package manager: on Arch
  `paru -S maestro-dev` (AUR, tracks 2.12.1 = the pin; `maestro`/`maestro-bin` carry an
  older 2.7.x that still works but warns). Resolution order: `$MAESTRO` →
  `$MAESTRO_HOME/bin/maestro` → `maestro` on PATH (package-manager copy wins) → the
  pinned XDG-cached copy. Only when none exists does xtask download the pinned
  `maestro.zip` (sha256-verified) into `$XDG_CACHE_HOME/lomo/maestro/<ver>` — last-resort
  provisioning for machines without the package; nothing is installed inside the
  repository. The report records the resolved binary's actual `--version` plus the pin.

The command applies deterministic-input prep on every run (requires `adb shell settings`,
no root): `stylus_handwriting_enabled=0` and `window/transition/animator` scales `0` —
this suppresses the Gboard stylus onboarding dialog and animation-timing flakes.

## Evidence layout

`reports/android-uitest/` (gitignored):

- `android-uitest.v1.json` — device info, APK, options, per-flow status, artifact paths.
- `<flow>/` — Maestro debug output: per-step screenshots + view-hierarchy JSON (readable
  by agents for selector discovery and visual verification).
- `<flow>.log` — captured maestro stdout/stderr; `<flow>.junit.xml` — JUnit report.

Container evidence is emulator-grade: it proves interaction behavior, not physical-device
truth. Release-truth still needs a physical device run.

`workspace_empty` selects a fresh empty SAF folder, leaves settings immediately, then checks
the committed root after navigation and process restart. It uses public UI text so it also
runs against an already-installed release with `just uitest workspace_empty --skip-build
--no-install`. It creates a unique test folder without clearing app data or deleting roots.

## Screen snapshots (`uishot`)

`just uishot` captures the currently displayed screen as one atomic evidence bundle —
for agents and humans who need "what is on screen right now" without authoring a flow:

```bash
just uishot                    # timestamped name
just uishot settings           # named snapshot (dir = name)
just uishot --redroid          # provision the pinned redroid container when none is attached
```

Each run writes `reports/android-uisnap/<name|ts>/` (gitignored):

- `screenshot.png` — `adb exec-out screencap -p` streamed straight to the host file:
  one command, no device-side intermediate, no `screencap`-then-`pull` race. PNG magic
  and a minimum size are validated; a suspicious capture retries once after 800 ms,
  then fails rather than emitting a black/partial frame.
- `hierarchy.json` — `maestro hierarchy` semantics tree (attributes + children).
- `components.json` — the same tree flattened to `[{idx, depth, id, text,
  accessibility_text, class, bounds, clickable, focused, scrollable}]` for direct
  agent consumption; `benchmark_*` ids map back to source via
  `rg benchmarkAnchor`/the anchor contract.
- `window.txt` — `dumpsys window` focus lines (current window/activity/IME target).
- `uisnap.v1.json` — manifest: device, maestro version+pin, git revision, artifact
  paths, screenshot byte count, retry flag, component count.

Same fail-closed semantics as `uitest`: adb/maestro/device resolve before any state
change; no attached device without `--redroid` aborts; the same deterministic-input prep
(animations off, stylus handwriting off) is applied. Diagnostic only, not a gate.

Retention: each successful run keeps the newest 20 timestamped (`YYYY-MM-DD_hhmmss`)
snapshot dirs and removes older ones — named snapshots are stable paths and are never
pruned (rerunning the same name overwrites it). The manifest records
`retention.{timestamped_keep, pruned}`.

## All-screen tour (`screens_tour`)

`apps/android/uitest/flows/screens_tour.yaml` is a committed smoke-tour: it creates a
probe memo, then walks every primary surface — input sheet, feed, filter sheet, search,
drawer, tag filter, memo menu, version history, daily review, gallery,
statistics, trash, settings, the Sync & Backup sub-page and Sync Center — asserting
each screen-root anchor and taking a named screenshot per stop:

```bash
just uitest screens_tour       # or `just uitest` to run it with all committed flows
```

Screenshots land in `android-uitest/screens_tour/<ts>/takeScreenshot/*.png`. It doubles
as the app's executable screen inventory: a screen the tour cannot reach fails the run
instead of producing a stale image. Note the lazy-list convention used there —
`scrollUntilVisible` before tapping rows that compose below the fold (settings
category rows, sync settings).

## Performance on the same device

`just build android benchmark` runs the committed AndroidX Macrobenchmark harness and
accepts the same `--redroid` provisioning flag (the Gradle harness drives the
device itself — no Maestro involved):

```bash
just build android benchmark startup --redroid         # provision the redroid container
just build android benchmark all                        # attached device, all scenarios
```

Evidence lands in `reports/android-benchmark/android-benchmark.v1.json` plus
raw androidx `*-benchmarkData.json` (Perfetto traces land alongside). Its `measurement`
block permanently records the emulator+debuggable `suppressedErrors` — the numbers are
useful for regression smoke on identical container runs, never for physical-device
release truth. Device stabilization (`prepare_device`) is deliberately NOT applied to
benchmarks: disabled animations would invalidate frame-timing measurements.

redroid uses the `application_trace` measurement channel. Its missing display-frame
completion data makes `FrameTimingMetric` return no samples on the pinned image; it
does not prevent UIAutomator journeys or Perfetto application traces. The harness uses
AndroidX `TraceSectionMetric` for main-thread / RenderThread work, `MemoryUsageMetric`
for process memory, and a launch trace ending at semantic UI readiness. Reports name
this channel and list unavailable display metrics explicitly; these values cannot be
compared directly with frame CPU duration, frame overrun, TTID or TTFD. Devices with a
complete display timeline retain the `display_frames` channel. Profile collection and
release compilation-mode sweeps use the same supported launch path.

AndroidX already captures each iteration's binary Perfetto trace. Archival copies only
files referenced by that scenario's `profilerOutputs`, verifies their paths, and never
routes trace bytes through a text shell decoder or starts a second long capture.

`just perf device` planes on the same attached device:

```bash
just perf device engine --corpus device --backend saf     # engine probes via run-as
just perf device sync --corpus device --backend direct    # sync cycles via adb reverse
just perf device startup cuj system --corpus device       # application traces on redroid
just perf device workspace --corpus device --backend saf --trace # fresh/switch/reopen/update
```

Dependency note: androidx Macrobenchmark remains the right harness (maintained androidx
standard, already integrated, drives via UIAutomator + Perfetto). Nearby alternatives
evaluated — Block's `francis` (A/B comparison CLI wrapping Macrobenchmark), `mperf`
(profiler-trace collector), `lumos` (FPS/CPU/RAM on real devices) — complement rather
than replace it; `androidx.benchmark` microbenchmark is the future addition for
algorithmic hot spots if needed.

## Anchors: the interaction contract

Debug builds set `testTagsAsResourceId`, so every `benchmarkAnchor("benchmark_*")` tag in
`ui-components`/`app` is visible to Maestro as an Android resource-id. The anchor
inventory is `apps/android/app/src/benchmark/BenchmarkAnchorContract.kt` — prefer `id:`
selectors over text: text changes with copy/i18n, anchors are a contract. The
`android_uitest_contract` test fails when a flow selects an anchor the contract does not
define, so renaming an anchor without updating flows is caught at review time.

## Flow authoring rules

- `appId: com.lomo.app`, start with `launchApp`.
- Fresh-install safety: begin business flows with `- runFlow: setup_workspace.yaml` —
  it picks a SAF workspace only while the empty-state "Go to Settings" CTA is visible.
- Prefer `id: "benchmark_*"` selectors; use `text:`/`visible:` for system UI
  (DocumentsUI has `option_menu_create_dir`, `button1` = USE THIS FOLDER).
- Guard first-run system UI with `optional: true` or `when:` conditions.
- `takeScreenshot` takes a name, not a path (output lands in the flow's artifact dir).
- `assertVisible` checks presence in the semantics tree, not z-order: during navigation a
  covered screen still "passes". Pair navigation taps with `- waitForAnimationToEnd`
  before the next interaction, especially across the workspace switch
  (Switching → Indexing → Idle).
- Flows must be idempotent and independent of existing device data (probe strings like
  `uitest-search-probe-xyzzy` keep assertions deterministic).
- Committed flows live in `apps/android/uitest/flows/*.yaml`; subflows invoked only via
  `runFlow` keep the same directory (the contract test walks every committed file).

## Quick probes (no Maestro)

For one-off interaction the zero-dependency path is fine:

```bash
adb exec-out screencap -p > shot.png            # screenshot (agent-readable)
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .  # view tree
adb shell input tap <x> <y> / input text "..." / input keyevent KEYCODE_BACK
adb shell run-as com.lomo.app ...               # debuggable build: inspect filesDir
```

## Dependency decision (recorded)

Requirement: a scriptable UI driver with sub-minute edit→run iteration, view-tree dumps
and screenshots consumable by agents, black-box reach into system UI (SAF picker, IME
dialogs) and zero production-code changes.

| Candidate | Verdict |
| --- | --- |
| **Maestro CLI 2.12.1** (`cli-2.12.1`, mobile-dev-inc, Apache-2.0) | Selected: YAML flows, `--udid` device targeting, `when:`/`optional:` guards, per-step screenshot+hierarchy artifacts, JUnit output |
| UIAutomator JUnit (androidx.test.uiautomator, used by `apps/android/benchmark`) | Rejected for this role: compile→install test APK per edit; right tool for the committed benchmark harness, wrong iteration cost for bug-repro loops |
| Espresso | Rejected: in-app sandbox cannot drive DocumentsUI/IME; same compile cost |
| Appium | Rejected: server+client stack and session setup is heavier than the task needs |
| raw `adb input`/`uiautomator dump` | Kept as documented fallback only — blind, no waiting/assertions |

Evidence: validated live on the `lomo-redroid` container (Android 15 / API 35) —
fresh-install SAF workspace selection, memo creation and `uishot` snapshot all green
with screenshots and hierarchies captured. Pin sites: `MAESTRO_VERSION`/`MAESTRO_SHA256` in
`crates/lomo-xtask/src/android.rs`. Bump both together; re-run `just uitest` to revalidate.
