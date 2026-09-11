use std::collections::BTreeMap;
use std::io::Write;
use std::mem::MaybeUninit;
use std::path::Path;
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::{
    ActionId, ActionOutcome, ActionResult, CapabilityToken, DocumentKind, DocumentLocator,
    DocumentMetadata, ExchangeArtifact, ExpectedFingerprint, LomoError, MetadataPage, PageSize,
    PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput,
    PlatformBatchResult, RelativeWorkspacePath, VerifiedAbsence, WorkspaceTarget, WriteMode,
};
use rustix::fd::OwnedFd;
use rustix::fs::{Mode, OFlags};
use rustix::io::Errno;
use sha2::{Digest, Sha256};

use crate::directory::DirectoryListing;
use crate::error::{conflict, internal, permission, storage, timeout, validation};
use crate::exchange::{ExchangeDirectory, generate_random_nonce};
use crate::path_security::{
    exists_beneath, open_beneath, open_parent_beneath, read_document_stream, stat_document_fd,
};
use crate::registry::RootRegistry;

/// Linux POSIX platform action executor.
///
/// Implements `PlatformActionExecutor` against local directories bound by root capabilities,
/// utilizing directory-descriptor capability operations, atomic temp-fsync-rename/link,
/// and SHA-256 baseline postcondition verification.
///
/// Before replacement, original source bytes are hashed again after syncing the temporary file.
/// A non-cooperating writer can still race between this comparison and the atomic rename.
/// Directory deletion requires an empty directory: each child needs its own verified action.
#[derive(Debug)]
pub struct PosixPlatformActionExecutor {
    registry: RootRegistry,
    exchange: ExchangeDirectory,
    witnesses: RwLock<BTreeMap<ActionId, (PlatformAction, PlatformActionOutput)>>,
}

impl PosixPlatformActionExecutor {
    /// Creates a new POSIX executor with an application-private exchange directory.
    ///
    /// # Errors
    ///
    /// Returns storage error if the exchange directory cannot be created.
    pub fn new(exchange_dir: impl AsRef<Path>) -> Result<Self, LomoError> {
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
        path: impl AsRef<Path>,
    ) -> Result<(), LomoError> {
        self.registry.bind(capability, path)
    }

