//! One application boundary for capability I/O and byte-exact postcondition verification.

use std::{
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use crate::{
    config::WorkspaceSessionConfig,
    csprng::generate_hex_token,
    error::{corruption, storage, validation},
    exchange_io::stage_content,
};
use lomo_core::{
    ActionEvidence, ActionId, ActionOutcome, ActionResult, BatchId, DocumentMetadata,
    ExpectedFingerprint, JobId, LomoError, PlatformAction, PlatformActionBatch,
    PlatformActionExecutor, PlatformActionOutput, RelativeWorkspacePath, StagedArtifactSource,
    WriteMode,
};

/// Maximum independent file observations (source reads or metadata) in one platform batch.
pub const MAX_WORKSPACE_READ_BATCH: usize = 8;

pub struct FileSnapshot {
    pub bytes: Vec<u8>,
    evidence: SourceEvidence,
}
enum SourceEvidence {
    Observed(ActionEvidence),
    Committed(lomo_core::OperationId),
}
impl FileSnapshot {
    fn observed_evidence(&self) -> Result<&ActionEvidence, LomoError> {
        match &self.evidence {
            SourceEvidence::Observed(evidence) => Ok(evidence),
            SourceEvidence::Committed(operation) => Err(storage(
                "unconverged_source",
                format!(
                    "operation {} is a logical source, not a platform CAS witness",
                    operation.as_str()
                ),
            )),
        }
    }
}
enum ReadView<'a> {
    Physical,
    Committed(&'a crate::write_behind::WriteBehind),
}

pub struct WorkspaceIo<'a> {
    pub config: &'a WorkspaceSessionConfig,
    pub executor: &'a Arc<dyn PlatformActionExecutor>,
    view: ReadView<'a>,
}

impl<'a> WorkspaceIo<'a> {
    pub fn physical(
        config: &'a WorkspaceSessionConfig,
        executor: &'a Arc<dyn PlatformActionExecutor>,
    ) -> Self {
        Self {
            config,
            executor,
            view: ReadView::Physical,
        }
    }
    pub fn committed(
        config: &'a WorkspaceSessionConfig,
        executor: &'a Arc<dyn PlatformActionExecutor>,
        writes: &'a crate::write_behind::WriteBehind,
    ) -> Self {
        Self {
            config,
            executor,
            view: ReadView::Committed(writes),
        }
    }

    pub fn execute(&self, action: PlatformAction) -> Result<PlatformActionOutput, LomoError> {
        self.execute_batch(vec![action])?.pop().ok_or_else(|| {
            corruption(
                "missing_platform_result",
                "a single action requires one verified result",
            )
        })
    }

    fn execute_batch(
        &self,
        actions: Vec<PlatformAction>,
    ) -> Result<Vec<PlatformActionOutput>, LomoError> {
        let expected_count = actions.len();
        let outcomes = self.execute_batch_outcomes(actions)?;
        let outputs = outcomes
            .into_iter()
            .map(|outcome| match outcome {
                ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => {
                    Ok(output)
                }
                ActionOutcome::Failed(error) => Err(error),
            })
            .collect::<Result<Vec<_>, _>>()?;
        if outputs.len() != expected_count {
            return Err(corruption(
                "missing_platform_result",
                "successful platform batch returned an incomplete prefix",
            ));
        }
        Ok(outputs)
    }

    /// The executor may return a verified prefix ending at its first failed action.
    fn execute_batch_outcomes(
        &self,
        actions: Vec<PlatformAction>,
    ) -> Result<Vec<ActionOutcome>, LomoError> {
        let nonce = generate_hex_token(16)?;
        let batch = PlatformActionBatch::new(
            JobId::parse(&format!("job-{nonce}"))?,
            BatchId::parse(&format!("batch-{nonce}"))?,
            1,
            deadline()?,
            actions,
        )?;
        let result = self.executor.execute(&batch)?;
        result.validate_against(&batch)?;
        Ok(result
            .into_action_results()
            .into_iter()
            .map(ActionResult::into_outcome)
            .collect())
    }

    fn read_action(&self, path: &RelativeWorkspacePath) -> Result<PlatformAction, LomoError> {
        let nonce = generate_hex_token(16)?;
        Ok(PlatformAction::read(
            ActionId::parse(&format!("read-{nonce}"))?,
            self.config.capability.clone(),
            path.clone(),
            ExpectedFingerprint::absent(),
        ))
    }

