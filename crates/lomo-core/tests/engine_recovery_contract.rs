//! Behavior Contract
//!
//! Capability: own one recoverable engine generation per workspace through an OS-backed lock and
//! a checksummed, atomically published control journal.
//!
//! Scenarios:
//! - Given one engine owns a canonical workspace, when another engine opens the same identity,
//!   then it fails with a structured busy error; after owner drop, reopen succeeds.
//! - Given one SAF tree is reopened with a rotated process capability, when both descriptors carry
//!   the same stable identity, then the second engine is Busy and later resumes the same journal.
//! - Given a freshly published-incomplete lock, when another engine opens, then it is Busy instead
//!   of reclaiming a creator that may still be initializing.
//! - Given a dead owner or a reused PID with different process start identity, when engines race to
//!   reclaim, then exactly one becomes owner.
//! - Given a dead reclaimer leaves its atomic claim directory behind, when a later engine opens,
//!   then it removes the stale claim and reclaims the dead workspace lock.
//! - Given an old owner observes a replacement nonce during Drop, then it preserves the replacement
//!   lock instead of deleting another engine's authority.
//! - Given a direct workspace completes bootstrap, when it reopens, then `CoreRevision` remains
//!   zero while `EventSequence` advances only for the durable lifecycle event.
//! - Given an uncommitted journal candidate remains beside the committed journal, when the engine
//!   reopens, then only the committed journal is authoritative.
//! - Given a truncated envelope, unknown schema, or bad checksum, when the engine opens, then it
//!   fails closed and preserves the corrupt bytes for recovery diagnostics.
//! - Given a checksummed schema-v2 journal whose read action predates opaque document locators,
//!   when the engine opens, then the path is migrated into the canonical locator and the current
//!   journal schema is atomically republished.
//!   atomically republished.
//!
//! Observable outcomes: `EngineState`, stable workspace identity, error category/code, lock
//! release, and exact durable journal bytes.
//! TDD proof: P0-02 was RED on 2026-07-27 with E0061; P0-03 is RED while a fresh lock directory
//! without `owner.pid` is immediately reclaimed and an old owner Drop removes any replacement;
//! Android-safe stale claim recovery was RED on 2026-08-02 with `workspace_lock_unavailable`
//! because the file-based protocol tried to read the claim directory as a file.
//! Schema-v2 locator migration was RED on 2026-08-06 with `journal_payload_invalid` because adding
//! `DocumentLocator` changed the persisted action shape without advancing the journal schema.
//! Excludes: actor scheduling, listener delivery, cancellation races, SAF execution, and FFI.

#[cfg(test)]
#[path = "support/failure.rs"]
mod failure_support;
#[cfg(test)]
#[path = "support/option.rs"]
mod option_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
mod tests {

    use std::fs;
    use std::path::PathBuf;
    use std::sync::{Arc, Barrier};

    use lomo_core::{
        CapabilityToken, EngineConfig, EngineState, ErrorCategory, LomoEngine, WorkspaceDescriptor,
        WorkspaceId,
    };
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    use super::failure_support::ResultFailureTestExt;
    use super::option_support::OptionTestExt;
    use super::support::ResultTestExt;

    struct Fixture {
        _temporary: tempfile::TempDir,
        config: EngineConfig,
    }

    impl Fixture {
        fn new() -> Self {
            let temporary = tempdir().must_succeed("temporary root");
            let control = temporary.path().join("control");
            let exchange = temporary.path().join("exchange");
            let workspace = temporary.path().join("workspace");
            fs::create_dir(&control).must_succeed("control root");
            fs::create_dir(&exchange).must_succeed("exchange root");
            fs::create_dir(&workspace).must_succeed("workspace root");
            let descriptor =
                WorkspaceDescriptor::direct(workspace).must_succeed("workspace descriptor");
            let config = EngineConfig::new(control, exchange, Some(descriptor))
                .must_succeed("engine config");
            Self {
                _temporary: temporary,
                config,
            }
        }
    }

    fn lock_dir(config: &EngineConfig) -> PathBuf {
        config
            .journal_path()
            .must_succeed("configured workspace journal")
            .parent()
            .must_succeed("workspace control directory")
            .join("engine.lock")
    }

    fn owner_record(config: &EngineConfig) -> serde_json::Value {
        let bytes = fs::read(lock_dir(config).join("owner.json")).must_succeed("owner record");
        serde_json::from_slice(&bytes).must_succeed("owner record JSON")
    }

    fn write_owner_record(config: &EngineConfig, record: &serde_json::Value) {
        let bytes = serde_json::to_vec(record).must_succeed("owner record encoding");
        fs::write(lock_dir(config).join("owner.json"), bytes).must_succeed("owner record write");
    }

