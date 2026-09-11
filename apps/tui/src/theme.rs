//! Think-compatible panel chrome: rounded borders and per-view colors.

use ratatui::style::{Color, Modifier, Style};
use ratatui::text::Span;
use ratatui::widgets::{Block, BorderType, Borders};

use crate::model::Screen;

/// Accent color for the active surface.
#[must_use]
pub const fn theme_color(screen: Screen, search_open: bool) -> Color {
    if search_open {
        return Color::Yellow;
    }
    match screen {
        Screen::Timeline | Screen::Statistics => Color::Cyan,
        Screen::Tasks | Screen::Settings => Color::Blue,
        Screen::Review => Color::Magenta,
        Screen::Attachments => Color::Green,
        Screen::Trash => Color::Yellow,
    }
}

/// Rounded titled panel used by Think's list, preview, and overlays.
#[must_use]
pub fn panel(title: String, color: Color) -> Block<'static> {
    Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(color))
        .title(Span::styled(
            title,
            Style::default().fg(color).add_modifier(Modifier::BOLD),
        ))
}

/// Selected-row style for memo lists.
#[must_use]
pub fn list_highlight() -> Style {
    Style::default()
        .bg(Color::DarkGray)
        .fg(Color::White)
        .add_modifier(Modifier::BOLD)
}

/// Selected-row style for the todo screen.
#[must_use]
pub fn todo_highlight() -> Style {
    Style::default()
        .bg(Color::Blue)
        .fg(Color::White)
        .add_modifier(Modifier::BOLD)
}
