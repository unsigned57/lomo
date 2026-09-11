//! One application boundary for capability I/O and byte-exact postcondition verification.

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::{
    ActionEvidence, ActionId, ActionOutcome, BatchId, ExpectedFingerprint, JobId, LomoError,
    PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput,
    RelativeWorkspacePath, WriteMode,
};
use lomo_workspace::SourceFingerprint;

use crate::config::WorkspaceSessionConfig;
use crate::csprng::generate_hex_token;
use crate::error::{corruption, storage, validation};
use crate::exchange_io::{read_artifact_content, stage_content};

pub struct FileSnapshot {
    pub bytes: Vec<u8>,
    pub evidence: ActionEvidence,
}

pub struct WorkspaceIo<'a> {
    pub config: &'a WorkspaceSessionConfig,
    pub executor: &'a Arc<dyn PlatformActionExecutor>,
}

impl WorkspaceIo<'_> {
    pub fn execute(&self, action: PlatformAction) -> Result<PlatformActionOutput, LomoError> {
        let nonce = generate_hex_token(16)?;
        let batch = PlatformActionBatch::new(
            JobId::parse(&format!("job-{nonce}"))?,
            BatchId::parse(&format!("batch-{nonce}"))?,
            1,
            deadline()?,
            vec![action],
        )?;
        let result = self.executor.execute(&batch)?;
        result.validate_against(&batch)?;
        let first = result.action_results().first().ok_or_else(|| {
            corruption(
                "missing_platform_result",
                "a single action requires one verified result",
            )
        })?;
        match first.outcome() {
            ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => {
                Ok(output.clone())
            }
            ActionOutcome::Failed(error) => Err(error.clone()),
        }
    }

    pub fn read(&self, path: &RelativeWorkspacePath) -> Result<Option<FileSnapshot>, LomoError> {
        let nonce = generate_hex_token(16)?;
        let action = PlatformAction::read_to_exchange(
            ActionId::parse(&format!("read-{nonce}"))?,
            self.config.capability.clone(),
            path.clone(),
            &format!("exchange-{nonce}"),
            ExpectedFingerprint::absent(),
        )?;
        let output = match self.execute(action) {
            Ok(output) => output,
            Err(error) if error.code() == "document_not_found" => return Ok(None),
            Err(error) => return Err(error),
        };
        let PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        } = output
        else {
            return Err(corruption(
                "invalid_read_output",
                "expected a verified source artifact",
            ));
        };
        let bytes = read_artifact_content(&self.config.exchange_dir, &artifact)?;
        let evidence = source_metadata.evidence().clone();
        if SourceFingerprint::of_bytes(&bytes).as_str() != evidence.digest().as_str()
            || artifact.length() != evidence.length()
        {
            return Err(corruption(
                "source_evidence_mismatch",
                "source and exchange evidence disagree",
            ));
        }
        crate::private_io::remember_baseline(&self.config.state_dir, &bytes)?;
        Ok(Some(FileSnapshot { bytes, evidence }))
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
            before.map_or_else(ExpectedFingerprint::absent, |snapshot| {
                ExpectedFingerprint::matching(snapshot.evidence.clone())
            }),
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
        if metadata.evidence().digest() != artifact.digest()
            || metadata.evidence().length() != artifact.length()
        {
            return Err(corruption(
                "write_evidence_mismatch",
                "write receipt differs from staged bytes",
            ));
        }
        let after = self.require(path)?;
        if after.bytes != bytes {
            return Err(crate::error::conflict(
                "write_postcondition_mismatch",
                "read-back differs from committed bytes",
            ));
        }
        Ok(after)
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
            ExpectedFingerprint::matching(before.evidence.clone()),
        );
        let output = self.execute(action)?;
        match output {
            PlatformActionOutput::DeleteComplete { .. } => Ok(()),
            PlatformActionOutput::Stat { .. }
            | PlatformActionOutput::Listed { .. }
            | PlatformActionOutput::DirectoryReady { .. }
            | PlatformActionOutput::ReadToExchange { .. }
            | PlatformActionOutput::WriteComplete { .. }
            | PlatformActionOutput::MoveComplete { .. } => Err(corruption(
                "invalid_delete_output",
                "expected a verified delete receipt",
            )),
        }
    }
}

pub fn epoch_millis() -> Result<i64, LomoError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| validation("system_time_error", error.to_string()))?;
    i64::try_from(duration.as_millis())
        .map_err(|error| validation("epoch_overflow", error.to_string()))
}

pub fn deadline() -> Result<u64, LomoError> {
    u64::try_from(epoch_millis()?)
        .map_err(|error| validation("epoch_overflow", error.to_string()))?
        .checked_add(60_000)
        .ok_or_else(|| validation("deadline_overflow", "platform deadline overflow"))
}
