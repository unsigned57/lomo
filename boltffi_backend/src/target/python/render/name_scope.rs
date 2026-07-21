use std::collections::HashMap;

use crate::core::{Error, Result};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct NameScope {
    label: String,
    names: HashMap<String, String>,
}

impl NameScope {
    pub fn new(label: impl Into<String>) -> Self {
        Self {
            label: label.into(),
            names: HashMap::new(),
        }
    }

    pub fn insert(mut self, name: impl Into<String>, subject: impl Into<String>) -> Result<Self> {
        let name = name.into();
        let subject = subject.into();
        if let Some(existing) = self.names.insert(name.clone(), subject.clone()) {
            return Err(Error::PythonNameCollision {
                scope: self.label,
                name,
                existing,
                colliding: subject,
            });
        }
        Ok(self)
    }

    pub fn insert_all<I>(self, names: I) -> Result<Self>
    where
        I: IntoIterator<Item = (String, String)>,
    {
        names
            .into_iter()
            .try_fold(self, |scope, (name, subject)| scope.insert(name, subject))
    }
}
