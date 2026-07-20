//! CLI for phase-0 corpus generation and evidence tools.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use lomo_feasibility::{
    CorpusMode, FeasibilityExitCode, GenerateError, GenerateRequest, generate_corpus,
    probe_markdown_file,
};

fn main() -> ExitCode {
    match run(&env::args().skip(1).collect::<Vec<_>>()) {
        Ok(()) => exit(FeasibilityExitCode::Success),
        Err(code) => exit(code),
    }
}

fn exit(code: FeasibilityExitCode) -> ExitCode {
    let value = match code {
        FeasibilityExitCode::Success => 0_u8,
        FeasibilityExitCode::ValidationFailed => 1,
        FeasibilityExitCode::ProbeFailed => 2,
        FeasibilityExitCode::EnvironmentIncomplete => 3,
        FeasibilityExitCode::ReportIncomplete => 4,
    };
    ExitCode::from(value)
}

fn run(arguments: &[String]) -> Result<(), FeasibilityExitCode> {
    let Some((command, rest)) = arguments.split_first() else {
        print_help();
        return Ok(());
    };
    match command.as_str() {
        "generate" => generate(rest),
        "scale-markdown-bench" => scale_markdown_bench(rest),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        unknown => {
            eprintln!("unknown command `{unknown}`");
            print_help();
            Err(FeasibilityExitCode::ValidationFailed)
        }
    }
}

/// Isolated process benchmark for 100k markdown corpus (peak RSS is this process only).
fn scale_markdown_bench(arguments: &[String]) -> Result<(), FeasibilityExitCode> {
    let mut corpus = PathBuf::from("build/corpora/scale-perf");
    let mut full_samples = 3_usize;
    let mut warm_samples = 21_usize;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--corpus" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                corpus = PathBuf::from(value);
                index += 2;
            }
            "--full-samples" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                full_samples = value
                    .parse()
                    .map_err(|_e| FeasibilityExitCode::ValidationFailed)?;
                index += 2;
            }
            "--warm-samples" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                warm_samples = value
                    .parse()
                    .map_err(|_e| FeasibilityExitCode::ValidationFailed)?;
                index += 2;
            }
            other => {
                eprintln!("unknown scale-markdown-bench flag `{other}`");
                return Err(FeasibilityExitCode::ValidationFailed);
            }
        }
    }

    let memo_dir = corpus.join("memo");
    let mut memo_paths = collect_memo_paths(&memo_dir)?;
    memo_paths.sort();
    if memo_paths.len() != 100_000 {
        eprintln!(
            "scale-markdown-bench: expected 100000 memos, got {}",
            memo_paths.len()
        );
        return Err(FeasibilityExitCode::ValidationFailed);
    }

    let mut full_ms = Vec::with_capacity(full_samples);
    let mut total_events: u64 = 0;
    let mut result_count: u64 = 0;
    for _ in 0..full_samples {
        let started = Instant::now();
        let mut events = 0_u64;
        let mut ok = 0_u64;
        for path in &memo_paths {
            let report = probe_markdown_file(path).map_err(|error| {
                eprintln!("scale-markdown-bench: {error}");
                FeasibilityExitCode::ProbeFailed
            })?;
            events = events.saturating_add(report.event_count as u64);
            ok = ok.saturating_add(1);
        }
        full_ms.push(started.elapsed().as_secs_f64() * 1000.0);
        total_events = events;
        result_count = ok;
    }
    full_ms.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let full_p50 = percentile(&full_ms, 0.50);
    let full_p95 = percentile(&full_ms, 0.95);

    // Single-memo warm path (first file, after full corpus has warmed caches).
    let warm_path = &memo_paths[0];
    let mut warm_ms = Vec::with_capacity(warm_samples);
    for _ in 0..warm_samples {
        let started = Instant::now();
        let report = probe_markdown_file(warm_path).map_err(|error| {
            eprintln!("scale-markdown-bench warm: {error}");
            FeasibilityExitCode::ProbeFailed
        })?;
        std::hint::black_box(report.event_count);
        warm_ms.push(started.elapsed().as_secs_f64() * 1000.0);
    }
    warm_ms.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let warm_p50 = percentile(&warm_ms, 0.50);

    let peak_rss = read_peak_rss_bytes().ok_or_else(|| {
        eprintln!("scale-markdown-bench: failed to read /proc/self/status VmHWM");
        FeasibilityExitCode::EnvironmentIncomplete
    })?;

    // Machine-readable single line for xtask to parse.
    println!(
        "SCALE_MARKDOWN_BENCH full_p50_ms={full_p50:.6} full_p95_ms={full_p95:.6} \
         warm_p50_ms={warm_p50:.6} result_count={result_count} total_events={total_events} \
         peak_rss_bytes={peak_rss} full_samples={full_samples} warm_samples={warm_samples} \
         memo_files={}",
        memo_paths.len()
    );
    Ok(())
}

