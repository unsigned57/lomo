//! Behavior Contract
//! Capability: shared workspace Markdown retains its readable structure in the terminal.
//! Scenarios: paragraphs and explicit line breaks; lists, tables, quotes, code and attachments.
//! Observable outcomes: distinct display lines and text hierarchy with terminal-default body color.
//! TDD proof: `markdown_view_contract` fails when hard breaks collapse and table rows merge.
//! Excludes: parsing rules owned by lomo-workspace and terminal image bytes.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use lomo_tui::content::MemoBody;

    fn lines(body: &str) -> Result<Vec<String>, Box<dyn std::error::Error>> {
        Ok(MemoBody::parse(body.to_owned())?
            .lines()
            .iter()
            .map(ToString::to_string)
            .collect())
    }

    #[test]
    fn preserves_paragraphs_and_explicit_line_breaks() {
        let rendered = lines("first line  \nsecond line\n\nnew paragraph")
            .expect("fixture and operation must succeed");
        assert_eq!(rendered, ["first line", "second line", "", "new paragraph"]);
    }

    #[test]
    fn table_rows_and_quote_hierarchy_remain_readable() {
        let rendered = lines(
            "| name | value |\n| --- | --- |\n| alpha | one |\n| beta | two |\n\n> quoted thought",
        )
        .expect("fixture and operation must succeed");
        assert!(
            rendered.iter().any(|line| line.contains("alpha")
                && line.contains("one")
                && !line.contains("beta"))
        );
        assert!(
            rendered
                .iter()
                .any(|line| line.starts_with("│ ") && line.contains("quoted thought"))
        );
    }

    #[test]
    fn tasks_code_tags_and_attachments_keep_their_content() {
        let rendered = lines(
            "# Title\n\n- [x] completed\n- [ ] unfinished\n\n```rust\nlet x = 1;\n```\n\n#reading\n\n![photo](media/photo.png)",
        ).expect("fixture and operation must succeed");
        let text = rendered.join("\n");
        for expected in [
            "Title",
            "✓ completed",
            "□ unfinished",
            "let x = 1;",
            "#reading",
            "media/photo.png",
        ] {
            assert!(text.contains(expected), "missing {expected}: {text}");
        }
    }

    #[test]
    fn heading_levels_remain_distinguishable_in_terminal_text() {
        let rendered = lines("# Title\n\n## Section").expect("headings");
        assert_eq!(rendered, ["# Title", "", "## Section"]);
    }
}
