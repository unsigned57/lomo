use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::Path,
};

use sha2::{Digest as _, Sha256};
use syn::{Item, Visibility};

use super::Violation;

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct Export {
    pub rust: String,
    pub kotlin: String,
    pub callback: bool,
}

pub fn exports(source: &str) -> Result<BTreeSet<Export>, String> {
    let file = syn::parse_file(source).map_err(|error| error.to_string())?;
    let mut exports = BTreeSet::new();
    collect_items(&file.items, &mut exports);
    Ok(exports)
}

fn exported(attributes: &[syn::Attribute]) -> bool {
    attributes.iter().any(|attribute| {
        attribute
            .path()
            .segments
            .last()
            .is_some_and(|segment| segment.ident == "export")
    })
}

fn collect_items(items: &[Item], exports: &mut BTreeSet<Export>) {
    for item in items {
        match item {
            Item::Fn(function) if exported(&function.attrs) => {
                let name = function.sig.ident.to_string();
                exports.insert(Export {
                    rust: name.clone(),
                    kotlin: format!("com.lomo.nativebridge.{}", camel(&name)),
                    callback: false,
                });
            }
            Item::Impl(implementation) => collect_impl(implementation, exports),
            Item::Trait(callback) if exported(&callback.attrs) => {
                for member in &callback.items {
                    if let syn::TraitItem::Fn(function) = member {
                        let owner = callback.ident.to_string();
                        let name = function.sig.ident.to_string();
                        exports.insert(Export {
                            rust: format!("{owner}::{name}"),
                            kotlin: format!("com.lomo.nativebridge.{owner}.{}", camel(&name)),
                            callback: true,
                        });
                    }
                }
            }
            Item::Mod(module) => {
                if let Some((_, items)) = &module.content {
                    collect_items(items, exports);
                }
            }
            Item::Const(_)
            | Item::Enum(_)
            | Item::ExternCrate(_)
            | Item::Fn(_)
            | Item::ForeignMod(_)
            | Item::Macro(_)
            | Item::Static(_)
            | Item::Struct(_)
            | Item::Trait(_)
            | Item::TraitAlias(_)
            | Item::Type(_)
            | Item::Union(_)
            | Item::Use(_)
            | Item::Verbatim(_)
            | _ => {}
        }
    }
}

fn collect_impl(implementation: &syn::ItemImpl, exports: &mut BTreeSet<Export>) {
    let syn::Type::Path(owner) = implementation.self_ty.as_ref() else {
        return;
    };
    let Some(owner) = owner.path.segments.last() else {
        return;
    };
    for member in &implementation.items {
        let syn::ImplItem::Fn(function) = member else {
            continue;
        };
        if !matches!(function.vis, Visibility::Public(_))
            || !(exported(&implementation.attrs) || exported(&function.attrs))
        {
            continue;
        }
        let name = function.sig.ident.to_string();
        let static_part = if function.sig.receiver().is_none() {
            ".Companion"
        } else {
            ""
        };
        exports.insert(Export {
            rust: format!("{}::{name}", owner.ident),
            kotlin: format!(
                "com.lomo.nativebridge.{}{static_part}.{}",
                owner.ident,
                camel(&name)
            ),
            callback: false,
        });
    }
}

fn camel(name: &str) -> String {
    let mut upper = false;
    let mut result = String::new();
    for character in name.trim_start_matches("r#").chars() {
        if character == '_' {
            upper = true;
        } else if upper {
            result.extend(character.to_uppercase());
            upper = false;
        } else {
            result.push(character);
        }
    }
    result
}

#[derive(Default)]
pub struct ResolvedGraph {
    pub declarations: BTreeSet<String>,
    pub roots: BTreeSet<String>,
    pub edges: BTreeSet<(String, String)>,
}

/// Production `LomoApplication.onCreate` reflectively installs data Koin modules.
pub const APP_RUNTIME_DATA_KOIN_INSTALLER: &str = "com.lomo.app.LomoApplication.onCreate";

/// Kotlin getter for `val dataModules` in `com.lomo.data.di.DataModules`.
pub const APP_RUNTIME_DATA_KOIN_INSTALLED: &str = "com.lomo.data.di.DataModulesKt.getDataModules";

/// One module-level contract edge for the app→data reflection install (not a symbol allowlist).
pub fn install_app_data_koin_runtime_edge(graph: &mut ResolvedGraph) {
    graph.edges.insert((
        APP_RUNTIME_DATA_KOIN_INSTALLER.to_owned(),
        APP_RUNTIME_DATA_KOIN_INSTALLED.to_owned(),
    ));
}

