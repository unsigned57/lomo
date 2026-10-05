// adversarial-audit: rendering precision — overlay clearing, menu row math,
// Unicode geometry, tiny terminals, dialog information density and status-bar
// truth. Every test asserts the invariant a correct renderer would keep; each
// failure is one demonstrated defect (RED evidence, never a fix).

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{feed_mut, memo, model_with_memos};
    use lomo_tui::{
        event::Command,
        input::TextBuffer,
        model::{
            AppModel, AttachmentRow, BadgeClass, Confirmation, HeatPoint, InputMode, LoadStatus,
            MemoCard, PaletteItem, PaletteScope, Picker, PickerKind, Req, RevisionRow, Screen,
            SelectionList, SetupState, Severity, StatsView, TaskRow, TextAnchor, View,
        },
        settings::SettingsEdit,
        update::apply_command,
    };
    use ratatui::{Terminal, backend::TestBackend, style::Color};
    use std::path::PathBuf;

    fn screen_rows(model: &AppModel) -> Vec<String> {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
            .expect("fixture and operation must succeed");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .expect("fixture and operation must succeed");
        let buffer = terminal.backend().buffer();
        (0..model.height)
            .map(|y| {
                (0..model.width)
                    .map(|x| buffer[(x, y)].symbol())
                    .collect::<String>()
            })
            .collect()
    }

    fn render(model: &AppModel) -> String {
        screen_rows(model).join("\n")
    }

    fn date_model(width: u16, height: u16, error: Option<&str>) -> AppModel {
        let mut model = model_with_memos(2, width, height).expect("feed");
        model.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("2026-13-40".to_owned()),
            error: error.map(str::to_owned),
        };
        model
    }

    /// Underlay text must never survive inside the overlay rectangle: the
    /// palette clears its area before drawing the frame.
    #[test]
    fn palette_overlay_leaves_no_underlay_text_inside_its_area() {
        let mut model = model_with_memos(3, 80, 24).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Actions), None);
        let area = lomo_tui::overlays::overlay_area(&model);
        let rows = screen_rows(&model);
        let inside: String = rows
            .iter()
            .enumerate()
            .filter(|(y, _)| *y >= usize::from(area.y) && *y < usize::from(area.bottom()))
            .flat_map(|(_, row)| row.chars())
            .collect();
        assert!(
            !inside.contains("Body 0"),
            "feed text bled into the overlay: {rows:?}"
        );
        assert!(inside.contains('╭') && inside.contains('╯'));
    }

    /// The date field is emitted at `inner.y + 3` and the error at `inner.y + 5`
    /// without checking the inner height: at 80x9 the field vanishes outright
    /// (the dialog accepts blind input) and at 80x10 the error paragraph lands
    /// below the border on un-cleared rows.
    #[test]
    fn date_overlay_field_survives_a_short_dialog() {
        let model = date_model(80, 9, None);
        let text = render(&model);
        assert!(
            text.contains("2026-13-40"),
            "the typed date must stay visible inside the dialog, got: {text}"
        );
    }

    /// Below ~12 rows the error paragraph's rect collapses to height 0: a
    /// rejected date fails silently — the dialog keeps no trace of why Enter
    /// did nothing.
    #[test]
    fn date_overlay_error_stays_visible_inside_its_frame() {
        let model = date_model(80, 10, Some("day out of range"));
        let area = lomo_tui::overlays::overlay_area(&model);
        let rows = screen_rows(&model);
        let inside: String = rows
            .iter()
            .enumerate()
            .filter(|(y, _)| *y >= usize::from(area.y) && *y < usize::from(area.bottom()))
            .flat_map(|(_, row)| row.chars())
            .collect();
        assert!(
            inside.contains("day out of range"),
            "the rejection must be visible inside the dialog, got: {inside}"
        );
    }

    /// A destructive dialog must name its target. `draw_confirm` prints only
    /// "this memo": no date, no excerpt, no revision — the user cannot verify
    /// which object `y` will delete.
    #[test]
    fn confirm_dialog_identifies_the_memo_it_deletes() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Delete), None);
        assert!(matches!(
            model.input,
            InputMode::Confirm(Confirmation::Delete { .. })
        ));
        let area = lomo_tui::overlays::overlay_area(&model);
        let rows = screen_rows(&model);
        let inside: String = rows
            .iter()
            .enumerate()
            .filter(|(y, _)| *y >= usize::from(area.y) && *y < usize::from(area.bottom()))
            .flat_map(|(_, row)| row.chars())
            .collect();
        assert!(
            inside.contains("2026-09-11") || inside.contains("Body 0"),
            "the delete dialog must show which memo it deletes, got: {inside}"
        );
    }

    /// `Confirmation::EmptyTrash` knows it deletes every trashed memo but the
    /// dialog shows no count; the same class covers `RestoreRevision` hiding the
    /// revision number it carries.
    #[test]
    fn confirm_dialog_quantifies_bulk_and_revision_targets() {
        let mut model = AppModel::new(80, 24);
        model.input = InputMode::Confirm(Confirmation::RestoreRevision {
            id: memo("m-1", "x").expect("memo").id,
            revision: 7,
            stamp: "2026-09-11 12:00".to_owned(),
            preview: "x".to_owned(),
        });
        let text = render(&model);
        assert!(
            text.contains("r7") || text.contains("revision 7") || text.contains('7'),
            "the dialog must name the revision it restores, got: {text}"
        );
    }

    /// `selected_row` falls back to display row 0 when the selection outlives
    /// the filtered entries, while `accept_picker` clamps to the last entry:
    /// the highlight vanishes (or lands on a header) but Enter still executes.
    /// Reachable: a `Saved` reply empties the draft and removes the
    /// "Discard capture draft" row under an open palette.
    #[test]
    fn selection_highlight_tracks_the_entry_enter_will_execute() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        model.draft.text = TextBuffer::new("kept thought".to_owned());
        model.last_created = Some(lomo_workspace::MemoId::parse("old-1").expect("id"));
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette open");
        };
        let count = lomo_tui::menu::entries(&model, picker).len();
        assert_eq!(apply_command(&mut model, Command::Move(i32::MAX)), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette open");
        };
        assert_eq!(picker.selected, count - 1);
        let revision = model.draft.revision;
        // A `Saved` reply only lands for the live submission — arm the mutex
        // for this revision and register its pending intent, then let the
        // reply reset the draft (removing the "Discard capture draft" row)
        // and queue a feed refresh.
        let req = model.request(lomo_tui::model::PendingKind::DraftCommit { revision });
        model.draft.save = lomo_tui::model::SaveState::Submitting { req, revision };
        drop(lomo_tui::messages::apply_message(
            &mut model,
            lomo_tui::effects::RuntimeMessage::Saved {
                req,
                revision,
                id: lomo_workspace::MemoId::parse("saved-1").expect("id"),
            },
        ));
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette open");
        };
        let rows = lomo_tui::menu::rows(&model, picker);
        let entries = lomo_tui::menu::entries(&model, picker);
        let display = lomo_tui::menu::selected_row(&rows, picker);
        let highlighted_index = lomo_tui::menu::entry_at(&rows, display);
        // Enter resolves through `Picker::entry_index` — the same identity
        // anchor the drawn mark uses (I2).
        let executed_index = picker.entry_index(&entries);
        let screen = screen_rows(&model);
        let picker_rows: Vec<&String> = screen
            .iter()
            .filter(|row| row.contains('│') && row.contains('▎'))
            .collect();
        assert_eq!(
            highlighted_index,
            executed_index,
            "highlight maps to entry {highlighted_index:?} (drawn mark rows: {picker_rows:?}) but \
             Enter runs entry {executed_index:?} ({:?})",
            executed_index
                .and_then(|index| entries.get(index))
                .map(|entry| &entry.command)
        );
    }

    /// The same stale-selection path on a non-grouped picker lights up row 0 —
    /// an entry Enter will not run.
    #[test]
    fn stale_selection_highlights_the_entry_that_runs() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        model.set_tags(vec![
            "alpha".to_owned(),
            "beta".to_owned(),
            "gamma".to_owned(),
        ]);
        assert!(matches!(
            apply_command(&mut model, Command::Tags),
            Some(lomo_tui::effects::Effect::Tags { .. })
        ));
        assert_eq!(apply_command(&mut model, Command::Move(2)), None);
        // Shrink the entry list out from under the selection: a live tag
        // refresh removes the rows the highlight sits on.
        model.set_tags(Vec::new());
        let InputMode::Picker(picker) = &model.input else {
            panic!("tags picker open");
        };
        let rows = lomo_tui::menu::rows(&model, picker);
        let entries = lomo_tui::menu::entries(&model, picker);
        let display = lomo_tui::menu::selected_row(&rows, picker);
        let highlighted_index = lomo_tui::menu::entry_at(&rows, display);
        let executed_index = picker.entry_index(&entries);
        assert_eq!(
            highlighted_index,
            executed_index,
            "highlight maps to entry {highlighted_index:?} but Enter runs entry {executed_index:?} \
             ({:?})",
            executed_index
                .and_then(|index| entries.get(index))
                .map(|entry| &entry.command)
        );
    }

    /// The heatmap legend is pinned 22 cells left of the panel's right edge;
    /// under ~28 columns it escapes the panel and overwrites the frame and
    /// weekday labels.
    #[test]
    fn stats_heatmap_legend_stays_inside_its_panel() {
        let mut model = AppModel::new(20, 24);
        model.view = View::Statistics(StatsView {
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
            daily: Vec::new(),
        });
        let rows = screen_rows(&model);
        let legend_row = rows
            .iter()
            .find(|row| row.contains("Less"))
            .expect("legend row");
        let margin: String = legend_row.chars().take(2).collect();
        assert_eq!(
            margin.trim(),
            "",
            "legend bled onto the left screen margin: {legend_row:?}"
        );
    }

    /// Message/Help scroll is clamped to `lines.len() - 1` instead of
    /// `lines.len() - visible_rows`: deep scrolling leaves the last line alone
    /// at the top of a mostly-empty panel.
    #[test]
    fn message_overlay_scroll_is_bounded_by_visible_rows() {
        let mut model = AppModel::new(80, 40);
        model.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: (0..10).map(|i| format!("line {i}")).collect(),
            scroll: 0,
        };
        for _ in 0..20 {
            assert_eq!(
                lomo_tui::input_update::apply(&mut model, &Command::Scroll(1)),
                None
            );
        }
        let InputMode::Message { scroll, lines, .. } = &model.input else {
            panic!("message open");
        };
        let inner_height = usize::from(lomo_tui::overlays::overlay_area(&model).height) - 2;
        assert!(
            *scroll <= lines.len().saturating_sub(inner_height),
            "scroll {scroll} leaves fewer than a page of lines ({lines:?})"
        );
    }

    /// The reader's scroll anchor can point past the wrapped rows after a body
    /// shrinks across revisions: `anchor_row` lands on the final row and the
    /// reader shows a one-line tail page instead of a full screen.
    #[test]
    fn reader_keeps_a_full_page_when_the_anchor_outlives_the_body() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        let card: MemoCard = memo("memo-0", "one\ntwo\nthree").expect("memo");
        model.view = View::Reader {
            memo: card,
            anchor: TextAnchor {
                line: 900,
                grapheme: 0,
            },
        };
        let page = lomo_tui::reader::page(&model).expect("reader page");
        let max_top = page
            .rows
            .len()
            .saturating_sub(usize::from(page.area.height));
        assert!(
            page.top <= max_top,
            "reader top {} beyond the last full page {max_top}",
            page.top
        );
    }

    /// No terminal size may panic or leave the confirm dialog without its
    /// prompt: at two border rows the prompt is unreachable but `y` still
    /// deletes.
    #[test]
    fn tiny_terminals_never_panic_and_keep_dialog_prompts() {
        for (width, height) in [(1, 1), (4, 3), (8, 2), (20, 5), (30, 1)] {
            let mut model = model_with_memos(2, width, height).expect("feed");
            render(&model);
            assert_eq!(apply_command(&mut model, Command::Palette), None);
            render(&model);
            assert_eq!(apply_command(&mut model, Command::Back), None);
            assert_eq!(apply_command(&mut model, Command::Help), None);
            render(&model);
            assert_eq!(apply_command(&mut model, Command::Back), None);
        }
        let mut model = model_with_memos(1, 80, 6).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Delete), None);
        let text = render(&model);
        assert!(
            text.contains("trash") || text.contains("回收站"),
            "confirm prompt must be visible at 80x6, got: {text}"
        );
    }

    /// `draw_field` renders the wrapped row containing the cursor; a grapheme
    /// wider than the field becomes `□` on row 0 while `cursor_position`
    /// reports row 1 — the field goes blank on a width-1 CJK input.
    #[test]
    fn field_cursor_row_matches_the_wrapped_content() {
        let (row, _) = lomo_tui::text_layout::cursor_position("中", 1);
        let wrapped =
            lomo_tui::text_layout::wrap_lines(&lomo_tui::text_layout::plain_lines("中"), 1);
        assert!(
            wrapped.get(row).is_some(),
            "cursor row {row} has no rendered line: {wrapped:?}"
        );
    }

    /// Help lines are truncated, not wrapped: inside a 40-column terminal the
    /// longest help rows lose their tails mid-hint.
    #[test]
    fn help_text_survives_a_narrow_overlay() {
        let s = lomo_tui::i18n::UiStrings::detect();
        let longest = lomo_tui::overlays::help(s)
            .iter()
            .map(|line| unicode_width::UnicodeWidthStr::width(line.as_str()))
            .max()
            .unwrap_or(0);
        let mut model = model_with_memos(1, 44, 24).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Help), None);
        let inner_width =
            usize::from(lomo_tui::overlays::overlay_area(&model).width).saturating_sub(2);
        assert!(
            longest <= inner_width,
            "help lines ({longest} cells) exceed the inner width {inner_width}"
        );
    }

    /// A failed action leaves its diagnostic in the status bar exactly until
    /// the next command; the feed's `LoadStatus::Failed` is then invisible
    /// while stale rows keep rendering. The failure must stay discoverable.
    #[test]
    fn feed_failure_stays_visible_after_the_status_clears() {
        let mut model = model_with_memos(2, 80, 24).expect("feed");
        feed_mut(&mut model).expect("feed").load =
            LoadStatus::Failed("disk unavailable".to_owned());
        let text = render(&model);
        assert!(
            text.contains("disk unavailable"),
            "a failed load with stale rows shows no badge: {text}"
        );
    }

    /// A long settings value wraps instead of clipping mid-path: every
    /// character the value carries stays on screen (C-10).
    #[test]
    fn settings_values_survive_the_reading_column() {
        let mut model = AppModel::new(60, 24);
        let long = "/".repeat(80);
        model.view = View::Settings(lomo_tui::settings::SettingsView {
            file: PathBuf::from("/cfg/lomo/config.toml"),
            rows: vec![lomo_tui::settings::SettingRow {
                field: lomo_tui::config::SettingsField::Workspace,
                value: long.clone(),
                hot: false,
            }],
            selected: 0,
            info: Vec::new(),
            home_dir: None,
        });
        let text = render(&model);
        let drawn = text.matches('/').count();
        assert!(
            drawn >= long.len(),
            "the full value must wrap onto the screen, not clip at the column: {text}"
        );
    }

    /// `draw_rows` (Tasks/Attachments) blanks the pane when `selected` outlives
    /// `items`: the scroll offset is derived from the stale index.
    #[test]
    fn row_views_survive_a_stale_selection() {
        let mut model = AppModel::new(80, 24);
        model.view = View::Tasks(SelectionList {
            items: vec![TaskRow {
                memo_id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                line: 0,
                text: "task".to_owned(),
                date: "2026-09-11".to_owned(),
                done: false,
            }],
            selected: 9,
            scroll: 0,
        });
        let text = render(&model);
        assert!(
            text.contains("task"),
            "stale selection blanked the view: {text}"
        );
    }

    /// When the selected memo's only visible row is its trailing `Gap`, the
    /// marker rule `position != Gap` leaves the whole screen without a
    /// selection mark — the feed looks unselected while Enter opens that memo.
    /// The mark survives as the *weakened* `▎` (dimmed, not the full selection
    /// color): the gap is the card's own row, not its content (C-07).
    #[test]
    fn selected_memo_keeps_a_visible_mark_while_its_gap_anchors_the_scroll() {
        let mut model = model_with_memos(3, 80, 24).expect("feed");
        let feed = feed_mut(&mut model).expect("feed");
        feed.selected = feed.memos.first().map(|memo| memo.id.clone());
        feed.anchor = feed.selected.clone().map(|id| lomo_tui::model::MemoAnchor {
            id,
            position: lomo_tui::model::CardPosition::Gap,
        });
        let text = render(&model);
        assert!(
            text.contains('▎'),
            "the selected memo has no visible mark: {text}"
        );
        // The only visible row of the selected card is its gap, so every
        // mark on screen is that weakened one.
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
            .expect("fixture and operation must succeed");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, &model))
            .expect("fixture and operation must succeed");
        let buffer = terminal.backend().buffer();
        let marks: Vec<(u16, u16)> = (0..model.height)
            .flat_map(|y| (0..model.width).map(move |x| (x, y)))
            .filter(|&(x, y)| buffer[(x, y)].symbol() == "▎")
            .collect();
        assert!(!marks.is_empty(), "the gap row must carry the mark");
        assert!(
            marks
                .iter()
                .all(|&(x, y)| buffer[(x, y)].fg == Color::DarkGray),
            "a gap-anchored mark is the weakened ▎, not the full selection \
             color: {marks:?}"
        );
    }

    /// The heatmap palette is chosen by the terminal's color capability, not
    /// hardcoded to truecolor: without a truecolor signal the ramp drops to
    /// indexed approximations, and below that to named ANSI colors — a cell
    /// never asks for a color the terminal cannot express (C-15).
    #[test]
    fn heatmap_scale_degrades_below_truecolor() {
        let scale = lomo_tui::stats_draw::heat_scale_for;
        assert!(
            scale(Some("truecolor"), Some("xterm"))
                .iter()
                .any(|color| matches!(color, Color::Rgb(..))),
            "a truecolor terminal keeps the tuned ramp"
        );
        let indexed = scale(None, Some("xterm-256color"));
        assert!(
            indexed
                .iter()
                .all(|color| matches!(color, Color::Indexed(_))),
            "a 256-color terminal must not receive raw Rgb cells: {indexed:?}"
        );
        for (term, colorterm) in [("vt100", None), ("xterm", None), ("screen", None)] {
            let basic = scale(colorterm, Some(term));
            assert!(
                basic
                    .iter()
                    .all(|color| !matches!(color, Color::Rgb(..) | Color::Indexed(_))),
                "TERM={term} gets named colors only: {basic:?}"
            );
        }
        assert!(
            scale(None, None)
                .iter()
                .all(|color| !matches!(color, Color::Rgb(..) | Color::Indexed(_))),
            "no color signal degrades all the way"
        );
    }

    /// Vertical cursor movement must wrap by the width the field is *drawn*
    /// at — the overlay inner, the search panel's inset — never the screen
    /// width. `model.width` produces phantom wrap rows the caret then chases
    /// (C-17). At 120 columns the drawn widths are: overlay inner 70, the
    /// wizard's labeled value column 57, the search field 90, the composer 92.
    #[test]
    fn field_cursor_wraps_at_the_rendered_width() {
        let down = Command::Edit(lomo_tui::event::TextEdit::Down);

        let mut model = AppModel::new(120, 24);
        model.input = InputMode::Picker(Picker {
            kind: PickerKind::Dates,
            text: TextBuffer::new("x".repeat(80)),
            selected: 0,
            identity: None,
        });
        let InputMode::Picker(picker) = &mut model.input else {
            panic!("picker")
        };
        picker.text.set_cursor(0);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("picker")
        };
        assert_eq!(
            picker.text.before_cursor().len(),
            70,
            "Down wraps by the picker's drawn field width, not the screen's"
        );

        let mut model = AppModel::new(120, 24);
        model.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("x".repeat(80)),
            error: None,
        };
        let InputMode::Date { text, .. } = &mut model.input else {
            panic!("date")
        };
        text.set_cursor(0);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        let InputMode::Date { text, .. } = &model.input else {
            panic!("date")
        };
        assert_eq!(text.before_cursor().len(), 70);

        let mut model = AppModel::new(120, 24);
        model.input = InputMode::Setting(SettingsEdit::new(
            lomo_tui::config::SettingsField::Workspace,
            &"x".repeat(80),
        ));
        let InputMode::Setting(edit) = &mut model.input else {
            panic!("setting")
        };
        edit.text.set_cursor(0);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        let InputMode::Setting(edit) = &model.input else {
            panic!("setting")
        };
        assert_eq!(edit.text.before_cursor().len(), 70);

        // The wizard's value column starts after the `marker + key` label.
        let mut model = AppModel::new(120, 24);
        let mut setup = SetupState::new(
            PathBuf::from("/cfg/lomo/config.toml"),
            lomo_tui::config::ConfigProposal {
                workspace: PathBuf::from("/home/u/Notes"),
                time_zone: "UTC".to_owned(),
                previously_initialized: false,
                recorded_workspace: None,
            },
            Req(0),
            Some(PathBuf::from("/home/u")),
        );
        setup.workspace = TextBuffer::new("x".repeat(80));
        setup.workspace.set_cursor(0);
        model.input = InputMode::Setup(setup);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        let InputMode::Setup(setup) = &model.input else {
            panic!("setup")
        };
        assert_eq!(
            setup.workspace.before_cursor().len(),
            57,
            "the wizard field wraps after its 13-column label"
        );

        let mut model = AppModel::new(120, 24);
        model.input = InputMode::Search {
            text: TextBuffer::new("x".repeat(100)),
        };
        let InputMode::Search { text } = &mut model.input else {
            panic!("search")
        };
        text.set_cursor(0);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        let InputMode::Search { text } = &model.input else {
            panic!("search")
        };
        assert_eq!(
            text.before_cursor().len(),
            90,
            "the search field wraps inside its bordered panel"
        );

        // The composer already derives its field width — a control row that
        // must stay green.
        let mut model = AppModel::new(120, 24);
        model.input = InputMode::Compose;
        model.draft.text = TextBuffer::new("x".repeat(100));
        model.draft.text.set_cursor(0);
        assert_eq!(lomo_tui::input_update::apply(&mut model, &down), None);
        assert_eq!(model.draft.text.before_cursor().len(), 92);
    }

    /// Every view and overlay paints inside the buffer at every height —
    /// ratatui panics on an out-of-bounds cell write, so a clean draw is
    /// itself the clip-contract proof. The sweep is exhaustive (h = 1..=24
    /// over common widths × every view × every input layer), not sampled:
    /// a corner that is only safe at large sizes is not safe (I6).
    #[test]
    fn every_view_and_overlay_survive_every_height() {
        const WIDTHS: [u16; 10] = [1, 4, 8, 20, 30, 44, 60, 80, 96, 120];
        let mut painted = 0usize;
        for height in 1..=24 {
            for width in WIDTHS {
                for (name, model) in scenarios(width, height) {
                    let frame =
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| render(&model)))
                            .unwrap_or_else(|_| {
                                panic!("{name} frame panicked at {width}x{height}")
                            });
                    if width >= 8 {
                        assert!(
                            frame.chars().any(|cell| !cell.is_whitespace()),
                            "{name} painted a blank {width}x{height} frame"
                        );
                    }
                    painted += 1;
                }
            }
        }
        assert!(
            painted > 4_000,
            "the sweep is exhaustive, not sampled: {painted} frames"
        );
    }

    /// The (view × input) matrix `every_view_and_overlay_survive_every_height`
    /// walks, rebuilt at each size so state can never leak between frames.
    fn scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models = feed_scenarios(width, height);
        models.extend(panel_scenarios(width, height));
        models.extend(field_input_scenarios(width, height));
        models.extend(dialog_scenarios(width, height));
        models
    }

    /// A fresh four-memo feed at this size — every scenario owns its model.
    fn feed(width: u16, height: u16) -> AppModel {
        model_with_memos(4, width, height).expect("feed fixture")
    }

    /// The feed and reader views, including a pending image payload.
    fn feed_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        vec![
            ("feed/browse", feed(width, height)),
            ("feed/filtered", {
                let mut model = feed(width, height);
                feed_mut(&mut model).expect("feed").query.text = "needle".to_owned();
                model
            }),
            ("feed/failed+badges", {
                let mut model = feed(width, height);
                feed_mut(&mut model).expect("feed").load =
                    LoadStatus::Failed("disk unavailable".to_owned());
                model.set_status("a stale toast");
                model.raise_badge(
                    Severity::Error,
                    BadgeClass::Watch,
                    "watcher offline".to_owned(),
                );
                model.raise_badge(
                    Severity::Warn,
                    BadgeClass::Sync,
                    "reconcile failed".to_owned(),
                );
                model
            }),
            ("reader", {
                let mut model = feed(width, height);
                model.view = View::Reader {
                    memo: memo("m-1", "first 中文 line\nsecond\nthird").expect("memo"),
                    anchor: TextAnchor {
                        line: 1,
                        grapheme: 0,
                    },
                };
                model
            }),
            ("reader/pending-image", {
                let mut model = feed(width, height);
                let card = memo("m-1", "before\n![pic](media/pic.png)\nafter").expect("memo");
                model.images.push(lomo_tui::graphics::ReaderImage {
                    request: lomo_tui::graphics::ImageRequest {
                        version: card.version(),
                        path: lomo_core::RelativeWorkspacePath::parse("media/pic.png")
                            .expect("relative path"),
                        columns: 20,
                        rows: 8,
                        picker: lomo_tui::graphics::SharedPicker::new(
                            ratatui_image::picker::Picker::from_fontsize((8, 16)),
                        ),
                    },
                    state: lomo_tui::graphics::ImageState::Pending,
                });
                model.view = View::Reader {
                    memo: card,
                    anchor: TextAnchor::default(),
                };
                model
            }),
        ]
    }

    /// The row-list and dashboard views: tasks, attachments, statistics,
    /// settings, and the two non-content states.
    fn panel_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        vec![
            ("tasks", {
                let mut model = feed(width, height);
                model.view = View::Tasks(SelectionList::new(vec![TaskRow {
                    memo_id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                    line: 0,
                    text: "todo 项".to_owned(),
                    date: "2026-09-11".to_owned(),
                    done: false,
                }]));
                model
            }),
            ("attachments", {
                let mut model = feed(width, height);
                model.view = View::Attachments(SelectionList::new(vec![AttachmentRow {
                    path: lomo_core::RelativeWorkspacePath::parse("media/pic.png")
                        .expect("relative path"),
                    owners: vec!["2026-09-11".to_owned()],
                }]));
                model
            }),
            ("statistics", {
                let mut model = feed(width, height);
                model.view = View::Statistics(StatsView {
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
                });
                model
            }),
            ("settings", {
                let mut model = feed(width, height);
                model.view = View::Settings(lomo_tui::settings::SettingsView {
                    file: PathBuf::from("/cfg/lomo/config.toml"),
                    rows: vec![lomo_tui::settings::SettingRow {
                        field: lomo_tui::config::SettingsField::Workspace,
                        value: "/a/rather/long/workspace/path/that/wraps".to_owned(),
                        hot: false,
                    }],
                    selected: 0,
                    info: vec!["device lomo-test".to_owned()],
                    home_dir: None,
                });
                model
            }),
            ("loading", {
                let mut model = feed(width, height);
                model.view = View::Loading {
                    screen: Screen::Statistics,
                    req: Req(7),
                };
                model
            }),
            ("failed", {
                let mut model = feed(width, height);
                model.view = View::Failed {
                    screen: Screen::Timeline,
                    diagnostic: "projection unreadable".to_owned(),
                };
                model
            }),
        ]
    }

    /// Text-field input layers: composer, search, the pickers, and the date
    /// dialog.
    fn field_input_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models: Vec<(&'static str, AppModel)> = Vec::new();
        let mut compose = feed(width, height);
        compose.input = InputMode::Compose;
        compose.draft.text = TextBuffer::new("draft 中文 with #tag".to_owned());
        models.push(("input/compose", compose));
        let mut compose_failed = feed(width, height);
        compose_failed.input = InputMode::Compose;
        compose_failed.draft.save = lomo_tui::model::SaveState::Failed {
            diagnostic: "disk full".to_owned(),
        };
        models.push(("input/compose-failed", compose_failed));
        let mut search = feed(width, height);
        search.input = InputMode::Search {
            text: TextBuffer::new("needle 中文".to_owned()),
        };
        models.push(("input/search", search));
        let mut palette = feed(width, height);
        palette.input = InputMode::Picker(Picker {
            kind: PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(memo("m-9", "actionable").expect("memo"))),
                scope: PaletteScope::Item,
            },
            text: TextBuffer::new("x".to_owned()),
            selected: 0,
            identity: None,
        });
        models.push(("input/picker-palette", palette));
        let mut tags = feed(width, height);
        tags.set_tags(vec!["alpha".to_owned(), "beta/gamma".to_owned()]);
        tags.input = InputMode::Picker(Picker {
            kind: PickerKind::Tags(lomo_application::TagSelectionMode::Subtree),
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        models.push(("input/picker-tags", tags));
        let mut dates = feed(width, height);
        dates.input = InputMode::Picker(Picker {
            kind: PickerKind::Dates,
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        models.push(("input/picker-dates", dates));
        let mut history = feed(width, height);
        history.input = InputMode::Picker(Picker {
            kind: PickerKind::History {
                id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                revisions: vec![RevisionRow {
                    revision: 2,
                    stamp: "2026-09-10 08:00:00".to_owned(),
                    preview: "older body".to_owned(),
                }],
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        models.push(("input/picker-history", history));
        let mut date = feed(width, height);
        date.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("2026-13-40".to_owned()),
            error: Some("day out of range".to_owned()),
        };
        models.push(("input/date", date));
        models
    }

    /// Confirmation, settings-edit, setup, message, and help dialogs — each
    /// owns a fresh model so a mutation in one frame never leaks onward.
    fn dialog_scenarios(width: u16, height: u16) -> Vec<(&'static str, AppModel)> {
        let mut models: Vec<(&'static str, AppModel)> = Vec::new();
        for (name, confirmation) in [
            (
                "input/confirm-delete",
                Confirmation::Delete {
                    memo: Box::new(memo("m-2", "doomed body").expect("memo")),
                },
            ),
            (
                "input/confirm-delete-forever",
                Confirmation::DeleteForever(Box::new(memo("m-2", "doomed").expect("memo"))),
            ),
            (
                "input/confirm-empty-trash",
                Confirmation::EmptyTrash { count: Some(7) },
            ),
            (
                "input/confirm-restore",
                Confirmation::Restore(Box::new(memo("m-2", "restored").expect("memo"))),
            ),
            (
                "input/confirm-restore-revision",
                Confirmation::RestoreRevision {
                    id: lomo_workspace::MemoId::parse("m-1").expect("id"),
                    revision: 7,
                    stamp: "2026-09-10 08:00:00".to_owned(),
                    preview: "older body".to_owned(),
                },
            ),
            (
                "input/confirm-discard",
                Confirmation::DiscardDraft {
                    preview: "draft first line".to_owned(),
                },
            ),
        ] {
            let mut model = feed(width, height);
            model.input = InputMode::Confirm(confirmation);
            models.push((name, model));
        }
        let mut setting = feed(width, height);
        setting.input = InputMode::Setting(SettingsEdit {
            field: lomo_tui::config::SettingsField::Workspace,
            text: TextBuffer::new("/a/very/long/workspace/path".to_owned()),
            error: Some("not an absolute path".to_owned()),
        });
        models.push(("input/setting", setting));
        let mut setup = feed(width, height);
        setup.input = InputMode::Setup(SetupState::new(
            PathBuf::from("/cfg/lomo/config.toml"),
            lomo_tui::config::ConfigProposal {
                workspace: PathBuf::from("/home/u/Notes"),
                time_zone: "UTC".to_owned(),
                previously_initialized: true,
                recorded_workspace: Some(PathBuf::from("/old/Notes")),
            },
            Req(0),
            Some(PathBuf::from("/home/u")),
        ));
        models.push(("input/setup", setup));
        let mut message = feed(width, height);
        message.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: (0..40)
                .map(|line| format!("line {line} — 中文尾缀"))
                .collect(),
            scroll: 3,
        };
        models.push(("input/message", message));
        let mut deep_scroll = feed(width, height);
        deep_scroll.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: (0..40).map(|line| format!("line {line}")).collect(),
            scroll: 999,
        };
        models.push(("input/message-deep-scroll", deep_scroll));
        let mut help = feed(width, height);
        help.input = InputMode::Help { scroll: 0 };
        models.push(("input/help", help));
        let mut help_deep = feed(width, height);
        help_deep.input = InputMode::Help { scroll: 999 };
        models.push(("input/help-deep-scroll", help_deep));
        models
    }
}
