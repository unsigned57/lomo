//! Behavior Contract
//! Capability: attachment images decode and encode off the draw path on a probed
//! picker, and placements obey reader visibility.
//! Scenarios: kitty/iTerm2/sixel payloads carry protocol escapes into the frame
//! buffer, halfblocks render real pixel colors, a corrupt attachment is a visible
//! failure, zero boxes are rejected, probing/unsupported verdicts mint no work,
//! kitty transmits its payload exactly once, and overlays drop placements.
//! Observable outcomes: protocol bytes land in buffer cells, `area()` stays inside
//! the request box, `bytes()` accounts the resident raster, no image state before
//! a `Ready` verdict.
//! TDD proof: /tmp/lomo-tui-pty-check.py failed because Enter sent no Kitty image
//! payload; Esc deletion is also checked.
//! Excludes: terminal-specific pixel rasterization and a real Wayland clipboard.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, command, model_with_memos, ready_graphics, run_effect};
    use lomo_tui::{
        event::Command,
        graphics::{ImageState, TerminalImage, prepare_image},
        media::rgba_to_png,
        model::{AppModel, CancelToken, InputMode},
        ops::bootstrap_model,
    };
    use ratatui::{buffer::Buffer, layout::Rect, style::Color, widgets::StatefulWidget};
    use ratatui_image::{
        StatefulImage,
        picker::{Picker, ProtocolType},
        protocol::StatefulProtocol,
    };
    use std::sync::Arc;

    fn png() -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        rgba_to_png(16, 16, &[255, 80, 20, 255].repeat(256))
            .map_err(|error| format!("{error:?}").into())
    }

    /// A picker the way `StdioProber` reports one: queried font metrics plus an
    /// explicit protocol answer.
    fn picker(protocol: ProtocolType) -> Picker {
        let mut picker = Picker::from_fontsize((8, 16));
        picker.set_protocol_type(protocol);
        picker
    }

    /// Render one prepared image into a fresh buffer and return the buffer —
    /// protocol escapes land in cell symbols (kitty APC, sixel data, iTerm2
    /// OSC) and halfblocks land as colored `▀` cells.
    fn rendered(image: &TerminalImage) -> Buffer {
        let area = image.area();
        let mut buffer = Buffer::empty(Rect::new(0, 0, area.width.max(1), area.height.max(1)));
        let mut protocol = image.lock_protocol();
        StatefulImage::<StatefulProtocol>::default().render(area, &mut buffer, &mut protocol);
        buffer
    }

    /// Every probed protocol prepares a fitted raster inside the request box
    /// and writes its real protocol cells on render — never on `prepare_image`'s
    /// caller's frame.
    #[test]
    fn probed_protocols_prepare_real_pixels_inside_the_cell_box() {
        let source = png().expect("fixture and operation must succeed");
        for protocol in [
            ProtocolType::Kitty,
            ProtocolType::Sixel,
            ProtocolType::Iterm2,
            ProtocolType::Halfblocks,
        ] {
            let image = prepare_image(&source, 20, 8, &picker(protocol), &CancelToken::live())
                .expect("fixture and operation must succeed");
            let area = image.area();
            assert!(
                area.width <= 20 && area.height <= 8 && area.width > 0 && area.height > 0,
                "{protocol:?} placement must fit the request box: {area:?}"
            );
            // The resident raster is the fitted RGBA — the budget accounts it.
            assert!(
                image.bytes() > 0 && image.bytes().is_multiple_of(4),
                "{protocol:?} resident bytes must be a real RGBA raster: {}",
                image.bytes()
            );
            let buffer = rendered(&image);
            let symbol = buffer
                .cell((0, 0))
                .expect("fixture and operation must succeed")
                .symbol();
            match protocol {
                ProtocolType::Kitty => assert!(
                    symbol.contains("\x1b_G"),
                    "kitty render must carry the APC transmit sequence: {symbol:?}"
                ),
                ProtocolType::Sixel => assert!(
                    symbol.contains("\x1bP"),
                    "sixel render must carry the DCS payload: {symbol:?}"
                ),
                ProtocolType::Iterm2 => assert!(
                    symbol.contains("1337;File="),
                    "iTerm2 render must carry the OSC 1337 payload: {symbol:?}"
                ),
                ProtocolType::Halfblocks => {
                    let cell = buffer
                        .cell((0, 0))
                        .expect("fixture and operation must succeed");
                    assert_eq!(cell.symbol(), "▀");
                    // The uniform orange fixture reaches the cell as a real
                    // RGB color — pixels, not a placeholder glyph.
                    assert_eq!(cell.fg, Color::Rgb(255, 80, 20));
                }
            }
        }
    }

    /// A kitty payload is transmitted exactly once from one protocol state:
    /// the first render writes the APC transmit sequence, the second only the
    /// unicode placeholders — redraws never re-upload the raster (D-07).
    #[test]
    fn kitty_transmits_its_payload_exactly_once_per_resize() {
        let image = prepare_image(
            &png().expect("fixture and operation must succeed"),
            20,
            8,
            &picker(ProtocolType::Kitty),
            &CancelToken::live(),
        )
        .expect("fixture and operation must succeed");
        let first = rendered(&image);
        assert!(
            first
                .cell((0, 0))
                .expect("fixture and operation must succeed")
                .symbol()
                .contains("\x1b_G"),
            "the first render transmits the image"
        );
        let second = rendered(&image);
        let symbol = second
            .cell((0, 0))
            .expect("fixture and operation must succeed")
            .symbol();
        assert!(
            !symbol.contains("\x1b_G"),
            "a second render must not retransmit: {symbol:?}"
        );
        assert!(
            symbol.contains('\u{10EEEE}'),
            "placeholders keep occupying the cells after transmit"
        );
    }

    /// A zero cell box is a visible error — no nominal geometry is invented —
    /// and corrupt bytes stay a visible decode failure, not an empty image.
    #[test]
    fn zero_boxes_and_corrupt_attachments_report_errors() {
        let zero = prepare_image(
            &png().expect("fixture and operation must succeed"),
            0,
            8,
            &picker(ProtocolType::Kitty),
            &CancelToken::live(),
        )
        .err()
        .ok_or("zero columns must fail")
        .expect("fixture and operation must succeed");
        assert!(zero.to_string().contains("graphics-capable"));
        let corrupt = prepare_image(
            b"not an image",
            20,
            8,
            &picker(ProtocolType::Kitty),
            &CancelToken::live(),
        )
        .err()
        .ok_or("corrupt image must fail")
        .expect("fixture and operation must succeed");
        assert!(corrupt.to_string().contains("image decode"));
    }

    /// No image work exists without a probe verdict: `Probing` and
    /// `Unsupported` mint nothing and clear resident state; only `Ready`
    /// unlocks the decode queue (D-03).
    #[test]
    fn image_work_only_runs_on_a_ready_probe_verdict() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media"))
            .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png().expect("fixture and operation must succeed"),
        )
        .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            "- 10:00:00\n![image](media/a.png)\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");

        assert_eq!(
            model.graphics,
            lomo_tui::graphics::GraphicsVerdict::Probing,
            "the model holds no capability until the probe answers"
        );
        assert!(
            lomo_tui::graphics::hydrate_images(&mut model).is_none(),
            "a pending probe mints no image requests"
        );
        model.graphics = lomo_tui::graphics::GraphicsVerdict::Unsupported {
            diagnostic: "no protocol answer".to_owned(),
        };
        assert!(
            lomo_tui::graphics::hydrate_images(&mut model).is_none() && model.images.is_empty(),
            "an unsupported verdict keeps placeholders and mints no work"
        );

        model.graphics = ready_graphics(ProtocolType::Halfblocks);
        let effect = lomo_tui::graphics::hydrate_images(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Ready(_))),
            "a ready verdict unlocks the decode queue: {:?}",
            model.images
        );
        let page = lomo_tui::reader::page(&model)
            .ok_or("reader")
            .expect("fixture and operation must succeed");
        assert_eq!(page.pictures.len(), 1, "the placement lands in the reader");
    }

    #[test]
    fn pictures_are_removed_from_overlays_and_from_the_returned_feed() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media"))
            .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png().expect("fixture and operation must succeed"),
        )
        .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            "- 10:00:00\n![image](media/a.png)\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        model.graphics = ready_graphics(ProtocolType::Kitty);
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::graphics::hydrate_images(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let page = lomo_tui::reader::page(&model)
            .ok_or("reader")
            .expect("fixture and operation must succeed");
        assert_eq!(page.pictures.len(), 1);
        assert!(
            page.pictures
                .iter()
                .all(|picture| picture.rect.intersection(page.area) == picture.rect)
        );
        model.input = InputMode::Help { scroll: 0 };
        assert!(
            lomo_tui::reader::page(&model)
                .ok_or("reader")
                .expect("fixture and operation must succeed")
                .pictures
                .is_empty()
        );
        model.input = InputMode::Browse;
        command(&fixture.runtime, &mut model, Command::Back)
            .expect("fixture and operation must succeed");
        assert!(lomo_tui::reader::page(&model).is_none());
    }

    /// The queried font metrics are the pixel-per-cell fact the fit is made in:
    /// a taller font fills the same cell box with proportionally more pixels —
    /// the resident raster is the terminal's reported metric, never an assumed
    /// 8×16 grid — and a font whose aspect changes the cell coverage changes
    /// the placed area.
    #[test]
    fn queried_font_metrics_drive_the_fitted_cell_area() {
        let source = png().expect("fixture and operation must succeed");
        let mut short = Picker::from_fontsize((8, 16));
        short.set_protocol_type(ProtocolType::Sixel);
        let mut tall = Picker::from_fontsize((16, 32));
        tall.set_protocol_type(ProtocolType::Sixel);
        let short_image = prepare_image(&source, 20, 8, &short, &CancelToken::live())
            .expect("fixture and operation must succeed");
        let tall_image = prepare_image(&source, 20, 8, &tall, &CancelToken::live())
            .expect("fixture and operation must succeed");
        // Height-bound square fit: 8 rows of cells in both cases, but the tall
        // font doubles the pixels per cell — the resident raster quadruples.
        assert_eq!(short_image.area(), Rect::new(0, 0, 16, 8));
        assert_eq!(tall_image.area(), Rect::new(0, 0, 16, 8));
        assert_eq!(short_image.bytes(), 128 * 128 * 4);
        assert_eq!(tall_image.bytes(), 256 * 256 * 4);
        // A font with a different aspect changes which axis binds the fit —
        // the cell coverage follows the reported metrics.
        let mut wide = Picker::from_fontsize((8, 20));
        wide.set_protocol_type(ProtocolType::Sixel);
        let wide_image = prepare_image(&source, 20, 8, &wide, &CancelToken::live())
            .expect("fixture and operation must succeed");
        assert_eq!(wide_image.area(), Rect::new(0, 0, 20, 8));
        let buffer = rendered(&wide_image);
        let symbol = buffer
            .cell((0, 0))
            .expect("fixture and operation must succeed")
            .symbol();
        assert!(
            symbol.contains("\x1bP"),
            "the sixel payload is encoded inside prepare_image: {symbol:?}"
        );
    }

    #[test]
    fn resize_keeps_images_and_rehydrates_only_changed_requests() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media"))
            .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("media/a.png"),
            png().expect("fixture and operation must succeed"),
        )
        .expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            "- 10:00:00\n![image](media/a.png)\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        model.graphics = ready_graphics(ProtocolType::Kitty);
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::graphics::hydrate_images(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert_eq!(model.images.len(), 1);
        let first = model.images.first().expect("one image");
        assert!(matches!(first.state, ImageState::Ready(_)));
        let before = first.request.clone();

        lomo_tui::update::apply_resize(&mut model, 40, 12);
        assert_eq!(
            model.images.len(),
            1,
            "resize must not blank reusable image state"
        );
        assert!(
            lomo_tui::graphics::hydrate_images(&mut model).is_some(),
            "a changed request must reload at the new geometry"
        );
        let first = model.images.first().expect("one image");
        assert!(
            matches!(first.state, ImageState::Loading(_)),
            "a resized image re-enters the load queue instead of being dropped"
        );
        assert_ne!(
            first.request.columns, before.columns,
            "the reload request carries the new dimensions"
        );

        lomo_tui::update::apply_resize(&mut model, 40, 12);
        assert!(
            matches!(
                model.images.first().map(|image| &image.state),
                Some(ImageState::Loading(_))
            ),
            "an identical resize must not re-pend an in-flight image"
        );
    }

    #[test]
    fn ready_images_obey_an_aggregate_byte_budget() {
        use lomo_tui::graphics::{ImageRequest, ImageState, READY_IMAGE_BUDGET_BYTES, ReaderImage};
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let memo = model
            .selected_memo()
            .map_or_else(|| panic!("memo fixture"), Clone::clone);
        let lomo_tui::graphics::GraphicsVerdict::Ready(picker) =
            ready_graphics(ProtocolType::Kitty)
        else {
            panic!("fixture verdict")
        };
        let request_at = |index: usize| ImageRequest {
            version: memo.version(),
            path: lomo_core::RelativeWorkspacePath::parse(&format!("media/{index}.png"))
                .expect("fixture and operation must succeed"),
            columns: 20,
            rows: 8,
            picker: picker.clone(),
        };
        // A real decoded raster sized so three admissions cross the aggregate
        // budget: halfblocks keeps the encode cheap while `bytes()` still
        // accounts the fitted image the protocol state retains.
        let fat_image = || {
            let mut fat_picker = Picker::from_fontsize((16, 16));
            fat_picker.set_protocol_type(ProtocolType::Halfblocks);
            prepare_image(
                &png().expect("fixture and operation must succeed"),
                170,
                160,
                &fat_picker,
                &CancelToken::live(),
            )
            .expect("fixture and operation must succeed")
        };
        let resident = fat_image().bytes();
        assert!(
            resident * 3 > READY_IMAGE_BUDGET_BYTES && resident * 2 < READY_IMAGE_BUDGET_BYTES,
            "fixture sizing: three admissions evict, two fit: {resident}"
        );
        for index in 0..3 {
            let request = request_at(index);
            model.images.push(ReaderImage {
                request: request.clone(),
                state: ImageState::Pending,
            });
            lomo_tui::graphics::apply_image(&mut model, &request, Ok(Arc::new(fat_image())));
        }
        let ready_bytes: usize = model
            .images
            .iter()
            .map(|image| match &image.state {
                ImageState::Ready(image) => image.bytes(),
                ImageState::Pending
                | ImageState::Loading(_)
                | ImageState::Failed(_)
                | ImageState::Evicted => 0,
            })
            .sum();
        assert!(
            ready_bytes <= READY_IMAGE_BUDGET_BYTES,
            "aggregate ready payload must stay bounded: {ready_bytes}"
        );
        assert!(
            matches!(
                model.images.first().map(|image| &image.state),
                Some(ImageState::Evicted)
            ),
            "the oldest ready image is evicted first — out of the load queue while its request is unchanged"
        );
        assert!(
            matches!(
                model.images.get(2).map(|image| &image.state),
                Some(ImageState::Ready(_))
            ),
            "the newest arrival stays resident"
        );
    }

    #[test]
    fn reader_places_images_at_their_source_reference() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let body = lomo_tui::content::MemoBody::parse(
            "intro paragraph\n\n![pic](media/a.png)\n\noutro paragraph".to_owned(),
        )
        .expect("fixture and operation must succeed");
        let mut memo = model.selected_memo().expect("memo").clone();
        memo.attachments = vec![
            lomo_core::RelativeWorkspacePath::parse("media/a.png")
                .expect("fixture and operation must succeed"),
        ];
        memo.body = lomo_tui::model::BodyState::Ready(Arc::new(body));
        model.view = lomo_tui::model::View::Reader {
            memo: memo.clone(),
            anchor: lomo_tui::model::TextAnchor::default(),
        };
        let lomo_tui::graphics::GraphicsVerdict::Ready(picker) =
            ready_graphics(ProtocolType::Kitty)
        else {
            panic!("fixture verdict")
        };
        let image = Arc::new(
            prepare_image(
                &png().expect("fixture and operation must succeed"),
                20,
                2,
                &picker,
                &CancelToken::live(),
            )
            .expect("fixture and operation must succeed"),
        );
        model.images = vec![lomo_tui::graphics::ReaderImage {
            request: lomo_tui::graphics::ImageRequest {
                version: memo.version(),
                path: lomo_core::RelativeWorkspacePath::parse("media/a.png")
                    .expect("fixture and operation must succeed"),
                columns: 20,
                rows: 2,
                picker,
            },
            state: ImageState::Ready(image),
        }];
        let page = lomo_tui::reader::page(&model).expect("reader page");
        let outro_row = page
            .rows
            .iter()
            .position(|row| {
                row.line
                    .spans
                    .iter()
                    .any(|span| span.content.contains("outro paragraph"))
            })
            .expect("outro line");
        let image_block_top = page
            .pictures
            .first()
            .map(|p| usize::from(p.rect.y - page.area.y))
            .expect("picture top");
        assert!(
            image_block_top < outro_row,
            "the image must render at its reference line, before 'outro' at row {outro_row} (image block top {image_block_top})"
        );
        let last_row_anchor = page.rows.last().map(|row| row.anchor);
        assert_ne!(
            last_row_anchor,
            Some(lomo_tui::model::TextAnchor {
                line: usize::MAX,
                grapheme: 0
            }),
            "images must not be appended after the whole body"
        );
    }
}
