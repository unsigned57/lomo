//! Small overlays and their shared mouse geometry.
use crate::i18n::UiStrings;
use crate::menu::MenuRow;
use crate::model::{AppModel, Confirmation, InputMode, PaletteItem, PaletteScope, PickerKind};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, BorderType, Borders, Clear, Paragraph},
};
use unicode_width::UnicodeWidthStr;

/// Pickers stay compact; reading overlays (help, history, notices) may use a taller box.
///
/// The rectangle never shrinks below one cell: a dialog that owns input must
/// keep *some* visible footprint — below 5 rows it degrades to a bare strip
/// (`draw_collapsed`) instead of vanishing while it can still accept `y`.
#[must_use]
pub fn overlay_area(model: &AppModel) -> Rect {
    let max_height = match model.input {
        InputMode::Help { .. } | InputMode::Message { .. } | InputMode::Setup(_) => 34,
        InputMode::Browse
        | InputMode::Compose
        | InputMode::Search { .. }
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Setting(_)
        | InputMode::Confirm(_) => 16,
    };
    let width = model.width.saturating_sub(4).clamp(1, 72);
    let height = model.height.saturating_sub(4).clamp(1, max_height);
    Rect::new(
        model.width.saturating_sub(width) / 2,
        model.height.saturating_sub(height) / 2,
        width,
        height,
    )
}

/// The rows a scrollable overlay's content actually shows at this size.
///
/// The framed inner height, or the bare strip's height once the box collapsed —
/// scroll clamps key on this, never on the padded dialog height (C-08).
#[must_use]
pub fn content_height(model: &AppModel) -> usize {
    let screen = Rect::new(0, 0, model.width, model.height);
    let inner = overlay_inner(model);
    if inner.is_empty() {
        usize::from(overlay_area(model).intersection(screen).height)
    } else {
        usize::from(inner.height)
    }
}

/// The width the overlay's text field actually renders at.
///
/// The framed inner column, or the bare strip's width once the box collapsed
/// (C-17). Vertical cursor moves must wrap by this width, never the screen
/// width, or the caret and the glyphs part company.
#[must_use]
pub fn field_width(model: &AppModel) -> u16 {
    let screen = Rect::new(0, 0, model.width, model.height);
    let inner = overlay_inner(model);
    if inner.is_empty() {
        // The collapsed form draws the bare field across the whole strip.
        return overlay_area(model).intersection(screen).width;
    }
    if matches!(model.input, InputMode::Setup(_)) {
        // Wizard rows put the value behind their `marker + key` label column —
        // the compact form gives the focused field the whole interior
        // instead (its drawn width, never a guess).
        if inner.height < 6 {
            inner.width
        } else {
            inner.width.saturating_sub(SETUP_LABEL_COLUMNS)
        }
    } else {
        inner.width
    }
}

/// The interior the overlay's bordered block exposes at this terminal size,
/// clipped to the screen. Empty means the box cannot hold one content row —
/// `draw` then degrades to a bare strip instead of an empty bordered shell.
fn overlay_inner(model: &AppModel) -> Rect {
    let screen = Rect::new(0, 0, model.width, model.height);
    let area = overlay_area(model).intersection(screen);
    Block::default()
        .borders(Borders::ALL)
        .inner(area)
        .intersection(screen)
}

pub fn draw(frame: &mut Frame, model: &AppModel, s: &UiStrings) {
    if matches!(
        model.input,
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. }
    ) {
        return;
    }
    let screen = frame.area();
    let area = overlay_area(model).intersection(screen);
    if area.is_empty() {
        // No usable rectangle: the overlay is skipped entirely rather than
        // writing any content outside the frame.
        return;
    }
    frame.render_widget(Clear, area);
    let block = Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(Color::DarkGray))
        .title(Span::styled(
            format!(" {} ", overlay_title(model, s)),
            Style::default().add_modifier(Modifier::BOLD),
        ));
    let inner = block.inner(area).intersection(screen);
    if inner.is_empty() {
        draw_collapsed(frame, model, area, s);
        return;
    }
    frame.render_widget(block, area);
    match &model.input {
        InputMode::Picker(picker) => draw_picker(frame, model, picker, inner, s),
        InputMode::Date { text, error, .. } => draw_date(frame, inner, text, error.as_deref(), s),
        InputMode::Setting(edit) => draw_setting(frame, inner, edit, s),
        InputMode::Setup(setup) => draw_setup(frame, inner, setup, s),
        InputMode::Confirm(confirmation) => draw_confirm(frame, inner, confirmation, s),
        InputMode::Message { lines, scroll, .. } => {
            let lines = lines
                .iter()
                .skip(visible_skip(
                    *scroll,
                    lines.len(),
                    usize::from(inner.height),
                ))
                .map(|line| Line::raw(line.clone()))
                .collect::<Vec<_>>();
            // `WordWrapper` leaks an unbreakable word past the right edge —
            // materialize the wrap with the shared grapheme wrapper (09-I6-01).
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(&lines, inner.width)),
                inner,
            );
        }
        InputMode::Help { scroll } => {
            let lines = help(s);
            let skip = visible_skip(*scroll, lines.len(), usize::from(inner.height));
            let lines = lines
                .into_iter()
                .skip(skip)
                .map(Line::raw)
                .collect::<Vec<_>>();
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(&lines, inner.width)),
                inner,
            );
        }
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. } => {}
    }
}

