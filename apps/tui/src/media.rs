use std::collections::BTreeMap;
use std::io::Cursor;
use std::path::Path;

use image::{ImageBuffer, ImageFormat, Rgba};

use crate::editor::CommandRunner;
use crate::error::TuiError;

/// Terminal graphics protocol the TUI is willing to use.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GraphicsProtocol {
    None,
    Kitty,
    ITerm2,
    Sixel,
}

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

/// Detects Kitty / iTerm2 / Sixel from an injected environment map.
#[must_use]
pub fn detect_graphics(vars: &BTreeMap<String, String>) -> GraphicsProtocol {
    if nonempty(vars.get("KITTY_WINDOW_ID")).is_some() {
        return GraphicsProtocol::Kitty;
    }
    if vars.get("TERM_PROGRAM").map(String::as_str) == Some("iTerm.app") {
        return GraphicsProtocol::ITerm2;
    }
    if let Some(term) = vars.get("TERM") {
        if term.contains("sixel") || term.contains("mlterm") {
            return GraphicsProtocol::Sixel;
        }
        if term.contains("kitty") {
            return GraphicsProtocol::Kitty;
        }
    }
    GraphicsProtocol::None
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

/// Same-name different-content files gain a short digest suffix.
#[must_use]
pub fn unique_media_relative_path(
    stem: &str,
    ext: &str,
    digest_hex: &str,
    existing: &[String],
) -> String {
    let candidate = format!("media/{stem}.{ext}");
    if !existing.iter().any(|path| path == &candidate) {
        return candidate;
    }
    let short = digest_hex.get(..6).unwrap_or("000000");
    format!("media/{stem}_{short}.{ext}")
}

/// Spawns `path`'s handler with `argv` (typically `xdg-open`) as a managed child.
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
) -> Result<Box<dyn crate::editor::ManagedChild>, TuiError> {
    let Some(program) = argv.first().filter(|value| !value.is_empty()) else {
        return Err(TuiError::Player {
            diagnostic: "player command is not configured".to_owned(),
        });
    };
    let mut player_args: Vec<String> = argv.iter().skip(1).cloned().collect();
    player_args.push(path.display().to_string());
    runner
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
        })
}

/// Classifies a completed player wait into a user-visible diagnostic.
#[must_use]
pub fn player_exit_diagnostic(status: std::process::ExitStatus) -> Option<String> {
    if status.success() {
        None
    } else {
        Some(format!("player exited {status}"))
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

fn nonempty(value: Option<&String>) -> Option<&str> {
    value.map(String::as_str).filter(|text| !text.is_empty())
}
