//! Behavior Contract
//! Capability: memo write commands are validated once, so JSON, native and host construction
//! cannot persist an over-budget body, a malformed time token, or a malformed baseline fingerprint.
//! Owning layer: lomo-application (validated command boundary); priority: P0.
//!
//! Scenarios:
//! - Given a legal create command, when encoded and decoded, then it round-trips unchanged.
//! - Given an over-budget body in JSON, when decoded, then decoding fails.
//! - Given a malformed time token in JSON, when decoded, then decoding fails.
//! - Given a malformed expected fingerprint in JSON, when decoded, then decoding fails.
//! - Given a pin command, when JSON carries a non-positive timestamp or an unpin that still
//!   carries a timestamp, then decoding fails and no command exists.
//! - Given a host-constructed invalid command, when submitted, then the session rejects it before
//!   acquiring the transaction lock and writes no workspace file.
//!
//! Observable outcomes: decode errors, session error categories, and the workspace directory contents.
//!
//! TDD proof: `cargo test -p lomo-application --test session_command_validation_contract --locked`.
//! The audit's `command_json_cannot_construct_invalid_body_time_or_edit_baseline` is the RED lock
//! for the JSON path; these cases keep each parameter separate so one failure cannot mask another.
//! RED: `pin_json_cannot_carry_a_non_positive_timestamp` and
//! `pin_json_cannot_request_an_unpin_that_carries_a_timestamp` decoded successfully while
//! `PinMemoRequest` still exposed its raw fields.
//!
//! Excludes: UI, network providers, and the write-path transaction internals.

