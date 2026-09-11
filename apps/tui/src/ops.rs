use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_application::calendar::{CivilDate, journal_stamp, local_date};
use lomo_application::csprng::generate_hex_token;
use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort, MemoSummary,
    PinMemoRequest, RestoreMemoRequest, SearchOutcome, SearchRequest, StatisticsSnapshot,
    ToggleTaskRequest, UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
};
use lomo_core::{CapabilityToken, OperationId, PageSize};
use lomo_media::{ContentDigest, MediaSource, PromotePlan, stage_media};
use lomo_platform_fs::PosixPlatformActionExecutor;
use lomo_workspace::{MemoId, WorkspaceRootId};

use crate::config::AppConfig;
use crate::error::TuiError;
use crate::media::{GraphicsProtocol, ImageClipboard, unique_media_relative_path};
use crate::model::{AppModel, HeatPoint, ListRow, Overlay, Screen, SearchSession, StatsView};
use crate::update::Effect;
use crate::xdg::RuntimePaths;

/// Composition root: POSIX executor + shared application session.
pub struct TuiRuntime {
    pub session: WorkspaceSession,
    pub paths: RuntimePaths,
    pub config: AppConfig,
    pub graphics: GraphicsProtocol,
    pub workspace: PathBuf,
}

/// Opens private dirs, binds the workspace root, and rebuilds the projection.
///
/// # Errors
/// Path, executor, or session failures.
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
        fs::create_dir_all(dir)?;
    }
    fs::create_dir_all(&config.workspace)?;
    let executor = Arc::new(PosixPlatformActionExecutor::new(&paths.exchange_dir)?);
    let capability = CapabilityToken::parse("notes-root")?;
    executor.bind_root(capability.clone(), &config.workspace)?;
    let session = WorkspaceSession::open(
        WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            time_zone: config.time_zone.clone(),
            date_format: config.date_format,
            state_dir: paths.state_dir.clone(),
            cache_dir: paths.cache_dir.clone(),
            runtime_dir: paths.runtime_dir.clone(),
            exchange_dir: paths.exchange_dir.clone(),
        },
        executor,
    )?;
    Ok(TuiRuntime {
        session,
        workspace: config.workspace.clone(),
        paths,
        config,
        graphics,
    })
}

/// Loads the first screen and overdue reminders.
///
/// # Errors
/// Projection query failures.
pub fn bootstrap_model(runtime: &TuiRuntime, mut model: AppModel) -> Result<AppModel, TuiError> {
    reload_screen(runtime, &mut model)?;
    let now = now_ms()?;
    let plan = runtime.session.reminder_plan(Some(now))?;
    let overdue: Vec<String> = plan
        .alarms
        .iter()
        .filter(|alarm| alarm.is_catch_up || alarm.trigger_at_utc_ms <= now)
        .map(|alarm| {
            format!(
                "overdue {} at {}",
                alarm.memo_identity, alarm.trigger_at_utc_ms
            )
        })
        .collect();
    if !overdue.is_empty() {
        model.overlay = Overlay::Overdue { lines: overdue };
    }
    Ok(model)
}

/// Performs non-editor effects.
///
/// # Errors
/// Session, media, or IO failures.
pub fn apply_effect(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    effect: Effect,
) -> Result<(), TuiError> {
    match effect {
        Effect::None
        | Effect::Quit
        | Effect::NewMemo
        | Effect::EditMemo
        | Effect::ImportClipboard
        | Effect::PlayAttachment => Ok(()),
        Effect::LoadScreen => reload_screen(runtime, model),
        Effect::Search => run_search(runtime, model),
        Effect::ToggleTask => toggle_selected_task(runtime, model),
        Effect::PinSelected => pin_selected(runtime, model),
        Effect::DeleteSelected => delete_selected(runtime, model),
        Effect::RestoreSelected => restore_selected(runtime, model),
        Effect::ShowHistory => show_history(runtime, model),
        Effect::ConfirmDelete => {
            crate::update::request_confirm(model, crate::model::ConfirmAction::Delete);
            Ok(())
        }
        Effect::ConfirmRestore => {
            crate::update::request_confirm(model, crate::model::ConfirmAction::Restore);
            Ok(())
        }
    }
}

