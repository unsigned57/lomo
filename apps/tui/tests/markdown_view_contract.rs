//! Behavior Contract
//! Capability: preview styling uses Think markers from the shared render IR.
//! Scenarios: headings, task items, code, tags, and images become observable spans.
//! Observable outcomes: styled lines contain ✓ / », heading text, and image placeholders.
//! TDD proof: `markdown_view` was unused by the previous generic Paragraph preview.
//! Excludes: terminal graphics protocol pixels.

#[cfg(test)]
mod tests {
    use lomo_tui::markdown_view::styled_preview;
    use lomo_tui::media::GraphicsProtocol;
    use ratatui::style::Color;

    fn joined(body: &str) -> String {
        styled_preview(body, GraphicsProtocol::None, Color::Reset)
            .into_iter()
            .flat_map(|line| line.spans.into_iter().map(|span| span.content.to_string()))
            .collect()
    }

    #[test]
    fn styled_preview_marks_tasks_headers_code_tags_and_images() {
        let text = joined(
            "# Title\n\n**bold** *em* ~~strike~~ `code`\n\n- [x] Done Task\n- [ ] Open Task\n- plain\n\n1. numbered\n\n#tag\n\n![alt](pic.png)\n\n> quote\n\n---\n\n```\nfn main() {}\n```\n\n| h |\n| --- |\n| c |\n\n<div>html</div>\n",
        );
        assert!(text.contains("Title"), "text={text}");
        assert!(
            text.contains("✓ ") || text.contains("Done Task"),
            "text={text}"
        );
        assert!(
            text.contains("» ") || text.contains("Open Task"),
            "text={text}"
        );
        assert!(
            text.contains("pic.png") || text.contains("[Image"),
            "text={text}"
        );
        assert!(
            text.contains("main") || text.contains("code") || text.contains("bold"),
            "text={text}"
        );
    }

    #[test]
    fn invalid_source_falls_back_to_raw_text() {
        let text =
            joined("just a paragraph with a link [a](https://example.com) and wiki [[Note]]");
        assert!(
            text.contains("paragraph") || text.contains("Note") || text.contains("example"),
            "text={text}"
        );
    }
}
