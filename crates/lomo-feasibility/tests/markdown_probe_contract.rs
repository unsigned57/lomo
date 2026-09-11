//! Behavior Contract
//!
//! Capability: prove `pulldown-cmark` can stream-parse Lomo fixtures with offsets without
//! mutating source bytes.
//!
//! Scenarios:
//! - Given UTF-8 fixtures under `fixtures/markdown`, when probed, then parse succeeds with
//!   non-zero events and stable content digest.
//! - Given invalid UTF-8, when probed, then `InvalidUtf8` is returned.
//! - Given a GFM fixture with a heading and image, when probed, then counts are observable.
//!
//! Observable outcomes: `MarkdownProbeReport` and explicit UTF-8 failure.
//! Excludes: Lomo time-header product parse, formal `RenderDocument` wire schema, Compose.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use std::path::PathBuf;

    use lomo_feasibility::{MarkdownProbeError, bytes_stable_after_parse, probe_markdown_file};

    fn fixtures_markdown() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/markdown")
            .canonicalize()
            .expect("fixtures/markdown")
    }

    #[test]
    fn utf8_fixtures_parse_with_offsets_and_stable_bytes() {
        let root = fixtures_markdown();
        for name in [
            "lomo-basic.md",
            "plain.md",
            "gfm-extensions.md",
            "cjk-emoji.md",
        ] {
            let path = root.join(name);
            let bytes = std::fs::read(&path).expect("read fixture");
            assert!(
                bytes_stable_after_parse(&bytes),
                "{name} must remain byte-stable under parse"
            );
            let report = probe_markdown_file(&path).expect("probe");
            assert!(report.event_count > 0, "{name} should emit events");
            assert!(report.first_event_offset.is_some());
        }
    }

    #[test]
    fn gfm_fixture_exposes_heading_and_image_counts() {
        let path = fixtures_markdown().join("gfm-extensions.md");
        let report = probe_markdown_file(&path).expect("probe");
        assert!(report.heading_count >= 1);
        assert!(report.image_count >= 1);
        assert!(report.link_count >= 1 || report.image_count >= 1);
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let path = fixtures_markdown().join("invalid-utf8.bin");
        let error = probe_markdown_file(&path).expect_err("must reject");
        assert_eq!(error, MarkdownProbeError::InvalidUtf8);
    }
}
