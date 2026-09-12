//! Generic Linux `x86_64` TUI archive. Strips `target-cpu=native`; no personal toolchain paths.

use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail};

use crate::{
    util::{cargo, emit_stderr, remove_if_exists, run},
    workspace::Workspace,
};

pub const ARCHIVE_NAME: &str = "lomo-linux-x86_64.tar.gz";
pub const BINARY_REL: &str = "bin/lomo";
pub const CONFIG_REL: &str = "config/config.toml.example";
pub const README_REL: &str = "README.md";

pub const CONFIG_TEMPLATE: &str = "\
# Copy to $XDG_CONFIG_HOME/lomo/config.toml (usually ~/.config/lomo/config.toml).
workspace = \"/path/to/notes\"
time_zone = \"UTC\"
# editor = [\"helix\"]
# If editor is omitted, lomo uses $VISUAL then $EDITOR. It never defaults to vim.
# player = [\"xdg-open\"]
";

pub const PACKAGE_README: &str = "\
# Lomo Linux TUI

Generic x86_64 Linux binary for a Lomo Markdown workspace.

## Layout

- `bin/lomo` — terminal UI
- `config/config.toml.example` — optional template; first run writes `$XDG_CONFIG_HOME/lomo/config.toml`

## Run

First run writes `$XDG_CONFIG_HOME/lomo/config.toml` with `workspace` set to `$HOME/Notes`,
or to a directory passed as `./bin/lomo /path/to/notes`. An existing file is never overwritten.

```
export XDG_RUNTIME_DIR=\"${XDG_RUNTIME_DIR:-/run/user/$(id -u)}\"
./bin/lomo
```

Editor priority is the `editor` argv in config, then `$VISUAL`, then `$EDITOR`. Missing clipboard,
player, or `$XDG_RUNTIME_DIR` fails closed. Linux network sync and LAN sharing are out of this
archive.

This archive is a generic x86_64 build (not tuned to the packager's CPU).
";

/// Removes `target-cpu=native` from space-separated `RUSTFLAGS`.
#[must_use]
pub fn strip_native_cpu_from_space_separated(raw: &str) -> String {
    strip_native_cpu_tokens(raw.split_whitespace(), " ")
}

/// Removes `target-cpu=native` from `CARGO_ENCODED_RUSTFLAGS` (`\\x1f`-separated).
#[must_use]
pub fn strip_native_cpu_from_encoded(raw: &str) -> String {
    strip_native_cpu_tokens(raw.split('\u{1f}'), "\u{1f}")
}

fn strip_native_cpu_tokens<'a>(tokens: impl Iterator<Item = &'a str>, join: &str) -> String {
    let mut iter = tokens.peekable();
    let mut kept = Vec::new();
    while let Some(token) = iter.next() {
        if token == "-C"
            && iter
                .peek()
                .is_some_and(|next| next.contains("target-cpu=native"))
        {
            if iter.next().is_none() {
                break;
            }
            continue;
        }
        if token.contains("target-cpu=native") {
            continue;
        }
        kept.push(token);
    }
    kept.join(join)
}

/// Copies the release binary and writes the config template plus short Linux README.
///
/// # Errors
/// Missing binary, I/O failure, or a staged document that still mentions `target-cpu=native`.
pub fn stage_linux_package(stage_root: &Path, binary: &Path) -> Result<()> {
    if !binary.is_file() {
        bail!("linux package binary is missing: {}", binary.display());
    }
    let bin_dir = stage_root.join("bin");
    let config_dir = stage_root.join("config");
    fs::create_dir_all(&bin_dir)
        .with_context(|| format!("failed to create {}", bin_dir.display()))?;
    fs::create_dir_all(&config_dir)
        .with_context(|| format!("failed to create {}", config_dir.display()))?;
    let staged_binary = stage_root.join(BINARY_REL);
    fs::copy(binary, &staged_binary)
        .with_context(|| format!("failed to copy {}", binary.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(&staged_binary)
            .with_context(|| format!("failed to stat {}", staged_binary.display()))?
            .permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&staged_binary, permissions)
            .with_context(|| format!("failed to chmod {}", staged_binary.display()))?;
    }
    fs::write(stage_root.join(CONFIG_REL), CONFIG_TEMPLATE)
        .with_context(|| format!("failed to write {CONFIG_REL}"))?;
    fs::write(stage_root.join(README_REL), PACKAGE_README)
        .with_context(|| format!("failed to write {README_REL}"))?;
    verify_staged_layout(stage_root)
}

