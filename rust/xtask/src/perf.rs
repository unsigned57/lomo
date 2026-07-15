use std::cmp::Reverse;
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant, SystemTime};

use anyhow::{Context, Result, bail};
use lomo_feasibility::{
    BaselineConclusion, BaselineMetricV1, BaselineReportV1, BaselineSizesV1, CorpusMode,
    DeviceFingerprintV1, FeasibilityExitCode, GenerateRequest, HttpsFixture,
    ToolchainFingerprintV1, fixture_client, generate_corpus, probe_echo, probe_markdown_file,
    probe_s3_conditional_put, probe_s3_list_pagination, probe_stream_timeout,
    redact_sensitive_text, reset_http_probe_state, run_local_git_probe, run_sqlite_probe,
};

use crate::native::{self, Abi, NativeProfile};
use crate::tools;
use crate::util::{cargo, run, text_output};
use crate::workspace::{self, Workspace};

const SAMPLE_COUNT: usize = 21;
const WARMUP: usize = 3;
const STABILITY_MAX_REL_DELTA: f64 = 0.10;
const SCALE_MARKDOWN_SAMPLES: usize = 3;

/// Host metrics that must survive the two-round stability gate for `Pass`.
/// I/O-noisy probes (HTTPS, git bare, device cold start) are optional: exclusion does not
/// invent a pass, but missing any required name forces `Inconclusive`.
const REQUIRED_BASELINE_METRICS: &[&str] = &[
    "planner_local_only_pure_1000",
    "planner_high_conflict_pure_1000",
    "planner_long_path_envelope_1000",
    "sqlite_probe_wal_fts_backup",
    "markdown_fixture_set_parse",
    "markdown_scale_100k_memo_parse",
];

pub fn run_diagnostics(workspace: &Workspace) -> Result<()> {
    tools::ensure_diagnostics(workspace)?;

    // cargo-bloat rejects dual crate-type = [cdylib, rlib]. Measure the packaged
    // release shared libraries instead — that is the APK-relevant artifact.
    native::generate_all(workspace, NativeProfile::Release)?;
    report_native_so_sizes(workspace)?;

    let mut lines = cargo(workspace);
    lines.args(["llvm-lines", "--package", "lomo-native", "--lib"]);
    run(&mut lines)?;

    emit_quick_corpus(workspace)?;
    // Prove candidate dep graph compiles for four ABIs without shipping into production SO.
    native::verify_feasibility_android_targets(workspace, &native::Abi::ALL)?;
    emit_baseline_report(workspace)?;
    Ok(())
}

fn emit_quick_corpus(workspace: &Workspace) -> Result<()> {
    let output_dir = workspace.root.join("build/corpora/quick");
    let request = GenerateRequest {
        seed: 1,
        mode: CorpusMode::Quick,
        output_dir: output_dir.clone(),
        fixture_root: workspace.root.join("fixtures"),
    };
    let manifest = generate_corpus(&request).context("quick corpus generation failed")?;
    let digest = manifest
        .canonical_digest()
        .context("quick corpus digest failed")?;
    eprintln!(
        "xtask: quick corpus ready at {} (digest={digest})",
        output_dir.display()
    );
    Ok(())
}

fn report_native_so_sizes(workspace: &Workspace) -> Result<()> {
    let readelf = native::ndk_tool(workspace, "llvm-size")?;
    eprintln!("xtask: native release library sizes");
    for abi in Abi::ALL {
        let path = workspace
            .root
            .join("app/jniLibs")
            .join(abi.android_name())
            .join("liblomo_native.so");
        let mut command = Command::new(&readelf);
        command.arg(path.as_os_str());
        run(&mut command)?;
    }
    Ok(())
}

