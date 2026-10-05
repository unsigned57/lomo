//! Presentation state: one active view and one input owner.
use std::sync::Arc;

use lomo_application::{MemoFilters, PageCursor, SearchMode};
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

use crate::input::TextBuffer;
use crate::settings::SettingsView;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Screen {
    Timeline,
    Tasks,
    Review,
    Statistics,
    Attachments,
    Trash,
    Settings,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FeedKind {
    Timeline,
    Review,
    Trash,
}

impl FeedKind {
    #[must_use]
    pub const fn screen(self) -> Screen {
        match self {
            Self::Timeline => Screen::Timeline,
            Self::Review => Screen::Review,
            Self::Trash => Screen::Trash,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TagConstraint {
    pub name: String,
    pub scope: lomo_application::TagSelectionMode,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeedQuery {
    pub text: String,
    pub mode: SearchMode,
    pub filters: MemoFilters,
    pub date_label: Option<String>,
}
impl Default for FeedQuery {
    fn default() -> Self {
        Self {
            text: String::new(),
            mode: SearchMode::Fulltext,
            filters: MemoFilters::default(),
            date_label: None,
        }
    }
}

impl FeedQuery {
    #[must_use]
    pub fn is_filtered(&self) -> bool {
        !self.text.trim().is_empty() || self.filters != MemoFilters::default()
    }
}

/// Identity of one issued `Effect`.
///
/// `AppModel::request` mints it and registers the request's landing intent in
/// `AppModel::pending` in the same step; every receipt-style `RuntimeMessage`
/// names the `Req` it answers, and a reply whose `Req` is no longer registered
/// takes the degradation path instead of touching whatever state happens to be
/// current.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct Req(pub u64);

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BodyState {
    Pending,
    /// Awaiting the `Bodies` reply of this exact request. A reply claims the
    /// request out of `pending`, so the marker self-heals: a request that was
    /// superseded or never answered reads as loadable again.
    Loading {
        req: Req,
    },
    Ready(Arc<crate::content::MemoBody>),
    Failed(String),
}

impl BodyState {
    /// Whether this body still needs a fetch. `Loading` is loadable again once
    /// its request left the live registry — the reply either landed or the
    /// request was cancelled, and neither outcome is signaled in-place.
    #[must_use]
    pub fn needs_load(&self, pending: &Pending) -> bool {
        match self {
            Self::Pending => true,
            Self::Loading { req } => !pending.contains(*req),
            Self::Ready(_) | Self::Failed(_) => false,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoCard {
    pub id: MemoId,
    pub date: String,
    pub time: String,
    pub summary: String,
    pub body: BodyState,
    pub tags: Vec<String>,
    pub attachments: Vec<RelativeWorkspacePath>,
    pub fingerprint: String,
    pub revision: u64,
    pub pinned: bool,
    pub trashed: bool,
    pub excerpt: Option<lomo_application::search_excerpt::SearchExcerpt>,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct MemoVersion {
    pub id: MemoId,
    pub revision: u64,
    pub fingerprint: String,
}

impl MemoCard {
    #[must_use]
    pub fn version(&self) -> MemoVersion {
        MemoVersion {
            id: self.id.clone(),
            revision: self.revision,
            fingerprint: self.fingerprint.clone(),
        }
    }
}

/// How loud a piece of user feedback is — the severity is part of the
/// notice's identity, not something the renderer guesses from wording.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Severity {
    /// One-shot information: a success receipt or a neutral observation.
    Info,
    /// Something failed or needs attention but the session is intact.
    Warn,
    /// The session degraded: a lane died, the watcher is gone, work was lost.
    Error,
}

/// Where a notice is presented — the channel follows the semantic class of
/// the feedback (I9), never the convenience of the producer.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Surface {
    /// The status line: a toast that newer feedback may replace.
    Toast,
    /// A persistent header mark in one class — it survives unrelated input
    /// until the user acknowledges it (top-level Esc) or a same-class
    /// success retires it.
    Badge(BadgeClass),
    /// A dialog that owns the focus — only for content that must be read.
    Modal,
}

/// Which failure family a badge belongs to. The class is what "same-class
/// success clears it" keys on: a watcher outage is retired by a watcher
/// recovery, never by an unrelated save.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BadgeClass {
    /// The registered notice was raised while another input owned the focus —
    /// cleared when the user reads it (`:` → Last notification).
    Notice,
    /// Workspace observation is degraded — cleared by `WatcherReady`.
    Watch,
    /// Background maintenance (reconcile, sweep, config reload) failed —
    /// cleared by the next successful same-class landing.
    Sync,
    /// A draft write failed — cleared by the next `DraftStored`/`Saved`.
    Draft,
    /// A user action (pin/delete/restore/import/quit) failed — cleared by the
    /// next successful mutation.
    Action,
    /// The external attachment/player path failed — cleared by the next
    /// successful open or clean player exit.
    Player,
    /// A supervised lane died — terminal for the session; only the explicit
    /// acknowledgement retires the mark (the `Failed` view names it too).
    Worker,
}

/// One persistent header mark: class identity plus the text it stands for.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Badge {
    pub class: BadgeClass,
    pub severity: Severity,
    pub text: String,
}

/// Classified user feedback: severity, surface and content together decide
/// how it is presented and where it is remembered.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Notice {
    pub severity: Severity,
    pub surface: Surface,
    pub title: String,
    pub lines: Vec<String>,
}

impl Notice {
    /// A status-line toast — one-shot information the next toast may replace.
    #[must_use]
    pub const fn toast(severity: Severity, title: String, lines: Vec<String>) -> Self {
        Self {
            severity,
            surface: Surface::Toast,
            title,
            lines,
        }
    }
    /// A persistent badge — failure evidence that survives unrelated input.
    #[must_use]
    pub const fn badge(
        severity: Severity,
        class: BadgeClass,
        title: String,
        lines: Vec<String>,
    ) -> Self {
        Self {
            severity,
            surface: Surface::Badge(class),
            title,
            lines,
        }
    }
    /// A focus-owning dialog — only content that must be read earns a modal.
    #[must_use]
    pub const fn modal(severity: Severity, title: String, lines: Vec<String>) -> Self {
        Self {
            severity,
            surface: Surface::Modal,
            title,
            lines,
        }
    }
    /// The one-line form a toast or badge shows — title plus every detail line.
    #[must_use]
    pub fn summary(&self) -> String {
        if self.lines.is_empty() {
            self.title.clone()
        } else {
            format!("{}: {}", self.title, self.lines.join(" · "))
        }
    }
}

/// A visual row is anchored to a logical line and grapheme, independent of width.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Ord, PartialOrd)]
pub struct TextAnchor {
    pub line: usize,
    pub grapheme: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
/// Card rows in reading order; `Time` is the top of a card.
pub enum CardPosition {
    Time,
    Body(TextAnchor),
    Footer(usize),
    Gap,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoAnchor {
    pub id: MemoId,
    pub position: CardPosition,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LoadStatus {
    Ready,
    Stale,
    Loading,
    Failed(String),
}

/// What one in-flight request intends its reply to land on.
///
/// `AppModel.pending` is the single authority on async intent: the pending
/// kind decides where a reply may mutate state and which fallback a failure
/// takes.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PendingKind {
    /// A page reply lands on the feed whose `pending_page` names this request —
    /// slot identity (`kind + query` at issue time), not a shared epoch.
    FeedPage,
    /// Install the replied view on the `View::Loading` placeholder this request pushed.
    Navigate,
    /// Install the replied memo as a reader on the placeholder this request pushed.
    OpenMemo,
    /// Re-read memo `id`'s open reader in place — the reply and its failure
    /// land only while that exact memo is still the reader (09-F-04).
    RefreshReader { id: MemoId },
    /// Refresh the current non-feed view in place; lands only while that
    /// screen is still current.
    RefreshView { screen: Screen },
    /// Hydrate bodies by exact `MemoVersion` identity.
    Bodies,
    /// Durable write of the capture draft at this revision — auto-persist and
    /// external-editor saves share this intent.
    DraftPersist { revision: u64 },
    /// Commit of the capture draft at this revision; the `Submitting` marker
    /// and the pending entry retire together.
    DraftCommit { revision: u64 },
    /// Open the history picker for this memo; parks while another input owns
    /// the focus and is delivered once the model returns to `Browse`.
    History { id: MemoId },
    /// Refresh the tag dictionary. Issuing a new one supersedes older pending
    /// tag refreshes — latest dictionary wins.
    Tags,
    /// Resolve a date filter. `dialog` replies land on the open
    /// `InputMode::Date` that still awaits this request; preset replies apply
    /// the filter directly without opening the dialog.
    Date { dialog: bool },
    /// A user mutation whose reply is `Changed`/`Message` evidence.
    Mutation,
    /// A projection reconcile/refresh cycle (watcher batch, focus regain, F5).
    Maintenance,
    /// A config.toml re-read + hot apply (`ReloadConfig`/`SaveSetting`); the
    /// reply is `ConfigApplied` naming which fields landed live and which
    /// still await a restart.
    ConfigReload,
    /// An external attachment open; the reply is a user notice.
    Attachment,
    /// A reader image decode; lands on the matching `model.images` request.
    Image,
    /// Install the prepared bootstrap model — the startup boundary I5 loads
    /// through the same request identity.
    Bootstrap,
    /// The quit handshake.
    Quit,
}

/// A receipt that needs the input focus while another input mode is live.
///
/// It waits parked; `AppModel::drain_parked` delivers it once the model
/// returns to `Browse`. Replies are never silently dropped because a dialog
/// was open.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ParkedReply {
    History {
        id: MemoId,
        revisions: Vec<RevisionRow>,
    },
}

/// Cancellation flag a worker lane checks before and during execution.
///
/// The flag lives in the `Pending` registry next to its intent: `register`
/// mints it live, `cancel`/`cancel_matching` trip it and leave the tripped
/// flag behind as the revocation tombstone `Scheduler::submit` reads, and
/// `claim` removes it quietly (the producing job already ran).
/// A token outside the registry — `live()` — never trips, which is exactly
/// what an untracked job means.
#[derive(Clone, Debug, Default)]
pub struct CancelToken(Arc<std::sync::atomic::AtomicBool>);
impl CancelToken {
    /// A token not wired to any pending intent: it can never fire.
    #[must_use]
    pub fn live() -> Self {
        Self::default()
    }
    /// Trip the flag — workers must drop this job before or during execution.
    pub fn cancel(&self) {
        self.0.store(true, std::sync::atomic::Ordering::Release);
    }
    /// Whether the job carrying this token has been revoked.
    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.0.load(std::sync::atomic::Ordering::Acquire)
    }
}
impl PartialEq for CancelToken {
    /// Tokens compare by cancellation state — they are execution plumbing,
    /// not model identity; two freshly minted live flags are equal.
    fn eq(&self, other: &Self) -> bool {
        self.is_cancelled() == other.is_cancelled()
    }
}
impl Eq for CancelToken {}

/// Live-request registry.
///
/// The first thing `apply_message` does with a receipt is `claim` its `Req`;
/// `cancel` revokes a still-in-flight intent (supersede, discard, abandon).
/// I3 hangs worker cancellation off the same calls: every registered request
/// owns a `CancelToken` the lanes observe, so revoking an intent drops the
/// queued job before it executes rather than discarding its result after.
///
/// Revocation is a durable fact, not just a removed row: `cancel` leaves the
/// tripped token in `tokens` as the tombstone, so a stale submission carrying
/// the dead `Req` later (the deferred-search window) is dropped at admission
/// instead of being re-minted live (09-F-03). `Req` is session-monotone — a
/// tombstone can never be recycled, only outlived: `register` overwrites it
/// on re-mint and `claim` clears it when the receipt lands. Its bound is the
/// session's revocation count, one flag per revoked request.
#[derive(Clone, Debug, Default, Eq)]
pub struct Pending {
    intents: std::collections::BTreeMap<Req, PendingKind>,
    tokens: std::collections::BTreeMap<Req, CancelToken>,
}
impl PartialEq for Pending {
    /// Registry identity is the live intent set — cancellation tokens,
    /// including revocation tombstones, are execution plumbing like
    /// `DerivedCache`, not model state.
    fn eq(&self, other: &Self) -> bool {
        self.intents == other.intents
    }
}
impl Pending {
    pub fn register(&mut self, req: Req, kind: PendingKind) {
        self.intents.insert(req, kind);
        self.tokens.insert(req, CancelToken::live());
    }
    /// Consume the intent a reply names; `None` means the request is already
    /// dead and the reply must degrade instead of landing.
    pub fn claim(&mut self, req: Req) -> Option<PendingKind> {
        self.tokens.remove(&req);
        self.intents.remove(&req)
    }
    /// Revoke a live intent before its reply arrives (superseded, discarded or
    /// abandoned) — the receipt will degrade rather than touch stale state,
    /// and the flag the lane holds trips so a queued job never executes.
    /// The tripped flag stays as the tombstone `submit` reads (see the type
    /// docs): a revoked `Req` can never be re-admitted as live work.
    pub fn cancel(&mut self, req: Req) -> Option<PendingKind> {
        if let Some(token) = self.tokens.get(&req) {
            token.cancel();
        }
        self.intents.remove(&req)
    }
    /// Revoke every intent matching `kind` — e.g. a newer tag refresh
    /// supersedes the pending dictionary load wholesale.
    pub fn cancel_matching(&mut self, kind: fn(&PendingKind) -> bool) {
        let dead: Vec<Req> = self
            .intents
            .iter()
            .filter(|(_, k)| kind(k))
            .map(|(req, _)| *req)
            .collect();
        for req in dead {
            drop(self.cancel(req));
        }
    }
    /// The execution flag a lane attaches to this request's job. `Some` with
    /// the flag already tripped is the revocation tombstone — `submit` drops
    /// the admission; `None` means the request is untracked — the lane runs
    /// it under a flag that can never fire.
    #[must_use]
    pub fn token(&self, req: Req) -> Option<CancelToken> {
        self.tokens.get(&req).cloned()
    }
    /// A still-registered intent means the request is in flight — the owner of
    /// a wedged marker uses this to tell a live request from a dead one.
    #[must_use]
    pub fn contains(&self, req: Req) -> bool {
        self.intents.contains_key(&req)
    }
    /// Peek without consuming — the host checks draft intent before the reply
    /// is claimed so a failed persist can reopen the closing path.
    #[must_use]
    pub fn get(&self, req: Req) -> Option<&PendingKind> {
        self.intents.get(&req)
    }
}

/// Derived view state: a memoized projection of its inputs. Cloning yields a
/// fresh (empty) cache — the clone re-derives from the same inputs — and
/// equality ignores it, because two equal inputs produce equal caches.
pub(crate) struct DerivedCache<T>(std::cell::RefCell<T>);
impl<T> DerivedCache<T> {
    pub(crate) const fn new(value: T) -> Self {
        Self(std::cell::RefCell::new(value))
    }
    pub(crate) fn borrow(&self) -> std::cell::Ref<'_, T> {
        self.0.borrow()
    }
    pub(crate) fn borrow_mut(&self) -> std::cell::RefMut<'_, T> {
        self.0.borrow_mut()
    }
}
impl<T: Default> Default for DerivedCache<T> {
    fn default() -> Self {
        Self::new(T::default())
    }
}
impl<T: Default> Clone for DerivedCache<T> {
    /// Derived state never deep-clones; the destination re-derives on demand.
    fn clone(&self) -> Self {
        Self::default()
    }
}
impl<T> PartialEq for DerivedCache<T> {
    fn eq(&self, _other: &Self) -> bool {
        true
    }
}
impl<T> Eq for DerivedCache<T> {}
impl<T> std::fmt::Debug for DerivedCache<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DerivedCache")
    }
}

