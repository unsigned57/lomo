use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::limits::validation;

const MAX_RELATIVE_PATH_BYTES: usize = 4_096;
const MAX_PATH_SEGMENT_BYTES: usize = 255;

/// Canonical workspace-relative path without normalization.
///
/// This mirrors the stage-1 engine path boundary so document commands and engine jobs share one
/// relative-path law. Absolute paths, `.` / `..`, empty segments, backslashes, controls, and
/// oversized paths are rejected at construction.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct WorkspaceRelativePath(String);

impl WorkspaceRelativePath {
    /// Parses a canonical workspace-relative path without normalization.
    ///
    /// # Errors
    ///
    /// Returns a validation error for empty/absolute/ambiguous/escaped/oversized paths.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        let has_windows_prefix = raw.as_bytes().get(1).is_some_and(|byte| *byte == b':');
        let invalid_segment = raw.split('/').any(|segment| {
            segment.is_empty()
                || matches!(segment, "." | "..")
                || segment.len() > MAX_PATH_SEGMENT_BYTES
        });
        if raw.is_empty()
            || raw.len() > MAX_RELATIVE_PATH_BYTES
            || raw.starts_with('/')
            || has_windows_prefix
            || raw.contains('\\')
            || raw.contains('\0')
            || raw.chars().any(char::is_control)
            || invalid_segment
        {
            return Err(validation(
                "invalid_workspace_path",
                "workspace path must be a bounded canonical relative UTF-8 path",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Stable memo identity: `${dateKey}_${timePart}_${ordinal}`.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct MemoIdentity {
    value: String,
    date_key: String,
    time_part: String,
    ordinal: u32,
}

impl MemoIdentity {
    /// Builds a memo identity from date key, time part, and zero-based ordinal.
    ///
    /// # Errors
    ///
    /// Returns a validation error when `date_key` / `time_part` are empty, contain separators that
    /// would break the wire form, or the rendered identity is empty.
    pub fn try_new(date_key: &str, time_part: &str, ordinal: u32) -> Result<Self, LomoError> {
        if date_key.is_empty()
            || time_part.is_empty()
            || date_key.contains('_')
            || time_part.contains('_')
            || date_key.chars().any(char::is_control)
            || time_part.chars().any(char::is_control)
        {
            return Err(validation(
                "invalid_memo_identity_parts",
                "date_key and time_part must be non-empty and free of '_' / controls",
            ));
        }
        let value = format!("{date_key}_{time_part}_{ordinal}");
        Ok(Self {
            value,
            date_key: date_key.to_owned(),
            time_part: time_part.to_owned(),
            ordinal,
        })
    }

    /// Parses `${dateKey}_${timePart}_${ordinal}` without normalizing body text.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the wire form does not match the identity contract.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        let mut parts = raw.rsplitn(2, '_');
        let ordinal_text = parts.next().ok_or_else(|| {
            validation(
                "invalid_memo_identity",
                "memo identity must be dateKey_timePart_ordinal",
            )
        })?;
        let prefix = parts.next().ok_or_else(|| {
            validation(
                "invalid_memo_identity",
                "memo identity must be dateKey_timePart_ordinal",
            )
        })?;
        let mut prefix_parts = prefix.splitn(2, '_');
        let date_key = prefix_parts.next().unwrap_or_default();
        let time_part = prefix_parts.next().unwrap_or_default();
        if date_key.is_empty() || time_part.is_empty() || prefix_parts.next().is_some() {
            return Err(validation(
                "invalid_memo_identity",
                "memo identity must be dateKey_timePart_ordinal",
            ));
        }
        let ordinal = ordinal_text.parse::<u32>().map_err(|_error| {
            validation(
                "invalid_memo_identity",
                "memo identity ordinal must be an unsigned integer",
            )
        })?;
        Self::try_new(date_key, time_part, ordinal)
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.value
    }

    #[must_use]
    pub fn date_key(&self) -> &str {
        &self.date_key
    }

    #[must_use]
    pub fn time_part(&self) -> &str {
        &self.time_part
    }

    #[must_use]
    pub const fn ordinal(&self) -> u32 {
        self.ordinal
    }
}
