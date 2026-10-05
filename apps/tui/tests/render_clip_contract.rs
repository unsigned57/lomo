//! adversarial re-audit: the repaired rendering clipping contract (I6) and
//! picker highlight ↔ Enter execution consistency (C-02, under I2).
//!
//! This file attacks `audit/08-TUI对抗性审计修复记录.md`'s claims beyond the
//! repaired matrix — widths 0/2/3/5/6/7, zero-height frames, duplicate-command
//! rows, tag-dictionary churn under an open picker, feed/reader/message scroll
//! overruns, and every direct-buffer-write path (`set_stringn`, `cell_mut`,
//! per-cell writes in `ui.rs` and `stats_draw.rs`).
//!
//! # Behavior Contract
//!
//! - I6: no draw pass may write a cell outside the reading column every
//!   surface renders inside (`layout::reading_layout` centers it at
//!   `min(width - 4, 96)`); overlay output must stay inside
//!   `overlays::overlay_area`, clipped to the frame.
//! - I2/C-02: the row the `▎` mark lights up is exactly the entry
//!   `input_update::accept_picker` executes — `menu::selected_row`/`entry_at`
//!   and `Picker::entry_index` share one resolution rule under list rebuilds,
//!   filtering, scope toggles and identity loss.
//! - An input mode that owns keystrokes must keep a visible footprint of the
//!   field it edits (the picker's collapsed strip is the precedent).
//! - Scroll offsets clamp to `lines - visible` on every scrolling surface —
//!   feed, reader, message, help — so a page is always full or the content
//!   genuinely ends (the C-08/C-09 family).
//!
//! Scope excludes: TEA async semantics, incremental projection, derived-cache
//! performance (sibling reviews), C-03 confirmation wording and C-16 notice
//! semantics (geometry only here).
//!
//! Every assertion states the correct contract; a RED failure is defect
//! evidence reported in `audit/09-复审-渲染裁剪与Picker.md` — nothing here is
//! a "does not panic" placeholder.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures must be constructed successfully before probing; \
              a failed expectation is itself audit evidence"
)]
mod tests {
    use super::support::{feed, feed_mut, memo, model_with_memos};
    use lomo_tui::{
        effects::Effect,
        event::{Availability, Command, Refusal},
        feed_layout::{feed_window, memo_lines},
        graphics::{ImageRequest, ImageState, ReaderImage, SharedPicker, prepare_image},
        input::TextBuffer,
        input_update,
        layout::ReadingLayout,
        menu::{self, MenuEntry, MenuRow, PaletteGroup},
        model::{
            AppModel, AttachmentRow, BadgeClass, BodyState, CardPosition, Confirmation, HeatPoint,
            InputMode, LoadStatus, MemoAnchor, PaletteItem, PaletteScope, Picker, PickerKind, Req,
            RevisionRow, SelectionList, SetupState, Severity, StatsView, TaskRow, TextAnchor, View,
        },
        overlays, reader,
        settings::{SettingRow, SettingsEdit, SettingsView},
        stats_draw::heat_scale_for,
        text_layout::{cursor_position, plain_lines, wrap_lines},
        update::apply_command,
    };
    use ratatui::{
        Terminal,
        backend::{Backend, TestBackend},
        buffer::Buffer,
        layout::{Position, Rect},
        style::Color,
    };
    use std::{io::Cursor, path::PathBuf, sync::Arc};

    // ---------- frame-level helpers ----------

    fn draw(model: &AppModel) -> Terminal<TestBackend> {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
            .expect("fixture backend must build");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .expect("frame must draw");
        terminal
    }

    fn rendered(model: &AppModel) -> String {
        let terminal = draw(model);
        buffer_text(terminal.backend().buffer())
    }

    fn buffer_text(buffer: &Buffer) -> String {
        let mut out = String::new();
        for y in buffer.area.top()..buffer.area.bottom() {
            for x in buffer.area.left()..buffer.area.right() {
                out.push_str(buffer[(x, y)].symbol());
            }
            out.push('\n');
        }
        out
    }

    fn text_in(buffer: &Buffer, area: Rect) -> String {
        let clipped = area.intersection(buffer.area);
        let mut out = String::new();
        for y in clipped.top()..clipped.bottom() {
            for x in clipped.left()..clipped.right() {
                out.push_str(buffer[(x, y)].symbol());
            }
            out.push('\n');
        }
        out
    }

    fn layout(model: &AppModel) -> ReadingLayout {
        lomo_tui::ui::layout_for(model)
    }

    /// The full-height reading column every surface is constrained to.
    fn reading_column(model: &AppModel) -> Rect {
        let area = Rect::new(0, 0, model.width, model.height);
        let width = area
            .width
            .saturating_sub(4)
            .min(lomo_tui::layout::READING_COLUMNS);
        Rect::new(area.width.saturating_sub(width) / 2, 0, width, area.height)
    }

    /// Every buffer cell outside all `allowed` rects whose symbol is not
    /// blank — the I6 violation list for one frame.
    fn outside_writes(allowed: &[Rect], buffer: &Buffer) -> Vec<String> {
        let mut offenders = Vec::new();
        for y in buffer.area.top()..buffer.area.bottom() {
            for x in buffer.area.left()..buffer.area.right() {
                let cell = &buffer[(x, y)];
                let symbol = cell.symbol();
                if symbol != " "
                    && !allowed
                        .iter()
                        .any(|rect| rect.contains(Position::new(x, y)))
                {
                    offenders.push(format!(
                        "({x},{y})={symbol:?} fg={:?} bg={:?}",
                        cell.fg, cell.bg
                    ));
                }
            }
        }
        offenders
    }

    /// Cells inside the overlay's clipped rect — where overlay content may live.
    fn overlay_clip(model: &AppModel) -> Rect {
        let screen = Rect::new(0, 0, model.width, model.height);
        overlays::overlay_area(model).intersection(screen)
    }

    // ---------- model fixtures ----------

    /// Consume a dispatch's effect — `Option<Effect>` is `#[must_use]` but the
    /// probes only care about the model's resulting state.
    fn sink(_: Option<Effect>) {}

    fn feed_model(width: u16, height: u16) -> AppModel {
        model_with_memos(6, width, height).expect("feed fixture")
    }

    fn stats_view() -> StatsView {
        StatsView {
            zone: "UTC".to_owned(),
            as_of_year: 2026,
            as_of_month: 9,
            as_of_day: 22,
            total_memos: 12,
            total_words: 300,
            active_days: 4,
            current_streak: 2,
            longest_streak: 5,
            this_week: 1,
            this_month: 3,
            this_year: 12,
            daily: vec![
                HeatPoint {
                    year: 2026,
                    month: 9,
                    day: 20,
                    count: 1,
                },
                HeatPoint {
                    year: 2026,
                    month: 9,
                    day: 21,
                    count: 3,
                },
                HeatPoint {
                    year: 2026,
                    month: 9,
                    day: 22,
                    count: 9,
                },
            ],
        }
    }

    fn settings_view() -> SettingsView {
        SettingsView {
            file: PathBuf::from("/cfg/lomo/config.toml"),
            rows: vec![
                SettingRow {
                    field: lomo_tui::config::SettingsField::Workspace,
                    value: "/工作区/一个相当长的路径名称/一个相当长的路径名称/一个相当长的路径名称"
                        .to_owned(),
                    hot: false,
                },
                SettingRow {
                    field: lomo_tui::config::SettingsField::Editor,
                    value: "hx --wait".to_owned(),
                    hot: true,
                },
            ],
            selected: 0,
            info: vec!["device lomo-test".to_owned()],
            home_dir: None,
        }
    }

    fn setup_model(width: u16, height: u16) -> AppModel {
        let mut model = feed_model(width, height);
        model.input = InputMode::Setup(SetupState::new(
            PathBuf::from("/cfg/lomo/config.toml"),
            lomo_tui::config::ConfigProposal {
                workspace: PathBuf::from("/home/u/notes-哨兵"),
                time_zone: "Asia/Shanghai".to_owned(),
                previously_initialized: false,
                recorded_workspace: None,
            },
            Req(0),
            Some(PathBuf::from("/home/u")),
        ));
        model
    }

