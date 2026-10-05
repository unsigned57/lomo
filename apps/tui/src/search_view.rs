//! Highlight only the source ranges supplied by the application search result.
use lomo_application::search_excerpt::SearchExcerpt;
use ratatui::{
    style::{Color, Modifier, Style},
    text::{Line, Span},
};
use unicode_segmentation::UnicodeSegmentation;

use crate::model::TextAnchor;

#[must_use]
pub fn excerpt_lines(excerpt: &SearchExcerpt) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    let mut spans = Vec::new();
    for (byte, grapheme) in excerpt.text.grapheme_indices(true) {
        if grapheme == "\n" {
            lines.push(Line::from(std::mem::take(&mut spans)));
            continue;
        }
        let highlighted = excerpt
            .highlights
            .iter()
            .any(|range| range.start < byte + grapheme.len() && range.end > byte);
        let style = if highlighted {
            Style::default()
                .fg(Color::Cyan)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default()
        };
        if let Some(last) = spans.last_mut().filter(|span| span.style == style) {
            last.content.to_mut().push_str(grapheme);
        } else {
            spans.push(Span::styled(grapheme.to_owned(), style));
        }
    }
    lines.push(Line::from(spans));
    lines
}

/// The source-addressed anchor of the excerpt's first highlight.
///
/// Coordinates are (logical line, grapheme) — the same frame `text_layout`
/// anchors wrapped rows in — so scrolling to a hit never inspects styles.
#[must_use]
pub fn excerpt_anchor(excerpt: &SearchExcerpt) -> Option<TextAnchor> {
    let byte = excerpt.highlights.first().map(|range| range.start)?;
    let mut anchor = TextAnchor {
        line: 0,
        grapheme: 0,
    };
    for (offset, grapheme) in excerpt.text.grapheme_indices(true) {
        if offset >= byte {
            break;
        }
        if grapheme == "\n" {
            anchor.line += 1;
            anchor.grapheme = 0;
        } else {
            anchor.grapheme += 1;
        }
    }
    Some(anchor)
}
