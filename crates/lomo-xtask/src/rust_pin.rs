//! Sole repository Rust toolchain pin: `rust-toolchain.toml` `channel`.
//!
//! `workspace.package.rust-version` must equal the channel's major.minor (MSRV line).
//! Bootstrap, cargo `+channel` installs, and version checks read this module only.

use std::{fs, path::Path};

use anyhow::{Context, Result, bail};

use crate::workspace::Workspace;

/// Pinned rustup channel (`x.y` or `x.y.z`). Floating names are rejected.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RustPin {
    /// Exact channel string as written in `rust-toolchain.toml`.
    pub channel: String,
    /// `major.minor` used for `package.rust-version` and soft version prefix checks.
    pub msrv: String,
}

impl RustPin {
    /// rustup `+channel` selector for this pin.
    #[must_use]
    pub fn cargo_plus_toolchain(&self) -> String {
        format!("+{}", self.channel)
    }

    /// Whether a `rustc --version` line satisfies this pin.
    #[must_use]
    pub fn matches_rustc_version_line(&self, version_line: &str) -> bool {
        version_line
            .split_whitespace()
            .any(|token| version_token_matches_pin(token, &self.channel, &self.msrv))
    }
}

/// Load the pin from `rust-toolchain.toml` and prove `Cargo.toml` rust-version agrees.
pub fn load(workspace: &Workspace) -> Result<RustPin> {
    let channel = read_toolchain_channel(&workspace.rust.join("rust-toolchain.toml"))?;
    let pin = parse_channel(&channel)?;
    let rust_version = read_workspace_rust_version(&workspace.rust.join("Cargo.toml"))?;
    if rust_version != pin.msrv {
        bail!(
            "rust-version drift: Cargo.toml has {rust_version}, \
             rust-toolchain.toml channel {} implies msrv {}; run \
             `just rust-toolchain-bump {}` or align rust-version to {}",
            pin.channel,
            pin.msrv,
            pin.channel,
            pin.msrv
        );
    }
    Ok(pin)
}

/// Parse and validate a user-supplied channel for bump (no Cargo.toml check).
///
/// # Errors
///
/// Returns an error when the channel is empty, a floating name (`stable`/`beta`/`nightly`),
/// or not an `x.y` / `x.y.z` pin.
pub fn parse_channel(channel: &str) -> Result<RustPin> {
    let channel = channel.trim();
    if channel.is_empty() {
        bail!("Rust channel must not be empty");
    }
    let lower = channel.to_ascii_lowercase();
    if matches!(
        lower.as_str(),
        "stable" | "beta" | "nightly" | "stable-*" | "beta-*" | "nightly-*"
    ) || lower.starts_with("stable-")
        || lower.starts_with("beta-")
        || lower.starts_with("nightly-")
    {
        bail!(
            "floating or named channels are forbidden; pin an exact x.y or x.y.z (got {channel})"
        );
    }
    let msrv = msrv_from_channel(channel)?;
    Ok(RustPin {
        channel: channel.to_owned(),
        msrv,
    })
}

/// Apply a new channel to repository pin sites (toolchain, rust-version, docs, CI cache keys).
///
/// Does not claim quality green: caller runs bootstrap and gates.
pub fn bump(workspace: &Workspace, new_channel: &str, dry_run: bool) -> Result<()> {
    let new_pin = parse_channel(new_channel)?;
    // Repair path: if rust-version already drifted, still read the channel file.
    let old_pin = if let Ok(pin) = load(workspace) {
        pin
    } else {
        let channel = read_toolchain_channel(&workspace.rust.join("rust-toolchain.toml"))?;
        parse_channel(&channel)?
    };
    if old_pin.channel == new_pin.channel && old_pin.msrv == new_pin.msrv {
        crate::util::emit_stderr(format_args!(
            "xtask: rust toolchain already pinned to {} (msrv {})",
            new_pin.channel, new_pin.msrv
        ));
        return Ok(());
    }

    let replacements = planned_replacements(workspace, &old_pin, &new_pin)?;
    crate::util::emit_stderr(format_args!(
        "xtask: rust toolchain bump {} → {} (msrv {} → {})",
        old_pin.channel, new_pin.channel, old_pin.msrv, new_pin.msrv
    ));
    for (path, _before, after) in &replacements {
        let relative = path.strip_prefix(&workspace.root).unwrap_or(path.as_path());
        if dry_run {
            crate::util::emit_stderr(format_args!(
                "xtask: dry-run would write {}",
                relative.display()
            ));
        } else {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)
                    .with_context(|| format!("failed to create {}", parent.display()))?;
            }
            fs::write(path, after)
                .with_context(|| format!("failed to write {}", path.display()))?;
            crate::util::emit_stderr(format_args!("xtask: wrote {}", relative.display()));
        }
    }

    if dry_run {
        crate::util::emit_stderr(format_args!(
            "xtask: dry-run complete; re-run without --dry-run to apply"
        ));
        return Ok(());
    }

    crate::util::emit_stderr(format_args!(
        "xtask: pin files updated. Next: `just bootstrap`, then fix deny/clippy, then \
         `just check` / `just ci`. This command does not mark the upgrade green."
    ));
    Ok(())
}

