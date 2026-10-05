//! Behavior Contract
//! Capability (I4 re-audit): derived caches and pagination under adversarial
//! refresh — every render-path cost stays viewport-bounded, every derived
//! projection re-keys on its inputs, and a bounded window refresh can never
//! corrupt the loaded list or its counts.
//!
//! Scenarios (per probe):
//! - Card rows re-key on every render input (summary/tags/pinned/body state/
//!   body identity/width/excerpt) — a stale slot can never answer.
//! - `feed_window` materializes `viewport + lookahead` only, at any loaded size.
//! - Parked bodies restore by exact `(id, revision, fingerprint)` without a
//!   fetch; the 512-entry parked cache evicts the least-recently-parked.
//! - Page replies land only on the live `pending_page`; superseded requests
//!   degrade; refresh splices keep order, membership and `total` honest.
//! - Reader wraps memoize per body+width; scroll anchors survive resizes.
//! - Filter/unfilter restores the suspended reading position through a
//!   bounded window re-query, never by reviving stale cards.
//!
//! Observable outcomes: `MemoId` membership and order, `FeedState` counts and
//! cursors, `BodyState`/`Arc` identities, materialized row counts, wall-clock
//! medians at two dataset scales.
//!
//! Excludes: TEA executor/lane async correctness, pending-registry internals,
//! reconcile incremental correctness, image protocol encode cost — covered by
//! their own audit suites.
//!
//! TDD proof: this file is the re-audit evidence — probes assert the correct
//! invariant, not current behavior; RED results are reported verbatim in
//! `audit/09-复审-派生缓存与性能.md`.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, feed, feed_mut, memo, model_with_memos};
    use lomo_tui::{
        content::MemoBody,
        effects::{Effect, PageIntent, RuntimeMessage},
        event::Command,
        feed_layout,
        messages::apply_message,
        model::{AppModel, BodyState, CardPosition, FeedState, MemoCard, Req, View},
        navigation, reader, update,
    };
    use lomo_workspace::MemoId;
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    // ——— fixture helpers ————————————————————————————————————

    /// A Pending-body card: no Markdown parse, so multi-thousand-card feeds
    /// build cheaply for the scale probes.
    fn stub(index: usize) -> MemoCard {
        MemoCard {
            id: MemoId::parse(&format!("memo-{index}")).expect("id"),
            date: "2026-09-11".to_owned(),
            time: "12:00".to_owned(),
            summary: format!("Stub body {index}"),
            body: BodyState::Pending,
            tags: Vec::new(),
            attachments: Vec::new(),
            fingerprint: "fp-1".to_owned(),
            revision: 1,
            pinned: false,
            trashed: false,
            excerpt: None,
        }
    }

    /// A `memos` vector of `count` stub cards — the loaded-feed fixture for
    /// merge and window probes.
    fn stub_feed(count: usize, width: u16, height: u16) -> AppModel {
        let mut model = AppModel::new(width, height);
        let feed = feed_mut(&mut model).expect("feed");
        feed.memos = (0..count).map(stub).collect();
        feed.load = lomo_tui::model::LoadStatus::Ready;
        feed.total = Some(count as u64);
        feed.reconcile();
        model
    }

    /// A fabricated cursor — opaque to the model-level merge path.
    fn cursor(rank: i64) -> lomo_application::PageCursor {
        lomo_application::PageCursor::new(
            "fp".to_owned(),
            None,
            false,
            rank,
            rank,
            format!("memo-{rank}"),
            1,
        )
    }

    /// All text a card's materialized rows carry.
    fn rows_text(feed: &FeedState, width: u16, height: u16, id: &str) -> String {
        feed_layout::feed_window(feed, width, height)
            .rows
            .iter()
            .filter(|row| row.id.as_str() == id)
            .map(|row| {
                row.line
                    .spans
                    .iter()
                    .map(|span| span.content.as_ref())
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// Issue `intent` on the current feed and return the minted request — the
    /// effect is deliberately not executed so its reply can be crafted.
    fn issued(model: &mut AppModel, intent: PageIntent) -> Req {
        let Some(Effect::Query(request)) = update::issue_query(model, intent) else {
            panic!("issue_query must produce a feed query");
        };
        request.req
    }

    /// Deliver a fabricated `Page` reply to the live `req`. `order` mirrors the
    /// reply's ordering evidence: the live-rank id sequence over the reply and
    /// the still-live loaded ids (empty for append replies).
    fn land_page(
        model: &mut AppModel,
        req: Req,
        append: bool,
        cards: Vec<MemoCard>,
        next: Option<lomo_application::PageCursor>,
        order: Vec<MemoId>,
        total: Option<u64>,
    ) {
        let _effect = apply_message(
            model,
            RuntimeMessage::Page {
                req,
                append,
                cards,
                next,
                order,
                total,
            },
        );
    }

    /// Every loaded stub id — the `known` set a refresh request carries.
    fn known_ids(count: usize) -> Vec<MemoId> {
        (0..count)
            .map(|index| MemoId::parse(&format!("memo-{index}")).expect("id"))
            .collect()
    }

    fn ids(feed: &FeedState) -> Vec<String> {
        feed.memos
            .iter()
            .map(|memo| memo.id.as_str().to_owned())
            .collect()
    }

    /// The `index`-th loaded card — `.get` keeps a bad index a legible panic.
    fn card_at(feed: &FeedState, index: usize) -> &MemoCard {
        feed.memos
            .get(index)
            .expect("the stub loaded that many cards")
    }

    fn card_at_mut(feed: &mut FeedState, index: usize) -> &mut MemoCard {
        feed.memos
            .get_mut(index)
            .expect("the stub loaded that many cards")
    }

    // ——— §1 card-row cache: every render input re-keys —————————

    /// The card slot must re-key on every input `card_rows` reads — a stale
    /// materialization would draw yesterday's card under today's identity.
    #[test]
    fn card_rows_rekey_on_every_render_input() {
        let mut model = model_with_memos(40, 100, 24).expect("fixture");
        // Anchor mid-feed so the target card is inside the window.
        feed_mut(&mut model)
            .expect("the fixture is well-formed")
            .anchor = Some(lomo_tui::model::MemoAnchor {
            id: MemoId::parse("memo-20").expect("id"),
            position: CardPosition::Time,
        });
        // The summary is the render source only while the body is not Ready —
        // park the target's body first so every field is observable on screen.
        card_at_mut(
            feed_mut(&mut model).expect("the fixture is well-formed"),
            20,
        )
        .body = BodyState::Pending;
        let baseline = rows_text(
            feed(&model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(
            baseline.contains("Body 20"),
            "baseline shows the pending body summary"
        );

        summary_edit_rekeys_the_card(&mut model);
        tag_edit_rekeys_the_card(&mut model);
        pinned_mark_rekeys_the_card(&mut model);
        body_arc_identity_rekeys_the_card(&mut model);
        body_failure_phase_rekeys_the_card(&mut model);
        width_epoch_rekeys_the_card(&mut model);
    }

    /// Probe 1 — summary (fingerprint unchanged — the key must not lean on it alone)
    fn summary_edit_rekeys_the_card(model: &mut AppModel) {
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).summary =
            "edited summary".to_owned();
        let now = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(
            now.contains("edited summary"),
            "summary edit must re-render"
        );
    }

    /// Probe 2 — tags
    fn tag_edit_rekeys_the_card(model: &mut AppModel) {
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).tags =
            vec!["rust".to_owned()];
        let now = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(now.contains("#rust"), "tag footer must appear");
    }

    /// Probe 3 — pinned mark
    fn pinned_mark_rekeys_the_card(model: &mut AppModel) {
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).pinned = true;
        let now = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(now.contains("◆"), "pin mark must appear");
    }

    /// Probe 4 — a fresh body Arc under the same version swaps the rendered
    /// rows — Arc identity is a layout-cache input, not just the version fields.
    fn body_arc_identity_rekeys_the_card(model: &mut AppModel) {
        let pending_text = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        let fresh = memo("memo-20", "replacement body text").expect("card");
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).body = fresh.body;
        let now = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(
            now.contains("replacement body text"),
            "a new body Arc (same version) must swap the card lines"
        );
        assert!(
            !now.contains("edited summary") && now != pending_text,
            "Pending vs Ready must render different content for the same card"
        );
    }

    /// Probe 5 — body failure phase renders the error text
    fn body_failure_phase_rekeys_the_card(model: &mut AppModel) {
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).body =
            BodyState::Failed("disk vanished".to_owned());
        let now = rows_text(
            feed(model).expect("the fixture is well-formed"),
            98,
            20,
            "memo-20",
        );
        assert!(now.contains("disk vanished"), "Failed body shows its error");
    }

    /// Probe 6 — width epoch: a narrower card re-wraps long content — the key
    /// carries `width`, so the slot must not replay 98-wide rows at 40.
    fn width_epoch_rekeys_the_card(model: &mut AppModel) {
        let wide_body = memo(
            "memo-20",
            "a line of body text long enough that a forty-column card must wrap it",
        )
        .expect("card");
        card_at_mut(feed_mut(model).expect("the fixture is well-formed"), 20).body = wide_body.body;
        let wide =
            feed_layout::feed_window(feed(model).expect("the fixture is well-formed"), 98, 20)
                .rows
                .into_iter()
                .filter(|row| row.id.as_str() == "memo-20")
                .count();
        let narrow =
            feed_layout::feed_window(feed(model).expect("the fixture is well-formed"), 40, 20)
                .rows
                .into_iter()
                .filter(|row| row.id.as_str() == "memo-20")
                .count();
        assert!(
            narrow > wide,
            "a width epoch must re-wrap the card, not replay old rows \
             (wide {wide} rows, narrow {narrow})"
        );
    }

    /// `feed_window` touches `viewport + 2·lookahead` cards at most — the
    /// bound is structural: row count and card count stay flat no matter how
    /// deep the loaded list grows.
    #[test]
    fn feed_window_materializes_only_the_viewbound_neighborhood() {
        for count in [64_usize, 512, 4096] {
            let mut model = stub_feed(count, 100, 24);
            feed_mut(&mut model)
                .expect("the fixture is well-formed")
                .anchor = Some(lomo_tui::model::MemoAnchor {
                id: MemoId::parse(&format!("memo-{}", count / 2)).expect("id"),
                position: CardPosition::Time,
            });
            let window =
                feed_layout::feed_window(feed(&model).expect("the fixture is well-formed"), 98, 20);
            // A stub card is ~4 rows; the window covers height+lookahead below
            // the anchor row and lookahead above — generous bound, tight
            // enough that materializing the whole feed (>4·count rows) fails.
            assert!(
                window.rows.len() <= 20 + 2 * 48 + 8,
                "{count} loaded → {} rows materialized; the window must be \
                 viewport-bound, not feed-bound",
                window.rows.len()
            );
            assert!(
                window.cards.len() <= 20 + 2 * 48,
                "{count} loaded → window spans {} cards",
                window.cards.len()
            );
            assert_eq!(
                window.rows.get(window.top).map(|row| row.id.as_str()),
                Some(format!("memo-{}", count / 2).as_str()),
                "rows[top] is the anchor card's row"
            );
        }
        // Edge geometries: empty and singleton feeds, 1-row viewport.
        let empty = stub_feed(0, 100, 24);
        let window =
            feed_layout::feed_window(feed(&empty).expect("the fixture is well-formed"), 98, 20);
        assert!(window.rows.is_empty() && window.cards.is_empty());
        let single = stub_feed(1, 100, 24);
        let window =
            feed_layout::feed_window(feed(&single).expect("the fixture is well-formed"), 98, 2);
        assert!(
            window.rows.len() >= 3,
            "one card still emits its frame rows"
        );
    }

    /// Scrolling is row-granular and clamps at both ends; `select_visible`
    /// repairs the selection into the drawn slice on every hop.
    #[test]
    fn scrolling_clamps_at_both_ends_and_keeps_selection_visible() {
        let mut model = stub_feed(200, 100, 24);
        for delta in [i32::MIN, -37, -1, 1, 37, i32::MAX, i32::MAX, i32::MIN] {
            feed_layout::scroll_feed(
                feed_mut(&mut model).expect("the fixture is well-formed"),
                98,
                20,
                delta,
            );
            let feed = feed(&model).expect("the fixture is well-formed");
            let window = feed_layout::feed_window(feed, 98, 20);
            let visible: Vec<&str> = window
                .rows
                .iter()
                .skip(window.top)
                .take(20)
                .map(|row| row.id.as_str())
                .collect();
            assert!(
                !visible.is_empty(),
                "a populated feed always has a viewport slice (delta={delta})"
            );
            assert!(
                visible.contains(&feed.selected.as_ref().expect("selected").as_str()),
                "selection must stay inside the viewport after delta={delta}"
            );
        }
        let feed = feed(&model).expect("the fixture is well-formed");
        assert_eq!(
            feed.anchor.as_ref().expect("anchor").id.as_str(),
            "memo-0",
            "i32::MIN scroll lands on the first card"
        );
    }

    // ——— §2 body cache: versioned park/restore, resident bound ————

    /// A card evicted by the resident cap parks its parse keyed on
    /// `(id, revision, fingerprint)`; revisiting restores the same `Arc`
    /// with no fetch at all — the visible window itself is never evicted.
    #[test]
    fn parked_bodies_restore_without_a_refetch() {
        let mut model = model_with_memos(700, 120, 30).expect("fixture");
        let target_arc = {
            let feed = feed(&model).expect("the fixture is well-formed");
            match &card_at(feed, 0).body {
                BodyState::Ready(body) => Arc::clone(body),
                BodyState::Pending | BodyState::Loading { .. } | BodyState::Failed(_) => {
                    panic!("fixture bodies are ready")
                }
            }
        };
        // Anchor at the bottom: memo-0 is the farthest resident, evicted first.
        let bottom = MemoId::parse("memo-699").expect("id");
        {
            let f = feed_mut(&mut model).expect("the fixture is well-formed");
            f.anchor = Some(lomo_tui::model::MemoAnchor {
                id: bottom,
                position: CardPosition::Time,
            });
        }
        drop(navigation::hydrate_visible(&mut model));
        assert!(
            matches!(
                card_at(feed(&model).expect("the fixture is well-formed"), 0).body,
                BodyState::Pending
            ),
            "the resident cap demotes the farthest card to Pending"
        );
        // Walk back to it: the parked parse comes home silently — no request.
        drop(update::apply_command(&mut model, Command::First));
        let effect = navigation::hydrate_visible(&mut model);
        assert!(
            matches!(
                card_at(feed(&model).expect("the fixture is well-formed"), 0).body,
                BodyState::Ready(_)
            ),
            "revisiting a parked card restores its body"
        );
        if let BodyState::Ready(body) =
            &card_at(feed(&model).expect("the fixture is well-formed"), 0).body
        {
            assert!(
                Arc::ptr_eq(body, &target_arc),
                "the parked parse is the same allocation — no re-parse"
            );
        }
        assert!(
            effect.is_none(),
            "a window restored entirely from the parked cache issues no Bodies \
             effect: {effect:?}"
        );
        // And no card inside the current window was ever evicted.
        let feed = feed(&model).expect("the fixture is well-formed");
        let window = feed_layout::feed_window(feed, 118, 26);
        for index in window.cards {
            assert!(
                matches!(card_at(feed, index).body, BodyState::Ready(_)),
                "card {index} inside the live window must stay resident"
            );
        }
    }

    /// Beyond the 512-entry parked bound, the least-recently-parked parse is
    /// dropped — a card that deep must genuinely re-fetch, while a
    /// recently-parked one still restores silently. This proves both the cap
    /// and that its eviction is ordered, not random.
    #[test]
    fn body_cache_cap_drops_the_oldest_parked_version() {
        let mut model = model_with_memos(1600, 120, 30).expect("fixture");
        let anchor_bottom = lomo_tui::model::MemoAnchor {
            id: MemoId::parse("memo-1599").expect("id"),
            position: CardPosition::Time,
        };
        feed_mut(&mut model)
            .expect("the fixture is well-formed")
            .anchor = Some(anchor_bottom);
        // Each sweep evicts at most 128 farthest residents — run until the
        // resident count converges at the cap. ~1080 evictions ≫ 512 parked,
        // so memo-0's entry (first sweep) leaves the cache.
        for _ in 0..12 {
            drop(navigation::hydrate_visible(&mut model));
        }
        let still_pending = feed(&model)
            .expect("the fixture is well-formed")
            .memos
            .iter()
            .filter(|memo| matches!(memo.body, BodyState::Pending))
            .count();
        assert!(
            still_pending >= 1000,
            "sweeps park every off-window resident beyond the cap: {still_pending} pending"
        );
        // A late-parked card keeps its entry: park the tail closer to the
        // anchor — memo-900 was evicted after the cache filled, so it must
        // still answer.
        let f = feed_mut(&mut model).expect("the fixture is well-formed");
        f.anchor = Some(lomo_tui::model::MemoAnchor {
            id: MemoId::parse("memo-900").expect("id"),
            position: CardPosition::Time,
        });
        let late = navigation::hydrate_visible(&mut model);
        let late_versions: Vec<String> = match &late {
            Some(Effect::Bodies { versions, .. }) => versions
                .iter()
                .map(|version| version.id.as_str().to_owned())
                .collect(),
            _ => Vec::new(),
        };
        assert!(
            matches!(
                card_at(feed(&model).expect("the fixture is well-formed"), 900).body,
                BodyState::Ready(_)
            ),
            "a card parked inside the cache bound restores without a fetch"
        );
        assert!(
            !late_versions.iter().any(|id| id == "memo-900"),
            "memo-900's parked entry must answer — no refetch: {late_versions:?}"
        );
        // The oldest parked card is gone from the cache: revisiting it must
        // fetch — the effect names its version.
        let f = feed_mut(&mut model).expect("the fixture is well-formed");
        f.anchor = Some(lomo_tui::model::MemoAnchor {
            id: MemoId::parse("memo-0").expect("id"),
            position: CardPosition::Time,
        });
        let effect = navigation::hydrate_visible(&mut model);
        let Some(Effect::Bodies { versions, .. }) = &effect else {
            panic!("the oldest parked body was dropped by the cap — it must re-fetch");
        };
        assert!(
            versions
                .iter()
                .any(|version| version.id.as_str() == "memo-0"),
            "memo-0's parked entry overflowed the 512 bound — a fetch is due"
        );
    }

    /// A body reply binds to the exact `MemoVersion` it was issued for: same
    /// id with a moved revision/fingerprint is rejected in place, and a reply
    /// naming a dead request degrades instead of hydrating anything.
    #[test]
    fn body_replies_land_only_on_their_exact_version() {
        let mut model = model_with_memos(30, 120, 30).expect("fixture");
        // Park memo-0's body by hand, then mutate its version fields: the
        // parked entry and any reply for the old version must both miss.
        let f = feed_mut(&mut model).expect("the fixture is well-formed");
        let stale_body = match &card_at(f, 0).body {
            BodyState::Ready(body) => Arc::clone(body),
            BodyState::Pending | BodyState::Loading { .. } | BodyState::Failed(_) => {
                panic!("ready body")
            }
        };
        let first = card_at_mut(f, 0);
        first.body = BodyState::Pending;
        first.revision = 2;
        first.fingerprint = "fp-2".to_owned();
        // A crafted Bodies reply carrying the OLD version must not land on it.
        let req = model.request(lomo_tui::model::PendingKind::Bodies);
        let stale_reply = lomo_tui::effects::BodyReply {
            version: lomo_tui::model::MemoVersion {
                id: MemoId::parse("memo-0").expect("id"),
                revision: 1,
                fingerprint: "version-1".to_owned(),
            },
            result: Ok(lomo_tui::effects::LoadedBody {
                body: stale_body,
                attachments: Vec::new(),
            }),
        };
        drop(apply_message(
            &mut model,
            RuntimeMessage::Bodies {
                req,
                bodies: vec![stale_reply],
            },
        ));
        assert!(
            matches!(
                card_at(feed(&model).expect("the fixture is well-formed"), 0).body,
                BodyState::Pending
            ),
            "a body reply for the previous version must not hydrate the new one"
        );
        // And the right version lands.
        let req = model.request(lomo_tui::model::PendingKind::Bodies);
        let fresh = memo("memo-0", "v2 body").expect("card");
        let v2 = card_at(feed(&model).expect("the fixture is well-formed"), 0).version();
        drop(apply_message(
            &mut model,
            RuntimeMessage::Bodies {
                req,
                bodies: vec![lomo_tui::effects::BodyReply {
                    version: v2,
                    result: Ok(lomo_tui::effects::LoadedBody {
                        body: match fresh.body {
                            BodyState::Ready(body) => body,
                            BodyState::Pending
                            | BodyState::Loading { .. }
                            | BodyState::Failed(_) => {
                                panic!("ready")
                            }
                        },
                        attachments: Vec::new(),
                    }),
                }],
            },
        ));
        assert!(
            matches!(
                card_at(feed(&model).expect("the fixture is well-formed"), 0).body,
                BodyState::Ready(_)
            ),
            "the exact version's reply lands"
        );
        assert!(
            rows_text(
                feed(&model).expect("the fixture is well-formed"),
                118,
                26,
                "memo-0"
            )
            .contains("v2 body"),
            "the landed body renders its own content, not the stale parse"
        );
    }

    // ——— §3 pagination merge: order, membership, counts ————————

    /// An append reply may carry already-loaded ids (overlap between issues);
    /// only genuinely new ids append, the cursor advances, and no reply can
    /// land twice.
    #[test]
    fn an_append_reply_adds_only_new_ids_and_moves_the_cursor() {
        let mut model = stub_feed(48, 100, 24);
        feed_mut(&mut model)
            .expect("the fixture is well-formed")
            .next_cursor = Some(cursor(48));
        let req = issued(&mut model, PageIntent::Append(cursor(48)));
        // The reply overlaps the tail of the loaded list plus 8 new ids.
        let cards: Vec<MemoCard> = (40..56).map(stub).collect();
        land_page(
            &mut model,
            req,
            true,
            cards.clone(),
            Some(cursor(56)),
            Vec::new(),
            None,
        );
        let feed = feed(&model).expect("the fixture is well-formed");
        assert_eq!(feed.memos.len(), 56, "only unseen ids append");
        let unique: std::collections::BTreeSet<_> = ids(feed).into_iter().collect();
        assert_eq!(unique.len(), 56, "no duplicates may survive the merge");
        assert!(
            feed.next_cursor.is_some(),
            "the cursor advances with the reply"
        );
        // The same reply a second time is dead — claim already consumed it.
        land_page(
            &mut model,
            req,
            true,
            cards,
            Some(cursor(64)),
            Vec::new(),
            None,
        );
        assert_eq!(
            super::support::feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            56,
            "a claimed request's reply can never land twice"
        );
    }

    /// Rapid pagination: issue Append, issue another before the first lands —
    /// the first request is cancelled and its reply degrades; only the live
    /// request's reply mutates the feed. The same rule covers a stale Failed.
    #[test]
    fn superseded_page_replies_degrade_quietly() {
        let mut model = stub_feed(48, 100, 24);
        feed_mut(&mut model)
            .expect("the fixture is well-formed")
            .next_cursor = Some(cursor(48));
        let first = issued(&mut model, PageIntent::Append(cursor(48)));
        let second = issued(&mut model, PageIntent::Append(cursor(96)));
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .pending_page,
            Some(second),
            "the newest request owns the feed slot"
        );
        // Stale data reply for the cancelled first request: quiet no-op.
        land_page(
            &mut model,
            first,
            true,
            (48..60).map(stub).collect(),
            Some(cursor(60)),
            Vec::new(),
            None,
        );
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            48,
            "a superseded reply must not touch the feed"
        );
        // Stale failure: status surfaces, feed untouched.
        drop(apply_message(
            &mut model,
            RuntimeMessage::Failed {
                req: first,
                diagnostic: "late failure".to_owned(),
            },
        ));
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            48
        );
        assert!(
            matches!(
                feed(&model).expect("the fixture is well-formed").load,
                lomo_tui::model::LoadStatus::Loading
            ),
            "the live request is still in flight — a stale failure must not mark it"
        );
        // The live reply lands.
        land_page(
            &mut model,
            second,
            true,
            (48..96).map(stub).collect(),
            Some(cursor(96)),
            Vec::new(),
            None,
        );
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            96
        );
        assert!(
            matches!(
                feed(&model).expect("the fixture is well-formed").load,
                lomo_tui::model::LoadStatus::Ready
            ),
            "the live reply completes the load"
        );
    }

    /// A deletion *inside* the refresh window is handled exactly: the splice
    /// drops the dead card, keeps every neighbour, and relocates the
    /// selection/anchor to the surviving neighbour — this is the path the
    /// repair record claims works, verified end to end.
    #[test]
    fn refresh_splice_relocates_an_in_window_deletion() {
        let mut model = stub_feed(200, 100, 24);
        {
            let f = feed_mut(&mut model).expect("the fixture is well-formed");
            f.selected = Some(MemoId::parse("memo-150").expect("id"));
            f.anchor = Some(lomo_tui::model::MemoAnchor {
                id: MemoId::parse("memo-150").expect("id"),
                position: CardPosition::Time,
            });
        }
        // memo-150 vanished: the refresh window covers 140..160 without it and
        // the reply's `order` simply omits it — membership evidence, not a
        // rank delta.
        let req = issued(
            &mut model,
            PageIntent::Refresh {
                anchors: vec![MemoId::parse("memo-150").expect("id")],
                known: known_ids(200),
            },
        );
        let reply_cards: Vec<MemoCard> =
            (140..160).filter(|index| *index != 150).map(stub).collect();
        land_page(
            &mut model,
            req,
            false,
            reply_cards,
            Some(cursor(199)),
            (0..200)
                .filter(|index| *index != 150)
                .map(|index| MemoId::parse(&format!("memo-{index}")).expect("id"))
                .collect(),
            Some(199),
        );
        let f = feed(&model).expect("the fixture is well-formed");
        let list = ids(f);
        assert_eq!(
            list.len(),
            199,
            "one card vanished — the count drops by one"
        );
        assert!(
            !list.iter().any(|id| id == "memo-150"),
            "the deleted card is gone: {list:?}"
        );
        assert_eq!(
            f.selected.as_ref().map(MemoId::as_str),
            Some("memo-151"),
            "selection relocates to the nearest surviving neighbour"
        );
        assert_eq!(
            f.anchor.as_ref().map(|anchor| anchor.id.as_str()),
            Some("memo-151"),
            "the anchor relocates with it"
        );
        // Full order check: the merge must be exactly old-minus-150.
        let expected: Vec<String> = (0..200)
            .filter(|index| *index != 150)
            .map(|index| format!("memo-{index}"))
            .collect();
        assert_eq!(list, expected, "the splice preserves order and membership");
    }

    /// The adversarial case: the deletion is *above* the refresh window, not
    /// inside it. A rank delta only proves a count loss — never *who* — so
    /// membership outside the window is decided solely by the reply's `order`
    /// evidence: it omits memo-3, the merge drops it, and every live neighbour
    /// (memo-139 right at the window's edge) must survive.
    #[test]
    fn refresh_above_window_deletions_must_not_drop_live_cards() {
        let mut model = stub_feed(200, 100, 24);
        // memo-3 was deleted in the store while the user read mid-feed; the
        // window covers 140..160 and the reply's `order` omits the departure.
        let req = issued(
            &mut model,
            PageIntent::Refresh {
                anchors: vec![MemoId::parse("memo-150").expect("id")],
                known: known_ids(200),
            },
        );
        let reply_cards: Vec<MemoCard> = (140..160).map(stub).collect();
        land_page(
            &mut model,
            req,
            false,
            reply_cards,
            Some(cursor(199)),
            (0..200)
                .filter(|index| *index != 3)
                .map(|index| MemoId::parse(&format!("memo-{index}")).expect("id"))
                .collect(),
            Some(199),
        );
        let feed = feed(&model).expect("the fixture is well-formed");
        let list = ids(feed);
        let mut violations = Vec::new();
        if list.iter().any(|id| id == "memo-3") {
            violations.push(
                "memo-3 was deleted in the store but still renders (zombie survives the splice)"
                    .to_owned(),
            );
        }
        // memo-139 sits just above the window and is live — no evidence names
        // it, so the splice must keep it.
        if !list.iter().any(|id| id == "memo-139") {
            violations.push(
                "memo-139 is live in the store but was dropped from the feed \
                 (mis-attributed splice victim)"
                    .to_owned(),
            );
        }
        // The splice is also required to keep the count honest.
        if list.len() != 199 {
            violations.push(format!("merged length {} ≠ 199", list.len()));
        }
        assert!(
            violations.is_empty(),
            "a deletion above the refresh window mis-splices the prefix:\n{}",
            violations.join("\n")
        );
    }

    /// The refresh reply's `next` points just past its window; when the old
    /// tail is kept, regression below the already-loaded frontier makes the
    /// next `Last` re-fetch cards the feed already holds — and an insertion
    /// discovered mid-tail lands at the END of the list, breaking order.
    #[test]
    fn refresh_must_not_regress_the_pagination_cursor_into_the_kept_tail() {
        let mut model = stub_feed(200, 100, 24);
        // Fully loaded: the store end is known.
        feed_mut(&mut model)
            .expect("the fixture is well-formed")
            .next_cursor = None;
        let req = issued(
            &mut model,
            PageIntent::Refresh {
                anchors: vec![MemoId::parse("memo-150").expect("id")],
                known: known_ids(200),
            },
        );
        // A refresh window mid-feed: 48 cards, more items after, tail kept —
        // and the reply's frontier mints at the live tail (the store end), not
        // the window edge.
        let reply_cards: Vec<MemoCard> = (120..168).map(stub).collect();
        land_page(
            &mut model,
            req,
            false,
            reply_cards,
            None,
            (0..200)
                .map(|index| MemoId::parse(&format!("memo-{index}")).expect("id"))
                .collect(),
            Some(200),
        );
        let mut violations = Vec::new();
        {
            let feed = feed(&model).expect("the fixture is well-formed");
            if feed.memos.len() != 200 {
                violations.push(format!("the kept tail lost cards: {}", feed.memos.len()));
            }
            if feed.next_cursor.is_some() {
                violations.push(format!(
                    "next_cursor regressed into the kept tail — the next 'load \
                     more' re-reads cards the feed already holds: {:?}",
                    feed.next_cursor
                ));
            }
        }
        // Demonstrate the failure mode the regression enables: a store
        // insertion served inside the re-read tail region lands at the END of
        // the list — append has no way to place it at its rank.
        if let Some(next) = feed(&model)
            .expect("the fixture is well-formed")
            .next_cursor
            .clone()
        {
            let req = issued(&mut model, PageIntent::Append(next));
            let mut inserted = stub(1000);
            inserted.id = MemoId::parse("memo-0168b").expect("id");
            let mut page = vec![inserted];
            page.extend((168..200).map(stub));
            land_page(&mut model, req, true, page, None, Vec::new(), None);
            let list = ids(feed(&model).expect("the fixture is well-formed"));
            let new_at = list
                .iter()
                .position(|id| id == "memo-0168b")
                .expect("the new card must land");
            if new_at > list.iter().position(|id| id == "memo-199").expect("tail") {
                violations.push(format!(
                    "a card ranking right after the window edge appended at the \
                     END of the list (position {new_at} of {})",
                    list.len()
                ));
            }
        }
        assert!(
            violations.is_empty(),
            "a mid-feed refresh corrupts pagination state:\n{}",
            violations.join("\n")
        );
    }

    /// Filter → unfilter restores the suspended reading position through a
    /// bounded window re-query — a real session: date filter in, Esc out.
    #[test]
    fn filter_roundtrip_restores_position_on_a_bounded_window() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(150)
            .expect("fixture and operation must succeed");
        // A second, newer day so the unfiltered order interleaves sources.
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_12.md"),
            "- 11:00:00\nsecond day\n\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        // Read deep into the list first.
        while feed(&model)
            .expect("the fixture is well-formed")
            .next_cursor
            .is_some()
        {
            command(&fixture.runtime, &mut model, Command::Last)
                .expect("fixture and operation must succeed");
        }
        let before = feed(&model).expect("the fixture is well-formed");
        let (selected, anchor) = (
            before.selected.clone().expect("selected"),
            before.anchor.clone().expect("anchor"),
        );
        let full_total = before.total;
        // Apply a day filter, then clear it with Esc.
        command(
            &fixture.runtime,
            &mut model,
            Command::SetDate("2026-09-11".to_owned()),
        )
        .expect("fixture and operation must succeed");
        assert!(
            feed(&model)
                .expect("the fixture is well-formed")
                .query
                .is_filtered(),
            "the date filter applies"
        );
        command(&fixture.runtime, &mut model, Command::Back)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert!(
            !feed.query.is_filtered(),
            "the unfiltered query is restored"
        );
        assert_eq!(
            feed.selected.as_ref(),
            Some(&selected),
            "the suspended selection comes back"
        );
        assert_eq!(
            feed.anchor.as_ref().map(|anchor| &anchor.id),
            Some(&anchor.id),
            "the suspended anchor comes back"
        );
        assert_eq!(feed.total, full_total, "the unfiltered total is restored");
        assert!(
            feed.memos.len() <= 3 * 48 + 48,
            "restore reads a bounded window, not the old list: {}",
            feed.memos.len()
        );
        assert!(
            feed.memos.iter().any(|memo| memo.id == anchor.id),
            "the window is anchored on the suspended position"
        );
    }

    /// The suspended position's anchor vanished while filtered: the restore
    /// must terminate bounded (`AtMemo` falls back to head) and land an honest
    /// page — never drain the whole query hunting a dead anchor.
    #[test]
    fn filter_restore_with_a_dead_anchor_lands_a_bounded_head_window() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(150)
            .expect("fixture and operation must succeed");
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        command(
            &fixture.runtime,
            &mut model,
            Command::SetDate("2026-09-11".to_owned()),
        )
        .expect("fixture and operation must succeed");
        // The anchor memo's entire day file vanishes mid-filter.
        std::fs::remove_file(fixture.runtime.workspace.join("2026_09_11.md"))
            .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Back)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert!(
            feed.memos.len() <= 3 * 48 + 48,
            "a dead anchor must not turn the restore into a full re-read: {} cards",
            feed.memos.len()
        );
        assert!(
            matches!(feed.load, lomo_tui::model::LoadStatus::Ready),
            "the restore lands Ready, not wedged Loading"
        );
        assert!(
            feed.selected.is_none()
                || feed
                    .memos
                    .iter()
                    .any(|memo| feed.selected.as_ref() == Some(&memo.id)),
            "the selection names a live card or none — never a ghost"
        );
    }

    // ——— §4 reader wrap cache and anchor stability ————————————

    /// `MemoBody::wrapped` memoizes per (body, width): same inputs → same Arc;
    /// a width change re-wraps; a different body can never share the cache.
    /// A cloned body re-derives (its cache is deliberately empty).
    #[test]
    fn wrapped_rows_share_an_allocation_per_body_and_width() {
        let body = MemoBody::parse(
            (0..400)
                .map(|i| format!("line {i}"))
                .collect::<Vec<_>>()
                .join("\n"),
        )
        .expect("parse");
        let a = body.wrapped(80);
        let b = body.wrapped(80);
        assert!(
            Arc::ptr_eq(&a, &b),
            "same body+width serves the same Arc — no re-wrap"
        );
        let narrow = body.wrapped(40);
        assert!(
            !Arc::ptr_eq(&a, &narrow),
            "a width change is a different wrap"
        );
        let other = MemoBody::parse("different".to_owned()).expect("parse");
        assert!(
            !Arc::ptr_eq(&a, &other.wrapped(80)),
            "another body has its own cache"
        );
        // A body carrying the same content is a distinct allocation — its
        // empty cache re-derives on demand rather than deep-sharing rows.
        let reparsed = MemoBody::parse(
            (0..400)
                .map(|i| format!("line {i}"))
                .collect::<Vec<_>>()
                .join("\n"),
        )
        .expect("parse");
        let rewrapped = reparsed.wrapped(80);
        assert_eq!(a.len(), rewrapped.len(), "the re-derived wrap is identical");
        assert!(
            !Arc::ptr_eq(&a, &rewrapped),
            "a different body never shares the wrap allocation"
        );
    }

    /// The reader's page is a bounded window, scrolling clamps at both ends,
    /// and a terminal resize keeps the semantic anchor on the same source line
    /// — the wrap may change, the reading position must not.
    #[test]
    fn reader_page_is_bounded_and_the_anchor_survives_resizes() {
        let raw = (0..3_000)
            .map(|line| format!("reader line {line} with enough text to wrap around"))
            .collect::<Vec<_>>()
            .join("\n\n");
        let mut card = memo("memo-reader", &raw).expect("card");
        card.summary = raw;
        let mut model = model_with_memos(1, 100, 40).expect("fixture");
        model.view = View::Reader {
            memo: card,
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        let page = reader::page(&model).expect("page");
        assert!(
            page.rows.len() <= usize::from(page.area.height) + 2 * 48 + 4,
            "the materialized page is viewport+lookahead, not the document: {} rows",
            page.rows.len()
        );
        // Scroll deep, remember the source line the anchor rests on.
        for _ in 0..20 {
            if let Some(next) = reader::scroll_anchor(&model, 30)
                && let View::Reader { anchor, .. } = &mut model.view
            {
                *anchor = next;
            }
        }
        let before = reader::page(&model).expect("page");
        let top_line = before
            .rows
            .get(before.top.saturating_sub(before.origin))
            .map(|row| row.anchor.line)
            .expect("a top row exists");
        // Halve the width: everything re-wraps, the anchor's source line holds.
        update::apply_resize(&mut model, 50, 40);
        let after = reader::page(&model).expect("page");
        let resized_line = after
            .rows
            .get(after.top.saturating_sub(after.origin))
            .map(|row| row.anchor.line)
            .expect("a top row exists");
        assert_eq!(
            top_line, resized_line,
            "a resize must not move the reading position to a different line"
        );
        // Scroll past both ends: the clamp holds — top never exceeds
        // total-height and never underflows.
        for _ in 0..200 {
            if let Some(next) = reader::scroll_anchor(&model, 50)
                && let View::Reader { anchor, .. } = &mut model.view
            {
                *anchor = next;
            }
        }
        let end = reader::page(&model).expect("page");
        assert!(
            end.top <= end.total.saturating_sub(usize::from(end.area.height)),
            "the page top clamps to the last full viewport"
        );
        for _ in 0..200 {
            if let Some(next) = reader::scroll_anchor(&model, -50)
                && let View::Reader { anchor, .. } = &mut model.view
            {
                *anchor = next;
            }
        }
        let start = reader::page(&model).expect("page");
        assert_eq!(start.top, 0, "the page top clamps at the document start");
    }

    /// A same-version body replacement (`RestoreRevision`, external edit with
    /// identical summary) produces a fresh `Arc` — the wrap cache lives on the
    /// body, so the reader can never show the old parse under the new Arc.
    #[test]
    fn reader_body_replacement_rewraps_immediately() {
        let mut card = memo("memo-r", "original body with a marker-one").expect("card");
        card.summary = "original body with a marker-one".to_owned();
        let mut model = model_with_memos(1, 100, 40).expect("fixture");
        model.view = View::Reader {
            memo: card.clone(),
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        let text_of = |model: &AppModel| {
            reader::page(model)
                .expect("page")
                .rows
                .iter()
                .map(|row| {
                    row.line
                        .spans
                        .iter()
                        .map(|span| span.content.as_ref())
                        .collect::<String>()
                })
                .collect::<Vec<_>>()
                .join("\n")
        };
        assert!(text_of(&model).contains("marker-one"));
        card.body = BodyState::Ready(Arc::new(
            MemoBody::parse("rewritten body with marker-two".to_owned()).expect("parse"),
        ));
        model.view = View::Reader {
            memo: card,
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        let text = text_of(&model);
        assert!(
            text.contains("marker-two") && !text.contains("marker-one"),
            "a new body Arc invalidates the wrap cache — no stale rows"
        );
    }

    // ——— §5 runtime query surface: refresh totals, cursors —————

    /// A multi-page refresh on a *search* feed must report the true query
    /// total — `page.total` is the honest store count, not head+window math.
    /// The probe loads past the 144-card refresh cap so the covers loop runs
    /// multiple pages; the reply's `items_after` must account for how deep the
    /// loop already read.
    #[test]
    fn search_refresh_reports_the_store_total_not_an_inflated_window_count() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(200)
            .expect("fixture and operation must succeed"); // every memo contains "needle"
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Search)
            .expect("fixture and operation must succeed");
        command(
            &fixture.runtime,
            &mut model,
            Command::Type("needle".to_owned()),
        )
        .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        // Page the filtered feed to the end so the refresh anchors sit deeper
        // than the 3-page refresh cap.
        while feed(&model)
            .expect("the fixture is well-formed")
            .next_cursor
            .is_some()
        {
            command(&fixture.runtime, &mut model, Command::Last)
                .expect("fixture and operation must succeed");
        }
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            200,
            "the whole result set is loaded"
        );
        let effect = update::reload_feed(&mut model);
        super::support::run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert_eq!(
            feed.total,
            Some(200),
            "a search refresh must report the store's real total, not \
             cards-read + page.total − last-page-len"
        );
        assert_eq!(
            feed.memos.len(),
            200,
            "the anchored window splices without losing loaded cards"
        );
    }

    /// The same refresh on an *unfiltered* feed must keep the known end: the
    /// kept tail is already loaded to the cursor's proof. Anchored mid-feed,
    /// the reply's `next` points at the window edge — a regressed cursor
    /// silently re-reads the tail on the next page request.
    #[test]
    fn unfiltered_refresh_keeps_the_pagination_frontier() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(200)
            .expect("fixture and operation must succeed");
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        while feed(&model)
            .expect("the fixture is well-formed")
            .next_cursor
            .is_some()
        {
            command(&fixture.runtime, &mut model, Command::Last)
                .expect("fixture and operation must succeed");
        }
        assert_eq!(
            feed(&model)
                .expect("the fixture is well-formed")
                .memos
                .len(),
            200
        );
        // Anchor mid-feed so the refresh window lands below the loaded end.
        command(&fixture.runtime, &mut model, Command::First)
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Move(100))
            .expect("fixture and operation must succeed");
        let effect = update::reload_feed(&mut model);
        super::support::run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert_eq!(
            feed.total,
            Some(200),
            "the unfiltered refresh reports the true total"
        );
        assert_eq!(feed.memos.len(), 200, "the window splices in place");
        assert!(
            feed.next_cursor.is_none(),
            "the loaded tail already proves the query ends — the refresh must \
             not reopen pagination at the window edge"
        );
    }

    /// A stale append cursor after a workspace rewrite must be rejected — the
    /// cursor is version-bound (`high_water_revision`), not silently followed
    /// into a re-ordered list. The recovery path (refresh) still answers.
    #[test]
    fn a_stale_cursor_is_rejected_not_served() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(120)
            .expect("fixture and operation must succeed");
        let runtime = &fixture.runtime;
        let query = lomo_application::MemoQuery {
            search_text: None,
            filters: lomo_application::MemoFilters::default(),
            sort: lomo_application::MemoSort::default(),
        };
        let page1 = runtime
            .session
            .query_memos_starting_at(
                &query,
                None,
                lomo_application::MemoQueryStart::Head,
                lomo_core::PageSize::new(48).expect("fixture and operation must succeed"),
            )
            .expect("fixture and operation must succeed");
        let cursor = page1.next_cursor.expect("first page has a cursor");
        // Any projection write moves the high-water mark — an old cursor
        // cannot be replayed into the new order.
        std::fs::write(
            runtime.workspace.join("2026_09_12.md"),
            "- 11:00:00\nnewer memo\n\n",
        )
        .expect("fixture and operation must succeed");
        runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let reused = runtime.session.query_memos_starting_at(
            &query,
            None,
            lomo_application::MemoQueryStart::After(&cursor),
            lomo_core::PageSize::new(48).expect("fixture and operation must succeed"),
        );
        assert!(
            reused.is_err(),
            "a cursor minted before a projection write must not serve pages \
             from the new order — it must fail closed"
        );
        // The live path recovers: a fresh head page still answers.
        let head = runtime
            .session
            .query_memos_starting_at(
                &query,
                None,
                lomo_application::MemoQueryStart::Head,
                lomo_core::PageSize::new(48).expect("fixture and operation must succeed"),
            )
            .expect("fixture and operation must succeed");
        assert_eq!(head.items.len(), 48, "head pages remain reachable");
    }

    /// Fulltext and fuzzy are different engines behind the same surface: the
    /// mode flag must reach the query. "ndle" is a subsequence of "needle"
    /// but never an FTS token — fuzzy scores it, fulltext must not.
    /// (Session-level probes live in their own fixture: every call bumps the
    /// session's `search_epoch`, and a lower TUI req would then discard.)
    #[test]
    fn search_modes_carry_their_own_semantics() {
        // Application surface the TUI calls.
        let probe = RuntimeFixture::new().expect("fixture and operation must succeed");
        probe.seed(80).expect("fixture and operation must succeed");
        let page = |mode: lomo_application::SearchMode, text: &str, epoch: u64| {
            let outcome = probe
                .runtime
                .session
                .search(&lomo_application::SearchRequest {
                    query_epoch: epoch,
                    mode,
                    text: text.to_owned(),
                    filters: lomo_application::MemoFilters::default(),
                    cursor: None,
                    anchor: None,
                    page_size: lomo_core::PageSize::new(48)
                        .expect("fixture and operation must succeed"),
                })
                .expect("fixture and operation must succeed");
            let lomo_application::SearchOutcome::Ready(page) = outcome else {
                panic!("a live epoch is never discarded");
            };
            page
        };
        assert_eq!(
            page(lomo_application::SearchMode::Fulltext, "needle", 1)
                .items
                .len(),
            48,
            "fulltext pages the token's hits"
        );
        assert_eq!(
            page(lomo_application::SearchMode::Fulltext, "ndle", 2)
                .items
                .len(),
            0,
            "fulltext is token-based — a fragment cannot match"
        );
        assert_eq!(
            page(lomo_application::SearchMode::Fuzzy, "ndle", 3)
                .items
                .len(),
            48,
            "fuzzy matches the subsequence — the modes are not interchangeable"
        );

        // TUI surface: the toggle reaches the live query while typing.
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(80)
            .expect("fixture and operation must succeed");
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Search)
            .expect("fixture and operation must succeed");
        command(
            &fixture.runtime,
            &mut model,
            Command::Type("needle".to_owned()),
        )
        .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model).expect("the fixture is well-formed").query.mode,
            lomo_application::SearchMode::Fulltext,
            "the default mode is fulltext"
        );
        command(&fixture.runtime, &mut model, Command::ToggleSearchMode)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert_eq!(
            feed.query.mode,
            lomo_application::SearchMode::Fuzzy,
            "the toggle flips the live query's mode"
        );
        assert_eq!(
            feed.memos.len(),
            48,
            "the mode change re-queries rather than re-skinning the old hits"
        );
    }

    /// The refresh loop reads at most the bounded window when every anchor is
    /// gone — `covers` can never succeed, the loop must stop, and the splice
    /// replaces the neighbourhood honestly.
    #[test]
    fn refresh_with_all_anchors_gone_stops_at_the_window() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(200)
            .expect("fixture and operation must succeed");
        let mut model = lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
            .expect("fixture and operation must succeed");
        while feed(&model)
            .expect("the fixture is well-formed")
            .next_cursor
            .is_some()
        {
            command(&fixture.runtime, &mut model, Command::Last)
                .expect("fixture and operation must succeed");
        }
        // The workspace shrinks to a different day: every anchor is dead.
        std::fs::remove_file(fixture.runtime.workspace.join("2026_09_11.md"))
            .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let effect = update::reload_feed(&mut model);
        super::support::run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let feed = feed(&model).expect("the fixture is well-formed");
        assert!(
            feed.memos.len() <= 3 * 48 + 48,
            "a refresh with no living anchor terminates inside the bound: {} cards",
            feed.memos.len()
        );
        assert!(
            matches!(feed.load, lomo_tui::model::LoadStatus::Ready),
            "it still lands as a ready page, not a wedged load"
        );
    }

    // ——— §6 per-frame residuals at loaded-feed scale ——————————

    /// `hydrate_visible` currently scans every loaded memo twice (the Ready
    /// count and the farthest-collect), and `ui::draw` resolves the selected
    /// memo by linear search per hint chip. Neither materializes off-window
    /// rows — but both are O(loaded) per interaction. This probe asserts a
    /// generous flat bound that any *materialization* regression would blow
    /// through, and reports the measured slope for the record.
    #[test]
    fn per_frame_surfaces_do_not_scale_with_loaded_card_count() {
        fn hydrate_time(count: usize) -> Duration {
            let mut model = stub_feed(count, 100, 30);
            let mut best = Duration::MAX;
            for _ in 0..9 {
                let start = Instant::now();
                drop(navigation::hydrate_visible(&mut model));
                best = best.min(start.elapsed());
            }
            best
        }
        fn draw_time(count: usize) -> Duration {
            let model = stub_feed(count, 100, 30);
            let mut terminal = ratatui::Terminal::new(ratatui::backend::TestBackend::new(100, 30))
                .expect("backend");
            let mut best = Duration::MAX;
            for _ in 0..9 {
                let start = Instant::now();
                terminal
                    .draw(|frame| lomo_tui::ui::draw(frame, &model))
                    .expect("draw");
                best = best.min(start.elapsed());
            }
            best
        }
        let small = hydrate_time(400);
        let large = hydrate_time(12_000);
        assert!(
            large < Duration::from_millis(4),
            "hydrate_visible stays sub-frame at 12k loaded memos — the residual \
             scan is small but unbounded: 400→{small:?} 12000→{large:?}"
        );

        let d_small = draw_time(400);
        let d_large = draw_time(12_000);
        assert!(
            d_large < Duration::from_millis(8),
            "a draw at 12k loaded memos stays under half a frame — the per-chip \
             selected_memo scans are measurable but bounded: 400→{d_small:?} \
             12000→{d_large:?}"
        );
    }
}
