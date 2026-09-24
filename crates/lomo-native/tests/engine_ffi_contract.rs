//! Behavior Contract
//!
//! Capability: expose the formal application kernel through the unique `lomo-native` `BoltFFI`
//! facade without duplicating lifecycle or job decisions.
//!
//! Scenarios:
//! - Given raw direct/SAF FFI config, when the engine opens, then boundary validation produces the
//!   same structured state and error fields as `lomo-core`.
//! - Given a SAF bootstrap job, when Kotlin-style callers poll, cancel, and shut down, then opaque
//!   ids remain strings but terminal decisions remain owned by core.
//! - Given an invalid stable workspace id, capability, or deadline, when it crosses FFI, then the
//!   boundary returns a structured validation error and creates no engine state.
//!
//! Observable outcomes: exported state/job/cancel/shutdown enums, stable error fields, and the sole
//! event-sequence gap law.
//! TDD proof: the core descriptor/engine contracts were RED on 2026-07-27 before the FFI production
//! edit because stable SAF identity could not cross independently from the capability; this
//! companion contract locks the facade validation/mapping outcome.
//! Excludes: generated Kotlin syntax, Android SAF execution, and frozen sync-v1 planning.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use std::fs;

    use lomo_native::{
        ActionEvidence, ActionOutcome, ActionResult, ContentDigest, DocumentKind, DocumentMetadata,
        EngineConfig, EngineState, ExchangeArtifact, LomoEngine, MetadataPage, PlatformAction,
        PlatformActionOutput, PlatformBatchResult, ShutdownOutcome, VerifiedAbsence,
        WorkspaceDescriptor, WorkspaceTarget,
    };
    use tempfile::tempdir;

    #[test]
    fn ffi_lifecycle_preserves_core_job_and_error_ownership() {
        let temporary = tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).test_ok("control root");
        fs::create_dir(&exchange).test_ok("exchange root");
        let config = EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-root-ffi".to_owned(),
                capability_token: "saf-root-ffi".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        };
        let engine = LomoEngine::open(config).test_ok("FFI engine");
        let job_id = match engine.state() {
            EngineState::Opening { job_id } => job_id,
            other @ (EngineState::AwaitingWorkspaceSelection
            | EngineState::Ready { .. }
            | EngineState::ReadOnlyRecovery { .. }
            | EngineState::ShuttingDown) => {
                panic!("SAF FFI engine must be opening, got {other:?}")
            }
        };
        let step = engine.poll_job(job_id).test_ok("poll bootstrap");
        assert!(matches!(
            step,
            lomo_native::JobStep::NeedsPlatformBatch { .. }
        ));
        assert_eq!(
            engine.shutdown(5_000).test_ok("shutdown"),
            ShutdownOutcome::Completed
        );
    }

    #[test]
    fn ffi_rejects_invalid_capability_before_engine_creation() {
        let temporary = tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).test_ok("control root");
        fs::create_dir(&exchange).test_ok("exchange root");
        let error = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-invalid-capability".to_owned(),
                capability_token: "../escaped".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_err("invalid capability");
        assert_eq!(error.category(), "validation");
        assert_eq!(error.code(), "invalid_capability_token");
    }

    #[test]
    fn ffi_rejects_invalid_stable_workspace_identity_before_engine_creation() {
        let temporary = tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).test_ok("control root");
        fs::create_dir(&exchange).test_ok("exchange root");
        let error = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "../escaped".to_owned(),
                capability_token: "saf-valid-capability".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_err("invalid stable workspace identity");
        assert_eq!(error.category(), "validation");
        assert_eq!(error.code(), "invalid_workspace_id");
    }

    #[test]
    fn ffi_submit_preserves_the_formal_core_protocol() {
        let temporary = tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).test_ok("control root");
        fs::create_dir(&exchange).test_ok("exchange root");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-root-submit".to_owned(),
                capability_token: "saf-root-submit".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("FFI engine");
        let job_id = match engine.state() {
            EngineState::Opening { job_id } => job_id,
            other @ (EngineState::AwaitingWorkspaceSelection
            | EngineState::Ready { .. }
            | EngineState::ReadOnlyRecovery { .. }
            | EngineState::ShuttingDown) => {
                panic!("SAF FFI engine must be opening, got {other:?}")
            }
        };
        let lomo_native::JobStep::NeedsPlatformBatch { batch } =
            engine.poll_job(job_id.clone()).test_ok("poll bootstrap")
        else {
            panic!("bootstrap batch is required");
        };
        let action_results = batch
            .actions
            .iter()
            .map(|action| ActionResult {
                action_id: action_id(action).to_owned(),
                outcome: ActionOutcome::Applied {
                    output: output_for(action),
                },
            })
            .collect();
        let step = engine
            .submit_platform_result(
                job_id,
                PlatformBatchResult {
                    schema_version: batch.schema_version,
                    job_id: batch.job_id,
                    batch_id: batch.batch_id,
                    attempt: batch.attempt,
                    action_results,
                },
            )
            .test_ok("submit complete result");
        assert!(matches!(step, lomo_native::JobStep::Completed));
        assert!(matches!(engine.state(), EngineState::Ready { .. }));
    }

    #[test]
    fn ffi_event_sequence_gap_law_matches_core() {
        assert!(!lomo_native::event_sequence_requires_full_invalidate(3, 3));
        assert!(!lomo_native::event_sequence_requires_full_invalidate(3, 4));
        assert!(lomo_native::event_sequence_requires_full_invalidate(3, 5));
        assert!(lomo_native::event_sequence_requires_full_invalidate(3, 2));
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

    fn output_for(action: &PlatformAction) -> PlatformActionOutput {
        let evidence = || ActionEvidence {
            length: 0,
            digest: ContentDigest::Verified {
                hex: "d".repeat(64),
            },
            fingerprint: "ffi-root-fingerprint".to_owned(),
        };
        let metadata = |target: WorkspaceTarget, kind: DocumentKind| DocumentMetadata {
            target,
            document_handle: "fixture-document".to_owned(),
            kind,
            mime_type: None,
            evidence: evidence(),
        };
        match action {
            PlatformAction::Stat { target, .. } => PlatformActionOutput::Stat {
                metadata: metadata(target.clone(), DocumentKind::Directory),
            },
            PlatformAction::ListChildren { .. } => PlatformActionOutput::Listed {
                page: MetadataPage {
                    items: Vec::new(),
                    next_cursor: None,
                },
            },
            PlatformAction::EnsureDirectory { path, .. } => PlatformActionOutput::DirectoryReady {
                metadata: metadata(
                    WorkspaceTarget::Relative { path: path.clone() },
                    DocumentKind::Directory,
                ),
            },
            PlatformAction::ReadToExchange {
                path,
                exchange_token,
                ..
            } => PlatformActionOutput::ReadToExchange {
                source_metadata: metadata(
                    WorkspaceTarget::Relative { path: path.clone() },
                    DocumentKind::File,
                ),
                artifact: ExchangeArtifact {
                    token: exchange_token.clone(),
                    length: 0,
                    digest: "d".repeat(64),
                },
            },
            PlatformAction::WriteFromExchange { artifact, path, .. } => {
                PlatformActionOutput::WriteComplete {
                    metadata: DocumentMetadata {
                        target: WorkspaceTarget::Relative { path: path.clone() },
                        document_handle: path.clone(),
                        kind: DocumentKind::File,
                        mime_type: None,
                        evidence: ActionEvidence {
                            length: artifact.length,
                            digest: ContentDigest::Verified {
                                hex: artifact.digest.clone(),
                            },
                            fingerprint: "ffi-root-fingerprint".to_owned(),
                        },
                    },
                }
            }
            PlatformAction::ArtifactWrite { source, path, .. } => {
                PlatformActionOutput::WriteComplete {
                    metadata: DocumentMetadata {
                        target: WorkspaceTarget::Relative { path: path.clone() },
                        document_handle: path.clone(),
                        kind: DocumentKind::File,
                        mime_type: None,
                        evidence: ActionEvidence {
                            length: source.length,
                            digest: ContentDigest::Verified {
                                hex: source.digest.clone(),
                            },
                            fingerprint: "ffi-root-fingerprint".to_owned(),
                        },
                    },
                }
            }
            PlatformAction::Move { target, .. } => PlatformActionOutput::MoveComplete {
                metadata: metadata(
                    WorkspaceTarget::Relative {
                        path: target.clone(),
                    },
                    DocumentKind::File,
                ),
            },
            PlatformAction::Delete { path, .. } => PlatformActionOutput::DeleteComplete {
                absence: VerifiedAbsence {
                    target: WorkspaceTarget::Relative { path: path.clone() },
                    fingerprint: "ffi-deleted-fingerprint".to_owned(),
                },
            },
        }
    }
}
