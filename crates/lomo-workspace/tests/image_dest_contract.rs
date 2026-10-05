//! Contract: Markdown image destinations classify into a typed boundary at the workspace
//! fact layer (I7/D-01/D-02).
//!
//! - Unit under test: `lomo_workspace::ImageDest`, `RenderInline::Image.destination`,
//!   `RenderDocumentV1::attachment_destinations`.
//! - Capability: `!(dest)` yields `Local(canonical workspace path)` | `External(raw URL-ish)` |
//!   `Malformed(raw)` at parse time — equivalent spellings collapse to one local key, external
//!   and malformed destinations can never become workspace IO paths, and the authored token
//!   stays available for span rewrites.
//! - Given equivalent local spellings (`./`, `//`, `\\`, `x/./`), when a document renders, then
//!   they classify to the same `Local` path and dedup to one fact.
//! - Given scheme/protocol-relative/anchor destinations, when a document renders, then they are
//!   `External` and produce no local key.
//! - Given root escapes or empty destinations, when a document renders, then they are
//!   `Malformed` and produce no local key.
//! - Given a typed destination list, when serialized, then the wire shape is the authored
//!   token (schema-v1 compatible) and deserializes to the same classification.
//!
//! Observable outcomes: typed `attachment_destinations`, typed inline `destination`,
//! `projected()` string agreement with the pre-existing canonical authority.
//! TDD proof: RED before the enum existed (string destinations allowed `../` escapes to
//! propagate); GREEN with classification at the fact boundary.
//! Excludes: TUI placeholder wording, store projection shape (covered by its own contracts).

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use lomo_core::RelativeWorkspacePath;
    use lomo_workspace::{ImageDest, RenderInline, SourceBytes, render_markdown};

    fn render(source: &str) -> lomo_workspace::RenderDocumentV1 {
        render_markdown(&SourceBytes::try_from_str(source).test_ok("source")).test_ok("render")
    }

    fn local_path(dest: &ImageDest) -> Option<&RelativeWorkspacePath> {
        match dest {
            ImageDest::Local { path, .. } => Some(path),
            ImageDest::External(_) | ImageDest::Malformed(_) => None,
        }
    }

    #[test]
    fn local_spellings_collapse_to_one_canonical_destination() {
        for spelling in [
            "media/pic.png",
            "media/./pic.png",
            "media//pic.png",
            "media\\pic.png",
            "./media/pic.png",
            "  media/pic.png  ",
        ] {
            let dest = ImageDest::classify(spelling);
            assert_eq!(
                dest.projected(),
                "media/pic.png",
                "{spelling:?} must project to the canonical key"
            );
            assert_eq!(
                local_path(&dest).map(RelativeWorkspacePath::as_str),
                Some("media/pic.png"),
                "{spelling:?} must classify Local"
            );
        }
    }

    #[test]
    fn equivalent_spellings_dedup_to_one_typed_fact() {
        let doc = render(
            "![](./media/a.png)\n\n![](media//a.png)\n\n![](media\\a.png)\n\n![](media/a.png)\n",
        );
        assert_eq!(
            doc.attachment_destinations()
                .iter()
                .map(ImageDest::projected)
                .collect::<Vec<_>>(),
            ["media/a.png"],
            "all four spellings must collapse to one canonical destination"
        );
        assert!(matches!(
            doc.attachment_destinations().first(),
            Some(ImageDest::Local { .. })
        ));
    }

    #[test]
    fn external_destinations_classify_without_local_paths() {
        for spelling in [
            "https://example.com/remote.png",
            "data:image/png;base64,AAAA",
            "//cdn.example.com/x.png",
            "#section-anchor",
        ] {
            let dest = ImageDest::classify(spelling);
            assert!(
                matches!(dest, ImageDest::External(_)),
                "{spelling:?} must classify External, got {dest:?}"
            );
            assert_eq!(dest.local(), None, "external destinations carry no path");
            assert_eq!(
                dest.projected(),
                spelling,
                "external keeps its raw spelling"
            );
        }
    }

    #[test]
    fn malformed_destinations_classify_without_local_paths() {
        for spelling in [
            "../outside.png",
            "media/../../escape.png",
            "media/../.././escape.png",
            "",
            "   ",
        ] {
            let dest = ImageDest::classify(spelling);
            assert!(
                matches!(dest, ImageDest::Malformed(_)),
                "{spelling:?} must classify Malformed, got {dest:?}"
            );
            assert_eq!(dest.local(), None, "malformed destinations carry no path");
        }
        // `a/../b.png` folds legally inside the workspace — not malformed.
        assert!(matches!(
            ImageDest::classify("media/../pics/b.png"),
            ImageDest::Local { .. }
        ));
    }

    #[test]
    fn render_fact_list_is_typed_and_covers_images_and_audio_links() {
        let doc = render(
            "![pic](media/pic.png)\n\n![](https://example.com/x.png)\n\n\
             ![](../escape.png)\n\n[clip](media/clip.mp3)\n",
        );
        let dests = doc.attachment_destinations();
        assert_eq!(dests.len(), 4, "one fact per distinct destination");
        assert_eq!(
            local_path(dests.first().test_ok("local fact")).map(RelativeWorkspacePath::as_str),
            Some("media/pic.png")
        );
        assert!(matches!(
            dests.get(1).test_ok("external fact"),
            ImageDest::External(_)
        ));
        assert!(matches!(
            dests.get(2).test_ok("malformed fact"),
            ImageDest::Malformed(_)
        ));
        assert_eq!(
            local_path(dests.get(3).test_ok("audio fact")).map(RelativeWorkspacePath::as_str),
            Some("media/clip.mp3"),
            "audio attachments classify Local as well"
        );
    }

    #[test]
    fn inline_image_destination_is_typed_and_keeps_the_authored_token() {
        let doc = render("![alt](./media/pic.png)\n");
        let mut found = false;
        for block in doc.blocks() {
            if let lomo_workspace::RenderBlock::Paragraph { inlines, .. } = block {
                for inline in inlines {
                    if let RenderInline::Image { destination, .. } = inline {
                        found = true;
                        assert_eq!(destination.raw(), "./media/pic.png");
                        assert_eq!(destination.projected(), "media/pic.png");
                        assert_eq!(
                            destination.local().map(RelativeWorkspacePath::as_str),
                            Some("media/pic.png")
                        );
                    }
                }
            }
        }
        assert!(found, "the document must contain one image inline");
    }

    #[test]
    fn wiki_image_destinations_classify_the_same_way() {
        let doc = render("![[media/pic.png]]\n\n![[../escape.png]]\n");
        let dests = doc.attachment_destinations();
        assert_eq!(dests.len(), 2);
        assert_eq!(
            local_path(dests.first().test_ok("local fact")).map(RelativeWorkspacePath::as_str),
            Some("media/pic.png")
        );
        assert!(matches!(
            dests.get(1).test_ok("malformed fact"),
            ImageDest::Malformed(_)
        ));
    }

    #[test]
    fn serde_wire_is_the_authored_token_and_round_trips() {
        let doc = render("![](media/./pic.png)\n\n![](https://example.com/x.png)\n");
        let json = serde_json::to_string(&doc).test_ok("serialize document");
        // Destinations on the wire keep the authored spelling — schema-v1 compatible with the
        // previous String payload.
        assert!(
            json.contains("media/./pic.png"),
            "raw spelling on the wire: {json}"
        );
        assert!(
            json.contains("https://example.com/x.png"),
            "external token on the wire: {json}"
        );
        let back: lomo_workspace::RenderDocumentV1 =
            serde_json::from_str(&json).test_ok("deserialize document");
        assert_eq!(
            back.attachment_destinations()
                .iter()
                .map(ImageDest::projected)
                .collect::<Vec<_>>(),
            ["media/pic.png", "https://example.com/x.png"],
            "deserialization re-classifies to the same facts"
        );
    }

    #[test]
    fn semantic_fact_keeps_raw_token_while_list_projects() {
        // The Attachment semantic fact must keep the authored token for source-span fidelity;
        // the destinations list carries the classified projection.
        let doc = render("![](./media/pic.png)\n");
        let attachment_facts: Vec<_> = doc
            .semantic_facts()
            .iter()
            .filter(|fact| fact.kind() == lomo_workspace::SemanticFactKind::Attachment)
            .collect();
        assert_eq!(attachment_facts.len(), 1, "one attachment semantic fact");
        assert_eq!(
            attachment_facts.first().test_ok("attachment fact").value(),
            "./media/pic.png"
        );
        assert_eq!(
            doc.attachment_destinations()
                .iter()
                .map(ImageDest::projected)
                .collect::<Vec<_>>(),
            ["media/pic.png"]
        );
    }
}
