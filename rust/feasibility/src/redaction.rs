use std::path::{Component, Path};

use thiserror::Error;

/// Failures when converting or redacting report text.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum RedactionError {
    #[error("absolute path is not under the repository root")]
    PathEscapesRepository,
    #[error("path is empty after normalization")]
    EmptyPath,
}

/// Convert an absolute path under `repository_root` into a report-safe relative path.
///
/// # Errors
///
/// Returns [`RedactionError`] when the path escapes the repository or normalizes to empty.
pub fn relative_path_for_report(
    repository_root: &Path,
    absolute_path: &Path,
) -> Result<String, RedactionError> {
    let relative = absolute_path
        .strip_prefix(repository_root)
        .map_err(|_prefix| RedactionError::PathEscapesRepository)?;
    if relative.as_os_str().is_empty() {
        return Err(RedactionError::EmptyPath);
    }
    if relative
        .components()
        .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(RedactionError::PathEscapesRepository);
    }
    let rendered = relative.to_string_lossy().replace('\\', "/");
    if rendered.is_empty() {
        return Err(RedactionError::EmptyPath);
    }
    Ok(rendered)
}

/// Remove credentials, absolute host paths, and free-form memo bodies from report notes.
#[must_use]
pub fn redact_sensitive_text(input: &str) -> String {
    let without_credentials = redact_credential_tokens(input);
    let without_paths = redact_absolute_paths(&without_credentials);
    redact_body_markers(&without_paths)
}

fn redact_credential_tokens(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    for token in input.split_whitespace() {
        if !output.is_empty() {
            output.push(' ');
        }
        if looks_like_secret(token) {
            output.push_str("[REDACTED_SECRET]");
        } else {
            output.push_str(token);
        }
    }
    if input.ends_with('\n') {
        output.push('\n');
    }
    output
}

fn looks_like_secret(token: &str) -> bool {
    let lower = token.to_ascii_lowercase();
    if lower.contains("password=")
        || lower.contains("secret=")
        || lower.contains("token=")
        || lower.contains("authorization:")
        || lower.starts_with("akid")
    {
        return true;
    }
    // Opaque base64-like tokens only — never absolute paths or repo-relative segments.
    token.len() >= 32
        && !token.contains('/')
        && !token.contains('\\')
        && token
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '+' || ch == '=')
}

fn redact_absolute_paths(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(start) = rest.find('/') {
        output.push_str(&rest[..start]);
        let candidate = &rest[start..];
        let end = candidate
            .find(|ch: char| ch.is_whitespace() || ch == '"' || ch == '\'')
            .unwrap_or(candidate.len());
        let path = &candidate[..end];
        if path.starts_with("/home/")
            || path.starts_with("/Users/")
            || path.starts_with("/tmp/")
            || path.starts_with("/var/")
            || path.starts_with("/opt/")
        {
            output.push_str("[REDACTED_PATH]");
        } else {
            output.push_str(path);
        }
        rest = &candidate[end..];
    }
    output.push_str(rest);
    output
}

fn redact_body_markers(input: &str) -> String {
    const MARKERS: [&str; 2] = ["memo_body=", "content="];
    let mut output = input.to_owned();
    for marker in MARKERS {
        let mut search_from = 0;
        while let Some(relative) = output[search_from..].find(marker) {
            let start = search_from + relative;
            let after = start + marker.len();
            let end = output[after..]
                .find(|ch: char| ch.is_whitespace())
                .map_or(output.len(), |offset| after + offset);
            output.replace_range(after..end, "[REDACTED_BODY]");
            search_from = after + "[REDACTED_BODY]".len();
        }
    }
    output
}
