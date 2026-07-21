//! Behavior Contract:
//! - Unit under test: `extract_memo_body_from_raw`
//! - Owning layer: lomo-workspace
//! - Priority tier: P1
//! - Capability: Project memo body from raw header+body bytes without Kotlin `String.lines()` authority.
//!
//! Scenarios:
//! - Given a Lomo header line with same-line body and continuation lines, when extracted, then body
//!   is the owner content projection.
//! - Given plain Markdown without a time header, when extracted, then the whole body is returned.
//! - Given empty raw, when extracted, then validation fails closed.
//!
//! Observable outcomes: exact body strings or validation errors.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_workspace::extract_memo_body_from_raw;

    #[test]
    fn extracts_body_from_lomo_header_block() {
        let raw = "- 09:30 hello\ncontinuation\n";
        let body = extract_memo_body_from_raw(raw).expect("body extract");
        assert_eq!(body, "hello\ncontinuation");
    }

    #[test]
    fn extracts_plain_markdown_body() {
        let raw = "no header just body\nline2\n";
        let body = extract_memo_body_from_raw(raw).expect("plain body");
        assert_eq!(body, "no header just body\nline2");
    }

    #[test]
    fn empty_raw_fails_closed() {
        let error = extract_memo_body_from_raw("   \n").expect_err("empty fails");
        assert!(
            error.to_string().contains("memo_body_extract")
                || error.to_string().contains("empty")
                || error.to_string().contains("validation"),
            "unexpected error: {error}"
        );
    }
}