fn emit_baseline_report(workspace: &Workspace) -> Result<()> {
    let report_dir = workspace.root.join("build/reports/feasibility");
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let report = collect_baseline_report(workspace)?;
    let json = report.to_json().map_err(|error| {
        anyhow::anyhow!(
            "baseline report incomplete (exit {}): {error}",
            FeasibilityExitCode::ReportIncomplete.as_i32()
        )
    })?;
    let summary = report.to_human_summary().map_err(|error| {
        anyhow::anyhow!(
            "baseline summary incomplete (exit {}): {error}",
            FeasibilityExitCode::ReportIncomplete.as_i32()
        )
    })?;

    let json_path = report_dir.join("baseline-report.v1.json");
    let summary_path = report_dir.join("baseline-report.v1.txt");
    fs::write(&json_path, json)
        .with_context(|| format!("failed to write {}", json_path.display()))?;
    fs::write(&summary_path, format!("{summary}\n"))
        .with_context(|| format!("failed to write {}", summary_path.display()))?;

    // Keep committed size baseline in sync with measured release packaging.
    write_committed_size_baseline(workspace, &report)?;

    eprintln!("xtask: wrote {}", json_path.display());
    eprintln!("xtask: wrote {}", summary_path.display());
    eprintln!("{summary}");
    Ok(())
}

fn write_committed_size_baseline(workspace: &Workspace, report: &BaselineReportV1) -> Result<()> {
    let path = workspace
        .root
        .join("fixtures/baseline/size-baseline.v1.json");
    let mut metrics = Vec::new();
    for metric in &report.metrics {
        metrics.push(serde_json::json!({
            "name": metric.name,
            "unit": metric.unit,
            "p50": metric.p50,
            "p95": metric.p95,
            "peak_rss_bytes": metric.peak_rss_bytes,
            "network_request_count": metric.network_request_count,
            "workload_summary": metric.workload_summary,
            "samples": metric.samples,
            "result_count": metric.result_count,
            "warm_path_p50_ms": metric.warm_path_p50_ms,
        }));
    }
    let document = serde_json::json!({
        "schema_version": 1,
        "description": "Exact package/native size baseline plus host/device relative time metrics from `just perf`.",
        "measurement_notes": [
            "liblomo_native.so sizes are from production app/jniLibs packaging (feasibility-probe disabled).",
            "native-smoke/jniLibs may be larger when built with --features feasibility-probe; smoke size is not the APK gate.",
            "Debug universal APK size is environment-specific and recorded as a relative host baseline only.",
            "Time metrics are relative host (and optional attached device) measurements — not product SLA on absolute hardware.",
            "markdown_scale_100k_memo_parse runs in an isolated lomo-feasibility process; peak_rss_bytes is that process VmHWM.",
            "Scale metric also records result_count (memo files parsed) and warm_path_p50_ms (single-memo warm path).",
            "Other metrics omit peak_rss_bytes unless isolated; xtask /proc/self HWM is not product evidence.",
            "Hard gate: final compressed universal APK <= debug_universal_compressed_bytes * 1.15.",
            "Two measurement rounds: exclude metric if |p50_a-p50_b| > max(10% of max p50, 1ms).",
            "Pass requires every required_metrics entry established: planner trio, sqlite, markdown fixture set, and markdown_scale_100k_memo_parse with peak_rss/result_count/warm_path.",
            "Per-metric samples may differ (e.g. scale uses fewer full-corpus passes); see metrics[].samples.",
            "Authoritative stage-0 status: STAGE0-STATUS.md (must not drift from this note)."
        ],
        "native": {
            "liblomo_native_so_bytes": report.sizes.abi_so_bytes,
            "libjnidispatch_so_bytes": jnidispatch_sizes(workspace)?
        },
        "apk": {
            "debug_universal_compressed_bytes": report.sizes.apk_compressed_bytes,
            "source": "just perf / find_latest_debug_apk under .kotlin/toolchain-build",
            "kind": "host_relative_baseline",
            "hard_gate_multiplier": 1.15,
            "hard_gate_max_compressed_bytes": hard_gate_max_bytes(report.sizes.apk_compressed_bytes)
        },
        "performance": {
            "status": if matches!(report.conclusion, BaselineConclusion::Pass) {
                "established"
            } else {
                "partial_host_established"
            },
            "required_device": "API 26+ attached device or host for relative metrics",
            "sample_count": report.sample_count,
            "required_metrics": REQUIRED_BASELINE_METRICS,
            "device": {
                "api_level": report.device.api_level,
                "abi": report.device.abi,
                "kind": report.device.kind
            },
            "metrics": metrics,
            "policy": "Relative host/device baselines only; arm64 device evidence remains a stage-2 hard gate before first production ownership switch."
        }
    });
    let pretty =
        serde_json::to_string_pretty(&document).context("failed to serialize size baseline")?;
    fs::write(&path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", path.display()))?;
    eprintln!("xtask: wrote {}", path.display());
    Ok(())
}

fn jnidispatch_sizes(workspace: &Workspace) -> Result<BTreeMap<String, u64>> {
    let mut map = BTreeMap::new();
    for abi in Abi::ALL {
        let path = workspace
            .root
            .join("app/jniLibs")
            .join(abi.android_name())
            .join("libjnidispatch.so");
        if path.is_file() {
            map.insert(abi.android_name().to_owned(), fs::metadata(&path)?.len());
        }
    }
    Ok(map)
}

fn collect_baseline_report(workspace: &Workspace) -> Result<BaselineReportV1> {
    let git_revision = git_revision(workspace)?;
    let rustc = rustc_version(workspace)?;
    let mut abi_so_bytes = BTreeMap::new();
    for abi in Abi::ALL {
        let path = workspace
            .root
            .join("app/jniLibs")
            .join(abi.android_name())
            .join("liblomo_native.so");
        let metadata = fs::metadata(&path)
            .with_context(|| format!("missing release native library {}", path.display()))?;
        abi_so_bytes.insert(abi.android_name().to_owned(), metadata.len());
    }

    let apk_path = find_latest_debug_apk(workspace)?;
    let apk_compressed_bytes = fs::metadata(&apk_path)
        .with_context(|| format!("failed to stat {}", apk_path.display()))?
        .len();

    eprintln!("xtask: measuring host feasibility baselines (two rounds, {SAMPLE_COUNT} samples)");
    let round1 = measure_all_metrics(workspace)?;
    let round2 = measure_all_metrics(workspace)?;
    let (metrics, stability_notes) = stabilize_metrics(&round1, &round2);

    let device = device_fingerprint();
    let mut notes = vec![
        redact_sensitive_text(&format!(
            "apk_source={}",
            apk_path
                .strip_prefix(&workspace.root)
                .unwrap_or(&apk_path)
                .display()
        )),
        redact_sensitive_text(
            "time/RSS are relative host (and optional attached-device) baselines, not absolute product SLA",
        ),
    ];
    notes.extend(
        stability_notes
            .into_iter()
            .map(|note| redact_sensitive_text(&note)),
    );

    let conclusion = baseline_conclusion(&metrics, &mut notes);

    Ok(BaselineReportV1 {
        schema_version: BaselineReportV1::SCHEMA_VERSION,
        git_revision,
        toolchain: ToolchainFingerprintV1 {
            rustc,
            ndk: workspace::NDK_VERSION.to_owned(),
            host: std::env::consts::ARCH.to_owned(),
        },
        device,
        dependency_features: BTreeMap::from([
            (
                "lomo-native".to_owned(),
                "cdylib+uniffi (production: no feasibility-probe)".to_owned(),
            ),
            ("lomo-sync-core".to_owned(), "planner-v1".to_owned()),
            (
                "feasibility".to_owned(),
                "rusqlite+pulldown-cmark+reqwest/rustls+git2 probes".to_owned(),
            ),
        ]),
        sample_count: u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX),
        metrics,
        sizes: BaselineSizesV1 {
            apk_compressed_bytes,
            abi_so_bytes,
        },
        conclusion,
        notes,
    })
}