/// The parked-body store's capacity — distinct from the resident window: the
/// working set stays `Ready` in place, and only evictions beyond the resident
/// bound park here.
const BODY_CACHE_CAP: usize = 512;

/// One evicted parse: the shared body plus its attachment list.
struct ParkedBody {
    body: Arc<crate::content::MemoBody>,
    attachments: Vec<RelativeWorkspacePath>,
}

/// Bodies evicted from the resident window park here keyed on the exact
/// `MemoVersion` (id + revision + fingerprint): revisiting a card restores its
/// parse without another store read, and a changed document misses the key by
/// construction — a parked body can never answer for a different version.
///
/// A parse has exactly one owner: `take` moves it back onto the card, so the
/// live entry count is bounded by the number of *parked* cards, not by the
/// length of browsing history. `order` is the recency queue (back = most
/// recent park); eviction pops the front. Inserts and evicts are O(1); a
/// `take`/`re-park` key removal is O(cap) — bounded by construction.
#[derive(Default)]
pub(crate) struct BodyCache {
    map: std::collections::HashMap<MemoVersion, ParkedBody>,
    order: std::collections::VecDeque<MemoVersion>,
}

impl BodyCache {
    /// Removes `version` from the recency queue.
    fn dequeue(&mut self, version: &MemoVersion) {
        if let Some(position) = self.order.iter().position(|key| key == version) {
            self.order.remove(position);
        }
    }

