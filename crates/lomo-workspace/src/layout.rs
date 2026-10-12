//! Canonical workspace and session layout inventory (P-26 / R-09).
//!
//! [`WorkspaceLayout`] is the sole dispatch point for every path Lomo mints under
//! `<workspace>/.lomo/` — the durable record codec tree plus internal subtrees
//! (`local`, `identity`, `trash`, `purged`, `sync`, `migration-staging`). A new
//! `.lomo` child directory is added as a field here first; consumers never join
//! `.lomo` segments themselves.
//!
//! The record codec framing lives in `crate::lomo_record`; this module owns
//! only *where* records live and the head that selects the history/state layout
//! version.
//!
//! [`SessionStateLayout`] owns the names minted inside a *session-private* state
//! directory (`drafts`, `capture`, `intents`, `intent-payloads`, `baselines`,
//! `device_id`, `exchange`), so the Android per-workspace control root
//! (`control_root/session/<identity>/state`) and the TUI state dir resolve the
//! same children. [`SessionLayout`] mints the per-workspace session tree under
//! a host control root.
//!
//! Reserved sibling roots at the workspace root are exported as names, not
//! layout fields: [`MEDIA_ROOT_NAME`], [`MEMO_ROOT_NAME`],
//! [`LEGACY_TRASH_ROOT_NAME`], [`GIT_ROOT_NAME`], [`LOMO_SQLITE_ROOT_NAME`] and
//! [`LOMO_REMOTE_ROOT_NAME`]. The `.lomo-media-*` lifecycle roots are owned by
//! `lomo-media` (`STAGE_DIR_NAME`, `MEDIA_TRASH_DIR_NAME`,
//! `MEDIA_DELETE_INTENT_DIR_NAME`); they are re-declared there, not here, because
//! `lomo-media` depends on this crate and not the reverse.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::limits::{corruption, storage, validation};
use crate::lomo_record::{LomoPayload, LomoRecordKind, read_record, write_record_atomic};

/// The `.lomo` durable control directory name at a workspace root.
pub const LOMO_DIR_NAME: &str = ".lomo";
/// The disposable `SQLite` projection root sibling to `.lomo` (owner: `lomo-store`).
pub const LOMO_SQLITE_ROOT_NAME: &str = ".lomo-sqlite";
/// Foreign remote-control trees mirrored into a workspace (never leave the device).
pub const LOMO_REMOTE_ROOT_NAME: &str = ".lomo-remote";
/// The committed media documents root at a workspace root.
pub const MEDIA_ROOT_NAME: &str = "media";
/// Platform-configured image attachment root at a workspace root.
///
/// The Android client materializes its managed image directory under this name
/// (`IMAGE_DIRECTORY_NAME`); bare attachment basenames may claim files here.
pub const IMAGES_ROOT_NAME: &str = "images";
/// Platform-configured voice attachment root at a workspace root.
///
/// The Android client materializes its managed voice directory under this name
/// (`VOICE_DIRECTORY_NAME`); bare attachment basenames may claim files here.
pub const VOICE_ROOT_NAME: &str = "voice";
/// The committed memo documents root at a workspace root.
pub const MEMO_ROOT_NAME: &str = "memos";
/// The pre-record-model physical trash root still readable for legacy workspaces.
pub const LEGACY_TRASH_ROOT_NAME: &str = "trash";
/// A git checkout internal directory that must never be treated as documents.
pub const GIT_ROOT_NAME: &str = ".git";

