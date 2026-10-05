//! Contextual key translation — and the capability table behind every surface.
//!
//! One `Availability` verdict per command is the single source of truth (I2):
//! menus, the hint bar, `--help` and dispatch all project it, so no surface
//! can advertise an action the same table would refuse. One `KEY_BINDINGS`
//! table feeds key translation, menu key columns and `--help`, so a hint can
//! never name a key nothing listens to.
use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

use crate::i18n::UiStrings;
use crate::model::{AppModel, FeedKind, FeedQuery, InputMode, MemoCard, PaletteItem, Screen, View};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TextEdit {
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
    Backspace,
    Delete,
    Undo,
    Redo,
    Newline,
    Complete,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Command {
    Quit,
    Help,
    Palette,
    Actions,
    Back,
    Accept,
    Compose,
    Commit,
    ExternalEdit,
    Search,
    Tags,
    Date,
    CustomDate,
    RemoveKeyword,
    RemoveDate,
    ShowNotice,
    ToggleSearchMode,
    ToggleTagScope,
    ClearFilters,
    DiscardDraft,
    Type(String),
    Edit(TextEdit),
    Move(i32),
    Scroll(i32),
    Page(i32),
    First,
    Last,
    Goto(Screen),
    /// The payload shares the tag dictionary's `Arc<str>` — building a picker
    /// row is a refcount bump, not a String clone.
    SelectTag(Option<std::sync::Arc<str>>),
    SetDate(String),
    Pin,
    Delete,
    DeleteForever,
    EmptyTrash,
    Restore,
    History,
    RestoreRevision(u64),
    OpenMemo(MemoId),
    ToggleTask,
    ImportClipboard,
    Attachments,
    OpenAttachment(RelativeWorkspacePath),
    Refresh,
    /// A paste arrived while no text field could receive it.
    PasteDenied,
    ShowCreated,
    Click(u16, u16),
    /// Closes the owning overlay — nothing else. Picker close rows bind this
    /// instead of `Back`, which would additionally pop a history layer (A-02).
    DismissPicker,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MemoAction {
    Read,
    Edit,
    Pin,
    Delete,
    DeleteForever,
    Restore,
    History,
    Attachments,
}

/// Whether the model lets a command run right now — the verdict every
/// surface consults before advertising or executing it.
///
/// `Ready` dispatches. `Refused` carries the typed reason: the status line
/// shows it and the menu greys the row while still naming why — and the I9
/// badge layer consumes the same typed value, not re-parsed prose. `Hidden`
/// means the action does not exist in this state at all: no menu materializes
/// the row, and dispatch ignores it should a stale binding ever send one.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Availability {
    Ready,
    Refused(Refusal),
    Hidden,
}

/// Why an advertised-looking action refuses — the localized reason text is
/// generated once here so the status line and the greyed menu row cannot
/// drift into different stories.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Refusal {
    /// The command needs a selected item and the view has none.
    NoSelection,
    /// Enter on a picker whose filtered entry list is empty.
    NoMatches,
    /// The overlay cannot draw a single entry row.
    NoRoom,
    /// The memo this action would touch sits in the trash.
    MemoTrashed,
    /// A trash-only action on a memo that is not trashed.
    MemoNotTrashed,
    /// The trash sweep only runs where the trash is in scope.
    OutsideTrash,
    /// The trash holds nothing to empty.
    TrashEmpty,
    /// Navigation asked for the screen already showing or already loading.
    AlreadyThere,
    /// Enter asked to read the memo the reader already shows.
    AlreadyReading,
    /// Esc at the root — nothing is stacked underneath to return to.
    TopLevel,
    /// The draft is empty: commit or discard has nothing to act on.
    EmptyDraft,
    /// A save is already in flight for this draft revision.
    Submitting,
    /// Nothing was saved this session for "view last saved" to open.
    NothingSaved,
    /// No notification exists for the notice dialog to show.
    NoNotice,
    /// The filter this would remove is not set.
    FilterUnset,
    /// No filters are active.
    NoFilters,
    /// The command only works while a memo feed is on screen.
    FeedOnly,
}