/// A stored scroll offset can outlive its bound — the message's lines are the
/// model's data and can shrink after the scroll was taken, and a collapsed
/// overlay keeps fewer rows than the offset once assumed. Rendering clamps a
/// stale offset to the last full page (`lines - visible`, the same bound
/// `input_update::bounded_scroll` writes) instead of skipping every line onto
/// a blank panel.
fn visible_skip(scroll: usize, lines: usize, visible: usize) -> usize {
    scroll.min(lines.saturating_sub(visible))
}

/// The borderless form for terminals too short to host the framed overlay:
/// the overlay's essential content drawn inside the cleared `area`, never
/// outside it. Interactive overlays show their input line; read-only overlays
/// show their scrolled lines.
fn draw_collapsed(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    match &model.input {
        // The filter stays reachable, but no entry row is visible — accept
        // refuses invisible entries (see `input_update::accept_picker`).
        InputMode::Picker(picker) => crate::ui::draw_field(
            frame,
            Rect::new(area.x, area.y, area.width, area.height.min(1)),
            &picker.text,
            Some(s.text("Type to filter…", "输入以筛选…")),
        ),
        InputMode::Date { text, .. } => {
            crate::ui::draw_field(
                frame,
                Rect::new(area.x, area.y, area.width, area.height.min(1)),
                text,
                Some(s.text("YYYY-MM-DD", "YYYY-MM-DD")),
            );
            if area.height > 1 {
                let prompt = s.text(
                    "date or range YYYY-MM-DD..YYYY-MM-DD",
                    "日期或范围 YYYY-MM-DD..YYYY-MM-DD",
                );
                frame.render_widget(
                    Paragraph::new(prompt).style(Style::default().fg(Color::DarkGray)),
                    Rect::new(
                        area.x,
                        area.y.saturating_add(1),
                        area.width,
                        area.height.saturating_sub(1),
                    ),
                );
            }
        }
        InputMode::Confirm(confirmation) => {
            let (prompt, target, keys) = confirm_parts(confirmation, s);
            let mut lines = vec![Line::raw(prompt)];
            if let Some(target) = target {
                lines.push(Line::raw(target));
            }
            lines.push(Line::styled(keys, Style::default().fg(Color::DarkGray)));
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(&lines, area.width)),
                area,
            );
        }
        InputMode::Message { lines, scroll, .. } => {
            let lines = lines
                .iter()
                .skip(visible_skip(*scroll, lines.len(), usize::from(area.height)))
                .map(|line| Line::raw(line.clone()))
                .collect::<Vec<_>>();
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(&lines, area.width)),
                area,
            );
        }
        InputMode::Help { scroll } => {
            let lines = help(s);
            let skip = visible_skip(*scroll, lines.len(), usize::from(area.height));
            let lines = lines
                .into_iter()
                .skip(skip)
                .map(Line::raw)
                .collect::<Vec<_>>();
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(&lines, area.width)),
                area,
            );
        }
        InputMode::Setting(edit) => crate::ui::draw_field(
            frame,
            Rect::new(area.x, area.y, area.width, area.height.min(1)),
            &edit.text,
            None,
        ),
        InputMode::Setup(setup) => crate::ui::draw_field(
            frame,
            Rect::new(area.x, area.y, area.width, area.height.min(1)),
            match setup.focus {
                crate::model::SetupFocus::Workspace => &setup.workspace,
                crate::model::SetupFocus::TimeZone => &setup.time_zone,
            },
            None,
        ),
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. } => {}
    }
}

