// adversarial-audit: per-keypress and per-open work must stay inside the visible
// window; nothing below may scale with the number of LOADED cards, the total
// library size, or the byte volume of attachments.
//
// Claims under test (each maps to a 60fps / interactive budget):
//  * `feed_lines` must only lay out cards the viewport can show — it currently
//    rebuilds a `FeedLine` row for EVERY loaded memo on every keypress.
//  * `hydrate_visible` + `move_selection` + `draw_feed` each take a full
//    `feed_lines` pass, so one `j` costs ~3 O(loaded) rebuilds.
//  * `reader::page` re-wraps the entire body every frame.
//  * Fuzzy search re-collects every summary and re-scores every body on EACH
//    page/keystroke — paging through results is O(N) per page → O(N²).
//  * The Attachments screen re-parses up to 20 history revision bodies per memo.
//  * Session open / watcher reconcile re-read and SHA-256 every workspace byte.
//  * Sixel encode is O(w × h/6 × 216) with per-pixel re-quantization per color.
//  * The stats heatmap performs ~O(weeks × 7) jiff timezone resolutions per frame.
//  * Off-screen bodies are demoted to Pending, so scrolling back re-parses them.
//
// Budgets: a frame is ~16ms; an interactive keypress must stay well under it.
// Structural assertions (row counts, rescan evidence) are flaky-proof; the
// timing numbers embedded in the assertion messages document magnitude.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, memo, model_with_memos};
    use lomo_tui::{
        effects::Effect,
        event::Command,
        graphics,
        model::{CancelToken, InputMode, Picker, PickerKind, StatsView, View},
        navigation,
        update::apply_command,
    };
    use ratatui::{Terminal, backend::TestBackend, buffer::Buffer, layout::Rect};
    use ratatui_image::{Resize, ResizeEncodeRender};
    use std::time::{Duration, Instant};

    const FRAME_BUDGET: Duration = Duration::from_millis(16);

    fn draw_feed_frame(model: &lomo_tui::model::AppModel, width: u16, height: u16) -> Duration {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).expect("backend");
        let start = Instant::now();
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .expect("draw");
        start.elapsed()
    }

    /// One `j` keypress = `apply_command(Move)` (`ensure_selected_visible` →
    /// `feed_lines` #1) + `hydrate_visible` (`feed_lines` #2) + `draw_feed`
    /// (`feed_lines` #3). Every pass materializes ~8 `FeedLine`s per loaded card.
    #[test]
    fn feed_geometry_is_rebuilt_for_the_whole_feed_on_every_keypress() {
        for count in [200_usize, 2_000, 8_000] {
            let model = model_with_memos(count, 120, 30).expect("fixture");
            let feed = super::support::feed(&model).expect("feed");
            let start = Instant::now();
            let rows = lomo_tui::feed_layout::feed_lines(feed, 94);
            let pass = start.elapsed();
            assert!(
                rows.len() <= usize::from(model.height) * 8,
                "feed_lines materializes {} rows in {pass:?} for {count} loaded memos \
                 on a {}-row viewport; a windowed layout must not touch cards \
                 outside the viewport",
                rows.len(),
                model.height
            );
        }
    }

    /// The complete per-keypress cost at 8k loaded cards: three full feed passes.
    #[test]
    fn one_keypress_costs_three_full_feed_rebuilds() {
        let count = 8_000_usize;
        let mut model = model_with_memos(count, 120, 30).expect("fixture");
        let start = Instant::now();
        let effect = apply_command(&mut model, Command::Move(1));
        let apply = start.elapsed();
        let start = Instant::now();
        let hydrate = navigation::hydrate_visible(&mut model);
        let hydrate_t = start.elapsed();
        let draw = draw_feed_frame(&model, 120, 30);
        assert!(effect.is_none(), "one step inside the window must not page");
        assert!(
            hydrate.is_none(),
            "bodies are already resident in the fixture"
        );
        assert!(
            apply + hydrate_t + draw < FRAME_BUDGET,
            "a single j keypress must fit a frame budget at {count} loaded memos; \
             apply_command(Move)={apply:?} hydrate_visible={hydrate_t:?} \
             draw={draw:?} total≈{:?}",
            apply + hydrate_t + draw
        );
    }

    /// Scrolling demotes off-screen bodies to Pending; scrolling back re-issues
    /// the same versions — every revisit re-runs `projected_memo` +
    /// `MemoBody::parse`.
    #[test]
    fn offscreen_body_demotion_reloads_the_same_memos_on_every_revisit() {
        let mut model = model_with_memos(400, 120, 30).expect("fixture");
        let mut requests: std::collections::BTreeMap<String, u32> =
            std::collections::BTreeMap::new();
        let shared_body = std::sync::Arc::new(
            lomo_tui::content::MemoBody::parse("hydrated body".to_owned()).expect("body"),
        );
        // Drill far past the resident window, then return, delivering each
        // reply like the host does: the memos the user first saw are
        // re-requested from SQLite and re-parsed from Markdown on every revisit.
        for delta in [150_i32, 150, -150, -150, 150, -150] {
            let _effect = apply_command(&mut model, Command::Move(delta));
            while let Some(Effect::Bodies { versions, req }) =
                navigation::hydrate_visible(&mut model)
            {
                let bodies = versions
                    .iter()
                    .map(|version| lomo_tui::effects::BodyReply {
                        version: version.clone(),
                        result: Ok(lomo_tui::effects::LoadedBody {
                            body: std::sync::Arc::clone(&shared_body),
                            attachments: Vec::new(),
                        }),
                    })
                    .collect::<Vec<_>>();
                for version in versions {
                    *requests.entry(version.id.as_str().to_owned()).or_default() += 1;
                }
                let _effect = lomo_tui::messages::apply_message(
                    &mut model,
                    lomo_tui::effects::RuntimeMessage::Bodies { req, bodies },
                );
            }
        }
        let reloaded: Vec<_> = requests.iter().filter(|(_, count)| **count > 1).collect();
        assert!(
            reloaded.is_empty(),
            "the same memo body must not be re-parsed on every scroll revisit; \
             {} distinct memos requested, {} requested more than once (each \
             request = one SQLite fetch + one Markdown parse); re-requested \
             ids: {:?}",
            requests.len(),
            reloaded.len(),
            reloaded.iter().take(5).collect::<Vec<_>>()
        );
    }

    /// Once the resident bound is exceeded, demotion parks the parse in the
    /// version-keyed `BodyCache`: walking back over those cards restores them
    /// with no fetch at all, while a card whose fingerprint moved on misses
    /// its parked entry and re-requests under its new version.
    #[test]
    fn parked_bodies_restore_only_for_the_exact_version() {
        let mut model = model_with_memos(900, 120, 30).expect("fixture");
        let shared_body = std::sync::Arc::new(
            lomo_tui::content::MemoBody::parse("hydrated body".to_owned()).expect("body"),
        );
        let mut requests: std::collections::BTreeMap<String, u32> =
            std::collections::BTreeMap::new();
        let drain = |model: &mut lomo_tui::model::AppModel,
                     requests: &mut std::collections::BTreeMap<String, u32>| {
            while let Some(Effect::Bodies { versions, req }) = navigation::hydrate_visible(model) {
                let bodies = versions
                    .iter()
                    .map(|version| lomo_tui::effects::BodyReply {
                        version: version.clone(),
                        result: Ok(lomo_tui::effects::LoadedBody {
                            body: std::sync::Arc::clone(&shared_body),
                            attachments: Vec::new(),
                        }),
                    })
                    .collect::<Vec<_>>();
                for version in versions {
                    *requests.entry(version.id.as_str().to_owned()).or_default() += 1;
                }
                let _effect = lomo_tui::messages::apply_message(
                    model,
                    lomo_tui::effects::RuntimeMessage::Bodies { req, bodies },
                );
            }
        };
        // Walk past the 512-resident bound to the end of the feed: eviction
        // parks the cards farthest from the anchor, so the tail is parked
        // first and every card the window re-enters on the way down restores
        // from the cache — a fetch anywhere in this walk means a miss.
        for _ in 0..80 {
            let _effect = apply_command(&mut model, Command::Move(12));
            drain(&mut model, &mut requests);
        }
        assert!(
            requests.is_empty(),
            "parked bodies must restore from the versioned cache without a \
             fetch; {} ids were requested: {:?}",
            requests.len(),
            requests.keys().take(5).collect::<Vec<_>>()
        );

        // The parked entry answers only its exact `(id, revision,
        // fingerprint)`: a parked card whose fingerprint moved on — the
        // refresh/edit case — must fetch the new version instead of serving
        // the stale parse.
        let (index, changed) = {
            let View::Feed(feed) = &mut model.view else {
                panic!("feed");
            };
            let (index, memo) = feed
                .memos
                .iter_mut()
                .enumerate()
                .find(|(_, memo)| matches!(memo.body, lomo_tui::model::BodyState::Pending))
                .expect("a parked card left Pending");
            memo.fingerprint = "version-2".to_owned();
            (index, memo.id.as_str().to_owned())
        };
        let selected = {
            let View::Feed(feed) = &model.view else {
                panic!("feed");
            };
            feed.memos
                .iter()
                .position(|memo| Some(&memo.id) == feed.selected.as_ref())
                .expect("selected card")
        };
        let delta = i32::try_from(index).expect("card index")
            - i32::try_from(selected).expect("card index");
        let _effect = apply_command(&mut model, Command::Move(delta));
        drain(&mut model, &mut requests);
        assert_eq!(
            requests.get(changed.as_str()).copied(),
            Some(1),
            "{changed} changed its fingerprint — the parked parse must not \
             answer for the new version"
        );
    }

    /// `reader::page` wraps the whole body every frame; a long memo makes every
    /// scroll step O(body lines).
    #[test]
    fn reader_rewraps_the_entire_body_per_frame() {
        let body = (0..4_000)
            .map(|line| format!("line {line} 内容 wrapping benchmark"))
            .collect::<Vec<_>>()
            .join("\n");
        let mut card = memo("memo-reader", &body).expect("fixture");
        card.summary = body;
        let mut model = model_with_memos(1, 120, 40).expect("fixture");
        model.view = View::Reader {
            memo: card,
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        let start = Instant::now();
        let page = lomo_tui::reader::page(&model).expect("reader page");
        let elapsed = start.elapsed();
        assert!(
            page.rows.len() <= usize::from(model.height) * 8,
            "reader must wrap only the visible window; it wrapped {} rows in \
             {elapsed:?} for a 4000-line body",
            page.rows.len()
        );
    }

    /// Fuzzy paging re-collects every summary and re-scores every body for
    /// EVERY page — the second page costs a full library scan again.
    #[test]
    fn fuzzy_search_rescans_the_library_for_every_page() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(600).expect("seeded workspace");
        let request = |cursor| lomo_application::SearchRequest {
            query_epoch: 1,
            mode: lomo_application::SearchMode::Fuzzy,
            text: "needle".to_owned(),
            filters: lomo_application::MemoFilters::default(),
            cursor,
            anchor: None,
            page_size: lomo_core::PageSize::new(48).expect("page size"),
        };
        let start = Instant::now();
        let first = fixture
            .runtime
            .session
            .search(&request(None))
            .expect("page 1");
        let first_t = start.elapsed();
        let lomo_application::SearchOutcome::Ready(page1) = first else {
            panic!("fuzzy search must be ready");
        };
        let start = Instant::now();
        let second = fixture
            .runtime
            .session
            .search(&request(page1.next_cursor))
            .expect("page 2");
        let second_t = start.elapsed();
        let lomo_application::SearchOutcome::Ready(page2) = second else {
            panic!("fuzzy page 2 must be ready");
        };
        assert_eq!(page1.total, 600);
        assert!(
            second_t < first_t / 4,
            "a cursor page must resume, not rescan; 600 memos: page1={first_t:?} \
             page2={second_t:?} items={}+{} proves every page re-scans and \
             re-scores the whole library",
            page1.items.len(),
            page2.items.len()
        );
    }

    /// The Attachments screen folds in up to 20 history revision bodies per memo,
    /// each run through the full Markdown render (`project_content_facts`).
    #[test]
    fn attachments_screen_reparses_the_history_window() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(400).expect("seeded workspace");
        let start = Instant::now();
        let observations = fixture
            .runtime
            .session
            .observe_attachments()
            .expect("attachment index");
        let elapsed = start.elapsed();
        assert!(
            elapsed < Duration::from_millis(20),
            "opening the attachments screen must not re-parse the history window; \
             it took {elapsed:?} for {} observations (worker thread: every \
             query/paint queues behind it)",
            observations.len()
        );
    }

    /// Session open hashes every workspace byte even when nothing changed; a
    /// single `FsChanged` batch does the same on the one worker thread.
    #[test]
    fn session_open_and_reconcile_rehash_the_whole_workspace() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(300).expect("seeded workspace");
        // A 16 MiB attachment decouples byte volume from file count: the open
        // path must not read it at all when the projection is already valid.
        let media = fixture.runtime.workspace.join("media");
        std::fs::create_dir_all(&media).expect("media dir");
        std::fs::write(media.join("big.bin"), vec![7u8; 16 * 1024 * 1024]).expect("blob");
        let start = Instant::now();
        let _unchanged = fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("reconcile");
        let warm = start.elapsed();
        std::fs::remove_file(media.join("big.bin")).expect("remove blob");
        let start = Instant::now();
        let changed = fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("reconcile");
        let small = start.elapsed();
        assert!(
            warm < Duration::from_millis(50),
            "an unchanged reconcile must not stream every workspace byte; 300 \
             memos: unchanged 16MiB-blob run={warm:?} post-removal run={small:?} \
             (rewritten={})",
            changed.rewritten
        );
    }

    /// A second `open_runtime` on an already-indexed workspace re-reads and
    /// hashes every file before the first frame can be drawn.
    #[test]
    fn warm_reopen_still_scans_and_hashes_every_file() {
        let root = tempfile::tempdir().expect("tempdir");
        // The cold open materializes the projection AND one history record per
        // memo; the timed warm re-open must not re-read any of them.
        {
            use std::fmt::Write;
            let mut text = String::new();
            for index in 0..1_500 {
                write!(
                    text,
                    "- 10:{:02}:{:02}\nbody {index}\n\n",
                    index / 60,
                    index % 60
                )
                .expect("seed write");
            }
            let cold = super::support::runtime_at(root.path(), "notes").expect("cold open");
            std::fs::write(cold.workspace.join("2026_09_11.md"), text).expect("seed file");
            cold.session.rebuild_projection().expect("cold rebuild");
            drop(cold);
        }
        let files = std::fs::read_dir(root.path().join("notes"))
            .expect("list seeded workspace")
            .count();
        let start = Instant::now();
        let reopened = super::support::runtime_at(root.path(), "notes").expect("warm open");
        let elapsed = start.elapsed();
        drop(reopened);
        assert!(
            elapsed < Duration::from_millis(100),
            "a warm open must be a metadata stat, not a full re-read+hash; \
             ~{files} top-level entries + .lomo records (1500 memos): {elapsed:?}"
        );
    }

    /// Protocol encode is O(pixels) work — it must live in `prepare_image` on a
    /// worker lane, so a draw-path render only writes the prepared cells and
    /// never re-encodes (B-06/D-07). The structural lock: after prepare, the
    /// stateful protocol reports no resize needed, and a buffer render lands
    /// the payload as one cell write inside a fraction of the frame budget.
    #[test]
    fn draw_path_renders_prepared_cells_without_reencoding() {
        let width = 624_u32; // 78 columns × 8px cells
        let height = 320_u32; // 20 rows × 16px cells
        let mut rgba = Vec::with_capacity((width * height * 4) as usize);
        for index in 0..(width * height) {
            rgba.extend_from_slice(&[
                u8::try_from(index % 256).expect("channel in byte range"),
                u8::try_from((index / 7) % 256).expect("channel in byte range"),
                u8::try_from((index / 31) % 256).expect("channel in byte range"),
                255,
            ]);
        }
        let png = lomo_tui::media::rgba_to_png(width, height, &rgba).expect("png");
        let mut picker = ratatui_image::picker::Picker::from_fontsize((8, 16));
        picker.set_protocol_type(ratatui_image::picker::ProtocolType::Sixel);
        let image = graphics::prepare_image(&png, 78, 20, &picker, &CancelToken::live())
            .expect("sixel encode");
        let mut protocol = image.lock_protocol();
        assert!(
            protocol
                .needs_resize(&Resize::Fit(None), image.area())
                .is_none(),
            "a prepared image must not re-encode when the draw path renders it"
        );
        let mut buffer = Buffer::empty(Rect::new(0, 0, 80, 24));
        let start = Instant::now();
        ratatui::widgets::StatefulWidget::render(
            ratatui_image::StatefulImage::<ratatui_image::protocol::StatefulProtocol>::default(),
            image.area(),
            &mut buffer,
            &mut protocol,
        );
        let elapsed = start.elapsed();
        drop(protocol);
        let symbol = buffer
            .cell((0, 0))
            .expect("payload cell")
            .symbol()
            .to_owned();
        assert!(
            elapsed < FRAME_BUDGET && symbol.contains("\x1bPq"),
            "the draw path writes the prepared sixel payload as one cell write, \
             not a per-pixel encode: {} bytes in {elapsed:?}",
            symbol.len()
        );
    }

    /// The heatmap does a jiff timezone resolution per displayed day — every
    /// frame while the statistics screen is open.
    #[test]
    fn stats_heatmap_reruns_calendar_arithmetic_every_frame() {
        let stats = StatsView {
            zone: "Asia/Shanghai".to_owned(),
            as_of_year: 2026,
            as_of_month: 9,
            as_of_day: 11,
            total_memos: 5_000,
            total_words: 120_000,
            active_days: 800,
            current_streak: 12,
            longest_streak: 40,
            this_week: 9,
            this_month: 30,
            this_year: 900,
            daily: (1..=28)
                .map(|day| lomo_tui::model::HeatPoint {
                    year: 2026,
                    month: 9,
                    day,
                    count: u64::from(day % 5),
                })
                .collect(),
        };
        let mut terminal = Terminal::new(TestBackend::new(120, 40)).expect("backend");
        let s = lomo_tui::i18n::UiStrings::detect();
        // Measurement hygiene: a single sample picks up parallel-suite load
        // plus ratatui's debug-build `Terminal::draw` overhead (~0.9 ms). The
        // probe measures the heatmap's own arithmetic, so the median of a
        // handful of draws is the honest figure; the budget is unchanged.
        let mut samples = Vec::with_capacity(9);
        for _ in 0..samples.capacity() {
            let start = Instant::now();
            terminal
                .draw(|frame| {
                    lomo_tui::stats_draw::draw_stats(frame, Rect::new(0, 0, 120, 40), &stats, s);
                })
                .expect("draw");
            samples.push(start.elapsed());
        }
        samples.sort_unstable();
        let elapsed = samples
            .get(samples.len() / 2)
            .copied()
            .expect("nine samples were just pushed");
        assert!(
            elapsed < FRAME_BUDGET / 4,
            "a stats frame must not spend multiple ms on per-day timezone math \
             (~54 visible weeks): median {elapsed:?} of {samples:?}"
        );
    }

    /// The tag picker rebuilds the entire tag hierarchy (`BTreeSet` + parent
    /// expansion) and re-lowercases every label on every frame and keystroke.
    #[test]
    fn tag_picker_rebuilds_the_dictionary_per_frame() {
        let mut model = model_with_memos(1, 120, 30).expect("fixture");
        model.set_tags(
            (0..600)
                .map(|index| format!("area{index}/topic{index}/sub{index}"))
                .collect(),
        );
        model.input = InputMode::Picker(Picker {
            kind: PickerKind::Tags(lomo_application::TagSelectionMode::Exact),
            text: lomo_tui::input::TextBuffer::default(),
            selected: 0,
            identity: None,
        });
        let InputMode::Picker(picker) = &model.input else {
            panic!("picker");
        };
        let start = Instant::now();
        let rows = lomo_tui::menu::rows(&model, picker);
        let elapsed = start.elapsed();
        assert!(
            elapsed < Duration::from_millis(2),
            "picker rows must be built once per open, not per frame; 600 tags → \
             {} rows rebuilt in {elapsed:?}",
            rows.len()
        );
    }

    /// `bootstrap_model` runs the reminder plan over EVERY memo summary on the
    /// main thread before the first frame is ever drawn.
    #[test]
    fn bootstrap_reads_the_whole_summary_table_before_first_paint() {
        let fixture = RuntimeFixture::new().expect("fixture");
        fixture.seed(500).expect("seeded workspace");
        let start = Instant::now();
        let model = lomo_tui::ops::bootstrap_model(
            &fixture.runtime,
            lomo_tui::model::AppModel::new(120, 40),
        )
        .expect("bootstrap");
        let elapsed = start.elapsed();
        let View::Feed(feed) = &model.view else {
            panic!("bootstrap lands on the feed");
        };
        assert!(
            elapsed < Duration::from_millis(50),
            "startup bootstrap must stay a bounded page, not a full-library \
             walk; 500 memos: bootstrap_model={elapsed:?} (loaded {} cards, \
             {} tags)",
            feed.memos.len(),
            model.tags().len()
        );
    }

    // ——— B-05: effect lanes — the UI never blocks on execution ———
    //
    // A single FIFO worker + blocking `send` let reconcile/empty-trash/player
    // work starve every interactive request and freeze the UI on a full
    // channel. These probes lock the lane contract: fast work never queues
    // behind slow work, saturated lanes refuse instead of blocking, and
    // superseded jobs are dropped BEFORE they run — not executed then
    // discarded.

    /// Queue two body hydrations of the same class behind a latched worker,
    /// then cancel a third request mid-queue: the displaced and cancelled
    /// jobs must never reach the executor — stale work is dropped at the lane,
    /// not executed and then thrown away.
    #[test]
    fn stale_queued_work_is_dropped_before_execution() {
        use lomo_tui::executor::{Scheduler, Submit};
        use lomo_tui::model::{Pending, PendingKind, Req};

        let fixture = RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let release_rx = std::sync::Mutex::new(release_rx);
        let executed = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let runner: lomo_tui::executor::Runner = {
            let executed = std::sync::Arc::clone(&executed);
            std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                let req = effect.req();
                executed.lock().expect("record").push(req);
                if matches!(effect, Effect::Tags { .. }) {
                    entered_tx.send(req).expect("entered");
                    release_rx.lock().expect("gate").recv().expect("release");
                }
                Ok(lomo_tui::effects::RuntimeMessage::Changed {
                    req,
                    status: "done".to_owned(),
                })
            })
        };
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::ready(runtime));
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        let issue = |pending: &mut Pending, serial: u64, effect: fn(Req) -> Effect| {
            let req = Req(serial);
            pending.register(req, PendingKind::Mutation);
            scheduler.submit(pending, effect(req))
        };
        // Latch the query lane: the tags job is inside the executor.
        assert!(matches!(
            issue(&mut pending, 1, |req| Effect::Tags { req }),
            Submit::Queued
        ));
        entered_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("the tags job is inside the executor");
        // Two same-class body hydrations queue up; the second supersedes the
        // first before it can run.
        assert!(matches!(
            issue(&mut pending, 2, |req| Effect::Bodies {
                req,
                versions: Vec::new()
            }),
            Submit::Queued
        ));
        assert!(matches!(
            issue(&mut pending, 3, |req| Effect::Bodies {
                req,
                versions: Vec::new()
            }),
            Submit::Queued
        ));
        assert!(
            !pending.contains(Req(2)),
            "a superseded same-class request is cancelled at admission"
        );
        // A request revoked mid-queue is likewise never executed.
        assert!(matches!(
            issue(&mut pending, 4, |req| Effect::Date {
                req,
                text: "today".to_owned()
            }),
            Submit::Queued
        ));
        pending.cancel(Req(4));
        release_tx.send(Req(1)).expect("release the latch");
        let mut replies = Vec::new();
        for _ in 0..2 {
            replies.push(
                scheduler
                    .replies()
                    .recv_timeout(Duration::from_secs(5))
                    .expect("live jobs reply"),
            );
        }
        let ran = executed.lock().expect("executed").clone();
        assert_eq!(
            ran,
            &[Req(1), Req(3)],
            "only live work may reach the executor — the displaced and \
             cancelled requests were dropped before execution: {ran:?} \
             (replies {replies:?})"
        );
        scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A reconcile parked on the maintenance lane must not delay a page
    /// request on the query lane — fast paths never queue behind slow paths.
    #[test]
    fn a_slow_maintenance_job_cannot_starve_queries() {
        use lomo_tui::effects::{FeedRequest, PageIntent};
        use lomo_tui::executor::{Scheduler, Submit};
        use lomo_tui::model::{FeedKind, Pending, PendingKind, Req};

        let fixture = RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();
        let release_rx = std::sync::Mutex::new(release_rx);
        let runner: lomo_tui::executor::Runner =
            std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                if matches!(effect, Effect::Reconcile { .. }) {
                    entered_tx.send(()).expect("entered");
                    release_rx.lock().expect("gate").recv().expect("release");
                }
                Ok(lomo_tui::effects::RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            });
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::ready(runtime));
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        pending.register(Req(1), PendingKind::Maintenance);
        assert!(matches!(
            scheduler.submit(
                &mut pending,
                Effect::Reconcile {
                    req: Req(1),
                    observed: None
                }
            ),
            Submit::Queued
        ));
        entered_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("reconcile entered the maintenance lane");
        pending.register(Req(2), PendingKind::FeedPage);
        assert!(matches!(
            scheduler.submit(
                &mut pending,
                Effect::Query(FeedRequest {
                    req: Req(2),
                    kind: FeedKind::Timeline,
                    query: lomo_tui::model::FeedQuery::default(),
                    intent: PageIntent::Initial,
                })
            ),
            Submit::Queued
        ));
        // The query reply arrives while the maintenance lane is still latched.
        let reply = scheduler
            .replies()
            .recv_timeout(Duration::from_secs(5))
            .expect("the query lane answers without waiting on maintenance");
        assert!(
            matches!(
                reply,
                lomo_tui::effects::RuntimeMessage::Changed { req, .. } if req == Req(2)
            ),
            "a query must not queue behind slow maintenance: {reply:?}"
        );
        drop(release_tx);
        scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }

    /// A full lane must refuse admission instantly — a blocking `send` on the
    /// UI path freezes every keystroke (the probe itself would hang on a
    /// blocking implementation, so the assertion is the refusal itself).
    #[test]
    fn a_saturated_lane_refuses_instead_of_blocking_the_ui() {
        use lomo_tui::executor::{LANE_QUEUE_DEPTH, Refusal, Scheduler, Submit};
        use lomo_tui::model::{Pending, PendingKind, Req};

        let fixture = RuntimeFixture::new().expect("fixture");
        let runtime = std::sync::Arc::new(fixture.runtime);
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel::<()>();
        let release_rx = std::sync::Mutex::new(release_rx);
        let runner: lomo_tui::executor::Runner =
            std::sync::Arc::new(move |_runtime, effect, _outbox, _token| {
                if matches!(effect, Effect::Pin { .. }) {
                    entered_tx.send(()).expect("entered");
                    release_rx.lock().expect("gate").recv().expect("release");
                }
                Ok(lomo_tui::effects::RuntimeMessage::Changed {
                    req: effect.req(),
                    status: "done".to_owned(),
                })
            });
        let slot = std::sync::Arc::new(lomo_tui::ops::RuntimeSlot::ready(runtime));
        let scheduler = Scheduler::spawn_with(&slot, &runner).expect("scheduler");
        let mut pending = Pending::default();
        let id = lomo_workspace::MemoId::parse("memo-1").expect("memo id");
        let pin = |serial: u64| Effect::Pin {
            req: Req(serial),
            id: id.clone(),
            pinned: true,
        };
        pending.register(Req(1), PendingKind::Mutation);
        assert!(matches!(
            scheduler.submit(&mut pending, pin(1)),
            Submit::Queued
        ));
        entered_rx
            .recv_timeout(Duration::from_secs(5))
            .expect("the first pin latched the mutate worker");
        for serial in 2..=u64::try_from(LANE_QUEUE_DEPTH + 1).expect("capacity fits u64") {
            pending.register(Req(serial), PendingKind::Mutation);
            assert!(
                matches!(scheduler.submit(&mut pending, pin(serial)), Submit::Queued),
                "the bounded lane admits up to its depth: {serial}"
            );
        }
        pending.register(Req(500), PendingKind::Mutation);
        let start = Instant::now();
        let outcome = scheduler.submit(&mut pending, pin(500));
        assert!(
            matches!(outcome, Submit::Refused(Refusal::Saturated)),
            "a full lane refuses the request — it must never block the UI \
             thread waiting for room: {outcome:?}"
        );
        assert!(
            start.elapsed() < Duration::from_secs(1),
            "admission is a nonblocking try: {:?}",
            start.elapsed()
        );
        drop(release_tx);
        scheduler
            .finish(Duration::from_secs(2))
            .expect("bounded shutdown");
    }
}
