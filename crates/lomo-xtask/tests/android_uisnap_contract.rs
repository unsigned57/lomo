//! Behavior Contract:
//! Capability: `just uishot` captures an atomic, agent-readable snapshot of whatever an
//! attached device currently displays — screenshot, semantic view hierarchy, flattened
//! component index, focused window and a schema-versioned manifest — without driving a
//! flow. It owns snapshot naming, capture sanity checks and evidence layout.
//! Owning layer: lomo-xtask android command surface; priority: P0 (UI-debug facility wiring).
//!
//! Scenarios:
//! - Given `--name <slug>`, when validated, then only 1-64 characters from
//!   [A-Za-z0-9._-] are accepted; traversal or empty input fails closed naming it.
//! - Given a screencap payload, when checked, then non-PNG bytes are always rejected and
//!   a sub-threshold frame is flagged suspicious (blank mid-transition surface).
//! - Given a Maestro `{attributes, children}` tree, when flattened, then only nodes that
//!   identify themselves (id/text/accessibility text or an interactive flag) land in the
//!   component index with depth and bounds preserved — `benchmark_*` ids included.
//! - Given a report root holding timestamped and named snapshot dirs, when a run
//!   finishes, then only `YYYY-MM-DD_hhmmss` dirs beyond the retention keep are
//!   removed oldest-first; named snapshots and stray files are never collected.
//! - Given `lomo-xtask uishot --bogus` or `uishot <name> <extra>`, when dispatched, then
//!   the command exits non-zero before any adb/device work.
//! - Given the xtask sources, when inspected, then the command pins the exec-out
//!   screencap path, `maestro hierarchy`, the `android-uisnap` report layout and the
//!   `uisnap.v1.json` manifest.
//!
//! Observable outcomes: slug validation, PNG sanity checks, flattened component lists,
//! CLI non-zero exits for bad arguments, and source-level wiring pins.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_uisnap_contract --locked`.
//! RED: before this command no `uishot` entry existed; slug/sanity/flatten helpers and
//! the report-dir wiring fail until implemented.
//! Excludes: attached-device execution (covered by `just uishot` runs that emit
//! `uisnap.v1.json` plus artifacts under `reports/android-uisnap/`).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, bail, ensure};
    use lomo_xtask::{
        uishot_flatten_hierarchy, uishot_prune_snapshots, uishot_screenshot_is_png,
        uishot_screenshot_is_suspicious, uishot_slug, uishot_slug_is_timestamp,
    };
    use serde_json::json;

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    fn root() -> Result<std::path::PathBuf> {
        lomo_xtask::repository_root()
    }

    #[test]
    fn slug_accepts_safe_names_and_rejects_unsafe_ones() -> Result<()> {
        for name in ["settings", "memo-menu", "screen_2", "v1.2"] {
            ensure!(
                uishot_slug(Some(name))?.as_str() == name,
                "safe name {name} must round-trip"
            );
        }
        for name in [
            "",
            "../escape",
            "a/b",
            "a\\b",
            "white space",
            "emoji\u{1f600}",
        ] {
            let Err(error) = uishot_slug(Some(name)) else {
                bail!("name {name:?} must fail closed");
            };
            ensure!(
                error.to_string().contains(name),
                "error must name the rejected slug: {error:#}"
            );
        }
        let overlong = "x".repeat(65);
        ensure!(uishot_slug(Some(&overlong)).is_err(), "65 chars must fail");
        Ok(())
    }

    #[test]
    fn slug_defaults_to_utc_timestamp_layout() -> Result<()> {
        let slug = uishot_slug(None)?;
        ensure!(slug.len() == 17, "slug {slug} must be YYYY-MM-DD_hhmmss");
        ensure!(
            matches!(
                slug.as_bytes(),
                [_, _, _, _, b'-', _, _, b'-', _, _, b'_', ..]
            ),
            "slug {slug} must use the timestamp layout"
        );
        ensure!(
            slug.chars()
                .all(|c| c.is_ascii_digit() || c == '-' || c == '_'),
            "slug {slug} must be digits plus separators"
        );
        Ok(())
    }

    #[test]
    fn retention_keeps_newest_timestamped_dirs_and_spares_named_ones() -> Result<()> {
        let root = tempfile::tempdir()?;
        let names = [
            "2026-01-01_000001",
            "2026-01-01_000002",
            "2026-01-01_000003",
            "2026-01-01_000004",
            "2026-13-99_999999", // invalid date but valid layout: still prunable
            "settings",          // named snapshot: never pruned
            "2026-01-01_00000",  // 16 chars: not the timestamp layout
        ];
        for name in names {
            fs::create_dir_all(root.path().join(name))?;
        }
        fs::write(root.path().join("android-uisnap.v1.json"), "{}")?; // stray file ignored

        let pruned = uishot_prune_snapshots(root.path(), 2)?;
        ensure!(
            pruned == 3,
            "5 timestamped dirs minus keep=2 must prune 3, got {pruned}"
        );

        let remaining: Vec<String> = fs::read_dir(root.path())?
            .map(|entry| entry.map(|e| e.file_name().to_string_lossy().into_owned()))
            .collect::<std::io::Result<Vec<String>>>()?;
        for gone in [
            "2026-01-01_000001",
            "2026-01-01_000002",
            "2026-01-01_000003",
        ] {
            ensure!(
                !remaining.iter().any(|n| n == gone),
                "{gone} must be pruned"
            );
        }
        for kept in [
            "2026-01-01_000004",
            "2026-13-99_999999", // sorts newest so it survives this round
            "settings",
            "2026-01-01_00000",
            "android-uisnap.v1.json",
        ] {
            ensure!(remaining.iter().any(|n| n == kept), "{kept} must survive");
        }
        // Idempotent: a second pass removes nothing.
        ensure!(uishot_prune_snapshots(root.path(), 2)? == 0);
        Ok(())
    }

    #[test]
    fn timestamp_layout_detection_is_exact() {
        for stamped in [
            "2026-01-01_000001",
            "1999-12-31_235959",
            "0001-01-01_000000",
        ] {
            assert!(uishot_slug_is_timestamp(stamped), "{stamped} must match");
        }
        for other in [
            "settings",
            "2026-01-01_00001",   // 16 chars
            "2026-01-01_0000001", // 18 chars
            "2026/01/01_000001",  // wrong separators
            "2026-01-01T000001",  // wrong separator
            "abcd-ef-gh_ijklmn",  // non-digits
        ] {
            assert!(!uishot_slug_is_timestamp(other), "{other} must not match");
        }
    }

    #[test]
    fn screenshot_sanity_rejects_non_png_and_flags_tiny_frames() {
        let mut png = b"\x89PNG\r\n\x1a\n".to_vec();
        png.resize(64 * 1024, 1u8);
        assert!(uishot_screenshot_is_png(&png));
        assert!(!uishot_screenshot_is_suspicious(&png));

        assert!(!uishot_screenshot_is_png(b"not an image"));
        assert!(uishot_screenshot_is_suspicious(b"\x89PNG\r\n\x1a\n"));
        assert!(!uishot_screenshot_is_png(&[]));
    }

    #[test]
    fn flatten_keeps_identifying_nodes_with_depth_and_bounds() -> Result<()> {
        let tree = json!({
            "attributes": {},
            "children": [{
                "attributes": {
                    "resource-id": "benchmark_main_root",
                    "text": "",
                    "accessibilityText": "",
                    "bounds": "[0,0][1080,2400]",
                    "clickable": "false",
                    "scrollable": "false",
                    "focused": "false",
                    "class": "android.widget.FrameLayout"
                },
                "children": [
                    {
                        "attributes": {
                            "resource-id": "",
                            "text": "",
                            "accessibilityText": "",
                            "bounds": "[0,0][100,100]",
                            "clickable": "false",
                            "class": "android.widget.View"
                        },
                        "children": []
                    },
                    {
                        "attributes": {
                            "resource-id": "benchmark_main_create_fab",
                            "text": "",
                            "accessibilityText": "New memo",
                            "bounds": "[800,2200][1000,2350]",
                            "clickable": "true",
                            "scrollable": "false",
                            "focused": "false",
                            "class": "android.widget.Button"
                        },
                        "children": []
                    }
                ]
            }]
        });
        let components = uishot_flatten_hierarchy(&tree);
        ensure!(
            components.len() == 2,
            "empty non-interactive node must be filtered: {components:?}"
        );
        let [root_node, fab] = components.as_slice() else {
            bail!("flatten produced {} nodes", components.len());
        };
        ensure!(root_node["id"] == "benchmark_main_root");
        ensure!(root_node["depth"] == 1 && root_node["idx"] == 0);
        ensure!(fab["id"] == "benchmark_main_create_fab");
        ensure!(fab["accessibility_text"] == "New memo");
        ensure!(fab["bounds"] == "[800,2200][1000,2350]");
        ensure!(fab["clickable"] == true && fab["scrollable"] == false);
        Ok(())
    }

    #[test]
    fn cli_rejects_bad_arguments_before_device_work() -> Result<()> {
        for args in [
            ["uishot", "--bogus-flag"].as_slice(),
            ["uishot", "one", "two"].as_slice(),
            // The removed `--avd` surface rejects as an unknown flag, not a silent alias.
            ["uishot", "--avd", "lomo_bench"].as_slice(),
        ] {
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(args)
                .output()?;
            ensure!(
                !output.status.success(),
                "{args:?} must exit non-zero before device work"
            );
            let stderr = String::from_utf8_lossy(&output.stderr);
            ensure!(
                !stderr.is_empty(),
                "{args:?} must explain the rejection on stderr"
            );
        }
        Ok(())
    }

    #[test]
    fn source_wiring_pins_capture_path_and_report_layout() -> Result<()> {
        let root = root()?;
        let android = read(&root.join("crates/lomo-xtask/src/android.rs"))?;
        for pin in [
            "android-uisnap",
            "uisnap.v1.json",
            "exec-out",
            "screencap",
            "hierarchy",
            "mCurrentFocus",
            "android_uisnap",
        ] {
            ensure!(
                android.contains(pin),
                "android.rs missing uishot wiring: {pin}"
            );
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        for pin in ["\"uishot\"", "uishot_command", "--redroid"] {
            ensure!(cli.contains(pin), "cli.rs missing uishot wiring: {pin}");
        }
        let justfile = read(&root.join("Justfile"))?;
        ensure!(
            justfile.contains("uishot *args:"),
            "Justfile missing the uishot recipe"
        );
        Ok(())
    }
}
