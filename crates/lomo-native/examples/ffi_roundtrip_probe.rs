//! Behavior Contract
//!
//! Capability: measure one host-visible FFI facade round trip — `LomoEngine::query_memos`
//! decodes wire `StoreMemoQuery`/cursor into core types, queries the committed projection, then
//! encodes `StoreMemoPage` wire DTOs back out — in an isolated process over a deterministic
//! seeded workspace. This is the conversion+query cost Android's JNI layer pays per list call;
//! the JVM/Kotlin side is out of scope for the host probe.
//!
//! Scenarios:
//! - Given a seeded workspace opened through `LomoEngine::open` + `open_workspace_session` with a
//!   POSIX `PlatformBatchHost`, when `query_memos` is called with a default wire query, then each
//!   call is an `ffi_roundtrip_ms` sample covering FFI decode → core query → FFI encode.
//! - Given a `next_cursor` from a page, when a follow-up `query_memos` decodes that cursor, then
//!   it also counts as a round-trip sample.
//! - Given missing args, an unexpected memo count, an empty first page, or a `query_count`
//!   mismatch, when the probe runs, then it fails closed without emitting a success line.
//!
//! Observable outcomes: one `FFI_ROUNDTRIP_BENCH` line with p50/p95 for the round trip, memo
//! count, sample count and isolated peak RSS.
//! TDD proof: RED before implementation was `error: no example target named ffi_roundtrip_probe in
//! lomo-native package`.
//! Excludes: JNI marshal cost inside ART (Android macrobenchmark covers the real device path),
//! session-open cost, and sync/media FFI surfaces.

use std::env;
use std::error::Error;
use std::fs;
use std::io::{self, Write};
use std::process::ExitCode;
use std::time::{Duration, Instant};

use lomo_core::{CapabilityToken, PlatformActionExecutor};
use lomo_native::{
    EngineConfig, EngineError, LomoEngine, PlatformActionBatch, PlatformBatchHost,
    PlatformBatchResult, StoreMemoPage, StoreMemoQuery, StorePageCursor, WorkspaceDescriptor,
    batch_from_ffi, result_to_ffi,
};
use lomo_platform_fs::FsPlatformActionExecutor;

const DOCUMENT_COUNT: usize = 240;
const BLOCKS_PER_DOCUMENT: usize = 2;
const EXPECTED_MEMO_COUNT: u64 = (DOCUMENT_COUNT * BLOCKS_PER_DOCUMENT) as u64;
const PAGE_SIZE: u32 = 50;
const WARMUP: usize = 3;
const SAMPLES: usize = 21;

struct PosixBatchHost {
    executor: FsPlatformActionExecutor,
}

impl PlatformBatchHost for PosixBatchHost {
    fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError> {
        let core_batch = batch_from_ffi(batch)?;
        let result = self
            .executor
            .execute(&core_batch)
            .map_err(EngineError::from)?;
        Ok(result_to_ffi(&result))
    }
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            match writeln!(io::stderr(), "ffi-roundtrip-probe: {error}") {
                Ok(()) => {}
                Err(_write_error) => {}
            }
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), Box<dyn Error>> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let emit_line = parse_emit_line(&arguments)?;

    let layout = TempLayout::new()?;
    seed_workspace(&layout.workspace)?;

    let engine = LomoEngine::open(EngineConfig {
        control_root: layout.control.display().to_string(),
        exchange_root: layout.exchange.display().to_string(),
        workspace: Some(WorkspaceDescriptor::Direct {
            root_path: layout.workspace.display().to_string(),
            capability_token: "notes-root".to_owned(),
        }),
        bootstrap_deadline_millis: 30_000,
    })?;

    let executor = FsPlatformActionExecutor::new(&layout.exchange)?;
    executor.bind_root(CapabilityToken::parse("notes-root")?, &layout.workspace)?;
    engine.open_workspace_session(
        Box::new(PosixBatchHost { executor }),
        "UTC".to_owned(),
        "yyyy_MM_dd".to_owned(),
        layout.media_stage.display().to_string(),
    )?;

    let query = StoreMemoQuery::default();
    let memo_count = engine.query_count(query.clone())?;
    if memo_count != EXPECTED_MEMO_COUNT {
        return Err(invalid_input(format!(
            "expected {EXPECTED_MEMO_COUNT} projected memos after seed open, got {memo_count}"
        )));
    }

    for _ in 0..WARMUP {
        walk_roundtrips(&engine, &query, |_| {})?;
    }

    let mut samples = Vec::with_capacity(SAMPLES);
    while samples.len() < SAMPLES {
        walk_roundtrips(&engine, &query, |duration| {
            if samples.len() < SAMPLES {
                samples.push(millis(duration));
            }
        })?;
    }

    let peak_rss_bytes = peak_rss()?;
    samples.sort_by(f64::total_cmp);
    let p50 = percentile(&samples, 50);
    let p95 = percentile(&samples, 95);

    if emit_line {
        writeln!(
            io::stdout(),
            "FFI_ROUNDTRIP_BENCH ffi_roundtrip_p50_ms={p50:.6} ffi_roundtrip_p95_ms={p95:.6} \
             memo_count={memo_count} doc_count={DOCUMENT_COUNT} samples={SAMPLES} \
             peak_rss_bytes={peak_rss_bytes}"
        )?;
    }
    Ok(())
}