impl Refusal {
    /// The one-line reason — bilingual so every projection prints the same
    /// words the status line would.
    #[must_use]
    pub fn text(self) -> &'static str {
        let s = UiStrings::detect();
        match self {
            Self::NoSelection => s.text("nothing to select", "没有可选择的条目"),
            Self::NoMatches => s.text("no matching entries", "没有匹配项"),
            Self::NoRoom => s.text(
                "overlay too small to show entries",
                "浮层太小，无法显示条目",
            ),
            Self::MemoTrashed => s.text("the memo is in the trash", "记录已在回收站中"),
            Self::MemoNotTrashed => s.text("the memo is not in the trash", "记录不在回收站中"),
            Self::OutsideTrash => s.text("only inside the trash", "仅在回收站中可用"),
            Self::TrashEmpty => s.text("the trash is already empty", "回收站已是空的"),
            Self::AlreadyThere => s.text("already on this page", "已在当前页面"),
            Self::AlreadyReading => s.text("already reading this memo", "已在阅读这条记录"),
            Self::TopLevel => s.text("nothing to go back to", "没有可返回的页面"),
            Self::EmptyDraft => s.text("the draft is empty", "草稿为空"),
            Self::Submitting => s.text("a save is already in flight", "正在保存中"),
            Self::NothingSaved => s.text("nothing saved this session", "本次会话还没有保存记录"),
            Self::NoNotice => s.text("no notification to show", "没有可查看的提示"),
            Self::FilterUnset => s.text("that filter is not set", "该筛选条件未设置"),
            Self::NoFilters => s.text("no filters are set", "未设置筛选条件"),
            Self::FeedOnly => s.text("only on a memo list", "仅在记录列表上可用"),
        }
    }
}

impl Command {
    #[must_use]
    pub const fn memo_action(&self) -> Option<MemoAction> {
        match self {
            Self::Accept => Some(MemoAction::Read),
            Self::ExternalEdit => Some(MemoAction::Edit),
            Self::Pin => Some(MemoAction::Pin),
            Self::Delete => Some(MemoAction::Delete),
            Self::DeleteForever => Some(MemoAction::DeleteForever),
            Self::Restore => Some(MemoAction::Restore),
            Self::History => Some(MemoAction::History),
            Self::Attachments => Some(MemoAction::Attachments),
            Self::Quit
            | Self::Help
            | Self::Palette
            | Self::Actions
            | Self::Back
            | Self::Compose
            | Self::Commit
            | Self::Search
            | Self::Tags
            | Self::Date
            | Self::CustomDate
            | Self::RemoveKeyword
            | Self::RemoveDate
            | Self::ShowNotice
            | Self::ToggleSearchMode
            | Self::ToggleTagScope
            | Self::ClearFilters
            | Self::DiscardDraft
            | Self::Type(_)
            | Self::Edit(_)
            | Self::Move(_)
            | Self::Scroll(_)
            | Self::Page(_)
            | Self::First
            | Self::Last
            | Self::Goto(_)
            | Self::SelectTag(_)
            | Self::SetDate(_)
            | Self::ToggleTask
            | Self::EmptyTrash
            | Self::RestoreRevision(_)
            | Self::OpenMemo(_)
            | Self::ImportClipboard
            | Self::OpenAttachment(_)
            | Self::Refresh
            | Self::PasteDenied
            | Self::ShowCreated
            | Self::Click(..)
            | Self::DismissPicker => None,
        }
    }

