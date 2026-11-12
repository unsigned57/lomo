//! Diagnostic: compare isolated saves with consecutive edits to one monthly document.
//! Run with `cargo run -p lomo-application --example edit_burst_probe --release --locked`.
//! Reports real acknowledgement/settle latency and applied document writes; no timing assertion.

use lomo_application::{
    CreateMemoRequest, UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
};
use lomo_core::{
    ActionOutcome, CapabilityToken, LomoError, OperationId, PlatformAction, PlatformActionBatch,
    PlatformActionExecutor, PlatformBatchResult, RelativeWorkspacePath,
};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_workspace::{WorkspaceGenerationId, WorkspaceRootId};
use serde::Serialize;
use std::{
    error::Error,
    fmt::Write as _,
    fs, io,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

const DOCUMENT: &str = "2026_11_04.md";
struct CountingExecutor {
    inner: FsPlatformActionExecutor,
    writes: AtomicUsize,
}
impl PlatformActionExecutor for CountingExecutor {
    fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
        let result = self.inner.execute(batch)?;
        for (action, result) in batch.actions().iter().zip(result.action_results()) {
            if matches!(action, PlatformAction::WriteFromExchange {path,..} if path.as_str()==DOCUMENT)
                && matches!(result.outcome(), ActionOutcome::Applied(_))
            {
                self.writes.fetch_add(1, Ordering::SeqCst);
            }
        }
        Ok(result)
    }
}
#[derive(Serialize)]
struct Sample {
    mode: &'static str,
    edits: usize,
    acknowledgement_ms: f64,
    settled_ms: f64,
    document_writes: usize,
}
fn sample(isolated: bool, count: usize) -> Result<Sample, Box<dyn Error>> {
    let temp = tempfile::tempdir()?;
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace)?;
    let padding = "monthly note body ".repeat(64);
    let mut document = String::new();
    for i in 0..32 {
        write!(document, "- 09:{i:02}:00\n{padding}\n\n")?;
    }
    fs::write(workspace.join(DOCUMENT), document)?;
    let config = WorkspaceSessionConfig {
        capability: CapabilityToken::parse("edit-burst-probe")?,
        root_id: WorkspaceRootId::Notes,
        workspace_generation: WorkspaceGenerationId::mint()?,
        time_zone: "UTC".to_owned(),
        date_format: lomo_application::calendar::DateFormat::default(),
        state_dir: temp.path().join("state"),
        cache_dir: temp.path().join("cache"),
        runtime_dir: temp.path().join("runtime"),
        exchange_dir: temp.path().join("exchange"),
        media_stage_root: temp.path().join("media-stage"),
    };
    let executor = Arc::new(CountingExecutor {
        inner: FsPlatformActionExecutor::new(&config.exchange_dir)?,
        writes: AtomicUsize::new(0),
    });
    executor
        .inner
        .bind_root(config.capability.clone(), &workspace)?;
    let port: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(&executor);
    let session = WorkspaceSession::open(config, port)?;
    let created = session.create_memo(CreateMemoRequest {
        operation_id: OperationId::parse("probe-create")?,
        relative_path: Some(RelativeWorkspacePath::parse(DOCUMENT)?),
        time_token: Some("10:00:00".to_owned()),
        content: "editable".to_owned(),
        expected_document_fingerprint: None,
        pinned: false,
        pending_promotes: Vec::new(),
        chronology_epoch_ms: None,
    })?;
    session.settle_workspace()?;
    executor.writes.store(0, Ordering::SeqCst);
    let mut baseline = created.commit_result.file_fingerprint;
    let mut acknowledgement_time = Duration::ZERO;
    let start = Instant::now();
    for i in 0..count {
        let before = Instant::now();
        let result = session.update_memo(UpdateMemoRequest {
            operation_id: OperationId::parse(&format!("edit-{i}"))?,
            memo_id: created.memo_id.clone(),
            content: format!("revision {i}"),
            expected_document_fingerprint: baseline,
            pending_promotes: Vec::new(),
        })?;
        acknowledgement_time += before.elapsed();
        baseline = result.commit_result.file_fingerprint;
        if isolated {
            session.settle_workspace()?;
        }
    }
    session.settle_workspace()?;
    let settled_ms = start.elapsed().as_secs_f64() * 1000.0;
    let current = session
        .get_memo(&created.memo_id)?
        .ok_or_else(|| io::Error::other("memo disappeared"))?;
    if current.body != format!("revision {}", count - 1)
        || session
            .list_history(&created.memo_id, None, 256)?
            .items
            .len()
            != count + 1
    {
        return Err(io::Error::other("burst lost content or history").into());
    }
    Ok(Sample {
        mode: if isolated { "isolated" } else { "consecutive" },
        edits: count,
        acknowledgement_ms: acknowledgement_time.as_secs_f64() * 1000.0,
        settled_ms,
        document_writes: executor.writes.load(Ordering::SeqCst),
    })
}
fn main() -> Result<(), Box<dyn Error>> {
    let mut samples = Vec::new();
    for _ in 0..5 {
        for isolated in [true, false] {
            samples.push(sample(isolated, 32)?);
        }
    }
    serde_json::to_writer_pretty(io::stdout().lock(), &samples)?;
    Ok(())
}
