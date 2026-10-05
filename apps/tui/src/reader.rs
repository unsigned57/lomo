//! Full-text geometry, progress and image placements share the reader's semantic anchor.
//!
//! I4: `MemoBody::wrapped` memoizes the grapheme wrap per width on the body
//! itself, and a page materializes only the viewport plus a lookahead window
//! of merged text and image rows. Scrolling resolves anchors through the same
//! derivation, so navigation and drawing never wrap twice.
use crate::{
    graphics::{ImageState, ReaderImage, TerminalImage},
    model::{AppModel, BodyState, InputMode, TextAnchor, View},
    text_layout::{VisualLine, anchor_row, plain_lines, printable, wrap_lines},
};
use lomo_core::RelativeWorkspacePath;
use ratatui::{
    layout::Rect,
    text::{Line, Span},
};
use std::{ops::Range, sync::Arc};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

/// Image-block rows anchor after every real grapheme of their source line, so
/// scroll anchoring keeps the text anchor total order and cursor positions
/// always resolve to text rows.
const IMAGE_ANCHOR_GRAPHEME: usize = usize::MAX / 2;
/// Rows materialized beyond the viewport on each side of the anchor.
const LOOKAHEAD_ROWS: usize = 48;

#[derive(Clone, Debug)]
pub struct ImagePlacement {
    pub rect: Rect,
    pub image: Arc<TerminalImage>,
}

pub struct ReaderPage {
    pub area: Rect,
    /// Materialized merged rows for `origin .. origin + rows.len()`.
    pub rows: Vec<VisualLine>,
    /// Absolute merged-row index of `rows[0]`.
    pub origin: usize,
    /// Absolute merged-row index of the semantic anchor — the viewport top.
    pub top: usize,
    /// Total merged rows the document occupies.
    pub total: usize,
    pub pictures: Vec<ImagePlacement>,
}

/// One attachment image's emission site in merged-row space.
struct ImageBlock<'a> {
    /// Source line the block anchors to (`usize::MAX` = after the body).
    site: usize,
    /// The `[Image: ..]` placeholder's grapheme range inside `site` — blanked
    /// from materialized rows once this image is `Ready`, because the pixels
    /// are the site's content then (D-08). `None` for the after-body slot.
    placeholder: Option<Range<usize>>,
    /// First wrapped index this block inserts before (`anchor.line > site`).
    insert: usize,
    /// Absolute merged row of the block's label row.
    start: usize,
    /// Rows the block emits (label plus state rows).
    rows: usize,
    entry: &'a ReaderImage,
}

impl ImageBlock<'_> {
    /// `label` plus however many rows the state occupies.
    fn row_count(&self) -> usize {
        match &self.entry.state {
            ImageState::Ready(image) => 1 + usize::from(image.area().height),
            ImageState::Pending
            | ImageState::Loading(_)
            | ImageState::Failed(_)
            | ImageState::Evicted => 2,
        }
    }
}

/// Emits the path label plus the state rows of one image at `site_line`
/// (`usize::MAX` appends an unreferenced attachment after the body). Picture
/// indices are relative to the block's own rows.
fn emit_image_block(
    rows: &mut Vec<VisualLine>,
    pictures: &mut Vec<(usize, Arc<TerminalImage>)>,
    entry: &ReaderImage,
    site_line: usize,
    loading: &str,
    evicted: &str,
) {
    rows.push(VisualLine {
        line: Line::raw(entry.request.path.as_str().to_owned()),
        anchor: TextAnchor {
            line: site_line,
            grapheme: IMAGE_ANCHOR_GRAPHEME,
        },
    });
    match &entry.state {
        ImageState::Ready(image) => {
            let start = rows.len();
            for row in 0..image.area().height {
                rows.push(VisualLine {
                    line: Line::default(),
                    anchor: TextAnchor {
                        line: site_line,
                        grapheme: IMAGE_ANCHOR_GRAPHEME + 1 + usize::from(row),
                    },
                });
            }
            pictures.push((start, Arc::clone(image)));
        }
        ImageState::Failed(error) => rows.push(VisualLine {
            line: Line::raw(error.clone()),
            anchor: TextAnchor {
                line: site_line,
                grapheme: IMAGE_ANCHOR_GRAPHEME + 1,
            },
        }),
        ImageState::Pending | ImageState::Loading(_) => rows.push(VisualLine {
            line: Line::raw(loading.to_owned()),
            anchor: TextAnchor {
                line: site_line,
                grapheme: IMAGE_ANCHOR_GRAPHEME + 1,
            },
        }),
        ImageState::Evicted => rows.push(VisualLine {
            line: Line::raw(evicted.to_owned()),
            anchor: TextAnchor {
                line: site_line,
                grapheme: IMAGE_ANCHOR_GRAPHEME + 1,
            },
        }),
    }
}

