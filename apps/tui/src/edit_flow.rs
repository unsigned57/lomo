use lomo_workspace::MemoId;

use crate::drafts::{remove_draft, write_conflict_evidence, write_draft};
use crate::editor::{
    CommandRunner, CommitDecision, EditBaseline, EditKind, decide_commit, draft_path,
    resolve_editor, run_editor,
};
use crate::error::TuiError;
use crate::model::{AppModel, Overlay};
use crate::ops::{
    TuiRuntime, create_from_editor, document_fingerprint, mint_operation_id, reload_screen,
    update_from_editor,
};

/// Inputs for one external-editor round trip.
pub struct EditRequest<'a> {
    pub kind: EditKind,
    pub initial: &'a str,
    pub baseline: Option<String>,
    pub visual: Option<&'a str>,
    pub editor_env: Option<&'a str>,
}

/// Runs the editor and commits through `lomo-application`. Caller must suspend/restore the TTY.
///
/// # Errors
/// Editor, IO, or session failures. Missing editor is recorded on the model, not panicked.
pub fn complete_edit<R: CommandRunner>(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    runner: &R,
    request: EditRequest<'_>,
) -> Result<(), TuiError> {
    let argv = match resolve_editor(
        runtime.config.editor.as_deref(),
        request.visual,
        request.editor_env,
    ) {
        Ok(argv) => argv,
        Err(error) => {
            model.set_status(&error.to_string());
            return Ok(());
        }
    };
    let op = mint_operation_id()?;
    let path = draft_path(&runtime.paths.drafts_dir, op.as_str());
    let draft = match run_editor(runner, &argv, &path, request.initial) {
        Ok(text) => text,
        Err(error) => {
            model.set_status(&error.to_string());
            return Ok(());
        }
    };
    let memo_id = match &request.kind {
        EditKind::Create => None,
        EditKind::Update { memo_id } => Some(memo_id.as_str()),
    };
    let disk = document_fingerprint(runtime, memo_id)?;
    apply_decision(
        runtime,
        model,
        request,
        &path,
        op.as_str(),
        &draft,
        disk.as_deref(),
    )
}

/// Loads the selected memo and runs [`complete_edit`] for update.
///
/// # Errors
/// Session read or editor failures.
pub fn edit_selection<R: CommandRunner>(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    runner: &R,
    visual: Option<&str>,
    editor_env: Option<&str>,
) -> Result<(), TuiError> {
    let Some(id) = model.selected_id().map(ToOwned::to_owned) else {
        model.set_status("no memo selected");
        return Ok(());
    };
    let Some(memo) = runtime.session.get_memo(&MemoId::parse(&id)?)? else {
        model.set_status("memo disappeared");
        return Ok(());
    };
    complete_edit(
        runtime,
        model,
        runner,
        EditRequest {
            kind: EditKind::Update { memo_id: id },
            initial: &memo.body,
            baseline: Some(memo.file_fingerprint),
            visual,
            editor_env,
        },
    )
}

fn apply_decision(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    request: EditRequest<'_>,
    path: &std::path::Path,
    operation_id: &str,
    draft: &str,
    disk: Option<&str>,
) -> Result<(), TuiError> {
    match decide_commit(
        &request.kind,
        request.initial,
        draft,
        &EditBaseline {
            fingerprint: request.baseline,
        },
        disk,
    ) {
        CommitDecision::CancelledEmpty => {
            remove_draft(path)?;
            model.set_status("empty create cancelled");
        }
        CommitDecision::Unchanged => {
            remove_draft(path)?;
            model.set_status("unchanged");
        }
        CommitDecision::Submit { content } => {
            match request.kind {
                EditKind::Create => {
                    create_from_editor(runtime, &content)?;
                }
                EditKind::Update { memo_id } => {
                    update_from_editor(runtime, &memo_id, &content)?;
                }
            }
            remove_draft(path)?;
            model.set_status("saved");
            reload_screen(runtime, model)?;
        }
        CommitDecision::Conflict {
            draft_content,
            baseline,
            disk,
        } => {
            write_draft(path, &draft_content)?;
            write_conflict_evidence(
                &runtime.paths.drafts_dir,
                operation_id,
                &baseline,
                &disk,
                &draft_content,
            )?;
            model.overlay = Overlay::Alert {
                title: "Edit conflict".to_owned(),
                body: format!("draft kept at {}", path.display()),
            };
        }
    }
    Ok(())
}
