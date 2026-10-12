use lomo_tui::{
    cli::{self, CliAction},
    config::{ConfigProbe, probe_config},
    crash::install_panic_hook,
    error::TuiError,
    logging::init_logging,
    model::AppModel,
    ops::{BootstrapSpec, LaunchConfig, RuntimeSlot, SetupSpec},
    xdg::{HostDirs, env_nonempty, resolve_paths},
};
use std::{
    collections::BTreeMap,
    io::{self, Write},
    sync::Arc,
};

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
            let paths = resolve_paths(&HostDirs::detect())?;
            install_panic_hook(paths.state_dir.clone());
            let _log_guard = init_logging(&paths.state_dir, env_nonempty("LOMO_LOG").as_deref());
            tracing::info!(version = env!("CARGO_PKG_VERSION"), "lomo starting");
            // Probing is side-effect free: an existing config validates fully
            // here, a missing one hands the first-run wizard its proposal —
            // nothing is minted until the user confirms inside `host::run`.
            let launch = match probe_config(&paths, workspace_override.as_deref())? {
                ConfigProbe::Ready { config, .. } => LaunchConfig::Ready(config),
                ConfigProbe::FirstRun { file, proposal } => {
                    LaunchConfig::Setup(SetupSpec { file, proposal })
                }
            };
            // Terminal capabilities are queried from the terminal itself by
            // the graphics probe inside `host::run`; env here only gates the
            // conservative feature enables (mouse/paste/focus escapes).
            let env: BTreeMap<String, String> = std::iter::once("TERM")
                .filter_map(|key| env_nonempty(key).map(|value| (key.to_owned(), value)))
                .collect();
            let (columns, rows) = crossterm::terminal::size()?;
            let mut model = AppModel::new(columns, rows);
            // The workspace opens on an effect lane, not here: the UI loop
            // draws the Loading shell while verification is still running.
            let spec = BootstrapSpec {
                paths,
                launch,
                width: columns,
                height: rows,
            };
            lomo_tui::host::run(
                &Arc::new(RuntimeSlot::opening()),
                &spec,
                &mut model,
                lomo_tui::host::TerminalCapabilities::detect(&env),
            )
        }
    }
}