/// Everything `page` derives once: the memoized wrap, the image blocks in
/// merged-row space, the anchor's absolute row and the merged total.
struct Derived<'a> {
    area: Rect,
    /// Styled body lines — the source `wrapped` was produced from; needed to
    /// blank a claimed site's placeholder run inside its materialized rows.
    lines: &'a [Line<'static>],
    wrapped: Arc<[VisualLine]>,
    blocks: Vec<ImageBlock<'a>>,
    anchor_abs: usize,
    total: usize,
    browse: bool,
}

fn derive(model: &AppModel) -> Option<Derived<'_>> {
    let View::Reader { memo, anchor } = &model.view else {
        return None;
    };
    let layout = crate::ui::layout_for(model);
    let area = Rect::new(
        layout.content.x + 2,
        layout.content.y + 2,
        layout.content.width.saturating_sub(2),
        layout.content.height.saturating_sub(2),
    );
    let s = crate::i18n::UiStrings::detect();
    let (wrapped, sites, lines) = match &memo.body {
        BodyState::Ready(body) => (body.wrapped(area.width), body.image_sites(), body.lines()),
        BodyState::Pending | BodyState::Loading { .. } => (
            Arc::from(wrap_lines(
                &plain_lines(s.text("Loading body…", "正在加载正文…")),
                area.width,
            )),
            &[][..],
            &[][..],
        ),
        BodyState::Failed(error) => (
            Arc::from(wrap_lines(&plain_lines(error), area.width)),
            &[][..],
            &[][..],
        ),
    };
    // Each attachment image takes the semantic site of its `![..](destination)`
    // node; an attachment with no inline reference keeps the after-body slot.
    let mut claimed = vec![false; sites.len()];
    let mut blocks: Vec<ImageBlock<'_>> = model
        .images
        .iter()
        .filter(|entry| entry.request.version == memo.version())
        .map(|entry| {
            let (site, placeholder) = sites
                .iter()
                .enumerate()
                .find(|(index, site)| {
                    claimed.get(*index).is_some_and(|taken| !*taken)
                        && site.destination.local().map(RelativeWorkspacePath::as_str)
                            == Some(entry.request.path.as_str())
                })
                .map_or((usize::MAX, None), |(index, site)| {
                    if let Some(taken) = claimed.get_mut(index) {
                        *taken = true;
                    }
                    (site.line, Some(site.placeholder.clone()))
                });
            // `insert` is the first wrapped row past the site line — the
            // predicate must be the true *prefix* `anchor.line <= site`;
            // `partition_point` binary-searches for the first false.
            let insert = wrapped.partition_point(|row| row.anchor.line <= site);
            ImageBlock {
                site,
                placeholder,
                insert,
                start: 0,
                rows: 0,
                entry,
            }
        })
        .collect();
    blocks.sort_by_key(|block| block.site);
    let mut shift = 0usize;
    for block in &mut blocks {
        block.rows = block.row_count();
        block.start = block.insert + shift;
        shift += block.rows;
    }
    let total = wrapped.len() + shift;
    // Merged order is anchor order: image rows at `(site, IMAGE_ANCHOR+)` sort
    // between the text rows of `site` and `site + 1`, so the anchor's merged
    // index is its wrapped index plus every image row that sorts at or before it.
    let anchor_abs = anchor_row(&wrapped, *anchor)
        + blocks
            .iter()
            .map(|block| {
                if block.site < anchor.line {
                    block.rows
                } else if block.site == anchor.line && anchor.grapheme >= IMAGE_ANCHOR_GRAPHEME {
                    (anchor.grapheme - IMAGE_ANCHOR_GRAPHEME + 1).min(block.rows)
                } else {
                    0
                }
            })
            .sum::<usize>();
    Some(Derived {
        area,
        lines,
        wrapped,
        blocks,
        anchor_abs,
        total,
        browse: model.input == InputMode::Browse,
    })
}

