// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
//! Behavior Contract:
//! Capability: recognize bare web/location links in the workspace render IR; owner lomo-workspace; P1.
//! Scenarios:
//! - Given bare URLs with Unicode and trailing punctuation, when rendered, then links preserve destinations and source bytes.
//! - Given geographic URIs, when rendered, then valid coordinates become links and invalid coordinates remain text.
//! - Given code or explicit links, when rendered, then no nested or accidental links are introduced.
//!
//! Observable outcomes: typed link destinations, source slices and unchanged visible text.
//!
//! TDD proof: RED/GREEN logs in reports/manual/interaction-fixes/bare-links-{red,green}.log.
//!
//! Excludes: Android URI handlers, networking and map application availability.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_workspace::{RenderBlock, RenderInline, SourceBytes, render_markdown};

    fn links(markdown: &str) -> Vec<String> {
        let source = SourceBytes::try_from_str(markdown).test_ok("source");
        let document = render_markdown(&source).test_ok("render");
        assert_eq!(document.plain_text(), markdown);
        let mut links = Vec::new();
        for block in document.blocks() {
            if let RenderBlock::Paragraph { inlines, .. } = block {
                for inline in inlines {
                    if let RenderInline::Link {
                        destination,
                        source_span,
                        ..
                    } = inline
                    {
                        assert_eq!(
                            markdown
                                .get(source_span.start()..source_span.end())
                                .unwrap_or_else(|| panic!("source slice")),
                            destination
                        );
                        links.push(destination.clone());
                    }
                }
            }
        }
        links
    }

    #[test]
    fn bare_web_urls_keep_balanced_parentheses_and_exclude_sentence_punctuation() {
        assert_eq!(
            links("See https://example.com/a_(b), https://example.org/path#section."),
            [
                "https://example.com/a_(b)",
                "https://example.org/path#section"
            ]
        );
    }

    #[test]
    fn bare_geo_uris_preserve_coordinates_and_queries_but_reject_invalid_ranges() {
        assert_eq!(
            links("位置 geo:31.2304,121.4737 geo:0,0?q=31.23,121.47 geo:91,0 geo:0,181"),
            ["geo:31.2304,121.4737", "geo:0,0?q=31.23,121.47"]
        );
    }

    #[test]
    fn uri_schemes_are_case_insensitive_and_invalid_locations_remain_text() {
        assert_eq!(
            links("HTTPS://example.com GEO:-90,180;u=5 geo:NaN,0 geo:1,2,inf geo:1,2,3,4"),
            ["HTTPS://example.com", "GEO:-90,180;u=5"]
        );
    }

    #[test]
    fn location_labels_keep_balanced_parentheses_and_malformed_tokens_remain_bounded_text() {
        assert_eq!(
            links("(geo:0,0?q=31,121(Shanghai))"),
            ["geo:0,0?q=31,121(Shanghai)"]
        );
        let malformed = "geo:".repeat(20_000);
        assert_eq!(links(&malformed), Vec::<String>::new());
    }

    #[test]
    fn decoded_entities_keep_raw_link_source_spans_and_decoded_destinations() {
        let markdown = "&amp; https://example.com?a=1&amp;b=2";
        let source = SourceBytes::try_from_str(markdown).test_ok("source");
        let document = render_markdown(&source).test_ok("render");
        let RenderBlock::Paragraph { inlines, .. } = document
            .blocks()
            .first()
            .unwrap_or_else(|| panic!("first block"))
        else {
            panic!("paragraph")
        };
        let link = inlines
            .iter()
            .find_map(|inline| {
                if let RenderInline::Link {
                    destination,
                    source_span,
                    ..
                } = inline
                {
                    Some((destination, source_span))
                } else {
                    None
                }
            })
            .unwrap_or_else(|| panic!("bare link"));
        assert_eq!(link.0, "https://example.com?a=1&b=2");
        assert_eq!(
            markdown
                .get(link.1.start()..link.1.end())
                .unwrap_or_else(|| panic!("encoded source slice")),
            "https://example.com?a=1&amp;b=2"
        );
        assert_eq!(document.plain_text(), "& https://example.com?a=1&b=2");
    }

    #[test]
    fn repeated_entities_and_links_keep_every_source_slice() {
        let markdown = "&amp; https://example.com ".repeat(1_000);
        let source = SourceBytes::try_from_str(&markdown).test_ok("source");
        let document = render_markdown(&source).test_ok("render");
        let RenderBlock::Paragraph { inlines, .. } = document
            .blocks()
            .first()
            .unwrap_or_else(|| panic!("paragraph"))
        else {
            panic!("paragraph")
        };
        let mut count = 0;
        for inline in inlines {
            if let RenderInline::Link {
                source_span,
                destination,
                ..
            } = inline
            {
                assert_eq!(
                    markdown.get(source_span.start()..source_span.end()),
                    Some(destination.as_str())
                );
                count += 1;
            }
        }
        assert_eq!(count, 1_000);
    }

    #[test]
    fn explicit_links_and_code_are_not_recursively_linkified() {
        let source =
            SourceBytes::try_from_str("`https://example.com` [https://example.org](geo:31,121)")
                .test_ok("source");
        let document = render_markdown(&source).test_ok("render");
        let RenderBlock::Paragraph { inlines, .. } = document
            .blocks()
            .first()
            .unwrap_or_else(|| panic!("first block"))
        else {
            panic!("paragraph")
        };
        let links: Vec<_> = inlines
            .iter()
            .filter_map(|inline| {
                if let RenderInline::Link {
                    destination,
                    children,
                    ..
                } = inline
                {
                    assert!(
                        children
                            .iter()
                            .all(|child| !matches!(child, RenderInline::Link { .. }))
                    );
                    Some(destination.as_str())
                } else {
                    None
                }
            })
            .collect();
        assert_eq!(links, ["geo:31,121"]);
    }
}
