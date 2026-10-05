//! Behavior Contract
//! Capability: workspace observation drives one reconcile per drained batch; player completion and
//! deferred bootstrap are typed messages; focus regain never forces a workspace listing.
//! Scenarios: drained watcher events coalesce into a single reconcile; unchanged projections do not
//! re-query; watcher outage is an explicit state with a manual F5 fallback; player exits surface
//! failures; bootstrap installs the prepared model.
//! Observable outcomes: effects returned by `apply_message`, `Effect::Reconcile` worker replies,
//! `model.watcher_active`, status text, feed totals surviving append pages.
//! TDD proof: `FocusGained` used to unconditionally rebuild the projection, `query_count` ran on
//! every page, the player blocked the sole worker, and `config.workspace` was silently created.
//! Excludes: a physical terminal, real inotify delivery timing and platform player binaries.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, model_with_memos};
    use lomo_tui::effects::{Effect, RuntimeMessage};
    use lomo_tui::event::Command;
    use lomo_tui::model::{AppModel, BadgeClass, LoadStatus, PendingKind, Screen, View};
    use lomo_tui::update::apply_command;

    #[test]
    fn drained_filesystem_events_request_one_reconcile() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        assert!(
            matches!(
                lomo_tui::messages::apply_message(
                    &mut model,
                    RuntimeMessage::FsChanged { observed: None },
                ),
                Some(Effect::Reconcile { .. })
            ),
            "a drained watcher batch must coalesce into exactly one reconcile"
        );
    }

    #[test]
    fn reconcile_reply_reloads_only_when_the_projection_changed() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture and operation must succeed");
        let req = model.request(PendingKind::Maintenance);
        let unchanged = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::Reconciled {
                req,
                changed: false,
            },
        );
        assert_eq!(
            unchanged, None,
            "an unchanged projection must not re-query loaded pages"
        );
        let req = model.request(PendingKind::Maintenance);
        let changed = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::Reconciled { req, changed: true },
        );
        assert!(
            matches!(changed, Some(Effect::Query(_))),
            "a changed projection must re-query the bounded window near the anchor, got {changed:?}"
        );
    }

    #[test]
    fn watcher_outage_is_explicit_and_focus_keeps_manual_refresh() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert!(
            !model.watcher_active,
            "watcher state starts unknown until the watcher reports ready"
        );
        assert!(
            lomo_tui::messages::apply_message(&mut model, RuntimeMessage::WatcherReady).is_none()
        );
        assert!(model.watcher_active);
        model.status = None;
        assert_eq!(
            lomo_tui::update::focus_reconcile(&mut model),
            None,
            "an active watcher owns external observation; focus stays silent"
        );
        assert_eq!(model.status, None);
        assert!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::WatcherUnavailable {
                    diagnostic: "inotify watch failed".to_owned(),
                },
            )
            .is_none()
        );
        assert!(!model.watcher_active);
        assert!(
            model.status.is_some(),
            "watcher failure must surface as an explicit status"
        );
        model.status = None;
        assert_eq!(
            lomo_tui::update::focus_reconcile(&mut model),
            None,
            "focus regain must never rebuild the workspace projection"
        );
        assert!(
            model.status.is_some(),
            "with the watcher dead, focus must keep the manual-refresh hint visible"
        );
        assert!(
            matches!(
                apply_command(&mut model, Command::Refresh),
                Some(Effect::Refresh { .. })
            ),
            "F5 remains the manual reconcile path when watching fails"
        );
    }

    /// I9: a player failure is a persistent `Player` badge — the toast
    /// carries the diagnostic one line can show, the badge keeps it visible
    /// through unrelated input, and a clean exit is the same-class success
    /// that retires it.
    #[test]
    fn player_completion_is_a_message_and_failures_are_visible() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::PlayerFinished {
                    success: false,
                    diagnostic: Some("player exited 1".to_owned()),
                }
            ),
            None
        );
        assert!(
            model
                .status
                .as_deref()
                .is_some_and(|status| status.contains("player exited 1")),
            "the diagnostic must reach the status line: {:?}",
            model.status
        );
        assert!(
            model.badges.iter().any(|badge| {
                badge.class == BadgeClass::Player && badge.text.contains("player exited 1")
            }),
            "the failure must persist as a Player badge: {:?}",
            model.badges
        );
        assert_eq!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::PlayerFinished {
                    success: true,
                    diagnostic: None,
                }
            ),
            None
        );
        assert!(
            !model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Player),
            "a clean exit retires the Player badge"
        );
    }

    #[test]
    fn bootstrap_failure_lands_in_a_typed_failed_view() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let req = model.request(PendingKind::Bootstrap);
        assert!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "workspace open failed".to_owned(),
                },
            )
            .is_none()
        );
        assert!(
            matches!(&model.view, View::Failed { diagnostic, .. } if diagnostic == "workspace open failed"),
            "bootstrap failures must surface the real diagnostic: {:?}",
            model.view
        );
    }

    #[test]
    fn runtime_ready_installs_the_prepared_model() {
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Bootstrap);
        model.view = View::Loading {
            screen: Screen::Timeline,
            req,
        };
        let mut prepared = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        prepared.draft.revision = 7;
        assert!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::RuntimeReady {
                    req,
                    model: Box::new(prepared),
                },
            )
            .is_none()
        );
        let View::Feed(feed) = &model.view else {
            panic!(
                "runtime ready must install the prepared feed: {:?}",
                model.view
            );
        };
        assert_eq!(feed.memos.len(), 2);
        assert_eq!(feed.load, LoadStatus::Ready);
        assert_eq!(model.draft.revision, 7);
    }

    #[test]
    fn append_pages_reuse_the_query_total_without_a_new_count() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(60)
            .expect("fixture and operation must succeed");
        let request = lomo_tui::effects::FeedRequest {
            req: lomo_tui::model::Req(1),
            kind: lomo_tui::model::FeedKind::Timeline,
            query: lomo_tui::model::FeedQuery::default(),
            intent: lomo_tui::effects::PageIntent::Initial,
        };
        let (results, _inbox) = std::sync::mpsc::sync_channel(256);
        let outbox = lomo_tui::executor::Outbox::new(results);
        let token = lomo_tui::model::CancelToken::live();
        let first = lomo_tui::ops::execute(
            &fixture.runtime,
            &Effect::Query(request.clone()),
            &outbox,
            &token,
        )
        .expect("fixture and operation must succeed");
        let RuntimeMessage::Page { total, next, .. } = first else {
            panic!("first page must report a total");
        };
        let initial_total = total.expect("first page carries the query total");
        let cursor = next.expect("seeded workspace must have a second page");
        let appended = lomo_tui::ops::execute(
            &fixture.runtime,
            &Effect::Query(lomo_tui::effects::FeedRequest {
                intent: lomo_tui::effects::PageIntent::Append(cursor),
                ..request
            }),
            &outbox,
            &token,
        )
        .expect("fixture and operation must succeed");
        let RuntimeMessage::Page {
            total: append_total,
            ..
        } = appended
        else {
            panic!("append must return a page");
        };
        assert_eq!(
            append_total, None,
            "append pages must not re-run COUNT; the query total arrives with the first page"
        );
        let mut model = model_with_memos(0, 80, 24).expect("fixture and operation must succeed");
        if let View::Feed(feed) = &mut model.view {
            feed.total = Some(initial_total);
        }
        let req = super::support::pending_page(&mut model).expect("page request in flight");
        assert!(
            lomo_tui::messages::apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req,
                    append: true,
                    cards: Vec::new(),
                    next: None,
                    order: Vec::new(),
                    total: None,
                },
            )
            .is_none()
        );
        let View::Feed(feed) = &model.view else {
            panic!("feed expected");
        };
        assert_eq!(
            feed.total,
            Some(initial_total),
            "an append reply without a total must not erase the query total"
        );
    }
}