    /// Reads an ordered group through the bounded lane. Verified absence is explicit; after
    /// a not-found prefix, only the unreported tail is submitted, never an observed source.
    fn read_physical_batch(
        &self,
        paths: &[RelativeWorkspacePath],
    ) -> Result<Vec<Option<FileSnapshot>>, LomoError> {
        if paths.is_empty() || paths.len() > MAX_WORKSPACE_READ_BATCH {
            return Err(validation(
                "invalid_workspace_read_batch",
                "source read batch must contain 1..=8 files",
            ));
        }
        let mut snapshots = Vec::with_capacity(paths.len());
        while snapshots.len() < paths.len() {
            let actions = paths
                .iter()
                .skip(snapshots.len())
                .map(|path| self.read_action(path))
                .collect::<Result<Vec<_>, _>>()?;
            let outcomes = self.execute_batch_outcomes(actions)?;
            if outcomes.is_empty() {
                return Err(corruption(
                    "missing_platform_result",
                    "source batch returned no observation",
                ));
            }
            for outcome in outcomes {
                let snapshot = match outcome {
                    ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => {
                        Some(source_snapshot(output)?)
                    }
                    ActionOutcome::Failed(error) if error.code() == "document_not_found" => None,
                    ActionOutcome::Failed(error) => return Err(error),
                };
                snapshots.push(snapshot);
            }
        }
        Ok(snapshots)
    }

    pub fn read_batch(
        &self,
        paths: &[RelativeWorkspacePath],
    ) -> Result<Vec<Option<FileSnapshot>>, LomoError> {
        if paths.is_empty() || paths.len() > MAX_WORKSPACE_READ_BATCH {
            return Err(validation(
                "invalid_workspace_read_batch",
                "source read batch must contain 1..=8 files",
            ));
        }
        let sources = match &self.view {
            ReadView::Physical => paths
                .iter()
                .map(|_| crate::write_behind::PendingSource::Physical)
                .collect(),
            ReadView::Committed(writes) => writes.sources(paths)?,
        };
        let physical = paths
            .iter()
            .zip(&sources)
            .filter_map(|(path, source)| {
                matches!(source, crate::write_behind::PendingSource::Physical)
                    .then_some(path.clone())
            })
            .collect::<Vec<_>>();
        let observed = if physical.is_empty() {
            Vec::new()
        } else {
            self.read_physical_batch(&physical)?
        };
        let mut observed = observed.into_iter();
        sources
            .into_iter()
            .map(|source| match source {
                crate::write_behind::PendingSource::Physical => observed.next().ok_or_else(|| {
                    corruption(
                        "missing_platform_result",
                        "physical source observation is missing",
                    )
                }),
                crate::write_behind::PendingSource::Written {
                    operation_id,
                    bytes,
                } => Ok(Some(FileSnapshot {
                    bytes,
                    evidence: SourceEvidence::Committed(operation_id),
                })),
                crate::write_behind::PendingSource::Removed => Ok(None),
            })
            .collect()
    }

    /// Reads required sources with the same witnessed, bounded source-read protocol.
    pub fn require_batch(
        &self,
        paths: &[RelativeWorkspacePath],
    ) -> Result<Vec<FileSnapshot>, LomoError> {
        self.read_batch(paths)?
            .into_iter()
            .zip(paths)
            .map(|(snapshot, path)| {
                snapshot.ok_or_else(|| storage("document_not_found", format!("missing {path:?}")))
            })
            .collect()
    }

    /// Metadata-only observations for a bounded set of exact paths, never sibling enumeration.
    pub fn inspect_batch(
        &self,
        paths: &[RelativeWorkspacePath],
    ) -> Result<Vec<DocumentMetadata>, LomoError> {
        if paths.is_empty() || paths.len() > MAX_WORKSPACE_READ_BATCH {
            return Err(validation(
                "invalid_workspace_inspect_batch",
                "metadata batch must contain 1..=8 paths",
            ));
        }
        let actions = paths
            .iter()
            .map(|path| {
                Ok(PlatformAction::inspect(
                    ActionId::parse(&format!("inspect-{}", generate_hex_token(16)?))?,
                    self.config.capability.clone(),
                    lomo_core::WorkspaceTarget::Relative(path.clone()),
                ))
            })
            .collect::<Result<Vec<_>, LomoError>>()?;
        self.execute_batch(actions)?
            .into_iter()
            .map(|output| match output {
                PlatformActionOutput::Stat { metadata } => Ok(metadata),
                PlatformActionOutput::Listed { .. }
                | PlatformActionOutput::DirectoryReady { .. }
                | PlatformActionOutput::ReadToExchange { .. }
                | PlatformActionOutput::Read { .. }
                | PlatformActionOutput::WriteComplete { .. }
                | PlatformActionOutput::MoveComplete { .. }
                | PlatformActionOutput::DeleteComplete { .. } => Err(corruption(
                    "invalid_inspect_output",
                    "expected metadata observation",
                )),
            })
            .collect()
    }

