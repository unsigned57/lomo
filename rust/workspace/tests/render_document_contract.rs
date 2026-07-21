//! Behavior Contract
//!
//! Capability: project one `RenderDocumentV1` from the same constrained source bytes that produce
//! the workspace document model, so GFM blocks/inlines and Lomo extensions (tags, wiki, highlight,
//! task, raw HTML text, audio/image destinations) share one node-fact authority with storage
//! analysis — without a second body re-parse and without production dual-stack wiring.
//!
//! Scenarios:
//! - Given a UTF-8 Markdown source, when rendered, then the IR schema is `RenderDocumentV1`, nodes
//!   are deterministic, and plain-text projection is stable under double render.
//! - Given UI semantic-ui characterization fixtures, when rendered, then block kinds, counts,
//!   task/link/image tallies, and plain-text fingerprints match locked goldens.
//! - Given a workspace document parse, when storage memo tags/attachments and `RenderDocumentV1`
//!   are read, then both projections come from the document's owned node facts (no second
//!   `render_markdown` / body re-tokenize of the same source).
//! - Given adversarial dual-pass divergence fixtures (tags inside code spans, nested markup,
//!   wiki image vs markdown image, header-only noise), when projected from one parse, then storage
//!   analysis and Render IR agree (they must not silently accept two authorities).
//! - Given resource over-limits (inline size, node count, nesting, IR string), when render runs,
//!   then it fails closed with `resource_limit` (no truncated IR).
//! - Given unknown schema or platform-tainted fields, when IR is inspected, then public types stay
//!   UI-neutral (no Compose/Android/DB types).
//!
//! Observable outcomes: `RenderDocumentV1` blocks/inlines, plain-text fingerprint, structured
//! resource-limit errors, structural same-parse agreement with storage analysis.
//! TDD proof: RED when workspace render re-tokenizes the body or dual tag/attachment authorities
//! diverge on adversarial fixtures; GREEN after one parse owns both projections.
//! Excludes: document patch (P2-04), FFI/native (P2-06), Kotlin presentation (P2-07), production
//! dual-stack switch (P2-09).

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::ptr;

    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        MAX_INLINE_RENDER_UTF8_BYTES, MAX_RENDER_DOCUMENT_NODES, MAX_SEMANTIC_NESTING_DEPTH,
        RENDER_DOCUMENT_SCHEMA_V1, RenderBlock, RenderDocumentV1, RenderInline, ResourceBudget,
        SourceBytes, parse_workspace_document, render_markdown,
    };
    use serde::Deserialize;
    use sha2::{Digest, Sha256};

    #[derive(Debug, Deserialize)]
    struct UiSemanticGolden {
        fixture: String,
        block_count: usize,
        block_kinds: Vec<String>,
        heading_count: usize,
        list_count: usize,
        table_count: usize,
        code_block_count: usize,
        quote_count: usize,
        link_count: usize,
        image_count: usize,
        task_checked: usize,
        task_unchecked: usize,
        plain_text_fingerprint: String,
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

    fn fixtures_semantic_ui() -> PathBuf {
        repository_root().join("fixtures/characterization/semantic-ui")
    }

    fn read_ui_golden(name: &str) -> UiSemanticGolden {
        let path = fixtures_semantic_ui().join(name);
        let text = fs::read_to_string(&path).unwrap_or_else(|error| {
            panic!("failed to read ui golden {}: {error}", path.display());
        });
        serde_json::from_str(&text).unwrap_or_else(|error| {
            panic!("failed to decode ui golden {}: {error}", path.display());
        })
    }

    fn render_fixture(fixture_name: &str) -> RenderDocumentV1 {
        let bytes = fs::read(fixtures_markdown().join(fixture_name)).test_ok("fixture bytes");
        let source = SourceBytes::try_from_bytes(bytes).test_ok("utf-8 fixture");
        render_markdown(&source).unwrap_or_else(|error| panic!("render failed: {error:?}"))
    }

    fn kind_name(block: &RenderBlock) -> &'static str {
        match block {
            RenderBlock::Paragraph { .. } => "paragraph",
            RenderBlock::Heading { .. } => "heading",
            RenderBlock::BlockQuote { .. } => "quote",
            RenderBlock::List { .. } => "list",
            RenderBlock::CodeBlock { .. } => "code_block",
            RenderBlock::ThematicBreak { .. } => "thematic_break",
            RenderBlock::Table { .. } => "table",
            RenderBlock::HtmlBlock { .. } => "html",
        }
    }

    fn count_inlines(inlines: &[RenderInline], links: &mut usize, images: &mut usize) {
        for inline in inlines {
            match inline {
                RenderInline::Link { children, .. } => {
                    *links += 1;
                    count_inlines(children, links, images);
                }
                RenderInline::Image { .. } => *images += 1,
                RenderInline::Strong { children, .. }
                | RenderInline::Emphasis { children, .. }
                | RenderInline::Strikethrough { children, .. }
                | RenderInline::Highlight { children, .. }
                | RenderInline::WikiReference { children, .. } => {
                    count_inlines(children, links, images);
                }
                RenderInline::Tag { .. }
                | RenderInline::Reminder { .. }
                | RenderInline::Text { .. }
                | RenderInline::Code { .. }
                | RenderInline::SoftBreak { .. }
                | RenderInline::HardBreak { .. }
                | RenderInline::HtmlInline { .. } => {}
            }
        }
    }

    fn walk_blocks(
        blocks: &[RenderBlock],
        links: &mut usize,
        images: &mut usize,
        task_checked: &mut usize,
        task_unchecked: &mut usize,
    ) {
        for block in blocks {
            match block {
                RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
                    count_inlines(inlines, links, images);
                }
                RenderBlock::BlockQuote { blocks, .. } => {
                    walk_blocks(blocks, links, images, task_checked, task_unchecked);
                }
                RenderBlock::List { items, .. } => {
                    for item in items {
                        match item.checked {
                            Some(true) => *task_checked += 1,
                            Some(false) => *task_unchecked += 1,
                            None => {}
                        }
                        walk_blocks(&item.blocks, links, images, task_checked, task_unchecked);
                    }
                }
                RenderBlock::Table { header, rows, .. } => {
                    for cell in header.iter().chain(rows.iter().flatten()) {
                        count_inlines(&cell.inlines, links, images);
                    }
                }
                RenderBlock::CodeBlock { .. }
                | RenderBlock::ThematicBreak { .. }
                | RenderBlock::HtmlBlock { .. } => {}
            }
        }
    }

    fn sha256_hex(text: &str) -> String {
        let digest = Sha256::digest(text.as_bytes());
        format!("{digest:x}")
    }

    #[test]
    fn render_document_schema_and_double_render_are_deterministic() {
        let document = render_fixture("gfm-extensions.md");
        assert_eq!(document.schema_version(), RENDER_DOCUMENT_SCHEMA_V1);
        assert!(!document.blocks().is_empty());
        assert!(document.node_count() > 0);
        assert!(document.node_count() <= MAX_RENDER_DOCUMENT_NODES);

        let again = render_fixture("gfm-extensions.md");
        assert_eq!(document.blocks(), again.blocks());
        assert_eq!(document.plain_text(), again.plain_text());
        assert_eq!(document.node_count(), again.node_count());
        assert_eq!(
            sha256_hex(document.plain_text()),
            sha256_hex(again.plain_text())
        );
    }

    #[test]
    fn ui_semantic_goldens_match_render_document_projection() {
        let corpus = [
            "empty.json",
            "plain.json",
            "gfm-extensions.json",
            "lomo-basic.json",
            "thino-basic.json",
            "long-line.json",
            "cjk-emoji.json",
            "bom-newline.json",
            "duplicate-timestamps.json",
            "dst-edge.json",
        ];
        for golden_name in corpus {
            let golden = read_ui_golden(golden_name);
            let document = render_fixture(&golden.fixture);
            let kinds: Vec<_> = document
                .blocks()
                .iter()
                .map(kind_name)
                .map(str::to_owned)
                .collect();
            assert_eq!(kinds.len(), golden.block_count, "{golden_name} block_count");
            assert_eq!(kinds, golden.block_kinds, "{golden_name} block_kinds");
            assert_eq!(
                kinds.iter().filter(|k| *k == "heading").count(),
                golden.heading_count,
                "{golden_name} heading_count"
            );
            assert_eq!(
                kinds.iter().filter(|k| *k == "list").count(),
                golden.list_count,
                "{golden_name} list_count"
            );
            assert_eq!(
                kinds.iter().filter(|k| *k == "table").count(),
                golden.table_count,
                "{golden_name} table_count"
            );
            assert_eq!(
                kinds.iter().filter(|k| *k == "code_block").count(),
                golden.code_block_count,
                "{golden_name} code_block_count"
            );
            assert_eq!(
                kinds.iter().filter(|k| *k == "quote").count(),
                golden.quote_count,
                "{golden_name} quote_count"
            );

            let mut links = 0usize;
            let mut images = 0usize;
            let mut task_checked = 0usize;
            let mut task_unchecked = 0usize;
            walk_blocks(
                document.blocks(),
                &mut links,
                &mut images,
                &mut task_checked,
                &mut task_unchecked,
            );
            assert_eq!(links, golden.link_count, "{golden_name} link_count");
            assert_eq!(images, golden.image_count, "{golden_name} image_count");
            assert_eq!(
                task_checked, golden.task_checked,
                "{golden_name} task_checked"
            );
            assert_eq!(
                task_unchecked, golden.task_unchecked,
                "{golden_name} task_unchecked"
            );
            assert_eq!(
                sha256_hex(document.plain_text()),
                golden.plain_text_fingerprint,
                "{golden_name} plain_text_fingerprint; plain={:?}",
                document.plain_text()
            );
        }
    }

    #[test]
    fn storage_analysis_and_render_share_node_facts_for_tags_and_attachments() {
        let bytes = fs::read(fixtures_markdown().join("gfm-extensions.md")).test_ok("fixture");
        let source = SourceBytes::try_from_bytes(bytes).test_ok("utf-8");
        let workspace = parse_workspace_document(&source, "2024-06-08").test_ok("parse");
        // Structural same-parse: workspace owns the Render IR. A second body render of the same
        // source is not the authority for workspace projections.
        let render = workspace.render_document();

        let mut storage_tags = Vec::new();
        let mut storage_attachments = Vec::new();
        for memo in workspace.memos() {
            // Memo tags/attachments are parse projections, not a second analyzer authority.
            for tag in memo.tags() {
                if !storage_tags.contains(tag) {
                    storage_tags.push(tag.clone());
                }
            }
            for attachment in memo.attachments() {
                if !storage_attachments.contains(attachment) {
                    storage_attachments.push(attachment.clone());
                }
            }
        }

        assert_eq!(
            render.attachment_destinations(),
            storage_attachments.as_slice()
        );
        assert_eq!(render.tag_names(), storage_tags.as_slice());
        // Owned IR must be the same object projection, not a rebuilt peer.
        assert!(ptr::eq(render, workspace.render_document()));

        let mut task_checked = 0usize;
        let mut task_unchecked = 0usize;
        let mut links = 0usize;
        let mut images = 0usize;
        walk_blocks(
            render.blocks(),
            &mut links,
            &mut images,
            &mut task_checked,
            &mut task_unchecked,
        );
        assert_eq!(task_checked, 1);
        assert_eq!(task_unchecked, 1);
        assert_eq!(images, 1);
        assert!(
            render
                .blocks()
                .iter()
                .any(|block| matches!(block, RenderBlock::Heading { level: 2, .. })),
            "heading node fact must exist"
        );
    }

    #[test]
    fn adversarial_divergence_fixtures_agree_under_one_parse_authority() {
        // Dual-pass (string storage scan vs pulldown text-only tag classify) historically diverged
        // on tags inside code spans. One node-fact authority must keep both projections identical.
        let cases = [
            (
                "tags_in_code",
                "Visible #real_tag and hidden ` #not_a_tag ` plus\n```\n#code_only\n```\n",
                &["real_tag"][..],
                &[][..],
            ),
            (
                "nested_markup_tags",
                "See **bold #inner_tag** and *em #em_tag* then plain #outer",
                &["inner_tag", "em_tag", "outer"][..],
                &[][..],
            ),
            (
                "wiki_image_vs_markdown_image",
                "Shot ![cam](media/img/a.jpg) and wiki ![[media/img/b.png|alt]] plus [[Note Title]]",
                &[][..],
                &["media/img/a.jpg", "media/img/b.png"][..],
            ),
            (
                "header_only_noise",
                "- 10:00:00\n#only_body_tag and ![a](media/a.jpg)\n",
                &["only_body_tag"][..],
                &["media/a.jpg"][..],
            ),
        ];

        for (name, markdown, expected_tags, expected_attachments) in cases {
            let source = SourceBytes::try_from_str(markdown).test_ok("utf-8");
            let workspace = parse_workspace_document(&source, "2024-06-01").test_ok("parse");
            let render = workspace.render_document();

            let mut storage_tags = Vec::new();
            let mut storage_attachments = Vec::new();
            for memo in workspace.memos() {
                for tag in memo.tags() {
                    if !storage_tags.contains(tag) {
                        storage_tags.push(tag.clone());
                    }
                }
                for attachment in memo.attachments() {
                    if !storage_attachments.contains(attachment) {
                        storage_attachments.push(attachment.clone());
                    }
                }
            }

            assert_eq!(
                storage_tags.as_slice(),
                expected_tags,
                "{name}: storage tags"
            );
            assert_eq!(
                storage_attachments.as_slice(),
                expected_attachments,
                "{name}: storage attachments"
            );
            assert_eq!(
                render.tag_names(),
                storage_tags.as_slice(),
                "{name}: render tags must equal storage (one authority)"
            );
            assert_eq!(
                render.attachment_destinations(),
                storage_attachments.as_slice(),
                "{name}: render attachments must equal storage (one authority)"
            );

            // Inline non-workspace render uses the same pipeline authority (equal facts).
            let inline = render_markdown(&source).test_ok("inline render");
            assert_eq!(
                inline.tag_names(),
                render.tag_names(),
                "{name}: inline tags"
            );
            assert_eq!(
                inline.attachment_destinations(),
                render.attachment_destinations(),
                "{name}: inline attachments"
            );
        }
    }

    #[test]
    fn lomo_extensions_project_typed_inlines_not_raw_markup_only() {
        fn walk(
            inlines: &[RenderInline],
            saw_tag: &mut bool,
            saw_highlight: &mut bool,
            saw_wiki: &mut bool,
            saw_reminder: &mut bool,
            saw_html: &mut bool,
        ) {
            for inline in inlines {
                match inline {
                    RenderInline::Tag { name, .. } if name == "life/note" => *saw_tag = true,
                    RenderInline::Highlight { children, .. } => {
                        *saw_highlight = true;
                        walk(
                            children,
                            saw_tag,
                            saw_highlight,
                            saw_wiki,
                            saw_reminder,
                            saw_html,
                        );
                    }
                    RenderInline::WikiReference {
                        target, children, ..
                    } if target == "Note Title" => {
                        *saw_wiki = true;
                        walk(
                            children,
                            saw_tag,
                            saw_highlight,
                            saw_wiki,
                            saw_reminder,
                            saw_html,
                        );
                    }
                    RenderInline::Reminder { token, .. }
                        if token.starts_with("@2026-07-18-09:00") =>
                    {
                        *saw_reminder = true;
                    }
                    RenderInline::HtmlInline { text, .. } if text.contains("<b>") => {
                        *saw_html = true;
                    }
                    RenderInline::Strong { children, .. }
                    | RenderInline::Emphasis { children, .. }
                    | RenderInline::Strikethrough { children, .. }
                    | RenderInline::Link { children, .. }
                    | RenderInline::WikiReference { children, .. } => walk(
                        children,
                        saw_tag,
                        saw_highlight,
                        saw_wiki,
                        saw_reminder,
                        saw_html,
                    ),
                    RenderInline::Text { .. }
                    | RenderInline::Code { .. }
                    | RenderInline::Image { .. }
                    | RenderInline::Tag { .. }
                    | RenderInline::Reminder { .. }
                    | RenderInline::SoftBreak { .. }
                    | RenderInline::HardBreak { .. }
                    | RenderInline::HtmlInline { .. } => {}
                }
            }
        }

        let source = SourceBytes::try_from_str(
            "Hello #life/note and ==glow== and [[Note Title]] and @2026-07-18-09:00 raw <b>x</b>.",
        )
        .test_ok("source");
        let document = render_markdown(&source).test_ok("render");
        let mut saw_tag = false;
        let mut saw_highlight = false;
        let mut saw_wiki = false;
        let mut saw_reminder = false;
        let mut saw_html = false;

        for block in document.blocks() {
            if let RenderBlock::Paragraph { inlines, .. } = block {
                walk(
                    inlines,
                    &mut saw_tag,
                    &mut saw_highlight,
                    &mut saw_wiki,
                    &mut saw_reminder,
                    &mut saw_html,
                );
            }
        }

        assert!(saw_tag, "tag must be typed");
        assert!(saw_highlight, "highlight must be typed");
        assert!(saw_wiki, "wiki reference must be typed");
        assert!(saw_reminder, "reminder must be typed");
        assert!(saw_html, "raw HTML text must be typed");
        // Plain projection keeps typed tag/reminder/html source text (semantic-ui lock) while wiki
        // projects the target and highlight projects children only.
        assert_eq!(
            document.plain_text(),
            "Hello #life/note and glow and Note Title and @2026-07-18-09:00 raw <b>x</b>."
        );
    }

    #[test]
    fn resource_limits_fail_closed_without_truncated_ir() {
        ResourceBudget::check_inline_render_bytes(MAX_INLINE_RENDER_UTF8_BYTES + 1)
            .test_err("over-size inline");
        let huge = "a".repeat(MAX_INLINE_RENDER_UTF8_BYTES + 1);
        let source = SourceBytes::try_from_str(&huge).test_ok("utf-8");
        let error = render_markdown(&source).test_err("must reject oversize");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "inline_render_too_large");

        // Nesting depth is checked against semantic structure; build deep blockquotes.
        let mut nested = String::new();
        for _ in 0..=MAX_SEMANTIC_NESTING_DEPTH {
            nested.push('>');
            nested.push(' ');
        }
        nested.push_str("deep");
        let deep = SourceBytes::try_from_str(&nested).test_ok("utf-8");
        let deep_err = render_markdown(&deep).test_err("must reject deep nesting");
        assert_eq!(deep_err.category(), ErrorCategory::ResourceLimit);
        assert_eq!(deep_err.code(), "semantic_nesting_too_deep");
    }

    #[test]
    fn unknown_schema_is_rejected_at_boundary() {
        let error = RenderDocumentV1::reject_unknown_schema(99).test_err("unknown schema");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "unknown_render_schema");
        RenderDocumentV1::reject_unknown_schema(RENDER_DOCUMENT_SCHEMA_V1).test_ok("v1 ok");
    }
}
