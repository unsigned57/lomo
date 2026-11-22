//! Behavior Contract
//!
//! Capability: `just perf device [startup|cuj|engine|workspace|system|sync|profile|release|all]... [--corpus device]
//! [--backend saf|direct] [--redroid] [--trace]` produces emulator+debuggable device
//! performance evidence: startup variants including dirty-workspace cold start, the
//! zero-anchor-cost CUJ set, per-scenario system metrics (gfxinfo/meminfo), and in-process
//! engine probes over the real `ManagedEngineSession` — with deterministic corpus injection
//! and animation-scale normalization recorded in one report.
//! Owning layer: lomo-xtask perf command surface; priority: P0 (device measurement facility).
//!
//! Scenarios:
//! - Given no attached device and no `--redroid`, when dispatched, then the command fails before
//!   any device state change — never inventing a run.
//! - Given a device, when any plane runs, then `window`/`transition`/`animator` scales are
//!   normalized to 1.0 first and the read-back values land in `measurement.animation_scales`
//!   (`uitest` leaves them at 0 persistently — fact 5 of the device-measurement plan).
//! - Given `--corpus`, when injection runs, then the deterministic workspace-shaped `device`
//!   corpus is pushed to `/sdcard/Documents/<ws>` for `saf` (granted through the production
//!   `DocumentsUI` flow in `LomoMacrobenchmark#prepareWorkspace`) or to `/data/local/tmp` +
//!   `run-as` copy into `files/` for `direct` (debuggable-only), and its manifest digest is
//!   recorded. Host-tier modes (`quick`/`scale`/`capacity`) are rejected at parse: their
//!   bucket layout (`fixtures/…`, flat `memo/` names) can never reconcile under a real
//!   workspace root — every `.md` in the tree must carry a valid date key.
//! - Given a plane list, when scenarios run, then each executed scenario gets its own
//!   `connectedDebugAndroidTest` invocation and `*-benchmarkData.json` summary; failures are
//!   recorded per-scenario in `planes.*.failures` and the command exits non-zero after the
//!   report is written — a partial suite is evidence, never a silent pass.
//! - Given the §3-C CUJ set, when a scenario with declared restrictions runs, then its
//!   result block carries `limitations` (and `emulator_limited` for `lanShareOpen`) on
//!   success and failure alike — restricted surfaces keep their caveat attached to the
//!   measured numbers instead of reading as full-fidelity evidence.
//! - Given `--corpus device --backend saf`, when the startup plane runs, then
//!   `startupDirty` and `startupOnboarding` (A5: pm clear → `DocumentsUI` grant → first
//!   reconcile → first screen) are appended to the sweep; without a SAF corpus both are
//!   skipped with a note, never fabricated.
//! - Given corpus grant/configuration failure, when preparation runs, then it fails before
//!   measuring any plane or updating a backend baseline. An existing other-root workspace
//!   never supplies samples under the requested corpus/backend label.
//! - Given `system`, when a CUJ scenario runs, then `dumpsys gfxinfo reset` precedes it and
//!   gfxinfo+meminfo dumps follow it, archived under the report directory.
//! - Given `engine`, when probes run, then an `am broadcast` sent under `run-as` (same-uid,
//!   so non-exported registration stays enforced) reaches the debug-gated `PerfProbeReceiver`,
//!   and results are pulled with `run-as` and merged. The probe set covers paging, search,
//!   ffi, an N-parameterized `.rec` history traversal (`history_seed` first materializes N
//!   real revisions through the production `sessionUpdateMemo` CAS chain), and the ≥500-file
//!   `listchildren` enumeration driven through the production `PlatformAction.ListChildren`
//!   lane. Every metric lands in the P6 noise gate: p50/p95/sample-count vs the persisted
//!   backend-specific engine baseline (schema v2) — relative deltas beyond 15% (or fewer than `MIN_NOISE_SAMPLES`
//!   samples) report `inconclusive`, never a definitive regression/improvement.
//! - Given `sync --corpus device --backend direct`, when the sync plane runs, then a
//!   plain-HTTP WebDAV+S3 fixture binds localhost, `adb reverse tcp:P tcp:P` exposes it to
//!   the device, a seeded `file://` bare Git remote lands in app-private files, and the
//!   `sync_cycle` probe drives the production `RemoteSyncRepository` (`probeBackend` →
//!   `runCycle` `applyRemote=true` → `cycleStatus`) per backend. Fixture per-route request
//!   counters and the durable `cycle_state.rec` record land in `planes.sync`; `run-as`
//!   reverse teardown always runs. `.lomo/sync` is fenced to one remote identity triple, so
//!   each backend leg first resets it (declared `syncStateReset` — the device-side
//!   disconnect) and every cycle is a real `first_takeover`, never fence-mismatch noise.
//!   LAN stays `emulator_limited` (multicast is isolated
//!   on the emulator); TLS stays a declared restriction (non-Play image + CA injection
//!   required — the HTTP tier is honest, not simulated).
//! - Given `profile`, when dispatched, then the `profileGen` harness journey collects the
//!   device-measured ART profile (`*-baseline-prof.txt`/`-startup-prof.txt`/
//!   `-primary.prof.txt`) into the report directory, and `planes.profile` reports entry
//!   counts, distinct classes and the class-set diff against the committed
//!   `baselineProfiles/generated.txt`. The workspace is guaranteed ready — without
//!   `--corpus` the workspace-shaped `device` corpus is injected and granted through the
//!   production picker first. `--write` additionally replaces the packaging inputs (`generated.txt`,
//!   `startup-prof.txt`) with a hash-bound collection receipt. The separate `release` plane
//!   compares startup and scroll under none|partialRequire|full on the same SAF corpus,
//!   then restores the debug install and regrants its workspace. Both planes are explicit.
//! - Given a device without display-frame completion callbacks, when UI planes run, then
//!   `application_trace` measures application slices, memory and semantic launch readiness.
//!   Display deadline/TTID/TTFD metrics stay explicitly unavailable; journeys still execute.
//! - Given the E6 probes, when engine runs, then views measure tasks/statistics/review/trash
//!   summaries, trash cycles delete only freshly created identities, and a corpus media
//!   fixture is staged through the production registry with its owned leases released.
//!
//! Observable outcomes: `reports/android-device-perf/device-perf.v1.json` carrying
//! device fingerprint, git revision, corpus digest, animation scales, compilation mode and
//! `suppressed_errors=EMULATOR,DEBUGGABLE`, plus per-plane result blocks and archived raw
//! artifacts (benchmarkData/gfxinfo/meminfo/probe JSON/*-prof.txt).
//!
//! TDD proof: `cargo test -p lomo-xtask --test device_perf_contract --locked`.
//! RED: before this module `perf` rejected every argument and neither device corpus injection
//! nor a probe channel existed; argument parsing and the report/schema pins fail first.
//! Excludes: physical-device evidence and LAN discovery (emulator multicast is isolated).
//! The release sweep on an emulator is emulator-grade evidence — B2/B3 adjudication also wants a
//! physical-device confirmation.

use std::{
    collections::{BTreeMap, BTreeSet},
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
    process::Command,
    time::{Duration, Instant},
};

mod workspace;

use anyhow::{Context, Result, bail, ensure};
use lomo_feasibility::{
    CorpusMode, DAV_PASSWORD, DAV_USERNAME, DEVICE_BULK_DIR, GIT_BRANCH, GenerateRequest,
    HttpSyncFixture, S3_ACCESS_KEY_ID, S3_BUCKET, S3_REGION, S3_SECRET_ACCESS_KEY, generate_corpus,
    seed_device_git_remote,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::{
    android::{
        self, AndroidVariant, BENCHMARK_CLASS, BENCHMARK_MODULE, BENCHMARK_SUPPRESSED_ERRORS,
        BENCHMARK_TARGET_PACKAGE, BenchmarkScenario,
    },
    native::Abi,
    util::run,
    workspace::Workspace,
};

const DEVICE_PERF_REPORT_DIR: &str = "android-device-perf";
const DEVICE_PERF_REPORT: &str = "device-perf.v1.json";
const DEFAULT_WORKSPACE_NAME: &str = "lomo-perf-device";
/// App-private Direct root under `files/` — `run-as`-injectable and `activateWorkspace`-legal.
const DIRECT_WORKSPACE_NAME: &str = "lomo-perf-direct";
const PROBE_ACTION: &str = "com.lomo.app.PERF_PROBE";
/// Probe results surface through `files/perf-probe/<run>.json`; a large corpus rebuild on a
/// software-rendered emulator may take minutes, so the poll window is generous.
const PROBE_TIMEOUT: Duration = Duration::from_secs(600);
const PROBE_POLL: Duration = Duration::from_secs(2);
/// Harness method that collects the measured baseline profile (P7a).
const PROFILE_GEN_METHOD: &str = "profileGen";
/// Compilation-mode instrumentation arg values the release A/B sweep drives (P7c);
/// `partialRequire` maps to `BaselineProfileMode.Require` — fail-closed inside androidx
/// when the release profile never registered.
const AB_COMPILATION_MODES: [&str; 3] = ["none", "partialRequire", "full"];
/// `apps/android/app/src/main/baselineProfiles/*.txt` merges into the release APK's
/// `assets/dexopt/baseline.prof` (the merged-art-profile intermediates already carry the
/// glob-generated entries) — `--write` replaces these two inputs.
use crate::profiles::BASELINE_PROFILE as WRITE_BASELINE_TARGET;
const RELEASE_AB_SCENARIOS: [BenchmarkScenario; 4] = [
    BenchmarkScenario::Startup,
    BenchmarkScenario::StartupWarm,
    BenchmarkScenario::StartupHot,
    BenchmarkScenario::Scroll,
];
const ANIMATION_SCALE_KEYS: [&str; 3] = [
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
];

/// P6 noise gate: engine metrics must carry ≥ this many samples for a verdict, and a
/// relative p50 delta beyond `NOISE_THRESHOLD` vs the persisted baseline is `inconclusive`
/// (emulator noise vs real change is indistinguishable — never asserted either way).
const MIN_NOISE_SAMPLES: usize = 5;
const NOISE_THRESHOLD: f64 = 0.15;
/// Persisted p50 baseline snapshot (P6 "optional baseline" — diagnostic, not a CI gate).
/// Lives beside the report dir so `reset_dir` never erases cross-run history.
const ENGINE_BASELINE_FILE: &str = "device-perf-engine-baseline.v2.json";
/// Sync leg seed file pushed into the bare git remote — a real remote-owned document the
/// first cycle's pull leg materializes into the workspace (outside corpus and dirty ranges).
const GIT_SEED_DOC: &str = "9000_01_01.md";
/// App-private bare remote path pushed via `run-as` (lomo-git `file://` endpoint).
const GIT_REMOTE_DIR: &str = "lomo-perf-git.git";

/// Measurement planes for `perf device`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DevicePerfPlane {
    /// Cold/warm/hot + dirty-workspace startup timing.
    Startup,
    /// Zero-anchor-cost CUJ frame timing (drawer, filter, scroll-top, tag, trash,
    /// version history, settings, search clear, memo actions).
    Cuj,
    /// In-process engine probes through `PerfProbeReceiver`.
    Engine,
    /// Explicit corpus-backed workspace switching, first indexing and external updates.
    Workspace,
    /// CUJ scenarios wrapped in `dumpsys gfxinfo`/`meminfo` collection.
    System,
    /// Remote-sync cycles over `adb reverse` (WebDAV/S3) + app-private `file://` Git.
    /// Requires `--corpus device --backend direct`; explicit opt-in like `profile` —
    /// `all`/bare sweeps stay on the SAF default which sync cannot use.
    Sync,
    /// Measured baseline-profile collection (`profileGen`), never part of `all`/bare
    /// sweeps — it gathers packaging input, not metrics.
    Profile,
    /// Signed release startup/scroll sweep over the same deterministic SAF corpus.
    Release,
}

