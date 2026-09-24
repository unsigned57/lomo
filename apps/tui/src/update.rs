//! Pure state transitions. Every side effect carries its target rather than consulting later selection.
pub use crate::effects::Effect;
use crate::effects::{EditTarget, FeedRequest, PageIntent};
use crate::event::{Command, MemoAction};
use crate::input::TextBuffer;
use crate::model::{
    AppModel, BodyState, Confirmation, FeedKind, FeedState, InputMode, LoadStatus, PaletteItem,
    PaletteScope, Picker, PickerKind, Screen, View,
};

#[must_use]
pub fn apply_command(model: &mut AppModel, command: Command) -> Option<Effect> {
    if command == Command::Quit {
        return Some(Effect::Quit);
    }
    // A status message is transient: it stays until the next user command, then the
    // context key hints return.
    model.status = None;
    if !matches!(model.input, InputMode::Browse) {
        return crate::input_update::apply(model, &command);
    }
    match command {
        Command::Compose => begin_input(model, InputStart::Compose),
        Command::Search => open_search(model),
        Command::Tags => begin_input(model, InputStart::Tags),
        Command::Palette => begin_input(model, InputStart::Palette(PaletteScope::All)),
        Command::Actions => begin_input(model, InputStart::Palette(PaletteScope::Item)),
        Command::Attachments => begin_input(model, InputStart::Attachments),
        Command::Date => begin_input(model, InputStart::Date),
        Command::CustomDate => begin_input(model, InputStart::CustomDate),
        Command::SetDate(text) => Some(request_date(model, text)),
        Command::RemoveKeyword | Command::RemoveDate => remove_filter(model, &command),
        Command::ShowNotice => begin_input(model, InputStart::Notice),
        Command::Help => begin_input(model, InputStart::Help),
        Command::Back => go_back(model),
        Command::Accept => crate::navigation::open_selected(model),
        Command::Move(delta) => crate::navigation::move_selection(model, delta),
        Command::Scroll(delta) => {
            crate::navigation::scroll(model, delta);
            request_more(model)
        }
        Command::Page(delta) => {
            let step = page_step(model);
            crate::navigation::scroll(model, delta.saturating_mul(step));
            request_more(model)
        }
        Command::First => {
            crate::navigation::first(model);
            None
        }
        Command::Last => {
            crate::navigation::scroll(model, i32::MAX);
            request_more(model)
        }
        Command::Click(column, row) => crate::navigation::click(model, column, row),
        Command::Goto(screen) => Some(navigate(model, screen)),
        Command::ToggleSearchMode => toggle_mode(model),
        Command::SelectTag(tag) => change_tag(model, tag),
        Command::ClearFilters => clear_filters(model),
        Command::ExternalEdit => edit_selected(model),
        Command::Pin
        | Command::Delete
        | Command::DeleteForever
        | Command::Restore
        | Command::History => apply_to_memo(
            model,
            &model.selected_memo()?.clone(),
            command.memo_action()?,
        ),
        Command::EmptyTrash => {
            let trash = matches!(&model.view, View::Feed(feed) if feed.kind == FeedKind::Trash);
            if trash {
                model.input = InputMode::Confirm(Confirmation::EmptyTrash);
            }
            None
        }
        Command::OpenMemo(id) => {
            let epoch = model.next_epoch();
            model.push_view(View::Loading(Screen::Timeline));
            Some(Effect::ReadMemo { epoch, id })
        }
        Command::ToggleTask => toggle_task(model),
        Command::ImportClipboard => Some(Effect::ImportClipboard),
        Command::OpenAttachment(path) => Some(Effect::OpenAttachment(path)),
        Command::DiscardDraft => {
            model.input = InputMode::Confirm(Confirmation::DiscardDraft);
            None
        }
        Command::Refresh => Some(Effect::Refresh),
        Command::FocusReconcile => focus_reconcile(model),
        Command::PasteDenied => {
            model.set_status(crate::i18n::UiStrings::detect().text(
                "Paste works only while editing text",
                "粘贴仅在文本输入时可用",
            ));
            None
        }
        Command::ShowCreated => show_created(model),
        Command::ToggleTagScope
        | Command::RestoreRevision(_)
        | Command::Quit
        | Command::Commit
        | Command::Type(_)
        | Command::Edit(_) => None,
    }
}
/// Task toggling only exists in the task view.
fn toggle_task(model: &AppModel) -> Option<Effect> {
    match &model.view {
        View::Tasks(list) => list
            .items
            .get(list.selected)
            .cloned()
            .map(Effect::ToggleTask),
        View::Feed(_)
        | View::Reader { .. }
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. } => None,
    }
}

