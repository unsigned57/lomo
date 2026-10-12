//! Fail-closed `ListObjectsV2` XML parser over `quick-xml` (B-14 P-50).
//!
//! Cheap byte-level guards (body size, UTF-8, DTD/entity pre-scan, tag ceiling) run before
//! the event parser; [`crate::xml::parse_document`] then enforces structure, decodes
//! predefined entities/CDATA, and rejects undeclared references.

use crate::error::{resource_limit, validation};
use crate::limits::MAX_S3_LIST_BODY_BYTES;
use crate::xml::parse_document;
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

    let Some(root) = parse_document(
        text,
        "s3_list_xml_malformed",
        "s3 ListObjectsV2 body is malformed XML",
        "s3_xml_entities_forbidden",
        "s3 ListObjectsV2 must not declare DTD or entities",
    )?
    else {
        return Ok(ListObjectsPage {
            objects: Vec::new(),
            is_truncated: false,
            next_continuation_token: None,
        });
    };

    let is_truncated = root
        .find_first("IsTruncated", false)
        .is_some_and(|element| element.trimmed_text().eq_ignore_ascii_case("true"));
    let next_continuation_token = root
        .find_first("NextContinuationToken", false)
        .map(|element| element.trimmed_text().to_owned())
        .filter(|value| !value.is_empty());

    let mut objects = Vec::new();
    let mut contents = Vec::new();
    root.find_all("Contents", false, &mut contents);
    for contents_element in contents {
        let Some(key_element) = contents_element.find_first("Key", false) else {
            return Err(validation(
                "s3_list_contents_missing_key",
                "s3 ListObjectsV2 Contents entry is missing Key",
            ));
        };
        let key = key_element.trimmed_text();
        if key.ends_with('/') {
            // Directory placeholder markers are not user-file objects.
            continue;
        }
        let etag = contents_element
            .find_first("ETag", false)
            .map(|element| normalize_etag(element.trimmed_text()));
        let size = contents_element
            .find_first("Size", false)
            .and_then(|element| optional_u64(element.trimmed_text()));
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
