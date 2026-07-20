//! Behavior Contract
//!
//! Capability: measure the production `lomo-workspace` document owner in an isolated process over
//! exactly 100,000 materialized memo files, including its owned Render IR and byte-stable unedited
//! serialization.
//!
//! Scenarios:
//! - Given a scale corpus with exactly 100,000 Markdown files, when full samples run, then every file
//!   is parsed by `parse_workspace_document`, its owned Render IR is counted, and unedited bytes are
//!   verified identical.
//! - Given measurement start, when timed full samples run, then one untimed full-corpus pass and one
//!   untimed steady-state pass have already warmed page cache / allocator so p50 is steady-state
//!   owner work rather than cold-FS or first-timed-pass noise.
//! - Given the warmed corpus, when the warm path runs, then single-document p50 is reported.
//! - Given a missing/incomplete corpus, invalid UTF-8/stem, parse failure, byte drift, or unavailable
//!   process `VmHWM`, when measurement runs, then the process fails closed without emitting success.
//!
//! Observable outcomes: one `WORKSPACE_SCALE_BENCH` line with p50/p95/warm p50, file/memo/node
//! counts, sample counts, and isolated peak RSS.
//! TDD proof: RED before implementation was `error: no example target named
//! workspace_scale_benchmark in lomo-workspace package`.
//! Excludes: corpus generation, Stage-0 feasibility parser, Kotlin baseline collection, and APK size.

use std::env;
use std::error::Error;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use lomo_workspace::{SourceBytes, parse_workspace_document};

const REQUIRED_MEMO_FILES: usize = 100_000;

