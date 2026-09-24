//! TUI composition root. Runtime IO is driven by typed effects.
use crate::{
    config::AppConfig,
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    media::GraphicsProtocol,
    model::{AppModel, FeedKind, InputMode, View},
    xdg::RuntimePaths,
};
use lomo_application::{WorkspaceSession, WorkspaceSessionConfig};
use lomo_core::{CapabilityToken, OperationId};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_workspace::WorkspaceRootId;
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

pub struct TuiRuntime {
    pub session: WorkspaceSession,
    pub paths: RuntimePaths,
    pub config: AppConfig,
    pub graphics: GraphicsProtocol,
    pub workspace: PathBuf,
    pub session_config: WorkspaceSessionConfig,
    pub executor: Arc<dyn lomo_core::PlatformActionExecutor>,
}

/// Opens the workspace and its isolated host resources.
/// # Errors
/// Configuration, capability, projection or filesystem failures.
pub fn open_runtime(
    paths: RuntimePaths,
    config: AppConfig,
    graphics: GraphicsProtocol,
) -> Result<TuiRuntime, TuiError> {
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
        media_stage_root: config.workspace.clone(),
    };
    let executor: Arc<dyn lomo_core::PlatformActionExecutor> = executor;
    let session = WorkspaceSession::open(session_config.clone(), Arc::clone(&executor))?;
    Ok(TuiRuntime {
        session,
        workspace: config.workspace.clone(),
        paths,
        config,
        graphics,
        session_config,
        executor,
    })
}
/// Loads the first page and recoverable capture draft.
/// # Errors
/// Query or private draft failures.
pub fn bootstrap_model(runtime: &TuiRuntime, mut model: AppModel) -> Result<AppModel, TuiError> {
    model.graphics = runtime.graphics;
    model.view = View::Feed(crate::queries::load_feed(
        runtime,
        FeedKind::Timeline,
        model.epoch,
    )?);
    model.tags = runtime
        .session
        .sidebar_projection()?
        .tag_counts
        .into_iter()
        .map(|tag| tag.name)
        .collect();
    model.draft = crate::drafts::load_capture(runtime)?;
    let overdue = runtime
        .session
        .reminder_plan(Some(now_ms()?))?
        .alarms
        .into_iter()
        .filter(|alarm| alarm.is_catch_up)
        .map(|alarm| {
            let stamp = lomo_application::calendar::journal_stamp(
                alarm.trigger_at_utc_ms,
                &runtime.config.time_zone,
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
/// Executes one effect. The caller applies its reply in the UI thread.
/// `results` carries asynchronous completions (player exit) back to the loop.
/// # Errors
/// Preserves session, tool and filesystem diagnostics.
pub fn execute(
    runtime: &TuiRuntime,
    effect: &Effect,
    results: &std::sync::mpsc::Sender<RuntimeMessage>,
) -> Result<RuntimeMessage, TuiError> {
    match effect {
        Effect::LoadImage(request) => Ok(RuntimeMessage::Image {
            request: request.clone(),
            result: crate::graphics::load_image(runtime, request)
                .map_err(|error| error.to_string()),
        }),
        Effect::Query(request) => crate::queries::query_feed(runtime, request),
        Effect::Navigate { epoch, screen } => Ok(RuntimeMessage::View {
            epoch: *epoch,
            view: Box::new(crate::queries::load_screen(runtime, *screen, *epoch)?),
        }),
        Effect::Bodies { epoch, versions } => Ok(RuntimeMessage::Bodies {
            epoch: *epoch,
            bodies: versions
                .iter()
                .map(|version| crate::effects::BodyReply {
                    version: version.clone(),
                    result: crate::queries::load_version_body(runtime, version)
                        .map_err(|error| error.to_string()),
                })
                .collect(),
        }),
        Effect::ReadMemo { epoch, id } => Ok(RuntimeMessage::ReadMemo {
            epoch: *epoch,
            memo: Box::new(crate::queries::load_body(runtime, id)?),
        }),
        Effect::PersistDraft { revision, content } => {
            crate::drafts::persist_capture(runtime, *revision, content)?;
            Ok(RuntimeMessage::DraftStored {
                revision: *revision,
            })
        }
        Effect::CommitDraft { revision, content } => {
            let id = crate::drafts::commit_capture(runtime, *revision, content)?;
            Ok(RuntimeMessage::Saved {
                revision: *revision,
                id,
            })
        }
        Effect::CommitEdit(edit) => crate::edit_flow::commit_edit(runtime, edit),
        Effect::CaptureEdited {
            revision,
            content,
            draft_path,
        } => {
            crate::drafts::persist_capture(runtime, *revision, content)?;
            crate::drafts::remove_draft(draft_path)?;
            Ok(RuntimeMessage::DraftStored {
                revision: *revision,
            })
        }
        Effect::OpenAttachment(path) => open_attachment(runtime, path, results),
        Effect::ToggleTask(_)
        | Effect::Pin { .. }
        | Effect::Delete { .. }
        | Effect::DeleteForever(_)
        | Effect::EmptyTrash
        | Effect::Restore(_)
        | Effect::RestoreRevision { .. }
        | Effect::History(_)
        | Effect::ImportClipboard => crate::mutations::execute(runtime, effect),
        Effect::Reconcile => {
            let result = runtime.session.rebuild_projection()?;
            Ok(RuntimeMessage::Reconciled {
                changed: result.rewritten,
            })
        }
        Effect::Tags => Ok(RuntimeMessage::Tags(
            runtime
                .session
                .sidebar_projection()?
                .tag_counts
                .into_iter()
                .map(|tag| tag.name)
                .collect(),
        )),
        Effect::Date { ticket, text } => crate::queries::date_filter(runtime, *ticket, text),
        Effect::Refresh => {
            runtime.session.rebuild_projection()?;
            Ok(RuntimeMessage::Changed(
                crate::i18n::UiStrings::detect()
                    .text("Workspace refreshed", "工作区已刷新")
                    .to_owned(),
            ))
        }
        Effect::Quit => Ok(RuntimeMessage::QuitReady),
        Effect::Edit(_) => Err(TuiError::config(
            "foreground terminal action cannot run in the worker",
        )),
    }
}

/// Stages the attachment under the workspace capability, spawns the player as
/// a managed child and reports its exit through `results` — the blocking wait
/// runs on a monitor thread, never on this effect worker.
/// # Errors
/// Staging or spawn failures keep their typed diagnostics.
fn open_attachment(
    runtime: &TuiRuntime,
    path: &lomo_core::RelativeWorkspacePath,
    results: &std::sync::mpsc::Sender<RuntimeMessage>,
) -> Result<RuntimeMessage, TuiError> {
    let staged = crate::mutations::stage_attachment(runtime, path)?;
    let mut child = crate::media::spawn_player(
        &crate::editor::StdCommandRunner,
        &runtime.config.player,
        &staged,
    )?;
    let monitor = results.clone();
    std::thread::spawn(move || {
        let reply = match child.wait() {
            Ok(status) => RuntimeMessage::PlayerFinished {
                success: status.success(),
                diagnostic: crate::media::player_exit_diagnostic(status),
            },
            Err(error) => RuntimeMessage::PlayerFinished {
                success: false,
                diagnostic: Some(format!("player wait failed: {error}")),
            },
        };
        drop(monitor.send(reply));
    });
    Ok(RuntimeMessage::Message {
        title: crate::i18n::UiStrings::detect()
            .text("Attachment opened", "附件已打开")
            .to_owned(),
        lines: vec![path.as_str().to_owned()],
    })
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
