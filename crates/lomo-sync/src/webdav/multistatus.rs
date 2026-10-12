//! Fail-closed Multi-Status (207) XML parser for `WebDAV` `PROPFIND`, over `quick-xml`
//! (B-14 P-50).
//!
//! Rejects `DOCTYPE` / entity declarations, oversized bodies, malformed structure, and
//! illegal hrefs. Cheap byte-level guards run before the event parser; local-name matching
//! stays namespace-insensitive and ASCII case-insensitive like the old walker.

use crate::error::{resource_limit, validation};
use crate::limits::MAX_WEBDAV_MULTISTATUS_BYTES;
use crate::webdav::endpoint::WebDavEndpoint;
use crate::xml::{XmlElement, parse_document};
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

    let Some(root) = parse_document(
        text,
        "webdav_multistatus_xml_malformed",
        "webdav multi-status body is malformed XML",
        "webdav_xml_entities_forbidden",
        "webdav multi-status must not declare DTD or entities",
    )?
    else {
        return Ok(Vec::new());
    };

    let mut responses = Vec::new();
    root.find_all("response", true, &mut responses);
    let mut resources = Vec::new();
    for response_element in responses {
        if let Some(resource) = parse_response(endpoint, response_element)? {
            resources.push(resource);
        }
    }
    Ok(resources)
}

fn parse_response(
    endpoint: &WebDavEndpoint,
    response_element: &XmlElement,
) -> Result<Option<MultistatusResource>, LomoError> {
    let Some(href_element) = response_element.find_first("href", true) else {
        return Err(validation(
            "webdav_response_missing_href",
            "webdav multi-status response is missing href",
        ));
    };
    let relative = match endpoint.relative_path_from_href(href_element.trimmed_text()) {
        Ok(path) => path,
        // Collection self-entry (href == root) is not a child path — skip it.
        Err(error) if error.code() == "webdav_href_is_root" => return Ok(None),
        Err(error) if error.code() == "webdav_href_empty" => return Ok(None),
        // Illegal / off-origin / traversal hrefs fail the whole snapshot closed.
        Err(error) => return Err(error),
    };
    let Some(prop_element) = first_successful_prop(response_element) else {
        return Ok(None);
    };
    let is_directory = prop_element.find_first("collection", true).is_some()
        || prop_element
            .find_first("resourcetype", true)
            .is_some_and(|element| element.text.to_ascii_lowercase().contains("collection"));
    let etag = prop_element
        .find_first("getetag", true)
        .map(XmlElement::trimmed_text)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned);
    let content_length = prop_element
        .find_first("getcontentlength", true)
        .map(XmlElement::trimmed_text)
        .and_then(optional_u64);
    Ok(Some(MultistatusResource {
        relative_path: relative,
        is_directory,
        etag,
        content_length,
    }))
}

/// Returns the `prop` element of the first `propstat` whose status carries a 2xx code.
/// A 2xx `propstat` without `prop` ends the search with `None`, like the old parser.
fn first_successful_prop(response_element: &XmlElement) -> Option<&XmlElement> {
    let mut propstats = Vec::new();
    response_element.find_all("propstat", true, &mut propstats);
    for propstat in propstats {
        let status = propstat
            .find_first("status", true)
            .map_or("", XmlElement::trimmed_text);
        if status_is_success(status) {
            return propstat.find_first("prop", true);
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
