use std::collections::BTreeMap;
use std::io::{self, stdout};
use std::path::Path;
use std::time::Duration;

use crossterm::event::{self as term_event, Event};
use crossterm::execute;
use crossterm::terminal::{
    EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode,
};
use lomo_tui::config::{CliAction, HELP_TEXT, load_config, parse_cli};
use lomo_tui::edit_flow::{EditRequest, complete_edit, edit_selection};
use lomo_tui::editor::{EditKind, StdCommandRunner};
use lomo_tui::error::TuiError;
use lomo_tui::event::{InputContext, command_from_key};
use lomo_tui::media::{SystemClipboard, detect_graphics};
use lomo_tui::model::AppModel;
use lomo_tui::ops::{
    TuiRuntime, apply_effect, bootstrap_model, import_from_clipboard, open_runtime, play_selected,
};
use lomo_tui::update::{Effect, apply_command, apply_resize};
use lomo_tui::xdg::{EnvLookup, env_nonempty, resolve_paths};
use ratatui::Terminal;
use ratatui::backend::CrosstermBackend;

fn main() -> Result<(), TuiError> {
    match parse_cli(std::env::args())? {
        CliAction::Help => {
            print_stdout(HELP_TEXT);
            Ok(())
        }
        CliAction::Version => {
            print_stdout(&format!("lomo {}", env!("CARGO_PKG_VERSION")));
            Ok(())
        }
        CliAction::Run { workspace_override } => run(workspace_override.as_deref()),
    }
}

fn run(workspace_override: Option<&Path>) -> Result<(), TuiError> {
    let home = env_nonempty("HOME");
    let config_home = env_nonempty("XDG_CONFIG_HOME");
    let state_home = env_nonempty("XDG_STATE_HOME");
    let cache_home = env_nonempty("XDG_CACHE_HOME");
    let runtime_home = env_nonempty("XDG_RUNTIME_DIR");
    let data_home = env_nonempty("XDG_DATA_HOME");
    let paths = resolve_paths(EnvLookup {
        home: home.as_deref(),
        config: config_home.as_deref(),
        state: state_home.as_deref(),
        cache: cache_home.as_deref(),
        runtime: runtime_home.as_deref(),
        data: data_home.as_deref(),
    })?;
    let config = load_config(&paths, workspace_override)?;
    let runtime = open_runtime(paths, config, detect_graphics(&process_env()))?;
    let (width, height) = crossterm::terminal::size().map_err(TuiError::from)?;
    let mut model = bootstrap_model(&runtime, AppModel::new(width, height))?;
    let mut terminal = setup_terminal()?;
    let runner = StdCommandRunner;
    let result = event_loop(&runtime, &mut model, &mut terminal, runner);
    restore_terminal(terminal)?;
    result
}

fn event_loop(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
    runner: StdCommandRunner,
) -> Result<(), TuiError> {
    loop {
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .map_err(|error| TuiError::Terminal {
                diagnostic: error.to_string(),
            })?;
        if !term_event::poll(Duration::from_millis(50)).map_err(TuiError::from)? {
            continue;
        }
        match term_event::read().map_err(TuiError::from)? {
            Event::Resize(width, height) => apply_resize(model, width, height),
            Event::Key(key) => {
                let ctx = InputContext::from_model(&model.overlay, &model.search);
                let effect = apply_command(model, command_from_key(key, ctx));
                if matches!(effect, Effect::Quit) {
                    return Ok(());
                }
                dispatch_effect(runtime, model, effect, terminal, runner)?;
            }
            Event::FocusGained | Event::FocusLost | Event::Mouse(_) | Event::Paste(_) => {}
        }
    }
}

fn dispatch_effect(
    runtime: &TuiRuntime,
    model: &mut AppModel,
    effect: Effect,
    terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
    runner: StdCommandRunner,
) -> Result<(), TuiError> {
    match effect {
        Effect::NewMemo => {
            let visual = env_nonempty("VISUAL");
            let editor_env = env_nonempty("EDITOR");
            suspend_terminal(terminal)?;
            let result = complete_edit(
                runtime,
                model,
                &runner,
                EditRequest {
                    kind: EditKind::Create,
                    initial: "",
                    baseline: None,
                    visual: visual.as_deref(),
                    editor_env: editor_env.as_deref(),
                },
            );
            resume_terminal(terminal)?;
            result
        }
        Effect::EditMemo => {
            let visual = env_nonempty("VISUAL");
            let editor_env = env_nonempty("EDITOR");
            suspend_terminal(terminal)?;
            let result = edit_selection(
                runtime,
                model,
                &runner,
                visual.as_deref(),
                editor_env.as_deref(),
            );
            resume_terminal(terminal)?;
            result
        }
        Effect::ImportClipboard => {
            model.status = match import_from_clipboard(runtime, model, &SystemClipboard) {
                Ok(path) => format!("imported {path}"),
                Err(error) => error.to_string(),
            };
            Ok(())
        }
        Effect::PlayAttachment => {
            model.status = match play_selected(runtime, model, &runner) {
                Ok(()) => "player started".to_owned(),
                Err(error) => error.to_string(),
            };
            Ok(())
        }
        Effect::None
        | Effect::Quit
        | Effect::LoadScreen
        | Effect::Search
        | Effect::ToggleTask
        | Effect::PinSelected
        | Effect::DeleteSelected
        | Effect::RestoreSelected
        | Effect::ShowHistory
        | Effect::ConfirmDelete
        | Effect::ConfirmRestore => apply_effect(runtime, model, effect),
    }
}

fn setup_terminal() -> Result<Terminal<CrosstermBackend<io::Stdout>>, TuiError> {
    enable_raw_mode().map_err(TuiError::from)?;
    let mut out = stdout();
    execute!(out, EnterAlternateScreen).map_err(TuiError::from)?;
    Terminal::new(CrosstermBackend::new(out)).map_err(|error| TuiError::Terminal {
        diagnostic: error.to_string(),
    })
}

fn restore_terminal(mut terminal: Terminal<CrosstermBackend<io::Stdout>>) -> Result<(), TuiError> {
    disable_raw_mode().map_err(TuiError::from)?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen).map_err(TuiError::from)?;
    Ok(())
}

fn suspend_terminal(terminal: &mut Terminal<CrosstermBackend<io::Stdout>>) -> Result<(), TuiError> {
    disable_raw_mode().map_err(TuiError::from)?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen).map_err(TuiError::from)?;
    Ok(())
}

fn resume_terminal(terminal: &mut Terminal<CrosstermBackend<io::Stdout>>) -> Result<(), TuiError> {
    enable_raw_mode().map_err(TuiError::from)?;
    execute!(terminal.backend_mut(), EnterAlternateScreen).map_err(TuiError::from)?;
    terminal.clear().map_err(|error| TuiError::Terminal {
        diagnostic: error.to_string(),
    })
}

fn process_env() -> BTreeMap<String, String> {
    let mut vars = BTreeMap::new();
    for key in ["KITTY_WINDOW_ID", "TERM_PROGRAM", "TERM"] {
        if let Some(value) = env_nonempty(key) {
            vars.insert(key.to_owned(), value);
        }
    }
    vars
}

#[expect(
    clippy::print_stdout,
    reason = "CLI help and version are stdout contracts"
)]
fn print_stdout(text: &str) {
    println!("{text}");
}
