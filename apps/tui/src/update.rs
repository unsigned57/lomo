//! Pure state transitions.
//!
//! Every side effect carries its request identity: issuing an `Effect`
//! registers its landing intent in `model.pending`, and only a reply that
//! still names a live request may touch state.
pub use crate::effects::Effect;
use crate::effects::{EditTarget, FeedRequest, PageIntent};
use crate::event::{Availability, Command, MemoAction};
use crate::input::TextBuffer;
use crate::model::{
    AppModel, BodyState, Confirmation, FeedKind, FeedState, InputMode, LoadStatus, PaletteScope,
    Pending, PendingKind, Picker, PickerKind, Screen, View,
};

#[must_use]
pub fn apply_command(model: &mut AppModel, command: Command) -> Option<Effect> {
    // Parked receipts need the input focus; once a command leaves the model
    // back at `Browse`, the oldest parked reply is delivered — never dropped.
    // The drain fires on the *return* to `Browse`, not on the state: a queue
    // that outlives a busy phase (e.g. carried across a `RuntimeReady`
    // install) waits for the next real transition instead of popping over
    // whatever the user is already doing at `Browse`.
    let was_busy = !matches!(model.input, InputMode::Browse);
    let effect = dispatch_command(model, command);
    if was_busy {
        model.drain_parked();
    }
    effect
}

fn dispatch_command(model: &mut AppModel, command: Command) -> Option<Effect> {
    if command == Command::Quit {
        let req = model.request(PendingKind::Quit);
        return Some(Effect::Quit { req });
    }
    // I9: feedback is never erased by an unrelated command — a toast holds
    // until newer feedback replaces it, and a badge needs an explicit
    // acknowledgement (top-level Esc) or a same-class success.
    // Paste denial surfaces in every mode — fieldless modes never reach the
    // field router, so the notice is applied before the mode dispatch.
    if command == Command::PasteDenied {
        model.set_status(crate::i18n::UiStrings::detect().text(
            "Paste works only while editing text",
            "粘贴仅在文本输入时可用",
        ));
        return None;
    }
    if !matches!(model.input, InputMode::Browse) {
        return crate::input_update::apply(model, &command);
    }
    dispatch_browse(model, command)
}

