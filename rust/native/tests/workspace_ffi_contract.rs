//! Behavior Contract — P2-06 `BoltFFI` workspace conversion surface
//!
//! Capability: expose render / workspace scan / document-command APIs through `lomo-native` as
//! conversion-only DTOs. The facade must not re-interpret Markdown; document semantics stay in
//! `lomo-workspace` and job sequencing stays in `lomo-core`.
//!
//! Scenarios:
//! - Given constrained inline Markdown, when `render_markdown` is called, then a typed render DTO
//!   is returned with schema/plain-text/tag projections and no facade-owned parse rules.
//! - Given a direct workspace with one Lomo memo file, when scan is started and platform batches
//!   are driven, then `read_workspace_scan_page` returns a bounded page whose typed content
//!   reference resolves to the complete exact memo body without facade-owned parsing.
//! - Given a replace command with a matching fingerprint, when driven, then the document command
//!   result fingerprint matches the pure planner and the file is rewritten once.
//! - Given a typed reminder reference returned by scan, when it crosses the conversion-only
//!   document-command DTO, then only that exact occurrence is rewritten.
//!
//! Observable outcomes: FFI DTOs, job ids, durable result payloads, on-disk bytes.
//! Excludes: production DI dual-stack (P2-09), Kotlin IR presentation (P2-07).

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
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use lomo_native::{
        ActionEvidence, ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, EngineConfig,
        ExchangeArtifact, JobStep, LomoEngine, MetadataPage, PlatformAction, PlatformActionOutput,
        PlatformBatchResult, RenderNodeKind, RenderRequest, WorkspaceDescriptor,
        WorkspaceDocumentCommand, WorkspaceDocumentCommandKind, WorkspaceScanRequest,
        WorkspaceTarget,
    };
    use lomo_workspace::SourceFingerprint;
    use tempfile::tempdir;

    fn fingerprint_of(bytes: &[u8]) -> String {
        SourceFingerprint::of_bytes(bytes).as_str().to_owned()
    }

    struct Harness {
        _temporary: tempfile::TempDir,
        workspace_root: std::path::PathBuf,
        exchange_root: std::path::PathBuf,
        engine: LomoEngine,
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
            let engine = LomoEngine::open(EngineConfig {
                control_root: control.display().to_string(),
                exchange_root: exchange.display().to_string(),
                workspace: Some(WorkspaceDescriptor::Direct {
                    root_path: workspace.display().to_string(),
                }),
                bootstrap_deadline_millis: 30_000,
            })
            .test_ok("open");
            assert!(matches!(
                engine.state(),
                lomo_native::EngineState::Ready { .. }
            ));
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
            fs::write(path, bytes).test_ok("write");
        }

        fn drive_until_terminal(&self, job_id: &str) -> JobStep {
            let mut guard = 0;
            loop {
                guard += 1;
                assert!(guard < 64, "unterminated job");
                let step = self.engine.poll_job(job_id.to_owned()).test_ok("poll");
                match step {
                    JobStep::NeedsPlatformBatch { batch } => {
                        let action_results = batch
                            .actions
                            .iter()
                            .map(|action| ActionResult {
                                action_id: action_id(action).to_owned(),
                                outcome: self.execute(action),
                            })
                            .collect();
                        let result = PlatformBatchResult {
                            schema_version: batch.schema_version,
                            job_id: batch.job_id.clone(),
                            batch_id: batch.batch_id.clone(),
                            attempt: batch.attempt,
                            action_results,
                        };
                        let after = self
                            .engine
                            .submit_platform_result(job_id.to_owned(), result)
                            .test_ok("submit");
                        if !matches!(after, JobStep::NeedsPlatformBatch { .. } | JobStep::Running) {
                            return after;
                        }
                    }
                    JobStep::Running => {}
                    JobStep::BlockedByConflict { .. }
                    | JobStep::Completed
                    | JobStep::Failed { .. } => return step,
                }
            }
        }

        fn execute(&self, action: &PlatformAction) -> ActionOutcome {
            match action {
                PlatformAction::ListChildren { .. } => self.execute_list_children(action),
                PlatformAction::ReadToExchange {
                    path,
                    exchange_token,
                    ..
                } => {
                    let bytes = fs::read(self.workspace_root.join(path)).test_ok("read");
                    let digest = {
                        use sha2::{Digest, Sha256};
                        format!("{:x}", Sha256::digest(&bytes))
                    };
                    fs::write(self.exchange_root.join(exchange_token), &bytes).test_ok("exchange");
                    ActionOutcome::Applied {
                        output: PlatformActionOutput::ReadToExchange {
                            source_metadata: DocumentMetadata {
                                target: WorkspaceTarget::Relative { path: path.clone() },
                                kind: DocumentKind::File,
                                mime_type: None,
                                evidence: ActionEvidence {
                                    length: bytes.len() as u64,
                                    digest: digest.clone(),
                                    fingerprint: format!("fp.{}", path.replace('/', ".")),
                                },
                            },
                            artifact: ExchangeArtifact {
                                token: exchange_token.clone(),
                                length: bytes.len() as u64,
                                digest,
                            },
                        },
                    }
                }
                PlatformAction::WriteFromExchange { artifact, path, .. } => {
                    self.write_count.fetch_add(1, Ordering::SeqCst);
                    let bytes = fs::read(self.exchange_root.join(&artifact.token)).test_ok("ex");
                    fs::write(self.workspace_root.join(path), &bytes).test_ok("write");
                    let digest = {
                        use sha2::{Digest, Sha256};
                        format!("{:x}", Sha256::digest(&bytes))
                    };
                    ActionOutcome::Applied {
                        output: PlatformActionOutput::WriteComplete {
                            metadata: DocumentMetadata {
                                target: WorkspaceTarget::Relative { path: path.clone() },
                                kind: DocumentKind::File,
                                mime_type: None,
                                evidence: ActionEvidence {
                                    length: bytes.len() as u64,
                                    digest,
                                    fingerprint: format!("fp.{}", path.replace('/', ".")),
                                },
                            },
                        },
                    }
                }
                PlatformAction::Stat { .. }
                | PlatformAction::EnsureDirectory { .. }
                | PlatformAction::Move { .. }
                | PlatformAction::Delete { .. } => {
                    panic!("unexpected {action:?}")
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
                WorkspaceTarget::Relative { path } => self.workspace_root.join(path),
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
            let end = (start + *page_size as usize).min(names.len());
            let next = (end < names.len()).then(|| {
                names
                    .get(end - 1)
                    .cloned()
                    .expect("page end implies last entry")
            });
            let items = names
                .get(start..end)
                .unwrap_or(&[])
                .iter()
                .map(|name| self.metadata_for_child(target, name))
                .collect();
            ActionOutcome::Applied {
                output: PlatformActionOutput::Listed {
                    page: MetadataPage {
                        items,
                        next_cursor: next,
                    },
                },
            }
        }

        fn metadata_for_child(&self, target: &WorkspaceTarget, name: &str) -> DocumentMetadata {
            let relative = match target {
                WorkspaceTarget::Root => name.to_owned(),
                WorkspaceTarget::Relative { path } => format!("{path}/{name}"),
            };
            let full = self.workspace_root.join(&relative);
            let metadata = fs::metadata(&full).test_ok("metadata");
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
                format!("{:x}", Sha256::digest(&bytes))
            };
            DocumentMetadata {
                target: WorkspaceTarget::Relative {
                    path: relative.clone(),
                },
                kind,
                mime_type: None,
                evidence: ActionEvidence {
                    length: bytes.len() as u64,
                    digest,
                    fingerprint: format!("fp.{}", relative.replace('/', ".")),
                },
            }
        }
    }

    fn action_id(action: &PlatformAction) -> &str {
        match action {
            PlatformAction::Stat { action_id, .. }
            | PlatformAction::ListChildren { action_id, .. }
            | PlatformAction::EnsureDirectory { action_id, .. }
            | PlatformAction::ReadToExchange { action_id, .. }
            | PlatformAction::WriteFromExchange { action_id, .. }
            | PlatformAction::Move { action_id, .. }
            | PlatformAction::Delete { action_id, .. } => action_id,
        }
    }

    #[test]
    fn render_markdown_is_conversion_only_and_projects_tags() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: None,
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open");
        let document = engine
            .render_markdown(RenderRequest {
                content: "hello #tag and more".to_owned(),
                schema_version: 1,
            })
            .test_ok("render");
        assert_eq!(document.schema_version, 1);
        assert!(document.plain_text.contains("hello"));
        assert!(document.tag_names.iter().any(|tag| tag == "tag"));
        assert!(document.node_count > 0);
        let tag = document
            .nodes
            .iter()
            .find(|node| matches!(node.kind, RenderNodeKind::Tag))
            .test_ok("typed tag node");
        assert_eq!(tag.text.as_deref(), Some("tag"));
        assert!(tag.source_end > tag.source_start);
    }

    #[test]
    fn ffi_scan_page_returns_bounded_memo_summaries() {
        let harness = Harness::new();
        let content = format!("scan #ffi {} tail", "界🙂".repeat(180));
        harness.write_file(
            "2024-02-01.md",
            format!("- 09:00:00\n{content}\n").as_bytes(),
        );
        let job_id = harness
            .engine
            .start_workspace_scan(
                WorkspaceScanRequest {
                    page_size: 16,
                    cursor: None,
                    root_path: None,
                },
                30_000,
            )
            .test_ok("start scan");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        let page = harness
            .engine
            .read_workspace_scan_page(job_id)
            .test_ok("page");
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items.first().expect("item").path, "2024-02-01.md");
        assert!(
            page.items
                .first()
                .expect("item")
                .tags
                .iter()
                .any(|tag| tag == "ffi")
        );
        let reference = &page.items.first().expect("item").content;
        let artifact = fs::read(harness.exchange_root.join(&reference.exchange_token))
            .test_ok("read memo content artifact");
        assert_eq!(artifact, content.as_bytes());
        assert_eq!(reference.length, content.len() as u64);
        assert_eq!(reference.digest, fingerprint_of(content.as_bytes()));
        assert_eq!(page.items.first().expect("item").body_start, 11);
        assert_eq!(
            page.items.first().expect("item").body_end,
            12 + content.len() as u64
        );
        assert!(page.next_cursor.is_none());
    }

    #[test]
    fn ffi_document_command_replace_writes_once() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nold\n";
        harness.write_file("2024-02-02.md", original);
        let job_id = harness
            .engine
            .start_workspace_document_command(
                WorkspaceDocumentCommand {
                    path: "2024-02-02.md".to_owned(),
                    expected_fingerprint: fingerprint_of(original),
                    command: WorkspaceDocumentCommandKind::Replace {
                        identity: "2024-02-02_10:00:00_0".to_owned(),
                        content: "new".to_owned(),
                    },
                },
                30_000,
            )
            .test_ok("start command");
        let terminal = harness.drive_until_terminal(&job_id);
        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        let result = harness
            .engine
            .read_workspace_document_command_result(job_id)
            .test_ok("result");
        assert_eq!(result.path, "2024-02-02.md");
        assert!(!result.result_fingerprint.is_empty());
        let after = fs::read(harness.workspace_root.join("2024-02-02.md")).test_ok("read");
        assert!(after.windows(3).any(|window| window == b"new"));
    }

    #[test]
    fn ffi_scan_reference_rewrites_the_exact_reminder_occurrence() {
        let harness = Harness::new();
        let token = "@2026-07-20-09:30x2";
        let replacement = "@2026-07-20-10:45x2.1";
        let original = format!("- 10:00:00\nfirst {token} then {token}\n");
        harness.write_file("2026-07-20.md", original.as_bytes());
        let scan_job = harness
            .engine
            .start_workspace_scan(
                WorkspaceScanRequest {
                    page_size: 16,
                    cursor: None,
                    root_path: None,
                },
                30_000,
            )
            .test_ok("start scan");
        assert!(matches!(
            harness.drive_until_terminal(&scan_job),
            JobStep::Completed
        ));
        let page = harness
            .engine
            .read_workspace_scan_page(scan_job)
            .test_ok("scan page");
        let second = page
            .items
            .first()
            .expect("item")
            .reminders
            .get(1)
            .expect("reminder")
            .clone();

        let command_job = harness
            .engine
            .start_workspace_document_command(
                WorkspaceDocumentCommand {
                    path: "2026-07-20.md".to_owned(),
                    expected_fingerprint: second.revision.clone(),
                    command: WorkspaceDocumentCommandKind::RewriteReminder {
                        reminder: second,
                        replacement: replacement.to_owned(),
                    },
                },
                30_000,
            )
            .test_ok("start rewrite");
        let terminal = harness.drive_until_terminal(&command_job);

        assert!(matches!(terminal, JobStep::Completed), "{terminal:?}");
        assert_eq!(harness.write_count.load(Ordering::SeqCst), 1);
        assert_eq!(
            fs::read(harness.workspace_root.join("2026-07-20.md")).test_ok("read"),
            format!("- 10:00:00\nfirst {token} then {replacement}\n").as_bytes()
        );
    }
}
