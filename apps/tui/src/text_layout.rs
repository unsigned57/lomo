//! Grapheme-aware soft wrapping and stable text anchors.
use ratatui::style::Style;
use ratatui::text::{Line, Span};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use crate::model::TextAnchor;

#[derive(Clone, Debug)]
pub struct VisualLine {
    pub line: Line<'static>,
    pub anchor: TextAnchor,
}

#[must_use]
pub fn wrap_lines(lines: &[Line<'static>], width: u16) -> Vec<VisualLine> {
    if width == 0 {
        return Vec::new();
    }
    let mut out = Vec::new();
    for (logical, line) in lines.iter().enumerate() {
        wrap_line(line, logical, usize::from(width), &mut out);
    }
    out
}

fn wrap_line(line: &Line<'static>, logical: usize, width: usize, out: &mut Vec<VisualLine>) {
    let mut spans = Vec::new();
    let mut columns = 0;
    let mut offset = 0;
    let mut anchor = TextAnchor {
        line: logical,
        grapheme: 0,
    };
    for span in &line.spans {
        for grapheme in span.content.graphemes(true) {
            if grapheme == "\n" {
                flush(&mut spans, anchor, out);
                columns = 0;
                offset += 1;
                anchor = TextAnchor {
                    line: logical,
                    grapheme: offset,
                };
                continue;
            }
            let text = printable(grapheme);
            let cells = UnicodeWidthStr::width(text.as_str());
            if columns > 0 && columns + cells > width {
                flush(&mut spans, anchor, out);
                columns = 0;
                anchor = TextAnchor {
                    line: logical,
                    grapheme: offset,
                };
            }
            let text = if cells > width {
                "□".to_owned()
            } else {
                text
            };
            columns += UnicodeWidthStr::width(text.as_str());
            push_span(&mut spans, text, line.style.patch(span.style));
            offset += 1;
        }
    }
    flush(&mut spans, anchor, out);
}
fn printable(grapheme: &str) -> String {
    if grapheme == "\t" {
        "    ".to_owned()
    } else if grapheme.chars().any(char::is_control) {
        grapheme.escape_default().to_string()
    } else {
        grapheme.to_owned()
    }
}
fn push_span(spans: &mut Vec<Span<'static>>, text: String, style: Style) {
    if let Some(last) = spans.last_mut().filter(|last| last.style == style) {
        last.content.to_mut().push_str(&text);
    } else {
        spans.push(Span::styled(text, style));
    }
}
fn flush(spans: &mut Vec<Span<'static>>, anchor: TextAnchor, out: &mut Vec<VisualLine>) {
    out.push(VisualLine {
        line: Line::from(std::mem::take(spans)),
        anchor,
    });
}
#[must_use]
pub fn anchor_row(lines: &[VisualLine], anchor: TextAnchor) -> usize {
    lines
        .iter()
        .rposition(|line| line.anchor <= anchor)
        .unwrap_or(0)
}
#[must_use]
pub fn plain_lines(text: &str) -> Vec<Line<'static>> {
    text.split('\n')
        .map(|line| Line::raw(line.to_owned()))
        .collect()
}

/// Maps a byte cursor to wrapped coordinates, including a new row at an exact wrap boundary.
#[must_use]
pub fn cursor_position(prefix: &str, width: u16) -> (usize, usize) {
    let mut row = 0;
    let mut col = 0;
    let width = usize::from(width.max(1));
    for grapheme in prefix.graphemes(true) {
        if grapheme == "\n" {
            row += 1;
            col = 0;
            continue;
        }
        let cells = UnicodeWidthStr::width(printable(grapheme).as_str()).min(width);
        if col + cells > width {
            row += 1;
            col = 0;
        }
        col += cells;
    }
    if col == width {
        (row + 1, 0)
    } else {
        (row, col)
    }
}
