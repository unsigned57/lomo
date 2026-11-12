//! Routes control records to device-private storage without changing their logical addresses.

use std::sync::Arc;

use lomo_core::{
    ActionOutcome, LomoError, PlatformAction, PlatformActionBatch, PlatformActionExecutor,
    PlatformBatchResult, RelativeWorkspacePath, WorkspaceTarget,
};

/// The application owns the storage split; hosts supply the two capability executors.
pub struct WorkspaceStorageExecutor {
    pub(super) documents: Arc<dyn PlatformActionExecutor>,
    pub(super) records: Arc<dyn PlatformActionExecutor>,
}

impl WorkspaceStorageExecutor {
    #[must_use]
    pub fn new(
        documents: Arc<dyn PlatformActionExecutor>,
        records: Arc<dyn PlatformActionExecutor>,
    ) -> Self {
        Self { documents, records }
    }
}

fn private_path(path: &RelativeWorkspacePath) -> bool {
    matches!(
        path.as_str().split('/').next(),
        Some(
            lomo_workspace::LOMO_DIR_NAME
                | lomo_media::STAGE_DIR_NAME
                | lomo_media::MEDIA_TRASH_DIR_NAME
                | lomo_media::MEDIA_DELETE_INTENT_DIR_NAME
        )
    )
}

fn private_action(action: &PlatformAction) -> Result<bool, LomoError> {
    match action {
        PlatformAction::Stat { target, .. }
        | PlatformAction::Inspect { target, .. }
        | PlatformAction::ListChildren { target, .. } => Ok(match target {
            WorkspaceTarget::Root => false,
            WorkspaceTarget::Relative(path) => private_path(path),
        }),
        PlatformAction::EnsureDirectory { path, .. }
        | PlatformAction::Read { path, .. }
        | PlatformAction::ReadToExchange { path, .. }
        | PlatformAction::WriteFromExchange { path, .. }
        | PlatformAction::ArtifactWrite { path, .. }
        | PlatformAction::Delete { path, .. } => Ok(private_path(path)),
        PlatformAction::Move { source, target, .. } => {
            let private = private_path(source);
            if private != private_path(target) {
                return Err(crate::error::validation(
                    "cross_storage_move",
                    "a move cannot cross the document/control storage boundary",
                ));
            }
            Ok(private)
        }
    }
}

impl PlatformActionExecutor for WorkspaceStorageExecutor {
    fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
        // Admit the complete route plan before the first side effect. Consecutive runs preserve
        // both platform batching and the protocol's stop-at-first-failure prefix.
        let routes = batch
            .actions()
            .iter()
            .map(private_action)
            .collect::<Result<Vec<_>, _>>()?;
        let mut results = Vec::with_capacity(routes.len());
        let mut pending = batch.actions().iter();
        for run in routes.chunk_by(|left, right| left == right) {
            let private = *run.first().ok_or_else(|| {
                crate::error::corruption(
                    "empty_storage_route",
                    "a storage route must contain an action",
                )
            })?;
            let part = PlatformActionBatch::new(
                batch.job_id().clone(),
                batch.batch_id().clone(),
                batch.attempt(),
                batch.deadline_epoch_millis(),
                pending.by_ref().take(run.len()).cloned().collect(),
            )?;
            let executor = if private {
                &self.records
            } else {
                &self.documents
            };
            let result = executor.execute(&part)?;
            result.validate_against(&part)?;
            let stopped = result.action_results().len() < run.len()
                || result
                    .action_results()
                    .iter()
                    .any(|result| matches!(result.outcome(), ActionOutcome::Failed(_)));
            results.extend(result.into_action_results());
            if stopped {
                break;
            }
        }
        let result = PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        );
        result.validate_against(batch)?;
        Ok(result)
    }
}
