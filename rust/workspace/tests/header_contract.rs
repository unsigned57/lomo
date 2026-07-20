//! Behavior Contract
//!
//! Capability: recognize Lomo/Thino time headers and reject illegal filename stems at the document
//! boundary.
//!
//! Scenarios:
//! - Given supported `- H:mm` / `- HH:mm:ss` lines, when parsed, then time and same-line content parts
//!   are returned without normalization of the time token.
//! - Given malformed headers, when parsed, then no header is recognized.
//! - Given BOM / zero-width separators, when parsed, then the header still matches.
//! - Given empty / underscore / control stems, when validated, then validation fails closed.
//!
//! Observable outcomes: `ParsedMemoHeader` parts and structured validation errors.
//! Excludes: full document segmentation (covered by `document_model_contract`).

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
    use lomo_workspace::header::{parse_memo_header_line, validate_filename_stem};

    #[test]
    fn header_line_accepts_supported_forms() {
        let with_minutes = parse_memo_header_line("- 09:30 hello").test_ok("header");
        assert_eq!(with_minutes.time_part(), "09:30");
        assert_eq!(with_minutes.content_part(), "hello");

        let single = parse_memo_header_line("  - 9:30 hi").test_ok("header");
        assert_eq!(single.time_part(), "9:30");
        assert_eq!(single.content_part(), "hi");

        let with_seconds = parse_memo_header_line("- 9:30:05 details").test_ok("header");
        assert_eq!(with_seconds.time_part(), "9:30:05");
        assert_eq!(with_seconds.content_part(), "details");

        let empty_content = parse_memo_header_line("- 09:30").test_ok("header");
        assert_eq!(empty_content.time_part(), "09:30");
        assert_eq!(empty_content.content_part(), "");
    }

    #[test]
    fn header_line_rejects_malformed_values() {
        for invalid in [
            "",
            "   ",
            "09:30 content",
            "-",
            "-    ",
            "- content only",
            "- 09 content",
            "- 24:01 overflow hour",
            "- 09:60 overflow minute",
            "- 09:10:60 overflow second",
            "- 09:30content",
        ] {
            assert!(
                parse_memo_header_line(invalid).is_none(),
                "expected reject for {invalid:?}"
            );
        }
    }

    #[test]
    fn header_line_ignores_bom_and_zero_width_separators() {
        let bom = parse_memo_header_line("\u{FEFF}- 17:56:16  body").test_ok("bom");
        assert_eq!(bom.time_part(), "17:56:16");
        assert_eq!(bom.content_part(), "body");

        let zws = parse_memo_header_line("-\u{200B}17:56:16  body").test_ok("zws");
        assert_eq!(zws.time_part(), "17:56:16");
        assert_eq!(zws.content_part(), "body");
    }

    #[test]
    fn filename_stem_rejects_underscore_and_controls() {
        validate_filename_stem("2024-06-01").test_ok("valid");
        validate_filename_stem("plain-note").test_ok("valid");
        assert!(validate_filename_stem("").is_err());
        assert!(validate_filename_stem("a_b").is_err());
        assert!(validate_filename_stem("a\nb").is_err());
    }
}