fn measure_all_metrics(workspace: &Workspace) -> Result<Vec<BaselineMetricV1>> {
    let mut metrics = Vec::new();
    metrics.extend(measure_planner_metrics(workspace)?);
    metrics.push(measure_sqlite_metric(workspace)?);
    metrics.push(measure_markdown_metric(workspace)?);
    metrics.push(measure_markdown_scale_metric(workspace)?);
    metrics.push(measure_http_metric()?);
    metrics.push(measure_git_metric(workspace)?);
    if let Some(metric) = measure_device_smoke_cold_start()? {
        metrics.push(metric);
    }
    Ok(metrics)
}

fn stabilize_metrics(
    first: &[BaselineMetricV1],
    second: &[BaselineMetricV1],
) -> (Vec<BaselineMetricV1>, Vec<String>) {
    let mut established = Vec::new();
    let mut notes = Vec::new();
    for left in first {
        let Some(right) = second.iter().find(|metric| metric.name == left.name) else {
            notes.push(format!(
                "metric {} missing from second round; excluded",
                left.name
            ));
            continue;
        };
        let denom = left.p50.max(right.p50).max(f64::EPSILON);
        let abs_delta = (left.p50 - right.p50).abs();
        // Relative 10% gate with a 1ms absolute floor so sub-ms microbenchmarks are not
        // rejected solely by measurement noise (plan: >10% relative blocks establishment).
        let abs_tol = (denom * STABILITY_MAX_REL_DELTA).max(1.0);
        if abs_delta > abs_tol {
            notes.push(format!(
                "metric {} p50 unstable ({:.3} vs {:.3}, abs={abs_delta:.3}, tol={abs_tol:.3}); excluded",
                left.name, left.p50, right.p50
            ));
            continue;
        }
        established.push(average_metric(left, right));
    }
    if established.is_empty() {
        notes.push(
            "no metrics passed the 10% two-round stability gate; report remains inconclusive"
                .to_owned(),
        );
    } else {
        notes.push(format!(
            "established {} metrics after two-round stability gate (max rel p50 delta {STABILITY_MAX_REL_DELTA})",
            established.len()
        ));
    }
    (established, notes)
}

