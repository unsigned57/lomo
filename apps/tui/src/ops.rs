//! TUI composition root. Runtime IO is driven by typed effects.
use crate::{
    config::AppConfig,
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    model::{AppModel, FeedKind, InputMode, View},
    xdg::RuntimePaths,
};
use lomo_application::{WorkspaceSession, WorkspaceSessionConfig};
use lomo_core::{CapabilityToken, OperationId};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_workspace::WorkspaceRootId;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError, RwLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// The two projections of `config.toml` the runtime carries (I8): `live` is
/// what this session actually runs with — hot fields mutate in place while
/// restart-required fields keep their boot-time value until relaunch — and
/// `on_disk` is the most recently validated file content, i.e. what the
/// Settings screen projects and what the next launch will read.
struct ConfigState {
    live: AppConfig,
    on_disk: AppConfig,
}

pub struct TuiRuntime {
    pub session: WorkspaceSession,
    pub paths: RuntimePaths,
    /// Hot-reloadable fields mutate `live` in place through `apply_config`;
    /// restart-required fields are bound into `session_config`/`workspace` at
    /// open and named pending instead of silently applied or silently dropped.
    config: RwLock<ConfigState>,
    pub workspace: PathBuf,
    pub session_config: WorkspaceSessionConfig,
    pub executor: Arc<dyn lomo_core::PlatformActionExecutor>,
}

impl TuiRuntime {
    /// A consistent snapshot of the live (session-effective) config. Small and
    /// cloneable by design — readers never hold the lock across session calls.
    ///
    /// A poisoned lock still yields readable state: a panicking writer must
    /// not wedge every query a second way.
    #[must_use]
    pub fn config(&self) -> AppConfig {
        self.config
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .live
            .clone()
    }

    /// A consistent snapshot of the on-disk config truth — the projection the
    /// Settings screen and settings edits are made against, so a pending
    /// restart-required change is shown as its file value, not the stale
    /// session value.
    #[must_use]
    pub fn file_config(&self) -> AppConfig {
        self.config
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .on_disk
            .clone()
    }

    /// Applies a freshly validated config in place, returning which fields
    /// landed live and which await a restart (registry-driven, I8). The file
    /// truth moves to `next` wholesale — it is what the file says — while only
    /// hot fields mutate `live`.
    pub fn apply_config(&self, next: AppConfig) -> crate::config::ReloadOutcome {
        let mut guard = self.config.write().unwrap_or_else(PoisonError::into_inner);
        let outcome = {
            let state = &mut *guard;
            state.on_disk = next;
            crate::config::apply_reload(&mut state.live, &state.on_disk)
        };
        drop(guard);
        outcome
    }
}

/// Everything the deferred workspace mount needs.
///
/// `Effect::Bootstrap` carries it so the UI thread only registers the request —
/// a lane does the blocking open (I5: the first frame draws before workspace
/// verification completes).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BootstrapSpec {
    pub paths: RuntimePaths,
    /// What bootstrap does with config.toml — the first-run boundary.
    pub launch: LaunchConfig,
    pub width: u16,
    pub height: u16,
}

/// How the deferred mount obtains its config (I8).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LaunchConfig {
    /// An existing, fully validated `config.toml` — open it directly.
    Ready(AppConfig),
    /// No `config.toml` exists. Bootstrap is *not* dispatched: the setup
    /// wizard owns this state until the user confirms, and `run` parks the
    /// bootstrap request on it.
    Setup(SetupSpec),
    /// The wizard confirmed: mint `config.toml` and the workspace on the lane,
    /// then open — the first durable side effect of a first run.
    Mint { file: PathBuf, config: AppConfig },
}

/// The first-run proposal data the wizard reviews.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SetupSpec {
    /// The path `config.toml` will be minted at.
    pub file: PathBuf,
    pub proposal: crate::config::ConfigProposal,
}

/// The live runtime the effect lanes resolve against, or why they may not.
enum SlotState {
    /// `Effect::Bootstrap` is queued or running — dependents park on `ready`.
    Opening,
    /// Open and model preparation finished; the runtime is live.
    Ready(Arc<TuiRuntime>),
    /// Bootstrap failed terminally; dependent work answers `Failed` with the
    /// diagnostic instead of waiting on a runtime that will never arrive.
    Failed(String),
}

