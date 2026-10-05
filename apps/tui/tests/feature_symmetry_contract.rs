// adversarial-audit: feature completeness and operational symmetry probes.
// Every test asserts the user-visible behavior a complete feature should have;
// each failure is evidence of a dead end, a one-way operation, or silent state loss.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, model_with_memos};
    use lomo_tui::{
        effects::{Effect, RuntimeMessage},
        event::Command,
        input::TextBuffer,
        messages::apply_message,
        model::{
            AppModel, Confirmation, InputMode, PendingKind, Picker, PickerKind, RevisionRow,
            Screen, View,
        },
        ops::{bootstrap_model, execute},
        update::apply_command,
    };
    use ratatui::{Terminal, backend::TestBackend};

    fn render(model: &AppModel) -> Result<String, Box<dyn std::error::Error>> {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))?;
        terminal.draw(|frame| lomo_tui::ui::draw(frame, model))?;
        Ok(terminal
            .backend()
            .buffer()
            .content
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect())
    }

    fn history_picker(model: &mut AppModel, revisions: Vec<RevisionRow>) {
        let id = model.selected_memo().map_or_else(
            || lomo_workspace::MemoId::parse("memo-0").expect("memo id"),
            |memo| memo.id.clone(),
        );
        let req = model.request(PendingKind::History { id: id.clone() });
        let applied = apply_message(model, RuntimeMessage::History { req, id, revisions });
        assert!(applied.is_none());
        assert!(
            matches!(model.input, InputMode::Picker(ref picker) if matches!(picker.kind, PickerKind::History { .. })),
            "the history reply opens its picker while browsing: {:?}",
            model.input
        );
    }

    /// `menu.rs:208` keeps one static "Toggle pin" label while `task_actions` right
    /// below it switches labels on `task.done`. A pinned memo must offer Unpin —
    /// the user must be able to SEE the inverse operation exists.
    #[test]
    fn a_pinned_memo_advertises_unpin_instead_of_repeating_toggle_pin() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let pin_label = |model: &mut AppModel| {
            assert_eq!(apply_command(model, Command::Actions), None);
            let InputMode::Picker(picker) = &model.input else {
                panic!("actions picker");
            };
            let label = lomo_tui::menu::entries(model, picker)
                .iter()
                .find(|entry| entry.command == Command::Pin)
                .map(|entry| entry.label.clone());
            model.input = InputMode::Browse;
            label.expect("a pin entry")
        };
        let unpinned = pin_label(&mut model);
        super::support::feed_mut(&mut model)
            .expect("feed")
            .memos
            .first_mut()
            .expect("memo")
            .pinned = true;
        let pinned = pin_label(&mut model);
        assert_ne!(
            unpinned, pinned,
            "the pin action must name the inverse once the memo is pinned; \
             both states render {unpinned:?}"
        );
    }

    /// `menu.rs:449` labels a row "Close" but binds `Command::Back`, which
    /// `input_update.rs:260` forwards to `go_back`: it pops the underlying VIEW
    /// (or clears its filters) instead of merely closing the picker — one "Close"
    /// button that silently performs screen navigation.
    #[test]
    fn the_history_picker_close_row_closes_only_the_picker() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(matches!(model.view, View::Reader { .. }), "reader");
        history_picker(
            &mut model,
            vec![RevisionRow {
                revision: 1,
                stamp: String::new(),
                preview: "old body".to_owned(),
            }],
        );
        let InputMode::Picker(picker) = &model.input else {
            panic!("history picker");
        };
        let close = lomo_tui::menu::entries(&model, picker)
            .iter()
            .position(|entry| entry.command == Command::DismissPicker)
            .expect("a close row");
        assert_eq!(apply_command(&mut model, Command::Move(i32::MAX)), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("history picker");
        };
        assert_eq!(picker.selected, close, "the last row is Close");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert_eq!(model.input, InputMode::Browse, "the picker closed");
        assert!(
            matches!(model.view, View::Reader { .. }),
            "Close must not pop the view underneath the picker; view became {:?}",
            model.view
        );
    }

    /// Same defect on a filtered feed: Close clears the user's filters because
    /// `go_back` peels the filter layer before the view layer.
    #[test]
    fn the_history_picker_close_row_preserves_active_filters() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture");
        super::support::feed_mut(&mut model)
            .expect("feed")
            .query
            .text = "needle".to_owned();
        history_picker(
            &mut model,
            vec![RevisionRow {
                revision: 1,
                stamp: String::new(),
                preview: "old body".to_owned(),
            }],
        );
        assert_eq!(apply_command(&mut model, Command::Move(i32::MAX)), None);
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let query = &super::support::feed(&model).expect("feed").query;
        assert_eq!(
            query.text, "needle",
            "closing a picker must not clear the filters underneath"
        );
    }

    /// update.rs:338-342 pushes the current view unconditionally: choosing
    /// "Timeline" while already on the timeline stacks a duplicate and forces a
    /// reload, so Esc "goes back" to the screen you never left.
    #[test]
    fn navigating_to_the_current_screen_does_not_duplicate_history() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let _effect = apply_command(&mut model, Command::Goto(Screen::Timeline));
        assert!(
            model.history.is_empty(),
            "Goto on the current screen must be a no-op, not a stacked duplicate: {:?}",
            model.history
        );
    }

    /// `update.rs:218-226` implements preset dates by OPENING the custom-date
    /// dialog (`InputMode::Date`) so its ticket can bind the reply — the dialog
    /// visibly flashes for one roundtrip after every preset pick.
    #[test]
    fn a_preset_date_applies_without_flashing_the_custom_date_dialog() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let effect = apply_command(&mut model, Command::SetDate("today".to_owned()));
        assert!(matches!(effect, Some(Effect::Date { .. })));
        assert!(
            !matches!(model.input, InputMode::Date { .. }),
            "a preset must apply directly; the custom-date dialog is visible state: {:?}",
            model.input
        );
    }

    /// input_update.rs:216-219 replaces the input with Browse before it knows a
    /// row exists: Enter on a picker that filtered to zero entries silently
    /// closes it — an unlabeled Esc on a reachable path.
    #[test]
    fn enter_on_an_empty_picker_does_not_silently_close_it() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let card = model.selected_memo().expect("memo").clone();
        assert!(card.attachments.is_empty(), "fixture has no attachments");
        model.input = InputMode::Picker(Picker {
            kind: PickerKind::Attachments(Box::new(card)),
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(
            matches!(model.input, InputMode::Picker(_)),
            "Enter with nothing selected must not silently close the picker"
        );
    }

    /// The mutation executor answers every state-changing effect with the same
    /// "Saved"/"已保存" (mutations.rs:186-190): pin and unpin — opposite
    /// operations — report identical outcomes, so nothing confirms what changed.
    #[test]
    fn pin_and_unpin_report_different_outcomes() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(1).expect("seed");
        let model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("model");
        let id = model.selected_memo().expect("memo").id.clone();
        let (results, _inbox) = std::sync::mpsc::sync_channel(256);
        // The same request identity on both effects keeps the comparison
        // honest: any difference must come from the reported outcome itself.
        let req = lomo_tui::model::Req(1);
        let outbox = lomo_tui::executor::Outbox::new(results);
        let token = lomo_tui::model::CancelToken::live();
        let pinned = execute(
            &fixture.runtime,
            &Effect::Pin {
                req,
                id: id.clone(),
                pinned: true,
            },
            &outbox,
            &token,
        )
        .expect("pin reply");
        let unpinned = execute(
            &fixture.runtime,
            &Effect::Pin {
                req,
                id,
                pinned: false,
            },
            &outbox,
            &token,
        )
        .expect("unpin reply");
        assert_ne!(
            pinned, unpinned,
            "pinning and unpinning are opposite mutations and must report \
             different outcomes; both replied {pinned:?}"
        );
    }

    /// A reader on a trashed memo renders the hint "e edit  m pin" (`ui.rs:577-580`)
    /// while both commands are silent no-ops (`update.rs:457-459,464-465`): the
    /// hint bar advertises dead keys.
    #[test]
    fn a_trashed_reader_advertises_only_available_actions() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let mut card = model.selected_memo().expect("memo").clone();
        card.trashed = true;
        model.view = View::Reader {
            memo: card,
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        assert_eq!(
            apply_command(&mut model, Command::Pin),
            None,
            "pin is a no-op on a trashed memo"
        );
        assert_eq!(
            apply_command(&mut model, Command::ExternalEdit),
            None,
            "external edit is a no-op on a trashed memo"
        );
        let text = render(&model).expect("render");
        assert!(
            !text.contains("m pin") && !text.contains("e edit"),
            "the hint bar must not advertise actions the view cannot run: {text}"
        );
    }

    /// `cli.rs:17-28` advertises `t`, `c` and `Ctrl+p`; `event.rs:178-233` maps none
    /// of them (`tea_contract.rs:73-88` asserts they produce no command). `--help`
    /// documents dead keys the app never honors.
    #[test]
    fn cli_help_does_not_advertise_dead_keys() {
        let help = lomo_tui::cli::render_help();
        assert!(
            !help.contains("Ctrl+p"),
            "Ctrl+p produces no command in any mode but --help advertises it"
        );
        assert!(
            !help.contains("/ t c"),
            "t and c produce no command while browsing but --help advertises them"
        );
    }

    /// `field_key` maps Up/Down to `Command::Move` in every text mode
    /// (`event.rs:267-268`), but the Search arm of `input_update` routes it into
    /// `edit_field` — which ignores `Move` — then returns None: arrows are mapped
    /// dead keys while a result list sits right there.
    #[test]
    fn arrow_keys_move_the_selection_while_a_search_is_open() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::Search), None);
        let before = super::support::feed(&model).expect("feed").selected.clone();
        let effect = apply_command(&mut model, Command::Move(1));
        let after = super::support::feed(&model).expect("feed").selected.clone();
        assert!(
            effect.is_some() || before != after,
            "Down during search is a mapped command that lands nowhere: \
             effect {effect:?}, selection stayed {before:?}"
        );
    }

    /// `input_update.rs:46-49` resets `picker.selected` to 0 for ANY command that
    /// is not Move/Scroll — including `FocusReconcile`, which arrives on every
    /// terminal `FocusGained` while a picker is open, and cursor edits that leave
    /// the filter text untouched. Unrelated events silently lose the selection.
    #[test]
    fn focus_return_does_not_reset_the_picker_selection() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.set_tags((0..10).map(|index| format!("tag{index}")).collect());
        assert!(matches!(
            apply_command(&mut model, Command::Tags),
            Some(Effect::Tags { .. })
        ));
        assert_eq!(apply_command(&mut model, Command::Move(5)), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("tag picker");
        };
        assert_eq!(picker.selected, 5);
        // Focus regain is a system event — it reaches `focus_reconcile`
        // directly, never `apply_command` (A-09).
        assert_eq!(lomo_tui::update::focus_reconcile(&mut model), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("tag picker");
        };
        assert_eq!(
            picker.selected, 5,
            "a focus event must not move the picker selection back to the top"
        );
        assert_eq!(
            apply_command(&mut model, Command::Edit(lomo_tui::event::TextEdit::Left)),
            None
        );
        let InputMode::Picker(picker) = &model.input else {
            panic!("tag picker");
        };
        assert_eq!(
            picker.selected, 5,
            "a cursor edit that changes no filter text must not reset the selection"
        );
    }

    /// A picker for a memo with zero revisions keeps only the synthetic "Close"
    /// row (`menu.rs:449`), so the "No earlier revisions" empty state in
    /// `overlays.rs:311-312` is unreachable — dead copy for a reachable state.
    #[test]
    fn a_memo_without_revisions_shows_the_empty_state() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        history_picker(&mut model, Vec::new());
        let expected =
            lomo_tui::i18n::UiStrings::detect().text("No earlier revisions", "没有更早的版本");
        let text = render(&model).expect("render");
        assert!(
            text.contains(expected),
            "a revisionless memo must show the empty state, not a lone Close row: {text}"
        );
    }

    /// Opening an attachment answers with `RuntimeMessage::Message`
    /// (`ops.rs:264-269`), which `present_notice` turns into a modal overlay that
    /// seizes input — a success toast that blocks the UI until dismissed.
    #[test]
    fn opening_an_attachment_reports_a_status_not_a_modal() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let req = model.request(PendingKind::Attachment);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Message {
                    req,
                    title: "Attachment opened".to_owned(),
                    lines: vec!["media/a.png".to_owned()],
                }
            ),
            None
        );
        assert_eq!(
            model.input,
            InputMode::Browse,
            "a success notice must be a status-line toast, not a modal overlay"
        );
    }

    /// Ctrl+S on an empty composer returns None with no status
    /// (`input_update.rs:84-87`): a reachable shortcut gives zero feedback.
    #[test]
    fn ctrl_s_on_an_empty_draft_explains_itself() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.input = InputMode::Compose;
        let effect = apply_command(&mut model, Command::Commit);
        assert!(
            effect.is_some() || model.status.is_some(),
            "Ctrl+S on an empty draft is a silent dead key: {effect:?}"
        );
    }

    /// `ShowCreated` on a memo that no longer exists resolves as a typed
    /// `MemoGone` receipt — a lookup miss is feedback in context (a toast on
    /// the feed you came from), never a full-screen `Failed` error (I9).
    #[test]
    fn viewing_a_deleted_saved_memo_keeps_the_reading_context() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(1).expect("seed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("model");
        let id = model.selected_memo().expect("memo").id.clone();
        command(&fixture.runtime, &mut model, Command::Delete).expect("trash the memo");
        command(&fixture.runtime, &mut model, Command::Accept).expect("confirm trash");
        command(&fixture.runtime, &mut model, Command::Goto(Screen::Trash)).expect("trash view");
        command(&fixture.runtime, &mut model, Command::Delete).expect("delete forever");
        command(&fixture.runtime, &mut model, Command::Accept).expect("confirm forever");
        command(&fixture.runtime, &mut model, Command::Back).expect("timeline");
        model.last_created = Some(id.clone());
        let effect = apply_command(&mut model, Command::ShowCreated);
        let Some(Effect::ReadMemo { req, .. }) = effect else {
            panic!("a stale last_created still issues the read");
        };
        let reply = execute(
            &fixture.runtime,
            &Effect::ReadMemo { req, id },
            &lomo_tui::executor::Outbox::new(std::sync::mpsc::sync_channel(256).0),
            &lomo_tui::model::CancelToken::live(),
        )
        .expect("a resolved miss is a receipt, not a failure");
        assert!(
            matches!(reply, RuntimeMessage::MemoGone { .. }),
            "a missing memo is a typed MemoGone, not a Failed: {reply:?}"
        );
        assert_eq!(apply_message(&mut model, reply), None);
        assert!(
            !matches!(model.view, View::Failed { .. }),
            "a missing memo must be a status in context, not a Failed screen: {:?}",
            model.view
        );
        assert!(
            model.status.is_some(),
            "the miss leaves a status toast in the feed context"
        );
    }

    /// Control probe: an invalid custom date DOES surface — the Date dialog
    /// stays open on the request it issued and shows the parse error inline.
    /// Passes today; pins the dialog-bound failure contract.
    #[test]
    fn a_rejected_custom_date_stays_in_the_dialog_with_its_error() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::CustomDate), None);
        assert_eq!(
            apply_command(&mut model, Command::Type("not-a-date".to_owned())),
            None
        );
        let effect = apply_command(&mut model, Command::Accept);
        let Some(Effect::Date { req, text }) = effect else {
            panic!("date request");
        };
        let error = execute(
            &fixture.runtime,
            &Effect::Date { req, text },
            &lomo_tui::executor::Outbox::new(std::sync::mpsc::sync_channel(256).0),
            &lomo_tui::model::CancelToken::live(),
        )
        .expect_err("invalid dates are rejected");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: error.to_string(),
                }
            ),
            None
        );
        let InputMode::Date { error: shown, .. } = &model.input else {
            panic!("the dialog stays open");
        };
        assert!(shown.is_some(), "the parse failure is visible inline");
    }

    /// Control probe: `DiscardDraft` is reachable, confirmed, and clears the
    /// draft — passes today and pins the destructive-guard contract.
    #[test]
    fn discarding_a_draft_stays_confirmed_and_clears_the_text() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.draft.text = TextBuffer::new("unsent".to_owned());
        assert_eq!(apply_command(&mut model, Command::DiscardDraft), None);
        assert!(matches!(
            model.input,
            InputMode::Confirm(Confirmation::DiscardDraft { .. })
        ));
        let effect = apply_command(&mut model, Command::Accept);
        assert!(
            matches!(effect, Some(Effect::PersistDraft { .. })),
            "the discard persists the empty draft"
        );
        assert!(model.draft.text.text().is_empty());
    }

    /// The History picker opens only while `Browse` owns the focus; a reply
    /// that arrives under another input parks with a status trace instead of
    /// vanishing — the requested feature is deferred, not dropped (A-14).
    #[test]
    fn a_history_reply_arriving_while_another_input_is_open_is_not_dropped() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        assert!(matches!(model.input, InputMode::Picker(_)), "palette open");
        let id = model.selected_memo().expect("memo").id.clone();
        let req = model.request(PendingKind::History { id: id.clone() });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req,
                    id,
                    revisions: Vec::new(),
                }
            ),
            None
        );
        let opened = matches!(model.input, InputMode::Picker(ref picker) if matches!(picker.kind, PickerKind::History { .. }));
        assert!(
            opened || model.status.is_some(),
            "the requested history must open, retry, or explain itself — not drop silently: input {:?}, status {:?}",
            model.input,
            model.status
        );
    }

    /// Focus regain is a system event, not a user command: `focus_reconcile`
    /// never reaches `apply_command`, so a "Saved" toast or a visible
    /// date-parse error survives the terminal window regaining focus (A-09).
    #[test]
    fn a_focus_event_preserves_status_and_visible_errors() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.watcher_active = true;
        model.set_status("Saved");
        assert_eq!(lomo_tui::update::focus_reconcile(&mut model), None);
        assert_eq!(
            model.status.as_deref(),
            Some("Saved"),
            "a system focus event must not erase the user's status line"
        );
        model.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("bogus".to_owned()),
            error: Some("invalid date".to_owned()),
        };
        assert_eq!(lomo_tui::update::focus_reconcile(&mut model), None);
        let InputMode::Date { error, .. } = &model.input else {
            panic!("date dialog");
        };
        assert_eq!(
            error.as_deref(),
            Some("invalid date"),
            "a focus event must not erase a visible parse error"
        );
    }

    /// `menu.rs:187-193` offers "Empty trash" on every trashed memo's action
    /// menu, but `update.rs:79-85` only confirms it when the current view IS
    /// the trash feed — from a trashed memo's Reader the menu row is a dead
    /// no-op that leaves the picker closed and the user guessing.
    #[test]
    fn empty_trash_from_a_trashed_memo_reader_still_works() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let mut card = model.selected_memo().expect("memo").clone();
        card.trashed = true;
        model.view = View::Reader {
            memo: card,
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        assert_eq!(apply_command(&mut model, Command::EmptyTrash), None);
        assert!(
            matches!(
                model.input,
                InputMode::Confirm(Confirmation::EmptyTrash { .. })
            ),
            "the offered EmptyTrash action must reach its confirmation — input stayed {:?}",
            model.input
        );
    }
}
