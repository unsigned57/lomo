use std::collections::BTreeMap;
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::{
    ActionId, ActionOutcome, ActionResult, CapabilityToken, DocumentKind, DocumentLocator,
    DocumentMetadata, ExchangeArtifact, ExpectedFingerprint, LomoError, MetadataPage, PageSize,
    PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput,
    PlatformBatchResult, RelativeWorkspacePath, StagedArtifactSource, VerifiedAbsence,
    WorkspaceTarget, WriteMode,
};
use sha2::{Digest, Sha256};

use crate::directory::DirectoryListing;
use crate::error::{conflict, internal, storage, timeout, validation};
use crate::exchange::{ExchangeDirectory, generate_random_nonce};
use crate::registry::RootRegistry;
use crate::sys;

/// Local filesystem platform action executor.
///
/// Implements `PlatformActionExecutor` against local directories bound by root
/// capabilities, utilizing per-OS anchored operations (directory descriptors on
/// unix, canonical-path handles on Windows), atomic temp-fsync-publish, and
/// SHA-256 baseline postcondition verification.
///
/// Before replacement, original source bytes are hashed again after syncing the
/// temporary file. A non-cooperating writer can still race between this
/// comparison and the atomic rename. Directory deletion requires an empty
/// directory: each child needs its own verified action.
#[derive(Debug)]
pub struct FsPlatformActionExecutor {
    registry: RootRegistry,
    exchange: ExchangeDirectory,
    witnesses: RwLock<BTreeMap<ActionId, (PlatformAction, PlatformActionOutput)>>,
}

impl FsPlatformActionExecutor {
    /// Creates a new executor with an application-private exchange directory.
    ///
    /// # Errors
    ///
    /// Returns storage error if the exchange directory cannot be created.
    pub fn new(exchange_dir: impl AsRef<std::path::Path>) -> Result<Self, LomoError> {
        Ok(Self {
            registry: RootRegistry::new(),
            exchange: ExchangeDirectory::new(exchange_dir)?,
            witnesses: RwLock::new(BTreeMap::new()),
        })
    }

    /// Binds a trusted capability token to a local directory root.
    ///
    /// # Errors
    ///
    /// Returns storage error if the path does not exist, or validation error if not a directory.
    pub fn bind_root(
        &self,
        capability: CapabilityToken,
        path: impl AsRef<std::path::Path>,
    ) -> Result<(), LomoError> {
        self.registry.bind(capability, path)
    }

    /// Returns the path to the private exchange directory.
    #[must_use]
    pub fn exchange_directory(&self) -> &std::path::Path {
        self.exchange.path()
    }