/// Browse-mode commands — the menu a focused timeline/reader answers to.
///
/// Every command crosses the capability gate first (I2): the same
/// `availability` the menu rows and the hint bar project decides whether the
/// key may run — `Refused` names its reason on the status line and `Hidden`
/// can never be dispatched, so an advertised action is always dispatchable
/// and a refused one always says why.
fn dispatch_browse(model: &mut AppModel, command: Command) -> Option<Effect> {
    if matches!(gate(model, &command), Gate::Closed) {
        return None;
    }
    match command {
        Command::Compose => begin_input(model, InputStart::Compose),
        Command::Search => open_search(model),
        Command::Tags => begin_input(model, InputStart::Tags),
        Command::Palette => begin_input(model, InputStart::Palette(PaletteScope::All)),
        Command::Actions => begin_input(model, InputStart::Palette(PaletteScope::Item)),
        Command::Date => begin_input(model, InputStart::Date),
        Command::CustomDate => begin_input(model, InputStart::CustomDate),
        Command::SetDate(text) => {
            // A preset is an invisible pending request — the custom-date dialog
            // never opens, so no visible input claims the reply.
            let req = model.request(PendingKind::Date { dialog: false });
            Some(Effect::Date { req, text })
        }
        Command::RemoveKeyword | Command::RemoveDate => remove_filter(model, &command),
        Command::ShowNotice => begin_input(model, InputStart::Notice),
        Command::Help => begin_input(model, InputStart::Help),
        Command::Back => go_back(model),
        Command::Accept => crate::navigation::open_selected(model),
        Command::Move(_)
        | Command::Scroll(_)
        | Command::Page(_)
        | Command::First
        | Command::Last => browse_navigate(model, &command),
        Command::Click(column, row) => crate::navigation::click(model, column, row),
        Command::Goto(screen) => Some(navigate(model, screen)),
        Command::ToggleSearchMode => toggle_mode(model),
        Command::SelectTag(tag) => change_tag(model, tag.as_ref()),
        Command::ClearFilters => clear_filters(model),
        Command::ExternalEdit => edit_selected(model),
        Command::Pin
        | Command::Delete
        | Command::DeleteForever
        | Command::Restore
        | Command::History
        | Command::Attachments => {
            let Some(action) = command.memo_action() else {
                unreachable!("the arm only lists memo actions");
            };
            let Some(memo) = model.selected_memo().cloned() else {
                unreachable!("the gate refused a memo action without a selection");
            };
            apply_to_memo(model, &memo, action)
        }
        Command::EmptyTrash => {
            // The gate restricted this to trash-in-scope contexts: the trash
            // feed itself, or a trashed memo's reader — its existence is
            // evidence the trash holds entries (A-05).
            model.input = InputMode::Confirm(Confirmation::EmptyTrash {
                count: trash_count(model),
            });
            None
        }
        Command::OpenMemo(id) => {
            let req = model.request(PendingKind::OpenMemo);
            model.push_view(View::Loading {
                screen: Screen::Timeline,
                req,
            });
            Some(Effect::ReadMemo { req, id })
        }
        Command::ToggleTask => Some(toggle_task(model)),
        Command::ImportClipboard => {
            let req = model.request(PendingKind::Mutation);
            Some(Effect::ImportClipboard { req })
        }
        Command::OpenAttachment(path) => {
            let req = model.request(PendingKind::Attachment);
            Some(Effect::OpenAttachment { req, path })
        }
        Command::DiscardDraft => {
            // The gate already proved the draft holds content — carry its
            // first line so the dialog names what it drops.
            model.input = InputMode::Confirm(Confirmation::DiscardDraft {
                preview: crate::menu::preview(model.draft.text.text()),
            });
            None
        }
        Command::Refresh => {
            let req = model.request(PendingKind::Maintenance);
            Some(Effect::Refresh { req })
        }
        Command::ShowCreated => Some(show_created(model)),
        Command::PasteDenied
        | Command::ToggleTagScope
        | Command::RestoreRevision(_)
        | Command::DismissPicker
        | Command::Quit
        | Command::Commit
        | Command::Type(_)
        | Command::Edit(_) => {
            unreachable!("the capability gate never lets a hidden command through")
        }
    }
}

/// The gate's verdict: `Refused` writes its reason to the status line and
/// closes; `Hidden` just closes — a hidden command is not an action the
/// state refused, it is one that does not exist here.
enum Gate {
    Open,
    Closed,
}

fn gate(model: &mut AppModel, command: &Command) -> Gate {
    match command.availability(model) {
        Availability::Ready => Gate::Open,
        Availability::Refused(reason) => {
            model.set_status(reason.text());
            Gate::Closed
        }
        Availability::Hidden => Gate::Closed,
    }
}

/// Browse-view movement — one path shared by direct keys and the search
/// field's pass-through (A-04).
///
/// The verdict is re-checked here so a stale caller can never move a
/// selection that does not exist, silently.
pub fn browse_navigate(model: &mut AppModel, command: &Command) -> Option<Effect> {
    if matches!(gate(model, command), Gate::Closed) {
        return None;
    }
    if let Command::Move(delta) = command {
        return crate::navigation::move_selection(model, *delta);
    }
    if matches!(command, Command::First) {
        crate::navigation::first(model);
        return None;
    }
    // `Move`/`First` were returned above; every remaining movement command is
    // a scroll of some stride — `Last` is one giant stride to the bottom.
    let step = if let Command::Scroll(delta) = command {
        *delta
    } else if let Command::Page(delta) = command {
        delta.saturating_mul(page_step(model))
    } else if matches!(command, Command::Last) {
        i32::MAX
    } else {
        unreachable!("browse_navigate only handles movement commands")
    };
    crate::navigation::scroll(model, step);
    request_more(model)
}

