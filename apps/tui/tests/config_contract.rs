//! Behavior Contract
//! Capability: config parsing, XDG path isolation, CLI flags, and first-run config mint.
//! Scenarios: missing workspace fails; editor absence is allowed until edit time; runtime dir is
//! required; missing `config.toml` is minted once from CLI override or `$HOME/Notes`.
//! Observable outcomes: parsed `AppConfig`, persisted `config.toml` bytes, `RuntimePaths`, `CliAction`.
//! TDD proof: first-run used to fail closed on a missing config file.
//! Excludes: writing a real `$HOME` config from this process; overwriting an existing file.

#[cfg(test)]
#[expect(clippy::expect_used, reason = "contract tests fail closed on config")]
mod tests {
    use std::path::{Path, PathBuf};

    use lomo_tui::cli::{CliAction, parse_cli};
    use lomo_tui::config::{load_config, parse_config_toml};
    use lomo_tui::error::TuiError;
    use lomo_tui::xdg::{EnvLookup, RuntimePaths, resolve_paths};
    use tempfile::tempdir;

    fn env<'a>(
        home: Option<&'a str>,
        runtime: Option<&'a str>,
        data: Option<&'a str>,
    ) -> EnvLookup<'a> {
        EnvLookup {
            home,
            config: None,
            state: None,
            cache: None,
            runtime,
            data,
            appdata: None,
            localappdata: None,
        }
    }

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

    #[test]
    fn workspace_is_required_and_editor_may_be_absent() {
        let config = parse_config_toml(
            "workspace = \"/notes\"\ntime_zone = \"Asia/Shanghai\"\n",
            None,
            None,
        )
        .expect("parse");
        assert_eq!(config.workspace, Path::new("/notes"));
        assert_eq!(config.time_zone, "Asia/Shanghai");
        assert!(config.editor.is_none());
        #[cfg(all(unix, not(target_os = "macos")))]
        assert_eq!(config.player, vec!["xdg-open".to_owned()]);
        #[cfg(target_os = "macos")]
        assert_eq!(config.player, vec!["open".to_owned()]);
        #[cfg(target_os = "windows")]
        assert_eq!(
            config.player,
            vec![
                "powershell".to_owned(),
                "-NoProfile".to_owned(),
                "-Command".to_owned(),
                "Start-Process".to_owned()
            ]
        );
        let error = parse_config_toml("workspace = \"\"\n", None, None).expect_err("empty");
        assert!(matches!(error, TuiError::Config { .. }));
    }

    #[test]
    fn cli_help_and_workspace_override() {
        assert_eq!(
            parse_cli(["lomo".to_owned(), "--help".to_owned()]).expect("help"),
            CliAction::Help
        );
        match parse_cli(["lomo".to_owned(), "/tmp/ws".to_owned()]).expect("run") {
            CliAction::Run { workspace_override } => {
                assert_eq!(workspace_override, Some(PathBuf::from("/tmp/ws")));
            }
            CliAction::Help | CliAction::Version | CliAction::Completions(_) => {
                panic!("expected run action")
            }
        }
        let unknown = parse_cli(["lomo".to_owned(), "--nope".to_owned()]).expect_err("unknown");
        assert!(matches!(unknown, TuiError::Config { .. }));
        assert_eq!(
            parse_cli(["lomo".to_owned(), "--version".to_owned()]).expect("version"),
            CliAction::Version
        );
        match parse_cli(["lomo".to_owned()]).expect("default run") {
            CliAction::Run { workspace_override } => assert!(workspace_override.is_none()),
            CliAction::Help | CliAction::Version | CliAction::Completions(_) => {
                panic!("expected default run")
            }
        }
        let extra = parse_cli(["lomo".to_owned(), "/tmp/ws".to_owned(), "more".to_owned()])
            .expect_err("extra");
        assert!(matches!(extra, TuiError::Config { .. }));
    }

    #[test]
    fn cli_generates_shell_completions() {
        match parse_cli([
            "lomo".to_owned(),
            "--generate-completions".to_owned(),
            "bash".to_owned(),
        ])
        .expect("completions")
        {
            CliAction::Completions(shell) => {
                assert_eq!(shell, clap_complete::Shell::Bash);
            }
            CliAction::Help | CliAction::Version | CliAction::Run { .. } => {
                panic!("expected completions action")
            }
        }
        let missing = parse_cli(["lomo".to_owned(), "--generate-completions".to_owned()])
            .expect_err("missing shell");
        assert!(matches!(missing, TuiError::Config { .. }));
        match parse_cli([
            "lomo".to_owned(),
            "--generate-completions".to_owned(),
            "powershell".to_owned(),
        ])
        .expect("powershell completions")
        {
            CliAction::Completions(shell) => {
                assert_eq!(shell, clap_complete::Shell::PowerShell);
            }
            CliAction::Help | CliAction::Version | CliAction::Run { .. } => {
                panic!("expected completions action")
            }
        }
        let bogus = parse_cli([
            "lomo".to_owned(),
            "--generate-completions".to_owned(),
            "tcsh".to_owned(),
        ])
        .expect_err("unsupported shell");
        assert!(matches!(bogus, TuiError::Config { .. }));
        let help = lomo_tui::cli::render_help();
        assert!(help.contains("--generate-completions"));
        assert!(help.contains("Keys:"), "help keeps the key table: {help}");
    }

    /// Explicit `XDG_*_HOME`-style overrides resolve as `<dir>/lomo` on every
    /// supported platform; only the platform *defaults* differ.
    #[test]
    fn explicit_env_dirs_resolve_everywhere() {
        let paths = resolve_paths(EnvLookup {
            home: Some("/home/u"),
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: None,
            appdata: None,
            localappdata: None,
        })
        .expect("paths");
        assert_eq!(paths.config_dir, Path::new("/cfg/lomo"));
        assert_eq!(paths.drafts_dir, Path::new("/state/lomo/drafts"));
        assert_eq!(paths.runtime_dir, Path::new("/run/user/1/lomo"));
        assert_eq!(
            paths.default_workspace.as_deref(),
            Some(Path::new("/home/u/Notes"))
        );
        let data_fallback = resolve_paths(EnvLookup {
            home: None,
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: Some("/data"),
            appdata: None,
            localappdata: None,
        })
        .expect("data fallback");
        assert_eq!(
            data_fallback.default_workspace.as_deref(),
            Some(Path::new("/data/lomo/notes"))
        );
        let no_mint = resolve_paths(EnvLookup {
            home: None,
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: None,
            appdata: None,
            localappdata: None,
        })
        .expect("explicit xdg without home");
        assert!(no_mint.default_workspace.is_none());
    }

    /// Linux/Unix: XDG defaults hang off `$HOME` and `$XDG_RUNTIME_DIR` is
    /// required — there is no portable runtime fallback.
    #[cfg(all(unix, not(target_os = "macos")))]
    #[test]
    fn xdg_defaults_and_runtime_required() {
        let error = resolve_paths(env(Some("/home/u"), None, None)).expect_err("runtime");
        assert_eq!(error, TuiError::MissingRuntimeDir);
        let home_fallback =
            resolve_paths(env(Some("/home/u"), Some("/run/user/1"), None)).expect("home fallback");
        assert_eq!(home_fallback.config_dir, Path::new("/home/u/.config/lomo"));
        assert_eq!(
            home_fallback.state_dir,
            Path::new("/home/u/.local/state/lomo")
        );
        assert_eq!(home_fallback.cache_dir, Path::new("/home/u/.cache/lomo"));
        let missing_home =
            resolve_paths(env(None, Some("/run/user/1"), None)).expect_err("home required");
        assert!(matches!(missing_home, TuiError::Config { .. }));
    }

    /// macOS: defaults live under `~/Library`; the runtime dir falls back to a
    /// private `run` dir because macOS provides none.
    #[cfg(target_os = "macos")]
    #[test]
    fn macos_defaults_use_library_dirs() {
        let paths = resolve_paths(env(Some("/Users/u"), None, None)).expect("macos paths");
        let app_support = Path::new("/Users/u/Library/Application Support/lomo");
        assert_eq!(paths.config_dir, app_support);
        assert_eq!(paths.state_dir, app_support);
        assert_eq!(paths.cache_dir, Path::new("/Users/u/Library/Caches/lomo"));
        assert_eq!(paths.runtime_dir, app_support.join("run"));
        // An explicit runtime env var still wins.
        let explicit =
            resolve_paths(env(Some("/Users/u"), Some("/tmp/run"), None)).expect("explicit");
        assert_eq!(explicit.runtime_dir, Path::new("/tmp/run/lomo"));
    }

    /// Windows: config roams with `%APPDATA%`, state/cache/runtime stay under
    /// `%LOCALAPPDATA%`; there is no runtime env to require.
    #[cfg(windows)]
    #[test]
    fn windows_defaults_use_appdata() {
        let lookup = EnvLookup {
            home: Some("C:\\Users\\u"),
            appdata: Some("C:\\Users\\u\\AppData\\Roaming"),
            localappdata: Some("C:\\Users\\u\\AppData\\Local"),
            ..env(None, None, None)
        };
        let paths = resolve_paths(lookup).expect("windows paths");
        assert_eq!(
            paths.config_dir,
            Path::new("C:\\Users\\u\\AppData\\Roaming\\lomo")
        );
        assert_eq!(
            paths.state_dir,
            Path::new("C:\\Users\\u\\AppData\\Local\\lomo")
        );
        assert_eq!(
            paths.cache_dir,
            Path::new("C:\\Users\\u\\AppData\\Local\\lomo\\cache")
        );
        assert_eq!(
            paths.runtime_dir,
            Path::new("C:\\Users\\u\\AppData\\Local\\lomo\\run")
        );
        assert_eq!(
            paths.default_workspace.as_deref(),
            Some(Path::new("C:\\Users\\u\\Notes"))
        );
        let missing = resolve_paths(env(None, None, None)).expect_err("appdata required");
        assert!(matches!(missing, TuiError::Config { .. }));
    }

    #[test]
    fn load_config_reads_toml_and_applies_workspace_override() {
        let dir = tempdir().expect("tmp");
        let paths = paths_in(dir.path(), None);
        std::fs::create_dir_all(&paths.config_dir).expect("cfg");
        std::fs::write(
            paths.config_dir.join("config.toml"),
            "workspace = \"/notes\"\neditor = [\"kak\", \"-e\"]\nplayer = [\"mpv\"]\n",
        )
        .expect("write");
        let loaded = load_config(&paths, Some(Path::new("/override"))).expect("load");
        assert_eq!(loaded.workspace, Path::new("/override"));
        assert_eq!(
            loaded.editor.as_deref(),
            Some(&["kak".to_owned(), "-e".to_owned()][..])
        );
        let helix = parse_config_toml("workspace = \"/n\"\neditor = \"helix\"\n", None, None)
            .expect("prog");
        assert_eq!(helix.editor, Some(vec!["helix".to_owned()]));
        let empty_editor = parse_config_toml("workspace = \"/n\"\neditor = \"\"\n", None, None)
            .expect("empty editor");
        assert!(empty_editor.editor.is_none());
        let invalid = parse_config_toml("not toml", None, None).expect_err("toml");
        assert!(matches!(invalid, TuiError::Config { .. }));
        assert!(
            TuiError::EditorNotConfigured
                .to_string()
                .contains("vim is not assumed")
        );
        assert!(
            TuiError::from(std::io::Error::other("pipe"))
                .to_string()
                .contains("io:")
        );
        let persisted =
            std::fs::read_to_string(paths.config_dir.join("config.toml")).expect("read");
        assert!(
            persisted.contains("/notes"),
            "existing config must not be rewritten on override: {persisted}"
        );
    }

    #[test]
    fn load_config_mints_missing_file_from_default_workspace() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        let loaded = load_config(&paths, None).expect("first run");
        assert_eq!(loaded.workspace, notes);
        let persisted =
            std::fs::read_to_string(paths.config_dir.join("config.toml")).expect("minted");
        assert!(
            persisted.contains(&notes.display().to_string()),
            "minted config must bind the default workspace: {persisted}"
        );
        assert!(
            persisted.contains("time_zone"),
            "minted config must be complete: {persisted}"
        );
        let again = load_config(&paths, None).expect("second run");
        assert_eq!(again.workspace, notes);
        let second = std::fs::read_to_string(paths.config_dir.join("config.toml")).expect("stable");
        assert_eq!(
            persisted, second,
            "second load must not rewrite a valid first-run file"
        );
    }

    #[test]
    fn load_config_mints_missing_file_from_cli_workspace() {
        let dir = tempdir().expect("tmp");
        let vault = dir.path().join("vault");
        let unused_default = dir.path().join("Notes");
        let paths = paths_in(dir.path(), Some(unused_default));
        let loaded = load_config(&paths, Some(&vault)).expect("cli first run");
        assert_eq!(loaded.workspace, vault);
        let persisted =
            std::fs::read_to_string(paths.config_dir.join("config.toml")).expect("minted");
        assert!(
            persisted.contains(&vault.display().to_string()),
            "CLI workspace must be persisted: {persisted}"
        );
        assert!(
            !persisted.contains("Notes"),
            "default home Notes must not win over CLI: {persisted}"
        );
    }

    #[test]
    fn load_config_missing_file_without_mint_target_fails_closed() {
        let dir = tempdir().expect("tmp");
        let paths = paths_in(dir.path(), None);
        let error = load_config(&paths, None).expect_err("no mint");
        assert!(matches!(error, TuiError::Config { .. }));
        assert!(
            !paths.config_dir.join("config.toml").exists(),
            "must not write a placeholder config without a workspace binding"
        );
    }
    #[test]
    fn workspace_paths_normalize_home_and_reject_relative() {
        let home = Path::new("/home/u");
        let expanded =
            parse_config_toml("workspace = \"~/notes\"", None, Some(home)).expect("tilde expands");
        assert_eq!(expanded.workspace, Path::new("/home/u/notes"));
        let bare = parse_config_toml("workspace = \"~\"", None, Some(home))
            .expect("bare tilde is the home root");
        assert_eq!(bare.workspace, home);
        let override_expanded = parse_config_toml(
            "workspace = \"/ignored\"",
            Some(Path::new("~/vault")),
            Some(home),
        )
        .expect("override expands");
        assert_eq!(override_expanded.workspace, Path::new("/home/u/vault"));
        for (raw, label) in [
            ("workspace = \"~/notes\"", "tilde without a home"),
            ("workspace = \"notes/rel\"", "relative path"),
            ("workspace = \"./rel\"", "dot-relative path"),
            ("workspace = \"~root/notes\"", "other user's tilde"),
        ] {
            let home_arg = if label == "tilde without a home" {
                None
            } else {
                Some(home)
            };
            assert!(
                parse_config_toml(raw, None, home_arg).is_err(),
                "{label} must be rejected"
            );
        }
        assert!(
            parse_config_toml(
                "workspace = \"/abs\"",
                Some(Path::new("rel/vault")),
                Some(home)
            )
            .is_err(),
            "a relative CLI override must be rejected"
        );
    }

    #[test]
    fn open_runtime_requires_an_existing_workspace() {
        let dir = tempdir().expect("tmp");
        let paths = paths_in(dir.path(), None);
        let config = lomo_tui::config::AppConfig {
            workspace: dir.path().join("missing-notes"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: None,
            player: vec!["echo".to_owned()],
        };
        let missing = lomo_tui::ops::open_runtime(
            paths.clone(),
            config.clone(),
            lomo_tui::media::GraphicsProtocol::None,
        )
        .map(|_| ())
        .expect_err("missing workspace must fail, not materialize");
        assert!(
            matches!(missing, TuiError::Config { .. }),
            "a missing workspace is a configuration error: {missing}"
        );
        assert!(
            !config.workspace.exists(),
            "opening must never create the workspace directory"
        );
        std::fs::create_dir_all(&config.workspace).expect("explicit library creation");
        lomo_tui::ops::open_runtime(paths, config, lomo_tui::media::GraphicsProtocol::None)
            .expect("an existing workspace opens");
    }

    #[test]
    fn first_run_mint_creates_the_new_workspace() {
        let dir = tempdir().expect("tmp");
        let notes = dir.path().join("NewLibrary");
        let paths = paths_in(dir.path(), Some(notes.clone()));
        let loaded = load_config(&paths, None).expect("first run");
        assert_eq!(loaded.workspace, notes);
        assert!(
            notes.is_dir(),
            "creating a new library is a distinct action from opening one"
        );
    }
}