/// Shared handle to the runtime while bootstrap is in flight.
///
/// Jobs that need the workspace wait on the ready edge inside their lane —
/// never on the UI thread. A job whose request dies while it waits stops
/// waiting instead of running stale work; a failed bootstrap fails them all
/// with the same diagnostic the user sees.
pub struct RuntimeSlot {
    inner: Mutex<SlotState>,
    ready: Condvar,
}

/// The outcome of waiting on [`RuntimeSlot`].
pub enum SlotWait {
    Ready(Arc<TuiRuntime>),
    /// The job's token tripped before the runtime arrived — drop it quietly.
    Cancelled,
    /// Bootstrap failed; the job's reply is the same `Failed` diagnostic.
    Failed(String),
}

impl RuntimeSlot {
    /// A slot the bootstrap job has not finished filling yet.
    #[must_use]
    pub const fn opening() -> Self {
        Self {
            inner: Mutex::new(SlotState::Opening),
            ready: Condvar::new(),
        }
    }

    /// A slot holding a live runtime — tests and the bootstrap install path.
    #[must_use]
    pub const fn ready(runtime: Arc<TuiRuntime>) -> Self {
        Self {
            inner: Mutex::new(SlotState::Ready(runtime)),
            ready: Condvar::new(),
        }
    }

    fn lock(&self) -> MutexGuard<'_, SlotState> {
        // A poisoned slot mutex still carries readable state — a bootstrap
        // panic while holding it must not hang every lane a second way.
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Install the opened runtime and wake every parked lane.
    pub fn install(&self, runtime: Arc<TuiRuntime>) {
        *self.lock() = SlotState::Ready(runtime);
        self.ready.notify_all();
    }

    /// Mark bootstrap as failed and wake every parked lane — they answer
    /// `Failed` with this diagnostic instead of parking forever.
    pub fn fail(&self, diagnostic: String) {
        *self.lock() = SlotState::Failed(diagnostic);
        self.ready.notify_all();
    }

    /// The runtime only when it is already installed — the non-blocking probe
    /// for code paths that may only run once the workspace is live.
    #[must_use]
    pub fn get(&self) -> Option<Arc<TuiRuntime>> {
        match &*self.lock() {
            SlotState::Ready(runtime) => Some(Arc::clone(runtime)),
            SlotState::Opening | SlotState::Failed(_) => None,
        }
    }

    /// Resolve the runtime for one job, parking while bootstrap is still
    /// running and bailing early when the job's request was revoked.
    pub fn wait_ready(&self, token: &crate::model::CancelToken) -> SlotWait {
        let mut guard = self.lock();
        loop {
            match &*guard {
                // The token is re-checked on the ready edge: a request revoked
                // while the runtime was opening must not run against it.
                SlotState::Ready(runtime) => {
                    return if token.is_cancelled() {
                        SlotWait::Cancelled
                    } else {
                        SlotWait::Ready(Arc::clone(runtime))
                    };
                }
                SlotState::Failed(diagnostic) => {
                    return SlotWait::Failed(diagnostic.clone());
                }
                SlotState::Opening => {
                    if token.is_cancelled() {
                        return SlotWait::Cancelled;
                    }
                    // Poll in slices so a revoked request stops waiting mid-open
                    // instead of riding the whole bootstrap to its reply.
                    let (next, _) = self
                        .ready
                        .wait_timeout(guard, Duration::from_millis(50))
                        .unwrap_or_else(PoisonError::into_inner);
                    guard = next;
                }
            }
        }
    }
}

