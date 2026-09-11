//! Behavior Contract
//!
//! Capability: keep existing Markdown bytes and document structure intact during local edits.
//! Owning layer: lomo-workspace; priority P0.
//! Scenarios:
//! - Given time-like lines in fenced code, nested lists, comments or frontmatter, parsing never
//!   promotes those lines to independent memos.
//! - Given an inline header or a plain Markdown document, replacing a body preserves the existing
//!   header spelling and document format, including BOM, comments, frontmatter and separators.
//! - Given CRLF or CR source, inserted multiline content follows that newline convention.
//! - Given an empty BOM source or a header without a newline, append/replace creates one valid memo.
//! - Given a fingerprint computed after newline normalization, writes reject that stale baseline.
//!
//! Observable outcomes: exact resulting bytes, memo counts, content, spans and conflict codes.
//! TDD proof: new fidelity scenarios fail against header reconstruction and line-only segmentation.
//! Excludes: platform file execution, persistent memo IDs, and UI rendering.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use lomo_workspace::{
        DocumentFormat, DocumentPatchCommand, SourceBytes, SourceFingerprint, WorkspaceDocument,
        WorkspaceRelativePath, parse_workspace_document, plan_document_patch,
    };

    use super::support::{OptionTestExt, ResultTestExt};

    fn parse(raw: &str) -> WorkspaceDocument {
        let source = SourceBytes::try_from_str(raw).test_ok("valid UTF-8");
        parse_workspace_document(&source, "2026_09_08").test_ok("document")
    }

    fn replace(raw: &str, content: &str) -> Vec<u8> {
        let document = parse(raw);
        let command = DocumentPatchCommand::Replace {
            path: WorkspaceRelativePath::parse("2026_09_08.md").test_ok("path"),
            expected_fingerprint: document.source().fingerprint().clone(),
            identity: document.memos().first().test_ok("memo").identity().clone(),
            content: content.to_owned(),
        };
        plan_document_patch(&document, &command)
            .test_ok("replace")
            .result_bytes()
            .to_vec()
    }

    fn append(raw: &str, content: &str) -> Vec<u8> {
        let document = parse(raw);
        let command = DocumentPatchCommand::Append {
            path: WorkspaceRelativePath::parse("2026_09_08.md").test_ok("path"),
            expected_fingerprint: document.source().fingerprint().clone(),
            time_part: "11:00:00".to_owned(),
            content: content.to_owned(),
        };
        plan_document_patch(&document, &command)
            .test_ok("append")
            .result_bytes()
            .to_vec()
    }

    #[test]
    fn timestamps_inside_markdown_structure_are_not_memos() {
        let raw = "\u{feff}---\r\nexample: |\r\n  - 23:59:59 frontmatter\r\n---\r\n<!--\r\n- 23:59:58 user comment\r\n-->\r\n- 09:00:00\r\n正文，保留全角符号。\r\n```markdown\r\n- 23:59:57 code\r\n```\r\n- parent\r\n  - 23:59:56 nested list\r\n\r\n- 10:00:00\r\nsecond\r\n";
        let document = parse(raw);
        let times: Vec<_> = document
            .memos()
            .iter()
            .map(lomo_workspace::WorkspaceMemo::time_part)
            .collect();
        assert_eq!(times, ["09:00:00", "10:00:00"]);
        assert_eq!(document.serialize_unedited(), raw.as_bytes());
        assert!(
            document
                .memos()
                .first()
                .test_ok("memo")
                .content()
                .contains("- 23:59:57 code")
        );
    }

    #[test]
    fn replacing_inline_content_preserves_header_and_separators_exactly() {
        let raw = "\u{feff}- \u{200b}9:03 \t 旧正文\r\n\r\n- 10:00:00\r\nneighbor\r\n";
        let expected = "\u{feff}- \u{200b}9:03 \t 新正文\r\n\r\n- 10:00:00\r\nneighbor\r\n";
        assert_eq!(replace(raw, "新正文"), expected.as_bytes());
    }

    #[test]
    fn body_span_includes_inline_content() {
        let document = parse("- 09:00:00 inline body\ncontinuation\n");
        let memo = document.memos().first().test_ok("memo");
        assert_eq!(
            document.source().slice(memo.body_span()).test_ok("body"),
            "inline body\ncontinuation\n"
        );
        assert_eq!(
            document
                .source()
                .slice(memo.header_span())
                .test_ok("header"),
            "- 09:00:00 "
        );
    }

    #[test]
    fn plain_markdown_edit_retains_its_format_and_user_metadata() {
        let raw = "\u{feff}---\r\ntitle: 用户数据\r\n---\r\n<!-- keep my comment -->\r\n# old\r\n";
        let replacement = "---\ntitle: 用户数据\n---\n<!-- keep my comment -->\n# updated";
        let expected =
            "\u{feff}---\r\ntitle: 用户数据\r\n---\r\n<!-- keep my comment -->\r\n# updated\r\n";
        let bytes = replace(raw, replacement);
        assert_eq!(bytes, expected.as_bytes());
        let text = std::str::from_utf8(&bytes).test_ok("result UTF-8");
        assert_eq!(parse(text).format(), DocumentFormat::PlainMarkdown);
    }

    #[test]
    fn append_converts_inserted_line_endings_without_touching_the_prefix() {
        let raw = "- 09:00:00\r\noriginal\r\n";
        let expected = "- 09:00:00\r\noriginal\r\n\r\n- 11:00:00\r\nfirst\r\n  - second\r\n```\r\ncode\r\n```\r\n";
        assert_eq!(
            append(raw, "first\n  - second\n```\ncode\n```"),
            expected.as_bytes()
        );
    }

    #[test]
    fn carriage_return_only_content_keeps_its_line_boundaries() {
        let raw = "- 9:00\rfirst\r\r- 10:00\rneighbor\r";
        let expected = "- 9:00\rone\rtwo\r\r- 10:00\rneighbor\r";
        assert_eq!(replace(raw, "one\rtwo"), expected.as_bytes());
    }

    #[test]
    fn a_header_without_a_terminator_accepts_body_content() {
        assert_eq!(replace("- 9:00", "body"), b"- 9:00\nbody");
    }

    #[test]
    fn a_bom_only_document_is_empty_and_keeps_the_bom_on_append() {
        assert_eq!(parse("\u{feff}").format(), DocumentFormat::Empty);
        assert_eq!(
            append("\u{feff}", "body"),
            "\u{feff}- 11:00:00\nbody\n".as_bytes()
        );
    }

    #[test]
    fn normalized_text_is_never_a_valid_physical_write_baseline() {
        let raw = "\u{feff}- 09:00:00\r\nbody\r\n";
        let document = parse(raw);
        let command = DocumentPatchCommand::Remove {
            path: WorkspaceRelativePath::parse("2026_09_08.md").test_ok("path"),
            expected_fingerprint: SourceFingerprint::of_bytes(raw.replace("\r\n", "\n").as_bytes()),
            identity: document.memos().first().test_ok("memo").identity().clone(),
        };
        let error = plan_document_patch(&document, &command).test_err("reject normalized baseline");
        assert_eq!(error.code(), "stale_snapshot");
        assert_eq!(document.serialize_unedited(), raw.as_bytes());
    }
}
