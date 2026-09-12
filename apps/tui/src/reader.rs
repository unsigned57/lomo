//! Full-text geometry, progress and image placements share the reader's semantic anchor.
use crate::{
    graphics::{ImageState, TerminalImage},
    model::{AppModel, BodyState, InputMode, TextAnchor, View},
    text_layout::{VisualLine, anchor_row, plain_lines, wrap_lines},
};
use ratatui::{layout::Rect, text::Line};
use std::sync::Arc;

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
    let lines = match &memo.body {
        BodyState::Ready(body) => body.lines().to_vec(),
        BodyState::Pending | BodyState::Loading { .. } => {
            plain_lines(s.text("Loading body…", "正在加载正文…"))
        }
        BodyState::Failed(error) => plain_lines(error),
    };
    let mut rows = wrap_lines(&lines, area.width);
    let mut images = Vec::new();
    for (index, entry) in model
        .images
        .iter()
        .enumerate()
        .filter(|(_, entry)| entry.request.version == memo.version())
    {
        let logical = lines.len() + index * 2;
        rows.push(VisualLine {
            line: Line::raw(entry.request.path.as_str().to_owned()),
            anchor: TextAnchor {
                line: logical,
                grapheme: 0,
            },
        });
        match &entry.state {
            ImageState::Ready(image) => {
                let start = rows.len();
                for row in 0..image.rows {
                    rows.push(VisualLine {
                        line: Line::default(),
                        anchor: TextAnchor {
                            line: logical + 1,
                            grapheme: usize::from(row),
                        },
                    });
                }
                images.push((start, Arc::clone(image)));
            }
            ImageState::Failed(error) => rows.push(VisualLine {
                line: Line::raw(error.clone()),
                anchor: TextAnchor {
                    line: logical + 1,
                    grapheme: 0,
                },
            }),
            ImageState::Pending | ImageState::Loading => rows.push(VisualLine {
                line: Line::raw(s.text("Loading image…", "正在加载图片…")),
                anchor: TextAnchor {
                    line: logical + 1,
                    grapheme: 0,
                },
            }),
        }
    }
    let top = anchor_row(&rows, *anchor);
    let pictures = images
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
