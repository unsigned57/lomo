//! Statistics: overview, period counts and an activity heatmap in the reading column's chrome.

use std::collections::HashMap;

use lomo_application::calendar::{CalendarError, CivilDate, day_bounds, local_date};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, BorderType, Borders, Paragraph};

use crate::i18n::UiStrings;
use crate::model::StatsView;

const OVERVIEW_ROWS: u16 = 6;
/// Two header rows (month labels), seven weekday rows and one legend row.
const HEATMAP_MIN_INNER_ROWS: u16 = 10;

/// Draws the statistics screen into `area`.
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

    let summary = vec![
        labeled(&i18n.label_total_notes, stats.total_memos.to_string()),
        labeled(&i18n.label_total_words, stats.total_words.to_string()),
        labeled(&i18n.label_active_days, stats.active_days.to_string()),
        labeled(
            &i18n.label_streak,
            format!(
                "{} · {} {}",
                stats.current_streak, i18n.label_longest, stats.longest_streak
            ),
        ),
    ];
    frame.render_widget(
        Paragraph::new(summary).block(panel(&i18n.header_stats)),
        summary_area,
    );
    frame.render_widget(
        Paragraph::new(period_lines(stats, i18n)).block(panel(&i18n.header_cycle)),
        period_area,
    );
    draw_heatmap(frame, heatmap_area, stats, i18n);
}

fn period_lines(stats: &StatsView, i18n: &UiStrings) -> Vec<Line<'static>> {
    vec![
        labeled(
            i18n.text("This week: ", "本周: "),
            stats.this_week.to_string(),
        ),
        labeled(
            i18n.text("This month: ", "本月: "),
            stats.this_month.to_string(),
        ),
        labeled(
            i18n.text("This year: ", "今年: "),
            stats.this_year.to_string(),
        ),
    ]
}

fn labeled(label: &str, value: String) -> Line<'static> {
    Line::from(vec![
        Span::styled(label.to_owned(), Style::default().fg(Color::DarkGray)),
        Span::raw(value),
    ])
}

fn panel(title: &str) -> Block<'static> {
    Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(Color::DarkGray))
        .title(Span::styled(
            format!(" {title} "),
            Style::default().add_modifier(Modifier::BOLD),
        ))
}

fn draw_heatmap(frame: &mut Frame, area: Rect, stats: &StatsView, i18n: &UiStrings) {
    let block = panel(&i18n.header_heatmap);
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let Some(plan) = heatmap_plan(inner, stats) else {
        return;
    };
    paint_weekday_labels(frame, inner, start_y(inner), i18n);
    paint_heatmap_cells(frame, inner, stats, i18n, &plan);
    paint_heatmap_legend(frame, inner, i18n);
}

