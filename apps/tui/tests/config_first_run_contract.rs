// adversarial-reaudit: independent re-verification of the configuration,
// first-run and capture-recovery fixes (E-01..E-19, A-16, D-11, D-14, F-09).
//
// # Behavior Contract
//
// Capability: `config.toml` is the single typed source of truth — parsing is
// full validation at the earliest boundary; the Settings screen and the TOML
// serializer are two projections of one field registry; hot fields apply live
// while restart-required fields are explicitly named; the first-run wizard
// produces no persistent side effect before confirmation; a torn or invalid
// config never replaces the last valid runtime state; a corrupt capture
// draft is set aside with evidence, never silently discarded.
//
// Owning layer: `config.rs` (registry/parse/mint/render), `ops.rs`
// (open/save/reload), `input_update.rs` (wizard + inline edit),
// `edit_flow.rs` (external `e` edit), `drafts.rs` (F-09), `xdg.rs`
// (env marker/paths), `messages.rs`/`host.rs` (reload landing/coalescing).
//
// Given/When/Then (probe catalogue):
// - Boundary parsing: non-IANA zones, Windows zone names, case mutations,
//   whitespace padding; `$ENV`/`..`/relative workspace forms; control
//   characters through the minted-file round trip; duplicate keys, nested
//   tables, type confusion, BOM and CRLF; quoted/env/empty command specs.
// - Hot reload: a rewrite applies editor/player live and names
//   workspace/time_zone restart-required; a torn intermediate file keeps the
//   last valid live + on-disk state, raises a visible failure and recovers on
//   the next valid write; rapid rewrites land the final state; a deleted
//   file fails loudly without touching live config.
// - First run: Esc leaves zero side effects; invalid wizard fields stay
//   editable with an inline error; the confirmed mint is the first durable
//   write and records the `initialized` marker; a corrupt marker cannot
//   brick the recovery proposal; the CLI override proposes but never
//   persists across launches.
// - Settings: an invalid inline value is rejected before the file changes;
//   a mid-edit reload keeps the edit buffer; the `e` flow installs only a
//   validated draft and refuses to clobber a concurrently-written file;
//   a save preserves externally edited fields it was never shown.
// - F-09: a corrupt/future-schema capture is renamed aside, the composer
//   starts empty, a modal names the backup, a second corruption preserves
//   the first backup, and IO failures surface as typed errors.
//
// TDD proof: this file is evidence-first — tests asserting the CORRECT
// invariant that still FAIL are genuine residual defects and are kept RED
// deliberately; each is mapped to `audit/09-复审-配置与首启.md`.
//
// Exclusions: real inotify event timing (watcher seams are driven through
// `RuntimeMessage::ConfigChanged`), the real `$EDITOR` binary (a scripted
// CommandRunner drives the `e` flow), and platform-specific branches the CI
// host cannot reach (Windows rename/ACL semantics are source-reviewed).

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, model_with_memos};
    use lomo_tui::{
        config::{
            AppConfig, ConfigProbe, FieldValue, SETTINGS_FIELDS, SettingsField, mint_config,
            parse_config_toml, probe_config, render_config_toml,
        },
        drafts::{capture_path, load_capture},
        edit_flow::complete_edit,
        editor::CommandRunner,
        effects::{EditTarget, Effect, RuntimeMessage},
        error::TuiError,
        event::Command,
        input::TextBuffer,
        messages::apply_message,
        model::{
            AppModel, BadgeClass, InputMode, PendingKind, Screen, SetupState, Severity, Surface,
            View,
        },
        ops::{BootstrapSpec, LaunchConfig, RuntimeSlot, bootstrap, bootstrap_model, execute},
        update::apply_command,
        xdg::{EnvLookup, RuntimePaths, initialized_workspace, mark_initialized, resolve_paths},
    };
    #[cfg(unix)]
    use std::os::unix::process::ExitStatusExt;
    #[cfg(windows)]
    use std::os::windows::process::ExitStatusExt;
    use std::{
        path::{Path, PathBuf},
        process::ExitStatus,
        sync::mpsc::sync_channel,
    };
    use tempfile::tempdir;

    // ---------- fixtures ----------

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

    fn parse(raw: &str) -> Result<AppConfig, TuiError> {
        parse_config_toml(raw, None, None)
    }

    /// The shape `confirm_setup` mints — a complete config for the proposal.
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

    /// The file the runtime's settings machinery reads and writes.
    fn config_file_of(fixture: &RuntimeFixture) -> PathBuf {
        lomo_tui::config::config_file(&fixture.runtime.paths)
    }

    fn write_config(fixture: &RuntimeFixture, raw: &str) {
        std::fs::create_dir_all(&fixture.runtime.paths.config_dir).expect("cfg dir");
        std::fs::write(config_file_of(fixture), raw).expect("write config.toml");
    }

    /// A well-formed file for the fixture's workspace; `extra` carries every
    /// remaining key (`time_zone`, `editor`, …) verbatim.
    fn write_valid_config(fixture: &RuntimeFixture, extra: &str) {
        write_config(
            fixture,
            &format!(
                "workspace = \"{}\"\n{extra}",
                fixture.runtime.workspace.display()
            ),
        );
    }

    fn outbox() -> lomo_tui::executor::Outbox {
        let (sender, _inbox) = sync_channel(256);
        lomo_tui::executor::Outbox::new(sender)
    }

    /// Run one effect synchronously the way a lane worker would, then land
    /// the reply chain on the model.
    fn run_effect_on(
        runtime: &lomo_tui::ops::TuiRuntime,
        model: &mut AppModel,
        effect: Effect,
    ) -> Result<(), TuiError> {
        let mut pending = Some(effect);
        while let Some(effect) = pending.take() {
            let reply = execute(
                runtime,
                &effect,
                &outbox(),
                &lomo_tui::model::CancelToken::live(),
            )?;
            pending = apply_message(model, reply);
        }
        Ok(())
    }

    /// Deliver a `ConfigChanged` observation and run the resulting
    /// `ReloadConfig` effect the way the Maint lane would.
    fn config_changed(fixture: &RuntimeFixture, model: &mut AppModel) -> Result<(), TuiError> {
        let Some(effect) = apply_message(model, RuntimeMessage::ConfigChanged) else {
            return Err(TuiError::config("ConfigChanged must issue a reload"));
        };
        if !matches!(effect, Effect::ReloadConfig { .. }) {
            return Err(TuiError::config(format!(
                "expected ReloadConfig, got {effect:?}"
            )));
        }
        run_effect_on(&fixture.runtime, model, effect)
    }

    /// A reload attempt that fails on the lane becomes a `Failed` receipt —
    /// drive the whole delivery the way the executor would.
    fn config_changed_expect_failure(
        fixture: &RuntimeFixture,
        model: &mut AppModel,
    ) -> Result<(), TuiError> {
        let Some(Effect::ReloadConfig { req }) =
            apply_message(model, RuntimeMessage::ConfigChanged)
        else {
            return Err(TuiError::config("ConfigChanged must issue a reload"));
        };
        let outcome = execute(
            &fixture.runtime,
            &Effect::ReloadConfig { req },
            &outbox(),
            &lomo_tui::model::CancelToken::live(),
        );
        let Err(error) = outcome else {
            return Err(TuiError::config(
                "fixture: the written config must fail to parse",
            ));
        };
        let landed = apply_message(
            model,
            RuntimeMessage::Failed {
                req,
                diagnostic: error.to_string(),
            },
        );
        if landed.is_some() {
            return Err(TuiError::config(
                "a failure receipt must emit no further effect",
            ));
        }
        Ok(())
    }

    fn settings_model(fixture: &RuntimeFixture) -> Result<AppModel, Box<dyn std::error::Error>> {
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))?;
        command(
            &fixture.runtime,
            &mut model,
            Command::Goto(Screen::Settings),
        )?;
        if !matches!(model.view, View::Settings(_)) {
            return Err(std::io::Error::other("navigation must land on the settings view").into());
        }
        Ok(model)
    }

    fn badge_of(model: &AppModel, class: BadgeClass) -> Option<String> {
        model
            .badges
            .iter()
            .find(|badge| badge.class == class)
            .map(|badge| badge.text.clone())
    }

    /// Scripted editor: writes `content` into the draft (argv's last element),
    /// optionally mutating another file mid-session to emulate a concurrent
    /// external write during `$EDITOR`.
    struct Scripted {
        content: String,
        exit: i32,
        meanwhile: Option<(PathBuf, String)>,
    }

    impl CommandRunner for Scripted {
        fn run_foreground(
            &self,
            _program: &str,
            args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            if let Some((path, content)) = &self.meanwhile {
                std::fs::write(path, content)?;
            }
            std::fs::write(
                args.last()
                    .ok_or_else(|| std::io::Error::other("missing draft"))?,
                &self.content,
            )?;
            #[cfg(unix)]
            return Ok(ExitStatus::from_raw(self.exit));
            #[cfg(windows)]
            return Ok(ExitStatus::from_raw(self.exit as u32));
            #[cfg(not(any(unix, windows)))]
            unreachable!("scripted editor exit status has no portable constructor")
        }

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn lomo_tui::editor::ManagedChild>, std::io::Error> {
            Err(std::io::Error::other("the scripted editor never spawns"))
        }
    }

    // ---------- boundary: time_zone ----------

    /// `time_zone` must be a real IANA/tzdb name — not a POSIX offset string,
    /// not a Windows display name, not a look-alike. `UTC+8` reads like
    /// UTC+8 to a user but is not a zone; `Asia/Beijing` never existed.
    #[test]
    fn time_zone_rejects_non_iana_forms() {
        for zone in [
            "UTC+8",
            "GMT+8",
            "Asia/Beijing",
            "China Standard Time",
            "W. Europe Standard Time",
            "+08:00",
            "utc+8",
            "CST",
        ] {
            assert!(
                parse(&format!("workspace = \"/n\"\ntime_zone = \"{zone}\"\n")).is_err(),
                "non-IANA zone '{zone}' must be a config error at parse"
            );
        }
        // Legal controls that must keep parsing.
        for zone in [
            "UTC",
            "Asia/Shanghai",
            "Asia/Tokyo",
            "America/New_York",
            "Europe/Berlin",
            "GMT",
            "Etc/UTC",
            "EST5EDT",
        ] {
            assert!(
                parse(&format!("workspace = \"/n\"\ntime_zone = \"{zone}\"\n")).is_ok(),
                "a real tzdb zone '{zone}' must parse"
            );
        }
    }

    /// `Etc/GMT+8` is a legal tzdb name — but it means UTC-8 (the Etc zones
    /// invert the sign for POSIX compatibility). Accepting it is technically
    /// correct validation; this probe RECORDS the accepted value so the trap
    /// is visible in the audit trail rather than mistaken for "UTC+8 works".
    #[test]
    fn etc_gmt_zones_are_valid_but_sign_inverted() {
        let config = parse("workspace = \"/n\"\ntime_zone = \"Etc/GMT+8\"\n")
            .expect("Etc/GMT+8 is a real tzdb entry");
        assert_eq!(config.time_zone, "Etc/GMT+8");
        // 2026-06-01T00:00:00Z renders as 2026-05-31 in Etc/GMT+8 (UTC-8) —
        // prove the sign inversion is real, not just a name quirk.
        let stamp = lomo_application::calendar::journal_stamp(
            1_780_272_000_000, // 2026-06-01T00:00:00Z
            "Etc/GMT+8",
            lomo_application::calendar::DateFormat::YyyyMmDdHyphen,
        )
        .expect("stamp");
        assert_eq!(
            stamp.filename.trim_end_matches(".md"),
            "2026-05-31",
            "Etc/GMT+8 must behave as UTC-8 — a user who wanted China time \
             gets a day half a world behind"
        );
    }

    /// Whitespace is part of the zone contract — a padded or interior-space
    /// name must not bind. Case is deliberately NOT part of it: jiff's tzdb
    /// lookup is ASCII-case-insensitive (`cmp_ignore_ascii_case`), so a
    /// case-mutated spelling binds the same zone — tolerant, and the typed
    /// string is preserved verbatim in the file until the next save.
    #[test]
    fn time_zone_case_and_whitespace_rules() {
        for zone in ["utc ", " UTC", "Asia /Shanghai", "\tUTC", "Asia/Shang hai"] {
            assert!(
                parse(&format!("workspace = \"/n\"\ntime_zone = \"{zone}\"\n")).is_err(),
                "zone '{zone}' must be rejected"
            );
        }
        // Recorded permissive aliases: jiff binds them all to the real zone;
        // AppConfig keeps the as-typed string.
        for (typed, zone) in [("utc", "utc"), ("asia/shanghai", "asia/shanghai")] {
            let config = parse(&format!("workspace = \"/n\"\ntime_zone = \"{typed}\"\n"))
                .unwrap_or_else(|e| panic!("case-mutated '{typed}' binds via jiff: {e}"));
            assert_eq!(config.time_zone, zone);
        }
    }

    // ---------- boundary: workspace / media_dir ----------

    /// Environment references, parent traversal and relative forms must not
    /// silently anchor the workspace to the process cwd — they are literal
    /// strings a hand-edit produced, and the parser must say so.
    #[test]
    fn workspace_rejects_env_refs_parents_and_relative_forms() {
        for raw in [
            "workspace = \"$HOME/notes\"",
            "workspace = \"${HOME}/notes\"",
            "workspace = \"$NOTES\"",
            "workspace = \"notes\"",
            "workspace = \"./notes\"",
            "workspace = \"../notes\"",
            "workspace = \"/a/../b\"",
            "workspace = \"~root/notes\"",
            "workspace = \"%APPDATA%\\\\notes\"",
            "media_dir = \"$MEDIA\"",
            "media_dir = \"media\"",
            "media_dir = \"~/m/..\"",
        ] {
            let text = if raw.starts_with("media_dir") {
                format!("workspace = \"/n\"\n{raw}\n")
            } else {
                format!("{raw}\n")
            };
            assert!(
                parse(&text).is_err(),
                "the poisoned form must be a config error: {text}"
            );
        }
    }

    /// A workspace may be a path a shell cannot spell — the minted file must
    /// still re-parse to the identical config, or the install bricks on the
    /// second launch.
    #[cfg(unix)]
    #[test]
    fn minted_workspace_paths_with_control_chars_round_trip() {
        let root = tempdir().expect("tmp");
        for name in [
            "bell\u{7}notes",
            "line\nbreak",
            "tab\there",
            "quote\"dir",
            "back\\slash",
            "space dir",
        ] {
            let workspace = root.path().join(name);
            let config = AppConfig {
                media_dir: workspace.join("media"),
                workspace,
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                editor: Some(vec!["hx".to_owned()]),
                player: vec!["mpv".to_owned()],
            };
            let rendered = render_config_toml(&config);
            let reparsed = parse(&rendered).unwrap_or_else(|error| {
                panic!("the minted file for {name:?} must re-parse: {error}\n{rendered}")
            });
            assert_eq!(
                reparsed, config,
                "a minted config must survive its own serializer for {name:?}"
            );
        }
    }

    /// A trailing slash is an absolute path a user might paste — record the
    /// accepted normalization, and that `.lomo`-named and deeply-nested
    /// absolute paths are legal bindings. Path feasibility (existence) is
    /// checked at open, never at parse.
    #[test]
    fn workspace_accepts_absolute_forms_users_paste() {
        for raw in ["/notes/", "/notes/.lomo", "/a/b/c/d/e/f/g"] {
            assert!(
                parse(&format!("workspace = \"{raw}\"\n")).is_ok(),
                "an absolute path must bind as written: {raw}"
            );
        }
        // An overlong path fails at creation, not at parse — validation
        // answers for syntax, the filesystem answers for feasibility.
        let long = format!("/{}", "x".repeat(400));
        assert!(
            parse(&format!("workspace = \"{long}\"\n")).is_ok(),
            "a very long absolute path is still a well-formed binding"
        );
    }

    // ---------- boundary: TOML shape attacks ----------

    /// The file must fail closed on every malformed shape: duplicate keys,
    /// nested tables, case-mutated keys, type confusion, trailing garbage.
    #[test]
    fn toml_shape_attacks_are_config_errors() {
        for raw in [
            "workspace = \"/a\"\nworkspace = \"/b\"\n",
            "workspace = \"/n\"\n[other]\nkey = 1\n",
            "Workspace = \"/n\"\n",
            "workspace = 42\n",
            "workspace = [\"/n\"]\n",
            "workspace = { path = \"/n\" }\n",
            "workspace = \"/n\" trailing\n",
            "workspace = \"/n\"\ntime_zone = 8\n",
            "workspace = \"/n\"\ndate_format = true\n",
            "workspace = \"/n\"\neditor = 42\n",
            "workspace = \"/n\"\neditor = [\"a\", 1]\n",
            "workspace = \"/n\"\nplayer = [\"mpv\"] extra\n",
        ] {
            assert!(
                parse(raw).is_err(),
                "malformed shape must be a config error: {raw:?}"
            );
        }
    }

    /// Editors that prepend a UTF-8 BOM or save CRLF must not break the file
    /// — the parser boundary treats encoding noise as encoding, not content.
    #[test]
    fn bom_and_crlf_configs_still_parse() {
        let config = parse("\u{feff}workspace = \"/notes\"\ntime_zone = \"UTC\"\n")
            .expect("a BOM-prefixed config must parse");
        assert_eq!(config.workspace, Path::new("/notes"));
        let crlf =
            parse("workspace = \"/notes\"\r\neditor = \"hx --wait\"\r\n").expect("CRLF must parse");
        assert_eq!(
            crlf.editor,
            Some(vec!["hx".to_owned(), "--wait".to_owned()])
        );
    }

    // ---------- boundary: command specifications ----------

    /// `editor`/`player` strings parse with real quoting rules — the field
    /// table promises one syntax for both spellings (string or argv array).
    #[test]
    fn command_specs_apply_shell_rules_without_a_shell() {
        let quoted = parse("workspace = \"/n\"\neditor = \"'a b' --flag\"\n").expect("quoted");
        assert_eq!(
            quoted.editor,
            Some(vec!["a b".to_owned(), "--flag".to_owned()])
        );
        let nested = parse("workspace = \"/n\"\neditor = \"\\\"a b\\\" -x\"\n").expect("nested");
        assert_eq!(nested.editor, Some(vec!["a b".to_owned(), "-x".to_owned()]));
        let env_form = parse("workspace = \"/n\"\neditor = \"env FOO=x prog --v\"\n")
            .expect("env wrapper is a literal argv");
        assert_eq!(
            env_form.editor,
            Some(vec![
                "env".to_owned(),
                "FOO=x".to_owned(),
                "prog".to_owned(),
                "--v".to_owned()
            ])
        );
        let player = parse("workspace = \"/n\"\nplayer = \"mpv --fs\"\n").expect("player str");
        assert_eq!(player.player, vec!["mpv".to_owned(), "--fs".to_owned()]);
        for raw in [
            "workspace = \"/n\"\neditor = \"'unclosed\"\n",
            "workspace = \"/n\"\neditor = \"prog 'a\"\n",
            "workspace = \"/n\"\neditor = []\n",
            "workspace = \"/n\"\neditor = [\"prog\", \"\"]\n",
            "workspace = \"/n\"\neditor = [\"prog\", \"  \"]\n",
            "workspace = \"/n\"\neditor = \"  \"\n",
            "workspace = \"/n\"\nplayer = []\n",
            "workspace = \"/n\"\nplayer = [\"mpv\", \"\"]\n",
            "workspace = \"/n\"\nplayer = \"\"\n",
        ] {
            assert!(
                parse(raw).is_err(),
                "an unusable command spec must fail closed: {raw}"
            );
        }
    }

    /// The Settings edit seed is the row's `display_value`: for every field
    /// the text the user is shown must parse back to the SAME typed value —
    /// a seed that mangles argv under a no-op Enter silently rewrites the
    /// file with a different program.
    #[test]
    fn display_value_round_trips_through_parse_edit() {
        let config = AppConfig {
            workspace: PathBuf::from("/notes dir"),
            media_dir: PathBuf::from("/notes dir/media"),
            time_zone: "Asia/Shanghai".to_owned(),
            date_format: lomo_application::calendar::DateFormat::YyyyMmDdHyphen,
            // An argv element containing a space — legal via the array form.
            editor: Some(vec!["my editor".to_owned(), "--flag".to_owned()]),
            player: vec!["media player".to_owned(), "--fs".to_owned()],
        };
        for &field in SETTINGS_FIELDS {
            let shown = field.display_value(&config);
            let reparsed = field
                .parse_edit(&shown, Some(Path::new("/home/u")))
                .unwrap_or_else(|error| {
                    panic!(
                        "the seeded edit text for {} must still parse: '{shown}': {error}",
                        field.key()
                    )
                });
            assert_eq!(
                reparsed,
                field.value(&config),
                "a no-op Enter on the seeded text must reproduce the same \
                 value for {} — '{shown}' re-parsed differently",
                field.key()
            );
        }
    }

    /// End-to-end form of the same invariant through the Settings screen:
    /// opening an editor row whose argv element contains a space and pressing
    /// Enter unchanged must leave the file's argv identical.
    #[test]
    fn a_noop_settings_enter_must_not_rewrite_the_editor_argv() {
        let root = tempdir().expect("tmp");
        let workspace = root.path().join("notes");
        std::fs::create_dir_all(&workspace).expect("workspace");
        let config = AppConfig {
            workspace: workspace.clone(),
            media_dir: workspace.join("media"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: Some(vec!["my editor".to_owned(), "--flag".to_owned()]),
            player: vec!["mpv".to_owned()],
        };
        let runtime = lomo_tui::ops::open_runtime(paths_in(root.path(), None), config.clone())
            .expect("runtime");
        let file = lomo_tui::config::config_file(&runtime.paths);
        lomo_tui::config::save_config(&file, &config).expect("seed config");
        let mut model = bootstrap_model(&runtime, AppModel::new(80, 24)).expect("model");
        command(&runtime, &mut model, Command::Goto(Screen::Settings)).expect("settings");
        // Select the editor row.
        for _ in 0..SETTINGS_FIELDS.len() {
            if let View::Settings(view) = &model.view
                && view.selected_field() == Some(SettingsField::Editor)
            {
                break;
            }
            command(&runtime, &mut model, Command::Move(1)).expect("move");
        }
        let View::Settings(view) = &model.view else {
            panic!("still on the settings view")
        };
        assert_eq!(
            view.selected_field(),
            Some(SettingsField::Editor),
            "the probe must land on the editor row"
        );
        command(&runtime, &mut model, Command::Accept).expect("open the row editor");
        let InputMode::Setting(edit) = &model.input else {
            panic!("Accept must open the inline editor: {:?}", model.input)
        };
        assert_eq!(
            edit.text.text(),
            "'my editor' --flag",
            "control: the seed is the shell-quoted argv — the only text form \
             that parses back to the same typed value"
        );
        // Press Enter unchanged — no user edit happened.
        if let Some(effect) = apply_command(&mut model, Command::Accept) {
            run_effect_on(&runtime, &mut model, effect).expect("save effect");
        }
        let saved = parse_config_toml(
            &std::fs::read_to_string(&file).expect("config file"),
            None,
            None,
        )
        .expect("the file must still parse");
        assert_eq!(
            saved.editor, config.editor,
            "an unchanged Enter must not silently rewrite the argv"
        );
    }

    // ---------- hot reload ----------

    /// A rewritten config lands through `ConfigChanged` → `ReloadConfig`:
    /// hot fields mutate live state, restart-required fields are NAMED.
    #[test]
    fn reload_applies_hot_fields_and_names_restart_fields() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        write_config(
            &fixture,
            &format!(
                "workspace = \"{}\"\ntime_zone = \"Asia/Tokyo\"\neditor = [\"kak\", \"-e\"]\nplayer = [\"mpv\"]\n",
                fixture.runtime.workspace.display()
            ),
        );
        config_changed(&fixture, &mut model).expect("reload lands");
        let live = fixture.runtime.config();
        assert_eq!(
            live.editor,
            Some(vec!["kak".to_owned(), "-e".to_owned()]),
            "a hot field must apply to the running session"
        );
        assert_eq!(
            live.player,
            vec!["mpv".to_owned()],
            "player is hot and must apply"
        );
        assert_eq!(
            live.time_zone, "UTC",
            "time_zone is bound into the session — it must wait for restart"
        );
        let on_disk = fixture.runtime.file_config();
        assert_eq!(on_disk.time_zone, "Asia/Tokyo");
        let status = model
            .status
            .clone()
            .expect("an applied reload must leave a status");
        assert!(
            status.contains("time_zone"),
            "the status must name the restart-required field: {status}"
        );
    }

    /// A torn write — the file a watcher can observe mid-replacement — must
    /// not replace the last validated state: the reload fails loudly, the
    /// live and on-disk projections stay at the previous config, and the
    /// failure is a persistent badge until a valid write retires it.
    #[test]
    fn a_torn_write_keeps_the_last_valid_config_and_reports() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        write_valid_config(&fixture, "editor = [\"hx\"]");
        config_changed(&fixture, &mut model).expect("baseline reload");
        let live_before = fixture.runtime.config();
        let disk_before = fixture.runtime.file_config();

        // The torn file a reader may see between truncate and rewrite.
        write_config(&fixture, "workspace = \"/n\"\ntime_zo");
        config_changed_expect_failure(&fixture, &mut model).expect("failure lands");
        assert_eq!(
            fixture.runtime.config(),
            live_before,
            "the live config must not move on a failed reload"
        );
        assert_eq!(
            fixture.runtime.file_config(),
            disk_before,
            "the on-disk projection must keep the last validated content"
        );
        assert!(
            badge_of(&model, BadgeClass::Sync).is_some(),
            "a failed reload must leave a persistent Sync badge, not vanish"
        );

        // Deleted entirely: still loud, still nothing lost.
        std::fs::remove_file(config_file_of(&fixture)).expect("delete config");
        config_changed_expect_failure(&fixture, &mut model).expect("missing file fails");
        assert_eq!(fixture.runtime.config(), live_before);
        assert_eq!(fixture.runtime.file_config(), disk_before);
        assert!(badge_of(&model, BadgeClass::Sync).is_some());

        // A subsequent valid write clears the badge and lands.
        write_valid_config(&fixture, "editor = [\"hx\"]");
        config_changed(&fixture, &mut model).expect("recovery reload");
        assert!(
            badge_of(&model, BadgeClass::Sync).is_none(),
            "a successful reload retires the failure badge: {:?}",
            model.badges
        );
    }

    /// Rapid consecutive rewrites must land the final values — coalesced or
    /// not, the last read wins and earlier intermediate states never stick.
    #[test]
    fn rapid_consecutive_rewrites_land_the_final_state() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        for (index, editor) in ["hx", "kak", "nvim"].iter().enumerate() {
            write_valid_config(&fixture, &format!("editor = [\"{editor}\"]"));
            config_changed(&fixture, &mut model)
                .unwrap_or_else(|error| panic!("rewrite {index} must land: {error}"));
        }
        assert_eq!(
            fixture.runtime.config().editor,
            Some(vec!["nvim".to_owned()]),
            "the final write must own the live hot field"
        );
    }

    /// A save through the Settings screen writes the registry-rendered file,
    /// applies hot fields live and names restart-required ones — exactly the
    /// reload rules, so a save cannot bypass the contract.
    #[test]
    fn settings_save_writes_then_applies_by_reload_rules() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings");
        write_valid_config(&fixture, "time_zone = \"UTC\"");
        config_changed(&fixture, &mut model).expect("baseline reload");

        let req = model.request(PendingKind::ConfigReload);
        run_effect_on(
            &fixture.runtime,
            &mut model,
            Effect::SaveSetting {
                req,
                field: SettingsField::Editor,
                value: FieldValue::Editor(Some(vec!["kak".to_owned()])),
            },
        )
        .expect("editor save");
        assert_eq!(
            fixture.runtime.config().editor,
            Some(vec!["kak".to_owned()]),
            "a hot save must apply live"
        );

        let req = model.request(PendingKind::ConfigReload);
        run_effect_on(
            &fixture.runtime,
            &mut model,
            Effect::SaveSetting {
                req,
                field: SettingsField::TimeZone,
                value: FieldValue::TimeZone("Asia/Shanghai".to_owned()),
            },
        )
        .expect("tz save");
        assert_eq!(
            fixture.runtime.config().time_zone,
            "UTC",
            "a restart-required save must not pretend to apply live"
        );
        let persisted = parse_config_toml(
            &std::fs::read_to_string(config_file_of(&fixture)).expect("file"),
            None,
            None,
        )
        .expect("persisted file must parse");
        assert_eq!(persisted.time_zone, "Asia/Shanghai");
        assert_eq!(
            persisted.editor,
            Some(vec!["kak".to_owned()]),
            "the persisted file keeps BOTH saves — the file is the truth"
        );

        // The save's own watcher batch must not regress what was just saved.
        config_changed(&fixture, &mut model).expect("self-notification reload");
        assert_eq!(
            fixture.runtime.file_config().time_zone,
            "Asia/Shanghai",
            "the save's own watcher event must not revert the file truth"
        );
    }

    /// A save builds on `file_config()` — the snapshot the Settings screen
    /// showed. An external write that reached the file but whose
    /// `ConfigChanged` is still in flight must not be clobbered by the save:
    /// the save has to rebase onto the file, not onto its stale snapshot.
    #[test]
    fn a_save_must_not_clobber_an_unreloaded_external_edit() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        write_valid_config(&fixture, "time_zone = \"UTC\"");
        config_changed(&fixture, &mut model).expect("baseline reload");

        // An external edit lands on disk; its watcher event is still queued.
        write_valid_config(&fixture, "time_zone = \"Asia/Tokyo\"");

        // The user saves editor before the reload delivers.
        let req = model.request(PendingKind::ConfigReload);
        run_effect_on(
            &fixture.runtime,
            &mut model,
            Effect::SaveSetting {
                req,
                field: SettingsField::Editor,
                value: FieldValue::Editor(Some(vec!["kak".to_owned()])),
            },
        )
        .expect("editor save");
        let persisted = parse_config_toml(
            &std::fs::read_to_string(config_file_of(&fixture)).expect("file"),
            None,
            None,
        )
        .expect("persisted file must parse");
        assert_eq!(
            persisted.time_zone, "Asia/Tokyo",
            "the external edit that reached the file must survive the save — \
             building the write on the stale in-memory snapshot silently \
             destroys a change the app itself observed"
        );
        assert_eq!(persisted.editor, Some(vec!["kak".to_owned()]));
    }

    /// A reload delivered while an inline edit is open must not destroy the
    /// edit buffer: the Settings view refreshes underneath, the in-flight
    /// text the user typed survives to its own validation.
    #[test]
    fn a_reload_during_inline_edit_keeps_the_edit_buffer() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings");
        write_valid_config(&fixture, "time_zone = \"UTC\"");
        config_changed(&fixture, &mut model).expect("baseline reload");

        command(&fixture.runtime, &mut model, Command::Accept).expect("open editor row");
        let InputMode::Setting(edit) = &mut model.input else {
            panic!("Accept must open the inline editor: {:?}", model.input)
        };
        edit.text = TextBuffer::new("partial typing".to_owned());

        write_valid_config(&fixture, "time_zone = \"Asia/Tokyo\"");
        config_changed(&fixture, &mut model).expect("mid-edit reload");
        let InputMode::Setting(edit) = &model.input else {
            panic!(
                "the in-flight edit must survive the reload: {:?}",
                model.input
            )
        };
        assert_eq!(edit.text.text(), "partial typing");

        // And a FAILED reload mid-edit behaves the same — badge, no clobber.
        write_config(&fixture, "not valid");
        config_changed_expect_failure(&fixture, &mut model).expect("failed reload");
        let InputMode::Setting(edit) = &model.input else {
            panic!(
                "a failed reload must not destroy the edit: {:?}",
                model.input
            )
        };
        assert_eq!(edit.text.text(), "partial typing");
    }

    /// An invalid inline value is refused before the file is touched: the
    /// editor stays open with the diagnostic and `config.toml` keeps its
    /// bytes.
    #[test]
    fn an_invalid_inline_edit_never_reaches_the_file() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings");
        write_valid_config(&fixture, "time_zone = \"UTC\"");
        config_changed(&fixture, &mut model).expect("baseline reload");
        let before = std::fs::read_to_string(config_file_of(&fixture)).expect("file");

        for _ in 0..SETTINGS_FIELDS.len() {
            if let View::Settings(view) = &model.view
                && view.selected_field() == Some(SettingsField::TimeZone)
            {
                break;
            }
            command(&fixture.runtime, &mut model, Command::Move(1)).expect("move");
        }
        let View::Settings(view) = &model.view else {
            panic!("still on the settings view")
        };
        assert_eq!(view.selected_field(), Some(SettingsField::TimeZone));
        command(&fixture.runtime, &mut model, Command::Accept).expect("open editor row");
        let InputMode::Setting(edit) = &mut model.input else {
            panic!("Accept must open the inline editor")
        };
        edit.text = TextBuffer::new("Mars/Olympus".to_owned());
        let effect = apply_command(&mut model, Command::Accept);
        assert!(
            effect.is_none(),
            "an invalid edit must not dispatch a write: {effect:?}"
        );
        let InputMode::Setting(edit) = &model.input else {
            panic!("the refused edit must stay open: {:?}", model.input)
        };
        assert!(edit.error.is_some(), "the diagnostic lands inline");
        assert_eq!(
            std::fs::read_to_string(config_file_of(&fixture)).expect("file"),
            before,
            "the file must be untouched by a refused edit"
        );
    }

    // ---------- the `e` config edit ----------

    /// `e` on the Settings screen drafts the real file's bytes: a valid draft
    /// installs atomically and queues a reload; an invalid draft is refused
    /// with the draft retained.
    #[test]
    fn external_edit_installs_only_a_validated_draft() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings");
        write_valid_config(&fixture, "time_zone = \"UTC\"\neditor = [\"hx\"]");
        config_changed(&fixture, &mut model).expect("baseline reload");

        // Invalid draft: modal warning, draft retained, file untouched.
        let before = std::fs::read_to_string(config_file_of(&fixture)).expect("file");
        let effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Scripted {
                content: "workspace = \"/n\"\ntime_zone = \"Mars/Olympus\"\n".to_owned(),
                exit: 0,
                meanwhile: None,
            },
            &EditTarget::Config,
            None,
            None,
        )
        .expect("edit flow");
        assert!(effect.is_none(), "an invalid draft must not dispatch");
        let notice = model.notice.clone().expect("the refusal is presented");
        assert_eq!(notice.surface, Surface::Modal);
        assert!(
            notice.lines.iter().any(|line| line.contains("draft")),
            "the modal names the retained draft: {:?}",
            notice.lines
        );
        assert_eq!(
            std::fs::read_to_string(config_file_of(&fixture)).expect("file"),
            before,
            "an invalid draft never clobbers the known-good file"
        );

        // Valid draft: installed, reload dispatched, live hot fields land.
        let Some(effect) = complete_edit(
            &fixture.runtime,
            &mut model,
            &Scripted {
                content: format!(
                    "workspace = \"{}\"\ntime_zone = \"Asia/Tokyo\"\neditor = [\"hx\"]\n",
                    fixture.runtime.workspace.display()
                ),
                exit: 0,
                meanwhile: None,
            },
            &EditTarget::Config,
            None,
            None,
        )
        .expect("edit flow") else {
            panic!("a valid install must dispatch its reload")
        };
        let Effect::ReloadConfig { .. } = effect else {
            panic!("a valid install must queue a reload: {effect:?}")
        };
        run_effect_on(&fixture.runtime, &mut model, effect).expect("reload");
        assert_eq!(
            fixture.runtime.file_config().time_zone,
            "Asia/Tokyo",
            "the installed draft becomes the new file truth"
        );
        assert_eq!(
            fixture.runtime.config().editor,
            Some(vec!["hx".to_owned()]),
            "the hot field in the installed draft applies live"
        );
    }

    /// The `e` draft is read from the file at open; a change that lands on
    /// the file while the editor is open must not be silently overwritten on
    /// save — baseline drift needs detection, not last-writer-wins.
    #[test]
    fn an_external_write_during_edit_must_not_be_clobbered() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = settings_model(&fixture).expect("settings");
        write_valid_config(&fixture, "time_zone = \"UTC\"\neditor = [\"hx\"]");
        config_changed(&fixture, &mut model).expect("baseline reload");

        // While "the editor" holds the draft, the file changes underneath —
        // a concurrent settings save, a synced checkout, a second terminal.
        let concurrent = format!(
            "workspace = \"{}\"\ntime_zone = \"Asia/Tokyo\"\n",
            fixture.runtime.workspace.display()
        );
        // The draft legitimately differs (the user changed date_format) — the
        // install must happen, but only onto the file the user actually saw.
        let _effect = complete_edit(
            &fixture.runtime,
            &mut model,
            &Scripted {
                content: format!(
                    "workspace = \"{}\"\ntime_zone = \"UTC\"\neditor = [\"hx\"]\ndate_format = \"yyyy.MM.dd\"\n",
                    fixture.runtime.workspace.display()
                ),
                exit: 0,
                meanwhile: Some((config_file_of(&fixture), concurrent)),
            },
            &EditTarget::Config,
            None,
            None,
        )
        .expect("edit flow");
        let persisted = std::fs::read_to_string(config_file_of(&fixture)).expect("file");
        let parsed = parse_config_toml(&persisted, None, None).expect("file parses");
        assert_eq!(
            parsed.time_zone, "Asia/Tokyo",
            "the file changed under the open draft — installing the stale \
             draft silently destroys the concurrent write; the save must \
             detect the baseline drift (parsed {parsed:?})"
        );
    }

    // ---------- first run ----------

    /// Esc on the setup wizard must leave literally nothing behind: no
    /// `config.toml`, no workspace, no marker — abort is a pure cancel.
    #[test]
    fn setup_esc_leaves_zero_side_effects() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, None).expect("first-run probe")
        else {
            panic!("missing config.toml must probe FirstRun")
        };
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Bootstrap);
        model.input = InputMode::Setup(SetupState::new(
            file.clone(),
            proposal,
            req,
            paths.home_dir.clone(),
        ));
        let effect = apply_command(&mut model, Command::Back);
        let Some(Effect::Quit { .. }) = effect else {
            panic!("Esc on the wizard must resolve to Quit: {effect:?}")
        };
        assert!(
            matches!(model.input, InputMode::Browse),
            "the wizard mode must be left: {:?}",
            model.input
        );
        assert!(
            !file.exists() && !notes.exists() && !paths.state_dir.join("initialized").exists(),
            "abort must write nothing: no config, no workspace, no marker"
        );
        // A second probe still sees a first run — the abort committed nothing.
        let ConfigProbe::FirstRun { .. } = probe_config(&paths, None).expect("re-probe") else {
            panic!("nothing was persisted; the second launch must see FirstRun again")
        };
    }

    /// An invalid wizard value lands back on the wizard as an inline error —
    /// it never reaches `mint_config`, and the awaiting flag blocks a
    /// double-Enter from minting twice.
    #[test]
    fn setup_invalid_values_stay_editable_and_never_mint() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes));
        let ConfigProbe::FirstRun { file, proposal } = probe_config(&paths, None).expect("probe")
        else {
            panic!("FirstRun")
        };
        for (workspace, zone, must_mention) in [
            ("relative/path", "UTC", "workspace"),
            ("/abs/path", "Mars/Olympus", "time_zone"),
        ] {
            let mut model = AppModel::new(80, 24);
            let req = model.request(PendingKind::Bootstrap);
            let mut setup = SetupState::new(file.clone(), proposal.clone(), req, None);
            setup.workspace = TextBuffer::new(workspace.to_owned());
            setup.time_zone = TextBuffer::new(zone.to_owned());
            model.input = InputMode::Setup(setup);
            let effect = apply_command(&mut model, Command::Accept);
            assert!(
                effect.is_none(),
                "an invalid wizard value must not dispatch: {effect:?}"
            );
            let InputMode::Setup(setup) = &model.input else {
                panic!("the wizard must stay open: {:?}", model.input)
            };
            assert!(
                setup
                    .error
                    .as_deref()
                    .is_some_and(|error| error.contains(must_mention)),
                "the inline error must name the offending field: {:?}",
                setup.error
            );
            assert!(!file.exists(), "nothing was minted");
        }

        // A valid confirm dispatches exactly once: awaiting swallows the
        // second Enter — the mint request is not re-issued.
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Bootstrap);
        model.input =
            InputMode::Setup(SetupState::new(file.clone(), proposal, req, paths.home_dir));
        let Some(Effect::SetupConfirmed { .. }) = apply_command(&mut model, Command::Accept) else {
            panic!("a valid wizard must confirm: {:?}", model.input)
        };
        let InputMode::Setup(setup) = &model.input else {
            panic!("the wizard stays open awaiting the lane: {:?}", model.input)
        };
        assert!(setup.awaiting);
        assert!(
            apply_command(&mut model, Command::Accept).is_none(),
            "a second Enter while awaiting must be inert"
        );
        assert!(!file.exists(), "minting happens on the lane, not on Enter");
    }

    /// The confirmed mint — run through `ops::bootstrap` the way the host's
    /// `SetupConfirmed → Bootstrap{Mint}` rewrite does — is the first durable
    /// write: `config.toml` parses on re-probe, the workspace exists, and the
    /// `initialized` marker records the confirmed workspace.
    #[test]
    fn confirmed_setup_mints_config_workspace_and_marker() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        let ConfigProbe::FirstRun { file, proposal } = probe_config(&paths, None).expect("probe")
        else {
            panic!("FirstRun")
        };
        let slot = RuntimeSlot::opening();
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Bootstrap);
        let reply = bootstrap(
            &slot,
            &BootstrapSpec {
                paths: paths.clone(),
                launch: LaunchConfig::Mint {
                    file: file.clone(),
                    config: mintable(&proposal),
                },
                width: 80,
                height: 24,
            },
            req,
            &outbox(),
        )
        .expect("the confirmed mint must open");
        assert!(matches!(reply, RuntimeMessage::RuntimeReady { .. }));
        assert!(
            slot.get().is_some(),
            "the runtime must install into the slot"
        );
        assert!(file.exists() && notes.is_dir(), "the mint wrote both");
        let ConfigProbe::Ready { config, .. } = probe_config(&paths, None).expect("re-probe")
        else {
            panic!("a minted config must probe Ready")
        };
        assert_eq!(config.workspace, notes);
        let recorded = initialized_workspace(&paths).expect("marker read");
        assert_eq!(
            recorded.as_deref(),
            Some(notes.as_path()),
            "the marker must record the confirmed workspace"
        );

        // A `Setup` launch reaching the bootstrap lane is a dispatch bug —
        // refused loudly, never silently minted.
        let bad = bootstrap(
            &RuntimeSlot::opening(),
            &BootstrapSpec {
                paths: paths_in(dir.path(), None),
                launch: LaunchConfig::Setup(lomo_tui::ops::SetupSpec {
                    file: dir.path().join("other.toml"),
                    proposal,
                }),
                width: 80,
                height: 24,
            },
            model.request(PendingKind::Bootstrap),
            &outbox(),
        );
        assert!(bad.is_err(), "Setup reaching bootstrap must be refused");
    }

    /// A confirm that fails on the lane — the workspace cannot be created —
    /// lands back on the wizard as an editable error, not a dead screen.
    #[test]
    fn a_failed_mint_returns_the_wizard_with_its_error() {
        let dir = tempdir().expect("tmp");
        let paths = paths_in(dir.path(), Some(dir.path().join("Notes")));
        // A file where the workspace dir would go: create_dir_all fails.
        let blocked = dir.path().join("blocked");
        std::fs::write(&blocked, b"not a dir").expect("blocker file");
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Bootstrap);
        let mut setup = SetupState::new(
            paths.config_dir.join("config.toml"),
            lomo_tui::config::ConfigProposal {
                workspace: blocked.clone(),
                time_zone: "UTC".to_owned(),
                previously_initialized: false,
                recorded_workspace: None,
            },
            req,
            paths.home_dir.clone(),
        );
        setup.awaiting = true;
        model.input = InputMode::Setup(setup);
        let spec = BootstrapSpec {
            paths: paths.clone(),
            launch: LaunchConfig::Mint {
                file: paths.config_dir.join("config.toml"),
                config: AppConfig {
                    workspace: blocked,
                    media_dir: dir.path().join("media"),
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    editor: None,
                    player: lomo_tui::config::default_player(),
                },
            },
            width: 80,
            height: 24,
        };
        let outcome = bootstrap(&RuntimeSlot::opening(), &spec, req, &outbox());
        assert!(outcome.is_err(), "a blocked workspace must fail the mint");
        let diagnostic = outcome.expect_err("checked").to_string();
        let landed = apply_message(&mut model, RuntimeMessage::Failed { req, diagnostic });
        assert!(
            landed.is_none(),
            "a failure receipt emits no further effect"
        );
        let InputMode::Setup(setup) = &model.input else {
            panic!(
                "the failure must land back on the wizard: {:?}",
                model.input
            )
        };
        assert!(
            setup.error.is_some() && !setup.awaiting,
            "the wizard must be editable again with the diagnostic"
        );
    }

    /// A corrupt `initialized` marker cannot tell deletion from first run —
    /// but it must not kill the recovery path. The marker is advisory state:
    /// unreadable CONTENT should degrade to the fresh-install proposal, not
    /// hard-fail `probe_config` blaming "workspace" for a marker-read value.
    #[test]
    fn a_corrupt_initialized_marker_must_not_brick_the_recovery() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes));
        std::fs::create_dir_all(&paths.state_dir).expect("state dir");
        for content in ["relative/garbage", "../escape", "~unexpanded/path"] {
            std::fs::write(paths.state_dir.join("initialized"), content).expect("corrupt marker");
            let probe = probe_config(&paths, None);
            assert!(
                matches!(probe, Ok(ConfigProbe::FirstRun { .. })),
                "a corrupt marker ({content:?}) must degrade to a fresh proposal, \
                 not kill first-run recovery with a misattributed error: {probe:?}"
            );
        }
        // An empty marker is recorded as "no record" already.
        std::fs::write(paths.state_dir.join("initialized"), "  \n").expect("empty marker");
        let ConfigProbe::FirstRun { proposal, .. } =
            probe_config(&paths, None).expect("empty marker probes fresh")
        else {
            panic!("FirstRun")
        };
        assert!(!proposal.previously_initialized);
    }

    /// The marker's recorded workspace wins the proposal only while the file
    /// is missing; a present config makes the marker inert — and a deleted
    /// marker on an initialized install degrades to the fresh proposal.
    #[test]
    fn marker_precedence_and_deletion_semantics() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let recorded = dir.path().join("other-vault");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        std::fs::create_dir_all(&recorded).expect("recorded workspace");
        mark_initialized(&paths, &recorded).expect("marker");

        // Config present → Ready wins; the marker is never even consulted.
        std::fs::create_dir_all(&paths.config_dir).expect("cfg");
        std::fs::write(
            paths.config_dir.join("config.toml"),
            format!("workspace = \"{}\"\n", notes.display()),
        )
        .expect("config");
        let ConfigProbe::Ready { config, .. } = probe_config(&paths, None).expect("ready") else {
            panic!("a real config probes Ready")
        };
        assert_eq!(
            config.workspace, notes,
            "the file's truth wins over the marker"
        );

        // Marker deleted → the proposal is the fresh default again.
        std::fs::remove_file(paths.state_dir.join("initialized")).expect("remove marker");
        std::fs::remove_file(paths.config_dir.join("config.toml")).expect("remove config");
        let ConfigProbe::FirstRun { proposal, .. } =
            probe_config(&paths, None).expect("fresh again")
        else {
            panic!("FirstRun")
        };
        assert!(
            !proposal.previously_initialized && proposal.workspace == notes,
            "a deleted marker reads as a genuinely new install: {proposal:?}"
        );
    }

    /// The CLI override is a per-run proposal on first install: it seeds the
    /// wizard, is never written by the probe itself, and a second launch
    /// without it must not see it persisted.
    #[test]
    fn cli_override_is_a_proposal_that_never_persists() {
        let dir = tempdir().expect("tmp");
        let cli = dir.path().join("cli-vault");
        let default = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(default.clone()));
        let ConfigProbe::FirstRun { file, proposal } =
            probe_config(&paths, Some(&cli)).expect("first probe")
        else {
            panic!("FirstRun")
        };
        assert_eq!(proposal.workspace, cli);
        // Abort without confirming (the Esc path): probe again as the second
        // launch would — with no override this time.
        assert!(!file.exists() && !cli.exists());
        let ConfigProbe::FirstRun {
            proposal: second, ..
        } = probe_config(&paths, None).expect("second launch probe")
        else {
            panic!("FirstRun again")
        };
        assert_eq!(
            second.workspace, default,
            "the unconfirmed override must not leak into the second launch"
        );
    }

    // ---------- F-09: corrupt capture recovery ----------

    /// A corrupt capture is renamed aside to `<file>.corrupt`, the composer
    /// starts empty and the rename is reported — the draft is never silently
    /// discarded.
    #[test]
    fn corrupt_capture_is_set_aside_with_evidence() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::create_dir_all(path.parent().expect("parent")).expect("drafts dir");
        let garbage = b"{\"schema\":1, oops not json".to_vec();
        std::fs::write(&path, &garbage).expect("corrupt capture");
        let loaded = load_capture(&fixture.runtime).expect("corrupt must recover");
        assert!(
            loaded.composer.text.text().is_empty(),
            "the composer starts empty after recovery"
        );
        let backup = loaded
            .recovered_corrupt
            .expect("the renamed-aside path is reported");
        assert!(
            backup.to_string_lossy().ends_with(".corrupt"),
            "the backup keeps the .corrupt suffix: {backup:?}"
        );
        assert_eq!(
            std::fs::read(&backup).expect("backup exists"),
            garbage,
            "the corrupt bytes are preserved verbatim for forensics"
        );
        assert!(!path.exists(), "the live path is clean for the next write");
    }

    /// A future/unsupported schema is corruption, not a brick: the file is
    /// set aside exactly like malformed JSON.
    #[test]
    fn future_schema_capture_is_set_aside_not_bricked() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::create_dir_all(path.parent().expect("parent")).expect("drafts dir");
        std::fs::write(
            &path,
            b"{\"schema\":2,\"revision\":1,\"content\":\"next\",\"phase\":{\"state\":\"editing\"}}",
        )
        .expect("future capture");
        let loaded = load_capture(&fixture.runtime).expect("must recover");
        assert!(loaded.composer.text.text().is_empty());
        assert!(loaded.recovered_corrupt.is_some());
    }

    /// A second corruption while `.corrupt` already exists must not silently
    /// destroy the first backup — each recovery needs collision-safe naming
    /// (and on Windows `rename` refuses an existing target outright).
    #[test]
    fn every_corruption_preserves_its_backup() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::create_dir_all(path.parent().expect("parent")).expect("drafts dir");
        std::fs::write(&path, b"first garbage").expect("corrupt #1");
        let first = load_capture(&fixture.runtime)
            .expect("recovery #1")
            .recovered_corrupt
            .expect("backup #1");
        std::fs::write(&path, b"second garbage").expect("corrupt #2");
        let second = load_capture(&fixture.runtime)
            .expect("a second corruption must still recover")
            .recovered_corrupt
            .expect("backup #2");
        assert_ne!(
            first, second,
            "a fixed `.corrupt` name collides: the second recovery must not \
             overwrite the first backup — evidence needs collision-safe naming"
        );
        assert_eq!(
            std::fs::read(&first).expect("backup #1 survives"),
            b"first garbage",
            "the first backup must still hold its own bytes"
        );
    }

    /// Control characters in draft content survive the JSON round trip; an
    /// unreadable file (permission denied) surfaces as IO, not as a
    /// corrupt-record recovery.
    #[cfg(unix)]
    #[test]
    fn capture_io_failures_and_control_chars_are_typed() {
        use std::os::unix::fs::PermissionsExt;
        let fixture = RuntimeFixture::new().expect("fixture");
        lomo_tui::drafts::persist_capture(&fixture.runtime, 0, "bell\u{7} and\ttab")
            .expect("persist");
        let loaded = load_capture(&fixture.runtime).expect("load");
        assert_eq!(loaded.composer.text.text(), "bell\u{7} and\ttab");

        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o000)).expect("chmod");
        let outcome = load_capture(&fixture.runtime);
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).expect("restore");
        // On a privileged runner chmod may not block the read — skip then.
        if let Err(error) = outcome {
            assert!(
                matches!(error, TuiError::Io { .. }) || error.to_string().contains("io:"),
                "an unreadable capture is an IO error, not corrupt recovery: {error}"
            );
        }
    }

    /// A corrupt capture that cannot be moved aside — the drafts directory
    /// read-only — must fail loudly and honestly, naming both problems; it
    /// must never silently keep the corrupt file as the live draft.
    #[cfg(unix)]
    #[test]
    fn an_unsettable_aside_corrupt_capture_fails_loudly() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let fixture = RuntimeFixture::new().expect("fixture");
        if std::fs::metadata(&fixture.runtime.paths.drafts_dir)
            .expect("metadata")
            .uid()
            == 0
        {
            return; // chmod cannot fence root — nothing to prove here.
        }
        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::write(&path, b"garbage").expect("corrupt capture");
        std::fs::set_permissions(
            &fixture.runtime.paths.drafts_dir,
            std::fs::Permissions::from_mode(0o500),
        )
        .expect("read-only drafts");
        let outcome = load_capture(&fixture.runtime);
        std::fs::set_permissions(
            &fixture.runtime.paths.drafts_dir,
            std::fs::Permissions::from_mode(0o700),
        )
        .expect("restore");
        let Err(error) = outcome else {
            panic!("a blocked set-aside must fail visibly")
        };
        let text = error.to_string();
        assert!(
            text.contains("corrupt") || text.contains("set"),
            "the failure must name what happened to the corrupt draft: {text}"
        );
        assert_eq!(
            std::fs::read(&path).expect("corrupt file still readable"),
            b"garbage",
            "the corrupt draft is preserved in place on a failed set-aside"
        );
    }

    /// The boot path turns the recovery into a modal the user must see — the
    /// composer is empty and the notice names the backup path.
    #[test]
    fn corrupt_capture_notice_is_a_modal_the_user_must_see() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let path = capture_path(&fixture.runtime).expect("capture path");
        std::fs::create_dir_all(path.parent().expect("parent")).expect("drafts dir");
        std::fs::write(&path, b"garbage").expect("corrupt capture");
        let model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("boot model");
        let notice = model
            .notice
            .clone()
            .expect("the recovery must be presented");
        assert_eq!(notice.severity, Severity::Warn);
        assert_eq!(notice.surface, Surface::Modal);
        assert!(
            notice.lines.iter().any(|line| line.contains(".corrupt")),
            "the modal names the backup path: {:?}",
            notice.lines
        );
        assert!(model.draft.text.text().is_empty(), "composer starts empty");
        assert!(
            matches!(model.input, InputMode::Message { .. }),
            "the modal owns the focus: {:?}",
            model.input
        );
    }

    // ---------- xdg / misc contracts ----------

    /// A relative XDG override is ignored (spec-compliant) — but only by
    /// falling back to the platform default, never by anchoring to the cwd.
    #[cfg(all(unix, not(target_os = "macos")))]
    #[test]
    fn relative_xdg_values_fall_back_not_sideways() {
        let paths = resolve_paths(EnvLookup {
            home: Some("/home/u"),
            config: Some("relative/cfg"),
            state: Some("../state"),
            cache: None,
            runtime: Some("/run/user/1"),
            data: None,
            appdata: None,
            localappdata: None,
        })
        .expect("resolve");
        assert!(paths.config_dir.is_absolute());
        assert!(paths.state_dir.is_absolute());
        assert!(
            paths.config_dir.starts_with("/home/u") && paths.state_dir.starts_with("/home/u"),
            "ignored overrides must fall back to the platform default, not \
             sideways into the cwd: {:?} {:?}",
            paths.config_dir,
            paths.state_dir
        );
    }

    /// `probe_config` on an existing-but-invalid file is a hard error — loud
    /// fail-closed at startup — while a missing file is `FirstRun`. The probe
    /// itself still writes nothing in either direction.
    #[test]
    fn probe_is_loud_on_invalid_and_silent_on_missing() {
        let dir = tempdir().expect("tmp");
        let paths = paths_in(dir.path(), Some(dir.path().join("Notes")));
        std::fs::create_dir_all(&paths.config_dir).expect("cfg");
        let file = paths.config_dir.join("config.toml");
        std::fs::write(&file, "workspace = \"/x\"\ntime_zo").expect("truncated");
        let probe = probe_config(&paths, None);
        assert!(
            probe.is_err(),
            "a truncated config is a startup error, not a silent FirstRun: {probe:?}"
        );
        assert_eq!(
            std::fs::read_to_string(&file).expect("file"),
            "workspace = \"/x\"\ntime_zo",
            "the probe must not touch the broken file"
        );
        std::fs::remove_file(&file).expect("remove");
        assert!(matches!(
            probe_config(&paths, None),
            Ok(ConfigProbe::FirstRun { .. })
        ));
    }

    /// `mint_config` must never clobber an existing file — even a broken one:
    /// `create_new` fails rather than overwriting user state.
    #[test]
    fn mint_never_clobbers_an_existing_file() {
        let dir = tempdir().expect("tmp");
        let file = dir.path().join("cfg").join("config.toml");
        std::fs::create_dir_all(file.parent().expect("parent")).expect("cfg");
        std::fs::write(&file, "user data").expect("existing");
        let workspace = dir.path().join("ws");
        let outcome = mint_config(
            &file,
            &AppConfig {
                workspace: workspace.clone(),
                media_dir: workspace.join("media"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                editor: None,
                player: vec!["mpv".to_owned()],
            },
        );
        assert!(outcome.is_err(), "mint must refuse an existing file");
        assert_eq!(
            std::fs::read_to_string(&file).expect("file"),
            "user data",
            "the existing file must be byte-identical"
        );
        assert!(workspace.is_dir(), "the workspace dir is still created");
    }
}
