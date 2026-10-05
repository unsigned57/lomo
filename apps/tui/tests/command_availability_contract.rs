// adversarial-audit: command availability x notification layering re-audit (I2/I9).
//
// Behavior Contract
//
// Capability: verify the TUI's two dispatch-layer invariants end to end.
//   I2 — `Command::availability` is the single authority: an advertised key,
//   menu row or hint chip is always dispatchable, every refusal names its
//   reason on the status line, and a `Hidden` command can never reach
//   dispatch at all.
//   I9 — feedback stays semantically layered: informational results are
//   toasts, persistent failures are classed badges that survive unrelated
//   commands until acknowledged (root Esc) or disproved by a same-class
//   success, modals only seize focus for content that must be read, and
//   destructive confirmations name their targets.
// Priority: P0 (an advertisement that silently does nothing, or a refusal
//   without explanation) / P1 (notification lifecycle drift, a modal that
//   loses its refusal evidence).
// Scenarios:
// - Given any model state and any command, when it dispatches, then a Ready
//   verdict produces an effect or an observable state change, a Refused
//   verdict writes exactly its reason to the status line and nothing else,
//   and a Hidden verdict leaves the model untouched.
// - Given an open picker, when Enter lands on a row, then ready rows act,
//   refused rows keep the picker and name their reason, empty lists refuse
//   aloud, and the Close row dismisses only the overlay.
// - Given a modal input, when a command its key table cannot produce is
//   dispatched, then it is a pure no-op — no effect, no state change.
// - Given failure badges and toasts, when unrelated commands run, then
//   badges persist, a same-class success retires only its own class, and a
//   root Esc acknowledges without moving the view.
// - Given confirmations, when rendered, then the dialog names its target
//   (memo stamp + excerpt, revision, sweep count, draft preview) and the
//   two delete paths stay distinguishable.
// Observable outcomes: `apply_command`/`apply_message` returns, and
//   `model.{input,view,history,status,badges,parked,pending,draft}` — the
//   same state the renderer and receipts read — plus rendered
//   `TestBackend` text for surface-level checks.
// TDD proof: this is a test-only re-audit; every scenario asserts the
//   intended invariant against current behavior. The pre-file RED run is
//   recorded in audit/09 — a missing test target — and any residual defect
//   a failing scenario exposes is reported there, not fixed here.
// Excludes: host event plumbing, executor lanes and real terminal I/O —
//   probed at the model/update boundary; watcher/scheduler mechanics are
//   the host layer's audit.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures are built in one expression; a broken fixture must abort the probe"
)]
mod tests {
    use std::collections::VecDeque;
    use std::path::PathBuf;
    use std::sync::Arc;

    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use lomo_application::TagSelectionMode;
    use lomo_core::RelativeWorkspacePath;
    use lomo_tui::config::{AppConfig, ConfigProposal, SettingsField};
    use lomo_tui::effects::{Effect, MutationOutcome, RuntimeMessage};
    use lomo_tui::event::{
        self, Availability, Command, KeyScope, Refusal, TextEdit, command_from_key,
        command_from_paste, keys_help,
    };
    use lomo_tui::i18n::UiStrings;
    use lomo_tui::input::TextBuffer;
    use lomo_tui::menu;
    use lomo_tui::messages::apply_message;
    use lomo_tui::model::{
        AppModel, AttachmentRow, Badge, BadgeClass, Composer, Confirmation, FeedKind, FeedQuery,
        FeedState, InputMode, LoadStatus, MemoCard, Notice, PaletteItem, PaletteScope, ParkedReply,
        Pending, PendingKind, Picker, PickerKind, RevisionRow, SaveState, Screen, SelectionList,
        SetupState, Severity, StatsView, TaskRow, TextAnchor, UnfilteredContext, View,
    };
    use lomo_tui::overlays;
    use lomo_tui::settings::{SettingsEdit, SettingsView};
    use lomo_tui::update::{apply_command, focus_reconcile};
    use lomo_workspace::MemoId;
    use ratatui::{Terminal, backend::TestBackend};

    use super::support::{feed, memo, model_with_memos};

    // ---------- shared fixture helpers ----------

    fn render(model: &AppModel) -> String {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
            .expect("test backend must construct");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .expect("render must not panic");
        terminal
            .backend()
            .buffer()
            .content
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect()
    }

    fn card(id: &str, body: &str) -> MemoCard {
        memo(id, body).expect("fixture memo parses")
    }

    fn flagged_card(id: &str, body: &str, pinned: bool, trashed: bool) -> MemoCard {
        let mut card = card(id, body);
        card.pinned = pinned;
        card.trashed = trashed;
        card
    }

    fn attachment_path(name: &str) -> RelativeWorkspacePath {
        RelativeWorkspacePath::parse(name).expect("fixture path parses")
    }

    fn memo_id(name: &str) -> MemoId {
        MemoId::parse(name).expect("fixture id parses")
    }

    fn feed_model(count: usize) -> AppModel {
        model_with_memos(count, 80, 24).expect("feed fixture builds")
    }

    fn empty_feed_model() -> AppModel {
        let mut model = AppModel::new(80, 24);
        if let View::Feed(feed) = &mut model.view {
            feed.load = LoadStatus::Ready;
            feed.total = Some(0);
        }
        model
    }

    fn trash_model(count: usize) -> AppModel {
        let mut model = AppModel::new(80, 24);
        let mut trash = FeedState::new(FeedKind::Trash);
        trash.memos = (0..count)
            .map(|index| {
                flagged_card(
                    &format!("trash-{index}"),
                    &format!("Trashed note {index}"),
                    false,
                    true,
                )
            })
            .collect();
        trash.total = Some(u64::try_from(count).unwrap_or(u64::MAX));
        trash.load = LoadStatus::Ready;
        trash.reconcile();
        model.view = View::Feed(Box::new(trash));
        model
    }

    fn reader_model(card: MemoCard) -> AppModel {
        let mut model = feed_model(2);
        model.push_view(View::Reader {
            memo: card,
            anchor: TextAnchor::default(),
        });
        model
    }

    fn tasks_model(dones: &[bool]) -> AppModel {
        let mut model = feed_model(0);
        let rows = dones
            .iter()
            .enumerate()
            .map(|(index, done)| TaskRow {
                memo_id: memo_id(&format!("task-{index}")),
                line: 0,
                text: format!("Task {index}"),
                date: "2026-09-11".to_owned(),
                done: *done,
            })
            .collect();
        model.view = View::Tasks(SelectionList::new(rows));
        model
    }

    fn attachments_model(count: usize) -> AppModel {
        let mut model = feed_model(0);
        let rows = (0..count)
            .map(|index| AttachmentRow {
                path: attachment_path(&format!("media/file-{index}.png")),
                owners: vec![format!("memo-{index}")],
            })
            .collect();
        model.view = View::Attachments(SelectionList::new(rows));
        model
    }

    fn stats_model() -> AppModel {
        let mut model = feed_model(0);
        model.view = View::Statistics(StatsView {
            zone: "UTC".to_owned(),
            as_of_year: 2026,
            as_of_month: 9,
            as_of_day: 11,
            total_memos: 4,
            total_words: 100,
            active_days: 3,
            current_streak: 2,
            longest_streak: 5,
            this_week: 1,
            this_month: 2,
            this_year: 4,
            daily: Vec::new(),
        });
        model
    }

