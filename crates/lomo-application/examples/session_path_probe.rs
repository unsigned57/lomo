//! Behavior Contract
//!
//! Capability: measure the production `lomo-application` session hot paths in an isolated process
//! over a deterministic seeded workspace — digest-matched idle reconcile, one verified `io.read`
//! platform action through the same batch envelope `workspace_io` issues, and the cursor append
//! fetch Android Paging pays on every list continuation.
//!
//! Scenarios:
//! - Given a seeded workspace with the exact expected dated-document/memo shape, when the session
//!   materializes the projection once, then subsequent `rebuild_projection` passes take the
//!   digest-matched idle path and are timed as `reconcile_idle_ms` samples.
//! - Given a bound POSIX executor, when a `Read` batch is issued with the same action/batch/deadline
//!   wire envelope `WorkspaceIo::read` constructs (nonce action/job/batch ids, 60s deadline,
//!   `validate_against` evidence check), then each verified round trip is an `io_read_ms` sample.
//! - Given the materialized projection, when a first page warms the reader, then each
//!   `query_memos_page` continuation fetch through the returned cursor is a `paging_append_ms`
//!   sample.
//! - Given missing arguments, a wrong seeded memo count, an unreadable memo path, an empty append
//!   page, or unavailable process `VmHWM`, when the probe runs, then it fails closed without
//!   emitting a success line.
//!
//! Observable outcomes: one `SESSION_PATH_BENCH` line with p50/p95 for `reconcile_idle`, `io_read`
//! and `paging_append`, plus memo/doc counts, sample counts and isolated peak RSS.
//! TDD proof: RED before implementation was `error: no example target named session_path_probe in
//! lomo-application package`.
//! Excludes: JNI marshalling (the facade round trip lives in `lomo-native`), UI frame timing,
//! corpus generation, and APK size.

use std::env;
use std::error::Error;
use std::fs;
use std::io::{self, Write};
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use lomo_application::{
    MemoFilters, MemoQuery, MemoSort, WorkspaceSession, WorkspaceSessionConfig,
};
use lomo_core::{
    ActionId, ActionOutcome, BatchId, CapabilityToken, ExpectedFingerprint, JobId, PageSize,
    PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput,
    RelativeWorkspacePath,
};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_workspace::{WorkspaceGenerationId, WorkspaceRootId};

