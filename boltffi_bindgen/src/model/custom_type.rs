use serde::{Deserialize, Serialize};

use super::types::Type;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomType {
    pub name: String,
    pub repr: Type,
}

impl CustomType {
    pub fn new(name: impl Into<String>, repr: Type) -> Self {
        Self {
            name: name.into(),
            repr,
        }
    }
}
