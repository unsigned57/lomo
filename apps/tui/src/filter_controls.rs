//! Filter chips use the same hit regions for rendering and click removal.
use crate::{
    event::Command,
    model::{AppModel, View},
};
use ratatui::layout::Rect;
use unicode_width::UnicodeWidthStr;

#[must_use]
pub fn controls(model: &AppModel, area: Rect) -> Vec<(Rect, String, Command)> {
    let View::Feed(feed) = &model.view else {
        return Vec::new();
    };
    let mut labels = Vec::new();
    if !feed.query.text.is_empty() {
        labels.push((format!("/ {} ×", feed.query.text), Command::RemoveKeyword));
    }
    if let Some(tag) = &feed.query.filters.tag {
        labels.push((format!("#{tag} ×"), Command::SelectTag(None)));
    }
    if let Some(date) = &feed.query.date_label {
        labels.push((format!("{date} ×"), Command::RemoveDate));
    }
    let mut left = area.x;
    let mut out = Vec::new();
    for (label, command) in labels {
        let width = u16::try_from(UnicodeWidthStr::width(label.as_str()))
            .unwrap_or(u16::MAX)
            .min(area.right().saturating_sub(left));
        if width == 0 || area.height < 2 {
            break;
        }
        out.push((Rect::new(left, area.y + 1, width, 1), label, command));
        left = left.saturating_add(width).saturating_add(2);
    }
    out
}
