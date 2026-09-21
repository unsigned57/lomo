//! Behavior Contract (B02 item 5 / T33: owner-side conflict merge + suggestion)
//!
//! Capability: `lomo-sync` owns text merge, memo-identity merge delegation, and time adjudication
//! for conflict suggestions; Kotlin only displays `suggested_choice`/`merged_body` and submits the
//! user's choice or edited text.
//!
//! Scenarios:
//! - Given one side empty/missing or equal text, when suggested, then the deterministic side wins.
//! - Given non-overlapping insertions around common anchors or a superset segment, when merged,
//!   then the bounded merged text is returned.
//! - Given disjoint memo content, when merged, then owner identity merge wins; otherwise older side
//!   concatenates first by mtime (local first when mtimes are absent).
//! - Given overlapping edits or budgets exceeded, when merged, then `merged_body` is `None` and the
//!   conflict stays open for review.
//! - Given normalized-equal / blank / containment bodies, when suggested, then the safe choice is
//!   `keep_remote`/`keep_local`/`merge_text` per the adjudication chain.
//! - Given a ≥5-minute newer side with no safe choice, when suggested, then only `suggested_choice`
//!   carries the newer side; `safe_choice` stays `None`.
//! - Given a binary path, when suggested, then all fields are empty.
//!
//! Observable outcomes: `ConflictSuggestion` fields.
//!
//! Excludes: durable session revision fencing, artifact persistence, Kotlin enum mapping.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_sync::{ConflictSuggestionChoice, suggest_conflict_resolution};

    fn suggest(local: Option<&str>, remote: Option<&str>) -> lomo_sync::ConflictSuggestion {
        suggest_conflict_resolution(local, remote, None, None, false)
    }

    #[test]
    fn empty_or_missing_side_returns_the_other_side() {
        let s = suggest(Some("local only"), None);
        assert_eq!(s.merged_body.as_deref(), Some("local only"));

        let s = suggest(None, Some("remote only"));
        assert_eq!(s.merged_body.as_deref(), Some("remote only"));
        // Whole-side-present merge is a safe keep of that side.
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepRemote));
        assert_eq!(
            s.suggested_choice,
            Some(ConflictSuggestionChoice::KeepRemote)
        );
    }

    #[test]
    fn anchored_insertions_and_superset_segments_merge() {
        let s = suggest(
            Some("start\nlocal\nmiddle\nend"),
            Some("start\nmiddle\nremote\nend"),
        );
        assert_eq!(
            s.merged_body.as_deref(),
            Some("start\nlocal\nmiddle\nremote\nend")
        );

        let s = suggest(Some("alpha\nbeta"), Some("alpha\nbeta\ngamma"));
        assert_eq!(s.merged_body.as_deref(), Some("alpha\nbeta\ngamma"));
        // Remote is a strict superset → safe keep_remote, no merge_text needed.
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepRemote));
    }

    #[test]
    fn disjoint_memo_content_concatenates_older_side_first() {
        let s = suggest_conflict_resolution(
            Some("local idea\nlocal detail"),
            Some("remote idea\nremote detail"),
            Some(20),
            Some(10),
            false,
        );
        assert_eq!(
            s.merged_body.as_deref(),
            Some("remote idea\nremote detail\n\nlocal idea\nlocal detail")
        );
        assert_eq!(
            s.suggested_choice,
            Some(ConflictSuggestionChoice::MergeText)
        );

        // Missing mtimes order local first.
        let s = suggest(Some("local-only note"), Some("remote-only note"));
        assert_eq!(
            s.merged_body.as_deref(),
            Some("local-only note\n\nremote-only note")
        );
    }

    #[test]
    fn owner_identity_merge_wins_over_disjoint_concat() {
        // Two Lomo memo shards sharing a memo timestamp identity: owner merge emits the newer side's
        // block for the shared key instead of blind concat.
        let local = "- 10:30 local edit\n- 11:00 local only";
        let remote = "- 10:30 remote edit\n- 11:30 remote only";
        let s = suggest_conflict_resolution(Some(local), Some(remote), Some(20), Some(10), false);
        let merged = s
            .merged_body
            .as_deref()
            .expect("identity merge or concat must produce a body");
        assert_ne!(
            merged,
            format!("{remote}\n\n{local}"),
            "identity merge must not blind-concat shared-timestamp shards"
        );
    }

    #[test]
    fn overlapping_edits_and_budget_decline_to_review() {
        // Shared anchors with conflicting segments between them cannot merge → decline.
        let s = suggest(Some("start\nlocal\nend"), Some("start\nremote\nend"));
        assert_eq!(s.merged_body, None);
        assert_eq!(s.safe_choice, None);

        // Disjoint bodies with no shared content still merge by older-first concat.
        let s = suggest(Some("a\nb\nc"), Some("x\ny\nz"));
        assert_eq!(s.merged_body.as_deref(), Some("a\nb\nc\n\nx\ny\nz"));

        // Line-count budget exceeded → decline.
        let big_local = (0..1_001)
            .map(|i| format!("l{i}"))
            .collect::<Vec<_>>()
            .join("\n");
        let s = suggest(Some(&big_local), Some("remote"));
        assert_eq!(s.merged_body, None);
    }

    #[test]
    fn adjudication_chain_blank_containment_and_newer_side() {
        // Normalized equal → keep_remote.
        let s = suggest(Some("same\n"), Some("same"));
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepRemote));

        // Blank local → keep_remote; blank remote → keep_local.
        let s = suggest(Some("  \n"), Some("content"));
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepRemote));
        let s = suggest(Some("content"), Some("\n\n"));
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepLocal));

        // Containment → keep the containing side.
        let s = suggest(Some("alpha"), Some("alpha\nbeta"));
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepRemote));
        let s = suggest(Some("alpha\nbeta"), Some("beta"));
        assert_eq!(s.safe_choice, Some(ConflictSuggestionChoice::KeepLocal));
    }

    #[test]
    fn newer_side_only_fills_suggested_not_safe() {
        // Unmergeable anchored overlap + remote much newer → suggested keep_remote, safe stays None.
        let s = suggest_conflict_resolution(
            Some("start\nlocal\nend"),
            Some("start\nremote\nend"),
            Some(0),
            Some(6 * 60 * 1_000),
            false,
        );
        assert_eq!(s.safe_choice, None);
        assert_eq!(
            s.suggested_choice,
            Some(ConflictSuggestionChoice::KeepRemote)
        );

        // Under the threshold → nothing suggested.
        let s = suggest_conflict_resolution(
            Some("start\nlocal\nend"),
            Some("start\nremote\nend"),
            Some(0),
            Some(60 * 1_000),
            false,
        );
        assert_eq!(s.suggested_choice, None);
    }

    #[test]
    fn binary_paths_never_suggest() {
        let s =
            suggest_conflict_resolution(Some("local"), Some("remote"), Some(20), Some(10), true);
        assert_eq!(s.safe_choice, None);
        assert_eq!(s.suggested_choice, None);
        assert_eq!(s.merged_body, None);
    }
}
