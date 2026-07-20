//! Behavior Contract
//!
//! Capability: own one recoverable engine generation per workspace through an OS-backed lock and
//! a checksummed, atomically published control journal.
//!
//! Scenarios:
//! - Given one engine owns a canonical workspace, when another engine opens the same identity,
//!   then it fails with a structured busy error; after owner drop, reopen succeeds.
//! - Given a direct workspace completes bootstrap, when it reopens, then `CoreRevision` remains
//!   zero while `EventSequence` advances only for the durable lifecycle event.
//! - Given an uncommitted journal candidate remains beside the committed journal, when the engine
//!   reopens, then only the committed journal is authoritative.
//! - Given a truncated envelope, unknown schema, or bad checksum, when the engine opens, then it
//!   fails closed and preserves the corrupt bytes for recovery diagnostics.
//!
//! Observable outcomes: `EngineState`, stable workspace identity, error category/code, lock
//! release, and exact durable journal bytes.
//! TDD proof: the first run fails to compile because `EngineConfig`, `LomoEngine`, and
//! `EngineState` are absent; GREEN is recorded in `STAGE1-EVIDENCE.md`.
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
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {

    use std::fs;

    use lomo_core::{EngineConfig, EngineState, ErrorCategory, LomoEngine, WorkspaceDescriptor};
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
        fs::write(lock_dir.join("owner.pid"), "1\n").must_succeed("dead pid owner");
        // PID 1 is init and is usually alive; write a clearly dead high pid instead.
        fs::write(lock_dir.join("owner.pid"), "4294967294\n").must_succeed("dead pid owner");

        let reopened = LomoEngine::open(fixture.config)
            .must_succeed("stale lock with dead owner must reclaim");
        assert!(matches!(reopened.state(), EngineState::Ready { .. }));
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
            other => panic!("direct workspace must be ready, got {other:?}"),
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
            other => panic!("reopened direct workspace must be ready, got {other:?}"),
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
}