impl DevicePerfPlane {
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Startup => "startup",
            Self::Cuj => "cuj",
            Self::Engine => "engine",
            Self::Workspace => "workspace",
            Self::System => "system",
            Self::Sync => "sync",
            Self::Profile => "profile",
            Self::Release => "release",
        }
    }
}

/// Corpus injection backend.
#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DeviceBackend {
    /// Grants a `DocumentsUI` tree over `/sdcard/Documents/<ws>` through the production picker.
    Saf,
    /// Stages into the app-private `files/` root via `run-as`; the engine `configure` probe
    /// activates it through the production workspace-switch path. Requires `--corpus`.
    Direct,
}

impl DeviceBackend {
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Saf => "saf",
            Self::Direct => "direct",
        }
    }
}

/// Parsed `perf device` options — every flag is explicit opt-in.
#[derive(Clone, Debug)]
pub struct DevicePerfOptions {
    pub planes: Vec<DevicePerfPlane>,
    pub corpus: Option<CorpusMode>,
    pub backend: DeviceBackend,
    /// Device backend to provision when `adb devices` reports nothing (`--redroid`).
    pub boot: Option<android::DeviceBoot>,
    pub trace: bool,
    /// `profile` plane only: replace the committed baseline-profile packaging inputs
    /// (`baselineProfiles/generated.txt`, `baselineProfiles/startup-prof.txt`) with the
    /// collected rules.
    pub write: bool,
}

impl DevicePerfOptions {
    fn validate_device(&self, device: &Value) -> Result<()> {
        if self.planes.contains(&DevicePerfPlane::Release) {
            let api = device
                .get("api_level")
                .and_then(Value::as_u64)
                .context("device facts have no API level")?;
            ensure!(
                api >= 29,
                "release measurements require API 29+ for profileable semantic anchors"
            );
        }
        Ok(())
    }
}

/// Parses `perf device` arguments. Unknown planes/flags and invalid backend combinations fail closed.
///
/// # Errors
/// Unknown planes/flags, missing flag values and unsupported backend combinations return
/// an error before any device state changes.
pub fn parse_device_perf_args(arguments: &[String]) -> Result<DevicePerfOptions> {
    let mut planes = Vec::new();
    let mut corpus = None;
    let mut backend = DeviceBackend::Saf;
    let mut boot = None;
    let mut trace = false;
    let mut write = false;
    let mut arguments = arguments.iter();
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "startup" => planes.push(DevicePerfPlane::Startup),
            "cuj" => planes.push(DevicePerfPlane::Cuj),
            "engine" => planes.push(DevicePerfPlane::Engine),
            "workspace" => planes.push(DevicePerfPlane::Workspace),
            "system" => planes.push(DevicePerfPlane::System),
            // `profile` is a collection plane and `sync` pins the Direct backend —
            // both stay opt-in and `all` deliberately excludes them.
            "profile" => planes.push(DevicePerfPlane::Profile),
            "release" => planes.push(DevicePerfPlane::Release),
            "sync" => planes.push(DevicePerfPlane::Sync),
            "all" => planes.extend([
                DevicePerfPlane::Startup,
                DevicePerfPlane::Cuj,
                DevicePerfPlane::Engine,
                DevicePerfPlane::System,
            ]),
            "--corpus" => {
                let value = arguments
                    .next()
                    .ok_or_else(|| anyhow::anyhow!("--corpus requires a mode"))?;
                corpus = Some(match CorpusMode::parse(value) {
                    // Injection lands in a real workspace root, so only the workspace-shaped
                    // `device` tier (dated `yyyy_MM_dd` day documents at the root + `media/`)
                    // can ever reconcile — host-tier bucket layouts (`fixtures/…`,
                    // `memo/NNNNNN.md`) hit the engine's date-key validation during
                    // activation (`invalid date key: DECISIONS` observed on emulator-5554).
                    Ok(CorpusMode::Device) => CorpusMode::Device,
                    Ok(mode) => bail!(
                        "--corpus {value} is a host-tier corpus; device injection requires the workspace-shaped `device` corpus (mode {})",
                        mode.as_str()
                    ),
                    Err(error) => bail!("--corpus {value}: {error}"),
                });
            }
            "--backend" => {
                backend = match arguments
                    .next()
                    .ok_or_else(|| anyhow::anyhow!("--backend requires saf|direct"))?
                    .as_str()
                {
                    "saf" => DeviceBackend::Saf,
                    "direct" => DeviceBackend::Direct,
                    other => bail!("unknown perf device backend: {other} (expected saf|direct)"),
                };
            }
            "--redroid" => boot = Some(android::DeviceBoot::Redroid),
            "--trace" => trace = true,
            "--write" => write = true,
            _ => bail!(
                "unknown perf device argument: {argument}; usage: just perf device [startup|cuj|engine|workspace|system|sync|profile|release|all]... [--corpus device] [--backend saf|direct] [--redroid] [--trace] [--write]"
            ),
        }
    }
    if planes.is_empty() {
        planes.extend([
            DevicePerfPlane::Startup,
            DevicePerfPlane::Cuj,
            DevicePerfPlane::Engine,
            DevicePerfPlane::System,
        ]);
    }
    validate_device_perf_args(&planes, corpus.as_ref(), backend, write)?;
    Ok(DevicePerfOptions {
        planes,
        corpus,
        backend,
        boot,
        trace,
        write,
    })
}

/// Cross-flag invariants after parsing: Direct needs a corpus to activate, `sync` pins the
/// Direct backend plus the device corpus, and release measurements require a deterministic SAF corpus.
fn validate_device_perf_args(
    planes: &[DevicePerfPlane],
    corpus: Option<&CorpusMode>,
    backend: DeviceBackend,
    write: bool,
) -> Result<()> {
    if planes.contains(&DevicePerfPlane::Workspace) && corpus.is_none() {
        bail!(
            "the `workspace` plane requires --corpus device — it resets only its owned test corpus"
        );
    }
    if backend == DeviceBackend::Direct && corpus.is_none() {
        bail!(
            "--backend direct requires --corpus (the probe activates the injected filesDir root)"
        );
    }
    if planes.contains(&DevicePerfPlane::Sync) && backend != DeviceBackend::Direct {
        bail!(
            "the `sync` plane requires --backend direct — sync durable state (.lomo/sync/v1) \
             must live under an app-private root, and the UI grant flow only accepts SAF trees"
        );
    }
    if planes.contains(&DevicePerfPlane::Sync) && corpus.is_none() {
        bail!(
            "the `sync` plane requires --corpus device — sync cycles need a deterministic \
             indexed workspace to plan/publish/reconcile against"
        );
    }
    if write && !planes.contains(&DevicePerfPlane::Profile) {
        bail!(
            "--write requires the `profile` plane — it rewrites the committed baseline-profile packaging inputs"
        );
    }
    if planes.contains(&DevicePerfPlane::Release) {
        ensure!(
            backend == DeviceBackend::Saf,
            "release measurements require --backend saf so DocumentsUI can reactivate the corpus after the signature swap"
        );
        ensure!(
            corpus.is_some(),
            "release measurements require --corpus device for a deterministic populated workspace"
        );
    }
    Ok(())
}

/// Entry point for `perf device`.
///
/// # Errors
/// Propagates device/adb/build failures and, after the report is written, any recorded
/// scenario/probe failure — the report on disk always reflects what actually ran.
pub fn run_device_perf(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let options = parse_device_perf_args(arguments)?;
    let adb = android::adb_program(workspace)?;
    let report_dir = workspace.reports_dir().join(DEVICE_PERF_REPORT_DIR);
    reset_dir(&report_dir)?;

    let serial = android::resolve_serial(&adb, options.boot, "perf device")?;
    let animation_scales = normalize_animation_scales(&adb, &serial)?;
    let (device_block, abi, frame_timeline) = device_facts(&adb, &serial)?;
    options.validate_device(&device_block)?;
    crate::util::emit_stderr(format_args!(
        "xtask: perf device on {serial} (abi={})",
        abi.android_name()
    ));
    if !frame_timeline {
        crate::util::emit_stderr(format_args!(
            "xtask: {serial}: using AndroidX application CPU traces and semantic launch readiness; display frame deadlines are unavailable"
        ));
    }

    // Measure the artifact built from current sources — same convention as `benchmark()`.
    let apk = android::build(workspace, AndroidVariant::Debug, &[abi])?;
    android::adb_install(&adb, &serial, &apk)?;
    let mut launch = Command::new(&adb);
    launch.args([
        "-s",
        &serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "com.lomo.app/.MainActivity",
    ]);
    run(&mut launch).context("start app process for same-UID debug probe registration")?;

    let gradle = android::gradle_command(workspace)?;
    let mut run = DeviceRun {
        workspace,
        adb: &adb,
        serial: &serial,
        gradle: &gradle,
        harness_build: &android::benchmark_harness_build_dir(workspace),
        report_dir: &report_dir,
        options: &options,
        debug_apk: apk.clone(),
        abi,
        frame_timeline,
        corpus: None,
        failures: Vec::new(),
        notes: Vec::new(),
    };

    // ---- Corpus injection + workspace configuration ----
    let corpus_block = if let Some(mode) = options.corpus {
        Some(run.inject_corpus(mode)?)
    } else {
        run.notes.push(
            "no --corpus: scenarios run against whatever workspace state the device already has"
                .to_owned(),
        );
        None
    };
    // Plane blocks re-attach corpus identity (mode/digest/bulk workload) so an engine or
    // sync result never floats free of the corpus it measured — plane-level injection is
    // the single corpus point, per-scenario args would be a no-op alias of it.
    run.corpus.clone_from(&corpus_block);

    let planes = run.execute_planes();
    let failures = std::mem::take(&mut run.failures);
    let report = json!({
        "schema_version": 1,
        "kind": "android_device_perf",
        "git_revision": android::git_revision(workspace)?,
        "measurement": {
            "target_package": BENCHMARK_TARGET_PACKAGE,
            "harness_module": BENCHMARK_MODULE,
            "harness_class": BENCHMARK_CLASS,
            "suppressed_errors": BENCHMARK_SUPPRESSED_ERRORS,
            "compilation_mode": if options.planes.contains(&DevicePerfPlane::Release) { "per-plane" } else { "none" },
            "channel": android::benchmark_channel(frame_timeline),
            "unavailable_metrics": if frame_timeline { json!([]) } else {
                json!(["frameDurationCpuMs", "frameOverrunMs", "timeToInitialDisplayMs", "timeToFullDisplayMs"])
            },
            "accuracy_note": "Debug planes declare EMULATOR,DEBUGGABLE; the release plane declares its own APK, compilation modes and suppressions. Emulator results remain emulator-grade evidence, not physical-device truth.",
            "animation_scales": animation_scales,
            "trace": options.trace,
        },
        "device": device_block,
        "apk": apk,
        "corpus": corpus_block,
        "options": {
            "planes": options.planes.iter().map(|plane| plane.name()).collect::<Vec<_>>(),
            "backend": options.backend.name(),
            "device_boot": options.boot.as_ref().map(android::DeviceBoot::label),
            "write": options.write,

        },
        "planes": Value::Object(planes),
        "failures": failures,
        "notes": run.notes,
    });
    write_report(&report_dir, &report)?;
    ensure!(
        failures.is_empty(),
        "perf device finished with failures: {}",
        failures.join("; ")
    );
    Ok(report)
}

/// Wipes then recreates the report directory — a stale report must never masquerade as the
/// current run's evidence.
fn reset_dir(dir: &Path) -> Result<()> {
    if dir.exists() {
        fs::remove_dir_all(dir).with_context(|| format!("failed to reset {}", dir.display()))?;
    }
    fs::create_dir_all(dir).with_context(|| format!("failed to create {}", dir.display()))
}

