//! Statistics: overview, period counts and an activity heatmap in the reading column's chrome.

use std::collections::HashMap;

use lomo_application::calendar::CivilDate;
use ratatui::Frame;
use ratatui::buffer::Buffer;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use unicode_width::UnicodeWidthChar;

use crate::i18n::UiStrings;
use crate::model::StatsView;

const OVERVIEW_ROWS: u16 = 6;
/// Two header rows (month labels), seven weekday rows and one legend row.
const HEATMAP_MIN_INNER_ROWS: u16 = 10;
const BORDER_STYLE: Style = Style::new().fg(Color::DarkGray);
const TITLE_STYLE: Style = Style::new().add_modifier(Modifier::BOLD);

/// Draws the statistics screen into `area`.
///
/// Every write goes through `put_str`/per-cell paints instead of `Block` and
/// `Paragraph` widgets: this frame is repainted every tick and the widgets
/// pay per-cell plumbing plus grapheme segmentation the fixed i18n labels do
/// not need.
pub fn draw_stats(frame: &mut Frame, area: Rect, stats: &StatsView, i18n: &UiStrings) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(OVERVIEW_ROWS), Constraint::Min(0)])
        .split(area);
    let Some(overview_area) = chunks.first().copied() else {
        return;
    };
    let Some(heatmap_area) = chunks.get(1).copied() else {
        return;
    };
    let top = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(overview_area);
    let Some(summary_area) = top.first().copied() else {
        return;
    };
    let Some(period_area) = top.get(1).copied() else {
        return;
    };

    let buf = frame.buffer_mut();
    paint_panel(buf, summary_area, &i18n.header_stats);
    write_label_rows(
        buf,
        panel_inner(summary_area),
        [
            (&*i18n.label_total_notes, stats.total_memos.to_string()),
            (&*i18n.label_total_words, stats.total_words.to_string()),
            (&*i18n.label_active_days, stats.active_days.to_string()),
            (
                &*i18n.label_streak,
                format!(
                    "{} · {} {}",
                    stats.current_streak, i18n.label_longest, stats.longest_streak
                ),
            ),
        ],
    );
    paint_panel(buf, period_area, &i18n.header_cycle);
    write_label_rows(
        buf,
        panel_inner(period_area),
        [
            (
                i18n.text("This week: ", "本周: "),
                stats.this_week.to_string(),
            ),
            (
                i18n.text("This month: ", "本月: "),
                stats.this_month.to_string(),
            ),
            (
                i18n.text("This year: ", "今年: "),
                stats.this_year.to_string(),
            ),
        ],
    );
    draw_heatmap(buf, heatmap_area, stats, i18n);
}

/// Writes `text` at `(x, y)` one scalar at a time, returning the x position
/// after the last written cell. Equivalent to `Buffer::set_stringn` (zero-
/// width and control characters skipped, wide glyphs blank their trailing
/// cell, stops when the next glyph no longer fits) without the grapheme
/// segmentation pass — valid here because every caller writes fixed i18n
/// labels, which are plain BMP text with no combining marks. `limit` is the
/// exclusive end column — usually the right edge of the panel being painted.
fn put_str(buf: &mut Buffer, x: u16, y: u16, text: &str, limit: u16, style: Style) -> u16 {
    if y >= buf.area.bottom() {
        return x;
    }
    let right = buf.area.right().min(limit);
    let mut cx = x;
    for ch in text.chars() {
        if ch.is_control() {
            continue;
        }
        // ASCII scalars are all single-cell printable once controls are
        // filtered — the width table only runs for non-ASCII input.
        let width = if ch.is_ascii() {
            1
        } else {
            u16::try_from(UnicodeWidthChar::width(ch).unwrap_or(0)).unwrap_or(0)
        };
        if width == 0 {
            continue;
        }
        if right.saturating_sub(cx) < width {
            break;
        }
        let mut encoded = [0_u8; 4];
        if let Some(cell) = buf.cell_mut((cx, y)) {
            cell.set_symbol(ch.encode_utf8(&mut encoded))
                .set_style(style);
        }
        cx = cx.saturating_add(1);
        // Blank the cells a wide glyph covers, like `set_stringn` does.
        for _ in 1..width {
            if let Some(cell) = buf.cell_mut((cx, y)) {
                cell.reset();
            }
            cx = cx.saturating_add(1);
        }
    }
    cx
}

