//! Behavior Contract
//! Capability: terminal graphics degradation, clipboard errors, and external player failures.
//! Scenarios: a terminal without an image protocol renders `[Image: path]`; a missing
//! player reports the backend error; spawned players never inherit the TUI stdio and
//! their exit + bounded stderr reach `player_exit_diagnostic`.
//! Observable outcomes: placeholder text, `TuiError::Player` / `ClipboardError`,
//! unique hashed names, and stderr-bounded player diagnostics.
//! TDD proof: media policy module did not exist.
//! Excludes: protocol bytes (covered by `graphics_contract`), the probe handshake
//! (`media_pipeline_contract`), and a real Wayland clipboard.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on media policy"
)]
mod tests {
    use std::io::Cursor;
    use std::os::unix::process::ExitStatusExt;
    use std::path::Path;
    use std::process::ExitStatus;

    use lomo_tui::editor::{CommandRunner, ManagedChild};
    use lomo_tui::error::TuiError;
    use lomo_tui::media::{
        ClipboardError, ImageClipboard, MediaKind, drain_player_stderr, image_placeholder,
        player_exit_diagnostic, rgba_to_png, spawn_player,
    };

    struct ImmediateChild {
        status: i32,
    }

    impl ManagedChild for ImmediateChild {
        fn wait(&mut self) -> Result<ExitStatus, std::io::Error> {
            Ok(ExitStatus::from_raw(self.status))
        }
    }

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

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn ManagedChild>, std::io::Error> {
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

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn ManagedChild>, std::io::Error> {
            Ok(Box::new(ImmediateChild { status: 0 }))
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

        fn spawn_managed(
            &self,
            _program: &str,
            _args: &[String],
        ) -> Result<Box<dyn ManagedChild>, std::io::Error> {
            Ok(Box::new(ImmediateChild { status: 1 }))
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

    /// Text placeholders are the complete presentation for a terminal without
    /// an image protocol — the verdict that produces them lives behind the
    /// probe (`graphics::TerminalProber`), never an env-name table.
    #[test]
    fn placeholder_text_and_media_kind_classify_attachments() {
        assert_eq!(image_placeholder("media/a.png"), "[Image: media/a.png]");
        assert_eq!(
            lomo_tui::media::audio_placeholder("media/a.mp3"),
            "[Audio: media/a.mp3]"
        );
        assert_eq!(
            lomo_tui::media::media_kind_for_path("media/a.mp3"),
            MediaKind::Audio
        );
        assert_eq!(
            lomo_tui::media::media_kind_for_path("media/a.png"),
            MediaKind::Image
        );
    }

    #[test]
    fn missing_player_is_an_error_not_success() {
        let error = spawn_player(
            &MissingPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .map(|_| ())
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
        let mut spawned = spawn_player(
            &ZeroPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .expect("spawn succeeds without waiting on the worker");
        assert!(
            spawned.child.wait().expect("wait").success(),
            "zero exit is a successful completion"
        );
        let empty = spawn_player(&ZeroPlayer, &[], Path::new("media/a.mp3"))
            .map(|_| ())
            .expect_err("empty argv");
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
        let mut failed = spawn_player(
            &FailPlayer,
            &["xdg-open".to_owned()],
            Path::new("media/a.mp3"),
        )
        .expect("a failing exit status is still a managed spawn");
        assert!(
            !failed.child.wait().expect("wait").success(),
            "non-zero exit surfaces through the managed handle, not a blocked worker"
        );
    }

    /// A spawned player's stderr is a bounded capture merged into the exit
    /// diagnostic — a chatty player cannot grow memory and its complaints are
    /// never painted onto the alternate screen (D-06).
    #[test]
    fn player_exit_carries_bounded_stderr_in_the_diagnostic() {
        let noisy = drain_player_stderr(Some(Box::new(Cursor::new(vec![b'x'; 128 * 1024]))));
        assert!(
            noisy.len() <= 16 * 1024,
            "stderr capture stays bounded: {}",
            noisy.len()
        );
        assert!(drain_player_stderr(None).is_empty());
        let failure = player_exit_diagnostic(ExitStatus::from_raw(1 << 8), "device busy")
            .expect("diagnostic");
        assert!(
            failure.contains("device busy") && failure.contains("exit status: 1"),
            "the exit status and stderr merge into one diagnostic: {failure}"
        );
        assert!(
            player_exit_diagnostic(ExitStatus::from_raw(0), "").is_none(),
            "a clean silent exit produces no diagnostic"
        );
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
    fn colliding_paste_resolves_through_the_workspace_stage_ledger() {
        let fixture =
            super::support::RuntimeFixture::new().expect("fixture and operation must succeed");
        let occupied = rgba_to_png(1, 1, &[0, 0, 255, 255]).expect("png");
        std::fs::create_dir_all(fixture.runtime.workspace.join("media")).expect("media dir");
        std::fs::write(
            fixture.runtime.workspace.join("media/pasted.png"),
            &occupied,
        )
        .expect("seed a same-named different-digest occupant");
        let png = rgba_to_png(1, 1, &[255, 0, 0, 255]).expect("png");
        let relative = lomo_tui::mutations::import_clipboard_png(&fixture.runtime, &png)
            .expect("clipboard import must commit through the shared stage ledger");
        assert_eq!(
            relative, "media/pasted_1.png",
            "the durable ledger resolves a deterministic suffix for a name claimed by other bytes"
        );
        assert_eq!(
            std::fs::read(fixture.runtime.workspace.join(&relative))
                .expect("committed workspace bytes"),
            png
        );
        // Staging lives under the configured media_dir (E-18) — never directly
        // under the workspace root.
        let stage_dir = lomo_media::stage_directory(&fixture.runtime.config().media_dir);
        let ledger = lomo_media::StageLedger::load(&stage_dir).expect("shared stage ledger");
        assert!(
            ledger.records().is_empty(),
            "a committed import retires its stage record instead of retaining a claim"
        );
        let staged_leftovers: Vec<_> = std::fs::read_dir(&stage_dir)
            .expect("stage dir")
            .collect::<Result<Vec<_>, _>>()
            .expect("readable stage entries")
            .into_iter()
            .filter(|entry| entry.file_name() != lomo_media::STAGE_LEDGER_FILE)
            .collect();
        assert!(
            staged_leftovers.is_empty(),
            "committed staged bytes are reclaimed once the pending-operation lease releases"
        );
    }

    #[test]
    fn rgba_buffer_encodes_png_magic() {
        let bytes = [255_u8, 0, 0, 255];
        let png = rgba_to_png(1, 1, &bytes).expect("png");
        let magic = png.get(..8).expect("png header");
        assert_eq!(magic, [137, 80, 78, 71, 13, 10, 26, 10]);
        let corrupt = rgba_to_png(2, 2, &[1, 2, 3]).expect_err("size");
        assert!(matches!(corrupt, ClipboardError::Corrupt { .. }));
        assert_eq!(
            lomo_tui::media::audio_placeholder("media/a.mp3"),
            "[Audio: media/a.mp3]"
        );
    }
}