/// Reloads the active screen from `lomo-application`.
///
/// # Errors
/// Query failures.
pub fn reload_screen(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    model.stats = None;
    match model.screen {
        Screen::Timeline => load_timeline(runtime, model),
        Screen::Tasks => load_tasks(runtime, model),
        Screen::Review => load_review(runtime, model),
        Screen::Statistics => load_stats(runtime, model),
        Screen::Attachments => load_attachments(runtime, model),
        Screen::Trash => load_trash(runtime, model),
        Screen::Settings => {
            load_settings(runtime, model);
            Ok(())
        }
    }
}

fn load_timeline(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let as_of = today(&runtime.config.time_zone)?;
    let page = runtime.session.list_memos(&MemoQuery {
        search_text: None,
        filters: MemoFilters::default(),
        sort: MemoSort::default(),
    })?;
    model.items = page
        .items
        .into_iter()
        .map(|summary| memo_row(&summary, &runtime.config, as_of))
        .collect();
    model.clamp_selection();
    fill_preview(runtime, model)
}

fn load_tasks(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let tasks = runtime.session.list_tasks()?;
    model.items = tasks
        .into_iter()
        .map(|task| ListRow {
            id: format!("{}:{}", task.memo_id, task.line_index),
            header: String::new(),
            title: compact_inline(&task.text, 120),
            subtitle: path_date_label(&task.source_path),
            done: Some(task.done),
        })
        .collect();
    model.clamp_selection();
    model.preview = model.selected_id().map_or_else(String::new, str::to_owned);
    Ok(())
}

fn load_review(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let date = today(&runtime.config.time_zone)?;
    let candidates = runtime
        .session
        .review_candidates(&runtime.config.time_zone, date)?;
    model.items = candidates
        .into_iter()
        .map(|item| ListRow {
            id: item.memo_id,
            header: format!(" {} ", path_date_label(&item.source_path)),
            title: compact_inline(&item.body_preview, 80),
            subtitle: String::new(),
            done: None,
        })
        .collect();
    model.clamp_selection();
    fill_preview(runtime, model)
}

fn load_stats(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let as_of = today(&runtime.config.time_zone)?;
    let stats = runtime.session.statistics(&StatisticsSnapshot::new(
        runtime.config.time_zone.clone(),
        as_of,
    ))?;
    model.items = Vec::new();
    model.preview.clear();
    model.stats = Some(StatsView {
        zone: runtime.config.time_zone.clone(),
        as_of_year: as_of.year(),
        as_of_month: as_of.month(),
        as_of_day: as_of.day(),
        total_memos: stats.total_memos,
        total_words: stats.total_words,
        active_days: stats.active_days,
        current_streak: stats.current_streak,
        longest_streak: stats.longest_streak,
        this_week: stats.this_week_count,
        this_month: stats.this_month_count,
        this_year: stats.this_year_count,
        daily: stats
            .memo_count_by_date
            .into_iter()
            .map(|(date, count)| HeatPoint {
                year: date.year(),
                month: date.month(),
                day: date.day(),
                count,
            })
            .collect(),
    });
    Ok(())
}

fn load_attachments(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let observed = runtime.session.observe_attachments()?;
    model.items = observed
        .into_iter()
        .map(|item| ListRow {
            id: item.relative_path.clone(),
            header: String::new(),
            title: item.relative_path,
            subtitle: item.owner_key,
            done: None,
        })
        .collect();
    model.clamp_selection();
    model.preview = model.selected_id().map_or_else(String::new, |path| {
        crate::media::preview_media_line(
            path,
            crate::media::media_kind_for_path(path),
            runtime.graphics,
        )
    });
    Ok(())
}

fn load_trash(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let as_of = today(&runtime.config.time_zone)?;
    let page = runtime.session.list_memos(&MemoQuery {
        search_text: None,
        filters: MemoFilters {
            trash_only: true,
            ..MemoFilters::default()
        },
        sort: MemoSort::default(),
    })?;
    model.items = page
        .items
        .into_iter()
        .map(|summary| memo_row(&summary, &runtime.config, as_of))
        .collect();
    model.clamp_selection();
    fill_preview(runtime, model)
}

