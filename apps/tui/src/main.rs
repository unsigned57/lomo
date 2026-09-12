use lomo_tui::{
    config::{CliAction, HELP_TEXT, load_config, parse_cli},
    error::TuiError,
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

fn main() -> Result<(), TuiError> {
    match parse_cli(std::env::args())? {
        CliAction::Help => {
            writeln!(io::stdout(), "{HELP_TEXT}")?;
            Ok(())
        }
        CliAction::Version => {
            writeln!(io::stdout(), "lomo {}", env!("CARGO_PKG_VERSION"))?;
            Ok(())
        }
        CliAction::Run { workspace_override } => {
            let paths = resolve_paths(EnvLookup {
                home: env_nonempty("HOME").as_deref(),
                config: env_nonempty("XDG_CONFIG_HOME").as_deref(),
                state: env_nonempty("XDG_STATE_HOME").as_deref(),
                cache: env_nonempty("XDG_CACHE_HOME").as_deref(),
                runtime: env_nonempty("XDG_RUNTIME_DIR").as_deref(),
                data: env_nonempty("XDG_DATA_HOME").as_deref(),
            })?;
            let config = load_config(&paths, workspace_override.as_deref())?;
            let env: BTreeMap<String, String> = ["KITTY_WINDOW_ID", "TERM_PROGRAM", "TERM"]
                .into_iter()
                .filter_map(|key| env_nonempty(key).map(|value| (key.to_owned(), value)))
                .collect();
            let runtime = Arc::new(open_runtime(paths, config, detect_graphics(&env))?);
            let size = crossterm::terminal::window_size()?;
            let mut model = bootstrap_model(&runtime, AppModel::new(size.columns, size.rows))?;
            model.cell_size = lomo_tui::graphics::CellSize::reported(
                size.width,
                size.height,
                size.columns,
                size.rows,
            );
            lomo_tui::host::run(&runtime, &mut model)
        }
    }
}
