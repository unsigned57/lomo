use std::fs;
use std::path::{Path, PathBuf};

use crate::cli::{CliError, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Directory {
    path: PathBuf,
}

impl Directory {
    pub fn for_target(target_name: impl AsRef<Path>) -> Result<Self> {
        Ok(Self {
            path: super::cargo_target_directory()?
                .join("boltffi")
                .join("pack")
                .join(target_name),
        })
    }

    pub fn join(&self, path: impl AsRef<Path>) -> PathBuf {
        self.path.join(path)
    }

    pub fn recreate(&self) -> Result<()> {
        self.remove()?;
        self.create()
    }

    fn create(&self) -> Result<()> {
        fs::create_dir_all(&self.path).map_err(|source| CliError::CreateDirectoryFailed {
            path: self.path.clone(),
            source,
        })
    }

    fn remove(&self) -> Result<()> {
        match fs::remove_dir_all(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(source) => Err(CliError::WriteFailed {
                path: self.path.clone(),
                source,
            }),
        }
    }
}