fn load_settings(runtime: &TuiRuntime, model: &mut AppModel) {
    let editor = runtime
        .config
        .editor
        .as_ref()
        .map_or_else(|| "(not configured)".to_owned(), |argv| argv.join(" "));
    model.settings_lines = vec![
        format!("workspace: {}", runtime.workspace.display()),
        format!("timezone: {}", runtime.config.time_zone),
        format!("editor: {editor}"),
        format!("player: {}", runtime.config.player.join(" ")),
        format!("graphics: {:?}", runtime.graphics),
        format!("device: {}", runtime.session.device_id()),
    ];
    model.items = Vec::new();
    model.preview = model.settings_lines.join("\n");
}

fn fill_preview(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        model.preview.clear();
        return Ok(());
    };
    let memo_id = MemoId::parse(id)?;
    model.preview = match runtime.session.get_memo(&memo_id)? {
        Some(memo) => memo.body,
        None => String::new(),
    };
    Ok(())
}

fn run_search(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let SearchSession::Open { query, mode, epoch } = &model.search else {
        return Ok(());
    };
    let request = SearchRequest {
        query_epoch: *epoch,
        mode: *mode,
        text: query.clone(),
        cursor: None,
        page_size: PageSize::new(64)?,
    };
    match runtime.session.search(&request)? {
        SearchOutcome::Discarded { .. } => Ok(()),
        SearchOutcome::Ready(page) => {
            let as_of = today(&runtime.config.time_zone)?;
            model.items = page
                .items
                .into_iter()
                .map(|hit| {
                    let mut row = memo_row(&hit.summary, &runtime.config, as_of);
                    row.id = hit.memo_id;
                    row
                })
                .collect();
            model.clamp_selection();
            fill_preview(runtime, model)
        }
    }
}

fn toggle_selected_task(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        return Ok(());
    };
    let Some((memo_id, line)) = id.split_once(':') else {
        return Ok(());
    };
    let line_index: u32 = line
        .parse()
        .map_err(|error| TuiError::config(format!("invalid task line: {error}")))?;
    let done = model
        .items
        .get(model.selected)
        .is_some_and(|row| row.done == Some(false));
    runtime.session.toggle_task(ToggleTaskRequest {
        operation_id: mint_operation_id()?,
        memo_id: MemoId::parse(memo_id)?,
        line_index,
        done,
    })?;
    reload_screen(runtime, model)
}

fn pin_selected(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        return Ok(());
    };
    runtime.session.pin_memo(PinMemoRequest {
        operation_id: mint_operation_id()?,
        memo_id: MemoId::parse(id)?,
        pinned: true,
        pinned_at_ms: None,
    })?;
    reload_screen(runtime, model)
}

fn delete_selected(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        return Ok(());
    };
    let memo_id = MemoId::parse(id)?;
    let fingerprint = runtime
        .session
        .get_memo(&memo_id)?
        .map(|memo| memo.file_fingerprint)
        .ok_or_else(|| TuiError::config("memo disappeared"))?;
    runtime.session.delete_memo(DeleteMemoRequest {
        operation_id: mint_operation_id()?,
        memo_id,
        expected_document_fingerprint: fingerprint,
        trashed_at_ms: None,
    })?;
    reload_screen(runtime, model)
}

fn restore_selected(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        return Ok(());
    };
    runtime.session.restore_memo(&RestoreMemoRequest {
        operation_id: mint_operation_id()?,
        memo_id: MemoId::parse(id)?,
    })?;
    reload_screen(runtime, model)
}

fn show_history(runtime: &TuiRuntime, model: &mut AppModel) -> Result<(), TuiError> {
    let Some(id) = model.selected_id() else {
        return Ok(());
    };
    let page = runtime
        .session
        .list_history(&MemoId::parse(id)?, None, 32)?;
    let lines = page
        .items
        .iter()
        .map(|rev| format!("r{} {}", rev.revision, rev.created_at_ms))
        .collect();
    model.overlay = Overlay::History { lines };
    Ok(())
}