/// Task toggling only exists in the task view — the gate already verified
/// the view and the selection.
fn toggle_task(model: &mut AppModel) -> Effect {
    let View::Tasks(list) = &model.view else {
        unreachable!("the gate refused ToggleTask outside the task list");
    };
    let Some(task) = list.items.get(list.selected).cloned() else {
        unreachable!("the gate checked the selection");
    };
    let req = model.request(PendingKind::Mutation);
    Effect::ToggleTask { req, task }
}

/// One page of scroll in reader rows or feed content rows — the reader area
/// is the content area minus its two-row margin, no page build needed.
fn page_step(model: &AppModel) -> i32 {
    let content = crate::ui::layout_for(model).content;
    let height = if matches!(model.view, View::Reader { .. }) {
        content.height.saturating_sub(2)
    } else {
        content.height
    };
    i32::from(height.saturating_sub(1).max(1))
}

/// Focus regain is a system signal, not a user command (A-09).
///
/// The host calls this directly so it never clears the status line,
/// opens/closes inputs, or resets picker state. Watching is event-driven;
/// focus only surfaces an outage and the manual fallback — it never rebuilds
/// the projection on its own.
pub fn focus_reconcile(model: &mut AppModel) -> Option<Effect> {
    if model.watcher_active {
        return None;
    }
    let text = crate::i18n::UiStrings::detect().text(
        "File watching unavailable; press F5 to refresh",
        "文件监视不可用，按 F5 手动刷新",
    );
    if model.status.as_deref() != Some(text) {
        model.set_status(text);
    }
    None
}

/// How often a dead watcher's fallback re-reconciles — low-rate background
/// maintenance, never interactive cadence.
pub const WATCHER_FALLBACK_INTERVAL: std::time::Duration = std::time::Duration::from_secs(15);

/// Watcher-death fallback polled by the host (F-07).
///
/// While `dead` is set, two things stand in for observation: a reconcile
/// issued at `WATCHER_FALLBACK_INTERVAL` so the projection cannot go
/// permanently stale, and an outage hint re-armed whenever the status slot
/// clears so the degradation cannot be silently erased (the persistent badge
/// is the I9 seam — this is a status-line trace, not a notification layer).
/// `last` is the host's dispatch clock: `None` arms an immediate first
/// reconcile, recovery resets it so a later outage stands in immediately.
pub fn watcher_fallback(
    model: &mut AppModel,
    dead: bool,
    last: &mut Option<std::time::Instant>,
    now: std::time::Instant,
) -> Option<Effect> {
    if !dead {
        *last = None;
        return None;
    }
    if model.status.is_none() {
        model.set_status(crate::i18n::UiStrings::detect().text(
            "File watching stopped — auto-refreshing",
            "文件监视已停止——自动刷新中",
        ));
    }
    let due =
        last.is_none_or(|stamp| now.saturating_duration_since(stamp) >= WATCHER_FALLBACK_INTERVAL);
    if !due {
        return None;
    }
    *last = Some(now);
    let req = model.request(PendingKind::Maintenance);
    // A dead watcher attests nothing — the fallback runs the full scan.
    Some(Effect::Reconcile {
        req,
        observed: None,
    })
}

fn picker(model: &mut AppModel, kind: PickerKind) {
    let mut picker = Picker {
        kind,
        text: TextBuffer::default(),
        selected: 0,
        identity: None,
    };
    // Stamp the selection identity immediately — the mark and Enter then
    // name the same row for the picker's whole life.
    picker.rebind(&crate::menu::entries(model, &picker));
    model.input = InputMode::Picker(picker);
}
#[derive(Clone, Copy)]
enum InputStart {
    Compose,
    Tags,
    Palette(PaletteScope),
    Date,
    CustomDate,
    Notice,
    Help,
}

