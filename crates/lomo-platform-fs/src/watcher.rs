use std::collections::BTreeMap;
use std::mem::MaybeUninit;
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use rustix::fd::OwnedFd;
use rustix::fs::inotify::{self, CreateFlags, ReadFlags, WatchFlags};
use rustix::io::Errno;

use crate::error::{storage, validation};

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

/// Inotify-based directory change observer for Linux POSIX.
pub struct DirectoryWatcher {
    root: PathBuf,
    fd: OwnedFd,
    watch_descriptors: BTreeMap<i32, PathBuf>,
}

fn watch_flags() -> WatchFlags {
    WatchFlags::CREATE
        | WatchFlags::DELETE
        | WatchFlags::MODIFY
        | WatchFlags::CLOSE_WRITE
        | WatchFlags::MOVED_FROM
        | WatchFlags::MOVED_TO
        | WatchFlags::DELETE_SELF
        | WatchFlags::MOVE_SELF
        | WatchFlags::DONT_FOLLOW
}

fn add_watch_recursive(
    fd: &OwnedFd,
    dir: &Path,
    flags: WatchFlags,
    wds: &mut BTreeMap<i32, PathBuf>,
) -> Result<(), LomoError> {
    let wd = inotify::add_watch(fd, dir, flags).map_err(|err| {
        storage(
            "inotify_watch_failed",
            &format!("failed to watch directory '{}': {err}", dir.display()),
        )
    })?;
    wds.insert(wd, dir.to_path_buf());

    let entries = std::fs::read_dir(dir).map_err(|err| {
        storage(
            "inotify_read_dir_failed",
            &format!("failed to read directory '{}': {err}", dir.display()),
        )
    })?;

    for entry_res in entries {
        let entry = entry_res.map_err(|err| {
            storage(
                "inotify_read_entry_failed",
                &format!("failed to read entry in '{}': {err}", dir.display()),
            )
        })?;
        let ft = entry.file_type().map_err(|err| {
            storage(
                "inotify_file_type_failed",
                &format!("failed to query file type in '{}': {err}", dir.display()),
            )
        })?;
        if ft.is_dir() && !ft.is_symlink() {
            add_watch_recursive(fd, &entry.path(), flags, wds)?;
        }
    }
    Ok(())
}

impl DirectoryWatcher {
    /// Initializes a directory change watcher for `root` and all nested subdirectories.
    ///
    /// # Errors
    ///
    /// Returns `LomoError` if inotify cannot be initialized or the watch cannot be registered.
    pub fn new(root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let root = root.as_ref().to_path_buf();
        let fd = inotify::init(CreateFlags::CLOEXEC | CreateFlags::NONBLOCK).map_err(|err| {
            storage(
                "inotify_init_failed",
                &format!("inotify_init1 failed: {err}"),
            )
        })?;

        let mut watch_descriptors = BTreeMap::new();
        add_watch_recursive(&fd, &root, watch_flags(), &mut watch_descriptors)?;

        Ok(Self {
            root,
            fd,
            watch_descriptors,
        })
    }

    /// Polls pending filesystem events without blocking.
    ///
    /// # Errors
    ///
    /// Returns `LomoError` if inotify event reading fails unexpectedly,
    /// a non-UTF-8 filename is encountered, or an unknown watch descriptor is reported.
    pub fn poll_events(&mut self) -> Result<Vec<DirectoryChangeEvent>, LomoError> {
        let mut events = Vec::new();
        let mut rebuild = false;
        let mut root_invalidated = false;
        {
            let mut buf = [MaybeUninit::uninit(); 4096];
            let mut reader = inotify::Reader::new(&self.fd, &mut buf);
            loop {
                let entry = match reader.next() {
                    Ok(entry) => entry,
                    Err(Errno::WOULDBLOCK) => break,
                    Err(error) => return Err(storage("inotify_read_failed", &error.to_string())),
                };
                let mask = entry.events();
                if mask.contains(ReadFlags::QUEUE_OVERFLOW) {
                    rebuild = true;
                    continue;
                }
                let Some(base) = self.watch_descriptors.get(&entry.wd()) else {
                    // Kernel descriptors may be retired together in one read. Rebuild the watch
                    // graph and require a full scan instead of inventing a path for lost events.
                    rebuild = true;
                    continue;
                };
                if mask.intersects(
                    ReadFlags::IGNORED
                        | ReadFlags::DELETE_SELF
                        | ReadFlags::MOVE_SELF
                        | ReadFlags::UNMOUNT,
                ) {
                    events.push(DirectoryChangeEvent {
                        kind: ChangeKind::Invalidated,
                        path: base.clone(),
                    });
                    root_invalidated |= base == &self.root;
                    rebuild = true;
                    continue;
                }
                let path = match entry.file_name() {
                    Some(name) => base.join(name.to_str().map_err(|error| {
                        validation("invalid_utf8_filename", &error.to_string())
                    })?),
                    None => base.clone(),
                };
                if mask.contains(ReadFlags::ISDIR)
                    && mask.intersects(
                        ReadFlags::CREATE
                            | ReadFlags::DELETE
                            | ReadFlags::MOVED_FROM
                            | ReadFlags::MOVED_TO,
                    )
                {
                    rebuild = true;
                }
                if let Some(kind) = map_change_kind(mask) {
                    events.push(DirectoryChangeEvent { kind, path });
                }
            }
        }
        if rebuild && !root_invalidated {
            // Register the new graph before publishing Rescan. Any changes in the transition
            // window are covered by the following application scan.
            let next = Self::new(&self.root)?;
            self.fd = next.fd;
            self.watch_descriptors = next.watch_descriptors;
            events.push(DirectoryChangeEvent {
                kind: ChangeKind::Rescan,
                path: self.root.clone(),
            });
        }

        Ok(events)
    }
}

const fn map_change_kind(mask: ReadFlags) -> Option<ChangeKind> {
    if mask.contains(ReadFlags::CREATE) {
        Some(ChangeKind::Created)
    } else if mask.contains(ReadFlags::DELETE) {
        Some(ChangeKind::Deleted)
    } else if mask.contains(ReadFlags::MOVED_FROM) || mask.contains(ReadFlags::MOVED_TO) {
        Some(ChangeKind::Renamed)
    } else if mask.contains(ReadFlags::MODIFY) || mask.contains(ReadFlags::CLOSE_WRITE) {
        Some(ChangeKind::Modified)
    } else {
        None
    }
}
