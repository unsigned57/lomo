//! Behavior Contract
//! Capability: actual terminal images decode off the draw path and obey reader visibility.
//! Scenarios: PNG/JPEG-capable decoder, Kitty/`iTerm2`/Sixel payloads, unsupported terminals, corrupt images,
//! partial visibility and input overlays.
//! Observable outcomes: real image protocol bytes, bounded dimensions and no visible image outside the reader.
//! TDD proof: /tmp/lomo-tui-pty-check.py failed because Enter sent no Kitty image payload; Esc deletion is also checked.
//! Excludes: terminal-specific pixel rasterization and a real Wayland clipboard.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, command, model_with_memos, run_effect};
    use base64::{Engine, engine::general_purpose::STANDARD};
    use lomo_tui::{
        event::Command,
        graphics::{CellSize, prepare_image},
        media::{GraphicsProtocol, rgba_to_png},
        model::{AppModel, InputMode},
        ops::bootstrap_model,
    };
    use std::sync::Arc;

    fn png() -> Result<Vec<u8>, Box<dyn std::error::Error>> {
        rgba_to_png(16, 16, &[255, 80, 20, 255].repeat(256))
            .map_err(|error| format!("{error:?}").into())
    }

    fn cells() -> CellSize {
        CellSize::reported(8, 16, 1, 1).expect("reported cell grid")
    }

    #[test]
    fn supported_protocols_contain_real_decoded_pixels_and_respect_cell_limits() {
        let source = png().expect("fixture and operation must succeed");
        let kitty = prepare_image(&source, 20, 8, GraphicsProtocol::Kitty, cells())
            .expect("fixture and operation must succeed");
        assert!(kitty.columns <= 20 && kitty.rows <= 8);
        let payload =
            std::str::from_utf8(&kitty.payload).expect("fixture and operation must succeed");
        assert!(payload.starts_with("\x1b_Ga=T,f=100"));
        let (_, encoded) = payload
            .split_once(';')
            .ok_or("payload separator")
            .expect("fixture and operation must succeed");
        let encoded = encoded
            .strip_suffix("\x1b\\")
            .ok_or("payload terminator")
            .expect("fixture and operation must succeed");
        let decoded = image::load_from_memory(
            &STANDARD
                .decode(encoded)
                .expect("fixture and operation must succeed"),
        )
        .expect("fixture and operation must succeed");
        assert!(decoded.width() > 0 && decoded.height() > 0);
        let iterm = prepare_image(&source, 20, 8, GraphicsProtocol::ITerm2, cells())
            .expect("fixture and operation must succeed");
        assert!(iterm.payload.starts_with(b"\x1b]1337;File=inline=1;"));
        let sixel = prepare_image(&source, 20, 8, GraphicsProtocol::Sixel, cells())
            .expect("fixture and operation must succeed");
        assert!(sixel.payload.starts_with(b"\x1bPq"));
        assert!(sixel.payload.ends_with(b"\x1b\\"));
    }

    #[test]
    fn unsupported_terminals_and_corrupt_attachments_report_errors() {
        let unsupported = prepare_image(
            &png().expect("fixture and operation must succeed"),
            20,
            8,
            GraphicsProtocol::None,
            cells(),
        )
        .err()
        .ok_or("unsupported terminal")
        .expect("fixture and operation must succeed");
        assert!(unsupported.to_string().contains("graphics-capable"));
        let corrupt = prepare_image(b"not an image", 20, 8, GraphicsProtocol::Kitty, cells())
            .err()
            .ok_or("corrupt image")
            .expect("fixture and operation must succeed");
        assert!(corrupt.to_string().contains("image decode"));
    }

    #[test]
    fn pictures_are_removed_from_overlays_and_from_the_returned_feed() {
        let mut fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.runtime.graphics = GraphicsProtocol::Kitty;
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

    #[test]
    fn sixel_requires_reported_cells_and_fits_a_shorter_font_grid() {
        assert_eq!(
            lomo_tui::graphics::image_cells(GraphicsProtocol::Sixel, None),
            None
        );
        let cells = CellSize::reported(640, 288, 80, 24).expect("12-pixel grid");
        let image = prepare_image(&png().expect("PNG"), 20, 8, GraphicsProtocol::Sixel, cells)
            .expect("Sixel");
        let payload = std::str::from_utf8(&image.payload).expect("Sixel data");
        assert!(payload.starts_with("\x1bPq\"1;1;96;96"));
        assert_eq!(image.rows, 8);
    }

    #[test]
    fn resize_keeps_images_and_rehydrates_only_changed_requests() {
        let mut fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.runtime.graphics = GraphicsProtocol::Kitty;
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
        model.cell_size = CellSize::reported(640, 288, 80, 24);
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
        assert!(matches!(
            first.state,
            lomo_tui::graphics::ImageState::Ready(_)
        ));
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
            matches!(first.state, lomo_tui::graphics::ImageState::Loading),
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
                Some(lomo_tui::graphics::ImageState::Loading)
            ),
            "an identical resize must not re-pend an in-flight image"
        );
    }

    #[test]
    fn ready_images_obey_an_aggregate_byte_budget() {
        use lomo_tui::graphics::{
            CellSize, ImageRequest, ImageState, READY_IMAGE_BUDGET_BYTES, ReaderImage,
            TerminalImage,
        };
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let memo = model
            .selected_memo()
            .map_or_else(|| panic!("memo fixture"), Clone::clone);
        let cells = CellSize::reported(8, 16, 1, 1).expect("cells");
        let epoch = model.epoch;
        let request_at = |index: usize| ImageRequest {
            epoch,
            version: memo.version(),
            path: lomo_core::RelativeWorkspacePath::parse(&format!("media/{index}.png"))
                .expect("fixture and operation must succeed"),
            columns: 20,
            rows: 8,
            protocol: GraphicsProtocol::Kitty,
            cells,
        };
        let image_at = |id: u32| TerminalImage {
            id,
            columns: 20,
            rows: 8,
            protocol: GraphicsProtocol::Kitty,
            payload: vec![7; READY_IMAGE_BUDGET_BYTES / 4],
        };
        for index in 0..4 {
            let request = request_at(index);
            model.images.push(ReaderImage {
                request: request.clone(),
                state: ImageState::Pending,
            });
            lomo_tui::graphics::apply_image(
                &mut model,
                &request,
                Ok(image_at(u32::try_from(index).expect("id") + 1)),
            );
        }
        let ready_bytes: usize = model
            .images
            .iter()
            .map(|image| match &image.state {
                ImageState::Ready(image) => image.payload.len(),
                ImageState::Pending | ImageState::Loading | ImageState::Failed(_) => 0,
            })
            .sum();
        assert!(
            ready_bytes <= READY_IMAGE_BUDGET_BYTES,
            "aggregate ready payload must stay bounded: {ready_bytes}"
        );
        assert!(
            matches!(
                model.images.first().map(|image| &image.state),
                Some(ImageState::Pending)
            ),
            "the oldest ready image is demoted first, keeping the bounded working set recent"
        );
        assert!(
            matches!(
                model.images.get(3).map(|image| &image.state),
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
        let cells = CellSize::reported(8, 16, 1, 1).expect("cells");
        model.images = vec![lomo_tui::graphics::ReaderImage {
            request: lomo_tui::graphics::ImageRequest {
                epoch: model.epoch,
                version: memo.version(),
                path: lomo_core::RelativeWorkspacePath::parse("media/a.png")
                    .expect("fixture and operation must succeed"),
                columns: 20,
                rows: 2,
                protocol: GraphicsProtocol::Kitty,
                cells,
            },
            state: lomo_tui::graphics::ImageState::Ready(Arc::new(
                lomo_tui::graphics::TerminalImage {
                    id: 7,
                    columns: 20,
                    rows: 2,
                    protocol: GraphicsProtocol::Kitty,
                    payload: b"payload".to_vec(),
                },
            )),
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
