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
    wrap_lines_bounded(lines, width, usize::MAX)
}

/// Materializes `lines` into the visual rows `wrap_lines` emits.
///
/// The anchors are dropped; every row fits `width`, so `Paragraph` can
/// paint them plainly — its own `WordWrapper` emits an unbreakable word
/// past the right edge instead of hard-wrapping it (09-I6-01).
#[must_use]
pub fn wrapped_lines(lines: &[Line<'static>], width: u16) -> Vec<Line<'static>> {
    wrap_lines(lines, width)
        .into_iter()
        .map(|row| row.line)
        .collect()
}

/// Wraps until `limit` visual rows exist; input beyond the limit is never
/// consumed, so bounded card previews never wrap a whole body.
#[must_use]
pub fn wrap_lines_bounded(lines: &[Line<'static>], width: u16, limit: usize) -> Vec<VisualLine> {
    if width == 0 || limit == 0 {
        return Vec::new();
    }
    let mut out = Vec::new();
    let last = lines.len().saturating_sub(1);
    for (logical, line) in lines.iter().enumerate() {
        if out.len() >= limit {
            break;
        }
        wrap_line(
            line,
            logical,
            usize::from(width),
            limit,
            logical == last,
            &mut out,
        );
    }
    out
}

fn wrap_line(
    line: &Line<'static>,
    logical: usize,
    width: usize,
    limit: usize,
    caret_row: bool,
    out: &mut Vec<VisualLine>,
) {
    let mut spans = Vec::new();
    let mut columns = 0;
    let mut offset = 0;
    let mut anchor = TextAnchor {
        line: logical,
        grapheme: 0,
    };
    for span in &line.spans {
        for grapheme in span.content.graphemes(true) {
            if out.len() >= limit {
                return;
            }
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
            let (wraps, placed) = grapheme_step(columns, cells, width);
            if wraps {
                flush(&mut spans, anchor, out);
                columns = 0;
                anchor = TextAnchor {
                    line: logical,
                    grapheme: offset,
                };
            }
            columns += placed;
            push_span(
                &mut spans,
                if cells > width {
                    "□".to_owned()
                } else {
                    text
                },
                line.style.patch(span.style),
            );
            offset += 1;
        }
    }
    if out.len() < limit {
        flush(&mut spans, anchor, out);
        // The text ends exactly on the column boundary: `cursor_position`
        // reports the caret at the start of the next row, so the wrapped
        // layout must own that row — an empty tail line where the caret and
        // the next typed grapheme both live.
        if caret_row && columns == width && out.len() < limit {
            out.push(VisualLine {
                line: Line::default(),
                anchor,
            });
        }
    }
}
/// The rendered text of one styled grapheme — tab expansion and control
/// escaping included — so callers counting wrapped output see the same
/// graphemes `wrap_line` emitted.
pub(crate) fn printable(grapheme: &str) -> String {
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

/// The one grapheme-placement rule `wrap_line` and `cursor_position` share,
/// so a cursor can never land on a visual row the wrapper did not emit
/// (09-I6-05): a grapheme wider than the whole column renders as `□` — a
/// single cell — and any grapheme that would overflow a non-empty row starts
/// the next visual row. Returns `(breaks_the_row, cells_placed)`.
const fn grapheme_step(columns: usize, cells: usize, width: usize) -> (bool, usize) {
    (
        columns > 0 && columns + cells > width,
        if cells > width { 1 } else { cells },
    )
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
        let cells = UnicodeWidthStr::width(printable(grapheme).as_str());
        let (wraps, placed) = grapheme_step(col, cells, width);
        if wraps {
            row += 1;
            col = 0;
        }
        col += placed;
    }
    if col == width {
        (row + 1, 0)
    } else {
        (row, col)
    }
}