struct HeatmapPlan {
    zone: String,
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
    let Ok(start) = shift_days(today, &stats.zone, -days_back) else {
        return None;
    };
    Some(HeatmapPlan {
        zone: stats.zone.clone(),
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

fn paint_weekday_labels(frame: &mut Frame, inner: Rect, start_y: u16, i18n: &UiStrings) {
    for (index, label) in i18n.heatmap_weekdays.iter().enumerate() {
        let label_y = start_y.saturating_add(u16::try_from(index).unwrap_or(0));
        if label_y < inner.bottom() {
            frame.buffer_mut().set_string(
                inner.x.saturating_add(1),
                label_y,
                label,
                Style::default().fg(Color::DarkGray),
            );
        }
    }
}

fn paint_heatmap_cells(
    frame: &mut Frame,
    inner: Rect,
    stats: &StatsView,
    i18n: &UiStrings,
    plan: &HeatmapPlan,
) {
    let counts = daily_map(stats);
    let mut cursor = plan.start;
    let mut current_month = 0_u8;
    for col in 0..plan.max_weeks {
        let col_x = plan
            .start_x
            .saturating_add(u16::try_from(col).unwrap_or(0).saturating_mul(2));
        if cursor.month() != current_month {
            paint_month_label(frame, inner, col_x, cursor.month(), i18n);
            current_month = cursor.month();
        }
        paint_week_column(frame, inner, plan, &counts, cursor, col_x);
        let Ok(week) = shift_days(cursor, &plan.zone, 7) else {
            break;
        };
        cursor = week;
    }
}

fn paint_month_label(frame: &mut Frame, inner: Rect, col_x: u16, month: u8, i18n: &UiStrings) {
    let month_name = i18n
        .heatmap_months
        .get(usize::from(month.saturating_sub(1)))
        .copied()
        .unwrap_or("");
    if col_x.saturating_add(3) < inner.right() {
        frame.buffer_mut().set_string(
            col_x,
            inner.y.saturating_add(1),
            month_name,
            Style::default().fg(Color::DarkGray),
        );
    }
}

fn paint_week_column(
    frame: &mut Frame,
    inner: Rect,
    plan: &HeatmapPlan,
    counts: &HashMap<(i32, u8, u8), u64>,
    week_start: CivilDate,
    col_x: u16,
) {
    let mut day = week_start;
    for row in 0..7_u16 {
        if day > plan.today {
            break;
        }
        let count = counts
            .get(&(day.year(), day.month(), day.day()))
            .copied()
            .unwrap_or(0);
        let row_y = plan.start_y.saturating_add(row);
        if col_x < inner.right() && row_y < inner.bottom() {
            frame.buffer_mut().set_string(
                col_x,
                row_y,
                "●",
                Style::default().fg(heat_color(count)),
            );
        }
        let Ok(next) = shift_days(day, &plan.zone, 1) else {
            break;
        };
        day = next;
    }
}

const HEAT_SCALE: [Color; 5] = [
    Color::Rgb(60, 60, 60),
    Color::Rgb(163, 190, 140),
    Color::Rgb(235, 203, 139),
    Color::Rgb(208, 135, 112),
    Color::Rgb(191, 97, 106),
];

const fn heat_color(count: u64) -> Color {
    match count {
        0 => HEAT_SCALE[0],
        1 => HEAT_SCALE[1],
        2..=3 => HEAT_SCALE[2],
        4..=6 => HEAT_SCALE[3],
        _ => HEAT_SCALE[4],
    }
}

fn paint_heatmap_legend(frame: &mut Frame, inner: Rect, i18n: &UiStrings) {
    if inner.height < HEATMAP_MIN_INNER_ROWS {
        return;
    }
    let legend_x = inner.right().saturating_sub(22);
    let legend_y = inner.bottom().saturating_sub(1);
    frame.buffer_mut().set_string(
        legend_x,
        legend_y,
        &i18n.heatmap_less,
        Style::default().fg(Color::DarkGray),
    );
    let mut x = legend_x.saturating_add(5);
    for color in HEAT_SCALE {
        frame
            .buffer_mut()
            .set_string(x, legend_y, "●", Style::default().fg(color));
        x = x.saturating_add(2);
    }
    frame.buffer_mut().set_string(
        x,
        legend_y,
        &i18n.heatmap_more,
        Style::default().fg(Color::DarkGray),
    );
}

fn daily_map(stats: &StatsView) -> HashMap<(i32, u8, u8), u64> {
    stats
        .daily
        .iter()
        .map(|point| ((point.year, point.month, point.day), point.count))
        .collect()
}

fn shift_days(date: CivilDate, zone: &str, delta: i64) -> Result<CivilDate, CalendarError> {
    if delta == 0 {
        return Ok(date);
    }
    let mut current = date;
    if delta > 0 {
        for _ in 0..delta {
            let (_, end) = day_bounds(current, zone)?;
            current = local_date(end, zone)?;
        }
    } else {
        for _ in 0..(-delta) {
            let (start, _) = day_bounds(current, zone)?;
            current = local_date(start.saturating_sub(1), zone)?;
        }
    }
    Ok(current)
}
