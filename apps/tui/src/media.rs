use std::io::{Cursor, Read};
use std::path::Path;

use image::{ImageBuffer, ImageFormat, Rgba};

use crate::editor::CommandRunner;
use crate::error::TuiError;

/// Image vs audio attachment for terminal display and external player routing.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MediaKind {
    Image,
    Audio,
}

/// Clipboard failures report the real backend diagnostic.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ClipboardError {
    Unavailable { diagnostic: String },
    Empty { diagnostic: String },
    Corrupt { diagnostic: String },
}

/// Reads PNG bytes from a clipboard backend.
pub trait ImageClipboard {
    /// # Errors
    /// Unavailable backend, empty clipboard, or undecodable pixels.
    fn read_png(&self) -> Result<Vec<u8>, ClipboardError>;
}

/// System clipboard via arboard. Errors keep the backend message.
pub struct SystemClipboard;

impl ImageClipboard for SystemClipboard {
    fn read_png(&self) -> Result<Vec<u8>, ClipboardError> {
        let mut clipboard =
            arboard::Clipboard::new().map_err(|error| ClipboardError::Unavailable {
                diagnostic: error.to_string(),
            })?;
        let image = match clipboard.get_image() {
            Ok(image) => image,
            Err(error) => {
                return Err(ClipboardError::Empty {
                    diagnostic: error.to_string(),
                });
            }
        };
        let width = u32::try_from(image.width).map_err(|error| ClipboardError::Corrupt {
            diagnostic: error.to_string(),
        })?;
        let height = u32::try_from(image.height).map_err(|error| ClipboardError::Corrupt {
            diagnostic: error.to_string(),
        })?;
        rgba_to_png(width, height, image.bytes.as_ref())
    }
}

/// SSH/plain-text placeholder required by the media contract.
#[must_use]
pub fn image_placeholder(path: &str) -> String {
    format!("[Image: {path}]")
}

#[must_use]
pub fn audio_placeholder(path: &str) -> String {
    format!("[Audio: {path}]")
}

/// Classifies a relative attachment path as image or audio by extension.
#[must_use]
pub fn media_kind_for_path(path: &str) -> MediaKind {
    let ext = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "mp3" | "m4a" | "ogg" | "wav" | "aac" | "flac" | "opus" => MediaKind::Audio,
        _ => MediaKind::Image,
    }
}

/// Retained player stderr stays bounded — a chatty player cannot grow an
/// unbounded buffer on the monitor thread. The pipe itself is drained to
/// EOF regardless: only the diagnostic prefix is kept.
const MAX_PLAYER_STDERR_BYTES: usize = 16 * 1024;

/// One managed child plus the stderr captured while it ran, read by the
/// monitor thread after exit.
pub struct SpawnedPlayer {
    pub child: Box<dyn crate::editor::ManagedChild>,
    /// The piped stderr read end, when the runner captured it.
    pub stderr: Option<Box<dyn Read + Send>>,
}

/// Spawns `path`'s handler with `argv` (typically `xdg-open`) as a managed
/// child.
///
/// Players never inherit the TUI's terminal: stdin and stdout are null,
/// stderr is piped for capture so diagnostics land in `PlayerFinished`
/// instead of painting over the alternate screen (D-06).
///
/// The caller waits on the returned handle from a monitor thread so the effect
/// worker is never blocked by a long-running player.
///
/// # Errors
/// Empty argv or a missing/failed spawn. A non-zero exit belongs to the child.
pub fn spawn_player<R: CommandRunner>(
    runner: &R,
    argv: &[String],
    path: &Path,
) -> Result<SpawnedPlayer, TuiError> {
    let Some(program) = argv.first().filter(|value| !value.is_empty()) else {
        return Err(TuiError::Player {
            diagnostic: "player command is not configured".to_owned(),
        });
    };
    let mut player_args: Vec<String> = argv.iter().skip(1).cloned().collect();
    player_args.push(path.display().to_string());
    let mut child = runner
        .spawn_managed(program, &player_args)
        .map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                TuiError::Player {
                    diagnostic: format!("player binary not found: {error}"),
                }
            } else {
                TuiError::Player {
                    diagnostic: error.to_string(),
                }
            }
        })?;
    let stderr = child.take_stderr();
    Ok(SpawnedPlayer { child, stderr })
}

/// Drains a piped player stderr to EOF — the child closing it is the exit
/// signal, so this returns before `wait` reaps.
///
/// Only the first `MAX_PLAYER_STDERR_BYTES` are retained for diagnostics;
/// the rest is read and discarded so the pipe stays open — closing it early
/// would hand a chatty player SIGPIPE before it reaches its real exit
/// status (F-IMG-4). An I/O error on the pipe ends the drain: the exit
/// status is the completion fact, stderr is best-effort diagnostics.
#[must_use]
pub fn drain_player_stderr(stderr: Option<Box<dyn Read + Send>>) -> String {
    let Some(mut reader) = stderr else {
        return String::new();
    };
    let mut raw = Vec::with_capacity(MAX_PLAYER_STDERR_BYTES);
    let mut chunk = [0_u8; 8 * 1024];
    loop {
        match reader.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(read) => {
                let room = MAX_PLAYER_STDERR_BYTES.saturating_sub(raw.len());
                raw.extend_from_slice(chunk.get(..read.min(room)).unwrap_or(&[]));
            }
        }
    }
    String::from_utf8_lossy(&raw).trim().to_owned()
}

/// Classifies a completed player wait into a user-visible diagnostic, merging
/// the captured stderr a spawned player could otherwise have painted over the
/// alternate screen (D-06).
#[must_use]
pub fn player_exit_diagnostic(status: std::process::ExitStatus, stderr: &str) -> Option<String> {
    let stderr = stderr.trim();
    let base = if status.success() {
        None
    } else {
        Some(format!("player exited {status}"))
    };
    match (base, stderr.is_empty()) {
        (diagnostic, true) => diagnostic,
        (Some(diagnostic), false) => Some(format!("{diagnostic}: {stderr}")),
        (None, false) => Some(stderr.to_owned()),
    }
}

/// Encodes raw RGBA clipboard pixels as PNG.
///
/// # Errors
/// Buffer size mismatch or PNG encode failure.
pub fn rgba_to_png(width: u32, height: u32, bytes: &[u8]) -> Result<Vec<u8>, ClipboardError> {
    let image =
        ImageBuffer::<Rgba<u8>, _>::from_raw(width, height, bytes.to_vec()).ok_or_else(|| {
            ClipboardError::Corrupt {
                diagnostic: "clipboard RGBA buffer does not match width*height".to_owned(),
            }
        })?;
    let mut out = Cursor::new(Vec::new());
    image
        .write_to(&mut out, ImageFormat::Png)
        .map_err(|error| ClipboardError::Corrupt {
            diagnostic: error.to_string(),
        })?;
    Ok(out.into_inner())
}
