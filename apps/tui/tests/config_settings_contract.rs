// adversarial-audit: config.toml, the Settings screen and the first-run path.
// Every probe asserts the behavior a working settings surface should have;
// each failure is evidence that a field is dead, silently dropped, frozen at
// startup, or that first-run performs silent side effects the user never sees.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, model_with_memos};
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use lomo_tui::{
        cli::render_help,
        config::{
            AppConfig, ConfigProbe, SETTINGS_FIELDS, mint_config, parse_config_toml, probe_config,
        },
        editor::resolve_editor,
        effects::{Effect, RuntimeMessage},
        error::TuiError,
        event::{Command, command_from_key},
        model::{AppModel, Picker, PickerKind, Screen, View},
        ops::{bootstrap_model, execute, open_runtime},
        settings::{SettingRow, SettingsView},
        update::apply_command,
        xdg::{EnvLookup, RuntimePaths, resolve_paths},
    };
    use std::path::{Path, PathBuf};
    use tempfile::tempdir;

    fn paths_in(dir: &Path, default_workspace: Option<PathBuf>) -> RuntimePaths {
        RuntimePaths {
            config_dir: dir.join("cfg"),
            drafts_dir: dir.join("drafts"),
            exchange_dir: dir.join("ex"),
            state_dir: dir.join("state"),
            cache_dir: dir.join("cache"),
            runtime_dir: dir.join("run"),
            home_dir: Some(dir.join("home")),
            default_workspace,
        }
    }

    fn settings_model(fixture: &RuntimeFixture) -> Result<AppModel, Box<dyn std::error::Error>> {
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))?;
        command(
            &fixture.runtime,
            &mut model,
            Command::Goto(Screen::Settings),
        )?;
        if !matches!(model.view, View::Settings(_)) {
            return Err(std::io::Error::other(format!(
                "navigation must land on the settings view: {:?}",
                model.view.screen()
            ))
            .into());
        }
        Ok(model)
    }

    /// The same projection the renderer paints: file path, every row's key and
    /// live value, plus the informational tail — searchable like a drawn pane.
    fn settings_lines(model: &AppModel) -> Vec<String> {
        match &model.view {
            View::Settings(view) => {
                let mut lines = vec![view.file.display().to_string()];
                lines.extend(
                    view.rows
                        .iter()
                        .map(|row| format!("{} = {}", row.field.key(), row.value)),
                );
                lines.extend(view.info.iter().cloned());
                lines
            }
            View::Feed(_)
            | View::Reader { .. }
            | View::Tasks(_)
            | View::Statistics(_)
            | View::Attachments(_)
            | View::Loading { .. }
            | View::Failed { .. } => {
                panic!("expected the settings view, got {:?}", model.view.screen())
            }
        }
    }

    /// queries.rs:305-326 renders a static string list. A settings screen that
    /// cannot be edited must at least tell the user WHERE the editable file
    /// lives — otherwise the only way to change anything is invisible.
    #[test]
    fn settings_names_the_file_it_shadows() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let model = settings_model(&fixture).expect("settings view");
        let lines = settings_lines(&model);
        assert!(
            lines.iter().any(|line| line.contains("config.toml")),
            "the settings screen must name the config file it mirrors; \
             it only prints values with no pointer to the editable file: {lines:?}"
        );
    }

    /// `date_format` is a real `FileConfig` field (config.rs:28) consumed by the
    /// session (ops.rs:67) and task day labels (queries.rs:338), yet the
    /// settings list omits it — the "settings" view does not even enumerate
    /// the real configuration space.
    #[test]
    fn settings_covers_every_real_config_field() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let model = settings_model(&fixture).expect("settings view");
        let lines = settings_lines(&model);
        assert!(
            lines.iter().any(|line| line.contains("date_format")),
            "real config field date_format is invisible on the settings screen: {lines:?}"
        );
    }

    /// A screen named Settings must change state: selection moves, editing
    /// opens, external edit dispatches — something observable per command.
    /// `Delete`/`Pin` legitimately do not apply to settings rows and are not
    /// probed; navigation and editing are the contract.
    #[test]
    fn settings_offers_a_state_changing_action() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let model = settings_model(&fixture).expect("settings view");
        for probe in [
            Command::Move(1),
            Command::Scroll(5),
            Command::Page(1),
            Command::Accept,
            Command::ExternalEdit,
        ] {
            let mut probe_model = model.clone();
            let before_view = probe_model.view.clone();
            let before_input = probe_model.input.clone();
            let effect = apply_command(&mut probe_model, probe.clone());
            assert!(
                effect.is_some()
                    || probe_model.status.is_some()
                    || probe_model.view != before_view
                    || probe_model.input != before_input,
                "{probe:?} on the settings screen produces no effect and no \
                 feedback — a settings page must do something: {:?}",
                probe_model.view
            );
        }
    }

    /// Overflow content on the settings page must be reachable: rows beyond the
    /// pane are dead ends unless Move/Scroll/Page move the selection.
    /// A 40-row settings page in a 12-row pane must be able to move.
    #[test]
    fn settings_scrolls_when_its_content_overflows() {
        let mut model = AppModel::new(80, 12);
        model.view = View::Settings(SettingsView {
            file: PathBuf::from("/tmp/lomo/config.toml"),
            rows: SETTINGS_FIELDS
                .iter()
                .cycle()
                .take(40)
                .enumerate()
                .map(|(index, field)| SettingRow {
                    field: *field,
                    value: format!("option {index}"),
                    hot: false,
                })
                .collect(),
            selected: 0,
            info: Vec::new(),
            home_dir: None,
        });
        let before = model.view.clone();
        let scroll = apply_command(&mut model, Command::Scroll(10));
        let page = apply_command(&mut model, Command::Page(1));
        let moved = apply_command(&mut model, Command::Move(5));
        assert!(
            scroll.is_some()
                || page.is_some()
                || moved.is_some()
                || model.status.is_some()
                || model.view != before,
            "Scroll/PageDown on an overflowing settings page changes nothing: \
             no effect, no status, no selection — overflow rows are unreachable"
        );
    }

    /// `FileConfig` (config.rs:22-33) has no `deny_unknown_fields` while the
    /// rest of the codebase applies it everywhere (lomo-application intent.rs,
    /// transaction.rs, `record_plan.rs`). A misspelled key is silently ignored —
    /// here `timezone` (no underscore) drops to the UTC default.
    #[test]
    fn unknown_config_keys_are_rejected_not_swallowed() {
        let parsed = parse_config_toml(
            "workspace = \"/notes\"\ntimezone = \"Asia/Shanghai\"\ntheam = \"dark\"\n",
            None,
            None,
        );
        assert!(
            parsed.is_err(),
            "typo'd keys must fail closed like every other serde boundary; \
             they were silently ignored: {parsed:?}"
        );
    }

    /// `time_zone` is validated only by accident: session.rs:59 happens to call
    /// `local_date` during open. The config parser itself accepts garbage zones —
    /// "Mars/Olympus" parses fine and dies one layer deeper as a session error.
    #[test]
    fn time_zone_is_validated_where_it_is_parsed() {
        for zone in ["Mars/Olympus", "Not/AZone"] {
            let parsed = parse_config_toml(
                &format!("workspace = \"/notes\"\ntime_zone = \"{zone}\"\n"),
                None,
                None,
            );
            assert!(
                parsed.is_err(),
                "an unknown IANA zone must fail at config parse, not leak into \
                 a session validation error later: {parsed:?}"
            );
        }
    }

    /// `editor = "code --wait"` is the natural way to write an editor with a
    /// flag. `TomlEditor::Program` stores it as ONE argv element
    /// (config.rs:153-155) while the $VISUAL/$EDITOR path splits whitespace
    /// (editor.rs:134 `split_spec`). `Command::new("code --wait")` can only ENOENT.
    #[test]
    fn editor_string_form_splits_like_the_env_spec() {
        let config = parse_config_toml(
            "workspace = \"/notes\"\neditor = \"code --wait\"\n",
            None,
            None,
        )
        .expect("parse");
        let argv = resolve_editor(config.editor.as_deref(), None, None).expect("resolve");
        assert!(
            !argv.program.contains(char::is_whitespace),
            "configured editor resolves to an unspawnable program name; \
             the string form must split like $VISUAL/$EDITOR: {argv:?}"
        );
    }

    /// `editor` accepts both a string and an argv array; `player` accepts only
    /// the array (config.rs:29-32). `player = "mpv"` is a hard TOML type error
    /// while `editor = "mpv"` works — sibling fields must behave alike.
    #[test]
    fn player_accepts_the_same_string_shorthand_as_editor() {
        let parsed = parse_config_toml("workspace = \"/n\"\nplayer = \"mpv\"\n", None, None);
        assert!(
            parsed.is_ok(),
            "player must accept the string form the editor field already \
             documents: {parsed:?}"
        );
    }

    /// Empty values fall back to defaults instead of failing closed:
    /// `editor = []` / `[""]` -> None (env fallback), `player = []` -> platform
    /// default, `time_zone` = "" -> UTC. The user typed a key and got silence.
    #[test]
    fn empty_values_fail_closed_instead_of_silent_defaults() {
        for raw in [
            "workspace = \"/n\"\neditor = []\n",
            "workspace = \"/n\"\neditor = [\"\"]\n",
            "workspace = \"/n\"\nplayer = []\n",
            "workspace = \"/n\"\ntime_zone = \"\"\n",
        ] {
            assert!(
                parse_config_toml(raw, None, None).is_err(),
                "an explicitly-empty value must be a config error, not a silent \
                 default: {raw}"
            );
        }
    }

    /// A CLI override swaps the workspace wholesale (config.rs:142-145): the
    /// file's workspace string is never validated on override runs, so a
    /// hand-edited relative/broken path rots undetected until the day the
    /// user drops the flag.
    #[test]
    fn a_cli_override_does_not_silence_a_broken_file_workspace() {
        let parsed = parse_config_toml(
            "workspace = \"relative/rot\"\n",
            Some(Path::new("/override")),
            None,
        );
        assert!(
            parsed.is_err(),
            "the file's own workspace must still be validated when an override \
             binds this run; silently accepting it hides config rot: {parsed:?}"
        );
        // And the worst combination: the same key is still REQUIRED — an
        // override cannot rescue a file that omits workspace entirely, yet the
        // value that must be present is never checked.
        assert!(
            parse_config_toml("time_zone = \"UTC\"\n", Some(Path::new("/override")), None).is_err(),
            "control: workspace is a required key even under an override"
        );
    }

    /// F5 (`Command::Refresh` -> `Effect::Refresh` -> ops.rs:219 `rebuild_projection`)
    /// re-reads the WORKSPACE only. `runtime.config` is the startup snapshot; no
    /// code path re-parses config.toml, so edits never reach a running app and
    /// the "Workspace refreshed" status actively lies about freshness.
    #[test]
    fn f5_reloads_config_toml_changes() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings view");
        let before = settings_lines(&model);
        assert!(
            before.iter().any(|line| line.contains("UTC")),
            "fixture must start on UTC: {before:?}"
        );
        std::fs::create_dir_all(&fixture.runtime.paths.config_dir).expect("cfg dir");
        std::fs::write(
            fixture.runtime.paths.config_dir.join("config.toml"),
            format!(
                "workspace = \"{}\"\ntime_zone = \"Asia/Tokyo\"\n",
                fixture.runtime.workspace.display()
            ),
        )
        .expect("edit config on disk");
        command(&fixture.runtime, &mut model, Command::Refresh).expect("refresh");
        let after = settings_lines(&model);
        assert!(
            after.iter().any(|line| line.contains("Asia/Tokyo")),
            "editing config.toml then pressing F5 must surface the new value; \
             the screen still reports the frozen startup snapshot: {after:?}"
        );
    }

    /// XDG overrides must be absolute per the base-dir spec. `dir_or`
    /// (xdg.rs:219-226) joins a relative `XDG_CONFIG_HOME` verbatim, anchoring
    /// config/state/cache to the process cwd — the same strictness
    /// `normalize_workspace` applies to `workspace` is missing here.
    #[cfg(all(unix, not(target_os = "macos")))]
    #[test]
    fn relative_xdg_dirs_are_rejected() {
        let paths = resolve_paths(EnvLookup {
            home: Some("/home/u"),
            config: Some("relative/cfg"),
            state: None,
            cache: None,
            runtime: Some("/run/user/1"),
            data: None,
            appdata: None,
            localappdata: None,
        })
        .expect("resolve");
        assert!(
            paths.config_dir.is_absolute(),
            "a relative XDG_CONFIG_HOME must be ignored or rejected, not \
             silently anchored to the cwd: {:?}",
            paths.config_dir
        );
    }

    /// A minted `config.toml` must always parse back: a workspace path with
    /// TOML-forbidden control characters must be escaped by the registry
    /// serializer — anything less bricks the install on the second launch.
    #[cfg(unix)]
    #[test]
    fn minted_config_round_trips_through_the_parser() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("bell\u{7}notes");
        let paths = paths_in(dir.path(), Some(notes));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, None).expect("first-run probe")
        else {
            panic!("missing config.toml must probe FirstRun")
        };
        mint_config(
            &file,
            &AppConfig {
                media_dir: proposal.workspace.join("media"),
                workspace: proposal.workspace,
                time_zone: proposal.time_zone,
                date_format: lomo_application::calendar::DateFormat::default(),
                editor: None,
                player: lomo_tui::config::default_player(),
            },
        )
        .expect("confirmed mint");
        let persisted = std::fs::read_to_string(&file).expect("the minted file exists on disk");
        parse_config_toml(&persisted, None, Some(dir.path())).expect(
            "whatever first-run writes must parse on the next launch; \
             an unescaped control character bricks the install",
        );
    }

    /// The minted file must document every settable key — `render_config_toml`
    /// writes through the same registry the parser reads, so a missing key in
    /// the file means the registry itself dropped it.
    #[test]
    fn minted_config_documents_every_settable_key() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, None).expect("first-run probe")
        else {
            panic!("missing config.toml must probe FirstRun")
        };
        mint_config(
            &file,
            &AppConfig {
                media_dir: proposal.workspace.join("media"),
                workspace: proposal.workspace,
                time_zone: proposal.time_zone,
                date_format: lomo_application::calendar::DateFormat::default(),
                editor: Some(vec!["hx".to_owned()]),
                player: lomo_tui::config::default_player(),
            },
        )
        .expect("confirmed mint");
        let persisted = std::fs::read_to_string(&file).expect("minted");
        for &field in SETTINGS_FIELDS {
            assert!(
                persisted.contains(field.key()),
                "the first-run file must document settable key {}: {persisted}",
                field.key()
            );
        }
    }

    /// Every single-key binding the `--help` table advertises must actually
    /// produce a command — an unbound key in help is a lie. The table now
    /// names `n e / : . m d ? q` etc.; re-check each against `command_from_key`
    /// so a future edit cannot drift the text ahead of the bindings again.
    #[test]
    fn cli_help_names_only_keys_that_are_bound() {
        let help = render_help();
        let mut model = model_with_memos(2, 80, 24).expect("feed");
        for key in ['n', 'e', '/', ':', '.', 'm', 'd', '?', 'q'] {
            assert!(
                command_from_key(
                    KeyEvent::new(KeyCode::Char(key), KeyModifiers::NONE),
                    &model
                )
                .is_some(),
                "--help advertises '{key}' as a direct key but nothing is bound"
            );
        }
        // Ctrl+s submits from the composer, not from browse mode.
        model.input = lomo_tui::model::InputMode::Compose;
        assert!(
            command_from_key(
                KeyEvent::new(KeyCode::Char('s'), KeyModifiers::CONTROL),
                &model
            )
            .is_some(),
            "--help advertises Ctrl+s for submit but nothing is bound"
        );
        for phantom in ["Ctrl+p", " t c "] {
            assert!(
                !help.contains(phantom),
                "--help must not advertise unbound keys ({phantom}): {help}"
            );
        }
    }

    /// `init_logging` (logging.rs:30-38) swallows an invalid `LOMO_LOG` filter and
    /// returns None — indistinguishable from the filter being unset. A broken
    /// diagnostic knob must surface, not vanish.
    #[test]
    fn invalid_lomo_log_filters_surface_instead_of_disabling_silently() {
        let dir = tempdir().expect("tmp");
        assert!(
            lomo_tui::logging::file_dispatch(dir.path(), "[[[").is_err(),
            "control: the filter parser must reject garbage"
        );
        let invalid = lomo_tui::logging::init_logging(dir.path(), Some("[[["));
        let unset = lomo_tui::logging::init_logging(dir.path(), None);
        assert!(
            invalid.is_some() != unset.is_some(),
            "an invalid filter must produce a distinguishable outcome from \
             'unset'; both silently yield no logging"
        );
    }

    /// Probing a missing config must be a pure read: no workspace directory,
    /// no config.toml, no marker — persistent state appears only after the
    /// wizard's confirmation routes through `mint_config`.
    #[test]
    fn first_run_probe_has_no_persistent_side_effects() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        assert!(!notes.exists(), "precondition: nothing exists yet");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, None).expect("first run probe")
        else {
            panic!("missing config.toml must probe FirstRun")
        };
        assert_eq!(proposal.workspace, notes, "the proposal names the default");
        assert!(
            !notes.exists() && !file.exists(),
            "probing must not create the workspace or write config.toml: \
             {} / {}",
            notes.display(),
            file.display()
        );
    }

    /// The workspace reconcile stays workspace-scoped (`changed: false` for a
    /// config edit), and the *config* watcher channel — `ConfigChanged` →
    /// `ReloadConfig` — is what surfaces a config.toml edit on the open
    /// Settings screen, marking `time_zone` restart-required.
    #[test]
    fn watcher_reconcile_scope_excludes_config_toml() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings view");
        std::fs::create_dir_all(&fixture.runtime.paths.config_dir).expect("cfg dir");
        std::fs::write(
            fixture.runtime.paths.config_dir.join("config.toml"),
            format!(
                "workspace = \"{}\"\ntime_zone = \"Asia/Tokyo\"\n",
                fixture.runtime.workspace.display()
            ),
        )
        .expect("edit config on disk");
        // Workspace watcher batch → one reconcile, still workspace-scoped.
        let effect = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::FsChanged { observed: None },
        );
        let Some(Effect::Reconcile { req, observed }) = effect else {
            panic!("reconcile request");
        };
        let (results, _inbox) = std::sync::mpsc::sync_channel(256);
        let reply = execute(
            &fixture.runtime,
            &Effect::Reconcile { req, observed },
            &lomo_tui::executor::Outbox::new(results),
            &lomo_tui::model::CancelToken::live(),
        )
        .expect("reconcile");
        assert!(
            matches!(reply, RuntimeMessage::Reconciled { changed: false, .. }),
            "config.toml is outside the reconcile scope: {reply:?}"
        );
        // The config watcher's own channel must pick the file up.
        let effect = lomo_tui::messages::apply_message(&mut model, RuntimeMessage::ConfigChanged);
        let Some(Effect::ReloadConfig { req }) = effect else {
            panic!("config watcher must dispatch a reload: {effect:?}");
        };
        let (results, _inbox) = std::sync::mpsc::sync_channel(256);
        let reply = execute(
            &fixture.runtime,
            &Effect::ReloadConfig { req },
            &lomo_tui::executor::Outbox::new(results),
            &lomo_tui::model::CancelToken::live(),
        )
        .expect("reload");
        assert!(
            matches!(
                reply,
                RuntimeMessage::ConfigApplied { ref restart_pending, .. }
                if restart_pending.contains(&lomo_tui::config::SettingsField::TimeZone)
            ),
            "a changed restart-required field must be named pending: {reply:?}"
        );
        let _follow_up = lomo_tui::messages::apply_message(&mut model, reply);
        let lines = settings_lines(&model);
        assert!(
            lines.iter().any(|line| line.contains("Asia/Tokyo")),
            "the config watcher channel must surface the new value on the open \
             Settings screen (marked restart-required): {lines:?}"
        );
        // And the live session value is untouched until restart.
        assert_eq!(
            fixture.runtime.config().time_zone,
            "UTC",
            "restart-required fields must not apply to the running session"
        );
    }

    /// `open_runtime` order: private dirs, `bind_root`, history migration and the
    /// generation mint (ops.rs:38-61) all run BEFORE `WorkspaceSession::open`
    /// validates the zone (session.rs:59). An invalid `time_zone` therefore
    /// surfaces as a *session* error — not a config error — after `.lomo` was
    /// already written into the user's workspace.
    #[test]
    fn config_validation_precedes_workspace_mutation() {
        let dir = tempdir().expect("tmp");
        let workspace = dir.path().join("notes");
        std::fs::create_dir_all(&workspace).expect("workspace");
        let paths = paths_in(dir.path(), None);
        let config = AppConfig {
            workspace: workspace.clone(),
            media_dir: workspace.join("media"),
            time_zone: "Mars/Olympus".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: None,
            player: vec!["echo".to_owned()],
        };
        let error = open_runtime(paths, config)
            .map(|_| ())
            .expect_err("an unknown zone must not open");
        let lomo_dir_minted = workspace.join(".lomo").exists();
        assert!(
            matches!(error, TuiError::Config { .. }) && !lomo_dir_minted,
            "a bad config.toml value must fail closed as a config error BEFORE \
             any durable write; instead the error is {error:?} and the \
             workspace already carries .lomo state: {lomo_dir_minted}"
        );
    }

    /// `dir_or` treats a whitespace-only env value as a real directory: `XDG_CONFIG_HOME=" "`
    /// produces ` /lomo`, anchored to the process cwd. Only `is_empty` is
    /// filtered (xdg.rs:223), never whitespace.
    #[test]
    fn whitespace_xdg_dirs_are_not_literal_directories() {
        let paths = resolve_paths(EnvLookup {
            home: Some("/home/u"),
            config: Some(" "),
            state: Some("\t"),
            cache: None,
            runtime: Some("/run/user/1"),
            data: None,
            appdata: None,
            localappdata: None,
        })
        .expect("resolve");
        assert!(
            paths.config_dir.is_absolute() && paths.state_dir.is_absolute(),
            "a whitespace XDG value must be treated as unset, not as a literal \
             directory name: {:?} {:?}",
            paths.config_dir,
            paths.state_dir
        );
    }

    /// `XDG_DATA_HOME` is the documented fallback for the first-run workspace
    /// (xdg.rs:203-205). A relative value mints `default_workspace` verbatim;
    /// `resolve_paths` accepts it and `mint_first_run_config` later dies with the
    /// generic "workspace path must be absolute" — blaming the workspace key
    /// for a poisoned XDG input.
    #[test]
    fn relative_xdg_data_home_does_not_poison_first_run() {
        let paths = resolve_paths(EnvLookup {
            home: None,
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: Some("relative/data"),
            appdata: None,
            localappdata: None,
        })
        .expect("resolve");
        assert!(
            paths
                .default_workspace
                .as_ref()
                .is_none_or(|path| path.is_absolute()),
            "a relative XDG_DATA_HOME must not mint a relative default \
             workspace — 'no proposal' is honest, 'relative' is poison: {:?}",
            paths.default_workspace
        );
    }

    /// `time_zone` filters `is_empty` (config.rs:169) where `editor` and
    /// `workspace` filter `trim().is_empty` (config.rs:139,154). `time_zone = " "`
    /// is therefore kept verbatim, and `time_zone = ""` silently becomes UTC —
    /// two adjacent emptiness rules, two outcomes, one of them a deferred
    /// session error.
    #[test]
    fn whitespace_time_zone_is_rejected_like_empty_editor() {
        for raw in [
            "workspace = \"/n\"\ntime_zone = \" \"\n",
            "workspace = \"/n\"\ntime_zone = \"UTC \"\n",
            "workspace = \"/n\"\neditor = [\" \"]\n",
            "workspace = \"/n\"\neditor = [\"\", \"hx\"]\n",
            "workspace = \"/n\"\nplayer = [\"\", \"mpv\"]\n",
        ] {
            assert!(
                parse_config_toml(raw, None, None).is_err(),
                "whitespace/empty-headed values must fail closed like the rest \
                 of the file: {raw}"
            );
        }
    }

    /// The settings screen renders `editor: {argv.join(" ")}` (queries.rs:316-321).
    /// Copying that rendering back — `editor = "kak -e"` — parses as
    /// `TomlEditor::Program`, ONE argv element with a space inside, which
    /// `Command::new` cannot spawn. The display teaches a syntax that silently
    /// changes argv.
    #[test]
    fn settings_editor_rendering_round_trips_to_the_same_argv() {
        let config =
            parse_config_toml("workspace = \"/n\"\neditor = [\"kak\", \"-e\"]", None, None)
                .expect("parse");
        let rendered = config
            .editor
            .as_ref()
            .map_or_else(String::new, |argv| argv.join(" "));
        assert_eq!(rendered, "kak -e", "control: the join the screen prints");
        let reparsed = parse_config_toml(
            &format!("workspace = \"/n\"\neditor = \"{rendered}\"\n"),
            None,
            None,
        )
        .expect("the rendered string still parses");
        let argv = resolve_editor(reparsed.editor.as_deref(), None, None).expect("resolve");
        assert_eq!(
            argv.program, "kak",
            "the settings rendering must round-trip to the same argv; the \
             string form silently produces an unspawnable program: {argv:?}"
        );
    }

    /// The positional workspace flag is honest: it binds only this run when a
    /// config already exists, and on first install it seeds the wizard's
    /// *proposal* — written to config.toml only once the user confirms.
    #[test]
    fn cli_workspace_flag_binds_this_run_and_only_proposes_on_first_install() {
        let help = render_help();
        assert!(
            help.contains("confirm"),
            "the flag must admit first-run writes need confirmation: {help}"
        );
        let dir = tempdir().expect("tmp");
        let cli_workspace = dir.path().join("cli-vault");
        let default = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(default));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, Some(&cli_workspace)).expect("first run with flag")
        else {
            panic!("missing config.toml must probe FirstRun")
        };
        assert_eq!(
            proposal.workspace, cli_workspace,
            "the flag seeds the wizard's proposal on first install"
        );
        assert!(
            !file.exists(),
            "the flag alone must not mint config.toml before confirmation"
        );
        // With a config already on disk the flag is a per-run bind, and the
        // file on disk keeps its own workspace.
        mint_config(&file, &mintable(&proposal)).expect("confirmed mint");
        let ConfigProbe::Ready { config, .. } =
            probe_config(&paths, Some(Path::new("/this-run"))).expect("reprobe")
        else {
            panic!("a minted config must probe Ready")
        };
        assert_eq!(config.workspace, Path::new("/this-run"));
    }

    /// The wizard's confirmed config, built straight from the probe proposal —
    /// the same shape `confirm_setup` mints after user confirmation.
    fn mintable(proposal: &lomo_tui::config::ConfigProposal) -> AppConfig {
        AppConfig {
            media_dir: proposal.workspace.join("media"),
            workspace: proposal.workspace.clone(),
            time_zone: proposal.time_zone.clone(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: None,
            player: lomo_tui::config::default_player(),
        }
    }

    /// A deleted config on an initialized install must not silently re-mint a
    /// `~/Notes` binding: the `initialized` marker records the real workspace
    /// and the wizard proposes it back — flagged as previously initialized.
    #[test]
    fn a_deleted_config_proposes_the_recorded_workspace() {
        let dir = tempdir().expect("tmp");
        let custom = dir.path().join("custom-vault");
        let default = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(default));
        std::fs::create_dir_all(&custom).expect("custom workspace");
        lomo_tui::xdg::mark_initialized(&paths, &custom).expect("marker");
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, None).expect("probe after delete")
        else {
            panic!("deleted config on an initialized install is still FirstRun")
        };
        assert!(
            proposal.previously_initialized,
            "the marker must flag this as a deleted config, not a fresh install"
        );
        assert_eq!(
            proposal.workspace, custom,
            "the marker's recorded workspace wins over the ~/Notes default"
        );
        assert!(
            !file.exists() && !paths.config_dir.join("config.toml").exists(),
            "recovery is a proposal, not a silent re-mint"
        );
    }

    // ---- GREEN controls: the contracts that do hold today ----

    /// The workspace key is required and empty values fail closed — the one
    /// place fail-closed parsing already works.
    #[test]
    fn workspace_field_fails_closed() {
        parse_config_toml("editor = \"hx\"\n", None, None).expect_err("missing workspace");
        parse_config_toml("workspace = \"\"\n", None, None).expect_err("empty workspace");
        parse_config_toml("workspace = \"rel/x\"\n", None, None).expect_err("relative workspace");
    }

    /// $VISUAL/$EDITOR DO split on whitespace — the asymmetry with the config
    /// string form is the defect, not splitting itself.
    #[test]
    fn env_editor_spec_splits_whitespace() {
        let argv = resolve_editor(None, Some("code --wait"), None).expect("env editor");
        assert_eq!(argv.program, "code");
        assert_eq!(argv.args, vec!["--wait".to_owned()]);
    }

    /// `date_format` is the one `FileConfig` value validated where it is parsed
    /// (config.rs:147-152 -> `parse_pattern`), proving the layer for `time_zone`
    /// already exists.
    #[test]
    fn date_format_fails_closed_at_parse() {
        assert!(
            parse_config_toml("workspace = \"/n\"\ndate_format = \"yyyy\"\n", None, None).is_err(),
            "an unknown pattern must be a config error"
        );
        let parsed = parse_config_toml(
            "workspace = \"/n\"\ndate_format = \"MM-dd-yyyy\"\n",
            None,
            None,
        )
        .expect("a supported pattern parses");
        assert_eq!(
            parsed.date_format,
            lomo_application::calendar::DateFormat::MmDdYyyyHyphen
        );
    }

    /// Settings IS reachable: the ':' palette lists it under Pages
    /// (menu.rs:146-165). No direct key exists — this locks the one entry
    /// point that works today.
    #[test]
    fn settings_is_reachable_through_the_command_palette() {
        let model = model_with_memos(1, 80, 24).expect("feed");
        let picker = Picker {
            kind: PickerKind::Palette {
                item: lomo_tui::model::PaletteItem::None,
                scope: lomo_tui::model::PaletteScope::All,
            },
            text: lomo_tui::input::TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = lomo_tui::menu::entries(&model, &picker);
        assert!(
            entries
                .iter()
                .any(|entry| entry.command == Command::Goto(Screen::Settings)),
            "the palette must expose navigation to Settings"
        );
    }

    /// ':' and '?' — the keys the status bar actually advertises on auxiliary
    /// views — are bound in `browse_key`, proving the dispatch table itself is
    /// not broken, only the --help text.
    #[test]
    fn advertised_chrome_keys_are_bound() {
        let model = model_with_memos(1, 80, 24).expect("feed");
        assert_eq!(
            command_from_key(
                KeyEvent::new(KeyCode::Char(':'), KeyModifiers::NONE),
                &model
            ),
            Some(Command::Palette)
        );
        assert_eq!(
            command_from_key(
                KeyEvent::new(KeyCode::Char('?'), KeyModifiers::NONE),
                &model
            ),
            Some(Command::Help)
        );
    }
}
