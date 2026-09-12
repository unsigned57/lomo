//! The external editor owns its terminal; durable commits return to the application worker.
use crate::{
    drafts::{remove_draft, write_draft},
    editor::{CommandRunner, draft_path, resolve_editor, run_editor},
    effects::{EditTarget, EditedMemo, Effect, RuntimeMessage},
    error::TuiError,
    input::TextBuffer,
    model::{AppModel, InputMode, SaveState},
    ops::{TuiRuntime, mint_operation_id},
};

/// # Errors
/// Tool failures and conflicts retain the exact private draft and original version baseline.
pub fn complete_edit<R: CommandRunner>(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    runner: &R,
    target: &EditTarget,
    visual: Option<&str>,
    editor_env: Option<&str>,
) -> Result<Option<Effect>, TuiError> {
    let argv = resolve_editor(runtime.config.editor.as_deref(), visual, editor_env)?;
    let operation = mint_operation_id()?;
    let path = draft_path(&runtime.paths.drafts_dir, operation.as_str());
    let initial = match target {
        EditTarget::Capture => model.draft.text.text(),
        EditTarget::Memo {
            id,
            fingerprint,
            body,
        } => {
            let evidence = serde_json::json!({"memo_id": id.as_str(), "baseline": fingerprint,
                "operation_id": operation.as_str(), "workspace": runtime.workspace});
            write_draft(&path.with_extension("json"), &evidence.to_string())?;
            body
        }
    };
    let draft = match run_editor(runner, &argv, &path, initial) {
        Ok(draft) => draft,
        Err(error) => {
            model.present_notice(
                crate::i18n::UiStrings::detect()
                    .text("Draft retained", "草稿已保留")
                    .to_owned(),
                vec![error.to_string(), path.display().to_string()],
            );
            if matches!(target, EditTarget::Capture) {
                let content = std::fs::read_to_string(&path)?;
                set_capture(model, content);
                return Ok(Some(Effect::PersistDraft {
                    revision: model.draft.revision,
                    content: model.draft.text.text().to_owned(),
                }));
            }
            return Ok(None);
        }
    };
    match target {
        EditTarget::Capture => {
            set_capture(model, draft.clone());
            Ok(Some(Effect::CaptureEdited {
                revision: model.draft.revision,
                content: draft,
                draft_path: path,
            }))
        }
        EditTarget::Memo {
            id,
            body,
            fingerprint,
        } => {
            if &draft == body {
                remove_edit(&path)?;
                return Ok(None);
            }
            Ok(Some(Effect::CommitEdit(EditedMemo {
                operation_id: operation,
                id: id.clone(),
                fingerprint: fingerprint.clone(),
                content: draft,
                draft_path: path,
            })))
        }
    }
}

fn set_capture(model: &mut AppModel, content: String) {
    model.draft.text = TextBuffer::new(content);
    model.draft.revision = model.draft.revision.saturating_add(1);
    model.draft.save = SaveState::Editing;
    model.input = InputMode::Compose;
}

/// # Errors
/// Private evidence cleanup failures are visible; application conflicts retain the draft.
pub fn commit_edit(runtime: &TuiRuntime, edit: &EditedMemo) -> Result<RuntimeMessage, TuiError> {
    let result = runtime
        .session
        .update_memo(lomo_application::UpdateMemoRequest {
            operation_id: edit.operation_id.clone(),
            memo_id: edit.id.clone(),
            content: edit.content.clone(),
            expected_document_fingerprint: edit.fingerprint.clone(),
            pending_promotes: Vec::new(),
        });
    if let Err(error) = result {
        return Ok(RuntimeMessage::Message {
            title: crate::i18n::UiStrings::detect()
                .text("Draft retained", "草稿已保留")
                .to_owned(),
            lines: vec![error.to_string(), edit.draft_path.display().to_string()],
        });
    }
    remove_edit(&edit.draft_path)?;
    Ok(RuntimeMessage::Changed(
        crate::i18n::UiStrings::detect()
            .text("Saved", "已保存")
            .to_owned(),
    ))
}

fn remove_edit(path: &std::path::Path) -> Result<(), TuiError> {
    remove_draft(path)?;
    remove_draft(&path.with_extension("json"))
}
