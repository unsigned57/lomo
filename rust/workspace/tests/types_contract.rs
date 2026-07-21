//! Behavior Contract
//!
//! Capability: reject invalid workspace paths, source bytes, spans, fingerprints, resource limits,
//! and memo identities before they can enter the stage-2 document model or patch planner.
//!
//! Scenarios:
//! - Given a workspace-relative path that is absolute, ambiguous, escaped, NUL-bearing, or
//!   oversized, when it is constructed, then validation fails without normalization.
//! - Given non-UTF-8 bytes, when `SourceBytes` is constructed, then decode fails closed with no
//!   replacement characters or empty-document fallback.
//! - Given valid UTF-8 with BOM / LF / CRLF / CR / trailing newlines, when `SourceBytes` is
//!   constructed, then fingerprint is SHA-256 of exact bytes and text-state captures BOM, dominant
//!   newline, and trailing facts.
//! - Given a byte span outside source bounds or inverted, when constructed, then validation fails.
//! - Given resource counts outside the stage-2 ceilings, when `ResourceBudget` checks run, then they
//!   fail with `resource_limit` rather than clamp.
//! - Given dateKey/timePart/ordinal parts, when `MemoIdentity` is built or parsed, then the wire form
//!   is exactly `${dateKey}_${timePart}_${ordinal}`.
//!
//! Observable outcomes: constrained values, stable fingerprints/identities, structured error
//! category/code.
//! TDD proof: architecture RED on 2026-07-18 proved the owner crate was absent; this contract is the
//! first behavior surface of `lomo-workspace`.
//! Excludes: Markdown parse events, `RenderDocumentV1` encoding, patch planning, FFI mapping, and
//! production dual-stack wiring.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        BomKind, ByteSpan, DominantNewline, MAX_EDITABLE_MEMO_UTF8_CHARS,
        MAX_INLINE_RENDER_UTF8_BYTES, MAX_IR_STRING_UTF8_BYTES, MAX_RENDER_DOCUMENT_NODES,
        MAX_SEMANTIC_NESTING_DEPTH, MAX_WORKSPACE_SCAN_PAGE_SIZE, MemoIdentity, NewlineKind,
        ResourceBudget, SourceBytes, SourceFingerprint, WorkspaceRelativePath,
    };

    #[test]
    fn workspace_paths_reject_ambiguous_or_escaped_values() {
        for invalid in [
            "",
            "/memo.md",
            "memo\\file.md",
            "memo//file.md",
            "memo/./file.md",
            "memo/../file.md",
            "../memo.md",
            "memo\0.md",
            "C:memo.md",
        ] {
            let error = WorkspaceRelativePath::parse(invalid)
                .test_err("ambiguous or escaped path must be rejected");
            assert_eq!(error.category(), ErrorCategory::Validation);
            assert_eq!(error.code(), "invalid_workspace_path");
        }

        let path = WorkspaceRelativePath::parse("memos/2026-07-18.md").test_ok("valid path");
        assert_eq!(path.as_str(), "memos/2026-07-18.md");
    }

    #[test]
    fn source_bytes_require_strict_utf8_and_preserve_fingerprint() {
        let invalid = SourceBytes::try_from_bytes(vec![0x80]).test_err("non-UTF-8 must fail");
        assert_eq!(invalid.category(), ErrorCategory::Corruption);
        assert_eq!(invalid.code(), "source_not_utf8");

        let mut with_bom = vec![0xEF, 0xBB, 0xBF];
        with_bom.extend_from_slice(b"hello\r\nworld\r\n");
        let source = SourceBytes::try_from_bytes(with_bom.clone()).test_ok("valid UTF-8");
        assert_eq!(source.as_bytes(), with_bom.as_slice());
        assert_eq!(
            source.fingerprint().as_str(),
            SourceFingerprint::of_bytes(&with_bom).as_str()
        );
        assert_eq!(source.text_state().bom(), BomKind::Utf8);
        assert_eq!(
            source.text_state().dominant_newline(),
            DominantNewline::Uniform(NewlineKind::Crlf)
        );
        assert!(source.text_state().trailing().ends_with_newline());

        let lf = SourceBytes::try_from_str("a\nb\n\n").test_ok("lf source");
        assert_eq!(
            lf.text_state().dominant_newline(),
            DominantNewline::Uniform(NewlineKind::Lf)
        );
        assert_eq!(lf.text_state().trailing().trailing_blank_lines(), 1);

        let mixed = SourceBytes::try_from_str("a\nb\r\n").test_ok("mixed");
        assert_eq!(
            mixed.text_state().dominant_newline(),
            DominantNewline::Mixed
        );
    }

    #[test]
    fn byte_spans_must_stay_inside_source_bounds() {
        let source = SourceBytes::try_from_str("abcdef").test_ok("source");
        ByteSpan::try_new(0, 6, source.len()).test_ok("full span");
        ByteSpan::try_new(2, 2, source.len()).test_ok("empty span");
        let inverted = ByteSpan::try_new(4, 3, source.len()).test_err("inverted");
        assert_eq!(inverted.code(), "invalid_byte_span");
        let overflow = ByteSpan::try_new(0, 7, source.len()).test_err("overflow");
        assert_eq!(overflow.code(), "invalid_byte_span");

        let span = ByteSpan::try_new(1, 4, source.len()).test_ok("mid span");
        assert_eq!(source.slice(span).test_ok("slice"), "bcd");
    }

    #[test]
    fn resource_budget_rejects_over_limit_counts() {
        ResourceBudget::check_inline_render_bytes(MAX_INLINE_RENDER_UTF8_BYTES)
            .test_ok("exact max");
        let too_large = ResourceBudget::check_inline_render_bytes(MAX_INLINE_RENDER_UTF8_BYTES + 1)
            .test_err("over max");
        assert_eq!(too_large.category(), ErrorCategory::ResourceLimit);
        assert_eq!(too_large.code(), "inline_render_too_large");

        ResourceBudget::check_editable_memo_chars(MAX_EDITABLE_MEMO_UTF8_CHARS)
            .test_ok("exact max");
        assert_eq!(
            ResourceBudget::check_editable_memo_chars(MAX_EDITABLE_MEMO_UTF8_CHARS + 1)
                .test_err("over max")
                .code(),
            "editable_memo_too_large"
        );

        ResourceBudget::check_render_document_nodes(MAX_RENDER_DOCUMENT_NODES).test_ok("exact max");
        assert_eq!(
            ResourceBudget::check_render_document_nodes(MAX_RENDER_DOCUMENT_NODES + 1)
                .test_err("over max")
                .code(),
            "render_document_too_large"
        );

        ResourceBudget::check_semantic_nesting_depth(MAX_SEMANTIC_NESTING_DEPTH)
            .test_ok("exact max");
        assert_eq!(
            ResourceBudget::check_semantic_nesting_depth(MAX_SEMANTIC_NESTING_DEPTH + 1)
                .test_err("over max")
                .code(),
            "semantic_nesting_too_deep"
        );

        ResourceBudget::check_ir_string_bytes(MAX_IR_STRING_UTF8_BYTES).test_ok("exact max");
        assert_eq!(
            ResourceBudget::check_ir_string_bytes(MAX_IR_STRING_UTF8_BYTES + 1)
                .test_err("over max")
                .code(),
            "ir_string_too_large"
        );

        ResourceBudget::check_workspace_scan_page_size(MAX_WORKSPACE_SCAN_PAGE_SIZE)
            .test_ok("exact max");
        assert_eq!(
            ResourceBudget::check_workspace_scan_page_size(0)
                .test_err("zero page")
                .code(),
            "invalid_workspace_scan_page_size"
        );
        assert_eq!(
            ResourceBudget::check_workspace_scan_page_size(MAX_WORKSPACE_SCAN_PAGE_SIZE + 1)
                .test_err("over max")
                .code(),
            "invalid_workspace_scan_page_size"
        );
    }

    #[test]
    fn path_limits_and_control_chars_are_table_driven() {
        // STAGE2-CONTRACT: relative path max 4096 UTF-8 bytes and per-segment max 255;
        // both constraints apply. NUL / other controls / escapes fail closed.

        let exact_segment = format!("memos/{}", "a".repeat(255));
        WorkspaceRelativePath::parse(&exact_segment).test_ok("255-byte segment allowed");
        let over_segment = format!("memos/{}", "a".repeat(256));
        assert_eq!(
            WorkspaceRelativePath::parse(&over_segment)
                .test_err("256-byte segment")
                .code(),
            "invalid_workspace_path"
        );

        // Max constructible under both caps: 16 segments of 255 + 15 separators = 4095 bytes.
        let segment = "b".repeat(255);
        let max_valid = std::iter::repeat_n(segment.as_str(), 16)
            .collect::<Vec<_>>()
            .join("/");
        assert_eq!(max_valid.len(), 4095);
        WorkspaceRelativePath::parse(&max_valid).test_ok("4095-byte path under both caps");

        // 4096 bytes cannot satisfy segment<=255 (max packing is 4095), so reject.
        let over_total = format!("{max_valid}c");
        assert_eq!(over_total.len(), 4096);
        assert_eq!(
            WorkspaceRelativePath::parse(&over_total)
                .test_err("4096-byte path")
                .code(),
            "invalid_workspace_path"
        );

        // Explicit oversized total with short segments.
        let mut long_parts = Vec::new();
        while long_parts.join("/").len() <= 4096 {
            long_parts.push("abcd".to_owned());
        }
        let long_path = long_parts.join("/");
        assert!(long_path.len() > 4096);
        assert_eq!(
            WorkspaceRelativePath::parse(&long_path)
                .test_err("over 4096 total")
                .code(),
            "invalid_workspace_path"
        );

        let control_cases: &[&str] = &[
            "memo\0.md",
            "memo\u{0001}.md",
            "memo\tfile.md",
            "memo\nfile.md",
            "memo\rfile.md",
            "memo\u{007f}.md",
            "\u{0008}memo.md",
            "notes/memo\0.md",
        ];
        for invalid in control_cases {
            let error = WorkspaceRelativePath::parse(invalid).test_err("control/NUL");
            assert_eq!(error.category(), ErrorCategory::Validation);
            assert_eq!(error.code(), "invalid_workspace_path", "input={invalid:?}");
        }

        let unicode = WorkspaceRelativePath::parse("笔记/2026-07-18.md").test_ok("unicode");
        assert_eq!(unicode.as_str(), "笔记/2026-07-18.md");
    }

    #[test]
    fn memo_identity_is_date_time_ordinal_wire_form() {
        let identity = MemoIdentity::try_new("2026-07-18", "09:41", 0).test_ok("identity");
        assert_eq!(identity.as_str(), "2026-07-18_09:41_0");
        assert_eq!(identity.date_key(), "2026-07-18");
        assert_eq!(identity.time_part(), "09:41");
        assert_eq!(identity.ordinal(), 0);

        let parsed = MemoIdentity::parse("2026-07-18_09:41_2").test_ok("parse");
        assert_eq!(parsed.as_str(), "2026-07-18_09:41_2");
        assert_eq!(parsed.ordinal(), 2);

        // Product default StorageFilenameFormats.DEFAULT_PATTERN = yyyy_MM_dd embeds '_'.
        let default_stem = MemoIdentity::try_new("2026_07_18", "09:41:00", 0).test_ok("yyyy_MM_dd");
        assert_eq!(default_stem.as_str(), "2026_07_18_09:41:00_0");
        let round_trip = MemoIdentity::parse(default_stem.as_str()).test_ok("parse yyyy_MM_dd");
        assert_eq!(round_trip.date_key(), "2026_07_18");
        assert_eq!(round_trip.time_part(), "09:41:00");
        assert_eq!(round_trip.ordinal(), 0);

        for invalid in ["", "only-date", "2026-07-18_09:41", "2026-07-18_09:41_x"] {
            let error = MemoIdentity::parse(invalid).test_err("invalid identity");
            assert_eq!(error.category(), ErrorCategory::Validation);
            assert_eq!(error.code(), "invalid_memo_identity");
        }

        // time_part must not contain '_' (would break right-to-left parse); date_key may.
        let bad_time = MemoIdentity::try_new("2026_07_18", "09_41", 0).test_err("time underscore");
        assert_eq!(bad_time.code(), "invalid_memo_identity_parts");
    }
}
