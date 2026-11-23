//! Behavior Contract:
//! Capability: measure first index, existing-index reopen (the Rust part of switching),
//! full rebuild, reads, creates, updates and dirty/idle refresh through `WorkspaceSession`.
//! Scenarios: Given two independent seeded roots, when repeated samples run, then each fresh
//! index has the requested memo count, switches reopen the other root, and every dirty refresh adds a
//! distinct memo. Returned bodies and counts must match before recording success.
//! Usage: `workspace_path_probe [documents=120] [memos_per_document=2] [samples=5] [attachment_subdirectories=0]`.
//! Observable outcomes: JSON samples in milliseconds and platform action counts per path.
//! TDD proof: diagnostic only; asserts real session outcomes, no timing pass threshold.
//! Excludes: SAF/JNI, UI readiness, physical-device latency and durability fault injection.

use std::{
    collections::BTreeMap,
    error::Error,
    fs, io,
    io::Write as _,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Instant,
};

use lomo_application::{
    CreateMemoRequest, MemoFilters, MemoQuery, MemoSort, UpdateMemoRequest, WorkspaceSession,
    WorkspaceSessionConfig,
};
use lomo_core::{
    CapabilityToken, OperationId, PlatformAction, PlatformActionBatch, PlatformActionExecutor,
    PlatformBatchResult, RelativeWorkspacePath,
};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_workspace::{WorkspaceGenerationId, WorkspaceRootId};
use serde::Serialize;

type Result<T> = std::result::Result<T, Box<dyn Error>>;

#[derive(Default, Serialize)]
struct Actions {
    batches: usize,
    reads: usize,
    writes: usize,
    lists: usize,
}

struct CountingExecutor {
    inner: FsPlatformActionExecutor,
    counts: Mutex<Actions>,
}

impl PlatformActionExecutor for CountingExecutor {
    fn execute(
        &self,
        batch: &PlatformActionBatch,
    ) -> std::result::Result<PlatformBatchResult, lomo_core::LomoError> {
        let mut counts = self
            .counts
            .lock()
            .map_err(|error| lomo_application::error::storage("probe_lock", error.to_string()))?;
        counts.batches += 1;
        for action in batch.actions() {
            match action {
                PlatformAction::Read { .. } | PlatformAction::ReadToExchange { .. } => {
                    counts.reads += 1;
                }
                PlatformAction::WriteFromExchange { .. } | PlatformAction::ArtifactWrite { .. } => {
                    counts.writes += 1;
                }
                PlatformAction::ListChildren { .. } => counts.lists += 1,
                PlatformAction::Stat { .. }
                | PlatformAction::Inspect { .. }
                | PlatformAction::EnsureDirectory { .. }
                | PlatformAction::Move { .. }
                | PlatformAction::Delete { .. } => {}
            }
        }
        drop(counts);
        self.inner.execute(batch)
    }
}

#[derive(Serialize)]
struct Sample {
    ms: f64,
    actions: Actions,
}

fn measure<T>(
    executor: &CountingExecutor,
    samples: &mut BTreeMap<&str, Vec<Sample>>,
    name: &'static str,
    work: impl FnOnce() -> Result<T>,
) -> Result<T> {
    *executor
        .counts
        .lock()
        .map_err(|error| io::Error::other(error.to_string()))? = Actions::default();
    let start = Instant::now();
    let value = work()?;
    let ms = start.elapsed().as_secs_f64() * 1000.0;
    let actions = std::mem::take(
        &mut *executor
            .counts
            .lock()
            .map_err(|error| io::Error::other(error.to_string()))?,
    );
    samples
        .entry(name)
        .or_default()
        .push(Sample { ms, actions });
    Ok(value)
}

fn query() -> MemoQuery {
    MemoQuery {
        search_text: None,
        filters: MemoFilters::default(),
        sort: MemoSort::default(),
    }
}

fn open(
    config: &WorkspaceSessionConfig,
    executor: &Arc<CountingExecutor>,
) -> Result<WorkspaceSession> {
    let port: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(executor);
    Ok(WorkspaceSession::open(config.clone(), port)?)
}

fn seed_root(
    directory: &Path,
    exchange: &Path,
    executor: &CountingExecutor,
    root: &str,
    shape: &CorpusShape,
) -> Result<(PathBuf, WorkspaceSessionConfig)> {
    let workspace = directory.join(root);
    fs::create_dir_all(&workspace)?;
    for day in 0..shape.documents {
        let date = jiff::civil::date(2000, 1, 1)
            .checked_add(jiff::Span::new().days(i64::try_from(day)?))?;
        let mut body = String::new();
        for memo in 0..shape.memos_per_document {
            use std::fmt::Write as _;
            writeln!(body, "- 09:00\n{root} day {day} memo {memo} #bench\n")?;
        }
        fs::write(
            workspace.join(format!("{}.md", date.strftime("%Y_%m_%d"))),
            body,
        )?;
    }
    for (index, lane) in lomo_workspace::ATTACHMENT_LOOKUP_DIRS
        .iter()
        .copied()
        .cycle()
        .take(shape.attachment_subdirectories)
        .enumerate()
    {
        let folder = workspace.join(lane).join(index.to_string());
        fs::create_dir_all(&folder)?;
        fs::write(
            folder.join("attachment.bin"),
            b"external attachment fixture",
        )?;
    }
    let capability = CapabilityToken::parse(root)?;
    executor.inner.bind_root(capability.clone(), &workspace)?;
    let private = directory.join(format!("private-{root}"));
    let config = WorkspaceSessionConfig {
        capability,
        root_id: WorkspaceRootId::Notes,
        workspace_generation: WorkspaceGenerationId::mint()?,
        time_zone: "UTC".into(),
        date_format: lomo_application::calendar::DateFormat::default(),
        state_dir: private.join("state"),
        cache_dir: private.join("cache"),
        runtime_dir: private.join("runtime"),
        exchange_dir: exchange.to_path_buf(),
        media_stage_root: private.join("media-stage"),
    };
    Ok((workspace, config))
}

