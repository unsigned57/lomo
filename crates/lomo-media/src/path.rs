//! Canonical media relative-path validation via workspace path policy.

use lomo_core::LomoError;
use lomo_workspace::WorkspaceRelativePath;
use serde::{Deserialize, Serialize};

use crate::error::validation;
use crate::identity::MediaMime;

/// Media relative path: thin newtype over [`WorkspaceRelativePath`].
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct MediaRelativePath(WorkspaceRelativePath);

impl MediaRelativePath {
    /// Parses a canonical workspace-relative media path without normalization.
    ///
    /// # Errors
    ///
    /// Returns validation for empty/absolute/ambiguous/escaped/oversized paths.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        let inner = WorkspaceRelativePath::parse(raw)?;
        // Media paths must not land under control dirs that are never media.
        let first = raw.split('/').next().unwrap_or("");
        if matches!(
            first,
            ".lomo" | ".lomo-sqlite" | ".lomo-media-trash" | ".lomo-media-stage"
        ) {
            // Staging/trash roots are owned paths; only forbid .lomo and sqlite as media targets.
            if matches!(first, ".lomo" | ".lomo-sqlite") {
                return Err(validation(
                    "invalid_media_path_root",
                    "media path must not target .lomo or .lomo-sqlite control directories",
                ));
            }
        }
        Ok(Self(inner))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        self.0.as_str()
    }

    #[must_use]
    pub const fn workspace_path(&self) -> &WorkspaceRelativePath {
        &self.0
    }
}

/// Suggests a human final relative path under `media/` using mime extension (not hash name).
///
/// # Errors
///
/// Returns validation when the composed path is invalid.
pub fn suggest_human_relative_path(
    human_stem: &str,
    mime: MediaMime,
) -> Result<MediaRelativePath, LomoError> {
    let stem = if human_stem.trim().is_empty() {
        "attachment"
    } else {
        human_stem.trim()
    };
    // Strip path separators from stem for safety.
    let safe: String = stem
        .chars()
        .map(|c| {
            if c == '/' || c == '\\' || c.is_control() {
                '_'
            } else {
                c
            }
        })
        .collect();
    let relative = format!("media/{safe}.{}", mime.preferred_extension());
    MediaRelativePath::parse(&relative)
}