/// One page of scroll in reader rows or feed content rows.
fn page_step(model: &AppModel) -> i32 {
    let height = crate::reader::page(model).map_or_else(
        || crate::ui::layout_for(model).content.height,
        |page| page.area.height,
    );
    i32::from(height.saturating_sub(1).max(1))
}

/// Watching is event-driven; focus only surfaces an outage and the manual
/// fallback. It never rebuilds the projection on its own.
fn focus_reconcile(model: &mut AppModel) -> Option<Effect> {
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

fn picker(model: &mut AppModel, kind: PickerKind) {
    model.input = InputMode::Picker(Picker {
        kind,
        text: TextBuffer::default(),
        selected: 0,
    });
}
#[derive(Clone, Copy)]
enum InputStart {
    Compose,
    Tags,
    Palette(PaletteScope),
    Attachments,
    Date,
    CustomDate,
    Notice,
    Help,
}

fn begin_input(model: &mut AppModel, start: InputStart) -> Option<Effect> {
    match start {
        InputStart::Compose => {
            model.input = InputMode::Compose;
            return Some(Effect::Tags);
        }
        InputStart::Tags => {
            picker(
                model,
                PickerKind::Tags(timeline_context(model).query.filters.tag_selection),
            );
            return Some(Effect::Tags);
        }
        InputStart::Palette(scope) => {
            let item = model.palette_item();
            if scope == PaletteScope::Item && item == PaletteItem::None {
                return None;
            }
            picker(model, PickerKind::Palette { item, scope });
        }
        InputStart::Attachments => picker(
            model,
            PickerKind::Attachments(Box::new(model.selected_memo()?.clone())),
        ),
        InputStart::Date => picker(model, PickerKind::Dates),
        InputStart::CustomDate => {
            model.input = InputMode::Date {
                ticket: model.next_ticket(),
                text: TextBuffer::default(),
                error: None,
            }
        }
        InputStart::Notice => {
            if let Some(notice) = &model.notice {
                model.input = InputMode::Message {
                    title: notice.title.clone(),
                    lines: notice.lines.clone(),
                    scroll: 0,
                };
            }
        }
        InputStart::Help => model.input = InputMode::Help { scroll: 0 },
    }
    None
}

fn request_date(model: &mut AppModel, text: String) -> Effect {
    let ticket = model.next_ticket();
    model.input = InputMode::Date {
        ticket,
        text: TextBuffer::new(text.clone()),
        error: None,
    };
    Effect::Date { ticket, text }
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
                Some(feed.clone())
            } else {
                None
            }
        })
        .unwrap_or_else(|| FeedState::new(FeedKind::Timeline))
}