    fn picker_model(width: u16, height: u16, kind: PickerKind) -> AppModel {
        let mut model = feed_model(width, height);
        let mut picker = Picker {
            kind,
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        picker.rebind(&menu::entries(&model, &picker));
        model.input = InputMode::Picker(picker);
        model
    }

    fn dates_picker(width: u16, height: u16) -> AppModel {
        picker_model(width, height, PickerKind::Dates)
    }

    fn tags_picker(width: u16, height: u16, tags: &[&str]) -> AppModel {
        let mut model = feed_model(width, height);
        model.set_tags(tags.iter().map(|t| (*t).to_owned()).collect());
        assert!(
            matches!(
                apply_command(&mut model, Command::Tags),
                Some(Effect::Tags { .. })
            ),
            "opening the tags picker issues the dictionary refresh"
        );
        model
    }

    /// One `MenuEntry` for fabricated menus — `menu::row`/`keyed` are private.
    fn menu_entry(label: &str, command: Command, availability: Availability) -> MenuEntry {
        MenuEntry {
            label: label.to_owned(),
            command,
            group: PaletteGroup::Global,
            key: None,
            availability,
        }
    }

    /// The C-02 core invariant: the entry under the drawn mark is the entry
    /// Enter would execute. `None == None` is a consistent empty picker.
    fn assert_mark_matches_enter(model: &AppModel, context: &str) {
        let InputMode::Picker(picker) = &model.input else {
            panic!("{context}: the picker is closed — the probe requires it open");
        };
        let rows = menu::rows(model, picker);
        let entries = menu::entries(model, picker);
        let mark_row = menu::selected_row(&rows, picker);
        let marked = menu::entry_at(&rows, mark_row);
        let executed = picker.entry_index(&entries);
        assert_eq!(
            marked,
            executed,
            "{context}: the ▎ mark names entry {marked:?} but Enter runs {executed:?} \
             ({:?}) — highlight and execution diverged",
            executed
                .and_then(|index| entries.get(index))
                .map(|e| &e.command),
        );
        if let Some(index) = marked {
            let row = rows.get(mark_row).expect("marked row must exist");
            assert!(
                matches!(row, MenuRow::Entry(_) | MenuRow::Tag(_)),
                "{context}: the mark sits on a non-selectable row {row:?}"
            );
            // The same command the mark names must be what Enter dispatches.
            let marked_entry = entries.get(index).expect("marked entry must exist");
            let executed_entry = entries
                .get(executed.expect("marked Some implies executed Some"))
                .expect("executed entry must exist");
            assert_eq!(
                marked_entry.command, executed_entry.command,
                "{context}: mark command vs Enter command disagree"
            );
        }
    }

    /// The row of `▎` the pixels actually show inside the picker's list area —
    /// the end of the chain the contract locks: buffer ↔ display row ↔ entry.
    fn drawn_mark_entry(model: &AppModel) -> Option<usize> {
        let InputMode::Picker(picker) = &model.input else {
            panic!("picker must be open");
        };
        let area = overlays::picker_area(model);
        if area.height == 0 {
            return None;
        }
        let rows = menu::rows(model, picker);
        let selected = menu::selected_row(&rows, picker);
        let top = overlays::picker_top(selected, rows.len(), area.height);
        let terminal = draw(model);
        let buffer = terminal.backend().buffer();
        let dy = (area.top()..area.bottom())
            .find(|y| (area.left()..area.right()).any(|x| buffer[(x, *y)].symbol() == "▎"))?;
        menu::entry_at(&rows, top + usize::from(dy - area.y))
    }

    // ---------- I6: the frame/column clipping contract ----------

    /// Every surface writes only inside the reading column — the one invariant
    /// all direct writes (`set_stringn`, `cell_mut`, per-cell heatmap paints)
    /// and every widget must obey. Overlays legitimately span the full screen
    /// (`overlay_area` clamps to `width - 2`, not the column), so overlay
    /// inputs allow `column ∪ overlay_clip`; in-browser surfaces allow the
    /// column only.
    #[test]
    fn nothing_writes_outside_the_reading_column() {
        let widths = [
            0_u16, 1, 2, 3, 5, 6, 7, 9, 12, 20, 24, 26, 32, 44, 60, 80, 96, 120,
        ];
        let heights = [0_u16, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 16, 20, 24];
        let mut frames = 0usize;
        for width in widths {
            for height in heights {
                for (name, model) in sweep_scenarios(width, height) {
                    let terminal =
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| draw(&model)))
                            .unwrap_or_else(|_| panic!("{name} panicked at {width}x{height}"));
                    let column = reading_column(&model);
                    let overlay_surface = matches!(
                        model.input,
                        InputMode::Picker(_)
                            | InputMode::Date { .. }
                            | InputMode::Setting(_)
                            | InputMode::Setup(_)
                            | InputMode::Confirm(_)
                            | InputMode::Message { .. }
                            | InputMode::Help { .. }
                    );
                    let allowed = if overlay_surface {
                        vec![column, overlay_clip(&model)]
                    } else {
                        vec![column]
                    };
                    let offenders = outside_writes(&allowed, terminal.backend().buffer());
                    assert!(
                        offenders.is_empty(),
                        "{name} at {width}x{height} wrote outside {allowed:?}: {offenders:?}\n{frame_dump}",
                        frame_dump = buffer_text(terminal.backend().buffer()),
                    );
                    frames += 1;
                }
            }
        }
        assert!(
            frames > 2_000,
            "the sweep must be exhaustive: {frames} frames"
        );
    }

    /// Widths below the reading column's floor draw nothing at all — a zero-width
    /// column must not accumulate stray marks (`▎` at x=2 while `area.width=0`).
    #[test]
    fn sub_column_widths_stay_blank() {
        for width in [0_u16, 1, 2, 3, 4] {
            for height in [0_u16, 1, 2, 6, 12, 24] {
                let model = feed_model(width, height);
                let terminal =
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| draw(&model)))
                        .unwrap_or_else(|_| panic!("feed panicked at {width}x{height}"));
                let buffer = terminal.backend().buffer();
                let written: Vec<String> = (buffer.area.top()..buffer.area.bottom())
                    .flat_map(|y| (buffer.area.left()..buffer.area.right()).map(move |x| (x, y)))
                    .filter(|&(x, y)| buffer[(x, y)].symbol() != " ")
                    .map(|(x, y)| format!("({x},{y})={:?}", buffer[(x, y)].symbol()))
                    .collect();
                assert!(
                    written.is_empty(),
                    "a {width}x{height} frame cannot hold a column; nothing may draw: {written:?}"
                );
            }
        }
    }

    /// The overlay must clear its own rect and keep every border glyph inside
    /// it — a feed sentinel under the overlay proves the clear; stray `╭│` in
    /// margins is how C-06's unboxed glyphs originally escaped.
    #[test]
    fn overlay_output_stays_inside_its_own_rect() {
        for height in [4_u16, 5, 6, 7, 8, 12, 24] {
            for width in [20_u16, 26, 44, 80] {
                let mut model = feed_model(width, height);
                for (index, m) in feed_mut(&mut model)
                    .expect("feed")
                    .memos
                    .iter_mut()
                    .enumerate()
                {
                    m.summary = format!("▓ underlay {index}");
                    m.body = BodyState::Pending;
                }
                let mut picker = Picker {
                    kind: PickerKind::Dates,
                    text: TextBuffer::default(),
                    selected: 0,
                    identity: None,
                };
                picker.rebind(&menu::entries(&model, &picker));
                model.input = InputMode::Picker(picker);
                let terminal = draw(&model);
                let buffer = terminal.backend().buffer();
                let clip = overlay_clip(&model);
                let inside = text_in(buffer, clip);
                assert!(
                    !inside.contains('▓'),
                    "{width}x{height}: underlay text survived inside the overlay rect: {inside}"
                );
                // Any overlay chrome glyph must live inside the overlay's rect;
                // the feed underlay never draws border glyphs.
                let stray: Vec<String> = (buffer.area.top()..buffer.area.bottom())
                    .flat_map(|y| (buffer.area.left()..buffer.area.right()).map(move |x| (x, y)))
                    .filter(|&(x, y)| {
                        matches!(buffer[(x, y)].symbol(), "╭" | "╮" | "╰" | "╯" | "│" | "─")
                            && !clip.contains(Position::new(x, y))
                    })
                    .map(|(x, y)| format!("({x},{y})"))
                    .collect();
                assert!(
                    stray.is_empty(),
                    "{width}x{height}: overlay chrome escaped its rect to {stray:?}"
                );
            }
        }
    }

    /// Statistics paints cells directly: panel borders, `put_str` labels,
    /// per-cell `●` heat dots and the legend swatches — all must stay inside
    /// the content rect, and the legend's first glyph inside the panel's inner
    /// edge (the C-05 left clamp), never on the screen margin.
    #[test]
    fn stats_direct_writes_stay_inside_the_content_rect() {
        for width in [5_u16, 6, 7, 12, 16, 20, 24, 26, 32, 40, 60, 80] {
            for height in [8_u16, 12, 16, 20, 24, 30] {
                let mut model = feed_model(width, height);
                model.view = View::Statistics(stats_view());
                let terminal = draw(&model);
                let buffer = terminal.backend().buffer();
                let content = layout(&model).content;
                let inner = content.intersection(Rect::new(
                    content.x.saturating_add(1),
                    content.y,
                    content.width.saturating_sub(2),
                    content.height,
                ));
                let stray: Vec<String> = (buffer.area.top()..buffer.area.bottom())
                    .flat_map(|y| (buffer.area.left()..buffer.area.right()).map(move |x| (x, y)))
                    .filter(|&(x, y)| {
                        matches!(
                            buffer[(x, y)].symbol(),
                            "●" | "╭" | "╮" | "╰" | "╯" | "│" | "─"
                        ) && !content.contains(Position::new(x, y))
                    })
                    .map(|(x, y)| format!("({x},{y})={:?}", buffer[(x, y)].symbol()))
                    .collect();
                assert!(
                    stray.is_empty(),
                    "{width}x{height}: stats chrome/heatmap escaped the content rect {content:?}: {stray:?}"
                );
                // When the legend renders it must start at or right of the
                // panel's inner left edge — the pre-fix regression painted it
                // at screen x=0.
                for y in inner.top()..inner.bottom() {
                    let row_has_legend = (inner.left()..inner.right()).any(|x| {
                        buffer[(x, y)].symbol() == "●" && buffer[(x, y)].fg != Color::Reset
                    });
                    if row_has_legend {
                        let first_x = (buffer.area.left()..buffer.area.right())
                            .find(|&x| buffer[(x, y)].symbol() != " ");
                        assert!(
                            first_x.is_some_and(|x| x >= inner.left().saturating_sub(1)),
                            "{width}x{height} row {y}: legend/panel row starts at {first_x:?}, \
                             left of the panel edge"
                        );
                    }
                }
            }
        }
    }

    /// An input mode that owns keystrokes must keep the field it edits visible.
    /// The picker's collapsed strip is the established precedent; the composer
    /// panel skips drawing entirely below ~4 content rows/~4 columns while
    /// still swallowing every typed character — a blind-edit surface.
    #[test]
    fn compose_field_stays_visible_while_it_owns_keystrokes() {
        // The ☃ tail marker survives wrapping and the panel's scroll-to-cursor:
        // wherever the field drew *anything* of the draft, `☃` is on screen.
        let mut blind = Vec::new();
        for (width, height) in [
            (80_u16, 6_u16),
            (80, 7),
            (80, 8),
            (80, 9),
            (9, 24),
            (8, 24),
            (7, 24),
            (6, 24),
        ] {
            let mut model = feed_model(width, height);
            model.input = InputMode::Compose;
            model.draft.text = TextBuffer::new("Qz-draft-sentinel☃".to_owned());
            // Keystrokes land while the field is invisible — the draft mutates.
            let before = model.draft.text.text().to_owned();
            sink(input_update::apply(
                &mut model,
                &Command::Type("!".to_owned()),
            ));
            assert!(
                model.draft.text.text() != before,
                "{width}x{height}: compose swallowed the typed character anyway"
            );
            let frame = rendered(&model);
            if !frame.contains('☃') {
                blind.push(format!("{width}x{height}"));
            }
        }
        // Control first — where the panel fits, the draft must be drawn.
        for (width, height) in [(80_u16, 10_u16), (80, 12), (80, 24), (10, 24)] {
            let mut model = feed_model(width, height);
            model.input = InputMode::Compose;
            model.draft.text = TextBuffer::new("Qz-draft-sentinel☃".to_owned());
            let frame = rendered(&model);
            assert!(
                frame.contains('☃'),
                "{width}x{height}: the compose panel must show the draft: {frame}"
            );
        }
        assert!(
            blind.is_empty(),
            "Compose owns the input at {blind:?} but the draft field drew nothing — \
             typing is blind (I6 footprint rule)"
        );
    }

    /// Same contract for the search panel: typing a keyword while the bordered
    /// field has collapsed to nothing must not be silent.
    #[test]
    fn search_field_stays_visible_while_it_owns_keystrokes() {
        let mut blind = Vec::new();
        // Width 10 fits the bordered strip but leaves the text field 0 cells.
        for (width, height) in [
            (80_u16, 2_u16),
            (80, 3),
            (9, 24),
            (8, 24),
            (7, 24),
            (5, 24),
            (10, 24),
        ] {
            let mut model = feed_model(width, height);
            model.input = InputMode::Search {
                text: TextBuffer::new("Qz-search".to_owned()),
            };
            let frame = rendered(&model);
            if !frame.contains('Q') {
                blind.push(format!("{width}x{height}"));
            }
        }
        // Control first — where the strip fits, the text must be drawn.
        for (width, height) in [(80_u16, 4_u16), (80, 24)] {
            let mut model = feed_model(width, height);
            model.input = InputMode::Search {
                text: TextBuffer::new("Qz-search".to_owned()),
            };
            let frame = rendered(&model);
            assert!(
                frame.contains("Qz"),
                "{width}x{height}: search text must draw: {frame}"
            );
        }
        assert!(
            blind.is_empty(),
            "Search owns the input at {blind:?} but no field strip drew — \
             the keyword edits blind"
        );
    }

    /// Extreme Unicode content — CJK, emoji, ZWJ families, full-width digits,
    /// combining marks, tabs, control characters and empty lines — must wrap
    /// inside the column and never bleed a cell into the margins.
    #[test]
    fn extreme_unicode_wraps_inside_the_column() {
        let long = "x".repeat(60);
        let corpus = [
            "中文字符串重复中文字符串重复中文字符串重复",
            "👨‍👩‍👧‍👦 family 🏳️‍🌈 flag 👩‍💻 dev",
            "e\u{301}́ combining mark",
            "１２３４５６ fullwidth digits",
            "tab\tseparated\tvalues",
            "bell\u{7} control\u{1b}[31m sequence",
            "",
            long.as_str(),
        ];
        for width in [3_u16, 5, 7, 13, 20, 40] {
            for height in [6_u16, 12, 24] {
                let mut model = feed_model(width, height);
                for (index, m) in feed_mut(&mut model)
                    .expect("feed")
                    .memos
                    .iter_mut()
                    .enumerate()
                {
                    let body = corpus.get(index % corpus.len()).copied().unwrap_or("");
                    m.summary = body.to_owned();
                    m.body = BodyState::Pending;
                }
                let terminal = draw(&model);
                let column = reading_column(&model);
                let offenders = outside_writes(&[column], terminal.backend().buffer());
                assert!(
                    offenders.is_empty(),
                    "{width}x{height}: unicode corpus wrote outside the column: {offenders:?}"
                );
            }
        }
        // The degenerate case a 1-cell column forces: CJK becomes '□' rather
        // than overflowing its cell.
        let mut model = feed_model(7, 24);
        feed_mut(&mut model)
            .expect("feed")
            .memos
            .first_mut()
            .expect("memo")
            .summary = "中文".to_owned();
        feed_mut(&mut model)
            .expect("feed")
            .memos
            .first_mut()
            .expect("memo")
            .body = BodyState::Pending;
        let frame = rendered(&model);
        assert!(
            frame.contains('□') || !frame.contains('中'),
            "a full-width grapheme must degrade to '□' inside a 1-cell column, never bleed"
        );
    }

    /// `cursor_position` must name a row `wrap_lines` actually emitted — the
    /// C-13 caret/glyph split — across the whole adversarial corpus.
    #[test]
    fn field_cursor_row_always_exists_in_the_wrapped_text() {
        let corpus = [
            "中文字符串重复",
            "👨‍👩‍👧‍👦🏳️‍🌈",
            "e\u{301}́x",
            "１２３",
            "a\tb",
            "line\nbreak\n中",
            "xx",
            "",
        ];
        for text in corpus {
            for width in [1_u16, 2, 3, 5, 7, 13] {
                let (row, _col) = cursor_position(text, width);
                let wrapped = wrap_lines(&plain_lines(text), width);
                assert!(
                    wrapped.get(row).is_some(),
                    "cursor row {row} missing for {text:?} at width {width}: {wrapped:?}"
                );
            }
        }
    }

    /// A decoded image draws only inside the reader's content area — the
    /// placement filter must clip protocol cells to `page.area`.
    #[test]
    fn ready_image_cells_stay_inside_the_reader_area() {
        let mut picker = ratatui_image::picker::Picker::from_fontsize((8, 16));
        picker.set_protocol_type(ratatui_image::picker::ProtocolType::Halfblocks);
        let mut png = Vec::new();
        image::DynamicImage::new_rgba8(8, 8)
            .write_to(&mut Cursor::new(&mut png), image::ImageFormat::Png)
            .expect("png encode");
        let prepared = prepare_image(&png, 20, 6, &picker, &lomo_tui::model::CancelToken::live())
            .expect("image must prepare");
        let mut model = feed_model(80, 24);
        let card = memo("m-1", "before\n![pic](media/pic.png)\nafter").expect("memo");
        model.images.push(ReaderImage {
            request: ImageRequest {
                version: card.version(),
                path: lomo_core::RelativeWorkspacePath::parse("media/pic.png")
                    .expect("relative path"),
                columns: 20,
                rows: 6,
                picker: SharedPicker::new(picker),
            },
            state: ImageState::Ready(Arc::new(prepared)),
        });
        model.view = View::Reader {
            memo: card,
            anchor: TextAnchor::default(),
        };
        let page = reader::page(&model).expect("reader page");
        for placement in &page.pictures {
            let r = placement.rect;
            assert!(
                r.left() >= page.area.left()
                    && r.right() <= page.area.right()
                    && r.top() >= page.area.top()
                    && r.bottom() <= page.area.bottom(),
                "image placement {r:?} escaped the reader area {:?}",
                page.area
            );
        }
        assert!(!page.pictures.is_empty(), "the ready image must place");
        let terminal = draw(&model);
        let buffer = terminal.backend().buffer();
        let stray: Vec<String> = (buffer.area.top()..buffer.area.bottom())
            .flat_map(|y| (buffer.area.left()..buffer.area.right()).map(move |x| (x, y)))
            .filter(|&(x, y)| {
                matches!(buffer[(x, y)].symbol(), "▀" | "▄")
                    && !page.area.contains(Position::new(x, y))
            })
            .map(|(x, y)| format!("({x},{y})"))
            .collect();
        assert!(
            stray.is_empty(),
            "halfblock cells escaped the reader area: {stray:?}"
        );
    }

    // ---------- C-02: highlight ↔ Enter consistency ----------

    /// The shared resolution rule under a fabricated menu with headers, a
    /// duplicate command, a Tag row and every reachable picker state.
    #[test]
    fn mark_and_enter_resolve_identically_under_headers_duplicates_and_stale_state() {
        let pin = Command::Pin;
        let del = Command::Delete;
        let tag_alpha = Command::SelectTag(Some(Arc::from("alpha")));
        let tag_beta = Command::SelectTag(Some(Arc::from("beta")));
        let entries = vec![
            menu_entry("pin first", pin.clone(), Availability::Ready),
            menu_entry(
                "pin twin",
                pin.clone(),
                Availability::Refused(Refusal::MemoTrashed),
            ),
            menu_entry("trash", del.clone(), Availability::Ready),
            menu_entry("#alpha", tag_alpha.clone(), Availability::Ready),
        ];
        let rows = vec![
            MenuRow::Header("group".to_owned()),
            MenuRow::Entry(entries.first().expect("entry").clone()),
            MenuRow::Entry(entries.get(1).expect("entry").clone()),
            MenuRow::Header("other".to_owned()),
            MenuRow::Entry(entries.get(2).expect("entry").clone()),
            MenuRow::Tag(Arc::from("alpha")),
        ];
        assert_eq!(entries.len(), 4, "fabricated menu shape");
        let identities: Vec<Option<Command>> = vec![
            None,
            Some(pin),
            Some(del),
            Some(tag_alpha),
            Some(tag_beta),            // not present — dead identity
            Some(Command::CustomDate), // never a menu command
        ];
        for selected in 0..entries.len() + 2 {
            for identity in &identities {
                let picker = Picker {
                    kind: PickerKind::Dates,
                    text: TextBuffer::default(),
                    selected,
                    identity: identity.clone(),
                };
                let mark_row = menu::selected_row(&rows, &picker);
                let marked = menu::entry_at(&rows, mark_row);
                let executed = picker.entry_index(&entries);
                assert_eq!(
                    marked, executed,
                    "selected={selected} identity={identity:?}: mark {marked:?} vs Enter {executed:?}"
                );
            }
        }
        // Documented collapse: command identity cannot distinguish twins, so
        // selecting the second `Pin` row still marks/executes the first — the
        // command dispatched is identical, so no execution divergence exists.
        let mut picker = Picker {
            kind: PickerKind::Dates,
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        picker.select(1, &entries);
        assert_eq!(
            menu::entry_at(&rows, menu::selected_row(&rows, &picker)),
            Some(0),
            "duplicate-command identity collapses to the first twin — Enter and \
             the mark still agree (the command is the identity)"
        );
        assert_eq!(picker.entry_index(&entries), Some(0));
    }

    /// The structural linchpin the consistency rule depends on: production
    /// `menu::rows` and `menu::entries` must keep a 1:1 selectable mapping for
    /// every picker kind — headers aside, every selectable row is an entry.
    #[test]
    fn production_menus_keep_rows_and_entries_in_lockstep() {
        let mut model = feed_model(80, 24);
        model.set_tags(vec!["alpha".to_owned(), "beta/gamma".to_owned()]);
        let kinds = [
            PickerKind::Palette {
                item: PaletteItem::None,
                scope: PaletteScope::All,
            },
            PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(
                    feed(&model)
                        .expect("feed")
                        .memos
                        .first()
                        .expect("memo")
                        .clone(),
                )),
                scope: PaletteScope::Item,
            },
            PickerKind::Tags(lomo_application::TagSelectionMode::Subtree),
            PickerKind::Dates,
            PickerKind::Attachments(Box::new(
                feed(&model)
                    .expect("feed")
                    .memos
                    .first()
                    .expect("memo")
                    .clone(),
            )),
            PickerKind::History {
                id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                revisions: vec![RevisionRow {
                    revision: 2,
                    stamp: "2026-09-10 08:00:00".to_owned(),
                    preview: "older".to_owned(),
                }],
            },
        ];
        for kind in kinds {
            let name = format!("{kind:?}");
            let picker = Picker {
                kind,
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            };
            let rows = menu::rows(&model, &picker);
            let entries = menu::entries(&model, &picker);
            let selectable = rows
                .iter()
                .filter(|row| matches!(row, MenuRow::Entry(_) | MenuRow::Tag(_)))
                .count();
            assert_eq!(
                selectable,
                entries.len(),
                "{name}: {selectable} selectable rows vs {} entries — the mark↔Enter \
                 mapping assumes 1:1",
                entries.len()
            );
            // And every row's command must resolve — duplicate commands would
            // silently collapse the identity anchor.
            let mut seen: Vec<&Command> = Vec::new();
            for entry in entries.iter() {
                assert!(
                    !seen.contains(&&entry.command),
                    "{name} emits the same command twice: {:?} — command-identity \
                     cannot distinguish the twins",
                    entry.command
                );
                seen.push(&entry.command);
            }
        }
    }

    /// Every dates-picker row: mark where `▎` is drawn, then Enter, and the
    /// dispatched effect/input transition must name exactly that entry's
    /// command — pixel mark ↔ logical entry ↔ command ↔ dispatch, end to end.
    #[test]
    fn enter_dispatches_the_command_the_drawn_mark_sits_on() {
        for target in 0..6 {
            let mut model = dates_picker(80, 24);
            for _ in 0..target {
                sink(input_update::apply(&mut model, &Command::Move(1)));
            }
            let (command, drawn, executed) = {
                let InputMode::Picker(picker) = &model.input else {
                    panic!("dates picker open");
                };
                let entries = menu::entries(&model, picker);
                let marked = drawn_mark_entry(&model);
                let executed = picker.entry_index(&entries);
                assert_eq!(
                    marked, executed,
                    "row {target}: pixels mark {marked:?} but Enter would run {executed:?}"
                );
                (
                    entries
                        .get(executed.expect("non-empty dates"))
                        .expect("entry")
                        .command
                        .clone(),
                    marked,
                    executed,
                )
            };
            assert_eq!(drawn, executed, "sanity");
            let effect = input_update::apply(&mut model, &Command::Accept);
            if let Command::SetDate(preset) = &command {
                assert!(
                    matches!(&effect, Some(Effect::Date { text, .. }) if text == preset),
                    "Enter on {command:?} must dispatch Effect::Date({preset:?}), got {effect:?}"
                );
            } else if matches!(command, Command::CustomDate) {
                assert!(
                    matches!(model.input, InputMode::Date { .. }),
                    "Enter on CustomDate must open the date dialog, got {:?}",
                    model.input
                );
            } else if matches!(command, Command::RemoveDate) {
                // No date filter is set: the refused row must refuse Enter
                // with its own reason and keep the picker open.
                assert_eq!(effect, None);
                assert!(model.status.is_some(), "refusal must be named");
                assert!(matches!(model.input, InputMode::Picker(_)));
            } else {
                panic!("dates picker emitted an unexpected command {command:?}");
            }
        }
    }

    /// Enter must refuse only when no entry row can possibly be drawn — the
    /// boundary is `picker_area().height == 0` (h < 8 at any width).
    #[test]
    fn enter_refuses_only_when_no_entry_row_fits() {
        for height in 1_u16..=12 {
            for width in [9_u16, 20, 80] {
                let mut model = dates_picker(width, height);
                let effect = input_update::apply(&mut model, &Command::Accept);
                let room = overlays::picker_area(&model).height > 0;
                if room {
                    assert!(
                        effect.is_some() || matches!(model.input, InputMode::Date { .. }),
                        "{width}x{height}: an entry row exists but Enter did nothing"
                    );
                } else {
                    assert_eq!(
                        effect, None,
                        "{width}x{height}: no entry row is drawable; Enter must refuse"
                    );
                    assert!(
                        model.status.is_some(),
                        "{width}x{height}: the refusal must name itself on the status line"
                    );
                    assert!(
                        matches!(model.input, InputMode::Picker(_)),
                        "{width}x{height}: a refused Enter keeps the picker open for Esc"
                    );
                }
            }
        }
    }

    /// The tag dictionary can shrink, rename and regrow while the picker is
    /// open — the mark must track a live command or fall back to the position,
    /// and Enter always runs what the mark shows.
    #[test]
    fn tag_churn_never_splits_the_mark_from_enter() {
        let mut model = tags_picker(80, 24, &["alpha", "beta", "gamma"]);
        assert_mark_matches_enter(&model, "open");
        // Select "alpha" (entries: All tags, scope, alpha, beta, gamma).
        sink(input_update::apply(&mut model, &Command::Move(2)));
        assert_mark_matches_enter(&model, "after Move(2)");
        let InputMode::Picker(picker) = &model.input else {
            panic!("picker open");
        };
        assert_eq!(
            picker.identity,
            Some(Command::SelectTag(Some(Arc::from("alpha")))),
            "the highlighted row stamped its command as identity"
        );
        // The dictionary shrinks underneath: the identity dies, the mark falls
        // back to the position — Enter must agree with the drawn mark.
        model.set_tags(vec!["beta".to_owned(), "gamma".to_owned()]);
        assert_mark_matches_enter(&model, "alpha removed");
        sink(input_update::apply(&mut model, &Command::Move(1)));
        assert_mark_matches_enter(&model, "moved after removal");
        // A rename is a remove+insert: the dead command falls back positionally.
        model.set_tags(vec!["beta-renamed".to_owned(), "gamma".to_owned()]);
        assert_mark_matches_enter(&model, "beta renamed");
        // The dead identity may not resurrect a stale row: reappearing "alpha"
        // only matters once something re-stamps it.
        model.set_tags(vec![
            "alpha".to_owned(),
            "beta-renamed".to_owned(),
            "gamma".to_owned(),
        ]);
        assert_mark_matches_enter(&model, "alpha reappears");
        // Filtering reduces to one match: the drawn mark is row 0, Enter runs it.
        sink(input_update::apply(
            &mut model,
            &Command::Type("ga".to_owned()),
        ));
        assert_mark_matches_enter(&model, "filtered to 'ga'");
        let effect = input_update::apply(&mut model, &Command::Accept);
        assert!(
            matches!(effect, Some(Effect::Query { .. })),
            "Enter on the filtered tag applies the filter: {effect:?}"
        );
        let feed = feed(&model).expect("feed");
        assert_eq!(
            feed.query.filters.tag.as_deref(),
            Some("gamma"),
            "the highlighted tag is the one the filter applied"
        );
    }

    /// Mouse clicks map rows through the same entry table — the drawn row at
    /// the clicked coordinate is the row that executes.
    #[test]
    fn picker_click_executes_the_row_under_the_cursor() {
        let mut model = picker_model(
            80,
            24,
            PickerKind::History {
                id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                revisions: vec![
                    RevisionRow {
                        revision: 7,
                        stamp: "2026-09-10 08:00:00".to_owned(),
                        preview: "rev seven".to_owned(),
                    },
                    RevisionRow {
                        revision: 3,
                        stamp: "2026-09-09 08:00:00".to_owned(),
                        preview: "rev three".to_owned(),
                    },
                ],
            },
        );
        // Rows: rev7, rev3, Close — three entry rows, no headers.
        let area = overlays::picker_area(&model);
        assert!(area.height >= 3, "fixture must show all history rows");
        // Click row 1 → RevisionRow 3 → the confirm names exactly that revision.
        sink(input_update::apply(
            &mut model,
            &Command::Click(area.x + 2, area.y + 1),
        ));
        assert!(
            matches!(
                model.input,
                InputMode::Confirm(Confirmation::RestoreRevision { revision: 3, .. })
            ),
            "clicking the second row must arm revision 3, got {:?}",
            model.input
        );
        // Reopen, click the Close row → Browse, never a confirm.
        let mut model = picker_model(
            80,
            24,
            PickerKind::History {
                id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                revisions: vec![RevisionRow {
                    revision: 7,
                    stamp: String::new(),
                    preview: "p".to_owned(),
                }],
            },
        );
        let area = overlays::picker_area(&model);
        sink(input_update::apply(
            &mut model,
            &Command::Click(area.x + 2, area.y + 1),
        ));
        assert!(
            matches!(model.input, InputMode::Browse),
            "the Close row must dismiss the picker, got {:?}",
            model.input
        );
        // A header row is not selectable — clicking it does not move the mark.
        let mut model = feed_model(80, 24);
        model.set_tags(vec!["alpha".to_owned()]);
        sink(apply_command(&mut model, Command::Palette));
        let area = overlays::picker_area(&model);
        let before = {
            let InputMode::Picker(picker) = &model.input else {
                panic!("palette open");
            };
            (picker.selected, picker.identity.clone())
        };
        sink(input_update::apply(
            &mut model,
            &Command::Click(area.x + 1, area.y),
        ));
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette stays open after a header click");
        };
        let rows = menu::rows(&model, picker);
        assert!(
            matches!(rows.first(), Some(MenuRow::Header(_))),
            "the palette's first row must be a header for this probe"
        );
        // Row 0 is a header: click maps to no entry — selection untouched.
        assert_eq!(
            (picker.selected, picker.identity.clone()),
            before,
            "clicking a header must not move the selection"
        );
    }

    /// Scroll deltas walk the picker viewport by entry — the drawn `▎` must
    /// always stay inside the list area, and `picker_top` must keep it framed.
    #[test]
    fn picker_viewport_never_loses_the_marked_row() {
        let tags: Vec<String> = (0..60).map(|i| format!("tag-{i:02}")).collect();
        let mut model = tags_picker(80, 8, &tags.iter().map(String::as_str).collect::<Vec<_>>());
        for delta in [0_i32, 1, 3, 10, 100, -5, -100] {
            if delta != 0 {
                sink(input_update::apply(&mut model, &Command::Move(delta)));
            }
            let InputMode::Picker(picker) = &model.input else {
                panic!("picker open");
            };
            let rows = menu::rows(&model, picker);
            let selected = menu::selected_row(&rows, picker);
            let area = overlays::picker_area(&model);
            let top = overlays::picker_top(selected, rows.len(), area.height);
            assert!(
                selected >= top && selected < top + usize::from(area.height.max(1)),
                "delta {delta}: mark row {selected} outside the viewport \
                 [{top}, {}) of a {}-row list",
                top + usize::from(area.height),
                rows.len()
            );
            let marked = drawn_mark_entry(&model);
            assert_eq!(
                marked,
                picker.entry_index(&menu::entries(&model, picker)),
                "delta {delta}: drawn mark vs Enter disagree"
            );
        }
    }

    /// Enter on a filtered-out-empty list must refuse aloud — never execute a
    /// stale index (the original C-02 shape).
    #[test]
    fn an_empty_filtered_picker_refuses_enter_aloud() {
        let mut model = tags_picker(80, 24, &["alpha", "beta"]);
        sink(input_update::apply(
            &mut model,
            &Command::Type("zzz-no-match".to_owned()),
        ));
        assert_mark_matches_enter(&model, "empty filtered list");
        let effect = input_update::apply(&mut model, &Command::Accept);
        assert_eq!(effect, None, "an empty list has nothing to execute");
        assert!(
            model.status.is_some(),
            "the refusal must be named on the status line"
        );
        assert!(matches!(model.input, InputMode::Picker(_)));
    }

    /// A memo carrying the same attachment path twice would emit two identical
    /// `OpenAttachment` rows — the consistency rule must still hold (both twins
    /// resolve to the first match; dispatch is the same command either way).
    #[test]
    fn duplicate_attachment_rows_cannot_split_mark_from_enter() {
        let mut model = feed_model(80, 24);
        let path = lomo_core::RelativeWorkspacePath::parse("media/dup.png").expect("path");
        feed_mut(&mut model)
            .expect("feed")
            .memos
            .first_mut()
            .expect("memo")
            .attachments = vec![path.clone(), path.clone()];
        sink(apply_command(&mut model, Command::Attachments));
        let InputMode::Picker(picker) = &model.input else {
            panic!("attachments picker open");
        };
        let entries = menu::entries(&model, picker);
        assert_eq!(entries.len(), 2, "the duplicated path produced twin rows");
        assert_mark_matches_enter(&model, "duplicate attachment rows");
        // Move onto the twin: the mark collapses to row 0 — and Enter must run
        // exactly the command the mark names (identical either way here).
        sink(input_update::apply(&mut model, &Command::Move(1)));
        assert_mark_matches_enter(&model, "twin selected");
        let effect = input_update::apply(&mut model, &Command::Accept);
        assert!(
            matches!(&effect, Some(Effect::OpenAttachment { path: p, .. }) if *p == path),
            "Enter opens the marked row's path: {effect:?}"
        );
    }

    // ---------- scroll / anchor contracts (C-08/C-09 family) ----------

    /// The feed's scroll bound is `total - viewport`, like the reader's
    /// `page.top` and the overlays' `bounded_scroll`: overshooting the last row
    /// must land on the last full viewport, not strand one row atop a blank
    /// pane. `scroll_feed`'s forward walk ends on the last card's final row —
    /// its Gap row — and `feed_window` makes that row the viewport top, so a
    /// single Page/G overshoot empties the pane.
    #[test]
    fn feed_scroll_past_the_end_fills_the_last_viewport() {
        let mut stranded = Vec::new();
        for command in [
            Command::Page(9),
            Command::Scroll(i32::MAX),
            Command::Last,
            Command::Scroll(3),
            Command::Scroll(0),
        ] {
            let mut model = model_with_memos(8, 80, 24).expect("feed");
            let content = layout(&model).content;
            let total: usize = feed(&model)
                .expect("feed")
                .memos
                .iter()
                .map(|m| memo_lines(m, content.width.saturating_sub(2), false).len())
                .sum();
            assert!(
                total > usize::from(content.height),
                "fixture feed must exceed one viewport ({total} rows vs {})",
                content.height
            );
            // Drive the same stride enough times to reach the tail.
            for _ in 0..3 {
                sink(apply_command(&mut model, command.clone()));
            }
            let window = feed_window(feed(&model).expect("feed"), content.width, content.height);
            let visible = window.rows.len().saturating_sub(window.top);
            if visible < usize::from(content.height) {
                stranded.push(format!("{command:?} → {visible}/{total} rows visible"));
            }
        }
        assert!(
            stranded.is_empty(),
            "scrolling to the feed's end must fill the last viewport; \
             these paths parked the anchor on the last card's gap row: {stranded:?}"
        );
        // Rendered corroboration for one path: a full viewport shows several
        // cards' bodies; the stranded frame shows the last card's tail only.
        let mut model = model_with_memos(8, 80, 24).expect("feed");
        sink(apply_command(&mut model, Command::Page(9)));
        let content = layout(&model).content;
        let terminal = draw(&model);
        let content_text = text_in(terminal.backend().buffer(), content);
        let bodies = content_text.matches("Body ").count();
        assert!(
            bodies >= 2,
            "a filled last viewport draws several cards; the stranded frame drew {bodies}:\n{content_text}"
        );
    }

    /// A stale semantic anchor at a card's Gap row must not blank the feed —
    /// `feed_window` resolves the anchor to the last row of the card, which is
    /// the gap; the viewport contract still owes a full page.
    #[test]
    fn a_gap_row_anchor_never_strands_the_feed_viewport() {
        let mut model = model_with_memos(8, 80, 24).expect("feed");
        let last = feed(&model)
            .expect("feed")
            .memos
            .last()
            .expect("memo")
            .id
            .clone();
        feed_mut(&mut model).expect("feed").anchor = Some(MemoAnchor {
            id: last,
            position: CardPosition::Gap,
        });
        let content = layout(&model).content;
        let window = feed_window(feed(&model).expect("feed"), content.width, content.height);
        let visible = window.rows.len().saturating_sub(window.top);
        assert!(
            visible >= usize::from(content.height),
            "a Gap-position anchor resolved to the card's last row and stranded \
             the viewport at {visible} visible rows"
        );
    }

    /// The reader clamps `page.top` to `total - height` through body growth
    /// and shrink — anchors may point anywhere; the viewport stays full.
    #[test]
    fn reader_page_top_stays_bounded_through_body_changes() {
        // Growth: an anchor at line 199 of a 200-line body shows the tail page.
        let body = (0..200)
            .map(|i| format!("line {i}"))
            .collect::<Vec<_>>()
            .join("\n");
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        model.view = View::Reader {
            memo: memo("m-1", &body).expect("memo"),
            anchor: TextAnchor {
                line: 199,
                grapheme: usize::MAX,
            },
        };
        let page = reader::page(&model).expect("page");
        let bound = page.total.saturating_sub(usize::from(page.area.height));
        assert!(
            page.top <= bound,
            "reader top {} exceeds the last full page {bound} (total {})",
            page.top,
            page.total
        );
        let materialized_below = page.rows.len().saturating_sub(page.top - page.origin);
        assert!(
            materialized_below >= usize::from(page.area.height).min(page.total - page.top),
            "the page under the anchor must be full: {materialized_below} rows below top"
        );
        // Shrink: an anchor far past a 3-line body resolves to a full last page.
        let mut model = model_with_memos(1, 40, 20).expect("feed");
        model.view = View::Reader {
            memo: memo("m-1", "one\ntwo\nthree").expect("memo"),
            anchor: TextAnchor {
                line: 900,
                grapheme: usize::MAX,
            },
        };
        let page = reader::page(&model).expect("page");
        assert!(
            page.top <= page.total.saturating_sub(usize::from(page.area.height)),
            "shrunk-body anchor left top {} past the bound",
            page.top
        );
        // `scroll_anchor` i32::MAX lands on the same bound the renderer uses.
        let anchor = reader::scroll_anchor(&model, i32::MAX).expect("scroll target");
        let mut view = model.view.clone();
        if let View::Reader { anchor: slot, .. } = &mut view {
            *slot = anchor;
        }
        model.view = view;
        let page = reader::page(&model).expect("page");
        assert_eq!(
            page.top,
            page.total.saturating_sub(usize::from(page.area.height)),
            "scrolling to the bottom must land the top on the last full page"
        );
    }

    /// Message/Help scroll must clamp to `lines - visible` where `visible` is
    /// the *collapsed strip's* height at short terminals — the C-08 bound —
    /// and a stale offset must still render the tail line inside the strip.
    #[test]
    fn overlay_scroll_bounds_track_the_collapsed_frame() {
        for height in [4_u16, 5, 6, 8, 10, 24] {
            let lines: Vec<String> = (0..20).map(|i| format!("line {i}")).collect();
            let mut model = feed_model(80, height);
            model.input = InputMode::Message {
                title: "Notice".to_owned(),
                lines,
                scroll: 999,
            };
            // Stale offset: the renderer clamps to the last full page and the
            // tail line still lands inside the overlay's strip.
            let terminal = draw(&model);
            let clip = overlay_clip(&model);
            let inside = text_in(terminal.backend().buffer(), clip);
            assert!(
                inside.contains("line 19"),
                "h={height}: a stale scroll must still render the last line inside \
                 the overlay: {inside:?}"
            );
            // The input-side bound keys on the same visible height.
            sink(input_update::apply(&mut model, &Command::Scroll(0)));
            let InputMode::Message { scroll, lines, .. } = &model.input else {
                panic!("message open");
            };
            let bound = lines.len().saturating_sub(overlays::content_height(&model));
            assert_eq!(
                *scroll, bound,
                "h={height}: stored scroll {} must re-clamp to lines-visible {bound}",
                *scroll
            );
        }
        // Help scrolls by the same rule on a taller list.
        let mut model = feed_model(80, 24);
        model.input = InputMode::Help { scroll: 0 };
        let help_len = overlays::help(lomo_tui::i18n::UiStrings::detect()).len();
        sink(input_update::apply(&mut model, &Command::Scroll(i32::MAX)));
        let InputMode::Help { scroll } = &model.input else {
            panic!("help open");
        };
        let bound = help_len.saturating_sub(overlays::content_height(&model));
        assert_eq!(
            *scroll, bound,
            "help scroll {} must clamp to {bound} for a {}-line document",
            *scroll, help_len
        );
    }

    /// Resizing mid-scroll keeps the stored offset valid — `≤ lines - strip
    /// height` — and the strip renders exactly the lines the offset names;
    /// a fresh bottom-scroll at the new size must reach the real tail.
    #[test]
    fn message_scroll_stays_valid_and_reaches_the_tail_after_resize() {
        let mut model = feed_model(80, 24);
        model.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: (0..20).map(|i| format!("line {i}")).collect(),
            scroll: 0,
        };
        sink(input_update::apply(&mut model, &Command::Scroll(i32::MAX)));
        lomo_tui::update::apply_resize(&mut model, 80, 6);
        // The stored offset stays legal (≤ the collapsed bound) and renders
        // its own lines — never a blank strip.
        let bound = {
            let InputMode::Message { lines, .. } = &model.input else {
                panic!("message open");
            };
            lines.len().saturating_sub(overlays::content_height(&model))
        };
        let terminal = draw(&model);
        let inside = text_in(terminal.backend().buffer(), overlay_clip(&model));
        let InputMode::Message { scroll, .. } = &model.input else {
            panic!("message open");
        };
        assert!(
            *scroll <= bound,
            "stored scroll {} exceeds the collapsed bound {bound}",
            *scroll
        );
        let expected = format!("line {scroll}");
        let first = inside.lines().next().unwrap_or("").trim_end().to_owned();
        assert_eq!(
            first, expected,
            "the strip must render the line its stored offset names: {inside:?}"
        );
        // And the tail is still reachable at the collapsed height.
        sink(input_update::apply(&mut model, &Command::Scroll(i32::MAX)));
        let terminal = draw(&model);
        let inside = text_in(terminal.backend().buffer(), overlay_clip(&model));
        assert!(
            inside.contains("line 19"),
            "after re-scrolling to the bottom the tail must render: {inside:?}"
        );
    }

    /// The status row carries persistent badges next to the toast/hint — and
    /// keeps doing so while an overlay is open above it.
    #[test]
    fn badges_and_toast_share_the_status_row_while_overlays_draw() {
        for (width, height) in [(80_u16, 24_u16), (80, 10), (44, 24)] {
            let mut model = feed_model(width, height);
            model.raise_badge(
                Severity::Error,
                BadgeClass::Watch,
                "watcher dead".to_owned(),
            );
            model.raise_badge(
                Severity::Warn,
                BadgeClass::Sync,
                "reconcile failed".to_owned(),
            );
            model.set_status("a toast line");
            let mut picker = Picker {
                kind: PickerKind::Dates,
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            };
            picker.rebind(&menu::entries(&model, &picker));
            model.input = InputMode::Picker(picker);
            let terminal = draw(&model);
            let buffer = terminal.backend().buffer();
            let status = layout(&model).status;
            let row_text = text_in(buffer, Rect::new(status.x, status.y, status.width, 1));
            assert!(
                row_text.contains('✗') && row_text.contains('!'),
                "{width}x{height}: status row lost a badge under the overlay: {row_text:?}"
            );
            // Severity colors are the badge's identity — the cells carry them.
            let marks: Vec<(u16, u16)> = (status.x..status.x + status.width)
                .filter_map(|x| {
                    let cell = &buffer[(x, status.y)];
                    matches!(cell.symbol(), "✗" | "!").then_some((x, status.y))
                })
                .collect();
            assert!(
                marks.iter().any(|&(x, y)| buffer[(x, y)].fg == Color::Red)
                    && marks
                        .iter()
                        .any(|&(x, y)| buffer[(x, y)].fg == Color::Yellow),
                "{width}x{height}: badges lost their severity colors: {marks:?}"
            );
            // The picker's own mark stays inside its list area — no bleed.
            let area = overlays::picker_area(&model);
            if area.height > 0 {
                let marked = drawn_mark_entry(&model);
                let InputMode::Picker(picker) = &model.input else {
                    panic!("picker open");
                };
                assert_eq!(
                    marked,
                    picker.entry_index(&menu::entries(&model, picker)),
                    "{width}x{height}: mark vs Enter under badges"
                );
            }
        }
    }

    /// Capability degradation ladder, including the edge inputs the original
    /// matrix skipped: empty COLORTERM, case-insensitive markers, and a TERM
    /// containing "direct"/"truecolor"/"24bit" substrings.
    #[test]
    fn heatmap_palette_matches_the_terminals_color_capability() {
        let scale = heat_scale_for;
        assert!(
            scale(Some("truecolor"), None)
                .iter()
                .all(|c| matches!(c, Color::Rgb(..))),
            "COLORTERM=truecolor keeps the tuned ramp"
        );
        assert!(
            scale(Some("24BIT"), None)
                .iter()
                .all(|c| matches!(c, Color::Rgb(..))),
            "capability markers are case-insensitive"
        );
        assert!(
            scale(None, Some("foot-direct"))
                .iter()
                .all(|c| matches!(c, Color::Rgb(..))),
            "a *-direct TERM is a truecolor signal"
        );
        assert!(
            scale(Some("garbage"), Some("xterm"))
                .iter()
                .all(|c| matches!(c, Color::Indexed(_))),
            "any non-empty COLORTERM implies at least an indexed palette"
        );
        assert!(
            scale(None, Some("xterm-256color"))
                .iter()
                .all(|c| matches!(c, Color::Indexed(_))),
            "a 256color TERM gets indexed approximations"
        );
        for (colorterm, term) in [
            (None, Some("vt100")),
            (None, Some("xterm")),
            (None, Some("screen")),
            (Some(""), Some("vt100")),
            (None, None),
        ] {
            let ramp = scale(colorterm, term);
            assert!(
                ramp.iter()
                    .all(|c| !matches!(c, Color::Rgb(..) | Color::Indexed(_))),
                "colorterm={colorterm:?} term={term:?} must degrade to named colors: {ramp:?}"
            );
        }
    }

    /// The picker's filter field keeps drawing what it owns: while the list
    /// has no room the collapsed strip still renders the filter text — the
    /// same footprint rule Enter's refusal keys on.
    #[test]
    fn collapsed_picker_still_shows_its_filter_field() {
        for height in [4_u16, 5, 6, 7] {
            let mut model = dates_picker(80, height);
            if let InputMode::Picker(picker) = &mut model.input {
                picker.text = TextBuffer::new("filt".to_owned());
            }
            let terminal = draw(&model);
            let inside = text_in(terminal.backend().buffer(), overlay_clip(&model));
            assert!(
                inside.contains("filt"),
                "h={height}: the collapsed picker must draw its filter field: {inside:?}"
            );
            // Enter still refuses — no entry is drawable.
            let effect = input_update::apply(&mut model, &Command::Accept);
            assert_eq!(effect, None, "h={height}: no entry row is drawable");
        }
    }

    /// The wizard's collapsed strip keeps the focused field visible — and the
    /// *framed* form must too: below the strip threshold `draw_setup`'s intro
    /// lines consume the whole inner rect and the editable field never draws,
    /// while keystrokes still mutate it.
    #[test]
    fn setup_wizard_keeps_its_field_visible_at_every_height() {
        let mut blind = Vec::new();
        for height in 4_u16..=14 {
            let mut model = setup_model(80, height);
            // Focus workspace — the proposal's path is the field's content.
            let before = {
                let InputMode::Setup(setup) = &model.input else {
                    panic!("setup open");
                };
                setup.workspace.text().to_owned()
            };
            // A keystroke lands — the field owns the input either way.
            sink(input_update::apply(
                &mut model,
                &Command::Type("z".to_owned()),
            ));
            let InputMode::Setup(setup) = &model.input else {
                panic!("setup open");
            };
            assert!(
                setup.workspace.text().len() > before.len(),
                "h={height}: Setup swallowed the typed character anyway"
            );
            let terminal = draw(&model);
            let inside = text_in(terminal.backend().buffer(), overlay_clip(&model));
            if !inside.contains("notes-") {
                blind.push(format!("h={height}: {inside:?}"));
            }
        }
        assert!(
            blind.is_empty(),
            "the setup field owns keystrokes but drew no text at {blind:?}"
        );
        // And the framed form keeps both fields + cursor inside `inner`.
        let mut model = setup_model(60, 24);
        let terminal = draw(&model);
        let buffer = terminal.backend().buffer();
        let inside = text_in(buffer, overlay_clip(&model));
        assert!(
            inside.contains("Asia/Shanghai") || inside.contains("Asia"),
            "the wizard must render its editable values: {inside:?}"
        );
        // Field focus switches wrap into the drawn column — never the screen's.
        sink(input_update::apply(&mut model, &Command::Move(1)));
        let InputMode::Setup(setup) = &model.input else {
            panic!("setup open");
        };
        assert!(
            matches!(setup.focus, lomo_tui::model::SetupFocus::TimeZone),
            "Move(1) focuses the second wizard field"
        );
    }

    /// The inline settings edit has the same footprint duty: the field sits
    /// at `inner.y + 3`, so any overlay inner height below four rows hides it
    /// while `Type` still edits.
    #[test]
    fn setting_edit_keeps_its_field_visible_at_every_height() {
        let mut blind = Vec::new();
        for height in 4_u16..=14 {
            let mut model = feed_model(80, height);
            model.input = InputMode::Setting(SettingsEdit::new(
                lomo_tui::config::SettingsField::Workspace,
                "Qz-set",
            ));
            sink(input_update::apply(
                &mut model,
                &Command::Type("z".to_owned()),
            ));
            let terminal = draw(&model);
            let inside = text_in(terminal.backend().buffer(), overlay_clip(&model));
            if !inside.contains("Qz") {
                blind.push(format!("h={height}: {inside:?}"));
            }
        }
        assert!(
            blind.is_empty(),
            "the settings field owns keystrokes but drew no text at {blind:?}"
        );
    }

    /// `draw_field`'s cursor stays inside the screen on every field-bearing
    /// surface — a cursor past the frame is an invisible-caret defect.
    #[test]
    fn field_cursors_never_leave_the_frame() {
        let mut cases: Vec<(&'static str, AppModel)> = Vec::new();
        let mut date = feed_model(7, 9);
        date.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("中文字段".to_owned()),
            error: None,
        };
        cases.push(("date/cjk-1-cell", date));
        let mut setting = feed_model(7, 9);
        setting.input = InputMode::Setting(SettingsEdit::new(
            lomo_tui::config::SettingsField::Workspace,
            "/长路径/长路径/长路径",
        ));
        cases.push(("setting/cjk", setting));
        cases.push(("setup/1-cell", setup_model(7, 9)));
        for (name, model) in cases {
            let mut terminal = draw(&model);
            let position = terminal
                .backend_mut()
                .get_cursor_position()
                .expect("cursor");
            let screen = Rect::new(0, 0, model.width, model.height);
            assert!(
                screen.contains(position),
                "{name}: cursor {position:?} outside the {screen:?} frame"
            );
        }
    }

    /// A row-list view with a stale `selected` far beyond `items` must still
    /// draw — `draw_rows` derives `top` from the stale index and may skip the
    /// entire list (the render-side mirror of C-02's stale-index class).
    #[test]
    fn row_lists_survive_a_far_stale_selection() {
        let mut model = feed_model(80, 24);
        model.view = View::Tasks(SelectionList {
            items: vec![
                TaskRow {
                    memo_id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                    line: 0,
                    text: "task sentinel".to_owned(),
                    date: "2026-09-11".to_owned(),
                    done: false,
                },
                TaskRow {
                    memo_id: lomo_workspace::MemoId::parse("m-2").expect("id"),
                    line: 0,
                    text: "second".to_owned(),
                    date: "2026-09-11".to_owned(),
                    done: true,
                },
            ],
            selected: 30,
            scroll: 0,
        });
        let terminal = draw(&model);
        let content = layout(&model).content;
        let inside = text_in(terminal.backend().buffer(), content);
        assert!(
            inside.contains("task sentinel") || inside.contains("second"),
            "a stale selection index must not blank the non-empty list: {inside:?}"
        );
    }

    // ---------- the sweep's scenario matrix ----------

    /// The (view × input) scenarios for the margin-purity sweep — each a fresh
    /// model so state never leaks between frames.
    fn sweep_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models = view_scenarios(width, height);
        models.extend(input_scenarios(width, height));
        models
    }

    /// Content views — feed variants, statistics, settings, lists, reader.
    fn view_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models = Vec::new();
        models.push(("feed", feed_model(width, height)));
        let mut unicode = feed_model(width, height);
        for (index, m) in feed_mut(&mut unicode)
            .expect("feed")
            .memos
            .iter_mut()
            .enumerate()
        {
            m.summary = [
                "中文重复中文重复中文重复",
                "👨‍👩‍👧‍👦🏳️‍🌈👩‍💻",
                "１２３４５６",
                "a\tb\u{7}c",
            ]
            .get(index % 4)
            .copied()
            .unwrap_or("")
            .to_owned();
            m.body = BodyState::Pending;
        }
        models.push(("feed/unicode", unicode));
        let mut failed = feed_model(width, height);
        feed_mut(&mut failed).expect("feed").load =
            LoadStatus::Failed("disk unavailable".to_owned());
        failed.set_status("toast");
        failed.raise_badge(Severity::Error, BadgeClass::Watch, "watch".to_owned());
        models.push(("feed/failed+badges", failed));
        let mut stats = feed_model(width, height);
        stats.view = View::Statistics(stats_view());
        models.push(("statistics", stats));
        let mut settings = feed_model(width, height);
        settings.view = View::Settings(settings_view());
        models.push(("settings", settings));
        let mut tasks = feed_model(width, height);
        tasks.view = View::Tasks(SelectionList::new(vec![TaskRow {
            memo_id: lomo_workspace::MemoId::parse("m-1").expect("id"),
            line: 0,
            text: "task 中文 👨‍👩‍👧‍👦".to_owned(),
            date: "2026-09-11".to_owned(),
            done: false,
        }]));
        models.push(("tasks", tasks));
        let mut attachments = feed_model(width, height);
        attachments.view = View::Attachments(SelectionList::new(vec![AttachmentRow {
            path: lomo_core::RelativeWorkspacePath::parse("media/中文-图片.png")
                .expect("relative path"),
            owners: vec!["2026-09-11".to_owned()],
        }]));
        models.push(("attachments", attachments));
        let mut reader = feed_model(width, height);
        reader.view = View::Reader {
            memo: memo("m-1", "第一行\nsecond\nthird 中文\nfourth").expect("memo"),
            anchor: TextAnchor {
                line: 1,
                grapheme: usize::MAX,
            },
        };
        models.push(("reader", reader));
        models
    }

    /// Input/overlay scenarios — composer, search, pickers, dialogs.
    fn input_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models = Vec::new();
        let mut compose = feed_model(width, height);
        compose.input = InputMode::Compose;
        compose.draft.text = TextBuffer::new("draft 中文 全文 #tag".to_owned());
        models.push(("input/compose", compose));
        let mut search = feed_model(width, height);
        search.input = InputMode::Search {
            text: TextBuffer::new("needle 中文".to_owned()),
        };
        models.push(("input/search", search));
        let mut palette = feed_model(width, height);
        palette.input = InputMode::Picker(Picker {
            kind: PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(memo("m-9", "actionable").expect("memo"))),
                scope: PaletteScope::All,
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        let palette_entries = {
            let InputMode::Picker(picker) = &palette.input else {
                panic!("palette picker");
            };
            menu::entries(&palette, picker)
        };
        if let InputMode::Picker(picker) = &mut palette.input {
            picker.rebind(&palette_entries);
        }
        models.push(("input/palette", palette));
        models.push(("input/dates", dates_picker(width, height)));
        let tags = tags_picker(width, height, &["alpha", "beta/gamma", "中文标签"]);
        models.push(("input/tags", tags));
        let mut history = feed_model(width, height);
        history.input = InputMode::Picker(Picker {
            kind: PickerKind::History {
                id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                revisions: vec![
                    RevisionRow {
                        revision: 7,
                        stamp: "2026-09-10 08:00:00".to_owned(),
                        preview: "rev 中文".to_owned(),
                    },
                    RevisionRow {
                        revision: 3,
                        stamp: String::new(),
                        preview: "older".to_owned(),
                    },
                ],
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        let history_entries = {
            let InputMode::Picker(picker) = &history.input else {
                panic!("history picker");
            };
            menu::entries(&history, picker)
        };
        if let InputMode::Picker(picker) = &mut history.input {
            picker.rebind(&history_entries);
        }
        models.push(("input/history", history));
        let mut date = feed_model(width, height);
        date.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("2026-13-40".to_owned()),
            error: Some("day out of range".to_owned()),
        };
        models.push(("input/date", date));
        let mut setting = feed_model(width, height);
        setting.input = InputMode::Setting(SettingsEdit::new(
            lomo_tui::config::SettingsField::Workspace,
            "/path/that/is/quite/long/and/wraps/around",
        ));
        models.push(("input/setting", setting));
        models.push(("input/setup", setup_model(width, height)));
        let mut confirm = feed_model(width, height);
        confirm.input = InputMode::Confirm(Confirmation::Delete {
            memo: Box::new(memo("m-2", "doomed body").expect("memo")),
        });
        models.push(("input/confirm-delete", confirm));
        let mut message = feed_model(width, height);
        message.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: (0..20).map(|i| format!("line {i} 中文")).collect(),
            scroll: 999,
        };
        models.push(("input/message-stale", message));
        let mut help = feed_model(width, height);
        help.input = InputMode::Help { scroll: 999 };
        models.push(("input/help-stale", help));
        models
    }
}
