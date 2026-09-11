//! Content-derived projection facts (tags / attachments) from the workspace render owner.
//!
//! Store projections must not invent a second Markdown tag/attachment scanner. When content is
//! valid UTF-8, facts come from `lomo_workspace::render_markdown_core`. Heuristic flags only apply
//! when the render pipeline cannot accept the source (invalid UTF-8 / resource limits).

use sha2::{Digest, Sha256};

use lomo_core::LomoError;
use lomo_workspace::{MemoIdentity, SourceBytes, render_markdown};

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

/// Projects the typed reminder references for one memo body using the workspace parser.
///
/// The document command and rebuild paths both use the same workspace parser.  A body passed by
/// the direct store writer normally has no header and therefore produces one plain-Markdown memo;
/// a full workspace document may contain headers, in which case the memo whose identity matches
/// `memo_id` is selected.  Ambiguous input is rejected instead of silently assigning reminders
/// from another memo to the current projection row.
///
/// # Errors
///
/// Returns validation when the source cannot be parsed, contains ambiguous memo identities, or
/// carries reminder facts for a non-canonical memo identity.
pub fn project_reminder_references(
    content: &str,
    memo_id: &str,
) -> Result<Vec<lomo_workspace::ReminderReference>, LomoError> {
    if content.trim().is_empty() {
        return Ok(Vec::new());
    }
    let source = SourceBytes::try_from_str(content)?;
    let document = lomo_workspace::parse_workspace_document(&source, memo_id)?;
    let selected = document
        .memos()
        .iter()
        .find(|memo| memo.identity().as_str() == memo_id)
        .or_else(|| {
            if document.memos().len() == 1 {
                document.memos().first()
            } else {
                None
            }
        });
    let Some(memo) = selected else {
        return Err(validation(
            "ambiguous_memo_reminder_projection",
            "document body contains multiple memo identities and none matches the target memo",
        ));
    };
    // Memo ids are opaque at the store command boundary: older Direct callers may use a stable
    // non-header id (for example an imported UUID). Keep the parse result as a first-class branch:
    // a canonical id is rebound to the parsed identity, while an opaque id deliberately retains
    // the parser's source-local identity. No validation error is silently erased.
    let canonical_identity = MemoIdentity::parse(memo_id);
    Ok(memo
        .reminders()
        .iter()
        .map(|reminder| {
            let rebound = match &canonical_identity {
                Ok(identity) => reminder.with_memo_identity(identity.clone()),
                Err(_opaque_identity) => reminder.clone(),
            };
            lomo_workspace::ReminderReference::from(&rebound)
        })
        .collect())
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

/// Maximum character budget for the list-row body preview.
pub const BODY_PREVIEW_MAX_CHARS: usize = 200;

/// Card-preview raw material for list rows.
///
/// The preview is the only body bytes a list row carries, and the app renders it as a standalone
/// Markdown document, so the cut must land on a block boundary: a blank-line separator when one
/// exists inside the budget, else a line boundary, and never inside an unclosed fenced code block.
#[must_use]
pub fn body_preview(content: &str) -> String {
    if content.chars().count() <= BODY_PREVIEW_MAX_CHARS {
        return content.to_owned();
    }
    let head: String = content.chars().take(BODY_PREVIEW_MAX_CHARS).collect();
    let cut = last_block_boundary(&head).unwrap_or(head.len());
    let cut = fence_safe_cut(&head, cut);
    // Cut points come from newline/fence-line boundaries, which are always char boundaries; a
    // boundary miss degrades to the un-cut head instead of trusting a possibly invalid slice.
    head.split_at_checked(cut)
        .map_or(head.as_str(), |(head_part, _tail)| head_part)
        .trim_end()
        .to_owned()
}

/// Materialized word count matching the statistics contract: maximal non-whitespace character runs.
#[must_use]
pub fn count_words(content: &str) -> i64 {
    let mut count = 0i64;
    let mut in_word = false;
    for ch in content.chars() {
        if ch.is_whitespace() {
            in_word = false;
        } else if !in_word {
            in_word = true;
            count += 1;
        }
    }
    count
}

/// Materialized character count in UTF-16 code units, matching the Kotlin statistics `String.length`.
#[must_use]
pub fn count_characters(content: &str) -> i64 {
    i64::try_from(content.encode_utf16().count()).unwrap_or(i64::MAX)
}

fn last_block_boundary(head: &str) -> Option<usize> {
    let blank = head
        .rfind("\n\n")
        .map(|index| index + 1)
        .or_else(|| head.rfind("\r\n\r\n").map(|index| index + 2));
    blank.or_else(|| {
        head.rfind('\n')
            .map(|index| index + 1)
            .or_else(|| head.rfind('\r').map(|index| index + 1))
    })
}

fn fence_safe_cut(head: &str, cut: usize) -> usize {
    let mut fence_open_lines: Vec<usize> = Vec::new();
    let mut offset = 0;
    for line in head.split_inclusive('\n') {
        if offset >= cut {
            break;
        }
        if line.trim_start().starts_with("```") {
            fence_open_lines.push(offset);
        }
        offset += line.len();
    }
    if fence_open_lines.len() % 2 == 1 {
        return fence_open_lines.last().copied().unwrap_or(0);
    }
    cut
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