    /// The state-aware verdict — what dispatch itself checks before running,
    /// what a menu row greys by, and what the hint bar filters on (I2).
    ///
    /// Verdicts name *why* through `Refusal`, so a rejected key never dies
    /// silently and a menu row can explain itself instead of vanishing.
    #[must_use]
    pub fn availability(&self, model: &AppModel) -> Availability {
        match self {
            // Editing and overlay primitives exist only inside the input mode
            // that owns them — browsing surfaces never offer them at all.
            Self::Type(_)
            | Self::Edit(_)
            | Self::PasteDenied
            | Self::ToggleTagScope
            | Self::RestoreRevision(_)
            | Self::DismissPicker => Availability::Hidden,

            Self::Commit => commit_availability(model),

            // Always-dispatchable actions — they carry their own payload or
            // open inputs that need no precondition.
            Self::Quit
            | Self::Help
            | Self::Palette
            | Self::Search
            | Self::Tags
            | Self::Date
            | Self::CustomDate
            | Self::Compose
            | Self::Refresh
            | Self::ImportClipboard
            | Self::OpenMemo(_)
            | Self::OpenAttachment(_)
            | Self::Click(..)
            | Self::SetDate(_)
            | Self::SelectTag(_) => Availability::Ready,

            Self::Actions => verdict(
                model.palette_item() != PaletteItem::None,
                Refusal::NoSelection,
            ),
            Self::Back => {
                let filtered = matches!(&model.view, View::Feed(feed) if feed.query.is_filtered());
                // A root Esc is also the acknowledgement gesture: with
                // feedback on screen (a toast or badges) it stays dispatchable
                // and clears it (I9).
                verdict(
                    filtered
                        || !model.history.is_empty()
                        || !model.badges.is_empty()
                        || model.status.is_some(),
                    Refusal::TopLevel,
                )
            }
            Self::ShowCreated => verdict(model.last_created.is_some(), Refusal::NothingSaved),
            Self::ShowNotice => verdict(model.notice.is_some(), Refusal::NoNotice),
            Self::DiscardDraft => verdict(
                !model.draft.text.text().trim().is_empty(),
                Refusal::EmptyDraft,
            ),
            Self::Goto(screen) => {
                // A failed placeholder re-issues its navigation; going where
                // the view already is (or is already loading towards) is a
                // no-op that must not stack a duplicate (A-11).
                verdict(
                    model.view.screen() != *screen || matches!(model.view, View::Failed { .. }),
                    Refusal::AlreadyThere,
                )
            }
            Self::ToggleSearchMode => {
                verdict(matches!(model.view, View::Feed(_)), Refusal::FeedOnly)
            }
            Self::RemoveKeyword => verdict(
                model
                    .timeline_query()
                    .is_some_and(|query| !query.text.is_empty()),
                Refusal::FilterUnset,
            ),
            Self::RemoveDate => verdict(
                model.timeline_query().is_some_and(|query| {
                    query.date_label.is_some()
                        || query.filters.date_from_inclusive_ms.is_some()
                        || query.filters.date_until_exclusive_ms.is_some()
                }),
                Refusal::FilterUnset,
            ),
            Self::ClearFilters => verdict(
                model.timeline_query().is_some_and(FeedQuery::is_filtered),
                Refusal::NoFilters,
            ),
            Self::EmptyTrash => trash_availability(model),
            Self::ToggleTask => task_availability(model),
            Self::Move(_) | Self::Scroll(_) | Self::Page(_) | Self::First | Self::Last => {
                movement_availability(model)
            }
            Self::Accept => accept_availability(model),
            Self::ExternalEdit => external_edit_availability(model),
            Self::Pin
            | Self::Delete
            | Self::DeleteForever
            | Self::Restore
            | Self::History
            | Self::Attachments => memo_command_availability(self, model),
        }
    }

    /// The browse-mode key label that fires this command — read off
    /// `KEY_BINDINGS`, the same table `command_from_key` dispatches on, so a
    /// menu key column or hint bar can never name a dead key.
    #[must_use]
    pub fn browse_key_label(&self) -> Option<&'static str> {
        KEY_BINDINGS
            .iter()
            .find(|binding| binding.scope == KeyScope::Browse && binding.command == *self)
            .map(|binding| binding.label)
    }
}

/// A boolean gate rendered as a verdict — every plain precondition shares
/// this shape so a refused reason can never be forgotten.
const fn verdict(condition: bool, refusal: Refusal) -> Availability {
    if condition {
        Availability::Ready
    } else {
        Availability::Refused(refusal)
    }
}

/// Ctrl+S's verdict — it exists only while the composer owns input, and the
/// composer itself splits "ready" from "empty" and "already submitting".
fn commit_availability(model: &AppModel) -> Availability {
    match &model.input {
        InputMode::Compose if model.draft.submitting_revision().is_some() => {
            Availability::Refused(Refusal::Submitting)
        }
        InputMode::Compose if model.draft.text.text().trim().is_empty() => {
            Availability::Refused(Refusal::EmptyDraft)
        }
        InputMode::Compose => Availability::Ready,
        InputMode::Browse
        | InputMode::Search { .. }
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Confirm(_)
        | InputMode::Message { .. }
        | InputMode::Help { .. }
        | InputMode::Setting(_)
        | InputMode::Setup(_) => Availability::Hidden,
    }
}

/// Emptying the trash exists where the trash is in scope: the trash feed
/// itself, or a trashed memo's reader — a trashed memo is itself evidence
/// the trash holds entries (A-05).
fn trash_availability(model: &AppModel) -> Availability {
    match &model.view {
        View::Feed(feed) if feed.kind == FeedKind::Trash => {
            verdict(!feed.memos.is_empty(), Refusal::TrashEmpty)
        }
        View::Reader { memo, .. } if memo.trashed => Availability::Ready,
        View::Feed(_)
        | View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. } => Availability::Refused(Refusal::OutsideTrash),
    }
}

/// Task toggling exists only on a selected task row.
fn task_availability(model: &AppModel) -> Availability {
    match &model.view {
        View::Tasks(list) => verdict(
            list.items.get(list.selected).is_some(),
            Refusal::NoSelection,
        ),
        View::Feed(_)
        | View::Reader { .. }
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. } => Availability::Refused(Refusal::NoSelection),
    }
}

