//! Content identity: streaming SHA-256 digest + self-held magic-byte MIME table.

use std::io::{Read, Write};
use std::path::Path;

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{storage, validation};

/// Bounded streaming read buffer for digests (not whole-file load).
pub const DIGEST_STREAM_CHUNK_BYTES: usize = 16 * 1024;

/// Lowercase hex SHA-256 of exact media bytes.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct ContentDigest(String);

impl ContentDigest {
    /// Parses a lowercase hex SHA-256 digest.
    ///
    /// # Errors
    ///
    /// Returns validation when the wire form is not 64 lowercase hex bytes.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 64
            || !raw
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(validation(
                "invalid_content_digest",
                "content digest must be 64 lowercase hexadecimal bytes",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    /// Digests an in-memory slice (tests / tiny fixtures only — production uses streaming paths).
    #[must_use]
    pub fn of_slice(bytes: &[u8]) -> Self {
        let digest = Sha256::digest(bytes);
        Self(format!("{digest:x}"))
    }

    /// Streams `reader` with a bounded buffer and returns the digest + byte count.
    ///
    /// # Errors
    ///
    /// Returns storage when the reader fails mid-stream.
    pub fn stream_from_reader<R: Read>(reader: &mut R) -> Result<(Self, u64), LomoError> {
        let mut hasher = Sha256::new();
        let mut buffer = [0_u8; DIGEST_STREAM_CHUNK_BYTES];
        let mut total = 0_u64;
        loop {
            let read = reader.read(&mut buffer).map_err(|error| {
                storage(
                    "media_stream_read_failed",
                    &format!("stream read failed: {error}"),
                )
            })?;
            if read == 0 {
                break;
            }
            let Some(chunk) = buffer.get(..read) else {
                return Err(validation(
                    "media_stream_chunk_out_of_bounds",
                    "stream chunk bounds violated",
                ));
            };
            hasher.update(chunk);
            total = total.saturating_add(u64::try_from(read).unwrap_or(u64::MAX));
        }
        Ok((Self(format!("{:x}", hasher.finalize())), total))
    }

    /// Opens `path` and streams a digest without loading the whole file.
    ///
    /// # Errors
    ///
    /// Returns storage when open/read fails.
    pub fn stream_from_path(path: &Path) -> Result<(Self, u64), LomoError> {
        let mut file = std::fs::File::open(path).map_err(|error| {
            storage(
                "media_open_failed",
                &format!("failed to open media path for digest: {error}"),
            )
        })?;
        Self::stream_from_reader(&mut file)
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Supported media MIME kinds owned by the self-held magic table.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum MediaMime {
    ImagePng,
    ImageJpeg,
    ImageGif,
    ImageWebp,
    ImageBmp,
    ImageHeic,
    ImageHeif,
    ImageAvif,
    AudioM4a,
    AudioMp3,
    AudioAac,
    AudioWav,
    AudioOgg,
}

impl MediaMime {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ImagePng => "image/png",
            Self::ImageJpeg => "image/jpeg",
            Self::ImageGif => "image/gif",
            Self::ImageWebp => "image/webp",
            Self::ImageBmp => "image/bmp",
            Self::ImageHeic => "image/heic",
            Self::ImageHeif => "image/heif",
            Self::ImageAvif => "image/avif",
            Self::AudioM4a => "audio/mp4",
            Self::AudioMp3 => "audio/mpeg",
            Self::AudioAac => "audio/aac",
            Self::AudioWav => "audio/wav",
            Self::AudioOgg => "audio/ogg",
        }
    }

    /// Parses a wire MIME string produced by [`Self::as_str`].
    ///
    /// # Errors
    ///
    /// Returns validation when the MIME is not a supported media type.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        match raw {
            "image/png" => Ok(Self::ImagePng),
            "image/jpeg" => Ok(Self::ImageJpeg),
            "image/gif" => Ok(Self::ImageGif),
            "image/webp" => Ok(Self::ImageWebp),
            "image/bmp" => Ok(Self::ImageBmp),
            "image/heic" => Ok(Self::ImageHeic),
            "image/heif" => Ok(Self::ImageHeif),
            "image/avif" => Ok(Self::ImageAvif),
            "audio/mp4" => Ok(Self::AudioM4a),
            "audio/mpeg" => Ok(Self::AudioMp3),
            "audio/aac" => Ok(Self::AudioAac),
            "audio/wav" => Ok(Self::AudioWav),
            "audio/ogg" => Ok(Self::AudioOgg),
            _ => Err(validation(
                "unsupported_media_mime",
                "media mime is not a supported image or audio type",
            )),
        }
    }

