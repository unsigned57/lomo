//! Toolchain pin parity: a fact duplicated across manifests stays identical.

use super::Violation;

/// One `(git, rev)` pair — a `rev` is only meaningful inside its `git` remote.
struct Pin {
    git: String,
    rev: String,
}

/// The `BoltFFI` CLI (`cargo install --git … --rev … boltffi_cli` from
/// `tools.toml` `[ffi.boltffi_cli]`) and the `boltffi_core` runtime dependency
/// (`Cargo.toml` `[workspace.dependencies]`) are two writes of the same commit.
/// A one-sided bump — or a `git` remote change under an identical `rev` —
/// splits the generated bindings from the runtime ABI, so the gate holds the
/// whole pair identical.
///
/// # Errors
/// Returns an error when either manifest is unparseable or a required pin
/// field is missing or not a string — a manifest that cannot prove the pin is
/// a failure, not an empty violation list.
pub fn boltffi_pin_violations(
    tools_source: &str,
    workspace_manifest: &str,
) -> Result<Vec<Violation>, String> {
    let cli = boltffi_cli_pin(tools_source)?;
    let runtime = boltffi_core_pin(workspace_manifest)?;
    let mut violations = Vec::new();
    if cli.git != runtime.git {
        violations.push(Violation::new(
            "boltffi-pin-parity",
            "tools.toml × Cargo.toml",
            format!(
                "ffi.boltffi_cli.git \"{}\" and workspace boltffi_core git \"{}\" \
                 must stay identical",
                cli.git, runtime.git
            ),
        ));
    }
    if cli.rev != runtime.rev {
        violations.push(Violation::new(
            "boltffi-pin-parity",
            "tools.toml × Cargo.toml",
            format!(
                "ffi.boltffi_cli.rev \"{}\" and workspace boltffi_core rev \"{}\" \
                 must stay identical — a one-sided bump splits the CLI from the \
                 runtime ABI",
                cli.rev, runtime.rev
            ),
        ));
    }
    Ok(violations)
}

fn boltffi_cli_pin(tools_source: &str) -> Result<Pin, String> {
    let tools = tools_source
        .parse::<toml::Table>()
        .map_err(|error| format!("tools.toml: {error}"))?;
    let section = tools
        .get("ffi")
        .and_then(toml::Value::as_table)
        .and_then(|ffi| ffi.get("boltffi_cli"))
        .and_then(toml::Value::as_table)
        .ok_or("tools.toml: [ffi.boltffi_cli] pin table missing")?;
    Ok(Pin {
        git: required_string(section, "git", "tools.toml [ffi.boltffi_cli]")?,
        rev: required_string(section, "rev", "tools.toml [ffi.boltffi_cli]")?,
    })
}

fn boltffi_core_pin(workspace_manifest: &str) -> Result<Pin, String> {
    let manifest = workspace_manifest
        .parse::<toml::Table>()
        .map_err(|error| format!("Cargo.toml: {error}"))?;
    let dependency = manifest
        .get("workspace")
        .and_then(toml::Value::as_table)
        .and_then(|workspace| workspace.get("dependencies"))
        .and_then(toml::Value::as_table)
        .and_then(|dependencies| dependencies.get("boltffi_core"))
        .and_then(toml::Value::as_table)
        .ok_or("Cargo.toml: workspace.dependencies.boltffi_core must be a git+rev table")?;
    Ok(Pin {
        git: required_string(dependency, "git", "workspace.dependencies.boltffi_core")?,
        rev: required_string(dependency, "rev", "workspace.dependencies.boltffi_core")?,
    })
}

fn required_string(table: &toml::Table, key: &str, owner: &str) -> Result<String, String> {
    table
        .get(key)
        .and_then(toml::Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| format!("{owner}.{key} must be a string"))
}
