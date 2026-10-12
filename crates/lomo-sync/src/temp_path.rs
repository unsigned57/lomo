//! Collision-resistant temporary staging filenames for remote downloads (B-14 legacy fix).
//!
//! The previous `nanos-or-0` scheme could hand concurrent GET bodies the same path: a
//! clock error collapsed every name to `…-0.part`, and same-nanosecond calls raced the
//! shared file. Uniqueness now comes from a process-wide monotonic sequence; the
//! nanosecond timestamp remains only as a human-readable diagnostic component.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Process-wide sequence: the uniqueness witness even when the wall clock is broken.
static TEMP_NAME_SEQ: AtomicU64 = AtomicU64::new(0);

/// Builds `lomo-{kind}-{prefix}-{nanos}-{seq}.part` under `temp_dir`.
///
/// `seq` is monotonic per process and never repeats, so same-nanosecond or
/// clock-before-epoch calls still receive distinct paths. `nanos` is diagnostic only —
/// a clock error yields `0` without weakening uniqueness.
#[must_use]
pub fn staging_path(temp_dir: &Path, kind: &str, prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_nanos());
    let seq = TEMP_NAME_SEQ.fetch_add(1, Ordering::Relaxed);
    temp_dir.join(format!("lomo-{kind}-{prefix}-{nanos}-{seq}.part"))
}
