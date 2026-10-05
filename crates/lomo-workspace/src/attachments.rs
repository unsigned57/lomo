//! Attachment-destination canonical authority.
//!
//! One spelling-agnostic law for "which workspace file does this Markdown destination name":
//! every destination that resolves to a workspace file collapses to one canonical relative
//! path — `media/./pic.png`, `media//pic.png`, `media\pic.png`, and `./media/pic.png` all
//! become `media/pic.png`. A `..` segment folds into its parent the same way the host path
//! resolver does; a destination that escapes the workspace root can never name a collectable
//! file, so it returns `None`. Destinations that are not workspace files at all (empty,
//! `scheme:`, `//`, `#`) return `None` as well — callers distinguish "not a local object"
//! (skip) from "names a file" (canonical key).
//!
//! Render/document fact projections canonicalize at collection, so `attachment_destinations`,
//! memo `attachments`, the `attachment_ref` projection, sweep protection, and staged-media
//! promote matching all share this one authority.

use lomo_core::RelativeWorkspacePath;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// One Markdown `![..](dest)` / `![[..]]` destination, classified at the workspace fact
/// boundary — the same authority `attachment_ref` projection and sweep protection consume.
///
/// * `Local` names a workspace file under its canonical relative path (`media/./pic.png`,
///   `media//pic.png`, `media\pic.png`, `./media/pic.png` all collapse to `media/pic.png`).
///   Only `Local` may ever reach local IO.
/// * `External` names a non-local object — a URI scheme (`https:`, `data:`), a
///   protocol-relative reference (`//host/x`), or an in-document anchor (`#frag`).
/// * `Malformed` is a local-looking spelling canonicalization rejected (root escape, empty,
///   rejected characters): it names nothing and renders as a non-fatal placeholder.
///
/// Every variant keeps the author's raw source token: span rewrites
/// (`attachment_remap`) and source-slice verification need the literal text,
/// not the canonical key.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ImageDest {
    /// A workspace-relative file, already canonicalized.
    Local {
        /// The destination token as written in the source.
        raw: String,
        /// The canonical workspace-relative path the token resolves to.
        path: RelativeWorkspacePath,
    },
    /// A non-local destination (scheme URL, protocol-relative, anchor).
    External(Url),
    /// A local-looking spelling that failed canonicalization; names no file.
    Malformed(String),
}

impl ImageDest {
    /// Classifies one raw destination token. Local canonicalization runs through the same
    /// law as [`canonical_attachment_path`]; external detection is
    /// [`is_external_attachment_destination`]; anything left over is `Malformed`.
    #[must_use]
    pub fn classify(raw: &str) -> Self {
        if let Some(path) = canonicalize_attachment_path(raw) {
            return Self::Local {
                raw: raw.to_owned(),
                path,
            };
        }
        if is_external_attachment_destination(raw) {
            return Self::External(Url(raw.to_owned()));
        }
        Self::Malformed(raw.to_owned())
    }

    /// The destination token exactly as written in the source.
    #[must_use]
    pub fn raw(&self) -> &str {
        match self {
            Self::Local { raw, .. } | Self::Malformed(raw) => raw,
            Self::External(url) => url.as_str(),
        }
    }

    /// The projection-store value: canonical for workspace files, raw otherwise — the same
    /// strings [`projected_attachment_destination`] emitted for the untyped list.
    #[must_use]
    pub fn projected(&self) -> String {
        match self {
            Self::Local { path, .. } => path.as_str().to_owned(),
            Self::External(url) => url.as_str().to_owned(),
            Self::Malformed(raw) => raw.clone(),
        }
    }

    /// The canonical workspace path when this destination names a local file.
    #[must_use]
    pub const fn local(&self) -> Option<&RelativeWorkspacePath> {
        match self {
            Self::Local { path, .. } => Some(path),
            Self::External(_) | Self::Malformed(_) => None,
        }
    }
}

impl Serialize for ImageDest {
    /// The wire shape stays the authored destination token — identical to the `String`
    /// field it replaces, so `RenderDocumentV1` schema v1 does not drift.
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.raw())
    }
}