fn baseline_conclusion(
    metrics: &[BaselineMetricV1],
    notes: &mut Vec<String>,
) -> BaselineConclusion {
    let established: std::collections::BTreeSet<&str> =
        metrics.iter().map(|metric| metric.name.as_str()).collect();
    let mut missing_required = Vec::new();
    for name in REQUIRED_BASELINE_METRICS {
        if !established.contains(name) {
            missing_required.push(*name);
        }
    }
    if missing_required.is_empty() {
        notes.push(redact_sensitive_text(
            "conclusion=pass: all required host metrics established (optional I/O metrics may be absent)",
        ));
        BaselineConclusion::Pass
    } else {
        notes.push(redact_sensitive_text(&format!(
            "conclusion=inconclusive: missing required metrics: {}",
            missing_required.join(", ")
        )));
        BaselineConclusion::Inconclusive
    }
}

fn average_metric(left: &BaselineMetricV1, right: &BaselineMetricV1) -> BaselineMetricV1 {
    BaselineMetricV1 {
        name: left.name.clone(),
        unit: left.unit.clone(),
        p50: f64::midpoint(left.p50, right.p50),
        p95: f64::midpoint(left.p95, right.p95),
        peak_rss_bytes: match (left.peak_rss_bytes, right.peak_rss_bytes) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (a, b) => a.or(b),
        },
        network_request_count: left.network_request_count.or(right.network_request_count),
        workload_summary: left.workload_summary.clone(),
        samples: left.samples.or(right.samples),
        result_count: left.result_count.or(right.result_count),
        warm_path_p50_ms: match (left.warm_path_p50_ms, right.warm_path_p50_ms) {
            (Some(a), Some(b)) => Some(f64::midpoint(a, b)),
            (a, b) => a.or(b),
        },
    }
}

const fn hard_gate_max_bytes(apk_compressed_bytes: u64) -> u64 {
    // 115% hard gate using integer arithmetic (ceil via +99/100).
    apk_compressed_bytes.saturating_mul(115).saturating_add(99) / 100
}

fn measure_planner_metrics(workspace: &Workspace) -> Result<Vec<BaselineMetricV1>> {
    let mut command = cargo(workspace);
    command.args([
        "run",
        "--locked",
        "--release",
        "-p",
        "lomo-sync-core",
        "--example",
        "planner_benchmark",
        "--",
        "1000",
    ]);
    let stdout = text_output(&mut command)?;
    let mut metrics = Vec::new();
    for line in stdout.lines().skip(1) {
        let columns: Vec<&str> = line.split(',').collect();
        if columns.len() < 5 {
            continue;
        }
        let scenario = columns[0];
        let size = columns[1];
        let iterations: u32 = columns[2]
            .parse()
            .context("planner iterations from benchmark CSV")?;
        let p50: f64 = columns[3].parse().context("planner p50")?;
        let p95: f64 = columns[4].parse().context("planner p95")?;
        metrics.push(BaselineMetricV1 {
            name: format!("planner_{scenario}_{size}"),
            unit: "ms".to_owned(),
            p50,
            p95,
            // Planner runs as a cargo subprocess; xtask /proc/self HWM is not the workload RSS.
            peak_rss_bytes: None,
            network_request_count: None,
            samples: Some(iterations),
            workload_summary: format!("sync_v1_{scenario}_size_{size}_iterations_{iterations}"),
            result_count: None,
            warm_path_p50_ms: None,
        });
    }
    if metrics.is_empty() {
        bail!("planner_benchmark produced no CSV metrics");
    }
    Ok(metrics)
}

