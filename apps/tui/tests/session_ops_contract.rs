//! Behavior Contract
//! Capability: memo mutations and external editor commits stay within application transactions.
//! Scenarios: pin/delete/restore/history, permanent delete and empty trash, revision restore,
//! deferred edit commit, concurrent file changes and capture editing.
//! Observable outcomes: Markdown bytes, retained drafts, original fingerprint rejection and unchanged input context.
//! TDD proof: editor commits block the foreground return; capture editing submits without Ctrl+S.
//! Excludes: a real terminal, clipboard backend and network sync.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, command, feed, run_effect};
    use lomo_tui::{
        edit_flow::complete_edit,
        editor::CommandRunner,
        effects::{EditTarget, Effect},
        event::Command,
        input::TextBuffer,
        model::{AppModel, InputMode, SaveState, Screen},
        ops::{bootstrap_model, execute},
    };
    use std::{fs, os::unix::process::ExitStatusExt, process::ExitStatus};

    struct Editor {
        body: &'static str,
        concurrent_file: Option<std::path::PathBuf>,
    }
    impl CommandRunner for Editor {
        fn run_foreground(
            &self,
            _program: &str,
            args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            if let Some(path) = &self.concurrent_file {
                fs::write(path, "- 10:00:00\nconcurrent change\n")?;
            }
            fs::write(
                args.last().ok_or_else(|| std::io::Error::other("draft"))?,
                self.body,
            )?;
            Ok(ExitStatus::from_raw(0))
        }

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn lomo_tui::editor::ManagedChild>, std::io::Error> {
            Err(std::io::Error::other("the editor fake never spawns"))
        }
    }

    fn edit_target(
        fixture: &RuntimeFixture,
        model: &AppModel,
    ) -> Result<EditTarget, Box<dyn std::error::Error>> {
        let id = model.selected_memo().ok_or("selected")?.id.clone();
        let memo = lomo_tui::queries::load_body(&fixture.runtime, &id)?;
        let lomo_tui::model::BodyState::Ready(body) = memo.body else {
            return Err("body was not loaded".into());
        };
        Ok(EditTarget::Memo {
            id,
            fingerprint: memo.fingerprint,
            body: body.as_str().to_owned(),
        })
    }

    #[test]
    fn pin_delete_restore_and_history_keep_their_data_contracts() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(1).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        let id = model
            .selected_memo()
            .ok_or("selected")
            .expect("fixture and operation must succeed")
            .id
            .clone();
        command(&fixture.runtime, &mut model, Command::Pin)
            .expect("fixture and operation must succeed");
        assert!(
            model
                .selected_memo()
                .ok_or("pinned")
                .expect("fixture and operation must succeed")
                .pinned
        );
        let (results, _inbox) = std::sync::mpsc::channel();
        let reply = execute(&fixture.runtime, &Effect::History(id.clone()), &results)
            .expect("fixture and operation must succeed");
        assert!(matches!(
            reply,
            lomo_tui::effects::RuntimeMessage::History { .. }
        ));
        command(&fixture.runtime, &mut model, Command::Delete)
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Goto(Screen::Trash))
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .first()
                .map(|memo| &memo.id),
            Some(&id)
        );
        command(&fixture.runtime, &mut model, Command::Restore)
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        assert!(
            !fixture
                .runtime
                .session
                .get_memo(&id)
                .expect("fixture and operation must succeed")
                .ok_or("restored")
                .expect("fixture and operation must succeed")
                .is_trashed
        );
    }

    #[test]
    fn trash_offers_permanent_delete_and_empty_trash_behind_confirmations() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(3).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        for _ in 0..3 {
            command(&fixture.runtime, &mut model, Command::Delete)
                .expect("fixture and operation must succeed");
            command(&fixture.runtime, &mut model, Command::Accept)
                .expect("fixture and operation must succeed");
        }
        command(&fixture.runtime, &mut model, Command::Goto(Screen::Trash))
            .expect("fixture and operation must succeed");
        assert_eq!(feed(&model).expect("trash").memos.len(), 3);
        let first = feed(&model)
            .expect("trash")
            .memos
            .first()
            .expect("first trashed memo")
            .id
            .clone();
        // `d` in the trash is a permanent delete, never a second soft delete.
        command(&fixture.runtime, &mut model, Command::Delete)
            .expect("fixture and operation must succeed");
        assert!(matches!(
            model.input,
            InputMode::Confirm(lomo_tui::model::Confirmation::DeleteForever(ref id)) if *id == first
        ));
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        assert_eq!(feed(&model).expect("trash").memos.len(), 2);
        assert!(
            fixture
                .runtime
                .session
                .projected_memo(first.as_str())
                .expect("fixture and operation must succeed")
                .is_none()
        );
        command(&fixture.runtime, &mut model, Command::EmptyTrash)
            .expect("fixture and operation must succeed");
        assert!(matches!(
            model.input,
            InputMode::Confirm(lomo_tui::model::Confirmation::EmptyTrash)
        ));
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        assert!(feed(&model).expect("trash").memos.is_empty());
        assert_eq!(feed(&model).expect("trash").total, Some(0));
    }

    #[test]
    fn history_lists_revisions_and_restores_the_chosen_one() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(1).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        let id = model.selected_memo().expect("selected").id.clone();
        let target = edit_target(&fixture, &model).expect("fixture and operation must succeed");
        let effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Editor {
                body: "edited body",
                concurrent_file: None,
            },
            &target,
            None,
            None,
        )
        .expect("fixture and operation must succeed");
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::History)
            .expect("fixture and operation must succeed");
        let InputMode::Picker(picker) = &model.input else {
            panic!("history picker");
        };
        let entries = lomo_tui::menu::entries(&model, picker);
        let original = entries
            .iter()
            .position(|entry| entry.label.contains("needle 0"))
            .expect("the original body is listed as a revision");
        command(
            &fixture.runtime,
            &mut model,
            Command::Move(i32::try_from(original).expect("index")),
        )
        .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        assert!(matches!(
            model.input,
            InputMode::Confirm(lomo_tui::model::Confirmation::RestoreRevision { .. })
        ));
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        let body = fixture
            .runtime
            .session
            .get_memo(&id)
            .expect("fixture and operation must succeed")
            .expect("memo")
            .body;
        assert!(body.contains("needle 0") && !body.contains("edited body"));
    }

    #[test]
    fn editor_return_defers_the_version_bound_commit_to_the_worker() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(1).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        let target = edit_target(&fixture, &model).expect("fixture and operation must succeed");
        let effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Editor {
                body: "edited body",
                concurrent_file: None,
            },
            &target,
            None,
            None,
        )
        .expect("fixture and operation must succeed");
        assert!(
            fs::read_to_string(fixture.runtime.workspace.join("2026_09_11.md"))
                .expect("fixture and operation must succeed")
                .contains("needle")
        );
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert!(
            fs::read_to_string(fixture.runtime.workspace.join("2026_09_11.md"))
                .expect("fixture and operation must succeed")
                .contains("edited body")
        );
    }

    #[test]
    fn concurrent_change_rejects_the_original_baseline_and_retains_the_editor_draft() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(1).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        let target = edit_target(&fixture, &model).expect("fixture and operation must succeed");
        let path = fixture.runtime.workspace.join("2026_09_11.md");
        let effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Editor {
                body: "my conflicting draft",
                concurrent_file: Some(path.clone()),
            },
            &target,
            None,
            None,
        )
        .expect("fixture and operation must succeed");
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert!(
            fs::read_to_string(path)
                .expect("fixture and operation must succeed")
                .contains("concurrent change")
        );
        let retained = fs::read_dir(&fixture.runtime.paths.drafts_dir)
            .expect("fixture and operation must succeed")
            .map(|entry| entry.map(|entry| entry.path()))
            .collect::<Result<Vec<_>, _>>()
            .expect("fixture and operation must succeed");
        assert!(retained.iter().any(|path| {
            path.extension().is_some_and(|extension| extension == "md")
                && fs::read_to_string(path).is_ok_and(|body| body == "my conflicting draft")
        }));
        assert!(matches!(model.input, InputMode::Message { .. }));
    }

    #[test]
    fn external_capture_returns_to_the_draft_until_ctrl_s() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        model.input = InputMode::Compose;
        model.draft.text = TextBuffer::new("draft before editor".to_owned());
        let effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Editor {
                body: "expanded draft",
                concurrent_file: None,
            },
            &EditTarget::Capture,
            None,
            None,
        )
        .expect("fixture and operation must succeed");
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert_eq!(model.draft.text.text(), "expanded draft");
        assert_eq!(model.input, InputMode::Compose);
        assert_eq!(model.draft.save, SaveState::Editing);
        assert!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .is_empty()
        );
        command(&fixture.runtime, &mut model, Command::Commit)
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            1
        );
    }

    struct Clipboard(Result<Vec<u8>, lomo_tui::media::ClipboardError>);
    impl lomo_tui::media::ImageClipboard for Clipboard {
        fn read_png(&self) -> Result<Vec<u8>, lomo_tui::media::ClipboardError> {
            self.0.clone()
        }
    }

    struct MissingPlayer;
    impl CommandRunner for MissingPlayer {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                "player missing",
            ))
        }

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn lomo_tui::editor::ManagedChild>, std::io::Error> {
            Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                "player missing",
            ))
        }
    }

    #[test]
    fn clipboard_import_promotes_real_bytes_and_keeps_backend_failures_visible() {
        let fixture = RuntimeFixture::new().expect("runtime");
        let png = lomo_tui::media::rgba_to_png(1, 1, &[255, 0, 0, 255]).expect("PNG");
        let path = lomo_tui::mutations::import_from_clipboard(
            &fixture.runtime,
            &Clipboard(Ok(png.clone())),
        )
        .expect("import");
        assert_eq!(
            fs::read(fixture.runtime.workspace.join(&path)).expect("promoted file"),
            png
        );
        let attachment = lomo_core::RelativeWorkspacePath::parse(&path).expect("relative path");
        let staged = lomo_tui::mutations::stage_attachment(&fixture.runtime, &attachment)
            .expect("attachment stages under the workspace capability");
        let error =
            lomo_tui::media::spawn_player(&MissingPlayer, &fixture.runtime.config.player, &staged)
                .map(|_| ())
                .expect_err("missing player");
        assert!(matches!(error, lomo_tui::error::TuiError::Player { .. }));
        let error = lomo_tui::mutations::import_from_clipboard(
            &fixture.runtime,
            &Clipboard(Err(lomo_tui::media::ClipboardError::Unavailable {
                diagnostic: "no display".to_owned(),
            })),
        )
        .expect_err("clipboard backend failure");
        assert_eq!(
            error,
            lomo_tui::error::TuiError::Clipboard {
                diagnostic: "no display".to_owned()
            }
        );
        let query = lomo_application::MemoQuery {
            search_text: None,
            filters: lomo_application::MemoFilters::default(),
            sort: lomo_application::MemoSort::default(),
        };
        assert_eq!(
            fixture
                .runtime
                .session
                .query_count(&query)
                .expect("memo count"),
            1
        );
    }
}
