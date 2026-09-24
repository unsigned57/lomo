//! One centered reading column at every terminal width.
use ratatui::layout::Rect;

pub const READING_COLUMNS: u16 = 96;
pub const CARD_BODY_LINES: usize = 6;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ReadingLayout {
    pub header: Rect,
    pub filters: Rect,
    pub composer: Rect,
    pub content: Rect,
    pub status: Rect,
}

/// `filter_rows` is 2 for the label and chip rows, or 3 while the bordered search field is open.
#[must_use]
pub fn reading_layout(area: Rect, composer_rows: u16, filter_rows: u16) -> ReadingLayout {
    let width = area.width.saturating_sub(4).min(READING_COLUMNS);
    let x = area.x + area.width.saturating_sub(width) / 2;
    let header_h = area.height.min(1);
    let filter_h = area.height.saturating_sub(header_h).min(filter_rows);
    let status_h = area.height.saturating_sub(header_h + filter_h).min(1);
    let remaining = area.height.saturating_sub(header_h + filter_h + status_h);
    let compose_h = composer_rows.min(remaining / 2);
    ReadingLayout {
        header: Rect::new(x, area.y, width, header_h),
        filters: Rect::new(x, area.y + header_h, width, filter_h),
        composer: Rect::new(x, area.y + header_h + filter_h, width, compose_h),
        content: Rect::new(
            x,
            area.y + header_h + filter_h + compose_h,
            width,
            remaining.saturating_sub(compose_h),
        ),
        status: Rect::new(
            x,
            area.y + area.height.saturating_sub(status_h),
            width,
            status_h,
        ),
    }
}