pub fn ensure_feed(model: &mut AppModel) {
    if !matches!(&model.view, View::Feed(feed) if feed.kind == FeedKind::Timeline) {
        model.push_view(View::Feed(timeline_context(model)));
    }
}
#[must_use]
pub fn reload_feed(model: &mut AppModel) -> Option<Effect> {
    if !matches!(model.view, View::Feed(_)) {
        return None;
    }
    let epoch = model.next_epoch();
    let View::Feed(feed) = &mut model.view else {
        return None;
    };
    feed.epoch = epoch;
    feed.load = LoadStatus::Loading;
    let intent = if feed.memos.is_empty() {
        PageIntent::Initial
    } else {
        PageIntent::Refresh {
            loaded: feed.memos.len(),
            anchors: feed
                .selected
                .iter()
                .cloned()
                .chain(feed.anchor.iter().map(|anchor| anchor.id.clone()))
                .collect(),
        }
    };
    Some(Effect::Query(FeedRequest {
        epoch,
        kind: feed.kind,
        query: feed.query.clone(),
        intent,
    }))
}
/// A changed query starts over from the first page while the previous results stay on screen.
#[must_use]
pub fn requery(model: &mut AppModel) -> Option<Effect> {
    let epoch = model.next_epoch();
    let View::Feed(feed) = &mut model.view else {
        return None;
    };
    feed.epoch = epoch;
    feed.load = LoadStatus::Loading;
    Some(Effect::Query(FeedRequest {
        epoch,
        kind: feed.kind,
        query: feed.query.clone(),
        intent: PageIntent::Initial,
    }))
}
#[must_use]
pub fn search_changed(model: &mut AppModel) -> Option<Effect> {
    let InputMode::Search { text } = &model.input else {
        return None;
    };
    let query = text.text().trim().to_owned();
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
    let epoch = model.next_epoch();
    model.push_view(View::Loading(screen));
    Effect::Navigate { epoch, screen }
}
/// Esc peels one layer: an active filter first, then the view underneath.
fn go_back(model: &mut AppModel) -> Option<Effect> {
    if matches!(&model.view, View::Feed(feed) if feed.query.is_filtered()) {
        return clear_filters(model);
    }
    if let Some(view) = model.history.pop() {
        model.next_epoch();
        model.view = view;
        if let View::Feed(feed) = &mut model.view {
            feed.epoch = model.epoch;
            if feed.load == LoadStatus::Loading {
                feed.load = LoadStatus::Ready;
            }
        }
        if matches!(&model.view, View::Feed(feed) if feed.load == LoadStatus::Stale) {
            return reload_feed(model);
        }
        return None;
    }
    None
}
fn change_tag(model: &mut AppModel, tag: Option<String>) -> Option<Effect> {
    select_tag(
        model,
        tag.map(|name| crate::model::TagConstraint {
            name,
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
    let epoch = model.next_epoch();
    if let View::Feed(feed) = &mut model.view {
        if let Some(original) = feed.unfiltered.take() {
            *feed = *original;
            feed.epoch = epoch;
            if feed.load == LoadStatus::Stale {
                return reload_feed(model);
            }
            return None;
        }
        feed.query = crate::model::FeedQuery::default();
    }
    reload_feed(model)
}
fn request_more(model: &mut AppModel) -> Option<Effect> {
    if let View::Feed(feed) = &mut model.view {
        crate::navigation::maybe_next_page(feed)
    } else {
        None
    }
}
fn edit_selected(model: &mut AppModel) -> Option<Effect> {
    editor_for_memo(model, &model.selected_memo()?.clone())
}
pub fn apply_to_memo(
    model: &mut AppModel,
    memo: &crate::model::MemoCard,
    action: MemoAction,
) -> Option<Effect> {
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
        MemoAction::Pin if !memo.trashed => Some(Effect::Pin {
            id: memo.id.clone(),
            pinned: !memo.pinned,
        }),
        MemoAction::History => Some(Effect::History(memo.id.clone())),
        MemoAction::Delete if !memo.trashed => {
            model.input = InputMode::Confirm(Confirmation::Delete {
                id: memo.id.clone(),
                fingerprint: memo.fingerprint.clone(),
            });
            None
        }
        // In the trash, `d` means the only delete that is left: permanent.
        MemoAction::Delete | MemoAction::DeleteForever if memo.trashed => {
            model.input = InputMode::Confirm(Confirmation::DeleteForever(memo.id.clone()));
            None
        }
        MemoAction::Restore if memo.trashed => {
            model.input = InputMode::Confirm(Confirmation::Restore(memo.id.clone()));
            None
        }
        MemoAction::Pin | MemoAction::Delete | MemoAction::DeleteForever | MemoAction::Restore => {
            None
        }
    }
}

fn editor_for_memo(model: &mut AppModel, memo: &crate::model::MemoCard) -> Option<Effect> {
    if memo.trashed {
        return None;
    }
    match &memo.body {
        BodyState::Ready(body) => Some(Effect::Edit(EditTarget::Memo {
            id: memo.id.clone(),
            fingerprint: memo.fingerprint.clone(),
            body: body.as_str().to_owned(),
        })),
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
fn show_created(model: &mut AppModel) -> Option<Effect> {
    let id = model.last_created.clone()?;
    let epoch = model.next_epoch();
    model.push_view(View::Loading(Screen::Timeline));
    Some(Effect::ReadMemo { epoch, id })
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
