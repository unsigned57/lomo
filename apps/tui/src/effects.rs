//! Typed boundary between presentation transitions and runtime IO.
//!
//! Every `Effect` is an identified issuance: it carries the `Req` its reply
//! will name, and the landing intent was registered in `AppModel.pending` at
//! issue time. Every receipt-style `RuntimeMessage` carries that `Req` back.
use lomo_application::PageCursor;
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

use crate::model::{FeedKind, FeedQuery, MemoCard, MemoVersion, Req, Screen, TaskRow, View};

/// The execution lane an effect runs on (I3).
///
/// Resource bounds are per-lane: `Query` answers interactive reads and
/// coalesces same-class queued work; `Mutate` serializes ordered writes;
/// `Maint` absorbs low-priority slow work (reconcile, trash sweep, image
/// decode) so it can never starve the lanes above it; `Io` holds external
/// process/clipboard spawns off every other path.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Lane {
    Query,
    Mutate,
    Maint,
    Io,
}

impl Lane {
    /// Every lane, in spawn order.
    pub const ALL: [Self; 4] = [Self::Query, Self::Mutate, Self::Maint, Self::Io];
    /// Stable name for thread names and diagnostics.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Query => "query",
            Self::Mutate => "mutate",
            Self::Maint => "maint",
            Self::Io => "io",
        }
    }
}

/// The supersede class for queued work.
///
/// A new admission carrying the same key drops the still-queued older job
/// before it executes. Effects whose results must not collapse — ordered
/// mutations, per-request decodes — have no key.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum MergeKey {
    /// A page request for one feed slot — only the newest query matters.
    Feed(FeedKind),
    /// A navigation to one screen.
    Screen(Screen),
    /// A body read of one memo (`ReadMemo` — an open or an in-place
    /// reader refresh).
    MemoRead(MemoId),
    /// A history listing for one memo — a distinct user intent from reading
    /// it: a queued `History{X}` is never swallowed by a same-memo read
    /// (09-F-02), while a second `History{X}` supersedes the first.
    MemoHistory(MemoId),
    /// A visibility hydration batch — the newest snapshot owns the pass.
    Bodies,
    /// The tag dictionary refresh.
    Tags,
    /// A projection reconcile cycle.
    Reconcile,
    /// A user-triggered full refresh.
    Refresh,
    /// The trash sweep — one at a time.
    EmptyTrash,
    /// The media orphan sweep — queued duplicates collapse.
    MediaSweep,
    /// A config.toml reload — only the newest file state matters.
    ConfigReload,
}

/// One page request for one feed slot (`kind + query`). The `req` is the feed's
/// `pending_page` while in flight; the reply can only land on the feed that
/// still awaits it.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FeedRequest {
    pub req: Req,
    pub kind: FeedKind,
    pub query: FeedQuery,
    pub intent: PageIntent,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PageIntent {
    Initial,
    Append(PageCursor),
    /// Re-read the loaded neighborhood around `anchors`. `known` is every
    /// loaded card id at issue time: the reply orders it against the live
    /// query to name — not infer — which loaded cards left the result set
    /// and where each survivor now ranks, and `known.len()` bounds the read
    /// window.
    Refresh {
        anchors: Vec<MemoId>,
        known: Vec<MemoId>,
    },
}

impl PageIntent {
    #[must_use]
    pub const fn cursor(&self) -> Option<&PageCursor> {
        match self {
            Self::Append(cursor) => Some(cursor),
            Self::Initial | Self::Refresh { .. } => None,
        }
    }