    /// Returns the path to the private exchange directory.
    #[must_use]
    pub fn exchange_directory(&self) -> &Path {
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
                if exists_beneath(bound.fd(), source.as_str())? {
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
                Ok(!exists_beneath(bound.fd(), path.as_str())?)
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
        match stat_document_fd(bound.fd(), &WorkspaceTarget::Relative(path.clone())) {
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

        match stat_document_fd(bound.fd(), target) {
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

        let (dir_fd, rel_prefix) = match target {
            WorkspaceTarget::Root => {
                let fd = match open_beneath(
                    bound.fd(),
                    ".",
                    OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
                    Mode::empty(),
                ) {
                    Ok(fd) => fd,
                    Err(err) => return ActionOutcome::Failed(err),
                };
                (fd, String::new())
            }
            WorkspaceTarget::Relative(parent_rel) => {
                let fd = match open_beneath(
                    bound.fd(),
                    parent_rel.as_str(),
                    OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
                    Mode::empty(),
                ) {
                    Ok(fd) => fd,
                    Err(err) => return ActionOutcome::Failed(err),
                };
                (fd, parent_rel.as_str().to_owned())
            }
        };

        let entry_names = match read_sorted_entries(&dir_fd) {
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
            match stat_document_fd(bound.fd(), &child) {
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
        let already_exists = match exists_beneath(bound.fd(), path.as_str()) {
            Ok(e) => e,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if already_exists {
            match stat_document_fd(bound.fd(), &target) {
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
            if let Err(err) = ensure_directory_beneath(bound.fd(), path.as_str()) {
                return ActionOutcome::Failed(err);
            }
            match stat_document_fd(bound.fd(), &target) {
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
                "POSIX handle and requested relative path must identify the same document",
            ));
        }
        let bound = match self.registry.resolve(capability) {
            Ok(b) => b,
            Err(e) => return ActionOutcome::Failed(e),
        };

        let (source_metadata, content) = match read_document_stream(bound.fd(), path) {
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
        let existing_res = stat_document_fd(bound.fd(), &target_ws);

        if let Err(err) = validate_write_mode(mode, expected_target, &existing_res) {
            return ActionOutcome::Failed(err);
        }

        if let Err(err) = ensure_parent_directory_beneath(bound.fd(), path.as_str()) {
            return ActionOutcome::Failed(err);
        }

        let (parent_fd, file_name) = match open_parent_beneath(bound.fd(), path.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let nonce = match generate_random_nonce() {
            Ok(n) => n,
            Err(err) => return ActionOutcome::Failed(err),
        };
        let temp_file_name = format!(".tmp.{file_name}.{nonce}");

        if let Err(err) = write_and_sync_temp_file(&parent_fd, &temp_file_name, &bytes) {
            return ActionOutcome::Failed(err);
        }

        let existing = stat_document_fd(bound.fd(), &target_ws);
        if let Err(error) = validate_write_mode(mode, expected_target, &existing) {
            let diagnostic = cleanup_temp_file(&parent_fd, &temp_file_name, &error.to_string());
            return ActionOutcome::Failed(conflict("platform_postcondition_mismatch", &diagnostic));
        }

        if let Err(err) = commit_temp_file(&parent_fd, &temp_file_name, &file_name, mode) {
            return ActionOutcome::Failed(err);
        }

        if let Err(err) = rustix::fs::fsync(&parent_fd) {
            return ActionOutcome::Failed(storage(
                "parent_dir_fsync_failed",
                &format!("fsync failed on parent directory: {err}"),
            ));
        }

        let metadata = match stat_document_fd(bound.fd(), &target_ws) {
            Ok(m) => m,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if metadata.evidence().digest() != artifact.digest()
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
        let dst_meta = stat_document_fd(bound.fd(), &dst_target);

        let src_target = WorkspaceTarget::Relative(source.clone());
        let src_meta = stat_document_fd(bound.fd(), &src_target);

        if let Err(err) =
            validate_move_preconditions(expected_source, expected_target, &src_meta, &dst_meta)
        {
            return ActionOutcome::Failed(err);
        }

        let (src_parent_fd, src_name) = match open_parent_beneath(bound.fd(), source.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if let Err(err) = ensure_parent_directory_beneath(bound.fd(), target.as_str()) {
            return ActionOutcome::Failed(err);
        }

        let (dst_parent_fd, dst_name) = match open_parent_beneath(bound.fd(), target.as_str()) {
            Ok(p) => p,
            Err(err) => return ActionOutcome::Failed(err),
        };

        let source_now = stat_document_fd(bound.fd(), &src_target);
        let target_now = stat_document_fd(bound.fd(), &dst_target);
        if let Err(error) =
            validate_move_preconditions(expected_source, expected_target, &source_now, &target_now)
        {
            return ActionOutcome::Failed(error);
        }
        let flags = match expected_target {
            ExpectedFingerprint::Absent => rustix::fs::RenameFlags::NOREPLACE,
            ExpectedFingerprint::Match(_) => rustix::fs::RenameFlags::empty(),
        };
        if let Err(err) =
            rustix::fs::renameat_with(&src_parent_fd, &src_name, &dst_parent_fd, &dst_name, flags)
        {
            return ActionOutcome::Failed(storage(
                "move_failed",
                &format!("renameat failed: {err}"),
            ));
        }

        if let Err(err) = rustix::fs::fsync(&src_parent_fd) {
            return ActionOutcome::Failed(storage(
                "fsync_failed",
                &format!("fsync failed on source parent directory: {err}"),
            ));
        }
        if let Err(err) = rustix::fs::fsync(&dst_parent_fd) {
            return ActionOutcome::Failed(storage(
                "fsync_failed",
                &format!("fsync failed on destination parent directory: {err}"),
            ));
        }

        let dst_meta = match stat_document_fd(bound.fd(), &dst_target) {
            Ok(m) => m,
            Err(err) => return ActionOutcome::Failed(err),
        };

        if let ExpectedFingerprint::Match(expected) = expected_source
            && (dst_meta.evidence().digest() != expected.digest()
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
        let meta_res = stat_document_fd(bound.fd(), &target);

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

                let (parent_fd, file_name) = match open_parent_beneath(bound.fd(), path.as_str()) {
                    Ok(p) => p,
                    Err(err) => return ActionOutcome::Failed(err),
                };

                if let Err(err) = remove_entry_beneath(&parent_fd, &file_name) {
                    return ActionOutcome::Failed(err);
                }

                if let Err(err) = rustix::fs::fsync(&parent_fd) {
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

fn cleanup_temp_file(parent_fd: &OwnedFd, temp_name: &str, original_err: &str) -> String {
    match rustix::fs::unlinkat(parent_fd, temp_name, rustix::fs::AtFlags::empty()) {
        Ok(()) => original_err.to_owned(),
        Err(cleanup_err) => format!("{original_err} (cleanup failed: {cleanup_err})"),
    }
}

fn write_and_sync_temp_file(
    parent_fd: &OwnedFd,
    temp_file_name: &str,
    bytes: &[u8],
) -> Result<(), LomoError> {
    let temp_fd = rustix::fs::openat(
        parent_fd,
        temp_file_name,
        OFlags::RDWR | OFlags::CREATE | OFlags::EXCL | OFlags::CLOEXEC,
        Mode::from_bits_truncate(0o600),
    )
    .map_err(|err| {
        storage(
            "create_temp_file_failed",
            &format!("failed to open temp file: {err}"),
        )
    })?;

    let mut temp_file = std::fs::File::from(temp_fd);
    if let Err(err) = temp_file.write_all(bytes) {
        let msg = cleanup_temp_file(
            parent_fd,
            temp_file_name,
            &format!("failed to write temp bytes: {err}"),
        );
        return Err(storage("write_temp_file_failed", &msg));
    }
    if let Err(err) = temp_file.sync_all() {
        let msg = cleanup_temp_file(
            parent_fd,
            temp_file_name,
            &format!("failed to fsync temp file: {err}"),
        );
        return Err(storage("fsync_temp_file_failed", &msg));
    }
    Ok(())
}

fn commit_temp_file(
    parent_fd: &OwnedFd,
    temp_file_name: &str,
    file_name: &str,
    mode: WriteMode,
) -> Result<(), LomoError> {
    if mode == WriteMode::Create {
        let ren_res = rustix::fs::renameat_with(
            parent_fd,
            temp_file_name,
            parent_fd,
            file_name,
            rustix::fs::RenameFlags::NOREPLACE,
        );
        match ren_res {
            Ok(()) => Ok(()),
            Err(Errno::EXIST) => {
                let msg = cleanup_temp_file(
                    parent_fd,
                    temp_file_name,
                    "Create refused because the target already exists",
                );
                Err(conflict("platform_postcondition_mismatch", &msg))
            }
            Err(Errno::NOSYS) => {
                let link_res = rustix::fs::linkat(
                    parent_fd,
                    temp_file_name,
                    parent_fd,
                    file_name,
                    rustix::fs::AtFlags::empty(),
                );
                match rustix::fs::unlinkat(parent_fd, temp_file_name, rustix::fs::AtFlags::empty())
                {
                    Ok(()) => {}
                    Err(err) => {
                        return Err(storage(
                            "unlink_temp_failed",
                            &format!("failed to clean up temp file after link: {err}"),
                        ));
                    }
                }
                match link_res {
                    Ok(()) => Ok(()),
                    Err(Errno::EXIST) => Err(conflict(
                        "platform_postcondition_mismatch",
                        "Create refused because the target already exists",
                    )),
                    Err(err) => Err(storage(
                        "link_file_failed",
                        &format!("linkat failed: {err}"),
                    )),
                }
            }
            Err(err) => {
                let msg = cleanup_temp_file(
                    parent_fd,
                    temp_file_name,
                    &format!("renameat failed: {err}"),
                );
                Err(storage("rename_file_failed", &msg))
            }
        }
    } else if let Err(err) = rustix::fs::renameat(parent_fd, temp_file_name, parent_fd, file_name) {
        let msg = cleanup_temp_file(
            parent_fd,
            temp_file_name,
            &format!("renameat failed: {err}"),
        );
        Err(storage("rename_file_failed", &msg))
    } else {
        Ok(())
    }
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

fn read_sorted_entries(dir_fd: &OwnedFd) -> Result<Vec<String>, LomoError> {
    let mut buf = [MaybeUninit::uninit(); 4096];
    let mut raw_dir = rustix::fs::RawDir::new(dir_fd, &mut buf);
    let mut entry_names = Vec::new();

    while let Some(entry_res) = raw_dir.next() {
        let entry = entry_res.map_err(|err| {
            storage(
                "read_dir_entry_failed",
                &format!("failed to read directory entry: {err}"),
            )
        })?;
        let entry_raw = entry.file_name();
        let entry_name = entry_raw.to_str().map_err(|err| {
            validation(
                "invalid_utf8_filename",
                &format!("non-UTF-8 directory entry: {err}"),
            )
        })?;
        if entry_name == "." || entry_name == ".." {
            continue;
        }
        entry_names.push(entry_name.to_owned());
    }
    entry_names.sort();
    Ok(entry_names)
}

fn ensure_parent_directory_beneath(root_fd: &OwnedFd, path: &str) -> Result<(), LomoError> {
    if let Some((parent, _name)) = path.rsplit_once('/') {
        ensure_directory_beneath(root_fd, parent)?;
    }
    Ok(())
}

fn ensure_directory_beneath(root_fd: &OwnedFd, rel_path: &str) -> Result<OwnedFd, LomoError> {
    let segments: Vec<&str> = rel_path.split('/').filter(|s| !s.is_empty()).collect();
    let mut current_fd = rustix::fs::openat(
        root_fd,
        ".",
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .map_err(|err| storage("open_failed", &format!("failed to dup root fd: {err}")))?;

    for segment in segments {
        if segment == "." || segment == ".." {
            return Err(permission(
                "symlink_escape_rejected",
                &format!("invalid directory segment '{segment}'"),
            ));
        }

        match rustix::fs::openat(
            &current_fd,
            segment,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
            Mode::empty(),
        ) {
            Ok(next_fd) => {
                current_fd = next_fd;
            }
            Err(Errno::NOENT) => {
                rustix::fs::mkdirat(&current_fd, segment, Mode::from_bits_truncate(0o755))
                    .map_err(|err| {
                        storage(
                            "create_directory_failed",
                            &format!("mkdirat failed on '{segment}': {err}"),
                        )
                    })?;
                let next_fd = rustix::fs::openat(
                    &current_fd,
                    segment,
                    OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
                    Mode::empty(),
                )
                .map_err(|err| {
                    storage(
                        "open_failed",
                        &format!("openat failed on new directory '{segment}': {err}"),
                    )
                })?;
                rustix::fs::fsync(&current_fd).map_err(|err| {
                    storage(
                        "fsync_failed",
                        &format!("fsync failed on parent dir: {err}"),
                    )
                })?;
                current_fd = next_fd;
            }
            Err(Errno::LOOP) => {
                return Err(permission(
                    "symlink_escape_rejected",
                    &format!("symlink rejected for segment '{segment}'"),
                ));
            }
            Err(err) => {
                return Err(storage(
                    "open_failed",
                    &format!("failed to open segment '{segment}': {err}"),
                ));
            }
        }
    }

    Ok(current_fd)
}

fn remove_entry_beneath(parent_fd: &OwnedFd, name: &str) -> Result<(), LomoError> {
    let stat = rustix::fs::statat(parent_fd, name, rustix::fs::AtFlags::SYMLINK_NOFOLLOW)
        .map_err(|error| storage("stat_failed", &error.to_string()))?;
    let flags = if rustix::fs::FileType::from_raw_mode(stat.st_mode).is_dir() {
        rustix::fs::AtFlags::REMOVEDIR
    } else {
        rustix::fs::AtFlags::empty()
    };
    rustix::fs::unlinkat(parent_fd, name, flags)
        .map_err(|error| storage("unlink_failed", &error.to_string()))
}

impl PlatformActionExecutor for PosixPlatformActionExecutor {
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