const DOCUMENT_COUNT: usize = 240;
const BLOCKS_PER_DOCUMENT: usize = 2;
const EXPECTED_MEMO_COUNT: usize = DOCUMENT_COUNT * BLOCKS_PER_DOCUMENT;
const PAGE_SIZE: u32 = 50;
const WARMUP: usize = 3;
const SAMPLES: usize = 21;
/// Same 60-second wire deadline `workspace_io::deadline` issues for single-action batches.
const ACTION_DEADLINE_MS: u64 = 60_000;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            match writeln!(io::stderr(), "session-path-probe: {error}") {
                Ok(()) => {}
                Err(_write_error) => {}
            }
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), Box<dyn Error>> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let options = ProbeOptions::parse(&arguments)?;

    let workspace = tempfile_dir("session-path-probe-workspace")?;
    seed_workspace(workspace.path())?;

    let exchange = tempfile_dir("session-path-probe-exchange")?;
    let posix = Arc::new(FsPlatformActionExecutor::new(exchange.path())?);
    let capability = CapabilityToken::parse("bench-root")?;
    posix.bind_root(capability.clone(), workspace.path())?;
    let executor: Arc<dyn PlatformActionExecutor> = posix;

    let private = tempfile_dir("session-path-probe-private")?;
    let config = WorkspaceSessionConfig {
        capability: capability.clone(),
        root_id: WorkspaceRootId::Notes,
        workspace_generation: WorkspaceGenerationId::mint()?,
        time_zone: "UTC".to_owned(),
        date_format: lomo_application::calendar::DateFormat::default(),
        state_dir: private.path().join("state"),
        cache_dir: private.path().join("cache"),
        runtime_dir: private.path().join("runtime"),
        exchange_dir: exchange.path().to_path_buf(),
        media_stage_root: private.path().join("media-stage"),
    };
    let session = WorkspaceSession::open(config, Arc::clone(&executor))?;

    // First reconcile materializes the projection; every later pass must take the
    // digest-matched idle path — that is the tax `reconcile_idle_ms` prices.
    std::hint::black_box(session.rebuild_projection()?);
    let memo_count = session.query_count(&all_memos_query())?;
    if usize::try_from(memo_count)? != EXPECTED_MEMO_COUNT {
        return Err(invalid_input(format!(
            "expected {EXPECTED_MEMO_COUNT} projected memos after seed rebuild, got {memo_count}"
        )));
    }

    let read_path = RelativeWorkspacePath::parse("2025_02_10.md")?;

    for _ in 0..WARMUP {
        std::hint::black_box(session.rebuild_projection()?);
        std::hint::black_box(measure_io_read(executor.as_ref(), &capability, &read_path)?.elapsed);
        walk_appends(&session, |_| {})?;
    }

    let mut reconcile_samples = Vec::with_capacity(SAMPLES);
    for _ in 0..SAMPLES {
        let started = Instant::now();
        std::hint::black_box(session.rebuild_projection()?);
        reconcile_samples.push(millis(started.elapsed()));
    }

    let mut read_samples = Vec::with_capacity(SAMPLES);
    for _ in 0..SAMPLES {
        let measured = measure_io_read(executor.as_ref(), &capability, &read_path)?;
        std::hint::black_box(measured.bytes);
        read_samples.push(millis(measured.elapsed));
    }

    let mut append_samples = Vec::with_capacity(SAMPLES);
    while append_samples.len() < SAMPLES {
        walk_appends(&session, |duration| {
            if append_samples.len() < SAMPLES {
                append_samples.push(millis(duration));
            }
        })?;
    }

    let peak_rss_bytes = peak_rss()?;
    let (reconcile_idle_p50_ms, reconcile_idle_p95_ms) = percentiles(&mut reconcile_samples);
    let (io_read_p50_ms, io_read_p95_ms) = percentiles(&mut read_samples);
    let (paging_append_p50_ms, paging_append_p95_ms) = percentiles(&mut append_samples);

    if options.emit_line {
        writeln!(
            io::stdout(),
            "SESSION_PATH_BENCH reconcile_idle_p50_ms={reconcile_idle_p50_ms:.6} \
             reconcile_idle_p95_ms={reconcile_idle_p95_ms:.6} io_read_p50_ms={io_read_p50_ms:.6} \
             io_read_p95_ms={io_read_p95_ms:.6} paging_append_p50_ms={paging_append_p50_ms:.6} \
             paging_append_p95_ms={paging_append_p95_ms:.6} memo_count={memo_count} \
             doc_count={DOCUMENT_COUNT} samples={SAMPLES} peak_rss_bytes={peak_rss_bytes}"
        )?;
    }
    Ok(())
}

struct ProbeOptions {
    emit_line: bool,
}

impl ProbeOptions {
    fn parse(arguments: &[String]) -> Result<Self, Box<dyn Error>> {
        let mut emit_line = true;
        for argument in arguments {
            match argument.as_str() {
                "--quiet" => emit_line = false,
                unknown => {
                    return Err(invalid_input(format!(
                        "unknown session-path-probe argument: {unknown}"
                    )));
                }
            }
        }
        Ok(Self { emit_line })
    }
}

fn all_memos_query() -> MemoQuery {
    MemoQuery {
        search_text: None,
        filters: MemoFilters::default(),
        sort: MemoSort::default(),
    }
}

struct IoReadMeasurement {
    bytes: Vec<u8>,
    elapsed: Duration,
}