fn measure_sqlite_metric(workspace: &Workspace) -> Result<BaselineMetricV1> {
    let root = workspace
        .root
        .join("build/reports/feasibility/measure-sqlite");
    fs::create_dir_all(&root)?;
    let mut samples = Vec::with_capacity(SAMPLE_COUNT);
    for index in 0..(WARMUP + SAMPLE_COUNT) {
        let path = root.join(format!("probe-{index}.sqlite"));
        drop(fs::remove_file(&path));
        let started = Instant::now();
        run_sqlite_probe(&path).context("sqlite probe")?;
        let elapsed = started.elapsed();
        if index >= WARMUP {
            samples.push(duration_ms(elapsed));
        }
        drop(fs::remove_file(&path));
    }
    let (p50, p95) = percentiles_ms(&mut samples);
    Ok(BaselineMetricV1 {
        name: "sqlite_probe_wal_fts_backup".to_owned(),
        unit: "ms".to_owned(),
        p50,
        p95,
        // In-process probe shares xtask address space; cumulative VmHWM is not isolated RSS.
        peak_rss_bytes: None,
        network_request_count: None,
        samples: Some(u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX)),
        workload_summary: "bundled_sqlite_wal_fk_fts5_backup_reopen".to_owned(),
        result_count: None,
        warm_path_p50_ms: None,
    })
}

fn measure_markdown_scale_metric(workspace: &Workspace) -> Result<BaselineMetricV1> {
    let output_dir = workspace.root.join("build/corpora/scale-perf");
    let request = GenerateRequest {
        seed: 1,
        mode: CorpusMode::Scale,
        output_dir: output_dir.clone(),
        fixture_root: workspace.root.join("fixtures"),
    };
    let manifest = generate_corpus(&request).context("scale corpus for markdown perf")?;
    let memo_count = manifest
        .files
        .iter()
        .filter(|entry| entry.relative_path.starts_with("memo/"))
        .count();
    if memo_count != 100_000 {
        bail!("scale corpus must materialize 100000 memos, got {memo_count}");
    }
    // Isolated process: peak RSS is the bench process only (not xtask HWM).
    let mut command = cargo(workspace);
    command.args([
        "run",
        "--locked",
        "--release",
        "-p",
        "lomo-feasibility",
        "--bin",
        "lomo-feasibility",
        "--",
        "scale-markdown-bench",
        "--corpus",
    ]);
    command.arg(&output_dir);
    command.args([
        "--full-samples",
        &SCALE_MARKDOWN_SAMPLES.to_string(),
        "--warm-samples",
        &SAMPLE_COUNT.to_string(),
    ]);
    let stdout = text_output(&mut command)?;
    let line = stdout
        .lines()
        .rev()
        .find(|line| line.starts_with("SCALE_MARKDOWN_BENCH "))
        .with_context(|| format!("missing SCALE_MARKDOWN_BENCH line in: {stdout}"))?;
    let fields = parse_scale_bench_line(line)?;
    Ok(BaselineMetricV1 {
        name: "markdown_scale_100k_memo_parse".to_owned(),
        unit: "ms".to_owned(),
        p50: fields.full_p50_ms,
        p95: fields.full_p95_ms,
        peak_rss_bytes: Some(fields.peak_rss_bytes),
        network_request_count: None,
        samples: Some(u32::try_from(SCALE_MARKDOWN_SAMPLES).unwrap_or(u32::MAX)),
        workload_summary: format!(
            "isolated scale-markdown-bench memo_files={} full_samples={} warm_samples={} total_events={}",
            fields.memo_files, fields.full_samples, fields.warm_samples, fields.total_events
        ),
        result_count: Some(fields.result_count),
        warm_path_p50_ms: Some(fields.warm_p50_ms),
    })
}