/// The inline field edit: localized label and hint above the input row, the
/// parse error in red below it. Validation lives in `SettingsField::parse_edit`
/// — the overlay only displays what it reported.
fn draw_setting(
    frame: &mut Frame,
    inner: Rect,
    edit: &crate::settings::SettingsEdit,
    s: &UiStrings,
) {
    if inner.height < 4 {
        // Compact form — the full layout's field sits at `inner.y + 3`, so a
        // shorter interior would hide it while `Type` still edits: the field
        // takes the first row it owns keystrokes on, the verdict keeps the
        // rows below (`draw_date`'s precedent — 09-I6-06).
        crate::ui::draw_field(
            frame,
            Rect::new(inner.x, inner.y, inner.width, inner.height.min(1)),
            &edit.text,
            None,
        );
        if let Some(error) = edit.error.as_deref() {
            let region = Rect::new(
                inner.x,
                inner.y.saturating_add(1),
                inner.width,
                inner.height.saturating_sub(1),
            );
            if !region.is_empty() {
                frame.render_widget(
                    Paragraph::new(crate::text_layout::wrapped_lines(
                        &crate::text_layout::plain_lines(error),
                        region.width,
                    ))
                    .style(Style::default().fg(Color::Red)),
                    region,
                );
            }
        }
        return;
    }
    let label = crate::settings::field_label(edit.field, s);
    let hint = crate::settings::field_hint(edit.field, s);
    frame.render_widget(
        Paragraph::new(vec![
            Line::styled(label, Style::default().add_modifier(Modifier::BOLD)),
            Line::styled(hint, Style::default().fg(Color::DarkGray)),
        ]),
        Rect::new(inner.x, inner.y, inner.width, inner.height.min(2)),
    );
    let field = Rect::new(
        inner.x,
        inner.y.saturating_add(3),
        inner.width,
        inner.height.saturating_sub(3).min(1),
    )
    .intersection(inner);
    crate::ui::draw_field(frame, field, &edit.text, None);
    if let Some(error) = edit.error.as_deref() {
        let region = Rect::new(
            inner.x,
            inner.y.saturating_add(5),
            inner.width,
            inner.height.saturating_sub(5),
        )
        .intersection(inner);
        if !region.is_empty() {
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(
                    &crate::text_layout::plain_lines(error),
                    region.width,
                ))
                .style(Style::default().fg(Color::Red)),
                region,
            );
        }
    }
}

/// Columns the wizard's `marker + key` label takes before the field's value —
/// the cursor math and the collapsed-form width both key on it (C-17).
const SETUP_LABEL_COLUMNS: u16 = 13;

/// The wizard's compact form — intro + file + both field rows need ≥6 rows;
/// below that the intro would swallow the whole interior while the wizard
/// still edits. The focused field takes the row it owns keystrokes on (the
/// same field the collapsed strip draws — 09-I6-06).
fn draw_setup_compact(frame: &mut Frame, inner: Rect, setup: &crate::model::SetupState) {
    crate::ui::draw_field(
        frame,
        Rect::new(inner.x, inner.y, inner.width, inner.height.min(1)),
        match setup.focus {
            crate::model::SetupFocus::Workspace => &setup.workspace,
            crate::model::SetupFocus::TimeZone => &setup.time_zone,
        },
        None,
    );
    if let Some(error) = setup.error.as_deref() {
        let region = Rect::new(
            inner.x,
            inner.y.saturating_add(1),
            inner.width,
            inner.height.saturating_sub(1),
        );
        if !region.is_empty() {
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(
                    &crate::text_layout::plain_lines(error),
                    region.width,
                ))
                .style(Style::default().fg(Color::Red)),
                region,
            );
        }
    }
}

/// The first-run wizard: the proposal is shown in full — editable workspace
/// and time-zone fields plus the registry's other defaults read-only — and a
/// final line naming exactly what confirmation will create. Nothing on this
/// surface persists; only `Effect::SetupConfirmed` mints.
fn draw_setup(frame: &mut Frame, inner: Rect, setup: &crate::model::SetupState, s: &UiStrings) {
    if inner.height < 6 {
        draw_setup_compact(frame, inner, setup);
        return;
    }
    let (lines, field_row) = setup_lines(setup, s);
    // One wrap materializes the surface — the paragraph paints its lines and
    // the cursor reads its anchors, so no second row ledger can desync from
    // the wrap that actually drew (11-T-02).
    let wrapped = crate::text_layout::wrap_lines(&lines, inner.width);
    frame.render_widget(
        Paragraph::new(
            wrapped
                .iter()
                .map(|row| row.line.clone())
                .collect::<Vec<_>>(),
        ),
        inner,
    );
    draw_setup_cursor(frame, inner, setup, field_row, &wrapped);
}