/// One verified `io.read` round trip carrying the exact wire envelope `WorkspaceIo::read`
/// builds: per-call nonce action/job/batch ids, a 60s deadline, one `Read` action and a
/// `validate_against` evidence check. `workspace_io` stays a private `lomo_application`
/// module — widening it for the probe would open its write lane to every host — so the
/// envelope is re-issued at the same executor edge the wrapper calls.
fn measure_io_read(
    executor: &dyn PlatformActionExecutor,
    capability: &CapabilityToken,
    path: &RelativeWorkspacePath,
) -> Result<IoReadMeasurement, Box<dyn Error>> {
    let started = Instant::now();
    let nonce = lomo_application::csprng::generate_hex_token(16)?;
    let action = PlatformAction::read(
        ActionId::parse(&format!("read-{nonce}"))?,
        capability.clone(),
        path.clone(),
        ExpectedFingerprint::absent(),
    );
    let batch = PlatformActionBatch::new(
        JobId::parse(&format!("job-{nonce}"))?,
        BatchId::parse(&format!("batch-{nonce}"))?,
        1,
        epoch_millis()?
            .checked_add(ACTION_DEADLINE_MS)
            .ok_or_else(|| invalid_input("platform deadline overflow"))?,
        vec![action],
    )?;
    let result = executor.execute(&batch)?;
    result.validate_against(&batch)?;
    let first = result
        .action_results()
        .first()
        .ok_or_else(|| invalid_input("a read batch requires exactly one verified result"))?;
    let output = match first.outcome() {
        ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => output.clone(),
        ActionOutcome::Failed(error) => {
            return Err(invalid_input(format!("read action failed: {error}")));
        }
    };
    let PlatformActionOutput::Read { bytes, .. } = output else {
        return Err(invalid_input("read action returned a non-read output"));
    };
    Ok(IoReadMeasurement {
        bytes,
        elapsed: started.elapsed(),
    })
}

/// One append fetch per continuation cursor inside a first-page-to-exhaustion walk;
/// `observe` receives each append duration. The first page is fetched but never timed —
/// Android pays it at refresh, while `paging_append_ms` prices only continuation fetches.
fn walk_appends(
    session: &WorkspaceSession,
    mut observe: impl FnMut(Duration),
) -> Result<(), Box<dyn Error>> {
    let query = all_memos_query();
    let page_size = PageSize::new(PAGE_SIZE)?;
    let mut page = session.query_memos_page(&query, None, None, page_size)?;
    if page.items.is_empty() {
        return Err(invalid_input(
            "first memo page must not be empty after seeding",
        ));
    }
    while let Some(cursor) = page.next_cursor {
        let started = Instant::now();
        page = session.query_memos_page(&query, None, Some(&cursor), page_size)?;
        observe(started.elapsed());
        std::hint::black_box(page.items.len());
    }
    Ok(())
}

fn seed_workspace(workspace: &Path) -> Result<(), Box<dyn Error>> {
    use std::fmt::Write as _;
    for day in 0..DOCUMENT_COUNT {
        let ordinal = day % 28 + 1;
        let month = day / 28 + 1;
        let mut text = String::new();
        for block in 0..BLOCKS_PER_DOCUMENT {
            write!(
                text,
                "- {:02}:{:02}\nbench memo body day={day} block={block} #bench\n\n",
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

fn tempfile_dir(prefix: &str) -> Result<tempfile::TempDir, Box<dyn Error>> {
    Ok(tempfile::Builder::new().prefix(prefix).tempdir()?)
}

fn epoch_millis() -> Result<u64, Box<dyn Error>> {
    let duration = SystemTime::now().duration_since(UNIX_EPOCH)?;
    Ok(u64::try_from(duration.as_millis())?)
}

fn millis(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn percentiles(samples: &mut [f64]) -> (f64, f64) {
    samples.sort_by(f64::total_cmp);
    (percentile(samples, 50), percentile(samples, 95))
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