    /// Replaces one owner-record field, failing loudly when the record is not a JSON object.
    fn set_owner_field(record: &mut serde_json::Value, key: &str, value: &str) {
        record
            .as_object_mut()
            .must_succeed("owner record object")
            .insert(key.to_owned(), serde_json::Value::String(value.to_owned()));
    }

    #[test]
    fn workspace_lock_is_exclusive_and_released_with_the_owner() {
        let fixture = Fixture::new();
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("first engine");

        let error = LomoEngine::open(fixture.config.clone()).must_fail("second engine must fail");
        assert_eq!(error.category(), ErrorCategory::Busy);
        assert_eq!(error.code(), "workspace_busy");

        drop(first);
        let reopened =
            LomoEngine::open(fixture.config).must_succeed("lock released after owner drop");
        assert!(matches!(reopened.state(), EngineState::Ready { .. }));
    }

    #[test]
    fn fresh_incomplete_workspace_lock_is_busy_instead_of_reclaimed() {
        let fixture = Fixture::new();
        let lock = lock_dir(&fixture.config);
        fs::create_dir_all(&lock).must_succeed("fresh incomplete lock");

        let error = LomoEngine::open(fixture.config)
            .must_fail("fresh incomplete creator must retain authority");

        assert_eq!(error.category(), ErrorCategory::Busy);
        assert_eq!(error.code(), "workspace_busy");
        assert!(lock.is_dir());
    }

    #[test]
    fn old_owner_drop_preserves_a_replacement_nonce() {
        let fixture = Fixture::new();
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("first engine");
        let mut replacement = owner_record(&fixture.config);
        set_owner_field(&mut replacement, "nonce", &"f".repeat(32));
        write_owner_record(&fixture.config, &replacement);

        drop(first);

        assert!(lock_dir(&fixture.config).is_dir());
        assert_eq!(owner_record(&fixture.config), replacement);
    }

    #[test]
    fn reused_pid_with_different_start_identity_is_reclaimed() {
        let fixture = Fixture::new();
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("first engine");
        let mut reused = owner_record(&fixture.config);
        set_owner_field(&mut reused, "nonce", &"e".repeat(32));
        set_owner_field(
            &mut reused,
            "process_start_identity",
            "different-boot:different-start",
        );
        write_owner_record(&fixture.config, &reused);
        drop(first);

        let reopened = LomoEngine::open(fixture.config)
            .must_succeed("PID reuse must not preserve stale ownership");

        assert!(matches!(reopened.state(), EngineState::Ready { .. }));
    }

