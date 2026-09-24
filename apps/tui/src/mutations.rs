//! Mutations are performed only by the shared application transaction API.
use crate::{
    effects::{Effect, RuntimeMessage},
    error::TuiError,
    media::{ImageClipboard, SystemClipboard},
    ops::{TuiRuntime, mint_operation_id},
};
use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, PermanentDeleteRequest, PinMemoRequest, PinPolicy,
    RestoreMemoRequest, RestoreRevisionRequest, ToggleTaskRequest,
    calendar::{DateFormat, journal_stamp},
};
use lomo_core::RelativeWorkspacePath;
use lomo_media::{ContentDigest, MediaSource, PromotePlan, stage_media};
use std::fs;

/// Revisions newest first, each with its local creation time and a one-line preview.
fn history_rows(
    runtime: &TuiRuntime,
    id: &lomo_workspace::MemoId,
) -> Result<RuntimeMessage, TuiError> {
    let page = runtime.session.list_history(id, None, 32)?;
    let mut revisions = Vec::new();
    for item in page.items {
        // Snapshots written before timestamps were recorded carry no creation time.
        let stamp = if item.created_at_ms > 0 {
            let stamp = journal_stamp(
                item.created_at_ms,
                &runtime.config.time_zone,
                DateFormat::YyyyMmDdHyphen,
            )?;
            format!(
                "{} {}",
                stamp.filename.trim_end_matches(".md"),
                stamp.time_token
            )
        } else {
            String::new()
        };
        revisions.push(crate::model::RevisionRow {
            revision: item.revision,
            stamp,
            preview: crate::menu::preview(&item.content),
        });
    }
    Ok(RuntimeMessage::History {
        id: id.clone(),
        revisions,
    })
}

/// Drops every trashed memo. Each memo is its own transaction; the first failure stops the
/// sweep and reports how many were removed so the trash view shows the true remainder.
fn empty_trash(runtime: &TuiRuntime) -> Result<RuntimeMessage, TuiError> {
    let s = crate::i18n::UiStrings::detect();
    let query = lomo_application::MemoQuery {
        search_text: None,
        filters: lomo_application::MemoFilters {
            trash_only: true,
            ..lomo_application::MemoFilters::default()
        },
        sort: lomo_application::MemoSort::default(),
    };
    let mut removed = 0_u64;
    loop {
        let page =
            runtime
                .session
                .query_memos_page(&query, None, None, lomo_core::PageSize::new(64)?)?;
        if page.items.is_empty() {
            break;
        }
        for summary in page.items {
            runtime
                .session
                .permanently_delete_memo(&PermanentDeleteRequest {
                    operation_id: mint_operation_id()?,
                    memo_id: lomo_workspace::MemoId::parse(&summary.memo_id)?,
                })
                .map_err(|error| {
                    TuiError::config(format!(
                        "{} {removed} · {error}",
                        s.text("Emptying the trash stopped after", "清空回收站中断，已删除")
                    ))
                })?;
            removed += 1;
        }
    }
    Ok(RuntimeMessage::Changed(format!(
        "{} {removed}",
        s.text("Trash emptied · removed", "回收站已清空 · 已删除")
    )))
}

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
        Effect::History(id) => return history_rows(runtime, id),
        Effect::DeleteForever(id) => {
            runtime
                .session
                .permanently_delete_memo(&PermanentDeleteRequest {
                    operation_id: mint_operation_id()?,
                    memo_id: id.clone(),
                })?;
            return Ok(RuntimeMessage::Changed(
                crate::i18n::UiStrings::detect()
                    .text("Deleted permanently", "已永久删除")
                    .to_owned(),
            ));
        }
        Effect::EmptyTrash => return empty_trash(runtime),
        Effect::RestoreRevision { id, revision } => {
            runtime.session.restore_revision(RestoreRevisionRequest {
                operation_id: mint_operation_id()?,
                memo_id: id.clone(),
                revision: *revision,
            })?;
            return Ok(RuntimeMessage::Changed(format!(
                "{} r{revision}",
                crate::i18n::UiStrings::detect().text("Restored revision", "已恢复版本")
            )));
        }
        Effect::ImportClipboard => {
            import_from_clipboard(runtime, &SystemClipboard)?;
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
        | Effect::OpenAttachment(_)
        | Effect::Reconcile
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
/// Reads under the bound workspace capability and stages a private snapshot for
/// the external player. Returns the staged path for a managed spawn.
/// # Errors
/// A missing, escaped, extensionless or unreadable attachment.
pub fn stage_attachment(
    runtime: &TuiRuntime,
    path: &RelativeWorkspacePath,
) -> Result<std::path::PathBuf, TuiError> {
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
    Ok(staged)
}