/// The wizard's logical lines — greeting, minted file path, the two
/// editable fields, the read-only registry preview and the confirm hint —
/// plus the index of the first field line, which the cursor resolves into a
/// visual row through the wrapped product's `anchor.line` (11-T-02).
fn setup_lines(setup: &crate::model::SetupState, s: &UiStrings) -> (Vec<Line<'static>>, usize) {
    let mut lines: Vec<Line<'static>> = Vec::new();
    if setup.proposal.previously_initialized {
        // The initialized marker says this config was deleted after a
        // completed setup — the wizard names the recorded workspace instead
        // of greeting a fresh install.
        lines.push(Line::styled(
            s.text(
                "config.toml is missing but this app was already set up — recreating:",
                "config.toml 缺失，但此前已完成初始化——将重新创建：",
            ),
            Style::default().fg(Color::Yellow),
        ));
        if let Some(recorded) = setup.proposal.recorded_workspace.as_ref() {
            lines.push(Line::styled(
                format!(
                    "  {} {}",
                    s.text("previous workspace:", "原工作区："),
                    recorded.display()
                ),
                Style::default().fg(Color::Yellow),
            ));
        }
    } else {
        lines.push(Line::styled(
            s.text(
                "No config.toml yet — confirm to create it:",
                "尚无 config.toml——确认后将创建：",
            ),
            Style::default().fg(Color::DarkGray),
        ));
    }
    lines.push(Line::styled(
        format!("  {}", setup.file.display()),
        Style::default().fg(Color::DarkGray),
    ));
    lines.push(Line::default());
    // The field rows' index is *derived* — the cursor sits on whichever row
    // this build actually emitted, never a hardcoded offset a shifted intro
    // would desync (09-I6-06).
    let field_row = lines.len();
    // Field labels mirror the registry keys so the wizard reads like the file.
    for (focus, key) in [
        (
            crate::model::SetupFocus::Workspace,
            crate::config::SettingsField::Workspace,
        ),
        (
            crate::model::SetupFocus::TimeZone,
            crate::config::SettingsField::TimeZone,
        ),
    ] {
        let active = setup.focus == focus;
        let marker = if active { "▎" } else { " " };
        let style = if active {
            Style::default().add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::DarkGray)
        };
        let value = match focus {
            crate::model::SetupFocus::Workspace => setup.workspace.text(),
            crate::model::SetupFocus::TimeZone => setup.time_zone.text(),
        };
        lines.push(Line::from(vec![
            // `marker + space + key` occupies SETUP_LABEL_COLUMNS cells —
            // the same left column the read-only preview rows below land on.
            Span::styled(format!("{marker} {:<11}", key.key()), style),
            Span::styled(
                value.to_owned(),
                if active {
                    Style::default()
                } else {
                    Style::default().fg(Color::DarkGray)
                },
            ),
        ]));
    }
    lines.push(Line::default());
    lines.extend(setup_preview_lines(setup, s));
    lines.push(Line::styled(
        s.text(
            "Enter creates the config and workspace · Esc quits without creating anything",
            "Enter 创建配置与工作区 · Esc 不创建任何内容直接退出",
        ),
        Style::default().fg(Color::DarkGray),
    ));
    if setup.awaiting {
        lines.push(Line::styled(
            s.text("Creating…", "正在创建…"),
            Style::default().fg(Color::DarkGray),
        ));
    }
    if let Some(error) = setup.error.as_deref() {
        lines.push(Line::styled(
            error.to_owned(),
            Style::default().fg(Color::Red),
        ));
    }
    (lines, field_row)
}