fn begin_input(model: &mut AppModel, start: InputStart) -> Option<Effect> {
    match start {
        InputStart::Compose => {
            model.input = InputMode::Compose;
            return Some(tag_request(model));
        }
        InputStart::Tags => {
            picker(
                model,
                PickerKind::Tags(timeline_context(model).query.filters.tag_selection),
            );
            return Some(tag_request(model));
        }
        InputStart::Palette(scope) => {
            let item = model.palette_item();
            picker(model, PickerKind::Palette { item, scope });
        }
        InputStart::Date => picker(model, PickerKind::Dates),
        InputStart::CustomDate => {
            model.input = InputMode::Date {
                req: None,
                text: TextBuffer::default(),
                error: None,
            }
        }
        InputStart::Notice => {
            let Some(notice) = model.notice.clone() else {
                unreachable!("the gate refused ShowNotice without a notice");
            };
            model.input = InputMode::Message {
                title: notice.title,
                lines: notice.lines,
                scroll: 0,
            };
            // The registered notice is being read — its unread mark retires.
            model.clear_badges(crate::model::BadgeClass::Notice);
        }
        InputStart::Help => model.input = InputMode::Help { scroll: 0 },
    }
    None
}

/// A newer tag-dictionary load supersedes any still in flight — the latest
/// reply wins and the superseded one degrades on arrival.
fn tag_request(model: &mut AppModel) -> Effect {
    model
        .pending
        .cancel_matching(|kind| matches!(kind, PendingKind::Tags));
    let req = model.request(PendingKind::Tags);
    Effect::Tags { req }
}

/// Search always edits the timeline query; any other view is kept underneath for Esc.
fn open_search(model: &mut AppModel) -> Option<Effect> {
    ensure_feed(model);
    let View::Feed(feed) = &model.view else {
        return None;
    };
    let load = matches!(feed.load, LoadStatus::Loading | LoadStatus::Stale);
    model.input = InputMode::Search {
        text: TextBuffer::new(feed.query.text.clone()),
    };
    if load { reload_feed(model) } else { None }
}

fn timeline_context(model: &AppModel) -> FeedState {
    std::iter::once(&model.view)
        .chain(model.history.iter().rev())
        .find_map(|view| {
            if let View::Feed(feed) = view
                && feed.kind == FeedKind::Timeline
            {
                Some((**feed).clone())
            } else {
                None
            }
        })
        .unwrap_or_else(|| FeedState::new(FeedKind::Timeline))
}

pub fn ensure_feed(model: &mut AppModel) {
    if !matches!(&model.view, View::Feed(feed) if feed.kind == FeedKind::Timeline) {
        model.push_view(View::Feed(Box::new(timeline_context(model))));
    }
}
#[must_use]
pub fn reload_feed(model: &mut AppModel) -> Option<Effect> {
    let intent = {
        let View::Feed(feed) = &model.view else {
            return None;
        };
        if feed.memos.is_empty() {
            PageIntent::Initial
        } else {
            // The refresh window re-reads around the anchor — the visually
            // earliest identity leads so `covers` can pull the page that
            // still holds the selection. `known` is the loaded membership the
            // reply orders its `order` evidence against.
            PageIntent::Refresh {
                anchors: feed
                    .anchor
                    .iter()
                    .map(|anchor| anchor.id.clone())
                    .chain(feed.selected.iter().cloned())
                    .collect(),
                known: feed.memos.iter().map(|memo| memo.id.clone()).collect(),
            }
        }
    };
    issue_query(model, intent)
}
/// A changed query starts over from the first page while the previous results stay on screen.
#[must_use]
pub fn requery(model: &mut AppModel) -> Option<Effect> {
    issue_query(model, PageIntent::Initial)
}

