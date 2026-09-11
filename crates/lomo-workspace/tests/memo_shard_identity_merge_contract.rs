//! Behavior Contract:
//! - Unit under test: `merge_memo_shard_by_identity`
//! - Owning layer: lomo-workspace
//! - Priority tier: P0
//! - Capability: identity-keyed memo-shard conflict merge from owner document parse only.
//!   Merged bytes preserve dominant newline / inter-memo separator policy of the winning side
//!   (no hard-coded LF `"\n\n"` join; no silent CRLF→LF).
//!
//! Scenarios:
//! - Given both sides hold the same header timestamp (same memo edited), when merge runs, then the
//!   newer file's version wins without duplicating the block.
//! - Given a shared-timestamp memo plus distinct memos, when merge runs, then shared identity is
//!   deduplicated and distinct memos are unioned.
//! - Given no shared identities, plain Markdown, or a non-blank preamble, when merge runs, then
//!   `None` declines so non-identity merge can proceed.
//! - Given CRLF shards with shared identity, when merge runs, then merged text keeps CRLF and the
//!   winning side's blank-line separators.
//! - Given single-newline inter-memo layout, when merge runs, then separators stay single-newline
//!   (not forced blank lines).
//!
//! Observable outcomes: owner-planned merged UTF-8 bytes or decline.
//! Excludes: Kotlin header-line split, LCS line merge, repository write-back.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_workspace::merge_memo_shard_by_identity;

    #[test]
    fn keeps_newer_local_when_same_timestamp_edited() {
        let merged = merge_memo_shard_by_identity(
            "- 14:30:00 edited beginning",
            "- 14:30:00 original beginning",
            Some(20),
            Some(10),
        )
        .expect("parse")
        .expect("shared identity");
        assert_eq!(merged, "- 14:30:00 edited beginning");
    }

    #[test]
    fn keeps_newer_remote_when_same_timestamp_edited() {
        let merged = merge_memo_shard_by_identity(
            "- 14:30:00 stale local edit",
            "- 14:30:00 newer remote edit",
            Some(10),
            Some(20),
        )
        .expect("parse")
        .expect("shared identity");
        assert_eq!(merged, "- 14:30:00 newer remote edit");
    }

    #[test]
    fn deduplicates_shared_timestamp_while_keeping_distinct_memos() {
        let merged = merge_memo_shard_by_identity(
            "- 09:00:00 shared edited\n\n- 10:00:00 local only",
            "- 09:00:00 shared original",
            Some(20),
            Some(10),
        )
        .expect("parse")
        .expect("shared identity");
        assert_eq!(merged, "- 09:00:00 shared edited\n\n- 10:00:00 local only");
    }

    #[test]
    fn declines_when_sides_share_no_identity() {
        let merged = merge_memo_shard_by_identity(
            "- 09:00:00 local only",
            "- 10:00:00 remote only",
            Some(20),
            Some(10),
        )
        .expect("parse");
        assert!(merged.is_none());
    }

    #[test]
    fn declines_plain_markdown_without_headers() {
        let merged = merge_memo_shard_by_identity(
            "local idea\nlocal detail",
            "remote idea\nremote detail",
            Some(20),
            Some(10),
        )
        .expect("parse");
        assert!(merged.is_none());
    }

    #[test]
    fn declines_nonblank_preamble_before_first_memo() {
        let merged = merge_memo_shard_by_identity(
            "title line\n- 09:00:00 body",
            "- 09:00:00 other",
            Some(20),
            Some(10),
        )
        .expect("parse");
        assert!(merged.is_none());
    }

    #[test]
    fn preserves_crlf_and_winning_blank_line_separators() {
        let local = "- 09:00:00\r\nshared local\r\n\r\n- 10:00:00\r\nlocal only\r\n";
        let remote = "- 09:00:00\r\nshared remote\r\n";
        let merged = merge_memo_shard_by_identity(local, remote, Some(20), Some(10))
            .expect("parse")
            .expect("shared identity");
        assert!(
            merged.contains("\r\n"),
            "CRLF shards must not be forced to LF: {merged:?}"
        );
        assert!(
            !merged.contains("\n\n") || merged.contains("\r\n\r\n"),
            "must not invent bare LF double-newline separators: {merged:?}"
        );
        assert_eq!(
            merged,
            "- 09:00:00\r\nshared local\r\n\r\n- 10:00:00\r\nlocal only\r\n"
        );
    }

    #[test]
    fn preserves_single_newline_inter_memo_layout() {
        let local = "- 09:00:00 a\n- 10:00:00 b\n";
        let remote = "- 09:00:00 a\n";
        let merged = merge_memo_shard_by_identity(local, remote, Some(20), Some(10))
            .expect("parse")
            .expect("shared identity");
        assert_eq!(merged, "- 09:00:00 a\n- 10:00:00 b\n");
        assert!(
            !merged.contains("\n\n"),
            "must not invent blank-line separators when source used single newlines: {merged:?}"
        );
    }
}
