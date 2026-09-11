//! Behavior Contract
//! Capability: TUI composition root writes only through `lomo-application`.
//! Scenarios: pin/delete/restore/history/search/clipboard import/play; overdue catch-up overlay; editor update and fingerprint conflict keep the draft.
//! Observable outcomes: Markdown bytes, `.lomo` pin/trash facts, overlay text, retained draft files, player errors.
//! TDD proof: session effects other than task toggle were untested.
//! Excludes: a real TTY, system clipboard, and Android SAF.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "session-backed contract tests fail closed on missing workspace facts"
)]
mod tests {
    use std::fs;
    use std::os::unix::process::ExitStatusExt;
    use std::path::Path;
    use std::process::ExitStatus;

    use lomo_application::{CreateMemoRequest, MemoFilters, MemoQuery, MemoSort};
    use lomo_core::{OperationId, RelativeWorkspacePath};
    use lomo_tui::config::AppConfig;
    use lomo_tui::edit_flow::{EditRequest, complete_edit, edit_selection};
    use lomo_tui::editor::{CommandRunner, EditKind};
    use lomo_tui::error::TuiError;
    use lomo_tui::media::{ClipboardError, GraphicsProtocol, ImageClipboard, rgba_to_png};
    use lomo_tui::model::{AppModel, Overlay, Screen};
    use lomo_tui::ops::{
        apply_effect, bootstrap_model, import_from_clipboard, mint_operation_id, open_runtime,
        play_selected, workspace_path,
    };
    use lomo_tui::update::Effect;
    use lomo_tui::xdg::RuntimePaths;
    use lomo_workspace::MemoId;
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

    struct MissingPlayer;