fn planned_replacements(
    workspace: &Workspace,
    old: &RustPin,
    new: &RustPin,
) -> Result<Vec<(std::path::PathBuf, String, String)>> {
    let mut out = Vec::new();

    let toolchain_path = workspace.rust.join("rust-toolchain.toml");
    let toolchain = fs::read_to_string(&toolchain_path)
        .with_context(|| format!("failed to read {}", toolchain_path.display()))?;
    let toolchain_next = replace_toml_assignment(&toolchain, "channel", &new.channel)?;
    out.push((toolchain_path, toolchain, toolchain_next));

    let cargo_path = workspace.rust.join("Cargo.toml");
    let cargo = fs::read_to_string(&cargo_path)
        .with_context(|| format!("failed to read {}", cargo_path.display()))?;
    let cargo_next = replace_toml_assignment(&cargo, "rust-version", &new.msrv)?;
    out.push((cargo_path, cargo, cargo_next));

    // Human-facing pin lines and CI cache key prefixes use major.minor (msrv).
    let doc_paths = [
        "quality/README.md",
        "CLAUDE.md",
        "AGENTS.md",
        ".github/workflows/android_release.yml",
        ".github/workflows/architecture_checks.yml",
    ];
    for relative in doc_paths {
        let path = workspace.root.join(relative);
        if !path.is_file() {
            continue;
        }
        let before = fs::read_to_string(&path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let after = replace_pin_mentions(&before, old, new);
        if after != before {
            out.push((path, before, after));
        }
    }

    Ok(out)
}

fn replace_pin_mentions(text: &str, old: &RustPin, new: &RustPin) -> String {
    let mut result = text.to_owned();
    // Cache keys and prose: prefer longer old channel first, then msrv.
    if old.channel != old.msrv {
        result = result.replace(&old.channel, &new.channel);
    }
    result = result.replace(&old.msrv, &new.msrv);
    result
}

/// Rewrite `key = "value"` in a TOML snippet; other keys stay intact.
///
/// # Errors
///
/// Returns an error when the key assignment is missing or unterminated.
pub fn replace_toml_assignment(text: &str, key: &str, value: &str) -> Result<String> {
    let prefix = format!("{key} = \"");
    let Some(start) = text.find(&prefix) else {
        bail!("missing `{key} = \"...\"` assignment");
    };
    let value_start = start + prefix.len();
    // `find` on an ASCII key prefix yields a UTF-8 char boundary.
    let (head, rest) = text.split_at(value_start);
    let Some(end_rel) = rest.find('"') else {
        bail!("unterminated `{key}` string");
    };
    let (_old_value, tail) = rest.split_at(end_rel);
    let mut out = String::with_capacity(text.len() + value.len());
    out.push_str(head);
    out.push_str(value);
    out.push_str(tail);
    Ok(out)
}

fn read_toolchain_channel(path: &Path) -> Result<String> {
    let text =
        fs::read_to_string(path).with_context(|| format!("failed to read {}", path.display()))?;
    let mut in_toolchain = false;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') {
            in_toolchain = line == "[toolchain]";
            continue;
        }
        if !in_toolchain {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if key.trim() == "channel" {
            return Ok(value.trim().trim_matches('"').to_owned());
        }
    }
    bail!("{} is missing [toolchain] channel", path.display())
}

fn read_workspace_rust_version(path: &Path) -> Result<String> {
    let text =
        fs::read_to_string(path).with_context(|| format!("failed to read {}", path.display()))?;
    // Prefer [workspace.package] rust-version; fall back to first rust-version assignment.
    let mut in_workspace_package = false;
    let mut first = None;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') {
            in_workspace_package = line == "[workspace.package]";
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        if key.trim() != "rust-version" {
            continue;
        }
        let value = value.trim().trim_matches('"').to_owned();
        if in_workspace_package {
            return Ok(value);
        }
        first.get_or_insert(value);
    }
    first.context(format!("{} is missing rust-version", path.display()))
}

fn msrv_from_channel(channel: &str) -> Result<String> {
    let mut parts = channel.split('.');
    let major = parts.next().context("channel missing major version")?;
    let minor = parts.next().context("channel must be x.y or x.y.z")?;
    if major.is_empty() || minor.is_empty() {
        bail!("invalid channel `{channel}`");
    }
    if !major.chars().all(|c| c.is_ascii_digit()) || !minor.chars().all(|c| c.is_ascii_digit()) {
        bail!("channel major.minor must be numeric (got {channel})");
    }
    if let Some(patch) = parts.next()
        && (!patch.chars().all(|c| c.is_ascii_digit()) || parts.next().is_some())
    {
        bail!("channel must be x.y or x.y.z (got {channel})");
    }
    Ok(format!("{major}.{minor}"))
}

fn version_token_matches_pin(token: &str, channel: &str, msrv: &str) -> bool {
    if token == channel {
        return true;
    }
    // Channel `1.96` accepts rustc `1.96.1`.
    if channel == msrv {
        return token == msrv || token.starts_with(&format!("{msrv}."));
    }
    // Exact patch pin: require full match.
    token == channel
}
