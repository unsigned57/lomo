//! Mutations are performed only by the shared application transaction API.
use crate::{
    effects::{Effect, MutationOutcome, RuntimeMessage},
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
use lomo_media::{
    ArtifactId, ContentDigest, MediaRelativePath, MediaSource, PromotePlan, StageLease,
    StageLedger, StageOwnerKind, stage_directory_of, stage_media,
};
use std::fs;

/// Revisions newest first, each with its local creation time and a one-line preview.
fn history_rows(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    id: &lomo_workspace::MemoId,
) -> Result<RuntimeMessage, TuiError> {
    let page = runtime.session.list_history(id, None, 32)?;
    let mut revisions = Vec::new();
    for item in page.items {
        // Snapshots written before timestamps were recorded carry no creation time.
        let stamp = if item.created_at_ms > 0 {
            let stamp = journal_stamp(
                item.created_at_ms,
                &runtime.config().time_zone,
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
        req,
        id: id.clone(),
        revisions,
    })
}

/// Drops every trashed memo. Each memo is its own transaction; the first failure stops the
/// sweep and reports how many were removed so the trash view shows the true remainder.
/// The pending token is checked between pages: a revoked sweep stops doing
/// work instead of running to completion and discarding its result (I3).
fn empty_trash(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    token: &crate::model::CancelToken,
) -> Result<RuntimeMessage, TuiError> {
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
        if token.is_cancelled() {
            return Err(TuiError::config(format!(
                "{} {removed}",
                s.text(
                    "Emptying the trash cancelled after",
                    "清空回收站已取消，已删除"
                )
            )));
        }
        let page =
            runtime
                .session
                .query_memos_page(&query, None, None, lomo_core::PageSize::new(64)?)?;
        if page.items.is_empty() {
            break;
        }
        for summary in page.items {
            if token.is_cancelled() {
                return Err(TuiError::config(format!(
                    "{} {removed}",
                    s.text(
                        "Emptying the trash cancelled after",
                        "清空回收站已取消，已删除"
                    )
                )));
            }
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
    Ok(RuntimeMessage::Mutated {
        req,
        outcome: MutationOutcome::TrashEmptied { removed },
    })
}

/// # Errors
/// Application, media, clipboard and process failures.
pub fn execute(
    runtime: &TuiRuntime,
    effect: &Effect,
    token: &crate::model::CancelToken,
) -> Result<RuntimeMessage, TuiError> {
    let req = effect.req();
    // Every arm names the store outcome it just committed — the receipt is a
    // typed `Mutated`, so the status line says "Pinned", never a generic
    // "Saved" that could attach to any effect (I2).
    let outcome = match effect {
        Effect::ToggleTask { task, .. } => {
            runtime.session.toggle_task(ToggleTaskRequest {
                operation_id: mint_operation_id()?,
                memo_id: task.memo_id.clone(),
                line_index: task.line,
                done: !task.done,
            })?;
            MutationOutcome::TaskToggled { done: !task.done }
        }
        Effect::Pin { id, pinned, .. } => {
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
            if *pinned {
                MutationOutcome::Pinned
            } else {
                MutationOutcome::Unpinned
            }
        }
        Effect::Delete {
            id, fingerprint, ..
        } => {
            runtime.session.delete_memo(DeleteMemoRequest {
                operation_id: mint_operation_id()?,
                memo_id: id.clone(),
                expected_document_fingerprint: fingerprint.clone(),
                trashed_at_ms: None,
            })?;
            MutationOutcome::Trashed
        }
        Effect::Restore { id, .. } => {
            runtime.session.restore_memo(&RestoreMemoRequest {
                operation_id: mint_operation_id()?,
                memo_id: id.clone(),
            })?;
            MutationOutcome::Restored
        }
        Effect::History { req, id } => return history_rows(runtime, *req, id),
        Effect::DeleteForever { req, id } => return delete_forever(runtime, *req, id),
        Effect::EmptyTrash { req } => return empty_trash(runtime, *req, token),
        Effect::RestoreRevision { req, id, revision } => {
            return restore_revision(runtime, *req, id, *revision);
        }
        Effect::ImportClipboard { .. } => {
            import_from_clipboard(runtime, &SystemClipboard)?;
            MutationOutcome::ClipboardImported
        }
        Effect::MediaSweep { .. }
        | Effect::Query(_)
        | Effect::Navigate { .. }
        | Effect::Bodies { .. }
        | Effect::ReadMemo { .. }
        | Effect::PersistDraft { .. }
        | Effect::CommitDraft { .. }
        | Effect::LoadImage { .. }
        | Effect::Edit { .. }
        | Effect::CommitEdit { .. }
        | Effect::CaptureEdited { .. }
        | Effect::Tags { .. }
        | Effect::Date { .. }
        | Effect::OpenAttachment { .. }
        | Effect::Reconcile { .. }
        | Effect::Bootstrap { .. }
        | Effect::SetupConfirmed { .. }
        | Effect::ReloadConfig { .. }
        | Effect::SaveSetting { .. }
        | Effect::Refresh { .. }
        | Effect::Quit { .. } => {
            return Err(TuiError::config(
                "non-mutation effect sent to mutation executor",
            ));
        }
    };
    Ok(RuntimeMessage::Mutated { req, outcome })
}

/// Permanent deletion answers with the action's own receipt line.
fn delete_forever(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    id: &lomo_workspace::MemoId,
) -> Result<RuntimeMessage, TuiError> {
    runtime
        .session
        .permanently_delete_memo(&PermanentDeleteRequest {
            operation_id: mint_operation_id()?,
            memo_id: id.clone(),
        })?;
    Ok(RuntimeMessage::Mutated {
        req,
        outcome: MutationOutcome::DeletedForever,
    })
}

/// A revision restore reports the revision it landed, not just "saved".
fn restore_revision(
    runtime: &TuiRuntime,
    req: crate::model::Req,
    id: &lomo_workspace::MemoId,
    revision: u64,
) -> Result<RuntimeMessage, TuiError> {
    runtime.session.restore_revision(RestoreRevisionRequest {
        operation_id: mint_operation_id()?,
        memo_id: id.clone(),
        revision,
    })?;
    Ok(RuntimeMessage::Mutated {
        req,
        outcome: MutationOutcome::RevisionRestored { revision },
    })
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
    // Clipboard import is a one-shot draft: the minted operation owns the staged
    // artifact as a Draft lease from staging until the claim transfers to the
    // pending operation, so bytes stay ledger-owned between verify and commit.
    let media_root = &runtime.session_config.media_stage_root;
    let digest = ContentDigest::of_slice(png);
    let temp = runtime
        .paths
        .state_dir
        .join(format!("{}.png", digest.as_str()));
    fs::write(&temp, png)?;
    let staged = stage_media(
        media_root,
        MediaSource::StagedTemp { path: temp },
        "pasted.png",
    )?;
    let stage_dir = stage_directory_of(&staged.staging_path)?;
    let operation_id = mint_operation_id()?;
    let mut ledger = StageLedger::load(&stage_dir)?;
    let draft = StageLease::new(
        ArtifactId::of_digest(&staged.digest),
        StageOwnerKind::Draft,
        operation_id.as_str(),
    )?;
    // The ledger resolves the destination against durable claims and real workspace
    // files; the returned record carries the collision-free committed path.
    let record = ledger.record(Some(&runtime.workspace), &staged, draft.clone())?;
    let relative = record.suggested_final_relative_path.clone();
    ledger.transfer(
        &stage_dir,
        &draft,
        StageLease::new(
            record.artifact_id.clone(),
            StageOwnerKind::PendingOperation,
            operation_id.as_str(),
        )?,
    )?;
    let plan = PromotePlan {
        operation_id: operation_id.as_str().to_owned(),
        staged: record.to_staged(),
        final_relative_path: MediaRelativePath::parse(&relative)?,
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
    // The digest is computed in memory — exchange staging is a durable
    // workspace-transfer channel, not a digest calculator (D-05).
    let digest = ContentDigest::of_slice(&bytes);
    let extension = std::path::Path::new(path.as_str())
        .extension()
        .and_then(|extension| extension.to_str())
        .ok_or_else(|| TuiError::config("attachment has no file extension"))?;
    let staged =
        runtime
            .paths
            .cache_dir
            .join(format!("attachment-{}.{}", digest.as_str(), extension));
    fs::write(&staged, bytes)?;
    Ok(staged)
}