#[cfg(test)]
mod tests {
    use std::{fmt::Debug, fs, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, PinMemoRequest, PinPolicy, UpdateMemoRequest,
        WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, ErrorCategory, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{MAX_EDITABLE_MEMO_UTF8_CHARS, MemoId, WorkspaceRootId};

    trait TestResult<T> {
        fn value(self) -> T;
    }

    impl<T, E: Debug> TestResult<T> for Result<T, E> {
        fn value(self) -> T {
            match self {
                Ok(value) => value,
                Err(error) => panic!("unexpected failure: {error:?}"),
            }
        }
    }

    struct Fixture {
        temp: tempfile::TempDir,
        config: WorkspaceSessionConfig,
        executor: Arc<FsPlatformActionExecutor>,
    }

    impl Fixture {
        fn new() -> Self {
            let temp = tempfile::tempdir().value();
            fs::create_dir(temp.path().join("notes")).value();
            let capability = CapabilityToken::parse("notes").value();
            let config = WorkspaceSessionConfig {
                capability: capability.clone(),
                root_id: WorkspaceRootId::Notes,
                workspace_generation: lomo_workspace::WorkspaceGenerationId::mint().value(),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: temp.path().join("state"),
                cache_dir: temp.path().join("cache"),
                runtime_dir: temp.path().join("runtime"),
                exchange_dir: temp.path().join("exchange"),
                media_stage_root: temp.path().join("media-stage"),
            };
            let executor = Arc::new(FsPlatformActionExecutor::new(&config.exchange_dir).value());
            executor
                .bind_root(capability, temp.path().join("notes"))
                .value();
            Self {
                temp,
                config,
                executor,
            }
        }

        fn open(&self) -> WorkspaceSession {
            let executor = Arc::clone(&self.executor);
            WorkspaceSession::open(self.config.clone(), executor).value()
        }

        fn notes_entries(&self) -> usize {
            fs::read_dir(self.temp.path().join("notes")).value().count()
        }
    }

    fn valid_create() -> CreateMemoRequest {
        CreateMemoRequest {
            operation_id: OperationId::parse("typed-create").value(),
            relative_path: Some(RelativeWorkspacePath::parse("2026_09_09.md").value()),
            time_token: Some("09:00:00".to_owned()),
            content: "valid body".to_owned(),
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: Vec::new(),
            chronology_epoch_ms: None,
        }
    }

    fn create_json_with(key: &str, invalid: serde_json::Value) -> serde_json::Value {
        let mut value = serde_json::to_value(valid_create()).value();
        value
            .as_object_mut()
            .unwrap_or_else(|| panic!("create request JSON object"))
            .insert(key.to_owned(), invalid);
        value
    }

    #[test]
    fn valid_create_command_round_trips_through_json() {
        let request = valid_create();
        let value = serde_json::to_value(&request).value();
        let decoded: CreateMemoRequest = serde_json::from_value(value).value();
        assert_eq!(decoded, request);
    }

    #[test]
    fn over_budget_json_body_is_rejected() {
        let content = serde_json::json!("x".repeat(MAX_EDITABLE_MEMO_UTF8_CHARS + 1));
        let Err(_error) =
            serde_json::from_value::<CreateMemoRequest>(create_json_with("content", content))
        else {
            panic!("over-budget body decoded as a command");
        };
    }

    #[test]
    fn malformed_json_time_token_is_rejected() {
        let value = create_json_with("time_token", serde_json::json!("99:99"));
        let Err(_error) = serde_json::from_value::<CreateMemoRequest>(value) else {
            panic!("malformed time token decoded as a command");
        };
    }

    #[test]
    fn malformed_json_fingerprint_is_rejected() {
        let value = create_json_with(
            "expected_document_fingerprint",
            serde_json::json!("not-a-fingerprint"),
        );
        let Err(_error) = serde_json::from_value::<CreateMemoRequest>(value) else {
            panic!("malformed fingerprint decoded as a command");
        };
    }

    #[test]
    fn update_and_delete_json_require_a_valid_fingerprint() {
        let mut update = serde_json::to_value(UpdateMemoRequest {
            operation_id: OperationId::parse("update").value(),
            memo_id: MemoId::parse("memo-1").value(),
            content: "body".to_owned(),
            expected_document_fingerprint: "0".repeat(64),
            pending_promotes: Vec::new(),
        })
        .value();
        let mut delete = serde_json::to_value(DeleteMemoRequest {
            operation_id: OperationId::parse("delete").value(),
            memo_id: MemoId::parse("memo-1").value(),
            expected_document_fingerprint: "0".repeat(64),
            trashed_at_ms: None,
        })
        .value();
        for value in [&mut update, &mut delete] {
            value["expected_document_fingerprint"] = serde_json::json!("not-a-fingerprint");
        }
        let Err(_error) = serde_json::from_value::<UpdateMemoRequest>(update) else {
            panic!("malformed fingerprint decoded as an update command");
        };
        let Err(_error) = serde_json::from_value::<DeleteMemoRequest>(delete) else {
            panic!("malformed fingerprint decoded as a delete command");
        };
    }

    #[test]
    fn pin_json_cannot_carry_a_non_positive_timestamp() {
        let value = serde_json::json!({
            "operation_id": "pin-non-positive",
            "memo_id": "memo-1",
            "pinned": true,
            "pinned_at_ms": 0,
        });
        let Err(_error) = serde_json::from_value::<PinMemoRequest>(value) else {
            panic!("non-positive pin timestamp decoded as a command");
        };
    }

    #[test]
    fn pin_json_cannot_request_an_unpin_that_carries_a_timestamp() {
        let value = serde_json::json!({
            "operation_id": "pin-unpin-with-time",
            "memo_id": "memo-1",
            "pinned": false,
            "pinned_at_ms": 1_700_000_000_000_i64,
        });
        let Err(_error) = serde_json::from_value::<PinMemoRequest>(value) else {
            panic!("an unpin carrying a pin timestamp decoded as a command");
        };
    }

    #[test]
    fn valid_pin_command_round_trips_through_json() {
        let request = PinMemoRequest::new(
            OperationId::parse("pin-round-trip").value(),
            MemoId::parse("memo-1").value(),
            PinPolicy::Pinned {
                at_ms: Some(1_700_000_000_000),
            },
        )
        .value();
        let value = serde_json::to_value(&request).value();
        let decoded: PinMemoRequest = serde_json::from_value(value).value();
        assert_eq!(decoded, request);
    }

    #[test]
    fn host_constructed_non_positive_pin_cannot_become_a_command() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let created = session.create_memo(valid_create()).value();
        let Err(error) = PinMemoRequest::new(
            OperationId::parse("pin-invalid").value(),
            created.memo_id.clone(),
            PinPolicy::Pinned { at_ms: Some(0) },
        ) else {
            panic!("a non-positive pin timestamp must not become a command");
        };
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(
            session
                .get_memo(&created.memo_id)
                .value()
                .map(|memo| memo.is_pinned),
            Some(false)
        );
    }

    #[test]
    fn host_constructed_over_budget_create_is_rejected_before_lock_or_write() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let mut request = valid_create();
        request.content = "x".repeat(MAX_EDITABLE_MEMO_UTF8_CHARS + 1);
        let Err(error) = session.create_memo(request) else {
            panic!("host-constructed over-budget create must be rejected");
        };
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(fixture.notes_entries(), 0);
    }

    #[test]
    fn host_constructed_malformed_fingerprint_update_is_rejected_without_mutation() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let created = session.create_memo(valid_create()).value();
        let request = UpdateMemoRequest {
            operation_id: OperationId::parse("update-invalid").value(),
            memo_id: created.memo_id,
            content: "edited".to_owned(),
            expected_document_fingerprint: "zzzz".to_owned(),
            pending_promotes: Vec::new(),
        };
        let Err(error) = session.update_memo(request) else {
            panic!("malformed fingerprint update must be rejected");
        };
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert!(
            !fs::read_to_string(fixture.temp.path().join("notes/2026_09_09.md"))
                .value()
                .contains("edited")
        );
    }
}