/// The cursor sits on the focused field's insertion point, derived from the
/// wrapped product the paragraph just painted: `anchor.line` names which
/// logical line emitted each visual row, so wrapped intro or preview rows
/// can never desync it (11-T-02). `field_row` is the builder-emitted index
/// of the first field line — a logical anchor, not a visual offset.
fn draw_setup_cursor(
    frame: &mut Frame,
    inner: Rect,
    setup: &crate::model::SetupState,
    field_row: usize,
    wrapped: &[crate::text_layout::VisualLine],
) {
    let logical = field_row
        + match setup.focus {
            crate::model::SetupFocus::Workspace => 0,
            crate::model::SetupFocus::TimeZone => 1,
        };
    let Some(first_row) = wrapped.iter().position(|row| row.anchor.line == logical) else {
        return;
    };
    let (key, buffer) = match setup.focus {
        crate::model::SetupFocus::Workspace => {
            (crate::config::SettingsField::Workspace, &setup.workspace)
        }
        crate::model::SetupFocus::TimeZone => {
            (crate::config::SettingsField::TimeZone, &setup.time_zone)
        }
    };
    // `cursor_position` replays the field's whole drawn prefix — marker,
    // label and the value text before the caret — through the same
    // grapheme-step rule `wrap_lines` used, so a value that wraps carries
    // its caret onto the continuation row at the column the wrap drew, not
    // behind a label column that row never had.
    let (row_off, col) = crate::text_layout::cursor_position(
        &format!("▎ {:<11}{}", key.key(), buffer.before_cursor()),
        inner.width,
    );
    let Some(y) = inner
        .y
        .checked_add(u16::try_from(first_row + row_off).unwrap_or(u16::MAX))
    else {
        return;
    };
    if y >= inner.y.saturating_add(inner.height) {
        return;
    }
    let x = inner
        .x
        .saturating_add(u16::try_from(col).unwrap_or(u16::MAX))
        .min(inner.x.saturating_add(inner.width.saturating_sub(1)));
    frame.set_cursor_position((x, y));
}

/// The wizard's read-only tail: `media_dir` previews the workspace text the
/// user is editing (`<workspace>/media`, the minted default); `date_format`,
/// `editor` and `player` show the registry's platform defaults.
fn setup_preview_lines(setup: &crate::model::SetupState, s: &UiStrings) -> Vec<Line<'static>> {
    let media_preview = std::path::Path::new(setup.workspace.text().trim())
        .join("media")
        .display()
        .to_string();
    let editor_preview = s
        .text("(unset → $VISUAL/$EDITOR)", "（未设置 → $VISUAL/$EDITOR）")
        .to_owned();
    let mut lines = Vec::new();
    for (key, value) in [
        (crate::config::SettingsField::MediaDir, media_preview),
        (
            crate::config::SettingsField::DateFormat,
            lomo_application::calendar::DateFormat::default()
                .pattern()
                .to_owned(),
        ),
        (crate::config::SettingsField::Editor, editor_preview),
        (
            crate::config::SettingsField::Player,
            crate::config::default_player().join(" "),
        ),
    ] {
        lines.push(Line::from(vec![
            Span::styled(
                format!("  {:<11}", key.key()),
                Style::default().fg(Color::DarkGray),
            ),
            Span::styled(value, Style::default().fg(Color::DarkGray)),
        ]));
    }
    lines.push(Line::default());
    lines
}

fn draw_date(
    frame: &mut Frame,
    inner: Rect,
    text: &crate::input::TextBuffer,
    error: Option<&str>,
    s: &UiStrings,
) {
    if inner.height < 6 {
        // Compact form: the full layout needs prompt + field + error (≥6
        // rows). Below that a framed box would still swallow typed input and
        // rejections invisibly — a dialog must never take a date it cannot
        // show or refuse Enter without saying why. Field first, error below.
        crate::ui::draw_field(
            frame,
            Rect::new(inner.x, inner.y, inner.width, inner.height.min(1)),
            text,
            Some(s.text("YYYY-MM-DD", "YYYY-MM-DD")),
        );
        if let Some(error) = error {
            let region = Rect::new(
                inner.x,
                inner.y.saturating_add(1),
                inner.width,
                inner.height.saturating_sub(1),
            );
            if !region.is_empty() {
                frame.render_widget(
                    Paragraph::new(crate::text_layout::wrapped_lines(
                        &crate::text_layout::plain_lines(error),
                        region.width,
                    ))
                    .style(Style::default().fg(Color::Red)),
                    region,
                );
            }
        }
        return;
    }
    let prompt = s.text(
        "YYYY-MM-DD, or a range YYYY-MM-DD..YYYY-MM-DD\nPresets: today · yesterday · week · month",
        "YYYY-MM-DD，或范围 YYYY-MM-DD..YYYY-MM-DD\n快捷范围：today · yesterday · week · month",
    );
    frame.render_widget(
        Paragraph::new(prompt).style(Style::default().fg(Color::DarkGray)),
        inner,
    );
    // Field and error rows are fixed offsets inside the box; intersecting with
    // `inner` drops them cleanly when the box is too short to hold them.
    let field = Rect::new(
        inner.x,
        inner.y.saturating_add(3),
        inner.width,
        inner.height.saturating_sub(3).min(1),
    )
    .intersection(inner);
    crate::ui::draw_field(frame, field, text, None);
    if let Some(error) = error {
        let region = Rect::new(
            inner.x,
            inner.y.saturating_add(5),
            inner.width,
            inner.height.saturating_sub(5),
        )
        .intersection(inner);
        if !region.is_empty() {
            frame.render_widget(
                Paragraph::new(crate::text_layout::wrapped_lines(
                    &crate::text_layout::plain_lines(error),
                    region.width,
                ))
                .style(Style::default().fg(Color::Red)),
                region,
            );
        }
    }
}