/// Checks the staged tree for required files and forbidden native-CPU wording.
///
/// # Errors
/// Missing files or `target-cpu=native` leaking into docs.
pub fn verify_staged_layout(stage_root: &Path) -> Result<()> {
    let binary = stage_root.join(BINARY_REL);
    if !binary.is_file() {
        bail!("staged archive is missing {BINARY_REL}");
    }
    for relative in [CONFIG_REL, README_REL] {
        let path = stage_root.join(relative);
        let text = fs::read_to_string(&path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        if text.contains("target-cpu=native") {
            bail!("{relative} must not mention target-cpu=native");
        }
        if text.contains("/home/") || text.contains("C:\\") {
            bail!("{relative} must not embed personal toolchain paths");
        }
    }
    let readme = fs::read_to_string(stage_root.join(README_REL))?;
    if !readme.contains("bin/lomo") {
        bail!("package README must name bin/lomo");
    }
    Ok(())
}

/// Builds `lomo-tui` in release and writes `target/lomo/dist/lomo-linux-x86_64.tar.gz`.
///
/// # Errors
/// Non-`x86_64` Linux host, missing `tar`, cargo build failure, or archive I/O failure.
pub fn package_linux(workspace: &Workspace) -> Result<()> {
    if !cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        bail!("package-linux produces a generic x86_64 Linux archive and must run on linux x86_64");
    }
    ensure_tar_available()?;
    build_generic_tui(workspace)?;
    let binary = workspace.rust_target().join("release").join("lomo");
    let dist = workspace.linux_dist_dir();
    let stage = dist.join("linux-x86_64-stage");
    let archive = dist_archive_path(workspace);
    fs::create_dir_all(&dist).with_context(|| format!("failed to create {}", dist.display()))?;
    remove_if_exists(&stage)?;
    remove_if_exists(&archive)?;
    fs::create_dir_all(&stage).with_context(|| format!("failed to create {}", stage.display()))?;
    stage_linux_package(&stage, &binary)?;
    let mut tar = Command::new("tar");
    tar.current_dir(&stage).args([
        "-czf",
        archive.to_str().context("archive path is not UTF-8")?,
        ".",
    ]);
    run(&mut tar)?;
    emit_stderr(format_args!(
        "xtask: Linux archive ready: {}",
        archive.display()
    ));
    Ok(())
}

fn ensure_tar_available() -> Result<()> {
    let status = Command::new("tar")
        .arg("--version")
        .status()
        .context("tar is required to build the Linux archive")?;
    if !status.success() {
        bail!("tar --version failed; install tar to run package-linux");
    }
    Ok(())
}

fn build_generic_tui(workspace: &Workspace) -> Result<()> {
    let mut command = cargo(workspace);
    command.args(["build", "-p", "lomo-tui", "--release", "--locked"]);
    apply_generic_rustflags(&mut command, workspace)?;
    run(&mut command)
}

/// Combines cleaned user flags with `--remap-path-prefix` so panic paths are not personal.
#[must_use]
pub fn generic_rustflags(
    existing: Option<&str>,
    workspace_root: &Path,
    cargo_home: &Path,
    rustup_home: &Path,
) -> String {
    let mut flags = existing.map_or_else(String::new, strip_native_cpu_from_space_separated);
    for (from, to) in [
        (workspace_root, "lomo"),
        (cargo_home, "cargo-home"),
        (rustup_home, "rustup"),
    ] {
        let flag = format!("--remap-path-prefix={}={to}", from.display());
        if flags.split_whitespace().any(|token| token == flag) {
            continue;
        }
        if !flags.is_empty() {
            flags.push(' ');
        }
        flags.push_str(&flag);
    }
    flags
}

fn apply_generic_rustflags(command: &mut Command, workspace: &Workspace) -> Result<()> {
    let rustup_home = rustup_home_path();
    let existing = match env::var("RUSTFLAGS") {
        Ok(flags) => Some(flags),
        Err(env::VarError::NotPresent) => None,
        Err(error) => bail!("RUSTFLAGS is not valid UTF-8: {error}"),
    };
    let rustflags = generic_rustflags(
        existing.as_deref(),
        &workspace.root,
        &workspace.cargo_home,
        &rustup_home,
    );
    command.env("RUSTFLAGS", &rustflags);
    match env::var("CARGO_ENCODED_RUSTFLAGS") {
        Ok(encoded) => {
            let cleaned = strip_native_cpu_from_encoded(&encoded);
            let remapped = generic_rustflags(
                Some(&cleaned.replace('\u{1f}', " ")),
                &workspace.root,
                &workspace.cargo_home,
                &rustup_home,
            );
            command.env("CARGO_ENCODED_RUSTFLAGS", remapped.replace(' ', "\u{1f}"));
        }
        Err(env::VarError::NotPresent) => {}
        Err(error) => bail!("CARGO_ENCODED_RUSTFLAGS is not valid UTF-8: {error}"),
    }
    Ok(())
}

fn rustup_home_path() -> PathBuf {
    if let Some(path) = env::var_os("RUSTUP_HOME") {
        return PathBuf::from(path);
    }
    env::var_os("HOME").map_or_else(
        || PathBuf::from("/nonexistent-rustup"),
        |home| PathBuf::from(home).join(".rustup"),
    )
}

#[must_use]
pub fn dist_archive_path(workspace: &Workspace) -> PathBuf {
    workspace.linux_dist_dir().join(ARCHIVE_NAME)
}
