use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize, Serialize, Clone, Default)]
pub struct HeaderConfig {
    pub output: Option<PathBuf>,
}