/// Serializes the assembled report to disk before the failure verdict is returned.
fn write_report(report_dir: &Path, report: &Value) -> Result<()> {
    let report_path = report_dir.join(DEVICE_PERF_REPORT);
    let pretty =
        serde_json::to_string_pretty(report).context("failed to serialize device perf report")?;
    fs::write(&report_path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", report_path.display()))?;
    crate::util::emit_stderr(format_args!("xtask: wrote {}", report_path.display()));
    Ok(())
}

/// Device identity block + the build ABI for the APK install. The device JSON carries
/// the frame-completion capability the plane gate reads, so the report itself states
/// why gated planes never executed.
fn device_facts(adb: &Path, serial: &str) -> Result<(Value, Abi, bool)> {
    let model = android::adb_getprop(adb, serial, "ro.product.model")?;
    let api_level = android::adb_getprop(adb, serial, "ro.build.version.sdk")?;
    let abilist = android::adb_getprop(adb, serial, "ro.product.cpu.abilist")?;
    let fingerprint = android::adb_getprop(adb, serial, "ro.build.fingerprint")?;
    let hardware = android::adb_getprop(adb, serial, "ro.hardware")?;
    let frame_timeline = android::frame_timeline_capable(&fingerprint, &hardware);
    let primary = abilist
        .split(',')
        .next()
        .filter(|value| !value.trim().is_empty())
        .with_context(|| format!("device {serial} reports empty ro.product.cpu.abilist"))?;
    let abi = Abi::parse(primary.trim())?;
    let api: u32 = api_level
        .trim()
        .parse()
        .with_context(|| format!("ro.build.version.sdk unparsable: {api_level:?}"))?;
    Ok((
        json!({
            "serial": serial,
            "model": model,
            "api_level": api,
            "abilist": abilist,
            "fingerprint": fingerprint,
            "hardware": hardware,
            "capabilities": {
                "sf_frame_completion_timeline": frame_timeline,
            },
        }),
        abi,
        frame_timeline,
    ))
}

/// Shared context for one `perf device` invocation — planes and probes borrow it instead of
/// repeating the device/harness identity in every signature.
struct DeviceRun<'a> {
    workspace: &'a Workspace,
    adb: &'a Path,
    serial: &'a str,
    gradle: &'a Path,
    harness_build: &'a Path,
    report_dir: &'a Path,
    options: &'a DevicePerfOptions,
    /// The debug APK installed at run start — the release plane reinstalls it afterwards so
    /// the device is left in the state the run began with.
    debug_apk: PathBuf,
    /// Device ABI — the release A/B build targets the same primary ABI.
    abi: Abi,
    /// Whether the device emits the SF frame-completion timeline — gates the
    /// frame-timing planes before they execute.
    frame_timeline: bool,
    /// Injected-corpus block (`digest/file_count/backend`) re-attached to plane results.
    corpus: Option<Value>,
    failures: Vec<String>,
    notes: Vec<String>,
}

