use std::collections::BTreeSet;

use crate::model::{MAX_ITEMS, MAX_STRING_BYTES, ProtocolError, Request};

pub fn validate_request(request: &Request) -> Result<(), ProtocolError> {
    if request.timestamp_tolerance_ms < 0 {
        return Err(ProtocolError::NegativeValue {
            field: "timestamp_tolerance_ms",
            value: request.timestamp_tolerance_ms,
        });
    }
    for (field, count) in [
        ("local", request.local.len()),
        ("remote", request.remote.len()),
        ("metadata", request.metadata.len()),
    ] {
        if count > MAX_ITEMS {
            return Err(ProtocolError::InvalidCount {
                field,
                value: count,
            });
        }
    }
    validate_unique_paths("local", request.local.iter().map(|item| item.path.as_str()))?;
    validate_unique_paths(
        "remote",
        request.remote.iter().map(|item| item.path.as_str()),
    )?;
    validate_unique_paths(
        "metadata",
        request.metadata.iter().map(|item| item.path.as_str()),
    )?;
    validate_unique_paths(
        "pre_resolved",
        request.pre_resolved.iter().map(|item| item.path.as_str()),
    )?;
    validate_unique_paths("suppressed", request.suppressed.iter().map(String::as_str))?;
    validate_unique_paths(
        "missing_remote_verification",
        request
            .missing_remote_verification
            .iter()
            .map(|(path, _)| path.as_str()),
    )?;
    for snapshot in &request.local {
        validate_path(&snapshot.path)?;
        validate_optional_string("local fingerprint", snapshot.fingerprint.as_deref())?;
        validate_non_negative("local size", snapshot.size)?;
    }
    for snapshot in &request.remote {
        validate_path(&snapshot.path)?;
        validate_optional_string("remote etag", snapshot.etag.as_deref())?;
        validate_optional_string("remote fingerprint", snapshot.fingerprint.as_deref())?;
        validate_non_negative("remote size", snapshot.size)?;
    }
    for snapshot in &request.metadata {
        validate_path(&snapshot.path)?;
        validate_optional_string("metadata etag", snapshot.etag.as_deref())?;
        validate_optional_string(
            "metadata fingerprint",
            snapshot.local_fingerprint.as_deref(),
        )?;
    }
    for action in &request.pre_resolved {
        validate_path(&action.path)?;
    }
    for path in &request.suppressed {
        validate_path(path)?;
    }
    for (path, _) in &request.missing_remote_verification {
        validate_path(path)?;
    }
    Ok(())
}

fn validate_unique_paths<'a>(
    field: &'static str,
    paths: impl IntoIterator<Item = &'a str>,
) -> Result<(), ProtocolError> {
    let mut seen = BTreeSet::new();
    for path in paths {
        if !seen.insert(path) {
            return Err(ProtocolError::DuplicatePath {
                field,
                path: path.to_owned(),
            });
        }
    }
    Ok(())
}

pub fn validate_path(path: &str) -> Result<(), ProtocolError> {
    if path.is_empty()
        || path.as_bytes().contains(&0)
        || path.starts_with('/')
        || path.starts_with('\\')
        || path
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        return Err(ProtocolError::InvalidPath {
            path: path.to_owned(),
        });
    }
    validate_string("path", path)
}

fn validate_optional_string(field: &'static str, value: Option<&str>) -> Result<(), ProtocolError> {
    if let Some(value) = value {
        validate_string(field, value)?;
    }
    Ok(())
}

pub fn validate_string(field: &'static str, value: &str) -> Result<(), ProtocolError> {
    if value.len() > MAX_STRING_BYTES || value.as_bytes().contains(&0) {
        return Err(ProtocolError::InvalidString { field });
    }
    Ok(())
}

pub const fn validate_non_negative(
    field: &'static str,
    value: Option<i64>,
) -> Result<(), ProtocolError> {
    if let Some(value) = value
        && value < 0
    {
        return Err(ProtocolError::NegativeValue { field, value });
    }
    Ok(())
}
