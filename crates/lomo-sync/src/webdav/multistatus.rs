//! Fail-closed Multi-Status (207) XML parser for `WebDAV` `PROPFIND`.
//!
//! Rejects `DOCTYPE` / entity declarations, oversized bodies, and illegal hrefs. No external
//! XML crate: a purpose-built local-name walker is enough for the PROPFIND surface and keeps
//! the production graph lean.

use crate::error::{resource_limit, validation};
use crate::limits::MAX_WEBDAV_MULTISTATUS_BYTES;
use crate::webdav::endpoint::WebDavEndpoint;
use lomo_core::LomoError;

/// One successful resource from a Multi-Status response.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MultistatusResource {
    pub relative_path: String,
    pub is_directory: bool,
    pub etag: Option<String>,
    pub content_length: Option<u64>,
}

/// Parses a 207 Multi-Status body into resource entries under `endpoint`.
///
/// # Errors
///
/// - Resource-limit when the body exceeds [`MAX_WEBDAV_MULTISTATUS_BYTES`]
/// - Validation when `DOCTYPE`/entities appear, XML is malformed, or hrefs are illegal
pub fn parse_multistatus(
    endpoint: &WebDavEndpoint,
    body: &[u8],
) -> Result<Vec<MultistatusResource>, LomoError> {
    if body.len() > MAX_WEBDAV_MULTISTATUS_BYTES {
        return Err(resource_limit(
            "webdav_multistatus_too_large",
            "webdav multi-status body exceeds the 2 MiB limit",
        ));
    }
    let text = std::str::from_utf8(body).map_err(|_error| {
        validation(
            "webdav_multistatus_not_utf8",
            "webdav multi-status body must be UTF-8",
        )
    })?;
    // Fail closed on DTD / entity expansion vectors before any structural walk.
    let lower = text.to_ascii_lowercase();
    if lower.contains("<!doctype")
        || lower.contains("<!entity")
        || lower.contains("<!element")
        || lower.contains("&xxe")
    {
        return Err(validation(
            "webdav_xml_entities_forbidden",
            "webdav multi-status must not declare DTD or entities",
        ));
    }
    if text.matches('<').count() > 50_000 {
        return Err(resource_limit(
            "webdav_xml_too_many_tags",
            "webdav multi-status tag count exceeds the safety ceiling",
        ));
    }

    let mut resources = Vec::new();
    for response_xml in split_by_local_element(text, "response") {
        if let Some(resource) = parse_response(endpoint, &response_xml)? {
            resources.push(resource);
        }
    }
    Ok(resources)
}

fn parse_response(
    endpoint: &WebDavEndpoint,
    response_xml: &str,
) -> Result<Option<MultistatusResource>, LomoError> {
    let Some(href) = first_local_text(response_xml, "href") else {
        return Err(validation(
            "webdav_response_missing_href",
            "webdav multi-status response is missing href",
        ));
    };
    let relative = match endpoint.relative_path_from_href(href.trim()) {
        Ok(path) => path,
        // Collection self-entry (href == root) is not a child path — skip it.
        Err(error) if error.code() == "webdav_href_is_root" => return Ok(None),
        Err(error) if error.code() == "webdav_href_empty" => return Ok(None),
        // Illegal / off-origin / traversal hrefs fail the whole snapshot closed.
        Err(error) => return Err(error),
    };
    let Some(prop_xml) = first_successful_prop(response_xml) else {
        return Ok(None);
    };
    let is_directory = local_present(&prop_xml, "collection")
        || first_local_text(&prop_xml, "resourcetype")
            .is_some_and(|value| value.to_ascii_lowercase().contains("collection"));
    let etag = first_local_text(&prop_xml, "getetag")
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    let content_length = first_local_text(&prop_xml, "getcontentlength")
        .map(str::trim)
        .and_then(optional_u64);
    Ok(Some(MultistatusResource {
        relative_path: relative,
        is_directory,
        etag,
        content_length,
    }))
}

fn first_successful_prop(response_xml: &str) -> Option<String> {
    for propstat in split_by_local_element(response_xml, "propstat") {
        let status = first_local_text(&propstat, "status").unwrap_or("");
        if status_is_success(status) {
            return first_local_inner(&propstat, "prop");
        }
    }
    None
}

fn status_is_success(status: &str) -> bool {
    status
        .split_whitespace()
        .any(|token| optional_u16(token).is_some_and(|code| (200..300).contains(&code)))
}

