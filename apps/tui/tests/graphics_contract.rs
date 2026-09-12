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
    use super::support::{RuntimeFixture, command, run_effect};
    use base64::{Engine, engine::general_purpose::STANDARD};
    use lomo_tui::{
        event::Command,
        graphics::{CellSize, prepare_image},
        media::{GraphicsProtocol, rgba_to_png},
        model::{AppModel, InputMode},
        ops::bootstrap_model,
    };

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
}