    #[test]
    fn concurrent_stale_reclaim_has_exactly_one_owner() {
        let fixture = Fixture::new();
        let lock = lock_dir(&fixture.config);
        fs::create_dir_all(&lock).must_succeed("stale lock directory");
        fs::write(
            lock.join("owner.json"),
            br#"{"pid":4294967294,"process_start_identity":"dead-boot:1","nonce":"00000000000000000000000000000000","created_unix_millis":1}"#,
        )
        .must_succeed("stale owner record");
        let start = Arc::new(Barrier::new(3));
        let finish = Arc::new(Barrier::new(2));
        // Both reclaimers must be spawned before any is joined, so the barrier can release them
        // into the same reclaim window.
        let mut handles = Vec::with_capacity(2);
        for _ in 0..2 {
            let config = fixture.config.clone();
            let start = Arc::clone(&start);
            let finish = Arc::clone(&finish);
            handles.push(std::thread::spawn(move || {
                start.wait();
                let result = LomoEngine::open(config);
                finish.wait();
                result
            }));
        }
        start.wait();
        let mut results = Vec::with_capacity(handles.len());
        for handle in handles {
            results.push(handle.join().must_succeed("reclaimer thread"));
        }

        assert_eq!(results.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(results.iter().filter(|result| result.is_err()).count(), 1);
        for error in results.iter().filter_map(|result| result.as_ref().err()) {
            assert_eq!(error.category(), ErrorCategory::Busy);
            assert_eq!(error.code(), "workspace_busy");
        }
    }

    #[test]
    fn saf_capability_rotation_preserves_lock_and_journal_identity() {
        let temporary = tempdir().must_succeed("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).must_succeed("control root");
        fs::create_dir(&exchange).must_succeed("exchange root");
        let stable_identity =
            WorkspaceId::parse("ws-saf-primary-lomo").must_succeed("stable SAF identity");
        let first_config = EngineConfig::new(
            &control,
            &exchange,
            Some(WorkspaceDescriptor::saf(
                stable_identity.clone(),
                CapabilityToken::parse("cap-process-one").must_succeed("first capability"),
            )),
        )
        .must_succeed("first engine config");
        let rotated_config = EngineConfig::new(
            control,
            exchange,
            Some(WorkspaceDescriptor::saf(
                stable_identity,
                CapabilityToken::parse("cap-process-two").must_succeed("rotated capability"),
            )),
        )
        .must_succeed("rotated engine config");

        let first = LomoEngine::open(first_config).must_succeed("first SAF engine");
        let EngineState::Opening { job_id: first_job } = first.state() else {
            panic!("SAF engine must retain an opening bootstrap job");
        };
        let busy = LomoEngine::open(rotated_config.clone())
            .must_fail("rotated capability must not bypass the workspace lock");
        assert_eq!(busy.category(), ErrorCategory::Busy);
        assert_eq!(busy.code(), "workspace_busy");

        drop(first);
        let reopened = LomoEngine::open(rotated_config)
            .must_succeed("rotated capability must reopen the same workspace");
        let EngineState::Opening {
            job_id: reopened_job,
        } = reopened.state()
        else {
            panic!("reopened SAF engine must recover its bootstrap job");
        };
        assert_eq!(reopened_job, first_job);
    }

    #[test]
    fn workspace_lock_reclaims_stale_owner_from_dead_pid() {
        // Pure-safe create_dir+pid lock: a leftover lock dir with a non-existent owner pid must
        // be reclaimable so process death does not permanently brick the workspace.
        let fixture = Fixture::new();
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("seed journal + lock");
        let journal_path = fixture
            .config
            .journal_path()
            .must_succeed("configured workspace journal");
        let lock_dir = journal_path
            .parent()
            .must_succeed("journal parent")
            .join("engine.lock");
        drop(first);

        // Recreate a stale lock as if the owner process died without Drop.
        fs::create_dir(&lock_dir).must_succeed("stale lock dir");
        fs::write(
            lock_dir.join("owner.json"),
            br#"{"pid":4294967294,"process_start_identity":"dead-boot:1","nonce":"00000000000000000000000000000000","created_unix_millis":1}"#,
        )
        .must_succeed("dead owner record");

        let reopened = LomoEngine::open(fixture.config)
            .must_succeed("stale lock with dead owner must reclaim");
        assert!(matches!(reopened.state(), EngineState::Ready { .. }));
    }

    #[test]
    fn workspace_lock_reclaims_claim_abandoned_by_dead_process() {
        let fixture = Fixture::new();
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("seed journal + lock");
        let lock = lock_dir(&fixture.config);
        let control = lock
            .parent()
            .must_succeed("workspace control directory")
            .to_path_buf();
        drop(first);

        fs::create_dir(&lock).must_succeed("stale lock directory");
        let dead_owner =
            br#"{"pid":4294967294,"process_start_identity":"dead-boot:1","nonce":"00000000000000000000000000000000","created_unix_millis":1}"#;
        fs::write(lock.join("owner.json"), dead_owner).must_succeed("stale lock owner");
        let stale_claim = control.join("engine.lock.reclaim");
        fs::create_dir(&stale_claim).must_succeed("stale reclaim claim directory");
        fs::write(stale_claim.join("owner.json"), dead_owner).must_succeed("stale reclaim owner");

        let reopened = LomoEngine::open(fixture.config)
            .must_succeed("dead reclaim claim must not brick the workspace");

        assert!(matches!(reopened.state(), EngineState::Ready { .. }));
        assert!(!stale_claim.exists());
    }

    #[test]
    fn direct_bootstrap_is_durable_without_creating_a_domain_revision() {
        let fixture = Fixture::new();
        let journal_path = fixture
            .config
            .journal_path()
            .must_succeed("configured workspace journal");
        let first = LomoEngine::open(fixture.config.clone()).must_succeed("first engine");
        let first_sequence = match first.state() {
            EngineState::Ready {
                core_revision,
                event_sequence,
            } => {
                assert_eq!(core_revision.get(), 0);
                event_sequence.get()
            }
            EngineState::AwaitingWorkspaceSelection
            | EngineState::Opening { .. }
            | EngineState::ReadOnlyRecovery { .. }
            | EngineState::ShuttingDown => {
                panic!("direct workspace must be ready, got {:?}", first.state())
            }
        };
        drop(first);

        let committed = fs::read(&journal_path).must_succeed("committed journal");
        fs::write(
            journal_path.with_extension("candidate"),
            b"uncommitted candidate",
        )
        .must_succeed("orphan candidate");

        let reopened = LomoEngine::open(fixture.config).must_succeed("reopen committed state");
        match reopened.state() {
            EngineState::Ready {
                core_revision,
                event_sequence,
            } => {
                assert_eq!(core_revision.get(), 0);
                assert!(event_sequence.get() > first_sequence);
            }
            EngineState::AwaitingWorkspaceSelection
            | EngineState::Opening { .. }
            | EngineState::ReadOnlyRecovery { .. }
            | EngineState::ShuttingDown => {
                panic!(
                    "reopened direct workspace must be ready, got {:?}",
                    reopened.state()
                )
            }
        }
        assert_ne!(
            fs::read(journal_path).must_succeed("republished journal"),
            committed
        );
    }

    #[test]
    fn corrupt_journal_fails_closed_without_replacement() {
        for corrupt in [
            b"{".as_slice(),
            br#"{"magic":"LOMO_ENGINE","schema":99,"payload":"{}","checksum":"bad"}"#,
            br#"{"magic":"LOMO_ENGINE","schema":1,"payload":"{}","checksum":"bad"}"#,
        ] {
            let fixture = Fixture::new();
            let journal_path = fixture.config.journal_path().must_succeed("journal path");
            drop(LomoEngine::open(fixture.config.clone()).must_succeed("seed journal"));
            fs::write(&journal_path, corrupt).must_succeed("inject corrupt journal");

            let error = LomoEngine::open(fixture.config).must_fail("corruption must fail closed");
            assert_eq!(error.category(), ErrorCategory::Corruption);
            assert_eq!(
                fs::read(journal_path).must_succeed("corrupt journal retained"),
                corrupt
            );
        }
    }

    #[test]
    fn schema_v2_read_action_without_locator_migrates_and_republishes_current_schema() {
        let fixture = Fixture::new();
        let journal_path = fixture.config.journal_path().must_succeed("journal path");
        drop(LomoEngine::open(fixture.config.clone()).must_succeed("seed journal"));

        let mut envelope: serde_json::Value =
            serde_json::from_slice(&fs::read(&journal_path).must_succeed("seeded journal"))
                .must_succeed("seeded envelope");
        let payload_text = envelope
            .get("payload")
            .must_succeed("payload field")
            .as_str()
            .must_succeed("payload text");
        let mut payload: serde_json::Value =
            serde_json::from_str(payload_text).must_succeed("payload JSON");
        *payload
            .pointer_mut("/jobs/0/batch/actions")
            .must_succeed("legacy actions") = serde_json::json!([
            {
                "ReadToExchange": {
                    "action_id": "action-legacy-read",
                    "capability": "direct-root",
                    "path": "2026-08-06.md",
                    "exchange_token": "ex.0123456789abcdef0123456789abcdef.legacy",
                    "expected_source": "Absent"
                }
            }
        ]);
        *payload
            .pointer_mut("/jobs/0/driver_state_json")
            .must_succeed("legacy driver state") =
            serde_json::Value::String("legacy scan continuation".repeat(1024));
        let legacy_payload = serde_json::to_string(&payload).must_succeed("legacy payload");
        envelope
            .as_object_mut()
            .must_succeed("legacy envelope object")
            .extend([
                ("schema".to_owned(), serde_json::json!(2)),
                (
                    "payload".to_owned(),
                    serde_json::Value::String(legacy_payload.clone()),
                ),
                (
                    "checksum".to_owned(),
                    serde_json::Value::String(format!(
                        "{:x}",
                        Sha256::digest(legacy_payload.as_bytes())
                    )),
                ),
            ]);
        fs::write(
            &journal_path,
            serde_json::to_vec(&envelope).must_succeed("legacy envelope"),
        )
        .must_succeed("inject schema-v2 journal");

        drop(LomoEngine::open(fixture.config).must_succeed("migrate schema-v2 journal"));

        let migrated_envelope: serde_json::Value =
            serde_json::from_slice(&fs::read(journal_path).must_succeed("migrated journal"))
                .must_succeed("migrated envelope");
        assert_eq!(
            migrated_envelope
                .get("schema")
                .must_succeed("migrated schema"),
            &serde_json::json!(4)
        );
        let migrated_payload: serde_json::Value = serde_json::from_str(
            migrated_envelope
                .get("payload")
                .must_succeed("migrated payload field")
                .as_str()
                .must_succeed("migrated payload text"),
        )
        .must_succeed("migrated payload");
        assert_eq!(
            migrated_payload
                .pointer("/jobs/0/batch/actions/0/ReadToExchange/locator")
                .must_succeed("migrated locator"),
            &serde_json::json!({"Path": "2026-08-06.md"})
        );
        assert_eq!(
            migrated_payload
                .pointer("/jobs/0/driver_state_json")
                .must_succeed("compacted driver state"),
            &serde_json::Value::Null
        );
    }
}