/// Opens the workspace and its isolated host resources.
/// # Errors
/// Configuration, capability, projection or filesystem failures.
pub fn open_runtime(paths: RuntimePaths, config: AppConfig) -> Result<TuiRuntime, TuiError> {
    // The typed config is re-checked at the open boundary: a value built
    // outside `parse_config_toml` must fail as a *config* error before any
    // durable write — a session-level failure after `.lomo` was minted would
    // name the wrong layer and leave evidence behind.
    lomo_application::calendar::validate_zone(&config.time_zone)
        .map_err(|error| TuiError::config(format!("time_zone: {error}")))?;
    for (key, dir) in [
        ("workspace", &config.workspace),
        ("media_dir", &config.media_dir),
    ] {
        if !dir.is_absolute() {
            return Err(TuiError::config(format!(
                "{key} must be absolute, got '{}'",
                dir.display()
            )));
        }
    }
    for dir in [
        &paths.state_dir,
        &paths.cache_dir,
        &paths.runtime_dir,
        &paths.exchange_dir,
        &paths.drafts_dir,
        &paths.config_dir,
    ] {
        crate::drafts::private_directory(dir)?;
    }
    // Opening an existing library never materializes one: first-run minting is
    // the distinct create action. A missing workspace means a wrong path.
    if !config.workspace.is_dir() {
        return Err(TuiError::config(format!(
            "workspace directory does not exist: {}",
            config.workspace.display()
        )));
    }
    let executor = Arc::new(FsPlatformActionExecutor::new(&paths.exchange_dir)?);
    let capability = CapabilityToken::parse("notes-root")?;
    executor.bind_root(capability.clone(), &config.workspace)?;
    lomo_workspace::migrate_history_state_v1_to_v2(&config.workspace)?;
    let workspace_generation =
        lomo_workspace::load_or_mint_workspace_generation(&config.workspace)?;
    let session_config = WorkspaceSessionConfig {
        capability,
        root_id: WorkspaceRootId::Notes,
        workspace_generation,
        time_zone: config.time_zone.clone(),
        date_format: config.date_format,
        state_dir: paths.state_dir.clone(),
        cache_dir: paths.cache_dir.clone(),
        runtime_dir: paths.runtime_dir.clone(),
        exchange_dir: paths.exchange_dir.clone(),
        media_stage_root: config.media_dir.clone(),
    };
    let executor: Arc<dyn lomo_core::PlatformActionExecutor> = executor;
    let session = WorkspaceSession::open(session_config.clone(), Arc::clone(&executor))?;
    Ok(TuiRuntime {
        session,
        workspace: config.workspace.clone(),
        paths,
        config: RwLock::new(ConfigState {
            live: config.clone(),
            on_disk: config,
        }),
        session_config,
        executor,
    })
}

/// The deferred workspace mount itself: `Effect::Bootstrap` executes here on a
/// lane while the UI draws the `Loading` shell (I5).
///
/// Phase observations (`BootPhase`) keep the loading view's status line honest,
/// the opened runtime installs into `slot` so parked lane work unblocks as soon
/// as verification finishes, and the prepared model answers the request as
/// `RuntimeReady`. Any failure marks the slot `Failed` — work queued behind it
/// answers `Failed` with the same diagnostic rather than parking forever.
///
/// # Errors
/// Runtime open or model-preparation failures, unchanged from the two steps.
pub fn bootstrap(
    slot: &RuntimeSlot,
    spec: &BootstrapSpec,
    req: crate::model::Req,
    outbox: &crate::executor::Outbox,
) -> Result<RuntimeMessage, TuiError> {
    // Best-effort progress observation — the outbox may already be closing.
    let _delivered = outbox.report(RuntimeMessage::BootPhase {
        phase: crate::effects::BootPhase::Workspace,
    });
    let (config, minted) = match &spec.launch {
        LaunchConfig::Ready(config) => (config.clone(), false),
        LaunchConfig::Mint { file, config } => {
            // Confirmation already happened — this is the first durable write
            // of a first run. The marker records which workspace was minted so
            // a later deleted config reads as deletion, not a fresh install.
            crate::config::mint_config(file, config)?;
            if let Err(error) = crate::xdg::mark_initialized(&spec.paths, &config.workspace) {
                // The marker is advisory: config.toml itself is the truth, so
                // a failed marker write must not strand a minted config.
                tracing::warn!("could not write the initialized marker: {error}");
            }
            (config.clone(), true)
        }
        LaunchConfig::Setup(_) => {
            return Err(TuiError::config(
                "bootstrap dispatched before first-run setup was confirmed",
            ));
        }
    };
    let runtime = match open_runtime(spec.paths.clone(), config) {
        Ok(runtime) => Arc::new(runtime),
        Err(error) => {
            slot.fail(error.to_string());
            return Err(error);
        }
    };
    slot.install(Arc::clone(&runtime));
    let _delivered = outbox.report(RuntimeMessage::BootPhase {
        phase: crate::effects::BootPhase::Model,
    });
    match bootstrap_model(&runtime, AppModel::new(spec.width, spec.height)) {
        Ok(mut model) => {
            if minted {
                model.set_status(crate::i18n::UiStrings::detect().text(
                    "Workspace created and configured — welcome to lomo",
                    "已创建并配置工作区——欢迎使用 lomo",
                ));
            }
            Ok(RuntimeMessage::RuntimeReady {
                req,
                model: Box::new(model),
            })
        }
        Err(error) => {
            slot.fail(error.to_string());
            Err(error)
        }
    }
}