pub fn contract_violations(
    exports: &BTreeSet<Export>,
    generated: &BTreeSet<String>,
    graph: &ResolvedGraph,
) -> Vec<Violation> {
    let mut reachable = graph.roots.clone();
    let mut queue: Vec<_> = reachable.iter().cloned().collect();
    let mut calls: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (from, to) in &graph.edges {
        calls.entry(from).or_default().push(to);
    }
    while let Some(from) = queue.pop() {
        if let Some(targets) = calls.get(from.as_str()) {
            for target in targets {
                if reachable.insert((*target).to_owned()) {
                    queue.push((*target).to_owned());
                }
            }
        }
    }
    let mut violations = Vec::new();
    for export in exports {
        if !generated.contains(&export.kotlin) {
            violations.push(Violation::new(
                "ffi-generated-declaration",
                &export.rust,
                format!("missing generated symbol {}", export.kotlin),
            ));
        }
        let consumed = if export.callback {
            graph.edges.iter().any(|(base, implementation)| {
                base == &export.kotlin && reachable.contains(implementation)
            })
        } else {
            reachable.contains(&export.kotlin)
        };
        if !consumed {
            violations.push(Violation::new(
                "ffi-production-consumer",
                &export.rust,
                format!(
                    "{} has no reachable consuming adapter or callback implementation",
                    export.kotlin
                ),
            ));
        }
    }
    violations
}

pub fn workspace_exports(root: &Path) -> Result<BTreeSet<Export>, String> {
    let mut surface = BTreeSet::new();
    for path in super::source_files(root, "crates/lomo-native/src")? {
        if path.extension().is_none_or(|extension| extension != "rs") {
            continue;
        }
        for export in exports(&fs::read_to_string(&path).map_err(|error| error.to_string())?)? {
            if !surface.insert(export.clone()) {
                return Err(format!("duplicate foreign export {}", export.rust));
            }
        }
    }
    if surface.is_empty() {
        return Err("native facade contains no explicit exports".to_owned());
    }
    Ok(surface)
}

pub fn load_graph(
    root: &Path,
    facts_root: &Path,
) -> Result<(BTreeSet<String>, ResolvedGraph), String> {
    let mut graph = ResolvedGraph::default();
    let mut generated = None;
    for module in ["app", "data", "domain", "ui-components"] {
        let index = read_json(&facts_root.join(format!("index-{module}.json")))?;
        let directory = Path::new(string(&index, "directory")?);
        let mut observed = BTreeSet::new();
        for entry in fs::read_dir(directory).map_err(|error| error.to_string())? {
            let path = entry.map_err(|error| error.to_string())?.path();
            if path.extension().is_none_or(|extension| extension != "json") {
                continue;
            }
            let facts = read_json(&path)?;
            if facts
                .get("schema_version")
                .and_then(serde_json::Value::as_u64)
                != Some(1)
            {
                return Err("unsupported resolved graph schema".to_owned());
            }
            let source = Path::new(string(&facts, "path")?);
            let bytes = fs::read(source).map_err(|error| error.to_string())?;
            if format!("{:x}", Sha256::digest(bytes)) != string(&facts, "source_digest")? {
                return Err(format!(
                    "stale resolved symbol facts for {}",
                    source.display()
                ));
            }
            let declarations = strings(&facts, "declarations")?;
            if path
                .file_name()
                .is_some_and(|name| name == "generated.json")
            {
                if let Some(previous) = &generated
                    && previous != &declarations
                {
                    return Err("generated declarations disagree across modules".to_owned());
                }
                generated = Some(declarations);
                continue;
            }
            if !source.starts_with(root.join(format!("apps/android/{module}/src"))) {
                return Err(format!("non-production symbol input {}", source.display()));
            }
            observed.insert(source.to_owned());
            graph.declarations.extend(declarations);
            graph.roots.extend(strings(&facts, "roots")?);
            for edge in facts
                .get("edges")
                .and_then(serde_json::Value::as_array)
                .ok_or("resolved call edges missing")?
            {
                let values = edge.as_array().ok_or("call edge must be an array")?;
                let [from, to] = values.as_slice() else {
                    return Err("call edge requires two symbols".to_owned());
                };
                graph.edges.insert((
                    from.as_str().ok_or("caller identity")?.to_owned(),
                    to.as_str().ok_or("callee identity")?.to_owned(),
                ));
            }
        }
        let expected: BTreeSet<_> =
            super::source_files(root, &format!("apps/android/{module}/src"))?
                .into_iter()
                .filter(|path| path.extension().is_some_and(|extension| extension == "kt"))
                .collect();
        if observed != expected {
            return Err(format!(
                "incomplete resolved call graph for {module}: missing {:?}, unexpected {:?}",
                expected.difference(&observed).collect::<Vec<_>>(),
                observed.difference(&expected).collect::<Vec<_>>()
            ));
        }
    }
    install_app_data_koin_runtime_edge(&mut graph);
    if !graph.roots.is_subset(&graph.declarations) {
        return Err("resolved graph root lacks a production declaration".to_owned());
    }
    Ok((
        generated.ok_or("generated Kotlin declaration catalogue missing")?,
        graph,
    ))
}

fn read_json(path: &Path) -> Result<serde_json::Value, String> {
    serde_json::from_slice(&fs::read(path).map_err(|error| format!("{}: {error}", path.display()))?)
        .map_err(|error| error.to_string())
}

fn string<'a>(value: &'a serde_json::Value, key: &str) -> Result<&'a str, String> {
    value
        .get(key)
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| format!("missing string {key}"))
}

fn strings(value: &serde_json::Value, key: &str) -> Result<BTreeSet<String>, String> {
    value
        .get(key)
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| format!("missing list {key}"))?
        .iter()
        .map(|value| {
            value
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| format!("invalid symbol in {key}"))
        })
        .collect()
}
