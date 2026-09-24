use std::collections::BTreeMap;
use std::path::Path;
use std::sync::{Arc, RwLock};

use lomo_core::{CapabilityToken, LomoError};

use crate::error::{internal, permission, storage, validation};
use crate::sys::Root;

/// A bound root holding its pinned directory anchor.
#[derive(Clone, Debug)]
pub struct BoundRoot {
    root: Arc<Root>,
}

impl BoundRoot {
    /// The pinned root anchor all operations resolve beneath.
    #[must_use]
    pub(crate) fn root(&self) -> &Root {
        &self.root
    }
}

/// Registry mapping trusted `CapabilityToken`s to canonical filesystem directories.
#[derive(Debug, Default)]
pub struct RootRegistry {
    roots: RwLock<BTreeMap<CapabilityToken, BoundRoot>>,
}

impl RootRegistry {
    /// Creates an empty root capability registry.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Binds a capability token to a canonical directory path and pins its root.
    ///
    /// # Errors
    ///
    /// Returns storage error if the path cannot be canonicalized or opened,
    /// or validation error if the path is not a directory.
    pub fn bind(
        &self,
        capability: CapabilityToken,
        path: impl AsRef<Path>,
    ) -> Result<(), LomoError> {
        let path = path.as_ref();
        let canonical = path.canonicalize().map_err(|err| {
            storage(
                "capability_root_unavailable",
                &format!(
                    "cannot canonicalize capability root '{}': {err}",
                    path.display()
                ),
            )
        })?;
        if !canonical.is_dir() {
            return Err(validation(
                "capability_root_not_directory",
                &format!(
                    "capability root '{}' is not a directory",
                    canonical.display()
                ),
            ));
        }

        let root = Root::open(&canonical)?;
        let bound = BoundRoot {
            root: Arc::new(root),
        };

        let mut roots = self
            .roots
            .write()
            .map_err(|_error| internal("lock_poisoned", "registry rwlock poisoned"))?;
        if roots.contains_key(&capability) {
            return Err(validation(
                "capability_already_bound",
                "a live root capability cannot be rebound; create a new session or capability",
            ));
        }
        roots.insert(capability, bound);
        drop(roots);
        Ok(())
    }

    /// Resolves a capability token to its bound root.
    ///
    /// # Errors
    ///
    /// Returns permission error if the capability is not registered.
    pub fn resolve(&self, capability: &CapabilityToken) -> Result<BoundRoot, LomoError> {
        let map = self
            .roots
            .read()
            .map_err(|_err| internal("lock_poisoned", "registry rwlock poisoned"))?;
        map.get(capability).cloned().ok_or_else(|| {
            permission(
                "capability_unbound",
                &format!(
                    "capability token '{}' is not bound to a trusted root",
                    capability.as_str()
                ),
            )
        })
    }
}
