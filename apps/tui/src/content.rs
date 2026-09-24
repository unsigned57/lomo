//! One validated, parsed body per memo version. Drawing performs no parsing or IO.
use lomo_workspace::{RenderDocumentV1, SourceBytes, render_markdown};
use ratatui::text::Line;

use crate::error::TuiError;
use crate::markdown_view::{ImageSite, TagDisplay, styled_document, styled_document_sites};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoBody {
    raw: String,
    document: RenderDocumentV1,
    lines: Vec<Line<'static>>,
    card_lines: Vec<Line<'static>>,
    image_sites: Vec<ImageSite>,
}

impl MemoBody {
    /// # Errors
    /// Invalid source or Markdown resource limits are surfaced before rendering.
    pub fn parse(raw: String) -> Result<Self, TuiError> {
        let source = SourceBytes::try_from_str(&raw)?;
        let document = render_markdown(&source)?;
        let (lines, image_sites) = styled_document_sites(&document, TagDisplay::Inline);
        let card_lines = styled_document(&document, TagDisplay::Hidden);
        Ok(Self {
            raw,
            document,
            lines,
            card_lines,
            image_sites,
        })
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.raw
    }

    #[must_use]
    pub const fn document(&self) -> &RenderDocumentV1 {
        &self.document
    }

    #[must_use]
    pub fn lines(&self) -> &[Line<'static>] {
        &self.lines
    }

    /// Body rows for a feed card: tags are omitted because the card footer lists them.
    #[must_use]
    pub fn card_lines(&self) -> &[Line<'static>] {
        &self.card_lines
    }

    /// Semantic position of every `![image](destination)` in [`Self::lines`],
    /// in document order. The reader places decoded images at these sites.
    #[must_use]
    pub fn image_sites(&self) -> &[ImageSite] {
        &self.image_sites
    }
}
