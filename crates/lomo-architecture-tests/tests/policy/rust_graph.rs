use std::path::Path;

use serde_json::Value;

use super::Violation;

struct Owner {
    name: &'static str,
    directory: &'static str,
    dependencies: &'static [&'static str],
}

// Capability admission is closed: a new crate/driver needs an explicit ownership decision.
// These are dependency boundaries, not a source-file inventory or minimum dependency list.
const OWNERS: &[Owner] = &[
    Owner {
        name: "lomo-core",
        directory: "crates/lomo-core",
        dependencies: &["serde", "serde_json", "sha2"],
    },
    Owner {
        name: "lomo-workspace",
        directory: "crates/lomo-workspace",
        dependencies: &["lomo-core", "pulldown-cmark", "serde", "serde_json", "sha2"],
    },
    Owner {
        name: "lomo-media",
        directory: "crates/lomo-media",
        dependencies: &["lomo-core", "lomo-workspace", "serde", "serde_json", "sha2"],
    },
    Owner {
        name: "lomo-store",
        directory: "crates/lomo-store",
        dependencies: &[
            "lomo-core",
            "lomo-workspace",
            "lomo-media",
            "rustix",
            "rusqlite",
            "serde",
            "serde_json",
            "sha2",
            "zip",
            "tempfile",
        ],
    },
    Owner {
        name: "lomo-application",
        directory: "crates/lomo-application",
        dependencies: &[
            "lomo-core",
            "lomo-workspace",
            "lomo-store",
            "lomo-media",
            "rustix",
            "jiff",
            "serde",
            "serde_json",
            "sha2",
            "pinyin",
        ],
    },
    Owner {
        name: "lomo-platform-fs",
        directory: "crates/lomo-platform-fs",
        dependencies: &["lomo-core", "rustix", "sha2"],
    },
    Owner {
        name: "lomo-sync",
        directory: "crates/lomo-sync",
        dependencies: &[
            "lomo-core",
            "lomo-workspace",
            "lomo-store",
            "aes",
            "serde",
            "serde_json",
            "sha2",
            "scrypt",
            "reqwest",
            "rustls",
            "url",
            "crypto_secretbox",
        ],
    },
    Owner {
        name: "lomo-git",
        directory: "crates/lomo-git",
        dependencies: &["lomo-core", "lomo-sync", "git2", "sha2"],
    },
    Owner {
        name: "lomo-lan",
        directory: "crates/lomo-lan",
        dependencies: &["lomo-core", "aws-lc-rs", "sha2"],
    },
    Owner {
        name: "lomo-native",
        directory: "crates/lomo-native",
        dependencies: &[
            "boltffi",
            "lomo-core",
            "lomo-workspace",
            "lomo-store",
            "lomo-application",
            "lomo-media",
            "lomo-sync",
            "lomo-git",
            "lomo-lan",
            "lomo-platform-fs",
            "serde_json",
        ],
    },
    Owner {
        name: "boltffi",
        directory: "crates/boltffi-facade",
        dependencies: &["boltffi_core"],
    },
    Owner {
        name: "lomo-feasibility",
        directory: "crates/lomo-feasibility",
        dependencies: &[
            "serde",
            "serde_json",
            "sha2",
            "thiserror",
            "rusqlite",
            "pulldown-cmark",
            "reqwest",
            "rustls",
            "rcgen",
            "git2",
        ],
    },
    Owner {
        name: "lomo-xtask",
        directory: "crates/lomo-xtask",
        dependencies: &[
            "anyhow",
            "lomo-feasibility",
            "serde",
            "serde_json",
            "sha2",
            "yaml-rust2",
        ],
    },
    Owner {
        name: "lomo-architecture-tests",
        directory: "crates/lomo-architecture-tests",
        dependencies: &[],
    },
    Owner {
        name: "lomo-tui",
        directory: "apps/tui",
        dependencies: &[
            "lomo-application",
            "lomo-platform-fs",
            "lomo-core",
            "lomo-workspace",
            "lomo-media",
            "serde",
            "serde_json",
            "sha2",
            "base64",
            "unicode-segmentation",
            "unicode-width",
            "crossterm",
            "ratatui",
            "toml",
            "arboard",
            "image",
        ],
    },
];

