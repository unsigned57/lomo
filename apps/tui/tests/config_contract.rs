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

    use lomo_tui::config::{CliAction, load_config, parse_cli, parse_config_toml};
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
            default_workspace,
        }
    }

    #[test]
    fn workspace_is_required_and_editor_may_be_absent() {
        let config = parse_config_toml(
            "workspace = \"/notes\"\ntime_zone = \"Asia/Shanghai\"\n",
            None,
        )
        .expect("parse");
        assert_eq!(config.workspace, Path::new("/notes"));
        assert_eq!(config.time_zone, "Asia/Shanghai");
        assert!(config.editor.is_none());
        assert_eq!(config.player, vec!["xdg-open".to_owned()]);
        let error = parse_config_toml("workspace = \"\"\n", None).expect_err("empty");
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
            CliAction::Help | CliAction::Version => panic!("expected run action"),
        }
        let unknown = parse_cli(["lomo".to_owned(), "--nope".to_owned()]).expect_err("unknown");
        assert!(matches!(unknown, TuiError::Config { .. }));
        assert_eq!(
            parse_cli(["lomo".to_owned(), "--version".to_owned()]).expect("version"),
            CliAction::Version
        );
        match parse_cli(["lomo".to_owned()]).expect("default run") {
            CliAction::Run { workspace_override } => assert!(workspace_override.is_none()),
            CliAction::Help | CliAction::Version => panic!("expected default run"),
        }
        let extra = parse_cli(["lomo".to_owned(), "/tmp/ws".to_owned(), "more".to_owned()])
            .expect_err("extra");
        assert!(matches!(extra, TuiError::Config { .. }));
    }

    #[test]
    fn xdg_runtime_dir_is_required() {
        let error = resolve_paths(env(Some("/home/u"), None, None)).expect_err("runtime");
        assert_eq!(error, TuiError::MissingRuntimeDir);
        let paths = resolve_paths(EnvLookup {
            home: Some("/home/u"),
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: None,
        })
        .expect("paths");
        assert_eq!(paths.config_dir, Path::new("/cfg/lomo"));
        assert_eq!(paths.drafts_dir, Path::new("/state/lomo/drafts"));
        assert_eq!(paths.runtime_dir, Path::new("/run/user/1/lomo"));
        assert_eq!(
            paths.default_workspace.as_deref(),
            Some(Path::new("/home/u/Notes"))
        );
        let home_fallback =
            resolve_paths(env(Some("/home/u"), Some("/run/user/1"), None)).expect("home fallback");
        assert_eq!(home_fallback.config_dir, Path::new("/home/u/.config/lomo"));
        let missing_home =
            resolve_paths(env(None, Some("/run/user/1"), None)).expect_err("home required");
        assert!(matches!(missing_home, TuiError::Config { .. }));
        let data_fallback = resolve_paths(EnvLookup {
            home: None,
            config: Some("/cfg"),
            state: Some("/state"),
            cache: Some("/cache"),
            runtime: Some("/run/user/1"),
            data: Some("/data"),
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
        })
        .expect("explicit xdg without home");
        assert!(no_mint.default_workspace.is_none());
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
        let helix =
            parse_config_toml("workspace = \"/n\"\neditor = \"helix\"\n", None).expect("prog");
        assert_eq!(helix.editor, Some(vec!["helix".to_owned()]));
        let empty_editor =
            parse_config_toml("workspace = \"/n\"\neditor = \"\"\n", None).expect("empty editor");
        assert!(empty_editor.editor.is_none());
        let invalid = parse_config_toml("not toml", None).expect_err("toml");
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
}