    /// Hands the parked parse for this exact version back to its card — the
    /// entry leaves the cache, keeping live entries == parked cards.
    pub(crate) fn take(
        &mut self,
        version: &MemoVersion,
    ) -> Option<(Arc<crate::content::MemoBody>, Vec<RelativeWorkspacePath>)> {
        let entry = self.map.remove(version)?;
        self.dequeue(version);
        Some((entry.body, entry.attachments))
    }

    /// Parks one evicted body; at capacity the least-recently-parked version
    /// leaves first. Both maps stay in lockstep — a key exists in both or
    /// neither.
    pub(crate) fn park(
        &mut self,
        version: MemoVersion,
        body: Arc<crate::content::MemoBody>,
        attachments: Vec<RelativeWorkspacePath>,
    ) {
        self.dequeue(&version);
        while self.map.len() >= BODY_CACHE_CAP {
            let Some(oldest) = self.order.pop_front() else {
                break;
            };
            self.map.remove(&oldest);
        }
        self.order.push_back(version.clone());
        self.map.insert(version, ParkedBody { body, attachments });
    }
}

/// The tag dictionary's memoized flat list: the version that produced it plus
/// the shared name rows (`Arc<[Arc<str>]>` — one allocation serves every
/// picker frame).
type TagDictionary = Option<(u64, Arc<[Arc<str>]>)>;

