use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use yaml_rust2::{Yaml, YamlLoader};

use super::{ChangeInventory, TaskAction};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum OwnerKind {
    Rust,
    Kotlin,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Owner {
    pub name: String,
    pub path: PathBuf,
    pub kind: OwnerKind,
    pub dependencies: BTreeSet<String>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ImpactGraph {
    pub owners: BTreeMap<String, Owner>,
}

impl ImpactGraph {
    /// Loads declared Cargo edges (including optional/target edges) and Amper module scopes.
    ///
    /// # Errors
    /// Missing, malformed or unowned modules and unresolved project edges fail closed.
    pub fn load(root: &Path) -> Result<Self> {
        let output = Command::new("cargo")
            .current_dir(root)
            .args(["metadata", "--no-deps", "--format-version", "1", "--locked"])
            .output()?;
        ensure!(
            output.status.success(),
            "Cargo metadata failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        let metadata: serde_json::Value = serde_json::from_slice(&output.stdout)?;
        let packages = metadata
            .get("packages")
            .and_then(serde_json::Value::as_array)
            .context("Cargo packages missing")?;
        let names: BTreeSet<_> = packages
            .iter()
            .map(|package| package["name"].as_str().context("package name"))
            .collect::<Result<_>>()?;
        let mut owners = BTreeMap::new();
        for package in packages {
            let name = package["name"].as_str().context("package name")?.to_owned();
            let manifest = Path::new(package["manifest_path"].as_str().context("manifest path")?);
            let path = manifest
                .parent()
                .context("manifest parent")?
                .strip_prefix(root)?
                .to_owned();
            let dependencies = package["dependencies"]
                .as_array()
                .context("declared dependencies")?
                .iter()
                .filter_map(|dependency| dependency["name"].as_str())
                .filter(|name| names.contains(name))
                .map(str::to_owned)
                .collect();
            owners.insert(
                name.clone(),
                Owner {
                    name,
                    path,
                    kind: OwnerKind::Rust,
                    dependencies,
                },
            );
        }
        let project = yaml(&root.join("apps/android/project.yaml"))?;
        let modules = project["modules"]
            .as_vec()
            .context("Amper modules must be a list")?;
        for module in modules {
            let relative = module
                .as_str()
                .context("Amper module path must be a string")?;
            let path = Path::new("apps/android").join(relative);
            ensure!(
                !Path::new(relative).is_absolute()
                    && !Path::new(relative)
                        .components()
                        .any(|part| part == std::path::Component::ParentDir),
                "Amper module escapes its project"
            );
            let name = path
                .file_name()
                .and_then(|part| part.to_str())
                .context("module name")?
                .to_owned();
            let manifest = yaml(&root.join(&path).join("module.yaml"))?;
            let dependencies = kotlin_dependencies(&manifest)?;
            ensure!(
                owners
                    .insert(
                        name.clone(),
                        Owner {
                            name,
                            path,
                            kind: OwnerKind::Kotlin,
                            dependencies
                        }
                    )
                    .is_none(),
                "duplicate owner name"
            );
        }
        for owner in owners.values() {
            for dependency in &owner.dependencies {
                ensure!(
                    owners.contains_key(dependency),
                    "{} depends on unknown owner {dependency}",
                    owner.name
                );
            }
        }
        Ok(Self { owners })
    }

    /// Resolves source, removed files, golden vectors and configuration to verification owners.
    ///
    /// # Errors
    /// A new module/manifest outside the loaded ownership graph is rejected explicitly. A removed
    /// one is not new: a rename reports both of its endpoints, and only the surviving path can
    /// introduce an unowned module.
    pub fn affected(&self, changes: &ChangeInventory) -> Result<BTreeSet<String>> {
        if changes.complete {
            return Ok(self.owners.keys().cloned().collect());
        }
        let mut affected = BTreeSet::new();
        for path in &changes.paths {
            if path.starts_with("fixtures") {
                affected.extend(
                    self.owners
                        .values()
                        .filter(|owner| owner.kind == OwnerKind::Rust)
                        .map(|owner| owner.name.clone()),
                );
                continue;
            }
            if let Some(owner) = self
                .owners
                .values()
                .find(|owner| path.starts_with(&owner.path))
            {
                affected.insert(owner.name.clone());
                let relative = path.strip_prefix(&owner.path)?;
                let api_change = relative.file_name().is_some_and(|name| {
                    name == "module.yaml"
                        || name == "Cargo.toml"
                        || name == "lib.rs"
                        || name == "mod.rs"
                }) || (owner.kind == OwnerKind::Kotlin
                    && owner.name == "domain"
                    && relative.starts_with("src"));
                if api_change {
                    self.reverse_closure(&mut affected);
                }
                if Self::binding_input(path) {
                    affected.extend(["native-bindings".to_owned(), "data".to_owned()]);
                    self.reverse_closure(&mut affected);
                }
                continue;
            }
            if path
                .file_name()
                .is_some_and(|name| name == "module.yaml" || name == "Cargo.toml")
                && path.components().count() > 1
                && !changes.removed.contains(path)
            {
                bail!("unowned executable manifest: {}", path.display());
            }
            if path
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
                || path == Path::new("LICENSE")
            {
                continue;
            }
            // Unknown executable/configuration paths conservatively select all owners. There is
            // no catch-all docs category for fixtures, source deletions or new build input.
            affected.extend(self.owners.keys().cloned());
        }
        Ok(affected)
    }

    fn reverse_closure(&self, affected: &mut BTreeSet<String>) {
        loop {
            let previous = affected.len();
            for owner in self.owners.values() {
                if !owner.dependencies.is_disjoint(affected) {
                    affected.insert(owner.name.clone());
                }
            }
            if affected.len() == previous {
                break;
            }
        }
    }

    pub(super) fn binding_input(path: &Path) -> bool {
        path.starts_with("crates/lomo-native/src")
            || path.starts_with("crates/boltffi-facade")
            || path.starts_with("apps/android/native-bindings")
            || path == Path::new("crates/lomo-xtask/src/native.rs")
            || path == Path::new("tools.toml")
            || path == Path::new("crates/lomo-native/boltffi.toml")
    }

    pub(super) fn task_inputs(&self, action: &TaskAction) -> BTreeSet<PathBuf> {
        let owners: BTreeSet<String> = match action {
            TaskAction::RustClippy { package } | TaskAction::RustTests { package } => {
                BTreeSet::from([package.clone()])
            }
            TaskAction::KotlinTests { module } => BTreeSet::from([module.clone()]),
            TaskAction::KotlinRules => BTreeSet::from(["detekt-rules".to_owned()]),
            TaskAction::BaselineProfile => BTreeSet::from(["app".to_owned()]),
            TaskAction::KotlinLight { modules }
            | TaskAction::KotlinFull { modules }
            | TaskAction::AnalysisInput { modules } => modules.clone(),
            TaskAction::Architecture
            | TaskAction::FfiContract
            | TaskAction::RustFmt
            | TaskAction::RustDocs
            | TaskAction::Machete
            | TaskAction::Bindings
            | TaskAction::AndroidLint
            | TaskAction::ShellContracts => self.owners.keys().cloned().collect(),
        };
        let mut closure = owners;
        loop {
            let previous = closure.len();
            for name in closure.clone() {
                if let Some(owner) = self.owners.get(&name) {
                    closure.extend(owner.dependencies.iter().cloned());
                }
            }
            if closure.len() == previous {
                break;
            }
        }
        let mut inputs: BTreeSet<_> = closure
            .iter()
            .filter_map(|name| self.owners.get(name).map(|owner| owner.path.clone()))
            .collect();
        inputs.extend(
            [
                "Cargo.toml",
                "Cargo.lock",
                "rust-toolchain.toml",
                "tools.toml",
                "kotlin",
                "apps/android/kotlin",
                "apps/android/project.yaml",
                "quality",
                "fixtures",
                "crates/lomo-xtask",
            ]
            .map(PathBuf::from),
        );
        inputs
    }
}

fn yaml(path: &Path) -> Result<Yaml> {
    let source = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut documents = YamlLoader::load_from_str(&source)?;
    ensure!(
        documents.len() == 1,
        "{} must contain one YAML document",
        path.display()
    );
    let document = documents.pop().context("missing YAML document")?;
    ensure!(
        document.as_hash().is_some(),
        "{} must be a mapping",
        path.display()
    );
    Ok(document)
}

fn kotlin_dependencies(manifest: &Yaml) -> Result<BTreeSet<String>> {
    let mut dependencies = BTreeSet::new();
    for (key, value) in manifest.as_hash().context("Amper module mapping")? {
        let key = key.as_str().context("Amper mapping key")?;
        if !["dependencies", "test-dependencies"]
            .iter()
            .any(|section| key == *section || key.starts_with(&format!("{section}@")))
        {
            continue;
        }
        for dependency in value.as_vec().context("Amper dependency list")? {
            let coordinate = dependency_coordinate(dependency)?;
            if let Some(target) = coordinate.strip_prefix("//") {
                dependencies.insert(target.to_owned());
            }
        }
    }
    Ok(dependencies)
}

fn dependency_coordinate(dependency: &Yaml) -> Result<&str> {
    if let Some(coordinate) = dependency.as_str() {
        return Ok(coordinate);
    }
    let mapping = dependency
        .as_hash()
        .context("unsupported dependency shape")?;
    ensure!(
        mapping.len() == 1,
        "dependency mapping must have one coordinate"
    );
    mapping
        .iter()
        .next()
        .context("dependency entry")?
        .0
        .as_str()
        .context("dependency coordinate")
}
