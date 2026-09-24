//! Runtime replies change only the input, query or content version that requested them.
use crate::effects::{BodyReply, Effect, FailureTarget, RuntimeMessage};
use crate::model::{
    AppModel, BodyState, CardPosition, Composer, InputMode, LoadStatus, MemoAnchor, MemoCard,
    SaveState, TextAnchor, View,
};

#[must_use]
pub fn apply_message(model: &mut AppModel, message: RuntimeMessage) -> Option<Effect> {
    match message {
        RuntimeMessage::QuitReady => None,
        RuntimeMessage::Image { request, result } => {
            crate::graphics::apply_image(model, &request, result);
            None
        }
        RuntimeMessage::View { epoch, view } => {
            if epoch == model.epoch {
                model.view = *view;
            }
            None
        }
        RuntimeMessage::ReadMemo { epoch, memo } => {
            if epoch == model.epoch {
                let anchor = match &model.view {
                    View::Reader { memo: old, anchor } if old.id == memo.id => *anchor,
                    View::Feed(_)
                    | View::Reader { .. }
                    | View::Tasks(_)
                    | View::Statistics(_)
                    | View::Attachments(_)
                    | View::Settings(_)
                    | View::Loading(_)
                    | View::Failed { .. } => TextAnchor::default(),
                };
                model.view = View::Reader {
                    memo: *memo,
                    anchor,
                };
            }
            None
        }
        RuntimeMessage::Page {
            epoch,
            append,
            cards,
            next,
            total,
        } => {
            if epoch == model.epoch {
                apply_page(model, epoch, append, cards, next, total);
            }
            None
        }
        RuntimeMessage::Bodies { epoch, bodies } => {
            if epoch == model.epoch {
                hydrate_all(model, &bodies);
            }
            None
        }
        RuntimeMessage::DraftStored { revision } => {
            if revision <= model.draft.revision {
                model.draft.persisted_revision = model.draft.persisted_revision.max(revision);
            }
            None
        }
        RuntimeMessage::Saved { revision, id } => saved(model, revision, id),
        RuntimeMessage::Tags(tags) => {
            model.tags = tags;
            None
        }
        RuntimeMessage::Message { title, lines } => {
            model.present_notice(title, lines);
            None
        }
        RuntimeMessage::History { id, revisions } => {
            if model.input == InputMode::Browse {
                model.input = InputMode::Picker(crate::model::Picker {
                    kind: crate::model::PickerKind::History { id, revisions },
                    text: crate::input::TextBuffer::default(),
                    selected: 0,
                });
            }
            None
        }
        RuntimeMessage::Date {
            ticket,
            from,
            until,
            label,
        } => apply_date(model, ticket, from, until, label),
        RuntimeMessage::RuntimeReady { model: prepared } => {
            *model = *prepared;
            None
        }
        RuntimeMessage::FsChanged
        | RuntimeMessage::Reconciled { .. }
        | RuntimeMessage::WatcherReady
        | RuntimeMessage::WatcherUnavailable { .. }
        | RuntimeMessage::PlayerFinished { .. } => apply_observation(model, message),
        RuntimeMessage::Changed(status) => {
            model.set_status(&status);
            refresh_current(model)
        }
        RuntimeMessage::Failed { target, diagnostic } => {
            apply_failure(model, target, &diagnostic);
            None
        }
    }
}

/// Watcher and managed-process replies carry only observation state; a changed
/// projection is what re-queries the visible window.
fn apply_observation(model: &mut AppModel, message: RuntimeMessage) -> Option<Effect> {
    match message {
        RuntimeMessage::FsChanged => Some(Effect::Reconcile),
        RuntimeMessage::Reconciled { changed } => {
            if changed {
                refresh_current(model)
            } else {
                None
            }
        }
        RuntimeMessage::WatcherReady => {
            model.watcher_active = true;
            None
        }
        RuntimeMessage::WatcherUnavailable { diagnostic } => {
            model.watcher_active = false;
            model.set_status(&diagnostic);
            None
        }
        RuntimeMessage::PlayerFinished {
            success,
            diagnostic,
        } => {
            if !success {
                model.set_status(&diagnostic.unwrap_or_else(|| "player failed".to_owned()));
            }
            None
        }
        RuntimeMessage::QuitReady
        | RuntimeMessage::Image { .. }
        | RuntimeMessage::View { .. }
        | RuntimeMessage::Page { .. }
        | RuntimeMessage::Bodies { .. }
        | RuntimeMessage::ReadMemo { .. }
        | RuntimeMessage::DraftStored { .. }
        | RuntimeMessage::Saved { .. }
        | RuntimeMessage::Tags(_)
        | RuntimeMessage::Message { .. }
        | RuntimeMessage::History { .. }
        | RuntimeMessage::Date { .. }
        | RuntimeMessage::RuntimeReady { .. }
        | RuntimeMessage::Changed(_)
        | RuntimeMessage::Failed { .. } => None,
    }
}