/// The unfiltered reading context a filter suspends.
///
/// Holds query, selection and anchor — a position, not a memo snapshot. The
/// filtered cards stay on screen until the restore re-queries a bounded
/// window around the anchor.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UnfilteredContext {
    pub query: FeedQuery,
    pub selected: Option<MemoId>,
    pub anchor: Option<MemoAnchor>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FeedState {
    pub kind: FeedKind,
    pub query: FeedQuery,
    /// The live page request this feed is awaiting — `Some` exactly while
    /// `load` is `Loading`. A `Page`/`Failed` reply lands only on the feed
    /// that still awaits its `req`, so two feeds can never collide over a
    /// shared epoch and a superseded reply degrades instead.
    pub pending_page: Option<Req>,
    pub memos: Vec<MemoCard>,
    pub selected: Option<MemoId>,
    pub anchor: Option<MemoAnchor>,
    pub next_cursor: Option<PageCursor>,
    pub total: Option<u64>,
    pub load: LoadStatus,
    /// Card rows laid out for the current `(width, searching, locale)` epoch —
    /// memoized per card on every render input. Owned by `feed_layout`.
    pub(crate) geometry: DerivedCache<crate::feed_layout::Geometry>,
    /// The unfiltered reading context, captured once before the first filter.
    pub unfiltered: Option<UnfilteredContext>,
}
impl FeedState {
    #[must_use]
    pub fn new(kind: FeedKind) -> Self {
        Self {
            kind,
            query: FeedQuery::default(),
            pending_page: None,
            memos: Vec::new(),
            selected: None,
            anchor: None,
            next_cursor: None,
            total: None,
            load: LoadStatus::Loading,
            geometry: DerivedCache::default(),
            unfiltered: None,
        }
    }
    #[must_use]
    pub fn selected_memo(&self) -> Option<&MemoCard> {
        self.selected
            .as_ref()
            .and_then(|id| self.memos.iter().find(|memo| &memo.id == id))
    }
    pub fn reconcile(&mut self) {
        if !self
            .selected
            .as_ref()
            .is_some_and(|id| self.memos.iter().any(|memo| &memo.id == id))
        {
            self.selected = self.memos.first().map(|memo| memo.id.clone());
        }
        if !self
            .anchor
            .as_ref()
            .is_some_and(|anchor| self.memos.iter().any(|memo| memo.id == anchor.id))
        {
            self.anchor = self.selected.clone().map(|id| MemoAnchor {
                id,
                position: CardPosition::Time,
            });
        }
    }

    /// Captures the reading position a filter suspends — query, selection and
    /// anchor only. The live cards re-resolve through the restore re-query; a
    /// memo snapshot would pin stale bodies and duplicate the whole feed.
    pub fn remember_unfiltered(&mut self) {
        if self.unfiltered.is_none() && !self.query.is_filtered() {
            self.unfiltered = Some(UnfilteredContext {
                query: self.query.clone(),
                selected: self.selected.clone(),
                anchor: self.anchor.clone(),
            });
        }
    }

    /// Keeps the previous results on screen while a changed query is in flight.
    pub fn mark_requery(&mut self) {
        self.selected = None;
        self.anchor = None;
        self.next_cursor = None;
        self.total = None;
        self.load = LoadStatus::Loading;
    }

    pub fn reconcile_replacement(&mut self, previous: &[MemoCard]) -> bool {
        // Replacement membership is asked once per vanished identity and then
        // per old-list candidate: index the new ids once instead of scanning
        // the merged list for every neighbor probe — O(memos), never
        // O(previous × memos) when a deep feed loses its selection.
        let survivors: std::collections::HashSet<&MemoId> =
            self.memos.iter().map(|memo| &memo.id).collect();
        let selected_missing = self
            .selected
            .as_ref()
            .is_some_and(|id| !survivors.contains(id));
        let anchor_missing = self
            .anchor
            .as_ref()
            .is_some_and(|anchor| !survivors.contains(&anchor.id));
        if anchor_missing {
            self.anchor = self
                .anchor
                .as_ref()
                .and_then(|anchor| Self::surviving_neighbor(previous, &anchor.id, &survivors))
                .map(|id| MemoAnchor {
                    id,
                    position: CardPosition::Time,
                });
        }
        if selected_missing {
            self.selected = self
                .selected
                .as_ref()
                .and_then(|id| Self::surviving_neighbor(previous, id, &survivors));
        }
        self.reconcile();
        selected_missing || anchor_missing
    }