    /// Preferred human extension for final filenames (not identity).
    #[must_use]
    pub const fn preferred_extension(self) -> &'static str {
        match self {
            Self::ImagePng => "png",
            Self::ImageJpeg => "jpg",
            Self::ImageGif => "gif",
            Self::ImageWebp => "webp",
            Self::ImageBmp => "bmp",
            Self::ImageHeic => "heic",
            Self::ImageHeif => "heif",
            Self::ImageAvif => "avif",
            Self::AudioM4a => "m4a",
            Self::AudioMp3 => "mp3",
            Self::AudioAac => "aac",
            Self::AudioWav => "wav",
            Self::AudioOgg => "ogg",
        }
    }

    /// Resolves MIME from magic bytes; optional extension is a hint that must not conflict.
    ///
    /// # Errors
    ///
    /// Returns validation when magic is unknown or extension conflicts with magic.
    pub fn detect(header: &[u8], extension_hint: Option<&str>) -> Result<Self, LomoError> {
        let from_magic = detect_magic(header).ok_or_else(|| {
            validation(
                "unsupported_media_magic",
                "media magic bytes are not a supported image or audio type",
            )
        })?;
        if let Some(ext) = extension_hint {
            let normalized = ext.trim_start_matches('.').to_ascii_lowercase();
            if let Some(from_ext) = mime_from_extension(&normalized)
                && from_ext != from_magic
            {
                return Err(validation(
                    "media_magic_extension_conflict",
                    "file extension conflicts with magic-byte media type",
                ));
            }
        }
        Ok(from_magic)
    }
}

fn mime_from_extension(ext: &str) -> Option<MediaMime> {
    match ext {
        "png" => Some(MediaMime::ImagePng),
        "jpg" | "jpeg" => Some(MediaMime::ImageJpeg),
        "gif" => Some(MediaMime::ImageGif),
        "webp" => Some(MediaMime::ImageWebp),
        "bmp" => Some(MediaMime::ImageBmp),
        "heic" => Some(MediaMime::ImageHeic),
        "heif" => Some(MediaMime::ImageHeif),
        "avif" => Some(MediaMime::ImageAvif),
        "m4a" | "mp4" => Some(MediaMime::AudioM4a),
        "mp3" => Some(MediaMime::AudioMp3),
        "aac" => Some(MediaMime::AudioAac),
        "wav" => Some(MediaMime::AudioWav),
        "ogg" | "oga" => Some(MediaMime::AudioOgg),
        _ => None,
    }
}

fn detect_magic(header: &[u8]) -> Option<MediaMime> {
    if header.starts_with(&[0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n']) {
        return Some(MediaMime::ImagePng);
    }
    if header.starts_with(&[0xff, 0xd8, 0xff]) {
        return Some(MediaMime::ImageJpeg);
    }
    if header.starts_with(b"GIF87a") || header.starts_with(b"GIF89a") {
        return Some(MediaMime::ImageGif);
    }
    if header.len() >= 12 && header.starts_with(b"RIFF") && header.get(8..12) == Some(b"WEBP") {
        return Some(MediaMime::ImageWebp);
    }
    if header.starts_with(b"BM") {
        return Some(MediaMime::ImageBmp);
    }
    if header.len() >= 12 && header.get(4..8) == Some(b"ftyp") {
        let brand = header.get(8..12)?;
        if brand == b"heic" || brand == b"heix" || brand == b"hevc" || brand == b"hevx" {
            return Some(MediaMime::ImageHeic);
        }
        if brand == b"mif1" || brand == b"msf1" {
            return Some(MediaMime::ImageHeif);
        }
        if brand == b"avif" || brand == b"avis" {
            return Some(MediaMime::ImageAvif);
        }
        if brand == b"M4A " || brand == b"mp41" || brand == b"mp42" || brand == b"isom" {
            return Some(MediaMime::AudioM4a);
        }
    }
    if header.starts_with(&[0xff, 0xfb])
        || header.starts_with(&[0xff, 0xf3])
        || header.starts_with(&[0xff, 0xf2])
        || header.starts_with(b"ID3")
    {
        return Some(MediaMime::AudioMp3);
    }
    if header.starts_with(&[0xff, 0xf1]) || header.starts_with(&[0xff, 0xf9]) {
        return Some(MediaMime::AudioAac);
    }
    if header.starts_with(b"RIFF") && header.len() >= 12 && header.get(8..12) == Some(b"WAVE") {
        return Some(MediaMime::AudioWav);
    }
    if header.starts_with(b"OggS") {
        return Some(MediaMime::AudioOgg);
    }
    None
}

/// Reads up to 64 header bytes from `path` for magic detection.
///
/// # Errors
///
/// Returns storage when open/read fails.
pub fn read_magic_header(path: &Path) -> Result<Vec<u8>, LomoError> {
    let mut file = std::fs::File::open(path).map_err(|error| {
        storage(
            "media_open_failed",
            &format!("failed to open media path for magic: {error}"),
        )
    })?;
    let mut header = vec![0_u8; 64];
    let read = file.read(&mut header).map_err(|error| {
        storage(
            "media_header_read_failed",
            &format!("failed to read media header: {error}"),
        )
    })?;
    header.truncate(read);
    Ok(header)
}

/// Writes `bytes` to `path` (test helper surface; production staging uses paths from Kotlin/OS).
///
/// # Errors
///
/// Returns storage on write failure.
pub fn write_bytes_for_tests(path: &Path, bytes: &[u8]) -> Result<(), LomoError> {
    let mut file = std::fs::File::create(path).map_err(|error| {
        storage(
            "media_write_failed",
            &format!("failed to create test media file: {error}"),
        )
    })?;
    file.write_all(bytes).map_err(|error| {
        storage(
            "media_write_failed",
            &format!("failed to write test media file: {error}"),
        )
    })
}