/// Rounded border + bold title — the same visual `Block` produces, painted
/// directly to skip the widget's per-cell plumbing on every frame.
fn paint_panel(buf: &mut Buffer, area: Rect, title: &str) {
    if area.width < 2 || area.height < 2 {
        return;
    }
    let top = area.y;
    let bottom = area.y + area.height - 1;
    let left = area.x;
    let right = area.x + area.width - 1;
    let mut set = |x: u16, y: u16, symbol: &str| {
        if let Some(cell) = buf.cell_mut((x, y)) {
            cell.set_symbol(symbol).set_style(BORDER_STYLE);
        }
    };
    for x in (left + 1)..right {
        set(x, top, "─");
        set(x, bottom, "─");
    }
    for y in (top + 1)..bottom {
        set(left, y, "│");
        set(right, y, "│");
    }
    set(left, top, "╭");
    set(right, top, "╮");
    set(left, bottom, "╰");
    set(right, bottom, "╯");
    let mut tx = put_str(buf, left + 1, top, " ", right, TITLE_STYLE);
    tx = put_str(buf, tx, top, title, right, TITLE_STYLE);
    put_str(buf, tx, top, " ", right, TITLE_STYLE);
}

/// The content rect inside a one-cell border, saturating like `Block::inner`.
const fn panel_inner(area: Rect) -> Rect {
    Rect {
        x: area.x.saturating_add(1),
        y: area.y.saturating_add(1),
        width: area.width.saturating_sub(2),
        height: area.height.saturating_sub(2),
    }
}

/// Writes `[gray label][value]` rows into `area` — the stats panels never
/// wrap, so a reflow widget would be pure overhead.
fn write_label_rows<'a>(
    buf: &mut Buffer,
    area: Rect,
    rows: impl IntoIterator<Item = (&'a str, String)>,
) {
    for (index, (label, value)) in rows.into_iter().enumerate() {
        let Ok(offset) = u16::try_from(index) else {
            break;
        };
        let y = area.y.saturating_add(offset);
        if y >= area.bottom() {
            break;
        }
        let x = put_str(buf, area.x, y, label, area.right(), BORDER_STYLE);
        put_str(buf, x, y, &value, area.right(), Style::default());
    }
}

fn draw_heatmap(buf: &mut Buffer, area: Rect, stats: &StatsView, i18n: &UiStrings) {
    paint_panel(buf, area, &i18n.header_heatmap);
    let inner = panel_inner(area);
    let Some(plan) = heatmap_plan(inner, stats) else {
        return;
    };
    // The ramp is resolved once per frame — per-cell painting shares it.
    let scale = heat_scale();
    paint_weekday_labels(buf, inner, start_y(inner), i18n);
    paint_heatmap_cells(buf, inner, stats, i18n, &plan, scale);
    paint_heatmap_legend(buf, inner, i18n, scale);
}

struct HeatmapPlan {
    today: CivilDate,
    start: CivilDate,
    max_weeks: usize,
    start_x: u16,
    start_y: u16,
}

fn heatmap_plan(inner: Rect, stats: &StatsView) -> Option<HeatmapPlan> {
    if inner.width < 8 || inner.height < 4 {
        return None;
    }
    let Ok(today) = CivilDate::new(stats.as_of_year, stats.as_of_month, stats.as_of_day) else {
        return None;
    };
    let start_x = inner.x.saturating_add(4);
    let start_y = start_y(inner);
    let available = inner.width.saturating_sub(6);
    let max_weeks = usize::from(available / 2);
    if max_weeks == 0 {
        return None;
    }
    let Ok(iso) = today.weekday() else {
        return None;
    };
    let sunday_index = usize::from(iso % 7);
    let days_back = (max_weeks - 1) * 7 + sunday_index;
    let Ok(days_back) = i64::try_from(days_back) else {
        return None;
    };
    let Ok(start) = today.checked_add_days(-days_back) else {
        return None;
    };
    Some(HeatmapPlan {
        today,
        start,
        max_weeks,
        start_x,
        start_y,
    })
}

