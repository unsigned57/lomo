//! Tooling-only linked native surface for stage-0 four-ABI packaging of feasibility deps.
//!
//! Production `app/jniLibs` must never package this crate. Used by `lomo-xtask` to produce and
//! ELF-verify a `.so` that actually links rusqlite/pulldown-cmark/reqwest/git2 via
//! live call paths (not Cargo dependency edges alone).

// Tooling-only C ABI retention entry (see `lomo_feasibility_device_run`). Not product FFI.
// `#[unsafe(no_mangle)]` on the volume-evidence entry requires an explicit crate allow while
// stage-0 four-ABI SO packaging still uses a linked C symbol rather than a BoltFFI probe.
#![allow(
    unsafe_code,
    reason = "tooling-only no_mangle C retention for feasibility-device SO volume evidence"
)]

use std::time::{SystemTime, UNIX_EPOCH};

use lomo_feasibility::{
    MARKER_SQLITE, candidate_link_markers, markers_include_git_and_http, probe_markdown_text,
    run_sqlite_probe,
};
use thiserror::Error;

/// Errors from the device feasibility probe bundle.
#[derive(Debug, Error)]
pub enum FeasibilityDeviceError {
    #[error("sqlite probe failed: {detail}")]
    Sqlite { detail: String },
    #[error("io failed: {detail}")]
    Io { detail: String },
}

/// Result summary for host tests and SO string retention.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeasibilityDeviceReport {
    pub sqlite_ok: bool,
    pub markdown_events: u64,
    pub detail: String,
}

/// Run host/device-safe probes that exercise the linked candidate graph.
///
/// Calls `candidate_link_markers` so LTO cannot drop git2 / reqwest / Rustls from the SO.
/// Full HTTPS/Git matrices remain host-fixture proven.
///
/// # Errors
///
/// Returns [`FeasibilityDeviceError`] when a probe fails.
pub fn run_feasibility_device_bundle() -> Result<FeasibilityDeviceReport, FeasibilityDeviceError> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| FeasibilityDeviceError::Io {
            detail: error.to_string(),
        })?
        .as_nanos();
    let path = std::env::temp_dir().join(format!("lomo-feasibility-device-{nanos}.sqlite"));
    run_sqlite_probe(&path).map_err(|error| FeasibilityDeviceError::Sqlite {
        detail: error.to_string(),
    })?;
    let removed: Result<(), std::io::Error> = std::fs::remove_file(&path);
    drop(removed);

    let sample = "# title\n\n- [ ] task\n![img](https://example.com/a.png)\n";
    let report = probe_markdown_text(sample, sample.len());
    // Live constructors for git2 + reqwest/Rustls (not a no-op module touch).
    let link_markers = candidate_link_markers();
    if !markers_include_git_and_http(&link_markers) {
        return Err(FeasibilityDeviceError::Io {
            detail: format!("link markers incomplete: {link_markers}"),
        });
    }
    // SQLite path already executed above; retain the exact sentinel for SO strings checks.
    let sqlite_marker = MARKER_SQLITE;

    let markdown_events =
        u64::try_from(report.event_count).map_err(|error| FeasibilityDeviceError::Io {
            detail: error.to_string(),
        })?;

    Ok(FeasibilityDeviceReport {
        sqlite_ok: true,
        markdown_events,
        detail: format!(
            "headings={} links={} images={}; {sqlite_marker}; {link_markers}",
            report.heading_count, report.link_count, report.image_count
        ),
    })
}

/// C ABI retention entry so four-ABI `cdylib` packaging cannot strip the live probe graph.
///
/// Tooling-only: this is not a product FFI. The `no_mangle` attribute is required so LTO cannot
/// drop the live SQLite/Markdown/git2/reqwest call graph from the volume evidence `.so`.
#[unsafe(no_mangle)]
pub extern "C" fn lomo_feasibility_device_run() -> i32 {
    match run_feasibility_device_bundle() {
        Ok(_) => 0,
        Err(_) => 1,
    }
}
