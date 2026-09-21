use std::path::PathBuf;

use lomo_core::CapabilityToken;
use lomo_workspace::{WorkspaceGenerationId, WorkspaceRootId};

/// Device-private configuration and capabilities for a workspace session.
#[derive(Clone, Debug)]
pub struct WorkspaceSessionConfig {
    pub capability: CapabilityToken,
    pub root_id: WorkspaceRootId,
    /// Durable workspace generation fence; scopes reminder snooze bindings and alarm identities.
    pub workspace_generation: WorkspaceGenerationId,
    pub time_zone: String,
    pub date_format: crate::calendar::DateFormat,
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub exchange_dir: PathBuf,
}