const fn start_y(inner: Rect) -> u16 {
    inner.y.saturating_add(2)
}

fn paint_weekday_labels(buf: &mut Buffer, inner: Rect, start_y: u16, i18n: &UiStrings) {
    for (index, label) in i18n.heatmap_weekdays.iter().enumerate() {
        let label_y = start_y.saturating_add(u16::try_from(index).unwrap_or(0));
        if label_y < inner.bottom() {
            put_str(
                buf,
                inner.x.saturating_add(1),
                label_y,
                label,
                inner.right(),
                BORDER_STYLE,
            );
        }
    }
}

fn paint_heatmap_cells(
    buf: &mut Buffer,
    inner: Rect,
    stats: &StatsView,
    i18n: &UiStrings,
    plan: &HeatmapPlan,
    scale: &[Color; 5],
) {
    let counts = daily_map(stats);
    let mut cursor = (plan.start.year(), plan.start.month(), plan.start.day());
    let mut current_month = 0_u8;
    for col in 0..plan.max_weeks {
        let col_x = plan
            .start_x
            .saturating_add(u16::try_from(col).unwrap_or(0).saturating_mul(2));
        if cursor.1 != current_month {
            paint_month_label(buf, inner, col_x, cursor.1, i18n);
            current_month = cursor.1;
        }
        paint_week_column(buf, inner, plan, &counts, cursor, col_x, scale);
        // The next week is seven int steps — no calendar-engine round trip.
        for _ in 0..7 {
            cursor = next_day(cursor);
        }
    }
}

fn paint_month_label(buf: &mut Buffer, inner: Rect, col_x: u16, month: u8, i18n: &UiStrings) {
    let month_name = i18n
        .heatmap_months
        .get(usize::from(month.saturating_sub(1)))
        .copied()
        .unwrap_or("");
    if col_x.saturating_add(3) < inner.right() {
        put_str(
            buf,
            col_x,
            inner.y.saturating_add(1),
            month_name,
            inner.right(),
            BORDER_STYLE,
        );
    }
}

fn paint_week_column(
    buf: &mut Buffer,
    inner: Rect,
    plan: &HeatmapPlan,
    counts: &HashMap<(i32, u8, u8), u64>,
    week_start: (i32, u8, u8),
    col_x: u16,
    scale: &[Color; 5],
) {
    // The ~400 cells a heatmap walks step plain civil integers — the zone is
    // only needed to derive the endpoints, which `heatmap_plan` already did.
    let today = (plan.today.year(), plan.today.month(), plan.today.day());
    let mut day = week_start;
    for row in 0..7_u16 {
        if day > today {
            break;
        }
        let count = counts.get(&day).copied().unwrap_or(0);
        let row_y = plan.start_y.saturating_add(row);
        if col_x < inner.right()
            && row_y < inner.bottom()
            && let Some(cell) = buf.cell_mut((col_x, row_y))
        {
            cell.set_symbol("●").set_fg(heat_color(count, scale));
        }
        day = next_day(day);
    }
}

