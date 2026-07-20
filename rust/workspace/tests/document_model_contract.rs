//! Behavior Contract
//!
//! Capability: parse one UTF-8 source once into a workspace document that owns Lomo/Thino time-header
//! segmentation, plain Markdown fallback, stable `${dateKey}_${timePart}_${ordinal}` identity, memo
//! header/body byte spans, and storage-visible tags/attachments — without using `String.lines()` as
//! the position authority and without dual production parsers.
//!
//! Scenarios:
//! - Given each storage characterization fixture under `fixtures/markdown`, when parsed with the
//!   locked filename stem, then memo id/content/tags/attachments and inclusive line spans match
//!   `fixtures/characterization/markdown/*.json`.
//! - Given any unedited UTF-8 fixture, when parse then serialize, then output bytes equal the source.
//! - Given the same UTF-8 fixture twice, when parsed, then identities, spans, content, tags, and
//!   attachments are identical (double-parse stability).
//! - Given invalid UTF-8 bytes, when source construction/parse is attempted, then decode fails closed
//!   (`source_not_utf8`) with no empty-document success.
//! - Given empty Markdown, when parsed, then outcome is ok with zero memos.
//! - Given duplicate timestamps, when identities are assigned, then ordinals are zero-based in file
//!   order (`…_0`, `…_1`).
//! - Given GFM fixtures, when parsed, then pulldown-cmark offset events are observed on the same
//!   source (CommonMark/GFM base is not a second authority).
//!
//! Observable outcomes: `WorkspaceDocument` memos, byte spans, identities, tags, attachments,
//! unedited serialize bytes, structured UTF-8 failure, non-zero offset event count on GFM fixtures.
//! TDD proof: RED observed before document parse surface existed (compile/link failure on missing
//! `parse_workspace_document` / golden mismatches); GREEN after one-parse document model lands.
//! Excludes: `RenderDocumentV1` encoding (P2-03), document patch (P2-04), FFI/native wiring (P2-06),
//! production dual-stack switch (P2-09).

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
    use std::fs;
    use std::path::{Path, PathBuf};

    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        BomKind, DominantNewline, NewlineKind, SourceBytes, WorkspaceDocument,
        parse_workspace_document,
    };
    use serde::Deserialize;

    #[derive(Debug, Deserialize)]
    struct GoldenDocument {
        fixture: String,
        filename_stem: String,
        byte_length: u64,
        outcome: String,
        error_class: Option<String>,
        memos: Vec<GoldenMemo>,
    }

    #[derive(Debug, Deserialize)]
    struct GoldenMemo {
        id: String,
        content: String,
        tags: Vec<String>,
        attachments: Vec<String>,
        start_line: u32,
        end_line: u32,
    }

    fn repository_root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .test_ok("repository root")
    }

    fn fixtures_markdown() -> PathBuf {
        repository_root().join("fixtures/markdown")
    }

    fn fixtures_characterization_markdown() -> PathBuf {
        repository_root().join("fixtures/characterization/markdown")
    }

    fn read_golden(name: &str) -> GoldenDocument {
        let path = fixtures_characterization_markdown().join(name);
        let text = fs::read_to_string(&path).unwrap_or_else(|error| {
            panic!("failed to read golden {}: {error}", path.display());
        });
        serde_json::from_str(&text).unwrap_or_else(|error| {
            panic!("failed to decode golden {}: {error}", path.display());
        })
    }

    fn parse_fixture(
        fixture_name: &str,
        filename_stem: &str,
    ) -> Result<WorkspaceDocument, lomo_core::LomoError> {
        let bytes = fs::read(fixtures_markdown().join(fixture_name)).test_ok("fixture bytes");
        let source = SourceBytes::try_from_bytes(bytes)?;
        parse_workspace_document(&source, filename_stem)
    }

    fn assert_matches_golden(document: &WorkspaceDocument, golden: &GoldenDocument) {
        assert_eq!(document.source().len() as u64, golden.byte_length);
        assert_eq!(document.memos().len(), golden.memos.len());
        for (index, (actual, expected)) in
            document.memos().iter().zip(golden.memos.iter()).enumerate()
        {
            assert_eq!(actual.identity().as_str(), expected.id, "memo[{index}] id");
            assert_eq!(actual.content(), expected.content, "memo[{index}] content");
            assert_eq!(
                actual.tags(),
                expected.tags.as_slice(),
                "memo[{index}] tags"
            );
            assert_eq!(
                actual.attachments(),
                expected.attachments.as_slice(),
                "memo[{index}] attachments"
            );
            assert_eq!(
                actual.start_line(),
                expected.start_line,
                "memo[{index}] start_line"
            );
            assert_eq!(
                actual.end_line(),
                expected.end_line,
                "memo[{index}] end_line"
            );
            assert!(
                actual.memo_span().end() >= actual.memo_span().start(),
                "memo[{index}] memo_span ordered"
            );
            assert!(
                actual.header_span().end() >= actual.header_span().start(),
                "memo[{index}] header_span ordered"
            );
            assert!(
                actual.body_span().end() >= actual.body_span().start(),
                "memo[{index}] body_span ordered"
            );
            assert!(
                actual.memo_span().end() <= document.source().len(),
                "memo[{index}] memo_span inside source"
            );
        }
    }

    #[test]
    fn storage_characterization_goldens_match_document_model() {
        let corpus = [
            "lomo-basic.json",
            "thino-basic.json",
            "plain.json",
            "empty.json",
            "bom-newline.json",
            "long-line.json",
            "duplicate-timestamps.json",
            "dst-edge.json",
            "cjk-emoji.json",
            "gfm-extensions.json",
        ];
        for golden_name in corpus {
            let golden = read_golden(golden_name);
            assert_eq!(golden.outcome, "ok", "{golden_name} must be ok outcome");
            let document = parse_fixture(&golden.fixture, &golden.filename_stem)
                .unwrap_or_else(|error| panic!("{golden_name} parse failed: {error:?}"));
            assert_matches_golden(&document, &golden);
            assert_eq!(
                document.serialize_unedited(),
                document.source().as_bytes(),
                "{golden_name} unedited serialize must be original bytes"
            );
        }
    }

    #[test]
    fn invalid_utf8_fails_closed_without_empty_document() {
        let golden = read_golden("invalid-utf8.json");
        assert_eq!(golden.outcome, "error");
        assert_eq!(golden.error_class.as_deref(), Some("utf8_decode"));
        let bytes = fs::read(fixtures_markdown().join(&golden.fixture)).test_ok("invalid fixture");
        assert_eq!(bytes.len() as u64, golden.byte_length);
        let error = SourceBytes::try_from_bytes(bytes).test_err("non-UTF-8 must fail");
        assert_eq!(error.category(), ErrorCategory::Corruption);
        assert_eq!(error.code(), "source_not_utf8");
    }

    #[test]
    fn double_parse_is_stable_for_identities_spans_and_analysis() {
        let golden = read_golden("duplicate-timestamps.json");
        let first = parse_fixture(&golden.fixture, &golden.filename_stem).test_ok("first parse");
        let second = parse_fixture(&golden.fixture, &golden.filename_stem).test_ok("second parse");
        assert_eq!(first.memos().len(), second.memos().len());
        for (left, right) in first.memos().iter().zip(second.memos().iter()) {
            assert_eq!(left.identity().as_str(), right.identity().as_str());
            assert_eq!(left.content(), right.content());
            assert_eq!(left.tags(), right.tags());
            assert_eq!(left.attachments(), right.attachments());
            assert_eq!(left.memo_span(), right.memo_span());
            assert_eq!(left.header_span(), right.header_span());
            assert_eq!(left.body_span(), right.body_span());
            assert_eq!(left.start_line(), right.start_line());
            assert_eq!(left.end_line(), right.end_line());
            assert_eq!(left.time_part(), right.time_part());
        }
        assert_eq!(first.serialize_unedited(), second.serialize_unedited());
        assert_eq!(first.offset_event_count(), second.offset_event_count());
    }

    #[test]
    fn duplicate_timestamps_use_zero_based_file_order_ordinals() {
        let document = parse_fixture("duplicate-timestamps.md", "2024-06-06").test_ok("parse");
        let ids: Vec<_> = document
            .memos()
            .iter()
            .map(|memo| memo.identity().as_str().to_owned())
            .collect();
        assert_eq!(
            ids,
            vec![
                "2024-06-06_12:00:00_0".to_owned(),
                "2024-06-06_12:00:00_1".to_owned(),
            ]
        );
        assert_eq!(document.memos()[0].identity().ordinal(), 0);
        assert_eq!(document.memos()[1].identity().ordinal(), 1);
        assert_eq!(document.memos()[0].time_part(), "12:00:00");
        assert_eq!(document.memos()[1].time_part(), "12:00:00");
    }

    #[test]
    fn plain_markdown_fallback_uses_midnight_identity_and_whole_file_span() {
        let document = parse_fixture("plain.md", "plain-note").test_ok("parse");
        assert_eq!(document.memos().len(), 1);
        let memo = &document.memos()[0];
        assert_eq!(memo.identity().as_str(), "plain-note_00:00:00_0");
        assert_eq!(memo.time_part(), "00:00:00");
        assert_eq!(memo.start_line(), 0);
        assert_eq!(memo.end_line(), 10);
        assert!(memo.content().contains("# Plain Markdown fallback"));
        assert_eq!(memo.tags(), &["plain".to_owned()]);
        assert!(matches!(
            document.format(),
            lomo_workspace::DocumentFormat::PlainMarkdown
        ));
    }

    #[test]
    fn empty_document_has_zero_memos_and_stable_bytes() {
        let document = parse_fixture("empty.md", "2024-06-03").test_ok("parse");
        assert!(document.memos().is_empty());
        assert_eq!(document.serialize_unedited(), b"");
        assert!(matches!(
            document.format(),
            lomo_workspace::DocumentFormat::Empty
        ));
    }

    #[test]
    fn bom_and_crlf_are_preserved_on_unedited_serialize() {
        let bytes = fs::read(fixtures_markdown().join("bom-newline.md")).test_ok("bytes");
        let source = SourceBytes::try_from_bytes(bytes.clone()).test_ok("utf-8");
        assert_eq!(source.text_state().bom(), BomKind::Utf8);
        assert_eq!(
            source.text_state().dominant_newline(),
            DominantNewline::Uniform(NewlineKind::Crlf)
        );
        let document = parse_workspace_document(&source, "2024-06-04").test_ok("parse");
        assert_eq!(document.serialize_unedited(), bytes.as_slice());
        assert_eq!(document.memos().len(), 2);
        assert_eq!(
            document.memos()[0].identity().as_str(),
            "2024-06-04_10:00:00_0"
        );
        assert_eq!(
            document.memos()[1].identity().as_str(),
            "2024-06-04_10:00:01_0"
        );
    }

    #[test]
    fn gfm_fixture_runs_pulldown_offset_event_stream_on_same_source() {
        let document = parse_fixture("gfm-extensions.md", "2024-06-08").test_ok("parse");
        assert!(
            document.offset_event_count() > 0,
            "pulldown-cmark offset stream must produce events"
        );
        assert!(
            document.heading_event_count() >= 1,
            "GFM heading must be visible from the same parse"
        );
        assert!(
            document.image_event_count() >= 1,
            "GFM image must be visible from the same parse"
        );
        assert_eq!(document.memos().len(), 1);
        assert_eq!(
            document.memos()[0].attachments(),
            &["media/voice/a.m4a".to_owned()]
        );
        // Unedited path must not rewrite source through AST pretty-print.
        assert_eq!(document.serialize_unedited(), document.source().as_bytes());
    }

    #[test]
    fn lomo_memo_byte_spans_cover_header_and_body_without_line_string_authority() {
        let document = parse_fixture("lomo-basic.md", "2024-06-01").test_ok("parse");
        assert_eq!(document.memos().len(), 2);
        let first = &document.memos()[0];
        let header = document
            .source()
            .slice(first.header_span())
            .test_ok("header slice");
        assert!(
            header.contains("09:00:00"),
            "header span must cover the time header bytes: {header:?}"
        );
        let body = document
            .source()
            .slice(first.body_span())
            .test_ok("body slice");
        assert!(
            body.contains("Morning #life/note"),
            "body span must cover memo body bytes: {body:?}"
        );
        let memo_bytes = document
            .source()
            .slice(first.memo_span())
            .test_ok("memo slice");
        assert!(memo_bytes.contains("09:00:00"));
        assert!(memo_bytes.contains("First Lomo memo of the day."));
        assert_eq!(first.tags(), &["life/note".to_owned()]);
        assert_eq!(
            document.memos()[1].attachments(),
            &["media/img/a.jpg".to_owned()]
        );
    }

    #[test]
    fn body_edit_does_not_change_identity_parts_from_header_and_ordinal() {
        // Characterization lock: identity is dateKey + timePart + ordinal, never content-derived.
        let document = parse_fixture("lomo-basic.md", "2024-06-01").test_ok("parse");
        let first = &document.memos()[0];
        assert_eq!(first.identity().date_key(), "2024-06-01");
        assert_eq!(first.identity().time_part(), "09:00:00");
        assert_eq!(first.identity().ordinal(), 0);
        assert_eq!(first.time_part(), first.identity().time_part());
    }
}
