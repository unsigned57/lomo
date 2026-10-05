use yaml_rust2::Yaml;

use super::Capability::{
    Database, DependencyInjection, Ffi, Filesystem, Network, Platform, Portable, Ui,
};
use super::{Capability, DependencyCapabilities, KOTLIN_MODULES, Violation, yaml_document};

#[derive(Debug, Eq, PartialEq)]
pub struct ModuleDependency {
    pub name: String,
    pub scope: String,
    pub section: String,
}

pub fn internal_module_dependencies(source: &str) -> Result<Vec<ModuleDependency>, String> {
    Ok(dependencies(source)?
        .into_iter()
        .filter_map(|mut dependency| {
            let name = dependency.name.strip_prefix("//")?.to_owned();
            dependency.name = name;
            Some(dependency)
        })
        .collect())
}

fn dependencies(source: &str) -> Result<Vec<ModuleDependency>, String> {
    let document = yaml_document(source)?;
    let root = document.as_hash().ok_or("module root is not a mapping")?;
    let mut dependencies = Vec::new();
    for (key, value) in root {
        let section = key.as_str().ok_or("module key must be a string")?;
        let base = section
            .split('@')
            .next()
            .ok_or("missing dependency section")?;
        if !matches!(base, "dependencies" | "test-dependencies") {
            continue;
        }
        let entries = value
            .as_vec()
            .ok_or_else(|| format!("{section} must be a dependency list"))?;
        for entry in entries {
            let (name, scope) = dependency_entry(entry)?;
            dependencies.push(ModuleDependency {
                name: name.to_owned(),
                scope: scope.to_owned(),
                section: section.to_owned(),
            });
        }
    }
    Ok(dependencies)
}

fn dependency_entry(entry: &Yaml) -> Result<(&str, &str), String> {
    if let Some(name) = entry.as_str() {
        return Ok((name, "compile"));
    }
    let mapping = entry
        .as_hash()
        .ok_or("dependency must be a coordinate or scoped coordinate")?;
    if mapping.len() != 1 {
        return Err("a scoped dependency must contain exactly one coordinate".to_owned());
    }
    let (key, value) = mapping.iter().next().ok_or("empty dependency mapping")?;
    let name = key
        .as_str()
        .ok_or("dependency coordinate must be a string")?;
    let scope = value.as_str().ok_or("dependency scope must be a string")?;
    if !matches!(scope, "compile-only" | "runtime-only" | "exported") {
        return Err(format!("unknown dependency scope {scope} for {name}"));
    }
    Ok((name, scope))
}

pub fn kotlin_dependency_violations(
    module: &str,
    source: &str,
    capabilities: &DependencyCapabilities,
) -> Result<Vec<Violation>, String> {
    if !KOTLIN_MODULES.contains(&module) {
        return Err(format!("unowned Kotlin module: {module}"));
    }
    let mut violations = Vec::new();
    for dependency in dependencies(source)? {
        let permitted = if let Some(name) = dependency.name.strip_prefix("//") {
            allowed_internal(module, name, &dependency.scope)
        } else {
            allowed_external(module, &dependency, capabilities)?
        };
        if !permitted {
            violations.push(Violation::new(
                "kotlin-owner-dependency",
                format!("{module}/module.yaml"),
                format!(
                    "{}: {} ({}) crosses the owning boundary",
                    dependency.section, dependency.name, dependency.scope
                ),
            ));
        }
    }
    Ok(violations)
}

fn allowed_internal(module: &str, dependency: &str, scope: &str) -> bool {
    match module {
        "apps/android/app" => {
            matches!(dependency, "domain" | "ui-components")
                || (dependency == "data" && scope == "runtime-only")
        }
        "apps/android/data" => {
            dependency == "domain"
                || (dependency == "native-bindings" && matches!(scope, "compile" | "compile-only"))
        }
        "apps/android/ui-components" => dependency == "domain",
        _ => false,
    }
}

fn allowed_external(
    module: &str,
    dependency: &ModuleDependency,
    capabilities: &DependencyCapabilities,
) -> Result<bool, String> {
    if dependency.section.starts_with("test-dependencies") {
        return Ok(true);
    }
    let mut parts = dependency.name.split(':');
    let (Some(group), Some(artifact)) = (parts.next(), parts.next()) else {
        return Err(format!("invalid Maven dependency: {}", dependency.name));
    };
    let identity = format!("{group}:{artifact}");
    let required = capabilities.maven(&identity).ok_or_else(|| {
        format!("classify {identity} in quality/dependency-capabilities.toml before selecting it")
    })?;
    let permitted: &[Capability] = match module {
        "apps/android/domain" => &[Portable],
        "apps/android/data" => &[Portable, Filesystem, Platform, Network, DependencyInjection],
        "apps/android/app" => &[
            Portable,
            Filesystem,
            Platform,
            Network,
            Ui,
            DependencyInjection,
        ],
        "apps/android/ui-components" => &[Portable, Platform, Network, Ui],
        "apps/android/native-bindings" => &[Portable, Ffi],
        "apps/android/quality/detekt-rules" => &[Portable, Filesystem, Platform, Database, Network],
        _ => return Err(format!("unowned Kotlin capability boundary: {module}")),
    };
    Ok(required
        .iter()
        .all(|capability| permitted.contains(capability)))
}