struct ScaleBenchFields {
    full_p50_ms: f64,
    full_p95_ms: f64,
    warm_p50_ms: f64,
    result_count: u64,
    total_events: u64,
    peak_rss_bytes: u64,
    full_samples: u32,
    warm_samples: u32,
    memo_files: u64,
}

fn parse_scale_bench_line(line: &str) -> Result<ScaleBenchFields> {
    let mut map = BTreeMap::new();
    for token in line.split_whitespace().skip(1) {
        let Some((key, value)) = token.split_once('=') else {
            continue;
        };
        map.insert(key.to_owned(), value.to_owned());
    }
    let require = |key: &str| -> Result<&str> {
        map.get(key)
            .map(String::as_str)
            .with_context(|| format!("missing {key} in scale bench line"))
    };
    Ok(ScaleBenchFields {
        full_p50_ms: require("full_p50_ms")?.parse()?,
        full_p95_ms: require("full_p95_ms")?.parse()?,
        warm_p50_ms: require("warm_p50_ms")?.parse()?,
        result_count: require("result_count")?.parse()?,
        total_events: require("total_events")?.parse()?,
        peak_rss_bytes: require("peak_rss_bytes")?.parse()?,
        full_samples: require("full_samples")?.parse()?,
        warm_samples: require("warm_samples")?.parse()?,
        memo_files: require("memo_files")?.parse()?,
    })
}

fn measure_markdown_metric(workspace: &Workspace) -> Result<BaselineMetricV1> {
    let fixtures = workspace.root.join("fixtures/markdown");
    let mut paths = Vec::new();
    for entry in fs::read_dir(&fixtures).with_context(|| format!("read {}", fixtures.display()))? {
        let entry = entry.with_context(|| format!("read entry under {}", fixtures.display()))?;
        let path = entry.path();
        if path
            .extension()
            .is_some_and(|ext| ext == "md" || ext == "bin")
        {
            paths.push(path);
        }
    }
    if paths.is_empty() {
        bail!("no markdown fixtures under fixtures/markdown");
    }

    let mut samples = Vec::with_capacity(SAMPLE_COUNT);
    for index in 0..(WARMUP + SAMPLE_COUNT) {
        let started = Instant::now();
        for path in &paths {
            // invalid-utf8 is expected to fail closed; both Ok and Err paths are timed.
            match probe_markdown_file(path) {
                Ok(report) => {
                    std::hint::black_box(report.event_count);
                }
                Err(error) => {
                    std::hint::black_box(error.to_string());
                }
            }
        }
        let elapsed = started.elapsed();
        if index >= WARMUP {
            samples.push(duration_ms(elapsed));
        }
    }
    let (p50, p95) = percentiles_ms(&mut samples);
    Ok(BaselineMetricV1 {
        name: "markdown_fixture_set_parse".to_owned(),
        unit: "ms".to_owned(),
        p50,
        p95,
        peak_rss_bytes: None,
        network_request_count: None,
        samples: Some(u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX)),
        workload_summary: format!("fixtures/markdown count={}", paths.len()),
        result_count: None,
        warm_path_p50_ms: None,
    })
}

fn measure_http_metric() -> Result<BaselineMetricV1> {
    let mut samples = Vec::with_capacity(SAMPLE_COUNT);
    let mut requests = 0_u64;
    for index in 0..(WARMUP + SAMPLE_COUNT) {
        // Global S3-shaped object state is process-wide; reset before each sample.
        reset_http_probe_state();
        let fixture = HttpsFixture::start().context("https fixture start")?;
        let client =
            fixture_client(fixture.ca_pem(), Duration::from_secs(2)).context("fixture client")?;
        let started = Instant::now();
        probe_echo(&client, &fixture.base_url()).context("echo")?;
        let listed = probe_s3_list_pagination(&client, &fixture.base_url()).context("list")?;
        std::hint::black_box(listed);
        probe_s3_conditional_put(&client, &fixture.base_url()).context("put")?;
        // Timeout path is expected to fail closed with a client error.
        match probe_stream_timeout(&fixture.base_url(), fixture.ca_pem()) {
            Ok(()) => {}
            Err(error) => {
                std::hint::black_box(error.to_string());
            }
        }
        let elapsed = started.elapsed();
        let stats = fixture.stats();
        requests = stats.requests.max(requests);
        drop(fixture);
        if index >= WARMUP {
            samples.push(duration_ms(elapsed));
        }
    }
    let (p50, p95) = percentiles_ms(&mut samples);
    Ok(BaselineMetricV1 {
        name: "https_s3_shape_fixture".to_owned(),
        unit: "ms".to_owned(),
        p50,
        p95,
        peak_rss_bytes: None,
        network_request_count: Some(requests),
        samples: Some(u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX)),
        workload_summary: "local_https_echo_list_put_timeout".to_owned(),
        result_count: None,
        warm_path_p50_ms: None,
    })
}

