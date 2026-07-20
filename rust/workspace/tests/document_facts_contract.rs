//! Behavior Contract - P2 source-addressed facts and reminders
//!
//! Capability: parse one exact workspace source into source-addressed semantic facts, project memo
//! analysis and render IR from those facts, and mutate a reminder only through a revision/span/token
//! fingerprint-bound `ReminderRef`.
//!
//! Scenarios:
//! - Given legal and illegal reminder candidates, when one source is parsed, then only the strict
//!   `@YYYY-MM-DD-HH:mm[xN][iM][rR][.done|.k]` grammar becomes reminder facts; invalid candidates
//!   remain ordinary text.
//! - Given duplicate identical reminder tokens, when facts are projected, then each occurrence has
//!   its own exact absolute byte span and opaque reference.
//! - Given a reminder reference from an old source revision, when an externally edited document is
//!   patched, then `stale_snapshot` is returned and no similar token is selected.
//! - Given any projected semantic fact, when its span slices the exact source, then the slice is the
//!   source text that produced that fact.
//! - Given extension expansion or an overlong title/language/destination, when final IR validation
//!   runs, then it fails closed with `resource_limit`; post-parse expansion cannot bypass budgets.
//!
//! Observable outcomes: typed semantic facts, exact source slices, distinct `ReminderRef` values,
//! byte-local reminder patches, and structured resource-limit/stale errors.
//! TDD proof: RED on the pre-fix tree because semantic facts/ReminderRef do not exist, reminder
//! matching accepts malformed suffixes, extension nodes bypass the final budget, and title/language
//! strings are unchecked.
//! Excludes: Android provider execution, Compose layout, scheduling policy, and production DI.