/// Loads the first page and recoverable capture draft.
/// # Errors
/// Query or private draft failures.
pub fn bootstrap_model(runtime: &TuiRuntime, mut model: AppModel) -> Result<AppModel, TuiError> {
    model.view = View::Feed(Box::new(crate::queries::load_feed(
        runtime,
        FeedKind::Timeline,
    )?));
    model.set_tags(
        runtime
            .session
            .sidebar_projection()?
            .tag_counts
            .into_iter()
            .map(|tag| tag.name)
            .collect(),
    );
    let loaded = crate::drafts::load_capture(runtime)?;
    model.draft = loaded.composer;
    if let Some(backup) = loaded.recovered_corrupt {
        // F-09: the corrupt draft sits at `<file>.corrupt` — the user must
        // see that it exists and that the composer started empty.
        let strings = crate::i18n::UiStrings::detect();
        model.present(crate::model::Notice::modal(
            crate::model::Severity::Warn,
            strings
                .text("Corrupt capture draft set aside", "损坏的速记草稿已移开")
                .to_owned(),
            vec![
                strings
                    .text(
                        "The saved draft could not be parsed; it was renamed to:",
                        "已保存的草稿无法解析，已重命名为：",
                    )
                    .to_owned(),
                backup.display().to_string(),
            ],
        ));
    }
    let overdue = runtime
        .session
        .reminder_plan(Some(now_ms()?))?
        .alarms
        .into_iter()
        .filter(|alarm| alarm.is_catch_up)
        .map(|alarm| {
            let stamp = lomo_application::calendar::journal_stamp(
                alarm.trigger_at_utc_ms,
                &runtime.config().time_zone,
                lomo_application::calendar::DateFormat::YyyyMmDdHyphen,
            )?;
            Ok(format!(
                "{} {} · {}",
                stamp.filename.trim_end_matches(".md"),
                stamp.time_token,
                alarm.memo_identity
            ))
        })
        .collect::<Result<Vec<_>, TuiError>>()?;
    if !overdue.is_empty() {
        model.input = InputMode::Message {
            title: crate::i18n::UiStrings::detect().title_overdue.clone(),
            lines: overdue,
            scroll: 0,
        };
    }
    Ok(model)
}
/// A resolved empty answer is `MemoGone`, not a `Failed` — the landing intent
/// treats a lookup miss as context, not a screen-breaking error (A-07/I9).
fn read_memo(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    id: &lomo_workspace::MemoId,
) -> Result<RuntimeMessage, TuiError> {
    crate::queries::load_body(runtime, id)?.map_or_else(
        || {
            Ok(RuntimeMessage::MemoGone {
                req,
                id: id.clone(),
            })
        },
        |memo| {
            Ok(RuntimeMessage::ReadMemo {
                req,
                memo: Box::new(memo),
            })
        },
    )
}
/// Executes one effect; the caller applies its reply in the UI thread.
///
/// `outbox` carries asynchronous completions (player exit) back to the loop;
/// `token` is the pending registry's cancellation flag — long-running jobs
/// check it mid-loop so a revoked request stops doing work (I3).
/// # Errors
/// Preserves session, tool and filesystem diagnostics.
pub fn execute(
    runtime: &TuiRuntime,
    effect: &Effect,
    outbox: &crate::executor::Outbox,
    token: &crate::model::CancelToken,
) -> Result<RuntimeMessage, TuiError> {
    match effect {
        Effect::LoadImage { req, request } => Ok(RuntimeMessage::Image {
            req: *req,
            request: request.clone(),
            result: crate::graphics::load_image(runtime, request, token)
                .map(Arc::new)
                .map_err(|error| error.to_string()),
        }),
        Effect::Query(request) => crate::queries::query_feed(runtime, request),
        Effect::Navigate { req, screen } => Ok(RuntimeMessage::View {
            req: *req,
            view: Box::new(crate::queries::load_screen(runtime, *screen)?),
        }),
        Effect::Bodies { req, versions } => load_bodies(runtime, *req, versions, token),
        Effect::ReadMemo { req, id } => read_memo(runtime, *req, id),
        Effect::PersistDraft {
            req,
            revision,
            content,
        } => store_draft(runtime, *req, *revision, content),
        Effect::CommitDraft {
            req,
            revision,
            content,
        } => {
            let id = crate::drafts::commit_capture(runtime, *revision, content)?;
            Ok(RuntimeMessage::Saved {
                req: *req,
                revision: *revision,
                id,
            })
        }
        Effect::CommitEdit { req, edit } => crate::edit_flow::commit_edit(runtime, *req, edit),
        Effect::CaptureEdited {
            req,
            revision,
            content,
            draft_path,
        } => {
            let stored = store_draft(runtime, *req, *revision, content)?;
            crate::drafts::remove_draft(draft_path)?;
            Ok(stored)
        }
        Effect::OpenAttachment { req, path } => open_attachment(runtime, *req, path, outbox),
        Effect::MediaSweep { req, drafts } => media_sweep(runtime, *req, drafts),
        Effect::ToggleTask { .. }
        | Effect::Pin { .. }
        | Effect::Delete { .. }
        | Effect::DeleteForever { .. }
        | Effect::EmptyTrash { .. }
        | Effect::Restore { .. }
        | Effect::RestoreRevision { .. }
        | Effect::History { .. }
        | Effect::ImportClipboard { .. } => crate::mutations::execute(runtime, effect, token),
        Effect::Reconcile { req, observed } => {
            // Watcher-attested paths scope the reconcile to O(changed); an
            // unattested batch (rescan, dead-watcher fallback, focus) keeps
            // the full scan as the truth rebuilder.
            let result = match observed {
                Some(paths) => runtime.session.reconcile_observed_paths(paths)?,
                None => runtime.session.rebuild_projection()?,
            };
            Ok(RuntimeMessage::Reconciled {
                req: *req,
                changed: result.rewritten,
            })
        }
        Effect::Tags { req } => Ok(RuntimeMessage::Tags {
            req: *req,
            tags: runtime
                .session
                .sidebar_projection()?
                .tag_counts
                .into_iter()
                .map(|tag| tag.name)
                .collect(),
        }),
        Effect::Date { req, text } => crate::queries::date_filter(runtime, *req, text),
        Effect::Refresh { req } => refresh(runtime, *req),
        Effect::ReloadConfig { req } => reload_config(runtime, *req),
        Effect::SaveSetting { req, field, value } => save_setting(runtime, *req, *field, value),
        Effect::Quit { req } => Ok(RuntimeMessage::QuitReady { req: *req }),
        Effect::Bootstrap { .. } | Effect::SetupConfirmed { .. } => Err(TuiError::config(
            "bootstrap/setup fills the runtime slot — it cannot execute against a runtime",
        )),
        Effect::Edit { .. } => Err(TuiError::config(
            "foreground terminal action cannot run in the worker",
        )),
    }
}