/// Forward-slash workspace-relative path inventory.
///
/// Rebuild/archive/manifest classifiers see [`crate::WorkspaceRelativePath`]
/// text, not `Path`s; these constants are the *same* segments as the
/// [`WorkspaceLayout`] fields so a text classifier can never diverge from the
/// path joiner. Every entry is intentionally a literal: this module is the
/// single location where `.lomo` spellings exist.
pub mod relative {
    /// `.lomo` tree root.
    pub const ROOT: &str = ".lomo";
    /// Prefix for any path inside `.lomo`.
    pub const ROOT_PREFIX: &str = ".lomo/";
    /// `.lomo/layout_head.rec` — durable layout-version head.
    pub const LAYOUT_HEAD: &str = ".lomo/layout_head.rec";
    /// `.lomo/history/` prefix (any version).
    pub const HISTORY_PREFIX: &str = ".lomo/history/";
    /// `.lomo/history/v1` — the pre-DAG history tree.
    pub const HISTORY_V1: &str = ".lomo/history/v1";
    /// `.lomo/history/v2` — the DAG history tree.
    pub const HISTORY_V2: &str = ".lomo/history/v2";
    /// `.lomo/history/v2/heads/` — per-memo head records.
    pub const HISTORY_V2_HEADS_PREFIX: &str = ".lomo/history/v2/heads/";
    /// `.lomo/history/v2/objects/` — content-addressed revision records.
    pub const HISTORY_V2_OBJECTS_PREFIX: &str = ".lomo/history/v2/objects/";
    /// `.lomo/history/v2/tombstones/` — permanent history-prune tombstones.
    pub const HISTORY_V2_TOMBSTONES_PREFIX: &str = ".lomo/history/v2/tombstones/";
    /// `.lomo/state/` prefix (any version).
    pub const STATE_PREFIX: &str = ".lomo/state/";
    /// `.lomo/state/v2` — the DAG state tree.
    pub const STATE_V2: &str = ".lomo/state/v2";
    /// `.lomo/state/v2/heads/`.
    pub const STATE_V2_HEADS_PREFIX: &str = ".lomo/state/v2/heads/";
    /// `.lomo/state/v2/objects/`.
    pub const STATE_V2_OBJECTS_PREFIX: &str = ".lomo/state/v2/objects/";
    /// `.lomo/trash/` prefix (recoverable trash records).
    pub const TRASH_PREFIX: &str = ".lomo/trash/";
    /// `.lomo/trash/v1` — the trash record tree.
    pub const TRASH_V1: &str = ".lomo/trash/v1";
    /// `.lomo/trash/v1/` — record files inside the trash tree.
    pub const TRASH_V1_PREFIX: &str = ".lomo/trash/v1/";
    /// `.lomo/purged/` prefix (permanent-delete tombstones).
    pub const PURGED_PREFIX: &str = ".lomo/purged/";
    /// `.lomo/purged/v1` — the purge record tree.
    pub const PURGED_V1: &str = ".lomo/purged/v1";
    /// `.lomo/purged/v1/` — record files inside the purge tree.
    pub const PURGED_V1_PREFIX: &str = ".lomo/purged/v1/";
    /// `.lomo/identity/v1/` — durable memo-identity bindings.
    pub const IDENTITY_V1_PREFIX: &str = ".lomo/identity/v1/";
    /// `.lomo/local` — device-local record tree (never synced/archived).
    pub const LOCAL: &str = ".lomo/local";
    /// `.lomo/local/` prefix.
    pub const LOCAL_PREFIX: &str = ".lomo/local/";
    /// `.lomo/operations/` — durable operation journal prefix.
    pub const OPERATIONS_PREFIX: &str = ".lomo/operations/";
    /// `.lomo/sync` — the sync control tree root; the sync carrier never reconciles as
    /// workspace content.
    pub const SYNC: &str = ".lomo/sync";
    /// `.lomo/sync/v1` — the sync control tree root.
    pub const SYNC_V1: &str = ".lomo/sync/v1";
    /// `.lomo/sync/` prefix.
    pub const SYNC_PREFIX: &str = ".lomo/sync/";
    /// `.lomo/migration-staging/` — internal migration scratch prefix.
    pub const MIGRATION_STAGING_PREFIX: &str = ".lomo/migration-staging/";
    /// `.lomo-sqlite` prefix — also matches `-shm`/`-wal` suffixed spellings,
    /// so classifier call sites keep `starts_with` semantics.
    pub const SQLITE_ROOT_PREFIX: &str = ".lomo-sqlite";
    /// `.lomo-remote/` prefix — foreign remote-control trees.
    pub const REMOTE_PREFIX: &str = ".lomo-remote/";
    /// `/.lomo-remote/` — a nested foreign control tree inside a synced subtree.
    pub const REMOTE_NESTED: &str = "/.lomo-remote/";
}

/// Active durable layout version for history/state trees.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LomoLayoutVersion {
    /// Pre-P5-01 mutable single-file state + `memoId-rN` history.
    V1,
    /// Content-addressed history/state revision DAG (sync-safe).
    V2,
}