    /// Direct source read: the executor's verified evidence attests the returned bytes, so a
    /// logical read is one streaming pass — no exchange artifact, no re-read, no baseline write.
    pub fn read(&self, path: &RelativeWorkspacePath) -> Result<Option<FileSnapshot>, LomoError> {
        self.read_batch(std::slice::from_ref(path))?
            .pop()
            .ok_or_else(|| {
                corruption(
                    "missing_platform_result",
                    "source read returned no observation",
                )
            })
    }

    /// Metadata-only observation for artifact plans: recovery and satisfaction checks never
    /// route media bytes through memory.
    pub fn stat(
        &self,
        path: &RelativeWorkspacePath,
    ) -> Result<Option<DocumentMetadata>, LomoError> {
        let nonce = generate_hex_token(16)?;
        let action = PlatformAction::stat(
            ActionId::parse(&format!("stat-{nonce}"))?,
            self.config.capability.clone(),
            path.clone(),
        );
        match self.execute(action) {
            Ok(PlatformActionOutput::Stat { metadata }) => Ok(Some(metadata)),
            Err(error) if error.code() == "document_not_found" => Ok(None),
            Err(error) => Err(error),
            Ok(_) => Err(corruption(
                "invalid_stat_output",
                "expected verified stat metadata",
            )),
        }
    }

    /// Publishes a retained staged artifact: the executor streams source bytes to the target,
    /// verifying digest and length during the transfer and at the returned receipt.
    pub fn write_artifact(
        &self,
        path: &RelativeWorkspacePath,
        source: &StagedArtifactSource,
        expected_target: ExpectedFingerprint,
    ) -> Result<(), LomoError> {
        let action = PlatformAction::artifact_write(
            ActionId::parse(&format!("artifact-{}", generate_hex_token(16)?))?,
            self.config.capability.clone(),
            source.clone(),
            path.clone(),
            expected_target,
        );
        let output = self.execute(action)?;
        let PlatformActionOutput::WriteComplete { metadata } = output else {
            return Err(corruption(
                "invalid_artifact_write_output",
                "expected a verified write receipt",
            ));
        };
        if metadata.evidence().verified_digest() != Some(source.digest())
            || metadata.evidence().length() != source.length()
        {
            return Err(corruption(
                "artifact_write_evidence_mismatch",
                "artifact write receipt differs from the declared source",
            ));
        }
        Ok(())
    }

    pub fn require(&self, path: &RelativeWorkspacePath) -> Result<FileSnapshot, LomoError> {
        self.read(path)?
            .ok_or_else(|| storage("document_not_found", format!("missing {path:?}")))
    }

    pub fn write(
        &self,
        path: &RelativeWorkspacePath,
        before: Option<&FileSnapshot>,
        bytes: &[u8],
    ) -> Result<FileSnapshot, LomoError> {
        let expected = before
            .map(FileSnapshot::observed_evidence)
            .transpose()?
            .map_or_else(ExpectedFingerprint::absent, |evidence| {
                ExpectedFingerprint::matching(evidence.clone())
            });
        let artifact = stage_content(&self.config.exchange_dir, bytes)?;
        let action = PlatformAction::write_from_exchange(
            ActionId::parse(&format!("write-{}", generate_hex_token(16)?))?,
            self.config.capability.clone(),
            artifact.clone(),
            path.clone(),
            if before.is_some() {
                WriteMode::Replace
            } else {
                WriteMode::Create
            },
            expected,
        );
        let output = self.execute(action);
        let cleanup =
            std::fs::remove_file(self.config.exchange_dir.join(artifact.token().as_str()));
        if let Err(error) = cleanup {
            return Err(storage(
                "exchange_cleanup_failed",
                format!("{error}; write result: {output:?}"),
            ));
        }
        let PlatformActionOutput::WriteComplete { metadata } = output? else {
            return Err(corruption(
                "invalid_write_output",
                "expected a verified write receipt",
            ));
        };
        if metadata.evidence().verified_digest() != Some(artifact.digest())
            || metadata.evidence().length() != artifact.length()
        {
            return Err(corruption(
                "write_evidence_mismatch",
                "write receipt differs from staged bytes",
            ));
        }
        // The verified receipt already pins target digest and length to the staged artifact;
        // a byte-level read-back would only restate it. Committed bytes are the editing
        // baseline future conflicts may cite — retain them as the CAS baseline at the
        // commit boundary instead of baselining every read. Internal record writes are
        // categorically excluded: they produce baselines no fingerprint can cite.
        if baseline_worthy(path) {
            crate::private_io::remember_baseline(
                &lomo_workspace::SessionStateLayout::for_state_dir(&self.config.state_dir)
                    .baselines,
                bytes,
            )?;
        }
        Ok(FileSnapshot {
            bytes: bytes.to_vec(),
            evidence: SourceEvidence::Observed(metadata.evidence().clone()),
        })
    }

