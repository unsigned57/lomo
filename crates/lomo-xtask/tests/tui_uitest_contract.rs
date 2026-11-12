//! Behavior Contract:
//! Capability: `just tuitest` runs committed PTY interaction flows against the real
//! `lomo` TUI binary — the device-free TUI debug loop mirroring `just uitest`. It owns
//! flow-file resolution, the fail-closed argument surface and the screen-evidence
//! report layout under `reports/tui-uitest/`.
//! Owning layer: lomo-xtask tuitest command surface; priority: P0 (TUI-debug facility
//! wiring).
//!
//! Scenarios:
//! - Given no flow selector, when `tui_flow_files` resolves, then every committed
//!   top-level `apps/tui/uitest/flows/*.yaml` runs in sorted order.
//! - Given a flow name, `name.yaml`, a repository-relative path or an absolute path,
//!   when resolved, then exactly that file is selected; an unknown selector fails
//!   closed naming it.
//! - Given `lomo-xtask tuitest --bogus` or `tuitest <unknown-flow>`, when dispatched,
//!   then the command exits non-zero before any PTY work — never inventing a run.
//! - Given the committed flows, when their step keys are inspected, then only the
//!   supported vocabulary (`expect`/`send`/`key`/`sleep_ms`/`snap`/`expect_exit`)
//!   appears and every `key` name resolves — a typo'd step or unknown key must fail
//!   at parse time, never silently no-op.
//! - Given the xtask sources, when inspected, then the command pins the committed
//!   flow directory, the PTY geometry, the probe-answering reader and the
//!   `tui-uitest.v1.json` report.
//!
//! Observable outcomes: resolved flow lists, CLI non-zero exits for bad arguments,
//! and the source-level pins between xtask, flows and the TUI binary.
//!
//! TDD proof: `cargo test -p lomo-xtask --test tui_uitest_contract --locked`.
//! RED: before this command no `tuitest` entry existed and no committed PTY flow
//! directory was consulted.
//! Excludes: live PTY execution (covered by `just tuitest` runs that emit
//! `tui-uitest.v1.json` plus per-flow screen snapshots under `reports/`).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, bail, ensure};
    use lomo_xtask::{TUI_UITEST_FLOW_DIR, repository_root, tui_flow_files};

    const COMMITTED_FLOWS: &[&str] = &["create_memo", "setup_wizard"];
    /// The step vocabulary `parse_flow` accepts — a flow using anything else must
    /// fail closed, so the contract pins the surface a committed flow may use.
    const STEP_KEYS: &[&str] = &["expect", "send", "key", "sleep_ms", "snap", "expect_exit"];
    /// `key:` names the runner translates (see `key_bytes`).
    const KEY_NAMES: &[&str] = &[
        "enter",
        "esc",
        "tab",
        "backspace",
        "space",
        "up",
        "down",
        "right",
        "left",
        "home",
        "end",
        "pageup",
        "pagedown",
        "f5",
    ];

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn default_resolution_runs_every_committed_flow_sorted() -> Result<()> {
        let root = repository_root()?;
        let flows = tui_flow_files(&root, &[])?;
        let names: Vec<_> = flows
            .iter()
            .filter_map(|path| path.file_stem())
            .map(|stem| stem.to_string_lossy().into_owned())
            .collect();
        let mut expected: Vec<_> = COMMITTED_FLOWS.iter().map(ToString::to_string).collect();
        expected.sort();
        ensure!(
            names == expected,
            "committed flows changed ({names:?} != {expected:?}); update COMMITTED_FLOWS"
        );
        for flow in &flows {
            ensure!(
                flow.parent() == Some(root.join(TUI_UITEST_FLOW_DIR).as_path()),
                "{} must be a top-level flow file",
                flow.display()
            );
        }
        Ok(())
    }

    #[test]
    fn selectors_resolve_names_files_and_paths() -> Result<()> {
        let root = repository_root()?;
        let flow_dir = root.join(TUI_UITEST_FLOW_DIR);
        for selector in ["create_memo", "create_memo.yaml"] {
            let resolved = tui_flow_files(&root, &[selector.to_owned()])?;
            ensure!(
                resolved == vec![flow_dir.join("create_memo.yaml")],
                "selector {selector} must resolve to the committed flow"
            );
        }
        for selector in [
            "apps/tui/uitest/flows/setup_wizard.yaml",
            "apps/tui/uitest/flows/setup_wizard",
        ] {
            let resolved = tui_flow_files(&root, &[selector.to_owned()])?;
            ensure!(
                resolved == vec![flow_dir.join("setup_wizard.yaml")],
                "selector {selector} must resolve to the committed flow"
            );
        }
        let absolute = flow_dir.join("create_memo.yaml");
        let resolved = tui_flow_files(&root, &[absolute.to_string_lossy().into_owned()])?;
        ensure!(resolved == vec![absolute]);

        let Err(error) = tui_flow_files(&root, &["bogus-flow".to_owned()]) else {
            bail!("unknown selector must fail closed");
        };
        ensure!(
            error.to_string().contains("bogus-flow"),
            "error must name the rejected selector: {error:#}"
        );
        Ok(())
    }

    #[test]
    fn cli_rejects_unknown_flag_and_unknown_flow_before_pty_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["tuitest", "--bogus-flag"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown tuitest flag must exit non-zero"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("--bogus-flag"),
            "error must name the rejected flag: {stderr}"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["tuitest", "bogus-flow"])
            .output()?;
        ensure!(!output.status.success(), "unknown flow must exit non-zero");
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("bogus-flow"),
            "error must name the rejected flow: {stderr}"
        );
        Ok(())
    }

    #[test]
    fn committed_flow_steps_use_only_the_supported_vocabulary() -> Result<()> {
        let root = repository_root()?;
        for flow in tui_flow_files(&root, &[])? {
            let text = read(&flow)?;
            let stem = flow
                .file_stem()
                .and_then(|stem| stem.to_str())
                .context("flow file name must be UTF-8")?;
            ensure!(
                text.contains("steps:"),
                "{stem} must declare a `steps:` list"
            );
            for (index, line) in text.lines().enumerate() {
                let Some(step_entry) = line.trim().strip_prefix("- ") else {
                    continue;
                };
                let key = step_entry
                    .split(':')
                    .next()
                    .with_context(|| format!("{stem}:{} step must name a key", index + 1))?
                    .trim();
                ensure!(
                    STEP_KEYS.contains(&key),
                    "{stem}:{} uses unsupported step key {key:?} (allowed: {STEP_KEYS:?})",
                    index + 1
                );
                if key == "key" {
                    let name = step_entry
                        .split(':')
                        .nth(1)
                        .with_context(|| format!("{stem}:{} key step needs a name", index + 1))?
                        .trim();
                    let known = KEY_NAMES.contains(&name)
                        || name.strip_prefix("ctrl+").is_some_and(|rest| {
                            rest.len() == 1 && rest.chars().all(|c| c.is_ascii_lowercase())
                        })
                        || (name.len() == 1 && name.chars().all(|c| c.is_ascii_graphic()));
                    ensure!(known, "{stem}:{} names unknown key {name:?}", index + 1);
                }
            }
        }
        Ok(())
    }

    #[test]
    fn source_wiring_pins_flow_dir_pty_and_report_layout() -> Result<()> {
        let root = repository_root()?;
        let source = read(&root.join("crates/lomo-xtask/src/tui_uitest.rs"))?;
        for pin in [
            "apps/tui/uitest/flows",
            "tui-uitest.v1.json",
            "pty_uitest",
            "native_pty_system",
            // The graphics probe answer — without it the app's leaked reader
            // thread consumes stdin and every keystroke starves.
            "\\x1b[0n",
            "XDG_RUNTIME_DIR",
        ] {
            ensure!(source.contains(pin), "tui_uitest.rs missing wiring: {pin}");
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        for pin in ["\"tuitest\"", "tuitest_command", "--skip-build"] {
            ensure!(cli.contains(pin), "cli.rs missing tuitest wiring: {pin}");
        }
        let justfile = read(&root.join("Justfile"))?;
        ensure!(
            justfile.contains("tuitest *args:"),
            "Justfile missing the tuitest recipe"
        );
        Ok(())
    }
}
