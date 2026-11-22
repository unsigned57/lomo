//! Behavior Contract:
//! TDD proof: Regression preservation - `cargo test -p lomo-application --test workspace_interchange_contract --test session_transaction_contract --locked`
//! passed before lifetime refactoring (`reports/manual/remaining-gates/application-before.log`);
//! the same scenarios run in `application-green.log` after each acceptance's lexical scope ends at commit.
//! Test Change Justification:
//! Reason category: explicit acceptance-resource lifetime.
//! Old behavior/assertion being replaced: acceptance variables occupied the entire test scope despite consuming commit calls.
//! Why old assertion is no longer correct: no assertion changes; each resource now ends at its transaction boundary.
//! Coverage preserved by: pending visibility, frozen identity, commit, withdrawal and replay assertions retain their order and results.
//! Why this is not fitting the test to the implementation: shortening the lexical resource scope preserves the existing consuming API.
//! Capability: Real lomo-application session service, shared document write transaction,
//! and full/incremental SQLite projection rebuild.
//!
//! - Given three memos created at the exact same second with identical body,
//!   When committed to `yyyy_MM_dd.md`,
//!   Then each memo gets an independent stable CSPRNG `MemoId`,
//!   updating/deleting intermediate memos preserves sibling IDs without renumbering,
//!   and no machine markers are injected into the Markdown content.
//!
//! - Given a populated workspace with Markdown, identity, `history_v2`, and pins in .lomo,
//!   When the SQLite cache is cleared and opened in another device private directory,
//!   Then 100% of IDs, history, and pins are reconstructed from Markdown + .lomo,
//!   a later rebuild on the unchanged workspace reconciles without rewriting or
//!   advancing high-water, an external Markdown file forces a rewrite,
//!   and device identity is not inherited.
//!
//! - Given a session whose live projection already matches the workspace,
//!   When the same private cache is opened again,
//!   Then mount reports `rewritten = false` and does not read Markdown bodies.
//!
//! - Given a listing that reports the empty-content digest without hashing bytes,
//!   When Markdown changes and the same weak listing is used again,
//!   Then rebuild cannot skip: it rereads Markdown instead of certifying the old projection.
//!
//! - Given a complete POSIX listing that hashed a real empty file (SHA-256 of zero bytes),
//!   When the workspace is remounted without other changes,
//!   Then rebuild skips Markdown body reads: a verified empty file is not "digest unknown".
//!
//! - Given a live projection and a later incomplete directory enumeration,
//!   When rebuild runs,
//!   Then it fails with `metadata_enumeration_incomplete` and keeps the prior memos.
//!
//! - Given a baseline read followed by external edits to the Markdown file,
//!   When a write command with the baseline fingerprint is executed,
//!   Then it is rejected with conflict, preserving 3-way evidence and uncompleted intent,
//!   without corrupting BOM, CRLF, or untouched bytes.
//!
//! - Given an injected platform executor failure midway through a write batch,
//!   When the session re-opens or retries with the same `operation_id`,
//!   Then it reuses the operation and memo ID without duplicate entries in the document,
//!   and replaying with a different payload is rejected.
//!
//! - Given a document with multiple sibling memos,
//!   When one memo is modified,
//!   Then SQLite publication occurs after verified durable write,
//!   all sibling memo fingerprints in that document are incrementally refreshed,
//!   and event sequence advances monotonically.
//!
//! - Given a retained projection and an empty intent journal,
//!   When the session opens for presentation,
//!   Then the platform executor observes no workspace action: the first page is a function of
//!   app-private state alone, so time-to-first-memo cannot grow with workspace size.
//!   Layout authority is enforced where workspace facts are consumed — the projection reconcile
//!   and the physical transaction apply — so an unsupported layout head still fails
//!   revalidation and keeps writes closed, and one create observes the layout head exactly once.
//!   TDD proof: RED 2026-11-07 `cargo test -p lomo-application --test session_transaction_contract
//!   layout_authority --locked`: presentation open issued one `.lomo/layout_head.rec` read and a
//!   create issued two (`recover_pending` plus apply); on SAF each read is an absence proof that
//!   re-listed the workspace root (~300 ms at 1.5k dated files). GREEN: zero and one.
//!
//! - Given a verified session, when a create is accepted, then its pending row is queryable
//!   (`is_pending`, acceptance clock) before the executor observes any workspace action; commit
//!   upgrades that same identity in place. A commit failure or a dropped acceptance withdraws
//!   the row with a later projection clock, and the workspace stays untouched.
//!   TDD proof: RED 2026-11-07 — no acceptance stage existed (`accept_create` did not resolve;
//!   on device the memo surfaced only after ~150 ms of SAF writes). GREEN: `cargo test -p
//!   lomo-application --test session_transaction_contract accepted_create --locked`.
//! - Given an explicit dated document/time, when acceptance precedes commit, then pending and
//!   committed creation times equal the chronology reconstructed from those durable facts.
//!   TDD proof: `instant-feed/rust-chronology-red.log` retains wall-clock time instead of the
//!   dated document chronology; GREEN is recorded in `instant-feed/rust-chronology-green.log`.
//!   Evidence root: reports/manual/.
//! - Given a transient durable write failure in a live session, when the same operation is
//!   accepted again, then the frozen transaction completes under the original identity with no
//!   second pending row. Its receipt is a fresh completion, not a previously committed replay.
//!   TDD proof: Regression preservation — the existing cross-process recovery scenario and new
//!   same-session scenario pass under `instant-feed/rust-live-retry-green.log`; no retry production
//!   behavior was changed.
//!
//! Additional resource scenario: appending reads the document and identity through one bounded
//! independent batch, preserving existing memo bytes and the committed new identity.
//! TDD proof: `reports/manual/four-bugs/source-batch-red.log` observes width 1; GREEN observes width 2.
//!
//! Test Change Justification:
//! Reason category: remove redundant absence reads for minted identities and immutable objects.
//! Old behavior/assertion being replaced: create targets, including a freshly minted history head, received a plan-time read.
//! Why old assertion is no longer correct: a fresh CSPRNG identity has no prior history; its head and content-addressed objects are create-only and the
//! platform write already rejects foreign bytes or acknowledges identical bytes from its own
//! live observation; another absence read cannot strengthen that decision.
//! Coverage preserved by: mutable baselines still require exactly one read, all durable writes
//! remain witnessed, and the import contract reconstructs the original revision after cache loss.
//! Why this is not fitting the test to the implementation: this tightens the immutable-object
//! I/O bound to zero instead of weakening all read-count assertions to an upper bound.
//!
//! TDD proof: RED initially because `WorkspaceSession` implementation is pending.
//! Empty-file remount RED 2026-09-17: `content_listing_digest` treated the SHA-256 of zero bytes as
//! unknown, so a hashed empty file forced Markdown rereads; GREEN uses `ContentDigest::Unknown` vs
//! `Verified`.