fn apply_page(
    model: &mut AppModel,
    epoch: u64,
    append: bool,
    mut cards: Vec<MemoCard>,
    next: Option<lomo_application::PageCursor>,
    total: Option<u64>,
) {
    let feed = std::iter::once(&mut model.view)
        .chain(model.history.iter_mut().rev())
        .find_map(|view| {
            if let View::Feed(feed) = view
                && feed.epoch == epoch
            {
                Some(feed)
            } else {
                None
            }
        });
    let Some(feed) = feed else {
        return;
    };
    if append {
        for card in cards {
            if !feed.memos.iter().any(|old| old.id == card.id) {
                feed.memos.push(card);
            }
        }
    } else {
        let following_head = feed.memos.first().is_some_and(|first| {
            feed.selected.as_ref() == Some(&first.id)
                && feed.anchor.as_ref().is_some_and(|anchor| {
                    anchor.id == first.id && anchor.position == CardPosition::Time
                })
        });
        for card in &mut cards {
            if let Some(old) = feed
                .memos
                .iter()
                .find(|old| old.version() == card.version())
                && let BodyState::Ready(body) = &old.body
            {
                card.body = BodyState::Ready(std::sync::Arc::clone(body));
            }
        }
        if following_head
            && cards
                .first()
                .is_some_and(|first| Some(&first.id) == model.last_created.as_ref())
        {
            feed.selected = cards.first().map(|first| first.id.clone());
            feed.anchor = feed.selected.clone().map(|id| MemoAnchor {
                id,
                position: CardPosition::Time,
            });
        }
        let previous = std::mem::replace(&mut feed.memos, cards);
        if feed.reconcile_replacement(&previous) {
            model.status = Some(
                crate::i18n::UiStrings::detect()
                    .text(
                        "The previous memo left this view; selected its nearest neighbor",
                        "原记录已离开当前视图，已定位到相邻记录",
                    )
                    .to_owned(),
            );
        }
    }
    feed.next_cursor = next;
    // The query total is bound to the first page; append replies carry none
    // and must not erase the established count.
    if total.is_some() {
        feed.total = total;
    }
    feed.load = LoadStatus::Ready;
    feed.reconcile();
}

/// Every copy of a memo (views, history and the picker it was opened on) receives its body.
fn hydrate_all(model: &mut AppModel, replies: &[BodyReply]) {
    hydrate(&mut model.view, replies);
    if let InputMode::Picker(picker) = &mut model.input {
        match &mut picker.kind {
            crate::model::PickerKind::Palette {
                item: crate::model::PaletteItem::Memo(memo),
                ..
            }
            | crate::model::PickerKind::Attachments(memo) => hydrate_memo(memo, replies),
            crate::model::PickerKind::Palette { .. }
            | crate::model::PickerKind::Tags(_)
            | crate::model::PickerKind::Dates
            | crate::model::PickerKind::History { .. } => {}
        }
    }
    for view in &mut model.history {
        hydrate(view, replies);
    }
}

fn hydrate(view: &mut View, replies: &[BodyReply]) {
    match view {
        View::Feed(feed) => {
            for memo in &mut feed.memos {
                hydrate_memo(memo, replies);
            }
        }
        View::Reader { memo, .. } => hydrate_memo(memo, replies),
        View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. } => {}
    }
}