/// Proleptic Gregorian month length — the inputs are `CivilDate` components,
/// already validated at the projection boundary.
const fn days_in_month(year: i32, month: u8) -> u8 {
    match month {
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// The next civil day — integer stepping, not a calendar-engine round trip.
const fn next_day((year, month, day): (i32, u8, u8)) -> (i32, u8, u8) {
    if day < days_in_month(year, month) {
        (year, month, day + 1)
    } else if month < 12 {
        (year, month + 1, 1)
    } else {
        (year.saturating_add(1), 1, 1)
    }
}

const HEAT_SCALE: [Color; 5] = [
    Color::Rgb(60, 60, 60),
    Color::Rgb(163, 190, 140),
    Color::Rgb(235, 203, 139),
    Color::Rgb(208, 135, 112),
    Color::Rgb(191, 97, 106),
];

/// xterm-256 approximations of the tuned ramp — the same five steps resolved
/// into the 6×6×6 color cube and grayscale ramp (`#3a3a3a`, `#afaf87`,
/// `#d7d787`, `#d7875f`, `#af5f5f`), so a 256-color terminal sees the
/// intended progression instead of crossterm's uncontrolled downgrade.
const HEAT_SCALE_INDEXED: [Color; 5] = [
    Color::Indexed(237),
    Color::Indexed(144),
    Color::Indexed(186),
    Color::Indexed(173),
    Color::Indexed(131),
];

/// The floor for terminals without even the indexed palette: named ANSI
/// colors keeping the same gray → green → yellow → red → bright-red story.
const HEAT_SCALE_BASIC: [Color; 5] = [
    Color::DarkGray,
    Color::Green,
    Color::Yellow,
    Color::Red,
    Color::LightRed,
];

/// The ramp a terminal can express given its `COLORTERM`/`TERM` signals.
///
/// `COLORTERM=truecolor|24bit` or a `*-direct`/`truecolor`/`24bit` TERM keeps
/// the tuned scale; a `256color` TERM or any non-empty COLORTERM gets the
/// indexed approximations; anything quieter gets named colors — a cell never
/// asks for a color the terminal cannot name (C-15).
#[must_use]
pub fn heat_scale_for(colorterm: Option<&str>, term: Option<&str>) -> &'static [Color; 5] {
    let truecolor = colorterm.is_some_and(|value| {
        value.eq_ignore_ascii_case("truecolor") || value.eq_ignore_ascii_case("24bit")
    }) || term.is_some_and(|value| {
        ["direct", "truecolor", "24bit"]
            .iter()
            .any(|mark| value.contains(mark))
    });
    if truecolor {
        return &HEAT_SCALE;
    }
    let indexed = term.is_some_and(|value| value.contains("256color"))
        || colorterm.is_some_and(|value| !value.is_empty());
    if indexed {
        &HEAT_SCALE_INDEXED
    } else {
        &HEAT_SCALE_BASIC
    }
}

/// This session's ramp, probed once from the environment — the terminal's
/// color capability cannot change under a running frame.
fn heat_scale() -> &'static [Color; 5] {
    static SCALE: std::sync::OnceLock<&'static [Color; 5]> = std::sync::OnceLock::new();
    SCALE.get_or_init(|| {
        heat_scale_for(
            crate::xdg::env_nonempty("COLORTERM").as_deref(),
            crate::xdg::env_nonempty("TERM").as_deref(),
        )
    })
}

const fn heat_color(count: u64, scale: &[Color; 5]) -> Color {
    // Destructuring names the five steps instead of indexing them.
    let [empty, low, mid, high, top] = *scale;
    match count {
        0 => empty,
        1 => low,
        2..=3 => mid,
        4..=6 => high,
        _ => top,
    }
}

fn paint_heatmap_legend(buf: &mut Buffer, inner: Rect, i18n: &UiStrings, scale: &[Color; 5]) {
    if inner.height < HEATMAP_MIN_INNER_ROWS {
        return;
    }
    // A narrow panel pins the legend to its left edge instead of letting the
    // right-anchored offset escape the frame onto the screen margin.
    let legend_x = inner.right().saturating_sub(22).max(inner.x);
    let legend_y = inner.bottom().saturating_sub(1);
    put_str(
        buf,
        legend_x,
        legend_y,
        &i18n.heatmap_less,
        inner.right(),
        BORDER_STYLE,
    );
    let mut x = legend_x.saturating_add(5);
    for color in *scale {
        // A panel narrower than the legend drops trailing swatches rather
        // than writing them past the frame — the same bound put_str uses.
        if x >= inner.right() {
            break;
        }
        if let Some(cell) = buf.cell_mut((x, legend_y)) {
            cell.set_symbol("●");
            cell.set_fg(color);
        }
        x = x.saturating_add(2);
    }
    put_str(
        buf,
        x,
        legend_y,
        &i18n.heatmap_more,
        inner.right(),
        BORDER_STYLE,
    );
}

fn daily_map(stats: &StatsView) -> HashMap<(i32, u8, u8), u64> {
    stats
        .daily
        .iter()
        .map(|point| ((point.year, point.month, point.day), point.count))
        .collect()
}