/// `e` edits the selected memo — or `config.toml` itself on the Settings
/// screen, which needs no memo at all.
fn external_edit_availability(model: &AppModel) -> Availability {
    match &model.view {
        View::Settings(_) => Availability::Ready,
        View::Feed(_)
        | View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Loading { .. }
        | View::Failed { .. } => model
            .selected_memo()
            .map_or(Availability::Refused(Refusal::NoSelection), |memo| {
                MemoAction::Edit.availability(model, memo)
            }),
    }
}

/// A memo action against the current selection — the verdict is the action's
/// own against the selected memo, or `NoSelection` where there is none.
fn memo_command_availability(command: &Command, model: &AppModel) -> Availability {
    let Some(action) = command.memo_action() else {
        unreachable!("the caller only delegates memo actions");
    };
    model
        .selected_memo()
        .map_or(Availability::Refused(Refusal::NoSelection), |memo| {
            action.availability(model, memo)
        })
}

/// Movement commands — `j/k`, arrows, paging, `g/G`, the wheel — need a view
/// with something to move over. The reader scrolls its own text.
fn movement_availability(model: &AppModel) -> Availability {
    match &model.view {
        View::Reader { .. } => Availability::Ready,
        View::Feed(feed) if !feed.memos.is_empty() => Availability::Ready,
        View::Tasks(list) if !list.items.is_empty() => Availability::Ready,
        View::Attachments(list) if !list.items.is_empty() => Availability::Ready,
        View::Settings(settings) if !settings.rows.is_empty() => Availability::Ready,
        View::Feed(_)
        | View::Tasks(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Statistics(_)
        | View::Loading { .. }
        | View::Failed { .. } => Availability::Refused(Refusal::NoSelection),
    }
}

/// Enter's verdict per view — read a memo, toggle a task, open an attachment,
/// edit the selected setting; everywhere else there is nothing to accept.
fn accept_availability(model: &AppModel) -> Availability {
    match &model.view {
        View::Reader { .. } => Availability::Refused(Refusal::AlreadyReading),
        View::Feed(feed) if feed.selected_memo().is_some() => Availability::Ready,
        View::Tasks(list) if list.items.get(list.selected).is_some() => Availability::Ready,
        View::Attachments(list) if list.items.get(list.selected).is_some() => Availability::Ready,
        View::Settings(settings) if settings.rows.get(settings.selected).is_some() => {
            Availability::Ready
        }
        View::Feed(_)
        | View::Tasks(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Statistics(_)
        | View::Loading { .. }
        | View::Failed { .. } => Availability::Refused(Refusal::NoSelection),
    }
}

impl MemoAction {
    /// The action's verdict against one concrete memo — the palette freezes
    /// the item it was opened on, so the verdict evaluates that memo, not
    /// whatever happens to be selected behind the overlay.
    #[must_use]
    pub fn availability(&self, model: &AppModel, memo: &MemoCard) -> Availability {
        match self {
            Self::Read => {
                if matches!(&model.view, View::Reader { memo: open, .. } if open.id == memo.id) {
                    Availability::Refused(Refusal::AlreadyReading)
                } else {
                    Availability::Ready
                }
            }
            Self::Edit | Self::Pin => {
                if memo.trashed {
                    Availability::Refused(Refusal::MemoTrashed)
                } else {
                    Availability::Ready
                }
            }
            Self::DeleteForever | Self::Restore => {
                if memo.trashed {
                    Availability::Ready
                } else {
                    Availability::Refused(Refusal::MemoNotTrashed)
                }
            }
            // One key, two meanings: a live memo goes to the trash, a trashed
            // memo faces the permanent delete — the confirm names which.
            Self::Delete | Self::History | Self::Attachments => Availability::Ready,
        }
    }
}

/// Which input context a binding fires in — `command_from_key` resolves the
/// active scopes top-down and `--help` renders the reachable ones.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KeyScope {
    /// Fires ahead of every mode — process-level keys like Ctrl+C.
    Global,
    /// Browsing keys while no text field owns input.
    Browse,
    /// Quick-capture bindings that shadow the shared field keys.
    Compose,
    /// Keys every text field shares (Esc, Enter, arrows, edits). A plain
    /// `Char` types itself — a wildcard the table cannot express, handled by
    /// the field fallback.
    Field,
    /// The search field's extra binding on top of `Field`.
    Search,
    /// The setup wizard's extra field navigation on top of `Field`.
    Setup,
    /// The yes/no confirmation dialog.
    Confirm,
    /// Scrollable message overlays — messages and the `?` help screen.
    Overlay,
}

/// One row of the binding table: the key event that fires it, the scope it
/// fires in, the command it produces, plus the labels surfaces show.
///
/// `label` is the short key-column text ("m", "F5", "↑") menus and the hint
/// bar print; `help` is the bilingual one-line description `--help` renders.
/// `modifiers` marks the binding CONTROL-gated: a non-CONTROL binding ignores
/// other modifiers exactly like the old match arms did (`Alt+m` still pins),
/// and a CONTROL binding fires whenever CONTROL is among the pressed
/// modifiers — extra modifiers never disarm it.
#[derive(Clone, Debug)]
pub struct KeyBinding {
    pub scope: KeyScope,
    pub label: &'static str,
    pub code: KeyCode,
    pub modifiers: KeyModifiers,
    pub command: Command,
    pub help: (&'static str, &'static str),
}

impl KeyBinding {
    /// Whether this binding fires for the event — the CONTROL flag splits the
    /// table into modifier-checked ctrl chords and modifier-agnostic keys.
    fn fires(&self, key: &KeyEvent) -> bool {
        self.code == key.code
            && if self.modifiers.contains(KeyModifiers::CONTROL) {
                key.modifiers.contains(KeyModifiers::CONTROL)
            } else {
                !key.modifiers.contains(KeyModifiers::CONTROL)
            }
    }

    /// The event that fires this binding — used by the help↔binding contract.
    #[must_use]
    pub const fn event(&self) -> KeyEvent {
        KeyEvent::new(self.code, self.modifiers)
    }
}

const fn bind(
    scope: KeyScope,
    label: &'static str,
    code: KeyCode,
    modifiers: KeyModifiers,
    command: Command,
    en: &'static str,
    zh: &'static str,
) -> KeyBinding {
    KeyBinding {
        scope,
        label,
        code,
        modifiers,
        command,
        help: (en, zh),
    }
}

/// The one binding table every surface reads.
///
/// `command_from_key` dispatches on it, menus read their key column from it
/// via `Command::browse_key_label`, the hint bar filters it through
/// `Command::availability`, and `keys_help` renders it. There is no second
/// list of keys anywhere — that is the I2 invariant.
pub static KEY_BINDINGS: &[KeyBinding] = &[
    // — browsing: a small semantic set of direct keys —
    bind(
        KeyScope::Browse,
        "j",
        KeyCode::Char('j'),
        KeyModifiers::NONE,
        Command::Move(1),
        "next memo / scroll down",
        "下一条记录／向下滚动",
    ),
    bind(
        KeyScope::Browse,
        "↓",
        KeyCode::Down,
        KeyModifiers::NONE,
        Command::Move(1),
        "next memo / scroll down",
        "下一条记录／向下滚动",
    ),
    bind(
        KeyScope::Browse,
        "k",
        KeyCode::Char('k'),
        KeyModifiers::NONE,
        Command::Move(-1),
        "previous memo / scroll up",
        "上一条记录／向上滚动",
    ),
    bind(
        KeyScope::Browse,
        "↑",
        KeyCode::Up,
        KeyModifiers::NONE,
        Command::Move(-1),
        "previous memo / scroll up",
        "上一条记录／向上滚动",
    ),
    bind(
        KeyScope::Browse,
        "PgDn",
        KeyCode::PageDown,
        KeyModifiers::NONE,
        Command::Page(1),
        "page down",
        "下一页",
    ),
    bind(
        KeyScope::Browse,
        "Ctrl+D",
        KeyCode::Char('d'),
        KeyModifiers::CONTROL,
        Command::Page(1),
        "page down",
        "下一页",
    ),
    bind(
        KeyScope::Browse,
        "PgUp",
        KeyCode::PageUp,
        KeyModifiers::NONE,
        Command::Page(-1),
        "page up",
        "上一页",
    ),
    bind(
        KeyScope::Browse,
        "Ctrl+U",
        KeyCode::Char('u'),
        KeyModifiers::CONTROL,
        Command::Page(-1),
        "page up",
        "上一页",
    ),
    bind(
        KeyScope::Browse,
        "Home",
        KeyCode::Home,
        KeyModifiers::NONE,
        Command::First,
        "first memo",
        "第一条记录",
    ),
    bind(
        KeyScope::Browse,
        "g",
        KeyCode::Char('g'),
        KeyModifiers::NONE,
        Command::First,
        "first memo",
        "第一条记录",
    ),
    bind(
        KeyScope::Browse,
        "End",
        KeyCode::End,
        KeyModifiers::NONE,
        Command::Last,
        "last memo",
        "末条记录",
    ),
    bind(
        KeyScope::Browse,
        "G",
        KeyCode::Char('G'),
        KeyModifiers::NONE,
        Command::Last,
        "last memo",
        "末条记录",
    ),
    bind(
        KeyScope::Browse,
        "Enter",
        KeyCode::Enter,
        KeyModifiers::NONE,
        Command::Accept,
        "read / choose / toggle todo",
        "阅读／选择／切换待办",
    ),
    bind(
        KeyScope::Browse,
        "Esc",
        KeyCode::Esc,
        KeyModifiers::NONE,
        Command::Back,
        "one layer back",
        "退回一层",
    ),
    bind(
        KeyScope::Browse,
        "n",
        KeyCode::Char('n'),
        KeyModifiers::NONE,
        Command::Compose,
        "quick capture",
        "随手记录",
    ),
    bind(
        KeyScope::Browse,
        "e",
        KeyCode::Char('e'),
        KeyModifiers::NONE,
        Command::ExternalEdit,
        "edit memo or settings file in external editor",
        "在外部编辑器中编辑记录或配置文件",
    ),
    bind(
        KeyScope::Browse,
        "m",
        KeyCode::Char('m'),
        KeyModifiers::NONE,
        Command::Pin,
        "pin / unpin the selected memo",
        "置顶／取消置顶当前记录",
    ),
    bind(
        KeyScope::Browse,
        "d",
        KeyCode::Char('d'),
        KeyModifiers::NONE,
        Command::Delete,
        "move to trash / delete permanently in trash",
        "移入回收站／在回收站中永久删除",
    ),
    bind(
        KeyScope::Browse,
        "/",
        KeyCode::Char('/'),
        KeyModifiers::NONE,
        Command::Search,
        "search memos",
        "搜索记录",
    ),
    bind(
        KeyScope::Browse,
        ":",
        KeyCode::Char(':'),
        KeyModifiers::NONE,
        Command::Palette,
        "command palette",
        "命令面板",
    ),
    bind(
        KeyScope::Browse,
        ".",
        KeyCode::Char('.'),
        KeyModifiers::NONE,
        Command::Actions,
        "actions for this item",
        "当前项操作",
    ),
    bind(
        KeyScope::Browse,
        "F5",
        KeyCode::F(5),
        KeyModifiers::NONE,
        Command::Refresh,
        "refresh workspace and reload config",
        "刷新工作区并重载配置",
    ),
    bind(
        KeyScope::Browse,
        "?",
        KeyCode::Char('?'),
        KeyModifiers::NONE,
        Command::Help,
        "help",
        "帮助",
    ),
    bind(
        KeyScope::Browse,
        "q",
        KeyCode::Char('q'),
        KeyModifiers::NONE,
        Command::Quit,
        "quit (drafts are retained)",
        "退出（草稿保留）",
    ),
    // — global —
    bind(
        KeyScope::Global,
        "Ctrl+C",
        KeyCode::Char('c'),
        KeyModifiers::CONTROL,
        Command::Quit,
        "force quit (any mode)",
        "强制退出（任意界面）",
    ),
    // — quick capture: shadows the shared field keys first —
    bind(
        KeyScope::Compose,
        "Ctrl+S",
        KeyCode::Char('s'),
        KeyModifiers::CONTROL,
        Command::Commit,
        "submit capture",
        "保存速记",
    ),
    bind(
        KeyScope::Compose,
        "Ctrl+E",
        KeyCode::Char('e'),
        KeyModifiers::CONTROL,
        Command::ExternalEdit,
        "capture in external editor",
        "在外部编辑器中编写",
    ),
    bind(
        KeyScope::Compose,
        "Ctrl+Z",
        KeyCode::Char('z'),
        KeyModifiers::CONTROL,
        Command::Edit(TextEdit::Undo),
        "undo",
        "撤销",
    ),
    bind(
        KeyScope::Compose,
        "Ctrl+Y",
        KeyCode::Char('y'),
        KeyModifiers::CONTROL,
        Command::Edit(TextEdit::Redo),
        "redo",
        "重做",
    ),
    bind(
        KeyScope::Compose,
        "Tab",
        KeyCode::Tab,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Complete),
        "complete #tag",
        "补全 #标签",
    ),
    bind(
        KeyScope::Compose,
        "Enter",
        KeyCode::Enter,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Newline),
        "insert newline",
        "换行",
    ),
    bind(
        KeyScope::Compose,
        "↑",
        KeyCode::Up,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Up),
        "move cursor up",
        "光标上移",
    ),
    bind(
        KeyScope::Compose,
        "↓",
        KeyCode::Down,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Down),
        "move cursor down",
        "光标下移",
    ),
    // — the search field adds only the mode toggle —
    bind(
        KeyScope::Search,
        "Ctrl+F",
        KeyCode::Char('f'),
        KeyModifiers::CONTROL,
        Command::ToggleSearchMode,
        "toggle fulltext / fuzzy + pinyin",
        "切换全文／模糊与拼音搜索",
    ),
    // — keys every text field shares —
    bind(
        KeyScope::Field,
        "Esc",
        KeyCode::Esc,
        KeyModifiers::NONE,
        Command::Back,
        "leave this field",
        "离开此输入框",
    ),
    bind(
        KeyScope::Field,
        "Enter",
        KeyCode::Enter,
        KeyModifiers::NONE,
        Command::Accept,
        "confirm",
        "确认",
    ),
    bind(
        KeyScope::Field,
        "↑",
        KeyCode::Up,
        KeyModifiers::NONE,
        Command::Move(-1),
        "previous entry",
        "上一项",
    ),
    bind(
        KeyScope::Field,
        "↓",
        KeyCode::Down,
        KeyModifiers::NONE,
        Command::Move(1),
        "next entry",
        "下一项",
    ),
    bind(
        KeyScope::Field,
        "←",
        KeyCode::Left,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Left),
        "cursor left",
        "光标左移",
    ),
    bind(
        KeyScope::Field,
        "→",
        KeyCode::Right,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Right),
        "cursor right",
        "光标右移",
    ),
    bind(
        KeyScope::Field,
        "Home",
        KeyCode::Home,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Home),
        "line start",
        "行首",
    ),
    bind(
        KeyScope::Field,
        "End",
        KeyCode::End,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::End),
        "line end",
        "行末",
    ),
    bind(
        KeyScope::Field,
        "Backspace",
        KeyCode::Backspace,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Backspace),
        "delete backward",
        "删除前一个字符",
    ),
    bind(
        KeyScope::Field,
        "Delete",
        KeyCode::Delete,
        KeyModifiers::NONE,
        Command::Edit(TextEdit::Delete),
        "delete forward",
        "删除后一个字符",
    ),
    // — the wizard adds field-switching keys on top of `Field` —
    bind(
        KeyScope::Setup,
        "Tab",
        KeyCode::Tab,
        KeyModifiers::NONE,
        Command::Move(1),
        "next field",
        "下一个字段",
    ),
    bind(
        KeyScope::Setup,
        "Shift+Tab",
        KeyCode::BackTab,
        KeyModifiers::NONE,
        Command::Move(-1),
        "previous field",
        "上一个字段",
    ),
    // — the confirmation dialog answers y/n —
    bind(
        KeyScope::Confirm,
        "Enter",
        KeyCode::Enter,
        KeyModifiers::NONE,
        Command::Accept,
        "confirm",
        "确认",
    ),
    bind(
        KeyScope::Confirm,
        "y",
        KeyCode::Char('y'),
        KeyModifiers::NONE,
        Command::Accept,
        "confirm",
        "确认",
    ),
    bind(
        KeyScope::Confirm,
        "Esc",
        KeyCode::Esc,
        KeyModifiers::NONE,
        Command::Back,
        "cancel",
        "取消",
    ),
    bind(
        KeyScope::Confirm,
        "n",
        KeyCode::Char('n'),
        KeyModifiers::NONE,
        Command::Back,
        "cancel",
        "取消",
    ),
    // — scrollable overlays (messages, the `?` help screen) —
    bind(
        KeyScope::Overlay,
        "Esc",
        KeyCode::Esc,
        KeyModifiers::NONE,
        Command::Back,
        "close",
        "关闭",
    ),
    bind(
        KeyScope::Overlay,
        "Enter",
        KeyCode::Enter,
        KeyModifiers::NONE,
        Command::Back,
        "close",
        "关闭",
    ),
    bind(
        KeyScope::Overlay,
        "?",
        KeyCode::Char('?'),
        KeyModifiers::NONE,
        Command::Back,
        "close",
        "关闭",
    ),
    bind(
        KeyScope::Overlay,
        "↑",
        KeyCode::Up,
        KeyModifiers::NONE,
        Command::Scroll(-1),
        "scroll up",
        "向上滚动",
    ),
    bind(
        KeyScope::Overlay,
        "k",
        KeyCode::Char('k'),
        KeyModifiers::NONE,
        Command::Scroll(-1),
        "scroll up",
        "向上滚动",
    ),
    bind(
        KeyScope::Overlay,
        "↓",
        KeyCode::Down,
        KeyModifiers::NONE,
        Command::Scroll(1),
        "scroll down",
        "向下滚动",
    ),
    bind(
        KeyScope::Overlay,
        "j",
        KeyCode::Char('j'),
        KeyModifiers::NONE,
        Command::Scroll(1),
        "scroll down",
        "向下滚动",
    ),
    bind(
        KeyScope::Overlay,
        "PgDn",
        KeyCode::PageDown,
        KeyModifiers::NONE,
        Command::Scroll(10),
        "scroll a page down",
        "向下翻页",
    ),
    bind(
        KeyScope::Overlay,
        "PgUp",
        KeyCode::PageUp,
        KeyModifiers::NONE,
        Command::Scroll(-10),
        "scroll a page up",
        "向上翻页",
    ),
];

