//! One validated, parsed body per memo version. Drawing performs no parsing or IO.
use lomo_workspace::{RenderDocumentV1, SourceBytes, render_markdown};
use ratatui::text::Line;

use crate::error::TuiError;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoBody {
    raw: String,
    document: RenderDocumentV1,
    lines: Vec<Line<'static>>,
}

impl MemoBody {
    /// # Errors
    /// Invalid source or Markdown resource limits are surfaced before rendering.
    pub fn parse(raw: String) -> Result<Self, TuiError> {
        let source = SourceBytes::try_from_str(&raw)?;
        let document = render_markdown(&source)?;
        let lines = crate::markdown_view::styled_document(&document);
        Ok(Self {
            raw,
            document,
            lines,
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
}