impl DeviceRun<'_> {
    /// Executes the requested planes; failures land in `self.failures` and every executed
    /// scenario lands its own result entry regardless.
    fn execute_planes(&mut self) -> serde_json::Map<String, Value> {
        let mut planes = serde_json::Map::new();
        // This explicit plane resets app state for fresh-index samples and expects the
        // exact seeded corpus. Run it before CUJs/engine probes create or revise memos.
        if self.options.planes.contains(&DevicePerfPlane::Workspace) {
            planes.insert("workspace".to_owned(), self.workspace_plane());
        }
        if self.options.planes.contains(&DevicePerfPlane::Startup) {
            planes.insert("startup".to_owned(), self.startup_plane());
        }
        let want_cuj = self.options.planes.contains(&DevicePerfPlane::Cuj);
        let want_system = self.options.planes.contains(&DevicePerfPlane::System);
        if want_cuj || want_system {
            let (cuj, system) = self.cuj_planes(want_cuj, want_system);
            if let Some(value) = cuj {
                planes.insert("cuj".to_owned(), value);
            }
            if let Some(value) = system {
                planes.insert("system".to_owned(), value);
            }
        }
        if self.options.planes.contains(&DevicePerfPlane::Engine) {
            planes.insert("engine".to_owned(), self.engine_plane());
        }
        if self.options.planes.contains(&DevicePerfPlane::Sync) {
            planes.insert("sync".to_owned(), self.sync_plane());
        }
        if self.options.planes.contains(&DevicePerfPlane::Profile) {
            planes.insert("profile".to_owned(), self.profile_plane());
        }
        if self.options.planes.contains(&DevicePerfPlane::Release) {
            planes.insert("release".to_owned(), self.release_plane());
        }
        planes
    }

    fn startup_plane(&mut self) -> Value {
        let mut scenarios = BenchmarkScenario::DEVICE_STARTUP.to_vec();
        if self.options.corpus.is_some() && self.options.backend == DeviceBackend::Saf {
            // The dirty variant mutates files under /sdcard/Documents — shell-writable for SAF.
            // A Direct workspace lives under app-private files/ and the UI only grants SAF
            // roots, so a direct dirty start is measured at the engine plane instead.
            scenarios.push(BenchmarkScenario::StartupDirty);
            // A5: first-install onboarding — `pm clear` + `.lomo` wipe per iteration makes
            // each launch reconcile the pushed corpus write-bound through the production
            // DocumentsUI grant flow.
            scenarios.push(BenchmarkScenario::StartupOnboarding);
        } else if self.options.corpus.is_some() {
            self.notes.push(
                "startupDirty+startupOnboarding skipped: shell-side mutation of an app-private direct root is exercised by the engine rebuild probe, and the UI grant flow only accepts SAF roots"
                    .to_owned(),
            );
        } else {
            self.notes.push(
                "startupDirty+startupOnboarding skipped: --corpus not given; a dirty start needs an indexed corpus to dirty and onboarding needs a pushed workspace to grant"
                    .to_owned(),
            );
        }
        let mut results = Vec::with_capacity(scenarios.len());
        for scenario in scenarios {
            let outcome = self.run_scenario(scenario);
            results.push(self.record("startup", scenario, outcome));
        }
        json!({"scenarios": results})
    }

    /// CUJ pass; `system` wraps each scenario in gfxinfo reset + gfxinfo/meminfo dumps.
    fn cuj_planes(&mut self, want_cuj: bool, want_system: bool) -> (Option<Value>, Option<Value>) {
        let mut cuj_results = Vec::new();
        let mut system_results = Vec::new();
        for scenario in BenchmarkScenario::DEVICE_CUJ {
            if want_system && let Err(error) = self.reset_gfxinfo() {
                self.failures.push(format!(
                    "system/{}: gfxinfo reset: {error:#}",
                    scenario.name()
                ));
            }
            let outcome = self.run_scenario(scenario);
            let scenario_succeeded = outcome.is_ok();
            let summary = self.record("cuj", scenario, outcome);
            if want_cuj {
                cuj_results.push(summary);
            }
            if want_system {
                let captured = if scenario_succeeded {
                    self.collect_system_metrics(scenario.name())
                } else {
                    Err(anyhow::anyhow!(
                        "{} failed; no system snapshot accepted",
                        scenario.name()
                    ))
                };
                let entry = match captured {
                    Ok(block) => block,
                    Err(error) => {
                        self.failures
                            .push(format!("system/{}: {error:#}", scenario.name()));
                        json!({"name": scenario.name(), "status": "failed", "error": format!("{error:#}")})
                    }
                };
                system_results.push(entry);
            }
        }
        (
            want_cuj.then(|| json!({"scenarios": cuj_results})),
            want_system.then(|| json!({"scenarios": system_results})),
        )
    }

    fn engine_plane(&mut self) -> Value {
        match self.run_engine_probes() {
            Ok((probes, gate)) => {
                let mut block = json!({"probes": probes, "noise_gate": gate});
                if let Some(corpus) = &self.corpus {
                    set_json_field(&mut block, "corpus", corpus.clone());
                }
                block
            }
            Err(error) => {
                self.failures.push(format!("engine: {error:#}"));
                json!({"status": "failed", "error": format!("{error:#}")})
            }
        }
    }

    /// P7a/P7b: collects the device-measured baseline profile via `profileGen`, archives the
    /// `*-prof.txt` artifacts under `report_dir/profile/` and reports entry counts, distinct
    /// class counts and the class-set diff against committed measured profiles.
    /// `--write` publishes the packaging pair and receipt; release is an independent plane.
    fn profile_plane(&mut self) -> Value {
        match self.profile_plane_inner() {
            Ok(block) => block,
            Err(error) => {
                self.failures.push(format!("profile: {error:#}"));
                json!({"status": "failed", "error": format!("{error:#}")})
            }
        }
    }

    fn profile_plane_inner(&mut self) -> Result<Value> {
        let mut block = serde_json::Map::new();
        block.insert(
            "suppressed_errors".to_owned(),
            json!(BENCHMARK_SUPPRESSED_ERRORS),
        );

        // The journey exercises workspace code paths only once a SAF tree is granted; a
        // bare `perf device profile` still gets a deterministic workspace — the
        // workspace-shaped `device` corpus, the only tier whose dated day documents +
        // media/ layout the engine's date-key validation accepts — rather than silently
        // profiling the onboarding flow.
        if self.corpus.is_none() {
            let injected = self
                .inject_corpus(CorpusMode::Device)
                .context("profile workspace seeding failed")?;
            ensure!(
                injected.get("grant").and_then(Value::as_bool) == Some(true),
                "SAF workspace grant did not complete; profileGen would collect onboarding"
            );
            self.corpus = Some(injected);
        }
        block.insert(
            "workspace".to_owned(),
            self.corpus
                .as_ref()
                .context("profile corpus missing")?
                .clone(),
        );

        // Only artifacts this run produced may be attributed to it — the additional-output
        // tree persists files between runs and `*-prof.txt` carries no embedded run tag.
        let (artifacts, profile_dir) = self.collect_profile_artifacts(&mut block)?;

        // `profileGen` runs two collects by construction: a launch-only journey emits
        // `*-startup-prof.txt` (the R8 dex-layout input) and the full CUJ journey emits
        // `*-baseline-prof.txt` (the `baseline.prof` merge input). Either one missing
        // means the collection contract broke upstream — fail closed instead of
        // half-reporting a plane.
        let baseline =
            named_profile_artifact(&artifacts, "lomo-cuj-baseline-prof").with_context(|| {
                format!(
                    "profileGen produced no *-baseline-prof.txt under {}",
                    profile_dir.display()
                )
            })?;
        let startup = named_profile_artifact(&artifacts, "lomo-startup-startup-prof")
            .with_context(|| {
                format!(
                    "profileGen produced no *-startup-prof.txt under {}",
                    profile_dir.display()
                )
            })?;
        let (baseline_entries, baseline_classes) = profile_rules(baseline)?;
        let (startup_entries, startup_classes) = profile_rules(startup)?;
        ensure!(
            baseline_entries > 0 && startup_entries > 0,
            "collected profile is empty: {} / {}",
            baseline.display(),
            startup.display()
        );
        block.insert(
            "baseline".to_owned(),
            json!({
                "artifact": baseline,
                "entries": baseline_entries,
                "distinct_classes": baseline_classes.len(),
            }),
        );
        block.insert(
            "startup".to_owned(),
            json!({
                "artifact": startup,
                "entries": startup_entries,
                "distinct_classes": startup_classes.len(),
            }),
        );

        block.insert(
            "diff_vs_committed".to_owned(),
            self.profile_diff(&baseline_classes)?,
        );

        if self.options.write {
            block.insert(
                "write".to_owned(),
                self.write_profile_targets(baseline, startup)?,
            );
        } else {
            block.insert("write".to_owned(), json!({"enabled": false}));
        }

        block.insert("status".to_owned(), json!("ok"));
        Ok(Value::Object(block))
    }

    /// Runs `profileGen` and archives only this run's artifacts: `before` snapshots the
    /// additional-output tree so stale `*-prof.txt` files from earlier runs are excluded.
    fn collect_profile_artifacts(
        &self,
        block: &mut serde_json::Map<String, Value>,
    ) -> Result<(Vec<PathBuf>, PathBuf)> {
        let before = android::profile_artifact_snapshot(self.harness_build)?;
        self.run_harness_method(PROFILE_GEN_METHOD, &[])
            .context("profileGen collection failed")?;
        let profile_dir = self.report_dir.join("profile");
        let artifacts =
            android::collect_profile_outputs(self.harness_build, &profile_dir, &before)?;
        block.insert(
            "collection".to_owned(),
            json!({"method": PROFILE_GEN_METHOD, "artifacts": artifacts}),
        );
        Ok((artifacts, profile_dir))
    }

    /// Class-set difference between a new device collection and the committed measured profile.
    fn profile_diff(&self, collected: &BTreeSet<String>) -> Result<Value> {
        let committed_path = self.workspace.root.join(WRITE_BASELINE_TARGET);
        let (committed_entries, committed_classes) =
            profile_rules(&committed_path).with_context(|| {
                format!(
                    "committed baseline profile unreadable: {}",
                    committed_path.display()
                )
            })?;
        let added: Vec<&String> = collected.difference(&committed_classes).collect();
        let removed: Vec<&String> = committed_classes.difference(collected).collect();
        let sample = |items: &[&String]| -> Vec<String> {
            items
                .iter()
                .take(50)
                .map(|value| (*value).clone())
                .collect()
        };
        Ok(json!({
            "committed": committed_path,
            "committed_entries": committed_entries,
            "committed_classes": committed_classes.len(),
            "collected_classes": collected.len(),
            "added_classes_count": added.len(),
            "removed_classes_count": removed.len(),
            "added_classes_sample": sample(&added),
            "removed_classes_sample": sample(&removed),
        }))
    }

    /// Publishes official-parser-validated rules and a receipt of the measured APK/corpus.
    fn write_profile_targets(&self, baseline: &Path, startup: &Path) -> Result<Value> {
        let capture = crate::profiles::ProfileCapture {
            apk_sha256: crate::util::file_sha256(&self.debug_apk)?,
            corpus_sha256: self
                .corpus
                .as_ref()
                .and_then(|value| value.get("digest"))
                .and_then(Value::as_str)
                .context("profile corpus has no digest")?
                .to_owned(),
            device_fingerprint: self
                .shell(&["getprop", "ro.build.fingerprint"])?
                .trim()
                .to_owned(),
            source_revision: android::git_revision(self.workspace)?,
        };
        crate::profiles::publish_measured_profiles(
            &self.workspace.root,
            &self
                .workspace
                .android_sdk
                .join("cmdline-tools/latest/bin/profgen"),
            baseline,
            startup,
            capture,
        )
    }

    /// Signed-release A/B over startup and scroll, after regranting the same SAF corpus.
    /// Both installs activate the workspace through the production picker. Profileable
    /// release anchors prove the journey reaches real content, not an onboarding window.
    fn release_plane(&mut self) -> Value {
        let release_apk = match android::build(self.workspace, AndroidVariant::Release, &[self.abi])
        {
            Ok(apk) => apk,
            Err(error) => {
                self.failures
                    .push(format!("release: release build: {error:#}"));
                return json!({"status": "failed", "error": format!("release build: {error:#}")});
            }
        };

        match self.inject_corpus(CorpusMode::Device) {
            Ok(corpus) => self.corpus = Some(corpus),
            Err(error) => {
                self.failures.push(format!("release/corpus: {error:#}"));
                return json!({"status": "failed", "error": format!("{error:#}")});
            }
        }
        let release_digest = match crate::util::file_sha256(&release_apk) {
            Ok(digest) => digest,
            Err(error) => {
                self.failures
                    .push(format!("release/APK identity: {error:#}"));
                return json!({"status": "failed", "error": format!("{error:#}")});
            }
        };
        let mut runs = Vec::new();
        let mut swap_error: Option<String> = None;
        let swapped = self
            .shell(&["pm", "uninstall", BENCHMARK_TARGET_PACKAGE])
            .map(|_| ())
            .and_then(|()| android::adb_install(self.adb, self.serial, &release_apk))
            .and_then(|()| self.grant_benchmark_workspace());
        match swapped {
            Err(error) => swap_error = Some(format!("release install swap: {error:#}")),
            Ok(()) => {
                runs = self.measure_release_scenarios();
            }
        }

        // The debug install is the state every other plane assumes; restore it however the
        // sweep went.
        let restored = match self
            .shell(&["pm", "uninstall", BENCHMARK_TARGET_PACKAGE])
            .map(|_| ())
            .and_then(|()| android::adb_install(self.adb, self.serial, &self.debug_apk))
            .and_then(|()| self.grant_benchmark_workspace())
        {
            Ok(()) => true,
            Err(error) => {
                self.failures.push(format!("release/restore: {error:#}"));
                false
            }
        };

        if let Some(error) = swap_error {
            self.failures.push(format!("release: {error}"));
            return json!({
                "status": "failed",
                "error": error,
                "release_apk": release_apk,
                "debug_restored": restored,
            });
        }
        // A sweep that lost runs — or could not restore the debug install — is recorded
        // evidence with failures, never an "ok" block contradicting the outer verdict.
        let sweep_ok = restored
            && runs
                .iter()
                .all(|run| run.get("status").and_then(Value::as_str) == Some("ok"));
        json!({
            "status": if sweep_ok { "ok" } else { "failed" },
            "release_apk": release_apk,
            "compilation_modes": AB_COMPILATION_MODES,
            "target_debuggable": false,
            "suppressed_errors": ["EMULATOR"],
            "workspace": self.corpus,
            "release_apk_sha256": release_digest,
            "scenarios": RELEASE_AB_SCENARIOS
                .iter()
                .map(|scenario| scenario.name())
                .collect::<Vec<_>>(),
            "runs": runs,
            "debug_restored": restored,
        })
    }

    fn measure_release_scenarios(&mut self) -> Vec<Value> {
        let mut runs = Vec::new();
        for mode in AB_COMPILATION_MODES {
            let mode_dir = self.report_dir.join("release").join(mode);
            for scenario in RELEASE_AB_SCENARIOS {
                let outcome = self
                    .run_harness_method(
                        scenario.name(),
                        &[
                            ("compilationMode", mode),
                            ("androidx.benchmark.suppressErrors", "EMULATOR"),
                        ],
                    )
                    .and_then(|()| {
                        let raw = android::collect_benchmark_outputs(
                            self.harness_build,
                            &mode_dir,
                            scenario.name(),
                            self.options.trace,
                        )?;
                        android::summarize_benchmark_files(&raw, scenario.name())
                    });
                runs.push(match outcome {
                    Ok(summary) => json!({
                        "compilation_mode": mode,
                        "scenario": scenario.name(),
                        "status": "ok",
                        "summary": summary,
                    }),
                    Err(error) => {
                        self.failures
                            .push(format!("release/{mode}/{}: {error:#}", scenario.name()));
                        json!({
                            "compilation_mode": mode,
                            "scenario": scenario.name(),
                            "status": "failed",
                            "error": format!("{error:#}"),
                        })
                    }
                });
            }
        }
        runs
    }

    /// Folds a scenario outcome into either its summary or a recorded failure — a failed
    /// scenario stays in the report as `status:"failed"` instead of aborting the suite.
    /// Declared `limitations`/`emulator_limited` annotations ride along on success AND
    /// failure blocks so a restricted surface can never read as full-fidelity evidence.
    fn record(
        &mut self,
        plane: &str,
        scenario: BenchmarkScenario,
        outcome: Result<Value>,
    ) -> Value {
        let name = scenario.name();
        let mut block = match outcome {
            Ok(summary) => summary,
            Err(error) => {
                self.failures.push(format!("{plane}/{name}: {error:#}"));
                json!({"name": name, "status": "failed", "error": format!("{error:#}")})
            }
        };
        // summarize_benchmark_files emits a json! object and the failure block above is one
        // too — annotations attach in place; a non-object summary would be a schema bug the
        // caller sees verbatim.
        if let Some(map) = block.as_object_mut() {
            let limitations = scenario.limitations();
            if !limitations.is_empty() {
                map.insert(
                    "limitations".to_owned(),
                    json!(limitations.iter().collect::<Vec<_>>()),
                );
            }
            if scenario.emulator_limited() {
                map.insert("emulator_limited".to_owned(), Value::Bool(true));
            }
        }
        block
    }

    /// Generates the corpus (deterministic, digest-pinned), pushes it for the selected
    /// backend and — for SAF — grants the tree through the production `DocumentsUI` picker
    /// via the harness' `prepareWorkspace` method, which also waits until the pushed memos
    /// are indexed.
    fn inject_corpus(&self, mode: CorpusMode) -> Result<Value> {
        let corpus_dir = self
            .workspace
            .corpora_dir()
            .join(format!("device-perf-{}", mode.as_str()));
        // `generate_corpus` materializes into the directory it is given without clearing
        // it — the corpus dir is recycled across runs, so a shrunk generation would leave
        // stale dated docs that `adb push` ships but the manifest never lists. The pushed
        // tree must equal the manifest, so the dir is reset first.
        if corpus_dir.exists() {
            fs::remove_dir_all(&corpus_dir)
                .with_context(|| format!("failed to reset corpus dir {}", corpus_dir.display()))?;
        }
        let manifest = generate_corpus(&GenerateRequest {
            seed: 1,
            mode,
            output_dir: corpus_dir.clone(),
            fixture_root: self.workspace.root.join("fixtures"),
        })
        .context("device corpus generation failed")?;
        let digest = manifest
            .canonical_digest()
            .context("device corpus digest failed")?;
        crate::util::emit_stderr(format_args!(
            "xtask: {} corpus ready at {} (digest={digest})",
            mode.as_str(),
            corpus_dir.display()
        ));
        // Replacing files under an already-open Ready session cannot be followed by a
        // same-root configure no-op. Reopen the process against the staged corpus.
        self.shell(&["am", "force-stop", BENCHMARK_TARGET_PACKAGE])?;
        if (self.options.planes.contains(&DevicePerfPlane::Workspace)
            || self.options.planes.contains(&DevicePerfPlane::Profile)
            || self.options.planes.contains(&DevicePerfPlane::Release))
            && self.options.backend == DeviceBackend::Saf
        {
            // Workspace indexing tests require exactly the generated corpus, including
            // no CUJ-created documents left in this fixed test-owned directory.
            self.shell(&[
                "rm",
                "-rf",
                &format!("/sdcard/Documents/{DEFAULT_WORKSPACE_NAME}"),
            ])?;
        }
        match self.options.backend {
            DeviceBackend::Saf => self.inject_saf(mode, &corpus_dir, &manifest, &digest),
            DeviceBackend::Direct => self.inject_direct(mode, &corpus_dir, &manifest, &digest),
        }
    }

    fn inject_saf(
        &self,
        mode: CorpusMode,
        corpus_dir: &Path,
        manifest: &lomo_feasibility::CorpusManifestV1,
        digest: &str,
    ) -> Result<Value> {
        let device_root = format!("/sdcard/Documents/{DEFAULT_WORKSPACE_NAME}");
        self.push_to_saf_documents(corpus_dir, &device_root)?;
        self.grant_benchmark_workspace()
            .context("corpus/saf-grant: workspace activation failed; no measurements permitted")?;
        Ok(json!({
            "mode": mode.as_str(),
            "backend": "saf",
            "digest": digest,
            "device_root": device_root,
            "file_count": manifest.files.len(),
            "manifest": corpus_dir.join("corpus-manifest.v1.json"),
            "grant": true,
        }))
    }

    fn inject_direct(
        &self,
        mode: CorpusMode,
        corpus_dir: &Path,
        manifest: &lomo_feasibility::CorpusManifestV1,
        digest: &str,
    ) -> Result<Value> {
        let app_root = direct_workspace_root();
        self.push_to_direct_root(corpus_dir, &app_root)?;
        // `configure` persists the direct location on the production preferences path and
        // reconciles once, so later probes measure the same active workspace the UI uses.
        self.run_probe(
            "configure",
            &[("location", app_root.as_str())],
            "engine-configure",
        )
        .context(
            "corpus/direct-configure: workspace activation failed; no measurements permitted",
        )?;
        Ok(json!({
            "mode": mode.as_str(),
            "backend": "direct",
            "digest": digest,
            "device_root": app_root,
            "file_count": manifest.files.len(),
            "manifest": corpus_dir.join("corpus-manifest.v1.json"),
            "configured": true,
        }))
    }

    /// `adb push` the corpus contents into `/sdcard/Documents/<ws>` — the `ExternalStorage`
    /// provider sees the files immediately, no media scan needed.
    fn push_to_saf_documents(&self, corpus_dir: &Path, device_root: &str) -> Result<()> {
        // The device root is a lomo-perf-namespaced directory; a stale tree would mix corpus
        // generations, so it is reset before pushing — but the reset must keep every document
        // the app created itself. `.lomo` is the engine's durable record store: it survives
        // `pm uninstall` and `adb install -r`, its records are keyed by a stable logical root
        // id plus document path, and a byte-identical deterministic corpus reuses them as-is
        // (keeping them turns a re-push's reconcile read-bound instead of rewriting every
        // record — ~3 s/memo of SAF write amplification observed on emulator-5554). Records
        // must never outlive their document, though: an identity record whose `.md` was
        // deleted by the reset rejects every later write to that path with
        // `stale_identity_map`, and nothing prunes it because reconcile only scans documents
        // that exist. Stashing the whole root and re-importing only the paths the corpus did
        // not ship keeps document↔record pairs consistent; where a name collides the pushed
        // corpus wins and the stale copy stays out.
        let stash = "/sdcard/Documents/.lomo-perf-stash";
        self.shell_script(&format!(
            "rm -rf '{stash}' && if [ -d '{device_root}' ]; then mv '{device_root}' '{stash}'; fi"
        ))?;
        let mut push = Command::new(self.adb);
        push.arg("-s")
            .arg(self.serial)
            .arg("push")
            .arg(corpus_dir.as_os_str())
            .arg(device_root);
        run(&mut push).with_context(|| {
            format!("adb push {} -> {device_root} failed", corpus_dir.display())
        })?;
        // The manifest is a host-side evidence file, not workspace content.
        self.shell(&[
            "rm",
            "-f",
            &format!("{device_root}/corpus-manifest.v1.json"),
        ])?;
        // Re-import every stashed path the push did not land — `.lomo` plus any document the
        // app itself wrote between runs. `find`+`mv` per path keeps corpus bytes canonical on
        // collisions instead of `cp -rn`'s unpredictable directory merge.
        // `shell_script` rejects double quotes, and every staged path (`lomo-perf-device`,
        // dated `.md`, `.lomo` records, `media/img/att-*.jpg`) is space-free by
        // construction — the corpus generator and the app both write identifier-safe
        // names — so the bare expansions here stay word-safe.
        self.shell_script(&format!(
            "if [ -d '{stash}' ]; then cd '{stash}' && find . -mindepth 1 \\( -type f -o -type l \\) | while read -r f; do dest={device_root}/${{f#./}}; if [ ! -e $dest ]; then mkdir -p $(dirname $dest) && mv $f $dest; fi; done && cd / && rm -rf '{stash}'; fi"
        ))?;
        let entries = self.shell_count(&format!("ls '{device_root}' | wc -l"))?;
        ensure!(
            entries > 0,
            "adb push landed no files under {device_root} on {}",
            self.serial
        );
        crate::util::emit_stderr(format_args!(
            "xtask: pushed corpus to {device_root} ({entries} top-level entries)"
        ));
        Ok(())
    }

    /// Direct-backend staging: `adb push` to the shell-writable `/data/local/tmp`, relax read
    /// bits, then `run-as` copy into the app-private `files/` root (debuggable builds only).
    fn push_to_direct_root(&self, corpus_dir: &Path, app_root: &str) -> Result<()> {
        let staging = "/data/local/tmp/lomo-perf-corpus";
        self.shell(&["rm", "-rf", staging])?;
        let mut push = Command::new(self.adb);
        push.arg("-s")
            .arg(self.serial)
            .arg("push")
            .arg(corpus_dir.as_os_str())
            .arg(staging);
        run(&mut push)
            .with_context(|| format!("adb push {} -> {staging} failed", corpus_dir.display()))?;
        self.shell(&["chmod", "-R", "a+rX", staging])?;
        // `run-as` resolves the app-private files dir; the compound copy is one quoted
        // `sh -c` statement (see `script_arg` — adb does not preserve `-c` boundaries).
        self.run_as_script(&format!(
            "rm -rf '{app_root}' && mkdir -p '{app_root}' && cp -R '{staging}/.' '{app_root}/' && rm -f '{app_root}/corpus-manifest.v1.json'"
        ))
        .with_context(|| {
            format!("run-as copy into {app_root} failed — direct injection needs a debuggable build")
        })?;
        let entries = self.run_as_count(&format!("ls '{app_root}' | wc -l"))?;
        ensure!(
            entries > 0,
            "run-as copy landed no files under {app_root} on {}",
            self.serial
        );
        self.shell(&["rm", "-rf", staging])?;
        crate::util::emit_stderr(format_args!(
            "xtask: staged corpus into {app_root} ({entries} top-level entries)"
        ));
        Ok(())
    }

    /// One instrumented `LomoMacrobenchmark#<method>` invocation — used for scenario methods
    /// (which emit `*-benchmarkData.json`) and for `prepareWorkspace` (a pure `UIAutomator`
    /// driver that emits none). Instrumentation args map to
    /// `-Pandroid.testInstrumentationRunnerArguments.*`.
    fn grant_benchmark_workspace(&self) -> Result<()> {
        self.run_harness_method(
            "prepareWorkspace",
            &[("workspaceName", DEFAULT_WORKSPACE_NAME)],
        )
    }

    fn run_harness_method(&self, method: &str, args: &[(&str, &str)]) -> Result<()> {
        let mut command = Command::new(self.gradle);
        command
            .current_dir(self.workspace.root.join(BENCHMARK_MODULE))
            .args(["connectedDebugAndroidTest", "--console=plain"])
            .arg(format!(
                "-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_CLASS}#{method}"
            ));
        command.arg(format!(
            "-Pandroid.testInstrumentationRunnerArguments.measurementChannel={}",
            android::benchmark_channel(self.frame_timeline)
        ));
        if self.options.planes.contains(&DevicePerfPlane::System)
            && BenchmarkScenario::DEVICE_CUJ
                .iter()
                .any(|scenario| scenario.name() == method)
        {
            command.arg(format!(
                "-Pandroid.testInstrumentationRunnerArguments.systemScenario={method}"
            ));
        }
        for (key, value) in args {
            command.arg(format!(
                "-Pandroid.testInstrumentationRunnerArguments.{key}={value}"
            ));
        }
        run(&mut command).with_context(|| {
            // The Gradle failure line alone is useless for diagnosis — pull the JUnit
            // failure header out of the method's collected logcat when it exists so the
            // report records the actual assertion instead of just the task status.
            let detail = harness_failure_excerpt(self.harness_build, method)
                .map_or_else(String::new, |excerpt| format!(": {excerpt}"));
            format!(
                "instrumented method {method} failed{detail} (see {} for the Gradle log)",
                self.harness_build.display()
            )
        })
    }

    /// Runs one scenario method, collects its `*-benchmarkData.json` into the report directory
    /// and returns the parsed summary.
    fn run_scenario(&self, scenario: BenchmarkScenario) -> Result<Value> {
        let mut runner_args: Vec<(&str, &str)> = Vec::new();
        let workspace_root =
            if self.options.corpus.is_some() && self.options.backend == DeviceBackend::Saf {
                format!("/sdcard/Documents/{DEFAULT_WORKSPACE_NAME}")
            } else if self.options.corpus.is_some() {
                direct_workspace_root()
            } else {
                String::new()
            };
        if !workspace_root.is_empty() {
            runner_args.push(("workspaceRoot", workspace_root.as_str()));
            if self.options.backend == DeviceBackend::Saf {
                // `startupOnboarding` (and any DocumentsUI drive) matches the pushed folder
                // by display name — keep the arg explicit so a workspace rename cannot
                // silently default to a stale folder.
                runner_args.push(("workspaceName", DEFAULT_WORKSPACE_NAME));
            }
        }
        self.run_harness_method(scenario.name(), &runner_args)
            .and_then(|()| {
                let raw = android::collect_benchmark_outputs(
                    self.harness_build,
                    self.report_dir,
                    scenario.name(),
                    self.options.trace,
                )?;
                android::summarize_benchmark_files(&raw, scenario.name())
            })
    }

    /// `dumpsys gfxinfo <pkg> reset` before a scenario so the next dump only covers its frames.
    fn reset_gfxinfo(&self) -> Result<()> {
        self.shell(&["dumpsys", "gfxinfo", BENCHMARK_TARGET_PACKAGE, "reset"])
            .map(|_| ())
    }

    /// Post-scenario system metrics: gfxinfo summary (jank%), raw framestats, meminfo, and a
    /// diagnostic batterystats slice (emulator power rails are synthetic — the value is
    /// cumulative since last charge, useful only as a per-run delta trace).
    /// Unparseable output is recorded as `available:false` — never as an invented value.
    fn collect_system_metrics(&self, scenario: &str) -> Result<Value> {
        let out_dir = self.report_dir.join("system");
        fs::create_dir_all(&out_dir)
            .with_context(|| format!("failed to create {}", out_dir.display()))?;

        let snapshot = |kind: &str| {
            fs::read_to_string(out_dir.join(format!("{scenario}-{kind}.txt")))
                .with_context(|| format!("{scenario}: missing live-process {kind} artifact"))
        };
        let gfxinfo = snapshot("gfxinfo")?;
        let framestats = snapshot("framestats")?;
        let meminfo = snapshot("meminfo")?;
        let batterystats = self.shell(&["dumpsys", "batterystats"])?;
        fs::write(
            out_dir.join(format!("{scenario}-batterystats.txt")),
            &batterystats,
        )?;

        let janky = gfxinfo.lines().find_map(|line| {
            line.trim()
                .strip_prefix("Janky frames:")
                .map(str::trim)
                .map(str::to_owned)
        });
        let total_pss = meminfo.lines().find_map(|line| {
            let trimmed = line.trim();
            trimmed
                .strip_prefix("TOTAL PSS:")
                .map(|row| row.trim().to_owned())
        });
        let battery = self.battery_estimate(&batterystats)?;
        Ok(json!({
            "name": scenario,
            "capture_window": "after_last_iteration_trace_before_target_teardown",
            "counters_scope": "process_lifetime_including_setup",
            "gfxinfo": {
                "janky_frames": &janky,
                "available": janky.is_some(),
            },
            "framestats": { "available": framestats.contains("---PROFILEDATA---") },
            "meminfo": {
                "total_row": &total_pss,
                "available": total_pss.is_some(),
            },
            "batterystats": {
                "app_uid": battery.as_ref().map(|(uid, _)| uid.clone()),
                "estimated_mah_cumulative": battery.as_ref().map(|(_, mah)| mah.clone()),
                "available": battery.is_some(),
                "emulator_diagnostic_only": true,
            },
            "artifacts": [
                out_dir.join(format!("{scenario}-gfxinfo.txt")),
                out_dir.join(format!("{scenario}-framestats.txt")),
                out_dir.join(format!("{scenario}-meminfo.txt")),
                out_dir.join(format!("{scenario}-batterystats.txt")),
            ],
        }))
    }

    /// Extracts the target app's cumulative estimated-power line from a full
    /// `dumpsys batterystats` dump: `pm list packages -U` reports the numeric uid, which the
    /// dump lists as `UID u0a<appId>: <mAh> ...`.
    fn battery_estimate(&self, batterystats: &str) -> Result<Option<(String, String)>> {
        let uid_line = self
            .shell(&["pm", "list", "packages", "-U", BENCHMARK_TARGET_PACKAGE])
            .context("pm list packages -U for battery uid failed")?;
        let numeric = uid_line
            .rsplit("uid:")
            .next()
            .and_then(parse_u64)
            .with_context(|| format!("app uid unparsable from {uid_line:?}"))?;
        let app_id = numeric
            .checked_sub(10_000)
            .with_context(|| format!("app uid {numeric} below the app range"))?;
        let uid = format!("u0a{app_id}");
        let prefix = format!("UID {uid}:");
        Ok(batterystats.lines().find_map(|line| {
            let trimmed = line.trim();
            trimmed
                .strip_prefix(&prefix)
                .map(str::trim)
                .and_then(|rest| rest.split_whitespace().next())
                .map(|mah| (uid.clone(), mah.to_owned()))
        }))
    }

    /// One in-process engine probe: broadcast to the debug-gated receiver, poll for the
    /// result file under `files/perf-probe/`, pull it via `run-as` and archive it.
    fn run_probe(&self, op: &str, extras: &[(&str, &str)], run_name: &str) -> Result<Value> {
        // The preparation step starts the process, registering data's non-exported debug
        // receiver. Send a package-targeted broadcast as the app UID; --user 0 avoids
        // USER_CURRENT cross-user checks for non-shell callers.
        self.shell(&["am", "start", "-W", "-n", "com.lomo.app/.MainActivity"])
            .context("start process for data-owned debug probe registration")?;
        let mut broadcast = String::from("/system/bin/am broadcast --user 0 -p ");
        broadcast.push_str(BENCHMARK_TARGET_PACKAGE);
        broadcast.push_str(" -a ");
        broadcast.push_str(PROBE_ACTION);
        broadcast.push_str(" --es op ");
        broadcast.push_str(op);
        broadcast.push_str(" --es run ");
        broadcast.push_str(run_name);
        for (key, value) in extras {
            ensure!(
                !value.contains('\''),
                "perf probe extra {key} contains a shell quote"
            );
            broadcast
                .write_fmt(format_args!(" --es {key} '{value}'"))
                .map_err(|error| anyhow::anyhow!("probe broadcast assembly failed: {error}"))?;
        }
        let remote = format!("files/perf-probe/{run_name}.json");
        // A result file from an earlier invocation must never satisfy this probe's poll; a
        // `run-as` failure here also means the result could never be pulled, so fail fast.
        self.run_as_script(&format!("rm -f '{remote}'"))
            .context("failed to clear stale perf probe result")?;
        self.run_as_script(&broadcast)
            .with_context(|| format!("perf probe broadcast {op} failed"))?;
        let deadline = Instant::now() + PROBE_TIMEOUT;
        let mut found = false;
        while Instant::now() < deadline {
            let probe = self.run_as_script(&format!("test -f '{remote}' && echo ok"));
            match probe {
                Ok(out) if out.trim() == "ok" => {
                    found = true;
                    break;
                }
                Ok(_) | Err(_) => std::thread::sleep(PROBE_POLL),
            }
        }
        ensure!(
            found,
            "perf probe {op} ({run_name}) produced no result file within {PROBE_TIMEOUT:?}"
        );
        let quoted = format!("'{remote}'");
        let body = self.shell(&["run-as", BENCHMARK_TARGET_PACKAGE, "cat", &quoted])?;
        let document: Value = serde_json::from_str(&body)
            .with_context(|| format!("perf probe {run_name} returned invalid JSON: {body:?}"))?;
        let out_dir = self.report_dir.join("engine");
        fs::create_dir_all(&out_dir)?;
        let target = out_dir.join(format!("{run_name}.json"));
        fs::write(&target, &body)
            .with_context(|| format!("failed to archive {}", target.display()))?;
        ensure!(
            document.get("status").and_then(Value::as_str) == Some("ok"),
            "perf probe {run_name} reported failure: {body}"
        );
        Ok(json!({
            "op": op,
            "run": run_name,
            "artifact": target,
            "result": document,
        }))
    }

    /// Engine plane probes over the active workspace session: idle rebuilds, a dirty rebuild
    /// after a shell-side mutation, paging continuation, FTS search, an N-parameterized
    /// `.rec` history traversal (seeded first so N is real depth), the ≥500-file
    /// `listchildren` enumeration, and FFI roundtrip — the E-plane subset of the
    /// device-measurement plan. Returns `(probe results, P6 noise-gate block)`.
    fn run_engine_probes(&self) -> Result<(Vec<Value>, Value)> {
        // History depth: Direct updates land in ms; SAF writes cost ~seconds per document
        // operation, so the seeded revision count is scaled to the backend budget while the
        // traversal contract (cursor until N or exhaustion) stays identical.
        let (seed_n, history_n) = match self.options.backend {
            DeviceBackend::Direct => ("120", "120"),
            DeviceBackend::Saf => ("30", "30"),
        };
        let as_of = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .context("system clock before unix epoch")?
            .as_millis()
            .to_string();
        let mut probes = vec![
            self.run_probe(
                "paging",
                &[("pages", "5"), ("pageSize", "100")],
                "engine-paging",
            )?,
            self.run_probe("search", &[("samples", "9")], "engine-search")?,
            self.run_probe("ffi", &[("samples", "15")], "engine-ffi")?,
            self.run_probe(
                "views",
                &[("samples", "5"), ("asOfEpochMs", &as_of)],
                "engine-views",
            )?,
            // Seed before traverse — a fresh corpus memo has ~1 revision; N-parameterized
            // traversal measures something only when `.rec` history is actually deep.
            self.run_probe("history_seed", &[("n", seed_n)], "engine-history-seed")?,
            self.run_probe(
                "history",
                &[("samples", "7"), ("n", history_n)],
                "engine-history",
            )?,
            // Idle rebuilds come first — the dirty mutation lands before the dirty rebuild
            // probe so its first sample is a real unindexed-change reconcile.
            self.run_probe("rebuild", &[("samples", "5")], "engine-rebuild-idle")?,
        ];
        if self.options.corpus.is_some() {
            probes.push(self.run_probe(
                "listchildren",
                &[
                    ("path", DEVICE_BULK_DIR),
                    ("samples", "5"),
                    ("pageSize", "256"),
                ],
                "engine-listchildren",
            )?);
            probes.push(self.dirty_rebuild_probe()?);
            probes.push(self.run_media_stage_probe()?);
        }
        probes.push(self.run_probe("trash_cycle", &[("samples", "5")], "engine-trash-cycle")?);
        let baseline_path = self.workspace.reports_dir().join(ENGINE_BASELINE_FILE);
        let gate = engine_noise_gate(&baseline_path, self.options.backend, &probes)?;
        Ok((probes, gate))
    }

    /// Supplies one bounded corpus fixture; the app owns per-sample temp copies and leases.
    fn run_media_stage_probe(&self) -> Result<Value> {
        let source = self
            .workspace
            .corpora_dir()
            .join("device-perf-device/media/img/att-0000.jpg");
        let staging = "/data/local/tmp/lomo-perf-media-source.jpg";
        let mut push = Command::new(self.adb);
        push.arg("-s")
            .arg(self.serial)
            .arg("push")
            .arg(&source)
            .arg(staging);
        run(&mut push).context("push corpus media probe source")?;
        let outcome = self.run_probe(
            "media_stage",
            &[("samples", "5"), ("sourcePath", staging)],
            "engine-media-stage",
        );
        let cleanup = self
            .shell(&["rm", "-f", staging])
            .context("remove media probe source");
        match (outcome, cleanup) {
            (Ok(probe), Ok(_)) => Ok(probe),
            (Err(error), Ok(_)) | (Ok(_), Err(error)) => Err(error),
            (Err(error), Err(cleanup)) => {
                Err(error.context(format!("source cleanup also failed: {cleanup:#}")))
            }
        }
    }

    /// P5 sync plane: fixture bind → `adb reverse` → per-backend `sync_cycle` probes →
    /// guaranteed reverse teardown. `WebDAV` and `S3` legs hit the host plain-HTTP fixture;
    /// git uses a seeded `file://` bare remote inside app-private files. LAN is a declared
    /// restriction — never probed, never omitted.
    fn sync_plane(&mut self) -> Value {
        match self.sync_plane_inner() {
            Ok(block) => block,
            Err(error) => {
                self.failures.push(format!("sync: {error:#}"));
                json!({"status": "failed", "error": format!("{error:#}")})
            }
        }
    }

    fn sync_plane_inner(&mut self) -> Result<Value> {
        let fixture = HttpSyncFixture::start().context("sync fixture failed to start")?;
        let port = fixture.port();
        let mut reverse = Command::new(self.adb);
        reverse
            .arg("-s")
            .arg(self.serial)
            .arg("reverse")
            .arg(format!("tcp:{port}"))
            .arg(format!("tcp:{port}"));
        run(&mut reverse)
            .with_context(|| format!("adb reverse tcp:{port} failed on {}", self.serial))?;
        crate::util::emit_stderr(format_args!(
            "xtask: adb reverse tcp:{port} -> sync fixture (webdav=/dav/, s3=/{S3_BUCKET})"
        ));
        // Teardown must run however the measurement went — a leftover reverse mapping would
        // poison the next run's port choice.
        let block = self.sync_measure(&fixture, port);
        let mut teardown = Command::new(self.adb);
        teardown
            .arg("-s")
            .arg(self.serial)
            .arg("reverse")
            .arg("--remove")
            .arg(format!("tcp:{port}"));
        if let Err(error) = run(&mut teardown) {
            self.failures.push(format!(
                "sync/teardown: adb reverse --remove tcp:{port}: {error:#}"
            ));
        }
        Ok(block)
    }

    /// Executes the per-backend probe matrix against the live fixture + git remote.
    /// Per-leg failures are recorded in `self.failures` and stay visible in the block.
    ///
    /// `.lomo/sync/v1` is a single durable tree fenced to one identity triple
    /// (`WorkspaceGenerationId` + `RemoteDatasetId` + `RemoteIdentityDigest`): a second backend on
    /// the same workspace fails closed `sync_identity_mismatch`, exactly like a user who
    /// reconfigured the remote. Each leg therefore resets that tree first — the device-side
    /// equivalent of the app's disconnect — so every backend measures a real first cycle
    /// instead of re-reporting the durable fence. The reset is declared per leg as
    /// `syncStateReset` and never touches user documents.
    fn sync_measure(&mut self, fixture: &HttpSyncFixture, port: u16) -> Value {
        let workspace = direct_workspace_root();
        let mut backends = serde_json::Map::new();

        let extras = |pairs: &[(&str, String)]| -> Vec<(String, String)> {
            let mut v = vec![
                ("workspace".to_owned(), workspace.clone()),
                ("dataset".to_owned(), "lomo-perf-dataset".to_owned()),
                ("samples".to_owned(), "2".to_owned()),
            ];
            for (key, value) in pairs {
                v.push(((*key).to_owned(), (*value).clone()));
            }
            v
        };

        if let Err(error) = self.reset_sync_state(&workspace) {
            self.failures
                .push(format!("sync/webdav-state-reset: {error:#}"));
        }
        let webdav = self.sync_backend(
            fixture,
            "webdav",
            &extras(&[
                ("backend", "webdav".to_owned()),
                ("endpoint", fixture.webdav_url()),
                ("identity", DAV_USERNAME.to_owned()),
                ("secret", DAV_PASSWORD.to_owned()),
            ]),
        );
        backends.insert("webdav".to_owned(), webdav);

        if let Err(error) = self.reset_sync_state(&workspace) {
            self.failures
                .push(format!("sync/s3-state-reset: {error:#}"));
        }
        let s3 = self.sync_backend(
            fixture,
            "s3",
            &extras(&[
                ("backend", "s3".to_owned()),
                ("endpoint", fixture.s3_endpoint_url()),
                ("identity", S3_ACCESS_KEY_ID.to_owned()),
                ("secret", S3_SECRET_ACCESS_KEY.to_owned()),
                ("bucket", S3_BUCKET.to_owned()),
                ("prefix", "ws/".to_owned()),
                ("region", S3_REGION.to_owned()),
            ]),
        );
        backends.insert("s3".to_owned(), s3);

        let git = match self.stage_git_remote() {
            Ok((endpoint, head)) => {
                if let Err(error) = self.reset_sync_state(&workspace) {
                    self.failures
                        .push(format!("sync/git-state-reset: {error:#}"));
                }
                let mut block = self.sync_backend(
                    fixture,
                    "git",
                    &extras(&[
                        ("backend", "git".to_owned()),
                        ("endpoint", endpoint),
                        ("branch", GIT_BRANCH.to_owned()),
                        ("authorName", "lomo-perf".to_owned()),
                        ("authorEmail", "perf@lomo.device".to_owned()),
                    ]),
                );
                set_json_field(&mut block, "seeded_head", json!(head));
                block
            }
            Err(error) => {
                self.failures.push(format!("sync/git-seed: {error:#}"));
                json!({"backend": "git", "status": "failed", "error": format!("{error:#}")})
            }
        };
        backends.insert("git".to_owned(), git);

        backends.insert(
            "lan".to_owned(),
            json!({
                "status": "restricted",
                "emulator_limited": true,
                "note": "NSD/multicast is isolated by the emulator virtual router — pairing/discovery lifecycle is exercised host-side; real LAN discovery needs two physical devices and is declared emulator_limited, never synthesized",
            }),
        );

        let all_ok = ["webdav", "s3", "git"].iter().all(|name| {
            backends
                .get(*name)
                .and_then(|block| block.get("status"))
                .and_then(Value::as_str)
                == Some("ok")
        });
        json!({
            "status": if all_ok { "ok" } else { "failed" },
            "emulator_limited": true,
            "transport": {
                "kind": "adb_reverse",
                "port": port,
                "tls": false,
                "restriction": "plain-HTTP fixture over adb reverse; the HTTPS tier requires a non-Play image + CA injection and is not simulated",
            },
            "workspace": workspace,
            "backends": backends,
            "fixture_requests": stats_json(&fixture.stats()),
        })
    }

    /// One backend leg: stats snapshot → `sync_cycle` probe → request-delta merge. A failed
    /// leg stays in the report as `status:"failed"` with the request evidence it did produce.
    fn sync_backend(
        &mut self,
        fixture: &HttpSyncFixture,
        backend: &str,
        extras: &[(String, String)],
    ) -> Value {
        let before = fixture.stats();
        let refs: Vec<(&str, &str)> = extras
            .iter()
            .map(|(key, value)| (key.as_str(), value.as_str()))
            .collect();
        let run_name = format!("sync-{backend}");
        let outcome = self.run_probe("sync_cycle", &refs, &run_name);
        let delta = stats_delta(&before, &fixture.stats());
        let mut block = match outcome {
            Ok(probe) => {
                let mut block = probe;
                set_json_field(&mut block, "status", json!("ok"));
                block
            }
            Err(error) => {
                self.failures.push(format!("sync/{backend}: {error:#}"));
                json!({
                    "op": "sync_cycle",
                    "run": run_name,
                    "status": "failed",
                    "error": format!("{error:#}"),
                })
            }
        };
        set_json_field(&mut block, "backend", json!(backend));
        set_json_field(&mut block, "fixture_requests", delta);
        // Each leg ran on a freshly-reset `.lomo/sync` fence — declare it so a reader never
        // mistakes `sessionKind:first_takeover` per backend for durable-state reuse.
        set_json_field(&mut block, "syncStateReset", json!(true));
        block
    }

    /// Clears the durable `.lomo/sync` tree so the next `sync_cycle` leg measures a real
    /// first cycle against its own remote identity — the device-side equivalent of the
    /// app's disconnect before reconfiguring a different backend on one workspace.
    fn reset_sync_state(&self, workspace: &str) -> Result<()> {
        self.run_as_script(&format!("rm -rf '{workspace}/.lomo/sync'"))
            .context("sync state reset failed")?;
        Ok(())
    }

    /// Host-seeds a bare git remote with real commits, then lands it in app-private files
    /// (`/data/user/0/<pkg>/files/{GIT_REMOTE_DIR}`) for the `file://` endpoint leg.
    fn stage_git_remote(&self) -> Result<(String, String)> {
        let host_root = self.report_dir.join("sync").join("git");
        if host_root.exists() {
            fs::remove_dir_all(&host_root)
                .with_context(|| format!("failed to reset {}", host_root.display()))?;
        }
        let bare = host_root.join("remote.git");
        let seed = host_root.join("seed");
        fs::create_dir_all(&host_root)
            .with_context(|| format!("failed to create {}", host_root.display()))?;
        let body = "- 12:00:00\nseeded remote document for the device sync pull leg\n";
        let head = seed_device_git_remote(&bare, &seed, &[(GIT_SEED_DOC, body.as_bytes())])
            .context("git remote seeding failed")?;
        let staging = "/data/local/tmp/lomo-perf-git.git";
        self.shell(&["rm", "-rf", staging])?;
        let mut push = Command::new(self.adb);
        push.arg("-s")
            .arg(self.serial)
            .arg("push")
            .arg(&bare)
            .arg(staging);
        run(&mut push)
            .with_context(|| format!("adb push {} -> {staging} failed", bare.display()))?;
        self.shell(&["chmod", "-R", "a+rX", staging])?;
        let app_remote = format!("files/{GIT_REMOTE_DIR}");
        self.run_as_script(&format!(
            "rm -rf '{app_remote}' && cp -R '{staging}' '{app_remote}'"
        ))
        .with_context(|| format!("run-as copy into {app_remote} failed"))?;
        self.shell(&["rm", "-rf", staging])?;
        let endpoint =
            format!("file:///data/user/0/{BENCHMARK_TARGET_PACKAGE}/files/{GIT_REMOTE_DIR}");
        crate::util::emit_stderr(format_args!(
            "xtask: staged git remote ({GIT_SEED_DOC}, head={head}) at {endpoint}"
        ));
        Ok((endpoint, head))
    }

    /// Shell-side workspace mutation that leaves the next engine rebuild dirty: an append to
    /// the first dated corpus doc plus a fresh unindexed document. Mirrors `startupDirty`'s
    /// `makeWorkspaceDirty`: every `.md` under a workspace root is parsed as a `yyyy_MM_dd`
    /// date key during reconcile, so the fresh document's name must itself be a valid date —
    /// a decorative `zz_bench_dirty_*` name would fail the rebuild it is meant to dirty
    /// (`calendar_epoch_out_of_range`). The reserved `8xxx` year range keeps dirty docs distinct
    /// from corpus dates and lets the mutation remove the previous run's leftovers first,
    /// so exactly one unindexed add exists per run.
    fn mutate_workspace(&self) -> Result<()> {
        let mutation_millis = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .context("system clock before unix epoch")?
            .as_millis();
        let dirty_doc = format!(
            "{:04}_{:02}_{:02}.md",
            8_000 + (mutation_millis % 1_500),
            1 + (mutation_millis / 1_500_000) % 12,
            1 + (mutation_millis / 86_400_000) % 28,
        );
        let root = match self.options.backend {
            DeviceBackend::Saf => format!("/sdcard/Documents/{DEFAULT_WORKSPACE_NAME}"),
            DeviceBackend::Direct => direct_workspace_root(),
        };
        let statements = [
            format!(
                "printf -- '- 23:59:58 dirty-append-{mutation_millis}\\n' >> '{root}/2020_01_01.md'"
            ),
            format!("rm -f '{root}'/8???_??_??.md"),
            format!(
                "printf -- '- 23:59:59\\nbench-dirty-{mutation_millis}\\n' > '{root}/{dirty_doc}'"
            ),
        ];
        for statement in &statements {
            match self.options.backend {
                DeviceBackend::Saf => self.shell_script(statement),
                DeviceBackend::Direct => self.run_as_script(statement),
            }
            .context("workspace dirty mutation failed")
            .map(|_| ())?;
        }
        crate::util::emit_stderr(format_args!(
            "xtask: dirtied {} workspace (append + new doc {dirty_doc})",
            self.options.backend.name()
        ));
        Ok(())
    }

    fn shell(&self, args: &[&str]) -> Result<String> {
        android::adb_shell_text(self.adb, self.serial, args)
    }

    /// `sh -c <statement>` on device. `adb shell` space-joins argv into one remote command
    /// line, so a compound statement must arrive inside a quoted token — bare
    /// `["sh", "-c", stmt]` would let the remote `sh -c` capture only the first word and
    /// leak `|`/`&&` to the outer shell (running `ls` on `/`, counting the wrong dir).
    fn shell_script(&self, statement: &str) -> Result<String> {
        self.shell(&["sh", "-c", &script_arg(statement)?])
    }

    /// `run-as <pkg> sh -c <statement>` — the statement needs the same remote-token
    /// quoting; `run-as` itself does not re-quote.
    fn run_as_script(&self, statement: &str) -> Result<String> {
        self.shell(&[
            "run-as",
            BENCHMARK_TARGET_PACKAGE,
            "sh",
            "-c",
            &script_arg(statement)?,
        ])
    }

    fn shell_count(&self, statement: &str) -> Result<u64> {
        let out = self.shell_script(statement)?;
        out.trim()
            .parse()
            .with_context(|| format!("shell count unparsable: {out:?}"))
    }

    fn run_as_count(&self, statement: &str) -> Result<u64> {
        let out = self.run_as_script(statement)?;
        out.trim()
            .parse()
            .with_context(|| format!("run-as count unparsable: {out:?}"))
    }
}

