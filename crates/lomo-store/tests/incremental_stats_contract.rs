//! Behavior Contract:
//! Capability: transactional aggregates and indexed startup/reminder partitions; owner: lomo-store; priority: P1.
//! Scenarios: Given a populated projection, when lifecycle bits change or a row is deleted,
//! then aggregate counts change in the same transaction and rollback restores them.
//! Given an admitted projection, when startup recovery or reminder queries run, then their
//! plans use only the pending-operation or active-reminder partition, never the full memo table.
//! Observable outcomes: `query_stats` before commit/after rollback/delete, and SQLite query plans.
//! TDD proof: RED/GREEN: `cargo test -p lomo-store --test incremental_stats_contract --locked`.
//! Excludes: wall-clock latency and Android frame timing.
mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing storage facts"
)]
mod tests {
    use super::support::{indexed_store, seed_memo};
    use lomo_store::open_store;
    use tempfile::tempdir;

    #[test]
    fn startup_and_reminder_queries_seek_only_their_indexed_partitions() {
        let root = tempdir().expect("temporary root");
        let opened = open_store(root.path()).expect("open store");
        for (sql, index) in [
            (
                "SELECT rowid,search_content FROM memo WHERE pending_operation_id IS NOT NULL",
                "idx_memo_pending_operation",
            ),
            (
                "SELECT memo_id,reminders_json FROM memo INDEXED BY idx_memo_reminders WHERE is_trashed=0 AND pending_operation_id IS NULL AND reminders_json!='[]'",
                "idx_memo_reminders",
            ),
        ] {
            let mut statement = opened
                .connection
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .expect("query plan");
            let plan = statement
                .query_map([], |row| row.get::<_, String>(3))
                .expect("explain")
                .map(|row| row.expect("plan row"))
                .collect::<Vec<_>>()
                .join("\n");
            assert!(plan.contains(index), "query must use {index}: {plan}");
            assert!(
                !plan.lines().any(|line| line == "SCAN memo"),
                "query must not scan the memo table: {plan}"
            );
        }
    }

    #[test]
    fn row_changes_and_rollbacks_keep_aggregate_counts_exact() {
        let root = tempdir().expect("temporary root");
        seed_memo(root.path(), "one", "hello #tag", &["tag"]);
        drop(indexed_store(root.path()));
        let opened = open_store(root.path()).expect("open store");
        let connection = &opened.connection;
        connection
            .execute_batch("BEGIN; UPDATE memo SET is_pinned=1, is_trashed=1;")
            .expect("change lifecycle");
        let counts: (i64, i64) = connection.query_row(
            "SELECT (SELECT value_i64 FROM stats WHERE key='pinned_count'), (SELECT value_i64 FROM stats WHERE key='trashed_count')", [],
            |row| Ok((row.get(0)?, row.get(1)?))).expect("aggregate counts");
        assert_eq!(counts, (1, 1));
        connection
            .execute_batch("ROLLBACK; DELETE FROM memo; DELETE FROM tag;")
            .expect("rollback and delete");
        let total: i64 = connection
            .query_row("SELECT SUM(value_i64) FROM stats", [], |row| row.get(0))
            .expect("read aggregate sum");
        assert_eq!(total, 0);
    }
}