use std::{
    collections::BTreeSet,
    fs,
    path::Path,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, PinMemoRequest, PinPolicy, UpdateMemoRequest,
    WorkspaceSession, WorkspaceSessionConfig,
};
use lomo_core::{
    ActionEvidence, ActionOutcome, ActionResult, CapabilityToken, DocumentKind, DocumentMetadata,
    ErrorCategory, LomoError, MetadataPage, OperationId, PlatformAction, PlatformActionBatch,
    PlatformActionExecutor, PlatformActionOutput, PlatformBatchResult, RelativeWorkspacePath,
    RetryDisposition, WorkspaceTarget,
};
use lomo_platform_fs::FsPlatformActionExecutor;
use lomo_store::MemoQuery;
use lomo_workspace::WorkspaceRootId;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    clippy::redundant_clone,
    clippy::excessive_nesting,
    clippy::similar_names,
    clippy::clone_on_ref_ptr,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    struct TestContext {
        _workspace_dir: tempfile::TempDir,
        _state_dir: tempfile::TempDir,
        _cache_dir: tempfile::TempDir,
        _runtime_dir: tempfile::TempDir,
        _exchange_dir: tempfile::TempDir,
        workspace_path: std::path::PathBuf,
        session: WorkspaceSession,
        executor: Arc<FsPlatformActionExecutor>,
        config: WorkspaceSessionConfig,
    }

    fn setup_test_context() -> TestContext {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };

        let session =
            WorkspaceSession::open(config.clone(), executor.clone()).expect("open session");
        let workspace_path = workspace_dir.path().to_path_buf();

        TestContext {
            _workspace_dir: workspace_dir,
            _state_dir: state_dir,
            _cache_dir: cache_dir,
            _runtime_dir: runtime_dir,
            _exchange_dir: exchange_dir,
            workspace_path,
            session,
            executor,
            config,
        }
    }

    // Behavior Contract: retained startup reads the previous projection without enumerating
    // the workspace; revalidation then includes external changes. RED/GREEN command:
    // cargo test -p lomo-application --test session_transaction_contract retained_startup --locked.
    #[test]
    fn retained_startup_serves_previous_page_before_external_revalidation() {
        let context = setup_test_context();
        let config = context.config.clone();
        let executor = context.executor.clone();
        fs::write(
            context.workspace_path.join("2026_11_01.md"),
            "## 12:00:00\n\nfirst memo\n",
        )
        .expect("seed first document");
        context
            .session
            .rebuild_projection()
            .expect("verified index");
        drop(context.session);
        fs::write(
            context.workspace_path.join("2026_11_02.md"),
            "## 12:00:00\n\nexternal memo\n",
        )
        .expect("external document");
        let session =
            WorkspaceSession::open_for_presentation(config, executor).expect("retained open");
        let page = session
            .query_memos_page(
                &MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(20).expect("page size"),
            )
            .expect("cached page");
        assert_eq!(
            page.items.len(),
            1,
            "first page must precede the workspace walk"
        );
        assert!(!session.projection_is_verified());
        let rejected = session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-retained-write").expect("operation"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_11_03.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "must wait for verification".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect_err("retained projection cannot write");
        assert_eq!(rejected.code(), "projection_revalidating");
        assert!(!context.workspace_path.join("2026_11_03.md").exists());
        fs::write(context.workspace_path.join("2026_11_04.md"), [0xff])
            .expect("invalid external document");
        let failure = session
            .rebuild_projection()
            .expect_err("reject malformed external bytes");
        assert_eq!(failure.code(), "source_not_utf8");
        assert!(
            !session.projection_is_verified(),
            "failed revalidation cannot open writes"
        );
        fs::remove_file(context.workspace_path.join("2026_11_04.md"))
            .expect("remove invalid input");
        session.rebuild_projection().expect("revalidate");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(session.projection_is_verified());
        let page = session
            .query_memos_page(
                &MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(20).expect("page size"),
            )
            .expect("fresh page");
        assert_eq!(page.items.len(), 2);
    }

    /// Records every action kind and every layout-head byte read the executor receives.
    struct WorkspaceActionWitness {
        inner: Arc<FsPlatformActionExecutor>,
        actions: Mutex<Vec<String>>,
    }

    impl WorkspaceActionWitness {
        fn take(&self) -> Vec<String> {
            std::mem::take(&mut *self.actions.lock().expect("action witness"))
        }
    }

    impl PlatformActionExecutor for WorkspaceActionWitness {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            let mut actions = self.actions.lock().expect("action witness");
            for action in batch.actions() {
                actions.push(match action {
                    PlatformAction::Read { path, .. }
                    | PlatformAction::ReadToExchange { path, .. } => {
                        format!("read:{}", path.as_str())
                    }
                    PlatformAction::Stat { .. } => "stat".to_owned(),
                    PlatformAction::Inspect { .. } => "inspect".to_owned(),
                    PlatformAction::ListChildren { .. } => "list".to_owned(),
                    PlatformAction::EnsureDirectory { .. } => "ensure".to_owned(),
                    PlatformAction::WriteFromExchange { .. }
                    | PlatformAction::ArtifactWrite { .. } => "write".to_owned(),
                    PlatformAction::Move { .. } => "move".to_owned(),
                    PlatformAction::Delete { .. } => "delete".to_owned(),
                });
            }
            drop(actions);
            self.inner.execute(batch)
        }
    }

    const LAYOUT_HEAD_READ: &str = "read:.lomo/layout_head.rec";

    fn unsupported_layout_head() -> Vec<u8> {
        lomo_workspace::encode_record(&lomo_workspace::LomoPayload {
            kind: lomo_workspace::LomoRecordKind::LayoutHead,
            record_id: "layout".to_owned(),
            body_json: r#"{"layout":"V999"}"#.to_owned(),
        })
        .expect("layout head record")
    }

    fn create_request(operation: &str, path: &str) -> CreateMemoRequest {
        CreateMemoRequest {
            operation_id: OperationId::parse(operation).expect("operation"),
            relative_path: Some(RelativeWorkspacePath::parse(path).expect("path")),
            time_token: Some("12:00:00".to_owned()),
            content: format!("memo for {operation}"),
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: Vec::new(),
            chronology_epoch_ms: None,
        }
    }

    #[test]
    fn layout_authority_presentation_open_issues_no_workspace_action() {
        let context = setup_test_context();
        fs::write(
            context.workspace_path.join("2026_11_01.md"),
            "- 12:00:00\nretained memo\n",
        )
        .expect("seed document");
        context
            .session
            .rebuild_projection()
            .expect("verified index");
        let config = context.config.clone();
        let witness = Arc::new(WorkspaceActionWitness {
            inner: context.executor.clone(),
            actions: Mutex::new(Vec::new()),
        });
        drop(context.session);

        let session = WorkspaceSession::open_for_presentation(config, witness.clone())
            .expect("retained open");

        assert_eq!(
            witness.take(),
            Vec::<String>::new(),
            "presentation open touched the workspace"
        );
        let page = session
            .query_memos_page(
                &MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(20).expect("page size"),
            )
            .expect("retained page");
        assert_eq!(page.items.len(), 1);
    }

    #[test]
    fn layout_authority_unsupported_head_fails_revalidation_after_presentation() {
        let context = setup_test_context();
        fs::write(
            context.workspace_path.join("2026_11_01.md"),
            "- 12:00:00\nretained memo\n",
        )
        .expect("seed document");
        context
            .session
            .rebuild_projection()
            .expect("verified index");
        let config = context.config.clone();
        let executor = context.executor.clone();
        drop(context.session);
        let lomo_dir = context.workspace_path.join(".lomo");
        fs::create_dir_all(&lomo_dir).expect("lomo dir");
        let head = unsupported_layout_head();
        fs::write(lomo_dir.join("layout_head.rec"), &head).expect("foreign layout head");

        let session = WorkspaceSession::open_for_presentation(config, executor)
            .expect("presentation never consumes workspace layout facts");
        let failure = session
            .rebuild_projection()
            .expect_err("revalidation consumes workspace facts and must reject the layout");

        assert_eq!(failure.code(), "unsupported_workspace_layout");
        assert!(
            !session.projection_is_verified(),
            "an unsupported layout cannot open writes"
        );
        assert_eq!(
            fs::read(lomo_dir.join("layout_head.rec")).expect("head bytes"),
            head
        );
    }

    #[test]
    fn layout_authority_create_observes_layout_head_once() {
        let context = setup_test_context();
        let config = context.config.clone();
        let witness = Arc::new(WorkspaceActionWitness {
            inner: context.executor.clone(),
            actions: Mutex::new(Vec::new()),
        });
        drop(context.session);
        let session = WorkspaceSession::open(config, witness.clone()).expect("open session");
        witness.take();

        session
            .create_memo(create_request("op-layout-once", "2026_11_05.md"))
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");

        let layout_reads = witness
            .take()
            .into_iter()
            .filter(|action| action == LAYOUT_HEAD_READ)
            .count();
        assert_eq!(
            layout_reads, 1,
            "the transaction apply boundary owns the layout check"
        );
    }

    #[test]
    fn layout_authority_create_is_rejected_before_writing_an_unsupported_layout() {
        let context = setup_test_context();
        let lomo_dir = context.workspace_path.join(".lomo");
        fs::create_dir_all(&lomo_dir).expect("lomo dir");
        fs::write(lomo_dir.join("layout_head.rec"), unsupported_layout_head())
            .expect("foreign layout head");

        // The intent commits; the apply boundary refuses the foreign layout while converging,
        // so no byte reaches the workspace and the stall is surfaced to the next settle.
        context
            .session
            .create_memo(create_request("op-layout-reject", "2026_11_06.md"))
            .expect("intent commits");
        let failure = context
            .session
            .settle_workspace()
            .expect_err("unsupported layout");

        assert_eq!(failure.code(), "unsupported_workspace_layout");
        assert!(!context.workspace_path.join("2026_11_06.md").exists());
    }

    fn first_page(session: &WorkspaceSession) -> Vec<lomo_store::MemoSummary> {
        session
            .query_memos_page(
                &MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(20).expect("page size"),
            )
            .expect("page")
            .items
    }

    fn witnessed_session() -> (TestContext, WorkspaceSession, Arc<WorkspaceActionWitness>) {
        let context = setup_test_context();
        let witness = Arc::new(WorkspaceActionWitness {
            inner: context.executor.clone(),
            actions: Mutex::new(Vec::new()),
        });
        let config = context.config.clone();
        let session = WorkspaceSession::open(config, witness.clone()).expect("open session");
        witness.take();
        (context, session, witness)
    }

    #[test]
    fn accepted_create_is_visible_before_workspace_io_and_commits_in_place() {
        let (context, session, witness) = witnessed_session();

        let (accepted_id, accepted_revision, chronology, committed) = {
            let accepted = session
                .accept_create(create_request("op-accept", "2026_11_07.md"))
                .expect("accept");
            let accepted_id = accepted.publication().memo_id.clone();
            let accepted_revision = accepted.publication().core_revision;

            assert_eq!(
                witness.take(),
                Vec::<String>::new(),
                "acceptance touched the workspace"
            );
            let pending = first_page(&session);
            assert_eq!(pending.len(), 1);
            assert_eq!(pending[0].memo_id, accepted_id);
            assert!(
                pending[0].is_pending,
                "an accepted create is published as pending"
            );
            let chronology = lomo_application::calendar::memo_chronology(
                "2026_11_07",
                "12:00:00",
                &context.config.time_zone,
            )
            .expect("document chronology");
            assert_eq!(pending[0].created_at_ms, chronology);
            assert!(accepted_revision > 0);
            assert!(!context.workspace_path.join("2026_11_07.md").exists());

            (
                accepted_id,
                accepted_revision,
                chronology,
                accepted.commit().expect("commit"),
            )
        };

        assert_eq!(committed.memo_id.as_str(), accepted_id);
        assert!(committed.commit_result.core_revision > accepted_revision);
        let page = first_page(&session);
        assert_eq!(page.len(), 1);
        assert_eq!(page[0].memo_id, accepted_id);
        assert!(!page[0].is_pending);
        assert_eq!(page[0].created_at_ms, chronology);
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(context.workspace_path.join("2026_11_07.md").exists());
    }

    #[test]
    fn accepted_create_retry_recovers_the_original_receipt_in_the_same_session() {
        let context = setup_test_context();
        let executor = Arc::new(FaultInjectingExecutor {
            inner: context.executor.clone(),
            fail_on_write_path_substring: Some(".lomo/identity".to_owned()),
            failed: AtomicBool::new(false),
        });
        let session = WorkspaceSession::open(context.config.clone(), executor).expect("open");
        let request = create_request("op-live-retry", "2026_11_09.md");
        let (identity, _) = {
            let first = session
                .accept_create(request.clone())
                .expect("first acceptance");
            // Write-behind publishes the intent; an injected identity write fails during convergence.
            (
                first.publication().memo_id.clone(),
                first.commit().expect("intent commits"),
            )
        };
        let failure = session
            .settle_workspace()
            .expect_err("injected write failure");
        assert_eq!(failure.code(), "injected_executor_failure");

        let committed = {
            let retry = session.accept_create(request).expect("accept retry");
            assert_eq!(retry.publication().memo_id, identity);
            assert!(
                retry.publication().idempotent_replay,
                "the retry drains the queued intent first and answers with its committed receipt"
            );
            let page = first_page(&session);
            assert_eq!(page.len(), 1);
            assert!(
                !page[0].is_pending,
                "recovery already committed the original create"
            );
            retry.commit().expect("return recovered receipt")
        };
        assert_eq!(committed.memo_id.as_str(), identity);
        let bytes = fs::read_to_string(context.workspace_path.join("2026_11_09.md"))
            .expect("durable document");
        assert_eq!(bytes.matches("memo for op-live-retry").count(), 1);
    }

    #[test]
    fn accepted_create_failing_its_commit_withdraws_the_pending_row() {
        let (context, session, _witness) = witnessed_session();
        let mut request = create_request("op-accept-conflict", "2026_11_08.md");
        request.expected_document_fingerprint = Some("0".repeat(64));

        let (accepted_revision, withdrawn) = {
            let accepted = session.accept_create(request).expect("accept");
            (
                accepted.publication().core_revision,
                accepted
                    .commit()
                    .expect_err("stale baseline must fail the commit"),
            )
        };

        let withdrawal = withdrawn
            .withdrawal
            .as_ref()
            .expect("withdrawal succeeded")
            .as_ref()
            .expect("pending row removed");
        assert!(withdrawal.core_revision > accepted_revision);
        assert_eq!(first_page(&session), Vec::new());
        assert!(!context.workspace_path.join("2026_11_08.md").exists());
    }

    #[test]
    fn dropped_accepted_create_withdraws_the_pending_row() {
        let (_context, session, _witness) = witnessed_session();

        let accepted = session
            .accept_create(create_request("op-accept-dropped", "2026_11_09.md"))
            .expect("accept");
        assert_eq!(first_page(&session).len(), 1);
        drop(accepted);

        assert_eq!(first_page(&session), Vec::new());
        session
            .create_memo(create_request("op-after-drop", "2026_11_09.md"))
            .expect("the transaction lock is released with the acceptance");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(first_page(&session).len(), 1);
    }

    #[test]
    fn accepted_create_replay_reports_the_committed_receipt_without_a_pending_row() {
        let (_context, session, _witness) = witnessed_session();
        let first = session
            .create_memo(create_request("op-accept-replay", "2026_11_10.md"))
            .expect("first create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");

        let replayed = session
            .accept_create(create_request("op-accept-replay", "2026_11_10.md"))
            .expect("replay accept");

        assert!(replayed.publication().idempotent_replay);
        assert_eq!(replayed.publication().memo_id, first.memo_id.as_str());
        assert!(first_page(&session).iter().all(|memo| !memo.is_pending));
        assert_eq!(replayed.commit().expect("replay").memo_id, first.memo_id);
    }

    #[test]
    fn same_second_identical_content_memos_have_independent_stable_ids_and_clean_markdown() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        // 1. Create three identical memos at the exact same second
        let body = "Same identical memo text";
        let res1 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 1");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let res2 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-2").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: Some(res1.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 2");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let res3 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-3").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: Some(res2.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 3");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // All three IDs must be distinct and non-empty
        assert_ne!(res1.memo_id, res2.memo_id);
        assert_ne!(res2.memo_id, res3.memo_id);
        assert_ne!(res1.memo_id, res3.memo_id);

        // Markdown file inspection: No machine markers, clean standard Markdown
        let md_content =
            fs::read_to_string(ctx.workspace_path.join("2026_09_09.md")).expect("read md");
        assert!(!md_content.contains("<!--"));
        assert!(!md_content.contains("-->"));
        assert!(!md_content.contains("id:"));
        assert!(!md_content.contains("memo-"));
        assert_eq!(
            md_content.trim(),
            "- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text"
        );

        // 2. Update middle item (res2)
        let update_res = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-update-mid").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                content: "Updated middle content".to_string(),
                expected_document_fingerprint: res3.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update middle");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Sibling memos (res1, res3) must retain their original identities! No renumbering!
        let proj1 = ctx
            .session
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        let proj2 = ctx
            .session
            .get_memo(&res2.memo_id)
            .expect("get 2")
            .expect("found 2");
        let proj3 = ctx
            .session
            .get_memo(&res3.memo_id)
            .expect("get 3")
            .expect("found 3");

        assert_eq!(proj1.memo_id, res1.memo_id.as_str());
        assert_eq!(proj1.body, body);
        assert_eq!(proj2.memo_id, res2.memo_id.as_str());
        assert_eq!(proj2.body, "Updated middle content");
        assert_eq!(proj3.memo_id, res3.memo_id.as_str());
        assert_eq!(proj3.body, body);

        // 3. Delete middle item (res2)
        let _del_res = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("op-del-mid").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                expected_document_fingerprint: update_res.file_fingerprint.clone(),
                trashed_at_ms: Some(1_700_000_000_000),
            })
            .expect("delete middle");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Verify remaining memos on disk still retain original IDs
        let remaining1 = ctx
            .session
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        let remaining3 = ctx
            .session
            .get_memo(&res3.memo_id)
            .expect("get 3")
            .expect("found 3");
        assert_eq!(remaining1.memo_id, res1.memo_id.as_str());
        assert_eq!(remaining3.memo_id, res3.memo_id.as_str());

        let md_after_delete =
            fs::read_to_string(ctx.workspace_path.join("2026_09_09.md")).expect("read md");
        assert_eq!(
            md_after_delete.trim(),
            "- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text"
        );
    }

    #[test]
    fn cold_cache_and_copied_workspace_recovers_same_ids_history_and_pins_from_physical_facts() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res1 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-cold-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("09:00:00".to_string()),
                content: "First pinned memo".to_string(),
                expected_document_fingerprint: None,
                pinned: true,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 1");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let res2 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-cold-2").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("11:00:00".to_string()),
                content: "Second memo with history".to_string(),
                expected_document_fingerprint: Some(res1.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 2");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Update res2 to create history snapshot
        let _up_res = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-cold-update").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                content: "Second memo updated text".to_string(),
                expected_document_fingerprint: res2.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update 2");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Explicitly pin res1 to ensure state record is written
        let _pin_res = ctx
            .session
            .pin_memo(
                PinMemoRequest::new(
                    OperationId::parse("op-cold-pin").expect("valid op"),
                    res1.memo_id.clone(),
                    PinPolicy::Pinned {
                        at_ms: Some(1_700_000_100_000),
                    },
                )
                .expect("valid pin command"),
            )
            .expect("pin memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let orig_device_id = ctx.session.device_id().to_string();

        // Now open a brand new session in fresh, separate private directories!
        // This simulates a cold cache or copying the workspace to another device!
        let new_state_dir = tempdir().expect("new state dir");
        let new_cache_dir = tempdir().expect("new cache dir");
        let new_runtime_dir = tempdir().expect("new runtime dir");
        let new_exchange_dir = tempdir().expect("new exchange dir");

        let new_executor =
            Arc::new(FsPlatformActionExecutor::new(new_exchange_dir.path()).expect("new executor"));
        new_executor
            .bind_root(ctx.config.capability.clone(), &ctx.workspace_path)
            .expect("bind root to same workspace");

        let new_config = WorkspaceSessionConfig {
            capability: ctx.config.capability.clone(),
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: new_state_dir.path().to_path_buf(),
            cache_dir: new_cache_dir.path().to_path_buf(),
            runtime_dir: new_runtime_dir.path().to_path_buf(),
            exchange_dir: new_exchange_dir.path().to_path_buf(),
            media_stage_root: new_exchange_dir.path().join("media-stage"),
        };

        let session2 = WorkspaceSession::open(new_config, new_executor).expect("open session 2");

        // Device identity must NOT be inherited from workspace!
        assert_ne!(session2.device_id(), orig_device_id);

        // Open already rebuilt from physical facts. A later refresh must reconcile.
        let rebuild_res = session2.rebuild_projection().expect("rebuild projection");
        assert!(!rebuild_res.rewritten);
        assert_eq!(rebuild_res.memos_indexed, 2);
        let high_water = rebuild_res.high_water_revision;
        let reconciled = session2
            .rebuild_projection()
            .expect("unchanged workspace must reconcile");
        assert!(!reconciled.rewritten);
        assert_eq!(reconciled.high_water_revision, high_water);
        assert_eq!(reconciled.memos_indexed, 2);

        fs::write(
            ctx.workspace_path.join("2026_09_10.md"),
            "- 08:00:00\nexternal added memo\n",
        )
        .expect("external memo");
        let rewritten = session2
            .rebuild_projection()
            .expect("external markdown must rewrite");
        assert!(rewritten.rewritten);
        assert_eq!(rewritten.memos_indexed, 3);

        // Assert that res1 is pinned and has identical memo_id
        let memo1 = session2
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        assert_eq!(memo1.memo_id, res1.memo_id.as_str());
        assert!(
            memo1.is_pinned,
            "memo1 must be reconstructed as pinned from .lomo state facts!"
        );

        // Assert that res2 has identical memo_id and updated text
        let memo2 = session2
            .get_memo(&res2.memo_id)
            .expect("get 2")
            .expect("found 2");
        assert_eq!(memo2.memo_id, res2.memo_id.as_str());
        assert_eq!(memo2.body, "Second memo updated text");

        // Query pinned list
        let pinned_list = session2
            .list_memos(&MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters {
                    pinned_only: true,
                    ..lomo_store::MemoFilters::default()
                },
                sort: lomo_store::MemoSort::default(),
            })
            .expect("list pinned");
        assert_eq!(pinned_list.items.len(), 1);
        assert_eq!(pinned_list.items[0].memo_id, res1.memo_id.as_str());
    }

    #[test]
    fn stale_baseline_rejects_overwrite_and_preserves_three_way_evidence() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-stale-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("12:00:00".to_string()),
                content: "Original baseline text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let baseline_fingerprint = res.commit_result.file_fingerprint.clone();

        // Simulate external edit: modifying the file on disk directly
        let file_path = ctx.workspace_path.join("2026_09_09.md");
        let external_content = "- 12:00:00 Externally modified text\n";
        fs::write(&file_path, external_content).expect("external write");

        // Now attempt to update using the stale baseline
        let update_err = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-stale-update").expect("valid op"),
                memo_id: res.memo_id.clone(),
                content: "My local draft edit".to_string(),
                expected_document_fingerprint: baseline_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect_err("must reject stale baseline");

        assert_eq!(update_err.category(), ErrorCategory::Conflict);

        // Disk content must NOT be overwritten!
        let disk_now = fs::read_to_string(&file_path).expect("read disk");
        assert_eq!(disk_now, external_content);

        // Draft evidence must be preserved in state_dir/drafts/
        let drafts_dir = ctx.config.state_dir.join("drafts");
        assert!(drafts_dir.exists(), "drafts directory must exist");
        let draft_evidence = fs::read_to_string(drafts_dir.join("op-stale-update.json"))
            .expect("read draft evidence");
        assert!(draft_evidence.contains("My local draft edit"));
        assert!(draft_evidence.contains(&baseline_fingerprint));
    }

    /// A wrapper around `PlatformActionExecutor` that can inject failures on specific actions.
    struct SourceBatchWitness {
        inner: Arc<FsPlatformActionExecutor>,
        width: std::sync::atomic::AtomicUsize,
    }

    impl PlatformActionExecutor for SourceBatchWitness {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            let sources = batch.actions().iter().filter(|action| {
                matches!(action, PlatformAction::Read { path, .. }
                    if path.as_str() == "2026_09_09.md" || path.as_str().starts_with(".lomo/identity/"))
            }).count();
            self.width.fetch_max(sources, Ordering::SeqCst);
            self.inner.execute(batch)
        }
    }

    #[test]
    fn appending_reads_the_document_and_identity_in_one_independent_batch() {
        let ctx = setup_test_context();
        let create = |operation: &str, body: &str| CreateMemoRequest {
            operation_id: OperationId::parse(operation).expect("op"),
            relative_path: Some(RelativeWorkspacePath::parse("2026_09_09.md").expect("path")),
            time_token: Some("10:00:00".to_owned()),
            content: body.to_owned(),
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: Vec::new(),
            chronology_epoch_ms: None,
        };
        ctx.session
            .create_memo(create("batch-seed", "original memo"))
            .expect("seed");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let witness = Arc::new(SourceBatchWitness {
            inner: Arc::clone(&ctx.executor),
            width: std::sync::atomic::AtomicUsize::new(0),
        });
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<SourceBatchWitness>::clone(&witness);
        let session = WorkspaceSession::open(ctx.config.clone(), executor).expect("reopen");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        witness.width.store(0, Ordering::SeqCst);
        let result = session
            .create_memo(create("batch-append", "new memo"))
            .expect("append");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(
            witness.width.load(Ordering::SeqCst),
            2,
            "independent source reads must overlap"
        );
        assert_eq!(
            session
                .get_memo(&result.memo_id)
                .expect("read")
                .expect("memo")
                .body,
            "new memo"
        );
        assert!(
            fs::read_to_string(ctx.workspace_path.join("2026_09_09.md"))
                .expect("bytes")
                .contains("original memo")
        );
    }

    /// One transient write fault; recovery on this same executor then uses the real platform.
    struct FaultInjectingExecutor {
        inner: Arc<dyn PlatformActionExecutor>,
        fail_on_write_path_substring: Option<String>,
        failed: AtomicBool,
    }

    impl PlatformActionExecutor for FaultInjectingExecutor {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            if let Some(target) = &self.fail_on_write_path_substring {
                for action in batch.actions() {
                    if let PlatformAction::WriteFromExchange { path, .. } = action {
                        if !path.as_str().contains(target)
                            || self.failed.swap(true, Ordering::SeqCst)
                        {
                            continue;
                        }
                        return Err(LomoError::from_platform_boundary(
                            ErrorCategory::Storage,
                            "injected_executor_failure",
                            RetryDisposition::Never,
                            None,
                            None,
                            &format!("injected failure on writing {}", path.as_str()),
                        )
                        .unwrap_or_else(|e| e));
                    }
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn failure_recovery_reuses_operation_and_memo_id_without_duplication_and_rejects_mismatched_replay()
     {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let real_executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        real_executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let fault_executor = Arc::new(FaultInjectingExecutor {
            inner: real_executor.clone(),
            fail_on_write_path_substring: Some(".lomo/identity".to_string()),
            failed: AtomicBool::new(false),
        });

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };

        let session = WorkspaceSession::open(config.clone(), fault_executor).expect("open session");
        let op_id = OperationId::parse("op-fail-retry-1").expect("valid op");
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        // Create with injected failure on .lomo/identity: the intent commits and the failure
        // surfaces while its bytes converge.
        session
            .create_memo(CreateMemoRequest {
                operation_id: op_id.clone(),
                relative_path: Some(path.clone()),
                time_token: Some("14:00:00".to_string()),
                content: "Fault injection memo text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("intent commits");
        let err = session
            .settle_workspace()
            .expect_err("should fail due to injected failure");

        assert_eq!(err.code(), "injected_executor_failure");
        drop(session);

        // Now remove the fault injection and retry with the SAME operation_id and SAME payload.
        // Recovery is a new process: the previous session must release the private projection.
        let session_recovered =
            WorkspaceSession::open(config, real_executor).expect("open recovered session");

        let _retry_res = session_recovered
            .create_memo(CreateMemoRequest {
                operation_id: op_id.clone(),
                relative_path: Some(path.clone()),
                time_token: Some("14:00:00".to_string()),
                content: "Fault injection memo text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("retry must succeed");
        session_recovered
            .settle_workspace()
            .expect("committed writes converge");

        // Must NOT append a second duplicate entry into markdown!
        let md_content =
            fs::read_to_string(workspace_dir.path().join("2026_09_09.md")).expect("read md");
        let count = md_content.matches("Fault injection memo text").count();
        assert_eq!(count, 1, "Duplicate memo must not be appended on retry!");

        // Now attempt to replay the SAME operation_id with a DIFFERENT payload: must be rejected!
        let replay_err = session_recovered
            .create_memo(CreateMemoRequest {
                operation_id: op_id,
                relative_path: Some(path),
                time_token: Some("14:00:00".to_string()),
                content: "Different payload with same op_id".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect_err("must reject replay with different payload");

        assert_eq!(replay_err.category(), ErrorCategory::Conflict);
    }

    #[test]
    fn sqlite_publication_after_durable_write_refreshes_sibling_fingerprints_monotonically() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res_a = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-sib-a").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("15:00:00".to_string()),
                content: "Memo A body".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create a");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let res_b = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-sib-b").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("15:01:00".to_string()),
                content: "Memo B body".to_string(),
                expected_document_fingerprint: Some(res_a.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create b");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Initially, both memo A and memo B share the document's fingerprint after creating B
        let memo_a_before = ctx
            .session
            .get_memo(&res_a.memo_id)
            .expect("get a")
            .expect("found a");
        let memo_b_before = ctx
            .session
            .get_memo(&res_b.memo_id)
            .expect("get b")
            .expect("found b");
        assert_eq!(
            memo_a_before.file_fingerprint,
            res_b.commit_result.file_fingerprint
        );
        assert_eq!(
            memo_b_before.file_fingerprint,
            res_b.commit_result.file_fingerprint
        );

        let seq_before = res_b.commit_result.event_sequence;

        // Now update memo A
        let update_a = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-sib-update-a").expect("valid op"),
                memo_id: res_a.memo_id.clone(),
                content: "Memo A body updated".to_string(),
                expected_document_fingerprint: res_b.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update a");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        assert!(
            update_a.event_sequence > seq_before,
            "event sequence must be monotonic!"
        );

        // Check sibling memo B in SQLite: its file_fingerprint must have been refreshed to the new document fingerprint!
        let memo_b_after = ctx
            .session
            .get_memo(&res_b.memo_id)
            .expect("get b")
            .expect("found b");
        assert_eq!(
            memo_b_after.file_fingerprint, update_a.file_fingerprint,
            "Sibling memo B fingerprint must be incrementally refreshed in SQLite!"
        );
    }

    struct MarkdownReadCounter {
        inner: Arc<FsPlatformActionExecutor>,
        markdown_reads: Mutex<Vec<String>>,
    }

    impl PlatformActionExecutor for MarkdownReadCounter {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            for action in batch.actions() {
                let read_path = match action {
                    PlatformAction::ReadToExchange { path, .. }
                    | PlatformAction::Read { path, .. } => Some(path),
                    PlatformAction::Stat { .. }
                    | PlatformAction::Inspect { .. }
                    | PlatformAction::ListChildren { .. }
                    | PlatformAction::EnsureDirectory { .. }
                    | PlatformAction::WriteFromExchange { .. }
                    | PlatformAction::ArtifactWrite { .. }
                    | PlatformAction::Move { .. }
                    | PlatformAction::Delete { .. } => None,
                };
                if let Some(path) = read_path
                    && Path::new(path.as_str())
                        .extension()
                        .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
                {
                    self.markdown_reads
                        .lock()
                        .expect("markdown read counter")
                        .push(path.as_str().to_owned());
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn remounting_an_unchanged_workspace_skips_markdown_body_reads() {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        let first_executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("first executor"));
        first_executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind first root");
        let config = WorkspaceSessionConfig {
            capability: capability.clone(),
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config.clone(), first_executor).expect("open first");
        session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-remount-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_12.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "durable memo".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        let seeded = session.rebuild_projection().expect("seed listing digest");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(!seeded.rewritten);
        drop(session);

        let remount_executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("remount executor"));
        remount_executor
            .bind_root(capability, workspace_dir.path())
            .expect("bind remount root");
        let counter = Arc::new(MarkdownReadCounter {
            inner: remount_executor,
            markdown_reads: Mutex::new(Vec::new()),
        });
        let remounted = WorkspaceSession::open(config, counter.clone()).expect("remount");
        let mount = remounted.last_mount_result().expect("mount result");
        assert!(!mount.rewritten);
        assert_eq!(mount.memos_indexed, 1);
        let markdown_reads = counter
            .markdown_reads
            .lock()
            .expect("markdown read counter")
            .clone();
        assert!(
            markdown_reads.is_empty(),
            "unchanged remount must not reread markdown: {markdown_reads:?}"
        );
    }

    #[test]
    fn remounting_with_a_hashed_empty_file_skips_markdown_body_reads() {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        let first_executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("first executor"));
        first_executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind first root");
        let config = WorkspaceSessionConfig {
            capability: capability.clone(),
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };
        fs::write(workspace_dir.path().join("empty.dat"), []).expect("write empty file");
        let session = WorkspaceSession::open(config.clone(), first_executor).expect("open first");
        session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-empty-file-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_12.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "durable memo".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        let seeded = session.rebuild_projection().expect("seed listing digest");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(!seeded.rewritten);
        drop(session);

        let remount_executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("remount executor"));
        remount_executor
            .bind_root(capability, workspace_dir.path())
            .expect("bind remount root");
        let counter = Arc::new(MarkdownReadCounter {
            inner: remount_executor,
            markdown_reads: Mutex::new(Vec::new()),
        });
        let remounted = WorkspaceSession::open(config, counter.clone()).expect("remount");
        let mount = remounted.last_mount_result().expect("mount result");
        assert!(!mount.rewritten);
        assert_eq!(mount.memos_indexed, 1);
        let markdown_reads = counter
            .markdown_reads
            .lock()
            .expect("markdown read counter")
            .clone();
        assert!(
            markdown_reads.is_empty(),
            "verified empty file must not make remount reread markdown: {markdown_reads:?}"
        );
    }

    /// Records every byte-level observation the executor receives, so the contract can pin
    /// that a planned create target is observed exactly once — at plan time — and the apply
    /// phase delegates its conflict/replay check to the write's own platform arbitration.
    struct PathObservationCounter {
        inner: Arc<FsPlatformActionExecutor>,
        byte_reads: Mutex<Vec<String>>,
        writes: Mutex<Vec<String>>,
    }

    impl PlatformActionExecutor for PathObservationCounter {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            for action in batch.actions() {
                match action {
                    PlatformAction::ReadToExchange { path, .. }
                    | PlatformAction::Read { path, .. } => {
                        self.byte_reads
                            .lock()
                            .expect("byte read counter")
                            .push(path.as_str().to_owned());
                    }
                    PlatformAction::WriteFromExchange { path, .. }
                    | PlatformAction::ArtifactWrite { path, .. } => {
                        self.writes
                            .lock()
                            .expect("write counter")
                            .push(path.as_str().to_owned());
                    }
                    PlatformAction::Stat { .. }
                    | PlatformAction::Inspect { .. }
                    | PlatformAction::ListChildren { .. }
                    | PlatformAction::EnsureDirectory { .. }
                    | PlatformAction::Move { .. }
                    | PlatformAction::Delete { .. } => {}
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn create_memo_apply_never_re_reads_create_targets() {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        let executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");
        let counter = Arc::new(PathObservationCounter {
            inner: executor,
            byte_reads: Mutex::new(Vec::new()),
            writes: Mutex::new(Vec::new()),
        });
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config, counter.clone()).expect("open session");

        session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-observe-once").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_10_20.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "delegated arbitration memo".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");

        let byte_reads = counter
            .byte_reads
            .lock()
            .expect("byte read counter")
            .clone();
        let writes = counter.writes.lock().expect("write counter").clone();
        assert!(!writes.is_empty(), "create must plan durable writes");
        assert!(
            byte_reads
                .iter()
                .all(|path| !path.starts_with(".lomo/history/v2/heads/")),
            "fresh identities have no history baseline: {byte_reads:?}"
        );
        for path in &writes {
            let count = byte_reads
                .iter()
                .filter(|read| read.as_str() == path)
                .count();
            let expected_reads = usize::from(!path.starts_with(".lomo/history/v2/"));
            assert_eq!(
                count, expected_reads,
                "mutable baselines need one plan read; immutable creates arbitrate at the \
                 platform write boundary; target {path}, reads {byte_reads:?}"
            );
        }
    }

    struct MetadataOnlyListing {
        inner: Arc<FsPlatformActionExecutor>,
        markdown_reads: Mutex<Vec<String>>,
    }

    impl PlatformActionExecutor for MetadataOnlyListing {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            for action in batch.actions() {
                let read_path = match action {
                    PlatformAction::ReadToExchange { path, .. }
                    | PlatformAction::Read { path, .. } => Some(path),
                    PlatformAction::Stat { .. }
                    | PlatformAction::Inspect { .. }
                    | PlatformAction::ListChildren { .. }
                    | PlatformAction::EnsureDirectory { .. }
                    | PlatformAction::WriteFromExchange { .. }
                    | PlatformAction::ArtifactWrite { .. }
                    | PlatformAction::Move { .. }
                    | PlatformAction::Delete { .. } => None,
                };
                if let Some(path) = read_path
                    && Path::new(path.as_str())
                        .extension()
                        .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
                {
                    self.markdown_reads
                        .lock()
                        .expect("markdown read counter")
                        .push(path.as_str().to_owned());
                }
            }
            let result = self.inner.execute(batch)?;
            let action_results = result
                .action_results()
                .iter()
                .map(|action_result| {
                    let outcome = match action_result.outcome() {
                        ActionOutcome::Applied(output) => {
                            ActionOutcome::Applied(redact_listed_content_digests(output)?)
                        }
                        ActionOutcome::AlreadySatisfied(output) => {
                            ActionOutcome::AlreadySatisfied(redact_listed_content_digests(output)?)
                        }
                        ActionOutcome::Failed(error) => ActionOutcome::Failed(error.clone()),
                    };
                    Ok(ActionResult::new(
                        action_result.action_id().clone(),
                        outcome,
                    ))
                })
                .collect::<Result<Vec<_>, LomoError>>()?;
            Ok(PlatformBatchResult::new(
                result.schema_version(),
                result.job_id().clone(),
                result.batch_id().clone(),
                result.attempt(),
                action_results,
            ))
        }
    }

    fn redact_listed_content_digests(
        output: &PlatformActionOutput,
    ) -> Result<PlatformActionOutput, LomoError> {
        let PlatformActionOutput::Listed { page } = output else {
            return Ok(output.clone());
        };
        let items = page
            .items()
            .iter()
            .map(|item| {
                if item.kind() != DocumentKind::File {
                    return Ok(item.clone());
                }
                DocumentMetadata::new_with_handle(
                    item.target().clone(),
                    item.document_handle().clone(),
                    item.kind(),
                    item.mime_type(),
                    ActionEvidence::unknown(
                        item.evidence().length(),
                        item.evidence().fingerprint(),
                    )?,
                )
            })
            .collect::<Result<Vec<_>, LomoError>>()?;
        Ok(PlatformActionOutput::Listed {
            page: MetadataPage::new(items, page.next_cursor().map(CapabilityToken::as_str))?,
        })
    }

    struct IncompleteListing {
        inner: Arc<FsPlatformActionExecutor>,
        rejected_target: Mutex<Option<WorkspaceTarget>>,
    }

    impl PlatformActionExecutor for IncompleteListing {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            let rejected = self
                .rejected_target
                .lock()
                .expect("rejected listing target");
            if let Some(action) = batch.actions().iter().find(|action| {
                matches!(action, PlatformAction::ListChildren { target, .. } if Some(target) == rejected.as_ref())
            }) {
                let observed = self.inner.execute(batch)?;
                let failure = LomoError::from_platform_boundary(
                    ErrorCategory::Storage,
                    "metadata_enumeration_incomplete",
                    RetryDisposition::AfterUserAction,
                    None,
                    None,
                    "the platform document provider could not enumerate the target",
                )?;
                return Ok(PlatformBatchResult::new(
                    batch.schema_version(),
                    batch.job_id().clone(),
                    batch.batch_id().clone(),
                    batch.attempt(),
                    observed.action_results().iter().map(|result| {
                        if result.action_id() == action.id() {
                            ActionResult::new(action.id().clone(), ActionOutcome::Failed(failure.clone()))
                        } else {
                            result.clone()
                        }
                    }).collect(),
                ));
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn note_projection_opens_without_enumerating_the_attachment_namespaces() {
        let ctx = setup_test_context();
        drop(ctx.session);
        fs::create_dir(ctx.workspace_path.join("images")).expect("image directory");
        fs::write(
            ctx.workspace_path.join("images/2026_09_12.md"),
            "## 12:00:00\n\nattachment text\n",
        )
        .expect("attachment fixture");
        fs::write(
            ctx.workspace_path.join("2026_09_12.md"),
            "## 12:00:00\n\nnote text\n",
        )
        .expect("note fixture");
        fs::write(
            ctx.workspace_path.join("README.md"),
            "Workspace documentation",
        )
        .expect("unrelated Markdown fixture");
        let executor = Arc::new(IncompleteListing {
            inner: ctx.executor.clone(),
            rejected_target: Mutex::new(Some(WorkspaceTarget::Relative(
                RelativeWorkspacePath::parse("images").expect("image path"),
            ))),
        });
        let session = WorkspaceSession::open(ctx.config.clone(), executor)
            .expect("open despite an unavailable attachment listing");
        let result = session.rebuild_projection().expect("refresh notes");
        assert_eq!(result.memos_indexed, 1);
    }

    #[test]
    fn workspace_open_ignores_non_daily_and_invalid_date_markdown_including_in_trash() {
        let ctx = setup_test_context();
        drop(ctx.session);
        fs::write(
            ctx.workspace_path.join("2026_09_12.md"),
            "## 12:00:00\n\nnote text\n",
        )
        .expect("note fixture");
        fs::write(
            ctx.workspace_path.join("12345678.md"),
            "8-digit non-date Markdown fixture",
        )
        .expect("8-digit non-date fixture");
        fs::write(
            ctx.workspace_path.join("2024-02-30.md"),
            "Invalid calendar date Markdown fixture",
        )
        .expect("invalid calendar date fixture");
        fs::create_dir(ctx.workspace_path.join(".trash")).expect("trash directory");
        fs::write(
            ctx.workspace_path.join(".trash/README.md"),
            "Foreign Markdown inside trash",
        )
        .expect("foreign trash fixture");
        fs::write(
            ctx.workspace_path.join(".trash/2026_09_13.md"),
            "- 11:00:00 trashed daily note\n",
        )
        .expect("valid plain trash fixture");

        let session = WorkspaceSession::open(ctx.config.clone(), ctx.executor.clone())
            .expect("open workspace with non-daily markdown and foreign trash file");
        let page = session
            .query_memos_page(
                &MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(10).expect("page"),
            )
            .expect("query active memos");
        assert_eq!(page.items.len(), 1);
        assert!(
            ctx.workspace_path.join(".trash/README.md").exists(),
            "foreign trash markdown must stay untouched"
        );
    }

    #[test]
    fn metadata_only_listing_cannot_skip_rebuild_after_content_changes() {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        let posix =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("posix executor"));
        posix
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");
        let executor = Arc::new(MetadataOnlyListing {
            inner: posix,
            markdown_reads: Mutex::new(Vec::new()),
        });
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config.clone(), executor.clone()).expect("open");
        session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-weak-listing-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_12.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "durable memo".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .rebuild_projection()
            .expect("seed weak listing digest");
        let markdown_path = workspace_dir.path().join("2026_09_12.md");
        let original = fs::read_to_string(&markdown_path).expect("read markdown");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(
            original.contains("durable memo"),
            "created memo must be present before the content swap: {original:?}"
        );
        fs::write(
            &markdown_path,
            original.replace("durable memo", "changed body"),
        )
        .expect("replace markdown body");
        executor
            .markdown_reads
            .lock()
            .expect("markdown read counter")
            .clear();
        let rebuilt = session.rebuild_projection();
        assert!(
            rebuilt
                .as_ref()
                .is_err_and(|error| error.code() != "metadata_enumeration_incomplete")
                || rebuilt.as_ref().is_ok_and(|result| result.rewritten),
            "weak metadata-only listing must not certify an unchanged projection: {rebuilt:?}"
        );
        let markdown_reads = executor
            .markdown_reads
            .lock()
            .expect("markdown read counter")
            .clone();
        assert!(
            markdown_reads
                .iter()
                .any(|path| path.ends_with("2026_09_12.md")),
            "content change under a weak listing must reread markdown: {markdown_reads:?}"
        );
    }

    #[test]
    fn incomplete_listing_does_not_empty_the_live_projection() {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        let posix =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("posix executor"));
        posix
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");
        let executor = Arc::new(IncompleteListing {
            inner: posix,
            rejected_target: Mutex::new(None),
        });
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config, executor.clone()).expect("open");
        let created = session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-incomplete-listing-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_12.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "keep this memo".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        *executor
            .rejected_target
            .lock()
            .expect("rejected listing target") = Some(WorkspaceTarget::Root);
        let error = session
            .rebuild_projection()
            .expect_err("incomplete enumeration cannot rebuild");
        assert_eq!(error.code(), "metadata_enumeration_incomplete");
        let viewed = session
            .get_memo(&created.memo_id)
            .expect("query after incomplete listing")
            .expect("prior memo remains");
        assert_eq!(viewed.body, "keep this memo");
    }

    /// CAS baselines exist only so a future conflict can cite committed bytes. A read
    /// must never materialize one; a write retains exactly the committed bytes; a blob
    /// no live document fingerprint and no retained draft evidence cites is stale and
    /// is evicted at the next session open. Foreign filenames are never eviction's
    /// business.
    #[test]
    fn reads_never_materialize_baselines_and_stale_baselines_evict_on_open() {
        let ctx = setup_test_context();
        let baselines_dir = ctx.config.state_dir.join("baselines");
        let baseline_names = |dir: &Path| -> BTreeSet<String> {
            let Ok(entries) = fs::read_dir(dir) else {
                return BTreeSet::new();
            };
            entries
                .filter_map(|entry| {
                    entry
                        .expect("baseline entry")
                        .file_name()
                        .to_str()
                        .map(str::to_owned)
                })
                .collect()
        };

        // A committed write retains its committed bytes as a CAS baseline.
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-baseline-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_09.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "baseline create body".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let v1_fingerprint = created.commit_result.file_fingerprint.clone();
        assert!(
            baseline_names(&baselines_dir).contains(&v1_fingerprint),
            "a committed write must baseline exactly the committed bytes"
        );

        // A file version the session only ever *reads* (an external edit re-scanned
        // through `io.read`) must never appear as a baseline. The edit appends a
        // block so the existing memo's identity binding stays resolvable.
        let doc_path = ctx.workspace_path.join("2026_09_09.md");
        let mut external = fs::read_to_string(&doc_path).expect("read committed doc");
        external.push_str("- 12:30:00\nexternal tail memo\n");
        fs::write(&doc_path, &external).expect("external write");
        let external_fingerprint = lomo_workspace::SourceFingerprint::of_bytes(external.as_bytes())
            .as_str()
            .to_owned();
        ctx.session
            .rebuild_projection()
            .expect("external edit reconciles");
        assert!(
            !baseline_names(&baselines_dir).contains(&external_fingerprint),
            "io.read must not materialize a baseline for bytes it only read"
        );

        // A committed update against the external bytes baselines the new committed
        // document; the superseded v1 baseline is stale evidence.
        let updated = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-baseline-update").expect("valid op"),
                memo_id: created.memo_id.clone(),
                content: "baseline update body".to_string(),
                expected_document_fingerprint: external_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update against the observed baseline");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let v3_fingerprint = updated.commit_result.file_fingerprint.clone();
        assert!(baseline_names(&baselines_dir).contains(&v3_fingerprint));

        // A fingerprint-shaped blob nothing cites is stale; a foreign filename is not
        // evidence the CAS wrote and must be left alone.
        let stale_bytes = b"bytes no committed document or retained draft still cites";
        let stale_name = lomo_workspace::SourceFingerprint::of_bytes(stale_bytes)
            .as_str()
            .to_owned();
        fs::write(baselines_dir.join(&stale_name), stale_bytes).expect("seed stale blob");
        fs::write(baselines_dir.join("not-a-fingerprint.bin"), b"foreign")
            .expect("seed foreign blob");

        drop(ctx.session);
        let reopened = WorkspaceSession::open(ctx.config.clone(), ctx.executor.clone())
            .expect("reopen runs baseline eviction");

        let kept = baseline_names(&baselines_dir);
        assert!(
            !kept.contains(&stale_name),
            "an unreferenced baseline must be evicted at open"
        );
        assert!(
            !kept.contains(&v1_fingerprint),
            "a superseded baseline must be evicted at open"
        );
        assert!(
            kept.contains(&v3_fingerprint),
            "the live committed baseline survives eviction"
        );
        assert!(
            kept.contains("not-a-fingerprint.bin"),
            "eviction never reaches for foreign files"
        );
        assert_eq!(
            reopened
                .get_memo(&created.memo_id)
                .expect("get")
                .map(|memo| memo.body),
            Some("baseline update body".to_string())
        );
    }

    /// CAS baselines exist only so a future conflict can cite committed *document*
    /// bytes. The same transaction also commits `.lomo` records (the memo-identity
    /// binding plus the history head/object) whose bytes no live `memo`
    /// fingerprint and no retained draft evidence can ever cite — retaining them
    /// is write-time garbage that open-time eviction must unlink+fsync one blob at
    /// a time. The commit boundary therefore baselines only the document write.
    #[test]
    fn internal_record_writes_never_materialize_baselines() {
        let ctx = setup_test_context();
        let baselines_dir = ctx.config.state_dir.join("baselines");
        let baseline_names = |dir: &Path| -> BTreeSet<String> {
            let Ok(entries) = fs::read_dir(dir) else {
                return BTreeSet::new();
            };
            entries
                .filter_map(|entry| {
                    entry
                        .expect("baseline entry")
                        .file_name()
                        .to_str()
                        .map(str::to_owned)
                })
                .collect()
        };

        // A create commits the document plus its `.lomo` cohort (identity record,
        // history object, history head). Exactly one baseline may exist: the
        // committed document bytes.
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-record-baseline-create").expect("valid op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_09.md").expect("path")),
                time_token: Some("12:00:00".to_string()),
                content: "baseline create body".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let v1_fingerprint = created.commit_result.file_fingerprint.clone();
        assert_eq!(
            baseline_names(&baselines_dir),
            BTreeSet::from([v1_fingerprint.clone()]),
            "only committed document bytes may occupy the baseline CAS; every \
             `.lomo` record write in the same transaction is unreferenceable \
             churn"
        );

        // An update repeats the cohort under a new revision — still exactly the
        // document baselines (the superseded one is retained until open-time
        // eviction, which is the previous test's concern).
        let updated = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-record-baseline-update").expect("valid op"),
                memo_id: created.memo_id.clone(),
                content: "baseline update body".to_string(),
                expected_document_fingerprint: v1_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(
            baseline_names(&baselines_dir),
            BTreeSet::from([v1_fingerprint, updated.commit_result.file_fingerprint]),
            "the `.lomo` record cohort of an update is likewise never baselined"
        );
    }
}
