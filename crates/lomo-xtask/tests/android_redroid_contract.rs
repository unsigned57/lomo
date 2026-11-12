//! Behavior Contract:
//! Capability: `just redroid <up|down|status>` owns the pinned redroid container
//! backend, and device selection is unambiguous — adb serials are transports, not
//! devices, so the same OS instance reachable through `emulator-5554` and
//! `127.0.0.1:5555` resolves to one canonical `host:port` serial.
//! Owning layer: lomo-xtask android command surface; priority: P0 (device selection
//! correctness decides which machine every UI/benchmark run mutates).
//!
//! Scenarios:
//! - Given two serials sharing one kernel `boot_id` (the observed redroid dual
//!   registration), when deduplicated, then one device remains and the `host:port`
//!   serial wins.
//! - Given serials with distinct `boot_id`s, when deduplicated, then every unique
//!   device stays — ambiguity survives to the `ANDROID_SERIAL` gate, never a first-row
//!   pick.
//! - Given a group with no `host:port` transport, when deduplicated, then the first
//!   seen serial is canonical.
//! - Given `LOMO_REDROID_GPU_MODE` values, when the token shape is checked, then
//!   documented lowercase modes pass and empty/mixed-case/whitespace/shell fragments
//!   are rejected before reaching the kernel cmdline.
//! - Given `lomo-xtask redroid` with a missing or unknown action, when dispatched,
//!   then the command exits non-zero naming the usage — before any adb/engine work.
//! - Given the xtask/Justfile sources, when inspected, then `ANDROID_SERIAL`,
//!   `boot_id` dedup, the GPU-mode env, image prefetch, rootful stop guidance and the
//!   `redroid` recipe are wired.
//!
//! Observable outcomes: dedup/gpu-mode function results, CLI non-zero exits, and
//! source-level pins between the lifecycle surface and its implementation.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_redroid_contract --locked`.
//! RED: `deduplicate_device_serials`/`valid_redroid_gpu_mode` did not exist and no
//! `redroid` command was registered before this change.
//! Excludes: live adb/engine interaction (covered by `just redroid status|up|down`
//! runs on a host with the toolchain bootstrapped).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{deduplicate_device_serials, repository_root, valid_redroid_gpu_mode};

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    fn pairs(entries: &[(&str, &str)]) -> Vec<(String, String)> {
        entries
            .iter()
            .map(|(serial, boot)| ((*serial).to_owned(), (*boot).to_owned()))
            .collect()
    }

    #[test]
    fn redroid_dual_transports_collapse_to_the_tcp_serial() {
        // Observed on the dev host: one `lomo-redroid` container answering both its
        // adbd emulator transport and the published TCP port under one boot_id.
        let serials = deduplicate_device_serials(pairs(&[
            ("emulator-5554", "boot-a"),
            ("127.0.0.1:5555", "boot-a"),
        ]));
        assert_eq!(serials, vec!["127.0.0.1:5555".to_owned()]);

        // Registration order must not matter.
        let serials = deduplicate_device_serials(pairs(&[
            ("127.0.0.1:5555", "boot-a"),
            ("emulator-5554", "boot-a"),
        ]));
        assert_eq!(serials, vec!["127.0.0.1:5555".to_owned()]);
    }

    #[test]
    fn distinct_machines_and_fallback_serials_stay_visible() {
        let serials = deduplicate_device_serials(pairs(&[
            ("emulator-5554", "boot-a"),
            ("127.0.0.1:5555", "boot-a"),
            ("0a1b2c3d", "boot-b"),
        ]));
        assert_eq!(
            serials,
            vec!["127.0.0.1:5555".to_owned(), "0a1b2c3d".to_owned()]
        );

        // No `host:port` transport in a group: the first seen serial is canonical.
        let serials = deduplicate_device_serials(pairs(&[("0a1b2c3d", "boot-b")]));
        assert_eq!(serials, vec!["0a1b2c3d".to_owned()]);
    }

    #[test]
    fn gpu_mode_token_gate_accepts_documented_modes_only() {
        for mode in ["host", "guest", "angle", "host_nogpu"] {
            assert!(valid_redroid_gpu_mode(mode), "{mode} must pass");
        }
        for mode in [
            "",
            "Host",
            "HOST",
            "guest mode",
            "host;rm",
            "host=1",
            "🇭ost",
        ] {
            assert!(!valid_redroid_gpu_mode(mode), "{mode:?} must fail");
        }
    }

    #[test]
    fn cli_rejects_missing_and_unknown_redroid_action_before_device_work() -> Result<()> {
        for args in [vec!["redroid"], vec!["redroid", "bogus"]] {
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(&args)
                .output()?;
            ensure!(
                !output.status.success(),
                "{args:?} must exit non-zero before any adb work"
            );
            let stderr = String::from_utf8_lossy(&output.stderr);
            ensure!(
                stderr.contains("up") && stderr.contains("down") && stderr.contains("status"),
                "usage error must name the actions: {stderr}"
            );
        }
        Ok(())
    }

    #[test]
    fn cli_rejects_unknown_redroid_flag() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["redroid", "--bogus"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown redroid argument must exit non-zero"
        );
        Ok(())
    }

    #[test]
    fn source_wiring_pins_selection_gpu_and_lifecycle() -> Result<()> {
        let root = repository_root()?;
        let android = read(&root.join("crates/lomo-xtask/src/android.rs"))?;
        for pin in [
            "ANDROID_SERIAL",
            "/proc/sys/kernel/random/boot_id",
            "LOMO_REDROID_GPU_MODE",
            "androidboot.redroid_gpu_mode=",
            "image\", \"exists\"",
            "prefetch_redroid_image",
            "stop {REDROID_CONTAINER}",
            "multiple attached devices",
        ] {
            ensure!(android.contains(pin), "android.rs missing wiring: {pin}");
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        for pin in ["\"redroid\"", "android::redroid"] {
            ensure!(cli.contains(pin), "cli.rs missing wiring: {pin}");
        }
        let tools = read(&root.join("crates/lomo-xtask/src/tools.rs"))?;
        ensure!(
            tools.contains("prefetch_redroid_image"),
            "bootstrap must prefetch the pinned redroid image"
        );
        let justfile = read(&root.join("Justfile"))?;
        ensure!(
            justfile.contains("redroid *args"),
            "Justfile missing the `just redroid` recipe"
        );
        Ok(())
    }

    #[test]
    fn committed_quadlet_template_mirrors_the_provisioned_container() -> Result<()> {
        let root = repository_root()?;
        let unit = read(&root.join("apps/android/uitest/lomo-redroid.container"))?;
        for pin in [
            "Image=docker.io/redroid/redroid:15.0.0_64only-250627",
            "ContainerName=lomo-redroid",
            "--privileged",
            "Tmpfs=/data:rw,exec,size=2G",
            "PublishPort=5555:5555",
            "androidboot.redroid_gpu_mode=",
        ] {
            ensure!(unit.contains(pin), "quadlet template missing: {pin}");
        }
        Ok(())
    }
}
