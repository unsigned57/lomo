//! Snapshot-diff directory watcher for targets without `inotify` (macOS,
//! Windows, other non-Linux systems).
//!
//! The public contract is identical to the Linux backend: `poll_events` is a
//! non-blocking drain that returns the changes observed since the previous
//! call. Instead of kernel notifications, each poll walks the tree and diffs
//! `(kind, len, mtime, file identity)` against the stored snapshot. Create +
//! delete pairs carrying the same file identity are reported as `Renamed`, and
//! any change to the directory set is additionally signalled as `Rescan`, so
//! consumers rebuild derived state exactly as they do for inotify watch-graph
//! changes.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use lomo_core::LomoError;

use super::{ChangeKind, DirectoryChangeEvent};
use crate::error::storage;

/// Polling directory change observer.
pub struct DirectoryWatcher {
    root: PathBuf,
    snapshot: BTreeMap<PathBuf, EntryStamp>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct EntryStamp {
    is_dir: bool,
    len: u64,
    modified: Option<SystemTime>,
    identity: Option<FileIdentity>,
}

/// Stable file identity used to pair a delete with the matching create as a rename.
/// `None` where no stable std API exposes one (Windows `file_index` is unstable);
/// unpaired creates/deletes then surface as `Created` + `Deleted`, which carries
/// the same information.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct FileIdentity(u64, u64);

#[cfg(unix)]
fn file_identity(metadata: &std::fs::Metadata) -> Option<FileIdentity> {
    use std::os::unix::fs::MetadataExt;
    Some(FileIdentity(metadata.dev(), metadata.ino()))
}

#[cfg(not(unix))]
fn file_identity(_metadata: &std::fs::Metadata) -> Option<FileIdentity> {
    None
}

fn stamp_of(metadata: &std::fs::Metadata) -> EntryStamp {
    EntryStamp {
        is_dir: metadata.is_dir(),
        len: metadata.len(),
        modified: metadata.modified().ok(),
        identity: file_identity(metadata),
    }
}

fn scan_into(root: &Path, out: &mut BTreeMap<PathBuf, EntryStamp>) -> Result<(), LomoError> {
    let entries = std::fs::read_dir(root).map_err(|err| {
        storage(
            "watcher_read_dir_failed",
            &format!("failed to read directory '{}': {err}", root.display()),
        )
    })?;
    for entry in entries {
        let entry = entry.map_err(|err| {
            storage(
                "watcher_read_entry_failed",
                &format!("failed to read entry in '{}': {err}", root.display()),
            )
        })?;
        let path = entry.path();
        let metadata = std::fs::symlink_metadata(&path).map_err(|err| {
            storage(
                "watcher_stat_failed",
                &format!("failed to stat '{}': {err}", path.display()),
            )
        })?;
        let is_dir = metadata.is_dir();
        out.insert(path.clone(), stamp_of(&metadata));
        if is_dir {
            scan_into(&path, out)?;
        }
    }
    Ok(())
}

fn snapshot(root: &Path) -> Result<BTreeMap<PathBuf, EntryStamp>, LomoError> {
    let mut out = BTreeMap::new();
    let metadata = std::fs::symlink_metadata(root).map_err(|err| {
        storage(
            "watcher_stat_failed",
            &format!("failed to stat '{}': {err}", root.display()),
        )
    })?;
    if !metadata.is_dir() {
        return Err(storage(
            "watcher_root_invalid",
            &format!("watch root '{}' is not a directory", root.display()),
        ));
    }
    out.insert(root.to_path_buf(), stamp_of(&metadata));
    scan_into(root, &mut out)?;
    Ok(out)
}

impl DirectoryWatcher {
    /// Takes the initial snapshot of `root` and all nested subdirectories.
    ///
    /// # Errors
    /// Storage error when the tree cannot be walked.
    pub fn new(root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let root = root.as_ref().to_path_buf();
        let snapshot = snapshot(&root)?;
        Ok(Self { root, snapshot })
    }

    /// Diffs the current tree against the stored snapshot without blocking.
    ///
    /// # Errors
    /// Storage error when the tree cannot be walked.
    pub fn poll_events(&mut self) -> Result<Vec<DirectoryChangeEvent>, LomoError> {
        if !self.root.is_dir() {
            let had_entries = !self.snapshot.is_empty();
            self.snapshot.clear();
            return Ok(if had_entries {
                vec![DirectoryChangeEvent {
                    kind: ChangeKind::Invalidated,
                    path: self.root.clone(),
                }]
            } else {
                Vec::new()
            });
        }
        let next = snapshot(&self.root)?;
        let mut events = Vec::new();
        let mut deleted: Vec<PathBuf> = Vec::new();
        let mut created: Vec<PathBuf> = Vec::new();
        let mut structure_changed = false;

        for (path, stamp) in &self.snapshot {
            match next.get(path) {
                None => {
                    deleted.push(path.clone());
                    structure_changed |= stamp.is_dir;
                }
                Some(current) if current != stamp => {
                    events.push(DirectoryChangeEvent {
                        kind: ChangeKind::Modified,
                        path: path.clone(),
                    });
                }
                Some(_) => {}
            }
        }
        for (path, stamp) in &next {
            if !self.snapshot.contains_key(path) {
                created.push(path.clone());
                structure_changed |= stamp.is_dir;
            }
        }

        // Pair creates with deletes sharing one file identity into renames. Both endpoints
        // are reported as `Renamed` — the inotify backend emits one event for MOVED_FROM and
        // one for MOVED_TO, so the poll backend must carry the same two-endpoint attestation:
        // consumers treat each event path as observed coverage, and a destination-only report
        // would leave the source path's derived state certified stale.
        for create in std::mem::take(&mut created) {
            let identity = next[&create].identity;
            let pair = identity.and_then(|id| {
                deleted
                    .iter()
                    .position(|gone| self.snapshot[gone].identity == Some(id))
            });
            if let Some(pos) = pair {
                let source = deleted.remove(pos);
                events.push(DirectoryChangeEvent {
                    kind: ChangeKind::Renamed,
                    path: source,
                });
                events.push(DirectoryChangeEvent {
                    kind: ChangeKind::Renamed,
                    path: create,
                });
            } else {
                events.push(DirectoryChangeEvent {
                    kind: ChangeKind::Created,
                    path: create,
                });
            }
        }
        events.extend(deleted.into_iter().map(|path| DirectoryChangeEvent {
            kind: ChangeKind::Deleted,
            path,
        }));
        if structure_changed {
            events.push(DirectoryChangeEvent {
                kind: ChangeKind::Rescan,
                path: self.root.clone(),
            });
        }

        self.snapshot = next;
        Ok(events)
    }

    /// Re-diffs the tree in short sleeps until a change appears or `timeout`
    /// elapses; snapshot polling has no kernel wake to wait on.
    ///
    /// # Errors
    /// Same surface as `poll_events`.
    pub fn wait_events(
        &mut self,
        timeout: std::time::Duration,
    ) -> Result<Vec<DirectoryChangeEvent>, LomoError> {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            let events = self.poll_events()?;
            if !events.is_empty() {
                return Ok(events);
            }
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                return Ok(Vec::new());
            }
            std::thread::sleep(remaining.min(std::time::Duration::from_millis(10)));
        }
    }
}