/// The resolved `.lomo` inventory for one workspace root.
///
/// `for_workspace` reads the durable layout head so `history`/`state`/`layout`
/// always reflect the authoritative record version; version-free fields are
/// constant for every layout version. `for_workspace_with_layout` performs no
/// I/O and exists for tests and migration staging, where the caller dictates
/// the record tree version explicitly.
#[derive(Debug, Clone)]
pub struct WorkspaceLayout {
    /// The workspace root itself — parent of `.lomo` and the reserved sibling roots.
    pub workspace_root: PathBuf,
    /// `.lomo` durable tree root.
    pub root: PathBuf,
    /// `.lomo/manifest.v1` — workspace manifest record.
    pub manifest: PathBuf,
    /// `.lomo/layout_head.rec` — durable layout-version authority record.
    pub layout_head: PathBuf,
    /// `.lomo/operations/v1` — durable operation journal.
    pub operations: PathBuf,
    /// `.lomo/history/v{layout}` — history tree for the head-selected version.
    pub history: PathBuf,
    /// `.lomo/state/v{layout}` — state tree for the head-selected version.
    pub state: PathBuf,
    /// `.lomo/local/v1` — device-local records (never synced/archived).
    pub local: PathBuf,
    /// `.lomo/local/v1/generation.rec` — durable workspace generation fence.
    pub generation_record: PathBuf,
    /// `.lomo/identity/v1` — durable memo-identity bindings.
    pub identity: PathBuf,
    /// `.lomo/trash/v1` — recoverable trash records.
    pub trash: PathBuf,
    /// `.lomo/purged/v1` — permanent-delete tombstone records.
    pub purged: PathBuf,
    /// `.lomo/sync/v1` — sync control tree, fenced to the sync identity triple.
    pub sync: PathBuf,
    /// `.lomo/migration-staging` — internal migration scratch root.
    pub migration_staging: PathBuf,
    /// Record layout selected by the durable head (`V1` when absent).
    pub layout: LomoLayoutVersion,
}

impl WorkspaceLayout {
    /// Resolves layout paths for `workspace_root`, reading the migration head when present.
    ///
    /// A missing or unreadable head selects `V1`, matching the pre-migration workspace shape.
    #[must_use]
    pub fn for_workspace(workspace_root: &Path) -> Self {
        let layout = read_layout_version(workspace_root).unwrap_or(LomoLayoutVersion::V1);
        Self::for_workspace_with_layout(workspace_root, layout)
    }

    /// Resolves layout paths for an explicit layout version (tests / migration staging).
    ///
    /// Performs no head read: version-free fields are identical for either version.
    #[must_use]
    pub fn for_workspace_with_layout(workspace_root: &Path, layout: LomoLayoutVersion) -> Self {
        let root = workspace_root.join(LOMO_DIR_NAME);
        Self::at_lomo_root(workspace_root, root, layout)
    }

    /// A `.lomo` subtree staged at a scratch root (V1→V2 migration): `lomo_root`
    /// plays the role of `.lomo`, so record trees resolve under it verbatim while
    /// `workspace_root` still names the real workspace.
    #[must_use]
    pub(crate) fn for_staged_root(workspace_root: &Path, staging_root: &Path) -> Self {
        Self::at_lomo_root(
            workspace_root,
            staging_root.to_path_buf(),
            LomoLayoutVersion::V2,
        )
    }

    fn at_lomo_root(workspace_root: &Path, root: PathBuf, layout: LomoLayoutVersion) -> Self {
        let (history_seg, state_seg) = match layout {
            LomoLayoutVersion::V1 => ("v1", "v1"),
            LomoLayoutVersion::V2 => ("v2", "v2"),
        };
        let local = root.join("local").join("v1");
        Self {
            workspace_root: workspace_root.to_path_buf(),
            manifest: root.join("manifest.v1"),
            layout_head: root.join("layout_head.rec"),
            operations: root.join("operations").join("v1"),
            history: root.join("history").join(history_seg),
            state: root.join("state").join(state_seg),
            generation_record: local.join("generation.rec"),
            local,
            identity: root.join("identity").join("v1"),
            trash: root.join("trash").join("v1"),
            purged: root.join("purged").join("v1"),
            sync: root.join("sync").join("v1"),
            migration_staging: root.join("migration-staging"),
            root,
            layout,
        }
    }

    /// Ensures the durable record directories for the active layout exist.
    ///
    /// Only the codec trees (`root`, `operations`, `history`, `state`, `local`) are
    /// created eagerly — they have always been required. Internal subtrees
    /// (`identity`, `trash`, `purged`, `sync`, `migration-staging`) stay lazily
    /// created by their owning writers via `write_record_atomic` parent creation;
    /// adding them here would change the on-disk shape of workspaces that never
    /// exercised those features.
    ///
    /// # Errors
    ///
    /// Storage errors when directories cannot be created.
    pub fn ensure_layout(&self) -> Result<(), lomo_core::LomoError> {
        for dir in [
            &self.root,
            &self.operations,
            &self.history,
            &self.state,
            &self.local,
        ] {
            fs::create_dir_all(dir).map_err(|err| {
                storage(
                    "lomo_dir_create_failed",
                    &format!("cannot create {}: {err}", dir.display()),
                )
            })?;
        }
        Ok(())
    }
}