    fn surviving_neighbor(
        previous: &[MemoCard],
        id: &MemoId,
        survivors: &std::collections::HashSet<&MemoId>,
    ) -> Option<MemoId> {
        let index = previous.iter().position(|memo| &memo.id == id)?;
        previous
            .iter()
            .skip(index + 1)
            .chain(previous.iter().take(index).rev())
            .find(|old| survivors.contains(&old.id))
            .map(|memo| memo.id.clone())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TaskRow {
    pub memo_id: MemoId,
    pub line: u32,
    pub text: String,
    pub date: String,
    pub done: bool,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AttachmentRow {
    pub path: RelativeWorkspacePath,
    pub owners: Vec<String>,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SelectionList<T> {
    pub items: Vec<T>,
    pub selected: usize,
    pub scroll: usize,
}
impl<T> SelectionList<T> {
    #[must_use]
    pub const fn new(items: Vec<T>) -> Self {
        Self {
            items,
            selected: 0,
            scroll: 0,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum View {
    /// Boxed: the feed dwarfs the other variants — a `View` stays narrow
    /// regardless of how much card state a feed holds.
    Feed(Box<FeedState>),
    Reader {
        memo: MemoCard,
        anchor: TextAnchor,
    },
    Tasks(SelectionList<TaskRow>),
    Statistics(StatsView),
    Attachments(SelectionList<AttachmentRow>),
    /// The registry-driven settings screen: rows are the `SettingsField`
    /// table's interactive projection, not a frozen string list.
    Settings(SettingsView),
    /// A placeholder standing in for the request (`req`) that will install the
    /// real view. The reply lands only while the placeholder still awaits that
    /// exact request — a dead placeholder reloads instead of faking content.
    Loading {
        screen: Screen,
        req: Req,
    },
    Failed {
        screen: Screen,
        diagnostic: String,
    },
}
impl View {
    #[must_use]
    pub const fn screen(&self) -> Screen {
        match self {
            Self::Feed(feed) => feed.kind.screen(),
            Self::Reader { .. } => Screen::Timeline,
            Self::Tasks(_) => Screen::Tasks,
            Self::Statistics(_) => Screen::Statistics,
            Self::Attachments(_) => Screen::Attachments,
            Self::Settings(_) => Screen::Settings,
            Self::Loading { screen, .. } | Self::Failed { screen, .. } => *screen,
        }
    }
}

/// One durable revision of a memo, ready for a picker row.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RevisionRow {
    pub revision: u64,
    /// Local `YYYY-MM-DD HH:MM:SS`, or empty for snapshots recorded without a timestamp.
    pub stamp: String,
    pub preview: String,
}

/// The item the palette was opened on; its identity is frozen for the palette's lifetime.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PaletteItem {
    None,
    Memo(Box<MemoCard>),
    Task(TaskRow),
    Attachment(AttachmentRow),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PaletteScope {
    All,
    Item,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PickerKind {
    Palette {
        item: PaletteItem,
        scope: PaletteScope,
    },
    Tags(lomo_application::TagSelectionMode),
    Dates,
    Attachments(Box<MemoCard>),
    History {
        id: MemoId,
        revisions: Vec<RevisionRow>,
    },
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Picker {
    pub kind: PickerKind,
    pub text: TextBuffer,
    /// The selected entry's position in the current list — movement and
    /// clicks write it through `Picker::select`, which stamps `identity`.
    pub selected: usize,
    /// The command `selected` names — the selection survives list rebuilds
    /// (a tag refresh, a `Saved` reply removing a palette row) by rebinding
    /// to the entry that still carries this command (I2).
    pub identity: Option<crate::event::Command>,
}
impl Picker {
    /// Point the selection at entry `index`, stamping the row's command as
    /// the identity the highlight names. Every writer goes through here so
    /// `selected` and `identity` can never disagree about which row is lit.
    pub fn select(&mut self, index: usize, entries: &[crate::menu::MenuEntry]) {
        self.selected = index;
        self.identity = entries.get(index).map(|entry| entry.command.clone());
    }
    /// The entry index the selection resolves to: the identity's slot when
    /// the command still exists — a shrunk or rebuilt list rebinds by
    /// identity, never by a stale index — else the clamped position. `None`
    /// means the list is empty: nothing is highlighted, nothing executes.
    #[must_use]
    pub fn entry_index(&self, entries: &[crate::menu::MenuEntry]) -> Option<usize> {
        if entries.is_empty() {
            return None;
        }
        if let Some(identity) = &self.identity
            && let Some(index) = entries.iter().position(|entry| &entry.command == identity)
        {
            return Some(index);
        }
        Some(self.selected.min(entries.len() - 1))
    }
    /// After the entry list is rebuilt underneath the picker, re-point the
    /// selection at the entry that still carries its identity.
    pub fn rebind(&mut self, entries: &[crate::menu::MenuEntry]) {
        let index = self.entry_index(entries).unwrap_or(0);
        self.select(index, entries);
    }
}
/// A destructive ask carries its target: the overlay names exactly what `y`
/// destroys — the frozen card, the sweep's count, the revision's stamp — so
/// the dialog never has to look the object back up (I9).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Confirmation {
    /// `memo` is the frozen target card: the prompt shows its date and first
    /// line, and acceptance still deletes by the exact `id`+`fingerprint` the
    /// dialog was opened on.
    Delete {
        memo: Box<MemoCard>,
    },
    DeleteForever(Box<MemoCard>),
    /// `count` is how many trashed memos the sweep will remove — the loaded
    /// trash feed's own total; `None` when no trash view is loaded (a trashed
    /// memo's reader still proves the trash is non-empty, just not how deep).
    EmptyTrash {
        count: Option<u64>,
    },
    Restore(Box<MemoCard>),
    RestoreRevision {
        id: MemoId,
        revision: u64,
        stamp: String,
        preview: String,
    },
    /// `preview` is the draft's first line — the dialog shows what it drops.
    DiscardDraft {
        preview: String,
    },
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum InputMode {
    Browse,
    Compose,
    Search {
        text: TextBuffer,
    },
    Picker(Picker),
    Date {
        /// The live `Effect::Date` request this dialog is awaiting (`None` while
        /// no resolution is in flight). Editing the text cancels it; a reply
        /// lands only while it still names this exact request.
        req: Option<Req>,
        text: TextBuffer,
        error: Option<String>,
    },
    Confirm(Confirmation),
    Message {
        title: String,
        lines: Vec<String>,
        scroll: usize,
    },
    Help {
        scroll: usize,
    },
    /// Inline edit of one settings field — validation errors land back on
    /// this mode, never on the config file.
    Setting(crate::settings::SettingsEdit),
    /// First-run wizard: no config.toml exists yet. Nothing is persisted
    /// until the user confirms; `req` is the registered `Bootstrap` intent
    /// whose `RuntimeReady` reply installs the real model.
    Setup(SetupState),
}

/// Which setup field the wizard's text input owns.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SetupFocus {
    Workspace,
    TimeZone,
}

/// First-run setup state: an editable proposal reviewed before any persistent
/// side effect.
///
/// `file` is where `config.toml` will be minted; `req` is the
/// already-registered bootstrap request that only dispatches after the user
/// confirms — a cancelled wizard leaves the filesystem untouched.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SetupState {
    pub file: std::path::PathBuf,
    pub proposal: crate::config::ConfigProposal,
    pub workspace: TextBuffer,
    pub time_zone: TextBuffer,
    pub focus: SetupFocus,
    pub error: Option<String>,
    pub req: Req,
    /// `~` expansion base, captured at probe time so confirmation never
    /// re-reads the environment.
    pub home_dir: Option<std::path::PathBuf>,
    /// True once the confirmation was dispatched; the wizard shows progress
    /// instead of accepting a second Enter.
    pub awaiting: bool,
}

impl SetupState {
    #[must_use]
    pub fn new(
        file: std::path::PathBuf,
        proposal: crate::config::ConfigProposal,
        req: Req,
        home_dir: Option<std::path::PathBuf>,
    ) -> Self {
        Self {
            workspace: TextBuffer::new(proposal.workspace.display().to_string()),
            time_zone: TextBuffer::new(proposal.time_zone.clone()),
            file,
            proposal,
            focus: SetupFocus::Workspace,
            error: None,
            req,
            home_dir,
            awaiting: false,
        }
    }

    /// The buffer the wizard's focused row edits.
    pub const fn field_mut(&mut self) -> &mut TextBuffer {
        match self.focus {
            SetupFocus::Workspace => &mut self.workspace,
            SetupFocus::TimeZone => &mut self.time_zone,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SaveState {
    Editing,
    /// The submission mutex for the draft revision it was taken on. `req`
    /// names the pending `DraftCommit` request: discarding the draft cancels
    /// it so the eventual `Saved` receipt degrades instead of landing, and a
    /// superseded `req` makes the marker dead regardless of the revision.
    Submitting {
        req: Req,
        revision: u64,
    },
    Failed {
        diagnostic: String,
    },
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Composer {
    pub text: TextBuffer,
    pub revision: u64,
    pub persisted_revision: u64,
    pub save: SaveState,
}
impl Default for Composer {
    fn default() -> Self {
        Self {
            text: TextBuffer::default(),
            revision: 0,
            persisted_revision: 0,
            save: SaveState::Editing,
        }
    }
}
impl Composer {
    /// The revision whose commit is still in flight, or `None`.
    ///
    /// `Submitting { req, revision }` is the submission mutex for the draft
    /// revision it was taken on: once `draft.revision` advances past it — an
    /// edit, an external-edit return, a discard — the marker is dead and its
    /// eventual reply is stale by definition. The pending registry carries the
    /// request identity; a dead marker neither gates composer input nor
    /// accepts receipts, and its pending entry is cancelled on discard so the
    /// `Saved` receipt degrades instead of touching the next draft.
    #[must_use]
    pub const fn submitting_revision(&self) -> Option<u64> {
        match self.save {
            SaveState::Submitting { revision, .. } if revision == self.revision => Some(revision),
            SaveState::Editing | SaveState::Submitting { .. } | SaveState::Failed { .. } => None,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppModel {
    pub width: u16,
    pub height: u16,
    pub view: View,
    pub history: Vec<View>,
    pub input: InputMode,
    pub draft: Composer,
    pub status: Option<String>,
    /// The reported tag dictionary. Private so `set_tags` stays the single
    /// write path — the derived menu caches key on `tags_version`, which must
    /// change exactly when this list does.
    tags: Vec<String>,
    /// Changes exactly when `tags` is replaced; the picker dictionary derived
    /// from it re-keys on this version instead of rebuilding per paint.
    tags_version: u64,
    tag_dictionary: DerivedCache<TagDictionary>,
    /// The tag picker's menu projections, memoized on `(tags_version, scope,
    /// language)` — each is built once per key change, not per frame.
    tag_menu: DerivedCache<crate::menu::TagMenuCache>,
    /// Parsed bodies parked by the resident-window bound — a card whose body
    /// leaves the working set keeps its parse here, so revisiting it restores
    /// `Ready` without another store read or Markdown render.
    pub(crate) body_cache: DerivedCache<BodyCache>,
    /// Live-request registry: every issued effect registers its landing intent
    /// here, and every receipt claims its `req` before mutating anything.
    pub pending: Pending,
    /// Receipts that needed the input focus while another mode was live.
    pub parked: std::collections::VecDeque<ParkedReply>,
    serial: u64,
    pub last_created: Option<MemoId>,
    /// The last classified notice — `:` replays it via `ShowNotice`.
    pub notice: Option<Notice>,
    /// Persistent header marks, one per class. They survive unrelated input:
    /// only an explicit acknowledgement (top-level Esc) or a same-class
    /// success removes one (I9).
    pub badges: Vec<Badge>,
    /// The terminal's graphics capability as a probed verdict — `Probing`
    /// until the query/response lands, never an environment guess (D-03).
    pub graphics: crate::graphics::GraphicsVerdict,
    pub images: Vec<crate::graphics::ReaderImage>,
    /// Whether the platform watcher is observing the workspace. `false` means
    /// external changes only arrive through the manual F5 reconcile.
    pub watcher_active: bool,
}
impl AppModel {
    #[must_use]
    pub fn new(width: u16, height: u16) -> Self {
        Self {
            width,
            height,
            view: View::Feed(Box::new(FeedState::new(FeedKind::Timeline))),
            history: Vec::new(),
            input: InputMode::Browse,
            draft: Composer::default(),
            status: None,
            tags: Vec::new(),
            tags_version: 0,
            tag_dictionary: DerivedCache::default(),
            tag_menu: DerivedCache::default(),
            body_cache: DerivedCache::default(),
            pending: Pending::default(),
            parked: std::collections::VecDeque::new(),
            serial: 0,
            last_created: None,
            notice: None,
            badges: Vec::new(),
            graphics: crate::graphics::GraphicsVerdict::Probing,
            images: Vec::new(),
            watcher_active: false,
        }
    }
    #[must_use]
    pub fn selected_memo(&self) -> Option<&MemoCard> {
        match &self.view {
            View::Feed(feed) => feed.selected_memo(),
            View::Reader { memo, .. } => Some(memo),
            View::Tasks(_)
            | View::Statistics(_)
            | View::Attachments(_)
            | View::Settings(_)
            | View::Loading { .. }
            | View::Failed { .. } => None,
        }
    }
    /// The row the palette acts on from the current view.
    #[must_use]
    pub fn palette_item(&self) -> PaletteItem {
        match &self.view {
            View::Tasks(list) => list
                .items
                .get(list.selected)
                .cloned()
                .map_or(PaletteItem::None, PaletteItem::Task),
            View::Attachments(list) => list
                .items
                .get(list.selected)
                .cloned()
                .map_or(PaletteItem::None, PaletteItem::Attachment),
            View::Feed(_)
            | View::Reader { .. }
            | View::Statistics(_)
            | View::Settings(_)
            | View::Loading { .. }
            | View::Failed { .. } => self
                .selected_memo()
                .cloned()
                .map_or(PaletteItem::None, |memo| PaletteItem::Memo(Box::new(memo))),
        }
    }
    #[must_use]
    pub fn selected_id(&self) -> Option<&str> {
        self.selected_memo().map(|memo| memo.id.as_str())
    }
    /// The timeline feed the filter commands act on: the current view, or
    /// the one suspended underneath an auxiliary page — never a snapshot, so
    /// a verdict evaluated against it cannot be stale.
    #[must_use]
    pub fn timeline_feed(&self) -> Option<&FeedState> {
        std::iter::once(&self.view)
            .chain(self.history.iter().rev())
            .find_map(|view| match view {
                View::Feed(feed) if feed.kind == FeedKind::Timeline => Some(&**feed),
                View::Feed(_)
                | View::Reader { .. }
                | View::Tasks(_)
                | View::Statistics(_)
                | View::Attachments(_)
                | View::Settings(_)
                | View::Loading { .. }
                | View::Failed { .. } => None,
            })
    }
    /// The timeline query — keyword, mode and filters — shared by the filter
    /// rows and the filter commands' capability verdicts (I2).
    #[must_use]
    pub fn timeline_query(&self) -> Option<&FeedQuery> {
        self.timeline_feed().map(|feed| &feed.query)
    }
    pub fn set_status(&mut self, text: &str) {
        self.status = Some(text.to_owned());
    }
    /// The raw tag dictionary exactly as the workspace reported it. Mutations
    /// go through `set_tags` so `tags_version` can never lie about a change.
    #[must_use]
    pub fn tags(&self) -> &[String] {
        &self.tags
    }
    /// Replaces the tag dictionary — the only write path, so the derived
    /// picker rows can key on `tags_version` without re-scanning `tags`.
    /// The flattened name list is derived here, at the write edge: a tag reply
    /// pays the expansion once and every later picker read serves the `Arc`.
    pub fn set_tags(&mut self, tags: Vec<String>) {
        let names: Arc<[Arc<str>]> = Arc::from(crate::menu::tag_names(&tags));
        self.tags = tags;
        self.tags_version = self.tags_version.wrapping_add(1);
        *self.tag_dictionary.borrow_mut() = Some((self.tags_version, names));
    }
    /// The flattened tag-name list (parents included) memoized per
    /// `tags_version` — every picker row reads this shared `Arc`.
    #[must_use]
    pub fn tag_names(&self) -> Arc<[Arc<str>]> {
        let mut cache = self.tag_dictionary.borrow_mut();
        if let Some((version, names)) = cache.as_ref()
            && *version == self.tags_version
        {
            return Arc::clone(names);
        }
        // Clone paths and test fixtures bypass `set_tags` — derive on demand
        // so the projection can never disagree with `tags`.
        let names: Arc<[Arc<str>]> = Arc::from(crate::menu::tag_names(&self.tags));
        *cache = Some((self.tags_version, Arc::clone(&names)));
        names
    }
    /// The tag picker's selectable entries, memoized on `(tags_version,
    /// scope, language)` — a tag reply or scope toggle re-keys the slot.
    pub(crate) fn tag_entries(
        &self,
        scope: lomo_application::TagSelectionMode,
    ) -> Arc<[crate::menu::MenuEntry]> {
        let language = crate::i18n::UiStrings::detect().language;
        let mut cache = self.tag_menu.borrow_mut();
        if let Some((version, cached_scope, cached_language, entries)) = &cache.entries
            && *version == self.tags_version
            && *cached_scope == scope
            && *cached_language == language
        {
            return Arc::clone(entries);
        }
        let entries: Arc<[crate::menu::MenuEntry]> =
            Arc::from(crate::menu::tag_entries(self, scope));
        cache.entries = Some((self.tags_version, scope, language, Arc::clone(&entries)));
        entries
    }
    /// The tag picker's drawn rows — the same key, the drawn projection.
    pub(crate) fn tag_menu_rows(
        &self,
        scope: lomo_application::TagSelectionMode,
    ) -> Arc<[crate::menu::MenuRow]> {
        let language = crate::i18n::UiStrings::detect().language;
        let mut cache = self.tag_menu.borrow_mut();
        if let Some((version, cached_scope, cached_language, rows)) = &cache.rows
            && *version == self.tags_version
            && *cached_scope == scope
            && *cached_language == language
        {
            return Arc::clone(rows);
        }
        let rows: Arc<[crate::menu::MenuRow]> = Arc::from(crate::menu::tag_row_list(self, scope));
        cache.rows = Some((self.tags_version, scope, language, Arc::clone(&rows)));
        rows
    }
    /// The filtered `(entries, rows)` pair for one query text — built once per
    /// `(tags_version, scope, language, text)`. Each keystroke re-keys the
    /// slot; every repaint of the same text shares the `Arc`s.
    fn tag_filtered(
        &self,
        scope: lomo_application::TagSelectionMode,
        text: &str,
    ) -> (Arc<[crate::menu::MenuEntry]>, Arc<[crate::menu::MenuRow]>) {
        let language = crate::i18n::UiStrings::detect().language;
        {
            let cache = self.tag_menu.borrow();
            if let Some(hit) = &cache.filtered
                && hit.version == self.tags_version
                && hit.scope == scope
                && hit.language == language
                && hit.text == text
            {
                return (Arc::clone(&hit.entries), Arc::clone(&hit.rows));
            }
        }
        // `tag_entries` borrows the same cell — produce the pair first, then
        // store it.
        let (entries, rows) = crate::menu::filtered_tags(self, scope, text);
        let entries: Arc<[crate::menu::MenuEntry]> = Arc::from(entries);
        let rows: Arc<[crate::menu::MenuRow]> = Arc::from(rows);
        self.tag_menu.borrow_mut().filtered = Some(crate::menu::FilteredTagMenu {
            version: self.tags_version,
            scope,
            language,
            text: text.to_owned(),
            entries: Arc::clone(&entries),
            rows: Arc::clone(&rows),
        });
        (entries, rows)
    }
    /// Tag entries kept by the picker's filter text — memoized like the
    /// unfiltered list, keyed additionally on the text itself.
    pub(crate) fn tag_entries_filtered(
        &self,
        scope: lomo_application::TagSelectionMode,
        text: &str,
    ) -> Arc<[crate::menu::MenuEntry]> {
        self.tag_filtered(scope, text).0
    }
    /// Drawn rows for the filtered tag list — the same key as the entries.
    pub(crate) fn tag_menu_rows_filtered(
        &self,
        scope: lomo_application::TagSelectionMode,
        text: &str,
    ) -> Arc<[crate::menu::MenuRow]> {
        self.tag_filtered(scope, text).1
    }
    /// Raise or refresh the persistent mark for one class — one badge per
    /// class; a new failure replaces the stale one it superseded.
    pub fn raise_badge(&mut self, severity: Severity, class: BadgeClass, text: String) {
        if let Some(badge) = self.badges.iter_mut().find(|badge| badge.class == class) {
            badge.severity = severity;
            badge.text = text;
        } else {
            self.badges.push(Badge {
                class,
                severity,
                text,
            });
        }
    }
    /// Remove every badge in `class` — the same-class success or the
    /// acknowledgement that retires it.
    pub fn clear_badges(&mut self, class: BadgeClass) {
        self.badges.retain(|badge| badge.class != class);
    }
    /// Explicit acknowledgement: every badge and the current toast leave
    /// together — the dismissal gesture is the user's "seen it" (I9).
    pub fn acknowledge_feedback(&mut self) {
        self.badges.clear();
        self.status = None;
    }
    /// Route one piece of classified feedback to its surface (I9).
    ///
    /// Every notice registers in `self.notice` for `:` replay and lands a
    /// summary on the status line. A `Badge` also raises its persistent mark;
    /// a `Modal` opens the dialog under `Browse`, but while another input
    /// owns the focus it cannot seize it — it toasts, registers, and raises
    /// the `Notice` badge as its unread marker instead.
    pub fn present(&mut self, notice: Notice) {
        self.status = Some(notice.summary());
        match notice.surface {
            Surface::Toast => {}
            Surface::Badge(class) => {
                self.raise_badge(notice.severity, class, notice.summary());
            }
            Surface::Modal if self.input == InputMode::Browse => {
                self.input = InputMode::Message {
                    title: notice.title.clone(),
                    lines: notice.lines.clone(),
                    scroll: 0,
                };
                // The registered notice is being read — the unread mark goes.
                self.clear_badges(BadgeClass::Notice);
            }
            Surface::Modal => {
                self.raise_badge(Severity::Warn, BadgeClass::Notice, notice.summary());
            }
        }
        self.notice = Some(notice);
    }
    pub fn push_view(&mut self, next: View) {
        self.history.push(std::mem::replace(&mut self.view, next));
    }
    /// Mint the next request identity. Call sites that issue a receipt-bearing
    /// effect go through `request` instead so the intent is registered in the
    /// same step; bare `next_req` is only for identity without a reply (the
    /// foreground editor handoff).
    pub const fn next_req(&mut self) -> Req {
        self.serial = self.serial.saturating_add(1);
        Req(self.serial)
    }
    /// Mint a request identity and register its landing intent — the single
    /// choke point that keeps `pending` a complete map of in-flight requests.
    pub fn request(&mut self, kind: PendingKind) -> Req {
        let req = self.next_req();
        self.pending.register(req, kind);
        req
    }
    /// `RuntimeReady` install (I5/09-F-01): the bootstrap reply merges its
    /// prepared product into the live shell instead of swapping the whole
    /// model.
    ///
    /// The prepared half owns what the bootstrap produced off-thread — the
    /// loaded view stack, the tag dictionary, the recovered composer draft
    /// and its boot-time notices. The shell keeps everything that arrived
    /// while the request was in flight: request identity (`serial`), the
    /// pending registry and parked receipts, user feedback, the landed
    /// graphics verdict, watcher state, the terminal geometry and whatever
    /// input owns the focus. `serial` takes the maximum, so a request minted
    /// before install can never collide with one minted after — a
    /// pre-bootstrap job's late reply claims only its own intent.
    ///
    /// `prepared.pending` is never merged: its registrations were minted
    /// under the prepared serial and were never dispatched, so folding them
    /// in would let a foreign receipt claim phantom intent. (It is empty by
    /// construction today — nothing off-thread registers work.)
    pub fn install_runtime(&mut self, prepared: Self) {
        self.serial = self.serial.max(prepared.serial);
        debug_assert!(
            prepared.pending.intents.is_empty(),
            "the prepared bootstrap model must not carry intent — its requests were never dispatched"
        );
        // The graphics verdict never crosses this boundary: the prepared
        // model was assembled off-thread where no `GraphicsDetected` could
        // land, and the verdict machine's only absorbing state — the loop's
        // own probe-expired answer — stays the shell's, so a merge can never
        // rewind it (11-T-01).
        debug_assert!(
            matches!(prepared.graphics, crate::graphics::GraphicsVerdict::Probing),
            "the prepared bootstrap model never observed the terminal — a non-Probing verdict here would silently replace the shell's landed one"
        );
        self.parked.extend(prepared.parked);
        // Feedback merges newest-wins: the shell's boot-window observations
        // are fresher than the prepared boot-time text, so prepared fields
        // only fill still-empty slots.
        if self.status.is_none() {
            self.status = prepared.status;
        }
        if self.notice.is_none() {
            self.notice = prepared.notice;
        }
        for badge in prepared.badges {
            if !self.badges.iter().any(|live| live.class == badge.class) {
                self.badges.push(badge);
            }
        }
        if self.last_created.is_none() {
            self.last_created = prepared.last_created;
        }
        // The recovered composer draft lands only on a virgin composer: text
        // the user typed while bootstrap ran outranks the persisted
        // snapshot, which stays on disk either way.
        if self.draft == Composer::default() {
            self.draft = prepared.draft;
        }
        // An input the user opened during bootstrap keeps its focus; an
        // untouched `Browse` takes whatever the prepared boot had to say
        // (the overdue-reminder dialog), and under a busy input a prepared
        // message reroutes through `present` like any live modal (I9). The
        // first-run wizard's own reply retires it the same way.
        if matches!(self.input, InputMode::Browse | InputMode::Setup(_)) {
            self.input = prepared.input;
        } else if let InputMode::Message { title, lines, .. } = prepared.input {
            self.present(Notice::modal(Severity::Warn, title, lines));
        }
        // The workspace projection the request produced: the loaded view
        // stack replaces the loading shell wholesale.
        self.view = prepared.view;
        self.history = prepared.history;
        self.set_tags(prepared.tags);
        // `graphics`, `images`, `watcher_active`, `width` and `height` stay
        // the shell's — they are live session observations the off-thread
        // preparation never saw.
    }
    /// Deliver replies that parked while another input owned the focus. Called
    /// after every command and message application so a parked receipt lands
    /// as soon as the model settles back to `Browse`.
    pub fn drain_parked(&mut self) {
        while self.input == InputMode::Browse {
            let Some(parked) = self.parked.pop_front() else {
                break;
            };
            match parked {
                ParkedReply::History { id, revisions } => {
                    let mut picker = Picker {
                        kind: PickerKind::History { id, revisions },
                        text: TextBuffer::default(),
                        selected: 0,
                        identity: None,
                    };
                    picker.rebind(&crate::menu::entries(self, &picker));
                    self.input = InputMode::Picker(picker);
                }
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HeatPoint {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    pub count: u64,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StatsView {
    pub zone: String,
    pub as_of_year: i32,
    pub as_of_month: u8,
    pub as_of_day: u8,
    pub total_memos: u64,
    pub total_words: u64,
    pub active_days: u64,
    pub current_streak: u64,
    pub longest_streak: u64,
    pub this_week: u64,
    pub this_month: u64,
    pub this_year: u64,
    pub daily: Vec<HeatPoint>,
}
