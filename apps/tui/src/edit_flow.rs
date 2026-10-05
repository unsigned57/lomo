//! The external editor owns its terminal; durable commits return to the application worker.
use crate::{
    drafts::{remove_draft, write_draft},
    editor::{CommandRunner, draft_path, resolve_editor, run_editor},
    effects::{EditTarget, EditedMemo, Effect, RuntimeMessage},
    error::TuiError,
    input::TextBuffer,
    model::{AppModel, InputMode, Notice, PendingKind, Req, SaveState, Severity},
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
    let argv = resolve_editor(runtime.config().editor.as_deref(), visual, editor_env)?;
    let operation = mint_operation_id()?;
    // Config edits draft as `.toml` so editors pick the right syntax mode.
    let path = match target {
        EditTarget::Config => {
            draft_path(&runtime.paths.drafts_dir, operation.as_str()).with_extension("toml")
        }
        EditTarget::Capture | EditTarget::Memo { .. } => {
            draft_path(&runtime.paths.drafts_dir, operation.as_str())
        }
    };
    let initial = match target {
        EditTarget::Capture => model.draft.text.text().to_owned(),
        EditTarget::Config => {
            // The draft holds the real file's bytes — never a re-render — so a
            // cancelled edit loses nothing and comments survive a save.
            let file = crate::config::config_file(&runtime.paths);
            std::fs::read_to_string(&file).map_err(|error| {
                TuiError::config(format!("cannot read {}: {error}", file.display()))
            })?
        }
        EditTarget::Memo {
            id,
            fingerprint,
            body,
        } => {
            let evidence = serde_json::json!({"memo_id": id.as_str(), "baseline": fingerprint,
                "operation_id": operation.as_str(), "workspace": runtime.workspace});
            write_draft(&path.with_extension("json"), &evidence.to_string())?;
            body.clone()
        }
    };
    let draft = match run_editor(runner, &argv, &path, &initial) {
        Ok(draft) => draft,
        Err(error) => {
            if matches!(target, EditTarget::Capture) {
                // The capture path restores the composer first — presenting
                // after `set_capture` lets `present` see `Compose` owns the
                // focus, so the notice registers with an unread badge instead
                // of a modal the mode switch would instantly destroy (I9).
                let content = std::fs::read_to_string(&path)?;
                set_capture(model, content);
                let revision = model.draft.revision;
                let req = model.request(PendingKind::DraftPersist { revision });
                model.present(Notice::modal(
                    Severity::Warn,
                    crate::i18n::UiStrings::detect()
                        .text("Draft retained", "草稿已保留")
                        .to_owned(),
                    vec![error.to_string(), path.display().to_string()],
                ));
                return Ok(Some(Effect::PersistDraft {
                    req,
                    revision,
                    content: model.draft.text.text().to_owned(),
                }));
            }
            model.present(Notice::modal(
                Severity::Warn,
                crate::i18n::UiStrings::detect()
                    .text("Draft retained", "草稿已保留")
                    .to_owned(),
                vec![error.to_string(), path.display().to_string()],
            ));
            return Ok(None);
        }
    };
    match target {
        EditTarget::Capture => {
            set_capture(model, draft.clone());
            let revision = model.draft.revision;
            let req = model.request(PendingKind::DraftPersist { revision });
            Ok(Some(Effect::CaptureEdited {
                req,
                revision,
                content: draft,
                draft_path: path,
            }))
        }
        EditTarget::Config => finish_config_edit(runtime, model, &path, &initial, &draft),
        EditTarget::Memo {
            id,
            body,
            fingerprint,
        } => {
            if &draft == body {
                remove_edit(&path)?;
                return Ok(None);
            }
            let req = model.request(PendingKind::Mutation);
            Ok(Some(Effect::CommitEdit {
                req,
                edit: EditedMemo {
                    operation_id: operation,
                    id: id.clone(),
                    fingerprint: fingerprint.clone(),
                    content: draft,
                    draft_path: path,
                },
            }))
        }
    }
}

/// Lands a config draft only after the strict parser accepts it: a valid
/// edit atomically replaces `config.toml` and queues a reload; an invalid one
/// keeps the draft file and its diagnostic, never clobbering a known-good
/// config.
///
/// The draft installs only onto the exact bytes it was seeded from: a file
/// that changed (or became unreadable) while `$EDITOR` was open is a
/// baseline drift — the install refuses, retains the draft, and says why,
/// instead of last-writer-wins over a change the user never saw.
fn finish_config_edit(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    path: &std::path::Path,
    initial: &str,
    draft: &str,
) -> Result<Option<Effect>, TuiError> {
    let file = crate::config::config_file(&runtime.paths);
    if draft == initial {
        remove_draft(path)?;
        return Ok(None);
    }
    match crate::config::parse_config_toml(draft, None, runtime.paths.home_dir.as_deref()) {
        Ok(_) => match std::fs::read_to_string(&file) {
            Ok(current) if current == initial => {
                crate::drafts::atomic_write(&file, draft.as_bytes())?;
                remove_draft(path)?;
                let req = model.request(PendingKind::ConfigReload);
                Ok(Some(Effect::ReloadConfig { req }))
            }
            outcome => {
                let strings = crate::i18n::UiStrings::detect();
                let reason = match outcome {
                    Ok(_) => strings
                        .text(
                            "config.toml changed on disk while the editor was open",
                            "编辑器打开期间 config.toml 已被修改",
                        )
                        .to_owned(),
                    Err(error) => format!(
                        "{}{error}",
                        strings.text(
                            "could not re-read config.toml: ",
                            "无法重新读取 config.toml：",
                        )
                    ),
                };
                // Same retained-draft rule as an invalid draft: the modal
                // names the file refused and where the user's edit survives.
                model.present(Notice::modal(
                    Severity::Warn,
                    strings
                        .text(
                            "Config changed on disk — draft retained",
                            "配置已在磁盘上变更——草稿已保留",
                        )
                        .to_owned(),
                    vec![reason, path.display().to_string()],
                ));
                Ok(None)
            }
        },
        Err(error) => {
            // The diagnostic plus the retained draft's path is content the
            // user must read — a modal, not a toast (I9).
            model.present(Notice::modal(
                Severity::Warn,
                crate::i18n::UiStrings::detect()
                    .text("Invalid config — draft retained", "配置无效——草稿已保留")
                    .to_owned(),
                vec![error.to_string(), path.display().to_string()],
            ));
            Ok(None)
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
pub fn commit_edit(
    runtime: &TuiRuntime,
    req: Req,
    edit: &EditedMemo,
) -> Result<RuntimeMessage, TuiError> {
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
            req,
            title: crate::i18n::UiStrings::detect()
                .text("Draft retained", "草稿已保留")
                .to_owned(),
            lines: vec![error.to_string(), edit.draft_path.display().to_string()],
        });
    }
    remove_edit(&edit.draft_path)?;
    Ok(RuntimeMessage::Changed {
        req,
        status: crate::i18n::UiStrings::detect()
            .text("Saved", "已保存")
            .to_owned(),
    })
}

fn remove_edit(path: &std::path::Path) -> Result<(), TuiError> {
    remove_draft(path)?;
    remove_draft(&path.with_extension("json"))
}
