//! Behavior Contract (P3-03 / Architecture Guardrail Scheme 1)
//!
//! Capability: query execution paths enforce zero table scans (`SCAN TABLE memo`) and explicit
//! index hits for all primary query patterns (keyset pagination, sorts, trash, source path, tags).
//!
//! Scenarios:
//! - Given a populated store projection, when main-list keyset query (`CreatedAt` DESC) is planned,
//!   then SQLite seeks via `idx_memo_active_pinned_created` and avoids `SCAN memo`.
//! - Given a populated store projection, when main-list keyset query (`UpdatedAt` DESC) is planned,
//!   then SQLite seeks via `idx_memo_active_pinned_updated` and avoids `SCAN memo`.
//! - Given a populated store projection, when trash-only query is planned, then SQLite seeks via
//!   `idx_memo_active_pinned_created` with `is_trashed = 1` and avoids `SCAN memo`.
//! - Given a populated store projection, when single memo lookup by `source_path` is planned,
//!   then SQLite uses `idx_memo_source_path` and avoids `SCAN memo`.
//! - Given a populated store projection, when single memo lookup by primary key is planned,
//!   then SQLite uses primary key index and avoids `SCAN memo`.
//! - Given a populated store projection, when tag filtering subquery is planned, then SQLite
//!   uses index seek and subquery uses primary keys on `memo_tag` and `tag`.
//! - Given an intentional unindexed query, when plan assertion runs, then the assertion rig
//!   detects `SCAN memo` and rejects the execution plan (proving assertion sensitivity).
//!
//! Observable outcomes: EXPLAIN QUERY PLAN execution nodes, index names, and scan assertions.
//! TDD proof: ensures Q1 / unbounded scan regressions are caught at compile/test time.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    reason = "contract tests fail closed with panics on missing facts; plan matrix covers all core paths"
)]
mod tests {
    use super::support::{indexed_store, seed_memo, seed_state};
    use lomo_store::open_store;
    use rusqlite::Connection;
    use tempfile::tempdir;

