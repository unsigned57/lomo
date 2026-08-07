//! Behavior Contract
//!
//! Capability: map every formal core platform action/output/error variant through the unique
//! `lomo-native` `BoltFFI` conversion surface without losing identity or category semantics.
//!
//! Scenarios:
//! - Given every core platform action constructor, when converted to FFI, then the matching
//!   facade variant is produced.
//! - Given every platform output and failure category/retry, when converted from FFI, then core
//!   accepts the structured result.
//! - Given workspace/result/state/job terminal mappings, when converted, then facade enums and
//!   `EngineError` display fields remain stable.
//!
//! Observable outcomes: converted enum variants, error category/code/display, batch attempt prefix.
//! TDD proof: fails when a conversion branch is missing or architecture re-embeds tests in src/.
//! Excludes: Android SAF execution, generated Kotlin syntax, and product write authority.

use lomo_core as core;
use lomo_native::*;

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use super::*;

    fn digest() -> String {
        "a".repeat(64)
    }

    fn evidence() -> ActionEvidence {
        ActionEvidence {
            length: 1,
            digest: digest(),
            fingerprint: "fp-coverage".to_owned(),
        }
    }

    fn core_evidence() -> core::ActionEvidence {
        core::ActionEvidence::verified(
            1,
            core::Sha256Digest::parse(&digest()).test_ok("digest"),
            "fp-coverage",
        )
        .test_ok("evidence")
    }

    fn relative_path() -> core::RelativeWorkspacePath {
        core::RelativeWorkspacePath::parse("notes/a.md").test_ok("path")
    }

    fn action_id(raw: &str) -> core::ActionId {
        core::ActionId::parse(raw).test_ok("action id")
    }

    fn capability() -> core::CapabilityToken {
        core::CapabilityToken::parse("cap-coverage").test_ok("capability")
    }

    fn exchange_artifact() -> core::ExchangeArtifact {
        core::ExchangeArtifact::new(
            "exchange-token-1",
            1,
            core::Sha256Digest::parse(&digest()).test_ok("digest"),
        )
        .test_ok("artifact")
    }

    fn assert_action_round_trip(action: &core::PlatformAction) {
        let ffi = action_to_ffi(action);
        match (action, &ffi) {
            (core::PlatformAction::Stat { .. }, PlatformAction::Stat { .. })
            | (core::PlatformAction::ListChildren { .. }, PlatformAction::ListChildren { .. })
            | (
                core::PlatformAction::EnsureDirectory { .. },
                PlatformAction::EnsureDirectory { .. },
            )
            | (
                core::PlatformAction::ReadToExchange { .. },
                PlatformAction::ReadToExchange { .. },
            )
            | (
                core::PlatformAction::WriteFromExchange { .. },
                PlatformAction::WriteFromExchange { .. },
            )
            | (core::PlatformAction::Move { .. }, PlatformAction::Move { .. })
            | (core::PlatformAction::Delete { .. }, PlatformAction::Delete { .. }) => {}
            other => panic!("action conversion mismatch: {other:?}"),
        }
    }

    #[test]
    fn action_to_ffi_covers_every_platform_action_variant() {
        let capability = capability();
        let path = relative_path();
        let artifact = exchange_artifact();
        let expected = core::ExpectedFingerprint::matching(core_evidence());
        let actions = [
            core::PlatformAction::stat_root(action_id("stat-root"), capability.clone()),
            core::PlatformAction::stat(action_id("stat-rel"), capability.clone(), path.clone()),
            core::PlatformAction::list_root(
                action_id("list-root"),
                capability.clone(),
                Some("cursor-1".to_owned()),
                core::PageSize::new(8).test_ok("page"),
            ),
            core::PlatformAction::list_children(
                action_id("list-rel"),
                capability.clone(),
                path.clone(),
                None,
                core::PageSize::new(8).test_ok("page"),
            ),
            core::PlatformAction::ensure_directory(
                action_id("ensure"),
                capability.clone(),
                path.clone(),
            ),
            core::PlatformAction::read_to_exchange(
                action_id("read"),
                capability.clone(),
                path.clone(),
                "exchange-token-1",
                expected.clone(),
            )
            .test_ok("read action"),
            core::PlatformAction::write_from_exchange(
                action_id("write-create"),
                capability.clone(),
                artifact.clone(),
                path.clone(),
                core::WriteMode::Create,
                core::ExpectedFingerprint::absent(),
            ),
            core::PlatformAction::write_from_exchange(
                action_id("write-replace"),
                capability.clone(),
                artifact.clone(),
                path.clone(),
                core::WriteMode::Replace,
                expected.clone(),
            ),
            core::PlatformAction::move_path(
                action_id("move"),
                capability.clone(),
                path.clone(),
                core::RelativeWorkspacePath::parse("notes/b.md").test_ok("target"),
                expected.clone(),
                core::ExpectedFingerprint::absent(),
            ),
            core::PlatformAction::delete(action_id("delete"), capability, path, expected),
        ];
        for action in &actions {
            assert_action_round_trip(action);
        }
        let absent: ExpectedFingerprint = expected_to_ffi(&core::ExpectedFingerprint::absent());
        let matching: ExpectedFingerprint =
            expected_to_ffi(&core::ExpectedFingerprint::matching(core_evidence()));
        let artifact_ffi: ExchangeArtifact = artifact_to_ffi(&artifact);
        let evidence_ffi: ActionEvidence = evidence_to_ffi(&core_evidence());
        let root: WorkspaceTarget = target_to_ffi(&core::WorkspaceTarget::Root);
        let relative: WorkspaceTarget =
            target_to_ffi(&core::WorkspaceTarget::Relative(relative_path()));
        assert!(matches!(absent, ExpectedFingerprint::Absent));
        assert!(matches!(matching, ExpectedFingerprint::Match { .. }));
        assert_eq!(artifact_ffi.token, "exchange-token-1");
        assert_eq!(evidence_ffi.fingerprint, "fp-coverage");
        assert!(matches!(root, WorkspaceTarget::Root));
        assert!(matches!(relative, WorkspaceTarget::Relative { .. }));
    }

    #[test]
    fn output_from_ffi_covers_every_platform_output_variant() {
        let metadata = DocumentMetadata {
            target: WorkspaceTarget::Root,
            document_handle: "root-document".to_owned(),
            kind: DocumentKind::Directory,
            mime_type: Some("inode/directory".to_owned()),
            evidence: evidence(),
        };
        let relative_metadata = DocumentMetadata {
            target: WorkspaceTarget::Relative {
                path: "notes/a.md".to_owned(),
            },
            document_handle: "provider:notes/a.md".to_owned(),
            kind: DocumentKind::File,
            mime_type: Some("text/markdown".to_owned()),
            evidence: evidence(),
        };
        let outputs = [
            PlatformActionOutput::Stat {
                metadata: metadata.clone(),
            },
            PlatformActionOutput::Listed {
                page: MetadataPage {
                    items: vec![relative_metadata.clone()],
                    next_cursor: Some("next".to_owned()),
                },
            },
            PlatformActionOutput::DirectoryReady {
                metadata: relative_metadata.clone(),
            },
            PlatformActionOutput::ReadToExchange {
                source_metadata: relative_metadata.clone(),
                artifact: ExchangeArtifact {
                    token: "exchange-token-1".to_owned(),
                    length: 1,
                    digest: digest(),
                },
            },
            PlatformActionOutput::WriteComplete {
                metadata: relative_metadata.clone(),
            },
            PlatformActionOutput::MoveComplete {
                metadata: relative_metadata,
            },
            PlatformActionOutput::DeleteComplete {
                absence: VerifiedAbsence {
                    target: WorkspaceTarget::Relative {
                        path: "notes/a.md".to_owned(),
                    },
                    fingerprint: "fp-deleted".to_owned(),
                },
            },
        ];
        for output in outputs {
            output_from_ffi(output).test_ok("output conversion");
        }

        let applied = action_result_from_ffi(ActionResult {
            action_id: "action-1".to_owned(),
            outcome: ActionOutcome::Applied {
                output: PlatformActionOutput::Stat {
                    metadata: metadata.clone(),
                },
            },
        })
        .test_ok("applied");
        assert!(matches!(applied.outcome(), core::ActionOutcome::Applied(_)));
        let satisfied = action_result_from_ffi(ActionResult {
            action_id: "action-2".to_owned(),
            outcome: ActionOutcome::AlreadySatisfied {
                output: PlatformActionOutput::Stat { metadata },
            },
        })
        .test_ok("already satisfied");
        assert!(matches!(
            satisfied.outcome(),
            core::ActionOutcome::AlreadySatisfied(_)
        ));
        let failed = action_result_from_ffi(ActionResult {
            action_id: "action-3".to_owned(),
            outcome: ActionOutcome::Failed {
                failure: EngineFailure {
                    category: "conflict".to_owned(),
                    code: "platform_postcondition_mismatch".to_owned(),
                    retry_disposition: "after_user_action".to_owned(),
                    operation_id: Some("op-1".to_owned()),
                    job_id: Some("job-1".to_owned()),
                    diagnostic: "mismatch".to_owned(),
                },
            },
        })
        .test_ok("failed");
        assert!(matches!(failed.outcome(), core::ActionOutcome::Failed(_)));
    }

    #[test]
    fn failure_mapping_covers_every_category_and_retry_disposition() {
        for (category, retry) in [
            ("validation", "never"),
            ("permission", "after_user_action"),
            ("corruption", "never"),
            ("storage", "transient"),
            ("network", "transient"),
            ("authentication", "after_user_action"),
            ("conflict", "after_user_action"),
            ("cancelled", "never"),
            ("timeout", "transient"),
            ("busy", "transient"),
            ("resource_limit", "never"),
            ("internal", "never"),
        ] {
            failure_to_core(&EngineFailure {
                category: category.to_owned(),
                code: "code".to_owned(),
                retry_disposition: retry.to_owned(),
                operation_id: None,
                job_id: None,
                diagnostic: "diag".to_owned(),
            })
            .test_ok("category/retry mapping");
        }
        category_from_name("not-a-category").test_err("expected failure");
        retry_from_name("not-a-retry").test_err("expected failure");
        let invalid = invalid_platform_failure();
        assert_eq!(invalid.category(), "validation");
        assert_eq!(invalid.code(), "invalid_platform_error");
        assert!(invalid.to_string().contains("invalid_platform_error"));
    }

    #[test]
    fn workspace_and_result_boundary_conversion_round_trips() {
        let temporary = tempfile::tempdir().test_ok("temp root");
        let direct_root = temporary.path().join("workspace");
        std::fs::create_dir(&direct_root).test_ok("workspace dir");
        workspace_from_ffi(WorkspaceDescriptor::Direct {
            root_path: direct_root.display().to_string(),
        })
        .test_ok("direct workspace");
        workspace_from_ffi(WorkspaceDescriptor::Saf {
            stable_workspace_id: "ws-saf-conversion-coverage".to_owned(),
            capability_token: "cap-coverage".to_owned(),
        })
        .test_ok("saf workspace");
        let batch = result_from_ffi(PlatformBatchResult {
            schema_version: 1,
            job_id: "job-coverage".to_owned(),
            batch_id: "batch-coverage".to_owned(),
            attempt: 1,
            action_results: vec![ActionResult {
                action_id: "action-coverage".to_owned(),
                outcome: ActionOutcome::Applied {
                    output: PlatformActionOutput::DeleteComplete {
                        absence: VerifiedAbsence {
                            target: WorkspaceTarget::Root,
                            fingerprint: "fp-root".to_owned(),
                        },
                    },
                },
            }],
        })
        .test_ok("batch result");
        assert_eq!(batch.action_results().len(), 1);

        let error = EngineError::from(
            core::LomoError::from_platform_boundary(
                core::ErrorCategory::Validation,
                "sample_validation",
                core::RetryDisposition::Never,
                None,
                None,
                "sample diagnostic",
            )
            .test_ok("platform validation error"),
        );
        assert_eq!(error.category(), "validation");
        assert_eq!(error.code(), "sample_validation");
        assert!(error.to_string().contains("sample diagnostic"));
        assert_eq!(
            cancel_to_ffi(core::CancelOutcome::UnknownJob),
            CancelOutcome::UnknownJob
        );
        assert_eq!(
            shutdown_to_ffi(core::ShutdownOutcome::DeadlineExceeded),
            ShutdownOutcome::DeadlineExceeded
        );
        assert_eq!(
            shutdown_to_ffi(core::ShutdownOutcome::AlreadyShutdown),
            ShutdownOutcome::AlreadyShutdown
        );
        let awaiting: EngineState = state_to_ffi(core::EngineState::AwaitingWorkspaceSelection);
        let shutting_down: EngineState = state_to_ffi(core::EngineState::ShuttingDown);
        let running: JobStep = job_step_to_ffi(core::JobStep::Running);
        let running_native: JobStep = job_step_to_ffi(core::JobStep::RunningNative {
            task_kind: "sync-preflight".to_owned(),
            attempt: 2,
            dispatch_generation: 7,
        });
        assert!(matches!(awaiting, EngineState::AwaitingWorkspaceSelection));
        assert!(matches!(shutting_down, EngineState::ShuttingDown));
        assert!(matches!(running, JobStep::Running));
        assert!(matches!(
            running_native,
            JobStep::RunningNative {
                task_kind,
                attempt: 2,
                dispatch_generation: 7,
            } if task_kind == "sync-preflight"
        ));
        // SyncPlannerError conversion remains owned by lomo-sync.
    }
}