/// Names minted inside one session-private `state` directory.
///
/// The directory is device-private and never inside `.lomo`: Android resolves it
/// at `control_root/session/<workspace_identity>/state` (see [`SessionLayout`])
/// and the TUI at its XDG state dir. Every child name is canonical across hosts.
#[derive(Debug, Clone)]
pub struct SessionStateLayout {
    /// The session `state` directory itself.
    pub root: PathBuf,
    /// `device_id` — minted device identity record.
    pub device_id: PathBuf,
    /// `drafts/` — the session draft store.
    pub drafts: PathBuf,
    /// `capture/` — host capture journal (the TUI quick-capture lane, P-55).
    pub capture: PathBuf,
    /// `exchange/` — session-scoped platform-action exchange spool.
    pub exchange: PathBuf,
    /// `intents/` — durable intent journal.
    pub intents: PathBuf,
    /// `intent-payloads/` — large intent payload bodies kept beside the journal.
    pub intent_payloads: PathBuf,
    /// `baselines/` — editing baseline bytes for CAS reads.
    pub baselines: PathBuf,
}

impl SessionStateLayout {
    /// Resolves the canonical session-state children under `state_dir`.
    #[must_use]
    pub fn for_state_dir(state_dir: impl AsRef<Path>) -> Self {
        let root = state_dir.as_ref().to_path_buf();
        Self {
            device_id: root.join("device_id"),
            drafts: root.join("drafts"),
            capture: root.join("capture"),
            exchange: root.join("exchange"),
            intents: root.join("intents"),
            intent_payloads: root.join("intent-payloads"),
            baselines: root.join("baselines"),
            root,
        }
    }
}

/// The per-workspace session-private tree under a host control root:
/// `<control_root>/session/<workspace_identity>/{state,cache,runtime}`.
#[derive(Debug, Clone)]
pub struct SessionLayout {
    /// `<control_root>/session/<workspace_identity>`.
    pub root: PathBuf,
    /// `root/state` — the session state directory ([`SessionStateLayout::root`]).
    pub state: PathBuf,
    /// `root/cache` — disposable session cache.
    pub cache: PathBuf,
    /// `root/runtime` — runtime locks and scratch.
    pub runtime: PathBuf,
}

impl SessionLayout {
    /// Resolves the session tree for `workspace_identity` under `control_root`.
    #[must_use]
    pub fn for_control_root(control_root: &Path, workspace_identity: &str) -> Self {
        let root = control_root.join("session").join(workspace_identity);
        Self {
            state: root.join("state"),
            cache: root.join("cache"),
            runtime: root.join("runtime"),
            root,
        }
    }

    /// The canonical child names inside [`Self::state`].
    #[must_use]
    pub fn state_layout(&self) -> SessionStateLayout {
        SessionStateLayout::for_state_dir(&self.state)
    }
}

/// Writes the layout head to V2 only after a successful migration validation.
///
/// # Errors
///
/// Storage/encode failures. Never deletes user Markdown/media.
pub fn write_layout_head_v2(workspace_root: &Path) -> Result<(), lomo_core::LomoError> {
    let path = WorkspaceLayout::for_workspace(workspace_root).layout_head;
    let body = LayoutHeadBody {
        layout: LomoLayoutVersion::V2,
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "layout_head_encode_failed",
            &format!("cannot encode layout head: {err}"),
        )
    })?;
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::LayoutHead,
            record_id: "layout".into(),
            body_json,
        },
    )
}

fn read_layout_version(workspace_root: &Path) -> Result<LomoLayoutVersion, lomo_core::LomoError> {
    let path = WorkspaceLayout::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V1)
        .layout_head;
    if !path.exists() {
        return Ok(LomoLayoutVersion::V1);
    }
    let record = read_record(&path)?;
    if record.payload.kind != LomoRecordKind::LayoutHead {
        return Err(corruption(
            "layout_head_kind_mismatch",
            "layout head path does not contain a LayoutHead record",
        ));
    }
    let body: LayoutHeadBody = serde_json::from_str(&record.payload.body_json).map_err(|err| {
        corruption(
            "layout_head_payload_invalid",
            &format!("cannot decode layout head: {err}"),
        )
    })?;
    Ok(body.layout)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct LayoutHeadBody {
    layout: LomoLayoutVersion,
}
