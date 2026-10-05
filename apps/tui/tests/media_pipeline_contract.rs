// adversarial-audit: terminal image pipeline — probe → load → encode → place → emit.
//
// Claims under test (each test locks a behavior contract, not a style preference):
//  * attachment destinations canonicalize at the workspace fact authority, so
//    equivalent spellings share one key, external URLs never become local paths,
//    and feed/reader loading survives both (`queries::card` / `load_body`).
//  * terminal graphics capability is a query/response verdict, never an env
//    guess — no image work exists before the probe answers, a non-terminal
//    stdio fails closed, and `Unsupported`/`Probing` keep text placeholders.
//  * the protocol's own encoder (ratatui-image) produces the sixel payload
//    during `prepare_image` — the draw path writes prepared cells only.
//  * `stage_attachment` is digest-deduped and leaves no exchange artifact.
//  * the working chain (local `media/` attachment + a kitty verdict) reaches a
//    `StatefulProtocol` payload — included as the control case.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{RuntimeFixture, command, ready_graphics, run_effect};
    use lomo_tui::{
        effects::{Effect, RuntimeMessage},
        event::Command,
        graphics::{GraphicsVerdict, ImageState, StdioProber, TerminalProber, prepare_image},
        media::rgba_to_png,
        model::{AppModel, CancelToken},
        ops::bootstrap_model,
    };
    use ratatui::{buffer::Buffer, layout::Rect, widgets::StatefulWidget};
    use ratatui_image::{
        StatefulImage,
        picker::{Picker, ProtocolType},
        protocol::{StatefulProtocol, StatefulProtocolType},
    };

    fn png(width: u32, height: u32, pixel: [u8; 4]) -> Vec<u8> {
        rgba_to_png(width, height, &pixel.repeat((width * height) as usize)).expect("png fixture")
    }

    /// The picker the probe reports on a capable terminal: queried font metrics
    /// plus an explicit protocol answer, constructed without touching stdio.
    fn probed_picker(protocol: ProtocolType) -> Picker {
        let mut picker = Picker::from_fontsize((8, 16));
        picker.set_protocol_type(protocol);
        picker
    }

    /// Render a prepared image into a buffer and return the first cell's symbol —
    /// protocol payloads land there for kitty/sixel/iTerm2.
    fn first_cell_symbol(image: &lomo_tui::graphics::TerminalImage) -> String {
        let mut buffer = Buffer::empty(Rect::new(
            0,
            0,
            image.area().width.max(1),
            image.area().height.max(1),
        ));
        let mut protocol = image.lock_protocol();
        StatefulImage::<StatefulProtocol>::default().render(
            image.area(),
            &mut buffer,
            &mut protocol,
        );
        buffer
            .cell((0, 0))
            .expect("rendered buffer cell")
            .symbol()
            .to_owned()
    }

    fn seed_memo(fixture: &RuntimeFixture, body: &str) {
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            format!("- 10:00:00\n{body}\n"),
        )
        .expect("seed memo");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("rebuild");
    }

    /// A memo whose body contains `![](https://…)` keeps the raw destination in
    /// the workspace render facts — external objects stay distinguishable — but
    /// it never becomes a local attachment key: `attachment_ref`/`image_urls`
    /// get no row, and the feed card and reader degrade instead of failing.
    #[test]
    fn external_image_destination_never_enters_the_local_attachment_path_set() {
        let fixture = RuntimeFixture::new().expect("fixture");
        seed_memo(
            &fixture,
            "plain text before\n\n![](https://example.com/remote.png)",
        );

        // The workspace render fact still carries the raw destination —
        // distinguishable as external — while no local key exists.
        let document = lomo_workspace::render_markdown(
            &lomo_workspace::SourceBytes::try_from_str(
                "plain text before\n\n![](https://example.com/remote.png)",
            )
            .expect("source"),
        )
        .expect("render");
        assert_eq!(
            document
                .attachment_destinations()
                .iter()
                .map(lomo_workspace::ImageDest::projected)
                .collect::<Vec<_>>(),
            ["https://example.com/remote.png"],
            "the raw external destination stays visible in the render facts"
        );
        assert!(
            document
                .attachment_destinations()
                .iter()
                .all(|destination| {
                    matches!(destination, lomo_workspace::ImageDest::External(_))
                }),
            "the external destination stays classified as non-local"
        );

        let query = lomo_application::MemoQuery {
            search_text: None,
            filters: lomo_application::MemoFilters::default(),
            sort: lomo_application::MemoSort::default(),
        };
        let page = fixture
            .runtime
            .session
            .query_memos_page(
                &query,
                None,
                None,
                lomo_core::PageSize::new(48).expect("size"),
            )
            .expect("projection page");
        let item = page.items.first().expect("one projected memo");
        assert!(
            item.image_urls.is_empty(),
            "an external destination produces no local image row: {:?}",
            item.image_urls
        );

        // The external destination names no workspace file: no attachment key,
        // no sweep observation, no typed path — and no failure.
        let card = lomo_tui::queries::card(item.clone(), &fixture.runtime)
            .expect("one external image destination must not fail card()");
        assert!(
            card.attachments.is_empty(),
            "an external destination cannot become a workspace attachment path"
        );
        let feed =
            lomo_tui::queries::load_feed(&fixture.runtime, lomo_tui::model::FeedKind::Timeline)
                .expect("the feed page must survive an external image destination");
        assert_eq!(feed.memos.len(), 1);
        let id = lomo_workspace::MemoId::parse(&item.memo_id).expect("memo id");
        let body = lomo_tui::queries::load_body(&fixture.runtime, &id)
            .expect("reader load_body survives the same external destination")
            .expect("the seeded memo is present");
        assert!(
            body.attachments.is_empty(),
            "the reader attachment set contains no external destination"
        );
        assert!(
            fixture
                .runtime
                .session
                .observe_attachments()
                .expect("attachment observations")
                .iter()
                .all(|observation| !observation.relative_path.contains("://")),
            "external destinations never become sweep-protection keys"
        );
    }

    /// `![](./media/a.png)` canonicalizes to `media/a.png` at the workspace fact
    /// authority, so the feed card, the reader, and body hydration all see the
    /// same attachment path the store indexes under `attachment_ref`.
    #[test]
    fn canonical_equivalent_spellings_load_in_feed_and_reader() {
        let fixture = RuntimeFixture::new().expect("fixture");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media")).expect("media dir");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png(4, 4, [1, 2, 3, 255]),
        )
        .expect("seed image");
        seed_memo(&fixture, "![dot](./media/a.png)");

        let feed =
            lomo_tui::queries::load_feed(&fixture.runtime, lomo_tui::model::FeedKind::Timeline)
                .expect("feed card loads on the canonical attachment key");
        let memo = feed.memos.first().expect("one memo");
        let version = memo.version();
        assert_eq!(
            memo.attachments
                .iter()
                .map(lomo_core::RelativeWorkspacePath::as_str)
                .collect::<Vec<_>>(),
            ["media/a.png"],
            "the card attachment is the canonical path"
        );
        let body = lomo_tui::queries::load_body(&fixture.runtime, &memo.id)
            .expect("reader body loads")
            .expect("the seeded memo is present");
        assert_eq!(
            body.attachments
                .iter()
                .map(lomo_core::RelativeWorkspacePath::as_str)
                .collect::<Vec<_>>(),
            ["media/a.png"],
            "reader attachments are canonical too"
        );
        lomo_tui::queries::load_version_body(&fixture.runtime, &version)
            .expect("the body-hydration path loads identically");
    }

    /// Two destinations that canonicalize to one file — `![](./media/a.png)`
    /// plus `![](media//a.png)` — project to a single attachment key, so the
    /// scanned evidence count and the `attachment_ref` rows agree and the
    /// rebuild commits instead of diverging.
    #[test]
    fn colliding_canonical_spellings_share_one_attachment_key() {
        let fixture = RuntimeFixture::new().expect("fixture");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media")).expect("media dir");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png(4, 4, [1, 2, 3, 255]),
        )
        .expect("seed image");
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            "- 10:00:00\n![dot](./media/a.png)\n\n![slashes](media//a.png)\n",
        )
        .expect("seed memo");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("aliased destination spellings rebuild to one key");
        let observations = fixture
            .runtime
            .session
            .observe_attachments()
            .expect("attachment observations");
        assert!(
            !observations.is_empty(),
            "the memo attachment must produce an observation"
        );
        assert!(
            observations
                .iter()
                .all(|observation| observation.relative_path == "media/a.png"),
            "every observation is the canonical key: {observations:?}"
        );
        // Both spellings resolve to — and protect — the same workspace file.
        for spelling in [
            "./media/a.png",
            "media//a.png",
            "media\\a.png",
            "media/a.png",
        ] {
            assert!(
                fixture
                    .runtime
                    .session
                    .attachment_is_protected(spelling)
                    .expect("protection query"),
                "{spelling} must protect the same canonical file"
            );
        }
    }

    /// Graphics capability is a query/response fact, never an env guess (D-03):
    /// a non-terminal stdio refuses the probe instead of inventing a protocol,
    /// the model mints no image work while `Probing`, and the `Ready` verdict
    /// — delivered as a runtime message, not a startup env read — unlocks the
    /// decode queue. WezTerm/Ghostty/Konsole/foot simply answer the query.
    #[test]
    fn capability_is_a_probe_verdict_never_an_env_guess() {
        use std::io::IsTerminal;
        // The probe refuses before writing a query when stdio cannot answer —
        // on a real terminal `probe()` performs the live handshake instead.
        if !std::io::stdin().is_terminal() || !std::io::stdout().is_terminal() {
            let verdict = TerminalProber::probe(&StdioProber);
            assert!(
                matches!(verdict, GraphicsVerdict::Unsupported { .. }),
                "a non-answering stdio pair fails closed: {verdict:?}"
            );
        }

        let fixture = RuntimeFixture::new().expect("fixture");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media")).expect("media dir");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png(4, 4, [1, 2, 3, 255]),
        )
        .expect("seed image");
        seed_memo(&fixture, "![dot](media/a.png)");
        let mut model =
            bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        command(&fixture.runtime, &mut model, Command::Accept).expect("open reader");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect).expect("body hydration");

        assert_eq!(
            model.graphics,
            GraphicsVerdict::Probing,
            "no capability exists before the probe answers — env names are never consulted"
        );
        assert!(
            lomo_tui::graphics::hydrate_images(&mut model).is_none(),
            "a probing terminal mints no image requests"
        );

        // The verdict installs through the message channel; a ready answer
        // kicks the hydration effect itself so a live reader starts decoding.
        let follow_up = lomo_tui::messages::apply_message(
            &mut model,
            RuntimeMessage::GraphicsDetected {
                verdict: ready_graphics(ProtocolType::Kitty),
            },
        );
        assert!(
            matches!(follow_up, Some(Effect::LoadImage { .. })),
            "a ready verdict must mint the image load: {follow_up:?}"
        );
        run_effect(&fixture.runtime, &mut model, follow_up).expect("image load");
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Ready(_))),
            "the probed kitty verdict decodes the attachment: {:?}",
            model.images
        );
    }

    /// The sixel payload is produced by the protocol's own encoder during
    /// `prepare_image` — the fitted raster is bounded by the box in queried
    /// font metrics, the prepared DCS lands in one buffer cell, and the draw
    /// path performs no pixel work (B-06).
    #[test]
    fn sixel_payload_is_encoded_by_the_protocol_off_the_draw_path() {
        let picker = probed_picker(ProtocolType::Sixel);
        let source = png(240, 180, [7, 42, 200, 255]);
        let image =
            prepare_image(&source, 30, 12, &picker, &CancelToken::live()).expect("sixel prepare");
        assert!(
            image.bytes() <= 240 * 192 * 4,
            "the fitted raster stays inside the request box in pixel metrics: {}",
            image.bytes()
        );
        assert!(image.area().width <= 30 && image.area().height <= 12);
        let symbol = first_cell_symbol(&image);
        let prefix: String = symbol.chars().take(16).collect();
        assert!(
            symbol.contains("\x1bPq"),
            "the prepared sixel DCS lands in the buffer cell: {prefix}…"
        );
        assert!(
            symbol.ends_with("\x1b\\"),
            "the sixel string is properly terminated"
        );
    }

    /// `stage_attachment` writes the player copy digest-named under the cache
    /// dir and never touches the exchange channel — repeated opens dedupe to
    /// one file and leave no durable artifact behind (D-05).
    #[test]
    fn stage_attachment_dedupes_by_digest_without_exchange_artifacts() {
        let fixture = RuntimeFixture::new().expect("fixture");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media")).expect("media dir");
        let bytes = vec![7u8; 4096];
        std::fs::write(fixture.runtime.workspace.join("media/a.mp3"), &bytes).expect("seed audio");
        let path = lomo_core::RelativeWorkspacePath::parse("media/a.mp3").expect("path");
        let exchange_before: Vec<_> = std::fs::read_dir(&fixture.runtime.paths.exchange_dir)
            .expect("exchange dir")
            .collect::<Result<Vec<_>, _>>()
            .expect("readable");
        let first =
            lomo_tui::mutations::stage_attachment(&fixture.runtime, &path).expect("stage once");
        let second =
            lomo_tui::mutations::stage_attachment(&fixture.runtime, &path).expect("stage twice");
        assert_eq!(first, second, "the digest name dedupes identical bytes");
        let exchange_after: Vec<_> = std::fs::read_dir(&fixture.runtime.paths.exchange_dir)
            .expect("exchange dir")
            .collect::<Result<Vec<_>, _>>()
            .expect("readable");
        assert_eq!(
            exchange_before.len(),
            exchange_after.len(),
            "staging writes nothing into the durable exchange channel"
        );
        let cache_files: Vec<_> = std::fs::read_dir(&fixture.runtime.paths.cache_dir)
            .expect("cache dir")
            .collect::<Result<Vec<_>, _>>()
            .expect("readable")
            .into_iter()
            .filter(|e| e.file_name().to_string_lossy().starts_with("attachment-"))
            .collect();
        assert_eq!(
            cache_files.len(),
            1,
            "the player copy is digest-named, deduped"
        );
    }

    /// Control case: clipboard import → `media/` → a kitty verdict arrives as
    /// `ImageState::Ready` with a stateful kitty protocol whose render carries
    /// the APC transmit — and the reader places it.
    #[test]
    fn the_working_chain_reaches_a_stateful_kitty_protocol() {
        let fixture = RuntimeFixture::new().expect("fixture");
        let relative = lomo_tui::mutations::import_clipboard_png(
            &fixture.runtime,
            &png(16, 16, [200, 30, 30, 255]),
        )
        .expect("clipboard import");
        assert_eq!(relative, "media/pasted.png");

        let mut model =
            bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("bootstrap");
        model.graphics = ready_graphics(ProtocolType::Kitty);
        command(&fixture.runtime, &mut model, Command::Accept).expect("open reader");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect).expect("body hydration");
        let effect = lomo_tui::graphics::hydrate_images(&mut model);
        run_effect(&fixture.runtime, &mut model, effect).expect("image load");

        let image = model
            .images
            .first()
            .expect("one reader image")
            .state
            .clone();
        let ImageState::Ready(image) = image else {
            panic!("the imported image must decode: {image:?}");
        };
        assert!(
            matches!(
                image.lock_protocol().protocol_type(),
                StatefulProtocolType::Kitty(_)
            ),
            "the kitty verdict produces kitty protocol state"
        );
        assert!(
            first_cell_symbol(&image).contains("\x1b_G"),
            "the first render carries the kitty APC transmit"
        );
        let page = lomo_tui::reader::page(&model).expect("reader page");
        assert_eq!(page.pictures.len(), 1, "placement exists for emit");
    }
}
