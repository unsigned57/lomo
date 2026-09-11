//! Behavior Contract — P2-05 multi-phase workspace scan + document-command jobs
//!
//! Capability: drive workspace scan and document commands through the stage-1 single-writer
//! actor using exchange tokens only (no large `ByteArray` bodies across the job boundary). Scan
//! publishes bounded pages (≤256) with an opaque Rust-owned cursor. Document commands fail closed
//! on stale fingerprints and do not double-write on replay / `AlreadySatisfied` postconditions.
//!
//! Scenarios:
//! - Given a Direct workspace with markdown files, when a scan job is driven, then a bounded page
//!   of memo summaries is published with job/workspace-scoped exchange references whose artifacts
//!   contain the complete exact memo content rather than a truncated preview, while intermediate
//!   source artifacts are removed after projection.
//! - Given two workspace sessions whose journals allocate the same job id, when each publishes scan
//!   content, then their opaque tokens differ and reveal neither workspace path.
//! - Given a scan page whose content artifact cannot be published, when the driver advances, then
//!   the whole job fails and no partial page result is observable.
//! - Given the same canonical scan request is already active, when startup asks to refresh again,
//!   then the existing durable job id is returned instead of creating a duplicate scan.
//! - Given a document replace command with a matching fingerprint, when driven, then the file is
//!   rewritten once via write-from-exchange and the result fingerprint matches the pure planner.
//! - Given a successful create/update/remove, when the result is published, then it carries the
//!   Rust-parsed affected memo facts and all intermediate document exchange files are removed.
//! - Given a SAF soft delete, when its platform job completes, then a checksummed workspace trash
//!   record is durable before success while the source document remains byte-identical.
//! - Given a matching trash record, when restore completes, then only the durable marker is removed;
//!   given permanent delete, then source removal is verified before that marker can disappear.
//! - Given durable trash records, when the trash scan is paged, then checksummed Rust-decoded facts
//!   and exact body exchange references are published without Kotlin parsing record bytes.
//! - Given an empty trash directory or more records than one provider listing page, when trash scan
//!   resumes, then it terminates empty or advances to the next provider cursor without repeating an
//!   empty page or losing a record.
//! - Given terminal exchange cleanup is temporarily unavailable after a verified write, when the
//!   platform result is committed, then the job is still durably Completed and a later poll
//!   retries the idempotent cleanup instead of converting success into failure.
//! - Given an external edit after read (stale fingerprint), when the document command advances,
//!   then the job fails with `stale_snapshot` and the on-disk file is unchanged.
//! - Given a completed write whose postcondition is already satisfied, when the same write batch is
//!   replayed with `AlreadySatisfied`, then no second mutating plan is emitted.
//! - Given one listed page contains multiple Markdown files, when the scan plans independent reads,
//!   then those reads share one bounded platform batch instead of one durable round trip per file.
//! - Given a workspace scan starts, when it requests a provider listing page, then it uses the
//!   protocol maximum of 256 documents before partitioning independent reads into bounded batches.
//! - Given one file with 257+ memos or a page boundary inside a later file, when scan resumes from
//!   its opaque cursor, then every memo is emitted exactly once in file order.
//! - Given a cursor that points inside a file, when that file changes before resume, then scan fails
//!   closed with `stale_snapshot` rather than skipping or duplicating memos.
//! - Given a memo containing a task, when its scan summary is published, then the exact body byte
//!   span is included so a UI-relative typed action span can be translated without line parsing.
//! - Given a memo containing duplicate reminder tokens, when its scan summary is published, then
//!   each occurrence carries distinct revision/span/token-fingerprint identity and typed facts.
//! - Given a reminder reference from scan, when a rewrite command is driven, then only that exact
//!   occurrence changes; a tampered or stale reference fails closed before any write.
//!
//! Observable outcomes: job steps, durable `read_job_result` JSON, exact exchange artifact bytes,
//! opaque token scope, on-disk file bytes, bounded read-batch width, write counts.
//! TDD proof: RED on 2026-08-06 because every cold restore allocated a new workspace scan even
//! while an identical `WaitingPlatform` job remained durable in the same workspace journal.
//! TDD proof: RED on 2026-08-06 because scan-read exchange artifacts survived after the driver had
//! parsed them and published the only durable memo-body references.
//! TDD proof: RED on 2026-08-09 because a trash cursor whose listed records were fully consumed but
//! whose provider cursor had a next page returned the same empty cursor forever.
//! TDD proof: RED on 2026-08-25 because a scan planned exactly one `ReadToExchange` action per
//! durable batch, making a 217-file SAF workspace pay hundreds of serial actor/provider round trips.
//! TDD proof: RED on 2026-08-25 because the scan still requested 63-document provider pages after
//! independent reads had acquired their own 63-action batch boundary.
//! Excludes: `BoltFFI` generation (P2-06), production DI dual-stack (P2-09), Kotlin IR presentation.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use sha2::{Digest, Sha256};
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::path::{Path, PathBuf};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use lomo_core::{
        ActionEvidence, ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, EngineConfig,
        ErrorCategory, ExchangeArtifact, ExpectedFingerprint, JobStep, LomoEngine, MetadataPage,
        PlatformAction, PlatformActionBatch, PlatformActionOutput, PlatformBatchResult,
        RetryDisposition, Sha256Digest, VerifiedAbsence, WorkspaceDescriptor, WorkspaceTarget,
        WriteMode,
    };
    use lomo_workspace::{
        DOCUMENT_COMMAND_DRIVER_KIND, DocumentCommandKind, DocumentCommandRequest,
        DocumentCommandResult, DocumentExpectedState, DocumentHistoryWrite,
        HISTORY_SCAN_DRIVER_KIND, HistoryScanPage, HistoryScanRequest, HistorySnapshotV1,
        LomoPayload, LomoRecordKind, SCAN_DRIVER_KIND, SourceFingerprint,
        TRASH_COMMAND_DRIVER_KIND, TRASH_SCAN_DRIVER_KIND, TrashCommandKind, TrashCommandRequest,
        TrashCommandResult, TrashMemoSummary, TrashRecordCreate, TrashRecordV1, TrashScanPage,
        TrashScanRequest, WorkspaceScanRequest, decode_record, decode_trash_record, encode_record,
        encode_trash_record, trash_record_relative_path, workspace_driver_registry,
    };
    use tempfile::tempdir;

    // Local helper: fingerprint of bytes using the same constructor as production.
    fn fingerprint_of(bytes: &[u8]) -> String {
        SourceFingerprint::of_bytes(bytes).as_str().to_owned()
    }

    fn build_memo_source(time_part: &str, prefix: &str, count: usize) -> String {
        use std::fmt::Write as _;

        (0..count).fold(String::new(), |mut source, index| {
            write!(source, "- {time_part}\n{prefix}-{index}\n").test_ok("append memo fixture");
            source
        })
    }

    struct Harness {
        _temporary: tempfile::TempDir,
        workspace_root: PathBuf,
        exchange_root: PathBuf,
        engine: Arc<LomoEngine>,
        write_count: Arc<AtomicUsize>,
    }

    impl Harness {
        fn new() -> Self {
            let temporary = tempdir().test_ok("temp");
            let control = temporary.path().join("control");
            let exchange = temporary.path().join("exchange");
            let workspace = temporary.path().join("workspace");
            fs::create_dir_all(&control).test_ok("control");
            fs::create_dir_all(&exchange).test_ok("exchange");
            fs::create_dir_all(&workspace).test_ok("workspace");
            let config = EngineConfig::new(
                control,
                exchange.clone(),
                Some(WorkspaceDescriptor::direct(&workspace).test_ok("direct")),
            )
            .test_ok("config")
            .with_drivers(workspace_driver_registry());
            let engine = LomoEngine::open(config).test_ok("engine");
            // Direct bootstrap completes immediately to Ready.
            assert!(
                matches!(engine.state(), lomo_core::EngineState::Ready { .. }),
                "direct engine must be Ready, got {:?}",
                engine.state()
            );
            Self {
                _temporary: temporary,
                workspace_root: workspace,
                exchange_root: exchange,
                engine,
                write_count: Arc::new(AtomicUsize::new(0)),
            }
        }

        fn write_file(&self, relative: &str, bytes: &[u8]) {
            let path = self.workspace_root.join(relative);
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent).test_ok("parent");
            }
            fs::write(path, bytes).test_ok("write file");
        }

        fn read_file(&self, relative: &str) -> Vec<u8> {
            fs::read(self.workspace_root.join(relative)).test_ok("read file")
        }

        fn read_exchange_token(&self, token: &str) -> Vec<u8> {
            fs::read(self.exchange_root.join(token)).test_ok("read exchange token")
        }

        fn terminal_after_batch(step: &JobStep) -> bool {
            !matches!(step, JobStep::NeedsPlatformBatch { .. } | JobStep::Running)
        }

        fn guard_create_target(full: &Path) -> Option<ActionOutcome> {
            if full.exists() {
                Some(ActionOutcome::Failed(
                    lomo_core::LomoError::from_platform_boundary(
                        ErrorCategory::Conflict,
                        "target_already_exists",
                        RetryDisposition::AfterUserAction,
                        None,
                        None,
                        "Create target already exists",
                    )
                    .test_ok("error"),
                ))
            } else {
                None
            }
        }

        fn verify_current_target(
            current_bytes: &[u8],
            expected: &ActionEvidence,
        ) -> Option<ActionOutcome> {
            let current_digest = format!("{:x}", Sha256::digest(current_bytes));
            if current_digest == expected.digest().as_str() {
                return None;
            }
            Some(ActionOutcome::Failed(
                lomo_core::LomoError::from_platform_boundary(
                    ErrorCategory::Validation,
                    "postcondition_mismatch",
                    RetryDisposition::Never,
                    None,
                    None,
                    "Target fingerprint does not match the expected postcondition",
                )
                .test_ok("error"),
            ))
        }

        fn execute_batch(&self, batch: &PlatformActionBatch) -> Vec<ActionResult> {
            batch
                .actions()
                .iter()
                .map(|action| ActionResult::new(action.id().clone(), self.execute(action)))
                .collect()
        }

        fn drive_until_terminal(&self, job_id: &lomo_core::JobId) -> JobStep {
            let mut guard = 0;
            loop {
                guard += 1;
                assert!(guard < 64, "job did not terminate");
                let step = self.engine.poll_job(job_id).test_ok("poll");
                match step {
                    JobStep::NeedsPlatformBatch { batch } => {
                        let results = self.execute_batch(&batch);
                        let result = PlatformBatchResult::new(
                            batch.schema_version(),
                            batch.job_id().clone(),
                            batch.batch_id().clone(),
                            batch.attempt(),
                            results,
                        );
                        let after = self
                            .engine
                            .submit_platform_result(job_id, result)
                            .test_ok("submit");
                        if Self::terminal_after_batch(&after) {
                            return after;
                        }
                    }
                    JobStep::Running | JobStep::RunningNative { .. } => {}
                    JobStep::BlockedByConflict { .. }
                    | JobStep::Completed
                    | JobStep::Failed { .. } => return step,
                }
            }
        }

        fn scan_page(
            &self,
            page_size: u32,
            cursor: Option<String>,
        ) -> Result<lomo_workspace::WorkspaceScanPage, lomo_core::LomoError> {
            let request = WorkspaceScanRequest {
                page_size,
                cursor,
                root_path: None,
            };
            let request_json = serde_json::to_string(&request).test_ok("request");
            let job_id = self.engine.start_user_job(
                SCAN_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )?;
            let terminal = self.drive_until_terminal(&job_id);
            if let JobStep::Failed { error } = terminal {
                return Err(error);
            }
            let result = self.engine.read_job_result(&job_id)?.ok_or_else(|| {
                lomo_core::LomoError::from_platform_boundary(
                    ErrorCategory::Internal,
                    "scan_result_missing",
                    RetryDisposition::Never,
                    None,
                    None,
                    "scan completed without a page",
                )
                .test_ok("static test error")
            })?;
            serde_json::from_str(&result).map_err(|_error| {
                lomo_core::LomoError::from_platform_boundary(
                    ErrorCategory::Corruption,
                    "scan_result_invalid",
                    RetryDisposition::Never,
                    None,
                    None,
                    "scan result is not a page",
                )
                .test_ok("static test error")
            })
        }

        fn trash_command(&self, request: &TrashCommandRequest) -> TrashCommandResult {
            let request_json = serde_json::to_string(request).test_ok("trash request");
            let job_id = self
                .engine
                .start_user_job(
                    TRASH_COMMAND_DRIVER_KIND,
                    &request_json,
                    Duration::from_secs(30),
                )
                .test_ok("start trash command");
            let terminal = self.drive_until_terminal(&job_id);
            assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
            let payload = self
                .engine
                .read_job_result(&job_id)
                .test_ok("trash result")
                .test_ok("trash payload");
            serde_json::from_str(&payload).test_ok("decode trash result")
        }

        fn trash_scan_page(&self, page_size: u32, cursor: Option<String>) -> TrashScanPage {
            let request = TrashScanRequest { page_size, cursor };
            let request_json = serde_json::to_string(&request).test_ok("trash scan request");
            let job_id = self
                .engine
                .start_user_job(
                    TRASH_SCAN_DRIVER_KIND,
                    &request_json,
                    Duration::from_secs(30),
                )
                .test_ok("start trash scan");
            let terminal = self.drive_until_terminal(&job_id);
            assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
            let payload = self
                .engine
                .read_job_result(&job_id)
                .test_ok("trash scan result")
                .test_ok("trash scan payload");
            serde_json::from_str(&payload).test_ok("decode trash scan page")
        }

        fn write_trash_record(&self, record: &TrashRecordV1) {
            let path = trash_record_relative_path(&record.memo_id).test_ok("trash record path");
            let bytes = encode_trash_record(record).test_ok("encode trash record");
            self.write_file(path.as_str(), &bytes);
        }

        fn execute(&self, action: &PlatformAction) -> ActionOutcome {
            match action {
                PlatformAction::ListChildren { .. } => self.execute_list_children(action),
                PlatformAction::ReadToExchange { .. } => self.execute_read_to_exchange(action),
                PlatformAction::WriteFromExchange { .. } => {
                    self.execute_write_from_exchange(action)
                }
                PlatformAction::EnsureDirectory { .. } => self.execute_ensure_directory(action),
                PlatformAction::Delete { .. } => self.execute_delete(action),
                PlatformAction::Move { .. } => self.execute_move(action),
                PlatformAction::Stat { .. } => panic!("unexpected action in harness: {action:?}"),
            }
        }

        /// Mirrors the SAF access replay law: source absent + target present is an already
        /// satisfied rename; a present source with an occupied target fails closed.
        fn execute_move(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::Move {
                source,
                target,
                expected_source,
                ..
            } = action
            else {
                panic!("move helper received non-move action: {action:?}");
            };
            let move_metadata = |path: &lomo_core::RelativeWorkspacePath, bytes: &[u8]| {
                let digest = format!("{:x}", Sha256::digest(bytes));
                DocumentMetadata::new(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::File,
                    None,
                    ActionEvidence::verified(
                        bytes.len() as u64,
                        Sha256Digest::parse(&digest).test_ok("digest"),
                        &format!("fp.{}", path.as_str().replace('/', ".")),
                    )
                    .test_ok("evidence"),
                )
                .test_ok("metadata")
            };
            let from = self.workspace_root.join(source.as_str());
            if !from.exists() {
                let bytes = fs::read(self.workspace_root.join(target.as_str()))
                    .test_ok("replayed move target bytes");
                return ActionOutcome::AlreadySatisfied(PlatformActionOutput::MoveComplete {
                    metadata: move_metadata(target, &bytes),
                });
            }
            if let ExpectedFingerprint::Match(expected) = expected_source {
                let bytes = fs::read(&from).test_ok("move source bytes");
                if let Some(failure) = Self::verify_current_target(&bytes, expected) {
                    return failure;
                }
            }
            let to = self.workspace_root.join(target.as_str());
            if to.exists() {
                return ActionOutcome::Failed(
                    lomo_core::LomoError::from_platform_boundary(
                        ErrorCategory::Conflict,
                        "move_target_exists",
                        RetryDisposition::AfterUserAction,
                        None,
                        None,
                        "Move target already exists",
                    )
                    .test_ok("error"),
                );
            }
            let bytes = fs::read(&from).test_ok("move source bytes");
            fs::rename(&from, &to).test_ok("rename to the durable filename law");
            ActionOutcome::Applied(PlatformActionOutput::MoveComplete {
                metadata: move_metadata(target, &bytes),
            })
        }

        fn execute_write_from_exchange(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::WriteFromExchange {
                artifact,
                path,
                mode,
                expected_target,
                ..
            } = action
            else {
                panic!("write helper received non-write action: {action:?}");
            };
            let bytes = fs::read(self.exchange_root.join(artifact.token().as_str()))
                .test_ok("read exchange write artifact");
            let digest = format!("{:x}", Sha256::digest(&bytes));
            assert_eq!(digest, artifact.digest().as_str(), "artifact digest");
            let full = self.workspace_root.join(path.as_str());
            if let Some(outcome) = Self::guard_write_target(&full, *mode, expected_target) {
                return outcome;
            }
            self.write_count.fetch_add(1, Ordering::SeqCst);
            fs::write(&full, &bytes).test_ok("write target");
            let evidence = ActionEvidence::verified(
                bytes.len() as u64,
                Sha256Digest::parse(&digest).test_ok("digest"),
                &format!("fp.{}", path.as_str().replace('/', ".")),
            )
            .test_ok("evidence");
            ActionOutcome::Applied(PlatformActionOutput::WriteComplete {
                metadata: DocumentMetadata::new(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::File,
                    None,
                    evidence,
                )
                .test_ok("metadata"),
            })
        }

        fn guard_write_target(
            full: &Path,
            mode: WriteMode,
            expected_target: &ExpectedFingerprint,
        ) -> Option<ActionOutcome> {
            if mode == WriteMode::Create {
                assert!(
                    matches!(expected_target, ExpectedFingerprint::Absent),
                    "create must carry an absent target precondition"
                );
                return Self::guard_create_target(full);
            }
            let ExpectedFingerprint::Match(expected) = expected_target else {
                return None;
            };
            let current = match fs::read(full) {
                Ok(bytes) => Some(bytes),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
                Err(error) => panic!("failed to read expected target: {error}"),
            };
            current
                .as_deref()
                .and_then(|bytes| Self::verify_current_target(bytes, expected))
        }

        fn execute_ensure_directory(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::EnsureDirectory { path, .. } = action else {
                panic!("directory helper received non-directory action: {action:?}");
            };
            fs::create_dir_all(self.workspace_root.join(path.as_str())).test_ok("ensure directory");
            let digest = format!("{:x}", Sha256::digest([]));
            let evidence = ActionEvidence::verified(
                0,
                Sha256Digest::parse(&digest).test_ok("directory digest"),
                &format!("fp.dir.{}", path.as_str().replace('/', ".")),
            )
            .test_ok("directory evidence");
            ActionOutcome::Applied(PlatformActionOutput::DirectoryReady {
                metadata: DocumentMetadata::new(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::Directory,
                    None,
                    evidence,
                )
                .test_ok("directory metadata"),
            })
        }

        fn execute_delete(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::Delete {
                path,
                expected_target,
                ..
            } = action
            else {
                panic!("delete helper received non-delete action: {action:?}");
            };
            let full = self.workspace_root.join(path.as_str());
            if let Some(outcome) = Self::guard_delete_target(&full, expected_target) {
                return outcome;
            }
            if full.exists() {
                fs::remove_file(&full).test_ok("delete target");
            }
            let fingerprint = match expected_target {
                ExpectedFingerprint::Match(expected) => expected.fingerprint(),
                ExpectedFingerprint::Absent => "absent.delete",
            };
            ActionOutcome::Applied(PlatformActionOutput::DeleteComplete {
                absence: VerifiedAbsence::new(WorkspaceTarget::Relative(path.clone()), fingerprint)
                    .test_ok("verified absence"),
            })
        }

        fn guard_delete_target(
            full: &Path,
            expected_target: &ExpectedFingerprint,
        ) -> Option<ActionOutcome> {
            if !full.exists() {
                return None;
            }
            let ExpectedFingerprint::Match(expected) = expected_target else {
                return None;
            };
            let current = fs::read(full).test_ok("read delete target");
            Self::verify_current_target(&current, expected)
        }

        fn execute_list_children(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::ListChildren {
                target,
                page_size,
                cursor,
                ..
            } = action
            else {
                panic!("list helper received non-list action: {action:?}");
            };
            let dir = match target {
                WorkspaceTarget::Root => self.workspace_root.clone(),
                WorkspaceTarget::Relative(path) => self.workspace_root.join(path.as_str()),
            };
            let mut names: Vec<String> = fs::read_dir(&dir)
                .test_ok("list")
                .map(|entry| {
                    entry
                        .test_ok("entry")
                        .file_name()
                        .to_string_lossy()
                        .into_owned()
                })
                .collect();
            names.sort();
            let start = cursor
                .as_ref()
                .and_then(|value| {
                    names
                        .iter()
                        .position(|name| name == value)
                        .map(|index| index + 1)
                })
                .unwrap_or(0);
            let end = (start + page_size.get() as usize).min(names.len());
            let slice = names.get(start..end).unwrap_or(&[]);
            let next = (end < names.len()).then(|| {
                names
                    .get(end - 1)
                    .map(String::as_str)
                    .expect("page end implies last entry")
            });
            let items = slice
                .iter()
                .map(|name| self.metadata_for_child(target, name))
                .collect();
            ActionOutcome::Applied(PlatformActionOutput::Listed {
                page: MetadataPage::new(items, next).test_ok("page"),
            })
        }

        fn execute_read_to_exchange(&self, action: &PlatformAction) -> ActionOutcome {
            let PlatformAction::ReadToExchange {
                path,
                locator,
                exchange_token,
                ..
            } = action
            else {
                panic!("read helper received non-read action: {action:?}");
            };
            let source_identity = match locator {
                lomo_core::DocumentLocator::Path(source_path) => source_path.as_str(),
                lomo_core::DocumentLocator::Opaque(document_handle) => document_handle.as_str(),
            };
            let bytes = fs::read(self.workspace_root.join(source_identity))
                .test_ok("read source by locator");
            let digest = {
                use sha2::{Digest, Sha256};
                format!("{:x}", Sha256::digest(&bytes))
            };
            let exchange_path = self.exchange_root.join(exchange_token.as_str());
            if let Some(parent) = exchange_path.parent() {
                fs::create_dir_all(parent).test_ok("exchange parent");
            }
            fs::write(&exchange_path, &bytes).test_ok("write exchange");
            let evidence = ActionEvidence::verified(
                bytes.len() as u64,
                Sha256Digest::parse(&digest).test_ok("digest"),
                &format!("fp.{}", path.as_str().replace('/', ".")),
            )
            .test_ok("evidence");
            ActionOutcome::Applied(PlatformActionOutput::ReadToExchange {
                source_metadata: match locator {
                    lomo_core::DocumentLocator::Path(_) => DocumentMetadata::new(
                        WorkspaceTarget::Relative(path.clone()),
                        DocumentKind::File,
                        None,
                        evidence,
                    ),
                    lomo_core::DocumentLocator::Opaque(document_handle) => {
                        DocumentMetadata::new_with_handle(
                            WorkspaceTarget::Relative(path.clone()),
                            document_handle.clone(),
                            DocumentKind::File,
                            None,
                            evidence,
                        )
                    }
                }
                .test_ok("metadata"),
                artifact: ExchangeArtifact::new(
                    exchange_token.as_str(),
                    bytes.len() as u64,
                    Sha256Digest::parse(&digest).test_ok("digest"),
                )
                .test_ok("artifact"),
            })
        }

        fn metadata_for_child(&self, target: &WorkspaceTarget, name: &str) -> DocumentMetadata {
            let relative = match target {
                WorkspaceTarget::Root => name.to_owned(),
                WorkspaceTarget::Relative(path) => format!("{}/{name}", path.as_str()),
            };
            let full = self.workspace_root.join(&relative);
            let metadata = fs::metadata(&full).test_ok("meta");
            let kind = if metadata.is_dir() {
                DocumentKind::Directory
            } else {
                DocumentKind::File
            };
            let bytes = if metadata.is_file() {
                fs::read(&full).test_ok("read listed file")
            } else {
                Vec::new()
            };
            let digest = {
                use sha2::{Digest, Sha256};
                Sha256Digest::parse(&format!("{:x}", Sha256::digest(&bytes))).test_ok("digest")
            };
            let evidence = ActionEvidence::verified(
                bytes.len() as u64,
                digest,
                &format!("fp.{}", relative.replace('/', ".")),
            )
            .test_ok("evidence");
            DocumentMetadata::new_with_handle(
                WorkspaceTarget::Relative(
                    lomo_core::RelativeWorkspacePath::parse(&relative).test_ok("path"),
                ),
                lomo_core::DocumentHandle::parse(&relative).test_ok("document handle"),
                kind,
                None,
                evidence,
            )
            .test_ok("metadata")
        }
    }

    #[test]
    fn scan_publishes_bounded_page_without_shipping_file_bodies() {
        let harness = Harness::new();
        let body = b"- 10:00:00\nhello #tag\n";
        harness.write_file("2024-01-01.md", body);
        harness.write_file("notes.txt", b"ignore me");

        let request = WorkspaceScanRequest {
            page_size: 16,
            cursor: None,
            root_path: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start scan");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(
            matches!(terminal, JobStep::Completed),
            "scan must complete, got {terminal:?}"
        );
        let result = harness
            .engine
            .read_job_result(&job_id)
            .test_ok("result")
            .test_ok("scan page");
        let page: lomo_workspace::WorkspaceScanPage =
            serde_json::from_str(&result).test_ok("page json");
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items.first().expect("item").path, "2024-01-01.md");
        assert!(
            page.items
                .first()
                .expect("item")
                .identity
                .contains("10:00:00")
        );
        assert!(
            page.items
                .first()
                .expect("item")
                .tags
                .iter()
                .any(|t| t == "tag")
        );
        assert!(page.next_cursor.is_none());
        assert_eq!(page.items.first().expect("item").body_start, 11);
        assert_eq!(page.items.first().expect("item").body_end, 22);
    }

    #[test]
    fn identical_active_scan_request_reuses_the_durable_job() {
        let harness = Harness::new();
        let request_json = serde_json::to_string(&WorkspaceScanRequest {
            page_size: 63,
            cursor: None,
            root_path: None,
        })
        .test_ok("request");

        let first = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start first scan");
        let second = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("resume existing scan");

        assert_eq!(second, first);
    }

    #[test]
    fn scan_read_reuses_the_opaque_handle_returned_by_listing() {
        let harness = Harness::new();
        harness.write_file("2024-01-09.md", b"- 10:00:00\nhello\n");
        let request_json = serde_json::to_string(&WorkspaceScanRequest {
            page_size: 16,
            cursor: None,
            root_path: None,
        })
        .test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start scan");
        let JobStep::NeedsPlatformBatch { batch: list_batch } =
            harness.engine.poll_job(&job_id).test_ok("poll list")
        else {
            panic!("expected list batch");
        };
        let list_results = list_batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let read_step = harness
            .engine
            .submit_platform_result(
                &job_id,
                PlatformBatchResult::new(
                    list_batch.schema_version(),
                    list_batch.job_id().clone(),
                    list_batch.batch_id().clone(),
                    list_batch.attempt(),
                    list_results,
                ),
            )
            .test_ok("submit list");
        let JobStep::NeedsPlatformBatch { batch: read_batch } = read_step else {
            panic!("expected read batch");
        };
        let PlatformAction::ReadToExchange { locator, .. } =
            read_batch.actions().first().expect("read action")
        else {
            panic!("expected read action");
        };
        assert_eq!(
            locator,
            &lomo_core::DocumentLocator::Opaque(
                lomo_core::DocumentHandle::parse("2024-01-09.md").test_ok("handle"),
            )
        );
    }

    #[test]
    fn scan_batches_independent_file_reads_from_one_listing_page() {
        let harness = Harness::new();
        for day in 1..=4 {
            harness.write_file(
                &format!("2024-01-{day:02}.md"),
                format!("- 10:00:00\nmemo {day}\n").as_bytes(),
            );
        }
        let request_json = serde_json::to_string(&WorkspaceScanRequest {
            page_size: 16,
            cursor: None,
            root_path: None,
        })
        .test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start scan");
        let JobStep::NeedsPlatformBatch { batch: list_batch } =
            harness.engine.poll_job(&job_id).test_ok("poll list")
        else {
            panic!("expected list batch");
        };
        let list_results = list_batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let read_step = harness
            .engine
            .submit_platform_result(
                &job_id,
                PlatformBatchResult::new(
                    list_batch.schema_version(),
                    list_batch.job_id().clone(),
                    list_batch.batch_id().clone(),
                    list_batch.attempt(),
                    list_results,
                ),
            )
            .test_ok("submit list");
        let JobStep::NeedsPlatformBatch { batch: read_batch } = read_step else {
            panic!("expected read batch");
        };

        assert_eq!(read_batch.actions().len(), 4);
        assert!(
            read_batch
                .actions()
                .iter()
                .all(|action| matches!(action, PlatformAction::ReadToExchange { .. }))
        );
    }

    #[test]
    fn scan_uses_protocol_max_listing_page_before_partitioning_reads() {
        let harness = Harness::new();
        let request_json = serde_json::to_string(&WorkspaceScanRequest {
            page_size: 256,
            cursor: None,
            root_path: None,
        })
        .test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start scan");
        let JobStep::NeedsPlatformBatch { batch: list_batch } =
            harness.engine.poll_job(&job_id).test_ok("poll list")
        else {
            panic!("expected list batch");
        };
        let [PlatformAction::ListChildren { page_size, .. }] = list_batch.actions() else {
            panic!("expected one list action, got {:?}", list_batch.actions());
        };

        assert_eq!(page_size.get(), 256);
    }

    #[test]
    fn scan_content_reference_resolves_the_complete_exact_memo_body() {
        let harness = Harness::new();
        let content = format!("prefix-{}-suffix", "界🙂".repeat(180));
        let source = format!("- 10:00:00\n{content}\n");
        harness.write_file("2024-01-05.md", source.as_bytes());

        let page = harness.scan_page(16, None).test_ok("scan page");
        assert_eq!(page.items.len(), 1);
        let reference = &page.items.first().expect("item").content;
        let artifact = harness.read_exchange_token(&reference.exchange_token);

        assert_eq!(artifact, content.as_bytes());
        assert_eq!(reference.length, content.len() as u64);
        assert_eq!(reference.digest, fingerprint_of(content.as_bytes()));
        assert!(!reference.exchange_token.contains("2024-01-05.md"));
        assert!(!reference.exchange_token.contains('/'));
        assert!(!reference.exchange_token.contains(".."));
        let artifacts = fs::read_dir(&harness.exchange_root)
            .test_ok("exchange directory")
            .map(|entry| entry.test_ok("exchange entry").file_name())
            .collect::<Vec<_>>();
        assert_eq!(artifacts, vec![reference.exchange_token.as_str()]);
    }

    #[test]
    fn scan_content_tokens_are_scoped_across_workspace_sessions() {
        let first = Harness::new();
        let second = Harness::new();
        first.write_file("2024-01-06.md", b"- 10:00:00\nsame\n");
        second.write_file("2024-01-06.md", b"- 10:00:00\nsame\n");

        let first_page = first.scan_page(16, None).test_ok("first page");
        let second_page = second.scan_page(16, None).test_ok("second page");
        let first_reference = &first_page.items.first().expect("item").content;
        let second_reference = &second_page.items.first().expect("item").content;

        assert_ne!(
            first_reference.exchange_token,
            second_reference.exchange_token
        );
        assert_eq!(
            first.read_exchange_token(&first_reference.exchange_token),
            b"same"
        );
        assert_eq!(
            second.read_exchange_token(&second_reference.exchange_token),
            b"same"
        );
    }

    #[test]
    fn scan_content_artifact_failure_publishes_no_partial_page() {
        let harness = Harness::new();
        harness.write_file("2024-01-07.md", b"- 10:00:00\none\n- 11:00:00\ntwo\n");
        let request_json = serde_json::to_string(&WorkspaceScanRequest {
            page_size: 16,
            cursor: None,
            root_path: None,
        })
        .test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(SCAN_DRIVER_KIND, &request_json, Duration::from_secs(30))
            .test_ok("start scan");

        let list_step = harness.engine.poll_job(&job_id).test_ok("poll list");
        let JobStep::NeedsPlatformBatch { batch: list_batch } = list_step else {
            panic!("expected list batch");
        };
        let list_results = list_batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let read_step = harness
            .engine
            .submit_platform_result(
                &job_id,
                PlatformBatchResult::new(
                    list_batch.schema_version(),
                    list_batch.job_id().clone(),
                    list_batch.batch_id().clone(),
                    list_batch.attempt(),
                    list_results,
                ),
            )
            .test_ok("submit list");
        let JobStep::NeedsPlatformBatch { batch: read_batch } = read_step else {
            panic!("expected read batch");
        };
        let read_action = read_batch.actions().first().expect("action");
        let PlatformAction::ReadToExchange { exchange_token, .. } = read_action else {
            panic!("expected read-to-exchange action");
        };
        let read_outcome = harness.execute(read_action);
        let scope = exchange_token
            .as_str()
            .strip_suffix(".scan-0-0")
            .test_ok("scan read token scope");
        fs::create_dir(harness.exchange_root.join(format!("{scope}.memo-0")))
            .test_ok("block content artifact path");
        let failed = harness.engine.submit_platform_result(
            &job_id,
            PlatformBatchResult::new(
                read_batch.schema_version(),
                read_batch.job_id().clone(),
                read_batch.batch_id().clone(),
                read_batch.attempt(),
                vec![ActionResult::new(read_action.id().clone(), read_outcome)],
            ),
        );

        match failed {
            Ok(JobStep::Failed { error }) | Err(error) => {
                assert_eq!(error.code(), "exchange_write_failed");
            }
            other => panic!("content artifact failure must fail the job, got {other:?}"),
        }
        assert!(
            harness
                .engine
                .read_job_result(&job_id)
                .test_ok("read failed result")
                .is_none(),
            "a failed artifact write must not publish a partial page"
        );
    }

    #[test]
    fn scan_cursor_resumes_inside_a_single_file_without_loss_or_duplicates() {
        let harness = Harness::new();
        let source = build_memo_source("10:00:00", "memo", 300);
        harness.write_file("2024-02-01.md", source.as_bytes());

        let first = harness.scan_page(256, None).test_ok("first page");
        assert_eq!(first.items.len(), 256);
        let cursor = first.next_cursor.clone().test_ok("cursor within file");
        let second = harness.scan_page(256, Some(cursor)).test_ok("second page");
        assert_eq!(second.items.len(), 44);
        assert!(second.next_cursor.is_none());

        let identities: Vec<_> = first
            .items
            .iter()
            .chain(&second.items)
            .map(|item| item.identity.clone())
            .collect();
        assert_eq!(identities.len(), 300);
        let unique: std::collections::BTreeSet<_> = identities.iter().collect();
        assert_eq!(unique.len(), 300);
        let content_tokens: std::collections::BTreeSet<_> = first
            .items
            .iter()
            .chain(&second.items)
            .map(|item| item.content.exchange_token.as_str())
            .collect();
        assert_eq!(content_tokens.len(), 300);
        assert_eq!(
            harness
                .read_exchange_token(&first.items.get(255).expect("item").content.exchange_token),
            b"memo-255"
        );
        assert_eq!(
            harness
                .read_exchange_token(&second.items.first().expect("item").content.exchange_token),
            b"memo-256"
        );
        assert_eq!(
            identities.first().map(String::as_str),
            Some("2024-02-01_10:00:00_0")
        );
        assert_eq!(
            identities.last().map(String::as_str),
            Some("2024-02-01_10:00:00_299")
        );
    }

    #[test]
    fn scan_cursor_preserves_the_memo_offset_across_a_file_boundary() {
        let harness = Harness::new();
        let first_source = build_memo_source("09:00:00", "a", 200);
        let second_source = build_memo_source("10:00:00", "b", 100);
        harness.write_file("2024-02-01.md", first_source.as_bytes());
        harness.write_file("2024-02-02.md", second_source.as_bytes());

        let first = harness.scan_page(256, None).test_ok("first page");
        assert_eq!(first.items.len(), 256);
        let second = harness
            .scan_page(256, first.next_cursor)
            .test_ok("second page");
        assert_eq!(second.items.len(), 44);
        assert_eq!(
            second.items.first().expect("item").identity,
            "2024-02-02_10:00:00_56"
        );
        assert_eq!(
            second.items.get(43).expect("item").identity,
            "2024-02-02_10:00:00_99"
        );
        assert!(second.next_cursor.is_none());
    }

    #[test]
    fn scan_cursor_fails_stale_when_the_partially_emitted_file_changes() {
        let harness = Harness::new();
        let source = build_memo_source("10:00:00", "memo", 300);
        harness.write_file("2024-02-03.md", source.as_bytes());
        let first = harness.scan_page(256, None).test_ok("first page");
        let cursor = first.next_cursor.test_ok("cursor within file");

        harness.write_file(
            "2024-02-03.md",
            format!("- 08:00:00\nexternal\n{source}").as_bytes(),
        );
        let error = harness
            .scan_page(256, Some(cursor))
            .test_err("changed file must stale cursor");
        assert_eq!(error.code(), "stale_snapshot");
    }

    #[test]
    fn scan_projects_distinct_typed_reminder_references() {
        let harness = Harness::new();
        let token = "@2026-07-20-09:30x3i15rw.done";
        let source = format!("- 10:00:00\nfirst {token} then {token}\n");
        harness.write_file("2026-07-20.md", source.as_bytes());

        let page = harness.scan_page(16, None).test_ok("scan page");
        let reminders = &page.items.first().expect("item").reminders;

        assert_eq!(reminders.len(), 2);
        assert_ne!(
            reminders.first().expect("r0").opaque_id,
            reminders.get(1).expect("r1").opaque_id
        );
        assert_ne!(
            reminders.first().expect("r0").source_start,
            reminders.get(1).expect("r1").source_start
        );
        for reminder in reminders {
            assert_eq!(reminder.revision, fingerprint_of(source.as_bytes()));
            assert_eq!(reminder.memo_identity, "2026-07-20_10:00:00_0");
            assert_eq!(reminder.token, token);
            assert_eq!(reminder.token_fingerprint, fingerprint_of(token.as_bytes()));
            assert_eq!(reminder.due_at_local, "2026-07-20-09:30");
            assert_eq!(reminder.repeat_count, 3);
            assert_eq!(reminder.fired_count, 0);
            assert_eq!(reminder.interval_minutes, 15);
            assert_eq!(reminder.recurrence_code, "w");
            assert!(reminder.done);
            assert!(reminder.source_end > reminder.source_start);
        }
    }

    #[test]
    fn document_append_remove_and_toggle_task_are_byte_local() {
        let harness = Harness::new();
        let original = b"- 09:00:00\n- [ ] todo item\n\n- 10:00:00\nkeep me\n";
        harness.write_file("2024-02-01.md", original);
        let expected = fingerprint_of(original);

        // Toggle resolves the marker inside the first memo body from its body-relative span.
        let task_marker = b"[ ]";
        let task_start = original
            .windows(task_marker.len())
            .position(|w| w == task_marker)
            .expect("task marker");
        let body_start = b"- 09:00:00\n".len();
        let task_start_relative = (task_start - body_start) as u64;
        let task_end_relative = task_start_relative + task_marker.len() as u64;

        let toggle = DocumentCommandRequest {
            path: "2024-02-01.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected,
            },
            command: DocumentCommandKind::ToggleTask {
                identity: "2024-02-01_09:00:00_0".to_owned(),
                body_start: task_start_relative,
                body_end: task_end_relative,
            },
            history: None,
        };
        let toggle_json = serde_json::to_string(&toggle).test_ok("toggle request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &toggle_json,
                Duration::from_secs(30),
            )
            .test_ok("start toggle");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        let after_toggle = harness.read_file("2024-02-01.md");
        assert!(
            after_toggle.windows(5).any(|w| w == b"- [x]"),
            "toggle must flip checkbox: {:?}",
            String::from_utf8_lossy(&after_toggle)
        );
        assert!(after_toggle.windows(7).any(|w| w == b"keep me"));

        let expected2 = fingerprint_of(&after_toggle);
        let append = DocumentCommandRequest {
            path: "2024-02-01.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected2,
            },
            command: DocumentCommandKind::Append {
                time_part: "11:00:00".to_owned(),
                content: "appended body".to_owned(),
            },
            history: None,
        };
        let append_json = serde_json::to_string(&append).test_ok("append request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &append_json,
                Duration::from_secs(30),
            )
            .test_ok("start append");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        let after_append = harness.read_file("2024-02-01.md");
        assert!(after_append.windows(13).any(|w| w == b"appended body"));

        let expected3 = fingerprint_of(&after_append);
        let remove = DocumentCommandRequest {
            path: "2024-02-01.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected3,
            },
            command: DocumentCommandKind::Remove {
                identity: "2024-02-01_10:00:00_0".to_owned(),
            },
            history: None,
        };
        let remove_json = serde_json::to_string(&remove).test_ok("remove request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &remove_json,
                Duration::from_secs(30),
            )
            .test_ok("start remove");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        let after_remove = harness.read_file("2024-02-01.md");
        assert!(
            !after_remove.windows(7).any(|w| w == b"keep me"),
            "remove must drop the 10:00 memo"
        );
        assert!(after_remove.windows(13).any(|w| w == b"appended body"));
    }

    #[test]
    fn document_create_writes_an_absent_daily_file_via_exchange() {
        let harness = Harness::new();
        let request = DocumentCommandRequest {
            path: "2026-08-04.md".to_owned(),
            expected_state: DocumentExpectedState::Absent,
            command: DocumentCommandKind::Create {
                time_part: "09:30:00".to_owned(),
                content: "created through SAF job".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("create request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start create");

        let terminal = harness.drive_until_terminal(&job_id);

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        assert_eq!(
            harness.read_file("2026-08-04.md"),
            b"- 09:30:00\ncreated through SAF job\n"
        );
        let payload = harness
            .engine
            .read_job_result(&job_id)
            .test_ok("result")
            .test_ok("payload");
        let result: DocumentCommandResult = serde_json::from_str(&payload).test_ok("decode result");
        let affected = result.affected_memo.test_ok("affected memo facts");
        assert_eq!(affected.identity, "2026-08-04_09:30:00_0");
        assert_eq!(affected.time_part, "09:30:00");
        assert_eq!(affected.fingerprint, result.result_fingerprint);
        assert_eq!(
            fs::read_dir(&harness.exchange_root)
                .test_ok("exchange dir")
                .count(),
            0,
            "document job must remove read/write exchange artifacts at terminal success"
        );
    }

    #[test]
    fn document_create_commits_history_sidecar_before_job_completion() {
        let harness = Harness::new();
        let request = DocumentCommandRequest {
            path: "2026-08-26.md".to_owned(),
            expected_state: DocumentExpectedState::Absent,
            command: DocumentCommandKind::Create {
                time_part: "12:00:00".to_owned(),
                content: "history survives projection rebuild".to_owned(),
            },
            history: Some(DocumentHistoryWrite {
                revision: 1,
                created_at_ms: 1_777_000_000_000,
            }),
        };
        let request_json = serde_json::to_string(&request).test_ok("history create request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start history create");

        let terminal = harness.drive_until_terminal(&job_id);

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        // Durable filename law strips the identity's `HH:mm:ss` colons (unsafe as SAF display
        // names), so `2026-08-26_12:00:00_0-r1` lands as `2026-08-26_120000_0-r1.rec`.
        let history_path = harness
            .workspace_root
            .join(".lomo/history/v1/2026-08-26_120000_0-r1.rec");
        let record = decode_record(&fs::read(history_path).test_ok("history record bytes"))
            .test_ok("history record");
        assert_eq!(record.payload.kind, LomoRecordKind::History);
        assert!(
            record
                .payload
                .body_json
                .contains("history survives projection rebuild")
        );
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 2);

        let scan_request = serde_json::to_string(&HistoryScanRequest {
            page_size: 16,
            cursor: None,
        })
        .test_ok("history scan request");
        let scan_job = harness
            .engine
            .start_user_job(
                HISTORY_SCAN_DRIVER_KIND,
                &scan_request,
                Duration::from_secs(30),
            )
            .test_ok("start history scan");
        assert!(matches!(
            harness.drive_until_terminal(&scan_job),
            JobStep::Completed
        ));
        let payload = harness
            .engine
            .read_job_result(&scan_job)
            .test_ok("history scan result")
            .test_ok("history scan payload");
        let page: HistoryScanPage = serde_json::from_str(&payload).test_ok("decode history page");
        let revision = page.items.first().test_ok("history revision");
        assert_eq!(revision.memo_id, "2026-08-26_12:00:00_0");
        assert_eq!(revision.revision, 1);
        assert_eq!(revision.created_at_ms, 1_777_000_000_000);
        assert_eq!(
            harness.read_exchange_token(&revision.content.exchange_token),
            b"history survives projection rebuild"
        );
    }

    #[test]
    fn history_scan_repairs_provider_sanitized_record_names_to_the_durable_filename_law() {
        let harness = Harness::new();
        // Simulates a provider that sanitized the `HH:mm:ss` colons out of the display name at
        // write time: payload identity is self-consistent, the on-disk name is not.
        let body = HistorySnapshotV1 {
            memo_id: "2026-08-26_12:00:00_0".to_owned(),
            revision: 1,
            content: "sanitized filename survives refresh".to_owned(),
            file_fingerprint: fingerprint_of(b"sanitized filename survives refresh"),
            created_at_ms: 1_777_000_000_000,
        };
        let record_id = format!("{}-r{}", body.memo_id, body.revision);
        let bytes = encode_record(&LomoPayload {
            kind: LomoRecordKind::History,
            record_id,
            body_json: serde_json::to_string(&body).test_ok("history body"),
        })
        .test_ok("encode record");
        harness.write_file(".lomo/history/v1/2026-08-26_12-00-00_0-r1.rec", &bytes);

        let scan_request = serde_json::to_string(&HistoryScanRequest {
            page_size: 16,
            cursor: None,
        })
        .test_ok("history scan request");
        let scan_job = harness
            .engine
            .start_user_job(
                HISTORY_SCAN_DRIVER_KIND,
                &scan_request,
                Duration::from_secs(30),
            )
            .test_ok("start history scan");
        assert!(matches!(
            harness.drive_until_terminal(&scan_job),
            JobStep::Completed
        ));
        let payload = harness
            .engine
            .read_job_result(&scan_job)
            .test_ok("history scan result")
            .test_ok("history scan payload");
        let page: HistoryScanPage = serde_json::from_str(&payload).test_ok("decode history page");
        let revision = page.items.first().test_ok("history revision");
        assert_eq!(revision.memo_id, "2026-08-26_12:00:00_0");
        assert_eq!(revision.revision, 1);
        assert_eq!(
            harness.read_exchange_token(&revision.content.exchange_token),
            b"sanitized filename survives refresh"
        );

        // The repair renames the record onto the durable filename law instead of failing the
        // whole workspace refresh.
        assert!(
            !harness
                .workspace_root
                .join(".lomo/history/v1/2026-08-26_12-00-00_0-r1.rec")
                .exists(),
            "sanitized record name must be repaired away"
        );
        let repaired = harness
            .workspace_root
            .join(".lomo/history/v1/2026-08-26_120000_0-r1.rec");
        assert_eq!(fs::read(repaired).test_ok("repaired record bytes"), bytes);
    }

    #[test]
    fn history_scan_fails_closed_with_the_offending_path_when_payload_identity_is_inconsistent() {
        let harness = Harness::new();
        let body = HistorySnapshotV1 {
            memo_id: "2026-08-26_12:00:00_0".to_owned(),
            revision: 1,
            content: "identity cannot be trusted".to_owned(),
            file_fingerprint: fingerprint_of(b"identity cannot be trusted"),
            created_at_ms: 1_777_000_000_000,
        };
        let bytes = encode_record(&LomoPayload {
            kind: LomoRecordKind::History,
            record_id: "bogus-identity".to_owned(),
            body_json: serde_json::to_string(&body).test_ok("history body"),
        })
        .test_ok("encode record");
        harness.write_file(".lomo/history/v1/2026-08-26_120000_0-r1.rec", &bytes);

        let scan_request = serde_json::to_string(&HistoryScanRequest {
            page_size: 16,
            cursor: None,
        })
        .test_ok("history scan request");
        let scan_job = harness
            .engine
            .start_user_job(
                HISTORY_SCAN_DRIVER_KIND,
                &scan_request,
                Duration::from_secs(30),
            )
            .test_ok("start history scan");
        let terminal = harness.drive_until_terminal(&scan_job);
        let JobStep::Failed { error } = terminal else {
            panic!("inconsistent payload identity must fail the job, got {terminal:?}")
        };
        let diagnostic = error.to_string();
        assert!(
            diagnostic.contains("history_record_path_mismatch"),
            "diagnostic must carry the failure code: {diagnostic}"
        );
        assert!(
            diagnostic.contains(".lomo/history/v1/2026-08-26_120000_0-r1.rec"),
            "diagnostic must name the offending record file: {diagnostic}"
        );
    }

    #[test]
    fn document_completion_precedes_retryable_exchange_cleanup() {
        let harness = Harness::new();
        let request = DocumentCommandRequest {
            path: "2026-08-05.md".to_owned(),
            expected_state: DocumentExpectedState::Absent,
            command: DocumentCommandKind::Create {
                time_part: "09:31:00".to_owned(),
                content: "commit before cleanup".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("create request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start create");
        let JobStep::NeedsPlatformBatch { batch } =
            harness.engine.poll_job(&job_id).test_ok("initial batch")
        else {
            panic!("create must require one platform write");
        };
        let results = harness.execute_batch(&batch);
        let platform_result = PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        );

        let original_mode = fs::metadata(&harness.exchange_root)
            .test_ok("exchange metadata")
            .permissions()
            .mode();
        fs::set_permissions(
            &harness.exchange_root,
            fs::Permissions::from_mode(original_mode & !0o222),
        )
        .test_ok("make exchange cleanup unavailable");
        let terminal = harness
            .engine
            .submit_platform_result(&job_id, platform_result)
            .test_ok("submit verified write");

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(
            harness.read_file("2026-08-05.md"),
            b"- 09:31:00\ncommit before cleanup\n"
        );
        assert_eq!(
            fs::read_dir(&harness.exchange_root)
                .test_ok("pending exchange cleanup")
                .count(),
            1,
            "cleanup failure must retain a durable retry target"
        );

        fs::set_permissions(
            &harness.exchange_root,
            fs::Permissions::from_mode(original_mode),
        )
        .test_ok("restore exchange permissions");
        let recovered = harness.engine.poll_job(&job_id).test_ok("retry cleanup");
        assert!(matches!(recovered, JobStep::Completed), "{recovered:?}");
        assert_eq!(
            fs::read_dir(&harness.exchange_root)
                .test_ok("exchange cleaned")
                .count(),
            0,
            "poll must reclaim the committed terminal artifact idempotently"
        );
    }

    #[test]
    fn trash_command_persists_recoverable_record_without_rewriting_source_document() {
        let harness = Harness::new();
        let source = b"- 09:30:00\nkeep\n\n- 10:45:00\ndelete me #tag\n";
        harness.write_file("2026_08_09.md", source);
        let identity = "2026_08_09_10:45:00_0";
        let request = TrashCommandRequest {
            path: "2026_08_09.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Trash {
                identity: identity.to_owned(),
                chronology_epoch_ms: 1_754_721_900_000,
            },
        };
        let request_json = serde_json::to_string(&request).test_ok("trash request");
        let job_id = harness
            .engine
            .start_user_job(
                TRASH_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start trash");

        let terminal = harness.drive_until_terminal(&job_id);

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.read_file("2026_08_09.md"), source);
        let marker_path = trash_record_relative_path(identity).test_ok("marker path");
        let marker_bytes = harness.read_file(marker_path.as_str());
        let marker = decode_trash_record(&marker_bytes).test_ok("trash record");
        assert_eq!(marker.memo_id, identity);
        assert_eq!(marker.source_path, "2026_08_09.md");
        assert_eq!(marker.body, "delete me #tag");
        assert_eq!(marker.tags, vec!["tag"]);
        assert_eq!(marker.source_fingerprint, fingerprint_of(source));
        let payload = harness
            .engine
            .read_job_result(&job_id)
            .test_ok("result")
            .test_ok("payload");
        let result: TrashCommandResult = serde_json::from_str(&payload).test_ok("decode result");
        assert_eq!(result.path, "2026_08_09.md");
        assert_eq!(result.result_fingerprint, fingerprint_of(source));
        assert_eq!(result.affected_memo.identity, identity);
        assert_eq!(result.trashed_at_ms, Some(marker.trashed_at_ms));
        assert_eq!(
            fs::read_dir(&harness.exchange_root)
                .test_ok("exchange dir")
                .count(),
            0,
            "terminal trash success must reclaim private exchange artifacts"
        );
    }

    #[test]
    fn trash_command_overwrites_existing_marker_idempotently() {
        let harness = Harness::new();
        let source = b"- 09:30:00\nkeep\n\n- 10:45:00\ndelete me\n";
        harness.write_file("2026_08_09.md", source);
        let identity = "2026_08_09_10:45:00_0";
        let marker_path = trash_record_relative_path(identity).test_ok("marker path");
        harness.write_file(marker_path.as_str(), b"stale marker bytes");

        let request = TrashCommandRequest {
            path: "2026_08_09.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Trash {
                identity: identity.to_owned(),
                chronology_epoch_ms: 1_754_721_900_000,
            },
        };
        let result = harness.trash_command(&request);
        assert_eq!(result.path, "2026_08_09.md");
        assert_eq!(result.affected_memo.identity, identity);
        let marker_bytes = harness.read_file(marker_path.as_str());
        let marker = decode_trash_record(&marker_bytes).test_ok("trash record");
        assert_eq!(marker.memo_id, identity);
        assert_eq!(marker.body, "delete me");
    }

    #[test]
    fn restore_command_removes_only_the_validated_trash_record() {
        let harness = Harness::new();
        let source = b"- 09:30:00\nkeep\n\n- 10:45:00\nrestore me\n";
        harness.write_file("2026_08_10.md", source);
        let identity = "2026_08_10_10:45:00_0";
        harness.trash_command(&TrashCommandRequest {
            path: "2026_08_10.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Trash {
                identity: identity.to_owned(),
                chronology_epoch_ms: 1_754_808_300_000,
            },
        });
        let marker_path = trash_record_relative_path(identity).test_ok("marker path");
        assert!(harness.workspace_root.join(marker_path.as_str()).is_file());

        let restored = harness.trash_command(&TrashCommandRequest {
            path: "2026_08_10.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Restore {
                identity: identity.to_owned(),
            },
        });

        assert_eq!(harness.read_file("2026_08_10.md"), source);
        assert!(!harness.workspace_root.join(marker_path.as_str()).exists());
        assert_eq!(restored.result_fingerprint, fingerprint_of(source));
        assert_eq!(restored.affected_memo.identity, identity);
        assert_eq!(restored.trashed_at_ms, None);
    }

    #[test]
    fn permanent_delete_rewrites_source_before_removing_the_recovery_record() {
        let harness = Harness::new();
        let source = b"- 09:30:00\nkeep\n\n- 10:45:00\ndelete forever\n";
        harness.write_file("2026_08_11.md", source);
        let identity = "2026_08_11_10:45:00_0";
        harness.trash_command(&TrashCommandRequest {
            path: "2026_08_11.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Trash {
                identity: identity.to_owned(),
                chronology_epoch_ms: 1_754_894_700_000,
            },
        });
        let marker_path = trash_record_relative_path(identity).test_ok("marker path");

        let deleted = harness.trash_command(&TrashCommandRequest {
            path: "2026_08_11.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::PermanentDelete {
                identity: identity.to_owned(),
            },
        });

        let after = harness.read_file("2026_08_11.md");
        assert!(after.windows(4).any(|window| window == b"keep"));
        assert!(!after.windows(14).any(|window| window == b"delete forever"));
        assert!(!harness.workspace_root.join(marker_path.as_str()).exists());
        assert_eq!(deleted.result_fingerprint, fingerprint_of(&after));
        assert_eq!(deleted.affected_memo.identity, identity);
        assert_eq!(deleted.trashed_at_ms, None);
    }

    #[test]
    fn trash_scan_publishes_rebuildable_record_facts_and_exact_body_reference() {
        let harness = Harness::new();
        let source = b"- 08:15:00\ntrash scan body #scan\n";
        harness.write_file("2026_08_12.md", source);
        let identity = "2026_08_12_08:15:00_0";
        let deleted = harness.trash_command(&TrashCommandRequest {
            path: "2026_08_12.md".to_owned(),
            expected_fingerprint: fingerprint_of(source),
            command: TrashCommandKind::Trash {
                identity: identity.to_owned(),
                chronology_epoch_ms: 1_754_972_100_000,
            },
        });

        let page = harness.trash_scan_page(16, None);

        assert_eq!(page.next_cursor, None);
        assert_eq!(page.items.len(), 1);
        let item = page.items.first().test_ok("trash item");
        assert_eq!(item.memo_id, identity);
        assert_eq!(item.source_path, "2026_08_12.md");
        assert_eq!(item.source_fingerprint, fingerprint_of(source));
        assert_eq!(item.chronology_epoch_ms, 1_754_972_100_000);
        assert_eq!(
            item.trashed_at_ms,
            deleted.trashed_at_ms.test_ok("trash time")
        );
        assert_eq!(item.tags, vec!["scan"]);
        assert_eq!(
            harness.read_exchange_token(&item.content.exchange_token),
            b"trash scan body #scan"
        );
    }

    #[test]
    fn trash_scan_returns_one_terminal_empty_page_for_an_empty_directory() {
        let harness = Harness::new();

        let page = harness.trash_scan_page(16, None);

        assert!(page.items.is_empty());
        assert!(page.next_cursor.is_none());
    }

    #[test]
    fn trash_scan_advances_past_a_fully_consumed_provider_page() {
        let harness = Harness::new();
        for index in 0..64 {
            let memo_id = format!("2026_08_13_08:15:00_{index}");
            harness.write_trash_record(
                &TrashRecordV1::try_new(TrashRecordCreate {
                    memo_id,
                    source_path: "2026_08_13.md".to_owned(),
                    time_part: "08:15:00".to_owned(),
                    source_fingerprint: fingerprint_of(b"source"),
                    chronology_epoch_ms: 1_755_058_500_000 + i64::from(index),
                    trashed_at_ms: 1_755_058_600_000 + i64::from(index),
                    body: format!("trash-{index}"),
                    tags: Vec::new(),
                    attachments: Vec::new(),
                    reminders: Vec::new(),
                    has_todo: false,
                    has_url: false,
                })
                .test_ok("trash record"),
            );
        }

        let first = harness.trash_scan_page(61, None);
        let second = harness.trash_scan_page(2, first.next_cursor);
        let third = harness.trash_scan_page(2, second.next_cursor);
        let all: Vec<&TrashMemoSummary> = first
            .items
            .iter()
            .chain(&second.items)
            .chain(&third.items)
            .collect();

        assert_eq!(first.items.len(), 61);
        assert_eq!(second.items.len(), 2);
        assert_eq!(third.items.len(), 1);
        assert!(third.next_cursor.is_none());
        assert_eq!(all.len(), 64);
        let identities: std::collections::BTreeSet<_> =
            all.iter().map(|item| item.memo_id.as_str()).collect();
        assert_eq!(identities.len(), 64);
    }

    #[test]
    fn document_create_rejects_an_existing_daily_file_without_overwrite() {
        let harness = Harness::new();
        let original = b"- 08:00:00\nexisting\n";
        harness.write_file("2026-08-04.md", original);
        let request = DocumentCommandRequest {
            path: "2026-08-04.md".to_owned(),
            expected_state: DocumentExpectedState::Absent,
            command: DocumentCommandKind::Create {
                time_part: "09:30:00".to_owned(),
                content: "must not overwrite".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("create request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start create");

        let terminal = harness.drive_until_terminal(&job_id);

        assert!(matches!(terminal, JobStep::Failed { .. }), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 0);
        assert_eq!(harness.read_file("2026-08-04.md"), original);
    }

    #[test]
    fn document_replace_writes_once_via_exchange_and_is_byte_local() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nold body\n\n- 11:00:00\nkeep\n";
        harness.write_file("2024-01-02.md", original);
        let expected = fingerprint_of(original);

        let request = DocumentCommandRequest {
            path: "2024-01-02.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected,
            },
            command: DocumentCommandKind::Replace {
                identity: "2024-01-02_10:00:00_0".to_owned(),
                content: "new body".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start document");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        let after = harness.read_file("2024-01-02.md");
        assert!(after.windows(8).any(|w| w == b"new body"));
        assert!(after.windows(4).any(|w| w == b"keep"));
        let result = harness
            .engine
            .read_job_result(&job_id)
            .test_ok("result")
            .test_ok("payload");
        assert!(result.contains("result_fingerprint"));
    }

    #[test]
    fn document_rewrite_reminder_changes_only_the_scanned_occurrence() {
        let harness = Harness::new();
        let token = "@2026-07-20-09:30x2";
        let replacement = "@2026-07-20-10:45x2.1";
        let original = format!("- 10:00:00\nfirst {token} then {token}\n");
        harness.write_file("2026-07-20.md", original.as_bytes());
        let page = harness.scan_page(16, None).test_ok("scan page");
        let second = page
            .items
            .first()
            .expect("item")
            .reminders
            .get(1)
            .expect("reminder")
            .clone();

        let request = DocumentCommandRequest {
            path: "2026-07-20.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: second.revision.clone(),
            },
            command: DocumentCommandKind::RewriteReminder {
                reminder: second,
                replacement: replacement.to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start reminder rewrite");
        let terminal = harness.drive_until_terminal(&job_id);

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        assert_eq!(
            harness.read_file("2026-07-20.md"),
            format!("- 10:00:00\nfirst {token} then {replacement}\n").as_bytes()
        );
    }

    #[test]
    fn document_command_fails_closed_on_stale_snapshot_without_mutating() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nold\n";
        harness.write_file("2024-01-03.md", original);
        let expected = fingerprint_of(original);

        let request = DocumentCommandRequest {
            path: "2024-01-03.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected,
            },
            command: DocumentCommandKind::Replace {
                identity: "2024-01-03_10:00:00_0".to_owned(),
                content: "should not land".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start");

        // Drive only the first batch (read), then externally edit before submit of write.
        let step = harness.engine.poll_job(&job_id).test_ok("poll");
        let JobStep::NeedsPlatformBatch { batch } = step else {
            panic!("expected read batch");
        };
        // Externally edit before read result is applied — fingerprint will not match expected.
        harness.write_file("2024-01-03.md", b"- 10:00:00\nexternal\n");
        let results = batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let result = PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        );
        let after = harness.engine.submit_platform_result(&job_id, result);
        match after {
            Ok(JobStep::Failed { error }) | Err(error) => {
                assert_eq!(error.category(), ErrorCategory::Conflict);
                assert_eq!(error.retry_disposition(), RetryDisposition::AfterUserAction);
                assert_eq!(error.code(), "stale_snapshot");
            }
            other => panic!("stale snapshot must fail closed, got {other:?}"),
        }
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 0);
        assert_eq!(
            harness.read_file("2024-01-03.md"),
            b"- 10:00:00\nexternal\n"
        );
    }

    #[test]
    fn write_replay_already_satisfied_does_not_double_write() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nbody\n";
        harness.write_file("2024-01-04.md", original);
        let expected = fingerprint_of(original);
        let request = DocumentCommandRequest {
            path: "2024-01-04.md".to_owned(),
            expected_state: DocumentExpectedState::Match {
                fingerprint: expected,
            },
            command: DocumentCommandKind::Replace {
                identity: "2024-01-04_10:00:00_0".to_owned(),
                content: "once".to_owned(),
            },
            history: None,
        };
        let request_json = serde_json::to_string(&request).test_ok("request");
        let job_id = harness
            .engine
            .start_user_job(
                DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_secs(30),
            )
            .test_ok("start");

        // First: drive read batch.
        let step = harness.engine.poll_job(&job_id).test_ok("poll");
        let JobStep::NeedsPlatformBatch { batch } = step else {
            panic!("read batch");
        };
        let results = batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let result = PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        );
        let after_read = harness
            .engine
            .submit_platform_result(&job_id, result)
            .test_ok("submit read");
        let JobStep::NeedsPlatformBatch { batch: write_batch } = after_read else {
            panic!("write batch expected, got {after_read:?}");
        };

        // Apply write once.
        let write_results: Vec<_> = write_batch
            .actions()
            .iter()
            .map(|action| ActionResult::new(action.id().clone(), harness.execute(action)))
            .collect();
        let replay_outputs = write_results.clone();
        let write_result = PlatformBatchResult::new(
            write_batch.schema_version(),
            write_batch.job_id().clone(),
            write_batch.batch_id().clone(),
            write_batch.attempt(),
            write_results,
        );
        let completed = harness
            .engine
            .submit_platform_result(&job_id, write_result)
            .test_ok("submit write");
        assert!(matches!(completed, JobStep::Completed));
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);

        // Late replay with AlreadySatisfied must not mutate again; job stays completed.
        let replay_results: Vec<_> = replay_outputs
            .into_iter()
            .map(|applied| {
                // Convert the already observed Applied result into the provider's replay response;
                // terminal cleanup has correctly reclaimed the private write artifact by now.
                let outcome = match applied.outcome().clone() {
                    ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => {
                        ActionOutcome::AlreadySatisfied(output)
                    }
                    ActionOutcome::Failed(error) => ActionOutcome::Failed(error),
                };
                ActionResult::new(applied.action_id().clone(), outcome)
            })
            .collect();
        let replay = PlatformBatchResult::new(
            write_batch.schema_version(),
            write_batch.job_id().clone(),
            write_batch.batch_id().clone(),
            write_batch.attempt(),
            replay_results,
        );
        let late = harness
            .engine
            .submit_platform_result(&job_id, replay)
            .test_ok("late replay");
        assert!(matches!(late, JobStep::Completed));
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        let polled = harness.engine.poll_job(&job_id).test_ok("poll terminal");
        assert!(matches!(polled, JobStep::Completed));
    }

    #[test]
    fn scan_accepts_default_yyyy_mm_dd_filename_stems() {
        // Product default StorageFilenameFormats.DEFAULT_PATTERN embeds underscores.
        let harness = Harness::new();
        harness.write_file("2024_06_01.md", b"- 09:00:00\ndefault format memo\n");
        harness.write_file("2024-06-02.md", b"- 10:00\nhyphen format memo\n");

        let page = harness.scan_page(32, None).test_ok("scan default stems");
        let identities: Vec<String> = page
            .items
            .iter()
            .map(|item| item.identity.clone())
            .collect();
        assert!(
            identities.iter().any(|id| id.starts_with("2024_06_01_")),
            "default yyyy_MM_dd dateKey must form identity: {identities:?}"
        );
        assert!(
            identities.iter().any(|id| id.starts_with("2024-06-02_")),
            "hyphen dateKey must form identity: {identities:?}"
        );
        assert_eq!(
            page.items.len(),
            2,
            "both product date files must scan: {identities:?}"
        );
    }

    #[test]
    fn scan_bounds_directory_listing_to_one_driver_budget() {
        let harness = Harness::new();
        for index in 0..100 {
            harness.write_file(
                &format!("{index:03}.md"),
                format!("- 10:00:00\nmemo {index}\n").as_bytes(),
            );
        }

        let page = harness.scan_page(1, None).test_ok("scan page");

        assert_eq!(page.items.len(), 1);
        assert!(page.next_cursor.is_some());
    }

    #[test]
    fn scan_returns_empty_directory_page_before_consuming_the_next_listing_page() {
        let harness = Harness::new();
        for index in 0..257 {
            harness.write_file(&format!("attachment-{index:03}.bin"), b"not markdown");
        }

        let page = harness.scan_page(1, None).test_ok("scan page");

        assert!(page.items.is_empty());
        assert!(page.next_cursor.is_some());
    }
}