/// F5 rebuilds the projection AND re-reads `config.toml`: the manual path
/// shares the watcher's reload semantics — an invalid file never replaces the
/// live config, and the diagnostic is part of the reply, not hidden.
fn refresh(runtime: &TuiRuntime, req: crate::model::Req) -> Result<RuntimeMessage, TuiError> {
    runtime.session.rebuild_projection()?;
    let strings = crate::i18n::UiStrings::detect();
    let prefix = strings.text("Workspace refreshed", "工作区已刷新");
    let status = match crate::config::reload_config(&runtime.paths) {
        Ok(next) => {
            let outcome = runtime.apply_config(next);
            reload_status(strings, &outcome, prefix)
        }
        Err(error) => {
            format!(
                "{prefix} · {}{error}",
                strings.text("config kept: ", "配置保留：")
            )
        }
    };
    Ok(RuntimeMessage::Changed { req, status })
}

/// Re-parse `config.toml` and apply the registry's hot fields; differing
/// restart-required fields come back named in the reply.
fn reload_config(runtime: &TuiRuntime, req: crate::model::Req) -> Result<RuntimeMessage, TuiError> {
    let next = crate::config::reload_config(&runtime.paths)?;
    let outcome = runtime.apply_config(next);
    Ok(RuntimeMessage::ConfigApplied {
        req,
        config: Box::new(runtime.file_config()),
        applied: outcome.applied,
        restart_pending: outcome.restart_pending,
    })
}