fn measure_git_metric(workspace: &Workspace) -> Result<BaselineMetricV1> {
    let root = workspace.root.join("build/reports/feasibility/measure-git");
    let mut samples = Vec::with_capacity(SAMPLE_COUNT);
    for index in 0..(WARMUP + SAMPLE_COUNT) {
        let path = root.join(format!("round-{index}"));
        let started = Instant::now();
        run_local_git_probe(&path).context("git probe")?;
        let elapsed = started.elapsed();
        if index >= WARMUP {
            samples.push(duration_ms(elapsed));
        }
        drop(fs::remove_dir_all(&path));
    }
    let (p50, p95) = percentiles_ms(&mut samples);
    Ok(BaselineMetricV1 {
        name: "git_bare_push_fetch_rebase".to_owned(),
        unit: "ms".to_owned(),
        p50,
        p95,
        peak_rss_bytes: None,
        network_request_count: None,
        samples: Some(u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX)),
        workload_summary: "vendored_libgit2_local_bare_transport".to_owned(),
        result_count: None,
        warm_path_p50_ms: None,
    })
}

fn measure_device_smoke_cold_start() -> Result<Option<BaselineMetricV1>> {
    if !adb_has_device() {
        eprintln!("xtask: no adb device; skipping native-smoke cold-start metric");
        return Ok(None);
    }
    if !adb_package_installed("com.lomo.nativesmoke") {
        eprintln!("xtask: native-smoke not installed; skipping cold-start metric");
        return Ok(None);
    }

    let mut samples = Vec::with_capacity(SAMPLE_COUNT);
    // warm launch (discard failures so missing UI readiness does not abort host baselines)
    if let Err(error) = force_stop_and_start() {
        eprintln!("xtask: warm native-smoke launch skipped: {error}");
    }
    for _ in 0..SAMPLE_COUNT {
        samples.push(force_stop_and_start()?);
    }
    let (p50, p95) = percentiles_ms(&mut samples);
    Ok(Some(BaselineMetricV1 {
        name: "native_smoke_cold_start_wait_ms".to_owned(),
        unit: "ms".to_owned(),
        p50,
        p95,
        peak_rss_bytes: None,
        network_request_count: None,
        samples: Some(u32::try_from(SAMPLE_COUNT).unwrap_or(u32::MAX)),
        workload_summary: "am_start_W_com.lomo.nativesmoke/.NativeSmokeActivity".to_owned(),
        result_count: None,
        warm_path_p50_ms: None,
    }))
}

fn force_stop_and_start() -> Result<f64> {
    let mut stop = Command::new("adb");
    stop.args(["shell", "am", "force-stop", "com.lomo.nativesmoke"]);
    run(&mut stop)?;
    std::thread::sleep(Duration::from_millis(200));
    let mut start = Command::new("adb");
    start.args([
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "com.lomo.nativesmoke/.NativeSmokeActivity",
    ]);
    let output = text_output(&mut start)?;
    for line in output.lines() {
        if let Some(value) = line.strip_prefix("WaitTime:") {
            let wait: f64 = value.trim().parse().context("WaitTime parse")?;
            return Ok(wait);
        }
    }
    bail!("am start -W did not report WaitTime:\n{output}");
}

fn adb_has_device() -> bool {
    let mut command = Command::new("adb");
    command.arg("devices");
    let Ok(devices) = text_output(&mut command) else {
        return false;
    };
    devices.lines().any(|line| {
        let mut parts = line.split_whitespace();
        matches!(parts.next(), Some(serial) if !serial.is_empty() && serial != "List")
            && parts.next() == Some("device")
    })
}