/// Wraps a device `sh -c` statement in a double-quoted token so it survives `adb shell`'s
/// argv join as one remote word. Statements may embed single quotes but never `"`.
fn script_arg(statement: &str) -> Result<String> {
    ensure!(
        !statement.contains('"'),
        "device shell statement must not contain a double quote: {statement:?}"
    );
    Ok(format!("\"{statement}\""))
}

/// Finds the `*{kind}.txt` artifact inside this run's collected profile outputs.
///
/// androidx `Outputs.writeFile` writes the aggregated rules at the path-stable canonical
/// name plus an identical `-<timestamp>` twin, and the additional-output dir may persist
/// between runs: on a rerun the canonical path sits in `before` and stays excluded, so the
/// canonical match wins when fresh but the name-unique timestamped twin is acceptable —
/// both carry the same rules and only this run's files reach `artifacts`. `None` means the
/// collection contract broke upstream — callers attach the per-kind failure message.
fn named_profile_artifact<'a>(artifacts: &'a [PathBuf], kind: &str) -> Option<&'a PathBuf> {
    let named = |path: &&PathBuf| {
        path.file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.contains(kind))
    };
    artifacts
        .iter()
        .find(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with(&format!("{kind}.txt")))
        })
        .or_else(|| artifacts.iter().find(named))
}

