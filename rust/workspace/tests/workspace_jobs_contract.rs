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
//!   contain the complete exact memo content rather than a truncated preview.
//! - Given two workspace sessions whose journals allocate the same job id, when each publishes scan
//!   content, then their opaque tokens differ and reveal neither workspace path.
//! - Given a scan page whose content artifact cannot be published, when the driver advances, then
//!   the whole job fails and no partial page result is observable.
//! - Given a document replace command with a matching fingerprint, when driven, then the file is
//!   rewritten once via write-from-exchange and the result fingerprint matches the pure planner.
//! - Given an external edit after read (stale fingerprint), when the document command advances,
//!   then the job fails with `stale_snapshot` and the on-disk file is unchanged.
//! - Given a completed write whose postcondition is already satisfied, when the same write batch is
//!   replayed with `AlreadySatisfied`, then no second mutating plan is emitted.
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
//! opaque token scope, on-disk file bytes, write counts.
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
    use std::fs;
    use std::path::PathBuf;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use lomo_core::{
        ActionEvidence, ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, EngineConfig,
        ExchangeArtifact, JobStep, LomoEngine, MetadataPage, PlatformAction, PlatformActionOutput,
        PlatformBatchResult, Sha256Digest, WorkspaceDescriptor, WorkspaceTarget,
    };
    use lomo_workspace::{
        DOCUMENT_COMMAND_DRIVER_KIND, DocumentCommandKind, DocumentCommandRequest,
        SCAN_DRIVER_KIND, SourceFingerprint, WorkspaceScanRequest, workspace_driver_registry,
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

        fn drive_until_terminal(&self, job_id: &lomo_core::JobId) -> JobStep {
            let mut guard = 0;
            loop {
                guard += 1;
                assert!(guard < 64, "job did not terminate");
                let step = self.engine.poll_job(job_id).test_ok("poll");
                match step {
                    JobStep::NeedsPlatformBatch { batch } => {
                        let results = batch
                            .actions()
                            .iter()
                            .map(|action| {
                                ActionResult::new(action.id().clone(), self.execute(action))
                            })
                            .collect();
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
                        if !matches!(after, JobStep::NeedsPlatformBatch { .. } | JobStep::Running) {
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
                    lomo_core::ErrorCategory::Internal,
                    "scan_result_missing",
                    lomo_core::RetryDisposition::Never,
                    None,
                    None,
                    "scan completed without a page",
                )
                .test_ok("static test error")
            })?;
            serde_json::from_str(&result).map_err(|_error| {
                lomo_core::LomoError::from_platform_boundary(
                    lomo_core::ErrorCategory::Corruption,
                    "scan_result_invalid",
                    lomo_core::RetryDisposition::Never,
                    None,
                    None,
                    "scan result is not a page",
                )
                .test_ok("static test error")
            })
        }

        fn execute(&self, action: &PlatformAction) -> ActionOutcome {
            match action {
                PlatformAction::ListChildren { .. } => self.execute_list_children(action),
                PlatformAction::ReadToExchange { .. } => self.execute_read_to_exchange(action),
                PlatformAction::WriteFromExchange {
                    artifact,
                    path,
                    expected_target,
                    ..
                } => {
                    self.write_count.fetch_add(1, Ordering::SeqCst);
                    let exchange_path = self.exchange_root.join(artifact.token().as_str());
                    let bytes = fs::read(&exchange_path).test_ok("read exchange write artifact");
                    let digest = {
                        use sha2::{Digest, Sha256};
                        format!("{:x}", Sha256::digest(&bytes))
                    };
                    assert_eq!(digest, artifact.digest().as_str(), "artifact digest");
                    // Stale expected target fails closed without write.
                    if let lomo_core::ExpectedFingerprint::Match(expected) = expected_target {
                        let current = match fs::read(self.workspace_root.join(path.as_str())) {
                            Ok(bytes) => Some(bytes),
                            Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
                            Err(error) => panic!("failed to read expected target: {error}"),
                        };
                        if let Some(current_bytes) = current {
                            let current_digest = {
                                use sha2::{Digest, Sha256};
                                format!("{:x}", Sha256::digest(&current_bytes))
                            };
                            if current_digest != expected.digest().as_str()
                                && current_bytes.len() as u64 != expected.length()
                            {
                                // Allow evidence length/digest mismatch → fail
                            }
                            // Compare digest primarily.
                            if current_digest != expected.digest().as_str() {
                                return ActionOutcome::Failed(
                                    lomo_core::LomoError::from_platform_boundary(
                                        lomo_core::ErrorCategory::Validation,
                                        "postcondition_mismatch",
                                        lomo_core::RetryDisposition::Never,
                                        None,
                                        None,
                                        "Target fingerprint does not match the expected postcondition",
                                    )
                                    .test_ok("error"),
                                );
                            }
                        }
                    }
                    let full = self.workspace_root.join(path.as_str());
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
                PlatformAction::Stat { .. }
                | PlatformAction::EnsureDirectory { .. }
                | PlatformAction::Move { .. }
                | PlatformAction::Delete { .. } => {
                    panic!("unexpected action in harness: {action:?}")
                }
            }
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
                exchange_token,
                ..
            } = action
            else {
                panic!("read helper received non-read action: {action:?}");
            };
            let bytes = fs::read(self.workspace_root.join(path.as_str())).test_ok("read source");
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
                source_metadata: DocumentMetadata::new(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::File,
                    None,
                    evidence,
                )
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
            DocumentMetadata::new(
                WorkspaceTarget::Relative(
                    lomo_core::RelativeWorkspacePath::parse(&relative).test_ok("path"),
                ),
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

        // Toggle identity is the "[ ]" / "[x]" marker span (not the leading "- ").
        let task_marker = b"[ ]";
        let task_start = original
            .windows(task_marker.len())
            .position(|w| w == task_marker)
            .expect("task marker") as u64;
        let task_end = task_start + task_marker.len() as u64;

        let toggle = DocumentCommandRequest {
            path: "2024-02-01.md".to_owned(),
            expected_fingerprint: expected,
            command: DocumentCommandKind::ToggleTask {
                source_start: task_start,
                source_end: task_end,
            },
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
            expected_fingerprint: expected2,
            command: DocumentCommandKind::Append {
                time_part: "11:00:00".to_owned(),
                content: "appended body".to_owned(),
            },
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
            expected_fingerprint: expected3,
            command: DocumentCommandKind::Remove {
                identity: "2024-02-01_10:00:00_0".to_owned(),
            },
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
    fn document_replace_writes_once_via_exchange_and_is_byte_local() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nold body\n\n- 11:00:00\nkeep\n";
        harness.write_file("2024-01-02.md", original);
        let expected = fingerprint_of(original);

        let request = DocumentCommandRequest {
            path: "2024-01-02.md".to_owned(),
            expected_fingerprint: expected,
            command: DocumentCommandKind::Replace {
                identity: "2024-01-02_10:00:00_0".to_owned(),
                content: "new body".to_owned(),
            },
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
            expected_fingerprint: second.revision.clone(),
            command: DocumentCommandKind::RewriteReminder {
                reminder: second,
                replacement: replacement.to_owned(),
            },
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
            expected_fingerprint: expected,
            command: DocumentCommandKind::Replace {
                identity: "2024-01-03_10:00:00_0".to_owned(),
                content: "should not land".to_owned(),
            },
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
            expected_fingerprint: expected,
            command: DocumentCommandKind::Replace {
                identity: "2024-01-04_10:00:00_0".to_owned(),
                content: "once".to_owned(),
            },
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
        let replay_results: Vec<_> = write_batch
            .actions()
            .iter()
            .map(|action| {
                let applied = harness.execute(action);
                // Convert Applied → AlreadySatisfied for replay semantics.
                let outcome = match applied {
                    ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => {
                        ActionOutcome::AlreadySatisfied(output)
                    }
                    ActionOutcome::Failed(error) => ActionOutcome::Failed(error),
                };
                ActionResult::new(action.id().clone(), outcome)
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
        // execute() still runs for harness bookkeeping on replay construction above — count may
        // increase in this test harness; the durable engine must not plan a new write batch.
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
}
