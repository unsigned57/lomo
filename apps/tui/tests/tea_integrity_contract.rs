// adversarial-audit: TEA async integrity — request/receipt correlation locks.
// Every receipt-bearing effect registers a `PendingKind` intent under a `Req`;
// every `RuntimeMessage` claims that request before it may touch state. These
// probes lock the I1 invariants GREEN: superseded requests never land, dead
// loads are retried instead of faked Ready, failures always leave a trace,
// and History replies park instead of hitting whatever is on screen.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{feed, feed_mut, model_with_memos};
    use lomo_tui::{
        effects::{Effect, MutationOutcome, RuntimeMessage},
        event::{Command, TextEdit},
        messages::apply_message,
        model::{
            BadgeClass, Confirmation, InputMode, LoadStatus, Notice, PendingKind, PickerKind, Req,
            RevisionRow, SaveState, Severity, View,
        },
        update::apply_command,
    };

    /// A submission marker left behind when the draft's revision advanced
    /// past it — the state the mid-commit discard used to leave behind.
    /// Reachable code can no longer produce it; the state machine treats the
    /// marker as dead rather than live (defense at the state level).
    fn superseded_submission() -> lomo_tui::model::AppModel {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.draft.revision = 2;
        let req = model.next_req();
        model.draft.save = SaveState::Submitting { req, revision: 1 };
        model
    }

    /// `Confirm(DiscardDraft)` clears text, bumps the revision AND retires the
    /// in-flight submission marker: the composer is usable immediately, and
    /// the stale commit receipt — whenever it lands — performs nothing.
    #[test]
    fn discarding_a_draft_retires_the_in_flight_commit() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.draft.text = lomo_tui::input::TextBuffer::new("draft".to_owned());
        model.draft.revision = 1;
        let req = model.request(PendingKind::DraftCommit { revision: 1 });
        model.draft.save = SaveState::Submitting { req, revision: 1 };
        model.input = InputMode::Confirm(Confirmation::DiscardDraft {
            preview: "draft".to_owned(),
        });
        assert!(matches!(
            apply_command(&mut model, Command::Accept),
            Some(Effect::PersistDraft { revision: 2, .. })
        ));
        assert_eq!(
            model.draft.save,
            SaveState::Editing,
            "the discard retires the in-flight submission mutex"
        );

        // The stale commit receipt lands without any durable-save side effect.
        let id = lomo_workspace::MemoId::parse("memo-9").expect("fixture id");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req,
                    revision: 1,
                    id
                }
            ),
            None,
            "a receipt for the discarded revision is fully inert"
        );
        assert_eq!(model.last_created, None);
        assert_eq!(model.status, None);
        assert_eq!(model.draft.revision, 2);

        // The composer accepts input again — and Esc still leaves it.
        assert!(matches!(
            apply_command(&mut model, Command::Compose),
            Some(Effect::Tags { .. })
        ));
        assert_eq!(
            apply_command(&mut model, Command::Type("fresh".to_owned())),
            None
        );
        assert_eq!(model.draft.text.text(), "fresh");
        assert!(matches!(
            apply_command(&mut model, Command::Back),
            Some(Effect::PersistDraft { revision: 3, .. })
        ));
        assert_eq!(model.input, InputMode::Browse);
    }

    /// A `Submitting` marker that outlived its revision is dead state, not a
    /// wedge: stale receipts are dropped before any side effect, and composer
    /// commands still work because the mutex only guards the live revision.
    #[test]
    fn a_superseded_submission_marker_cannot_wedge_the_composer() {
        let mut model = superseded_submission();
        let stale = model.next_req();
        let id = lomo_workspace::MemoId::parse("memo-9").expect("fixture id");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req: stale,
                    revision: 1,
                    id
                }
            ),
            None,
            "the stale Saved performs no reset and no refresh"
        );
        assert_eq!(model.last_created, None);
        assert_eq!(model.status, None);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: stale,
                    diagnostic: "disk full".to_owned(),
                }
            ),
            None,
            "a stale commit failure is dropped before touching the save state"
        );
        assert_eq!(
            model.status.as_deref(),
            Some("disk full"),
            "a dead request's failure still degrades visibly — never silent"
        );

        assert!(matches!(
            apply_command(&mut model, Command::Compose),
            Some(Effect::Tags { .. })
        ));
        assert_eq!(
            apply_command(&mut model, Command::Type("recovery".to_owned())),
            None
        );
        assert_eq!(model.draft.text.text(), "recovery");
        assert_eq!(
            model.draft.save,
            SaveState::Editing,
            "typing retires the dead marker"
        );
        // Ctrl+S commits the live revision — the dead one cannot shadow it.
        assert!(matches!(
            apply_command(&mut model, Command::Commit),
            Some(Effect::CommitDraft { revision: 3, .. })
        ));
        assert!(
            matches!(model.draft.save, SaveState::Submitting { revision: 3, .. }),
            "the mutex now guards revision 3"
        );
    }

    /// A superseded page request can never touch the feed again: its data
    /// reply settles silently, its failure still surfaces in the status line,
    /// and the feed's lifecycle stays owned by the live request — the feed is
    /// never parked in `LoadStatus::Loading` on a dead generation (F-02/F-12).
    #[test]
    fn a_superseded_page_request_cannot_wedge_or_touch_the_feed() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let cursor = lomo_application::PageCursor::new(
            "fingerprint".to_owned(),
            None,
            false,
            0,
            0,
            "memo-1".to_owned(),
            0,
        );
        feed_mut(&mut model).expect("feed").next_cursor = Some(cursor.clone());
        let Some(Effect::Query(request)) = lomo_tui::update::issue_query(
            &mut model,
            lomo_tui::effects::PageIntent::Append(cursor),
        ) else {
            panic!("append request");
        };
        let stale = request.req;
        // A second query supersedes the first — it owns the feed slot now.
        let live = feed_mut(&mut model).expect("feed").next_cursor.clone();
        let Some(Effect::Query(request)) = lomo_tui::update::issue_query(
            &mut model,
            lomo_tui::effects::PageIntent::Append(live.expect("cursor")),
        ) else {
            panic!("second append request");
        };
        let live = request.req;
        assert_ne!(stale, live);

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req: stale,
                    append: true,
                    cards: vec![],
                    next: None,
                    order: Vec::new(),
                    total: None,
                }
            ),
            None
        );
        assert_eq!(
            feed(&model).expect("feed").load,
            LoadStatus::Loading,
            "the live request still owns the loading lifecycle"
        );
        assert_eq!(
            feed(&model).expect("feed").pending_page,
            Some(live),
            "the slot belongs to the live request, not the superseded one"
        );

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: stale,
                    diagnostic: "disk read failed".to_owned(),
                }
            ),
            None,
            "the failure for the superseded request degrades instead of landing"
        );
        assert_eq!(
            model.status.as_deref(),
            Some("disk read failed"),
            "a dead request's failure still leaves a visible trace"
        );
        assert_eq!(
            feed(&model).expect("feed").load,
            LoadStatus::Loading,
            "the feed is untouched — its live request is still in flight"
        );

        // The live request's failure is what unblocks the feed: explicit
        // Failed state, request released, and pagination can retry (F-02).
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: live,
                    diagnostic: "disk gone".to_owned(),
                }
            ),
            None
        );
        assert!(
            matches!(feed(&model).expect("feed").load, LoadStatus::Failed(_)),
            "the live failure moves the feed out of Loading"
        );
        assert!(feed(&model).expect("feed").pending_page.is_none());
        assert!(
            matches!(
                lomo_tui::navigation::maybe_next_page(&mut model),
                Some(Effect::Query(_))
            ),
            "a failed feed can request the page again — no wedge"
        );
    }

    /// `go_back` never rewrites a wedged load as `Ready`: restoring a feed
    /// whose `Loading` has no live request behind it issues a replacement
    /// query under a fresh identity (F-03) — honesty, not cosmetics.
    #[test]
    fn a_restored_feed_reissues_a_dead_load_instead_of_faking_ready() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(matches!(model.view, View::Reader { .. }));
        // The feed is now underneath the reader; simulate the wedged load —
        // a `Loading` mark whose request died while the view was buried.
        let Some(View::Feed(underneath)) = model.history.first_mut() else {
            panic!("the feed is retained under the reader");
        };
        underneath.load = LoadStatus::Loading;

        assert!(
            matches!(
                apply_command(&mut model, Command::Back),
                Some(Effect::Query(_))
            ),
            "restoring a dead-loading feed reissues the page request"
        );
        let restored = feed(&model).expect("feed");
        assert_eq!(
            restored.load,
            LoadStatus::Loading,
            "the feed honestly reports that it is still loading"
        );
        assert!(
            restored
                .pending_page
                .is_some_and(|req| model.pending.contains(req)),
            "the new marker is owned by a live request"
        );
    }

    /// I9 semantic layering: a mutation failure registers a durable notice and
    /// raises a persistent `Action` badge — unrelated input can neither erase
    /// the toast nor clear the badge. Only the explicit Esc acknowledgement or
    /// a same-class `Mutated` success retires it.
    #[test]
    fn mutation_failures_persist_until_acknowledged_or_superseded() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let req = model.request(PendingKind::Mutation);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "fingerprint mismatch: memo changed".to_owned(),
                }
            ),
            None
        );
        assert!(model.status.is_some(), "the toast carries the diagnostic");
        assert!(
            model.notice.is_some(),
            "the failure registers for `:` replay"
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Action),
            "the failure raises a persistent Action badge"
        );

        // Unrelated input cannot erase the feedback — no status wipe, no
        // badge loss.
        assert_eq!(apply_command(&mut model, Command::Move(1)), None);
        assert!(model.status.is_some(), "unrelated input cannot erase it");
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Action),
            "the badge survives an unrelated command"
        );

        // The explicit top-level Esc is the acknowledgement: badges and the
        // toast retire together.
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert!(model.badges.is_empty(), "Esc acknowledges every badge");
        assert_eq!(model.status, None);
    }

    /// The badge's second retirement path: a same-class success — here a
    /// `Mutated` receipt for a later Mutation request — retires the mark the
    /// failure raised.
    #[test]
    fn a_same_class_success_retires_the_failure_badge() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let failed = model.request(PendingKind::Mutation);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: failed,
                    diagnostic: "gone".to_owned(),
                }
            ),
            None
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Action)
        );
        let ok = model.request(PendingKind::Mutation);
        assert!(
            apply_message(
                &mut model,
                RuntimeMessage::Mutated {
                    req: ok,
                    outcome: MutationOutcome::Pinned,
                }
            )
            .is_some(),
            "the successful mutation queues the feed refresh"
        );
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Action),
            "the same-class success retires the badge"
        );
    }

    /// The watcher outage is a persistent `Watch` badge — not a toast the
    /// next keypress could bury — and `WatcherReady` retires it (I9).
    #[test]
    fn watcher_outage_is_a_badge_retired_by_readiness() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::WatcherUnavailable {
                    diagnostic: "notify backend gone".to_owned(),
                }
            ),
            None
        );
        assert!(!model.watcher_active);
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Watch),
            "the outage raises the Watch badge"
        );
        assert!(model.notice.is_some());

        // Unrelated input leaves the outage marked.
        assert_eq!(apply_command(&mut model, Command::Move(1)), None);
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Watch)
        );

        assert_eq!(
            apply_message(&mut model, RuntimeMessage::WatcherReady),
            None
        );
        assert!(model.watcher_active);
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Watch),
            "WatcherReady retires the outage badge"
        );
    }

    /// A player exit failure raises the `Player` badge; a later clean exit
    /// retires it (I9).
    #[test]
    fn a_player_failure_badges_until_a_clean_exit() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::PlayerFinished {
                    success: false,
                    diagnostic: Some("mpv: exit 2".to_owned()),
                }
            ),
            None
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Player),
            "the exit failure raises the Player badge"
        );
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::PlayerFinished {
                    success: true,
                    diagnostic: None,
                }
            ),
            None
        );
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Player),
            "a clean exit retires the Player badge"
        );
    }

    /// Modal content arriving while another input owns the focus cannot seize
    /// it — the notice registers and an unread `Notice` badge marks it for
    /// `:` replay; `ShowNotice` then opens the stored notice and clears the
    /// badge (I9).
    #[test]
    fn a_modal_notice_parks_as_a_badge_under_a_busy_input() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.input = InputMode::Compose;
        model.present(Notice::modal(
            Severity::Warn,
            "Draft retained".to_owned(),
            vec!["/tmp/draft.md".to_owned()],
        ));
        assert!(
            matches!(model.input, InputMode::Compose),
            "the composer keeps its focus"
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Notice),
            "the unread mark stands in for the dialog"
        );
        assert!(model.status.is_some(), "a toast still lands");

        // Back out to Browse, then `:` replays the registered notice.
        assert!(matches!(
            apply_command(&mut model, Command::Back),
            Some(Effect::PersistDraft { .. }) | None
        ));
        assert!(matches!(model.input, InputMode::Browse));
        assert_eq!(apply_command(&mut model, Command::ShowNotice), None);
        assert!(
            matches!(model.input, InputMode::Message { .. }),
            "the stored notice opens as a modal"
        );
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Notice),
            "reading the notice retires its badge"
        );
    }

    /// A `ReadMemo` that resolves to a miss is `MemoGone`: the `OpenMemo`
    /// placeholder pops back to the feed and a friendly toast names the miss —
    /// never a `Failed` screen or a raw `config:` diagnostic (I9).
    #[test]
    fn a_missing_memo_restores_the_context_with_a_toast() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let id = lomo_workspace::MemoId::parse("gone-1").expect("fixture id");
        let req = model.request(PendingKind::OpenMemo);
        model.push_view(View::Loading {
            screen: lomo_tui::model::Screen::Timeline,
            req,
        });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::MemoGone {
                    req,
                    id: id.clone(),
                }
            ),
            None
        );
        assert!(
            matches!(model.view, View::Feed(_)),
            "the placeholder pops back to the source feed"
        );
        assert!(model.status.is_some(), "the miss leaves a toast");
        assert!(
            model
                .notice
                .as_ref()
                .is_some_and(|notice| notice.lines.contains(&id.as_str().to_owned())),
            "the toast names the missing memo"
        );
    }

    /// A successful attachment open is one-shot information: a status toast
    /// and a registered notice — never a focus-seizing modal (I9).
    #[test]
    fn an_opened_attachment_is_a_toast_not_a_modal() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let req = model.request(PendingKind::Attachment);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Message {
                    req,
                    title: "Opened attachment".to_owned(),
                    lines: vec!["/tmp/file.png".to_owned()],
                }
            ),
            None
        );
        assert!(
            matches!(model.input, InputMode::Browse),
            "no modal seizes the focus"
        );
        assert!(model.status.is_some(), "the toast lands");
        assert!(model.notice.is_some(), "the receipt registers for replay");
        // And the success retires any Player badge an earlier failure raised.
        model.raise_badge(Severity::Warn, BadgeClass::Player, "stale".to_owned());
        let req = model.request(PendingKind::Attachment);
        drop(apply_message(
            &mut model,
            RuntimeMessage::Message {
                req,
                title: "Opened attachment".to_owned(),
                lines: vec![],
            },
        ));
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Player),
            "the open success retires the Player badge"
        );
    }

    /// A History reply is bound to the request's memo identity — and when
    /// another input owns the focus it parks with a visible notice instead of
    /// landing on the wrong memo or vanishing (A-14/F-04). Returning to
    /// `Browse` delivers the parked picker.
    #[test]
    fn history_replies_land_on_their_request_and_park_under_a_busy_input() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture");
        assert_eq!(apply_command(&mut model, Command::Move(2)), None);
        let selected = model.selected_id().expect("selection").to_owned();
        assert_eq!(selected, "memo-2");
        let requested = lomo_workspace::MemoId::parse("memo-0").expect("fixture id");
        let req = model.request(PendingKind::History {
            id: requested.clone(),
        });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req,
                    id: requested,
                    revisions: vec![RevisionRow {
                        revision: 3,
                        stamp: String::new(),
                        preview: "old body".to_owned(),
                    }],
                }
            ),
            None
        );
        let InputMode::Picker(picker) = &model.input else {
            panic!("the reply opens a picker in the current browse context");
        };
        assert!(
            matches!(&picker.kind, PickerKind::History { id, .. } if id.as_str() == "memo-0"),
            "the picker serves the request's memo, not the browsed one"
        );

        // A reply arriving while another input owns the focus parks instead
        // of dropping — and it leaves a status trace that it is waiting.
        model.input = InputMode::Confirm(Confirmation::EmptyTrash { count: None });
        let waiting = lomo_workspace::MemoId::parse("memo-1").expect("fixture id");
        let req = model.request(PendingKind::History {
            id: waiting.clone(),
        });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req,
                    id: waiting,
                    revisions: vec![],
                }
            ),
            None
        );
        assert!(
            matches!(
                model.input,
                InputMode::Confirm(Confirmation::EmptyTrash { .. })
            ),
            "the busy input keeps the focus"
        );
        assert!(
            model.status.is_some(),
            "the parked reply leaves a visible trace"
        );

        // Closing the input returns the model to `Browse`, which drains the
        // parked receipt — the history picker opens now, never lost.
        assert_eq!(apply_command(&mut model, Command::Back), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("the parked reply is delivered once focus returns");
        };
        assert!(
            matches!(&picker.kind, PickerKind::History { id, .. } if id.as_str() == "memo-1"),
            "the parked picker serves the request's memo"
        );
    }

    /// `DraftStored` replies guard the persisted revision monotonically:
    /// future revisions cannot poison it and stale ones cannot regress it.
    #[test]
    fn draftstored_replies_never_regress_the_persisted_revision() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.draft.revision = 5;
        let req = model.request(PendingKind::DraftPersist { revision: 7 });
        assert_eq!(
            apply_message(&mut model, RuntimeMessage::DraftStored { req, revision: 7 }),
            None
        );
        assert_eq!(
            model.draft.persisted_revision, 0,
            "a reply beyond the model revision is ignored"
        );
        let req = model.request(PendingKind::DraftPersist { revision: 3 });
        assert_eq!(
            apply_message(&mut model, RuntimeMessage::DraftStored { req, revision: 3 }),
            None
        );
        assert_eq!(model.draft.persisted_revision, 3);
        let req = model.request(PendingKind::DraftPersist { revision: 2 });
        assert_eq!(
            apply_message(&mut model, RuntimeMessage::DraftStored { req, revision: 2 }),
            None
        );
        assert_eq!(
            model.draft.persisted_revision, 3,
            "an out-of-order older reply cannot regress persistence"
        );
    }

    /// `View::Failed` keeps only retry-shaped commands alive: navigation keys
    /// and memo actions are dead, F5 still reaches `Effect::Refresh`, and the
    /// composer opens over the failed view.
    #[test]
    fn a_failed_view_keeps_only_retry_and_composition_alive() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let req = model.request(PendingKind::Bootstrap);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "workspace lost".to_owned(),
                }
            ),
            None
        );
        assert!(matches!(model.view, View::Failed { .. }));
        assert_eq!(apply_command(&mut model, Command::Move(1)), None);
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert_eq!(apply_command(&mut model, Command::Pin), None);
        assert!(
            matches!(
                apply_command(&mut model, Command::Refresh),
                Some(Effect::Refresh { .. })
            ),
            "F5 remains the retry path on a failed view"
        );
        assert!(
            matches!(
                apply_command(&mut model, Command::Compose),
                Some(Effect::Tags { .. })
            ),
            "capture still opens over the failure"
        );
        assert_eq!(model.input, InputMode::Compose);
    }

    /// A live `SaveState::Submitting` still gates the whole composer — the
    /// mutex that freezes the submitted revision while its commit is in
    /// flight. It only guards the revision it was taken on (see
    /// `a_superseded_submission_marker_cannot_wedge_the_composer`).
    #[test]
    fn submitting_blocks_every_composer_command() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.input = InputMode::Compose;
        model.draft.text = lomo_tui::input::TextBuffer::new("in flight".to_owned());
        model.draft.revision = 1;
        let req = model.request(PendingKind::DraftCommit { revision: 1 });
        model.draft.save = SaveState::Submitting { req, revision: 1 };
        for command in [
            Command::Type("x".to_owned()),
            Command::Edit(TextEdit::Backspace),
            Command::Commit,
            Command::ExternalEdit,
        ] {
            assert_eq!(
                apply_command(&mut model, command.clone()),
                None,
                "a live Submitting must swallow {command:?}"
            );
        }
        assert_eq!(model.draft.text.text(), "in flight");
    }

    /// A Tags reply lands only while its request is live: a foreign or
    /// superseded reply settles silently instead of overwriting the tag
    /// dictionary (F-11), and the newest live request always wins.
    #[test]
    fn tags_replies_land_only_while_their_request_is_live() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.set_tags(vec!["current".to_owned()]);

        // A reply naming no live request is foreign — it cannot land at all.
        let foreign: Req = model.next_req();
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Tags {
                    req: foreign,
                    tags: vec!["stale".to_owned()]
                }
            ),
            None
        );
        assert_eq!(
            model.tags(),
            vec!["current".to_owned()].as_slice(),
            "a foreign Tags reply cannot overwrite the dictionary"
        );

        // The live request's reply lands.
        let live = model.request(PendingKind::Tags);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Tags {
                    req: live,
                    tags: vec!["fresh".to_owned()]
                }
            ),
            None
        );
        assert_eq!(model.tags(), vec!["fresh".to_owned()].as_slice());

        // A newer load supersedes the in-flight one exactly as `tag_request`
        // does; the superseded reply then settles without touching state.
        let superseded = model.request(PendingKind::Tags);
        model.pending.cancel(superseded);
        let newest = model.request(PendingKind::Tags);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Tags {
                    req: superseded,
                    tags: vec!["old".to_owned()]
                }
            ),
            None
        );
        assert_eq!(
            model.tags(),
            vec!["fresh".to_owned()].as_slice(),
            "the superseded request's reply is ignored"
        );
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Tags {
                    req: newest,
                    tags: vec!["newest".to_owned()]
                }
            ),
            None
        );
        assert_eq!(model.tags(), vec!["newest".to_owned()].as_slice());
    }

    /// A `Saved` that does not name the live in-flight submission is fully
    /// inert: no `last_created` anchor, no status line, no feed refresh, and
    /// the draft is left exactly as it stands — whether its request is dead
    /// or the intent was claimed but the mutex already moved on.
    #[test]
    fn a_stale_saved_performs_no_side_effects() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.draft.revision = 4;
        let id = lomo_workspace::MemoId::parse("memo-8").expect("fixture id");
        let req = model.request(PendingKind::DraftCommit { revision: 2 });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req,
                    revision: 2,
                    id
                }
            ),
            None,
            "a Saved naming no live submission returns nothing"
        );
        assert_eq!(model.last_created, None);
        assert_eq!(model.status, None);
        assert_eq!(
            model.draft.revision, 4,
            "the mismatched draft is left untouched"
        );
    }

    // ——— F-06/F-07/F-08: supervision, bounded shutdown, no silent drops ———
    //
    // A worker that panicked used to leave the app half-alive: the dead
    // queue was discovered only by the next `send` error, quit could join a
    // wedged thread forever, `closing` swallowed the force-exit keystroke,
    // the watcher had no fallback, and monitor completions were silently
    // dropped. These probes lock the I3 contract.

    /// A lane worker panic is a `Failed` receipt plus a `WorkerDied`
    /// observation that lands at bootstrap severity — the dead lane refuses
    /// new work instead of accepting jobs it can never run.
    #[test]
    fn a_panicking_lane_fails_loudly_and_refuses_new_work() {
        use lomo_tui::effects::Lane;
        use lomo_tui::executor::{Refusal, Scheduler, Submit};
        use lomo_tui::model::Pending;

        let fixture = super::support::RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let runner: lomo_tui::executor::Runner = std::sync::Arc::new(
            |_runtime,
             effect,
             _outbox,
             _token|
             -> Result<RuntimeMessage, lomo_tui::error::TuiError> {
                if matches!(effect, Effect::Tags { .. }) {
                    panic!("synthetic lane panic");
                }
                Ok(RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            },
        );
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::ready(runtime));
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Tags);
        assert!(matches!(
            scheduler.submit(&mut pending, Effect::Tags { req: Req(1) }),
            Submit::Queued
        ));
        let mut failed_receipt = false;
        let mut died_observation = false;
        for _ in 0..4 {
            let reply = scheduler
                .replies()
                .recv_timeout(std::time::Duration::from_secs(5))
                .expect("the dead lane reports, it does not go quiet");
            if let RuntimeMessage::Failed { req, diagnostic } = &reply {
                assert_eq!(*req, Req(1));
                assert!(
                    diagnostic.contains("panic"),
                    "the panicking job's receipt names the panic: {diagnostic}"
                );
                failed_receipt = true;
            } else if let RuntimeMessage::WorkerDied { lane, .. } = &reply {
                assert_eq!(*lane, Lane::Query);
                died_observation = true;
            } else {
                panic!("unexpected reply while the lane dies: {reply:?}");
            }
            if failed_receipt && died_observation {
                break;
            }
        }
        assert!(
            failed_receipt && died_observation,
            "a panicking worker surfaces both the failed request and its own \
             death: receipt={failed_receipt} died={died_observation}"
        );
        // The dead lane refuses new admissions — it cannot queue work it will
        // never run.
        pending.register(Req(2), PendingKind::Tags);
        assert!(
            matches!(
                scheduler.submit(&mut pending, Effect::Tags { req: Req(2) }),
                Submit::Refused(Refusal::Dead)
            ),
            "a dead lane refuses new work instead of silently queuing it"
        );
        // And the observation lands at bootstrap severity: a dead lane fails
        // the view closed rather than leaving a half-alive session.
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::WorkerDied {
                    lane: Lane::Query,
                    diagnostic: "query lane panicked".to_owned()
                }
            ),
            None
        );
        assert!(
            matches!(model.view, View::Failed { .. }),
            "a dead worker is a visible failure, not a quiet wedged queue: {:?}",
            model.view
        );
        scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// The quit handshake is bounded: a second quit keystroke while closing
    /// forces the exit (Ctrl+C must not be swallowed), and expiry bounds the
    /// handshake so a wedged worker cannot suspend shutdown forever (F-06).
    #[test]
    fn the_quit_handshake_can_be_forced_and_is_bounded() {
        let mut gate = lomo_tui::host::CloseGate::new();
        assert!(!gate.is_closing());
        gate.begin();
        assert!(gate.is_closing());
        // A second quit request during the handshake is the operator forcing
        // the exit — never a swallowed event.
        assert!(gate.forces_exit(&Command::Quit));
        assert!(!gate.forces_exit(&Command::Scroll(1)));
        // The handshake cannot hang: after the budget the loop leaves.
        assert!(!gate.expired(std::time::Instant::now()));
        assert!(gate.expired(std::time::Instant::now() + std::time::Duration::from_secs(60)));
        // A failed persist reopens the session — the gate resets, it does not
        // force-quit past the failure.
        gate.reopen();
        assert!(!gate.is_closing());
        assert!(!gate.forces_exit(&Command::Quit));
    }

    /// A dead watcher must not leave the projection stale: while watching is
    /// unavailable a low-rate reconcile stands in, and the outage hint
    /// re-arms whenever the status slot clears — it cannot be silently erased
    /// (F-07; the persistent badge is the I9 seam).
    #[test]
    fn watcher_death_engages_a_low_rate_reconcile_fallback() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let mut last = None;
        let now = std::time::Instant::now();
        // Live watcher: no fallback work at all.
        assert_eq!(
            lomo_tui::update::watcher_fallback(&mut model, false, &mut last, now),
            None
        );
        // Death is detected: a reconcile stands in immediately with a live
        // pending intent, and the outage is visible.
        let first = lomo_tui::update::watcher_fallback(&mut model, true, &mut last, now);
        let Some(Effect::Reconcile { req, observed }) = first else {
            panic!("a dead watcher must stand in with reconcile: {first:?}");
        };
        assert!(
            observed.is_none(),
            "a dead watcher attests no path coverage"
        );
        assert!(model.pending.contains(req));
        assert!(
            model.status.is_some(),
            "the outage stays visible while the watcher is dead"
        );
        // The fallback is low-rate: back-to-back polls issue nothing new.
        assert_eq!(
            lomo_tui::update::watcher_fallback(
                &mut model,
                true,
                &mut last,
                now + std::time::Duration::from_millis(50)
            ),
            None
        );
        // …but the hint re-arms whenever the status slot clears.
        model.status = None;
        assert_eq!(
            lomo_tui::update::watcher_fallback(
                &mut model,
                true,
                &mut last,
                now + std::time::Duration::from_millis(60)
            ),
            None
        );
        assert!(
            model.status.is_some(),
            "the outage hint re-arms; it cannot be silently erased"
        );
        // After the interval a fresh reconcile is issued.
        assert!(
            matches!(
                lomo_tui::update::watcher_fallback(
                    &mut model,
                    true,
                    &mut last,
                    now + lomo_tui::update::WATCHER_FALLBACK_INTERVAL
                        + std::time::Duration::from_millis(1)
                ),
                Some(Effect::Reconcile { .. })
            ),
            "the fallback keeps reconciling at its interval"
        );
        // Recovery disarms — a healthy watcher never falls back.
        assert_eq!(
            lomo_tui::update::watcher_fallback(
                &mut model,
                false,
                &mut last,
                now + lomo_tui::update::WATCHER_FALLBACK_INTERVAL * 2
            ),
            None
        );
    }

    /// Producer observations (player exits, watcher signals) must never be
    /// silently dropped: a saturated inbox makes `report` fail with a trace
    /// instead of `drop(send)` swallowing the completion (F-08).
    #[test]
    fn an_undeliverable_observation_is_traced_not_dropped() {
        let (sender, _inbox) = std::sync::mpsc::sync_channel(1);
        sender
            .send(RuntimeMessage::WatcherReady)
            .expect("fill the channel");
        let outbox = lomo_tui::executor::Outbox::new(sender);
        let start = std::time::Instant::now();
        let delivered = outbox.report(RuntimeMessage::PlayerFinished {
            success: true,
            diagnostic: None,
        });
        assert!(
            !delivered,
            "a saturated inbox refuses the report — and the failure is traced"
        );
        assert!(
            start.elapsed() < std::time::Duration::from_secs(5),
            "the report path is bounded, not a blocking send: {:?}",
            start.elapsed()
        );
        let (sender, inbox) = std::sync::mpsc::sync_channel(1);
        let outbox = lomo_tui::executor::Outbox::new(sender);
        assert!(outbox.report(RuntimeMessage::WatcherReady));
        assert!(matches!(inbox.try_recv(), Ok(RuntimeMessage::WatcherReady)));
    }

    /// I5 bootstrap discipline: a lane job that needs the workspace parks on
    /// an `Opening` slot — nothing reaches the executor until `install`, and
    /// the install releases the parked work against the live runtime.
    #[test]
    fn runtime_dependent_work_parks_until_the_slot_is_installed() {
        use lomo_tui::executor::{Scheduler, Submit};
        use lomo_tui::model::Pending;

        let fixture = super::support::RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::opening());
        let entered = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let runner: lomo_tui::executor::Runner = {
            let entered = std::sync::Arc::clone(&entered);
            std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                entered.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Ok(RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            })
        };
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Tags);
        assert!(matches!(
            scheduler.submit(&mut pending, Effect::Tags { req: Req(1) }),
            Submit::Queued
        ));
        // Several wait-slices pass with the slot still empty — the job must
        // stay parked, not race the open.
        std::thread::sleep(std::time::Duration::from_millis(200));
        assert_eq!(
            entered.load(std::sync::atomic::Ordering::SeqCst),
            0,
            "a parked job must never reach the executor before install"
        );
        assert!(
            scheduler.replies().try_recv().is_err(),
            "a parked job emits no early receipt"
        );
        slot.install(runtime);
        let reply = scheduler
            .replies()
            .recv_timeout(std::time::Duration::from_secs(5))
            .expect("install releases the parked job");
        assert!(
            matches!(reply, RuntimeMessage::Changed { req: Req(1), .. }),
            "the parked job answers once the runtime is live: {reply:?}"
        );
        scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A request revoked while its job waits on the slot dies quietly: the
    /// worker never runs it and no synthetic receipt is minted — the pending
    /// intent is already gone, so a reply would only degrade.
    #[test]
    fn a_request_cancelled_while_parked_produces_no_receipt() {
        use lomo_tui::executor::{Scheduler, Submit};
        use lomo_tui::model::Pending;

        let fixture = super::support::RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::opening());
        let entered = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let runner: lomo_tui::executor::Runner = {
            let entered = std::sync::Arc::clone(&entered);
            std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                entered.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                Ok(RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            })
        };
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Tags);
        assert!(matches!(
            scheduler.submit(&mut pending, Effect::Tags { req: Req(1) }),
            Submit::Queued
        ));
        // Let the worker reach the parked wait, then revoke the request.
        std::thread::sleep(std::time::Duration::from_millis(150));
        drop(pending.cancel(Req(1)));
        slot.install(runtime);
        assert!(
            scheduler
                .replies()
                .recv_timeout(std::time::Duration::from_millis(500))
                .is_err(),
            "a request that died parked must not mint a synthetic receipt"
        );
        assert_eq!(
            entered.load(std::sync::atomic::Ordering::SeqCst),
            0,
            "the cancelled job never reached the executor"
        );
        scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A failed bootstrap fails every parked job with the same diagnostic the
    /// user sees — nothing waits on a runtime that will never arrive.
    #[test]
    fn a_failed_bootstrap_fails_parked_work_with_the_diagnostic() {
        use lomo_tui::executor::{Scheduler, Submit};
        use lomo_tui::model::Pending;

        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::opening());
        let runner: lomo_tui::executor::Runner =
            std::sync::Arc::new(|_runtime, effect, _outbox, _token| {
                Ok(RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            });
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Tags);
        assert!(matches!(
            scheduler.submit(&mut pending, Effect::Tags { req: Req(1) }),
            Submit::Queued
        ));
        slot.fail("cannot open workspace".to_owned());
        let reply = scheduler
            .replies()
            .recv_timeout(std::time::Duration::from_secs(5))
            .expect("a failed bootstrap still answers parked work");
        let RuntimeMessage::Failed { req, diagnostic } = reply else {
            panic!("parked work must fail, not vanish: {reply:?}");
        };
        assert_eq!(req, Req(1));
        assert!(
            diagnostic.contains("cannot open workspace"),
            "the parked job inherits the bootstrap diagnostic: {diagnostic}"
        );
        scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A maintenance-lane probe: `Refresh` jobs latch the lane inside the
    /// runner so queued `Reconcile` merges can be staged deterministically.
    struct ReconcileProbe {
        scheduler: lomo_tui::executor::Scheduler,
        pending: lomo_tui::model::Pending,
        captured:
            std::sync::Arc<std::sync::Mutex<Vec<Option<Vec<lomo_core::RelativeWorkspacePath>>>>>,
        entered_rx: std::sync::mpsc::Receiver<()>,
        release_tx: std::sync::mpsc::Sender<()>,
    }

    impl ReconcileProbe {
        fn new() -> Self {
            let fixture = super::support::RuntimeFixture::new().expect("fixture");
            let runtime = std::sync::Arc::new(fixture.runtime);
            let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::ready(runtime));
            let (entered_tx, entered_rx) = std::sync::mpsc::channel();
            let (release_tx, release_rx) = std::sync::mpsc::channel();
            let release_rx = std::sync::Mutex::new(release_rx);
            let captured = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
            let runner: lomo_tui::executor::Runner = {
                let captured = std::sync::Arc::clone(&captured);
                std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                    if matches!(effect, Effect::Refresh { .. }) {
                        entered_tx.send(()).expect("entered");
                        release_rx.lock().expect("gate").recv().expect("release");
                    }
                    if let Effect::Reconcile { observed, .. } = &effect {
                        captured.lock().expect("capture").push(observed.clone());
                    }
                    Ok(RuntimeMessage::Changed {
                        req: effect.req(),
                        status: "done".to_owned(),
                    })
                })
            };
            let scheduler =
                lomo_tui::executor::Scheduler::spawn_with(&slot, &runner).expect("scheduler");
            Self {
                scheduler,
                pending: lomo_tui::model::Pending::default(),
                captured,
                entered_rx,
                release_tx,
            }
        }

        fn submit(&mut self, serial: u64, effect: Effect) -> lomo_tui::executor::Submit {
            self.pending.register(Req(serial), PendingKind::Maintenance);
            self.scheduler.submit(&mut self.pending, effect)
        }

        /// Park the maintenance lane inside the runner so subsequent work
        /// stays queued until `release`.
        fn latch(&mut self, serial: u64) {
            assert!(matches!(
                self.submit(serial, Effect::Refresh { req: Req(serial) }),
                lomo_tui::executor::Submit::Queued
            ));
            self.entered_rx
                .recv_timeout(std::time::Duration::from_secs(5))
                .expect("the latch is inside the executor");
        }

        fn release(&self) {
            self.release_tx.send(()).expect("release the latch");
        }

        /// Drain receipts until `target` answers — the latch's own `Changed`
        /// lands first, so counting arrivals is the ordering-safe probe.
        fn expect_receipt(&self, target: Req, window: usize) {
            let mut seen = false;
            for _ in 0..window {
                let reply = self
                    .scheduler
                    .replies()
                    .recv_timeout(std::time::Duration::from_secs(5))
                    .expect("the drained lane answers every job");
                let RuntimeMessage::Changed { req, .. } = reply else {
                    panic!("unexpected reply: {reply:?}");
                };
                seen |= req == target;
            }
            assert!(seen, "request {target:?} never answered");
        }

        fn runs(&self) -> Vec<Option<Vec<String>>> {
            self.captured
                .lock()
                .expect("captured")
                .iter()
                .map(|run| {
                    run.as_ref().map(|paths| {
                        paths
                            .iter()
                            .map(|path| path.as_str().to_owned())
                            .collect::<Vec<_>>()
                    })
                })
                .collect()
        }
    }

    /// `Some ∪ Some`: both watcher-attested path sets survive the queue merge —
    /// the absorbed request's intent is cancelled and one union reconcile
    /// answers both.
    #[test]
    fn queued_reconciles_union_their_attested_coverage() {
        let mut probe = ReconcileProbe::new();
        let path =
            |raw: &str| lomo_core::RelativeWorkspacePath::parse(raw).expect("canonical path");
        probe.latch(1);
        assert!(matches!(
            probe.submit(
                2,
                Effect::Reconcile {
                    req: Req(2),
                    observed: Some(vec![path("notes/b.md")])
                }
            ),
            lomo_tui::executor::Submit::Queued
        ));
        assert!(matches!(
            probe.submit(
                3,
                Effect::Reconcile {
                    req: Req(3),
                    observed: Some(vec![path("notes/a.md")])
                }
            ),
            lomo_tui::executor::Submit::Queued
        ));
        assert!(
            !probe.pending.contains(Req(3)),
            "the absorbed reconcile cancels its intent; its coverage lives on"
        );
        probe.release();
        probe.expect_receipt(Req(2), 2);
        assert_eq!(
            probe.runs(),
            vec![Some(vec!["notes/a.md".to_owned(), "notes/b.md".to_owned()])],
            "the union keeps both attested sets, deduplicated and sorted"
        );
        probe
            .scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// `Some ∪ None`: an unattested watcher batch forfeits coverage — the
    /// merged reconcile runs as a full scan rather than trusting a partial
    /// path set.
    #[test]
    fn an_unattested_batch_degrades_the_merged_reconcile_to_a_full_scan() {
        let mut probe = ReconcileProbe::new();
        let path =
            |raw: &str| lomo_core::RelativeWorkspacePath::parse(raw).expect("canonical path");
        probe.latch(1);
        probe.submit(
            2,
            Effect::Reconcile {
                req: Req(2),
                observed: Some(vec![path("notes/c.md")]),
            },
        );
        probe.submit(
            3,
            Effect::Reconcile {
                req: Req(3),
                observed: None,
            },
        );
        probe.release();
        probe.expect_receipt(Req(2), 2);
        assert_eq!(
            probe.runs(),
            vec![None],
            "an unattested batch degrades the merge to a full reconcile"
        );
        probe
            .scheduler
            .finish(std::time::Duration::from_secs(2))
            .expect("bounded shutdown");
    }
}