    impl CommandRunner for MissingPlayer {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                "xdg-open: no such file",
            ))
        }
    }

    struct PngClipboard(Vec<u8>);

    impl ImageClipboard for PngClipboard {
        fn read_png(&self) -> Result<Vec<u8>, ClipboardError> {
            Ok(self.0.clone())
        }
    }

    struct BrokenClipboard;

    impl ImageClipboard for BrokenClipboard {
        fn read_png(&self) -> Result<Vec<u8>, ClipboardError> {
            Err(ClipboardError::Unavailable {
                diagnostic: "no Display or Wayland session".to_owned(),
            })
        }
    }

    fn runtime_bundle(
        editor: Option<Vec<String>>,
    ) -> (tempfile::TempDir, lomo_tui::ops::TuiRuntime) {
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
            editor,
            player: vec!["xdg-open".to_owned()],
        };
        let runtime = open_runtime(paths, config, GraphicsProtocol::None).expect("open");
        (root, runtime)
    }

    fn create_body(runtime: &lomo_tui::ops::TuiRuntime, op: &str, body: &str) -> String {
        runtime
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(op).expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_11.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: body.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create")
            .memo_id
            .as_str()
            .to_owned()
    }

    #[test]
    fn pin_delete_restore_and_history_go_through_the_session() {
        let (_root, runtime) = runtime_bundle(Some(vec!["scripted".to_owned()]));
        let memo_id = create_body(&runtime, "op-pin", "keep this note");
        let mut model = bootstrap_model(&runtime, AppModel::new(140, 40)).expect("boot");
        apply_effect(&runtime, &mut model, Effect::PinSelected).expect("pin");
        let pinned = runtime
            .session
            .list_memos(&MemoQuery {
                search_text: None,
                filters: MemoFilters {
                    pinned_only: true,
                    ..MemoFilters::default()
                },
                sort: MemoSort::default(),
            })
            .expect("list pinned");
        let pinned_row = pinned.items.first().expect("one pinned memo");
        assert_eq!(pinned_row.memo_id, memo_id);
        apply_effect(&runtime, &mut model, Effect::ShowHistory).expect("history");
        assert!(matches!(model.overlay, Overlay::History { .. }));
        apply_effect(&runtime, &mut model, Effect::ConfirmDelete).expect("confirm");
        assert!(matches!(model.overlay, Overlay::Confirm { .. }));
        model.overlay = Overlay::None;
        apply_effect(&runtime, &mut model, Effect::DeleteSelected).expect("delete");
        model.screen = Screen::Trash;
        apply_effect(&runtime, &mut model, Effect::LoadScreen).expect("trash");
        assert!(
            model.items.iter().any(|row| row.id == memo_id),
            "trash={:?}",
            model.items
        );
        apply_effect(&runtime, &mut model, Effect::RestoreSelected).expect("restore");
        let restored = runtime
            .session
            .get_memo(&MemoId::parse(&memo_id).expect("id"))
            .expect("get")
            .expect("present");
        assert!(!restored.is_trashed);
        assert!(
            mint_operation_id()
                .expect("mint")
                .as_str()
                .starts_with("op-")
        );
        assert_eq!(workspace_path(&runtime), runtime.workspace.as_path());
    }

    #[test]
    fn search_submit_filters_timeline_and_missing_editor_is_status_not_panic() {
        let (_root, runtime) = runtime_bundle(None);
        let _id = create_body(&runtime, "op-search", "unique-token-xyz");
        let mut model = bootstrap_model(&runtime, AppModel::new(120, 30)).expect("boot");
        model.search = lomo_tui::model::SearchSession::Open {
            query: "unique-token-xyz".to_owned(),
            mode: lomo_application::SearchMode::Fulltext,
            epoch: 1,
        };
        apply_effect(&runtime, &mut model, Effect::Search).expect("search");
        assert!(
            model
                .items
                .iter()
                .any(|row| row.title.contains("unique-token-xyz")),
            "search={:?}",
            model.items
        );
        complete_edit(
            &runtime,
            &mut model,
            &ScriptedEditor("ignored"),
            EditRequest {
                kind: EditKind::Create,
                initial: "",
                baseline: None,
                visual: None,
                editor_env: None,
            },
        )
        .expect("missing editor");
        assert!(model.status.contains("not assumed"));
    }

    #[test]
    fn editor_update_rewrites_body_and_conflict_keeps_draft() {
        let (root, runtime) = runtime_bundle(Some(vec!["scripted".to_owned()]));
        let memo_id = create_body(&runtime, "op-edit", "original body");
        let mut model = bootstrap_model(&runtime, AppModel::new(140, 40)).expect("boot");
        edit_selection(
            &runtime,
            &mut model,
            &ScriptedEditor("updated via editor"),
            None,
            None,
        )
        .expect("edit");
        assert_eq!(model.status, "saved");
        let markdown = fs::read_to_string(runtime.workspace.join("2026_09_11.md")).expect("md");
        assert!(
            markdown.contains("updated via editor"),
            "markdown={markdown}"
        );
        complete_edit(
            &runtime,
            &mut model,
            &ScriptedEditor("conflicting draft"),
            EditRequest {
                kind: EditKind::Update { memo_id },
                initial: "updated via editor",
                baseline: Some("stale-fingerprint".to_owned()),
                visual: None,
                editor_env: None,
            },
        )
        .expect("conflict");
        assert!(matches!(model.overlay, Overlay::Alert { .. }));
        let mut retained = false;
        for entry in fs::read_dir(root.path().join("state").join("drafts")).expect("drafts") {
            let path = entry.expect("entry").path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("md") {
                retained = true;
            }
        }
        assert!(retained, "conflict must keep a draft file");
        model.items.clear();
        edit_selection(&runtime, &mut model, &ScriptedEditor("x"), None, None).expect("no sel");
        assert_eq!(model.status, "no memo selected");
    }

    #[test]
    fn clipboard_import_and_player_failure_are_visible() {
        let (_root, runtime) = runtime_bundle(Some(vec!["scripted".to_owned()]));
        let _id = create_body(&runtime, "op-media", "host memo");
        let mut model = bootstrap_model(&runtime, AppModel::new(140, 40)).expect("boot");
        let png = rgba_to_png(1, 1, &[255, 0, 0, 255]).expect("png");
        let relative =
            import_from_clipboard(&runtime, &mut model, &PngClipboard(png)).expect("import");
        assert!(relative.starts_with("media/"));
        model.screen = Screen::Attachments;
        apply_effect(&runtime, &mut model, Effect::LoadScreen).expect("attachments");
        let error =
            import_from_clipboard(&runtime, &mut model, &BrokenClipboard).expect_err("clip");
        assert!(matches!(error, TuiError::Clipboard { .. }));
        let player = play_selected(&runtime, &model, &MissingPlayer).expect_err("player");
        assert!(matches!(player, TuiError::Player { .. }));
        apply_effect(&runtime, &mut model, Effect::None).expect("noop");
        apply_effect(&runtime, &mut model, Effect::PlayAttachment)
            .expect("play effect is deferred");
    }

    #[test]
    fn overdue_reminder_opens_catch_up_overlay_on_bootstrap() {
        let (_root, runtime) = runtime_bundle(Some(vec!["scripted".to_owned()]));
        runtime
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-overdue").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_20.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "pay rent @2026-07-20-10:45".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let model = bootstrap_model(&runtime, AppModel::new(80, 24)).expect("boot");
        match model.overlay {
            Overlay::Overdue { lines } => {
                assert!(
                    lines.iter().any(|line| line.contains("overdue")),
                    "lines={lines:?}"
                );
            }
            Overlay::None
            | Overlay::Help
            | Overlay::Palette { .. }
            | Overlay::Alert { .. }
            | Overlay::Confirm { .. }
            | Overlay::History { .. } => {
                panic!("expected overdue overlay, got {:?}", model.overlay)
            }
        }
        assert!(Path::new(&runtime.workspace).is_dir());
    }
}