/// Grapheme count of one styled line.
fn grapheme_count(line: &Line<'static>) -> usize {
    line.spans
        .iter()
        .map(|span| span.content.graphemes(true).count())
        .sum()
}

/// How many graphemes `wrap_line` emits for styled graphemes `[from, to)` —
/// replays its `printable` expansion, the too-wide `□` substitution and the
/// `\n` flush (which emits nothing), so a rendered index always names the
/// same graphemes the wrapper produced.
fn rendered_index(styled: &Line<'static>, width: usize, from: usize, to: usize) -> usize {
    let mut rendered = 0usize;
    let mut offset = 0usize;
    'spans: for span in &styled.spans {
        for grapheme in span.content.graphemes(true) {
            if offset >= to {
                break 'spans;
            }
            if offset >= from && grapheme != "\n" {
                let text = printable(grapheme);
                rendered += if UnicodeWidthStr::width(text.as_str()) > width {
                    1 // the `□` emitted for a grapheme wider than the row
                } else {
                    text.graphemes(true).count()
                };
            }
            offset += 1;
        }
    }
    rendered
}

/// `line` minus the rendered graphemes in `drops`, re-merging same-style
/// neighbours so the row reads as what `wrap_line` would have emitted
/// without them.
fn drop_rendered(line: &Line<'static>, drops: &[Range<usize>]) -> Line<'static> {
    let mut spans: Vec<Span<'static>> = Vec::new();
    let mut index = 0usize;
    for span in &line.spans {
        for grapheme in span.content.graphemes(true) {
            if !drops.iter().any(|range| range.contains(&index)) {
                match spans.last_mut() {
                    Some(last) if last.style == span.style => {
                        last.content.to_mut().push_str(grapheme);
                    }
                    _ => spans.push(Span::styled(grapheme.to_owned(), span.style)),
                }
            }
            index += 1;
        }
    }
    Line::from(spans)
}

impl Derived<'_> {
    /// `row` (`wrapped[index]`) minus every Ready-claimed placeholder run on
    /// its styled line. Rows, anchors and the merged total stay exactly as
    /// wrapped — only the `[Image: ..]` text leaves once the pixels exist.
    fn without_placeholders(
        &self,
        row: &VisualLine,
        index: usize,
        suppressed: &[(usize, Range<usize>)],
    ) -> VisualLine {
        let mut drops: Vec<Range<usize>> = Vec::new();
        for (_, placeholder) in suppressed
            .iter()
            .filter(|(line, _)| *line == row.anchor.line)
        {
            let Some(styled) = self.lines.get(row.anchor.line) else {
                continue;
            };
            // The row covers styled graphemes `[anchor.grapheme, row_end)`:
            // a wrap boundary is where the next row of the same line starts.
            let row_end = self
                .wrapped
                .get(index + 1)
                .filter(|next| next.anchor.line == row.anchor.line)
                .map_or_else(|| grapheme_count(styled), |next| next.anchor.grapheme);
            let overlap = placeholder.start.max(row.anchor.grapheme)..placeholder.end.min(row_end);
            if overlap.is_empty() {
                continue;
            }
            let width = usize::from(self.area.width);
            let start = rendered_index(styled, width, row.anchor.grapheme, overlap.start);
            let end = rendered_index(styled, width, row.anchor.grapheme, overlap.end);
            drops.push(start..end);
        }
        if drops.is_empty() {
            return row.clone();
        }
        VisualLine {
            line: drop_rendered(&row.line, &drops),
            anchor: row.anchor,
        }
    }
}

