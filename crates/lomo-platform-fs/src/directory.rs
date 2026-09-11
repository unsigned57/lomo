//! Bounded ASCII cursors refer to a complete directory name snapshot, never raw filenames.

use lomo_core::LomoError;
use sha2::{Digest, Sha256};

use crate::error::{conflict, validation};

pub struct DirectoryListing {
    names: Vec<String>,
    fingerprint: String,
}

impl DirectoryListing {
    pub fn new(names: Vec<String>, capability: &str, path: &str) -> Self {
        let mut hash = Sha256::new();
        for component in std::iter::once(capability)
            .chain(std::iter::once(path))
            .chain(names.iter().map(String::as_str))
        {
            hash.update(component.as_bytes());
            hash.update([0]);
        }
        Self {
            names,
            fingerprint: format!("{:x}", hash.finalize()),
        }
    }

    pub fn start(&self, cursor: Option<&str>) -> Result<usize, LomoError> {
        let Some(cursor) = cursor else {
            return Ok(0);
        };
        let (fingerprint, offset) = cursor
            .strip_prefix("d1.")
            .and_then(|raw| raw.rsplit_once('.'))
            .ok_or_else(invalid_cursor)?;
        let offset = offset.parse::<usize>().map_err(|_error| invalid_cursor())?;
        if fingerprint != self.fingerprint {
            return Err(conflict(
                "stale_directory_cursor",
                "directory entries changed during pagination; restart enumeration",
            ));
        }
        if offset > self.names.len() {
            return Err(invalid_cursor());
        }
        Ok(offset)
    }

    pub fn names(&self, start: usize, limit: usize) -> impl Iterator<Item = &str> {
        self.names
            .iter()
            .skip(start)
            .take(limit)
            .map(String::as_str)
    }

    pub fn cursor_after(&self, offset: usize) -> Option<String> {
        (offset < self.names.len()).then(|| format!("d1.{}.{offset}", self.fingerprint))
    }
}

fn invalid_cursor() -> LomoError {
    validation(
        "invalid_directory_cursor",
        "directory cursor must contain a snapshot fingerprint and bounded offset",
    )
}
