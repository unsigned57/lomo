use lomo_tui::{
    cli::{self, CliAction},
    config::load_config,
    crash::install_panic_hook,
    error::TuiError,
    logging::init_logging,
    media::detect_graphics,
    model::AppModel,
    ops::{bootstrap_model, open_runtime},
    xdg::{EnvLookup, env_nonempty, resolve_paths},
};
use std::{
    collections::BTreeMap,
    io::{self, Write},
    sync::Arc,
};

/// `HOME` on Unix, `USERPROFILE` on Windows — the user profile root.
#[cfg(unix)]
fn home_dir_env() -> Option<String> {
    env_nonempty("HOME")
}

#[cfg(windows)]
fn home_dir_env() -> Option<String> {
    env_nonempty("USERPROFILE")
}

#[cfg(not(any(unix, windows)))]
fn home_dir_env() -> Option<String> {
    env_nonempty("HOME")
}

fn main() -> Result<(), TuiError> {
    match cli::parse_cli(std::env::args())? {
        CliAction::Help => {
            writeln!(io::stdout(), "{}", cli::render_help())?;
            Ok(())
        }
        CliAction::Version => {
            writeln!(io::stdout(), "lomo {}", env!("CARGO_PKG_VERSION"))?;
            Ok(())
        }
        CliAction::Completions(shell) => {
            clap_complete::generate(shell, &mut cli::command(), "lomo", &mut io::stdout());
            Ok(())
        }
        CliAction::Run { workspace_override } => {
            let paths = resolve_paths(EnvLookup {
                home: home_dir_env().as_deref(),
                config: env_nonempty("XDG_CONFIG_HOME").as_deref(),
                state: env_nonempty("XDG_STATE_HOME").as_deref(),
                cache: env_nonempty("XDG_CACHE_HOME").as_deref(),
                runtime: env_nonempty("XDG_RUNTIME_DIR").as_deref(),
                data: env_nonempty("XDG_DATA_HOME").as_deref(),
                appdata: env_nonempty("APPDATA").as_deref(),
                localappdata: env_nonempty("LOCALAPPDATA").as_deref(),
            })?;
            install_panic_hook(paths.state_dir.clone());
            let _log_guard = init_logging(&paths.state_dir, env_nonempty("LOMO_LOG").as_deref());
            tracing::info!(version = env!("CARGO_PKG_VERSION"), "lomo starting");
            let config = load_config(&paths, workspace_override.as_deref())?;
            let env: BTreeMap<String, String> = ["KITTY_WINDOW_ID", "TERM_PROGRAM", "TERM"]
                .into_iter()
                .filter_map(|key| env_nonempty(key).map(|value| (key.to_owned(), value)))
                .collect();
            let runtime = Arc::new(open_runtime(paths, config, detect_graphics(&env))?);
            let (columns, rows) = crossterm::terminal::size()?;
            let mut model = bootstrap_model(&runtime, AppModel::new(columns, rows))?;
            // Pixel metrics are optional: `window_size` is unsupported on
            // Windows consoles and some Unix terminals; cell-addressed image
            // protocols still work with their nominal sampling grid.
            model.cell_size = crossterm::terminal::window_size().map_or(None, |size| {
                lomo_tui::graphics::CellSize::reported(
                    size.width,
                    size.height,
                    size.columns,
                    size.rows,
                )
            });
            lomo_tui::host::run(
                &runtime,
                &mut model,
                lomo_tui::host::TerminalCapabilities::detect(&env),
            )
        }
    }
}
