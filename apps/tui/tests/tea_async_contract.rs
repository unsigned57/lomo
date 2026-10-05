// adversarial-reaudit: independent re-verification of the TEA async fixes.
//
// # Behavior Contract
//
// Capability: proves (or refutes) that the request/reply correlation (I1),
// bounded execution (I3) and bootstrap (I5) repairs recorded in
// `audit/08-TUI对抗性审计修复记录.md` actually hold under adversarial
// interleavings — mid-commit discards, receipt races, lane saturation,
// worker death and the deferred `RuntimeReady` install.
//
// Owning layer: model (`messages.rs`/`update.rs`), executor (`executor.rs`),
// host seams (`CloseGate`, `dispatch`'s refusal path) — evidence priority:
// model-level state probes first, lane-level controlled-order probes second.
//
// Given/When/Then (probe catalogue):
// - F-01 lifecycle: commit failure unlocks the composer; Esc mid-submit keeps
//   the marker so the receipt still lands; discard+recommit lands only the new
//   Saved; a Saved claimed under a non-commit intent is inert; a wrong-revision
//   Saved never lands; persisted_revision is monotone.
// - F-02/F-12/F-03: two feeds of the SAME kind correlate pages by their own
//   `pending_page` req; a dead `View::Loading` restored from history
//   re-navigates under a fresh request.
// - I1 degrade contract: every `PendingKind` failure leaves a status or badge
//   trace; foreign receipts never mutate state.
// - A-14 parking: parked History replies deliver FIFO once input returns to
//   Browse across different busy-input transitions — a command that produces
//   an effect still drains them.
// - A-10 preset dates apply without the dialog; A-09 `focus_reconcile` never
//   reaches `apply_command` and never emits effects.
// - I3 lanes: Mutate is FIFO; a cancelled queued job never runs; a panicking
//   worker fails every queued job with its own req; saturation refuses;
//   `finish` is bounded against a permanently blocked worker.
// - Durability: `commit_capture` replays idempotently; a Pending record
//   restores as a Failed-save draft; a cancelled EmptyTrash deletes nothing.
//
// TDD proof: this file is evidence-first, not change-driven — tests asserting
// the CORRECT invariant that still FAIL are genuine residual defects and are
// kept RED deliberately; each is mapped to `audit/09-复审-TEA异步完整性.md`.
//
// Exclusions: real stdin/terminal probe, real watcher FS events, host-private
// `deliver`/`tick` internals (asserted through public seams only), and Kotlin.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, feed, feed_mut, memo, model_with_memos, ready_graphics};
    use lomo_tui::{
        effects::{Effect, FeedRequest, PageIntent, RuntimeMessage},
        event::Command,
        executor::{Refusal, Runner, Scheduler, Submit},
        messages::apply_message,
        model::{
            BadgeClass, CancelToken, Confirmation, FeedKind, FeedQuery, FeedState, InputMode,
            LoadStatus, Notice, ParkedReply, Pending, PendingKind, Picker, PickerKind, Req,
            RevisionRow, SaveState, Screen, Severity, TextAnchor, View,
        },
        update::{apply_command, focus_reconcile},
    };
    use std::{
        sync::{
            Arc, Mutex,
            atomic::{AtomicBool, Ordering},
            mpsc,
        },
        time::{Duration, Instant},
    };

    fn id(raw: &str) -> lomo_workspace::MemoId {
        lomo_workspace::MemoId::parse(raw).expect("fixture id")
    }

    fn revisions() -> Vec<RevisionRow> {
        vec![RevisionRow {
            revision: 1,
            stamp: "2026-09-11 12:00:00".to_owned(),
            preview: "body".to_owned(),
        }]
    }

    fn page(req: Req, cards: Vec<lomo_tui::model::MemoCard>) -> RuntimeMessage {
        // A non-append reply carries the live-order id sequence over its own
        // cards — the fabricated page claims the whole loaded window.
        let order = cards.iter().map(|card| card.id.clone()).collect();
        RuntimeMessage::Page {
            req,
            append: false,
            cards,
            next: None,
            order,
            total: None,
        }
    }

    /// A composer mid-commit: draft revision 1, `Submitting{req}` live.
    fn submitting_model() -> (lomo_tui::model::AppModel, Req) {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        model.draft.text = lomo_tui::input::TextBuffer::new("draft".to_owned());
        model.draft.revision = 1;
        model.input = InputMode::Compose;
        let Some(Effect::CommitDraft { req, revision, .. }) =
            apply_command(&mut model, Command::Commit)
        else {
            panic!("a populated composer must issue CommitDraft");
        };
        assert_eq!(revision, 1);
        (model, req)
    }

    // ---------- F-01 commit lifecycle ----------

    /// Commit → Failed: the mutex retires, the draft text survives, and the
    /// Draft badge records the evidence.
    #[test]
    fn a_failed_commit_unlocks_the_composer_with_evidence() {
        let (mut model, req) = submitting_model();
        // Double Ctrl+S while in flight refuses aloud — no second request.
        assert_eq!(apply_command(&mut model, Command::Commit), None);
        assert!(
            matches!(model.draft.save, SaveState::Submitting { .. }),
            "the first submission is still the mutex owner"
        );
        assert!(model.pending.contains(req));
        assert!(model.status.is_some(), "the refusal names itself");

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "disk full".to_owned(),
                }
            ),
            None
        );
        assert!(
            matches!(model.draft.save, SaveState::Failed { .. }),
            "the live commit's failure retires the mutex with evidence"
        );
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Draft),
            "a commit failure raises the persistent Draft badge"
        );
        assert_eq!(model.draft.text.text(), "draft", "failure keeps the text");
        assert_eq!(model.draft.revision, 1, "failure never moves the revision");
    }

    /// A failed commit retries under a NEW req for the SAME revision, the
    /// retried Saved lands, and the success retires the failure's badge.
    #[test]
    fn a_commit_retry_after_failure_lands_and_retires_the_badge() {
        let (mut model, req) = submitting_model();
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "disk full".to_owned(),
                }
            ),
            None
        );
        let Some(Effect::CommitDraft {
            req: retry,
            revision: 1,
            content,
        }) = apply_command(&mut model, Command::Commit)
        else {
            panic!("a failed commit must be committable again");
        };
        assert_ne!(retry, req);
        assert_eq!(content, "draft");
        let saved = apply_message(
            &mut model,
            RuntimeMessage::Saved {
                req: retry,
                revision: 1,
                id: id("memo-77"),
            },
        );
        assert_eq!(model.last_created, Some(id("memo-77")));
        assert_eq!(model.draft.save, SaveState::Editing);
        assert_eq!(model.draft.revision, 2);
        assert_eq!(model.draft.persisted_revision, 2);
        assert!(
            matches!(saved, Some(Effect::Query(_))),
            "a landed commit refreshes the feed under a new request"
        );
        // The badge the failure raised retires on the same-class success.
        assert!(
            !model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Draft)
        );
    }

    /// Esc while a commit is in flight leaves the composer but keeps the
    /// mutex: no redundant persist is issued and the Saved still lands.
    #[test]
    fn esc_during_submit_keeps_the_marker_and_the_receipt_still_lands() {
        let (mut model, req) = submitting_model();
        assert_eq!(
            apply_command(&mut model, Command::Back),
            None,
            "the in-flight commit owns the write — no extra persist"
        );
        assert_eq!(model.input, InputMode::Browse);
        assert!(
            matches!(model.draft.save, SaveState::Submitting { req: live, .. } if live == req),
            "the submission marker survives leaving the composer"
        );
        assert!(
            matches!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Saved {
                        req,
                        revision: 1,
                        id: id("memo-9")
                    }
                ),
                Some(Effect::Query(_))
            ),
            "the receipt lands from outside the composer too"
        );
        assert_eq!(model.last_created, Some(id("memo-9")));
        assert_eq!(model.draft.revision, 2);
        assert_eq!(model.draft.save, SaveState::Editing);
    }

    /// Discard while a commit is in flight, then commit a fresh draft: the
    /// first commit's late Saved degrades; only the second commit lands.
    #[test]
    fn discard_then_recommit_lands_only_the_new_saved() {
        let (mut model, first) = submitting_model();
        drop(apply_command(&mut model, Command::Back));
        drop(apply_command(&mut model, Command::DiscardDraft));
        assert!(matches!(
            apply_command(&mut model, Command::Accept),
            Some(Effect::PersistDraft { revision: 2, .. })
        ));
        assert!(!model.pending.contains(first), "discard revokes the intent");
        assert_eq!(model.draft.text.text(), "");
        assert_eq!(model.draft.save, SaveState::Editing);

        drop(apply_command(&mut model, Command::Compose));
        drop(apply_command(&mut model, Command::Type("fresh".to_owned())));
        let Some(Effect::CommitDraft {
            req: second,
            revision: 3,
            ..
        }) = apply_command(&mut model, Command::Commit)
        else {
            panic!("the new draft commits under its own revision");
        };
        assert!(
            matches!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Saved {
                        req: second,
                        revision: 3,
                        id: id("memo-3")
                    }
                ),
                Some(Effect::Query(_))
            ),
            "the live commit lands"
        );
        assert_eq!(model.last_created, Some(id("memo-3")));

        // The abandoned first commit's receipt can never reach `saved()`:
        // its intent was cancelled at discard time.
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req: first,
                    revision: 1,
                    id: id("memo-8")
                }
            ),
            None
        );
        assert_eq!(model.last_created, Some(id("memo-3")));
        // Its failure degrades loudly rather than hiding in the registry.
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: first,
                    diagnostic: "stale commit failed".to_owned(),
                }
            ),
            None
        );
        assert_eq!(model.status.as_deref(), Some("stale commit failed"));
    }

    /// A `Saved` receipt that claims a non-commit intent is fully inert —
    /// the wrong-shape landing is refused and the composer learns nothing.
    #[test]
    fn a_saved_receipt_under_the_wrong_intent_is_inert() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let req = model.request(PendingKind::DraftPersist { revision: 0 });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req,
                    revision: 0,
                    id: id("memo-5")
                }
            ),
            None
        );
        assert_eq!(model.last_created, None);
        assert_eq!(
            model.status, None,
            "a shape-mismatched receipt settles quietly"
        );
        assert_eq!(model.draft.persisted_revision, 0);
        assert!(!model.pending.contains(req));
    }

    /// A `Saved` naming a revision different from the intent's is inert even
    /// while the live marker is armed — receipt shape AND revision must agree.
    #[test]
    fn a_commit_receipt_with_a_wrong_revision_never_lands() {
        let (mut model, req) = submitting_model();
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Saved {
                    req,
                    revision: 7,
                    id: id("memo-7")
                }
            ),
            None
        );
        assert_eq!(model.last_created, None);
        assert!(
            matches!(model.draft.save, SaveState::Submitting { .. }),
            "the commit is still in flight — a mismatched receipt must not resolve it"
        );
        assert_eq!(model.draft.text.text(), "draft");
    }

    /// `persisted_revision` only ever advances: a stale `DraftStored` after a
    /// landed commit cannot move the watermark backwards.
    #[test]
    fn a_stale_persist_receipt_never_regresses_persistence() {
        let (mut model, req) = submitting_model();
        drop(apply_message(
            &mut model,
            RuntimeMessage::Saved {
                req,
                revision: 1,
                id: id("memo-1"),
            },
        ));
        assert_eq!(model.draft.persisted_revision, 2);
        let stale = model.request(PendingKind::DraftPersist { revision: 1 });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::DraftStored {
                    req: stale,
                    revision: 1
                }
            ),
            None
        );
        assert_eq!(
            model.draft.persisted_revision, 2,
            "an older store receipt cannot regress the persisted watermark"
        );
    }

    // ---------- F-02/F-12/F-03 feed correlation ----------

    /// Two feeds of the SAME kind with independent pending pages: each reply
    /// lands on the feed that asked — never on "the current one" (the epoch
    /// collision the `pending_page` slot fix removed).
    #[test]
    fn two_same_kind_feeds_correlate_pages_by_their_own_request() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        // Feed A (the timeline the first page loaded) goes into history with
        // its own outstanding page request.
        let req_a = model.request(PendingKind::FeedPage);
        {
            let feed = feed_mut(&mut model).expect("feed A");
            feed.pending_page = Some(req_a);
            feed.load = LoadStatus::Loading;
        }
        // A second timeline feed becomes current — same kind, new request.
        model.push_view(View::Feed(Box::new(FeedState::new(FeedKind::Timeline))));
        let req_b = model.request(PendingKind::FeedPage);
        {
            let feed = feed_mut(&mut model).expect("feed B");
            feed.pending_page = Some(req_b);
            feed.load = LoadStatus::Loading;
        }
        assert_ne!(req_a, req_b);

        // A's reply lands on the BURIED feed — the current one is untouched.
        assert_eq!(
            apply_message(
                &mut model,
                page(req_a, vec![memo("memo-a", "buried").expect("card")]),
            ),
            None
        );
        let current = feed(&model).expect("current feed");
        assert!(current.memos.is_empty(), "feed B did not ask for this page");
        assert_eq!(current.pending_page, Some(req_b));
        assert_eq!(current.load, LoadStatus::Loading);
        let Some(View::Feed(buried)) = model.history.first() else {
            panic!("feed A is retained in history");
        };
        assert_eq!(buried.memos.len(), 1);
        assert_eq!(
            buried.memos.first().map(|card| card.id.clone()),
            Some(id("memo-a"))
        );
        assert_eq!(buried.load, LoadStatus::Ready);
        assert_eq!(buried.pending_page, None);

        // A failure aimed at the buried feed names its slot too.
        let req_a2 = model.request(PendingKind::FeedPage);
        if let Some(View::Feed(buried)) = model.history.first_mut() {
            buried.pending_page = Some(req_a2);
            buried.load = LoadStatus::Loading;
        }
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: req_a2,
                    diagnostic: "disk gone".to_owned(),
                }
            ),
            None
        );
        let current = feed(&model).expect("current feed");
        assert_eq!(
            current.load,
            LoadStatus::Loading,
            "B's request is still live"
        );
        let Some(View::Feed(buried)) = model.history.first() else {
            panic!("feed A retained");
        };
        assert!(matches!(buried.load, LoadStatus::Failed(_)));
        assert_eq!(buried.pending_page, None);

        // And B's own reply lands on B.
        assert_eq!(
            apply_message(
                &mut model,
                page(req_b, vec![memo("memo-b", "live").expect("card")]),
            ),
            None
        );
        let current = feed(&model).expect("current feed");
        assert_eq!(current.memos.len(), 1);
        assert_eq!(
            current.memos.first().map(|card| card.id.clone()),
            Some(id("memo-b"))
        );
        assert_eq!(current.load, LoadStatus::Ready);
    }

    /// Restoring a `View::Loading` placeholder whose request died while the
    /// view was buried re-navigates under a fresh identity — the dead load is
    /// reissued, never rewritten to Ready and never left awaiting a ghost.
    #[test]
    fn a_restored_loading_view_with_a_dead_request_renavigates() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let Some(Effect::Navigate {
            req: dead,
            screen: Screen::Statistics,
        }) = apply_command(&mut model, Command::Goto(Screen::Statistics))
        else {
            panic!("Goto issues the placeholder request");
        };
        assert!(matches!(model.view, View::Loading { req, .. } if req == dead));
        // An overlay pushed over the placeholder buries it in history —
        // then its request dies (claimed/cancelled while buried).
        model.push_view(View::Reader {
            memo: memo("memo-9", "buried-reader").expect("card"),
            anchor: TextAnchor::default(),
        });
        assert!(model.pending.cancel(dead).is_some());

        let Some(Effect::Navigate { req, screen }) = apply_command(&mut model, Command::Back)
        else {
            panic!("a dead placeholder re-issues its navigation");
        };
        assert_eq!(screen, Screen::Statistics);
        assert_ne!(req, dead);
        assert!(matches!(
            model.view,
            View::Loading {
                req: live,
                screen: Screen::Statistics,
            } if live == req
        ));
        assert!(
            model.pending.contains(req),
            "the new placeholder is owned by a live request"
        );
        assert!(!model.pending.contains(dead));
    }

    // ---------- I1 degrade contract ----------

    /// Every pending intent's failure lands visibly: status toast or a
    /// persistent badge. This is the I9 safety net — table-driven so a new
    /// intent cannot silently join the quiet list.
    #[test]
    fn every_pending_intent_failure_leaves_a_trace() {
        let intents = [
            PendingKind::FeedPage,
            PendingKind::Navigate,
            PendingKind::RefreshView {
                screen: Screen::Statistics,
            },
            PendingKind::OpenMemo,
            PendingKind::RefreshReader { id: id("memo-0") },
            PendingKind::Bodies,
            PendingKind::DraftPersist { revision: 0 },
            PendingKind::DraftCommit { revision: 0 },
            PendingKind::History { id: id("memo-1") },
            PendingKind::Tags,
            PendingKind::Date { dialog: true },
            PendingKind::Date { dialog: false },
            PendingKind::Mutation,
            PendingKind::Maintenance,
            PendingKind::ConfigReload,
            PendingKind::Attachment,
            PendingKind::Image,
            PendingKind::Bootstrap,
            PendingKind::Quit,
        ];
        for intent in intents {
            let mut model = model_with_memos(1, 80, 24).expect("fixture");
            let req = model.request(intent.clone());
            assert_eq!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Failed {
                        req,
                        diagnostic: "probe failure".to_owned(),
                    }
                ),
                None,
                "{intent:?} must not spawn follow-up effects on failure"
            );
            assert!(
                model.status.is_some() || !model.badges.is_empty(),
                "{intent:?} failure left no visible trace"
            );
            assert!(
                !model.pending.contains(req),
                "{intent:?} must consume its request either way"
            );
        }
    }

    /// Receipts for never-registered requests mutate nothing — foreign data
    /// replies are quiet, foreign failures still surface on the status line.
    #[test]
    fn a_receipt_for_a_request_that_never_existed_is_inert() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let foreign = Req(9_999);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::View {
                    req: foreign,
                    view: Box::new(View::Reader {
                        memo: memo("memo-1", "x").expect("card"),
                        anchor: TextAnchor::default(),
                    }),
                }
            ),
            None
        );
        assert!(
            matches!(model.view, View::Feed(_)),
            "no foreign view installs"
        );
        assert_eq!(model.status, None);
        assert_eq!(
            apply_message(
                &mut model,
                page(foreign, vec![memo("memo-9", "y").expect("card")]),
            ),
            None
        );
        assert_eq!(feed(&model).expect("feed").memos.len(), 1);
        assert_eq!(model.status, None);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: foreign,
                    diagnostic: "orphan failure".to_owned(),
                }
            ),
            None
        );
        assert_eq!(
            model.status.as_deref(),
            Some("orphan failure"),
            "a foreign failure still reports — degrade is loud for errors"
        );
    }

    // ---------- A-14 parked replies ----------

    /// Two History replies parking under busy inputs deliver FIFO — one per
    /// return to `Browse`, across different busy-input kinds. None are
    /// dropped; ordering is preserved.
    #[test]
    fn parked_history_replies_deliver_fifo_across_busy_inputs() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        let first = model.request(PendingKind::History { id: id("memo-0") });
        let second = model.request(PendingKind::History { id: id("memo-1") });
        model.input = InputMode::Confirm(Confirmation::EmptyTrash { count: Some(3) });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req: first,
                    id: id("memo-0"),
                    revisions: revisions(),
                }
            ),
            None
        );
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req: second,
                    id: id("memo-1"),
                    revisions: revisions(),
                }
            ),
            None
        );
        assert_eq!(model.parked.len(), 2);
        assert!(
            model.status.is_some(),
            "parking reports itself on the status line"
        );
        assert!(matches!(model.input, InputMode::Confirm(_)));

        // Leaving the confirm dialog delivers the OLDEST parked reply.
        assert_eq!(apply_command(&mut model, Command::Back), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("the first parked reply opens the history picker");
        };
        assert!(
            matches!(&picker.kind, PickerKind::History { id: mid, .. } if mid.as_str() == "memo-0"),
            "parking is FIFO — the first reply opens first"
        );

        // Dismissing the picker returns to Browse → the second one delivers.
        assert_eq!(apply_command(&mut model, Command::DismissPicker), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("the second parked reply delivers on the next Browse return");
        };
        assert!(matches!(
            &picker.kind,
            PickerKind::History { id: mid, .. } if mid.as_str() == "memo-1"
        ));
        assert!(model.parked.is_empty());
    }

    /// A command that produces an effect still drains the park queue:
    /// accepting a confirmation both issues its mutation AND delivers the
    /// parked picker in the same step — nothing is stranded behind effects.
    #[test]
    fn a_parked_reply_delivers_through_an_effect_producing_command() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let req = model.request(PendingKind::History { id: id("memo-0") });
        model.input = InputMode::Confirm(Confirmation::EmptyTrash { count: Some(2) });
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req,
                    id: id("memo-0"),
                    revisions: revisions(),
                }
            ),
            None
        );
        assert_eq!(model.parked.len(), 1);
        // Accept issues the mutation; the same `apply_command` drains the
        // parked reply once the input settles at Browse.
        let effect = apply_command(&mut model, Command::Accept);
        assert!(
            matches!(effect, Some(Effect::EmptyTrash { .. })),
            "the accepted confirm issues its mutation"
        );
        assert!(
            matches!(
                &model.input,
                InputMode::Picker(Picker {
                    kind: PickerKind::History { .. },
                    ..
                })
            ),
            "the parked reply delivered through the effect-producing command"
        );
        assert!(model.parked.is_empty());
    }

    // ---------- A-10 preset date / A-09 focus ----------

    /// A preset date resolves invisibly: no dialog ever opens and the filter
    /// lands on the timeline once the reply arrives (A-10).
    #[test]
    fn a_preset_date_applies_the_filter_without_opening_the_dialog() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let Some(Effect::Date { req, text }) =
            apply_command(&mut model, Command::SetDate("today".to_owned()))
        else {
            panic!("a preset date issues the resolve request directly");
        };
        assert_eq!(text, "today");
        assert_eq!(
            model.input,
            InputMode::Browse,
            "the preset path never flashes the custom-date dialog"
        );
        assert!(model.pending.contains(req));
        assert!(
            matches!(
                apply_message(
                    &mut model,
                    RuntimeMessage::Date {
                        req,
                        from: 10,
                        until: 20,
                        label: "today".to_owned(),
                    }
                ),
                Some(Effect::Query(_)),
            ),
            "the resolved filter re-queries the feed"
        );
        assert_eq!(model.input, InputMode::Browse);
        let feed = feed(&model).expect("feed");
        assert_eq!(feed.query.filters.date_from_inclusive_ms, Some(10));
        assert_eq!(feed.query.filters.date_until_exclusive_ms, Some(20));
        assert_eq!(feed.query.date_label.as_deref(), Some("today"));
    }

    /// Focus regain is a system signal: the model exposes `focus_reconcile`
    /// directly (no `Command` variant exists — the bypass is compile-time).
    /// It never emits effects, never touches input, and only speaks when the
    /// watcher is dead and the status slot is free.
    #[test]
    fn focus_reconcile_is_a_pure_observation_hint() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.watcher_active = true;
        model.input = InputMode::Confirm(Confirmation::EmptyTrash { count: Some(2) });
        model.set_status("keep me");
        assert_eq!(focus_reconcile(&mut model), None);
        assert!(matches!(model.input, InputMode::Confirm(_)));
        assert_eq!(model.status.as_deref(), Some("keep me"));

        model.watcher_active = false;
        model.status = None;
        assert_eq!(
            focus_reconcile(&mut model),
            None,
            "a system hint never issues effects"
        );
        assert!(
            model.status.is_some(),
            "a dead watcher earns the fallback hint on focus"
        );
        let hint = model.status.clone();
        assert_eq!(focus_reconcile(&mut model), None);
        assert_eq!(model.status, hint, "the hint is not re-armed over itself");
    }

    /// Two Enters on the date dialog: the second resolution owns the dialog;
    /// the first's reply degrades without applying and releases its intent.
    #[test]
    fn a_second_date_accept_supersedes_the_first_resolution() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        drop(apply_command(&mut model, Command::CustomDate));
        drop(apply_command(&mut model, Command::Type("sep".to_owned())));
        let Some(Effect::Date { req: first, .. }) = apply_command(&mut model, Command::Accept)
        else {
            panic!("the dialog issues its resolution");
        };
        let Some(Effect::Date { req: second, .. }) = apply_command(&mut model, Command::Accept)
        else {
            panic!("a second Enter issues a fresh resolution");
        };
        assert_ne!(first, second);

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Date {
                    req: first,
                    from: 1,
                    until: 2,
                    label: "stale".to_owned(),
                }
            ),
            None
        );
        assert!(
            matches!(&model.input, InputMode::Date { req: Some(live), .. } if *live == second),
            "the superseded reply cannot apply over the live request's dialog"
        );
        assert!(
            feed(&model)
                .expect("feed")
                .query
                .filters
                .date_from_inclusive_ms
                .is_none()
        );
        assert!(!model.pending.contains(first), "the stale intent released");

        assert!(
            apply_message(
                &mut model,
                RuntimeMessage::Date {
                    req: second,
                    from: 10,
                    until: 20,
                    label: "sep".to_owned(),
                }
            )
            .is_some()
        );
        assert_eq!(model.input, InputMode::Browse);
        assert_eq!(
            feed(&model)
                .expect("feed")
                .query
                .filters
                .date_from_inclusive_ms,
            Some(10)
        );
    }

    // ---------- I3 executor lanes ----------

    /// A lane harness: the `Req(1)` job parks the lane inside the runner
    /// until `release`, deterministically ordering queued-work probes.
    /// `panic_on_release` turns the latch job into a worker-killing panic.
    struct LaneProbe {
        scheduler: Scheduler,
        pending: Pending,
        ran: Arc<Mutex<Vec<Req>>>,
        entered_rx: mpsc::Receiver<()>,
        release_tx: mpsc::SyncSender<()>,
        panic_on_release: Arc<AtomicBool>,
    }

    impl LaneProbe {
        fn new() -> Self {
            let fixture = RuntimeFixture::new().expect("fixture");
            let slot = Arc::new(lomo_tui::ops::RuntimeSlot::ready(Arc::new(fixture.runtime)));
            let (entered_tx, entered_rx) = mpsc::channel();
            let (release_tx, release_rx) = mpsc::sync_channel(0);
            let release_rx = Mutex::new(release_rx);
            let ran = Arc::new(Mutex::new(Vec::new()));
            let panic_on_release = Arc::new(AtomicBool::new(false));
            let runner: Runner = {
                let ran = Arc::clone(&ran);
                let panic_on_release = Arc::clone(&panic_on_release);
                Arc::new(move |_runtime, effect, _outbox, _token| {
                    let req = effect.req();
                    if req == Req(1) {
                        entered_tx.send(()).expect("entered");
                        release_rx.lock().expect("gate").recv().expect("release");
                        assert!(
                            !panic_on_release.load(Ordering::Acquire),
                            "adversarial worker panic"
                        );
                    }
                    ran.lock().expect("ran").push(req);
                    Ok(RuntimeMessage::Changed {
                        req,
                        status: format!("ran {req:?}"),
                    })
                })
            };
            let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
            Self {
                scheduler,
                pending: Pending::default(),
                ran,
                entered_rx,
                release_tx,
                panic_on_release,
            }
        }

        fn submit_as(&mut self, kind: PendingKind, effect: Effect) -> Submit {
            self.pending.register(effect.req(), kind);
            self.scheduler.submit(&mut self.pending, effect)
        }

        fn submit(&mut self, effect: Effect) -> Submit {
            self.submit_as(PendingKind::Mutation, effect)
        }

        fn latch(&mut self, effect: Effect) {
            assert!(
                matches!(
                    self.submit_as(PendingKind::Mutation, effect),
                    Submit::Queued
                ),
                "the latch job must be admitted to park the lane"
            );
            self.entered_rx
                .recv_timeout(Duration::from_secs(5))
                .expect("the latch is inside the executor");
        }

        fn release(&self) {
            self.release_tx.send(()).expect("release the latch");
        }

        fn reply(&self) -> RuntimeMessage {
            self.scheduler
                .replies()
                .recv_timeout(Duration::from_secs(5))
                .expect("a reply must arrive")
        }
    }

    fn pin(serial: u64) -> Effect {
        Effect::Pin {
            req: Req(serial),
            id: id("memo-0"),
            pinned: true,
        }
    }

    /// Mutate is FIFO: submissions run and answer in issue order — the lane
    /// never reorders user mutations.
    #[test]
    fn the_mutate_lane_executes_submissions_in_fifo_order() {
        let mut probe = LaneProbe::new();
        probe.latch(pin(1));
        for serial in 2..=4 {
            assert!(matches!(probe.submit(pin(serial)), Submit::Queued));
        }
        probe.release();
        let mut replies = Vec::new();
        for _ in 0..4 {
            let RuntimeMessage::Changed { req, .. } = probe.reply() else {
                panic!("every job answers Changed");
            };
            replies.push(req);
        }
        assert_eq!(replies, vec![Req(1), Req(2), Req(3), Req(4)]);
        assert_eq!(
            probe.ran.lock().expect("ran").as_slice(),
            &[Req(1), Req(2), Req(3), Req(4)],
            "execution order equals submission order"
        );
        probe
            .scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A request cancelled while its job waits in the queue never executes —
    /// the worker drops it at pop time before any user code runs (I3).
    #[test]
    fn a_queued_job_revoked_before_its_pop_never_executes() {
        let mut probe = LaneProbe::new();
        probe.latch(pin(1));
        assert!(matches!(probe.submit(pin(2)), Submit::Queued));
        assert!(probe.pending.cancel(Req(2)).is_some());
        probe.release();

        let RuntimeMessage::Changed { req, .. } = probe.reply() else {
            panic!("the latch job answers");
        };
        assert_eq!(req, Req(1));
        assert!(
            probe
                .scheduler
                .replies()
                .recv_timeout(Duration::from_millis(400))
                .is_err(),
            "a revoked queued job must produce no receipt"
        );
        assert_eq!(
            probe.ran.lock().expect("ran").as_slice(),
            &[Req(1)],
            "the revoked job never reached the runner"
        );
        probe
            .scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// The complement: a request cancelled between issue and dispatch — the
    /// deferred-search window (host.rs `state.query`) — must still refuse to
    /// run. `Pending::cancel` removes the token, so `submit` currently mints a
    /// live one and the stale job EXECUTES; the documented contract is
    /// `Submit::Dropped` (executor.rs:115-116). RED evidence for 09-F-03.
    #[test]
    fn a_request_revoked_before_dispatch_is_dropped_not_run() {
        let mut probe = LaneProbe::new();
        probe.pending.register(Req(9), PendingKind::Mutation);
        assert!(probe.pending.cancel(Req(9)).is_some());
        assert!(
            matches!(
                probe.scheduler.submit(&mut probe.pending, pin(9)),
                Submit::Dropped
            ),
            "a revoked request must be dropped at admission, never queued live"
        );
        assert!(
            probe
                .scheduler
                .replies()
                .recv_timeout(Duration::from_millis(200))
                .is_err(),
            "a revoked request must produce no work and no receipt"
        );
        probe
            .scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A worker panic after registration but before queued work ran: the
    /// panicking job answers `Failed`, the lane reports `WorkerDied`, and
    /// EVERY still-queued job is drained with its own `Failed` — no pending
    /// intent is abandoned in a dead queue (F-06).
    #[test]
    fn a_panicking_lane_fails_every_queued_job_with_its_own_req() {
        let mut probe = LaneProbe::new();
        probe.latch(pin(1));
        for serial in 2..=3 {
            assert!(matches!(probe.submit(pin(serial)), Submit::Queued));
        }
        probe.panic_on_release.store(true, Ordering::Release);
        probe.release();

        // Replies: the panicking job's Failed + WorkerDied + one Failed per
        // still-queued job (mark_dead drains the whole queue with receipts).
        let replies: Vec<RuntimeMessage> = (0..4).map(|_| probe.reply()).collect();
        assert!(
            replies.iter().all(|reply| matches!(
                reply,
                RuntimeMessage::Failed { .. } | RuntimeMessage::WorkerDied { .. }
            )),
            "a dead lane emits only failure evidence: {replies:?}"
        );
        let panicked = replies.iter().any(|reply| {
            matches!(
                reply,
                RuntimeMessage::Failed { req, diagnostic }
                    if *req == Req(1) && diagnostic.contains("panicked")
            )
        });
        let died = replies.iter().any(|reply| {
            matches!(
                reply,
                RuntimeMessage::WorkerDied { lane, .. }
                    if *lane == lomo_tui::effects::Lane::Mutate
            )
        });
        let drained: Vec<Req> = replies
            .iter()
            .filter_map(|reply| {
                let RuntimeMessage::Failed { req, diagnostic } = reply else {
                    return None;
                };
                (*req != Req(1)).then(|| {
                    assert!(
                        diagnostic.contains("queued request dropped"),
                        "the drained job's receipt names the lane death: {diagnostic}"
                    );
                    *req
                })
            })
            .collect();
        assert!(panicked, "the panicking job answers with its own req");
        assert!(died, "the lane death is reported as an observation");
        assert_eq!(drained, vec![Req(2), Req(3)], "queued work drains in order");

        // A dead lane refuses new work rather than silently accepting it.
        assert!(matches!(
            probe.submit(pin(4)),
            Submit::Refused(Refusal::Dead)
        ));
        // The dead worker already exited and reported — finish joins its
        // corpse inside the budget; it must not hang or complain again.
        drop(probe.scheduler.finish(Duration::from_secs(2)));
    }

    /// A saturated lane refuses visibly: the 257th admission gets
    /// `Refused(Saturated)` and the pending intent resolves through the
    /// caller-fabricated `Failed` — the request never wedges (I3/F-06).
    #[test]
    fn a_saturated_lane_refuses_and_the_refusal_resolves_visibly() {
        let mut probe = LaneProbe::new();
        probe.latch(pin(1));
        for serial in 2..=257 {
            assert!(
                matches!(probe.submit(pin(serial)), Submit::Queued),
                "admission {serial} fits the bounded queue"
            );
        }
        assert!(matches!(
            probe.submit(pin(258)),
            Submit::Refused(Refusal::Saturated)
        ));
        // The refused request's intent is still registered — the host turns
        // the refusal into a Failed receipt that resolves it.
        assert!(probe.pending.contains(Req(258)));
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.pending.register(Req(258), PendingKind::Mutation);
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req: Req(258),
                    diagnostic: "System busy — the request was refused; try again".to_owned(),
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
        assert!(!model.pending.contains(Req(258)));

        probe.release();
        for _ in 0..257 {
            probe.reply();
        }
        probe
            .scheduler
            .finish(Duration::from_secs(5))
            .expect("bounded shutdown");
    }

    /// A worker that never returns cannot hold the scheduler: `finish` waits
    /// at most its budget, reports the lane, and detaches — the quit path is
    /// never hostage to a dead or blocked worker (F-06/I3).
    #[test]
    fn a_blocked_lane_cannot_hold_shutdown_past_the_budget() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let slot = Arc::new(lomo_tui::ops::RuntimeSlot::ready(Arc::new(fixture.runtime)));
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let release_rx = Mutex::new(release_rx);
        let runner: Runner = Arc::new(move |_runtime, effect, _outbox, _token| {
            entered_tx.send(()).expect("entered");
            release_rx.lock().expect("gate").recv().expect("release");
            Ok(RuntimeMessage::Changed {
                req: effect.req(),
                status: "done".to_owned(),
            })
        });
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Mutation);
        assert!(matches!(
            scheduler.submit(&mut pending, pin(1)),
            Submit::Queued
        ));
        entered_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("the worker is inside the job");

        let budget = Duration::from_millis(150);
        let started = Instant::now();
        let outcome = scheduler.finish(budget);
        let elapsed = started.elapsed();
        let error = outcome.expect_err("a blocked lane must report, not hang");
        assert!(
            error.to_string().contains("did not stop within"),
            "the error names the wedged lane: {error}"
        );
        assert!(
            elapsed >= budget && elapsed < Duration::from_secs(2),
            "the bounded join waited its budget then left: {elapsed:?}"
        );
        drop(release_tx); // let the detached worker's recv fail so it exits
    }

    /// Same-kind feed queries merge newest-wins in the queue: the superseded
    /// request's pending intent is cancelled at admission, so its (never
    /// sent) reply can never race the live one. One reply, one live intent.
    #[test]
    fn same_kind_feed_queries_merge_newest_and_release_the_superseded_intent() {
        let mut probe = LaneProbe::new();
        let query = |req: Req| {
            Effect::Query(FeedRequest {
                req,
                kind: FeedKind::Timeline,
                query: FeedQuery::default(),
                intent: PageIntent::Initial,
            })
        };
        probe.latch(query(Req(1)));
        assert!(matches!(
            probe.submit_as(PendingKind::FeedPage, query(Req(2))),
            Submit::Queued
        ));
        assert!(matches!(
            probe.submit_as(PendingKind::FeedPage, query(Req(3))),
            Submit::Queued
        ));
        assert!(
            !probe.pending.contains(Req(2)),
            "the superseded request's intent is cancelled at admission"
        );
        assert!(probe.pending.contains(Req(3)));
        probe.release();

        let mut answered = Vec::new();
        for _ in 0..2 {
            answered.push(probe.reply());
        }
        assert!(
            answered.iter().all(
                |reply| matches!(reply, RuntimeMessage::Changed { req, .. } if *req != Req(2))
            ),
            "the dropped request never produces a receipt: {answered:?}"
        );
        assert_eq!(
            probe.ran.lock().expect("ran").as_slice(),
            &[Req(1), Req(3)],
            "only the newest same-kind query executed"
        );
        probe
            .scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// `MergeKey::Memo` conflates `ReadMemo` and `History` on the Query lane:
    /// a queued `History{X}` is cancelled outright when `ReadMemo{X}` lands
    /// behind it — an explicit user action vanishes with no receipt and no
    /// status. RED evidence for 09-F-02.
    #[test]
    fn a_queued_history_request_survives_an_unrelated_memo_read() {
        let mut probe = LaneProbe::new();
        probe.latch(Effect::Tags { req: Req(1) });
        assert!(matches!(
            probe.submit_as(
                PendingKind::History { id: id("memo-0") },
                Effect::History {
                    req: Req(2),
                    id: id("memo-0"),
                },
            ),
            Submit::Queued
        ));
        assert!(matches!(
            probe.submit_as(
                PendingKind::OpenMemo,
                Effect::ReadMemo {
                    req: Req(3),
                    id: id("memo-0"),
                },
            ),
            Submit::Queued
        ));
        assert!(
            probe.pending.contains(Req(2)),
            "a queued history request is a different user action — a same-memo read must not swallow it"
        );
        probe.release();
        probe
            .scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    // ---------- RuntimeReady shell wipe (09-F-01) ----------

    /// The deferred install must replace only what it prepared — not the
    /// shell state that outlived bootstrap. `*model = *prepared`
    /// (messages.rs) discards the probed graphics verdict, and the host's
    /// stdin gate holds while `Probing` — a verdict that landed first means
    /// permanent input freeze. RED evidence for 09-F-01.
    #[test]
    fn runtime_ready_keeps_the_probed_graphics_verdict() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        model.graphics = ready_graphics(ratatui_image::picker::ProtocolType::Kitty);
        let boot = model.request(PendingKind::Bootstrap);
        let prepared = model_with_memos(1, 80, 24).expect("prepared");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::RuntimeReady {
                    req: boot,
                    model: Box::new(prepared),
                }
            ),
            None
        );
        assert!(
            !matches!(model.graphics, lomo_tui::graphics::GraphicsVerdict::Probing),
            "the install must not revoke the verdict — the host gates stdin on Probing"
        );
    }

    /// The request serial must be monotone for the session's life; resetting
    /// it lets a stale executor reply claim a fresh intent that reuses the id.
    #[test]
    fn runtime_ready_keeps_request_serial_monotone() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let boot = model.request(PendingKind::Bootstrap);
        let stale = model.request(PendingKind::History { id: id("memo-0") });
        let prepared = model_with_memos(1, 80, 24).expect("prepared");
        drop(apply_message(
            &mut model,
            RuntimeMessage::RuntimeReady {
                req: boot,
                model: Box::new(prepared),
            },
        ));
        let post = model.request(PendingKind::History { id: id("memo-1") });
        let post2 = model.request(PendingKind::Tags);
        assert!(
            post > stale && post2 > stale,
            "Req must never repeat within a session — post-boot {post:?}/{post2:?} collides with pre-boot {stale:?}"
        );
    }

    /// The concrete consequence of a serial reset: a receipt issued BEFORE
    /// bootstrap (a job still parked/running in the executor) collides with a
    /// post-boot intent minted under the same Req and steals it — the user
    /// asked for memo-1's history and is shown memo-0's, and the live
    /// request's own reply then degrades. RED evidence for 09-F-01.
    #[test]
    fn a_pre_bootstrap_reply_cannot_steal_a_post_bootstrap_intent() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let boot = model.request(PendingKind::Bootstrap);
        // A request issued while bootstrap is in flight — e.g. a history
        // probe fired off the Loading shell — carries Req(2).
        let stale = model.request(PendingKind::History { id: id("memo-0") });
        let prepared = model_with_memos(1, 80, 24).expect("prepared");
        drop(apply_message(
            &mut model,
            RuntimeMessage::RuntimeReady {
                req: boot,
                model: Box::new(prepared),
            },
        ));
        // After the wipe the serial restarted (runtime_ready_keeps_request_
        // serial_monotone): the user's second post-boot request collides with
        // the in-flight pre-boot one.
        let _live_1 = model.request(PendingKind::Tags);
        let live = model.request(PendingKind::History { id: id("memo-1") });

        // The stale job's reply arrives. With a monotone serial it claims
        // only its own pre-boot intent (memo-0's picker opens legitimately);
        // under the reset it claims `live` — memo-1's request gets answered
        // by memo-0's history, and memo-1's own reply then degrades.
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::History {
                    req: stale,
                    id: id("memo-0"),
                    revisions: revisions(),
                }
            ),
            None
        );
        assert!(
            model.pending.contains(live),
            "a pre-bootstrap reply must not claim a distinct post-bootstrap intent"
        );
    }

    /// The install must not erase feedback the shell raised while the
    /// bootstrap reply was in flight — `deliver` itself badges a watcher
    /// spawn failure on this same receipt path (host.rs), and `*model =
    /// *prepared` currently erases it along with live intents, parked
    /// replies and compose text.
    #[test]
    fn runtime_ready_keeps_shell_feedback_and_pending_state() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let boot = model.request(PendingKind::Bootstrap);
        model.present(Notice::badge(
            Severity::Warn,
            BadgeClass::Watch,
            "File watching stopped — auto-refreshing".to_owned(),
            vec!["spawn refused".to_owned()],
        ));
        let live = model.request(PendingKind::Tags);
        model.parked.push_back(ParkedReply::History {
            id: id("memo-0"),
            revisions: revisions(),
        });
        let prepared = model_with_memos(1, 80, 24).expect("prepared");
        drop(apply_message(
            &mut model,
            RuntimeMessage::RuntimeReady {
                req: boot,
                model: Box::new(prepared),
            },
        ));
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Watch),
            "the badge raised on this receipt's own host path must survive the install"
        );
        assert!(
            model.pending.contains(live),
            "a live intent must not die with the shell swap"
        );
        assert!(
            !model.parked.is_empty(),
            "a parked reply must not evaporate on install"
        );
    }

    // ---------- RefreshReader identity ----------

    /// A `RefreshReader` reply must land only on the reader it was issued
    /// for. Today `refresh_reader` rebinds whatever `View::Reader` is current:
    /// a reconcile-triggered refresh for memo A replaces an open reader of
    /// memo B. RED evidence for 09-F-04.
    #[test]
    fn a_reader_refresh_reply_lands_only_on_the_reader_it_was_issued_for() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        drop(apply_command(&mut model, Command::Accept)); // reader A: memo-0
        assert!(matches!(
            model.view,
            View::Reader { ref memo, .. } if memo.id == id("memo-0")
        ));
        // A reconcile issues the in-place refresh for the open reader.
        let maintenance = model.request(PendingKind::Maintenance);
        let Some(Effect::ReadMemo { req, id: target }) = apply_message(
            &mut model,
            RuntimeMessage::Reconciled {
                req: maintenance,
                changed: true,
            },
        ) else {
            panic!("a changed reconcile re-reads the open memo");
        };
        assert_eq!(target, id("memo-0"));

        // The user backs out and opens a different memo before the reply lands.
        drop(apply_command(&mut model, Command::Back));
        drop(apply_command(&mut model, Command::Move(1)));
        drop(apply_command(&mut model, Command::Accept));
        assert!(matches!(
            model.view,
            View::Reader { ref memo, .. } if memo.id == id("memo-1")
        ));

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::ReadMemo {
                    req,
                    memo: Box::new(memo("memo-0", "stale body").expect("card")),
                }
            ),
            None
        );
        assert!(
            matches!(
                model.view,
                View::Reader { ref memo, .. } if memo.id == id("memo-1")
            ),
            "a refresh issued for memo-0 must not swap the open reader of memo-1"
        );
    }

    /// The failure side of the same hole: a failed refresh for memo A must
    /// not replace an open reader of memo B with a `Failed` screen.
    #[test]
    fn a_reader_refresh_failure_lands_only_on_the_reader_it_was_issued_for() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture");
        drop(apply_command(&mut model, Command::Accept));
        let maintenance = model.request(PendingKind::Maintenance);
        let Some(Effect::ReadMemo { req, .. }) = apply_message(
            &mut model,
            RuntimeMessage::Reconciled {
                req: maintenance,
                changed: true,
            },
        ) else {
            panic!("the refresh was issued");
        };
        drop(apply_command(&mut model, Command::Back));
        drop(apply_command(&mut model, Command::Move(1)));
        drop(apply_command(&mut model, Command::Accept));

        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "store read exploded".to_owned(),
                }
            ),
            None
        );
        assert!(
            matches!(
                model.view,
                View::Reader { ref memo, .. } if memo.id == id("memo-1")
            ),
            "a refresh failure for another memo must not destroy the open reader"
        );
    }

    // ---------- durable commit evidence ----------

    /// `commit_capture` is idempotent by operation: a replayed submission —
    /// crash between the Pending write and the Saved record — reuses the same
    /// operation id and returns the same memo id instead of duplicating.
    #[test]
    fn capture_commit_replays_the_same_operation_idempotently() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let first = lomo_tui::drafts::commit_capture(&fixture.runtime, 1, "draft body")
            .expect("first commit");
        let replay = lomo_tui::drafts::commit_capture(&fixture.runtime, 1, "draft body")
            .expect("replay returns the recorded receipt");
        assert_eq!(first, replay, "a replayed commit returns the same memo id");
        assert!(
            lomo_tui::drafts::commit_capture(&fixture.runtime, 1, "different body").is_err(),
            "a same-revision commit with different content is a conflict, not a write"
        );
        assert!(
            lomo_tui::drafts::commit_capture(&fixture.runtime, 0, "older").is_err(),
            "an older revision is refused"
        );
        let loaded = lomo_tui::drafts::load_capture(&fixture.runtime).expect("load");
        assert_eq!(
            loaded.composer.revision, 2,
            "a saved record boots the composer past the committed revision"
        );
        assert_eq!(loaded.composer.persisted_revision, 2);
        assert!(loaded.composer.text.text().is_empty());
    }

    /// A crash mid-commit — Pending record on disk, no Saved — restores the
    /// draft text with an explicit Failed save marker, and the identical
    /// retry reuses the recorded operation.
    #[test]
    fn an_interrupted_commit_restores_the_draft_as_failed() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let operation = lomo_tui::ops::mint_operation_id().expect("operation id");
        let path = lomo_tui::drafts::capture_path(&fixture.runtime).expect("path");
        std::fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        std::fs::write(
            &path,
            serde_json::json!({
                "schema": 1,
                "revision": 4,
                "content": "draft body",
                "phase": {"state": "pending", "operation_id": operation.as_str()},
            })
            .to_string(),
        )
        .expect("seed the interrupted record");

        let loaded = lomo_tui::drafts::load_capture(&fixture.runtime).expect("load");
        assert_eq!(loaded.composer.text.text(), "draft body");
        assert_eq!(loaded.composer.revision, 4);
        assert!(
            matches!(loaded.composer.save, SaveState::Failed { .. }),
            "an interrupted commit returns as a failed save, not a silent draft"
        );
        // The identical retry rides the recorded operation — no duplicate memo.
        let committed =
            lomo_tui::drafts::commit_capture(&fixture.runtime, 4, "draft body").expect("retry");
        let replayed =
            lomo_tui::drafts::commit_capture(&fixture.runtime, 4, "draft body").expect("replay");
        assert_eq!(committed, replayed);
    }

    /// A cancelled sweep must not delete: the token gate sits before every
    /// page fetch and every permanent delete (mutations.rs).
    #[test]
    fn a_cancelled_empty_trash_deletes_nothing() {
        let fixture = RuntimeFixture::new().expect("fixture");
        // Seed one live memo, then trash it via the session's own path.
        let created = fixture
            .runtime
            .session
            .create_memo(lomo_application::CreateMemoRequest {
                operation_id: lomo_tui::ops::mint_operation_id().expect("op"),
                relative_path: None,
                time_token: None,
                content: "trash me".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let view = fixture
            .runtime
            .session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("exists");
        fixture
            .runtime
            .session
            .delete_memo(lomo_application::DeleteMemoRequest {
                operation_id: lomo_tui::ops::mint_operation_id().expect("op"),
                memo_id: created.memo_id,
                expected_document_fingerprint: view.file_fingerprint,
                trashed_at_ms: None,
            })
            .expect("trash");

        let token = CancelToken::live();
        token.cancel();
        let (sender, _inbox) = mpsc::sync_channel(8);
        let outcome = lomo_tui::ops::execute(
            &fixture.runtime,
            &Effect::EmptyTrash { req: Req(1) },
            &lomo_tui::executor::Outbox::new(sender),
            &token,
        );
        assert!(
            outcome.is_err(),
            "a cancelled sweep must refuse before its first page"
        );
        let trash = fixture
            .runtime
            .session
            .query_memos_page(
                &lomo_application::MemoQuery {
                    search_text: None,
                    filters: lomo_application::MemoFilters {
                        trash_only: true,
                        ..lomo_application::MemoFilters::default()
                    },
                    sort: lomo_application::MemoSort::default(),
                },
                None,
                None,
                lomo_core::PageSize::new(64).expect("size"),
            )
            .expect("query");
        assert_eq!(
            trash.items.len(),
            1,
            "the trashed memo survived the cancelled sweep"
        );
    }
}