fn main() -> ExitCode {
    match run(&env::args().skip(1).collect::<Vec<_>>()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("workspace-scale-benchmark: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run(arguments: &[String]) -> Result<(), Box<dyn Error>> {
    let options = BenchmarkOptions::parse(arguments)?;
    let mut memo_paths = collect_memo_paths(&options.corpus.join("memo"))?;
    memo_paths.sort();
    if memo_paths.len() != REQUIRED_MEMO_FILES {
        return Err(invalid_input(format!(
            "expected {REQUIRED_MEMO_FILES} Markdown files, got {}",
            memo_paths.len()
        )));
    }

    // Untimed full-corpus passes warm page cache / allocator so timed p50 reflects steady-state
    // owner work rather than cold FS or first-pass allocator noise between isolated launches.
    // Two untimed passes: (1) bring corpus into cache, (2) settle after first full walk.
    for _ in 0..2 {
        let mut warmup_nodes = 0_u64;
        for path in &memo_paths {
            let result = parse_path(path)?;
            warmup_nodes = warmup_nodes.saturating_add(result.node_count);
        }
        std::hint::black_box(warmup_nodes);
    }

    let mut full_samples_ms = Vec::with_capacity(options.full_samples);
    let mut result_count = 0_u64;
    let mut memo_count = 0_u64;
    let mut node_count = 0_u64;
    for _ in 0..options.full_samples {
        let started = Instant::now();
        let mut sample_result_count = 0_u64;
        let mut sample_memo_count = 0_u64;
        let mut sample_node_count = 0_u64;
        for path in &memo_paths {
            let result = parse_path(path)?;
            sample_result_count = sample_result_count.saturating_add(1);
            sample_memo_count = sample_memo_count.saturating_add(result.memo_count);
            sample_node_count = sample_node_count.saturating_add(result.node_count);
        }
        full_samples_ms.push(started.elapsed().as_secs_f64() * 1_000.0);
        result_count = sample_result_count;
        memo_count = sample_memo_count;
        node_count = sample_node_count;
    }
    full_samples_ms.sort_by(f64::total_cmp);

    let warm_path = memo_paths
        .first()
        .ok_or_else(|| invalid_input("scale corpus has no warm path"))?;
    let mut warm_samples_ms = Vec::with_capacity(options.warm_samples);
    for _ in 0..options.warm_samples {
        let started = Instant::now();
        let result = parse_path(warm_path)?;
        std::hint::black_box(result);
        warm_samples_ms.push(started.elapsed().as_secs_f64() * 1_000.0);
    }
    warm_samples_ms.sort_by(f64::total_cmp);

    let peak_rss_bytes = read_peak_rss_bytes()?;
    println!(
        "WORKSPACE_SCALE_BENCH full_p50_ms={:.6} full_p95_ms={:.6} warm_p50_ms={:.6} \
         result_count={result_count} memo_count={memo_count} node_count={node_count} \
         peak_rss_bytes={peak_rss_bytes} full_samples={} warm_samples={} memo_files={}",
        percentile(&full_samples_ms, 50),
        percentile(&full_samples_ms, 95),
        percentile(&warm_samples_ms, 50),
        options.full_samples,
        options.warm_samples,
        memo_paths.len(),
    );
    Ok(())
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BenchmarkOptions {
    corpus: PathBuf,
    full_samples: usize,
    warm_samples: usize,
}

impl BenchmarkOptions {
    fn parse(arguments: &[String]) -> Result<Self, Box<dyn Error>> {
        let mut corpus = PathBuf::from("build/corpora/scale-perf");
        let mut full_samples = 5_usize;
        let mut warm_samples = 21_usize;
        let mut index = 0;
        while index < arguments.len() {
            let flag = arguments[index].as_str();
            let value = arguments
                .get(index + 1)
                .ok_or_else(|| invalid_input(format!("missing value for {flag}")))?;
            match flag {
                "--corpus" => corpus = PathBuf::from(value),
                "--full-samples" => full_samples = parse_positive_sample(value, flag)?,
                "--warm-samples" => warm_samples = parse_positive_sample(value, flag)?,
                other => return Err(invalid_input(format!("unknown flag `{other}`"))),
            }
            index += 2;
        }
        Ok(Self {
            corpus,
            full_samples,
            warm_samples,
        })
    }
}

#[derive(Clone, Copy, Debug)]
struct ParseResult {
    memo_count: u64,
    node_count: u64,
}

fn parse_path(path: &Path) -> Result<ParseResult, Box<dyn Error>> {
    let bytes = fs::read(path)?;
    let source = SourceBytes::try_from_bytes(bytes.clone())?;
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .ok_or_else(|| invalid_input(format!("non-UTF-8 filename stem: {}", path.display())))?;
    let document = parse_workspace_document(&source, stem)?;
    if document.serialize_unedited() != bytes {
        return Err(
            io::Error::other(format!("unedited serialize drifted for {}", path.display())).into(),
        );
    }
    Ok(ParseResult {
        memo_count: u64::try_from(document.memos().len())?,
        node_count: u64::from(document.render_document().node_count()),
    })
}

fn collect_memo_paths(memo_dir: &Path) -> Result<Vec<PathBuf>, Box<dyn Error>> {
    let mut paths = Vec::new();
    for entry in fs::read_dir(memo_dir)? {
        let path = entry?.path();
        if path.extension().is_some_and(|extension| extension == "md") {
            paths.push(path);
        }
    }
    Ok(paths)
}

fn parse_positive_sample(value: &str, flag: &str) -> Result<usize, Box<dyn Error>> {
    let parsed = value.parse::<usize>()?;
    if parsed == 0 {
        return Err(invalid_input(format!("{flag} must be positive")));
    }
    Ok(parsed)
}

fn percentile(sorted: &[f64], percentile: usize) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    sorted[index]
}

fn read_peak_rss_bytes() -> Result<u64, io::Error> {
    let status = fs::read_to_string("/proc/self/status")?;
    for line in status.lines() {
        let Some(rest) = line.strip_prefix("VmHWM:") else {
            continue;
        };
        let token = rest
            .split_whitespace()
            .next()
            .ok_or_else(|| io::Error::other("VmHWM value is missing"))?;
        let kilobytes = token
            .parse::<u64>()
            .map_err(|error| io::Error::other(format!("VmHWM is not a number: {error}")))?;
        return Ok(kilobytes.saturating_mul(1024));
    }
    Err(io::Error::other(
        "failed to find VmHWM in /proc/self/status",
    ))
}

fn invalid_input(message: impl Into<String>) -> Box<dyn Error> {
    io::Error::new(io::ErrorKind::InvalidInput, message.into()).into()
}
