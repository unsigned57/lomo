//! Test Change Justification:
//! Reason category: user-requested Android task page removal.
//! Old behavior/assertion being replaced: tasksOpen was a supported and required CUJ.
//! Why old assertion is no longer correct: that Android screen no longer exists.
//! Coverage preserved by: every remaining scenario still resolves to its real journey.
//! Why this is not fitting the test to the implementation: the user removed the measured feature.
//! Test Change Justification:
//! Reason category: release measurements are an explicit independent plane.
//! Old behavior/assertion being replaced: --ab is coupled to profile collection and onboarding.
//! Why old assertion is no longer correct: a performance comparison must exercise the same
//! populated SAF workspace and be repeatable without recollecting packaging inputs.
//! Coverage preserved by: profile write opt-in, release-plane admission and invalid-backend
//! controls in `device_release_perf_contract`; default sweeps still exclude destructive work.
//! Why this is not fitting the test to the implementation: the prior path could only compare
//! onboarding after uninstall, which did not measure the intended startup/scroll workload.
//!
//! Behavior Contract:
//! Capability: `just perf device [startup|cuj|engine|system|all]... [--corpus device]
//! [--backend saf|direct] [--redroid] [--trace]` — the fail-closed argument surface, the
//! corpus-injection/backend model, and the source pins that keep the device report carrying
//! emulator-grade suppression declarations, animation-scale normalization and per-plane
//! results.
//! Owning layer: lomo-xtask perf command surface; priority: P0 (device measurement facility).
//!
//! Scenarios:
//! - Given `workspace` and a corpus on either backend, when parsed, then the explicit plane
//!   resolves; missing corpus is rejected and default sweeps keep this resetting test opt-in.
//!   RED: workspace-cli-red.log reports unknown argument workspace; GREEN: this target.
//! - Given no plane tokens, when `perf device` parses, then all implemented planes resolve
//!   (startup, cuj, engine, system) with the SAF backend.
//! - Given `startup cuj`, `--corpus device`, `--backend direct`, `--redroid` and `--trace`,
//!   when parsed, then each option lands on `DevicePerfOptions` and `direct` requires a
//!   corpus.
//! - Given `sync` without its pinned `--corpus device --backend direct` pair (or an
//!   unknown plane, unknown flag, missing flag value or host-tier corpus
//!   `quick`/`scale`/`capacity`), when parsed, then the command fails before any adb
//!   call — sync durable state must live under an app-private root and only the
//!   workspace-shaped `device` tier can reconcile under a real workspace root
//!   (`fixtures/…` bucket names like `DECISIONS.md` fail the engine's date-key
//!   validation during activation).
//! - Given `sync --corpus device --backend direct`, when parsed, then the Sync plane
//!   resolves and stays opt-in — `all`/bare sweeps never pick it up because they hold
//!   the SAF default backend sync cannot use.
//! - Given `perf device` CLI dispatch, when `sync`/unknown arguments arrive, then the binary
//!   exits non-zero naming the rejected token — without touching a device.
//! - Given `profile`, when parsed, then the measured baseline-profile collection plane
//!   resolves; `--write` requires the `profile` plane and release measurements use their own explicit plane, and
//!   the collection-only plane never joins the bare/`all` sweeps.
//! - Given redroid without display frame timing, when UI planes run, then supported
//!   application CPU trace and semantic launch readiness metrics are selected; reports keep
//!   their channel distinct from display frame timing.
//! - Given `AndroidX` profiler outputs, when archived, then traces retain binary bytes and
//!   artifact filenames cannot escape the benchmark output directory.
//! - Given a same-name profile rewritten by a new collection, when archived, then the
//!   new content is retained and unchanged old artifacts remain excluded.
//! - Given the module source, when inspected, then it pins the report path, the three
//!   animation-scale keys normalized to 1.0, `suppressed_errors` EMULATOR+DEBUGGABLE, the
//!   SAF push target under /sdcard/Documents, the run-as direct staging, the `DocumentsUI`
//!   grant driver, `dumpsys gfxinfo`/`meminfo` collection and the `PerfProbeReceiver`
//!   registration — the structural half of the device-measurement plan.
//!
//!
//! Observable outcomes: parsed options, non-zero CLI exits, and wiring pins between
//! `perf_device.rs`, `LomoMacrobenchmark` and the app-side probe receiver.
//!
//! TDD proof: `cargo test -p lomo-xtask --test device_perf_contract --locked`.
//! RED: before P-73 `perf` rejected every argument; `parse_device_perf_args` and the
//! report/injection wiring did not exist.
//! Test Change Justification:
//! Reason category: baseline persistence contract corrected to include backend identity.
//! Old behavior/assertion being replaced: the source pin for the unscoped v1 baseline file.
//! Why old assertion is no longer correct: v1 contains no backend provenance and cannot be
//! safely migrated or compared; v2 stores independent SAF/Direct metric histories.
//! Coverage preserved by: the updated filename pin plus `device_perf_baseline_contract` runtime
//! tests for backend alternation, malformed input rejection and preservation of history.
//! Why this is not fitting the test to the implementation: the measured cross-backend
//! contamination in audit/performance/device-baseline-2026-10-20.md §4.7 requires isolation.
//!
//! Test Change Justification (redroid channel):
//! Reason category: observed `AndroidX` application-trace support on the same redroid image.
//! Old behavior/assertion being replaced: every UI/profile plane requires a display timeline.
//! Why old assertion is no longer correct: the actual drawer journey passes with supported
//! `TraceSectionMetric` and semantic readiness; only display timing metrics are unavailable.
//! Coverage preserved by: image classification controls, channel assertions, raw device evidence
//! and the same probe op pins across the split data-owned implementation files.
//! Why this is not fitting the test to the implementation: the blanket gate contradicted the
//! independently executed `AndroidX` journey, archived in the performance continuation report.
//!
//! Excludes: attached-device execution and metric values (a `just perf device ...` run on a
//! device emits `android-device-perf/device-perf.v1.json`).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, bail, ensure};
    use lomo_xtask::{
        BenchmarkScenario, DeviceBackend, DeviceBoot, DevicePerfPlane, benchmark_channel,
        collect_benchmark_outputs, frame_timeline_capable, parse_device_perf_args, repository_root,
    };

    fn args(tokens: &[&str]) -> Vec<String> {
        tokens.iter().map(|token| (*token).to_owned()).collect()
    }

    // Given a deterministic corpus, workspace measurements are explicit and supported
    // on both storage backends; without that corpus they must fail before device work.
    #[test]
    fn workspace_measurements_require_a_corpus_and_remain_opt_in() -> Result<()> {
        for backend in ["saf", "direct"] {
            let parsed = parse_device_perf_args(&args(&[
                "workspace",
                "--corpus",
                "device",
                "--backend",
                backend,
            ]))?;
            ensure!(
                parsed
                    .planes
                    .iter()
                    .map(|plane| plane.name())
                    .collect::<Vec<_>>()
                    == ["workspace"]
            );
        }
        let error = parse_device_perf_args(&args(&["workspace"]))
            .err()
            .context("workspace without a corpus was accepted")?;
        ensure!(error.to_string().contains("requires --corpus device"));
        for selectors in [&[][..], &["all"][..]] {
            let parsed = parse_device_perf_args(&args(selectors))?;
            ensure!(
                parsed
                    .planes
                    .iter()
                    .all(|plane| plane.name() != "workspace")
            );
        }
        Ok(())
    }

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn bare_device_and_named_planes_parse() -> Result<()> {
        let bare = parse_device_perf_args(&args(&[]))?;
        ensure!(
            bare.planes.len() == 4,
            "bare `perf device` selects all planes"
        );
        ensure!(bare.backend == DeviceBackend::Saf, "default backend is saf");
        ensure!(bare.corpus.is_none());
        ensure!(bare.boot.is_none() && !bare.trace);

        let parsed = parse_device_perf_args(&args(&[
            "startup",
            "cuj",
            "--corpus",
            "device",
            "--backend",
            "saf",
            "--redroid",
            "--trace",
        ]))?;
        ensure!(
            parsed.planes == vec![DevicePerfPlane::Startup, DevicePerfPlane::Cuj],
            "explicit plane list must resolve in order"
        );
        ensure!(parsed.corpus.is_some(), "--corpus device parses");
        ensure!(
            matches!(&parsed.boot, Some(DeviceBoot::Redroid)),
            "--redroid lands on the provisioning slot"
        );
        ensure!(parsed.trace);

        let all = parse_device_perf_args(&args(&["all", "--corpus", "device"]))?;
        ensure!(
            all.planes.len() == 4,
            "`all` covers every implemented plane"
        );

        let direct = parse_device_perf_args(&args(&[
            "engine",
            "--corpus",
            "device",
            "--backend",
            "direct",
        ]))?;
        ensure!(direct.backend == DeviceBackend::Direct);

        // `sync` resolves only under its pinned direct+device-corpus pair — and like
        // `profile` it never joins `all`/bare sweeps (they hold the SAF default).
        let sync = parse_device_perf_args(&args(&[
            "sync",
            "--corpus",
            "device",
            "--backend",
            "direct",
        ]))?;
        ensure!(
            sync.planes == vec![DevicePerfPlane::Sync],
            "sync plane must resolve under its pinned pair"
        );
        ensure!(sync.backend == DeviceBackend::Direct && sync.corpus.is_some());
        for tokens in [&[][..], &["all", "--corpus", "device"][..]] {
            let parsed = parse_device_perf_args(&args(tokens))?;
            ensure!(
                !parsed.planes.contains(&DevicePerfPlane::Sync),
                "sync must stay opt-in: {tokens:?}"
            );
        }
        Ok(())
    }

    /// P7c regression (observed on emulator-5554): the release A/B sweep archives each
    /// compilation mode under `profile/ab/<mode>` — a directory `reset_dir` never creates —
    /// and `fs::copy` does not create missing parents, so every run's archival failed with
    /// ENOENT. `collect_benchmark_outputs` owns making its destination exist, the same
    /// precondition `collect_profile_outputs` already guarantees.
    #[test]
    fn benchmark_output_collection_creates_missing_report_subdir() -> Result<()> {
        let root = std::env::temp_dir().join(format!(
            "lomo-device-perf-contract-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |span| span.as_nanos())
        ));
        let harness = root.join("harness");
        let output_dir = harness.join("outputs/connected_android_test_additional_output/device");
        fs::create_dir_all(&output_dir)
            .with_context(|| format!("create {}", output_dir.display()))?;
        fs::write(
            output_dir.join("com.lomo.benchmark.test-benchmarkData.json"),
            r#"{"benchmarks":[{"name":"startup","className":"com.lomo.benchmark.LomoMacrobenchmark","repeatIterations":3,"metrics":{}}]}"#,
        )?;

        let mode_dir = root.join("report").join("profile").join("ab").join("none");
        let collected = collect_benchmark_outputs(&harness, &mode_dir, "startup", false)?;
        let landed = collected.len() == 1 && collected.iter().all(|path| path.is_file());
        let _clean: std::io::Result<()> = fs::remove_dir_all(&root);
        ensure!(
            landed,
            "the matching artifact must land under the created mode dir"
        );
        Ok(())
    }

    #[test]
    fn trace_archival_preserves_binary_bytes_and_rejects_escape() -> Result<()> {
        let root = std::env::temp_dir().join(format!("lomo-trace-contract-{}", std::process::id()));
        let output = root.join("harness/outputs/connected_android_test_additional_output/device");
        fs::create_dir_all(&output)?;
        let trace = [0, 255, 128, 10, 0, 254];
        fs::write(output.join("iteration.perfetto-trace"), trace)?;
        fs::write(output.join("drawer-gfxinfo.txt"), "live renderer snapshot")?;
        let artifact = output.join("run-benchmarkData.json");
        let document = |filename: &str| {
            serde_json::json!({"benchmarks":[{
                "name":"drawer", "className":"com.lomo.benchmark.LomoMacrobenchmark",
                "profilerOutputs":[{"type":"PerfettoTrace","filename":filename}]
            }]})
            .to_string()
        };
        fs::write(&artifact, document("iteration.perfetto-trace"))?;
        collect_benchmark_outputs(&root.join("harness"), &root.join("report"), "drawer", true)?;
        ensure!(
            fs::read(root.join("report/traces/iteration.perfetto-trace"))? == trace,
            "binary trace must survive archival byte for byte"
        );
        ensure!(
            fs::read_to_string(root.join("report/system/drawer-gfxinfo.txt"))?
                == "live renderer snapshot",
            "system artifacts must survive test-package teardown"
        );
        fs::write(&artifact, document("../escape.perfetto-trace"))?;
        ensure!(
            collect_benchmark_outputs(&root.join("harness"), &root.join("report"), "drawer", true)
                .is_err(),
            "profiler artifact paths cannot escape the source directory"
        );
        fs::remove_dir_all(root)?;
        Ok(())
    }

    #[test]
    fn profile_collection_archives_overwritten_paths_and_excludes_unchanged_files() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let harness = directory.path().join("harness");
        let output = harness.join("outputs/connected_android_test_additional_output/device");
        fs::create_dir_all(&output)?;
        let changed = output.join("lomo-cuj-baseline-prof.txt");
        let unchanged = output.join("old-startup-prof.txt");
        fs::write(&changed, "Lold/Class;\n")?;
        fs::write(&unchanged, "Lold/Unchanged;\n")?;
        let before = lomo_xtask::profile_artifact_snapshot(&harness)?;
        fs::write(&changed, "Lnew/Class;\n")?;
        let collected = lomo_xtask::collect_profile_outputs(
            &harness,
            &directory.path().join("report"),
            &before,
        )?;
        ensure!(
            collected.len() == 1,
            "a rewritten aggregate belongs to this collection"
        );
        ensure!(
            fs::read_to_string(collected.first().context("collected profile")?)? == "Lnew/Class;\n",
            "the new profile content must be archived"
        );
        Ok(())
    }

    /// P7b: the `profile` plane is opt-in collection (not a measurement sweep), and its
    /// state-changing flags require it.
    #[test]
    fn profile_collection_write_and_release_measurements_are_separate() -> Result<()> {
        let parsed = parse_device_perf_args(&args(&["profile"]))?;
        ensure!(
            parsed.planes == vec![DevicePerfPlane::Profile],
            "`profile` must resolve to the collection plane"
        );
        ensure!(
            !parsed.write,
            "bare `profile` collects without writing packaging inputs or the release A/B"
        );

        let parsed = parse_device_perf_args(&args(&[
            "profile", "release", "--write", "--corpus", "device",
        ]))?;
        ensure!(
            parsed.write && parsed.planes.contains(&DevicePerfPlane::Release),
            "profile publication and release measurement resolve independently"
        );

        for tokens in [&[][..], &["all"][..]] {
            let parsed = parse_device_perf_args(&args(tokens))?;
            ensure!(
                !parsed.planes.contains(&DevicePerfPlane::Profile),
                "bare/`all` sweeps must not pick up the collection plane: {tokens:?}"
            );
        }

        for (tokens, marker) in [
            (&["--write"][..], "--write"),
            (&["startup", "--ab"][..], "--ab"),
            (&["profile", "--bogus"][..], "--bogus"),
        ] {
            let error = match parse_device_perf_args(&args(tokens)) {
                Ok(_) => bail!("{tokens:?} unexpectedly parsed without error"),
                Err(error) => format!("{error:#}"),
            };
            ensure!(
                error.contains(marker),
                "rejection of {tokens:?} must name {marker}: {error}"
            );
        }
        Ok(())
    }

    #[test]
    fn parser_fails_closed_on_bad_input() -> Result<()> {
        for (tokens, marker) in [
            // `sync` alone, `sync` under SAF, or `sync` without a corpus each name the
            // invariant they break — never a silent fallback to another backend.
            (&["sync"][..], "sync"),
            (&["sync", "--corpus", "device"][..], "direct"),
            (&["sync", "--backend", "direct"][..], "--corpus"),
            (
                &["sync", "--corpus", "device", "--backend", "saf"][..],
                "direct",
            ),
            (&["bogus-plane"][..], "bogus-plane"),
            (&["--bogus-flag"][..], "--bogus-flag"),
            (&["--corpus"][..], "--corpus requires"),
            // The removed `--avd` surface rejects as an unknown flag, not a silent alias.
            (&["--avd", "perf_api36"][..], "--avd"),
            (&["--corpus", "quick"][..], "host-tier"),
            (&["--corpus", "scale"][..], "host-tier"),
            (&["--corpus", "capacity"][..], "host-tier"),
            (&["--backend", "smb"][..], "smb"),
            (
                &["engine", "--backend", "direct"][..],
                "direct requires --corpus",
            ),
        ] {
            let error = match parse_device_perf_args(&args(tokens)) {
                Ok(_) => bail!("{tokens:?} unexpectedly parsed without error"),
                Err(error) => format!("{error:#}"),
            };
            ensure!(
                error.contains(marker),
                "rejection of {tokens:?} must name {marker}: {error}"
            );
        }
        Ok(())
    }

    #[test]
    fn cli_rejects_device_plane_before_adb() -> Result<()> {
        // `sync` without its pinned pair still fails closed before any device work —
        // the fully-valid `sync --corpus device --backend direct` combination is never
        // exercised here because it would legitimately proceed to adb.
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["perf", "device", "sync"])
            .output()?;
        ensure!(!output.status.success(), "`sync` must exit non-zero");
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("sync"),
            "error must name the rejected plane: {stderr}"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["perf", "device", "sync", "--corpus", "device"])
            .output()?;
        ensure!(
            !output.status.success(),
            "`sync` under the SAF default must exit non-zero"
        );
        ensure!(
            String::from_utf8_lossy(&output.stderr).contains("direct"),
            "error must name the required backend"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["perf", "device", "--bogus-flag"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown perf device flag must exit non-zero"
        );
        ensure!(
            String::from_utf8_lossy(&output.stderr).contains("--bogus-flag"),
            "error must name the rejected flag"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["perf", "bogus"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown perf argument must exit non-zero"
        );
        Ok(())
    }

    /// The frame-timeline gate: redroid's `SurfaceFlinger` queues frames but never issues
    /// the completion callbacks ``AndroidX`` `FrameTimingMetric`/`StartupTimingMetric`
    /// (`amStartAndWait`) read — observed as `lastFrameNs` null and "0 found for
    /// frameDurationCpuMs". Planes measured through that channel are gated
    /// `unavailable`; the probe channel (`engine`/`sync`) is unaffected.
    #[test]
    fn device_capability_selects_display_or_application_metrics() -> Result<()> {
        // The pinned redroid image: either the fingerprint prefix or the hardware
        // property marks it — a renamed device keeps the gate on either signal.
        ensure!(
            !frame_timeline_capable(
                "redroid/redroid_x86_64_only/redroid_x86_64_only:15/BP1A.250505.005.D1/eng.root:userdebug/test-keys",
                "redroid"
            ),
            "the pinned redroid image must report the missing capability"
        );
        ensure!(
            !frame_timeline_capable(
                "google/sdk_gphone64_arm64/emu64a:15/AP31.250617.009/12345678:user/release-keys",
                "redroid"
            ),
            "hardware=redroid alone marks the image even under a foreign fingerprint"
        );
        for (fingerprint, hardware, what) in [
            (
                "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.005/12345678:user/release-keys",
                "ranchu",
                "a standard emulator keeps the frame timeline",
            ),
            (
                "google/husky/husky:15/AP3A.241005.015/12345678:user/release-keys",
                "husky",
                "a physical device keeps the frame timeline",
            ),
        ] {
            ensure!(frame_timeline_capable(fingerprint, hardware), "{what}");
        }
        ensure!(benchmark_channel(false) == "application_trace");
        ensure!(benchmark_channel(true) == "display_frames");
        Ok(())
    }

    /// `perf_device.rs` source pins: report identity, suppression discipline, corpus
    /// injection, metrics/probes, the profile and sync planes, and the noise gate.
    const PERF_DEVICE_PINS: &[&str] = &[
        // Report identity + suppression discipline.
        "android-device-perf",
        "device-perf.v1.json",
        "suppressed_errors",
        // Animation normalization — all three scales at 1.0, never the uitest 0.0.
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
        // SAF injection under the ExternalStorage provider + DocumentsUI grant driver.
        "\"lomo-perf-device\"",
        "/sdcard/Documents/",
        "prepareWorkspace",
        // Direct injection: shell staging + run-as copy into files/.
        "/data/local/tmp/lomo-perf-corpus",
        "run-as",
        // System metrics + engine probes.
        "dumpsys",
        "gfxinfo",
        "meminfo",
        "BENCHMARK_TARGET_PACKAGE",
        "files/perf-probe/",
        // Compilation mode pinned to the declared value, not silently left default.
        "\"none\"",
        // P7 measured baseline-profile plane: profileGen collection, artifact globs,
        // packaging-input write targets and the release A/B swap.
        "DevicePerfPlane::Profile",
        "profileGen",
        "-baseline-prof.txt",
        "-startup-prof.txt",
        "-primary.prof",
        // Both aggregated artifacts are contractual outputs of `profileGen`'s two
        // collects — a missing one fails closed instead of half-reporting a plane.
        "profileGen produced no *-baseline-prof.txt",
        "profileGen produced no *-startup-prof.txt",
        "baselineProfiles/generated.txt",
        "baselineProfiles/startup-prof.txt",
        "--write",
        "DevicePerfPlane::Release",
        "compilationMode",
        "partialRequire",
        "AndroidVariant::Release",
        "uninstall",
        // §3-D scale probes: seeded .rec history depth + the ≥500-file bulk
        // enumeration through the production ListChildren lane + production-shaped probes.
        "history_seed",
        "listchildren",
        "paging_prod",
        "search_prod",
        "preview_render",
        "media_classify",
        "gallery_paging",
        "engine-views",
        "engine-trash-cycle",
        "engine-media-stage",
        "DEVICE_BULK_DIR",
        // §3-H P5 sync plane: fixture → adb reverse → per-backend sync_cycle →
        // request deltas + guaranteed teardown; LAN/TLS stay declared restrictions.
        "DevicePerfPlane::Sync",
        "HttpSyncFixture",
        "adb reverse",
        "seed_device_git_remote",
        "sync_cycle",
        "fixture_requests",
        "stats_delta",
        "emulator_limited",
        // `--redroid` provisions the pinned container backend.
        "--redroid",
        // §4-P6 noise gate: p50/p95 + min-samples + persisted p50 baseline —
        // over-threshold deltas verdict `inconclusive`, never a fabricated verdict.
        "MIN_NOISE_SAMPLES",
        "NOISE_THRESHOLD",
        "device-perf-engine-baseline.v2.json",
        "inconclusive",
        "no_baseline",
        // Frame-timeline capability gate: redroid detection, the plane partition and
        // the recorded `unavailable` blocks that replace doomed executions.
        "frame_timeline_capable",
        "ro.hardware",
        "sf_frame_completion_timeline",
        "benchmark_channel",
        "unavailable_metrics",
    ];

    /// The app-side probe receiver must actually implement the ops the runner sends —
    /// pinned by name so a drift between `run_probe` extras and `execute()` dispatch
    /// fails this contract instead of surfacing as a 600s device timeout.
    const PROBE_RECEIVER_PINS: &[&str] = &[
        "\"listchildren\"",
        "\"paging_prod\"",
        "\"search_prod\"",
        "\"preview_render\"",
        "\"media_classify\"",
        "\"gallery_paging\"",
        "\"history\"",
        "\"history_seed\"",
        "\"sync_cycle\"",
        "\"views\"",
        "\"trash_cycle\"",
        "\"media_stage\"",
        "PlatformAction.ListChildren",
        "registerDirect",
        "capabilityRegistry.revoke",
        "requestedN",
        "revisionsListed",
        "itemsListed",
        "remoteSync.probeBackend",
        "remoteSync.runCycle",
        "remoteSync.cycleStatus",
        "issueSecretLease",
        "revokeSecretLease",
        "applyRemote = true",
    ];

    /// The host fixture is the other half of the sync contract — pin the real
    /// protocol surface so a stubbed responder can never satisfy the runner.
    const FIXTURE_PINS: &[&str] = &[
        "PROPFIND",
        "MKCOL",
        "If-Match",
        "If-None-Match",
        "412",
        "multistatus",
        "SigV4",
        "uploads",
        "seed_device_git_remote",
        "fn stats",
    ];

    #[test]
    fn source_pins_report_schema_injection_and_probes() -> Result<()> {
        let root = repository_root()?;
        let source = read(&root.join("crates/lomo-xtask/src/perf_device.rs"))?;
        for pin in PERF_DEVICE_PINS {
            ensure!(source.contains(pin), "perf_device.rs missing pin: {pin}");
        }
        let receiver = [
            "PerfProbeReceiver.kt",
            "EnginePerfProbe.kt",
            "PerfProbeReadMeasurements.kt",
            "PerfProbeSyncMeasurements.kt",
        ]
        .iter()
        .map(|name| read(&root.join("apps/android/data/src/engine").join(name)))
        .collect::<Result<Vec<_>>>()?
        .join("\n");
        for pin in PROBE_RECEIVER_PINS {
            ensure!(
                receiver.contains(pin),
                "PerfProbeReceiver.kt missing pin: {pin}"
            );
        }
        let fixture = read(&root.join("crates/lomo-feasibility/src/device_fixture.rs"))?;
        for pin in FIXTURE_PINS {
            ensure!(
                fixture.contains(pin),
                "device_fixture.rs missing pin: {pin}"
            );
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        ensure!(
            cli.contains("perf_device::run_device_perf"),
            "cli.rs must dispatch `perf device`"
        );
        // The persisted bootstrap job's deadline is measured from engine open: its SAF
        // capability probe must be driven before `openWorkspaceSession`, because a first-mount
        // reconcile can block that call for minutes and would expire the probe unexecuted.
        let factory =
            read(&root.join("apps/android/data/src/engine/BoltFfiNativeEngineFactory.kt"))?;
        ensure!(
            factory.contains("runner.drive(jobId)"),
            "BoltFfiNativeEngineFactory must drive the bootstrap batch via PlatformBatchRunner"
        );
        let drive = factory
            .find("requireCompletedProbe(platformBatchRunner, state.jobId)")
            .context("BoltFfiNativeEngineFactory must drive the bootstrap probe in openSession")?;
        let session_open = factory
            .find("port.openWorkspaceSession(")
            .context("BoltFfiNativeEngineFactory must open the workspace session")?;
        ensure!(
            drive < session_open,
            "bootstrap batch must be driven before openWorkspaceSession so its deadline \
             bounds the capability probe, not the data-dependent SAF reconcile"
        );
        Ok(())
    }

    /// §3-C scenario group + A5 onboarding: the exact scenario-name surface the plan
    /// demands, its membership in the executed sweeps, the production anchor contract the
    /// harness waits on, and the restriction annotations the report must carry for the
    /// CUJs whose full fidelity is unreachable on an emulator.
    #[test]
    fn c_group_scenarios_anchors_and_restrictions_pinned() -> Result<()> {
        // §3-C: every demanded scenario name resolves to an enum variant whose name()
        // is identical — the runner builds `Class#<name()>` so a rename on either side
        // breaks instrumentation selection.
        let c_group = [
            BenchmarkScenario::EditorOpen,
            BenchmarkScenario::ReviewOpen,
            BenchmarkScenario::StatsOpen,
            BenchmarkScenario::GalleryScroll,
            BenchmarkScenario::ReelOpen,
            BenchmarkScenario::ImageViewerOpen,
            BenchmarkScenario::SyncCenterOpen,
            BenchmarkScenario::ShareCardRender,
            BenchmarkScenario::ConflictDialogShow,
            BenchmarkScenario::WidgetRefresh,
            BenchmarkScenario::LanShareOpen,
        ];
        let c_group_names = [
            "editorOpen",
            "reviewOpen",
            "statsOpen",
            "galleryScroll",
            "reelOpen",
            "imageViewerOpen",
            "syncCenterOpen",
            "shareCardRender",
            "conflictDialogShow",
            "widgetRefresh",
            "lanShareOpen",
        ];
        for (scenario, name) in c_group.iter().zip(c_group_names) {
            ensure!(
                scenario.name() == name,
                "scenario name must equal the JUnit method: {name}"
            );
            ensure!(
                BenchmarkScenario::DEVICE_CUJ.contains(scenario),
                "§3-C scenario {name} must be a DEVICE_CUJ member so `perf device cuj` runs it"
            );
        }
        // A5 onboarding is a startup-plane measurement (pm clear → SAF grant → first
        // reconcile → first screen): named `startupOnboarding`, appended by the runner
        // only under `--corpus device --backend saf`.
        ensure!(
            BenchmarkScenario::StartupOnboarding.name() == "startupOnboarding",
            "A5 onboarding scenario name must be startupOnboarding"
        );
        ensure!(
            !BenchmarkScenario::DEVICE_CUJ.contains(&BenchmarkScenario::StartupOnboarding)
                && !BenchmarkScenario::DEVICE_STARTUP
                    .contains(&BenchmarkScenario::StartupOnboarding),
            "startupOnboarding is runner-appended, never an unconditional sweep member"
        );

        let root = repository_root()?;
        let perf = read(&root.join("crates/lomo-xtask/src/perf_device.rs"))?;
        for pin in [
            // A5: appended to the startup plane only when a SAF corpus exists to grant.
            "BenchmarkScenario::StartupOnboarding",
            "workspaceName",
            // Restricted/limited scenario annotations land on each result block — never
            // silently omitted, never fabricated as full-fidelity measurements.
            "limitations",
            "emulator_limited",
        ] {
            ensure!(
                perf.contains(pin),
                "perf_device.rs missing C-group pin: {pin}"
            );
        }

        // Production anchor contract — every anchor the new scenarios wait on exists in
        // BenchmarkAnchorContract.kt AND is mirrored in the harness as a res id constant
        // (same literal on both sides: a drift in either silently un-anchors a scenario).
        let anchors =
            read(&root.join("apps/android/app/src/benchmark/BenchmarkAnchorContract.kt"))?;
        let java = read(&root.join(
            "apps/android/benchmark/src/androidTest/java/com/lomo/benchmark/LomoMacrobenchmark.java",
        ))?;
        for anchor in [
            "benchmark_input_editor_expand",
            "benchmark_memo_action_lan_share",
            "benchmark_gallery_cell_",
            "benchmark_gallery_reel_root",
            "benchmark_lan_share_root",
            "benchmark_sync_conflict_root",
            "sync_center_root",
            "sync_center_tab_conflicts",
        ] {
            ensure!(
                anchors.contains(anchor),
                "BenchmarkAnchorContract.kt missing anchor literal: {anchor}"
            );
            ensure!(
                java.contains(anchor),
                "LomoMacrobenchmark.java missing anchor literal: {anchor}"
            );
        }
        // Harness pins for the restricted paths: the explicit-component widget broadcast
        // route (APPWIDGET_UPDATE itself is a protected broadcast the shell cannot send)
        // and the package-clear first-install drive of startupOnboarding.
        for pin in [
            "BENCH_WIDGET_PING",
            "LomoWidgetReceiver",
            "pm clear",
            "grantPushedFolderInDocumentsUi",
        ] {
            ensure!(
                java.contains(pin),
                "LomoMacrobenchmark.java missing pin: {pin}"
            );
        }
        Ok(())
    }
}