/// One validated settings edit: write the registry-rendered file first (the
/// file stays the source of truth), then apply the new value under the same
/// hot/restart rules as any other reload.
fn save_setting(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    field: crate::config::SettingsField,
    value: &crate::config::FieldValue,
) -> Result<RuntimeMessage, TuiError> {
    // Edits build on the file truth — the file as it is NOW, not the in-memory
    // projection. Re-reading at the commit point rebases the save onto writes
    // that landed on disk but whose `ConfigChanged` is still in flight: only
    // the target field changes, never a rollback of fields this save was
    // never shown. A file that is missing or unparseable right now fails the
    // save loudly instead of being overwritten by a stale snapshot.
    let mut next = crate::config::reload_config(&runtime.paths)?;
    field.apply(&mut next, value.clone());
    crate::config::save_config(&crate::config::config_file(&runtime.paths), &next)?;
    let outcome = runtime.apply_config(next);
    Ok(RuntimeMessage::ConfigApplied {
        req,
        config: Box::new(runtime.file_config()),
        applied: outcome.applied,
        restart_pending: outcome.restart_pending,
    })
}

/// The user-facing summary of a config reload: which fields landed live and
/// which are marked for the next launch — restart-required fields are named,
/// never silently applied or silently ignored.
fn reload_status(
    strings: &crate::i18n::UiStrings,
    outcome: &crate::config::ReloadOutcome,
    prefix: &str,
) -> String {
    if outcome.applied.is_empty() && outcome.restart_pending.is_empty() {
        return prefix.to_owned();
    }
    let mut parts = vec![prefix.to_owned()];
    if !outcome.applied.is_empty() {
        parts.push(format!(
            "{}{}",
            strings.text("applied: ", "已应用："),
            outcome
                .applied
                .iter()
                .map(|field| field.key())
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    if !outcome.restart_pending.is_empty() {
        parts.push(format!(
            "{}{}",
            strings.text("takes effect on restart: ", "重启后生效："),
            outcome
                .restart_pending
                .iter()
                .map(|field| field.key())
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    parts.join(" · ")
}

/// Reads each requested body version. The token is checked between items: a
/// hydration batch walks many files, so a revoked pass stops mid-batch
/// instead of finishing dead work (I3).
/// # Errors
/// Cancellation surfaces as a config error — the lane drops the dead receipt.
fn load_bodies(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    versions: &[crate::model::MemoVersion],
    token: &crate::model::CancelToken,
) -> Result<RuntimeMessage, TuiError> {
    let mut bodies = Vec::with_capacity(versions.len());
    for version in versions {
        if token.is_cancelled() {
            return Err(TuiError::config("body hydration cancelled"));
        }
        bodies.push(crate::effects::BodyReply {
            version: version.clone(),
            result: crate::queries::load_version_body(runtime, version)
                .map_err(|error| error.to_string()),
        });
    }
    Ok(RuntimeMessage::Bodies { req, bodies })
}

/// Persisted drafts all reply `DraftStored` under the request that carried them.
/// # Errors
/// Propagates the draft-store failure for the caller to land as a `Failed` receipt.
fn store_draft(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    revision: u64,
    content: &str,
) -> Result<RuntimeMessage, TuiError> {
    crate::drafts::persist_capture(runtime, revision, content)?;
    Ok(RuntimeMessage::DraftStored { req, revision })
}

/// Stages the attachment under the workspace capability, spawns the player as
/// a managed child and reports its exit through `outbox` — the blocking wait
/// runs on a monitor thread, never on this effect worker. The completion is
/// a `report`, not a dropped send: a saturated inbox leaves a trace rather
/// than losing the exit (F-08).
/// # Errors
/// Staging or spawn failures keep their typed diagnostics.
fn open_attachment(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    path: &lomo_core::RelativeWorkspacePath,
    outbox: &crate::executor::Outbox,
) -> Result<RuntimeMessage, TuiError> {
    let staged = crate::mutations::stage_attachment(runtime, path)?;
    let mut spawned = crate::media::spawn_player(
        &crate::editor::StdCommandRunner,
        &runtime.config().player,
        &staged,
    )?;
    let monitor = outbox.clone();
    // The detached monitor outlives this call; `report` retries delivery and
    // leaves a stderr trace rather than dropping the exit silently (F-08).
    std::thread::Builder::new()
        .name("lomo-bg-player".to_owned())
        .spawn(move || {
            // Drain stderr first: the child closing the pipe is its exit
            // signal, so the bounded read never wedges `wait` (D-06).
            let captured = crate::media::drain_player_stderr(spawned.stderr.take());
            let reply = match spawned.child.wait() {
                Ok(status) => RuntimeMessage::PlayerFinished {
                    success: status.success(),
                    diagnostic: crate::media::player_exit_diagnostic(status, &captured),
                },
                Err(error) => RuntimeMessage::PlayerFinished {
                    success: false,
                    diagnostic: Some(format!(
                        "player wait failed: {error}{}",
                        if captured.is_empty() {
                            String::new()
                        } else {
                            format!(": {captured}")
                        }
                    )),
                },
            };
            // `false` is traced inside `report`; the monitor is exiting.
            let _delivered = monitor.report(reply);
        })
        .map_err(TuiError::from)?;
    Ok(RuntimeMessage::Message {
        req,
        title: crate::i18n::UiStrings::detect()
            .text("Attachment opened", "附件已打开")
            .to_owned(),
        lines: vec![path.as_str().to_owned()],
    })
}

/// The two-phase media orphan sweep (D-12). The effect's `drafts` carry the
/// UI-owned compose buffer; every `*.md` editor draft still sitting under
/// `drafts_dir` is added as a guard so a sweep can never reclaim media an
/// open `$EDITOR` session references. Guard bodies are projected inside the
/// session's sweep lock — nothing is persisted by guarding.
/// # Errors
/// Sweep, draft-read or clock failures stay typed for the `Failed` receipt.
fn media_sweep(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    drafts: &[lomo_application::GuardedDraftBody],
) -> Result<RuntimeMessage, TuiError> {
    let mut guarded = editor_draft_guards(&runtime.paths.drafts_dir)?;
    guarded.extend(drafts.iter().cloned());
    let now = u64::try_from(now_ms()?)
        .map_err(|error| TuiError::io(format!("negative clock: {error}")))?;
    let report = runtime.session.media_orphan_sweep_guarding(
        now,
        lomo_media::DEFAULT_RECOVERY_WINDOW_MS,
        &guarded,
    )?;
    Ok(RuntimeMessage::MediaSweepDone {
        req,
        moved: u64::try_from(report.moved_to_trash.len()).unwrap_or(u64::MAX),
        purged: u64::try_from(report.permanently_deleted.len()).unwrap_or(u64::MAX),
        failures: u64::try_from(report.failures.len()).unwrap_or(u64::MAX),
    })
}

/// Snapshot every external-editor draft body as a sweep guard. The draft
/// files live outside the Rust draft store — without these guards a sweep
/// could trash media a not-yet-committed edit still references.
/// # Errors
/// An unreadable directory or draft file aborts the sweep rather than
/// silently dropping that draft's protection.
fn editor_draft_guards(
    drafts_dir: &Path,
) -> Result<Vec<lomo_application::GuardedDraftBody>, TuiError> {
    let mut guards = Vec::new();
    let entries = match std::fs::read_dir(drafts_dir) {
        Ok(entries) => entries,
        // No drafts directory means no editor drafts — an empty guard set is
        // the honest answer, not an error.
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(guards),
        Err(error) => return Err(TuiError::from(error)),
    };
    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        if path.extension().is_none_or(|ext| ext != "md") {
            continue;
        }
        let content = match std::fs::read_to_string(&path) {
            Ok(content) => content,
            // The draft was removed between listing and reading — a deleted
            // draft protects nothing.
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(TuiError::from(error)),
        };
        guards.push(lomo_application::GuardedDraftBody {
            owner_id: entry.file_name().to_string_lossy().into_owned(),
            content,
        });
    }
    Ok(guards)
}

/// Cryptographically unique identity for a durable operation.
/// # Errors
/// OS randomness or identifier validation failure.
pub fn mint_operation_id() -> Result<OperationId, TuiError> {
    let hex = lomo_application::csprng::generate_hex_token(8)?;
    Ok(OperationId::parse(&format!("op-{hex}"))?)
}
/// # Errors
/// A system clock before the Unix epoch or an unrepresentable millisecond value.
pub fn now_ms() -> Result<i64, TuiError> {
    i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| TuiError::io(error.to_string()))?
            .as_millis(),
    )
    .map_err(|error| TuiError::io(error.to_string()))
}
#[must_use]
pub fn workspace_path(runtime: &TuiRuntime) -> &Path {
    &runtime.workspace
}
impl From<lomo_application::calendar::CalendarError> for TuiError {
    fn from(error: lomo_application::calendar::CalendarError) -> Self {
        Self::config(error.to_string())
    }
}
