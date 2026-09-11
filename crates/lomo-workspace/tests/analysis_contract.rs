//! Behavior Contract
//!
//! Capability: project storage-visible tags and attachment targets from the source-addressed
//! document fact graph without a second memo-body parser.
//!
//! Scenarios:
//! - Given nested tags and markdown images, when analyzed, then unique tags/attachments are returned
//!   in source order.
//! - Given CJK tags, when analyzed, then the full path is preserved.
//! - Given markdown audio image targets, when analyzed, then they appear as attachments.
//!
//! Observable outcomes: memo tag/attachment vectors from one workspace parse.
//! TDD proof: RED when the old public analyzer was removed before tests moved to the document fact
//! graph; GREEN when memo projections come only from the owned render facts.
//! Excludes: Kotlin presentation and platform I/O.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_workspace::{SourceBytes, parse_workspace_document};

    fn analyze(content: &str) -> lomo_workspace::WorkspaceDocument {
        let source = SourceBytes::try_from_str(content).test_ok("source");
        parse_workspace_document(&source, "plain").test_ok("parse")
    }

    #[test]
    fn extracts_nested_tags_and_images() {
        let document = analyze("Morning #life/note and ![shot](media/img/a.jpg) #life/note again");
        let memo = document.memos().first().expect("memo");
        assert_eq!(memo.tags(), &["life/note".to_owned()]);
        assert_eq!(memo.attachments(), &["media/img/a.jpg".to_owned()]);
    }

    #[test]
    fn extracts_cjk_tags() {
        let document = analyze("Thino-style diary entry #日记");
        assert_eq!(
            document.memos().first().expect("memo").tags(),
            &["日记".to_owned()]
        );
        let nested = analyze("Evening note with #标签/层级");
        assert_eq!(
            nested.memos().first().expect("memo").tags(),
            &["标签/层级".to_owned()]
        );
    }

    #[test]
    fn image_audio_markdown_counts_as_attachment() {
        let document = analyze("Audio: ![voice](media/voice/a.m4a)");
        assert_eq!(
            document.memos().first().expect("memo").attachments(),
            &["media/voice/a.m4a".to_owned()]
        );
    }
}
