//! Content-derived projection facts (tags / attachments) from the workspace render owner.
//!
//! Store projections must not invent a second Markdown tag/attachment scanner. When content is
//! valid UTF-8, facts come from `lomo_workspace::render_markdown_core`. Heuristic flags only apply
//! when the render pipeline cannot accept the source (invalid UTF-8 / resource limits).

use sha2::{Digest, Sha256};

use lomo_core::LomoError;
use lomo_workspace::{SourceBytes, render_markdown};

use crate::error::validation;

/// Projection facts derived from one memo body.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ContentFacts {
    pub has_todo: bool,
    pub has_url: bool,
    pub tags: Vec<String>,
    /// All attachment destinations (images + audio).
    pub attachment_paths: Vec<String>,
    /// Non-audio attachment destinations for list/gallery image URLs.
    pub image_urls: Vec<String>,
}

/// Projects tags/attachments/todo/url flags from Markdown body.
///
/// # Errors
///
/// Never fails for ordinary bodies: invalid UTF-8 / render budget falls back to heuristics with
/// empty tag/attachment lists (memo still indexes). Explicit validation only for oversized tags.
pub fn project_content_facts(content: &str) -> Result<ContentFacts, LomoError> {
    if content.is_empty() {
        return Ok(ContentFacts::default());
    }
    let Ok(source) = SourceBytes::try_from_str(content) else {
        return Ok(heuristic_flags_only(content));
    };
    let Ok(doc) = render_markdown(&source) else {
        return Ok(heuristic_flags_only(content));
    };
    let attachment_paths = doc.attachment_destinations().to_vec();
    let image_urls = attachment_paths
        .iter()
        .filter(|path| !is_audio_target(path))
        .cloned()
        .collect();
    let mut tags = doc.tag_names().to_vec();
    tags.retain(|tag| !tag.is_empty() && tag.len() <= 128 && !tag.contains('\''));
    tags.sort();
    tags.dedup();
    let has_todo = content.contains("- [ ]") || content.contains("- [x]");
    let has_url = content.contains("http://") || content.contains("https://");
    Ok(ContentFacts {
        has_todo,
        has_url,
        tags,
        attachment_paths,
        image_urls,
    })
}

/// Merges explicit command tags with content-derived tags (stable unique order).
///
/// # Errors
///
/// Returns validation when a tag is empty, longer than 128 bytes, or contains `'`.
pub fn merge_tags(
    command_tags: &[String],
    content_tags: &[String],
) -> Result<Vec<String>, LomoError> {
    let mut out = Vec::with_capacity(command_tags.len() + content_tags.len());
    for tag in command_tags.iter().chain(content_tags.iter()) {
        if tag.is_empty() || tag.len() > 128 || tag.contains('\'') {
            return Err(validation(
                "invalid_tag",
                "tag is empty, too long, or contains disallowed characters",
            ));
        }
        if !out.iter().any(|existing| existing == tag) {
            out.push(tag.clone());
        }
    }
    Ok(out)
}

/// Hex SHA-256 of content bytes (memo file fingerprint authority).
#[must_use]
pub fn fingerprint_content(content: &str) -> String {
    hex_encode(&Sha256::digest(content.as_bytes()))
}

/// Aggregate digest over sorted `(memo_id, fingerprint)` pairs for cutover compare.
#[must_use]
pub fn aggregate_memo_digest(pairs: &[(String, String)]) -> String {
    let mut hasher = Sha256::new();
    for (memo_id, fingerprint) in pairs {
        hasher.update(memo_id.as_bytes());
        hasher.update([0u8]);
        hasher.update(fingerprint.as_bytes());
        hasher.update(b"\n");
    }
    hex_encode(&hasher.finalize())
}

fn heuristic_flags_only(content: &str) -> ContentFacts {
    ContentFacts {
        has_todo: content.contains("- [ ]") || content.contains("- [x]"),
        has_url: content.contains("http://") || content.contains("https://"),
        tags: Vec::new(),
        attachment_paths: Vec::new(),
        image_urls: Vec::new(),
    }
}

fn is_audio_target(target: &str) -> bool {
    std::path::Path::new(target)
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            matches!(
                ext.to_ascii_lowercase().as_str(),
                "m4a" | "mp3" | "ogg" | "wav" | "aac"
            )
        })
}

fn hex_encode(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        match write!(out, "{byte:02x}") {
            Ok(()) | Err(_) => {}
        }
    }
    out
}