/// The dialog's question, its target line and the key hint, shared by the
/// framed and collapsed forms. The target is what `y` destroys: the memo's
/// stamp and first line, the sweep's count, the revision's number — a
/// destructive ask that cannot name its object is not honest (I9).
fn confirm_parts(
    confirmation: &Confirmation,
    s: &UiStrings,
) -> (String, Option<String>, &'static str) {
    let prompt = match confirmation {
        Confirmation::Delete { .. } => s.text(
            "Move this memo to the trash? It can be restored from there.",
            "将这条记录移入回收站？之后可以从回收站恢复。",
        ),
        Confirmation::DeleteForever(_) => s.text(
            "Delete this memo permanently? This cannot be undone.",
            "永久删除这条记录？此操作无法撤销。",
        ),
        Confirmation::EmptyTrash { .. } => s.text(
            "Delete every memo in the trash permanently? This cannot be undone.",
            "永久删除回收站里的全部记录？此操作无法撤销。",
        ),
        Confirmation::Restore(_) => s.text(
            "Restore this memo to the timeline?",
            "将这条记录恢复到时间线？",
        ),
        Confirmation::RestoreRevision { .. } => s.text(
            "Replace the current body with this revision? The current body stays in history.",
            "用这个版本替换当前正文？当前正文会保留在历史里。",
        ),
        Confirmation::DiscardDraft { .. } => s.text(
            "Discard the capture draft? This cannot be undone.",
            "丢弃速记草稿？此操作无法撤销。",
        ),
    }
    .to_owned();
    let target = confirm_target(confirmation, s);
    let keys = s.text(
        "y / Enter confirm   ·   n / Esc cancel",
        "y / Enter 确认   ·   n / Esc 取消",
    );
    (prompt, target, keys)
}

/// What the confirmation destroys — carried by the payload, clipped by the
/// frame width at draw time, never re-derived from live state.
fn confirm_target(confirmation: &Confirmation, s: &UiStrings) -> Option<String> {
    match confirmation {
        Confirmation::Delete { memo }
        | Confirmation::DeleteForever(memo)
        | Confirmation::Restore(memo) => {
            let when = format!("{} {}", memo.date, memo.time);
            let preview = memo.summary.lines().next().unwrap_or("").trim();
            Some(if preview.is_empty() {
                when
            } else {
                format!("{when} · {preview}")
            })
        }
        Confirmation::RestoreRevision {
            revision,
            stamp,
            preview,
            ..
        } => {
            let mut target = format!("r{revision}");
            for part in [stamp.as_str(), preview.as_str()] {
                if !part.is_empty() {
                    target.push_str(" · ");
                    target.push_str(part);
                }
            }
            Some(target)
        }
        Confirmation::EmptyTrash { count } => count.map(|count| match s.language {
            crate::i18n::UiLanguage::ChineseSimplified => {
                format!("回收站现有 {count} 条记录")
            }
            crate::i18n::UiLanguage::English => format!("Trash now holds {count} memos"),
        }),
        Confirmation::DiscardDraft { preview } => {
            (!preview.is_empty()).then(|| format!("{} {preview}", s.text("Draft:", "草稿：")))
        }
    }
}

fn draw_confirm(frame: &mut Frame, inner: Rect, confirmation: &Confirmation, s: &UiStrings) {
    let (prompt, target, keys) = confirm_parts(confirmation, s);
    let mut lines = vec![Line::raw(prompt)];
    if let Some(target) = target {
        lines.push(Line::styled(target, Style::default().fg(Color::Yellow)));
    }
    lines.push(Line::default());
    lines.push(Line::styled(keys, Style::default().fg(Color::DarkGray)));
    frame.render_widget(
        Paragraph::new(crate::text_layout::wrapped_lines(&lines, inner.width)),
        inner,
    );
}