/// One request owns one feed's page lifecycle (F-02/F-12).
///
/// Issuing cancels the feed's previous outstanding request and records the
/// new `req` as `pending_page`, so a reply can only land on the feed still
/// awaiting it.
pub fn issue_query(model: &mut AppModel, intent: PageIntent) -> Option<Effect> {
    let previous = {
        let View::Feed(feed) = &mut model.view else {
            return None;
        };
        feed.pending_page.take()
    };
    if let Some(old) = previous {
        model.pending.cancel(old);
    }
    let req = model.request(PendingKind::FeedPage);
    let View::Feed(feed) = &mut model.view else {
        return None;
    };
    feed.pending_page = Some(req);
    feed.load = LoadStatus::Loading;
    Some(Effect::Query(FeedRequest {
        req,
        kind: feed.kind,
        query: feed.query.clone(),
        intent,
    }))
}
#[must_use]
pub fn search_changed(model: &mut AppModel) -> Option<Effect> {
    let InputMode::Search { text } = &model.input else {
        return None;
    };
    let query = text.text().to_owned();
    if let View::Feed(feed) = &mut model.view {
        if feed.query.text == query {
            return None;
        }
        feed.remember_unfiltered();
        feed.query.text = query;
        feed.mark_requery();
    }
    query_changed(model)
}
pub fn toggle_mode(model: &mut AppModel) -> Option<Effect> {
    let View::Feed(feed) = &mut model.view else {
        return None;
    };
    feed.query.mode = match feed.query.mode {
        lomo_application::SearchMode::Fulltext => lomo_application::SearchMode::Fuzzy,
        lomo_application::SearchMode::Fuzzy => lomo_application::SearchMode::Fulltext,
    };
    if feed.query.text.is_empty() {
        return None;
    }
    feed.mark_requery();
    requery(model)
}
fn navigate(model: &mut AppModel, screen: Screen) -> Effect {
    let req = model.request(PendingKind::Navigate);
    let placeholder = View::Loading { screen, req };
    if let View::Loading { req: old, .. } = &model.view {
        // Navigating over a pending placeholder supersedes its request in
        // place — a dead placeholder never enters history.
        let old = *old;
        model.pending.cancel(old);
        model.view = placeholder;
    } else {
        model.push_view(placeholder);
    }
    Effect::Navigate { req, screen }
}
/// What a restored view needs once it returns to the front of the stack.
enum Restored {
    Nothing,
    ReloadFeed,
    ReNavigate(Screen),
}

/// Esc peels one layer: an active filter first, then the view underneath.
fn go_back(model: &mut AppModel) -> Option<Effect> {
    if matches!(&model.view, View::Feed(feed) if feed.query.is_filtered()) {
        return clear_filters(model);
    }
    let Some(view) = model.history.pop() else {
        // Reachable only while feedback is armed — the capability gate
        // refuses a root Esc otherwise. Here Esc is the acknowledgement
        // gesture: the user has seen the marks, so they leave with the
        // toast (I9).
        debug_assert!(
            !model.badges.is_empty() || model.status.is_some(),
            "the gate refused Back with an empty stack and no feedback"
        );
        model.acknowledge_feedback();
        return None;
    };
    // The abandoned view releases its live requests — their replies degrade
    // instead of landing on state the user already left.
    retire_view(&mut model.view, &mut model.pending);
    model.view = view;
    // A restored view keeps its honest lifecycle: a stale or dead load reloads,
    // a live load keeps awaiting its reply. Nothing is rewritten to Ready
    // (F-03) — a wedged `Loading` is surfaced by reissuing its request.
    let restored = match &model.view {
        View::Feed(feed) => {
            let dead_load = feed.load == LoadStatus::Loading
                && !feed
                    .pending_page
                    .is_some_and(|req| model.pending.contains(req));
            if feed.load == LoadStatus::Stale || dead_load {
                Restored::ReloadFeed
            } else {
                Restored::Nothing
            }
        }
        View::Loading { screen, req } if !model.pending.contains(*req) => {
            Restored::ReNavigate(*screen)
        }
        View::Loading { .. }
        | View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Failed { .. } => Restored::Nothing,
    };
    match restored {
        Restored::ReloadFeed => reload_feed(model),
        Restored::ReNavigate(screen) => {
            let req = model.request(PendingKind::Navigate);
            model.view = View::Loading { screen, req };
            Some(Effect::Navigate { req, screen })
        }
        Restored::Nothing => None,
    }
}