#[cfg(test)]
mod support;

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        DocumentPatchCommand, MAX_IR_STRING_UTF8_BYTES, MAX_RENDER_DOCUMENT_NODES,
        SemanticFactKind, SourceBytes, WorkspaceRelativePath, parse_workspace_document,
        plan_document_patch, render_markdown,
    };

    fn reminders(
        document: &lomo_workspace::WorkspaceDocument,
    ) -> Vec<&lomo_workspace::ReminderRef> {
        document
            .memos()
            .iter()
            .flat_map(lomo_workspace::WorkspaceMemo::reminders)
            .collect()
    }

    #[test]
    fn strict_reminder_grammar_keeps_invalid_candidates_as_text() {
        let valid = [
            "@2026-07-18-09:00",
            "@2026-07-18-09:00x2",
            "@2026-07-18-09:00x2i5",
            "@2026-07-18-09:00x2i5rd",
            "@2026-07-18-09:00x2i5rw.done",
            "@2026-07-18-09:00x2.1",
        ];
        let invalid = [
            "@2026-02-30-09:00",
            "@2026-07-18-24:00",
            "@2026-07-18-09:00:30",
            "@2026-07-18-09:00x0",
            "@2026-07-18-09:00i0",
            "@2026-07-18-09:00i5x2",
            "@2026-07-18-09:00rx",
            "@2026-07-18-09:00.donejunk",
            "mail@2026-07-18-09:00",
        ];
        let text = format!("- 09:00:00\n{}\n{}\n", valid.join(" "), invalid.join(" "));
        let source = SourceBytes::try_from_str(&text).test_ok("source");
        let document = parse_workspace_document(&source, "2026-07-18").test_ok("parse");
        let actual: Vec<_> = reminders(&document)
            .into_iter()
            .map(|reference| reference.token().to_owned())
            .collect();
        assert_eq!(actual, valid);
        for candidate in invalid {
            assert!(document.render_document().plain_text().contains(candidate));
        }
    }

    #[test]
    fn duplicate_reminders_have_distinct_exact_spans_and_revision_bound_refs() {
        let token = "@2026-07-18-09:00x2i5rd.done";
        let text = format!("- 09:00:00\nfirst {token} then {token}\n");
        let source = SourceBytes::try_from_str(&text).test_ok("source");
        let document = parse_workspace_document(&source, "2026-07-18").test_ok("parse");
        let refs = reminders(&document);
        assert_eq!(refs.len(), 2);
        assert_ne!(refs[0].source_span(), refs[1].source_span());
        assert_ne!(refs[0].opaque_id(), refs[1].opaque_id());
        for reference in refs {
            assert_eq!(
                document
                    .source()
                    .slice(reference.source_span())
                    .test_ok("slice"),
                token
            );
            assert_eq!(reference.revision(), document.source().fingerprint());
            assert_eq!(reference.token_fingerprint().as_str().len(), 64);
            assert_eq!(reference.due_at_local(), "2026-07-18-09:00");
            assert_eq!(reference.repeat_count(), 2);
            assert_eq!(reference.interval_minutes(), 5);
            assert_eq!(reference.recurrence_code(), "d");
            assert!(reference.done());
            assert_eq!(reference.fired_count(), 0);
        }
    }

    #[test]
    fn old_revision_reminder_ref_fails_closed_without_first_match_fallback() {
        let token = "@2026-07-18-09:00x2";
        let original = format!("- 09:00:00\n{token} and {token}\n");
        let old_source = SourceBytes::try_from_str(&original).test_ok("old source");
        let old_document = parse_workspace_document(&old_source, "2026-07-18").test_ok("old parse");
        let second = reminders(&old_document)[1].clone();

        let externally_edited = format!("- 09:00:00\nexternal prefix {token} and {token}\n");
        let new_source = SourceBytes::try_from_str(&externally_edited).test_ok("new source");
        let new_document = parse_workspace_document(&new_source, "2026-07-18").test_ok("new parse");
        let error = plan_document_patch(
            &new_document,
            &DocumentPatchCommand::RewriteReminder {
                path: WorkspaceRelativePath::parse("2026-07-18.md").test_ok("path"),
                reminder: second,
                replacement: "@2026-07-18-10:00x2".to_owned(),
            },
        )
        .test_err("old reminder ref must be stale");
        assert_eq!(error.code(), "stale_snapshot");
        assert_eq!(
            new_document.serialize_unedited(),
            externally_edited.as_bytes()
        );
    }

    #[test]
    fn semantic_fact_spans_slice_the_exact_source() {
        let text =
            "- 09:00:00\n#tag [link](https://example.test) ![[media/a.png|a]] @2026-07-18-09:00\n";
        let source = SourceBytes::try_from_str(text).test_ok("source");
        let document = parse_workspace_document(&source, "2026-07-18").test_ok("parse");
        let facts = document.render_document().semantic_facts();
        assert!(
            facts
                .iter()
                .any(|fact| fact.kind() == SemanticFactKind::Tag)
        );
        assert!(
            facts
                .iter()
                .any(|fact| fact.kind() == SemanticFactKind::Link)
        );
        assert!(
            facts
                .iter()
                .any(|fact| fact.kind() == SemanticFactKind::Attachment)
        );
        assert!(
            facts
                .iter()
                .any(|fact| fact.kind() == SemanticFactKind::Reminder)
        );
        for fact in facts {
            let sliced = source.slice(fact.source_span()).test_ok("fact span");
            assert!(!sliced.is_empty(), "fact={fact:?}");
            assert!(
                fact.matches_source_slice(sliced),
                "fact={fact:?} slice={sliced:?}"
            );
        }
    }

    #[test]
    fn final_ir_budget_rejects_extension_node_explosion() {
        let count = usize::try_from(MAX_RENDER_DOCUMENT_NODES).test_ok("node limit") + 1;
        let text = std::iter::repeat_n("#tag ", count).collect::<String>();
        let source = SourceBytes::try_from_str(&text).test_ok("source");
        let error = render_markdown(&source).test_err("expanded nodes must exceed final budget");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "render_document_too_large");
    }

    #[test]
    fn all_ir_strings_are_checked_after_parsing() {
        let overlong = "x".repeat(MAX_IR_STRING_UTF8_BYTES + 1);
        for markdown in [
            format!("[short](https://example.test \"{overlong}\")"),
            format!("```{overlong}\ncode\n```"),
            format!("![alt](https://example.test/a.png \"{overlong}\")"),
        ] {
            let source = SourceBytes::try_from_str(&markdown).test_ok("source");
            let error = render_markdown(&source).test_err("IR string must be bounded");
            assert_eq!(error.category(), ErrorCategory::ResourceLimit);
            assert_eq!(error.code(), "ir_string_too_large");
        }
    }
}