impl<'de> Deserialize<'de> for ImageDest {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let raw = String::deserialize(deserializer)?;
        Ok(Self::classify(&raw))
    }
}

/// An attachment destination that names a non-local object.
///
/// Construction is the [`is_external_attachment_destination`] predicate: `scheme:…`,
/// `//host/path`, and `#anchor` are all external. A full RFC URL parse is deliberately not
/// the law — Markdown destinations admit fragment-only and protocol-relative spellings that
/// are external without being absolute URLs.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Url(String);

impl Url {
    /// The destination token as written.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// The canonical workspace-relative key for one Markdown attachment destination, or `None`
/// when the destination cannot name a collectable workspace file.
#[must_use]
pub fn canonical_attachment_path(raw: &str) -> Option<String> {
    canonicalize_attachment_path(raw).map(|path| path.as_str().to_owned())
}

/// The typed core of [`canonical_attachment_path`]: the workspace path a destination names.
fn canonicalize_attachment_path(raw: &str) -> Option<RelativeWorkspacePath> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || is_external_attachment_destination(trimmed) {
        return None;
    }
    let unified = trimmed.replace('\\', "/");
    let mut segments: Vec<&str> = Vec::new();
    for segment in unified.split('/') {
        match segment {
            "" | "." => {}
            // Folding a `..` with no parent would escape the workspace root; such a
            // destination cannot denote a collectable workspace file.
            ".." => {
                segments.pop()?;
            }
            other => segments.push(other),
        }
    }
    if segments.is_empty() {
        return None;
    }
    // Canonicalization only yields keys for paths the workspace type itself accepts; a rejected
    // join (overlong, control chars, drive-letter shape) cannot name a collectable file. The
    // Result→Option edge is the classification contract: a spelling the workspace path type
    // rejects is not a local attachment at all.
    let Ok(path) = RelativeWorkspacePath::parse(&segments.join("/")) else {
        return None;
    };
    Some(path)
}

/// Whether an attachment destination denotes an external object rather than a workspace file:
/// in-document anchors (`#`), protocol-relative URLs (`//`), and URI schemes (`https:`,
/// `data:`).
#[must_use]
pub fn is_external_attachment_destination(raw: &str) -> bool {
    let raw = raw.trim();
    if raw.starts_with('#') || raw.starts_with("//") {
        return true;
    }
    let bytes = raw.as_bytes();
    let Some(first) = bytes.first() else {
        return false;
    };
    if !first.is_ascii_alphabetic() {
        return false;
    }
    let mut index = 1;
    while index < bytes.len()
        && (bytes.get(index).is_some_and(u8::is_ascii_alphanumeric)
            || matches!(bytes.get(index), Some(b'+' | b'-' | b'.')))
    {
        index += 1;
    }
    bytes.get(index) == Some(&b':')
}

/// Whether a destination names an audio attachment by file extension. Shared by the render
/// attachment classification (audio links count as attachments) and the store image-URL split.
#[must_use]
pub fn is_audio_attachment_destination(destination: &str) -> bool {
    std::path::Path::new(destination)
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            matches!(
                ext.to_ascii_lowercase().as_str(),
                "m4a" | "mp3" | "ogg" | "wav" | "aac"
            )
        })
}

/// The projected attachment-destination value for fact lists.
///
/// Destinations that name a workspace file collapse to their canonical path; destinations that
/// do not (external objects, root escapes) keep their raw spelling and stay distinguishable via
/// [`is_external_attachment_destination`]. This is the string view of [`ImageDest::projected`].
#[must_use]
pub fn projected_attachment_destination(raw: &str) -> String {
    ImageDest::classify(raw).projected()
}

/// Distinct canonical attachment keys for a raw destination list, first-seen order preserved.
///
/// Destinations that cannot name a workspace file carry no key and are dropped; equivalent
/// spellings collapse to one entry. `attachment_ref` rows and rebuild evidence counts derive
/// keys through this same projection so equivalent spellings can never diverge.
#[must_use]
pub fn canonical_attachment_keys(raw: &[String]) -> Vec<String> {
    let mut keys = Vec::new();
    for destination in raw {
        let Some(key) = canonical_attachment_path(destination) else {
            continue;
        };
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    keys
}
