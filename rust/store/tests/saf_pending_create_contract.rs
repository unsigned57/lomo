//! Behavior Contract
//!
//! Capability: a SAF memo create publishes a pending projection row at operation begin (before any
//! durable platform I/O), upgrades the same memo identity in place at projection commit, and rolls
//! the pending row back when the platform pipeline fails. Pending rows are volatile projection
//! state: reopening the store sweeps rows whose operation never committed.
//!
//! Scenarios:
//! - Given a begin, when queried, then the memo is visible with `is_pending` true, the allocated
//!   identity is `dateKey_timePart_0`, content revision is 1, counters advanced once and the
//!   create scopes (`MemoList`/`Search`/`Tags`/`Stats`) are returned.
//! - Given a begin, when the same `operation_id` begins again, then the same identity is returned
//!   as an idempotent replay without a second counter bump.
//! - Given a begin, when the projection commit arrives with the same operation id, then the row
//!   upgrades in place (`is_pending` false, final fingerprint, revision 1), the mutation is
//!   recorded for replay, and counters advanced exactly twice across begin+commit.
//! - Given a commit without a prior begin, when it creates, then the legacy shape still works and
//!   the row is not pending.
//! - Given a begin bound to one operation, when a different operation commits the same memo id,
//!   then `saf_projection_create_conflict` fails closed and the pending row remains.
//! - Given a begin, when rollback runs, then the pending row disappears, counters advance once
//!   more, and a second rollback is a no-op that does not move counters.
//! - Given committed memos sharing the same time part, when begin runs again, then the next
//!   ordinal is allocated (no identity collision).
//! - Given a begin whose process ends before commit, when the store reopens, then the stale
//!   pending row is swept and the list is empty.
//! - Given an invalid date key / time part / chronology, when begin runs, then a typed validation
//!   error is returned without publishing anything.
//!
//! Observable outcomes: query summaries (including `is_pending`), allocated identities, revision
//! and event counters, invalidation scopes, typed error codes.
//!
//! Excludes: platform-action execution timing and Android binder behavior (owned by the data
//! layer orchestration tests).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::{InvalidationScope, OperationId, PageSize};
    use lomo_store::{
        MemoFilters, MemoQuery, MemoSort, SafMemoCreateBegin, SafProjectionMutation,
        SafProjectionMutationKind, ScannedMemoProjection, Store,
    };
    use tempfile::tempdir;

    const DATE_KEY: &str = "2026-09-01";
    const TIME_PART: &str = "10:30:00";
    const SOURCE_PATH: &str = "2026-09-01.md";
    const CHRONOLOGY_MS: i64 = 1_787_614_200_000;

    fn begin_facts(operation_id: &'static str, body: &'static str) -> SafMemoCreateBegin {
        SafMemoCreateBegin {
            operation_id: OperationId::parse(operation_id)
                .expect("operation id")
                .as_str()
                .to_owned(),
            date_key: DATE_KEY.to_owned(),
            time_part: TIME_PART.to_owned(),
            chronology_epoch_ms: CHRONOLOGY_MS,
            source_path: SOURCE_PATH.to_owned(),
            body: body.to_owned(),
        }
    }

    fn expected_identity(ordinal: u32) -> String {
        format!("{DATE_KEY}_{TIME_PART}_{ordinal}")
    }

    fn fingerprint_of(body: &str) -> String {
        lomo_store::fingerprint_content(body)
    }

    fn commit_mutation(operation_id: &str, memo_id: &str, body: &str) -> SafProjectionMutation {
        SafProjectionMutation {
            operation_id: operation_id.to_owned(),
            kind: SafProjectionMutationKind::Create,
            memo_id: memo_id.to_owned(),
            expected_revision: 0,
            expected_fingerprint: None,
            projection: Some(ScannedMemoProjection {
                memo_id: memo_id.to_owned(),
                source_path: SOURCE_PATH.to_owned(),
                file_fingerprint: fingerprint_of(body),
                chronology_epoch_ms: CHRONOLOGY_MS,
                body: body.to_owned(),
                tags: Vec::new(),
                attachment_paths: Vec::new(),
                has_todo: false,
                has_url: false,
                reminders: Vec::new(),
            }),
            trashed_at_ms: None,
        }
    }

    fn active_summaries(store: &Store) -> Vec<(String, bool)> {
        store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(20).expect("page size"),
            )
            .expect("query")
            .items
            .into_iter()
            .map(|summary| (summary.memo_id, summary.is_pending))
            .collect()
    }

    #[test]
    fn begin_publishes_pending_projection_before_durable_commit() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");

        let begin = store
            .begin_saf_memo_create(&begin_facts("op-begin-1", "pending body"))
            .expect("begin");

        assert_eq!(begin.memo_id, expected_identity(0));
        assert!(!begin.idempotent_replay);
        assert_eq!(begin.core_revision, 1);
        assert_eq!(begin.event_sequence, 1);
        assert_eq!(
            begin.scopes,
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Search,
                InvalidationScope::Tags,
                InvalidationScope::Stats,
            ]
        );
        assert_eq!(
            active_summaries(&store),
            vec![(expected_identity(0), true)],
            "the pending memo must be visible before any durable platform I/O"
        );
    }

    #[test]
    fn begin_replay_returns_same_identity_without_second_bump() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");

        let first = store
            .begin_saf_memo_create(&begin_facts("op-replay", "pending body"))
            .expect("begin");
        let replay = store
            .begin_saf_memo_create(&begin_facts("op-replay", "pending body"))
            .expect("begin replay");

        assert!(replay.idempotent_replay);
        assert_eq!(replay.memo_id, first.memo_id);
        assert_eq!(replay.core_revision, first.core_revision);
        assert_eq!(replay.event_sequence, first.event_sequence);
        assert_eq!(active_summaries(&store).len(), 1);
    }

    #[test]
    fn commit_upgrades_pending_row_in_place_under_same_identity() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let body = "durable body";
        let begin = store
            .begin_saf_memo_create(&begin_facts("op-commit", body))
            .expect("begin");

        let commit = store
            .commit_saf_projection_mutation(&commit_mutation("op-commit", &begin.memo_id, body))
            .expect("commit");

        assert_eq!(commit.memo_id, begin.memo_id);
        assert!(!commit.idempotent_replay);
        assert_eq!(commit.core_revision, 2);
        assert_eq!(commit.event_sequence, 2);
        assert_eq!(
            active_summaries(&store),
            vec![(begin.memo_id.clone(), false)],
            "the durable commit must upgrade the pending row without a second identity"
        );
        let snapshot = store
            .get_projected_memo(begin.memo_id.as_str())
            .expect("snapshot")
            .expect("memo present");
        assert_eq!(snapshot.summary.file_fingerprint, fingerprint_of(body));
        assert_eq!(snapshot.summary.content_revision, 1);
        assert!(!snapshot.summary.is_pending);
    }

    #[test]
    fn commit_without_prior_begin_still_creates_legacy_row() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let body = "legacy body";

        let commit = store
            .commit_saf_projection_mutation(&commit_mutation(
                "op-legacy",
                &expected_identity(0),
                body,
            ))
            .expect("commit");

        assert_eq!(commit.memo_id, expected_identity(0));
        assert_eq!(
            active_summaries(&store),
            vec![(expected_identity(0), false)],
            "the direct commit shape must remain non-pending"
        );
    }

    #[test]
    fn commit_under_different_operation_fails_closed_and_keeps_pending_row() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let begin = store
            .begin_saf_memo_create(&begin_facts("op-owner", "pending body"))
            .expect("begin");

        let error = store
            .commit_saf_projection_mutation(&commit_mutation(
                "op-other",
                &begin.memo_id,
                "pending body",
            ))
            .expect_err("a different operation must not complete a pending create");

        assert_eq!(error.code(), "saf_projection_create_conflict");
        assert_eq!(
            active_summaries(&store),
            vec![(begin.memo_id, true)],
            "the pending row must survive a refused commit"
        );
    }

    #[test]
    fn rollback_removes_pending_projection_and_republishes() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let begin = store
            .begin_saf_memo_create(&begin_facts("op-rollback", "pending body"))
            .expect("begin");

        let rollback = store
            .rollback_saf_memo_create("op-rollback", begin.memo_id.as_str())
            .expect("rollback");

        let publication = rollback.expect("pending row removed");
        assert_eq!(publication.core_revision, 2);
        assert_eq!(publication.event_sequence, 2);
        assert_eq!(
            publication.scopes,
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Search,
                InvalidationScope::Tags,
                InvalidationScope::Stats,
            ]
        );
        assert!(
            active_summaries(&store).is_empty(),
            "the failed create must not stay visible"
        );

        let counters = (store.high_water_revision(), store.event_sequence());
        let repeated = store
            .rollback_saf_memo_create("op-rollback", begin.memo_id.as_str())
            .expect("repeated rollback");
        assert!(repeated.is_none(), "rollback must be idempotent");
        assert_eq!(
            (store.high_water_revision(), store.event_sequence()),
            counters,
            "a no-op rollback must not publish"
        );
    }

    #[test]
    fn begin_allocates_next_ordinal_across_committed_and_pending_rows() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let body = "first body";
        store
            .commit_saf_projection_mutation(&commit_mutation(
                "op-first",
                &expected_identity(0),
                body,
            ))
            .expect("legacy create");
        store
            .begin_saf_memo_create(&begin_facts("op-second", "second body"))
            .expect("pending begin");

        let third = store
            .begin_saf_memo_create(&begin_facts("op-third", "third body"))
            .expect("begin after committed and pending rows");

        assert_eq!(third.memo_id, expected_identity(2));
        assert_eq!(active_summaries(&store).len(), 3);
    }

    #[test]
    fn reopening_sweeps_pending_rows_whose_operation_never_committed() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        store
            .begin_saf_memo_create(&begin_facts("op-crash", "never committed"))
            .expect("begin");
        assert_eq!(active_summaries(&store).len(), 1);
        drop(store);

        let reopened = Store::open_projection(root.path()).expect("reopen");
        assert!(
            active_summaries(&reopened).is_empty(),
            "stale pending rows are volatile projection state"
        );
    }

    #[test]
    fn begin_fails_closed_on_invalid_identity_parts() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");

        let bad_time_part = SafMemoCreateBegin {
            time_part: "10_30_00".to_owned(),
            ..begin_facts("op-invalid", "body")
        };
        let error = store
            .begin_saf_memo_create(&bad_time_part)
            .expect_err("time part must not contain underscores");
        assert_eq!(error.code(), "invalid_memo_identity_parts");

        let bad_chronology = SafMemoCreateBegin {
            chronology_epoch_ms: 0,
            ..begin_facts("op-invalid", "body")
        };
        let error = store
            .begin_saf_memo_create(&bad_chronology)
            .expect_err("chronology must be positive");
        assert_eq!(error.code(), "invalid_memo_chronology");
        assert!(
            active_summaries(&store).is_empty(),
            "rejected begins must not publish"
        );
    }

    #[test]
    fn begin_pending_create_does_not_poison_source_document_fingerprint() {
        let root = tempdir().expect("projection root");
        let mut store = Store::open_projection(root.path()).expect("open projection");

        assert_eq!(
            store
                .source_document_fingerprint("2026_08_04.md")
                .expect("fingerprint query on empty doc"),
            None
        );

        store
            .begin_saf_memo_create(&begin_facts("op-pending-1", "pending body"))
            .expect("begin");

        assert_eq!(
            store
                .source_document_fingerprint("2026_08_04.md")
                .expect("fingerprint query with pending memo must ignore pending placeholder"),
            None,
            "pending memo rows must not synthesize an artificial document fingerprint"
        );
    }
}