/// Parses a human-readable profile file into (rule entries, distinct class names).
/// Rule syntax: `[H][S][P]L<class>;[->method(sig)]` or a bare `L<class>;` class rule —
/// the first `L` is always the class delimiter because the flag alphabet never includes
/// it. Non-comment lines without a class token count as entries but surface nowhere in
/// the diff — profman output never emits them; if a future format does, the entry count
/// still reflects the artifact's true size.
fn profile_rules(path: &Path) -> Result<(u64, BTreeSet<String>)> {
    let text =
        fs::read_to_string(path).with_context(|| format!("failed to read {}", path.display()))?;
    let mut entries = 0u64;
    let mut classes = BTreeSet::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        entries += 1;
        if let Some(start) = line.find('L') {
            // `chars`/`skip`/`take` express the byte-safe tail: `find` positions are char
            // boundaries here, but a rules file never justifies a panicking string slice.
            let tail: String = line.chars().skip(start).collect();
            if let Some(end) = tail.find(';') {
                classes.insert(tail.chars().take(end + 1).collect());
            }
        }
    }
    Ok((entries, classes))
}

fn direct_workspace_root() -> String {
    format!("/data/user/0/{BENCHMARK_TARGET_PACKAGE}/files/{DIRECT_WORKSPACE_NAME}")
}