fn parse_emit_line(arguments: &[String]) -> Result<bool, Box<dyn Error>> {
    let mut emit_line = true;
    for argument in arguments {
        match argument.as_str() {
            "--quiet" => emit_line = false,
            unknown => {
                return Err(invalid_input(format!(
                    "unknown ffi-roundtrip-probe argument: {unknown}"
                )));
            }
        }
    }
    Ok(emit_line)
}

/// One timed `query_memos` call per page across a first-page-to-exhaustion walk, including
/// cursor decode on continuation calls: each call is one complete FFI wire round trip
/// (`StoreMemoQuery`/`StorePageCursor` in, `StoreMemoPage` out). The first page is also
/// timed — Android pays the same per-call conversion at refresh.
fn walk_roundtrips(
    engine: &LomoEngine,
    query: &StoreMemoQuery,
    mut observe: impl FnMut(Duration),
) -> Result<(), Box<dyn Error>> {
    let mut cursor: Option<StorePageCursor> = None;
    loop {
        let started = Instant::now();
        let page: StoreMemoPage =
            engine.query_memos(query.clone(), cursor, PAGE_SIZE, None, false)?;
        observe(started.elapsed());
        if page.items.is_empty() && page.next_cursor.is_none() {
            break;
        }
        match page.next_cursor {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }
    Ok(())
}

fn seed_workspace(workspace: &std::path::Path) -> Result<(), Box<dyn Error>> {
    use std::fmt::Write as _;
    for day in 0..DOCUMENT_COUNT {
        let ordinal = day % 28 + 1;
        let month = day / 28 + 1;
        let mut text = String::new();
        for block in 0..BLOCKS_PER_DOCUMENT {
            write!(
                text,
                "- {:02}:{:02}\nffi memo body day={day} block={block} #bench\n\n",
                8 + block,
                15 + block * 7,
            )
            .map_err(|error| invalid_input(format!("memo seed text unwritable: {error}")))?;
        }
        fs::write(
            workspace.join(format!("2025_{month:02}_{ordinal:02}.md")),
            text,
        )?;
    }
    Ok(())
}

struct TempLayout {
    _root: tempfile::TempDir,
    control: std::path::PathBuf,
    exchange: std::path::PathBuf,
    workspace: std::path::PathBuf,
    media_stage: std::path::PathBuf,
}

impl TempLayout {
    fn new() -> Result<Self, Box<dyn Error>> {
        let root = tempfile::Builder::new()
            .prefix("ffi-roundtrip-probe")
            .tempdir()?;
        let layout = Self {
            control: root.path().join("control"),
            exchange: root.path().join("exchange"),
            workspace: root.path().join("workspace"),
            media_stage: root.path().join("media-stage"),
            _root: root,
        };
        for directory in [
            &layout.control,
            &layout.exchange,
            &layout.workspace,
            &layout.media_stage,
        ] {
            fs::create_dir_all(directory)?;
        }
        Ok(layout)
    }
}

fn millis(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn percentile(samples: &[f64], percentile: usize) -> f64 {
    if samples.is_empty() {
        return 0.0;
    }
    let index = ((samples.len() - 1) * percentile) / 100;
    *samples.get(index).unwrap_or(&0.0)
}

/// Reads this process's `VmHWM` so the metric records isolated peak RSS rather than the
/// caller's cumulative high-water mark.
fn peak_rss() -> Result<u64, Box<dyn Error>> {
    let status = fs::read_to_string("/proc/self/status")?;
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("VmHWM:") {
            let kib = rest
                .trim()
                .strip_suffix("kB")
                .map_or_else(|| rest.trim(), str::trim);
            return Ok(kib.trim().parse::<u64>()? * 1024);
        }
    }
    Err(invalid_input("/proc/self/status carries no VmHWM line"))
}

fn invalid_input(message: impl Into<String>) -> Box<dyn Error> {
    io::Error::new(io::ErrorKind::InvalidInput, message.into()).into()
}