/// A dropped view's live intents are revoked so its receipts degrade instead
/// of landing on a destroyed slot.
fn retire_view(view: &mut View, pending: &mut Pending) {
    match view {
        View::Feed(feed) => {
            if let Some(req) = feed.pending_page.take() {
                pending.cancel(req);
            }
        }
        View::Loading { req, .. } => {
            pending.cancel(*req);
        }
        View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Failed { .. } => {}
    }
}
fn change_tag(model: &mut AppModel, tag: Option<&std::sync::Arc<str>>) -> Option<Effect> {
    select_tag(
        model,
        tag.map(|name| crate::model::TagConstraint {
            name: name.to_string(),
            scope: lomo_application::TagSelectionMode::Exact,
        }),
    )
}

pub fn select_tag(
    model: &mut AppModel,
    tag: Option<crate::model::TagConstraint>,
) -> Option<Effect> {
    ensure_feed(model);
    if let View::Feed(feed) = &mut model.view {
        feed.remember_unfiltered();
        if let Some(tag) = tag {
            feed.query.filters.tag = Some(tag.name);
            feed.query.filters.tag_selection = tag.scope;
        } else {
            feed.query.filters.tag = None;
            feed.query.filters.tag_selection = lomo_application::TagSelectionMode::Exact;
        }
        feed.mark_requery();
    }
    query_changed(model)
}
fn clear_filters(model: &mut AppModel) -> Option<Effect> {
    ensure_feed(model);
    if let View::Feed(feed) = &mut model.view {
        if let Some(original) = feed.unfiltered.take() {
            // The filtered page request retires with the filter; the restored
            // position re-reads a bounded window around its anchor instead of
            // resurrecting a card snapshot.
            let filtered_req = feed.pending_page.take();
            feed.query = original.query;
            feed.selected = original.selected;
            feed.anchor = original.anchor;
            if let Some(old) = filtered_req {
                model.pending.cancel(old);
            }
            feed.load = LoadStatus::Stale;
            return reload_feed(model);
        }
        feed.query = crate::model::FeedQuery::default();
    }
    reload_feed(model)
}
fn request_more(model: &mut AppModel) -> Option<Effect> {
    crate::navigation::maybe_next_page(model)
}
fn edit_selected(model: &mut AppModel) -> Option<Effect> {
    // `e` on the Settings screen edits config.toml itself — the draft is
    // strictly validated before it may replace the file.
    if matches!(model.view, View::Settings(_)) {
        let req = model.next_req();
        return Some(Effect::Edit {
            req,
            target: EditTarget::Config,
        });
    }
    let Some(memo) = model.selected_memo().cloned() else {
        unreachable!("the gate refused ExternalEdit without a selection");
    };
    editor_for_memo(model, &memo)
}
/// One memo-scoped action against a concrete memo.
///
/// The memo is the palette's frozen item or the selection behind the direct
/// key. The verdict comes from the action's availability against *this*
/// memo — never the selection, which a palette deliberately ignores — so an
/// action the state would refuse never dies silently (I2).
pub fn apply_to_memo(
    model: &mut AppModel,
    memo: &crate::model::MemoCard,
    action: MemoAction,
) -> Option<Effect> {
    match action.availability(model, memo) {
        Availability::Ready => {}
        Availability::Refused(reason) => {
            model.set_status(reason.text());
            return None;
        }
        Availability::Hidden => return None,
    }
    match action {
        MemoAction::Read => {
            model.push_view(View::Reader {
                memo: memo.clone(),
                anchor: crate::model::TextAnchor::default(),
            });
            None
        }
        MemoAction::Edit => editor_for_memo(model, memo),
        MemoAction::Attachments => {
            picker(model, PickerKind::Attachments(Box::new(memo.clone())));
            None
        }
        MemoAction::Pin => {
            let req = model.request(PendingKind::Mutation);
            Some(Effect::Pin {
                req,
                id: memo.id.clone(),
                pinned: !memo.pinned,
            })
        }
        MemoAction::History => {
            let req = model.request(PendingKind::History {
                id: memo.id.clone(),
            });
            Some(Effect::History {
                req,
                id: memo.id.clone(),
            })
        }
        // One key, two confirmations: a live memo goes to the trash, a trashed
        // memo faces the permanent delete — the prompt names which. The
        // frozen card travels with the dialog so the overlay can name its
        // target (I9) while acceptance still works on the card's identity.
        MemoAction::Delete if memo.trashed => {
            model.input = InputMode::Confirm(Confirmation::DeleteForever(Box::new(memo.clone())));
            None
        }
        MemoAction::Delete => {
            model.input = InputMode::Confirm(Confirmation::Delete {
                memo: Box::new(memo.clone()),
            });
            None
        }
        MemoAction::DeleteForever => {
            model.input = InputMode::Confirm(Confirmation::DeleteForever(Box::new(memo.clone())));
            None
        }
        MemoAction::Restore => {
            model.input = InputMode::Confirm(Confirmation::Restore(Box::new(memo.clone())));
            None
        }
    }
}