fn collect_memo_paths(memo_dir: &Path) -> Result<Vec<PathBuf>, FeasibilityExitCode> {
    let mut paths = Vec::new();
    let entries = fs::read_dir(memo_dir).map_err(|error| {
        eprintln!("scale-markdown-bench: read {}: {error}", memo_dir.display());
        FeasibilityExitCode::EnvironmentIncomplete
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| {
            eprintln!("scale-markdown-bench: dir entry: {error}");
            FeasibilityExitCode::ProbeFailed
        })?;
        let path = entry.path();
        if path.extension().is_some_and(|ext| ext == "md") {
            paths.push(path);
        }
    }
    Ok(paths)
}

fn percentile(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let last = sorted.len().saturating_sub(1);
    #[allow(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        clippy::cast_precision_loss,
        reason = "percentile index is bounded to sorted.len()-1 for host metrics"
    )]
    let idx = {
        let scaled = (last as f64) * q;
        scaled.round() as usize
    };
    sorted[idx.min(last)]
}

fn read_peak_rss_bytes() -> Option<u64> {
    let Ok(status) = fs::read_to_string("/proc/self/status") else {
        return None;
    };
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("VmHWM:") {
            let token = rest.split_whitespace().next()?;
            let Ok(kb) = token.parse::<u64>() else {
                return None;
            };
            return Some(kb.saturating_mul(1024));
        }
    }
    None
}

fn generate(arguments: &[String]) -> Result<(), FeasibilityExitCode> {
    let mut seed = 1_u64;
    let mut mode = CorpusMode::Quick;
    let mut output = PathBuf::from("build/corpora/quick");
    let mut fixture_root = PathBuf::from("fixtures");
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--seed" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                seed = value
                    .parse()
                    .map_err(|_parse| FeasibilityExitCode::ValidationFailed)?;
                index += 2;
            }
            "--mode" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                mode = CorpusMode::parse(value).map_err(|error| map_generate_error(&error))?;
                index += 2;
            }
            "--out" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                output = PathBuf::from(value);
                index += 2;
            }
            "--fixtures" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                fixture_root = PathBuf::from(value);
                index += 2;
            }
            other => {
                eprintln!("unknown generate flag `{other}`");
                return Err(FeasibilityExitCode::ValidationFailed);
            }
        }
    }

    let request = GenerateRequest {
        seed,
        mode,
        output_dir: output,
        fixture_root,
    };
    let manifest = generate_corpus(&request).map_err(|error| map_generate_error(&error))?;
    let digest = manifest
        .canonical_digest()
        .map_err(|_report| FeasibilityExitCode::ReportIncomplete)?;
    eprintln!(
        "lomo-feasibility: wrote {} (seed={}, mode={}, digest={digest})",
        request.output_dir.join("corpus-manifest.v1.json").display(),
        request.seed,
        request.mode.as_str()
    );
    Ok(())
}

fn map_generate_error(error: &GenerateError) -> FeasibilityExitCode {
    eprintln!("lomo-feasibility: {error}");
    match error {
        GenerateError::UnknownMode { .. }
        | GenerateError::PathEscapesRoot { .. }
        | GenerateError::AbsolutePath { .. }
        | GenerateError::DuplicateIdentity { .. }
        | GenerateError::Report(_) => FeasibilityExitCode::ValidationFailed,
        GenerateError::MissingFixtureRoot { .. } => FeasibilityExitCode::EnvironmentIncomplete,
        GenerateError::Io { .. } => FeasibilityExitCode::ProbeFailed,
    }
}

fn print_help() {
    eprintln!(
        "lomo-feasibility\n\nCommands:\n  \
         generate --mode quick|scale|capacity --seed N --out DIR [--fixtures DIR]\n  \
         scale-markdown-bench --corpus DIR [--full-samples N] [--warm-samples N]\n  \
         help"
    );
}
