//! Behavior Contract
//! Capability: generic Linux `x86_64` TUI packaging stages a relocatable tree and rejects native-CPU flags.
//! Scenarios:
//! - Given a stub `lomo` binary, when staged, then `bin/lomo`, config template, and README exist.
//! - Given user `RUSTFLAGS` include host-CPU tuning and personal prefixes, when generic flags are
//!   composed, then native-CPU tokens are removed and `--remap-path-prefix` hides personal paths.
//! - Given extra CLI arguments, when `package-linux` runs, then the command fails closed.
//! - Given `--help`, when xtask prints commands, then `tui`, `check-linux`, and `package-linux`
//!   are listed and retired smoke recipes are absent.
//! - Given `device-smoke` or `sync-provider-smoke`, when xtask runs, then the command is unknown.
//!
//! Observable outcomes: staged files, cleaned flag strings, CLI error text, help text.
//! TDD proof: `package-linux` was absent from Justfile/xtask before this package.
//! Excludes: running a full `lomo-tui` release compile inside this contract.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing package facts"
)]
mod tests {
    use std::{fs, os::unix::fs::PermissionsExt, path::Path, process::Command};

    use lomo_xtask::{
        package::{
            BINARY_REL, CONFIG_REL, README_REL, generic_rustflags, stage_linux_package,
            strip_native_cpu_from_encoded, strip_native_cpu_from_space_separated,
        },
        run_cli,
    };
    use tempfile::tempdir;

    #[test]
    fn stages_binary_config_and_readme_as_relocatable_tree() {
        let root = tempdir().expect("temp");
        let stub = root.path().join("lomo-stub");
        fs::write(&stub, b"stub-lomo-binary").expect("stub");
        let stage = root.path().join("stage");
        stage_linux_package(&stage, &stub).expect("stage");

        let staged = stage.join(BINARY_REL);
        assert_eq!(fs::read(&staged).expect("read bin"), b"stub-lomo-binary");
        let mode = fs::metadata(&staged).expect("meta").permissions().mode();
        assert_eq!(mode & 0o111, 0o111, "staged binary must be executable");

        let config = fs::read_to_string(stage.join(CONFIG_REL)).expect("config");
        assert!(config.contains("workspace = "));
        assert!(config.contains("$XDG_CONFIG_HOME/lomo/config.toml"));
        assert!(!config.contains("target-cpu=native"));
        assert!(!config.contains("/home/"));

        let readme = fs::read_to_string(stage.join(README_REL)).expect("readme");
        assert!(readme.contains("bin/lomo"));
        assert!(!readme.contains("target-cpu=native"));
        assert!(!readme.contains("/home/"));
    }

    #[test]
    fn missing_binary_fails_closed() {
        let root = tempdir().expect("temp");
        let missing = root.path().join("absent");
        let error = stage_linux_package(root.path().join("stage").as_path(), &missing)
            .expect_err("missing binary");
        assert!(error.to_string().contains("missing"), "got {error}");
    }

    #[test]
    fn generic_rustflags_strip_native_cpu_and_remap_personal_prefixes() {
        let flags = generic_rustflags(
            Some("-C target-cpu=native -C link-arg=-fuse-ld=lld"),
            Path::new("/home/builder/lomo-multiplatform"),
            Path::new("/home/builder/.cargo"),
            Path::new("/home/builder/.rustup"),
        );
        assert!(!flags.contains("target-cpu=native"));
        assert!(flags.contains("-C link-arg=-fuse-ld=lld"));
        assert!(flags.contains("--remap-path-prefix=/home/builder/lomo-multiplatform=lomo"));
        assert!(flags.contains("--remap-path-prefix=/home/builder/.cargo=cargo-home"));
        assert!(flags.contains("--remap-path-prefix=/home/builder/.rustup=rustup"));
    }

    #[test]
    fn strips_native_cpu_from_rustflags_and_encoded_flags() {
        assert_eq!(
            strip_native_cpu_from_space_separated("-C target-cpu=native -C link-arg=-fuse-ld=lld"),
            "-C link-arg=-fuse-ld=lld"
        );
        assert_eq!(
            strip_native_cpu_from_space_separated("-Ctarget-cpu=native"),
            ""
        );
        let encoded = ["-C", "target-cpu=native", "-C", "link-arg=-fuse-ld=lld"].join("\u{1f}");
        assert_eq!(
            strip_native_cpu_from_encoded(&encoded),
            ["-C", "link-arg=-fuse-ld=lld"].join("\u{1f}")
        );
    }

    #[test]
    fn cli_rejects_extraneous_package_linux_arguments() {
        match run_cli(&["package-linux".to_owned(), "--unexpected".to_owned()]) {
            Ok(()) => panic!("package-linux must reject extra arguments"),
            Err(error) => {
                let message = error.to_string();
                assert!(
                    message.contains("command does not accept arguments"),
                    "got {message}"
                );
            }
        }
    }

    #[test]
    fn help_lists_linux_tui_surface_and_omits_retired_smoke() {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("help")
            .output()
            .expect("xtask help");
        assert!(output.status.success());
        let stderr = String::from_utf8_lossy(&output.stderr);
        for required in ["tui", "check-linux", "package-linux"] {
            assert!(
                stderr.contains(required),
                "help must list {required}, got {stderr}"
            );
        }
        for retired in ["device-smoke", "sync-provider-smoke"] {
            assert!(
                !stderr.contains(retired),
                "help must not list retired {retired}, got {stderr}"
            );
        }
    }

    #[test]
    fn cli_rejects_retired_smoke_commands() {
        for command in ["device-smoke", "sync-provider-smoke"] {
            match run_cli(&[command.to_owned()]) {
                Ok(()) => panic!("{command} must be unknown"),
                Err(error) => {
                    let message = error.to_string();
                    assert!(
                        message.contains("unknown xtask command"),
                        "{command} got {message}"
                    );
                }
            }
        }
    }
}
