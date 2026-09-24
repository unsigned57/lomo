//! Full-text geometry, progress and image placements share the reader's semantic anchor.
use crate::{
    graphics::{ImageState, ReaderImage, TerminalImage},
    model::{AppModel, BodyState, InputMode, TextAnchor, View},
    text_layout::{VisualLine, anchor_row, plain_lines, wrap_lines},
};
use ratatui::{layout::Rect, text::Line};
use std::sync::Arc;

/// Image-block rows anchor after every real grapheme of their source line, so
/// scroll anchoring keeps the text anchor total order and cursor positions
/// always resolve to text rows.
const IMAGE_ANCHOR_GRAPHEME: usize = usize::MAX / 2;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImagePlacement {
    pub rect: Rect,
    pub image: Arc<TerminalImage>,
}

pub struct ReaderPage {
    pub area: Rect,
    pub rows: Vec<VisualLine>,
    pub top: usize,
    pub pictures: Vec<ImagePlacement>,
}

/// Emits the path label plus the state rows of one image at `site_line`
/// (`usize::MAX` appends an unreferenced attachment after the body).
fn emit_image_block(
    rows: &mut Vec<VisualLine>,
    pictures: &mut Vec<(usize, Arc<TerminalImage>)>,
    entry: &ReaderImage,
    site_line: usize,
    loading: &str,
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
            for row in 0..image.rows {
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
        ImageState::Pending | ImageState::Loading => rows.push(VisualLine {
            line: Line::raw(loading.to_owned()),
            anchor: TextAnchor {
                line: site_line,
                grapheme: IMAGE_ANCHOR_GRAPHEME + 1,
            },
        }),
    }
}

#[must_use]
pub fn page(model: &AppModel) -> Option<ReaderPage> {
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
    let (lines, sites) = match &memo.body {
        BodyState::Ready(body) => (body.lines().to_vec(), body.image_sites()),
        BodyState::Pending | BodyState::Loading { .. } => (
            plain_lines(s.text("Loading body…", "正在加载正文…")),
            &[][..],
        ),
        BodyState::Failed(error) => (plain_lines(error), &[][..]),
    };
    // Each attachment image takes the semantic site of its `![..](destination)`
    // node; an attachment with no inline reference keeps the after-body slot.
    let mut claimed = vec![false; sites.len()];
    let mut blocks: Vec<(usize, &ReaderImage)> = model
        .images
        .iter()
        .filter(|entry| entry.request.version == memo.version())
        .map(|entry| {
            let site = sites
                .iter()
                .enumerate()
                .find(|(index, site)| {
                    claimed.get(*index).is_some_and(|taken| !*taken)
                        && site.destination == entry.request.path.as_str()
                })
                .map_or(usize::MAX, |(index, site)| {
                    if let Some(taken) = claimed.get_mut(index) {
                        *taken = true;
                    }
                    site.line
                });
            (site, entry)
        })
        .collect();
    blocks.sort_by_key(|(site, _)| *site);

    let wrapped = wrap_lines(&lines, area.width);
    let mut rows = Vec::with_capacity(wrapped.len() + blocks.len() * 2);
    let mut pictures = Vec::new();
    let mut pending = blocks.into_iter().peekable();
    let loading = s.text("Loading image…", "正在加载图片…");
    for visual in wrapped {
        while matches!(pending.peek(), Some((site, _)) if *site < visual.anchor.line) {
            let Some((site, entry)) = pending.next() else {
                break;
            };
            emit_image_block(&mut rows, &mut pictures, entry, site, loading);
        }
        rows.push(visual);
    }
    for (site, entry) in pending {
        emit_image_block(&mut rows, &mut pictures, entry, site, loading);
    }

    let top = anchor_row(&rows, *anchor);
    let pictures = pictures
        .into_iter()
        .filter_map(|(start, image)| {
            if model.input != InputMode::Browse
                || start < top
                || start + usize::from(image.rows) > top + usize::from(area.height)
            {
                return None;
            }
            let y = (area.y..area.bottom())
                .zip(top..)
                .find_map(|(y, row)| (row == start).then_some(y))?;
            Some(ImagePlacement {
                rect: Rect::new(area.x, y, image.columns.min(area.width), image.rows),
                image,
            })
        })
        .collect();
    Some(ReaderPage {
        area,
        rows,
        top,
        pictures,
    })
}