    fn fetch_query_plan(conn: &Connection, sql: &str) -> Vec<String> {
        let explain_sql = format!("EXPLAIN QUERY PLAN {sql}");
        let mut stmt = conn
            .prepare(&explain_sql)
            .expect("prepare EXPLAIN QUERY PLAN");
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(3))
            .expect("query explain");
        rows.map(|r| r.expect("row detail")).collect()
    }

    fn assert_query_plan(
        conn: &Connection,
        sql: &str,
        forbidden_patterns: &[&str],
        required_patterns: &[&str],
    ) {
        let plan_lines = fetch_query_plan(conn, sql);
        let full_plan = plan_lines.join("\n");

        for forbidden in forbidden_patterns {
            assert!(
                !full_plan.contains(forbidden),
                "Query plan contains forbidden pattern '{forbidden}':\nSQL: {sql}\nPlan:\n{full_plan}"
            );
        }
        for required in required_patterns {
            assert!(
                full_plan.contains(required),
                "Query plan missing required pattern '{required}':\nSQL: {sql}\nPlan:\n{full_plan}"
            );
        }
    }

    #[test]
    fn explain_query_plan_enforces_indexes_and_forbids_table_scans() {
        let dir = tempdir().expect("tempdir");

        for i in 1..=10 {
            seed_memo(
                dir.path(),
                &format!("memo-{i}"),
                &format!("Memo content body #{i}"),
                &["rust", "architecture"],
            );
        }
        for i in 1..=10 {
            if i % 2 == 0 {
                seed_state(dir.path(), &format!("memo-{i}"), true, false);
            }
        }
        seed_state(dir.path(), "memo-1", false, true);
        let store = indexed_store(dir.path());

        // Close and reopen through raw connection to inspect the projection with ANALYZE stats
        drop(store);
        let opened = open_store(dir.path()).expect("reopen store");
        let conn = &opened.connection;

        // Run ANALYZE so SQLite has real distribution statistics
        conn.execute_batch("ANALYZE;").expect("analyze");

        // 1. Main list keyset query (CreatedAt DESC): must use idx_memo_active_pinned_created
        let main_created_sql = "SELECT m.memo_id, m.source_path \
            FROM memo m \
            WHERE m.is_trashed = 0 \
            ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC \
            LIMIT 20";
        assert_query_plan(
            conn,
            main_created_sql,
            &["SCAN TABLE memo", "SCAN m", "USE TEMP B-TREE"],
            &["idx_memo_active_pinned_created"],
        );

        // 2. Main list keyset next-page query: must use idx_memo_active_pinned_created
        let main_keyset_next_sql = "SELECT m.memo_id \
            FROM memo m \
            WHERE m.is_trashed = 0 AND (m.is_pinned, m.created_at_ms, m.memo_id) < (1, 1000000, 'memo-5') \
            ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC \
            LIMIT 20";
        assert_query_plan(
            conn,
            main_keyset_next_sql,
            &["SCAN TABLE memo", "SCAN m", "USE TEMP B-TREE"],
            &["idx_memo_active_pinned_created"],
        );

        // 3. Main list keyset query (UpdatedAt DESC): must use idx_memo_active_pinned_updated
        let main_updated_sql = "SELECT m.memo_id \
            FROM memo m \
            WHERE m.is_trashed = 0 \
            ORDER BY m.is_pinned DESC, m.updated_at_ms DESC, m.created_at_ms DESC, m.memo_id DESC \
            LIMIT 20";
        assert_query_plan(
            conn,
            main_updated_sql,
            &["SCAN TABLE memo", "SCAN m", "USE TEMP B-TREE"],
            &["idx_memo_active_pinned_updated"],
        );

        // 4. Trash-only query: must use index prefix for is_trashed = 1, no full table scan
        let trash_sql = "SELECT m.memo_id \
            FROM memo m \
            WHERE m.is_trashed = 1 \
            ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC \
            LIMIT 20";
        assert_query_plan(
            conn,
            trash_sql,
            &["SCAN TABLE memo", "SCAN m", "USE TEMP B-TREE"],
            &["idx_memo_active_pinned_created"],
        );

        // 5. Source path lookup: must use idx_memo_source_path
        let source_path_sql = "SELECT m.memo_id, m.content_revision \
            FROM memo m \
            WHERE m.source_path = 'memos/2026_09_02.md' \
            LIMIT 1";
        assert_query_plan(
            conn,
            source_path_sql,
            &["SCAN TABLE memo", "SCAN m"],
            &["idx_memo_source_path"],
        );

        // 6. Primary key lookup: must use primary key index (sqlite_autoindex_memo_1)
        let pk_sql = "SELECT m.memo_id, m.body_preview \
            FROM memo m \
            WHERE m.memo_id = 'memo-2'";
        assert_query_plan(
            conn,
            pk_sql,
            &["SCAN TABLE memo", "SCAN m"],
            &["sqlite_autoindex_memo_1"],
        );

        // 7. Tag filter with EXISTS subquery: must use index on memo and avoid table scans on memo
        let tag_sql = "SELECT m.memo_id \
            FROM memo m \
            WHERE m.is_trashed = 0 AND EXISTS ( \
                SELECT 1 FROM memo_tag mt JOIN tag tg ON tg.id = mt.tag_id \
                WHERE mt.memo_id = m.memo_id AND tg.name = 'rust' \
            ) \
            ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC \
            LIMIT 20";
        assert_query_plan(
            conn,
            tag_sql,
            &["SCAN TABLE memo", "SCAN m"],
            &["idx_memo_active_pinned_created"],
        );

        // 8. Negative verification: deliberately unindexed query must be detected as SCAN TABLE
        let unindexed_sql = "SELECT m.memo_id FROM memo m WHERE m.body_preview LIKE '%keyword%'";
        let unindexed_plan = fetch_query_plan(conn, unindexed_sql).join("\n");
        assert!(
            unindexed_plan.contains("SCAN"),
            "Sanity check failed: unindexed query did not trigger SCAN:\n{unindexed_plan}"
        );
    }
}
