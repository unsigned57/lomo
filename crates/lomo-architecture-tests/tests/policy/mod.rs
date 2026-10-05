//! Executable ownership policies. Inputs are parsed facts; malformed or unowned input is an error.

mod authority;
mod config;
mod dependency_capabilities;
pub mod ffi;
mod inventory;
mod kotlin;
mod rust_graph;
mod rust_invariants;
mod rust_source;

pub use authority::kotlin_authority_violations;
pub use config::detekt_config_violations;
pub use dependency_capabilities::{Capability, DependencyCapabilities};
pub use inventory::{owned_kotlin_module, source_files};
pub use kotlin::{internal_module_dependencies, kotlin_dependency_violations};
pub use rust_graph::{rust_dependency_violations, rust_manifest_violations};
pub use rust_invariants::rust_invariant_violations;
pub use rust_source::rust_source_violations;

#[derive(Debug, Eq, PartialEq)]
pub struct Violation {
    pub rule: &'static str,
    pub subject: String,
    pub detail: String,
}

impl Violation {
    fn new(rule: &'static str, subject: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            rule,
            subject: subject.into(),
            detail: detail.into(),
        }
    }
}

pub const KOTLIN_MODULES: &[&str] = &[
    "apps/android/app",
    "apps/android/data",
    "apps/android/domain",
    "apps/android/ui-components",
    "apps/android/native-bindings",
    "apps/android/quality/detekt-rules",
];

fn yaml_document(source: &str) -> Result<yaml_rust2::Yaml, String> {
    let mut documents = yaml_rust2::YamlLoader::load_from_str(source)
        .map_err(|error| format!("invalid YAML: {error}"))?;
    if documents.len() != 1 {
        return Err("expected exactly one YAML document".to_owned());
    }
    let document = documents
        .pop()
        .ok_or_else(|| "YAML document is missing".to_owned())?;
    reject_yaml_merges(&document)?;
    if document.as_hash().is_none() {
        return Err("YAML root must be a mapping".to_owned());
    }
    Ok(document)
}

fn reject_yaml_merges(value: &yaml_rust2::Yaml) -> Result<(), String> {
    match value {
        yaml_rust2::Yaml::Hash(entries) => {
            for (key, value) in entries {
                if key.as_str() == Some("<<") {
                    return Err(
                        "YAML merge keys cannot hide ownership; declare the mapping explicitly"
                            .to_owned(),
                    );
                }
                reject_yaml_merges(value)?;
            }
        }
        yaml_rust2::Yaml::Array(values) => {
            for value in values {
                reject_yaml_merges(value)?;
            }
        }
        yaml_rust2::Yaml::Alias(_) | yaml_rust2::Yaml::BadValue => {
            return Err("YAML contains unresolved input".to_owned());
        }
        yaml_rust2::Yaml::Real(_)
        | yaml_rust2::Yaml::Integer(_)
        | yaml_rust2::Yaml::String(_)
        | yaml_rust2::Yaml::Boolean(_)
        | yaml_rust2::Yaml::Null => {}
    }
    Ok(())
}