/// Creates a memo from editor text through the shared write transaction.
///
/// # Errors
/// Session write failures.
pub fn create_from_editor(runtime: &TuiRuntime, content: &str) -> Result<String, TuiError> {
    let result = runtime.session.create_memo(CreateMemoRequest {
        operation_id: mint_operation_id()?,
        relative_path: None,
        time_token: None,
        content: content.to_owned(),
        expected_document_fingerprint: None,
        pinned: false,
        pending_promotes: Vec::new(),
        chronology_epoch_ms: None,
    })?;
    Ok(result.memo_id.as_str().to_owned())
}

/// Updates a memo from editor text using the current document fingerprint.
///
/// # Errors
/// Missing memo or write failures.
pub fn update_from_editor(
    runtime: &TuiRuntime,
    memo_id: &str,
    content: &str,
) -> Result<(), TuiError> {
    let id = MemoId::parse(memo_id)?;
    let current = runtime
        .session
        .get_memo(&id)?
        .ok_or_else(|| TuiError::config("memo disappeared during edit"))?;
    runtime.session.update_memo(UpdateMemoRequest {
        operation_id: mint_operation_id()?,
        memo_id: id,
        content: content.to_owned(),
        expected_document_fingerprint: current.file_fingerprint,
        pending_promotes: Vec::new(),
    })?;
    Ok(())
}

/// Reads the current source-document fingerprint for three-way editor checks.
///
/// # Errors
/// Projection failures.
pub fn document_fingerprint(
    runtime: &TuiRuntime,
    memo_id: Option<&str>,
) -> Result<Option<String>, TuiError> {
    let Some(id) = memo_id else {
        return Ok(None);
    };
    let memo = runtime.session.get_memo(&MemoId::parse(id)?)?;
    Ok(memo.map(|item| item.file_fingerprint))
}

/// Imports clipboard PNG bytes into the workspace via staged promote + memo update/create.
///
/// # Errors
/// Clipboard, staging, or write failures.
pub fn import_clipboard_png(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    png: &[u8],
) -> Result<String, TuiError> {
    let digest = ContentDigest::of_slice(png);
    let existing: Vec<String> = runtime
        .session
        .observe_attachments()?
        .into_iter()
        .map(|item| item.relative_path)
        .collect();
    let relative = unique_media_relative_path("pasted", "png", digest.as_str(), &existing);
    let temp = runtime
        .paths
        .state_dir
        .join(format!("{}.png", digest.as_str()));
    fs::write(&temp, png)?;
    let staged = stage_media(
        &runtime.paths.state_dir,
        MediaSource::StagedTemp { path: temp },
        "pasted.png",
    )?;
    let operation_id = mint_operation_id()?;
    let plan = PromotePlan {
        operation_id: operation_id.as_str().to_owned(),
        staged,
        final_relative_path: lomo_media::MediaRelativePath::parse(&relative)?,
    };
    let image_line = format!("![]({relative})");
    if let Some(id) = model
        .selected_id()
        .filter(|_| model.screen == Screen::Timeline)
    {
        let memo_id = MemoId::parse(id)?;
        let current = runtime
            .session
            .get_memo(&memo_id)?
            .ok_or_else(|| TuiError::config("memo disappeared"))?;
        let mut body = current.body;
        if !body.ends_with('\n') && !body.is_empty() {
            body.push('\n');
        }
        body.push_str(&image_line);
        runtime.session.update_memo(UpdateMemoRequest {
            operation_id,
            memo_id,
            content: body,
            expected_document_fingerprint: current.file_fingerprint,
            pending_promotes: vec![plan],
        })?;
    } else {
        runtime.session.create_memo(CreateMemoRequest {
            operation_id,
            relative_path: None,
            time_token: None,
            content: image_line,
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: vec![plan],
            chronology_epoch_ms: None,
        })?;
    }
    reload_screen(runtime, model)?;
    Ok(relative)
}

/// Reads PNG from a clipboard port and imports it.
///
/// # Errors
/// Clipboard or import failures.
pub fn import_from_clipboard<C: ImageClipboard>(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    clipboard: &C,
) -> Result<String, TuiError> {
    let png = clipboard.read_png().map_err(|error| match error {
        crate::media::ClipboardError::Unavailable { diagnostic }
        | crate::media::ClipboardError::Empty { diagnostic }
        | crate::media::ClipboardError::Corrupt { diagnostic } => {
            TuiError::Clipboard { diagnostic }
        }
    })?;
    import_clipboard_png(runtime, model, &png)
}