    fn settings_model() -> AppModel {
        let mut model = feed_model(0);
        let config = AppConfig {
            media_dir: PathBuf::from("/tmp/lomo/media"),
            workspace: PathBuf::from("/tmp/lomo"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            editor: None,
            player: lomo_tui::config::default_player(),
        };
        model.view = View::Settings(SettingsView::new(
            &config,
            PathBuf::from("/tmp/lomo/config.toml"),
            None,
            Vec::new(),
        ));
        model
    }

    fn filtered_model() -> AppModel {
        let mut model = feed_model(3);
        if let View::Feed(feed) = &mut model.view {
            feed.query.text = "needle".to_owned();
        }
        model
    }

    fn date_filtered_model() -> AppModel {
        let mut model = feed_model(3);
        if let View::Feed(feed) = &mut model.view {
            feed.query.date_label = Some("today".to_owned());
            feed.query.filters.date_from_inclusive_ms = Some(1_000);
        }
        model
    }

    fn loading_model() -> AppModel {
        let mut model = feed_model(0);
        let req = model.request(PendingKind::Navigate);
        model.view = View::Loading {
            screen: Screen::Tasks,
            req,
        };
        model
    }

    fn failed_model() -> AppModel {
        let mut model = feed_model(0);
        model.view = View::Failed {
            screen: Screen::Timeline,
            diagnostic: "feed exploded".to_owned(),
        };
        model
    }

    fn notice_model() -> AppModel {
        let mut model = feed_model(2);
        model.present(Notice::toast(
            Severity::Info,
            "Saved".to_owned(),
            vec!["memo-0".to_owned()],
        ));
        model
    }

    fn browse_states() -> Vec<(&'static str, AppModel)> {
        let mut states: Vec<(&'static str, AppModel)> = vec![
            ("empty feed", empty_feed_model()),
            ("feed", feed_model(3)),
            ("filtered feed", filtered_model()),
            ("date-filtered feed", date_filtered_model()),
            ("trash feed", trash_model(2)),
            ("empty trash", trash_model(0)),
            ("reader", reader_model(card("memo-live", "Live body"))),
            (
                "pinned reader",
                reader_model(flagged_card("memo-pin", "Pinned body", true, false)),
            ),
            (
                "trashed reader",
                reader_model(flagged_card("memo-trash", "Trashed body", false, true)),
            ),
            ("tasks", tasks_model(&[false, false])),
            ("empty tasks", tasks_model(&[])),
            ("attachments", attachments_model(2)),
            ("empty attachments", attachments_model(0)),
            ("statistics", stats_model()),
            ("settings", settings_model()),
            ("loading", loading_model()),
            ("failed", failed_model()),
        ];
        states.push(("status toast armed", {
            let mut model = feed_model(2);
            model.set_status("Saved");
            model
        }));
        states.push(("badge armed", {
            let mut model = feed_model(2);
            model.raise_badge(
                Severity::Warn,
                BadgeClass::Action,
                "Operation failed: boom".to_owned(),
            );
            model
        }));
        states.push(("notice registered", notice_model()));
        states.push(("last saved armed", {
            let mut model = feed_model(2);
            model.last_created = Some(memo_id("memo-9"));
            model
        }));
        states.push(("draft kept", {
            let mut model = feed_model(2);
            model.draft.text.insert("half-written draft");
            model.draft.revision = 1;
            model
        }));
        states
    }

    fn compose_model(submitting: bool) -> AppModel {
        let mut model = feed_model(2);
        model.input = InputMode::Compose;
        if submitting {
            model.draft.text.insert("draft body");
            model.draft.revision = 1;
            let req = model.request(PendingKind::DraftCommit { revision: 1 });
            model.draft.save = SaveState::Submitting { req, revision: 1 };
        }
        model
    }

    fn search_model() -> AppModel {
        let mut model = feed_model(3);
        if let View::Feed(feed) = &mut model.view {
            feed.query.text = "needle".to_owned();
        }
        model.input = InputMode::Search {
            text: TextBuffer::new("needle".to_owned()),
        };
        model
    }

    fn picker_model(model: &AppModel, kind: PickerKind) -> AppModel {
        let mut probe = model.clone();
        let mut picker = Picker {
            kind,
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        picker.rebind(&menu::entries(&probe, &picker));
        probe.input = InputMode::Picker(picker);
        probe
    }

    fn date_dialog_model() -> AppModel {
        let mut model = feed_model(2);
        model.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("bogus".to_owned()),
            error: Some("cannot resolve that date".to_owned()),
        };
        model
    }

    fn confirm_model(confirmation: Confirmation) -> AppModel {
        let mut model = feed_model(2);
        model.input = InputMode::Confirm(confirmation);
        model
    }

    fn message_model() -> AppModel {
        let mut model = feed_model(2);
        model.input = InputMode::Message {
            title: "Notice".to_owned(),
            lines: vec!["first".to_owned(), "second".to_owned()],
            scroll: 0,
        };
        model
    }

    fn setting_model() -> AppModel {
        let mut model = settings_model();
        model.input = InputMode::Setting(SettingsEdit::new(SettingsField::Workspace, "/tmp/lomo"));
        if let InputMode::Setting(edit) = &mut model.input {
            edit.error = Some("path must be absolute".to_owned());
        }
        model
    }

    fn setup_model() -> AppModel {
        let mut model = feed_model(0);
        let req = model.request(PendingKind::Bootstrap);
        let proposal = ConfigProposal {
            workspace: PathBuf::from("/tmp/lomo"),
            time_zone: "UTC".to_owned(),
            previously_initialized: false,
            recorded_workspace: None,
        };
        let mut setup =
            SetupState::new(PathBuf::from("/tmp/lomo/config.toml"), proposal, req, None);
        setup.error = Some("workspace is not a directory".to_owned());
        model.input = InputMode::Setup(setup);
        model
    }

    fn input_states() -> Vec<(&'static str, AppModel)> {
        let memo = card("memo-0", "Body 0");
        let base = feed_model(3);
        vec![
            ("compose empty", compose_model(false)),
            ("compose submitting", compose_model(true)),
            ("search", search_model()),
            (
                "palette",
                picker_model(
                    &base,
                    PickerKind::Palette {
                        item: PaletteItem::Memo(Box::new(memo)),
                        scope: PaletteScope::All,
                    },
                ),
            ),
            (
                "tags picker",
                picker_model(&base, PickerKind::Tags(TagSelectionMode::Exact)),
            ),
            ("dates picker", picker_model(&base, PickerKind::Dates)),
            (
                "attachments picker",
                picker_model(
                    &base,
                    PickerKind::Attachments(Box::new(card("memo-1", "No files"))),
                ),
            ),
            (
                "history picker",
                picker_model(
                    &base,
                    PickerKind::History {
                        id: memo_id("memo-0"),
                        revisions: vec![RevisionRow {
                            revision: 2,
                            stamp: "2026-09-10 08:00".to_owned(),
                            preview: "old body".to_owned(),
                        }],
                    },
                ),
            ),
            ("date dialog", date_dialog_model()),
            (
                "confirm",
                confirm_model(Confirmation::Delete {
                    memo: Box::new(card("memo-0", "Body 0")),
                }),
            ),
            ("message", message_model()),
            ("help", {
                let mut model = feed_model(2);
                model.input = InputMode::Help { scroll: 0 };
                model
            }),
            ("setting edit", setting_model()),
            ("setup", setup_model()),
        ]
    }

    // ---------- the command catalogue ----------

    // Every `Command` variant, with representative payloads. A new variant
    // must be added here or the matrix silently omits it.
    fn all_commands() -> Vec<Command> {
        vec![
            Command::Quit,
            Command::Help,
            Command::Palette,
            Command::Actions,
            Command::Back,
            Command::Accept,
            Command::Compose,
            Command::Commit,
            Command::ExternalEdit,
            Command::Search,
            Command::Tags,
            Command::Date,
            Command::CustomDate,
            Command::RemoveKeyword,
            Command::RemoveDate,
            Command::ShowNotice,
            Command::ToggleSearchMode,
            Command::ToggleTagScope,
            Command::ClearFilters,
            Command::DiscardDraft,
            Command::Type("x".to_owned()),
            Command::Edit(TextEdit::Backspace),
            Command::Edit(TextEdit::Left),
            Command::Move(1),
            Command::Move(-1),
            Command::Move(i32::MAX),
            Command::Scroll(1),
            Command::Scroll(-1),
            Command::Page(1),
            Command::Page(-1),
            Command::First,
            Command::Last,
            Command::Goto(Screen::Timeline),
            Command::Goto(Screen::Tasks),
            Command::Goto(Screen::Review),
            Command::Goto(Screen::Statistics),
            Command::Goto(Screen::Attachments),
            Command::Goto(Screen::Trash),
            Command::Goto(Screen::Settings),
            Command::SelectTag(None),
            Command::SelectTag(Some(Arc::from("reading"))),
            Command::SetDate("today".to_owned()),
            Command::SetDate("2026-01-01..2026-01-31".to_owned()),
            Command::Pin,
            Command::Delete,
            Command::DeleteForever,
            Command::EmptyTrash,
            Command::Restore,
            Command::History,
            Command::RestoreRevision(2),
            Command::OpenMemo(memo_id("memo-0")),
            Command::ToggleTask,
            Command::ImportClipboard,
            Command::Attachments,
            Command::OpenAttachment(attachment_path("media/x.png")),
            Command::Refresh,
            Command::PasteDenied,
            Command::ShowCreated,
            Command::Click(3, 4),
            Command::DismissPicker,
        ]
    }

    // ---------- the observable-state snapshot ----------

    #[derive(Debug, PartialEq)]
    struct Probe {
        input: InputMode,
        view: View,
        history: Vec<View>,
        status: Option<String>,
        badges: Vec<Badge>,
        pending: Pending,
        parked: VecDeque<ParkedReply>,
        draft: Composer,
        last_created: Option<MemoId>,
        notice: Option<Notice>,
        tags: Vec<String>,
        watcher_active: bool,
    }

    fn snapshot(model: &AppModel) -> Probe {
        Probe {
            input: model.input.clone(),
            view: model.view.clone(),
            history: model.history.clone(),
            status: model.status.clone(),
            badges: model.badges.clone(),
            pending: model.pending.clone(),
            parked: model.parked.clone(),
            draft: model.draft.clone(),
            last_created: model.last_created.clone(),
            notice: model.notice.clone(),
            tags: model.tags().to_vec(),
            watcher_active: model.watcher_active,
        }
    }

    fn is_inert_motion(command: &Command) -> bool {
        matches!(
            command,
            Command::Move(_)
                | Command::Scroll(_)
                | Command::Page(_)
                | Command::First
                | Command::Last
                | Command::Click(..)
        )
    }

    // ---------- matrix assertions ----------

    // The I2 invariant over one browse state: every command's verdict must
    // predict exactly what dispatch does. Violations collect instead of
    // failing fast so one run reports the whole state's drift at once.
    fn assert_state_matrix(name: &str, model: &AppModel) {
        let mut violations: Vec<String> = Vec::new();
        for command in all_commands() {
            let mut probe = model.clone();
            let verdict = command.availability(&probe);
            let before = snapshot(&probe);
            let effect = apply_command(&mut probe, command.clone());
            let after = snapshot(&probe);
            match verdict {
                Availability::Ready => {
                    if !is_inert_motion(&command) && effect.is_none() && before == after {
                        violations.push(format!(
                            "Ready command {command:?} produced neither an effect \
                             nor an observable state change"
                        ));
                    }
                }
                Availability::Refused(reason) => {
                    if effect.is_some() {
                        violations.push(format!(
                            "Refused command {command:?} still produced an effect"
                        ));
                    }
                    if probe.status.as_deref() != Some(reason.text()) {
                        violations.push(format!(
                            "Refused command {command:?} left status {:?}, \
                             not its reason {:?}",
                            probe.status,
                            reason.text()
                        ));
                    }
                    let mut silent = after;
                    silent.status.clone_from(&before.status);
                    if before != silent {
                        violations.push(format!(
                            "Refused command {command:?} changed more than the status line"
                        ));
                    }
                }
                Availability::Hidden => {
                    if command == Command::PasteDenied {
                        if probe.status.is_none() {
                            violations.push("a denied paste explained nothing".to_owned());
                        }
                    } else if effect.is_some() || before != after {
                        violations.push(format!(
                            "Hidden command {command:?} reached dispatch and changed state"
                        ));
                    }
                }
            }
        }
        assert!(
            violations.is_empty(),
            "{name}: capability/dispatch agreement failed:\n{}",
            violations.join("\n")
        );
    }

    // Commands the mode's own key table (plus mouse/synthetic routes) can
    // produce — everything else must be a pure no-op.
    fn reachable_in(input: &InputMode) -> Vec<Command> {
        let scopes: &[KeyScope] = match input {
            InputMode::Browse => &[KeyScope::Browse],
            InputMode::Compose => &[KeyScope::Compose, KeyScope::Field],
            InputMode::Search { .. } => &[KeyScope::Search, KeyScope::Field],
            InputMode::Picker(_) | InputMode::Date { .. } | InputMode::Setting(_) => {
                &[KeyScope::Field]
            }
            InputMode::Setup(_) => &[KeyScope::Setup, KeyScope::Field],
            InputMode::Confirm(_) => &[KeyScope::Confirm],
            InputMode::Message { .. } | InputMode::Help { .. } => &[KeyScope::Overlay],
        };
        let mut produced: Vec<Command> = event::KEY_BINDINGS
            .iter()
            .filter(|binding| binding.scope == KeyScope::Global || scopes.contains(&binding.scope))
            .map(|binding| binding.command.clone())
            .collect();
        // The plain-char wildcard types into every text field.
        if matches!(
            input,
            InputMode::Compose
                | InputMode::Search { .. }
                | InputMode::Picker(_)
                | InputMode::Date { .. }
                | InputMode::Setting(_)
                | InputMode::Setup(_)
        ) {
            produced.push(Command::Type("x".to_owned()));
        }
        // Mouse and modal-managed commands a real interaction can produce.
        produced.extend([
            Command::Click(0, 0),
            Command::Scroll(1),
            Command::Scroll(-1),
            Command::Back,
            Command::Accept,
        ]);
        // The search field's pass-through routes every navigation command to
        // the result list by design (A-04), including strides no bound key
        // can produce while the field owns the focus.
        if matches!(input, InputMode::Search { .. }) {
            produced.extend([
                Command::Move(1),
                Command::Move(-1),
                Command::Move(i32::MAX),
                Command::Move(i32::MIN),
                Command::Page(1),
                Command::Page(-1),
                Command::First,
                Command::Last,
            ]);
        }
        // The picker's Move arm shifts the mark by whatever stride arrives
        // and the wizard's Move arm switches focus by sign — the family is
        // handled as a family, so unreachable strides route identically.
        if matches!(input, InputMode::Picker(_) | InputMode::Setup(_)) {
            produced.extend([
                Command::Move(1),
                Command::Move(-1),
                Command::Move(i32::MAX),
                Command::Move(i32::MIN),
            ]);
        }
        if matches!(input, InputMode::Picker(_)) {
            produced.push(Command::DismissPicker);
        }
        produced
    }

    fn modal_boundary_escapes(name: &str, model: &AppModel) -> Vec<String> {
        let produced = reachable_in(&model.input);
        let mut escaped: Vec<String> = Vec::new();
        for command in all_commands() {
            if produced.contains(&command)
                || command == Command::Quit
                || command == Command::PasteDenied
            {
                continue;
            }
            let mut probe = model.clone();
            let before = snapshot(&probe);
            let effect = apply_command(&mut probe, command.clone());
            let after = snapshot(&probe);
            if effect.is_some() {
                escaped.push(format!("{name}: {command:?} produced an effect"));
            } else if before != after {
                escaped.push(format!("{name}: {command:?} changed modal state"));
            }
        }
        escaped
    }

    fn picker_kinds(model: &AppModel) -> Vec<(&'static str, PickerKind)> {
        let mut with_files = card("memo-files", "Has files");
        with_files
            .attachments
            .push(attachment_path("media/file.png"));
        vec![
            (
                "commands",
                PickerKind::Palette {
                    item: model.palette_item(),
                    scope: PaletteScope::All,
                },
            ),
            (
                "item actions",
                PickerKind::Palette {
                    item: model.palette_item(),
                    scope: PaletteScope::Item,
                },
            ),
            ("tags", PickerKind::Tags(TagSelectionMode::Exact)),
            ("dates", PickerKind::Dates),
            ("attachments", PickerKind::Attachments(Box::new(with_files))),
            (
                "empty attachments",
                PickerKind::Attachments(Box::new(card("memo-none", "No files"))),
            ),
            (
                "history",
                PickerKind::History {
                    id: memo_id("memo-0"),
                    revisions: vec![
                        RevisionRow {
                            revision: 2,
                            stamp: "2026-09-10 08:00".to_owned(),
                            preview: "older body".to_owned(),
                        },
                        RevisionRow {
                            revision: 1,
                            stamp: String::new(),
                            preview: "oldest body".to_owned(),
                        },
                    ],
                },
            ),
            (
                "empty history",
                PickerKind::History {
                    id: memo_id("memo-0"),
                    revisions: Vec::new(),
                },
            ),
        ]
    }

    fn assert_ready_rows_dispatch(
        state: &str,
        model: &AppModel,
        kind_name: &str,
        kind: &PickerKind,
    ) {
        let picker = Picker {
            kind: kind.clone(),
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = menu::entries(model, &picker);
        for (index, entry) in entries.iter().enumerate() {
            if entry.availability != Availability::Ready {
                continue;
            }
            let mut probe = model.clone();
            let mut dialog = picker.clone();
            dialog.select(index, &entries);
            probe.input = InputMode::Picker(dialog);
            let before = snapshot(&probe);
            let effect = apply_command(&mut probe, Command::Accept);
            assert!(
                effect.is_some() || snapshot(&probe) != before,
                "{state}:{kind_name}: Enter on Ready row '{}' produced neither an effect \
                 nor an observable change",
                entry.label
            );
        }
    }

    fn assert_refused_rows_explain(
        state: &str,
        model: &AppModel,
        kind_name: &str,
        kind: &PickerKind,
    ) {
        let picker = Picker {
            kind: kind.clone(),
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = menu::entries(model, &picker);
        for (index, entry) in entries.iter().enumerate() {
            let Availability::Refused(reason) = &entry.availability else {
                continue;
            };
            let mut probe = model.clone();
            let mut dialog = picker.clone();
            dialog.select(index, &entries);
            probe.input = InputMode::Picker(dialog);
            let before = snapshot(&probe);
            let effect = apply_command(&mut probe, Command::Accept);
            assert!(effect.is_none(), "{state}:{kind_name}: refused row acted");
            assert_eq!(
                probe.status.as_deref(),
                Some(reason.text()),
                "{state}:{kind_name}: refused row '{}' did not name its reason",
                entry.label
            );
            let mut silent = snapshot(&probe);
            silent.status.clone_from(&before.status);
            assert_eq!(
                before, silent,
                "{state}:{kind_name}: refused row '{}' changed more than the status line",
                entry.label
            );
        }
    }

    // ---------- I2: the capability gate drives dispatch ----------

    #[test]
    fn every_browse_command_follows_its_own_verdict() {
        for (name, model) in browse_states() {
            assert_state_matrix(name, &model);
        }
    }

    #[test]
    fn every_bound_key_dispatches_in_its_own_scope() {
        for binding in event::KEY_BINDINGS {
            let model = model_for_scope(binding.scope);
            let produced = command_from_key(binding.event(), &model);
            assert_eq!(
                produced.as_ref(),
                Some(&binding.command),
                "binding {} in {:?} scope must produce {:?}",
                binding.label,
                binding.scope,
                binding.command
            );
        }
    }

    fn model_for_scope(scope: KeyScope) -> AppModel {
        match scope {
            KeyScope::Global | KeyScope::Browse => feed_model(2),
            KeyScope::Compose => compose_model(false),
            KeyScope::Search => search_model(),
            KeyScope::Field => picker_model(&feed_model(2), PickerKind::Dates),
            KeyScope::Setup => setup_model(),
            KeyScope::Confirm => confirm_model(Confirmation::EmptyTrash { count: Some(2) }),
            KeyScope::Overlay => message_model(),
        }
    }

    #[test]
    fn ctrl_c_quits_from_every_input_mode() {
        let quit_chord = KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL);
        for (name, mut model) in input_states() {
            assert_eq!(
                command_from_key(quit_chord, &model),
                Some(Command::Quit),
                "{name}: Ctrl+C must always reach Quit"
            );
            let effect = apply_command(&mut model, Command::Quit);
            assert!(
                matches!(effect, Some(Effect::Quit { .. })),
                "{name}: Quit must dispatch under any input"
            );
        }
    }

    #[test]
    fn plain_chars_type_only_into_text_fields() {
        let typed = KeyEvent::new(KeyCode::Char('x'), KeyModifiers::NONE);
        for (name, model) in input_states() {
            let produced = command_from_key(typed, &model);
            let has_field = matches!(
                model.input,
                InputMode::Compose
                    | InputMode::Search { .. }
                    | InputMode::Picker(_)
                    | InputMode::Date { .. }
                    | InputMode::Setting(_)
                    | InputMode::Setup(_)
            );
            if has_field {
                assert_eq!(
                    produced,
                    Some(Command::Type("x".to_owned())),
                    "{name}: a field mode must type plain chars"
                );
            } else {
                assert!(
                    produced.is_none(),
                    "{name}: a fieldless mode must not invent a typing command"
                );
            }
        }
    }

    #[test]
    fn paste_targets_a_field_or_denies_visibly() {
        for (name, model) in input_states() {
            let produced = command_from_paste("clip".to_owned(), &model);
            let has_field = matches!(
                model.input,
                InputMode::Compose
                    | InputMode::Search { .. }
                    | InputMode::Picker(_)
                    | InputMode::Date { .. }
                    | InputMode::Setting(_)
                    | InputMode::Setup(_)
            );
            let expected = if has_field {
                Command::Type("clip".to_owned())
            } else {
                Command::PasteDenied
            };
            assert_eq!(produced, Some(expected), "{name}: paste routing wrong");
        }
        for (name, mut model) in input_states() {
            let effect = apply_command(&mut model, Command::PasteDenied);
            assert!(effect.is_none());
            assert!(
                model.status.is_some(),
                "{name}: a denied paste must explain itself"
            );
        }
    }

    // ---------- I2: modal boundary — unreachable commands are inert ----------

    #[test]
    fn unreachable_commands_are_pure_noops_under_modal_inputs() {
        let mut escaped: Vec<String> = Vec::new();
        for (name, model) in input_states() {
            escaped.extend(modal_boundary_escapes(name, &model));
        }
        assert!(
            escaped.is_empty(),
            "unreachable commands escaped the modal boundary:\n{}",
            escaped.join("\n")
        );
    }

    // ---------- I2: help and hint surfaces project the same table ----------

    #[test]
    fn the_help_table_names_every_bound_key_and_only_bound_keys() {
        let help = keys_help();
        let advertised = [
            KeyScope::Browse,
            KeyScope::Compose,
            KeyScope::Search,
            KeyScope::Global,
        ];
        let strings = UiStrings::detect();
        for binding in event::KEY_BINDINGS
            .iter()
            .filter(|binding| advertised.contains(&binding.scope))
        {
            assert!(
                help.contains(binding.label),
                "keys_help omits the '{}' binding",
                binding.label
            );
            let blurb = strings.text(binding.help.0, binding.help.1);
            assert!(
                help.contains(blurb),
                "keys_help omits the blurb for '{}'",
                binding.label
            );
        }
        let real_labels: Vec<&'static str> = event::KEY_BINDINGS
            .iter()
            .filter(|binding| advertised.contains(&binding.scope))
            .map(|binding| binding.label)
            .collect();
        for line in help.lines().skip(1) {
            let label_field: String = line.chars().skip(2).take(18).collect();
            for token in label_field.trim().split(" / ") {
                if !token.is_empty() {
                    assert!(
                        real_labels.contains(&token),
                        "keys_help advertises an unbound key '{token}'"
                    );
                }
            }
        }
        assert!(
            !help.contains("Ctrl+P") && !help.contains("Ctrl+p"),
            "the Ctrl+P ghost key must stay dead"
        );
    }

    #[test]
    fn cli_help_renders_the_same_table() {
        let help = lomo_tui::cli::render_help();
        assert!(help.contains("Keys:"));
        for must in ["Ctrl+C", "Ctrl+S", "Ctrl+E", "F5", "Esc"] {
            assert!(help.contains(must), "--help dropped the {must} row");
        }
        assert!(
            !help.contains("Ctrl+P") && !help.contains("Ctrl+p"),
            "--help must not advertise the dead Ctrl+P chord"
        );
    }

    #[test]
    fn in_app_help_names_only_bound_keys() {
        let strings = UiStrings::detect();
        let text = overlays::help(strings).join("\n");
        for ghost in ["Ctrl+P", "Ctrl+p", "F8"] {
            assert!(
                !text.contains(ghost),
                "in-app help still advertises dead key {ghost}"
            );
        }
        for must in [
            ":", ".", "F5", "?", "q", "n", "e", "m", "d", "/", "Tab", "Ctrl+S", "Ctrl+E", "Ctrl+F",
            "Ctrl+D/U", "Enter", "Esc", "g",
        ] {
            assert!(text.contains(must), "in-app help dropped the {must} row");
        }
    }

    // ---------- I2: the menu projects the same capability table ----------

    #[test]
    fn menu_entries_never_materialize_hidden_commands_and_keys_are_real() {
        for (state, model) in browse_states() {
            for (kind_name, kind) in picker_kinds(&model) {
                let picker = Picker {
                    kind: kind.clone(),
                    text: TextBuffer::default(),
                    selected: 0,
                    identity: None,
                };
                for entry in menu::entries(&model, &picker).iter() {
                    assert_ne!(
                        entry.availability,
                        Availability::Hidden,
                        "{state}:{kind_name}: a Hidden command must not materialize a row"
                    );
                    if let Some(key) = entry.key {
                        assert!(
                            event::KEY_BINDINGS.iter().any(|b| b.label == key),
                            "{state}:{kind_name}: row '{}' names key '{key}' no binding owns",
                            entry.label
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn conditional_rows_track_their_gates() {
        let plain = feed_model(2);
        let entries = menu::entries(
            &plain,
            &Picker {
                kind: PickerKind::Palette {
                    item: PaletteItem::None,
                    scope: PaletteScope::All,
                },
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            },
        );
        for command in [
            Command::ShowCreated,
            Command::ShowNotice,
            Command::DiscardDraft,
            Command::ToggleSearchMode,
            Command::RemoveKeyword,
            Command::RemoveDate,
            Command::ClearFilters,
        ] {
            assert!(
                entries.iter().all(|entry| entry.command != command),
                "an unarmed row for {command:?} must not be listed"
            );
        }

        let mut armed = feed_model(2);
        armed.last_created = Some(memo_id("memo-9"));
        armed.present(Notice::toast(
            Severity::Info,
            "Saved".to_owned(),
            Vec::new(),
        ));
        armed.draft.text.insert("draft");
        armed.draft.revision = 1;
        if let View::Feed(feed) = &mut armed.view {
            feed.query.text = "needle".to_owned();
            feed.query.date_label = Some("today".to_owned());
        }
        let armed_entries = menu::entries(
            &armed,
            &Picker {
                kind: PickerKind::Palette {
                    item: PaletteItem::None,
                    scope: PaletteScope::All,
                },
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            },
        );
        for command in [
            Command::ShowCreated,
            Command::ShowNotice,
            Command::DiscardDraft,
            Command::ToggleSearchMode,
            Command::RemoveKeyword,
            Command::RemoveDate,
            Command::ClearFilters,
        ] {
            assert!(
                armed_entries.iter().any(|entry| entry.command == command),
                "an armed row for {command:?} must be listed"
            );
        }
    }

    #[test]
    fn labels_track_item_state() {
        let strings = UiStrings::detect();
        memo_action_labels_track_card_state(strings);
        task_labels_track_done_state(strings);
    }

    /// Pin/unpin and delete labels follow the frozen card, not defaults.
    fn memo_action_labels_track_card_state(strings: &UiStrings) {
        let pinned = picker_model(
            &feed_model(2),
            PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(flagged_card(
                    "memo-p",
                    "Pinned body",
                    true,
                    false,
                ))),
                scope: PaletteScope::Item,
            },
        );
        let InputMode::Picker(picker) = &pinned.input else {
            panic!("fixture must hold a picker");
        };
        let entries = menu::entries(&pinned, picker);
        let pin_row = entries
            .iter()
            .find(|entry| entry.command == Command::Pin)
            .expect("pinned memo palette lists the pin row");
        assert_eq!(
            pin_row.label,
            strings.text("Unpin", "取消置顶"),
            "a pinned memo must offer Unpin"
        );

        let live = picker_model(
            &feed_model(2),
            PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(card("memo-l", "Live body"))),
                scope: PaletteScope::Item,
            },
        );
        let InputMode::Picker(live_picker) = &live.input else {
            panic!("fixture must hold a picker");
        };
        let live_entries = menu::entries(&live, live_picker);
        let pin_row = live_entries
            .iter()
            .find(|entry| entry.command == Command::Pin)
            .expect("live memo palette lists the pin row");
        assert_eq!(pin_row.label, strings.text("Pin", "置顶"));
        let delete_row = live_entries
            .iter()
            .find(|entry| entry.command == Command::Delete)
            .expect("live memo palette lists a trash move");
        assert_eq!(
            delete_row.label,
            strings.text("Move to trash", "移入回收站")
        );

        let trashed = picker_model(
            &feed_model(2),
            PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(flagged_card(
                    "memo-t",
                    "Trashed body",
                    false,
                    true,
                ))),
                scope: PaletteScope::Item,
            },
        );
        let InputMode::Picker(trash_picker) = &trashed.input else {
            panic!("fixture must hold a picker");
        };
        let trash_entries = menu::entries(&trashed, trash_picker);
        assert!(
            trash_entries
                .iter()
                .all(|entry| entry.command != Command::Delete),
            "a trashed memo must not offer a second trash move"
        );
        let forever = trash_entries
            .iter()
            .find(|entry| entry.command == Command::DeleteForever)
            .expect("trashed memo palette lists permanent delete");
        assert_eq!(
            forever.label,
            strings.text("Delete permanently", "永久删除")
        );
    }

    /// Task labels follow `done`.
    fn task_labels_track_done_state(strings: &UiStrings) {
        for (done, label) in [
            (false, strings.text("Mark as done", "标记为已完成")),
            (true, strings.text("Mark as open", "标记为未完成")),
        ] {
            let task_model = tasks_model(&[done]);
            let picker = Picker {
                kind: PickerKind::Palette {
                    item: task_model.palette_item(),
                    scope: PaletteScope::Item,
                },
                text: TextBuffer::default(),
                selected: 0,
                identity: None,
            };
            let entries = menu::entries(&task_model, &picker);
            let toggle = entries
                .iter()
                .find(|entry| entry.command == Command::ToggleTask)
                .expect("task palette lists the toggle");
            assert_eq!(toggle.label, label, "task done={done} label drifted");
        }
    }

    #[test]
    fn trashed_memo_rows_keep_their_reasons_visible() {
        let trashed = flagged_card("memo-t", "Trashed body", true, true);
        let base = feed_model(2);
        let picker = Picker {
            kind: PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(trashed)),
                scope: PaletteScope::Item,
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = menu::entries(&base, &picker);
        for (command, reason) in [
            (Command::Pin, Refusal::MemoTrashed),
            (Command::ExternalEdit, Refusal::MemoTrashed),
        ] {
            let row = entries
                .iter()
                .find(|entry| entry.command == command)
                .expect("a refused row stays listed");
            assert_eq!(
                row.availability,
                Availability::Refused(reason),
                "{command:?} on a trashed memo must grey with {reason:?}"
            );
        }
        // A live memo's palette greys the trash-scope rows it does list —
        // and never materializes `DeleteForever`, which a live memo cannot
        // answer for (permanent delete only exists once the memo is trashed).
        let live_picker = Picker {
            kind: PickerKind::Palette {
                item: PaletteItem::Memo(Box::new(card("memo-l", "Live body"))),
                scope: PaletteScope::Item,
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let live_entries = menu::entries(&base, &live_picker);
        for (command, reason) in [
            (Command::Restore, Refusal::MemoNotTrashed),
            (Command::EmptyTrash, Refusal::OutsideTrash),
        ] {
            let row = live_entries
                .iter()
                .find(|entry| entry.command == command)
                .expect("a refused row stays listed");
            assert_eq!(
                row.availability,
                Availability::Refused(reason),
                "{command:?} outside the trash must grey with {reason:?}"
            );
        }
        assert!(
            live_entries
                .iter()
                .all(|entry| entry.command != Command::DeleteForever),
            "a live memo must not offer the permanent delete row"
        );
    }

    // ---------- I2: picker dispatch is identical to browse dispatch ----------

    #[test]
    fn every_ready_picker_row_dispatches() {
        for (state, model) in browse_states() {
            for (kind_name, kind) in picker_kinds(&model) {
                assert_ready_rows_dispatch(state, &model, kind_name, &kind);
            }
        }
    }

    #[test]
    fn every_refused_picker_row_names_its_reason_and_stays_open() {
        for (state, model) in browse_states() {
            for (kind_name, kind) in picker_kinds(&model) {
                assert_refused_rows_explain(state, &model, kind_name, &kind);
            }
        }
    }

    #[test]
    fn enter_on_an_empty_picker_refuses_aloud_and_stays() {
        // An attachment-less memo's picker lists nothing.
        let mut probe = picker_model(
            &feed_model(2),
            PickerKind::Attachments(Box::new(card("memo-1", "No files"))),
        );
        let effect = apply_command(&mut probe, Command::Accept);
        assert!(effect.is_none());
        assert_eq!(
            probe.status.as_deref(),
            Some(Refusal::NoMatches.text()),
            "an empty list must refuse aloud"
        );
        assert!(
            matches!(probe.input, InputMode::Picker(_)),
            "a refused empty list must keep the picker open"
        );
        // A palette filtered to zero rows does the same.
        let mut filtered = picker_model(
            &feed_model(2),
            PickerKind::Palette {
                item: PaletteItem::None,
                scope: PaletteScope::All,
            },
        );
        if let InputMode::Picker(picker) = &mut filtered.input {
            picker.text.insert("qqqqqq");
        }
        let effect = apply_command(&mut filtered, Command::Accept);
        assert!(effect.is_none());
        assert_eq!(filtered.status.as_deref(), Some(Refusal::NoMatches.text()));
        assert!(matches!(filtered.input, InputMode::Picker(_)));
    }

    #[test]
    fn enter_without_room_refuses_instead_of_firing_an_invisible_row() {
        let mut tiny = picker_model(
            &AppModel::new(40, 4),
            PickerKind::Palette {
                item: PaletteItem::None,
                scope: PaletteScope::All,
            },
        );
        assert_eq!(
            overlays::picker_area(&tiny).height,
            0,
            "the fixture must actually collapse the entry area"
        );
        let effect = apply_command(&mut tiny, Command::Accept);
        assert!(effect.is_none());
        assert_eq!(tiny.status.as_deref(), Some(Refusal::NoRoom.text()));
        assert!(matches!(tiny.input, InputMode::Picker(_)));
    }

    #[test]
    fn the_history_close_row_dismisses_only_the_overlay() {
        // A filtered feed under a picker: DismissPicker must not peel the
        // filter or pop the view the way `Back` would.
        let mut model = filtered_model();
        model.push_view(View::Tasks(SelectionList::new(vec![TaskRow {
            memo_id: memo_id("task-0"),
            line: 0,
            text: "T".to_owned(),
            date: "2026-09-11".to_owned(),
            done: false,
        }])));
        let mut probe = picker_model(
            &model,
            PickerKind::History {
                id: memo_id("memo-0"),
                revisions: vec![RevisionRow {
                    revision: 2,
                    stamp: "2026-09-10 08:00".to_owned(),
                    preview: "old".to_owned(),
                }],
            },
        );
        let effect = apply_command(&mut probe, Command::DismissPicker);
        assert!(effect.is_none());
        assert_eq!(probe.input, InputMode::Browse, "the overlay alone closes");
        assert_eq!(probe.view, model.view, "the view stack is untouched");
        assert_eq!(
            snapshot(&probe).history,
            model.history.clone(),
            "history is untouched"
        );
        // In Browse the same command is a pure no-op — never a disguised Back.
        let mut flat = model.clone();
        let before = snapshot(&flat);
        let effect = apply_command(&mut flat, Command::DismissPicker);
        assert!(effect.is_none());
        assert_eq!(
            snapshot(&flat),
            before,
            "DismissPicker in Browse must not pop a layer"
        );
    }

    #[test]
    fn empty_history_shows_its_message_without_a_close_row() {
        let base = feed_model(2);
        let picker = Picker {
            kind: PickerKind::History {
                id: memo_id("memo-0"),
                revisions: Vec::new(),
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = menu::entries(&base, &picker);
        assert!(
            entries
                .iter()
                .all(|entry| entry.command != Command::DismissPicker),
            "an empty history must not park a Close row over the empty message"
        );
        let probe = picker_model(&base, picker.kind);
        let text = render(&probe);
        let strings = UiStrings::detect();
        assert!(
            text.contains(strings.text("No earlier revisions", "没有更早的版本")),
            "the empty-history message must render"
        );
        // A non-empty history does offer Close.
        let full = Picker {
            kind: PickerKind::History {
                id: memo_id("memo-0"),
                revisions: vec![RevisionRow {
                    revision: 1,
                    stamp: String::new(),
                    preview: "old".to_owned(),
                }],
            },
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let full_entries = menu::entries(&base, &full);
        assert!(
            full_entries
                .iter()
                .any(|entry| entry.command == Command::DismissPicker),
            "a non-empty history offers its Close row"
        );
        // Enter on an empty list still refuses aloud rather than closing.
        let mut probe = picker_model(
            &base,
            PickerKind::History {
                id: memo_id("memo-0"),
                revisions: Vec::new(),
            },
        );
        let effect = apply_command(&mut probe, Command::Accept);
        assert!(effect.is_none());
        assert_eq!(
            probe.status.as_deref(),
            Some(Refusal::NoMatches.text()),
            "Enter on the empty list refuses aloud, never silently closes"
        );
        assert!(matches!(probe.input, InputMode::Picker(_)));
    }

    #[test]
    fn movement_keys_rebind_the_picker_mark_by_identity() {
        let base = feed_model(3);
        let mut probe = picker_model(
            &base,
            PickerKind::Palette {
                item: PaletteItem::None,
                scope: PaletteScope::All,
            },
        );
        let first_entry = {
            let InputMode::Picker(picker) = &probe.input else {
                panic!("fixture must hold a picker");
            };
            let entries = menu::entries(&probe, picker);
            entries
                .first()
                .expect("palette lists entries")
                .command
                .clone()
        };
        let effect = apply_command(&mut probe, Command::Move(1));
        assert!(effect.is_none());
        let InputMode::Picker(picker) = &probe.input else {
            panic!("the picker must stay open");
        };
        let entries = menu::entries(&probe, picker);
        let index = picker.entry_index(&entries).expect("a row is selected");
        assert_ne!(
            entries.get(index).expect("index in bounds").command,
            first_entry,
            "movement must leave the first row"
        );
    }

    #[test]
    fn tag_scope_row_keeps_the_picker_and_flips_the_scope() {
        // `ToggleTagScope` is reachable only as a picker row — no bound key
        // produces it — so Enter on the row is the gesture under test.
        let base = feed_model(2);
        let picker = Picker {
            kind: PickerKind::Tags(TagSelectionMode::Exact),
            text: TextBuffer::default(),
            selected: 0,
            identity: None,
        };
        let entries = menu::entries(&base, &picker);
        let index = entries
            .iter()
            .position(|entry| entry.command == Command::ToggleTagScope)
            .expect("the scope row is listed");
        let mut probe = base;
        let mut dialog = picker;
        dialog.select(index, &entries);
        probe.input = InputMode::Picker(dialog);
        let effect = apply_command(&mut probe, Command::Accept);
        assert!(effect.is_none(), "the scope row is not an effect");
        let InputMode::Picker(picker) = &probe.input else {
            panic!("the scope row keeps the picker open");
        };
        assert!(
            matches!(picker.kind, PickerKind::Tags(TagSelectionMode::Subtree)),
            "the scope flips in place"
        );
        // Enter again flips back — the row toggles, it does not latch.
        let effect = apply_command(&mut probe, Command::Accept);
        assert!(effect.is_none());
        let InputMode::Picker(picker) = &probe.input else {
            panic!("the picker stays open");
        };
        assert!(
            matches!(picker.kind, PickerKind::Tags(TagSelectionMode::Exact)),
            "a second Enter flips the scope back"
        );
    }

    #[test]
    fn a_history_row_opens_a_confirm_that_names_the_revision() {
        let revisions = vec![RevisionRow {
            revision: 3,
            stamp: "2026-09-10 08:00".to_owned(),
            preview: "revision three body".to_owned(),
        }];
        let mut probe = picker_model(
            &feed_model(2),
            PickerKind::History {
                id: memo_id("memo-0"),
                revisions,
            },
        );
        let effect = apply_command(&mut probe, Command::Accept);
        assert!(effect.is_none(), "a confirm dialog produces no effect");
        let InputMode::Confirm(Confirmation::RestoreRevision {
            id,
            revision,
            stamp,
            preview,
        }) = &probe.input
        else {
            panic!("the history row must open a named restore confirm");
        };
        assert_eq!(id, &memo_id("memo-0"));
        assert_eq!(*revision, 3);
        assert_eq!(stamp, "2026-09-10 08:00");
        assert_eq!(preview, "revision three body");
    }

    #[test]
    fn picker_rows_dispatch_the_frozen_item_not_the_live_selection() {
        // The palette froze `task-frozen` while the task view still selects
        // the live row — the dispatched effect must name the frozen one.
        let task = TaskRow {
            memo_id: memo_id("task-frozen"),
            line: 0,
            text: "Frozen task".to_owned(),
            date: "2026-09-11".to_owned(),
            done: false,
        };
        let mut probe = picker_model(
            &tasks_model(&[false, false]),
            PickerKind::Palette {
                item: PaletteItem::Task(task),
                scope: PaletteScope::Item,
            },
        );
        let effect = apply_command(&mut probe, Command::Accept);
        let Some(Effect::ToggleTask { task: toggled, .. }) = effect else {
            panic!("Enter on the task row must toggle the frozen task, got {effect:?}");
        };
        assert_eq!(toggled.memo_id, memo_id("task-frozen"));
    }

    // ---------- I2/A-06: trashed-reader surfaces ----------

    #[test]
    fn trashed_reader_advertises_only_real_actions() {
        let strings = UiStrings::detect();
        let mut model = feed_model(2);
        model.view = View::Reader {
            memo: flagged_card("memo-t", "Trashed body", true, true),
            anchor: TextAnchor::default(),
        };
        let text = render(&model);
        assert!(
            text.contains(&format!("d {}", strings.text("delete forever", "永久删除"))),
            "the trashed reader must offer the permanent-delete key"
        );
        for dead in [
            format!("m {}", strings.text("pin", "置顶")),
            format!("m {}", strings.text("unpin", "取消置顶")),
            format!("e {}", strings.text("edit", "编辑")),
        ] {
            assert!(
                !text.contains(&dead),
                "the trashed reader must not advertise '{dead}'"
            );
        }
        // Dispatch agrees: the refused keys name their reason.
        for command in [Command::Pin, Command::ExternalEdit] {
            let mut probe = model.clone();
            let effect = apply_command(&mut probe, command.clone());
            assert!(effect.is_none());
            assert_eq!(
                probe.status.as_deref(),
                Some(Refusal::MemoTrashed.text()),
                "{command:?} on a trashed memo must say why"
            );
        }
        // And the real trash actions open their own confirmations.
        let mut probe = model.clone();
        let effect = apply_command(&mut probe, Command::Delete);
        assert!(effect.is_none());
        assert!(
            matches!(
                probe.input,
                InputMode::Confirm(Confirmation::DeleteForever(_))
            ),
            "d on a trashed memo asks the permanent question"
        );
        let mut restore = model;
        let effect = apply_command(&mut restore, Command::Restore);
        assert!(effect.is_none());
        assert!(matches!(
            restore.input,
            InputMode::Confirm(Confirmation::Restore(_))
        ));
    }

    #[test]
    fn feed_hint_lists_only_ready_chips() {
        let strings = UiStrings::detect();
        let text = render(&feed_model(3));
        for chip in [
            format!("n {}", strings.text("new", "记录")),
            format!("/ {}", strings.text("search", "搜索")),
            format!("Enter {}", strings.text("read", "阅读")),
        ] {
            assert!(text.contains(&chip), "the feed hint dropped '{chip}'");
        }
        // On the empty feed the selection commands drop out; the always-ready
        // ones keep their chips — refused keys are never advertised.
        let empty = render(&empty_feed_model());
        assert!(
            empty.contains(&format!("n {}", strings.text("new", "记录"))),
            "the always-ready new-memo key keeps its chip"
        );
        assert!(
            !empty.contains(&format!("Enter {}", strings.text("read", "阅读"))),
            "a refused read must not keep a chip"
        );
        assert!(
            !empty.contains(&format!(". {}", strings.text("actions", "操作"))),
            "a refused action menu must not keep a chip"
        );
    }

    // ---------- A-08: the trash sweep is scoped and counts ----------

    #[test]
    fn empty_trash_is_scoped_and_counts_what_it_can_see() {
        let strings = UiStrings::detect();
        // Outside the trash entirely: refused, named.
        let mut probe = feed_model(2);
        let effect = apply_command(&mut probe, Command::EmptyTrash);
        assert!(effect.is_none());
        assert_eq!(probe.status.as_deref(), Some(Refusal::OutsideTrash.text()));
        // An empty trash feed refuses its own sweep.
        let mut probe = trash_model(0);
        let effect = apply_command(&mut probe, Command::EmptyTrash);
        assert!(effect.is_none());
        assert_eq!(probe.status.as_deref(), Some(Refusal::TrashEmpty.text()));
        // On a loaded trash feed the confirm carries the live count.
        let mut probe = trash_model(3);
        let effect = apply_command(&mut probe, Command::EmptyTrash);
        assert!(effect.is_none());
        let InputMode::Confirm(Confirmation::EmptyTrash { count }) = &probe.input else {
            panic!("the sweep opens its own confirm");
        };
        assert_eq!(*count, Some(3), "the dialog names the sweep depth");
        let text = render(&probe);
        assert!(
            text.contains(&format!(
                "{} 3",
                strings.text("Trash now holds", "回收站现有")
            )),
            "the count must render in the dialog"
        );
        // A trashed memo's reader proves the trash holds entries even when
        // no trash feed is loaded — the dialog stays honest about the depth.
        let mut reader = reader_model(flagged_card("memo-t", "Trashed body", false, true));
        let effect = apply_command(&mut reader, Command::EmptyTrash);
        assert!(effect.is_none());
        let InputMode::Confirm(Confirmation::EmptyTrash { count }) = &reader.input else {
            panic!("the reader still opens the sweep confirm");
        };
        assert_eq!(*count, None, "no trash feed loaded means depth unknown");
        // A suspended trash feed in history still contributes its count.
        let mut stacked_reader = reader_model(flagged_card("memo-t", "Trashed body", false, true));
        let mut suspended = FeedState::new(FeedKind::Trash);
        suspended.memos = (0..4)
            .map(|index| flagged_card(&format!("t-{index}"), "Trashed", false, true))
            .collect();
        suspended.total = Some(4);
        suspended.load = LoadStatus::Ready;
        stacked_reader.history = vec![View::Feed(Box::new(suspended))];
        let effect = apply_command(&mut stacked_reader, Command::EmptyTrash);
        assert!(effect.is_none());
        let InputMode::Confirm(Confirmation::EmptyTrash { count }) = &stacked_reader.input else {
            panic!("the sweep confirm opens");
        };
        assert_eq!(*count, Some(4), "a suspended trash feed's total counts");
    }

    // ---------- A-07: deleted targets fail in context ----------

    #[test]
    fn open_memo_on_a_vanished_target_pops_and_toasts() {
        let strings = UiStrings::detect();
        let mut model = feed_model(2);
        let gone = memo_id("gone-1");
        let effect = apply_command(&mut model, Command::OpenMemo(gone.clone()));
        let Some(Effect::ReadMemo { req, id }) = effect else {
            panic!("OpenMemo must issue a read effect, got {effect:?}");
        };
        assert_eq!(id, gone);
        assert!(
            matches!(model.view, View::Loading { .. }),
            "the placeholder stands in while the read is in flight"
        );
        let effect = apply_message(&mut model, RuntimeMessage::MemoGone { req, id: gone });
        assert!(effect.is_none());
        assert!(
            matches!(model.view, View::Feed(_)),
            "a gone memo pops the placeholder back to its source view"
        );
        assert!(
            model.status.as_deref().is_some_and(|text| {
                text.contains(strings.text("That memo no longer exists", "该记录已不存在"))
            }),
            "the miss must explain itself"
        );
        // A hard failure on the same request degrades the same way.
        let mut again = feed_model(2);
        let effect = apply_command(&mut again, Command::OpenMemo(memo_id("gone-2")));
        let Some(Effect::ReadMemo { req, .. }) = effect else {
            panic!("OpenMemo must issue a read effect");
        };
        let effect = apply_message(
            &mut again,
            RuntimeMessage::Failed {
                req,
                diagnostic: "store timeout".to_owned(),
            },
        );
        assert!(effect.is_none());
        assert!(matches!(again.view, View::Feed(_)));
        assert!(
            again.status.as_deref().is_some_and(|text| {
                text.contains(strings.text("Could not open that memo", "无法打开这条记录"))
            }),
            "an open failure stays a contextual toast, not a failed screen"
        );
    }

    #[test]
    fn reader_refresh_miss_keeps_the_reader_alive() {
        let strings = UiStrings::detect();
        let mut model = reader_model(card("memo-1", "Live body"));
        let req = model.request(PendingKind::RefreshReader {
            id: memo_id("memo-1"),
        });
        let effect = apply_message(
            &mut model,
            RuntimeMessage::MemoGone {
                req,
                id: memo_id("memo-1"),
            },
        );
        assert!(effect.is_none());
        assert!(
            matches!(model.view, View::Reader { .. }),
            "a refresh miss keeps the last-known reader, not a dead view"
        );
        assert!(
            model.status.as_deref().is_some_and(|text| {
                text.contains(strings.text("That memo no longer exists", "该记录已不存在"))
            }),
            "the miss still explains itself"
        );
    }

    // ---------- A-12: attachment open is a toast ----------

    #[test]
    fn attachment_open_notice_is_a_toast_and_retires_the_player_badge() {
        let mut model = feed_model(2);
        model.raise_badge(
            Severity::Warn,
            BadgeClass::Player,
            "Could not open the attachment: player died".to_owned(),
        );
        let req = model.request(PendingKind::Attachment);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::Message {
                req,
                title: "Attachment opened".to_owned(),
                lines: vec!["media/file.png".to_owned()],
            },
        );
        assert!(effect.is_none());
        assert_eq!(model.input, InputMode::Browse, "a toast seizes nothing");
        assert!(
            model
                .status
                .as_deref()
                .is_some_and(|text| text.contains("Attachment opened")),
            "the open notice lands on the status line"
        );
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Player),
            "the same-class success retires the Player badge"
        );
    }

    // ---------- A-04: search arrows drive the result list ----------

    #[test]
    fn search_field_arrows_drive_the_result_selection() {
        let mut model = search_model();
        let initial = feed(&model)
            .expect("feed")
            .selected
            .clone()
            .expect("a selection exists");
        let effect = apply_command(&mut model, Command::Move(1));
        assert!(effect.is_none(), "a motion is not an effect");
        let after = feed(&model)
            .expect("feed")
            .selected
            .clone()
            .expect("a selection still exists");
        assert_ne!(initial, after, "↓ must move the result selection");
        assert!(
            matches!(model.input, InputMode::Search { .. }),
            "movement keeps the search field"
        );
        let effect = apply_command(&mut model, Command::Move(-1));
        assert!(effect.is_none(), "a motion is not an effect");
        assert_eq!(
            feed(&model).expect("feed").selected.as_ref(),
            Some(&initial),
            "↑ returns to the previous selection"
        );
        // Typing keeps querying; the field's text is the filter.
        let effect = apply_command(&mut model, Command::Type("s".to_owned()));
        assert!(
            matches!(effect, Some(Effect::Query(..))),
            "a keystroke re-issues the filtered query, got {effect:?}"
        );
        let InputMode::Search { text } = &model.input else {
            panic!("the search field persists");
        };
        assert_eq!(text.text(), "needles");
    }

    // ---------- A-03/A-13: refusals explain themselves everywhere ----------

    #[test]
    fn an_empty_or_flying_draft_commit_refuses_aloud() {
        let mut empty = compose_model(false);
        let effect = apply_command(&mut empty, Command::Commit);
        assert!(effect.is_none());
        assert_eq!(
            empty.status.as_deref(),
            Some(Refusal::EmptyDraft.text()),
            "an empty commit names its reason"
        );
        assert!(matches!(empty.input, InputMode::Compose));

        let mut ready = compose_model(false);
        ready.draft.text.insert("draft body");
        ready.draft.revision = 1;
        let effect = apply_command(&mut ready, Command::Commit);
        assert!(
            matches!(effect, Some(Effect::CommitDraft { .. })),
            "a ready draft commits, got {effect:?}"
        );
        assert_eq!(ready.draft.submitting_revision(), Some(1));

        let mut flying = compose_model(true);
        let effect = apply_command(&mut flying, Command::Commit);
        assert!(effect.is_none());
        assert_eq!(
            flying.status.as_deref(),
            Some(Refusal::Submitting.text()),
            "a second commit says a save is in flight"
        );
    }

    #[test]
    fn refused_reasons_are_all_distinct() {
        let reasons = [
            Refusal::NoSelection,
            Refusal::NoMatches,
            Refusal::NoRoom,
            Refusal::MemoTrashed,
            Refusal::MemoNotTrashed,
            Refusal::OutsideTrash,
            Refusal::TrashEmpty,
            Refusal::AlreadyThere,
            Refusal::AlreadyReading,
            Refusal::TopLevel,
            Refusal::EmptyDraft,
            Refusal::Submitting,
            Refusal::NothingSaved,
            Refusal::NoNotice,
            Refusal::FilterUnset,
            Refusal::NoFilters,
            Refusal::FeedOnly,
        ];
        let mut seen = std::collections::HashSet::new();
        for reason in reasons {
            assert!(
                seen.insert(reason.text()),
                "refusal {reason:?} shares its text with another reason"
            );
        }
    }

    #[test]
    fn mutation_outcomes_report_what_they_did() {
        let outcomes = [
            MutationOutcome::Pinned,
            MutationOutcome::Unpinned,
            MutationOutcome::Trashed,
            MutationOutcome::Restored,
            MutationOutcome::DeletedForever,
            MutationOutcome::TaskToggled { done: true },
            MutationOutcome::TaskToggled { done: false },
            MutationOutcome::TrashEmptied { removed: 3 },
            MutationOutcome::RevisionRestored { revision: 4 },
            MutationOutcome::ClipboardImported,
        ];
        let mut seen = std::collections::HashSet::new();
        for outcome in outcomes {
            assert!(
                seen.insert(outcome.status()),
                "outcome {outcome:?} shares its status text"
            );
        }
        assert!(
            MutationOutcome::TrashEmptied { removed: 7 }
                .status()
                .contains('7'),
            "the sweep receipt keeps its count"
        );
        assert!(
            MutationOutcome::RevisionRestored { revision: 9 }
                .status()
                .contains("r9"),
            "the revision receipt keeps its number"
        );
    }

    // ---------- A-11: navigation refuses the current screen ----------

    #[test]
    fn goto_self_refuses_and_goto_over_loading_supersedes_in_place() {
        let mut same = feed_model(2);
        let effect = apply_command(&mut same, Command::Goto(Screen::Timeline));
        assert!(effect.is_none());
        assert_eq!(
            same.status.as_deref(),
            Some(Refusal::AlreadyThere.text()),
            "navigating to the live screen refuses aloud"
        );
        assert!(same.history.is_empty(), "a refused goto stacks nothing");

        let mut loading = loading_model();
        let depth = loading.history.len();
        let before_view = loading.view.clone();
        let effect = apply_command(&mut loading, Command::Goto(Screen::Statistics));
        assert!(
            matches!(effect, Some(Effect::Navigate { .. })),
            "a different destination navigates"
        );
        assert_eq!(
            loading.history.len(),
            depth,
            "superseding a pending placeholder must not stack it"
        );
        assert!(
            matches!(
                loading.view,
                View::Loading {
                    screen: Screen::Statistics,
                    ..
                }
            ),
            "the new placeholder supersedes in place, was {before_view:?}"
        );
        // A failed screen re-navigates instead of refusing its own screen.
        let mut failed = failed_model();
        let effect = apply_command(&mut failed, Command::Goto(Screen::Timeline));
        assert!(
            matches!(effect, Some(Effect::Navigate { .. })),
            "a failed screen must retry its own destination"
        );
    }

    // ---------- A-14: parked replies wait for the focus ----------

    #[test]
    fn a_history_reply_parks_under_modal_input_and_delivers_after() {
        let mut model = confirm_model(Confirmation::EmptyTrash { count: Some(1) });
        let req = model.request(PendingKind::History {
            id: memo_id("memo-0"),
        });
        let effect = apply_message(
            &mut model,
            RuntimeMessage::History {
                req,
                id: memo_id("memo-0"),
                revisions: vec![RevisionRow {
                    revision: 1,
                    stamp: "2026-09-09 09:00".to_owned(),
                    preview: "old".to_owned(),
                }],
            },
        );
        assert!(effect.is_none());
        assert!(
            matches!(model.input, InputMode::Confirm(_)),
            "the parked reply does not evict the dialog"
        );
        assert!(
            model.status.is_some(),
            "the park explains itself on the status line"
        );
        assert_eq!(model.parked.len(), 1);
        // Esc unwinds the dialog; the parked reply then opens its picker.
        let effect = apply_command(&mut model, Command::Back);
        assert!(effect.is_none());
        assert!(
            matches!(
                model.input,
                InputMode::Picker(Picker {
                    kind: PickerKind::History { .. },
                    ..
                })
            ),
            "the parked history opens once the model returns to Browse"
        );
    }

    // ---------- the C-16 notice seam ----------

    #[test]
    fn a_modal_notice_under_busy_input_becomes_an_unread_badge() {
        let mut model = compose_model(false);
        model.present(Notice::modal(
            Severity::Warn,
            "Draft kept".to_owned(),
            vec!["drafts/abc.md".to_owned(), "conflict detected".to_owned()],
        ));
        assert!(
            matches!(model.input, InputMode::Compose),
            "a modal must not seize a busy input"
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Notice),
            "the unread mark is a badge"
        );
        assert!(model.status.is_some(), "the notice still toasts a summary");
        // Esc persists the open draft back to Browse, then `:` replays the
        // registered notice — and reading it retires the unread mark.
        let effect = apply_command(&mut model, Command::Back);
        assert!(
            matches!(effect, Some(Effect::PersistDraft { .. })),
            "leaving the composer persists the draft, got {effect:?}"
        );
        let effect = apply_command(&mut model, Command::ShowNotice);
        assert!(effect.is_none());
        let InputMode::Message { title, lines, .. } = &model.input else {
            panic!("the replay opens the full notice");
        };
        assert_eq!(title, "Draft kept");
        assert_eq!(lines.len(), 2, "the modal keeps every content line");
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Notice),
            "reading the notice retires its badge"
        );
    }

    // ---------- I9: badge/toast lifecycle ----------

    #[test]
    fn a_toast_outlives_unrelated_commands_and_input_round_trips() {
        let mut model = feed_model(3);
        model.set_status("Saved");
        let _ignored = apply_command(&mut model, Command::Move(1));
        assert_eq!(model.status.as_deref(), Some("Saved"));
        // A refused command replaces the toast with its own reason — that is
        // the layering contract, not silent erasure.
        let effect = apply_command(&mut model, Command::DeleteForever);
        assert!(effect.is_none());
        assert_eq!(
            model.status.as_deref(),
            Some(Refusal::MemoNotTrashed.text())
        );
        // And a new toast survives opening and closing an overlay.
        let mut helped = feed_model(3);
        helped.set_status("Saved");
        let effect = apply_command(&mut helped, Command::Help);
        assert!(effect.is_none());
        let effect = apply_command(&mut helped, Command::Back);
        assert!(effect.is_none());
        assert_eq!(helped.status.as_deref(), Some("Saved"));
    }

    #[test]
    fn badges_persist_through_unrelated_commands_until_acknowledged() {
        let mut model = feed_model(3);
        let req = model.request(PendingKind::Mutation);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::Failed {
                req,
                diagnostic: "pin failed".to_owned(),
            },
        );
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Action),
            "a mutation failure raises the Action badge"
        );
        // Unrelated commands leave it standing.
        let _ignored = apply_command(&mut model, Command::Move(1));
        let _ignored = apply_command(&mut model, Command::Refresh);
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Action),
            "unrelated commands never clear a badge"
        );
        // Esc at the root is the acknowledgement gesture — and only that.
        let effect = apply_command(&mut model, Command::Back);
        assert!(effect.is_none());
        assert!(
            model.badges.is_empty() && model.status.is_none(),
            "root Esc retires every mark"
        );
        assert!(
            matches!(model.view, View::Feed(_)) && model.history.is_empty(),
            "acknowledgement is not navigation"
        );
    }

    #[test]
    fn repeated_same_class_failures_replace_not_accumulate() {
        let mut model = feed_model(2);
        for diagnostic in ["pin failed", "delete failed"] {
            let req = model.request(PendingKind::Mutation);
            let effect = apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: diagnostic.to_owned(),
                },
            );
            assert!(effect.is_none());
        }
        let actions = model
            .badges
            .iter()
            .filter(|badge| badge.class == BadgeClass::Action)
            .count();
        assert_eq!(actions, 1, "one badge per class, refreshed not stacked");
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.text.contains("delete failed")),
            "the latest diagnostic replaces the stale one"
        );
    }

    #[test]
    fn same_class_success_retires_only_its_own_badge() {
        let mut model = feed_model(2);
        model.raise_badge(
            Severity::Warn,
            BadgeClass::Action,
            "action failed".to_owned(),
        );
        model.raise_badge(
            Severity::Warn,
            BadgeClass::Player,
            "player failed".to_owned(),
        );
        let req = model.request(PendingKind::Mutation);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::Mutated {
                req,
                outcome: MutationOutcome::Pinned,
            },
        );
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Action),
            "a mutation success retires the Action badge"
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Player),
            "an unrelated class is untouched"
        );
        assert!(
            matches!(effect, Some(Effect::Query(..))),
            "a committed mutation refreshes the feed, got {effect:?}"
        );
    }

    #[test]
    fn every_failure_intent_lands_on_its_badge_class() {
        let classes: [(PendingKind, BadgeClass); 6] = [
            (PendingKind::Mutation, BadgeClass::Action),
            (PendingKind::Quit, BadgeClass::Action),
            (PendingKind::Maintenance, BadgeClass::Sync),
            (PendingKind::ConfigReload, BadgeClass::Sync),
            (PendingKind::Attachment, BadgeClass::Player),
            (PendingKind::DraftCommit { revision: 1 }, BadgeClass::Draft),
        ];
        for (kind, class) in classes {
            let mut model = feed_model(2);
            let req = model.request(kind.clone());
            let effect = apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "boom".to_owned(),
                },
            );
            assert!(effect.is_none());
            assert!(
                model.badges.iter().any(|badge| badge.class == class),
                "{kind:?} failure must raise the {class:?} badge"
            );
            assert!(
                model
                    .status
                    .as_deref()
                    .is_some_and(|text| text.contains("boom")),
                "{kind:?} failure still toasts its diagnostic"
            );
        }
        // Lookup/refresh misses are context — status only, no badge.
        for kind in [
            PendingKind::Tags,
            PendingKind::History {
                id: memo_id("memo-0"),
            },
            PendingKind::Date { dialog: false },
            PendingKind::Bodies,
            PendingKind::Image,
        ] {
            let mut model = feed_model(2);
            let req = model.request(kind.clone());
            let effect = apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "lookup failed".to_owned(),
                },
            );
            assert!(effect.is_none());
            assert!(
                model.badges.is_empty(),
                "{kind:?} is a lookup — a badge would overstate it"
            );
            assert!(
                model
                    .status
                    .as_deref()
                    .is_some_and(|text| { text.contains("lookup failed") }),
                "{kind:?} failure still leaves a status trace"
            );
        }
    }

    #[test]
    fn a_partial_trash_sweep_keeps_its_progress_as_badge_text() {
        let mut model = trash_model(5);
        let req = model.request(PendingKind::Mutation);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::Failed {
                req,
                diagnostic: "stopped after 3 of 5 memos".to_owned(),
            },
        );
        assert!(effect.is_none());
        let badge = model
            .badges
            .iter()
            .find(|badge| badge.class == BadgeClass::Action)
            .expect("a sweep failure raises the Action badge");
        assert!(
            badge.text.contains('3') && badge.text.contains('5'),
            "the badge keeps the N/M progress: {}",
            badge.text
        );
        assert!(
            model
                .status
                .as_deref()
                .is_some_and(|text| text.contains("3 of 5")),
            "the status line carries the same progress"
        );
    }

    #[test]
    fn player_exit_badges_persist_until_a_clean_exit_retires_them() {
        let mut model = feed_model(2);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::PlayerFinished {
                success: false,
                diagnostic: Some("player segfaulted".to_owned()),
            },
        );
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Player),
            "a dirty player exit leaves a persistent mark"
        );
        let effect = apply_message(
            &mut model,
            RuntimeMessage::PlayerFinished {
                success: true,
                diagnostic: None,
            },
        );
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Player),
            "a clean exit retires the mark"
        );
    }

    #[test]
    fn watcher_outage_and_recovery_drive_the_watch_badge() {
        let mut model = feed_model(2);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::WatcherUnavailable {
                diagnostic: "inotify died".to_owned(),
            },
        );
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Watch),
            "an outage raises the Watch badge"
        );
        let effect = apply_message(&mut model, RuntimeMessage::WatcherReady);
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Watch),
            "recovery retires the badge"
        );
    }

    #[test]
    fn worker_death_fails_loud_and_marks_terminal() {
        let mut model = feed_model(2);
        let effect = apply_message(
            &mut model,
            RuntimeMessage::WorkerDied {
                lane: lomo_tui::effects::Lane::Query,
                diagnostic: "query lane died".to_owned(),
            },
        );
        assert!(effect.is_none());
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Worker),
            "a dead lane raises the terminal badge"
        );
        assert!(
            matches!(model.view, View::Failed { .. }),
            "a dead lane also fails the view closed"
        );
    }

    #[test]
    fn focus_regain_is_silent_while_watching_and_never_clobbers_dialogs() {
        // Healthy watcher: focus is a pure no-op on the model.
        let mut model = search_model();
        model.watcher_active = true;
        model.set_status("Saved");
        let before = snapshot(&model);
        let effect = focus_reconcile(&mut model);
        assert!(effect.is_none());
        assert_eq!(
            snapshot(&model),
            before,
            "a healthy watcher makes focus a no-op"
        );
        // Dead watcher: the outage hint surfaces but dialogs survive.
        let mut dead = confirm_model(Confirmation::EmptyTrash { count: Some(1) });
        dead.watcher_active = false;
        let effect = focus_reconcile(&mut dead);
        assert!(effect.is_none());
        assert!(
            matches!(dead.input, InputMode::Confirm(_)),
            "focus never closes a dialog"
        );
    }

    // ---------- I9: the root Esc acknowledgement ----------

    #[test]
    fn root_esc_acknowledges_feedback_and_only_feedback() {
        // Status only.
        let mut toasted = feed_model(2);
        toasted.set_status("Saved");
        let effect = apply_command(&mut toasted, Command::Back);
        assert!(effect.is_none());
        assert_eq!(toasted.status, None);
        assert!(matches!(toasted.view, View::Feed(_)));
        // Badges only.
        let mut badged = feed_model(2);
        badged.raise_badge(Severity::Warn, BadgeClass::Sync, "sync failed".to_owned());
        let effect = apply_command(&mut badged, Command::Back);
        assert!(effect.is_none());
        assert!(badged.badges.is_empty());
        // Nothing left: Esc refuses and names it.
        let mut bare = feed_model(2);
        let effect = apply_command(&mut bare, Command::Back);
        assert!(effect.is_none());
        assert_eq!(bare.status.as_deref(), Some(Refusal::TopLevel.text()));
    }

    #[test]
    fn esc_clears_filters_before_peeling_history() {
        let mut model = filtered_model();
        let depth = model.history.len();
        // Give the feed a suspended unfiltered context like a real filter run.
        if let View::Feed(feed) = &mut model.view {
            feed.unfiltered = Some(UnfilteredContext {
                query: FeedQuery::default(),
                selected: feed.selected.clone(),
                anchor: feed.anchor.clone(),
            });
        }
        let effect = apply_command(&mut model, Command::Back);
        assert!(
            matches!(effect, Some(Effect::Query(..))),
            "Esc on a filtered feed reloads, got {effect:?}"
        );
        assert_eq!(
            model.history.len(),
            depth,
            "filters peel before history pops"
        );
        if let View::Feed(feed) = &model.view {
            assert!(!feed.query.is_filtered(), "the filter itself cleared");
        }
    }

    // ---------- I9: confirmations name their targets ----------

    #[test]
    fn delete_and_permanent_delete_dialogs_name_and_distinguish_targets() {
        let strings = UiStrings::detect();
        let target = card("memo-1", "Body 1: 阅读内容");
        let trash_dialog = render(&confirm_model(Confirmation::Delete {
            memo: Box::new(target.clone()),
        }));
        let forever_dialog = render(&confirm_model(Confirmation::DeleteForever(Box::new(
            target,
        ))));
        for text in [&trash_dialog, &forever_dialog] {
            assert!(
                text.contains("2026-09-11") && text.contains("12:00"),
                "the dialog names its memo's stamp"
            );
            assert!(
                text.contains("Body 1"),
                "the dialog names its memo's excerpt"
            );
        }
        assert_ne!(
            trash_dialog, forever_dialog,
            "the two delete paths must read differently"
        );
        assert!(
            trash_dialog.contains(strings.text("trash", "回收站")),
            "the normal delete still offers a trash"
        );
        assert!(
            forever_dialog.contains(strings.text("permanently", "永久")),
            "the permanent delete says so"
        );
    }

    #[test]
    fn every_confirm_variant_names_its_target() {
        let strings = UiStrings::detect();
        // Restore carries the same frozen card.
        let restored = render(&confirm_model(Confirmation::Restore(Box::new(card(
            "memo-2",
            "Restored body",
        )))));
        assert!(
            restored.contains("2026-09-11") && restored.contains("Restored body"),
            "restore names its memo"
        );
        // RestoreRevision names revision + stamp + preview.
        let revision = render(&confirm_model(Confirmation::RestoreRevision {
            id: memo_id("memo-0"),
            revision: 3,
            stamp: "2026-09-10 08:00".to_owned(),
            preview: "older body".to_owned(),
        }));
        for part in ["r3", "2026-09-10 08:00", "older body"] {
            assert!(
                revision.contains(part),
                "the revision dialog dropped '{part}'"
            );
        }
        // EmptyTrash names the count when known.
        let sweep = render(&confirm_model(Confirmation::EmptyTrash { count: Some(7) }));
        assert!(
            sweep.contains(&format!(
                "{} 7",
                strings.text("Trash now holds", "回收站现有")
            )),
            "the sweep dialog names its count"
        );
        // DiscardDraft shows what it drops.
        let draft = render(&confirm_model(Confirmation::DiscardDraft {
            preview: "half-written draft".to_owned(),
        }));
        assert!(
            draft.contains("half-written draft"),
            "the draft dialog names its preview"
        );
    }

    #[test]
    fn confirmations_render_at_pathological_sizes() {
        let sizes = [(20, 3), (30, 5), (80, 24), (12, 4)];
        for (width, height) in sizes {
            let mut narrow = confirm_model(Confirmation::DeleteForever(Box::new(card(
                "memo-x",
                "窄屏确认正文",
            ))));
            narrow.width = width;
            narrow.height = height;
            let _rendered = render(&narrow);
            // The contract is "never panic, always escapable": y/n keys still work.
            let effect = apply_command(&mut narrow, Command::Back);
            assert!(effect.is_none());
            assert_eq!(
                narrow.input,
                InputMode::Browse,
                "Esc dismisses the dialog at {width}x{height}"
            );
        }
    }

    #[test]
    fn confirm_dismiss_is_cancel_only_and_confirm_acts_once() {
        let mut model = confirm_model(Confirmation::EmptyTrash { count: Some(2) });
        let before = snapshot(&model);
        let effect = apply_command(&mut model, Command::Back);
        assert!(effect.is_none(), "n is a dismiss, never an action");
        let mut expect = before;
        expect.input = InputMode::Browse;
        assert_eq!(
            snapshot(&model),
            expect,
            "dismissing a confirm changes only the input"
        );
        // And 'y' produces exactly the named mutation.
        let mut armed = confirm_model(Confirmation::Delete {
            memo: Box::new(card("memo-1", "Body 1")),
        });
        let effect = apply_command(&mut armed, Command::Accept);
        assert!(
            matches!(effect, Some(Effect::Delete { .. })),
            "y on a delete confirm issues the trash effect, got {effect:?}"
        );
    }

    // ---------- the Date dialog's error must survive inert input ----------

    #[test]
    fn a_date_error_survives_commands_that_do_not_edit_text() {
        // The inline error is the dialog's refusal evidence (C-17): sibling
        // field editors (settings, setup) clear their errors only on a real
        // text change — a scroll, a movement key or an unreachable command
        // must not erase what the user still needs to see.
        for command in [
            Command::Move(1),
            Command::Scroll(1),
            Command::Page(1),
            Command::First,
            Command::Pin,
            Command::Goto(Screen::Tasks),
        ] {
            let mut probe = date_dialog_model();
            let effect = apply_command(&mut probe, command.clone());
            assert!(effect.is_none());
            let InputMode::Date { error, text, .. } = &probe.input else {
                panic!("the date dialog must stay open");
            };
            assert_eq!(
                error.as_deref(),
                Some("cannot resolve that date"),
                "{command:?} must not erase an error it cannot resolve"
            );
            assert_eq!(text.text(), "bogus", "{command:?} must not edit the text");
        }
        // An actual edit still clears it — that part of the contract is right.
        let mut edited = date_dialog_model();
        let effect = apply_command(&mut edited, Command::Type("2".to_owned()));
        assert!(effect.is_none());
        let InputMode::Date { error, text, .. } = &edited.input else {
            panic!("the date dialog must stay open");
        };
        assert_eq!(text.text(), "bogus2");
        assert_eq!(*error, None, "a real edit may retire the stale error");
    }

    #[test]
    fn a_failed_date_reply_lands_back_on_its_dialog() {
        let mut model = feed_model(2);
        model.input = InputMode::Date {
            req: None,
            text: TextBuffer::new("bogus".to_owned()),
            error: None,
        };
        let effect = apply_command(&mut model, Command::Accept);
        let Some(Effect::Date { req, .. }) = effect else {
            panic!("Enter on the dialog must issue a resolution, got {effect:?}");
        };
        let InputMode::Date { req: awaiting, .. } = &model.input else {
            panic!("the dialog stays open awaiting the reply");
        };
        assert_eq!(*awaiting, Some(req));
        let effect = apply_message(
            &mut model,
            RuntimeMessage::Failed {
                req,
                diagnostic: "no such date".to_owned(),
            },
        );
        assert!(effect.is_none());
        let InputMode::Date { error, .. } = &model.input else {
            panic!("the failure lands back on the dialog, not the status line");
        };
        assert_eq!(error.as_deref(), Some("no such date"));
    }

    // ---------- status row composition ----------

    #[test]
    fn the_status_row_keeps_badges_beside_the_toast() {
        let mut model = feed_model(3);
        model.raise_badge(Severity::Error, BadgeClass::Watch, "watch died".to_owned());
        model.set_status("Saved");
        let text = render(&model);
        assert!(
            text.contains("watch died") && text.contains("Saved"),
            "the badge and the toast share the status row"
        );
    }

    #[test]
    fn resize_never_panics_and_keeps_state() {
        let mut model = search_model();
        let before = snapshot(&model);
        lomo_tui::update::apply_resize(&mut model, 30, 8);
        // The resize only re-measures: everything else is identical.
        assert_eq!(snapshot(&model), before, "a resize never steals state");
        let text = render(&model);
        assert!(!text.is_empty(), "the resized frame still draws");
    }
}
