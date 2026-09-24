//! Presentation state: one active view and one input owner.
use std::sync::Arc;

use lomo_application::{MemoFilters, PageCursor, SearchMode};
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

use crate::input::TextBuffer;

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

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BodyState {
    Pending,
    Loading { epoch: u64 },
    Ready(Arc<crate::content::MemoBody>),
    Failed(String),
}

impl BodyState {
    #[must_use]
    pub const fn needs_load(&self, epoch: u64) -> bool {
        match self {
            Self::Pending => true,
            Self::Loading { epoch: requested } => *requested != epoch,
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

#[derive(Clone, Debug, Eq, PartialEq)]
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Notice {
    pub title: String,
    pub lines: Vec<String>,
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FeedState {
    pub kind: FeedKind,
    pub query: FeedQuery,
    pub epoch: u64,
    pub memos: Vec<MemoCard>,
    pub selected: Option<MemoId>,
    pub anchor: Option<MemoAnchor>,
    pub next_cursor: Option<PageCursor>,
    pub total: Option<u64>,
    pub load: LoadStatus,
    /// The complete unfiltered reading context, captured once before the first filter.
    pub unfiltered: Option<Box<Self>>,
}
impl FeedState {
    #[must_use]
    pub fn new(kind: FeedKind) -> Self {
        Self {
            kind,
            query: FeedQuery::default(),
            epoch: 0,
            memos: Vec::new(),
            selected: None,
            anchor: None,
            next_cursor: None,
            total: None,
            load: LoadStatus::Loading,
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

    pub fn remember_unfiltered(&mut self) {
        if self.unfiltered.is_none() && !self.query.is_filtered() {
            self.unfiltered = Some(Box::new(self.clone()));
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
        let selected_missing = self
            .selected
            .as_ref()
            .is_some_and(|id| !self.memos.iter().any(|memo| &memo.id == id));
        let anchor_missing = self
            .anchor
            .as_ref()
            .is_some_and(|anchor| !self.memos.iter().any(|memo| memo.id == anchor.id));
        if anchor_missing {
            self.anchor = self
                .anchor
                .as_ref()
                .and_then(|anchor| self.surviving_neighbor(previous, &anchor.id))
                .map(|id| MemoAnchor {
                    id,
                    position: CardPosition::Time,
                });
        }
        if selected_missing {
            self.selected = self
                .selected
                .as_ref()
                .and_then(|id| self.surviving_neighbor(previous, id));
        }
        self.reconcile();
        selected_missing || anchor_missing
    }

    fn surviving_neighbor(&self, previous: &[MemoCard], id: &MemoId) -> Option<MemoId> {
        let index = previous.iter().position(|memo| &memo.id == id)?;
        previous
            .iter()
            .skip(index + 1)
            .chain(previous.iter().take(index).rev())
            .find(|old| self.memos.iter().any(|memo| memo.id == old.id))
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
    Feed(FeedState),
    Reader { memo: MemoCard, anchor: TextAnchor },
    Tasks(SelectionList<TaskRow>),
    Statistics(StatsView),
    Attachments(SelectionList<AttachmentRow>),
    Settings(Vec<String>),
    Loading(Screen),
    Failed { screen: Screen, diagnostic: String },
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
            Self::Loading(screen) | Self::Failed { screen, .. } => *screen,
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
    pub selected: usize,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Confirmation {
    Delete { id: MemoId, fingerprint: String },
    DeleteForever(MemoId),
    EmptyTrash,
    Restore(MemoId),
    RestoreRevision { id: MemoId, revision: u64 },
    DiscardDraft,
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
        ticket: u64,
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
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SaveState {
    Editing,
    Submitting { revision: u64 },
    Failed { diagnostic: String },
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppModel {
    pub width: u16,
    pub height: u16,
    pub view: View,
    pub history: Vec<View>,
    pub input: InputMode,
    pub draft: Composer,
    pub status: Option<String>,
    pub tags: Vec<String>,
    pub epoch: u64,
    serial: u64,
    pub last_created: Option<MemoId>,
    pub notice: Option<Notice>,
    pub graphics: crate::media::GraphicsProtocol,
    pub cell_size: Option<crate::graphics::CellSize>,
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
            view: View::Feed(FeedState::new(FeedKind::Timeline)),
            history: Vec::new(),
            input: InputMode::Browse,
            draft: Composer::default(),
            status: None,
            tags: Vec::new(),
            epoch: 0,
            serial: 0,
            last_created: None,
            notice: None,
            graphics: crate::media::GraphicsProtocol::None,
            cell_size: None,
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
            | View::Loading(_)
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
            | View::Loading(_)
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
    pub fn set_status(&mut self, text: &str) {
        self.status = Some(text.to_owned());
    }
    pub fn present_notice(&mut self, title: String, lines: Vec<String>) {
        self.status = Some(format!("{title}: {}", lines.join(" · ")));
        self.notice = Some(Notice {
            title: title.clone(),
            lines: lines.clone(),
        });
        if self.input == InputMode::Browse {
            self.input = InputMode::Message {
                title,
                lines,
                scroll: 0,
            };
        }
    }
    pub fn push_view(&mut self, next: View) {
        self.history.push(std::mem::replace(&mut self.view, next));
    }
    pub const fn next_epoch(&mut self) -> u64 {
        self.epoch = self.next_ticket();
        self.epoch
    }
    pub const fn next_ticket(&mut self) -> u64 {
        self.serial = self.serial.saturating_add(1);
        self.serial
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