    /// An anchored refresh is covered when every anchor resolved — the window
    /// bound (not `known.len()`) decides how much of the old neighborhood joins.
    #[must_use]
    pub fn covers(&self, cards: &[MemoCard]) -> bool {
        match self {
            Self::Refresh { anchors, .. } => anchors
                .iter()
                .all(|id| cards.iter().any(|card| &card.id == id)),
            Self::Initial | Self::Append(_) => true,
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum EditTarget {
    Capture,
    Memo {
        id: MemoId,
        fingerprint: String,
        body: String,
    },
    /// The live `config.toml` — the draft is validated against the strict
    /// parser before it may replace the file.
    Config,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EditedMemo {
    pub operation_id: lomo_core::OperationId,
    pub id: MemoId,
    pub fingerprint: String,
    pub content: String,
    pub draft_path: std::path::PathBuf,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Effect {
    Query(FeedRequest),
    Navigate {
        req: Req,
        screen: Screen,
    },
    Bodies {
        req: Req,
        versions: Vec<MemoVersion>,
    },
    ReadMemo {
        req: Req,
        id: MemoId,
    },
    PersistDraft {
        req: Req,
        revision: u64,
        content: String,
    },
    CommitDraft {
        req: Req,
        revision: u64,
        content: String,
    },
    LoadImage {
        req: Req,
        request: crate::graphics::ImageRequest,
    },
    /// Foreground handoff: the editor completes in `submit`, so this request
    /// identity is minted but never registered in `pending` — the follow-up
    /// effects it produces register their own.
    Edit {
        req: Req,
        target: EditTarget,
    },
    CommitEdit {
        req: Req,
        edit: EditedMemo,
    },
    CaptureEdited {
        req: Req,
        revision: u64,
        content: String,
        draft_path: std::path::PathBuf,
    },
    ToggleTask {
        req: Req,
        task: TaskRow,
    },
    Pin {
        req: Req,
        id: MemoId,
        pinned: bool,
    },
    Delete {
        req: Req,
        id: MemoId,
        fingerprint: String,
    },
    DeleteForever {
        req: Req,
        id: MemoId,
    },
    EmptyTrash {
        req: Req,
    },
    /// Session-owned media orphan sweep (D-12). `drafts` carries the
    /// compose buffer snapshot; the lane additionally guards every editor
    /// draft file under `drafts_dir` so a sweep can never reclaim media an
    /// unsaved draft references.
    MediaSweep {
        req: Req,
        drafts: Vec<lomo_application::GuardedDraftBody>,
    },
    Restore {
        req: Req,
        id: MemoId,
    },
    RestoreRevision {
        req: Req,
        id: MemoId,
        revision: u64,
    },
    History {
        req: Req,
        id: MemoId,
    },
    ImportClipboard {
        req: Req,
    },
    OpenAttachment {
        req: Req,
        path: RelativeWorkspacePath,
    },
    Tags {
        req: Req,
    },
    Date {
        req: Req,
        text: String,
    },
    /// Reconcile the projection with observed filesystem changes. Issued once
    /// per drained watcher batch — never a substitute for watching.
    ///
    /// `observed` is the watcher-attested changed-path set: `Some` scopes the
    /// reconcile to those paths (`reconcile_observed_paths`), `None` means the
    /// coverage is unattested (rescan, dead-watcher fallback) and the full scan
    /// remains the truth rebuilder.
    Reconcile {
        req: Req,
        observed: Option<Vec<RelativeWorkspacePath>>,
    },
    /// The deferred workspace mount (I5): opens the runtime and prepares the
    /// model on a lane while the UI draws the `Loading` shell. The lane loop
    /// intercepts it — it never reaches `ops::execute` because there is no
    /// runtime yet to execute against.
    ///
    /// `spec.launch` decides the semantics: `Ready` opens; `Mint` writes the
    /// confirmed config and workspace first — the first durable side effect.
    Bootstrap {
        req: Req,
        spec: crate::ops::BootstrapSpec,
    },
    /// The wizard's confirmation: the host rewrites this into a
    /// `Bootstrap{launch: Mint}` before dispatch, so the lane — never the UI
    /// thread — performs the minting writes. It exists as a distinct variant
    /// only so the pending intent (`Bootstrap`) and refusal path are typed.
    SetupConfirmed {
        req: Req,
        file: std::path::PathBuf,
        config: crate::config::AppConfig,
    },
    /// Re-read and strictly re-validate `config.toml`, then apply what the
    /// registry marks hot. External edits (watcher) and F5 share this path.
    ReloadConfig {
        req: Req,
    },
    /// One settings-screen field edit, already validated by
    /// `SettingsField::parse_edit`: writes the registry-rendered file, then
    /// applies the new value under the same rules as an external reload.
    SaveSetting {
        req: Req,
        field: crate::config::SettingsField,
        value: crate::config::FieldValue,
    },
    Refresh {
        req: Req,
    },
    Quit {
        req: Req,
    },
}
/// One bootstrap phase observation — progress evidence for the `Loading` view.
/// Producer-initiated: it carries no request identity and never claims one.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootPhase {
    /// `open_runtime`: capability bind, migration and workspace verification.
    Workspace,
    /// `bootstrap_model`: first feed page, tags, drafts and reminder plan.
    Model,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RuntimeMessage {
    QuitReady {
        req: Req,
    },
    Image {
        req: Req,
        request: crate::graphics::ImageRequest,
        /// Arc'd because a stateful protocol cannot be cloned for message
        /// equality — the prepared payload moves straight into the registry.
        result: Result<std::sync::Arc<crate::graphics::TerminalImage>, String>,
    },
    View {
        req: Req,
        view: Box<View>,
    },
    Page {
        req: Req,
        append: bool,
        cards: Vec<MemoCard>,
        next: Option<PageCursor>,
        /// For a non-append reply, the live-order id sequence over the union
        /// of the reply and the still-live loaded set: every loaded id the
        /// query kept, ordered by where it now sorts relative to the reply
        /// window (reply members supply their fresh cards; loaded members
        /// keep theirs; a loaded id absent from `order` left the result
        /// set — the only membership evidence the merge may act on).
        /// `Append` replies leave it empty — their merge appends unseen ids
        /// under cursor continuation instead.
        order: Vec<MemoId>,
        total: Option<u64>,
    },
    Bodies {
        req: Req,
        bodies: Vec<BodyReply>,
    },
    ReadMemo {
        req: Req,
        memo: Box<MemoCard>,
    },
    /// A `ReadMemo` whose lookup resolved to nothing: the memo no longer
    /// exists. A typed outcome, not an error — the landing intent decides
    /// whether a miss means "pop the open placeholder" or "the open reader's
    /// memo vanished" (I9).
    MemoGone {
        req: Req,
        id: MemoId,
    },
    DraftStored {
        req: Req,
        revision: u64,
    },
    Saved {
        req: Req,
        revision: u64,
        id: MemoId,
    },
    Tags {
        req: Req,
        tags: Vec<String>,
    },
    Message {
        req: Req,
        title: String,
        lines: Vec<String>,
    },
    History {
        req: Req,
        id: MemoId,
        revisions: Vec<crate::model::RevisionRow>,
    },
    Date {
        req: Req,
        from: i64,
        until: i64,
        label: String,
    },
    /// The deferred workspace mount finished; the prepared model replaces the
    /// `Loading` shell shown during startup.
    RuntimeReady {
        req: Req,
        model: Box<crate::model::AppModel>,
    },
    /// The config watcher observed a `config.toml` change — the Maint lane
    /// re-reads it through the same strict parser. Producer-initiated
    /// observation — not a pending receipt.
    ConfigChanged,
    /// The config watcher is dead or could not start; manual reload via the
    /// Settings save/F5 path still works. Producer-initiated observation.
    ConfigWatchUnavailable {
        diagnostic: String,
    },
    /// A `ReloadConfig`/`SaveSetting` reply: the post-reload config snapshot
    /// plus which fields applied live and which await a restart.
    ConfigApplied {
        req: Req,
        config: Box<crate::config::AppConfig>,
        applied: Vec<crate::config::SettingsField>,
        restart_pending: Vec<crate::config::SettingsField>,
    },
    /// The watcher drained a batch of filesystem events; reconcile once.
    /// `observed` carries the attested workspace-relative paths — `None` means
    /// the watcher could only prove that *something* changed (rescan,
    /// invalidation, unmappable path), which forces the full scan.
    /// Producer-initiated observation — not a pending receipt.
    FsChanged {
        observed: Option<Vec<RelativeWorkspacePath>>,
    },
    /// Bootstrap progress — the `Loading` view's status line shows the phase.
    /// Producer-initiated observation — not a pending receipt.
    BootPhase {
        phase: BootPhase,
    },
    Reconciled {
        req: Req,
        changed: bool,
    },
    WatcherReady,
    WatcherUnavailable {
        diagnostic: String,
    },
    /// A spawned external player exited; success/failure is carried, not logged.
    /// Producer-initiated observation — not a pending receipt.
    PlayerFinished {
        success: bool,
        diagnostic: Option<String>,
    },
    /// The terminal capability probe answered — the verdict installs into
    /// `model.graphics` (D-03). Producer-initiated observation — not a
    /// pending receipt.
    GraphicsDetected {
        verdict: crate::graphics::GraphicsVerdict,
    },
    /// The media orphan sweep completed; counters keep housekeeping visible
    /// without a modal. A `Maintenance` receipt.
    MediaSweepDone {
        req: Req,
        moved: u64,
        purged: u64,
        failures: u64,
    },
    /// A supervised execution lane exited unexpectedly while the session was
    /// live. Producer-initiated observation at bootstrap severity: the lane's
    /// death is surfaced, never discovered by the next failed send (F-06).
    WorkerDied {
        lane: Lane,
        diagnostic: String,
    },
    /// A store mutation committed — the outcome names what the store did, so
    /// the status line can say "Pinned" instead of a generic "Saved" (I2).
    Mutated {
        req: Req,
        outcome: MutationOutcome,
    },
    Changed {
        req: Req,
        status: String,
    },
    /// The effect's work failed. `req` names the request; the pending intent
    /// that issued it decides where the failure lands (feed slot, placeholder,
    /// date dialog, save state) — never a positional `target`.
    Failed {
        req: Req,
        diagnostic: String,
    },
}
/// The outcome a mutation receipt names — a typed verdict the status line
/// renders, so "pin the memo" never degrades to a generic "Saved" (I2).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MutationOutcome {
    Pinned,
    Unpinned,
    Trashed,
    Restored,
    DeletedForever,
    /// `done` is the state the task was toggled INTO.
    TaskToggled {
        done: bool,
    },
    TrashEmptied {
        removed: u64,
    },
    RevisionRestored {
        revision: u64,
    },
    ClipboardImported,
}

impl MutationOutcome {
    /// The localized receipt line the status bar prints for this outcome.
    #[must_use]
    pub fn status(&self) -> String {
        let s = crate::i18n::UiStrings::detect();
        match self {
            Self::Pinned => s.text("Pinned", "已置顶").to_owned(),
            Self::Unpinned => s.text("Unpinned", "已取消置顶").to_owned(),
            Self::Trashed => s.text("Moved to trash", "已移入回收站").to_owned(),
            Self::Restored => s.text("Restored", "已还原").to_owned(),
            Self::DeletedForever => s.text("Deleted permanently", "已永久删除").to_owned(),
            Self::TaskToggled { done: true } => s.text("Task completed", "待办已完成").to_owned(),
            Self::TaskToggled { done: false } => s.text("Task reopened", "待办已重开").to_owned(),
            Self::TrashEmptied { removed } => format!(
                "{} {removed}",
                s.text("Trash emptied · removed", "回收站已清空 · 已删除")
            ),
            Self::RevisionRestored { revision } => {
                format!("{} r{revision}", s.text("Restored revision", "已恢复版本"))
            }
            Self::ClipboardImported => s.text("Image imported", "已导入剪贴板图片").to_owned(),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LoadedBody {
    pub body: std::sync::Arc<crate::content::MemoBody>,
    pub attachments: Vec<RelativeWorkspacePath>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BodyReply {
    pub version: MemoVersion,
    pub result: Result<LoadedBody, String>,
}

impl Effect {
    /// The request identity this effect was issued under — the same `Req` its
    /// reply carries back.
    #[must_use]
    pub const fn req(&self) -> Req {
        match self {
            Self::Query(request) => request.req,
            Self::Navigate { req, .. }
            | Self::Bodies { req, .. }
            | Self::ReadMemo { req, .. }
            | Self::PersistDraft { req, .. }
            | Self::CommitDraft { req, .. }
            | Self::LoadImage { req, .. }
            | Self::Edit { req, .. }
            | Self::CommitEdit { req, .. }
            | Self::CaptureEdited { req, .. }
            | Self::ToggleTask { req, .. }
            | Self::Pin { req, .. }
            | Self::Delete { req, .. }
            | Self::DeleteForever { req, .. }
            | Self::EmptyTrash { req }
            | Self::MediaSweep { req, .. }
            | Self::Restore { req, .. }
            | Self::RestoreRevision { req, .. }
            | Self::History { req, .. }
            | Self::ImportClipboard { req }
            | Self::OpenAttachment { req, .. }
            | Self::Tags { req }
            | Self::Date { req, .. }
            | Self::Reconcile { req, .. }
            | Self::Bootstrap { req, .. }
            | Self::SetupConfirmed { req, .. }
            | Self::ReloadConfig { req }
            | Self::SaveSetting { req, .. }
            | Self::Refresh { req }
            | Self::Quit { req } => *req,
        }
    }

    /// The lane this effect executes on — the resource bound that keeps slow
    /// work off interactive paths (I3).
    ///
    /// `Edit` is a foreground terminal handoff intercepted by the host before
    /// lane dispatch; if a stray `Edit` ever reaches a lane it fails loudly
    /// inside `ops::execute`, and its refusal lane is irrelevant.
    #[must_use]
    pub const fn lane(&self) -> Lane {
        match self {
            Self::Query(_)
            | Self::Navigate { .. }
            | Self::Bodies { .. }
            | Self::ReadMemo { .. }
            | Self::History { .. }
            | Self::Tags { .. }
            | Self::Date { .. } => Lane::Query,
            Self::PersistDraft { .. }
            | Self::CommitDraft { .. }
            | Self::CommitEdit { .. }
            | Self::CaptureEdited { .. }
            | Self::ToggleTask { .. }
            | Self::Pin { .. }
            | Self::Delete { .. }
            | Self::DeleteForever { .. }
            | Self::Restore { .. }
            | Self::RestoreRevision { .. }
            | Self::SaveSetting { .. }
            | Self::Quit { .. } => Lane::Mutate,
            Self::LoadImage { .. }
            | Self::EmptyTrash { .. }
            | Self::MediaSweep { .. }
            | Self::Reconcile { .. }
            | Self::Bootstrap { .. }
            | Self::SetupConfirmed { .. }
            | Self::ReloadConfig { .. }
            | Self::Refresh { .. } => Lane::Maint,
            Self::Edit { .. } | Self::ImportClipboard { .. } | Self::OpenAttachment { .. } => {
                Lane::Io
            }
        }
    }

    /// The supersede class this queued job belongs to, if any. A later
    /// admission with the same key drops this job before it executes — the
    /// newest same-class request always wins. Ordered work returns `None`.
    #[must_use]
    pub fn merge_key(&self) -> Option<MergeKey> {
        match self {
            Self::Query(request) => Some(MergeKey::Feed(request.kind)),
            Self::Navigate { screen, .. } => Some(MergeKey::Screen(*screen)),
            Self::Bodies { .. } => Some(MergeKey::Bodies),
            Self::ReadMemo { id, .. } => Some(MergeKey::MemoRead(id.clone())),
            Self::History { id, .. } => Some(MergeKey::MemoHistory(id.clone())),
            Self::Tags { .. } => Some(MergeKey::Tags),
            Self::Reconcile { .. } => Some(MergeKey::Reconcile),
            Self::Refresh { .. } => Some(MergeKey::Refresh),
            Self::EmptyTrash { .. } => Some(MergeKey::EmptyTrash),
            Self::MediaSweep { .. } => Some(MergeKey::MediaSweep),
            Self::ReloadConfig { .. } => Some(MergeKey::ConfigReload),
            Self::PersistDraft { .. }
            | Self::CommitDraft { .. }
            | Self::LoadImage { .. }
            | Self::Edit { .. }
            | Self::CommitEdit { .. }
            | Self::CaptureEdited { .. }
            | Self::ToggleTask { .. }
            | Self::Pin { .. }
            | Self::Delete { .. }
            | Self::DeleteForever { .. }
            | Self::Restore { .. }
            | Self::RestoreRevision { .. }
            | Self::ImportClipboard { .. }
            | Self::OpenAttachment { .. }
            | Self::Date { .. }
            | Self::Bootstrap { .. }
            | Self::SetupConfirmed { .. }
            | Self::SaveSetting { .. }
            | Self::Quit { .. } => None,
        }
    }
}

impl RuntimeMessage {
    /// The request this receipt answers, or `None` for producer-initiated
    /// observations (`FsChanged`, watcher/player signals) which never carry
    /// pending intent and are applied on the observation channel.
    #[must_use]
    pub const fn req(&self) -> Option<Req> {
        match self {
            Self::QuitReady { req }
            | Self::Image { req, .. }
            | Self::View { req, .. }
            | Self::Page { req, .. }
            | Self::Bodies { req, .. }
            | Self::ReadMemo { req, .. }
            | Self::MemoGone { req, .. }
            | Self::DraftStored { req, .. }
            | Self::Saved { req, .. }
            | Self::Tags { req, .. }
            | Self::Message { req, .. }
            | Self::History { req, .. }
            | Self::Date { req, .. }
            | Self::RuntimeReady { req, .. }
            | Self::Reconciled { req, .. }
            | Self::MediaSweepDone { req, .. }
            | Self::ConfigApplied { req, .. }
            | Self::Mutated { req, .. }
            | Self::Changed { req, .. }
            | Self::Failed { req, .. } => Some(*req),
            Self::FsChanged { .. }
            | Self::ConfigChanged
            | Self::ConfigWatchUnavailable { .. }
            | Self::BootPhase { .. }
            | Self::WatcherReady
            | Self::WatcherUnavailable { .. }
            | Self::PlayerFinished { .. }
            | Self::GraphicsDetected { .. }
            | Self::WorkerDied { .. } => None,
        }
    }
}
