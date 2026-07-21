use std::collections::HashMap;

use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize, Serialize, Clone, Default)]
pub struct CargoConfig {
    #[serde(default)]
    pub global_args: Vec<String>,
    #[serde(default)]
    pub command_args: HashMap<String, Vec<String>>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PackageConfig {
    pub name: String,
    #[serde(rename = "crate")]
    pub crate_name: Option<String>,
    pub version: Option<String>,
    pub description: Option<String>,
    pub license: Option<String>,
    pub repository: Option<String>,
}