/// Optional integer parse: malformed values are absence, not a parse failure for Multi-Status props.
///
/// Workspace forbids [`Result::ok`] via `clippy::disallowed_methods`. This helper is the
/// intentional Option boundary rather than erasing errors at call sites with a bare `.ok()`.
#[expect(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    reason = "Result::ok is workspace-disallowed; optional property parse maps Err to None deliberately"
)]
fn optional_u64(value: &str) -> Option<u64> {
    match value.parse() {
        Ok(parsed) => Some(parsed),
        Err(_) => None,
    }
}

#[expect(
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    reason = "Result::ok is workspace-disallowed; optional status token parse maps Err to None deliberately"
)]
fn optional_u16(value: &str) -> Option<u16> {
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

fn first_local_inner(xml: &str, local: &str) -> Option<String> {
    let (_open_start, open_end, self_close) = find_open(xml, 0, local)?;
    if self_close {
        return Some(String::new());
    }
    let close_end = find_matching_close(xml, open_end, local)?;
    let close_lt = xml.get(..close_end)?.rfind("</")?;
    let inner = xml.get(open_end..close_lt)?;
    Some(inner.to_owned())
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

fn local_present(xml: &str, local: &str) -> bool {
    find_open(xml, 0, local).is_some()
}

/// Finds the next open tag with local name `local` at or after `from`.
/// Returns `(open_start, open_end_exclusive, is_self_closing)`.
fn find_open(xml: &str, from: usize, local: &str) -> Option<(usize, usize, bool)> {
    let bytes = xml.as_bytes();
    let mut index = from;
    while index < bytes.len() {
        if bytes.get(index).copied() != Some(b'<') {
            index = index.saturating_add(1);
            continue;
        }
        let after = index.saturating_add(1);
        let rest = xml.get(after..)?;
        if rest.starts_with('/') || rest.starts_with('!') || rest.starts_with('?') {
            index = after;
            continue;
        }
        let name_end = rest
            .find(|c: char| c.is_whitespace() || c == '>' || c == '/')
            .unwrap_or(rest.len());
        let name = rest.get(..name_end)?;
        let local_name = name.rsplit_once(':').map_or(name, |(_p, l)| l);
        if !local_name.eq_ignore_ascii_case(local) {
            index = after;
            continue;
        }
        let after_name = rest.get(name_end..)?;
        let gt_rel = after_name.find('>')?;
        let open_end = after
            .saturating_add(name_end)
            .saturating_add(gt_rel)
            .saturating_add(1);
        let open_tag = xml.get(index..open_end)?;
        let self_close = open_tag.ends_with("/>");
        return Some((index, open_end, self_close));
    }
    None
}

fn find_matching_close(xml: &str, from: usize, local: &str) -> Option<usize> {
    let mut depth = 1_i32;
    let mut cursor = from;
    while cursor < xml.len() {
        let (tag_start, tag_end, kind) = find_any_tag(xml, cursor, local)?;
        match kind {
            TagKind::Open => depth = depth.saturating_add(1),
            TagKind::SelfClose => {}
            TagKind::Close => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    // tag_start is only needed for open/close pairing bookkeeping.
                    debug_assert!(tag_start < tag_end);
                    return Some(tag_end);
                }
            }
        }
        let _: usize = tag_start;
        cursor = tag_end;
    }
    None
}

enum TagKind {
    Open,
    Close,
    SelfClose,
}

fn find_any_tag(xml: &str, from: usize, local: &str) -> Option<(usize, usize, TagKind)> {
    let bytes = xml.as_bytes();
    let mut index = from;
    while index < bytes.len() {
        if bytes.get(index).copied() != Some(b'<') {
            index = index.saturating_add(1);
            continue;
        }
        let after = index.saturating_add(1);
        let rest = xml.get(after..)?;
        if rest.starts_with('!') || rest.starts_with('?') {
            index = after;
            continue;
        }
        let is_close = rest.starts_with('/');
        let name_src = if is_close { rest.get(1..)? } else { rest };
        let name_end = name_src
            .find(|c: char| c.is_whitespace() || c == '>' || c == '/')
            .unwrap_or(name_src.len());
        let name = name_src.get(..name_end)?;
        let local_name = name.rsplit_once(':').map_or(name, |(_p, l)| l);
        if !local_name.eq_ignore_ascii_case(local) {
            index = after;
            continue;
        }
        let after_name = name_src.get(name_end..)?;
        let gt_rel = after_name.find('>')?;
        let full_len = 1_usize
            .saturating_add(usize::from(is_close))
            .saturating_add(name_end)
            .saturating_add(gt_rel)
            .saturating_add(1);
        let tag_end = index.saturating_add(full_len);
        let tag_text = xml.get(index..tag_end)?;
        let kind = if is_close {
            TagKind::Close
        } else if tag_text.ends_with("/>") {
            TagKind::SelfClose
        } else {
            TagKind::Open
        };
        return Some((index, tag_end, kind));
    }
    None
}
