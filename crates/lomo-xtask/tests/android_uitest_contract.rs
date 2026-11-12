//! Test Change Justification: the exact committed-flow inventory now includes the two
//! capsule regression journeys and their standalone editor cleanup flow. Sorted discovery,
//! selector rejection, anchor checks and runFlow target assertions remain unchanged.
//! Behavior Contract:
//! Test Change Justification:
//! Reason category: new isolated trash regression journey.
//! Old behavior/assertion being replaced: the exhaustive inventory omits `trash_clear`.
//! Why old assertion is no longer correct: the new flow covers batch clear and last-item empty state.
//! Coverage preserved by: exhaustive sorted discovery, selector validation and anchor validation.
//! Why this is not fitting the test to the implementation: flow-contract.log records the inventory
//! RED; real UI empty-state deadlines are checked separately on the attached device.
//!
//! Test Change Justification:
//! Reason category: shared-directory media regression flow.
//! Old behavior/assertion being replaced: the exhaustive inventory omits `media_shared_directory`.
//! Why old assertion is no longer correct: the committed flow now covers shared storage and restart.
//! Coverage preserved by: unchanged sorted discovery and selector/anchor validation.
//! Why this is not fitting the test to the implementation: check.json independently records the
//! extra flow inventory failure; directory-layout host tests expose path rebasing and duplicate mounts.
//!
//! Test Change Justification:
//! Reason category: media-directory ownership regression flow.
//! Old behavior/assertion being replaced: the exhaustive inventory omits `media_directory_switch`.
//! Why old assertion is no longer correct: the new flow verifies repeated directory changes and restart.
//! Coverage preserved by: unchanged exhaustive discovery and selector/anchor validation.
//! Why this is not fitting the test to the implementation: real JNI independently rejected the
//! same-workspace rebind with `workspace_busy`; flow-contract.stdout records the inventory failure.
//!
//! Test Change Justification:
//! Reason category: coordinate tools and retained settings-position device regressions.
//! Old behavior/assertion being replaced: inventory omits `editor_coordinates` and `settings_scroll_restore`.
//! Why old assertion is no longer correct: both new top-level flows verify user-reported interactions.
//! Coverage preserved by: unchanged exhaustive discovery and selector/anchor validation; the wide-only
//! sidebar scenario stays in `large_screen` and resolves explicitly rather than running on phone windows.
//! Why this is not fitting the test to the implementation: host toolbar and device settings RED
//! failures are recorded independently of the discovery inventory in ui-followup.
//!
//! Test Change Justification:
//! Reason category: pinned memo creation and Markdown device regression flow.
//! Old behavior/assertion being replaced: the inventory omits `pinned_memo_markdown`.
//! Why old assertion is no longer correct: the committed flow now verifies the pinned prefix
//! and completed/open task presentation after creating a memo.
//! Coverage preserved by: unchanged exhaustive discovery, selector and anchor checks.
//! Why this is not fitting the test to the implementation: the full gate failed on the extra
//! YAML, while the device flow independently failed on viewport loss before the fix and passed
//! with animations both disabled and enabled afterwards.
//!
//! Test Change Justification:
//! Reason category: collapsed memo preview regression flows.
//! Old behavior/assertion being replaced: the exhaustive inventory omits the two new memo preview flows.
//! Why old assertion is no longer correct: those YAML files now exercise text and code-first preview visibility.
//! Coverage preserved by: unchanged exhaustive sorted discovery and selector/anchor validation.
//! Why this is not fitting the test to the implementation: the unchanged inventory assertion failed
//! in the full gate, while code-ui-red.json separately records the actual empty-body device failure.
//!
//! Test Change Justification:
//! Reason category: search back-navigation regression flow.
//! Old behavior/assertion being replaced: the committed inventory omits `search_back`.
//! Why old assertion is no longer correct: the new flow covers system and toolbar back from search.
//! Coverage preserved by: unchanged exhaustive discovery and selector/anchor checks.
//! Why this is not fitting the test to the implementation: `just check` failed on the new YAML;
//! the animated device flow separately reproduces the stuck route and verifies its fix.
//!
//! Test Change Justification:
//! Reason category: gesture-ball configuration and interaction flow.
//! Old behavior/assertion being replaced: the committed inventory omits `quick_ball`.
//! Why old assertion is no longer correct: the feature adds a real device flow for persisted bindings,
//! preview isolation and directional actions.
//! Coverage preserved by: unchanged exhaustive sorted discovery and selector/anchor validation.
//! Why this is not fitting the test to the implementation: the unchanged assertion failed on the
//! newly added YAML in the quick-ball check; device outcomes are asserted separately.
//! Reason category: new empty-SAF workspace/navigation regression flow.
//! Old behavior/assertion being replaced: the committed flow inventory has five entries.
//! Why old assertion is no longer correct: `workspace_empty` adds a real release-compatible
//! flow covering grant, immediate navigation and restart persistence.
//! Coverage preserved by: the same sorted-resolution and selector/anchor validation tests.
//! Why this is not fitting the test to the implementation: the new user-reported path was
//! absent from the previous inventory and is executed separately on device.
//! Reason category: image import/viewer and editor collapse regression coverage.
//! Old behavior/assertion being replaced: the inventory contains six flows.
//! Why old assertion is no longer correct: `image_memo` and `editor_modes` exercise the reported bugs.
//! Coverage preserved by: sorted discovery and selector validation still cover every flow.
//! Why this is not fitting the test to the implementation: device runs verify immediate image
//! rendering, live page counts and safe editor collapse independently of this inventory.
//! Reason category: search-media navigation, OLED, review options and tablet picker regressions.
//! Old behavior/assertion being replaced: the inventory has eight entries.
//! Why old assertion is no longer correct: five new device flows cover the requested UI paths.
//! Coverage preserved by: exhaustive discovery/selector checks include every new flow.
//! Why this is not fitting the test to the implementation: the unchanged discovery assertion
//! failed on the actual extra YAML files (check2 rust-tests:lomo-xtask), independently of UI outcomes.
//! Reason category: toolbar underline regression flow.
//! Old behavior/assertion being replaced: the committed inventory omits `editor_underline`.
//! Why old assertion is no longer correct: a new flow exercises underline, undo/redo and persistence.
//! Coverage preserved by: unchanged sorted discovery, selector and anchor checks.
//! Why this is not fitting the test to the implementation: discovery failed on the actual extra YAML;
//! the separate device run checks toolbar behavior rather than source tokens.
//! Capability: `just uitest` runs committed Maestro interaction flows against an attached
//! device — the device-free UI debug loop. It owns flow-file resolution, fail-closed
//! argument surface, and the anchor wiring between committed flows and
//! `BenchmarkAnchorContract` resource-id tags.
//! Owning layer: lomo-xtask android command surface; priority: P0 (UI-debug facility wiring).
//!
//! Scenarios:
//! - Given no flow selector, when `uitest_flow_files` resolves, then every committed
//!   top-level `apps/android/uitest/flows/*.yaml` runs in sorted order.
//! - Given a flow name, `name.yaml`, a repository-relative path or an absolute path, when
//!   resolved, then exactly that file is selected; an unknown selector fails closed naming it.
//! - Given the committed flows, when their selectors are inspected, then every
//!   `benchmark_*` resource id exists in `BenchmarkAnchorContract` and every `runFlow`
//!   target is a committed flow file — a renamed anchor or missing file breaks the pin.
//! - Given `lomo-xtask uitest --bogus` or `uitest <unknown-flow>`, when dispatched, then the
//!   command exits non-zero before any adb/device work — never inventing a device run.
//! - Given the xtask sources, when inspected, then the command pins the committed flow
//!   directory, the pinned Maestro version and per-flow report layout.
//!
//! Observable outcomes: resolved flow lists, CLI non-zero exits for bad arguments, and the
//! source-level wiring pins between xtask, flows and the production anchor contract.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_uitest_contract --locked`.
//! RED: before this command no `uitest` entry existed and no committed flow directory was
//! consulted; the anchor pin fails if a flow selector and the contract disagree.
//! Excludes: attached-device execution (covered by `just uitest` runs that emit
//! `android-uitest.v1.json` plus per-flow screenshots under `reports/`).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, bail, ensure};
    use lomo_xtask::{MAESTRO_VERSION, repository_root, uitest_flow_files};

    const FLOW_DIR: &str = "apps/android/uitest/flows";
    const COMMITTED_FLOWS: &[&str] = &[
        "close_editor",
        "create_memo",
        "editor_coordinates",
        "editor_metadata_capsules",
        "editor_modes",
        "editor_underline",
        "image_memo",
        "media_directory_switch",
        "media_navigation",
        "media_shared_directory",
        "memo_code_preview",
        "memo_content_regressions",
        "oled_theme",
        "open_settings",
        "picker_large_screen",
        "pinned_memo_markdown",
        "quick_ball",
        "reminder_capsules",
        "reminder_large_screen",
        "review_options",
        "screens_tour",
        "search_back",
        "search_memo",
        "settings_scroll_restore",
        "setup_workspace",
        "trash_clear",
        "workspace_empty",
    ];
    /// Generated anchor families: `BenchmarkAnchorContract` produces these ids per
    /// runtime value (tag path, memo id, revision id), so a flow token is valid when
    /// the contract file pins the family prefix inside its generator literal.
    const GENERATED_ANCHOR_PREFIXES: &[&str] = &[
        "benchmark_drawer_tag_",
        "benchmark_memo_card_",
        "benchmark_memo_menu_",
        "benchmark_version_history_card_",
        "benchmark_version_history_restore_",
    ];

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn default_resolution_runs_every_committed_flow_sorted() -> Result<()> {
        let root = repository_root()?;
        let flows = uitest_flow_files(&root, &[])?;
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
                flow.parent() == Some(root.join(FLOW_DIR).as_path()),
                "{} must be a top-level flow file",
                flow.display()
            );
        }
        Ok(())
    }

    #[test]
    fn selectors_resolve_names_files_and_paths() -> Result<()> {
        let root = repository_root()?;
        let flow_dir = root.join(FLOW_DIR);
        for selector in ["create_memo", "create_memo.yaml"] {
            let resolved = uitest_flow_files(&root, &[selector.to_owned()])?;
            ensure!(
                resolved == vec![flow_dir.join("create_memo.yaml")],
                "selector {selector} must resolve to the committed flow"
            );
        }
        for selector in [
            "apps/android/uitest/flows/open_settings.yaml",
            "apps/android/uitest/flows/open_settings",
        ] {
            let resolved = uitest_flow_files(&root, &[selector.to_owned()])?;
            ensure!(
                resolved == vec![flow_dir.join("open_settings.yaml")],
                "selector {selector} must resolve to the committed flow"
            );
        }
        let absolute = flow_dir.join("search_memo.yaml");
        let resolved = uitest_flow_files(&root, &[absolute.to_string_lossy().into_owned()])?;
        ensure!(resolved == vec![absolute]);

        let Err(error) = uitest_flow_files(&root, &["bogus-flow".to_owned()]) else {
            bail!("unknown selector must fail closed");
        };
        ensure!(
            error.to_string().contains("bogus-flow"),
            "error must name the rejected selector: {error:#}"
        );
        Ok(())
    }

    #[test]
    fn cli_rejects_unknown_flag_and_unknown_flow_before_device_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["uitest", "--bogus-flag"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown uitest flag must exit non-zero"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("--bogus-flag"),
            "error must name the rejected flag: {stderr}"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["uitest", "bogus-flow"])
            .output()?;
        ensure!(!output.status.success(), "unknown flow must exit non-zero");
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("bogus-flow"),
            "error must name the rejected flow: {stderr}"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["uitest", "--avd", "lomo_bench"])
            .output()?;
        ensure!(
            !output.status.success(),
            "the removed --avd flag must exit non-zero"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("--avd"),
            "error must name the rejected flag: {stderr}"
        );
        Ok(())
    }

    #[test]
    fn committed_flow_selectors_match_anchor_contract_and_runflow_targets() -> Result<()> {
        let root = repository_root()?;
        let anchors =
            read(&root.join("apps/android/app/src/benchmark/BenchmarkAnchorContract.kt"))?;
        for flow in uitest_flow_files(&root, &[])? {
            let text = read(&flow)?;
            let stem = flow
                .file_stem()
                .and_then(|stem| stem.to_str())
                .context("flow file name must be UTF-8")?;
            ensure!(
                text.contains("appId: com.lomo.app"),
                "{stem} must target com.lomo.app"
            );
            for token in text
                .split(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                .filter(|token| token.starts_with("benchmark_"))
            {
                if anchors.contains(&format!("\"{token}\"")) {
                    continue;
                }
                let generated = GENERATED_ANCHOR_PREFIXES.iter().find(|prefix| {
                    token.starts_with(**prefix) && anchors.contains(&format!("\"{prefix}"))
                });
                ensure!(
                    generated.is_some(),
                    "{stem} selects {token} but BenchmarkAnchorContract.kt neither defines \
                     it literally nor pins a generated family prefix for it"
                );
            }
            for (index, line) in text.lines().enumerate() {
                let Some(target) = line.trim().strip_prefix("- runFlow:") else {
                    continue;
                };
                let target = target.trim();
                if target.is_empty() {
                    // Guarded/inline block form: `when:`/`commands:`/`file:` must follow.
                    ensure!(
                        text.contains("when:")
                            || text.contains("commands:")
                            || text.contains("file:"),
                        "{stem}:{} bare runFlow must carry when/commands/file",
                        index + 1
                    );
                    continue;
                }
                if let Some(inline) = target.strip_prefix('{') {
                    ensure!(
                        inline.contains("file:"),
                        "{stem}:{} inline runFlow must carry file:",
                        index + 1
                    );
                    continue;
                }
                let target_path = flow
                    .parent()
                    .context("flow must live in a directory")?
                    .join(target);
                ensure!(
                    target_path.is_file(),
                    "{stem}:{} runFlow target {target} does not exist",
                    index + 1
                );
            }
        }
        Ok(())
    }

    #[test]
    fn source_wiring_pins_flow_dir_maestro_and_report_layout() -> Result<()> {
        let root = repository_root()?;
        let android = read(&root.join("crates/lomo-xtask/src/android.rs"))?;
        for pin in [
            "apps/android/uitest/flows",
            &format!("\"{MAESTRO_VERSION}\""),
            "--test-output-dir",
            "android-uitest.v1.json",
            "sys.boot_completed",
            "stylus_handwriting_enabled",
        ] {
            ensure!(
                android.contains(pin),
                "android.rs missing uitest wiring: {pin}"
            );
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        for pin in ["\"uitest\"", "uitest_command", "--redroid", "--clear-data"] {
            ensure!(cli.contains(pin), "cli.rs missing uitest wiring: {pin}");
        }
        Ok(())
    }
}
