//! Adversarial feed-window probes: an evidence-driven refresh must keep the
//! loaded list a consistent ordered slice and a usable pagination frontier.
//! Merged from the numbered re-audit rounds.

#[cfg(test)]
pub mod support;

#[cfg(test)]
mod tests {

    // Behavior Contract
    // Capability (I4 second-round re-audit): the evidence-driven refresh merge must
    // keep the loaded list a *consistent slice of the live query order* — not merely
    // a consistent membership set — and the pagination frontier it preserves must
    // stay usable on the very next page request.
    //
    // Scenarios (per probe):
    // - The reply head itself jumped rank (an out-of-window pin promotion): the
    //   merge replays the reply's live `order` evidence — the promoted card
    //   leads the merged list at its true rank, and the order stays converged
    //   on every later refresh.
    // - The kept-tail frontier must be *re-minted* under the reply's revision:
    //   a retained `next_cursor` minted before the refresh-triggering write is
    //   dead on arrival (`stale_cursor`), so the reply carries a frontier bound
    //   at the surviving tail's live position — and `Last` keeps paging.
    //   The consumed-tail control arm shows the same sequence staying usable.
    // - A bounded-restore window refresh whose lookbehind page overshoots the
    //   loaded top must still keep every live card below the reply window —
    //   the `order` evidence names the survivors; the reply head's absence
    //   from the loaded list is a lookbehind artifact, not a replace-all cue.
    // - A search refresh whose anchor left the hit set drains only the bounded
    //   window and names the departure by omitting it from `order` — both
    //   engines, live session.
    // - `search()` rejects a request carrying both a cursor and an anchor before
    //   touching the epoch, and an anchor that *moved* (unpin) is re-covered by
    //   identity inside the refresh window — the self-healing side of the boundary.
    //
    // Observable outcomes: `feed.memos` id order vs. the store's own `Head` order,
    // `feed.next_cursor` revision vs. the live `high_water_revision`, `Command::Last`
    // result diagnostics, `load`/`selected`/`total` after merges.
    //
    // Excludes: projection-side correctness (`reconcile_parity_contract`), executor lanes,
    // render geometry.
    //
    // TDD proof: probes assert the correct invariant; three remain RED as residual
    // defect evidence for `audit/11-再复审-数据路径修复.md`.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "Adversarial fixtures must be constructed successfully before probing"
    )]
    mod ordered_slice {
        use crate::support::{RuntimeFixture, command, feed, run_effect};
        use lomo_application::{
            MemoQuery, MemoQueryStart, MemoSort, PinMemoRequest, PinPolicy, SearchMode,
            SearchRequest,
        };
        use lomo_core::{OperationId, PageSize};
        use lomo_tui::{
            event::Command,
            model::{AppModel, FeedState, LoadStatus},
            update,
        };
        use lomo_workspace::MemoId;

        // ——— fixture helpers ————————————————————————————————————

        /// The store's own `Head` order for the unfiltered timeline — the rank truth
        /// the loaded feed's order must stay a subsequence of.
        fn timeline_order(runtime: &lomo_tui::ops::TuiRuntime, count: usize) -> Vec<String> {
            let query = MemoQuery {
                search_text: None,
                filters: lomo_application::MemoFilters::default(),
                sort: MemoSort::default(),
            };
            let page = runtime
                .session
                .query_memos_starting_at(
                    &query,
                    None,
                    MemoQueryStart::Head,
                    PageSize::new(u32::try_from(count).expect("page size")).expect("page size"),
                )
                .expect("head order");
            page.items
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        fn ids(feed: &FeedState) -> Vec<String> {
            feed.memos
                .iter()
                .map(|memo| memo.id.as_str().to_owned())
                .collect()
        }

        fn pin(runtime: &lomo_tui::ops::TuiRuntime, op: &str, id: &MemoId, on: bool) {
            runtime
                .session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(op).expect("op"),
                        id.clone(),
                        if on {
                            PinPolicy::Pinned { at_ms: None }
                        } else {
                            PinPolicy::Unpinned
                        },
                    )
                    .expect("pin request"),
                )
                .expect("pin commit");
        }

        /// Fully page a feed to the query end — the deep-loaded precondition.
        fn page_to_end(runtime: &lomo_tui::ops::TuiRuntime, model: &mut AppModel) {
            while feed(model).expect("feed").next_cursor.is_some() {
                command(runtime, model, Command::Last).expect("page deeper");
            }
        }

        // ——— rank-moving reply heads vs the identity splice ————————————

        /// The refresh reply's own order is authoritative — `cards` is the true
        /// rank-0..49 slice after the pin promotion, and `order` places it ahead
        /// of every loaded survivor. The merge replays that live-rank evidence
        /// instead of anchoring the reply at its head's stale slot: the promoted
        /// card leads at position 0, the prefix it jumped over lands below it,
        /// and the merged list equals the store order restricted to the loaded
        /// set — a fresh `Head` read is the oracle.
        #[test]
        fn a_rank_jumping_reply_head_is_spliced_at_its_live_rank() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(200).expect("seed");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            command(&fixture.runtime, &mut model, Command::Last).expect("page two");
            command(&fixture.runtime, &mut model, Command::First).expect("back to head");
            assert_eq!(
                feed(&model).expect("feed").memos.len(),
                96,
                "two pages loaded"
            );

            // The rank-60 card is promoted to rank 0 — the refresh reply *does*
            // contain it (the lookbehind page catches the new head), so the merge
            // has full evidence of the new neighborhood.
            let promoted = feed(&model)
                .expect("feed")
                .memos
                .get(60)
                .expect("rank-60 card")
                .id
                .clone();
            pin(&fixture.runtime, "pin-promotion", &promoted, true);

            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");

            // The honest merge: the store's live order restricted to the loaded set.
            // Membership unchanged (96 ids), so this is just the top-96 minus the
            // one id the loaded set never contained — computed by filtering.
            let loaded: std::collections::BTreeSet<String> =
                ids(feed(&model).expect("feed")).into_iter().collect();
            let expected: Vec<String> = timeline_order(&fixture.runtime, 200)
                .into_iter()
                .filter(|id| loaded.contains(id))
                .collect();
            let actual = ids(feed(&model).expect("feed"));
            let landed = actual
                .iter()
                .position(|id| id == promoted.as_str())
                .expect("the promoted card survives once");
            assert_eq!(
                landed, 0,
                "the rank-0 pinned memo must lead the merged list — it landed at \
                 position {landed}, spliced at its stale slot (order: \
                 {actual:?})"
            );
            assert_eq!(
                actual, expected,
                "the merged order must equal the store order restricted to the loaded set"
            );

            // The defect is stable: the same refresh re-anchors the reply at the
            // promoted card's *new* stale position and reproduces the inversion.
            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("second refresh");
            assert_eq!(
                ids(feed(&model).expect("feed")),
                expected,
                "a second refresh must converge to the store order"
            );
        }

        /// The retained tail means the feed's frontier cannot come from the reply
        /// window's edge — but it also cannot be the pre-refresh cursor, which is
        /// pinned to the revision the refresh-triggering write superseded. The
        /// reply's `next` therefore mints at the surviving tail's own live
        /// position: `Last` keeps paging on a live cursor instead of hard-failing
        /// `stale_cursor` on a dead one.
        #[test]
        fn a_kept_tail_re_mints_the_frontier_at_the_live_revision() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(150).expect("seed");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            command(&fixture.runtime, &mut model, Command::Last).expect("page two");
            command(&fixture.runtime, &mut model, Command::First).expect("back to head");
            assert_eq!(
                feed(&model).expect("feed").memos.len(),
                96,
                "two pages loaded — the refresh reply covers only the first"
            );
            let frontier = feed(&model)
                .expect("feed")
                .next_cursor
                .clone()
                .expect("pagination frontier");

            // Any projection write — here an append to the day file — moves the
            // high-water revision and is exactly what fires the reconcile refresh.
            let path = fixture.runtime.workspace.join("2026_09_11.md");
            let mut source = std::fs::read_to_string(&path).expect("read");
            source.push_str("- 09:00:00\nneedle tail 八达岭长城 #reading/book\n\n");
            std::fs::write(&path, source).expect("write");
            fixture
                .runtime
                .session
                .rebuild_projection()
                .expect("rebuild");

            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");
            let feed_state = feed(&model).expect("feed");
            assert_eq!(feed_state.memos.len(), 96, "the tail survived the merge");
            let kept = feed_state.next_cursor.clone().expect("frontier kept");
            assert_ne!(
                kept.high_water_revision, frontier.high_water_revision,
                "the frontier is re-minted under the live revision — the pre-write \
                 cursor is dead and must never be retained"
            );

            // The next page request must work: the re-minted cursor is valid. A
            // dead cursor would wedge pagination — every retry hitting the same
            // `stale_cursor` with no refresh healing it.
            let first = command(&fixture.runtime, &mut model, Command::Last);
            assert!(
                first.is_ok(),
                "post-refresh paging must not hard-fail on the kept cursor: {first:?}"
            );
            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("second refresh");
            let second = command(&fixture.runtime, &mut model, Command::Last);
            assert!(
                second.is_ok(),
                "the wedge persists across refreshes — the dead frontier is kept \
                 forever: {second:?}"
            );
        }

        /// Control arm: when the refresh reply covers the *whole* loaded list the
        /// tail is empty, the reply's own `next` (minted at the new revision) is
        /// adopted, and the same write→refresh→page sequence stays usable —
        /// frontier freshness holds on both sides of the kept-tail boundary.
        #[test]
        fn a_consumed_tail_adopts_the_replys_fresh_frontier() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(150).expect("seed");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            assert_eq!(
                feed(&model).expect("feed").memos.len(),
                48,
                "one page loaded — a refresh window covers it all"
            );

            let path = fixture.runtime.workspace.join("2026_09_11.md");
            let mut source = std::fs::read_to_string(&path).expect("read");
            source.push_str("- 09:00:00\nneedle tail 八达岭长城 #reading/book\n\n");
            std::fs::write(&path, source).expect("write");
            fixture
                .runtime
                .session
                .rebuild_projection()
                .expect("rebuild");

            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");
            command(&fixture.runtime, &mut model, Command::Last)
                .expect("the adopted cursor is fresh — paging continues");
            assert_eq!(
                feed(&model).expect("feed").memos.len(),
                96,
                "the next page appended"
            );
        }

        /// A refresh whose lookbehind page overshoots the loaded top still owes
        /// the loaded tail its survival evidence: `order` names every loaded id
        /// the query kept, ranked by where it sorts against the reply window —
        /// so the 48 survivors below the window land in `below` and stay loaded
        /// even though the reply's head card was never in `feed.memos`.
        #[test]
        fn a_restored_window_refresh_keeps_the_live_tail() {
            let fixture = RuntimeFixture::new().expect("fixture");
            // Day 2 (150 newer memos, ranks 0..149) on top of day 1 (150, ranks
            // 150..299) — a filter→restore round-trip lands a day-1 window mid-list.
            fixture.seed(150).expect("seed day 1");
            {
                use std::fmt::Write;
                let mut text = String::new();
                for index in 0..150 {
                    write!(
                        text,
                        "- 11:{:02}:{:02}\nneedle {index} 八达岭长城 #reading/book\n\n",
                        index / 60,
                        index % 60
                    )
                    .expect("doc text");
                }
                std::fs::write(fixture.runtime.workspace.join("2026_09_12.md"), text)
                    .expect("seed day 2");
            }
            fixture
                .runtime
                .session
                .rebuild_projection()
                .expect("rebuild");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            page_to_end(&fixture.runtime, &mut model);
            assert_eq!(feed(&model).expect("feed").memos.len(), 300);

            // Park the reading position deep inside day 1, then suspend it behind a
            // day-2 filter and restore — the resumed feed is the bounded window
            // [anchor−48, anchor+48) PLUS the 48 still-live day-2 cards the
            // filtered session had loaded: the `order` evidence keeps them above
            // the window instead of discarding them.
            command(&fixture.runtime, &mut model, Command::First).expect("head");
            command(&fixture.runtime, &mut model, Command::Move(200)).expect("read mid-list");
            command(
                &fixture.runtime,
                &mut model,
                Command::SetDate("2026-09-12".to_owned()),
            )
            .expect("filter to day 2");
            command(&fixture.runtime, &mut model, Command::Back).expect("restore");
            let loaded = ids(feed(&model).expect("feed"));
            assert_eq!(
                loaded.len(),
                144,
                "the restore lands the bounded window plus the still-live cards \
                 the filtered session had loaded, not the old list"
            );

            // Park the selection on the loaded tail, then reconcile: the refresh
            // window sits at ranks 199..295 — its head is absent from the loaded
            // list entirely, and the `order` evidence *above* the window is what
            // keeps all 144 live loaded cards from being silently unloaded.
            command(&fixture.runtime, &mut model, Command::Move(143)).expect("window tail");
            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");

            let after = ids(feed(&model).expect("feed"));
            let missing: Vec<String> = loaded
                .iter()
                .filter(|id| !after.iter().any(|kept| kept == *id))
                .cloned()
                .collect();
            assert!(
                missing.is_empty(),
                "a refresh must never silently unload live cards — {} of {} loaded \
                 cards vanished (the reply head's absence is a lookbehind artifact, \
                 not evidence the tail left the query): {:?}…",
                missing.len(),
                loaded.len(),
                missing.iter().take(4).collect::<Vec<_>>()
            );
        }

        // ——— search-path anchoring ————————————————————————————————————

        /// A search refresh whose anchor left the hit set resolves `AtMemo` to the
        /// head fallback (fuzzy: `positions` miss → start 0), `covers` stays
        /// unreachable, and the loop must still stop at the bounded window while
        /// `order` omits the departed card — membership honest, bounded cost,
        /// selection repaired to a survivor.
        #[test]
        fn search_refresh_with_a_dead_anchor_stays_bounded() {
            for fuzzy in [false, true] {
                let fixture = RuntimeFixture::new().expect("fixture");
                fixture.seed(200).expect("seed");
                let mut model =
                    lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                        .expect("bootstrap");
                command(&fixture.runtime, &mut model, Command::Search).expect("search");
                command(
                    &fixture.runtime,
                    &mut model,
                    Command::Type("needle".to_owned()),
                )
                .expect("type");
                if fuzzy {
                    command(&fixture.runtime, &mut model, Command::ToggleSearchMode)
                        .expect("fuzzy mode");
                }
                command(&fixture.runtime, &mut model, Command::Accept).expect("accept");
                page_to_end(&fixture.runtime, &mut model);
                assert_eq!(feed(&model).expect("feed").memos.len(), 200);

                // The selected card is the refresh anchor — trash it so the identity
                // leaves the hit set entirely (a document rewrite mid-file would hit
                // the span-sensitive identity-ambiguity path; the commit path is the
                // clean departure).
                command(&fixture.runtime, &mut model, Command::First).expect("head");
                command(&fixture.runtime, &mut model, Command::Move(100)).expect("mid-list");
                let victim = feed(&model)
                    .expect("feed")
                    .selected
                    .clone()
                    .expect("a selection exists");
                let victim_card = feed(&model)
                    .expect("feed")
                    .memos
                    .iter()
                    .find(|memo| memo.id == victim)
                    .expect("the selected card");
                fixture
                    .runtime
                    .session
                    .delete_memo(lomo_application::DeleteMemoRequest {
                        operation_id: OperationId::parse("dead-anchor-delete").expect("op"),
                        memo_id: victim.clone(),
                        expected_document_fingerprint: victim_card.fingerprint.clone(),
                        trashed_at_ms: None,
                    })
                    .expect("trash the anchor memo");

                let effect = update::reload_feed(&mut model);
                run_effect(&fixture.runtime, &mut model, effect).expect("refresh");

                let feed_state = feed(&model).expect("feed");
                assert!(
                    matches!(feed_state.load, LoadStatus::Ready),
                    "fuzzy={fuzzy}: the bounded window lands Ready, not wedged"
                );
                assert_eq!(
                    feed_state.memos.len(),
                    199,
                    "fuzzy={fuzzy}: exactly the departed card left — `order` omitted \
                     it, nothing else was dropped"
                );
                assert!(
                    !feed_state.memos.iter().any(|memo| memo.id == victim),
                    "fuzzy={fuzzy}: the dead anchor's card is gone"
                );
                assert!(
                    feed_state
                        .selected
                        .as_ref()
                        .is_some_and(|id| feed_state.memos.iter().any(|memo| &memo.id == id)),
                    "fuzzy={fuzzy}: selection repaired onto a surviving neighbour"
                );
                assert_eq!(
                    feed_state.total,
                    Some(199),
                    "fuzzy={fuzzy}: the refreshed total is the store's, not the window's"
                );
            }
        }

        /// `SearchRequest.anchor` and `cursor` are mutually exclusive starts — the
        /// rejection must fire *before* the epoch check so an ambiguous request can
        /// never steal a live query's epoch by accident.
        #[test]
        fn search_rejects_a_cursor_and_an_anchor_in_one_request() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(80).expect("seed");
            let query = MemoQuery {
                search_text: None,
                filters: lomo_application::MemoFilters::default(),
                sort: MemoSort::default(),
            };
            let cursor = fixture
                .runtime
                .session
                .query_memos_starting_at(
                    &query,
                    None,
                    MemoQueryStart::Head,
                    PageSize::new(48).expect("page size"),
                )
                .expect("head page")
                .next_cursor
                .expect("a live cursor");
            let anchor = fixture
                .runtime
                .session
                .query_memos_starting_at(
                    &query,
                    None,
                    MemoQueryStart::Head,
                    PageSize::new(1).expect("page size"),
                )
                .expect("head page")
                .items
                .first()
                .expect("a hit")
                .memo_id
                .clone();
            let error = fixture
                .runtime
                .session
                .search(&SearchRequest {
                    query_epoch: 0,
                    mode: SearchMode::Fulltext,
                    text: "needle".to_owned(),
                    filters: lomo_application::MemoFilters::default(),
                    cursor: Some(cursor),
                    anchor: Some(anchor),
                    page_size: PageSize::new(48).expect("page size"),
                })
                .expect_err("cursor+anchor in one request is an ambiguous start");
            assert_eq!(
                error.code(),
                "ambiguous_search_start",
                "the rejection names the violated invariant"
            );
        }

        /// The self-healing side of the boundary: the refresh anchor is an
        /// *identity*, so a memo that itself moved rank is re-covered — `AtMemo`
        /// resolves its new rank, the reply re-reads its new neighborhood, and the
        /// merge drops the stale card in favour of the fresh one at its true slot.
        /// Unpinning the anchored head card must land it exactly at its natural
        /// rank with a cleared badge — one hop, no residue.
        #[test]
        fn a_moved_anchor_is_re_covered_by_identity_inside_the_window() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(200).expect("seed");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            command(&fixture.runtime, &mut model, Command::Last).expect("page two");

            // Pin the rank-60 card, then fully re-query so the feed is clean:
            // [pinned X, rank0..rank94] — X leads with the pin badge.
            let promoted = feed(&model)
                .expect("feed")
                .memos
                .get(60)
                .expect("rank-60 card")
                .id
                .clone();
            pin(&fixture.runtime, "pin-up", &promoted, true);
            let effect = update::requery(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("requery");
            command(&fixture.runtime, &mut model, Command::Last).expect("page two");
            command(&fixture.runtime, &mut model, Command::First).expect("head");
            {
                let feed_state = feed(&model).expect("feed");
                assert_eq!(feed_state.memos.len(), 96);
                assert_eq!(
                    feed_state.memos.first().map(|memo| memo.id.as_str()),
                    Some(promoted.as_str()),
                    "the pinned card leads"
                );
                assert!(
                    feed_state.memos.first().is_some_and(|memo| memo.pinned),
                    "the pin badge renders"
                );
            }

            // Unpin it while it anchors the refresh: the window follows the
            // identity to its new rank (~60), so the reply *does* name the moved
            // card — the merge has full evidence and must place it exactly there.
            pin(&fixture.runtime, "pin-down", &promoted, false);
            let effect = update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");

            let expected = timeline_order(&fixture.runtime, 108);
            let feed_state = feed(&model).expect("feed");
            let actual = ids(feed_state);
            let at = actual
                .iter()
                .position(|id| id == promoted.as_str())
                .expect("the moved card survives once");
            assert_eq!(
                at, 60,
                "the unpinned card lands at its natural rank — the window followed \
                 the identity"
            );
            assert!(
                !feed_state
                    .memos
                    .iter()
                    .find(|memo| memo.id == promoted)
                    .expect("the card")
                    .pinned,
                "the fresh card carries the cleared pin badge"
            );
            assert_eq!(
                actual, expected,
                "an in-window move leaves the merged order equal to the store's"
            );
        }
    }

    // Behavior Contract
    // Capability (I4 third-round re-audit): the ordered-evidence refresh protocol
    // must hold at the seams the second suite did not reach — request construction
    // (`PageIntent::Refresh { anchors, known }` is a point-in-time membership
    // snapshot), receipt gating (a superseded or foreign reply can never land on a
    // feed still awaiting its own request), and `merge_window` replay tolerance
    // (`order` is replayed verbatim; duplicates, phantoms and cross-segment drift
    // must degrade to evidence replay, never corruption).
    //
    // Scenarios (per probe):
    // - `reload_feed` must package every loaded id into `known` and the anchor
    //   then the selection into `anchors`; an empty feed stays `Initial`.
    // - Two reloads issued back-to-back: the first request is cancelled; a reply
    //   fabricated in its name — even one claiming every loaded card left — must
    //   never touch `feed.memos`, and the feed keeps awaiting the live request.
    // - A fabricated `order` containing a duplicate id, a phantom id, and a
    //   survivor re-ranked across the reply window: the merge replays the
    //   evidence verbatim — first occurrence wins, phantoms produce nothing, a
    //   reply card absent from `order` is dropped — and the result is a clean
    //   id sequence, never a panic or a duplicated card.
    // - A `Bodies` receipt landing between refresh issuance and the page reply
    //   (the only identity-preserving mid-flight mutation the runtime admits)
    //   must hydrate survivors without changing what `known` provably covered —
    //   the merge replays the reply's order over the current, hydrated list.
    // - `MemoQueryStart::AtMemo` on an identity that has left the query resolves
    //   to head — the documented head-fallback every `AtMemo` consumer inherits,
    //   including the refresh tail re-mint (`queries.rs:214-216`, probed here at
    //   the session boundary where the semantics are defined).
    //
    // Observable outcomes: `feed.memos` id order, `feed.selected`/`anchor`
    // continuity, `feed.next_cursor`/`total`/`load`, `feed.pending_page`
    // ownership, and the store's own `Head` order for the session-level probe.
    //
    // Excludes: projection-side correctness (`reconcile_trash_lane_contract`), executor
    // lanes, render geometry.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "Adversarial fixtures must be constructed successfully before probing"
    )]
    mod receipt_gating {
        use crate::support::{
            RuntimeFixture, body_reply, feed, memo, model_with_memos, pending_page,
        };
        use lomo_application::{MemoQuery, MemoQueryStart, MemoSort};
        use lomo_core::{OperationId, PageSize};
        use lomo_tui::{
            effects::RuntimeMessage,
            event::Command,
            messages::apply_message,
            model::{AppModel, BodyState, FeedState, PendingKind, Req},
            update,
        };
        use lomo_workspace::MemoId;

        // ——— fixture helpers ————————————————————————————————————

        fn ids(feed: &FeedState) -> Vec<String> {
            feed.memos
                .iter()
                .map(|memo| memo.id.as_str().to_owned())
                .collect()
        }

        fn id(raw: &str) -> MemoId {
            MemoId::parse(raw).expect("memo id")
        }

        /// The `req` a just-issued `Effect::Query` owns — the pending request the
        /// feed awaits and the receipt identity `pending.claim` will consult.
        fn issued_req(effect: Option<lomo_tui::effects::Effect>) -> Req {
            match effect {
                Some(lomo_tui::effects::Effect::Query(request)) => request.req,
                other => panic!("expected a page query effect, got {other:?}"),
            }
        }

        /// A fabricated non-append page reply — the message shape `query_feed`
        /// emits for a refresh.
        fn page_reply(
            req: Req,
            cards: Vec<lomo_tui::model::MemoCard>,
            order: Vec<MemoId>,
        ) -> RuntimeMessage {
            let total = u64::try_from(order.len()).expect("total");
            RuntimeMessage::Page {
                req,
                append: false,
                cards,
                next: None,
                order,
                total: Some(total),
            }
        }

        // ——— request construction ———————————————————————————————

        /// The refresh intent is the merge protocol's membership baseline: `known`
        /// must carry every loaded id and `anchors` the visual anchor then the
        /// selection — a request that forgot either evidence class re-opens the
        /// 11-F-03 silent-unload hole.
        #[test]
        fn the_refresh_intent_snapshots_anchors_and_every_loaded_id() {
            let mut model = model_with_memos(96, 100, 30).expect("model");
            let feed_state = feed(&model).expect("feed");
            let selected = feed_state.selected.clone().expect("selection");
            let anchor = feed_state.anchor.clone().expect("reading anchor").id;
            let loaded = ids(feed_state);

            let effect = update::reload_feed(&mut model).expect("refresh effect");
            let Some(lomo_tui::effects::Effect::Query(request)) = Some(effect) else {
                panic!("a populated feed issues a page query");
            };
            let lomo_tui::effects::PageIntent::Refresh { anchors, known } = request.intent else {
                panic!("a populated feed reloads through PageIntent::Refresh");
            };
            assert_eq!(
                anchors,
                vec![anchor, selected],
                "anchors are the reading anchor first, then the selection — the \
                 order `covers` resolves them in"
            );
            let known_set: std::collections::BTreeSet<&MemoId> = known.iter().collect();
            assert_eq!(known.len(), loaded.len(), "known carries no duplicates");
            assert!(
                loaded
                    .iter()
                    .all(|raw| known_set.contains(&id(raw.as_str()))),
                "every loaded id must reach the reply's order evidence"
            );
        }

        /// The degenerate boundary: an empty feed can refresh only as `Initial` —
        /// there is no membership to evidence.
        #[test]
        fn an_empty_feed_reloads_as_initial() {
            let mut model = AppModel::new(100, 30);
            let effect = update::reload_feed(&mut model).expect("reload effect");
            let Some(lomo_tui::effects::Effect::Query(request)) = Some(effect) else {
                panic!("a feed issues a page query");
            };
            assert!(
                matches!(request.intent, lomo_tui::effects::PageIntent::Initial),
                "an empty feed issues Initial, not a Refresh with empty evidence"
            );
        }

        // ——— receipt gating: superseded replies never land ——————————

        /// The merge replays whatever `order` a reply carries — so the identity
        /// gate in front of it is load-bearing. A reply minted for a request the
        /// feed already replaced must settle without touching a single card:
        /// `pending.claim` rejects it and `degrade` swallows it silently.
        #[test]
        fn a_reply_for_a_superseded_request_never_lands() {
            let mut model = model_with_memos(96, 100, 30).expect("model");
            let original = ids(feed(&model).expect("feed"));

            // A reconcile fires one refresh; a second reconcile (another write
            // raced in) replaces it — the first request is already cancelled.
            let stale_req = issued_req(update::reload_feed(&mut model));
            let live_req = issued_req(update::reload_feed(&mut model));
            assert_ne!(stale_req, live_req, "each issuance mints a fresh request");

            // The stale reply claims total evacuation: every loaded card left.
            drop(apply_message(
                &mut model,
                page_reply(stale_req, Vec::new(), Vec::new()),
            ));

            let feed_state = feed(&model).expect("feed");
            assert_eq!(
                ids(feed_state),
                original,
                "a superseded reply's order evidence must never be replayed"
            );
            assert_eq!(
                feed_state.pending_page,
                Some(live_req),
                "the feed still awaits only the live request"
            );
        }

        // ——— merge_window replay tolerance —————————————————————————

        /// `merge_window` replays `order` verbatim over `replies ∪ resident`: a
        /// duplicated id lands once at its first occurrence, a phantom id produces
        /// no card, a reply card absent from `order` is dropped, and a survivor
        /// re-ranked across the reply window lands where the evidence puts it —
        /// not where it was loaded. The merged list is exactly the deduplicated
        /// evidence sequence.
        #[test]
        fn order_replay_dedupes_phantoms_and_repositions_verbatim() {
            let mut model = model_with_memos(4, 100, 30).expect("model");
            let req = pending_page(&mut model).expect("pending page");

            // The reply carries one fresh card; `order` places a loaded survivor
            // above it, repeats it, names a phantom, then names another survivor.
            // Loaded memo-2 is absent entirely — the only eviction evidence.
            let fresh = memo("memo-9", "reply card").expect("card");
            let unused = memo("memo-8", "not ordered").expect("card");
            drop(apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req,
                    append: false,
                    cards: vec![fresh, unused],
                    next: None,
                    order: vec![
                        id("memo-1"),
                        id("memo-9"),
                        id("memo-1"),
                        id("memo-phantom"),
                        id("memo-3"),
                    ],
                    total: Some(3),
                },
            ));

            let feed_state = feed(&model).expect("feed");
            assert_eq!(
                ids(feed_state),
                vec!["memo-1", "memo-9", "memo-3"],
                "the merge must be exactly the deduplicated replay of `order`: \
                 phantom ids and unordered reply cards contribute nothing"
            );
            assert!(
                feed_state
                    .memos
                    .iter()
                    .all(|card| card.id.as_str() != "memo-2"),
                "the loaded card absent from `order` is the eviction evidence"
            );
            assert!(
                matches!(feed_state.load, lomo_tui::model::LoadStatus::Ready),
                "a landed reply leaves the feed Ready"
            );
            // The departed selection repairs to the nearest surviving neighbor.
            assert_eq!(
                feed_state.selected.as_ref().map(MemoId::as_str),
                Some("memo-1"),
                "the departed first card's selection repairs to a survivor"
            );
        }

        // ——— mid-flight contamination between issuance and landing ————

        /// Between refresh issuance and reply landing the only reachable
        /// membership-preserving mutation is body hydration (page replies to
        /// superseded requests degrade; commits/issue re-snapshot `known` by
        /// re-issuing). A `Bodies` receipt landing mid-flight must hydrate in
        /// place and survive the merge — the reply's `order` evidence is replayed
        /// over the hydrated list, not over the issuance-time one.
        #[test]
        fn a_bodies_reply_mid_refresh_hydrates_survivors_without_disturbing_order() {
            let mut model = model_with_memos(4, 100, 30).expect("model");
            // The target card starts pending so the mid-flight hydration is real.
            if let lomo_tui::model::View::Feed(feed_state) = &mut model.view {
                let card = feed_state
                    .memos
                    .iter_mut()
                    .find(|card| card.id.as_str() == "memo-2")
                    .expect("memo-2");
                card.body = BodyState::Pending;
            }

            let page_req = pending_page(&mut model).expect("refresh pending");
            let bodies_req = model.request(PendingKind::Bodies);
            drop(apply_message(
                &mut model,
                RuntimeMessage::Bodies {
                    req: bodies_req,
                    bodies: vec![
                        body_reply(memo("memo-2", "hydrated body").expect("card"))
                            .expect("body reply"),
                    ],
                },
            ));
            assert!(
                feed(&model)
                    .expect("feed")
                    .memos
                    .iter()
                    .any(|card| card.id.as_str() == "memo-2"
                        && matches!(card.body, BodyState::Ready(_))),
                "the mid-flight hydration landed"
            );

            // The refresh reply now lands: order re-ranks memo-2 first.
            drop(apply_message(
                &mut model,
                page_reply(page_req, Vec::new(), vec![id("memo-2"), id("memo-0")]),
            ));
            let feed_state = feed(&model).expect("feed");
            assert_eq!(ids(feed_state), vec!["memo-2", "memo-0"]);
            assert!(
                matches!(
                    feed_state
                        .memos
                        .first()
                        .map(|card| &card.body)
                        .expect("head"),
                    BodyState::Ready(_)
                ),
                "the hydrated survivor keeps its resident body through the merge"
            );
        }

        /// Navigation between issuance and landing mutates `selected`, never
        /// `memos`: the reply's evidence still names the departed selection among
        /// `order`'s survivors, so the user's mid-flight position survives the
        /// merge instead of being discarded with the request.
        #[test]
        fn navigation_mid_refresh_never_loses_the_users_new_position() {
            let mut model = model_with_memos(96, 100, 30).expect("model");
            let req = issued_req(update::reload_feed(&mut model));

            // The user moves while the refresh is in flight — selection is
            // model-local, no effect runs, `known` was already snapshotted.
            drop(update::apply_command(&mut model, Command::Move(50)));
            let moved = feed(&model)
                .expect("feed")
                .selected
                .clone()
                .expect("moved selection");

            // The reply's evidence names every still-live loaded id, including the
            // new position — replay must keep it selected.
            let order: Vec<MemoId> = feed(&model)
                .expect("feed")
                .memos
                .iter()
                .map(|card| card.id.clone())
                .collect();
            drop(apply_message(
                &mut model,
                page_reply(req, Vec::new(), order),
            ));

            assert_eq!(
                feed(&model).expect("feed").selected.as_ref(),
                Some(&moved),
                "a selection made while the refresh was in flight survives the merge"
            );
        }

        // ——— session-boundary mechanism probe ———————————————————————

        /// `AtMemo` resolves a departed identity to head — the semantic every
        /// `AtMemo` consumer inherits. The refresh tail re-mint
        /// (`queries.rs:214-216`) issues `AtMemo(below.last())` without verifying
        /// the boundary landed; if the tail departs between the sides query and
        /// the re-mint, the frontier silently rewinds to rank 0. That interleave
        /// needs a concurrent commit mid-`query_feed` and is not deterministically
        /// reachable from the harness — this probe locks the underlying semantics
        /// so the finding's mechanism is evidenced, not asserted.
        #[test]
        fn at_memo_on_a_departed_identity_resolves_to_head() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(10).expect("seed");
            let query = MemoQuery {
                search_text: None,
                filters: lomo_application::MemoFilters::default(),
                sort: MemoSort::default(),
            };
            let head_page = fixture
                .runtime
                .session
                .query_memos_starting_at(
                    &query,
                    None,
                    MemoQueryStart::Head,
                    PageSize::new(5).expect("ps"),
                )
                .expect("head page");
            let head = head_page.items.first().expect("head item").memo_id.clone();

            let gone = head_page.items.get(3).expect("a mid item").memo_id.clone();
            let snapshot = fixture
                .runtime
                .session
                .projected_memo(&gone)
                .expect("snapshot")
                .expect("projected");
            fixture
                .runtime
                .session
                .delete_memo(lomo_application::DeleteMemoRequest {
                    operation_id: OperationId::parse("probe-delete").expect("op"),
                    memo_id: MemoId::parse(&gone).expect("id"),
                    expected_document_fingerprint: snapshot.summary.file_fingerprint,
                    trashed_at_ms: None,
                })
                .expect("trash the probe memo");

            let page = fixture
                .runtime
                .session
                .query_memos_starting_at(
                    &query,
                    None,
                    MemoQueryStart::AtMemo(gone.as_str()),
                    PageSize::new(5).expect("ps"),
                )
                .expect("AtMemo resolves, never errors");
            assert_eq!(
                page.items.first().map(|item| item.memo_id.as_str()),
                Some(head.as_str()),
                "a departed identity falls back to head — the semantic the tail \
                 re-mint inherits without verification"
            );
            assert_eq!(page.items_before, 0, "head position");
        }
    }

    // Behavior Contract
    // Capability (I5 fourth-round re-audit): the refresh tail re-mint
    // (`queries.rs:214-228`) now verifies the boundary card before adopting the
    // minted frontier — `minted.cards.first().id == *tail` — falling back to the
    // reply's own frontier when the tail departed between the sides scan and the
    // re-mint checkout. The live-tail arm of that branch is exercisable
    // end-to-end through the real query path and is locked here: a refresh whose
    // `known` extends below the reply window must mint its frontier at the
    // deepest loaded survivor, so the next append continues below it — never
    // inside the refreshed window and never at rank 0.
    //
    // Scenarios:
    // - A refresh anchored at the live head with `known` covering the first 96
    //   ranks of a 120-memo store: the reply window covers the anchor on the
    //   first page, `below` carries ranks 48..96, and the re-minted `next` is
    //   the cursor below rank 95 — verified by driving `query_feed`'s real
    //   Refresh arm through `ops::execute` and then appending from the minted
    //   frontier, which must return rank 96 first.
    // - The `order` evidence must carry the below survivors verbatim after the
    //   window cards — the merge's replay input, unchanged by the re-mint fix.
    //
    // Observable outcomes: `RuntimeMessage::Page.next`, the appended page's first
    // card id, `order`'s tail segment.
    //
    // Excludes: projection-side correctness (`reconcile_trash_lane_contract`), the departed-
    // tail interleave (requires a mid-`query_feed` concurrent commit — not
    // deterministically reachable from this harness; the head-fallback semantic
    // itself stays locked by `feed_window_refresh_contract`).

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "Adversarial fixtures must be constructed successfully before probing"
    )]
    mod tail_remint {
        use crate::support::RuntimeFixture;
        use lomo_application::{MemoFilters, MemoQuery, MemoSort};
        use lomo_core::PageSize;
        use lomo_tui::{
            effects::{Effect, FeedRequest, PageIntent, RuntimeMessage},
            model::{FeedKind, FeedQuery, Req},
        };
        use lomo_workspace::MemoId;

        /// Live query order, all ids — the rank evidence `known` and `below`
        /// resolve against.
        fn live_order(fixture: &RuntimeFixture) -> Vec<MemoId> {
            let query = MemoQuery {
                search_text: None,
                filters: MemoFilters::default(),
                sort: MemoSort::default(),
            };
            let mut ids = Vec::new();
            let mut cursor = None;
            loop {
                let page = fixture
                    .runtime
                    .session
                    .query_memos_page(
                        &query,
                        None,
                        cursor.as_ref(),
                        PageSize::new(256).expect("ps"),
                    )
                    .expect("page");
                ids.extend(
                    page.items
                        .iter()
                        .map(|summary| MemoId::parse(&summary.memo_id).expect("id")),
                );
                match page.next_cursor {
                    Some(next) => cursor = Some(next),
                    None => break,
                }
            }
            ids
        }

        /// Drives `query_feed`'s real Refresh arm: the window covers the anchor on
        /// the first page, `below` keeps every loaded survivor under its tail
        /// bound, and the re-minted frontier must continue below the deepest one —
        /// the branch the round-4 verification added and must not regress.
        #[test]
        fn a_refresh_re_mints_the_frontier_below_the_deepest_loaded_survivor() {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(120).expect("seed");
            let ids = live_order(&fixture);
            assert_eq!(ids.len(), 120, "fixture sanity");

            let (results, _inbox) = std::sync::mpsc::sync_channel(256);
            let outbox = lomo_tui::executor::Outbox::new(results);
            let token = lomo_tui::model::CancelToken::live();

            let refresh = FeedRequest {
                req: Req(1),
                kind: FeedKind::Timeline,
                query: FeedQuery::default(),
                intent: PageIntent::Refresh {
                    anchors: vec![ids.first().expect("rank 0").clone()],
                    known: ids.get(..96).expect("96 ranks").to_vec(),
                },
            };
            let reply =
                lomo_tui::ops::execute(&fixture.runtime, &Effect::Query(refresh), &outbox, &token)
                    .expect("refresh reply");
            let RuntimeMessage::Page {
                cards, next, order, ..
            } = reply
            else {
                panic!("a refresh answers a page");
            };

            // The first page covers the anchor at rank 0; survivors below the
            // window's tail bound are ranks 48..96 — the tail mints at ids[95].
            assert_eq!(cards.len(), 48, "window bound");
            let tail = ids.get(95).expect("tail id");
            assert_eq!(
                order.last().map(MemoId::as_str),
                Some(tail.as_str()),
                "the order evidence ends on the deepest loaded survivor"
            );
            let frontier = next.expect("a live tail re-mints a frontier");

            // The minted frontier must continue below rank 95 — the live-tail arm
            // of the boundary check. Before the fix this was unconditional; after
            // it is adopted only when the first minted card is the tail itself.
            let append = FeedRequest {
                req: Req(2),
                kind: FeedKind::Timeline,
                query: FeedQuery::default(),
                intent: PageIntent::Append(frontier),
            };
            let reply =
                lomo_tui::ops::execute(&fixture.runtime, &Effect::Query(append), &outbox, &token)
                    .expect("append reply");
            let RuntimeMessage::Page {
                cards: continued, ..
            } = reply
            else {
                panic!("an append answers a page");
            };
            assert_eq!(
                continued.first().map(|card| card.id.as_str()),
                ids.get(96).map(MemoId::as_str),
                "the frontier continues below the deepest loaded survivor — \
                 never inside the window, never at rank 0"
            );
        }
    }
}
