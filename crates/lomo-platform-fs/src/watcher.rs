//! Directory change observation.
//!
//! Linux uses `inotify`; other targets (macOS, Windows) use a snapshot-diff
//! poller driven by the same non-blocking `poll_events` call.

#[cfg(target_os = "linux")]
mod inotify;
#[cfg(target_os = "linux")]
pub use inotify::DirectoryWatcher;

#[cfg(not(target_os = "linux"))]
mod poll;
#[cfg(not(target_os = "linux"))]
pub use poll::DirectoryWatcher;

use std::path::PathBuf;

/// `LomoError::code()` minted while the watched root cannot be observed —
/// deleted, moved away, or replaced by something unreadable.
///
/// Both backends retry the rebind internally on every poll; the failure is
/// typed transient so the consuming watch loop may bounded-retry it instead
/// of treating the first strike as a fatal outage.
pub const WATCH_ROOT_LOST_CODE: &str = "watch_root_lost";

/// Kind of filesystem change observed by `DirectoryWatcher`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ChangeKind {
    Created,
    Modified,
    Deleted,
    Renamed,
    Rescan,
    Invalidated,
}

/// A filesystem change event with its path relative to or within the watched directory.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DirectoryChangeEvent {
    pub kind: ChangeKind,
    pub path: PathBuf,
}