/// `--help`'s key section.
///
/// Rendered from `KEY_BINDINGS` so the documentation cannot drift from the
/// table dispatch reads (E-16). Scopes a terminal user can reach are listed;
/// modal dialogs describe their own keys inline.
#[must_use]
pub fn keys_help() -> String {
    let s = UiStrings::detect();
    let mut lines = vec!["Keys:".to_owned()];
    for scope in [
        KeyScope::Browse,
        KeyScope::Compose,
        KeyScope::Search,
        KeyScope::Global,
    ] {
        // One help row per command, its key labels joined — the same command
        // reachable through several keys reads as one line ("j / ↓").
        let mut grouped: Vec<(&Command, Vec<&'static str>)> = Vec::new();
        for binding in KEY_BINDINGS.iter().filter(|binding| binding.scope == scope) {
            if let Some((_, labels)) = grouped
                .iter_mut()
                .find(|(command, _)| *command == &binding.command)
            {
                labels.push(binding.label);
            } else {
                grouped.push((&binding.command, vec![binding.label]));
            }
        }
        for (command, labels) in grouped {
            let blurb = KEY_BINDINGS
                .iter()
                .find(|binding| binding.scope == scope && &binding.command == command)
                .map_or("", |binding| s.text(binding.help.0, binding.help.1));
            lines.push(format!("  {:<18}{blurb}", labels.join(" / ")));
        }
    }
    lines.join("\n")
}

/// Bracketed paste targets the active text field; every mode that owns no
/// text field reports the denial instead of silently dropping the text.
#[must_use]
pub fn command_from_paste(text: String, model: &AppModel) -> Option<Command> {
    match &model.input {
        InputMode::Compose
        | InputMode::Search { .. }
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Setting(_)
        | InputMode::Setup(_) => Some(Command::Type(text)),
        InputMode::Browse
        | InputMode::Confirm(_)
        | InputMode::Message { .. }
        | InputMode::Help { .. } => Some(Command::PasteDenied),
    }
}

#[must_use]
pub fn command_from_key(key: KeyEvent, model: &AppModel) -> Option<Command> {
    if key.kind == KeyEventKind::Release {
        return None;
    }
    // Global chords outrank every mode — Ctrl+C quits from anywhere.
    if let Some(command) = bound_command(KeyScope::Global, &key) {
        return Some(command);
    }
    match &model.input {
        InputMode::Browse => bound_command(KeyScope::Browse, &key),
        // Mode-specific rows shadow the shared field keys (compose's Enter is
        // a newline, never an accept); unbound keys fall through to typing.
        InputMode::Compose => {
            bound_command(KeyScope::Compose, &key).or_else(|| field_command(&key))
        }
        InputMode::Search { .. } => {
            bound_command(KeyScope::Search, &key).or_else(|| field_command(&key))
        }
        InputMode::Date { .. } | InputMode::Picker(_) | InputMode::Setting(_) => {
            field_command(&key)
        }
        InputMode::Setup(_) => bound_command(KeyScope::Setup, &key).or_else(|| field_command(&key)),
        InputMode::Confirm(_) => bound_command(KeyScope::Confirm, &key),
        InputMode::Message { .. } | InputMode::Help { .. } => {
            bound_command(KeyScope::Overlay, &key)
        }
    }
}

/// A fixed binding row whose scope matches and whose event test passes —
/// the single lookup every key dispatch and every advertised key shares.
fn bound_command(scope: KeyScope, key: &KeyEvent) -> Option<Command> {
    KEY_BINDINGS
        .iter()
        .find(|binding| binding.scope == scope && binding.fires(key))
        .map(|binding| binding.command.clone())
}

/// The shared text-field keys — the fixed `Field` rows plus the one wildcard
/// no table can express: a plain character types itself into the field.
fn field_command(key: &KeyEvent) -> Option<Command> {
    if let Some(command) = bound_command(KeyScope::Field, key) {
        return Some(command);
    }
    if !key.modifiers.contains(KeyModifiers::CONTROL)
        && let KeyCode::Char(ch) = key.code
    {
        return Some(Command::Type(ch.to_string()));
    }
    None
}