    pub fn delete(
        &self,
        path: &RelativeWorkspacePath,
        before: &FileSnapshot,
    ) -> Result<(), LomoError> {
        let action = PlatformAction::delete(
            ActionId::parse(&format!("delete-{}", generate_hex_token(16)?))?,
            self.config.capability.clone(),
            path.clone(),
            ExpectedFingerprint::matching(before.observed_evidence()?.clone()),
        );
        let output = self.execute(action)?;
        match output {
            PlatformActionOutput::DeleteComplete { .. } => Ok(()),
            PlatformActionOutput::Stat { .. }
            | PlatformActionOutput::Listed { .. }
            | PlatformActionOutput::DirectoryReady { .. }
            | PlatformActionOutput::ReadToExchange { .. }
            | PlatformActionOutput::Read { .. }
            | PlatformActionOutput::WriteComplete { .. }
            | PlatformActionOutput::MoveComplete { .. } => Err(corruption(
                "invalid_delete_output",
                "expected a verified delete receipt",
            )),
        }
    }

    /// Copies verified bytes through the bounded exchange lane before unlinking the source.
    /// A failed unlink leaves both copies recoverable, including across storage providers.
    pub fn relocate(
        &self,
        source: &RelativeWorkspacePath,
        target: &RelativeWorkspacePath,
        observed: &DocumentMetadata,
    ) -> Result<(), LomoError> {
        let token = format!("relocate-{}", generate_hex_token(16)?);
        let output = self.execute(PlatformAction::read_to_exchange(
            ActionId::parse(&format!("relocate-read-{}", generate_hex_token(8)?))?,
            self.config.capability.clone(),
            source.clone(),
            &token,
            ExpectedFingerprint::matching(observed.evidence().clone()),
        )?)?;
        let PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        } = output
        else {
            return Err(corruption(
                "invalid_relocation_read",
                "relocation requires a verified artifact",
            ));
        };
        let result = (|| {
            self.execute(PlatformAction::write_from_exchange(
                ActionId::parse(&format!("relocate-write-{}", generate_hex_token(8)?))?,
                self.config.capability.clone(),
                artifact.clone(),
                target.clone(),
                WriteMode::Create,
                ExpectedFingerprint::absent(),
            ))?;
            self.execute(PlatformAction::delete(
                ActionId::parse(&format!("relocate-delete-{}", generate_hex_token(8)?))?,
                self.config.capability.clone(),
                source.clone(),
                ExpectedFingerprint::matching(source_metadata.evidence().clone()),
            ))?;
            Ok(())
        })();
        std::fs::remove_file(self.config.exchange_dir.join(artifact.token().as_str())).map_err(
            |error| {
                storage(
                    "exchange_cleanup_failed",
                    format!("{error}; relocation result: {result:?}"),
                )
            },
        )?;
        result
    }
}

/// Whether committed bytes at `path` can ever serve as a conflict baseline. The
/// eviction keep-set is live `memo.file_fingerprint`s ∪ retained draft evidence —
/// both face workspace *documents*. Every internal target `write` can carry —
/// the `.lomo` record store and the media delete-intent journal — is
/// unreferenceable by construction, so baselining one is write-time garbage that
/// `evict_stale_baselines` later unlinks and fsyncs one blob at a time on every
/// open.
fn baseline_worthy(path: &RelativeWorkspacePath) -> bool {
    !matches!(
        path.as_str().split('/').next(),
        Some(lomo_workspace::LOMO_DIR_NAME | lomo_media::MEDIA_DELETE_INTENT_DIR_NAME)
    )
}

pub fn epoch_millis() -> Result<i64, LomoError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| validation("system_time_error", error.to_string()))?;
    i64::try_from(duration.as_millis())
        .map_err(|error| validation("epoch_overflow", error.to_string()))
}

fn source_snapshot(output: PlatformActionOutput) -> Result<FileSnapshot, LomoError> {
    let PlatformActionOutput::Read {
        source_metadata,
        bytes,
    } = output
    else {
        return Err(corruption(
            "invalid_read_output",
            "expected verified source bytes",
        ));
    };
    Ok(FileSnapshot {
        bytes,
        evidence: SourceEvidence::Observed(source_metadata.evidence().clone()),
    })
}

pub fn deadline() -> Result<u64, LomoError> {
    u64::try_from(epoch_millis()?)
        .map_err(|error| validation("epoch_overflow", error.to_string()))?
        .checked_add(60_000)
        .ok_or_else(|| validation("deadline_overflow", "platform deadline overflow"))
}
