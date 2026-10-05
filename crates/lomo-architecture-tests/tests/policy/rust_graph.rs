use std::path::Path;

use serde_json::Value;

use super::{Capability, DependencyCapabilities, Violation};

struct Owner {
    name: &'static str,
    directory: &'static str,
    internal_dependencies: &'static [&'static str],
    capabilities: &'static [Capability],
}

// Internal authority edges and permitted capabilities are architectural facts.
// External library identity/version choices come from manifests and the shared capability policy.
const OWNERS: &[Owner] = &[
    Owner {
        name: "lomo-core",
        directory: "crates/lomo-core",
        internal_dependencies: &[],
        capabilities: &[Capability::Portable],
    },
    Owner {
        name: "lomo-workspace",
        directory: "crates/lomo-workspace",
        internal_dependencies: &["lomo-core"],
        capabilities: &[Capability::Portable],
    },
    Owner {
        name: "lomo-media",
        directory: "crates/lomo-media",
        internal_dependencies: &["lomo-core", "lomo-workspace"],
        capabilities: &[Capability::Portable],
    },
    Owner {
        name: "lomo-store",
        directory: "crates/lomo-store",
        internal_dependencies: &["lomo-core", "lomo-workspace", "lomo-media"],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Database,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-application",
        directory: "crates/lomo-application",
        internal_dependencies: &["lomo-core", "lomo-workspace", "lomo-store", "lomo-media"],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-platform-fs",
        directory: "crates/lomo-platform-fs",
        internal_dependencies: &["lomo-core"],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-sync",
        directory: "crates/lomo-sync",
        internal_dependencies: &["lomo-core", "lomo-workspace", "lomo-store"],
        capabilities: &[Capability::Portable, Capability::Network],
    },
    Owner {
        name: "lomo-git",
        directory: "crates/lomo-git",
        internal_dependencies: &["lomo-core", "lomo-sync"],
        capabilities: &[
            Capability::Portable,
            Capability::Network,
            Capability::Filesystem,
        ],
    },
    Owner {
        name: "lomo-lan",
        directory: "crates/lomo-lan",
        internal_dependencies: &["lomo-core"],
        capabilities: &[
            Capability::Portable,
            Capability::Network,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-native",
        directory: "crates/lomo-native",
        internal_dependencies: &[
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
        ],
        capabilities: &[Capability::Portable, Capability::Ffi],
    },
    Owner {
        name: "boltffi",
        directory: "crates/boltffi-facade",
        internal_dependencies: &[],
        capabilities: &[Capability::Portable, Capability::Ffi],
    },
    Owner {
        name: "lomo-feasibility",
        directory: "crates/lomo-feasibility",
        internal_dependencies: &[],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Database,
            Capability::Network,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-xtask",
        directory: "crates/lomo-xtask",
        internal_dependencies: &["lomo-feasibility"],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Network,
            Capability::Platform,
        ],
    },
    Owner {
        name: "lomo-architecture-tests",
        directory: "crates/lomo-architecture-tests",
        internal_dependencies: &[],
        capabilities: &[],
    },
    Owner {
        name: "lomo-tui",
        directory: "apps/tui",
        internal_dependencies: &[
            "lomo-application",
            "lomo-platform-fs",
            "lomo-core",
            "lomo-workspace",
            "lomo-media",
        ],
        capabilities: &[
            Capability::Portable,
            Capability::Filesystem,
            Capability::Ui,
            Capability::Platform,
        ],
    },
];

pub fn rust_dependency_violations(
    root: &Path,
    metadata: &Value,
    capabilities: &DependencyCapabilities,
) -> Result<Vec<Violation>, String> {
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
        check_package(root, package, capabilities, &mut violations)?;
    }
    if members.is_empty() {
        return Err("workspace member inventory is empty".to_owned());
    }
    Ok(violations)
}

fn check_package(
    root: &Path,
    package: &Value,
    capabilities: &DependencyCapabilities,
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
        check_dependency(root, owner, manifest, dependency, capabilities, violations)?;
    }
    Ok(())
}

fn check_dependency(
    root: &Path,
    owner: &Owner,
    manifest: &str,
    dependency: &Value,
    capabilities: &DependencyCapabilities,
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
    if !is_development {
        if OWNERS.iter().any(|candidate| candidate.name == name) {
            if !owner.internal_dependencies.contains(&name) {
                violations.push(Violation::new(
                    "rust-owner-dependency",
                    manifest,
                    format!(
                        "{} -> {name} crosses internal authority (kind={kind})",
                        owner.name
                    ),
                ));
            }
        } else {
            match capabilities.cargo(name) {
                None => violations.push(Violation::new(
                    "rust-unclassified-dependency",
                    manifest,
                    format!("classify {name} in quality/dependency-capabilities.toml before selecting it"),
                )),
                Some(required) if !required.iter().all(|capability| owner.capabilities.contains(capability)) => {
                    violations.push(Violation::new(
                        "rust-owner-dependency",
                        manifest,
                        format!("{} -> {name} requires {required:?}, outside its capability boundary (kind={kind})", owner.name),
                    ));
                }
                Some(_) => {}
            }
        }
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