const fn overlay_title<'a>(model: &'a AppModel, s: &UiStrings) -> &'a str {
    match &model.input {
        InputMode::Picker(picker) => match &picker.kind {
            PickerKind::Palette {
                scope: PaletteScope::All,
                ..
            }
            | PickerKind::Palette {
                item: PaletteItem::None,
                ..
            } => s.text("Commands", "命令"),
            PickerKind::Palette {
                item: PaletteItem::Memo(_),
                ..
            } => s.text("Memo actions", "记录操作"),
            PickerKind::Palette {
                item: PaletteItem::Task(_),
                ..
            } => s.text("Todo actions", "待办操作"),
            PickerKind::Palette {
                item: PaletteItem::Attachment(_),
                ..
            } => s.text("Attachment actions", "附件操作"),
            PickerKind::Tags(_) => s.text("Filter by tag", "按标签筛选"),
            PickerKind::Dates => s.text("Filter by date", "按日期筛选"),
            PickerKind::Attachments(_) => s.text("Attachments", "附件"),
            PickerKind::History { .. } => {
                s.text("History · Enter restores", "版本历史 · Enter 恢复")
            }
        },
        InputMode::Date { .. } => s.text("Custom date range", "自定义日期范围"),
        InputMode::Confirm(_) => s.text("Confirm", "确认"),
        InputMode::Setting(edit) => edit.field.key(),
        InputMode::Setup(_) => s.text("First-run setup", "首次运行设置"),
        InputMode::Message { title, .. } => title.as_str(),
        InputMode::Help { .. } => s.text("Help", "帮助"),
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. } => "",
    }
}

#[must_use]
pub fn help(s: &UiStrings) -> Vec<String> {
    // Every line must survive the narrowest framed overlay (38 cells at a
    // 44-column terminal): hints are short labels, not prose — anything
    // longer wraps mid-hint and becomes unreadable there.
    [
        s.text("Navigate", "导航"),
        s.text("j/k ↑↓    select / scroll", "j/k ↑↓    选择／滚动"),
        s.text("Ctrl+D/U  page down / up", "Ctrl+D/U  向下／向上翻页"),
        s.text("g / G     first / last", "g / G     开头／末尾"),
        s.text(
            "Enter     read / choose / toggle",
            "Enter     阅读／选择／切换",
        ),
        s.text("Esc       one layer back", "Esc       退回一层"),
        "",
        s.text("Write", "输入"),
        s.text("n         quick capture", "n         随手记录"),
        s.text("Ctrl+S    save capture", "Ctrl+S    保存速记"),
        s.text("Tab       complete #tag", "Tab       补全 #标签"),
        s.text("Ctrl+E    external editor", "Ctrl+E    外部编辑器"),
        "",
        s.text("Find", "查找"),
        s.text("/         search", "/         搜索"),
        s.text(
            "Ctrl+F    fulltext / fuzzy + pinyin",
            "Ctrl+F    全文／模糊·拼音",
        ),
        "",
        s.text("Actions", "动作"),
        s.text("e         edit in editor", "e         外部编辑器修改"),
        s.text("m         pin / unpin", "m         置顶／取消置顶"),
        s.text(
            "d         trash / delete forever",
            "d         回收站／永久删除",
        ),
        s.text("F5        refresh workspace", "F5        刷新工作区"),
        "",
        s.text("Palette", "面板"),
        s.text(":         commands for everything", ":         全部命令"),
        s.text(".         actions for this item", ".         仅当前项操作"),
        s.text(
            "          tags/dates/history/trash",
            "          标签·日期·历史·回收站",
        ),
        "",
        s.text("Mouse", "鼠标"),
        s.text(
            "click     select · wheel scroll",
            "点击      选择 · 滚轮滚动",
        ),
        s.text("Shift+drag  select text", "Shift＋拖动 终端选字"),
        s.text("?         this help", "?         本帮助"),
        s.text("q         quit", "q         退出"),
    ]
    .into_iter()
    .map(str::to_owned)
    .collect()
}

/// The region entries are drawn in.
///
/// Inside the overlay border, below the one-row filter field. A zero-height
/// result means no entry can be visible — `input_update::accept_picker` and
/// `click_input` refuse to act on one.
#[must_use]
pub fn picker_area(model: &AppModel) -> Rect {
    picker_list(overlay_inner(model))
}

