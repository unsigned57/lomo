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
