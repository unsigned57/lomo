// adversarial-audit: get_projected_memos batches candidate sets in
// 256-id chunks with bound parameters; dedup, unknown ids, injection-shaped ids
// and chunk-boundary crossings must not multiply, drop, or invent rows
//!
//! Hypothesis under audit:
//! - The `IN (...)` placeholder path binds each id as a parameter; an id shaped
//!   like SQL (`' OR '1'='1`) must match nothing instead of widening the query.
//! - Duplicate ids in the candidate set must not produce duplicate snapshots
//!   (callers re-key by `memo_id`; a duplicated row would corrupt paging totals).
//! - A candidate set larger than one 256-id chunk must hydrate every chunk,
//!   not silently truncate at the first page.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::{indexed_store, seed_memo};
    use tempfile::tempdir;

    #[test]
    fn batch_hydration_dedups_and_binds_injection_shaped_ids() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "first body #work", &[]);
        seed_memo(dir.path(), "m2", "second body", &[]);
        let store = indexed_store(dir.path());

        let snapshots = store
            .get_projected_memos(&[
                "m1".to_owned(),
                "m1".to_owned(), // duplicate id in one candidate set
                "m2".to_owned(),
                "ghost".to_owned(),
                "' OR '1'='1".to_owned(), // injection-shaped id must stay a literal
                "m1\" OR \"\"=\"".to_owned(),
            ])
            .expect("batch hydrate");
        let mut ids: Vec<&str> = snapshots
            .iter()
            .map(|snapshot| snapshot.summary.memo_id.as_str())
            .collect();
        ids.sort_unstable();
        assert_eq!(
            vec!["m1", "m2"],
            ids,
            "hydration must return exactly one snapshot per existing id: \
             no duplicates, no ghosts, no injection widening"
        );
    }

    #[test]
    fn batch_hydration_hydrates_every_chunk_past_the_256_boundary() {
        let dir = tempdir().expect("tempdir");
        // 260 ids force a second chunk; the tail chunk (4 ids) must not be lost.
        let total = 260_usize;
        for index in 0..total {
            seed_memo(
                dir.path(),
                &format!("m{index:04}"),
                &format!("body {index}"),
                &[],
            );
        }
        let store = indexed_store(dir.path());

        let ids: Vec<String> = (0..total).map(|index| format!("m{index:04}")).collect();
        let snapshots = store.get_projected_memos(&ids).expect("batch hydrate");
        assert_eq!(
            total,
            snapshots.len(),
            "every id past the 256 chunk boundary must hydrate"
        );
        for snapshot in &snapshots {
            let index: usize = snapshot
                .summary
                .memo_id
                .trim_start_matches('m')
                .parse()
                .expect("numeric id");
            assert_eq!(
                format!("body {index}"),
                snapshot.body,
                "chunked hydration must not cross-wire bodies to the wrong memo"
            );
        }
    }
}