/// Materializes the merged window around `around` — `around ± lookahead` plus
/// the viewport below it.
fn materialize(derived: &Derived<'_>, around: usize) -> ReaderPage {
    let s = crate::i18n::UiStrings::detect();
    let loading = s.text("Loading image…", "正在加载图片…");
    let evicted = s.text(
        "Image unloaded · over the reader memory budget",
        "图片已卸载 · 超出阅读器内存预算",
    );
    let lo = around.saturating_sub(LOOKAHEAD_ROWS);
    let hi = around
        .saturating_add(usize::from(derived.area.height) + LOOKAHEAD_ROWS)
        .min(derived.total);
    // A `Ready` image's pixels are the site's content — blank the `[Image: ..]`
    // placeholder run inside the site line's materialized rows (D-08).
    // `Pending`/`Failed`/`Evicted` keep it: the placeholder is their
    // presentation.
    let suppressed: Vec<(usize, Range<usize>)> = derived
        .blocks
        .iter()
        .filter(|block| matches!(block.entry.state, ImageState::Ready(_)))
        .filter_map(|block| block.placeholder.clone().map(|range| (block.site, range)))
        .collect();
    let mut rows: Vec<VisualLine> = Vec::with_capacity(hi - lo);
    let mut pictures: Vec<(usize, Arc<TerminalImage>)> = Vec::new();
    let mut wi = 0usize;
    let mut shift = 0usize;
    let push_wrapped = |from: usize, to: usize, base: usize, rows: &mut Vec<VisualLine>| {
        for (offset, row) in derived
            .wrapped
            .get(from..to)
            .unwrap_or(&[])
            .iter()
            .enumerate()
        {
            if (lo.saturating_sub(base)..hi.saturating_sub(base)).contains(&offset) {
                rows.push(derived.without_placeholders(row, from + offset, &suppressed));
            }
        }
    };
    for block in &derived.blocks {
        push_wrapped(wi, block.insert, wi + shift, &mut rows);
        wi = block.insert;
        shift += block.rows;
        if block.start < hi && block.start + block.rows > lo {
            let mut block_rows = Vec::with_capacity(block.rows);
            let mut block_pictures = Vec::new();
            emit_image_block(
                &mut block_rows,
                &mut block_pictures,
                block.entry,
                block.site,
                loading,
                evicted,
            );
            for (local, row) in block_rows.into_iter().enumerate() {
                let abs = block.start + local;
                if abs >= lo && abs < hi {
                    rows.push(row);
                }
            }
            for (local, image) in block_pictures {
                pictures.push((block.start + local, image));
            }
        }
    }
    push_wrapped(wi, derived.wrapped.len(), wi + shift, &mut rows);

    // The page's top is the window the caller asked for, not the raw anchor —
    // a clamped anchor near the body end keeps a full page (I9 audit).
    let top = around;
    let pictures = pictures
        .into_iter()
        .filter_map(|(start, image)| {
            let block = image.area();
            if !derived.browse
                || start < top
                || start + usize::from(block.height) > top + usize::from(derived.area.height)
            {
                return None;
            }
            let Ok(offset) = u16::try_from(start - top) else {
                return None;
            };
            let y = derived.area.y.saturating_add(offset);
            Some(ImagePlacement {
                rect: Rect::new(
                    derived.area.x,
                    y,
                    block.width.min(derived.area.width),
                    block.height,
                ),
                image,
            })
        })
        .collect();
    ReaderPage {
        area: derived.area,
        rows,
        origin: lo,
        top,
        total: derived.total,
        pictures,
    }
}

/// The reader viewport materialized around its semantic anchor — a bounded
/// window of merged text and image rows.
#[must_use]
pub fn page(model: &AppModel) -> Option<ReaderPage> {
    let derived = derive(model)?;
    // An anchor that outlives its body resolves to the last row — the page
    // top still clamps to the last full screen instead of stranding one row
    // atop an empty viewport (same bound `scroll_anchor` enforces).
    let top = derived.anchor_abs.min(
        derived
            .total
            .saturating_sub(usize::from(derived.area.height)),
    );
    Some(materialize(&derived, top))
}

/// The merged row `delta` steps below the reader's current anchor — the scroll
/// target navigation installs. Resolves through the same shared geometry the
/// renderer draws.
#[must_use]
pub fn scroll_anchor(model: &AppModel, delta: i32) -> Option<TextAnchor> {
    let page = page(model)?;
    let next = page
        .top
        .saturating_add_signed(delta as isize)
        .min(page.total.saturating_sub(usize::from(page.area.height)));
    if (page.origin..page.origin + page.rows.len()).contains(&next) {
        return page.rows.get(next - page.origin).map(|row| row.anchor);
    }
    let window = materialize(&derive(model)?, next);
    window
        .rows
        .get(next.saturating_sub(window.origin))
        .map(|row| row.anchor)
}
