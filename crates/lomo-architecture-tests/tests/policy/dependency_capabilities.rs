use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum Capability {
    Portable,
    Filesystem,
    Database,
    Network,
    Ui,
    Platform,
    Ffi,
    DependencyInjection,
}

impl Capability {
    fn parse(value: &str) -> Result<Self, String> {
        match value {
            "portable" => Ok(Self::Portable),
            "filesystem" => Ok(Self::Filesystem),
            "database" => Ok(Self::Database),
            "network" => Ok(Self::Network),
            "ui" => Ok(Self::Ui),
            "platform" => Ok(Self::Platform),
            "ffi" => Ok(Self::Ffi),
            "dependency-injection" => Ok(Self::DependencyInjection),
            _ => Err(format!("unknown dependency capability: {value}")),
        }
    }
}

#[derive(Debug)]
pub struct DependencyCapabilities {
    cargo: BTreeMap<String, BTreeSet<Capability>>,
    maven: BTreeMap<String, BTreeSet<Capability>>,
}

impl DependencyCapabilities {
    pub fn load(root: &Path) -> Result<Self, String> {
        let path = root.join("quality/dependency-capabilities.toml");
        let source =
            fs::read_to_string(&path).map_err(|error| format!("{}: {error}", path.display()))?;
        Self::parse(&source)
    }

    pub fn parse(source: &str) -> Result<Self, String> {
        let table = source
            .parse::<toml::Table>()
            .map_err(|error| format!("invalid dependency capability policy: {error}"))?;
        if table
            .get("schema_version")
            .and_then(toml::Value::as_integer)
            != Some(1)
        {
            return Err("dependency capability schema_version must be 1".to_owned());
        }
        for key in table.keys() {
            if !matches!(key.as_str(), "schema_version" | "cargo" | "maven") {
                return Err(format!("unknown dependency capability section: {key}"));
            }
        }
        Ok(Self {
            cargo: parse_section(&table, "cargo")?,
            maven: parse_section(&table, "maven")?,
        })
    }

    pub(super) fn cargo(&self, package: &str) -> Option<&BTreeSet<Capability>> {
        self.cargo.get(package)
    }

    pub(super) fn maven(&self, coordinate: &str) -> Option<&BTreeSet<Capability>> {
        self.maven.get(coordinate)
    }
}

fn parse_section(
    table: &toml::Table,
    ecosystem: &str,
) -> Result<BTreeMap<String, BTreeSet<Capability>>, String> {
    let entries = table
        .get(ecosystem)
        .and_then(toml::Value::as_table)
        .ok_or_else(|| format!("missing {ecosystem} capability table"))?;
    entries
        .iter()
        .map(|(name, value)| {
            if !valid_identity(ecosystem, name) {
                return Err(format!("invalid {ecosystem} dependency identity: {name}"));
            }
            let values = value
                .as_array()
                .ok_or_else(|| format!("{ecosystem}.{name} capabilities must be an array"))?;
            if values.is_empty() {
                return Err(format!(
                    "{ecosystem}.{name} needs a non-empty capability set"
                ));
            }
            let mut capabilities = BTreeSet::new();
            for value in values {
                let capability = Capability::parse(
                    value
                        .as_str()
                        .ok_or("dependency capabilities must be strings")?,
                )?;
                if !capabilities.insert(capability) {
                    return Err(format!("duplicate capability for {ecosystem}.{name}"));
                }
            }
            Ok((name.clone(), capabilities))
        })
        .collect()
}

fn valid_identity(ecosystem: &str, name: &str) -> bool {
    let component = |part: &str| {
        !part.is_empty()
            && part
                .chars()
                .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.'))
    };
    match ecosystem {
        "cargo" => {
            !name.is_empty()
                && name
                    .chars()
                    .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_'))
        }
        "maven" => name
            .split_once(':')
            .is_some_and(|(group, artifact)| component(group) && component(artifact)),
        _ => false,
    }
}
