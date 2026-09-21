//! Mutations are performed only by the shared application transaction API.
use crate::{
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    media::{ImageClipboard, SystemClipboard},
    ops::{TuiRuntime, mint_operation_id},
};
use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, PinMemoRequest, PinPolicy, RestoreMemoRequest,
    ToggleTaskRequest,
};
use lomo_core::RelativeWorkspacePath;
use lomo_media::{ContentDigest, MediaSource, PromotePlan, stage_media};
use std::fs;

/// # Errors
/// Application, media, clipboard and process failures.
pub fn execute(runtime: &TuiRuntime, effect: &Effect) -> Result<RuntimeMessage, TuiError> {
    match effect {
        Effect::ToggleTask(task) => {
            runtime.session.toggle_task(ToggleTaskRequest {
                operation_id: mint_operation_id()?,
                memo_id: task.memo_id.clone(),
                line_index: task.line,
                done: !task.done,
            })?;
        }
        Effect::Pin { id, pinned } => {
            let pin = if *pinned {
                PinPolicy::Pinned { at_ms: None }
            } else {
                PinPolicy::Unpinned
            };
            runtime.session.pin_memo(PinMemoRequest::new(
                mint_operation_id()?,
                id.clone(),
                pin,
            )?)?;
        }
        Effect::Delete { id, fingerprint } => {
            runtime.session.delete_memo(DeleteMemoRequest {
                operation_id: mint_operation_id()?,
                memo_id: id.clone(),
                expected_document_fingerprint: fingerprint.clone(),
                trashed_at_ms: None,
            })?;
        }
        Effect::Restore(id) => {
            runtime.session.restore_memo(&RestoreMemoRequest {
                operation_id: mint_operation_id()?,
                memo_id: id.clone(),
            })?;
        }
        Effect::History(id) => {
            let page = runtime.session.list_history(id, None, 32)?;
            return Ok(RuntimeMessage::Message {
                title: crate::i18n::UiStrings::detect().title_history,
                lines: page
                    .items
                    .into_iter()
                    .map(|item| {
                        format!(
                            "r{} · {}\n{}",
                            item.revision, item.created_at_ms, item.content
                        )
                    })
                    .collect(),
            });
        }
        Effect::ImportClipboard => {
            import_from_clipboard(runtime, &SystemClipboard)?;
        }
        Effect::OpenAttachment(path) => {
            open_attachment(runtime, path, &crate::editor::StdCommandRunner)?;
            return Ok(RuntimeMessage::Message {
                title: crate::i18n::UiStrings::detect()
                    .text("Attachment opened", "附件已打开")
                    .to_owned(),
                lines: vec![path.as_str().to_owned()],
            });
        }
        Effect::Query(_)
        | Effect::Navigate { .. }
        | Effect::Bodies { .. }
        | Effect::ReadMemo { .. }
        | Effect::PersistDraft { .. }
        | Effect::CommitDraft { .. }
        | Effect::LoadImage(_)
        | Effect::Edit(_)
        | Effect::CommitEdit(_)
        | Effect::CaptureEdited { .. }
        | Effect::Tags
        | Effect::Date { .. }
        | Effect::Refresh
        | Effect::Quit => {
            return Err(TuiError::config(
                "non-mutation effect sent to mutation executor",
            ));
        }
    }
    Ok(RuntimeMessage::Changed(
        crate::i18n::UiStrings::detect()
            .text("Saved", "已保存")
            .to_owned(),
    ))
}
/// Imports clipboard bytes as a new memo through staged media promotion.
/// # Errors
/// Clipboard backend, staging or transaction failures.
pub fn import_from_clipboard<C: ImageClipboard>(
    runtime: &TuiRuntime,
    clipboard: &C,
) -> Result<String, TuiError> {
    let png = clipboard.read_png().map_err(|error| match error {
        crate::media::ClipboardError::Unavailable { diagnostic }
        | crate::media::ClipboardError::Empty { diagnostic }
        | crate::media::ClipboardError::Corrupt { diagnostic } => {
            TuiError::Clipboard { diagnostic }
        }
    })?;
    import_clipboard_png(runtime, &png)
}
/// # Errors
/// PNG staging and durable memo publication failures.
pub fn import_clipboard_png(runtime: &TuiRuntime, png: &[u8]) -> Result<String, TuiError> {
    let digest = ContentDigest::of_slice(png);
    let existing = runtime
        .session
        .observe_attachments()?
        .into_iter()
        .map(|item| item.relative_path)
        .collect::<Vec<_>>();
    let relative =
        crate::media::unique_media_relative_path("pasted", "png", digest.as_str(), &existing);
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
    runtime.session.create_memo(CreateMemoRequest {
        operation_id,
        relative_path: None,
        time_token: None,
        content: format!("![]({relative})"),
        expected_document_fingerprint: None,
        pinned: false,
        pending_promotes: vec![plan],
        chronology_epoch_ms: None,
    })?;
    Ok(relative)
}
/// Reads under the bound workspace capability, then opens a private snapshot.
/// # Errors
/// A missing, escaped or unreadable attachment, or a failed external player.
pub fn open_attachment<R: crate::editor::CommandRunner>(
    runtime: &TuiRuntime,
    path: &RelativeWorkspacePath,
    runner: &R,
) -> Result<(), TuiError> {
    let bytes = lomo_application::rebuild::read_workspace_file(
        &runtime.session_config,
        &runtime.executor,
        path,
    )?;
    let artifact =
        lomo_application::exchange_io::stage_content(&runtime.paths.exchange_dir, &bytes)?;
    let extension = std::path::Path::new(path.as_str())
        .extension()
        .and_then(|extension| extension.to_str())
        .ok_or_else(|| TuiError::config("attachment has no file extension"))?;
    let staged = runtime.paths.cache_dir.join(format!(
        "attachment-{}.{}",
        artifact.digest().as_str(),
        extension
    ));
    fs::write(&staged, bytes)?;
    crate::media::play_audio(runner, &runtime.config.player, &staged)
}
