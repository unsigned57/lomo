//! Behavior Contract
//! Capability: terminal graphics degradation, clipboard errors, and external player failures.
//! Scenarios: dumb TERM yields `[Image: path]`; missing player reports the backend error.
//! Observable outcomes: placeholder text, `TuiError::Player` / `ClipboardError`, unique hashed names.
//! TDD proof: media policy module did not exist.
//! Excludes: live Kitty protocol bytes and a real Wayland clipboard.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on media policy"
)]
mod tests {
    use std::collections::BTreeMap;
    use std::os::unix::process::ExitStatusExt;
    use std::path::Path;
    use std::process::ExitStatus;

    use lomo_tui::editor::CommandRunner;
    use lomo_tui::error::TuiError;
    use lomo_tui::media::{
        ClipboardError, GraphicsProtocol, ImageClipboard, MediaKind, detect_graphics,
        image_placeholder, play_audio, preview_media_line, rgba_to_png, unique_media_relative_path,
    };
    use lomo_tui::preview::preview_body;

    struct MissingPlayer;

    impl CommandRunner for MissingPlayer {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                "xdg-open: no such file",
            ))
        }
    }

    struct ZeroPlayer;

    impl CommandRunner for ZeroPlayer {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            Ok(ExitStatus::from_raw(0))
        }
    }

    struct FailPlayer;

    impl CommandRunner for FailPlayer {
        fn run_foreground(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<ExitStatus, std::io::Error> {
            Ok(ExitStatus::from_raw(1))
        }
    }

    struct BrokenClipboard;

    impl ImageClipboard for BrokenClipboard {
        fn read_png(&self) -> Result<Vec<u8>, ClipboardError> {
            Err(ClipboardError::Unavailable {
                diagnostic: "no Display or Wayland session".to_owned(),
            })
        }
    }

    #[test]
    fn dumb_terminal_uses_image_placeholder() {
        let mut env = BTreeMap::new();
        env.insert("TERM".to_owned(), "dumb".to_owned());
        assert_eq!(detect_graphics(&env), GraphicsProtocol::None);
        assert_eq!(image_placeholder("media/a.png"), "[Image: media/a.png]");
        let preview = preview_body("hello ![alt](media/a.png)", GraphicsProtocol::None);
        assert!(
            preview.contains("[Image: media/a.png]"),
            "preview={preview}"
        );
        let kitty = preview_media_line("media/a.png", MediaKind::Image, GraphicsProtocol::Kitty);
        assert!(kitty.contains("kitty"));
        assert!(!kitty.starts_with("[Image: "));
        let mut kitty_env = BTreeMap::new();
        kitty_env.insert("KITTY_WINDOW_ID".to_owned(), "1".to_owned());
        assert_eq!(detect_graphics(&kitty_env), GraphicsProtocol::Kitty);
        let mut iterm = BTreeMap::new();
        iterm.insert("TERM_PROGRAM".to_owned(), "iTerm.app".to_owned());
        assert_eq!(detect_graphics(&iterm), GraphicsProtocol::ITerm2);
        let mut sixel = BTreeMap::new();
        sixel.insert("TERM".to_owned(), "xterm-sixel".to_owned());
        assert_eq!(detect_graphics(&sixel), GraphicsProtocol::Sixel);
        assert_eq!(
            lomo_tui::media::media_kind_for_path("media/a.mp3"),
            MediaKind::Audio
        );
        let quoted = preview_body(
            "# Title\n\n> quote\n\n- item #tag\n\n```\ncode\n```\n\n---\n\n`inline` and **bold**\n",
            GraphicsProtocol::None,
        );
        assert!(quoted.contains("Title") || quoted.contains("code") || quoted.contains("#tag"));
        let table = preview_body("| a | b |\n| - | - |\n| 1 | 2 |\n", GraphicsProtocol::None);
        assert!(!table.is_empty());
    }

    #[test]
    fn missing_player_is_an_error_not_success() {
        let error = play_audio(
            &MissingPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .expect_err("missing player");
        match error {
            TuiError::Player { diagnostic } => {
                assert!(diagnostic.contains("not found") || diagnostic.contains("xdg-open"));
            }
            TuiError::Config { .. }
            | TuiError::MissingRuntimeDir
            | TuiError::EditorNotConfigured
            | TuiError::Io { .. }
            | TuiError::Session { .. }
            | TuiError::Terminal { .. }
            | TuiError::Clipboard { .. } => panic!("expected player error, got {error:?}"),
        }
        play_audio(
            &ZeroPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .expect("zero exit is success");
        let empty = play_audio(&ZeroPlayer, &[], Path::new("media/a.mp3")).expect_err("empty argv");
        match empty {
            TuiError::Player { diagnostic } => {
                assert!(diagnostic.contains("not configured"));
            }
            TuiError::Config { .. }
            | TuiError::MissingRuntimeDir
            | TuiError::EditorNotConfigured
            | TuiError::Io { .. }
            | TuiError::Session { .. }
            | TuiError::Terminal { .. }
            | TuiError::Clipboard { .. } => panic!("expected player error, got {empty:?}"),
        }
        let failed = play_audio(
            &FailPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .expect_err("nonzero");
        assert!(matches!(failed, TuiError::Player { .. }));
    }

    #[test]
    fn clipboard_unavailable_keeps_backend_diagnostic() {
        let error = BrokenClipboard.read_png().expect_err("unavailable");
        match error {
            ClipboardError::Unavailable { diagnostic } => {
                assert!(diagnostic.contains("Wayland") || diagnostic.contains("Display"));
            }
            ClipboardError::Empty { .. } | ClipboardError::Corrupt { .. } => {
                panic!("expected unavailable, got {error:?}")
            }
        }
    }

    #[test]
    fn colliding_names_gain_short_digest_suffix() {
        let digest = "abcdef0123456789";
        let first = unique_media_relative_path("pasted", "png", digest, &[]);
        assert_eq!(first, "media/pasted.png");
        let second =
            unique_media_relative_path("pasted", "png", digest, &["media/pasted.png".to_owned()]);
        assert_eq!(second, "media/pasted_abcdef.png");
    }

    #[test]
    fn rgba_buffer_encodes_png_magic() {
        let bytes = [255_u8, 0, 0, 255];
        let png = rgba_to_png(1, 1, &bytes).expect("png");
        let magic = png.get(..8).expect("png header");
        assert_eq!(magic, [137, 80, 78, 71, 13, 10, 26, 10]);
        let corrupt = rgba_to_png(2, 2, &[1, 2, 3]).expect_err("size");
        assert!(matches!(corrupt, ClipboardError::Corrupt { .. }));
        let iterm_line =
            preview_media_line("media/a.png", MediaKind::Image, GraphicsProtocol::ITerm2);
        assert!(iterm_line.contains("iterm2"));
        let sixel_line =
            preview_media_line("media/a.png", MediaKind::Image, GraphicsProtocol::Sixel);
        assert!(sixel_line.contains("sixel"));
        assert_eq!(
            preview_media_line("media/a.mp3", MediaKind::Audio, GraphicsProtocol::None),
            "[Audio: media/a.mp3]"
        );
    }
}