/// How many memos the trash sweep will remove, if a loaded trash feed can
/// prove it — the current view first, then any suspended one in history.
/// `None` is honest: the trash is non-empty (a trashed memo's reader proves
/// that) but its depth is unknown.
fn trash_count(model: &AppModel) -> Option<u64> {
    std::iter::once(&model.view)
        .chain(model.history.iter())
        .find_map(|view| match view {
            View::Feed(feed) if feed.kind == FeedKind::Trash => Some(
                feed.total
                    .unwrap_or_else(|| u64::try_from(feed.memos.len()).unwrap_or(u64::MAX)),
            ),
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

fn editor_for_memo(model: &mut AppModel, memo: &crate::model::MemoCard) -> Option<Effect> {
    match &memo.body {
        BodyState::Ready(body) => {
            let body = body.as_str().to_owned();
            let req = model.next_req();
            Some(Effect::Edit {
                req,
                target: EditTarget::Memo {
                    id: memo.id.clone(),
                    fingerprint: memo.fingerprint.clone(),
                    body,
                },
            })
        }
        BodyState::Failed(error) => {
            model.set_status(error);
            None
        }
        BodyState::Pending | BodyState::Loading { .. } => {
            model.set_status(
                crate::i18n::UiStrings::detect().text("Loading memo body…", "正在加载正文…"),
            );
            crate::navigation::hydrate_visible(model)
        }
    }
}
fn show_created(model: &mut AppModel) -> Effect {
    let Some(id) = model.last_created.clone() else {
        unreachable!("the gate refused ShowCreated with nothing saved");
    };
    let req = model.request(PendingKind::OpenMemo);
    model.push_view(View::Loading {
        screen: Screen::Timeline,
        req,
    });
    Effect::ReadMemo { req, id }
}
pub fn apply_resize(model: &mut AppModel, width: u16, height: u16) {
    model.width = width;
    model.height = height;
    // Image requests carry their sampling geometry: hydration invalidates only
    // the requests that actually changed instead of blanking the cache.
    let layout = crate::ui::layout_for(model);
    if model.input == InputMode::Browse
        && let View::Feed(feed) = &mut model.view
    {
        crate::feed_layout::ensure_selected_visible(
            feed,
            layout.content.width,
            layout.content.height,
        );
    }
}

fn query_changed(model: &mut AppModel) -> Option<Effect> {
    if matches!(&model.view, View::Feed(feed) if !feed.query.is_filtered() && feed.unfiltered.is_some())
    {
        clear_filters(model)
    } else {
        requery(model)
    }
}

fn remove_filter(model: &mut AppModel, command: &Command) -> Option<Effect> {
    ensure_feed(model);
    if let View::Feed(feed) = &mut model.view {
        if command == &Command::RemoveKeyword {
            feed.query.text.clear();
        } else {
            feed.query.filters.date_from_inclusive_ms = None;
            feed.query.filters.date_until_exclusive_ms = None;
            feed.query.date_label = None;
        }
        feed.mark_requery();
    }
    query_changed(model)
}