/// Extracts the `JUnit` failure header for `method` from the connected-test logcat the
/// Gradle run just collected (`outputs/androidTest-results/connected/debug/<device>/logcat-
/// <class>-<method>.txt`). `None` when the artifact is absent or carries no exception —
/// callers then keep the bare Gradle failure line.
fn harness_failure_excerpt(harness_build: &Path, method: &str) -> Option<String> {
    let results_dir = harness_build.join("outputs/androidTest-results/connected/debug");
    let Ok(entries) = fs::read_dir(&results_dir) else {
        return None;
    };
    let suffix = format!("logcat-{BENCHMARK_CLASS}-{method}.txt");
    for entry in entries.flatten() {
        let candidate = entry.path().join(&suffix);
        if !candidate.is_file() {
            continue;
        }
        let Ok(text) = fs::read_to_string(&candidate) else {
            return None;
        };
        // `E TestRunner: ----- begin exception -----` is followed by the throwable line;
        // that single line carries the assertion (e.g. `java.lang.NullPointerException:
        // tasks list never materialized …`). Cap the excerpt so the report stays a one-
        // line summary — the full stack remains in the logcat artifact on disk.
        let marker = "----- begin exception -----";
        let start = text.find(marker)? + marker.len();
        let tail: String = text.chars().skip(start).collect();
        let excerpt = tail
            .lines()
            .map(str::trim)
            .find(|line| !line.is_empty())
            .map(|line| line.chars().take(240).collect::<String>())?;
        return Some(excerpt);
    }
    None
}