    /// Executes a single platform action directly, returning its verified `ActionResult`.
    #[must_use]
    pub fn execute_action(&self, action: &PlatformAction) -> ActionResult {
        let outcome = self.execute_single(action);
        if let ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) = &outcome
            && let Err(error) = self.record_witness(action, output)
        {
            return ActionResult::new(action.id().clone(), ActionOutcome::Failed(error));
        }
        ActionResult::new(action.id().clone(), outcome)
    }

    fn record_witness(
        &self,
        action: &PlatformAction,
        output: &PlatformActionOutput,
    ) -> Result<(), LomoError> {
        if matches!(
            action,
            PlatformAction::WriteFromExchange { .. }
                | PlatformAction::ArtifactWrite { .. }
                | PlatformAction::Move { .. }
                | PlatformAction::Delete { .. }
        ) {
            self.witnesses
                .write()
                .map_err(|_error| {
                    internal(
                        "witness_lock_poisoned",
                        "platform replay witness lock is poisoned",
                    )
                })?
                .insert(action.id().clone(), (action.clone(), output.clone()));
        }
        Ok(())
    }

    fn check_witness(
        &self,
        action: &PlatformAction,
    ) -> Result<Option<PlatformActionOutput>, LomoError> {
        let saved = self
            .witnesses
            .read()
            .map_err(|_error| {
                internal(
                    "witness_lock_poisoned",
                    "platform replay witness lock is poisoned",
                )
            })?
            .get(action.id())
            .cloned();
        let Some((previous_action, output)) = saved else {
            return Ok(None);
        };
        if previous_action != *action {
            return Ok(None);
        }
        if self.is_action_still_satisfied(action, &output)? {
            Ok(Some(output))
        } else {
            Ok(None)
        }
    }

    fn is_action_still_satisfied(
        &self,
        action: &PlatformAction,
        output: &PlatformActionOutput,
    ) -> Result<bool, LomoError> {
        match (action, output) {
            (
                PlatformAction::WriteFromExchange {
                    capability, path, ..
                }
                | PlatformAction::ArtifactWrite {
                    capability, path, ..
                },
                PlatformActionOutput::WriteComplete { metadata },
            ) => self.matches_current_document(capability, path, metadata),
            (
                PlatformAction::Move {
                    capability,
                    source,
                    target,
                    ..
                },
                PlatformActionOutput::MoveComplete { metadata },
            ) => {
                let bound = self.registry.resolve(capability)?;
                if sys::exists_beneath(bound.root(), source.as_str())? {
                    return Ok(false);
                }
                self.matches_current_document(capability, target, metadata)
            }
            (
                PlatformAction::Delete {
                    capability, path, ..
                },
                PlatformActionOutput::DeleteComplete { .. },
            ) => {
                let bound = self.registry.resolve(capability)?;
                Ok(!sys::exists_beneath(bound.root(), path.as_str())?)
            }
            _ => Ok(false),
        }
    }

    fn matches_current_document(
        &self,
        capability: &CapabilityToken,
        path: &RelativeWorkspacePath,
        expected: &DocumentMetadata,
    ) -> Result<bool, LomoError> {
        let bound = self.registry.resolve(capability)?;
        match sys::stat_document(bound.root(), &WorkspaceTarget::Relative(path.clone())) {
            Ok(current) => Ok(&current == expected),
            Err(error) if error.code() == "document_not_found" => Ok(false),
            Err(error) => Err(error),
        }
    }

    fn execute_single(&self, action: &PlatformAction) -> ActionOutcome {
        match self.check_witness(action) {
            Ok(Some(output)) => return ActionOutcome::AlreadySatisfied(output),
            Err(error) => return ActionOutcome::Failed(error),
            Ok(None) => {}
        }

        match action {
            PlatformAction::Stat {
                capability, target, ..
            } => self.execute_stat(capability, target),
            PlatformAction::ListChildren {
                capability,
                target,
                cursor,
                page_size,
                ..
            } => self.execute_list_children(capability, target, cursor.as_deref(), *page_size),
            PlatformAction::EnsureDirectory {
                capability, path, ..
            } => self.execute_ensure_directory(capability, path),
            PlatformAction::ReadToExchange {
                capability,
                path,
                exchange_token,
                expected_source,
                locator,
                ..
            } => self.execute_read_to_exchange(
                capability,
                path,
                locator,
                exchange_token,
                expected_source,
            ),
            PlatformAction::WriteFromExchange {
                capability,
                artifact,
                path,
                mode,
                expected_target,
                ..
            } => {
                self.execute_write_from_exchange(capability, artifact, path, *mode, expected_target)
            }
            PlatformAction::ArtifactWrite {
                capability,
                source,
                path,
                expected_target,
                ..
            } => self.execute_artifact_write(capability, source, path, expected_target),
            PlatformAction::Move {
                capability,
                source,
                target,
                expected_source,
                expected_target,
                ..
            } => self.execute_move(capability, source, target, expected_source, expected_target),
            PlatformAction::Delete {
                capability,
                path,
                expected_target,
                ..
            } => self.execute_delete(capability, path, expected_target),
        }
    }

    fn execute_stat(
        &self,
        capability: &CapabilityToken,
        target: &WorkspaceTarget,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        match sys::stat_document(bound.root(), target) {
            Ok(metadata) => {
                let output = PlatformActionOutput::Stat { metadata };
                ActionOutcome::Applied(output)
            }
            Err(err) => ActionOutcome::Failed(err),
        }
    }

    fn execute_list_children(
        &self,
        capability: &CapabilityToken,
        target: &WorkspaceTarget,
        cursor: Option<&str>,
        page_size: PageSize,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let (dir, rel_prefix) = match target {
            WorkspaceTarget::Root => match bound.root().as_dir() {
                Ok(dir) => (dir, String::new()),
                Err(err) => return ActionOutcome::Failed(err),
            },
            WorkspaceTarget::Relative(parent_rel) => {
                match sys::open_dir_at(bound.root(), parent_rel.as_str()) {
                    Ok(dir) => (dir, parent_rel.as_str().to_owned()),
                    Err(err) => return ActionOutcome::Failed(err),
                }
            }
        };

        let entry_names = match dir.entries() {
            Ok(names) => names,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let listing = DirectoryListing::new(entry_names, capability.as_str(), &rel_prefix);
        let start = match listing.start(cursor) {
            Ok(start) => start,
            Err(error) => return ActionOutcome::Failed(error),
        };
        let limit = page_size.get() as usize;
        let mut items = Vec::new();
        for name in listing.names(start, limit) {
            let relative = if rel_prefix.is_empty() {
                name.to_owned()
            } else {
                format!("{rel_prefix}/{name}")
            };
            let child = match RelativeWorkspacePath::parse(&relative) {
                Ok(path) => WorkspaceTarget::Relative(path),
                Err(error) => return ActionOutcome::Failed(error),
            };
            match sys::stat_document(bound.root(), &child) {
                Ok(metadata) => items.push(metadata),
                Err(error) => return ActionOutcome::Failed(error),
            }
        }
        let next_cursor = listing.cursor_after(start + items.len());

        match MetadataPage::new(items, next_cursor.as_deref()) {
            Ok(page) => ActionOutcome::Applied(PlatformActionOutput::Listed { page }),
            Err(err) => ActionOutcome::Failed(err),
        }
    }

    fn execute_ensure_directory(
        &self,
        capability: &CapabilityToken,
        path: &RelativeWorkspacePath,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let target = WorkspaceTarget::Relative(path.clone());
        let already_exists = match sys::exists_beneath(bound.root(), path.as_str()) {
            Ok(e) => e,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if already_exists {
            match sys::stat_document(bound.root(), &target) {
                Ok(metadata) => {
                    if metadata.kind() == DocumentKind::Directory {
                        ActionOutcome::AlreadySatisfied(PlatformActionOutput::DirectoryReady {
                            metadata,
                        })
                    } else {
                        ActionOutcome::Failed(validation(
                            "target_not_directory",
                            &format!("path '{}' already exists and is a file", path.as_str()),
                        ))
                    }
                }
                Err(err) => ActionOutcome::Failed(err),
            }
        } else {
            if let Err(err) = sys::ensure_directory_beneath(bound.root(), path.as_str()) {
                return ActionOutcome::Failed(err);
            }
            match sys::stat_document(bound.root(), &target) {
                Ok(metadata) => {
                    let output = PlatformActionOutput::DirectoryReady { metadata };
                    ActionOutcome::Applied(output)
                }
                Err(err) => ActionOutcome::Failed(err),
            }
        }
    }

    fn execute_read_to_exchange(
        &self,
        capability: &CapabilityToken,
        path: &RelativeWorkspacePath,
        locator: &DocumentLocator,
        exchange_token: &lomo_core::ExchangeToken,
        expected_source: &ExpectedFingerprint,
    ) -> ActionOutcome {
        let matches_path = match locator {
            DocumentLocator::Path(located) => located == path,
            DocumentLocator::Opaque(handle) => handle.as_str() == path.as_str(),
        };
        if !matches_path {
            return ActionOutcome::Failed(validation(
                "document_locator_mismatch",
                "handle and requested relative path must identify the same document",
            ));
        }
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let (source_metadata, content) = match sys::read_document_stream(bound.root(), path) {
            Ok(pair) => pair,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if matches!(expected_source, ExpectedFingerprint::Match(expected_evidence) if source_metadata.evidence() != expected_evidence)
        {
            return ActionOutcome::Failed(conflict(
                "platform_postcondition_mismatch",
                "Source fingerprint does not match the expected postcondition",
            ));
        }

        let artifact = match self.exchange.write_content(exchange_token, &content) {
            Ok(a) => a,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let output = PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        };
        ActionOutcome::Applied(output)
    }

    fn execute_write_from_exchange(
        &self,
        capability: &CapabilityToken,
        artifact: &ExchangeArtifact,
        path: &RelativeWorkspacePath,
        mode: WriteMode,
        expected_target: &ExpectedFingerprint,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let bytes = match self.exchange.read_artifact(artifact) {
            Ok(b) => b,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let target_ws = WorkspaceTarget::Relative(path.clone());
        let existing_res = sys::stat_document(bound.root(), &target_ws);

        if let Err(err) = validate_write_mode(mode, expected_target, &existing_res) {
            return ActionOutcome::Failed(err);
        }

        if let Err(err) = ensure_parent_directory_beneath(bound.root(), path.as_str()) {
            return ActionOutcome::Failed(err);
        }

        let (parent, file_name) = match sys::open_parent(bound.root(), path.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let nonce = match generate_random_nonce() {
            Ok(n) => n,
            Err(err) => return ActionOutcome::Failed(err),
        };
        let temp_file_name = format!(".tmp.{file_name}.{nonce}");

        if let Err(err) = sys::write_temp(&parent, &temp_file_name, &bytes) {
            return ActionOutcome::Failed(err);
        }

        let existing = sys::stat_document(bound.root(), &target_ws);
        if let Err(error) = validate_write_mode(mode, expected_target, &existing) {
            let diagnostic = sys::cleanup_temp(&parent, &temp_file_name, &error.to_string());
            return ActionOutcome::Failed(conflict("platform_postcondition_mismatch", &diagnostic));
        }

        if let Err(err) = sys::commit_temp(&parent, &temp_file_name, &file_name, mode) {
            return ActionOutcome::Failed(err);
        }

        if let Err(err) = parent.fsync() {
            return ActionOutcome::Failed(storage(
                "parent_dir_fsync_failed",
                &format!("fsync failed on parent directory: {err}"),
            ));
        }

        let metadata = match sys::stat_document(bound.root(), &target_ws) {
            Ok(m) => m,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if metadata.evidence().verified_digest() != Some(artifact.digest())
            || metadata.evidence().length() != artifact.length()
        {
            return ActionOutcome::Failed(conflict(
                "write_postcondition_mismatch",
                "destination changed before the written bytes could be verified",
            ));
        }
        let output = PlatformActionOutput::WriteComplete { metadata };
        ActionOutcome::Applied(output)
    }

    fn execute_artifact_write(
        &self,
        capability: &CapabilityToken,
        source: &StagedArtifactSource,
        path: &RelativeWorkspacePath,
        expected_target: &ExpectedFingerprint,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        // The staged source lives outside the capability root by contract; its declared
        // digest and length are re-verified while streaming, never trusted.
        let mut source_file = match open_staged_artifact_source(source) {
            Ok(file) => file,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let target_ws = WorkspaceTarget::Relative(path.clone());
        match classify_artifact_target(bound.root(), &target_ws, source, expected_target) {
            Ok(ArtifactTargetPlan::Satisfied(metadata)) => {
                return ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete {
                    metadata,
                });
            }
            Ok(ArtifactTargetPlan::Publish(_)) => {}
            Err(err) => return ActionOutcome::Failed(err),
        }

        if let Err(err) = ensure_parent_directory_beneath(bound.root(), path.as_str()) {
            return ActionOutcome::Failed(err);
        }
        let (parent, file_name) = match sys::open_parent(bound.root(), path.as_str()) {
            Ok(pair) => pair,
            Err(err) => return ActionOutcome::Failed(err),
        };
        if let Err(err) = sys::reclaim_temps(&parent, &file_name) {
            return ActionOutcome::Failed(err);
        }

        let nonce = match generate_random_nonce() {
            Ok(nonce) => nonce,
            Err(err) => return ActionOutcome::Failed(err),
        };
        let temp_file_name = format!(".tmp.{file_name}.{nonce}");
        if let Err(err) = sys::stream_temp(&parent, &temp_file_name, &mut source_file, source) {
            return ActionOutcome::Failed(err);
        }

        // Re-observe after the stream: the target may have moved while bytes were in flight.
        let mode = match classify_artifact_target(bound.root(), &target_ws, source, expected_target)
        {
            Ok(ArtifactTargetPlan::Satisfied(metadata)) => {
                // The target reached the declared digest while we streamed; drop the temp.
                drop(sys::cleanup_temp(&parent, &temp_file_name, ""));
                return ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete {
                    metadata,
                });
            }
            Ok(ArtifactTargetPlan::Publish(mode)) => mode,
            Err(err) => {
                let diagnostic = sys::cleanup_temp(&parent, &temp_file_name, &err.to_string());
                return ActionOutcome::Failed(conflict(
                    "platform_postcondition_mismatch",
                    &diagnostic,
                ));
            }
        };

        if let Err(err) = sys::commit_temp(&parent, &temp_file_name, &file_name, mode) {
            return ActionOutcome::Failed(err);
        }
        if let Err(err) = parent.fsync() {
            return ActionOutcome::Failed(storage(
                "parent_dir_fsync_failed",
                &format!("fsync failed on parent directory: {err}"),
            ));
        }

        let metadata = match sys::stat_document(bound.root(), &target_ws) {
            Ok(metadata) => metadata,
            Err(err) => return ActionOutcome::Failed(err),
        };
        if metadata.evidence().verified_digest() != Some(source.digest())
            || metadata.evidence().length() != source.length()
        {
            return ActionOutcome::Failed(conflict(
                "write_postcondition_mismatch",
                "destination changed before the written bytes could be verified",
            ));
        }
        ActionOutcome::Applied(PlatformActionOutput::WriteComplete { metadata })
    }

    fn execute_move(
        &self,
        capability: &CapabilityToken,
        source: &RelativeWorkspacePath,
        target: &RelativeWorkspacePath,
        expected_source: &ExpectedFingerprint,
        expected_target: &ExpectedFingerprint,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let dst_target = WorkspaceTarget::Relative(target.clone());
        let dst_meta = sys::stat_document(bound.root(), &dst_target);

        let src_target = WorkspaceTarget::Relative(source.clone());
        let src_meta = sys::stat_document(bound.root(), &src_target);

        if let Err(err) =
            validate_move_preconditions(expected_source, expected_target, &src_meta, &dst_meta)
        {
            return ActionOutcome::Failed(err);
        }

        let (src_parent, src_name) = match sys::open_parent(bound.root(), source.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if let Err(err) = ensure_parent_directory_beneath(bound.root(), target.as_str()) {
            return ActionOutcome::Failed(err);
        }

        let (dst_parent, dst_name) = match sys::open_parent(bound.root(), target.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let source_now = sys::stat_document(bound.root(), &src_target);
        let target_now = sys::stat_document(bound.root(), &dst_target);
        if let Err(error) =
            validate_move_preconditions(expected_source, expected_target, &source_now, &target_now)
        {
            return ActionOutcome::Failed(error);
        }
        let no_replace = matches!(expected_target, ExpectedFingerprint::Absent);
        if let Err(err) = src_parent.move_entry(&src_name, &dst_parent, &dst_name, no_replace) {
            return ActionOutcome::Failed(err);
        }

        if let Err(err) = src_parent.fsync() {
            return ActionOutcome::Failed(storage(
                "fsync_failed",
                &format!("fsync failed on source parent directory: {err}"),
            ));
        }
        if let Err(err) = dst_parent.fsync() {
            return ActionOutcome::Failed(storage(
                "fsync_failed",
                &format!("fsync failed on destination parent directory: {err}"),
            ));
        }

        let dst_meta = match sys::stat_document(bound.root(), &dst_target) {
            Ok(m) => m,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if let ExpectedFingerprint::Match(expected) = expected_source
            && (dst_meta.evidence().verified_digest() != expected.verified_digest()
                || dst_meta.evidence().length() != expected.length())
        {
            return ActionOutcome::Failed(conflict(
                "move_postcondition_mismatch",
                "destination differs from the verified source bytes",
            ));
        }
        let output = PlatformActionOutput::MoveComplete { metadata: dst_meta };
        ActionOutcome::Applied(output)
    }

    fn execute_delete(
        &self,
        capability: &CapabilityToken,
        path: &RelativeWorkspacePath,
        expected_target: &ExpectedFingerprint,
    ) -> ActionOutcome {
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let target = WorkspaceTarget::Relative(path.clone());
        let meta_res = sys::stat_document(bound.root(), &target);

        match meta_res {
            Err(err) if err.code() == "document_not_found" => match expected_target {
                ExpectedFingerprint::Absent => {
                    let fp = absence_fingerprint(path.as_str());
                    let absence = match VerifiedAbsence::new(target, &fp) {
                        Ok(a) => a,
                        Err(err) => return ActionOutcome::Failed(err),
                    };
                    ActionOutcome::AlreadySatisfied(PlatformActionOutput::DeleteComplete {
                        absence,
                    })
                }
                ExpectedFingerprint::Match(_) => ActionOutcome::Failed(conflict(
                    "platform_postcondition_mismatch",
                    "Expected target document to match baseline, but document is absent",
                )),
            },
            Err(err) => ActionOutcome::Failed(err),
            Ok(meta) => {
                let ExpectedFingerprint::Match(expected_evidence) = expected_target else {
                    return ActionOutcome::Failed(conflict(
                        "platform_postcondition_mismatch",
                        "Delete refused: target exists but expected fingerprint was absent",
                    ));
                };

                if meta.evidence() != expected_evidence {
                    return ActionOutcome::Failed(conflict(
                        "platform_postcondition_mismatch",
                        "Delete target fingerprint mismatch",
                    ));
                }

                let (parent, file_name) = match sys::open_parent(bound.root(), path.as_str()) {
                    Ok(p) => p,
                    Err(err) => return ActionOutcome::Failed(err),
                };

                if let Err(err) = parent.remove_entry(&file_name) {
                    return ActionOutcome::Failed(err);
                }

                if let Err(err) = parent.fsync() {
                    return ActionOutcome::Failed(storage(
                        "fsync_failed",
                        &format!("fsync failed on parent directory: {err}"),
                    ));
                }

                let fp = deleted_fingerprint(path.as_str());
                let absence = match VerifiedAbsence::new(target, &fp) {
                    Ok(a) => a,
                    Err(err) => return ActionOutcome::Failed(err),
                };

                let output = PlatformActionOutput::DeleteComplete { absence };
                ActionOutcome::Applied(output)
            }
        }
    }
}

fn ensure_parent_directory_beneath(root: &sys::Root, path: &str) -> Result<(), LomoError> {
    if let Some((parent, _name)) = path.rsplit_once('/') {
        sys::ensure_directory_beneath(root, parent)?;
    }
    Ok(())
}

fn validate_write_mode(
    mode: WriteMode,
    expected_target: &ExpectedFingerprint,
    existing_res: &Result<DocumentMetadata, LomoError>,
) -> Result<(), LomoError> {
    match mode {
        WriteMode::Create => {
            if expected_target != &ExpectedFingerprint::Absent {
                return Err(conflict(
                    "platform_postcondition_mismatch",
                    "Create mode requires ExpectedFingerprint::Absent",
                ));
            }
            match existing_res {
                Ok(_) => Err(conflict(
                    "platform_postcondition_mismatch",
                    "Create refused because the target already exists",
                )),
                Err(err) if err.code() == "document_not_found" => Ok(()),
                Err(err) => Err(err.clone()),
            }
        }
        WriteMode::Replace => {
            let ExpectedFingerprint::Match(expected_evidence) = expected_target else {
                return Err(conflict(
                    "platform_postcondition_mismatch",
                    "Replace requires matching expected target evidence; absent is invalid for existing target",
                ));
            };
            match existing_res {
                Ok(existing_meta) => {
                    if existing_meta.evidence() == expected_evidence {
                        Ok(())
                    } else {
                        Err(conflict(
                            "platform_postcondition_mismatch",
                            "Target fingerprint does not match the expected postcondition",
                        ))
                    }
                }
                Err(err) if err.code() == "document_not_found" => Err(conflict(
                    "platform_postcondition_mismatch",
                    "Expected target document for Replace but document is absent",
                )),
                Err(err) => Err(err.clone()),
            }
        }
    }
}

enum ArtifactTargetPlan {
    /// The target already holds the declared artifact bytes — nothing to publish.
    Satisfied(DocumentMetadata),
    /// Publish with this rename semantic.
    Publish(WriteMode),
}

/// Opens the staged artifact source and verifies it is a regular file of the declared length.
/// The digest is verified while streaming, not here.
fn open_staged_artifact_source(source: &StagedArtifactSource) -> Result<std::fs::File, LomoError> {
    let file = match std::fs::File::open(source.path()) {
        Ok(file) => file,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
            return Err(storage(
                "artifact_source_missing",
                &format!("staged artifact source is missing: {err}"),
            ));
        }
        Err(err) => {
            return Err(storage(
                "artifact_source_open_failed",
                &format!("failed to open staged artifact source: {err}"),
            ));
        }
    };
    match file.metadata() {
        Ok(meta) if meta.len() == source.length() && meta.is_file() => Ok(file),
        Ok(_) => Err(conflict(
            "artifact_source_mismatch",
            "staged artifact length differs from the frozen declaration",
        )),
        Err(err) => Err(storage(
            "artifact_source_stat_failed",
            &format!("failed to stat staged artifact source: {err}"),
        )),
    }
}

/// Classifies an artifact-write target into the three recovery states: already holding the
/// declared digest (satisfied), absent/matching baseline (publish), or third-party change
/// (conflict).
fn classify_artifact_target(
    root: &sys::Root,
    target: &WorkspaceTarget,
    source: &StagedArtifactSource,
    expected_target: &ExpectedFingerprint,
) -> Result<ArtifactTargetPlan, LomoError> {
    match sys::stat_document(root, target) {
        Ok(metadata) => {
            if metadata.kind() == DocumentKind::File
                && metadata.evidence().length() == source.length()
                && metadata.evidence().verified_digest() == Some(source.digest())
            {
                return Ok(ArtifactTargetPlan::Satisfied(metadata));
            }
            match expected_target {
                ExpectedFingerprint::Match(expected) if metadata.evidence() == expected => {
                    Ok(ArtifactTargetPlan::Publish(WriteMode::Replace))
                }
                ExpectedFingerprint::Absent | ExpectedFingerprint::Match(_) => Err(conflict(
                    "platform_postcondition_mismatch",
                    "artifact target differs from the expected fingerprint",
                )),
            }
        }
        Err(err) if err.code() == "document_not_found" => match expected_target {
            ExpectedFingerprint::Absent => Ok(ArtifactTargetPlan::Publish(WriteMode::Create)),
            ExpectedFingerprint::Match(_) => Err(conflict(
                "platform_postcondition_mismatch",
                "expected artifact target baseline but the document is absent",
            )),
        },
        Err(err) => Err(err),
    }
}

fn validate_move_preconditions(
    expected_source: &ExpectedFingerprint,
    expected_target: &ExpectedFingerprint,
    source: &Result<DocumentMetadata, LomoError>,
    target: &Result<DocumentMetadata, LomoError>,
) -> Result<(), LomoError> {
    validate_write_mode(WriteMode::Replace, expected_source, source)?;
    let mode = match expected_target {
        ExpectedFingerprint::Absent => WriteMode::Create,
        ExpectedFingerprint::Match(_) => WriteMode::Replace,
    };
    validate_write_mode(mode, expected_target, target)
}

impl PlatformActionExecutor for FsPlatformActionExecutor {
    fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
        let now_millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|err| {
                internal(
                    "clock_failure",
                    &format!("system clock before UNIX epoch: {err}"),
                )
            })?
            .as_millis();
        let now_millis = u64::try_from(now_millis)
            .map_err(|err| internal("clock_overflow", &format!("system clock overflow: {err}")))?;

        if now_millis > batch.deadline_epoch_millis() {
            let first_id = match batch.actions().first() {
                Some(act) => act.id().clone(),
                None => {
                    return Err(validation(
                        "empty_platform_batch",
                        "platform batch must contain at least one action",
                    ));
                }
            };
            let fail_result = ActionResult::new(
                first_id,
                ActionOutcome::Failed(timeout(
                    "platform_batch_deadline_exceeded",
                    "batch deadline epoch has passed",
                )),
            );
            return Ok(PlatformBatchResult::new(
                batch.schema_version(),
                batch.job_id().clone(),
                batch.batch_id().clone(),
                batch.attempt(),
                vec![fail_result],
            ));
        }

        let mut results = Vec::with_capacity(batch.actions().len());
        for action in batch.actions() {
            let result = self.execute_action(action);
            let is_failed = matches!(result.outcome(), ActionOutcome::Failed(_));
            results.push(result);
            if is_failed {
                break;
            }
        }

        let batch_result = PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        );

        batch_result.validate_against(batch)?;

        Ok(batch_result)
    }
}

fn absence_fingerprint(path: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.as_bytes());
    let hex = format!("{:x}", hasher.finalize());
    let prefix: String = hex.chars().take(40).collect();
    format!("absent.{prefix}")
}

fn deleted_fingerprint(path: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.as_bytes());
    let hex = format!("{:x}", hasher.finalize());
    let prefix: String = hex.chars().take(40).collect();
    format!("deleted.{prefix}")
}