/// Plays the selected attachment with the configured external player.
///
/// # Errors
/// Missing selection or player failures.
pub fn play_selected<R: crate::editor::CommandRunner>(
    runtime: &TuiRuntime,
    model: &AppModel,
    runner: &R,
) -> Result<(), TuiError> {
    let Some(rel) = model.selected_id() else {
        return Ok(());
    };
    let path = runtime.workspace.join(rel);
    crate::media::play_audio(runner, &runtime.config.player, &path)
}

/// Mints a unique operation id for TUI-originated writes.
///
/// # Errors
/// CSPRNG or identifier parse failures.
pub fn mint_operation_id() -> Result<OperationId, TuiError> {
    let hex = generate_hex_token(8)?;
    Ok(OperationId::parse(&format!("op-{hex}"))?)
}

fn today(zone: &str) -> Result<CivilDate, TuiError> {
    Ok(local_date(now_ms()?, zone)?)
}

fn now_ms() -> Result<i64, TuiError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| TuiError::io(error.to_string()))?;
    i64::try_from(duration.as_millis()).map_err(|error| TuiError::io(error.to_string()))
}

fn memo_row(summary: &MemoSummary, config: &AppConfig, as_of: CivilDate) -> ListRow {
    ListRow {
        id: summary.memo_id.clone(),
        header: timeline_header(summary, config, as_of),
        title: compact_inline(&summary.body_preview, 80),
        subtitle: String::new(),
        done: None,
    }
}

fn timeline_header(summary: &MemoSummary, config: &AppConfig, as_of: CivilDate) -> String {
    match journal_stamp(summary.created_at_ms, &config.time_zone, config.date_format) {
        Ok(stamp) => format!(
            " {} {} ",
            date_label_from_filename(&stamp.filename, as_of.year()),
            stamp.time_token
        ),
        Err(_) => format!(
            " {} ",
            date_label_from_filename(&summary.source_path, as_of.year())
        ),
    }
}

fn date_label_from_filename(filename: &str, current_year: i32) -> String {
    let name = filename.rsplit('/').next().unwrap_or(filename);
    let stem = name.trim_end_matches(".md");
    let digits: String = stem.chars().filter(char::is_ascii_digit).collect();
    let Some(year_s) = digits.get(0..4) else {
        return stem.to_owned();
    };
    let Some(month) = digits.get(4..6) else {
        return stem.to_owned();
    };
    let Some(day) = digits.get(6..8) else {
        return stem.to_owned();
    };
    let Ok(year) = year_s.parse::<i32>() else {
        return stem.to_owned();
    };
    if year == current_year {
        format!("{month}-{day}")
    } else {
        format!("{year}-{month}-{day}")
    }
}

fn path_date_label(path: &str) -> String {
    date_label_from_filename(path, 0)
}

fn compact_inline(content: &str, max_chars: usize) -> String {
    let mut out = String::with_capacity(max_chars.saturating_add(3));
    let mut count = 0usize;
    let mut prev_space = false;
    let mut truncated = false;
    for ch in content.chars() {
        let normalized = if ch.is_whitespace() { ' ' } else { ch };
        if normalized == ' ' {
            if prev_space {
                continue;
            }
            prev_space = true;
        } else {
            prev_space = false;
        }
        if count >= max_chars {
            truncated = true;
            break;
        }
        out.push(normalized);
        count += 1;
    }
    while out.ends_with(' ') {
        out.pop();
    }
    if truncated {
        out.push_str("...");
    }
    out
}

impl From<lomo_application::calendar::CalendarError> for TuiError {
    fn from(error: lomo_application::calendar::CalendarError) -> Self {
        Self::config(error.to_string())
    }
}

/// Absolute workspace path for tests that inspect Markdown files.
#[must_use]
pub fn workspace_path(runtime: &TuiRuntime) -> &Path {
    &runtime.workspace
}