struct CorpusShape {
    documents: usize,
    memos_per_document: usize,
    samples: usize,
    attachment_subdirectories: usize,
}

impl CorpusShape {
    fn memo_count(&self) -> Result<u64> {
        Ok(u64::try_from(
            self.documents
                .checked_mul(self.memos_per_document)
                .ok_or_else(|| io::Error::other("corpus size overflow"))?,
        )?)
    }
}

fn corpus_shape() -> Result<CorpusShape> {
    let mut args = std::env::args().skip(1);
    let mut size = |default| -> Result<usize> {
        let value = args
            .next()
            .map_or(Ok(default), |raw| raw.parse::<usize>())?;
        if value == 0 {
            return Err(io::Error::other("corpus dimensions and samples must be positive").into());
        }
        Ok(value)
    };
    let shape = CorpusShape {
        documents: size(120)?,
        memos_per_document: size(2)?,
        samples: size(5)?,
        attachment_subdirectories: args.next().map_or(Ok(0), |raw| raw.parse::<usize>())?,
    };
    if args.next().is_some() {
        return Err(io::Error::other(
            "expected [documents] [memos_per_document] [samples] [attachment_subdirectories]",
        )
        .into());
    }
    Ok(shape)
}

fn run() -> Result<()> {
    let shape = corpus_shape()?;
    let expected_memos = shape.memo_count()?;
    let mut samples = BTreeMap::new();
    for sample in 0..shape.samples {
        let dirs = tempfile::tempdir()?;
        let exchange = dirs.path().join("exchange");
        fs::create_dir_all(&exchange)?;
        let executor = Arc::new(CountingExecutor {
            inner: FsPlatformActionExecutor::new(&exchange)?,
            counts: Mutex::new(Actions::default()),
        });
        let mut roots = Vec::new();
        for root in ["a", "b"] {
            let (workspace, config) = seed_root(dirs.path(), &exchange, &executor, root, &shape)?;
            let session = measure(&executor, &mut samples, "first_index", || {
                open(&config, &executor)
            })?;
            if session.query_count(&query())? != expected_memos {
                return Err(io::Error::other("seed count mismatch").into());
            }
            drop(session);
            roots.push((workspace, config));
        }
        for (workspace, config) in &roots {
            let session = measure(&executor, &mut samples, "switch_existing_index", || {
                open(config, &executor)
            })?;
            let page = measure(&executor, &mut samples, "first_page", || {
                Ok(session.list_memos(&query())?)
            })?;
            let first = page
                .items
                .first()
                .ok_or_else(|| io::Error::other("empty switched workspace"))?;
            if !first.body_preview.starts_with(config.capability.as_str()) {
                return Err(io::Error::other("switched workspace marker mismatch").into());
            }
            drop(session);
            measure(&executor, &mut samples, "full_rebuild", || {
                let port: Arc<dyn PlatformActionExecutor> =
                    Arc::<CountingExecutor>::clone(&executor);
                Ok(lomo_application::rebuild::rebuild_projection(
                    config, &port,
                )?)
            })?;
            // Reopen after the diagnostic rebuilt the disposable projection.
            let session = open(config, &executor)?;
            let created = measure(&executor, &mut samples, "create", || {
                Ok(session.create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse(&format!("create-{sample}"))?,
                    relative_path: Some(RelativeWorkspacePath::parse("2025_01_01.md")?),
                    time_token: Some("10:00".into()),
                    content: "created #bench".into(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                    date_format: None,
                    timestamp_format: None,
                })?)
            })?;
            let view = measure(&executor, &mut samples, "read", || {
                Ok(session
                    .get_memo(&created.memo_id)?
                    .ok_or_else(|| io::Error::other("missing created memo"))?)
            })?;
            if view.body != "created #bench" {
                return Err(io::Error::other("read body mismatch").into());
            }
            measure(&executor, &mut samples, "update", || {
                Ok(session.update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse(&format!("update-{sample}"))?,
                    memo_id: created.memo_id.clone(),
                    content: "updated #bench".into(),
                    expected_document_fingerprint: created.commit_result.file_fingerprint,
                    pending_promotes: Vec::new(),
                })?)
            })?;
            fs::write(
                workspace.join("8888_12_31.md"),
                "- 11:00\nexternal memo\n\n",
            )?;
            measure(&executor, &mut samples, "dirty_refresh", || {
                Ok(session.rebuild_projection()?)
            })?;
            if session.query_count(&query())? != expected_memos + 2 {
                return Err(io::Error::other("dirty count mismatch").into());
            }
            measure(&executor, &mut samples, "idle_refresh", || {
                Ok(session.rebuild_projection()?)
            })?;
        }
    }
    writeln!(
        io::stdout().lock(),
        "{}",
        serde_json::to_string_pretty(&samples)?
    )?;
    Ok(())
}

fn main() -> Result<()> {
    run()
}
