//! Behavior Contract — P2-06 `BoltFFI` workspace conversion surface
//!
//! Capability: expose render / document-command APIs through `lomo-native` as conversion-only
//! DTOs. The facade must not re-interpret Markdown; document semantics stay in `lomo-workspace`
//! and job sequencing stays in `lomo-core`.
//!
//! Scenarios:
//! - Given constrained inline Markdown, when `render_markdown` is called, then a typed render DTO
//!   is returned with schema/plain-text/tag projections and no facade-owned parse rules.
//! - Given a replace command with a matching fingerprint, when driven, then the document command
//!   result fingerprint and Rust-parsed affected memo facts match the pure planner and the file is
//!   rewritten once.
//!
//! Observable outcomes: FFI DTOs, job ids, durable result payloads, on-disk bytes.
//! TDD proof: RED on 2026-08-09 because `lomo-native` exposed neither typed document-command jobs
//! nor the `EnsureDirectory`/`Delete` platform lifecycle required to drive them.
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
    use std::{
        fs,
        sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        },
    };

    use lomo_native::{
        ActionEvidence, ActionOutcome, ActionResult, ContentDigest, DocumentKind, DocumentMetadata,
        EngineConfig, ExchangeArtifact, JobStep, LomoEngine, MetadataPage, PlatformAction,
        PlatformActionOutput, PlatformBatchResult, RenderNodeKind, RenderRequest,
        WorkspaceDescriptor, WorkspaceDocumentCommand, WorkspaceDocumentCommandKind,
        WorkspaceDocumentExpectedState, WorkspaceTarget,
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
                    capability_token: "notes-root".to_owned(),
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
                    JobStep::Running | JobStep::RunningNative { .. } => {}
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
                                document_handle: path.clone(),
                                kind: DocumentKind::File,
                                mime_type: None,
                                evidence: ActionEvidence {
                                    length: bytes.len() as u64,
                                    digest: ContentDigest::Verified {
                                        hex: digest.clone(),
                                    },
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
                    if let Some(parent) = self.workspace_root.join(path).parent() {
                        fs::create_dir_all(parent).test_ok("write parent");
                    }
                    fs::write(self.workspace_root.join(path), &bytes).test_ok("write");
                    let digest = {
                        use sha2::{Digest, Sha256};
                        format!("{:x}", Sha256::digest(&bytes))
                    };
                    ActionOutcome::Applied {
                        output: PlatformActionOutput::WriteComplete {
                            metadata: DocumentMetadata {
                                target: WorkspaceTarget::Relative { path: path.clone() },
                                document_handle: path.clone(),
                                kind: DocumentKind::File,
                                mime_type: None,
                                evidence: ActionEvidence {
                                    length: bytes.len() as u64,
                                    digest: ContentDigest::Verified { hex: digest },
                                    fingerprint: format!("fp.{}", path.replace('/', ".")),
                                },
                            },
                        },
                    }
                }
                PlatformAction::ArtifactWrite { source, path, .. } => {
                    self.execute_artifact_write(&source.path, path)
                }
                PlatformAction::EnsureDirectory { path, .. } => {
                    fs::create_dir_all(self.workspace_root.join(path)).test_ok("ensure directory");
                    ActionOutcome::Applied {
                        output: PlatformActionOutput::DirectoryReady {
                            metadata: self.metadata_for_relative(path),
                        },
                    }
                }
                PlatformAction::Delete { path, .. } => {
                    fs::remove_file(self.workspace_root.join(path)).test_ok("delete");
                    ActionOutcome::Applied {
                        output: PlatformActionOutput::DeleteComplete {
                            absence: lomo_native::VerifiedAbsence {
                                target: WorkspaceTarget::Relative { path: path.clone() },
                                fingerprint: "verified-absent".to_owned(),
                            },
                        },
                    }
                }
                PlatformAction::Stat { .. } | PlatformAction::Move { .. } => {
                    panic!("unexpected {action:?}")
                }
            }
        }

        fn execute_artifact_write(&self, source_path: &str, path: &str) -> ActionOutcome {
            self.write_count.fetch_add(1, Ordering::SeqCst);
            let bytes = fs::read(source_path).test_ok("staged source");
            if let Some(parent) = self.workspace_root.join(path).parent() {
                fs::create_dir_all(parent).test_ok("write parent");
            }
            fs::write(self.workspace_root.join(path), &bytes).test_ok("write");
            let digest = {
                use sha2::{Digest, Sha256};
                format!("{:x}", Sha256::digest(&bytes))
            };
            ActionOutcome::Applied {
                output: PlatformActionOutput::WriteComplete {
                    metadata: DocumentMetadata {
                        target: WorkspaceTarget::Relative {
                            path: path.to_owned(),
                        },
                        document_handle: path.to_owned(),
                        kind: DocumentKind::File,
                        mime_type: None,
                        evidence: ActionEvidence {
                            length: bytes.len() as u64,
                            digest: ContentDigest::Verified { hex: digest },
                            fingerprint: format!("fp.{}", path.replace('/', ".")),
                        },
                    },
                },
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
                document_handle: relative.clone(),
                kind,
                mime_type: None,
                evidence: ActionEvidence {
                    length: bytes.len() as u64,
                    digest: if matches!(kind, DocumentKind::Directory) {
                        ContentDigest::Unknown
                    } else {
                        ContentDigest::Verified { hex: digest }
                    },
                    fingerprint: format!("fp.{}", relative.replace('/', ".")),
                },
            }
        }

        fn metadata_for_relative(&self, relative: &str) -> DocumentMetadata {
            let full = self.workspace_root.join(relative);
            let metadata = fs::metadata(&full).test_ok("metadata");
            let kind = if metadata.is_dir() {
                DocumentKind::Directory
            } else {
                DocumentKind::File
            };
            let bytes = if metadata.is_file() {
                fs::read(&full).test_ok("read relative file")
            } else {
                Vec::new()
            };
            let digest = {
                use sha2::{Digest, Sha256};
                format!("{:x}", Sha256::digest(&bytes))
            };
            DocumentMetadata {
                target: WorkspaceTarget::Relative {
                    path: relative.to_owned(),
                },
                document_handle: relative.to_owned(),
                kind,
                mime_type: None,
                evidence: ActionEvidence {
                    length: bytes.len() as u64,
                    digest: if matches!(kind, DocumentKind::Directory) {
                        ContentDigest::Unknown
                    } else {
                        ContentDigest::Verified { hex: digest }
                    },
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
            | PlatformAction::ArtifactWrite { action_id, .. }
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
    fn ffi_document_command_replace_writes_once() {
        let harness = Harness::new();
        let original = b"- 10:00:00\nold\n";
        harness.write_file("2024-02-02.md", original);
        let job_id = harness
            .engine
            .start_workspace_document_command(
                WorkspaceDocumentCommand {
                    path: "2024-02-02.md".to_owned(),
                    expected_state: WorkspaceDocumentExpectedState::Match {
                        fingerprint: fingerprint_of(original),
                    },
                    command: WorkspaceDocumentCommandKind::Replace {
                        identity: "2024-02-02_10:00:00_0".to_owned(),
                        content: "new".to_owned(),
                    },
                    history: None,
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
        let affected = result.affected_memo.expect("affected memo facts");
        assert_eq!(affected.identity, "2024-02-02_10:00:00_0");
        assert_eq!(affected.path, result.path);
        assert_eq!(affected.fingerprint, result.result_fingerprint);
        assert_eq!(affected.time_part, "10:00:00");
        let after = fs::read(harness.workspace_root.join("2024-02-02.md")).test_ok("read");
        assert!(after.windows(3).any(|window| window == b"new"));
    }
}