pub fn rust_dependency_violations(root: &Path, metadata: &Value) -> Result<Vec<Violation>, String> {
    let packages = metadata
        .get("packages")
        .and_then(Value::as_array)
        .ok_or("metadata packages missing")?;
    let members = metadata
        .get("workspace_members")
        .and_then(Value::as_array)
        .ok_or("metadata workspace_members missing")?;
    let mut violations = Vec::new();
    for member in members {
        let id = member
            .as_str()
            .ok_or("workspace member ID must be a string")?;
        let package = packages
            .iter()
            .find(|package| package.get("id").and_then(Value::as_str) == Some(id))
            .ok_or_else(|| format!("workspace member {id} has no package metadata"))?;
        check_package(root, package, &mut violations)?;
    }
    if members.is_empty() {
        return Err("workspace member inventory is empty".to_owned());
    }
    Ok(violations)
}

fn check_package(
    root: &Path,
    package: &Value,
    violations: &mut Vec<Violation>,
) -> Result<(), String> {
    let name = string_field(package, "name")?;
    let manifest = string_field(package, "manifest_path")?;
    let Some(owner) = OWNERS.iter().find(|owner| owner.name == name) else {
        violations.push(Violation::new("rust-unowned-package", manifest, name));
        return Ok(());
    };
    if Path::new(manifest) != root.join(owner.directory).join("Cargo.toml") {
        violations.push(Violation::new(
            "rust-owner-path",
            manifest,
            format!("{name} belongs to {}", owner.directory),
        ));
    }
    let dependencies = package
        .get("dependencies")
        .and_then(Value::as_array)
        .ok_or("package dependencies missing")?;
    for dependency in dependencies {
        check_dependency(root, owner, manifest, dependency, violations)?;
    }
    Ok(())
}

fn check_dependency(
    root: &Path,
    owner: &Owner,
    manifest: &str,
    dependency: &Value,
    violations: &mut Vec<Violation>,
) -> Result<(), String> {
    // Cargo's name is the actual package, even if Cargo.toml renamed it to an innocent alias.
    let name = string_field(dependency, "name")?;
    let kind = dependency.get("kind").ok_or("dependency kind missing")?;
    let is_development = match kind {
        Value::Null => false,
        Value::String(kind) if kind == "build" => false,
        Value::String(kind) if kind == "dev" => true,
        Value::Bool(_)
        | Value::Number(_)
        | Value::String(_)
        | Value::Array(_)
        | Value::Object(_) => return Err(format!("unknown dependency kind: {kind}")),
    };
    if !is_development && !owner.dependencies.contains(&name) {
        violations.push(Violation::new("rust-owner-dependency", manifest,
            format!("{} -> {name} (kind={kind}, target={:?}, optional={:?}) is not an admitted capability", owner.name, dependency.get("target"), dependency.get("optional"))));
    }
    if let Some(target) = OWNERS.iter().find(|candidate| candidate.name == name) {
        let path = dependency.get("path").and_then(Value::as_str);
        if path.map(Path::new) != Some(root.join(target.directory).as_path()) {
            violations.push(Violation::new(
                "rust-owner-path",
                manifest,
                format!("{name} must resolve to {}", target.directory),
            ));
        }
    } else if dependency.get("path").is_some_and(|path| !path.is_null()) {
        violations.push(Violation::new(
            "rust-unowned-path-dependency",
            manifest,
            name,
        ));
    }
    Ok(())
}

fn string_field<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    value
        .get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("metadata field {name} must be a string"))
}

pub fn rust_manifest_violations(path: &str, source: &str) -> Result<Vec<Violation>, String> {
    let manifest = source
        .parse::<toml::Table>()
        .map_err(|error| format!("{path}: {error}"))?;
    let inherited = manifest.get("lints").and_then(toml::Value::as_table);
    if inherited.is_some_and(|lints| {
        lints.len() == 1 && lints.get("workspace").and_then(toml::Value::as_bool) == Some(true)
    }) {
        Ok(Vec::new())
    } else {
        Ok(vec![Violation::new(
            "rust-workspace-lints",
            path,
            "every package must inherit workspace lints without local overrides",
        )])
    }
}
