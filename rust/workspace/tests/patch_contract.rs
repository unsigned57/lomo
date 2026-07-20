//! Behavior Contract
//!
//! Capability: plan pure byte-local document patches (append, replace, remove, toggle-task) against
//! a parsed workspace document, validating path/identity/fingerprint/span/limits and failing closed
//! on external edits or non-unique targets — without I/O and without rewriting non-target bytes.
//!
//! Scenarios:
//! - Given a valid snapshot fingerprint, when replace/remove/append/toggle-task is planned, then only
//!   the target span changes (prefix + suffix byte-identical).
//! - Given a stale fingerprint, when any command is planned, then `stale_snapshot` is returned.
//! - Given a missing or non-unique memo identity, when replace/remove is planned, then validation
//!   fails without content/timestamp fallback.
//! - Given BOM / LF / CRLF / CR / trailing blank lines, when one memo is patched, then non-target
//!   bytes remain identical.
//! - Given mixed newlines, when append cannot decide a lossless newline, then planning fails closed.
//!
//! Observable outcomes: `DocumentPatchPlan` result bytes, structured error codes, byte-prefix /
//! changed-span / byte-suffix identity.
//! TDD proof: RED before patch planner exports exist; GREEN after pure planner lands.
//! Excludes: engine jobs / platform write (P2-05), FFI (P2-06), production dual-stack (P2-09).

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
    use super::support::{OptionTestExt, ResultTestExt};
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        DocumentPatchCommand, MemoIdentity, SourceBytes, SourceFingerprint, TaskSourceIdentity,
        WorkspaceRelativePath, parse_workspace_document, plan_document_patch,
    };

    fn path() -> WorkspaceRelativePath {
        WorkspaceRelativePath::parse("memos/2024-06-01.md").test_ok("path")
    }

    fn parse(text: &str) -> (lomo_workspace::WorkspaceDocument, SourceFingerprint) {
        let source = SourceBytes::try_from_str(text).test_ok("utf-8");
        let fingerprint = source.fingerprint().clone();
        let document = parse_workspace_document(&source, "2024-06-01").test_ok("parse");
        (document, fingerprint)
    }

    #[test]
    fn replace_changes_only_target_memo_span() {
        let text = "- 09:00:00\nfirst body\n\n- 10:00:00\nsecond body\n";
        let (document, fingerprint) = parse(text);
        let identity = MemoIdentity::parse("2024-06-01_09:00:00_0").test_ok("id");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: fingerprint,
                identity,
                content: "updated".to_owned(),
            },
        )
        .test_ok("plan");
        let source = document.source().as_bytes();
        let rebuilt = [
            plan.byte_prefix(source),
            plan.replacement(),
            plan.byte_suffix(source),
        ]
        .concat();
        assert_eq!(rebuilt, plan.result_bytes());
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert!(result.contains("- 09:00:00\nupdated"));
        assert!(result.contains("- 10:00:00\nsecond body\n"));
        assert!(!result.contains("first body"));
        assert_eq!(
            &plan.result_bytes()
                [plan.target_span().start()..plan.target_span().start() + plan.replacement().len()],
            plan.replacement()
        );
    }

    #[test]
    fn remove_drops_only_target_memo() {
        let text = "- 09:00:00\nfirst\n\n- 10:00:00\nsecond\n";
        let (document, fingerprint) = parse(text);
        let identity = MemoIdentity::parse("2024-06-01_10:00:00_0").test_ok("id");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Remove {
                path: path(),
                expected_fingerprint: fingerprint,
                identity,
            },
        )
        .test_ok("plan");
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert!(result.contains("- 09:00:00\nfirst"));
        assert!(!result.contains("10:00:00"));
        assert!(!result.contains("second"));
    }

    #[test]
    fn append_adds_new_memo_block_with_document_newline() {
        let text = "- 09:00:00\nexisting\n";
        let (document, fingerprint) = parse(text);
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: fingerprint,
                time_part: "11:00:00".to_owned(),
                content: "appended".to_owned(),
            },
        )
        .test_ok("plan");
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert!(result.starts_with("- 09:00:00\nexisting\n"));
        assert!(result.contains("- 11:00:00\nappended\n"));
        // prefix is full original source for pure append at end
        assert_eq!(
            plan.byte_prefix(document.source().as_bytes()),
            text.as_bytes()
        );
        assert_eq!(plan.byte_suffix(document.source().as_bytes()), b"");
    }

    #[test]
    fn toggle_task_flips_only_marker_bytes() {
        let text = "- 09:00:00\n- [ ] todo\n";
        let (document, fingerprint) = parse(text);
        let marker_start = text.find("[ ]").test_ok("marker");
        let identity = TaskSourceIdentity::try_new(marker_start, marker_start + 3).test_ok("task");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::ToggleTask {
                path: path(),
                expected_fingerprint: fingerprint,
                source_identity: identity,
            },
        )
        .test_ok("plan");
        assert_eq!(plan.replacement(), b"[x]");
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert_eq!(result, "- 09:00:00\n- [x] todo\n");
        assert_eq!(
            plan.byte_prefix(document.source().as_bytes()),
            &text.as_bytes()[..marker_start]
        );
        assert_eq!(
            plan.byte_suffix(document.source().as_bytes()),
            &text.as_bytes()[marker_start + 3..]
        );
    }

    #[test]
    fn stale_fingerprint_fails_closed() {
        let (document, _fingerprint) = parse("- 09:00:00\nbody\n");
        let stale = SourceFingerprint::of_bytes(b"not-the-source");
        let error = plan_document_patch(
            &document,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: stale,
                time_part: "10:00:00".to_owned(),
                content: "x".to_owned(),
            },
        )
        .test_err("stale");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "stale_snapshot");
    }

    #[test]
    fn missing_identity_fails_without_content_fallback() {
        let (document, fingerprint) = parse("- 09:00:00\nbody\n");
        let missing = MemoIdentity::parse("2024-06-01_12:00:00_0").test_ok("id");
        let error = plan_document_patch(
            &document,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: fingerprint,
                identity: missing,
                content: "nope".to_owned(),
            },
        )
        .test_err("missing");
        assert_eq!(error.code(), "memo_target_not_found");
    }

    #[test]
    fn bom_and_crlf_preserve_non_target_bytes() {
        let mut bytes = vec![0xEF, 0xBB, 0xBF];
        bytes.extend_from_slice(b"- 10:00:00\r\nfirst\r\n\r\n- 10:00:01\r\nsecond\r\n");
        let source = SourceBytes::try_from_bytes(bytes.clone()).test_ok("utf-8");
        let fingerprint = source.fingerprint().clone();
        let document = parse_workspace_document(&source, "2024-06-01").test_ok("parse");
        let identity = MemoIdentity::parse("2024-06-01_10:00:00_0").test_ok("id");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: fingerprint,
                identity,
                content: "changed".to_owned(),
            },
        )
        .test_ok("plan");
        let result = plan.result_bytes();
        assert_eq!(&result[..3], &[0xEF, 0xBB, 0xBF]);
        assert!(result.windows(2).any(|w| w == b"\r\n"));
        // second memo bytes after first replacement must still contain the original second block.
        let text = std::str::from_utf8(result).test_ok("utf-8");
        assert!(text.contains("- 10:00:01\r\nsecond\r\n"));
        assert!(text.contains("changed"));
        assert!(!text.contains("first"));
    }

    #[test]
    fn lf_cr_and_trailing_blank_lines_preserve_suffix() {
        let text = "- 08:00:00\nbody\n\n";
        let (document, fingerprint) = parse(text);
        let identity = MemoIdentity::parse("2024-06-01_08:00:00_0").test_ok("id");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: fingerprint,
                identity,
                content: "x".to_owned(),
            },
        )
        .test_ok("plan");
        // trailing blank after memo span may be suffix or absorbed depending on span end; non-target
        // suffix identity is the plan contract.
        let source = document.source().as_bytes();
        let rebuilt = [
            plan.byte_prefix(source),
            plan.replacement(),
            plan.byte_suffix(source),
        ]
        .concat();
        assert_eq!(rebuilt, plan.result_bytes());
    }

    #[test]
    fn mixed_newlines_fail_closed_on_append() {
        let text = "- 09:00:00\nbody\r\n";
        let (document, fingerprint) = parse(text);
        let error = plan_document_patch(
            &document,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: fingerprint,
                time_part: "10:00:00".to_owned(),
                content: "x".to_owned(),
            },
        )
        .test_err("mixed");
        assert_eq!(error.code(), "mixed_newline_ambiguous");
    }

    #[test]
    fn pure_cr_newline_append_uses_cr() {
        let text = "- 09:00:00\rbody\r";
        let (document, fingerprint) = parse(text);
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: fingerprint,
                time_part: "10:00:00".to_owned(),
                content: "next".to_owned(),
            },
        )
        .test_ok("plan");
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert!(result.contains("\r- 10:00:00\rnext\r") || result.contains("- 10:00:00\rnext\r"));
        assert!(!result.contains('\n'));
    }

    #[test]
    fn duplicate_timestamp_targets_by_ordinal_identity() {
        let text = "- 12:00:00\nfirst\n\n- 12:00:00\nsecond\n";
        let (document, fingerprint) = parse(text);
        let second = MemoIdentity::parse("2024-06-01_12:00:00_1").test_ok("id");
        let plan = plan_document_patch(
            &document,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: fingerprint,
                identity: second,
                content: "only-second".to_owned(),
            },
        )
        .test_ok("plan");
        let result = std::str::from_utf8(plan.result_bytes()).test_ok("utf-8");
        assert!(result.contains("first"));
        assert!(result.contains("only-second"));
        assert!(
            !result.contains("\nsecond\n") && !result.ends_with("\nsecond\n"),
            "original second body must be replaced: {result:?}"
        );
        assert!(
            result.contains("- 12:00:00\nfirst\n") && result.contains("- 12:00:00\nonly-second"),
            "both ordinals keep the shared timestamp header: {result:?}"
        );
    }
}
