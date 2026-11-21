# Android UI Test (device-free)

Read this file for container-driven UI debugging, Maestro flow execution, screen snapshots
(`uishot`), and two-device LAN sharing tests. [AGENTS.md](../AGENTS.md) owns task completion;
[Quality](README.md) owns verification gates (`uitest` and `uishot` are diagnostics);
[Android performance](android-performance.md) owns benchmarks, sync probes, and profiles;
[Architecture](../ARCHITECTURE.md) owns module authority.

## Capability

```bash
just uitest                      # build+install debug APK for the device ABI, run all committed flows
just uitest create_memo          # one flow (bare name, .yaml, repo-relative or absolute path)
just uitest --redroid            # provision the pinned redroid container when none is attached
just uitest --skip-build         # reuse target/lomo/artifacts/android/debug/<app>-<ver>-<abi>.apk
just uitest --no-install         # run flows against whatever is already installed
just uitest --clear-data         # pm clear first: exercises the fresh-install path
```

`--redroid` provisions `lomo-redroid` (`docker.io/redroid/redroid:15.0.0_64only-250627`, `--privileged`,
tmpfs `/data`, `use_memfd=1`, 1080x2400@420, adb at `127.0.0.1:5555`). redroid shares the host
kernel and starts cold on every container boot.

Device selection deduplicates transport aliases (`emulator-5554` and `127.0.0.1:5555`) by kernel
`boot_id` using canonical `host:port`. Set `ANDROID_SERIAL=<serial>` when more than one unique device
is attached. Manage standing container state with `just redroid up|down|status`. Set
`LOMO_REDROID_GPU_MODE=guest` (SwiftShader) on GPU-less hosts.

Host kernel requirements: `CONFIG_ANDROID_BINDER_IPC=y`, `CONFIG_ANDROID_BINDERFS=y`, plus `loop`
and `dm_mod` modules loaded. Container provisioning requires rootful Docker or Podman (rootless
Podman lacks binderfs inside user namespaces); once running, adb and flows run rootlessly. For an
always-on setup, install `apps/android/uitest/lomo-redroid.container` under `/etc/containers/systemd/`.

Evidence honesty: redroid is AOSP Android 15 (no Play Services, not pixel-identical to Google AVDs)
and establishes emulator-grade interaction behavior only, not physical-device truth. All committed
flows use AOSP DocumentsUI. Commands fail closed on missing adb/maestro/device/flow files or
unknown flags; each flow runs in its own `maestro test` process.

## Two-device LAN sharing

```bash
ANDROID_SERIAL=<sender-serial> just uitest lan_share --peer=<receiver-serial> --clear-data
```

Opt-in scenario excluded from the default suite (`--peer` is rejected elsewhere). Requires two
attached English debuggable devices with distinct `android_id` values and explicit `--clear-data`.
Creates temporary workspaces under `/sdcard/Download/lomo-lan-<run>-a|b` via DocumentsUI, verifies
real NSD/LAN discovery, six-digit first-contact pairing codes, rejection handling, two-way
memo + attachment SHA-256 transfer, and search persistence after process restart. Evidence lands in
`reports/android-lan-uitest/`. Host protocol contracts remain covered by
`cargo test -p lomo-lan --locked` and `cargo test -p lomo-xtask --test android_lan_uitest_contract --locked`.

## Device performance and sync sweeps

For Macrobenchmark, `just perf device` (including `sync`, `workspace`, `profile`, and `release`
planes), and Baseline Profile collection on the same device, see
[Android performance](android-performance.md).

## Prerequisites

- Android SDK `platform-tools` (`adb`) via `ANDROID_SDK_ROOT`/`ANDROID_HOME`.
- Rootful Docker or Podman for `--redroid`.
- JDK on `PATH` and Maestro CLI (`$MAESTRO` -> `$MAESTRO_HOME/bin/maestro` -> `maestro` on `PATH`
  -> pinned XDG cache download into `$XDG_CACHE_HOME/lomo/maestro/<ver>`).

Each UI-test/snapshot run disables animations and stylus handwriting (`stylus_handwriting_enabled=0`)
via `adb shell settings` for deterministic input.

## Evidence layout (`reports/android-uitest/`)

- `android-uitest.v1.json`: device metadata, APK info, per-flow status, and artifact paths.
- `<flow>/`: per-step Maestro screenshots and view hierarchies.
- `<flow>.log` and `<flow>.junit.xml`: Maestro output and JUnit report.

## Screen snapshots (`uishot`)

```bash
just uishot                    # timestamped snapshot (keeps newest 20 timestamped dirs)
just uishot settings           # stable named directory (overwritten on rerun, never pruned)
just uishot --redroid          # provision redroid when no device is attached
```

Writes `reports/android-uisnap/<name|ts>/` with `screenshot.png` (validated PNG with one 800ms
settle retry on suspicious frames), `hierarchy.json`, flattened `components.json`, `window.txt`, and
`uisnap.v1.json`.

## All-screen tour (`screens_tour`)

`just uitest screens_tour` walks every primary screen, asserting root `benchmark_*` anchors and
capturing named screenshots. Use `scrollUntilVisible` before tapping rows below the fold.

## Anchors and flow authoring rules

Debug builds expose `benchmarkAnchor("benchmark_*")` tags as Android resource IDs. All committed
anchors live in `apps/android/app/src/benchmark/BenchmarkAnchorContract.kt`; `android_uitest_contract`
rejects flows referencing undeclared anchors.

- Set `appId: com.lomo.app` and start with `launchApp`.
- Begin business flows with `- runFlow: setup_workspace.yaml`.
- Prefer `id: "benchmark_*"` selectors over copy/text; reserve `text:`/`visible:` for system UI
  (`option_menu_create_dir`, `button1` = `USE THIS FOLDER`), guarding first-run prompts with
  `optional: true` or `when:`.
- `assertVisible` checks semantics presence rather than z-order; pair navigation taps with
  `- waitForAnimationToEnd` (especially across `Switching -> Indexing -> Idle`).
- Keep flows idempotent with unique probe tokens under `apps/android/uitest/flows/*.yaml`.
- One-off zero-dependency probes can use `adb exec-out screencap -p`, `adb shell uiautomator dump`,
  `adb shell input`, and `adb shell run-as com.lomo.app`.

Maestro CLI (`2.12.1`, pinned via `MAESTRO_VERSION`/`MAESTRO_SHA256` in
[`crates/lomo-xtask/src/android.rs`](../crates/lomo-xtask/src/android.rs)) is selected over
UIAutomator/Espresso/Appium for sub-minute YAML iteration and black-box DocumentsUI control;
UIAutomator stays in `apps/android/benchmark` for compiled performance benchmarks.
