use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PythonConfig {
    #[serde(default = "default_python_output")]
    pub output: PathBuf,
    pub module_name: Option<String>,
    #[serde(default, alias = "pack")]
    pub wheel: PythonWheelConfig,
    #[serde(default)]
    pub enabled: bool,
}

impl Default for PythonConfig {
    fn default() -> Self {
        Self {
            output: default_python_output(),
            module_name: None,
            wheel: PythonWheelConfig::default(),
            enabled: false,
        }
    }
}
#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct PythonWheelConfig {
    #[serde(alias = "wheel_output")]
    pub output: Option<PathBuf>,
    pub interpreters: Option<Vec<String>>,
}

fn default_python_output() -> PathBuf {
    PathBuf::from("dist/python")
}