/// Resets `uitest` pollution: measurement requires animation scales at the platform default
/// 1.0. Values are read back and returned so the report records the applied state.
fn normalize_animation_scales(adb: &Path, serial: &str) -> Result<Value> {
    let mut scales = serde_json::Map::new();
    for key in ANIMATION_SCALE_KEYS {
        let mut put = Command::new(adb);
        put.args([
            "-s", serial, "shell", "settings", "put", "global", key, "1.0",
        ]);
        run(&mut put).with_context(|| format!("failed to set {key}=1.0 on {serial}"))?;
        let observed = android::adb_shell_text(adb, serial, &["settings", "get", "global", key])?;
        let observed = observed.trim().to_owned();
        ensure!(
            observed == "1.0",
            "animation scale {key} read back as {observed:?} on {serial}, expected 1.0"
        );
        scales.insert(key.to_owned(), json!(observed));
    }
    crate::util::emit_stderr(format_args!(
        "xtask: normalized animation scales to 1.0 on {serial}"
    ));
    Ok(Value::Object(scales))
}

/// Nearest-rank percentile over a sample vector — deterministic, documented, and the same
/// shape androidx benchmark summaries carry (the gate reads p50/p95, never means, so a
/// single outlier cannot masquerade as the distribution). `quantile_percent` is an integer
/// percent (50/95) so the rank math stays in `usize` — no float→int cast anywhere.
fn percentile(samples: &[f64], quantile_percent: usize) -> f64 {
    debug_assert_ne!(samples, [0_f64; 0]);
    let mut sorted = samples.to_vec();
    sorted.sort_by(f64::total_cmp);
    // Nearest-rank index ceil(p·n/100) computed in integer math and clamped into [1, n] —
    // the position is then provably in bounds (`swap_remove` returns the element itself).
    let rank = quantile_percent
        .saturating_mul(sorted.len())
        .saturating_add(99)
        / 100;
    let position = rank.clamp(1, sorted.len()).saturating_sub(1);
    sorted.swap_remove(position)
}

/// Sets `key` on a report object. Every caller hands in a `json!`-built object, so
/// `Value::Object` is a local construction invariant — not parsed input to validate.
fn set_json_field(object: &mut Value, key: &str, value: Value) {
    if let Value::Object(map) = object {
        map.insert(key.to_owned(), value);
    }
}

/// Parses a trimmed `u64` tail from shell output; an unparsable tail is `None` — the caller
/// then reports the shape-mismatch instead of an erased default.
fn parse_u64(value: &str) -> Option<u64> {
    let Ok(number) = value.trim().parse::<u64>() else {
        return None;
    };
    Some(number)
}

/// Serializes one fixture stats snapshot for the report.
fn stats_json(stats: &lomo_feasibility::SyncFixtureStats) -> Value {
    json!({
        "requests": stats.requests,
        "bytes_in": stats.bytes_in,
        "bytes_out": stats.bytes_out,
        "by_route": stats.by_route,
    })
}

/// Per-backend request evidence: the delta across one probe leg proves which routes the
/// remote was really exercised on (`PROPFIND dav`, `PUT s3`, …).
fn stats_delta(
    before: &lomo_feasibility::SyncFixtureStats,
    after: &lomo_feasibility::SyncFixtureStats,
) -> Value {
    let mut routes = BTreeMap::new();
    for (route, count) in &after.by_route {
        let seen = before.by_route.get(route).copied().unwrap_or(0);
        routes.insert(route.clone(), count.saturating_sub(seen));
    }
    json!({
        "requests": after.requests.saturating_sub(before.requests),
        "bytes_in": after.bytes_in.saturating_sub(before.bytes_in),
        "bytes_out": after.bytes_out.saturating_sub(before.bytes_out),
        "by_route": routes,
    })
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct EngineBaseline {
    schema_version: u32,
    kind: String,
    backends: BTreeMap<DeviceBackend, BTreeMap<String, EngineMetricBaseline>>,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct EngineMetricBaseline {
    p50: f64,
    p95: f64,
}

impl EngineBaseline {
    fn read(path: &Path) -> Result<Self> {
        let bytes = match fs::read(path) {
            Ok(bytes) => bytes,
            // Missing history is the only first-run state. Corrupt or unreadable history
            // must surface an error rather than silently resetting comparison evidence.
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Self {
                    schema_version: 2,
                    kind: "device_perf_engine_baseline".to_owned(),
                    backends: BTreeMap::new(),
                });
            }
            Err(error) => return Err(error).with_context(|| format!("read {}", path.display())),
        };
        let baseline: Self = serde_json::from_slice(&bytes)
            .with_context(|| format!("parse engine baseline {}", path.display()))?;
        ensure!(
            baseline.schema_version == 2,
            "unsupported engine baseline schema"
        );
        ensure!(
            baseline.kind == "device_perf_engine_baseline",
            "invalid engine baseline kind"
        );
        for metrics in baseline.backends.values() {
            for (key, metric) in metrics {
                ensure!(
                    !key.is_empty()
                        && metric.p50.is_finite()
                        && metric.p50 >= 0.0
                        && metric.p95.is_finite()
                        && metric.p95 >= metric.p50,
                    "invalid engine baseline metric {key}"
                );
            }
        }
        Ok(baseline)
    }
}

fn engine_probe_metrics(probes: &[Value]) -> Result<BTreeMap<String, Vec<f64>>> {
    ensure!(!probes.is_empty(), "engine noise gate requires probes");
    let mut metrics = BTreeMap::new();
    for probe in probes {
        let run = probe
            .get("run")
            .and_then(Value::as_str)
            .filter(|name| !name.is_empty())
            .context("engine probe missing run name")?;
        let values = probe
            .get("result")
            .and_then(|result| result.get("metrics"))
            .context("engine probe missing metrics")?;
        let samples: BTreeMap<String, Vec<f64>> = serde_json::from_value(values.clone())
            .with_context(|| format!("invalid engine probe metrics: {run}"))?;
        ensure!(!samples.is_empty(), "engine probe {run} has no metrics");
        for (name, values) in samples {
            let key = format!("{run}.{name}");
            ensure!(
                !name.is_empty()
                    && !values.is_empty()
                    && values
                        .iter()
                        .all(|value| value.is_finite() && *value >= 0.0),
                "invalid engine probe samples: {key}"
            );
            ensure!(
                metrics.insert(key.clone(), values).is_none(),
                "duplicate engine metric {key}"
            );
        }
    }
    Ok(metrics)
}

/// Evaluates and persists engine probe baselines independently for SAF and Direct.
///
/// Missing history is a first run; malformed history and samples are errors. Untouched
/// backend/metric entries survive updates. Deltas beyond 15% or fewer than five samples
/// remain diagnostic `inconclusive` verdicts, never product regression claims.
///
/// # Errors
/// Returns invalid input and baseline read/write errors without replacing prior history.
pub fn engine_noise_gate(
    baseline_path: &Path,
    backend: DeviceBackend,
    probes: &[Value],
) -> Result<Value> {
    let samples = engine_probe_metrics(probes)?;
    let mut baseline = EngineBaseline::read(baseline_path)?;
    // A backend's first run starts its own metric history, independently of other backends.
    let history = baseline.backends.entry(backend).or_default();
    let mut metrics = serde_json::Map::new();
    let mut inconclusive = Vec::new();
    for (key, values) in samples {
        let p50 = percentile(&values, 50);
        let p95 = percentile(&values, 95);
        let baseline_p50 = history.get(&key).map(|metric| metric.p50);
        let rel_delta = baseline_p50
            .filter(|base| *base > 0.0)
            .map(|base| (p50 - base) / base);
        let verdict = if values.len() < MIN_NOISE_SAMPLES {
            "inconclusive"
        } else if rel_delta.is_none() {
            "no_baseline"
        } else if rel_delta.is_some_and(|delta| delta.abs() > NOISE_THRESHOLD) {
            "inconclusive"
        } else {
            "within_threshold"
        };
        if verdict == "inconclusive" {
            inconclusive.push(key.clone());
        }
        metrics.insert(
            key.clone(),
            json!({
                "p50": p50, "p95": p95, "samples": values.len(),
                "baseline_p50": baseline_p50, "rel_delta": rel_delta, "verdict": verdict,
            }),
        );
        history.insert(key, EngineMetricBaseline { p50, p95 });
    }
    let temporary = baseline_path.with_extension("partial");
    fs::write(&temporary, serde_json::to_vec_pretty(&baseline)?)
        .with_context(|| format!("write engine baseline {}", temporary.display()))?;
    fs::rename(&temporary, baseline_path)
        .with_context(|| format!("publish engine baseline {}", baseline_path.display()))?;
    Ok(json!({
        "threshold": NOISE_THRESHOLD, "min_samples": MIN_NOISE_SAMPLES,
        "baseline": baseline_path, "backend": backend.name(), "schema_version": 2,
        "metrics": metrics, "inconclusive": inconclusive,
    }))
}
