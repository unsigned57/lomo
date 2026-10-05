// adversarial-audit: a Refresh intent whose anchors vanished re-reads the
// ENTIRE query result instead of staying inside the loaded window.
//
// Claim under test: `PageIntent::Refresh { anchors, known }` bounds a
// mutation/reconcile re-query to the previously loaded window (`covers`
// requires every anchor present; a deleted anchor makes `covers` unreachable
// and the loop drains every remaining page).

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, feed, run_effect};
    use lomo_tui::{
        effects::{FeedRequest, PageIntent, RuntimeMessage},
        event::Command,
        model::{AppModel, FeedKind, FeedQuery},
    };
    use lomo_workspace::MemoId;

    /// An external change that deletes the selected memo is exactly when reconcile fires
    /// `refresh_current` → `PageIntent::Refresh`. With the anchor gone, `covers` can never
    /// be satisfied, so the page loop keeps pulling until the cursor is exhausted —
    /// the whole library, not the loaded window, on a single worker.
    #[test]
    fn refresh_with_a_vanished_anchor_stays_bounded_by_the_loaded_window() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(120).expect("seeded workspace");
        let request = FeedRequest {
            req: lomo_tui::model::Req(1),
            kind: FeedKind::Timeline,
            query: FeedQuery::default(),
            intent: PageIntent::Refresh {
                anchors: vec![MemoId::parse("memo-deleted-externally").expect("id")],
                known: (0..48)
                    .map(|index| MemoId::parse(&format!("memo-loaded-{index}")).expect("id"))
                    .collect(),
            },
        };
        let RuntimeMessage::Page { cards, .. } =
            lomo_tui::queries::query_feed(&fixture.runtime, &request).expect("page reply")
        else {
            panic!("feed queries reply with a page");
        };
        assert!(
            cards.len() <= 96,
            "refresh must stop at the loaded window when its anchor vanished; \
             it re-read {} cards — the whole query result",
            cards.len()
        );
    }

    /// A refresh on a *search* feed must re-read the anchored neighborhood, not
    /// the ranked head: score order still resolves a memo identity to its rank,
    /// so the window can start at the anchor like any other query. A head-read
    /// window observes the wrong slice — a new hit ranking beside the anchor
    /// never enters it. Both engines must carry the anchor.
    #[test]
    fn search_refresh_rereads_the_anchored_neighborhood() {
        for fuzzy in [false, true] {
            let fixture = RuntimeFixture::new().expect("fixture");
            fixture.seed(200).expect("seeded workspace");
            let mut model =
                lomo_tui::ops::bootstrap_model(&fixture.runtime, AppModel::new(100, 30))
                    .expect("bootstrap");
            command(&fixture.runtime, &mut model, Command::Search).expect("search mode");
            command(
                &fixture.runtime,
                &mut model,
                Command::Type("needle".to_owned()),
            )
            .expect("type");
            if fuzzy {
                command(&fixture.runtime, &mut model, Command::ToggleSearchMode)
                    .expect("toggle to fuzzy");
            }
            command(&fixture.runtime, &mut model, Command::Accept).expect("accept query");
            while feed(&model).expect("feed").next_cursor.is_some() {
                command(&fixture.runtime, &mut model, Command::Last).expect("page deeper");
            }
            assert_eq!(feed(&model).expect("feed").memos.len(), 200);
            // Pin the reading position on the LAST hit — rank 199 of the
            // created_at-descending order, the seeded file's oldest memo.
            command(&fixture.runtime, &mut model, Command::First).expect("first");
            command(&fixture.runtime, &mut model, Command::Move(199)).expect("move to tail");
            let anchor = feed(&model)
                .expect("feed")
                .anchor
                .clone()
                .expect("anchor")
                .id;
            // An external append lands a hit one rank below the anchor — only
            // a window that starts at the anchor (not the ranked head) can
            // contain it.
            let path = fixture.runtime.workspace.join("2026_09_11.md");
            let mut source = std::fs::read_to_string(&path).expect("read");
            source.push_str("- 09:00:00\nneedle tail BEYOND_ANCHOR 八达岭长城 #reading/book\n\n");
            std::fs::write(&path, source).expect("write");
            fixture
                .runtime
                .session
                .rebuild_projection()
                .expect("rebuild");
            let effect = lomo_tui::update::reload_feed(&mut model);
            run_effect(&fixture.runtime, &mut model, effect).expect("refresh");
            let feed = feed(&model).expect("feed");
            let anchor_at = feed
                .memos
                .iter()
                .position(|memo| memo.id == anchor)
                .expect("the anchor still matches the query — it must survive");
            let marker_at = feed
                .memos
                .iter()
                .position(|memo| memo.summary.contains("BEYOND_ANCHOR"));
            assert_eq!(
                marker_at,
                Some(anchor_at + 1),
                "fuzzy={fuzzy}: a refresh anchored on the last hit must re-read \
                 ITS neighborhood — the hit ranking one below the anchor \
                 belongs right after it (anchor at {anchor_at})"
            );
            assert_eq!(
                feed.total,
                Some(201),
                "fuzzy={fuzzy}: the anchored window reports the true total"
            );
        }
    }
}
