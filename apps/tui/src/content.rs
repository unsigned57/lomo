//! One validated, parsed body per memo version. Drawing performs no parsing or IO.
use lomo_workspace::{RenderDocumentV1, SourceBytes, render_markdown};
use ratatui::text::Line;
use std::sync::{Arc, Mutex};

use crate::error::TuiError;
use crate::markdown_view::{ImageSite, TagDisplay, styled_document, styled_document_sites};
use crate::text_layout::{VisualLine, wrap_lines};

/// The reader's wrapped rows memoized per width — a pure projection of
/// `lines`, so it never participates in body equality and a clone re-wraps on
/// demand instead of deep-copying every visual row.
#[derive(Debug)]
pub struct WrapCache(Mutex<Option<(u16, Arc<[VisualLine]>)>>);
impl WrapCache {
    fn wrapped(&self, lines: &[Line<'static>], width: u16) -> Arc<[VisualLine]> {
        let mut slot = self
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some((cached_width, rows)) = slot.as_ref()
            && *cached_width == width
        {
            return Arc::clone(rows);
        }
        let rows: Arc<[VisualLine]> = Arc::from(wrap_lines(lines, width));
        *slot = Some((width, Arc::clone(&rows)));
        rows
    }
}
impl Default for WrapCache {
    fn default() -> Self {
        Self(Mutex::new(None))
    }
}
impl Clone for WrapCache {
    /// Derived state never deep-clones; the destination re-wraps on demand.
    fn clone(&self) -> Self {
        Self::default()
    }
}
impl PartialEq for WrapCache {
    fn eq(&self, _other: &Self) -> bool {
        true
    }
}
impl Eq for WrapCache {}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoBody {
    raw: String,
    document: RenderDocumentV1,
    lines: Vec<Line<'static>>,
    /// Card rows shared by reference — every feed card and every layout epoch
    /// sees the same allocation instead of a per-card copy.
    card_lines: Arc<[Line<'static>]>,
    image_sites: Vec<ImageSite>,
    wraps: WrapCache,
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
            card_lines: Arc::from(card_lines),
            image_sites,
            wraps: WrapCache::default(),
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

    /// The shared card-row allocation, for layouts that memoize by identity.
    #[must_use]
    pub fn card_lines_arc(&self) -> Arc<[Line<'static>]> {
        Arc::clone(&self.card_lines)
    }

    /// The reader's wrapped visual rows at `width`, memoized on the body.
    /// Navigation and drawing share the same `Arc`; only a width change
    /// re-wraps.
    #[must_use]
    pub fn wrapped(&self, width: u16) -> Arc<[VisualLine]> {
        self.wraps.wrapped(&self.lines, width)
    }

    /// Semantic position of every `![image](destination)` in [`Self::lines`],
    /// in document order. The reader places decoded images at these sites.
    #[must_use]
    pub fn image_sites(&self) -> &[ImageSite] {
        &self.image_sites
    }
}
