//! Behavior Contract
//! Capability: seven TUI views consume `lomo-application`; task toggle writes `[x]` through the session.
//! Scenarios: create a task memo, open Tasks, toggle, observe Markdown; editor create writes a dated file.
//! Observable outcomes: file bytes, model rows, overdue overlay for catch-up alarms.
//! TDD proof: TUI composition root did not exist.
//! Excludes: a real terminal, network sync, and Android.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "session-backed contract tests fail closed on missing workspace facts"
)]
mod tests {
    use std::fs;
    use std::os::unix::process::ExitStatusExt;
    use std::process::ExitStatus;

    use lomo_application::{CreateMemoRequest, SearchMode};
    use lomo_core::{OperationId, RelativeWorkspacePath};
    use lomo_tui::config::AppConfig;
    use lomo_tui::edit_flow::{EditRequest, complete_edit};
    use lomo_tui::editor::{CommandRunner, EditKind};
    use lomo_tui::event::Command;
    use lomo_tui::media::GraphicsProtocol;
    use lomo_tui::model::{AppModel, Overlay, Screen};
    use lomo_tui::ops::{apply_effect, bootstrap_model, open_runtime, reload_screen};
    use lomo_tui::update::{Effect, apply_command};
    use lomo_tui::xdg::RuntimePaths;
    use tempfile::tempdir;

    struct ScriptedEditor(&'static str);

    impl CommandRunner for ScriptedEditor {
        fn run_foreground(
            &self,
            _program: &str,
            args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            let path = args.last().ok_or_else(|| std::io::Error::other("draft"))?;
            fs::write(path, self.0)?;
            Ok(ExitStatus::from_raw(0))
        }
    }

    fn runtime_bundle() -> (tempfile::TempDir, lomo_tui::ops::TuiRuntime) {
        let root = tempdir().expect("root");
        let workspace = root.path().join("notes");
        let state = root.path().join("state");
        let cache = root.path().join("cache");
        let runtime_dir = root.path().join("run");
        fs::create_dir_all(&workspace).expect("ws");
        let paths = RuntimePaths {
            config_dir: root.path().join("cfg"),
            drafts_dir: state.join("drafts"),
            exchange_dir: state.join("exchange"),
            state_dir: state,
            cache_dir: cache,
            runtime_dir,
            default_workspace: None,
        };
        let config = AppConfig {
            workspace,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: Some(vec!["scripted".to_owned()]),
            player: vec!["xdg-open".to_owned()],
        };
        let runtime = open_runtime(paths, config, GraphicsProtocol::None).expect("open");
        (root, runtime)
    }

    #[test]
    fn task_toggle_rewrites_markdown_checkbox_via_session() {
        let (_root, runtime) = runtime_bundle();
        runtime
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-task").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_11.md").expect("path")),
                time_token: Some("09:00:00".to_owned()),
                content: "- [ ] buy milk".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let mut model = bootstrap_model(&runtime, AppModel::new(140, 40)).expect("boot");
        assert_eq!(
            apply_command(&mut model, Command::Goto(Screen::Tasks)),
            Effect::LoadScreen
        );
        apply_effect(&runtime, &mut model, Effect::LoadScreen).expect("tasks");
        assert!(
            model.items.iter().any(|row| row.title.contains("buy milk")),
            "tasks={:?}",
            model.items
        );
        apply_effect(&runtime, &mut model, Effect::ToggleTask).expect("toggle");
        let markdown = fs::read_to_string(runtime.workspace.join("2026_09_11.md")).expect("md");
        assert!(markdown.contains("- [x] buy milk"), "markdown={markdown}");
    }

    #[test]
    fn editor_create_writes_body_and_empty_create_does_not() {
        let (_root, runtime) = runtime_bundle();
        let mut model = bootstrap_model(&runtime, AppModel::new(100, 30)).expect("boot");
        complete_edit(
            &runtime,
            &mut model,
            &ScriptedEditor("hello from editor"),
            EditRequest {
                kind: EditKind::Create,
                initial: "",
                baseline: None,
                visual: None,
                editor_env: None,
            },
        )
        .expect("create");
        assert_eq!(model.status, "saved");
        let mut found = false;
        for entry in fs::read_dir(&runtime.workspace).expect("list") {
            let path = entry.expect("e").path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("md") {
                let text = fs::read_to_string(&path).expect("read");
                if text.contains("hello from editor") {
                    found = true;
                }
            }
        }
        assert!(found, "dated markdown should contain editor body");
        complete_edit(
            &runtime,
            &mut model,
            &ScriptedEditor("  \n"),
            EditRequest {
                kind: EditKind::Create,
                initial: "",
                baseline: None,
                visual: None,
                editor_env: None,
            },
        )
        .expect("empty");
        assert_eq!(model.status, "empty create cancelled");
    }

    #[test]
    fn command_palette_reaches_all_seven_screens() {
        let (_root, runtime) = runtime_bundle();
        let mut model = bootstrap_model(&runtime, AppModel::new(80, 24)).expect("boot");
        for screen in [
            Screen::Timeline,
            Screen::Tasks,
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ] {
            assert_eq!(
                apply_command(&mut model, Command::Goto(screen)),
                Effect::LoadScreen
            );
            apply_effect(&runtime, &mut model, Effect::LoadScreen).expect("load");
            assert_eq!(model.screen, screen);
        }
        assert!(!model.preview.is_empty() || model.screen == Screen::Attachments);
        assert_eq!(
            apply_command(&mut model, Command::ToggleSearchMode),
            Effect::None
        );
        assert_eq!(model.search_mode, SearchMode::Fuzzy);
        model.overlay = Overlay::Help;
        assert_eq!(apply_command(&mut model, Command::HelpToggle), Effect::None);
        assert_eq!(model.overlay, Overlay::None);
        reload_screen(&runtime, &mut model).expect("reload");
    }
}