/// The list region inside this picker interior: the filter owns row 0.
const fn picker_list(inner: Rect) -> Rect {
    Rect::new(
        inner.x,
        inner.y.saturating_add(1),
        inner.width,
        inner.height.saturating_sub(1),
    )
}

#[must_use]
pub fn picker_top(selected: usize, length: usize, height: u16) -> usize {
    // One selection-following rule for every row list — see
    // `layout::selection_top`; the picker already shared its shape.
    crate::layout::selection_top(selected, length, height)
}

fn draw_picker(
    frame: &mut Frame,
    model: &AppModel,
    picker: &crate::model::Picker,
    inner: Rect,
    s: &UiStrings,
) {
    crate::ui::draw_field(
        frame,
        Rect::new(inner.x, inner.y, inner.width, inner.height.min(1)),
        &picker.text,
        Some(s.text("Type to filter…", "输入以筛选…")),
    );
    let area = picker_list(inner);
    let rows = crate::menu::rows(model, picker);
    if rows.is_empty() {
        let text = match &picker.kind {
            PickerKind::Attachments(_) if picker.text.text().is_empty() => {
                s.text("This memo has no attachments", "这条记录没有附件")
            }
            PickerKind::Tags(_) if picker.text.text().is_empty() => {
                s.text("No tags yet", "还没有标签")
            }
            PickerKind::History { .. } if picker.text.text().is_empty() => {
                s.text("No earlier revisions", "没有更早的版本")
            }
            PickerKind::Palette { .. }
            | PickerKind::Tags(_)
            | PickerKind::Dates
            | PickerKind::Attachments(_)
            | PickerKind::History { .. } => s.text("No matches", "没有匹配项"),
        };
        frame.render_widget(
            Paragraph::new(format!("  {text}")).style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    let selected = crate::menu::selected_row(&rows, picker);
    let top = picker_top(selected, rows.len(), area.height);
    let lines: Vec<_> =
        rows.iter()
            .enumerate()
            .skip(top)
            .take(usize::from(area.height))
            .map(|(index, row)| match row {
                MenuRow::Header(title) => Line::styled(
                    format!("  {title}"),
                    Style::default()
                        .fg(Color::DarkGray)
                        .add_modifier(Modifier::BOLD),
                ),
                // A tag row carries only the shared name — the `{marker} #{name}`
                // projection allocates one string, with no key-column padding span.
                MenuRow::Tag(tag) => Line::styled(
                    format!("{} #{tag}", if index == selected { "▎" } else { " " }),
                    Style::default().fg(if index == selected {
                        Color::Cyan
                    } else {
                        Color::Reset
                    }),
                ),
                MenuRow::Entry(entry) => {
                    let active = index == selected;
                    let label_width = UnicodeWidthStr::width(entry.label.as_str());
                    let (marker, tail) = match &entry.availability {
                        crate::event::Availability::Ready => {
                            // Ready rows advertise their firing key — read off the
                            // same binding table the dispatcher consults.
                            (if active { "▎" } else { " " }, entry.key.unwrap_or(""))
                        }
                        crate::event::Availability::Refused(reason) => {
                            // A refused row stays visible and names its reason —
                            // the same text Enter would put on the status line.
                            (if active { "▎" } else { " " }, reason.text())
                        }
                        crate::event::Availability::Hidden => {
                            unreachable!("hidden commands never become menu rows")
                        }
                    };
                    let pad = usize::from(area.width)
                        .saturating_sub(2 + label_width + UnicodeWidthStr::width(tail) + 1)
                        .max(1);
                    let (label_color, tail_color) = match &entry.availability {
                        crate::event::Availability::Ready => (
                            if active { Color::Cyan } else { Color::Reset },
                            Color::DarkGray,
                        ),
                        crate::event::Availability::Refused(_)
                        | crate::event::Availability::Hidden => (Color::DarkGray, Color::DarkGray),
                    };
                    Line::from(vec![
                        Span::styled(
                            format!("{marker} {}", entry.label),
                            Style::default().fg(label_color),
                        ),
                        Span::styled(
                            format!("{}{tail}", " ".repeat(pad)),
                            Style::default()
                                .fg(tail_color)
                                .add_modifier(Modifier::ITALIC),
                        ),
                    ])
                }
            })
            .collect();
    frame.render_widget(Paragraph::new(lines), area);
}
