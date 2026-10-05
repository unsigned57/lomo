// adversarial-reaudit: image/media pipeline + the `ImageDest` boundary.
//
// Independent verification of the claimed I7 remediation (audit/08, group G5):
// nothing in `audit/08-TUI对抗性审计修复记录.md` is accepted on faith — every
// assertion below is a behavior lock derived from the D-01..D-14 / B-06 / C-14
// findings themselves.
//
// Behavior Contract:
// Capability: Markdown `![..](dest)` classifies at the workspace fact authority into
//   Local(canonical, contained) / External / Malformed; the feed, reader, image
//   hydration, player handoff and media sweep consume that classification without a
//   single malformed spelling poisoning the page; images decode on a worker, draw
//   inside the ratatui frame, and stay inside the resident-bytes budget.
//   Owning layers: `lomo-workspace` (classification), `apps/tui` (pipeline, lifecycle).
//   Priority: P0/P1.
// Scenarios:
// - Given every hostile destination spelling in the audit matrix, when it is
//   classified or projected, then only a contained canonical `Local` reaches the
//   attachment path set and the page never fails.
// - Given a memo mixing valid/local/external/malformed destinations, when feed,
//   body and reader load, then everything resolves and only `Local` mints image work.
// - Given a probed graphics verdict, when images enter the reader, then they decode
//   on the Maint lane, land Ready, draw as buffer cells, and the byte budget evicts
//   oldest-first while an evicted request never requeues under the same identity.
// - Given a probe thread that dies before reporting, when the host waits for
//   `GraphicsDetected`, then a verdict must still land (evidence test — currently RED).
// Observable outcomes: typed `ImageDest` classifications, `attachments` lists,
//   `model.images` states, `ReaderPage.pictures`, buffer cell symbols,
//   `PlayerFinished` reports, `MediaSweepDone` counters, filesystem moves.
// TDD proof: this suite re-derives the evidence — RED assertions mark defects the
//   repair record claims fixed but that still fail.
// Excludes: real-terminal pixel rendering, tmux passthrough bytes, real PTY probe
//   timing (no PTY harness in scope); those are source-inspection + protocol-shape
//   evidence only.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, ready_graphics, run_effect};
    use lomo_core::RelativeWorkspacePath;
    use lomo_tui::{
        effects::{Effect, Lane, RuntimeMessage},
        error::TuiError,
        event::Command,
        executor::Outbox,
        graphics::{
            self, GraphicsVerdict, ImageRequest, ImageState, ReaderImage, SharedPicker,
            StdioProber, TerminalProber,
        },
        media::rgba_to_png,
        model::{
            AppModel, BadgeClass, BodyState, CancelToken, FeedKind, InputMode, MemoVersion,
            PendingKind, View,
        },
        ops, queries, update,
    };
    use lomo_workspace::{ImageDest, MemoId, SemanticFactKind, SourceBytes, render_markdown};
    use ratatui::{
        Terminal, backend::TestBackend, buffer::Buffer, layout::Rect, widgets::StatefulWidget,
    };
    use ratatui_image::{
        StatefulImage,
        picker::{Picker, ProtocolType},
        protocol::StatefulProtocol,
    };
    use std::{io::IsTerminal, sync::mpsc, thread, time::Duration};

    fn png(width: u32, height: u32, pixel: [u8; 4]) -> Vec<u8> {
        let count = usize::try_from(width * height).expect("fixture pixel count fits usize");
        rgba_to_png(width, height, &pixel.repeat(count)).expect("png fixture")
    }

    /// The picker the probe reports on a capable terminal: queried font metrics
    /// plus an explicit protocol answer, constructed without touching stdio.
    fn probed_picker(protocol: ProtocolType) -> Picker {
        let mut picker = Picker::from_fontsize((8, 16));
        picker.set_protocol_type(protocol);
        picker
    }

    fn seed_file(fixture: &RuntimeFixture, relative: &str, bytes: &[u8]) {
        let path = fixture.runtime.workspace.join(relative);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).expect("seed parent dir");
        }
        std::fs::write(path, bytes).expect("seed file");
    }

    /// Settles the transaction lock with a bounded wait: the session takes a
    /// per-runtime `lomo_transaction.lock`, and under parallel test load a
    /// still-draining predecessor can hold it briefly. `transaction_lock_held`
    /// is a declared `AfterUserAction` conflict — waiting mirrors the caller
    /// contract; any other error fails immediately. The observed `RebuildResult`
    /// is dropped because its type is not nameable from this crate; callers
    /// needing fields issue one direct rebuild once the lock is proven free.
    fn rebuild_settled(fixture: &RuntimeFixture) {
        for _ in 0..40 {
            match fixture.runtime.session.rebuild_projection() {
                Ok(_) => return,
                Err(error) if error.code() == "transaction_lock_held" => {
                    thread::sleep(Duration::from_millis(25));
                }
                Err(error) => panic!("rebuild failed: {error:?}"),
            }
        }
        panic!("the transaction lock never settled within 1s");
    }

    fn seed_memo(fixture: &RuntimeFixture, body: &str) {
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            format!("- 10:00:00\n{body}\n"),
        )
        .expect("seed memo");
        rebuild_settled(fixture);
    }

    /// Drive the reader open on the feed's first memo and hydrate its body.
    fn open_reader_with_body(fixture: &RuntimeFixture, model: &mut AppModel) {
        command(&fixture.runtime, model, Command::Accept).expect("open reader");
        assert!(
            matches!(model.view, View::Reader { .. }),
            "Accept must install the reader: {:?}",
            model.view
        );
        let effect = lomo_tui::navigation::hydrate_visible(model);
        run_effect(&fixture.runtime, model, effect).expect("body hydration");
        let View::Reader { memo, .. } = &model.view else {
            panic!("reader must still be open");
        };
        assert!(
            matches!(memo.body, BodyState::Ready(_)),
            "the reader body must hydrate"
        );
    }

    /// Deliver the kitty verdict through the observation channel and drain the
    /// image queue it kicks — the exact `GraphicsDetected` → `LoadImage` path.
    fn install_kitty_verdict(fixture: &RuntimeFixture, model: &mut AppModel) {
        let effect = lomo_tui::messages::apply_message(
            model,
            RuntimeMessage::GraphicsDetected {
                verdict: ready_graphics(ProtocolType::Kitty),
            },
        );
        run_effect(&fixture.runtime, model, effect).expect("image queue drains");
    }

    fn local_of(raw: &str) -> Option<String> {
        match ImageDest::classify(raw) {
            ImageDest::Local { path, .. } => Some(path.as_str().to_owned()),
            ImageDest::External(_) | ImageDest::Malformed(_) => None,
        }
    }

    /// Executes an effect with the same lock-settle contract as
    /// `rebuild_settled` — session-backed effects inherit the transaction lock.
    fn execute_settled(
        fixture: &RuntimeFixture,
        effect: &Effect,
        outbox: &Outbox,
    ) -> RuntimeMessage {
        for _ in 0..40 {
            match ops::execute(&fixture.runtime, effect, outbox, &CancelToken::live()) {
                Err(TuiError::Session { code, .. }) if code == "transaction_lock_held" => {
                    thread::sleep(Duration::from_millis(25));
                }
                other => return other.expect("effect executes"),
            }
        }
        panic!("the transaction lock never settled within 1s");
    }

    /// Recursive subtree search under `root` for a file whose name contains `needle`.
    fn subtree_has_file(root: &std::path::Path, needle: &str) -> bool {
        let Ok(entries) = std::fs::read_dir(root) else {
            return false;
        };
        entries.flatten().any(|entry| {
            let path = entry.path();
            (path.is_file() && entry.file_name().to_string_lossy().contains(needle))
                || (path.is_dir() && subtree_has_file(&path, needle))
        })
    }

    /// D-07 transmit-once at protocol level: a prepared kitty protocol emits
    /// the `a=T` payload exactly once; every later render of the same geometry
    /// writes virtual-placement cells only, with no re-encode.
    #[test]
    fn a_prepared_kitty_protocol_transmits_exactly_once() {
        let picker = probed_picker(ProtocolType::Kitty);
        let image = graphics::prepare_image(
            &png(4, 4, [1, 2, 3, 255]),
            74,
            8,
            &picker,
            &CancelToken::live(),
        )
        .expect("prepare");
        let area = image.area();
        let cell = Rect::new(4, 5, area.width, area.height);
        let mut transmits = 0u32;
        for _round in 0..3 {
            let mut buffer = Buffer::empty(Rect::new(0, 0, 80, 24));
            let reencoded;
            {
                let mut protocol = image.lock_protocol();
                StatefulWidget::render(
                    StatefulImage::<StatefulProtocol>::default(),
                    cell,
                    &mut buffer,
                    &mut protocol,
                );
                reencoded = protocol.last_encoding_result().is_some();
            }
            let symbol = buffer
                .cell((4, 5))
                .map_or_else(String::new, |cell| cell.symbol().to_owned());
            if symbol.contains("\x1b_G") {
                transmits += 1;
            }
            assert!(
                !reencoded || transmits <= 1,
                "an unchanged geometry must not re-encode"
            );
        }
        assert_eq!(
            transmits, 1,
            "the kitty payload transmits on the first render and never again"
        );
        // A geometry change is the one thing that must retransmit (new size).
        let mut buffer = Buffer::empty(Rect::new(0, 0, 80, 24));
        {
            let mut protocol = image.lock_protocol();
            StatefulWidget::render(
                StatefulImage::<StatefulProtocol>::default(),
                Rect::new(4, 5, area.width.max(1) / 2, area.height.max(1) / 2),
                &mut buffer,
                &mut protocol,
            );
        }
        let symbol = buffer
            .cell((4, 5))
            .map_or_else(String::new, |cell| cell.symbol().to_owned());
        assert!(
            symbol.contains("\x1b_G"),
            "a resized protocol must retransmit the new raster"
        );
    }

    /// Every destination spelling in the adversarial matrix classifies into the
    /// typed boundary — the classification table the reader and projections
    /// consume, exercised at the `ImageDest::classify` authority.
    #[test]
    fn every_hostile_spelling_classifies_at_the_boundary() {
        // Local spellings collapse to the canonical key.
        for (raw, canonical) in [
            ("media/pic.png", "media/pic.png"),
            ("./media/pic.png", "media/pic.png"),
            ("media//pic.png", "media/pic.png"),
            ("media\\pic.png", "media/pic.png"),
            ("  media/pic.png  ", "media/pic.png"),
            ("x/./y.png", "x/y.png"),
            ("bracketed name.png", "bracketed name.png"),
            ("x%20y.png", "x%20y.png"),
            ("x?query=1", "x?query=1"),
            ("媒体/图片.png", "媒体/图片.png"),
        ] {
            assert_eq!(
                local_of(raw).as_deref(),
                Some(canonical),
                "{raw:?} must classify Local({canonical:?})"
            );
        }
        // `..` folds like the host path resolver — it may only shrink the
        // target inside the workspace, never escape it.
        assert_eq!(
            local_of("media/../pics/b.png").as_deref(),
            Some("pics/b.png")
        );
        assert_eq!(local_of("a/../b/c.png").as_deref(), Some("b/c.png"));
        // A leading `/` is a separator, not a root: the destination rebases
        // into the workspace instead of escaping it.
        assert_eq!(
            local_of("/absolute/path.png").as_deref(),
            Some("absolute/path.png")
        );
        // Root escapes and unreadable spellings are Malformed — never Local.
        let overlong_segment = "a".repeat(300); // one segment over the 255-byte bound
        let overlong_path = "a/".repeat(2100); // joined path over the 4096-byte bound
        for raw in [
            "../outside.png",
            "..",
            "x/../..",
            "x/../../y.png",
            "/..",
            "media/../../escape.png",
            "",
            "   ",
            ".",
            "/",
            "x\u{7}y.png",
            overlong_segment.as_str(),
            overlong_path.as_str(),
        ] {
            let dest = ImageDest::classify(raw);
            assert!(
                matches!(dest, ImageDest::Malformed(_)),
                "{raw:?} must classify Malformed, got {dest:?}"
            );
            assert_eq!(dest.local(), None);
        }
        // Scheme, protocol-relative and anchor destinations are External —
        // including Windows drive-letter spellings: `C:` parses as a URL
        // scheme, and even `c:\dir` never classifies Local, so a drive path
        // can never reach the workspace IO boundary as a local file.
        for raw in [
            "https://example.com/x.png",
            "http://example.com/x.png",
            "ftp://example.com/x.png",
            "data:image/png;base64,AAAA",
            "file:///etc/passwd",
            "mailto:a@b.c",
            "javascript:alert(1)",
            "C:/dir/x.png",
            "c:\\dir\\x.png",
            "//cdn.example.com/x.png",
            "//",
            "#frag",
            "#",
        ] {
            let dest = ImageDest::classify(raw);
            assert!(
                matches!(dest, ImageDest::External(_)),
                "{raw:?} must classify External, got {dest:?}"
            );
            assert_eq!(dest.local(), None);
        }
    }

    /// The boundary's security property: whatever the author types, a `Local`
    /// classification is proof of workspace containment — no `..` segment and
    /// no absolute anchor can survive into a canonical path — and the raw-token
    /// `canonical_attachment_path` authority agrees with the typed result.
    #[test]
    fn a_local_classification_is_proof_of_workspace_containment() {
        let hostile = [
            "../x.png",
            "a/../../b.png",
            "/etc/passwd",
            "media/../..\\..\\root.png",
            "media\\..\\up.png",
            "..\\..\\win.png",
            "./../root.png",
        ];
        for raw in hostile {
            let dest = ImageDest::classify(raw);
            if let ImageDest::Local { path, .. } = &dest {
                // Containment is proven by the type: a canonical path can
                // never carry `..`, a leading `/` or a drive anchor.
                let text = path.as_str();
                assert!(
                    !text.split('/').any(|segment| segment == "..")
                        && !text.starts_with('/')
                        && !text.contains('\\'),
                    "{raw:?} classified to a path that escapes containment: {text:?}"
                );
            }
            assert_eq!(
                lomo_workspace::canonical_attachment_path(raw),
                dest.local().map(|path| path.as_str().to_owned()),
                "{raw:?}: canonical key and typed local must agree"
            );
        }
        assert_eq!(local_of("../x.png"), None);
        assert_eq!(lomo_workspace::canonical_attachment_path("../x.png"), None);
    }

    /// `attachment_destinations` dedups on the projected key while
    /// `SemanticFact`s keep every authored token — both sides of the D-02 fix
    /// exercised at the workspace authority.
    #[test]
    fn render_facts_dedup_the_key_but_preserve_every_authored_token() {
        let source = "![](./media/a.png)\n\n![](media//a.png)\n\n![](media\\a.png)\n\n![](https://x.example/i.png)\n";
        let document =
            render_markdown(&SourceBytes::try_from_str(source).expect("source")).expect("render");
        assert_eq!(
            document
                .attachment_destinations()
                .iter()
                .map(ImageDest::projected)
                .collect::<Vec<_>>(),
            ["media/a.png", "https://x.example/i.png"],
            "equivalent spellings collapse to one projected destination"
        );
        let attachment_facts: Vec<_> = document
            .semantic_facts()
            .iter()
            .filter(|fact| fact.kind() == SemanticFactKind::Attachment)
            .collect();
        assert_eq!(
            attachment_facts.len(),
            4,
            "every authored image node emits a fact — dedup never rewrites evidence"
        );
        assert!(
            attachment_facts
                .iter()
                .any(|fact| fact.value() == "./media/a.png"),
            "the authored token survives on the fact: {attachment_facts:?}"
        );
    }

    /// D-01 whole-page resilience: one memo carrying every hostile class must
    /// load in the feed, hydrate its body, open the reader, mint image work
    /// only for `Local` destinations, and stay a live `View::Reader` — a bad
    /// destination degrades its own row, never the page.
    #[test]
    fn a_memo_full_of_hostile_destinations_still_reads_end_to_end() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/real.png", &png(4, 4, [9, 30, 200, 255]));
        seed_memo(
            &fixture,
            "alpha text\n\n\
             ![](./media/real.png)\n\n\
             ![](media//real.png)\n\n\
             ![](media\\real.png)\n\n\
             ![](https://example.com/remote.png)\n\n\
             ![](data:image/png;base64,iVBOR)\n\n\
             ![](//cdn.example.com/x.png)\n\n\
             ![](#frag)\n\n\
             ![](../escape.png)\n\n\
             ![]()\n\n\
             ![](/absolute/path.png)\n\n\
             ![](media/../outside.png)\n\n\
             ![](<bracketed name.png>)\n\n\
             ![](x?query=1)\n\n\
             ![](titled.png \"a title\")\n\n\
             ![](media/missing.png)\n\n\
             ![](媒体/图片.png)\n\n\
             omega text",
        );

        // Feed + card survive the whole hostile set.
        let feed = queries::load_feed(&fixture.runtime, FeedKind::Timeline)
            .expect("the feed page must survive hostile destinations (D-01)");
        let card = feed.memos.first().expect("one memo card");
        let mut card_attachments: Vec<_> = card
            .attachments
            .iter()
            .map(RelativeWorkspacePath::as_str)
            .collect();
        card_attachments.sort_unstable();
        let mut expected = vec![
            "absolute/path.png",
            "bracketed name.png",
            "media/missing.png",
            "media/real.png",
            "outside.png",
            "titled.png",
            "x?query=1",
            "媒体/图片.png",
        ];
        expected.sort_unstable();
        assert_eq!(
            card_attachments, expected,
            "the card attachment set is exactly the canonical local keys"
        );

        // Body hydration sees the same canonical set in document order.
        let body = queries::load_body(&fixture.runtime, &card.id)
            .expect("load_body must survive hostile destinations")
            .expect("the seeded memo is present");
        assert_eq!(
            body.attachments
                .iter()
                .map(RelativeWorkspacePath::as_str)
                .collect::<Vec<_>>(),
            [
                "media/real.png",
                "absolute/path.png",
                "outside.png",
                "bracketed name.png",
                "x?query=1",
                "titled.png",
                "media/missing.png",
                "媒体/图片.png",
            ],
            "the reader attachment list is the deduped canonical local set in doc order"
        );
    }

    /// The same hostile memo end-to-end through the reader: exactly the
    /// canonical local destinations mint image requests, each exactly once;
    /// absent files fail per-image and never poison the view.
    #[test]
    fn a_memo_full_of_hostile_destinations_requests_each_canonical_key_once() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/real.png", &png(4, 4, [9, 30, 200, 255]));
        seed_memo(
            &fixture,
            "alpha text\n\n\
             ![](./media/real.png)\n\n\
             ![](media//real.png)\n\n\
             ![](media\\real.png)\n\n\
             ![](https://example.com/remote.png)\n\n\
             ![](data:image/png;base64,iVBOR)\n\n\
             ![](//cdn.example.com/x.png)\n\n\
             ![](#frag)\n\n\
             ![](../escape.png)\n\n\
             ![]()\n\n\
             ![](/absolute/path.png)\n\n\
             ![](media/../outside.png)\n\n\
             ![](<bracketed name.png>)\n\n\
             ![](x?query=1)\n\n\
             ![](titled.png \"a title\")\n\n\
             ![](media/missing.png)\n\n\
             ![](媒体/图片.png)\n\n\
             omega text",
        );

        // Open the reader, deliver the probed verdict, drain the image queue.
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);
        let effect = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::GraphicsDetected {
                verdict: ready_graphics(ProtocolType::Kitty),
            },
        );
        run_effect(&fixture.runtime, &mut model, effect).expect("image queue drains");

        assert_eq!(
            model.images.len(),
            8,
            "exactly the canonical local destinations mint image requests: {:?}",
            model.images
        );
        let ready = model
            .images
            .iter()
            .filter(|image| matches!(image.state, ImageState::Ready(_)))
            .count();
        let failed = model
            .images
            .iter()
            .filter(|image| matches!(image.state, ImageState::Failed(_)))
            .count();
        assert_eq!(
            ready, 1,
            "only the existing file decodes: {:?}",
            model.images
        );
        assert_eq!(failed, 7, "absent files fail per-image, never the page");
        assert!(
            matches!(model.view, View::Reader { .. }),
            "the reader must stay open — a failed image never poisons the view"
        );
        // NOTE (independent re-audit evidence): `reader::page` placements for
        // this memo are EMPTY even though one image is Ready — the block-merge
        // predicate at reader.rs:183 is inverted, so image blocks append after
        // the body instead of merging at their sites. That defect is locked by
        // `image_blocks_must_merge_at_their_sites` below.
    }

    /// D-02: equivalent spellings share one canonical key through rebuild, and
    /// deleting them detaches protection — delete+recreate cycles converge and
    /// a no-change rebuild is a digest-confirmed no-op.
    #[test]
    fn canonical_projection_stays_stable_across_edit_delete_rebuild() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/a.png", &png(4, 4, [1, 1, 1, 255]));
        seed_file(&fixture, "media/b.png", &png(4, 4, [2, 2, 2, 255]));
        seed_memo(
            &fixture,
            "![1](./media/a.png)\n\n![2](media//a.png)\n\n![3](media\\a.png)\n\n![4](media/./a.png)\n\n![5](media/b.png)",
        );

        rebuild_settled(&fixture);
        let first = fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("a no-change rebuild must converge once the lock settles");
        assert_eq!(first.store_digest, first.workspace_digest);
        assert!(!first.rewritten, "unchanged input is a proven no-op");

        // Whole-document delete: the live-memo reference detaches — the journaled
        // revision history may still hold the path in its retention window, but
        // no `CurrentMemo`/`Draft` source may survive the projection rebuild.
        std::fs::remove_file(fixture.runtime.workspace.join("2026_09_11.md")).expect("remove memo");
        rebuild_settled(&fixture);
        let second = fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("rebuild after deletion");
        let observations = fixture
            .runtime
            .session
            .observe_attachments()
            .expect("observation list");
        let live_refs: Vec<_> = observations
            .iter()
            .filter(|item| {
                matches!(
                    item.source,
                    lomo_media::ReferenceSource::CurrentMemo | lomo_media::ReferenceSource::Draft
                ) && (item.relative_path == "media/a.png" || item.relative_path == "media/b.png")
            })
            .collect();
        assert!(
            live_refs.is_empty(),
            "a deleted memo must leave no live-memo reference: {live_refs:?}"
        );

        // A new document re-attaching one canonical key protects exactly it —
        // and the protection is the live-memo source, not stale history.
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_12.md"),
            "- 09:00:00\n![5](media/b.png)\n",
        )
        .expect("seed second memo");
        rebuild_settled(&fixture);
        let observations = fixture
            .runtime
            .session
            .observe_attachments()
            .expect("observation list");
        assert!(
            observations.iter().any(|item| {
                item.relative_path == "media/b.png"
                    && item.source == lomo_media::ReferenceSource::CurrentMemo
            }),
            "the recreated document's reference re-attaches protection"
        );
        assert!(
            observations.iter().all(|item| {
                item.relative_path != "media/a.png"
                    || item.source != lomo_media::ReferenceSource::CurrentMemo
            }),
            "the dropped spellings never reappear as a live reference"
        );
        rebuild_settled(&fixture);
        let third = fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("delete→create→rebuild must not diverge");
        assert!(!third.rewritten);
        assert_ne!(
            first.workspace_digest, second.workspace_digest,
            "the digest tracks the deletion"
        );
    }

    /// EVIDENCE OF A REMAINING DEFECT (RED — reader.rs:183): image blocks must
    /// merge at their `![..](dest)` site. The merge position is computed as
    /// `wrapped.partition_point(|row| site < row.anchor.line)` — the predicate
    /// is inverted (`partition_point` binary-searches a *true-prefix*, but
    /// `site < anchor` is true for the *suffix*), so the insert index is
    /// binary-search garbage: near-top sites land past the body end, deep
    /// sites land at 0, and the wrapped body is pushed twice.
    ///
    /// Contract: the block label row must directly follow the `[Image: dest]`
    /// site line, the merged row count must equal the declared total, and a
    /// Ready image's placement must sit at the site — otherwise a memo taller
    /// than the viewport never displays its image at all.
    #[test]
    fn image_blocks_must_merge_at_their_sites() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/a.png", &png(4, 4, [7, 7, 7, 255]));
        seed_memo(&fixture, "top text\n\n![](media/a.png)\n\nbottom text");
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);
        install_kitty_verdict(&fixture, &mut model);

        let page = lomo_tui::reader::page(&model).expect("reader page");
        assert!(
            page.rows.len() <= page.total,
            "the merged window can never exceed the merged total \
             (got {} rows for total {})",
            page.rows.len(),
            page.total
        );
        // The image is `Ready`, so its authored `[Image:]` placeholder is
        // blanked at the site (D-08) — locate the site semantically: the
        // block's path label anchors to the site's styled line, directly
        // after that line's last wrapped row.
        let View::Reader { memo, .. } = &model.view else {
            panic!("reader must be open");
        };
        let BodyState::Ready(body) = &memo.body else {
            panic!("body must be ready");
        };
        let site_line = body
            .image_sites()
            .first()
            .expect("the authored `![]` produces one site")
            .line;
        let label_index = page
            .rows
            .iter()
            .position(|row| {
                row.line
                    .spans
                    .iter()
                    .map(|span| span.content.as_ref())
                    .collect::<String>()
                    == "media/a.png"
            })
            .expect("the image block's path label is in the page");
        let label = page
            .rows
            .get(label_index)
            .map(|row| row.anchor)
            .expect("label row exists");
        assert_eq!(
            label.line, site_line,
            "the image block must anchor to its site line, not append after \
             the body (reader.rs partition predicate regression)"
        );
        let site_row = label_index
            .checked_sub(1)
            .and_then(|index| page.rows.get(index))
            .map(|row| row.anchor);
        assert_eq!(
            site_row.map(|anchor| anchor.line),
            Some(site_line),
            "the site line's last wrapped row must sit directly above the block"
        );
        assert_eq!(
            page.pictures.len(),
            1,
            "a Ready image inside the viewport must produce a placement"
        );
    }

    /// Three sites naming the same canonical file produce exactly one decoded
    /// image object — request identity is the canonical key, so dedup happens
    /// before the worker lane, not inside a first-match land.
    #[test]
    fn duplicate_image_sites_share_one_image_object() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/dup.png", &png(4, 4, [4, 4, 4, 255]));
        seed_memo(
            &fixture,
            "![a](media/dup.png)\n\ntext between\n\n![b](./media/dup.png)\n\nmore text\n\n![c](media//dup.png)",
        );
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);
        install_kitty_verdict(&fixture, &mut model);

        assert_eq!(
            model.images.len(),
            1,
            "three sites, three spellings, one canonical request: {:?}",
            model.images
        );
        let View::Reader { memo, .. } = &model.view else {
            panic!("reader must be open");
        };
        assert_eq!(
            memo.attachments.len(),
            1,
            "the attachment list dedups the spellings"
        );
        // The decoded image lands at the first site; the remaining sites keep
        // their authored placeholder — one image object per canonical key.
        let page = lomo_tui::reader::page(&model).expect("reader page");
        assert_eq!(page.pictures.len(), 1, "one image object, one placement");
        let BodyState::Ready(body) = &memo.body else {
            panic!("body must be ready");
        };
        assert_eq!(
            body.image_sites().len(),
            3,
            "all three sites remain addressable"
        );
    }

    /// D-03: a non-terminal stdio refuses the capability query outright — the
    /// probe never writes escapes a pipe could swallow, and fails closed.
    #[test]
    fn non_terminal_stdio_probe_fails_closed_without_a_query() {
        if !std::io::stdin().is_terminal() || !std::io::stdout().is_terminal() {
            let verdict = TerminalProber::probe(&StdioProber);
            let GraphicsVerdict::Unsupported { diagnostic } = &verdict else {
                panic!("a non-answering stdio must fail closed, got {verdict:?}");
            };
            assert!(
                diagnostic.contains("terminal"),
                "the refusal names the missing terminal: {diagnostic}"
            );
        }
    }

    /// D-03: the model mints no image work while `Probing`, installs the
    /// delivered verdict, and only then hydrates — `Unsupported` retires the
    /// whole registry instead of leaving ghost entries.
    ///
    /// The first `Unsupported` is the outbox's dying-declaration payload —
    /// the only `Unsupported` a later real answer may still supersede. The
    /// probe reports exactly once (host.rs), so a probe-*reported*
    /// `Unsupported` is terminal: `U{probe} → Ready` is production-
    /// unreachable and degrades at the seam (13-T-01). The reachable
    /// recovery arm is the placeholder — some other outbox holder panicked
    /// while the probe was still alive — yielding to the real verdict.
    #[test]
    fn the_probing_gate_holds_until_a_verdict_lands() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/a.png", &png(4, 4, [1, 2, 3, 255]));
        seed_memo(&fixture, "![dot](media/a.png)");
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);

        assert_eq!(model.graphics, GraphicsVerdict::Probing);
        assert!(
            graphics::hydrate_images(&mut model).is_none(),
            "a probing terminal mints no image work"
        );

        // An `Unsupported` answer retires everything — no image state outlives
        // it. The verbatim dying-declaration payload is the drift sentinel:
        // it must equal executor.rs's `PROBE_DIED_DIAGNOSTIC` emission.
        let effect = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::GraphicsDetected {
                verdict: GraphicsVerdict::Unsupported {
                    diagnostic: "graphics probe died before reporting a verdict".to_owned(),
                },
            },
        );
        assert!(effect.is_none(), "an unsupported verdict mints nothing");
        assert!(
            model.images.is_empty(),
            "the registry clears on Unsupported"
        );

        // A `Ready` answer unlocks the same reader immediately.
        let effect = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::GraphicsDetected {
                verdict: ready_graphics(ProtocolType::Kitty),
            },
        );
        assert!(
            matches!(effect, Some(Effect::LoadImage { .. })),
            "a ready verdict kicks the pending hydration: {effect:?}"
        );
        run_effect(&fixture.runtime, &mut model, effect).expect("image load");
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Ready(_))),
            "the deferred decode lands after the verdict"
        );
    }

    /// A prober that merely answers slowly still delivers `GraphicsDetected`
    /// through the host's `lomo-bg-probe` wiring — the gate is designed to
    /// lift on the verdict, which this mirrors byte-for-byte (host.rs:473-481).
    struct SlowProber;
    impl TerminalProber for SlowProber {
        fn probe(&self) -> GraphicsVerdict {
            thread::sleep(Duration::from_millis(120));
            GraphicsVerdict::Unsupported {
                diagnostic: "slow answer".to_owned(),
            }
        }
    }

    #[test]
    fn a_slow_probe_answer_still_lands_as_a_verdict() {
        let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
        let outbox = Outbox::new(tx);
        let handle = thread::Builder::new()
            .name("lomo-bg-probe".to_owned())
            .spawn(move || {
                let verdict = TerminalProber::probe(&SlowProber);
                let _delivered = outbox.report(RuntimeMessage::GraphicsDetected { verdict });
            })
            .expect("probe thread spawns");
        handle.join().expect("a slow prober still returns");
        let RuntimeMessage::GraphicsDetected { verdict } = rx
            .recv_timeout(Duration::from_secs(2))
            .expect("the verdict must land for the gate to lift")
        else {
            panic!("the probe thread must report GraphicsDetected");
        };
        let mut model = AppModel::new(80, 24);
        let effect = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::GraphicsDetected { verdict },
        );
        assert!(effect.is_none());
        assert!(
            matches!(model.graphics, GraphicsVerdict::Unsupported { .. }),
            "the gate lifts on the delivered verdict"
        );
    }

    /// EVIDENCE OF A REMAINING DEFECT (RED): host.rs:473-481 spawns the probe
    /// closure without `catch_unwind` or a watchdog, and event_loop:580-585
    /// never reads stdin while `model.graphics == Probing`. A prober that dies
    /// before `outbox.report` — a panic inside `Picker::from_query_stdio`'s
    /// outer section or a wedged prober — never sends `GraphicsDetected`, so the
    /// loop sleeps forever with all input dead. The required invariant: *a
    /// verdict always lands*.
    struct PanicProber;
    impl TerminalProber for PanicProber {
        fn probe(&self) -> GraphicsVerdict {
            panic!("injected probe failure");
        }
    }

    #[test]
    fn a_probe_that_dies_before_reporting_must_still_land_a_verdict() {
        let (tx, rx) = mpsc::sync_channel::<RuntimeMessage>(8);
        let outbox = Outbox::new(tx);
        let handle = thread::Builder::new()
            .name("lomo-bg-probe".to_owned())
            .spawn(move || {
                let verdict = TerminalProber::probe(&PanicProber);
                let _delivered = outbox.report(RuntimeMessage::GraphicsDetected { verdict });
            })
            .expect("probe thread spawns");
        let panicked = handle.join().is_err();
        assert!(panicked, "the injected panic must kill the probe thread");
        let arrived = rx.recv_timeout(Duration::from_millis(500)).is_ok();
        assert!(
            arrived,
            "a dead probe never reports GraphicsDetected — the event loop \
             gates stdin on GraphicsVerdict::Probing forever (host.rs:580-585 \
             has no watchdog; the spawn site has no catch_unwind)"
        );
    }

    /// Seeds a tall memo with one image above and one below the fold, opens the
    /// reader and delivers the kitty verdict with the queue drained.
    fn two_image_kitty_reader(fixture: &RuntimeFixture) -> AppModel {
        use std::fmt::Write as _;
        seed_file(fixture, "media/top.png", &png(4, 4, [200, 30, 30, 255]));
        seed_file(fixture, "media/bottom.png", &png(4, 4, [30, 200, 30, 255]));
        let mut body = String::from("![](media/top.png)\n\n");
        for line in 0..48 {
            write!(body, "filler line {line}\n\n").expect("write to String");
        }
        body.push_str("![](media/bottom.png)\n");
        seed_memo(fixture, &body);

        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(fixture, &mut model);
        install_kitty_verdict(fixture, &mut model);
        assert!(
            model
                .images
                .iter()
                .all(|image| matches!(image.state, ImageState::Ready(_))),
            "both attachments decode: {:?}",
            model.images
        );
        model
    }

    /// C-14 + D-07 at the frame level: the kitty transmit lives inside the
    /// composed buffer at the placement cell (text and image share one cell
    /// diff), a second `ui::draw` of the same view emits placement cells only,
    /// and re-rendering the placed protocol replays placeholders.
    #[test]
    fn kitty_cells_compose_in_frame_and_transmit_once() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let model = two_image_kitty_reader(&fixture);

        let page = lomo_tui::reader::page(&model).expect("reader page");
        assert_eq!(page.pictures.len(), 1, "only the in-viewport image places");
        let placement = page.pictures.first().expect("placement");

        // The kitty transmit lands inside the frame buffer at the placement —
        // text and image share one cell diff (C-14), not a post-draw side write.
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).expect("terminal");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, &model))
            .expect("draw");
        let cell_symbol = terminal
            .backend()
            .buffer()
            .cell((placement.rect.x, placement.rect.y))
            .map_or_else(String::new, |cell| cell.symbol().to_owned());
        assert!(
            cell_symbol.contains("\x1b_G"),
            "the kitty APC transmit lives in the composed buffer cell: {cell_symbol:?}"
        );

        // Transmit-once at the frame level: a second `ui::draw` of the same
        // view emits virtual-placement cells only — no second `a=T` payload
        // (D-07). Then rendering the *placed* protocol again is placeholders.
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, &model))
            .expect("second draw");
        let redraw = terminal
            .backend()
            .buffer()
            .cell((placement.rect.x, placement.rect.y))
            .map_or_else(String::new, |cell| cell.symbol().to_owned());
        assert!(
            !redraw.contains("\x1b_G"),
            "the second frame must not retransmit the kitty payload: {redraw:?}"
        );
        let mut buffer = Buffer::empty(Rect::new(0, 0, 80, 24));
        {
            let mut protocol = placement.image.lock_protocol();
            StatefulWidget::render(
                StatefulImage::<StatefulProtocol>::default(),
                placement.rect,
                &mut buffer,
                &mut protocol,
            );
        }
        let replay = buffer
            .cell((placement.rect.x, placement.rect.y))
            .map_or_else(String::new, |cell| cell.symbol().to_owned());
        assert!(
            !replay.contains("\x1b_G") && replay.contains('\u{10EEEE}'),
            "a repeated render replays placement cells without retransmitting: {replay:?}"
        );
    }

    /// Placement geometry tracks scroll, a modal overlay owns the viewport
    /// (no kitty payload leaks under it), a resize changes request identity and
    /// requeues decode, and leaving the reader retires the image registry.
    #[test]
    fn kitty_placements_track_scroll_overlay_resize_and_exit() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let mut model = two_image_kitty_reader(&fixture);
        let page = lomo_tui::reader::page(&model).expect("reader page");

        // Scrolling moves the placement through shared geometry.
        command(&fixture.runtime, &mut model, Command::Scroll(20)).expect("scroll");
        let scrolled = lomo_tui::reader::page(&model).expect("page after scroll");
        assert!(
            scrolled.top > page.top,
            "the semantic anchor advanced: {} -> {}",
            page.top,
            scrolled.top
        );
        assert!(
            scrolled
                .pictures
                .iter()
                .all(|picture| scrolled.area.intersection(picture.rect) == picture.rect),
            "placements after scroll stay inside the reader area"
        );

        // An overlay owns the viewport: image placements leave the frame.
        model.input = InputMode::Message {
            title: "modal".to_owned(),
            lines: vec!["line".to_owned()],
            scroll: 0,
        };
        let overlaid = lomo_tui::reader::page(&model).expect("page under overlay");
        assert!(
            overlaid.pictures.is_empty(),
            "a modal input clears image placements (the overlay owns the cells)"
        );
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).expect("terminal");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, &model))
            .expect("overlay draw");
        let drawn = terminal.backend().buffer();
        let any_apc = (0u16..80)
            .flat_map(|x| (0u16..24).map(move |y| (x, y)))
            .any(|position| {
                drawn
                    .cell(position)
                    .is_some_and(|cell| cell.symbol().contains("\x1b_G"))
            });
        assert!(!any_apc, "no kitty payload leaks under the modal");
        model.input = InputMode::Browse;

        // Resize changes request identity → images requeue, decode again.
        let before: Vec<_> = model
            .images
            .iter()
            .map(|image| (image.request.columns, image.request.rows))
            .collect();
        update::apply_resize(&mut model, 120, 30);
        let effect = graphics::hydrate_images(&mut model);
        assert!(
            matches!(effect, Some(Effect::LoadImage { .. })),
            "a geometry change requeues the images: {effect:?}"
        );
        let after: Vec<_> = model
            .images
            .iter()
            .map(|image| (image.request.columns, image.request.rows))
            .collect();
        assert_ne!(before, after, "the request box tracks the new geometry");
        run_effect(&fixture.runtime, &mut model, effect).expect("re-decode");
        assert!(
            model
                .images
                .iter()
                .all(|image| matches!(image.state, ImageState::Ready(_))),
            "resized requests decode again"
        );

        // Leaving the reader retires the image registry and cancels intent.
        command(&fixture.runtime, &mut model, Command::Back).expect("back to feed");
        let retired = graphics::hydrate_images(&mut model);
        assert!(retired.is_none());
        assert!(
            model.images.is_empty(),
            "leaving the reader releases every image"
        );
    }

    /// The 64 MiB ready budget evicts oldest-first and never requeues an
    /// evicted request under the same identity — a full cache settles instead
    /// of decoding in a loop.
    #[test]
    fn the_ready_budget_evicts_oldest_and_never_requeues_the_same_request() {
        let picker = probed_picker(ProtocolType::Kitty);
        // A 300×300-cell box at (8,16) px cells fits the 4×4 square to
        // 2400×2400 px ≈ 21.9 MiB of resident raster each.
        let bytes = png(4, 4, [10, 20, 30, 255]);
        let version = MemoVersion {
            id: MemoId::parse("memo-budget").expect("id"),
            revision: 1,
            fingerprint: "fp".to_owned(),
        };
        let request = |path: &str| ImageRequest {
            version: version.clone(),
            path: RelativeWorkspacePath::parse(path).expect("path"),
            columns: 300,
            rows: 300,
            picker: SharedPicker::new(probed_picker(ProtocolType::Kitty)),
        };
        let mut model = AppModel::new(80, 24);
        let req_a = request("media/a.png");
        let req_b = request("media/b.png");
        let req_c = request("media/c.png");
        for request in [&req_a, &req_b, &req_c] {
            model.images.push(ReaderImage {
                request: request.clone(),
                state: ImageState::Pending,
            });
        }

        for request in [&req_a, &req_b] {
            let image = graphics::prepare_image(
                &bytes,
                request.columns,
                request.rows,
                &picker,
                &CancelToken::live(),
            )
            .expect("prepare");
            assert!(image.bytes() > 16 * 1024 * 1024, "a real sized raster");
            graphics::apply_image(&mut model, request, Ok(std::sync::Arc::new(image)));
        }
        assert!(
            model
                .images
                .iter()
                .take(2)
                .all(|image| matches!(image.state, ImageState::Ready(_))),
            "two ~22MiB images fit the budget together: {:?}",
            model.images
        );

        let image_c = graphics::prepare_image(&bytes, 300, 300, &picker, &CancelToken::live())
            .expect("prepare");
        graphics::apply_image(&mut model, &req_c, Ok(std::sync::Arc::new(image_c)));
        assert!(
            matches!(
                model.images.first().map(|image| &image.state),
                Some(ImageState::Evicted)
            ),
            "the oldest ready image is evicted: {:?}",
            model.images.first()
        );
        assert!(
            model
                .images
                .iter()
                .skip(1)
                .all(|image| matches!(image.state, ImageState::Ready(_))),
            "the newer ready images stay resident"
        );
        // The evicted request is still in the registry but must never requeue.
        let evicted = model.images.first().expect("evicted entry");
        assert!(
            !evicted.state.needs_load(&model.pending),
            "Evicted never requeues under an unchanged request identity"
        );
    }

    /// A single image whose fitted raster exceeds the budget lands as a
    /// visible `Failed`, never a silent resident or an eviction loop.
    #[test]
    fn an_over_budget_image_is_a_visible_failure_not_an_eviction_loop() {
        let picker = probed_picker(ProtocolType::Kitty);
        // 520×260 cells at (8,16) ≈ 4160×4160 px ≈ 66.5 MiB > 64 MiB budget.
        let image = graphics::prepare_image(
            &png(4, 4, [1, 2, 3, 255]),
            520,
            260,
            &picker,
            &CancelToken::live(),
        )
        .expect("prepare oversized");
        assert!(image.bytes() > graphics::READY_IMAGE_BUDGET_BYTES);
        let request = ImageRequest {
            version: MemoVersion {
                id: MemoId::parse("memo-huge").expect("id"),
                revision: 1,
                fingerprint: "fp".to_owned(),
            },
            path: RelativeWorkspacePath::parse("media/huge.png").expect("path"),
            columns: 500,
            rows: 500,
            picker: SharedPicker::new(picker),
        };
        let mut model = AppModel::new(80, 24);
        model.images.push(ReaderImage {
            request: request.clone(),
            state: ImageState::Pending,
        });
        graphics::apply_image(&mut model, &request, Ok(std::sync::Arc::new(image)));
        let ImageState::Failed(diagnostic) = &model.images.first().expect("entry").state else {
            panic!("an over-budget image must fail visibly, not wedge the cache");
        };
        assert!(
            diagnostic.contains("exceeds"),
            "the failure names the budget: {diagnostic}"
        );
    }

    /// EVIDENCE OF A DEPENDENCY DEFECT (RED — ratatui-image 8.0.1
    /// halfblocks.rs:49): `Halfblocks::encode` computes
    /// `(rect.width * rect.height)` in u16 — any fitted cell area above
    /// `65_535` cells panics the decode worker instead of producing an image.
    /// Reachable whenever the terminal is large enough that a fitted image
    /// occupies >256×256 cells (e.g. a tall image on a big window). The
    /// required contract: `prepare_image` returns a typed `Err`, never panics.
    #[test]
    fn a_halfblocks_encode_must_survive_a_wide_render_box() {
        let picker = probed_picker(ProtocolType::Halfblocks);
        // A 1:2 source in a 300×300-cell box fits to 300×300 cells = 90_000 >
        // u16::MAX — the u16 product in `Halfblocks::encode` overflows.
        graphics::prepare_image(
            &png(4, 8, [1, 2, 3, 255]),
            300,
            300,
            &picker,
            &CancelToken::live(),
        )
        .expect("a large render box must encode or fail typed — never panic");
    }

    /// Eviction is not sticky across identity: a resize changes the request
    /// dims, the reconciler drops the `Evicted` state and the image decodes
    /// again — proof the settled cache responds to geometry change.
    #[test]
    fn an_evicted_image_reloads_only_when_its_request_identity_changes() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/e.png", &png(4, 4, [5, 6, 7, 255]));
        seed_memo(&fixture, "![](media/e.png)");
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);
        install_kitty_verdict(&fixture, &mut model);

        // Demote to Evicted — the state the budget produces.
        let entry = model.images.first_mut().expect("image entry");
        entry.state = ImageState::Evicted;
        assert!(
            graphics::hydrate_images(&mut model).is_none(),
            "an unchanged request must never requeue after eviction"
        );

        update::apply_resize(&mut model, 96, 40);
        let effect = graphics::hydrate_images(&mut model);
        assert!(
            matches!(effect, Some(Effect::LoadImage { .. })),
            "a request-identity change must requeue the evicted image"
        );
        run_effect(&fixture.runtime, &mut model, effect).expect("reload");
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Ready(_))),
            "the evicted image decodes again under the new identity"
        );
    }

    /// D-08 residual check — whether a Ready image still repeats its authored
    /// `[Image: dest]` placeholder above the pixels (the finding's stated fix
    /// was suppression). Evidence: the merged reader rows must NOT contain the
    /// placeholder text once the image decoded.
    #[test]
    fn a_ready_image_must_not_repeat_its_placeholder_text() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/shown.png", &png(4, 4, [8, 8, 8, 255]));
        seed_memo(
            &fixture,
            "before text\n\n![](media/shown.png)\n\nafter text",
        );
        let mut model =
            ops::bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        open_reader_with_body(&fixture, &mut model);
        install_kitty_verdict(&fixture, &mut model);

        let page = lomo_tui::reader::page(&model).expect("reader page");
        let rows_with_placeholder: Vec<String> = page
            .rows
            .iter()
            .map(|row| {
                row.line
                    .spans
                    .iter()
                    .map(|span| span.content.as_ref().to_owned())
                    .collect::<String>()
            })
            .filter(|text| text.contains("[Image:"))
            .collect();
        assert!(
            rows_with_placeholder.is_empty(),
            "a Ready image's site must not still render its `[Image:]` placeholder \
             (D-08): {rows_with_placeholder:?}"
        );
    }

    /// D-05/D-06 end-to-end: `open_attachment` stages the digest-named copy,
    /// spawns the configured player with stdin/stdout detached and stderr
    /// captured-and-bounded, then reports `PlayerFinished` on the outbox.
    /// The real `sh` proves the stdio contract — no fake runner.
    #[test]
    fn the_player_process_is_detached_bounded_and_reported() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/clip.mp3", &[7u8; 512]);
        seed_memo(&fixture, "attach media/clip.mp3");

        // Reconfigure the hot `player` field to a real shell.
        let mut config = fixture.runtime.config();
        config.player = vec![
            "sh".to_owned(),
            "-c".to_owned(),
            "if read -r line; then printf 'stdin-live\\n' >&2; else printf 'stdin-eof\\n' >&2; fi; \
             printf 'stdout-only\\n' >&1; \
             i=0; while [ \"$i\" -lt 40 ]; do printf 'noise-0123456789\\n' >&2; i=$((i+1)); done; \
             exit 7"
                .to_owned(),
        ];
        let outcome = fixture.runtime.apply_config(config);
        assert!(
            !outcome.applied.is_empty(),
            "the player field is hot — it must apply live: {outcome:?}"
        );

        let path = RelativeWorkspacePath::parse("media/clip.mp3").expect("path");
        let mut model = AppModel::new(80, 24);
        let effect = update::apply_command(&mut model, Command::OpenAttachment(path))
            .expect("the attachment command mints the open effect");
        assert_eq!(
            effect.lane(),
            Lane::Io,
            "player spawn runs off the query lane"
        );

        let (results, inbox) = mpsc::sync_channel::<RuntimeMessage>(16);
        let outbox = Outbox::new(results);
        let reply = ops::execute(&fixture.runtime, &effect, &outbox, &CancelToken::live())
            .expect("the open effect resolves to a toast reply");
        assert!(
            matches!(reply, RuntimeMessage::Message { .. }),
            "the open reply is a user notice: {reply:?}"
        );

        staged_copy_is_digest_named(&fixture);
        player_exit_report_is_bounded_and_truthful(&inbox);
        player_badge_tracks_the_exit(&mut model);
    }

    /// The staged copy is digest-named under the cache dir (D-05).
    fn staged_copy_is_digest_named(fixture: &RuntimeFixture) {
        let staged: Vec<_> = std::fs::read_dir(&fixture.runtime.paths.cache_dir)
            .expect("cache dir")
            .flatten()
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("attachment-")
            })
            .collect();
        assert_eq!(staged.len(), 1, "one digest-named staging copy");
    }

    /// The monitor thread reports the exit — captured stderr merges into
    /// the diagnostic, bounded at 16 KiB.
    fn player_exit_report_is_bounded_and_truthful(inbox: &mpsc::Receiver<RuntimeMessage>) {
        let report = inbox
            .recv_timeout(Duration::from_secs(10))
            .expect("the player exit must be reported, not dropped");
        let RuntimeMessage::PlayerFinished {
            success,
            diagnostic,
        } = report
        else {
            panic!("the monitor reports PlayerFinished, got {report:?}");
        };
        assert!(!success, "exit 7 is a failure");
        let diagnostic = diagnostic.expect("a failed player carries a diagnostic");
        assert!(
            diagnostic.contains("exit status: 7"),
            "the status lands in the diagnostic: {diagnostic}"
        );
        assert!(
            diagnostic.contains("stdin-eof"),
            "the player's stdin is detached (EOF): {diagnostic:?}"
        );
        assert!(
            !diagnostic.contains("stdout-only"),
            "the player's stdout is discarded, never captured into the UI"
        );
        assert!(
            diagnostic.len() <= 16 * 1024 + 128,
            "captured stderr is bounded: {} bytes",
            diagnostic.len()
        );
    }

    /// The failure lands as a persistent player badge (I9); success clears it.
    fn player_badge_tracks_the_exit(model: &mut AppModel) {
        drop(lomo_tui::messages::apply_message(
            model,
            RuntimeMessage::PlayerFinished {
                success: false,
                diagnostic: Some("player exited exit status: 7".to_owned()),
            },
        ));
        assert!(
            model
                .badges
                .iter()
                .any(|badge| badge.class == BadgeClass::Player),
            "a nonzero player exit raises the Player badge"
        );
        drop(lomo_tui::messages::apply_message(
            model,
            RuntimeMessage::PlayerFinished {
                success: true,
                diagnostic: None,
            },
        ));
        assert!(
            model
                .badges
                .iter()
                .all(|badge| badge.class != BadgeClass::Player),
            "a clean player exit retires the badge"
        );
    }

    /// EVIDENCE OF A REMAINING DEFECT (RED — media.rs:144): the stderr cap is
    /// implemented as `reader.take(16KiB).read_to_end()` then *dropping* the
    /// pipe read end — once the cap is hit the next `stderr` write gets EPIPE
    /// and the player is killed by SIGPIPE. A chatty player therefore never
    /// reaches its own exit status; the report reads `signal: 13` instead of
    /// the real exit code. Required contract: bounded capture must
    /// drain-and-discard past the cap, preserving the player's own status.
    #[test]
    fn a_chatty_player_must_not_be_killed_by_the_stderr_cap() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/loud.mp3", &[5u8; 128]);
        seed_memo(&fixture, "attach");
        let mut config = fixture.runtime.config();
        config.player = vec![
            "sh".to_owned(),
            "-c".to_owned(),
            // ~36 KiB of stderr — well past the 16 KiB capture cap.
            "i=0; while [ \"$i\" -lt 2000 ]; do printf 'noise-0123456789\\n' >&2; i=$((i+1)); done; \
             exit 7"
                .to_owned(),
        ];
        drop(fixture.runtime.apply_config(config));
        let path = RelativeWorkspacePath::parse("media/loud.mp3").expect("path");
        let mut model = AppModel::new(80, 24);
        let effect = update::apply_command(&mut model, Command::OpenAttachment(path))
            .expect("effect minted");
        let (results, inbox) = mpsc::sync_channel::<RuntimeMessage>(4);
        let outbox = Outbox::new(results);
        drop(
            ops::execute(&fixture.runtime, &effect, &outbox, &CancelToken::live())
                .expect("open resolves"),
        );
        let report = inbox
            .recv_timeout(Duration::from_secs(10))
            .expect("the player exit must be reported");
        let RuntimeMessage::PlayerFinished {
            success,
            diagnostic,
        } = report
        else {
            panic!("PlayerFinished expected, got {report:?}");
        };
        assert!(!success);
        let diagnostic = diagnostic.expect("a failed player carries a diagnostic");
        assert!(
            diagnostic.contains("exit status: 7"),
            "the player must reach its own exit status — SIGPIPE from the \
             dropped stderr pipe must not kill it first: {diagnostic:?}"
        );
    }

    /// D-05: attachment staging is content-addressed — replaying the same
    /// bytes produces one digest-named file, and distinct payloads stage to
    /// distinct names. No temp-dir exchange and no bare temp files appear.
    #[test]
    fn attachment_staging_is_content_addressed_and_deduplicated() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let bytes = png(4, 4, [7, 8, 9, 255]);
        seed_file(&fixture, "media/clip.png", &bytes);
        seed_memo(&fixture, "attach");
        let mut model = AppModel::new(80, 24);
        let (results, _inbox) = mpsc::sync_channel::<RuntimeMessage>(8);
        let outbox = Outbox::new(results);
        let path = RelativeWorkspacePath::parse("media/clip.png").expect("path");
        for _ in 0..2 {
            let effect = update::apply_command(&mut model, Command::OpenAttachment(path.clone()))
                .expect("effect minted");
            ops::execute(&fixture.runtime, &effect, &outbox, &CancelToken::live())
                .expect("staging succeeds");
        }
        let staged: Vec<_> = std::fs::read_dir(&fixture.runtime.paths.cache_dir)
            .expect("cache dir")
            .flatten()
            .map(|entry| entry.file_name().to_string_lossy().to_string())
            .filter(|name| name.starts_with("attachment-"))
            .collect();
        let [staged_name] = staged.as_slice() else {
            panic!("identical bytes must stage to one digest-named file: {staged:?}");
        };
        let expected = lomo_media::ContentDigest::of_slice(&bytes);
        assert_eq!(
            staged_name,
            &format!("attachment-{}.png", expected.as_str()),
            "the staged name is the content digest, not a random temp name"
        );
    }

    /// A missing player binary is a typed spawn error, not a panic or a hang.
    #[test]
    fn a_missing_player_binary_is_a_typed_error() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_file(&fixture, "media/clip.mp3", &[9u8; 64]);
        seed_memo(&fixture, "attach");
        let mut config = fixture.runtime.config();
        config.player = vec!["definitely-not-a-real-binary-xyz".to_owned()];
        drop(fixture.runtime.apply_config(config));
        let path = RelativeWorkspacePath::parse("media/clip.mp3").expect("path");
        let mut model = AppModel::new(80, 24);
        let effect = update::apply_command(&mut model, Command::OpenAttachment(path))
            .expect("effect minted");
        let (results, _inbox) = mpsc::sync_channel::<RuntimeMessage>(4);
        let outbox = Outbox::new(results);
        let error = ops::execute(&fixture.runtime, &effect, &outbox, &CancelToken::live())
            .expect_err("a missing binary must fail the spawn");
        assert!(
            matches!(error, TuiError::Player { .. }),
            "spawn failures keep the typed diagnostic: {error:?}"
        );
    }

    /// D-12: `Effect::MediaSweep` guards the compose buffer *and* every editor
    /// draft file under `drafts_dir` — only the truly unreferenced file moves.
    #[test]
    fn the_media_sweep_protects_compose_and_editor_draft_media() {
        let fixture = RuntimeFixture::new().expect("fixture");
        for (name, bytes) in [
            ("media/keep.png", png(2, 2, [1, 1, 1, 255])),
            ("media/compose.png", png(2, 2, [2, 2, 2, 255])),
            ("media/editor.png", png(2, 2, [3, 3, 3, 255])),
            ("media/orphan.png", png(2, 2, [4, 4, 4, 255])),
        ] {
            seed_file(&fixture, name, &bytes);
        }
        seed_memo(&fixture, "see ![](media/keep.png)");
        std::fs::create_dir_all(&fixture.runtime.paths.drafts_dir).expect("drafts dir");
        std::fs::write(
            fixture.runtime.paths.drafts_dir.join("draft-1.md"),
            "mid-edit ![](media/editor.png)",
        )
        .expect("editor draft");

        let mut model = AppModel::new(80, 24);
        let compose_guard = lomo_application::GuardedDraftBody {
            owner_id: "tui-compose".to_owned(),
            content: "draft ![](media/compose.png)".to_owned(),
        };
        let req = model.request(PendingKind::Maintenance);
        let effect = Effect::MediaSweep {
            req,
            drafts: vec![compose_guard.clone()],
        };
        assert_eq!(
            effect.lane(),
            Lane::Maint,
            "housekeeping stays low-priority"
        );
        let (results, _inbox) = mpsc::sync_channel::<RuntimeMessage>(4);
        let outbox = Outbox::new(results);
        let reply = execute_settled(&fixture, &effect, &outbox);
        let RuntimeMessage::MediaSweepDone {
            moved,
            purged,
            failures,
            ..
        } = reply
        else {
            panic!("the sweep answers MediaSweepDone, got {reply:?}");
        };
        assert_eq!(
            (moved, purged, failures),
            (1, 0, 0),
            "only the orphan moves"
        );
        let workspace = &fixture.runtime.workspace;
        assert!(
            workspace.join("media/keep.png").exists(),
            "memo-referenced media stays"
        );
        assert!(
            workspace.join("media/compose.png").exists(),
            "compose-draft media is guarded"
        );
        assert!(
            workspace.join("media/editor.png").exists(),
            "editor-draft media is guarded"
        );
        assert!(
            !workspace.join("media/orphan.png").exists(),
            "the orphan left the media tree"
        );
        assert!(
            subtree_has_file(&workspace.join(".lomo-media-trash"), "orphan.png"),
            "the orphan lands in durable .lomo-media-trash"
        );

        // Dropping the editor draft removes its guard — the file sweeps next
        // run; the compose guard still rides the effect.
        std::fs::remove_file(fixture.runtime.paths.drafts_dir.join("draft-1.md"))
            .expect("delete draft");
        let req2 = model.request(PendingKind::Maintenance);
        let effect2 = Effect::MediaSweep {
            req: req2,
            drafts: vec![compose_guard],
        };
        let reply2 = execute_settled(&fixture, &effect2, &outbox);
        let RuntimeMessage::MediaSweepDone { moved: moved2, .. } = reply2 else {
            panic!("MediaSweepDone expected, got {reply2:?}");
        };
        assert_eq!(moved2, 1, "only the unguarded editor file now moves");
        assert!(!workspace.join("media/editor.png").exists());
        assert!(
            workspace.join("media/compose.png").exists(),
            "the still-guarded compose media survives the second sweep"
        );
    }

    /// `land_media_sweep` only speaks when work actually happened — a clean
    /// sweep leaves the status line alone, a real collection reports it.
    #[test]
    fn sweep_landing_only_reports_real_work() {
        let mut model = AppModel::new(80, 24);
        let req = model.request(PendingKind::Maintenance);
        drop(lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::MediaSweepDone {
                req,
                moved: 0,
                purged: 0,
                failures: 0,
            },
        ));
        assert!(
            model.status.is_none(),
            "a silent housekeeping sweep never touches the status line"
        );
        let req2 = model.request(PendingKind::Maintenance);
        drop(lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::MediaSweepDone {
                req: req2,
                moved: 2,
                purged: 0,
                failures: 0,
            },
        ));
        let status = model.status.clone().expect("a real collection reports");
        assert!(
            status.contains("Media sweep") || status.contains("媒体清理"),
            "the sweep receipt lands on the status line: {status}"
        );
    }
}
