//! Typed boundary between presentation transitions and runtime IO.
use lomo_application::PageCursor;
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

use crate::model::{FeedKind, FeedQuery, MemoCard, MemoVersion, Screen, TaskRow, View};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FeedRequest {
    pub epoch: u64,
    pub kind: FeedKind,
    pub query: FeedQuery,
    pub intent: PageIntent,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PageIntent {
    Initial,
    Append(PageCursor),
    Refresh { loaded: usize, anchors: Vec<MemoId> },
}

impl PageIntent {
    #[must_use]
    pub const fn cursor(&self) -> Option<&PageCursor> {
        match self {
            Self::Append(cursor) => Some(cursor),
            Self::Initial | Self::Refresh { .. } => None,
        }
    }

    #[must_use]
    pub fn covers(&self, cards: &[MemoCard]) -> bool {
        match self {
            Self::Refresh { loaded, anchors } => {
                cards.len() >= *loaded
                    && anchors
                        .iter()
                        .all(|id| cards.iter().any(|card| &card.id == id))
            }
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
        epoch: u64,
        screen: Screen,
    },
    Bodies {
        epoch: u64,
        versions: Vec<MemoVersion>,
    },
    ReadMemo {
        epoch: u64,
        id: MemoId,
    },
    PersistDraft {
        revision: u64,
        content: String,
    },
    CommitDraft {
        revision: u64,
        content: String,
    },
    LoadImage(crate::graphics::ImageRequest),
    Edit(EditTarget),
    CommitEdit(EditedMemo),
    CaptureEdited {
        revision: u64,
        content: String,
        draft_path: std::path::PathBuf,
    },
    ToggleTask(TaskRow),
    Pin {
        id: MemoId,
        pinned: bool,
    },
    Delete {
        id: MemoId,
        fingerprint: String,
    },
    Restore(MemoId),
    History(MemoId),
    ImportClipboard,
    OpenAttachment(RelativeWorkspacePath),
    Tags,
    Date {
        ticket: u64,
        text: String,
    },
    Refresh,
    Quit,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RuntimeMessage {
    QuitReady,
    Image {
        request: crate::graphics::ImageRequest,
        result: Result<crate::graphics::TerminalImage, String>,
    },
    View {
        epoch: u64,
        view: Box<View>,
    },
    Page {
        epoch: u64,
        append: bool,
        cards: Vec<MemoCard>,
        next: Option<PageCursor>,
        total: Option<u64>,
    },
    Bodies {
        epoch: u64,
        bodies: Vec<BodyReply>,
    },
    ReadMemo {
        epoch: u64,
        memo: Box<MemoCard>,
    },
    DraftStored {
        revision: u64,
    },
    Saved {
        revision: u64,
        id: MemoId,
    },
    Tags(Vec<String>),
    Message {
        title: String,
        lines: Vec<String>,
    },
    Date {
        ticket: u64,
        from: i64,
        until: i64,
        label: String,
    },
    Changed(String),
    Failed {
        target: FailureTarget,
        diagnostic: String,
    },
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FailureTarget {
    Feed(u64),
    View(u64),
    Date(u64),
    DraftPersist(u64),
    DraftCommit(u64),
    Action,
}

impl Effect {
    #[must_use]
    pub const fn failure_target(&self) -> FailureTarget {
        match self {
            Self::Query(request) => FailureTarget::Feed(request.epoch),
            Self::Navigate { epoch, .. } | Self::ReadMemo { epoch, .. } => {
                FailureTarget::View(*epoch)
            }
            Self::Date { ticket, .. } => FailureTarget::Date(*ticket),
            Self::PersistDraft { revision, .. } | Self::CaptureEdited { revision, .. } => {
                FailureTarget::DraftPersist(*revision)
            }
            Self::CommitDraft { revision, .. } => FailureTarget::DraftCommit(*revision),
            Self::LoadImage(_)
            | Self::Bodies { .. }
            | Self::CommitEdit(_)
            | Self::Edit(_)
            | Self::ToggleTask(_)
            | Self::Pin { .. }
            | Self::Delete { .. }
            | Self::Restore(_)
            | Self::History(_)
            | Self::ImportClipboard
            | Self::OpenAttachment(_)
            | Self::Tags
            | Self::Refresh
            | Self::Quit => FailureTarget::Action,
        }
    }
}
