use std::fs;
use std::path::Path;

use crate::error::TuiError;

/// Persists editor drafts under `$XDG_STATE_HOME/lomo/drafts`.
///
/// # Errors
/// Directory creation or write failures.
pub fn write_draft(path: &Path, content: &str) -> Result<(), TuiError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("md.tmp");
    fs::write(&tmp, content)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Removes a draft after a successful commit or empty cancel.
///
/// # Errors
/// Unexpected IO errors other than already-absent files.
pub fn remove_draft(path: &Path) -> Result<(), TuiError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(TuiError::io(error.to_string())),
    }
}

/// Writes conflict evidence beside the retained draft.
///
/// # Errors
/// Write failures.
pub fn write_conflict_evidence(
    drafts_dir: &Path,
    operation_id: &str,
    baseline: &str,
    disk: &str,
    draft: &str,
) -> Result<(), TuiError> {
    fs::create_dir_all(drafts_dir)?;
    let path = drafts_dir.join(format!("{operation_id}.conflict.txt"));
    let body = format!("baseline={baseline}\ndisk={disk}\n---\n{draft}");
    fs::write(path, body)?;
    Ok(())
}