fn hydrate_memo(memo: &mut MemoCard, replies: &[BodyReply]) {
    if let Some(reply) = replies.iter().find(|reply| reply.version == memo.version()) {
        match &reply.result {
            Ok(loaded) => {
                memo.body = BodyState::Ready(std::sync::Arc::clone(&loaded.body));
                memo.attachments.clone_from(&loaded.attachments);
            }
            Err(error) => memo.body = BodyState::Failed(error.clone()),
        }
    }
}

fn apply_date(
    model: &mut AppModel,
    ticket: u64,
    from: i64,
    until: i64,
    label: String,
) -> Option<Effect> {
    if !matches!(&model.input, InputMode::Date { ticket: current, .. } if *current == ticket) {
        return None;
    }
    crate::update::ensure_feed(model);
    if let View::Feed(feed) = &mut model.view {
        feed.remember_unfiltered();
        feed.query.filters.date_from_inclusive_ms = Some(from);
        feed.query.filters.date_until_exclusive_ms = Some(until);
        feed.query.date_label = Some(label);
        feed.mark_requery();
    }
    model.input = InputMode::Browse;
    crate::update::requery(model)
}

fn apply_failure(model: &mut AppModel, target: FailureTarget, diagnostic: &str) {
    match target {
        FailureTarget::DraftPersist(revision) | FailureTarget::DraftCommit(revision) => {
            if revision != model.draft.revision {
                return;
            }
            if matches!(target, FailureTarget::DraftCommit(_))
                || !matches!(model.draft.save, SaveState::Submitting { .. })
            {
                model.draft.save = SaveState::Failed {
                    diagnostic: diagnostic.to_owned(),
                };
            }
        }
        FailureTarget::Feed(epoch) => {
            if epoch != model.epoch {
                return;
            }
            if let View::Feed(feed) = &mut model.view {
                feed.load = LoadStatus::Failed(diagnostic.to_owned());
            }
        }
        FailureTarget::View(epoch) => {
            if epoch != model.epoch {
                return;
            }
            model.view = View::Failed {
                screen: model.view.screen(),
                diagnostic: diagnostic.to_owned(),
            };
        }
        FailureTarget::Date(ticket) => {
            let InputMode::Date {
                ticket: current,
                error,
                ..
            } = &mut model.input
            else {
                return;
            };
            if *current != ticket {
                return;
            }
            *error = Some(diagnostic.to_owned());
        }
        FailureTarget::Bootstrap => {
            model.view = View::Failed {
                screen: model.view.screen(),
                diagnostic: diagnostic.to_owned(),
            };
        }
        FailureTarget::Reconcile | FailureTarget::Action => {}
    }
    model.set_status(diagnostic);
}

#[must_use]
pub fn refresh_current(model: &mut AppModel) -> Option<Effect> {
    for view in &mut model.history {
        mark_stale(view);
    }
    if let View::Feed(feed) = &mut model.view {
        if let Some(original) = &mut feed.unfiltered {
            original.load = LoadStatus::Stale;
        }
        return crate::update::reload_feed(model);
    }
    let epoch = model.next_epoch();
    if let View::Reader { memo, .. } = &model.view {
        return Some(Effect::ReadMemo {
            epoch,
            id: memo.id.clone(),
        });
    }
    Some(Effect::Navigate {
        epoch,
        screen: model.view.screen(),
    })
}

fn mark_stale(view: &mut View) {
    if let View::Feed(feed) = view {
        feed.load = LoadStatus::Stale;
        if let Some(original) = &mut feed.unfiltered {
            original.load = LoadStatus::Stale;
        }
    }
}

fn saved(model: &mut AppModel, revision: u64, id: lomo_workspace::MemoId) -> Option<Effect> {
    if model.draft.revision == revision {
        model.draft = Composer {
            revision: revision.saturating_add(1),
            persisted_revision: revision.saturating_add(1),
            ..Composer::default()
        };
        if model.input == InputMode::Compose {
            model.input = InputMode::Browse;
        }
    }
    model.last_created = Some(id);
    model.set_status(crate::i18n::UiStrings::detect().text(
        "Saved · commands: view last saved memo",
        "已保存 · 功能菜单可查看刚保存的记录",
    ));
    refresh_current(model)
}