fn adb_package_installed(package: &str) -> bool {
    let mut command = Command::new("adb");
    command.args(["shell", "pm", "path", package]);
    text_output(&mut command).is_ok_and(|value| !value.trim().is_empty())
}

fn adb_getprop(key: &str) -> Option<String> {
    let mut command = Command::new("adb");
    command.args(["shell", "getprop", key]);
    text_output(&mut command).map_or(None, |value| {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_owned())
        }
    })
}

fn device_fingerprint() -> DeviceFingerprintV1 {
    if !adb_has_device() {
        return DeviceFingerprintV1 {
            api_level: workspace::ANDROID_API,
            abi: "host".to_owned(),
            kind: "host_probes".to_owned(),
        };
    }
    let api_level = adb_getprop("ro.build.version.sdk").map_or(workspace::ANDROID_API, |value| {
        value.parse::<u32>().unwrap_or(workspace::ANDROID_API)
    });
    let abi = adb_getprop("ro.product.cpu.abi").unwrap_or_else(|| "unknown".to_owned());
    DeviceFingerprintV1 {
        api_level,
        abi,
        kind: "attached_device_plus_host_probes".to_owned(),
    }
}

fn percentiles_ms(samples: &mut [f64]) -> (f64, f64) {
    samples.sort_by(|left, right| left.partial_cmp(right).unwrap_or(std::cmp::Ordering::Equal));
    let p50 = percentile(samples, 50);
    let p95 = percentile(samples, 95);
    (p50, p95)
}

fn percentile(samples: &[f64], percentile: usize) -> f64 {
    if samples.is_empty() {
        return 0.0;
    }
    let index = ((samples.len() - 1) * percentile) / 100;
    samples[index]
}

fn duration_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn git_revision(workspace: &Workspace) -> Result<String> {
    let mut command = Command::new("git");
    command
        .args(["rev-parse", "HEAD"])
        .current_dir(&workspace.root);
    let value = text_output(&mut command)?.trim().to_owned();
    if value.is_empty() {
        bail!("git revision is empty");
    }
    let mut status = Command::new("git");
    status
        .args(["status", "--porcelain"])
        .current_dir(&workspace.root);
    let porcelain = text_output(&mut status)?;
    if porcelain.trim().is_empty() {
        Ok(value)
    } else {
        Ok(format!("{value}-dirty"))
    }
}

fn rustc_version(_workspace: &Workspace) -> Result<String> {
    let mut rustc = Command::new("rustc");
    rustc.arg("--version");
    let value = text_output(&mut rustc)?.trim().to_owned();
    if value.is_empty() {
        bail!("rustc version is empty");
    }
    Ok(value)
}

fn find_latest_debug_apk(workspace: &Workspace) -> Result<PathBuf> {
    let build_root = workspace.root.join(".kotlin/toolchain-build");
    if !build_root.exists() {
        bail!(
            "no Kotlin toolchain build directory; run `just check` or `just ci` before `just perf`"
        );
    }

    let mut candidates = collect_debug_apks(&build_root)?;
    candidates.sort_by_key(|(modified, _)| Reverse(*modified));
    candidates
        .iter()
        .find(|(_, path)| path.to_string_lossy().contains("_app_"))
        .or_else(|| candidates.first())
        .map(|(_, path)| path.clone())
        .context("no debug APK found under .kotlin/toolchain-build")
}

fn collect_debug_apks(root: &Path) -> Result<Vec<(SystemTime, PathBuf)>> {
    let mut candidates = Vec::new();
    let mut pending = vec![root.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory)
            .with_context(|| format!("failed to read {}", directory.display()))?
        {
            let path = entry?.path();
            if path.is_dir() {
                pending.push(path);
                continue;
            }
            let is_debug_apk = path
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("apk"))
                && path
                    .file_name()
                    .and_then(|value| value.to_str())
                    .is_some_and(|name| name.contains("debug"));
            if !is_debug_apk {
                continue;
            }
            let modified = fs::metadata(&path)
                .and_then(|metadata| metadata.modified())
                .with_context(|| format!("failed to read metadata for {}", path.display()))?;
            candidates.push((modified, path));
        }
    }
    Ok(candidates)
}
