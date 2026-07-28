//! Fail-closed `ListObjectsV2` XML parser (no external XML crate).

use crate::error::{resource_limit, validation};
use crate::limits::MAX_S3_LIST_BODY_BYTES;
use lomo_core::LomoError;

/// One object key from a `ListObjectsV2` response (files only; common prefixes ignored).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ListedObject {
    pub key: String,
    pub etag: Option<String>,
    pub size: Option<u64>,
}

/// Parsed `ListObjectsV2` page.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ListObjectsPage {
    pub objects: Vec<ListedObject>,
    pub is_truncated: bool,
    pub next_continuation_token: Option<String>,
}

/// Parses a `ListObjectsV2` XML body.
///
/// # Errors
///
/// Resource-limit when oversized; validation on DOCTYPE/entities/malformed structure.
pub fn parse_list_objects_v2(body: &[u8]) -> Result<ListObjectsPage, LomoError> {
    if body.len() > MAX_S3_LIST_BODY_BYTES {
        return Err(resource_limit(
            "s3_list_body_too_large",
            "s3 ListObjectsV2 body exceeds the 2 MiB limit",
        ));
    }
    let text = std::str::from_utf8(body)
        .map_err(|_error| validation("s3_list_not_utf8", "s3 ListObjectsV2 body must be UTF-8"))?;
    let lower = text.to_ascii_lowercase();
    if lower.contains("<!doctype")
        || lower.contains("<!entity")
        || lower.contains("<!element")
        || lower.contains("&xxe")
    {
        return Err(validation(
            "s3_xml_entities_forbidden",
            "s3 ListObjectsV2 must not declare DTD or entities",
        ));
    }
    if text.matches('<').count() > 50_000 {
        return Err(resource_limit(
            "s3_xml_too_many_tags",
            "s3 ListObjectsV2 tag count exceeds the safety ceiling",
        ));
    }

    let is_truncated = first_local_text(text, "IsTruncated")
        .is_some_and(|value| value.eq_ignore_ascii_case("true"));
    let next_continuation_token = first_local_text(text, "NextContinuationToken")
        .filter(|value| !value.is_empty())
        .map(str::to_owned);

    let mut objects = Vec::new();
    for contents in split_by_local_element(text, "Contents") {
        let Some(key) = first_local_text(contents.as_str(), "Key") else {
            return Err(validation(
                "s3_list_contents_missing_key",
                "s3 ListObjectsV2 Contents entry is missing Key",
            ));
        };
        if key.ends_with('/') {
            // Directory placeholder markers are not user-file objects.
            continue;
        }
        let etag = first_local_text(contents.as_str(), "ETag").map(normalize_etag);
        let size = first_local_text(contents.as_str(), "Size").and_then(optional_u64);
        objects.push(ListedObject {
            key: key.to_owned(),
            etag,
            size,
        });
    }

    Ok(ListObjectsPage {
        objects,
        is_truncated,
        next_continuation_token,
    })
}

fn normalize_etag(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.starts_with('"') && trimmed.ends_with('"') && trimmed.len() >= 2 {
        trimmed.to_owned()
    } else if trimmed.is_empty() {
        String::new()
    } else {
        format!("\"{trimmed}\"")
    }
}

/// Optional integer parse: malformed values are absence (list props), not a parse failure.
///
/// Workspace forbids [`Result::ok`] via `clippy::disallowed_methods`.
#[expect(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    reason = "Result::ok is workspace-disallowed; optional Size maps Err to None deliberately"
)]
fn optional_u64(value: &str) -> Option<u64> {
    match value.parse() {
        Ok(parsed) => Some(parsed),
        Err(_) => None,
    }
}

/// Splits `xml` into outer fragments for each element with the given local name.
fn split_by_local_element(xml: &str, local: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cursor = 0_usize;
    while cursor < xml.len() {
        let Some((open_start, open_end, self_close)) = find_open(xml, cursor, local) else {
            break;
        };
        if self_close {
            if let Some(fragment) = xml.get(open_start..open_end) {
                out.push(fragment.to_owned());
            }
            cursor = open_end;
            continue;
        }
        let Some(close_end) = find_matching_close(xml, open_end, local) else {
            break;
        };
        if let Some(fragment) = xml.get(open_start..close_end) {
            out.push(fragment.to_owned());
        }
        cursor = close_end;
    }
    out
}

fn first_local_text<'a>(xml: &'a str, local: &str) -> Option<&'a str> {
    let (_open_start, open_end, self_close) = find_open(xml, 0, local)?;
    if self_close {
        return Some("");
    }
    let close_end = find_matching_close(xml, open_end, local)?;
    let close_lt = xml.get(..close_end)?.rfind("</")?;
    xml.get(open_end..close_lt).map(str::trim)
}

fn find_open(xml: &str, from: usize, local: &str) -> Option<(usize, usize, bool)> {
    let hay = xml.get(from..)?;
    let mut search_from = 0_usize;
    while let Some(rel) = hay.get(search_from..)?.find('<') {
        let abs_in_hay = search_from + rel;
        let after = hay.get(abs_in_hay + 1..)?;
        if after.starts_with('/') || after.starts_with('!') || after.starts_with('?') {
            search_from = abs_in_hay + 1;
            continue;
        }
        let name_end = after
            .find(|c: char| c == '>' || c.is_whitespace() || c == '/')
            .unwrap_or(after.len());
        let name = after.get(..name_end)?;
        let local_name = name.rsplit_once(':').map_or(name, |(_, part)| part);
        if local_name != local {
            search_from = abs_in_hay + 1;
            continue;
        }
        let tag_end_rel = after.find('>')?;
        let open_end = from + abs_in_hay + 1 + tag_end_rel + 1;
        let open_start = from + abs_in_hay;
        let tag_body = after.get(..tag_end_rel)?;
        let self_close = tag_body.trim_end().ends_with('/');
        return Some((open_start, open_end, self_close));
    }
    None
}

fn find_matching_close(xml: &str, from: usize, local: &str) -> Option<usize> {
    let close_plain = format!("</{local}>");
    let close_ns = format!(":{local}>");
    let hay = xml.get(from..)?;
    // Prefer plain close; fall back to namespaced close tag ending.
    if let Some(rel) = hay.find(&close_plain) {
        return Some(from + rel + close_plain.len());
    }
    let mut search_from = 0_usize;
    while let Some(rel) = hay.get(search_from..)?.find(&close_ns) {
        let abs = search_from + rel;
        // Walk back to '<' of this close tag.
        let prefix = hay.get(..abs)?;
        let Some(lt_rel) = prefix.rfind('<') else {
            search_from = abs + 1;
            continue;
        };
        let candidate = hay.get(lt_rel..)?;
        if candidate.starts_with("</") {
            let gt = candidate.find('>')?;
            return Some(from + lt_rel + gt + 1);
        }
        search_from = abs + 1;
    }
    None
}
