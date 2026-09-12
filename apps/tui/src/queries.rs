//! Application query adapters produce presentation-ready, typed memo records.
use crate::effects::{FeedRequest, LoadedBody, PageIntent, RuntimeMessage};
use crate::error::TuiError;
use crate::model::{
    AttachmentRow, BodyState, FeedKind, FeedState, HeatPoint, LoadStatus, MemoCard, Screen,
    SelectionList, StatsView, TaskRow, View,
};
use crate::ops::{TuiRuntime, now_ms};
use lomo_application::calendar::{
    CivilDate, DateFormat, day_bounds, format_date_key, journal_stamp, local_date,
    parse_date_key_with_format,
};
use lomo_application::{
    MemoQuery, MemoSort, MemoSummary, SearchOutcome, SearchRequest, StatisticsSnapshot,
};
use lomo_core::{PageSize, RelativeWorkspacePath};
use lomo_workspace::MemoId;
use std::{collections::BTreeMap, sync::Arc};

/// # Errors
/// Invalid dates, projection identities or attachment paths.
pub fn card(summary: MemoSummary, runtime: &TuiRuntime) -> Result<MemoCard, TuiError> {
    let stamp = journal_stamp(
        summary.created_at_ms,
        &runtime.config.time_zone,
        DateFormat::YyyyMmDdHyphen,
    )?;
    Ok(MemoCard {
        id: MemoId::parse(&summary.memo_id)?,
        date: stamp.filename.trim_end_matches(".md").to_owned(),
        time: stamp.time_token,
        summary: summary.body_preview,
        body: BodyState::Pending,
        tags: summary.tags,
        attachments: summary
            .image_urls
            .iter()
            .map(|path| RelativeWorkspacePath::parse(path))
            .collect::<Result<_, _>>()?,
        fingerprint: summary.file_fingerprint,
        revision: summary.content_revision,
        pinned: summary.is_pinned,
        trashed: summary.is_trashed,
        excerpt: None,
    })
}
/// # Errors
/// Projection or Markdown decoding errors.
pub fn load_body(runtime: &TuiRuntime, id: &MemoId) -> Result<MemoCard, TuiError> {
    let snapshot = runtime
        .session
        .projected_memo(id.as_str())?
        .ok_or_else(|| TuiError::config(format!("memo disappeared: {}", id.as_str())))?;
    let mut memo = card(snapshot.summary, runtime)?;
    let body = crate::content::MemoBody::parse(snapshot.body)?;
    let document = body.document();
    memo.attachments = document
        .attachment_destinations()
        .iter()
        .map(|path| RelativeWorkspacePath::parse(path))
        .collect::<Result<_, _>>()?;
    memo.body = BodyState::Ready(Arc::new(body));
    Ok(memo)
}
/// # Errors
/// A body is returned only for the exact requested content revision and document fingerprint.
pub fn load_version_body(
    runtime: &TuiRuntime,
    version: &crate::model::MemoVersion,
) -> Result<LoadedBody, TuiError> {
    let snapshot = runtime
        .session
        .projected_memo(version.id.as_str())?
        .ok_or_else(|| TuiError::config(format!("memo disappeared: {}", version.id.as_str())))?;
    if snapshot.summary.content_revision != version.revision
        || snapshot.summary.file_fingerprint != version.fingerprint
    {
        return Err(TuiError::config(
            "memo changed while loading; refresh to read the new version",
        ));
    }
    let body = crate::content::MemoBody::parse(snapshot.body)?;
    let attachments = body
        .document()
        .attachment_destinations()
        .iter()
        .map(|path| RelativeWorkspacePath::parse(path))
        .collect::<Result<_, _>>()?;
    Ok(LoadedBody {
        body: Arc::new(body),
        attachments,
    })
}

/// # Errors
/// Search, cursor or projection failures.
pub fn query_feed(runtime: &TuiRuntime, request: &FeedRequest) -> Result<RuntimeMessage, TuiError> {
    if request.kind == FeedKind::Review {
        let cards = review_cards(runtime)?;
        let total =
            u64::try_from(cards.len()).map_err(|error| TuiError::config(error.to_string()))?;
        return Ok(RuntimeMessage::Page {
            epoch: request.epoch,
            append: false,
            cards,
            next: None,
            total: Some(total),
        });
    }
    let (mut cards, mut next, total) = query_page(runtime, request, request.intent.cursor())?;
    while !request.intent.covers(&cards) {
        let Some(cursor) = next.as_ref() else {
            break;
        };
        let (more, following, _) = query_page(runtime, request, Some(cursor))?;
        cards.extend(more);
        next = following;
    }
    Ok(RuntimeMessage::Page {
        epoch: request.epoch,
        append: matches!(request.intent, PageIntent::Append(_)),
        cards,
        next,
        total,
    })
}

type QueryPage = (
    Vec<MemoCard>,
    Option<lomo_application::PageCursor>,
    Option<u64>,
);

fn query_page(
    runtime: &TuiRuntime,
    request: &FeedRequest,
    cursor: Option<&lomo_application::PageCursor>,
) -> Result<QueryPage, TuiError> {
    let mut filters = request.query.filters.clone();
    filters.trash_only = request.kind == FeedKind::Trash;
    if request.query.text.trim().is_empty() {
        let query = MemoQuery {
            search_text: None,
            filters,
            sort: MemoSort::default(),
        };
        let page = runtime
            .session
            .query_memos_page(&query, None, cursor, PageSize::new(48)?)?;
        let total = runtime.session.query_count(&query)?;
        Ok((
            page.items
                .into_iter()
                .map(|summary| card(summary, runtime))
                .collect::<Result<_, _>>()?,
            page.next_cursor,
            Some(total),
        ))
    } else {
        let result = runtime.session.search(&SearchRequest {
            query_epoch: request.epoch,
            mode: request.query.mode,
            text: request.query.text.clone(),
            filters,
            cursor: cursor.cloned(),
            page_size: PageSize::new(48)?,
        })?;
        let SearchOutcome::Ready(page) = result else {
            return Err(TuiError::config("search superseded"));
        };
        let cards = page
            .items
            .into_iter()
            .map(|hit| {
                let mut memo = card(hit.summary, runtime)?;
                memo.excerpt = Some(hit.excerpt);
                Ok(memo)
            })
            .collect::<Result<_, TuiError>>()?;
        Ok((cards, page.next_cursor, Some(page.total)))
    }
}
/// # Errors
/// Query errors.
pub fn load_feed(runtime: &TuiRuntime, kind: FeedKind, epoch: u64) -> Result<FeedState, TuiError> {
    let mut feed = FeedState::new(kind);
    feed.epoch = epoch;
    if kind == FeedKind::Review {
        feed.memos = review_cards(runtime)?;
        feed.total = Some(
            u64::try_from(feed.memos.len()).map_err(|error| TuiError::config(error.to_string()))?,
        );
    } else {
        let reply = query_feed(
            runtime,
            &FeedRequest {
                epoch,
                kind,
                query: feed.query.clone(),
                intent: PageIntent::Initial,
            },
        )?;
        if let RuntimeMessage::Page {
            cards, next, total, ..
        } = reply
        {
            feed.memos = cards;
            feed.next_cursor = next;
            feed.total = total;
        }
    }
    feed.load = LoadStatus::Ready;
    feed.reconcile();
    Ok(feed)
}
fn review_cards(runtime: &TuiRuntime) -> Result<Vec<MemoCard>, TuiError> {
    let date = local_date(now_ms()?, &runtime.config.time_zone)?;
    runtime
        .session
        .review_candidates(&runtime.config.time_zone, date)?
        .into_iter()
        .map(|candidate| load_body(runtime, &MemoId::parse(&candidate.memo_id)?))
        .collect()
}

/// # Errors
/// Session data or configuration failures.
pub fn load_screen(runtime: &TuiRuntime, screen: Screen, epoch: u64) -> Result<View, TuiError> {
    match screen {
        Screen::Timeline => Ok(View::Feed(load_feed(runtime, FeedKind::Timeline, epoch)?)),
        Screen::Review => Ok(View::Feed(load_feed(runtime, FeedKind::Review, epoch)?)),
        Screen::Trash => Ok(View::Feed(load_feed(runtime, FeedKind::Trash, epoch)?)),
        Screen::Tasks => {
            let rows = runtime
                .session
                .list_tasks()?
                .into_iter()
                .map(|task| {
                    Ok(TaskRow {
                        memo_id: MemoId::parse(&task.memo_id)?,
                        line: task.line_index,
                        text: task.text,
                        date: task.source_path.trim_end_matches(".md").to_owned(),
                        done: task.done,
                    })
                })
                .collect::<Result<Vec<_>, TuiError>>()?;
            Ok(View::Tasks(SelectionList::new(rows)))
        }
        Screen::Statistics => load_statistics(runtime),
        Screen::Attachments => {
            let mut paths = BTreeMap::<String, Vec<String>>::new();
            for attachment in runtime.session.observe_attachments()? {
                paths
                    .entry(attachment.relative_path)
                    .or_default()
                    .push(attachment.owner_key);
            }
            let rows = paths
                .into_iter()
                .map(|(path, owners)| {
                    Ok(AttachmentRow {
                        path: RelativeWorkspacePath::parse(&path)?,
                        owners,
                    })
                })
                .collect::<Result<Vec<_>, TuiError>>()?;
            Ok(View::Attachments(SelectionList::new(rows)))
        }
        Screen::Settings => Ok(View::Settings(vec![
            format!("workspace: {}", runtime.workspace.display()),
            format!("timezone: {}", runtime.config.time_zone),
            format!(
                "editor: {}",
                runtime
                    .config
                    .editor
                    .as_ref()
                    .map_or_else(|| "VISUAL / EDITOR".to_owned(), |argv| argv.join(" "))
            ),
            format!("player: {}", runtime.config.player.join(" ")),
            format!("graphics: {:?}", runtime.graphics),
            format!("device: {}", runtime.session.device_id()),
        ])),
    }
}
fn load_statistics(runtime: &TuiRuntime) -> Result<View, TuiError> {
    let date = local_date(now_ms()?, &runtime.config.time_zone)?;
    let stats = runtime.session.statistics(&StatisticsSnapshot::new(
        runtime.config.time_zone.clone(),
        date,
    ))?;
    Ok(View::Statistics(StatsView {
        zone: runtime.config.time_zone.clone(),
        as_of_year: date.year(),
        as_of_month: date.month(),
        as_of_day: date.day(),
        total_memos: stats.total_memos,
        total_words: stats.total_words,
        active_days: stats.active_days,
        current_streak: stats.current_streak,
        longest_streak: stats.longest_streak,
        this_week: stats.this_week_count,
        this_month: stats.this_month_count,
        this_year: stats.this_year_count,
        daily: stats
            .memo_count_by_date
            .into_iter()
            .map(|(date, count)| HeatPoint {
                year: date.year(),
                month: date.month(),
                day: date.day(),
                count,
            })
            .collect(),
    }))
}
/// # Errors
/// Rejects malformed, inverted or invalid civil date ranges before querying.
pub fn date_filter(
    runtime: &TuiRuntime,
    ticket: u64,
    text: &str,
) -> Result<RuntimeMessage, TuiError> {
    let today = local_date(now_ms()?, &runtime.config.time_zone)?;
    let (start, end) = match text.trim() {
        "today" | "今天" => (today, today),
        "yesterday" | "昨天" => {
            let (start, _) = day_bounds(today, &runtime.config.time_zone)?;
            let previous = start
                .checked_sub(1)
                .ok_or_else(|| TuiError::config("date is outside the supported range"))?;
            let date = local_date(previous, &runtime.config.time_zone)?;
            (date, date)
        }
        "week" | "本周" => (today.iso_monday()?, today),
        "month" | "本月" => (CivilDate::new(today.year(), today.month(), 1)?, today),
        raw => {
            let (from, until) = raw.split_once("..").unwrap_or((raw, raw));
            (
                parse_date_key_with_format(from.trim(), DateFormat::YyyyMmDdHyphen)?,
                parse_date_key_with_format(until.trim(), DateFormat::YyyyMmDdHyphen)?,
            )
        }
    };
    if start > end {
        return Err(TuiError::config("date range starts after its end"));
    }
    let (from, _) = day_bounds(start, &runtime.config.time_zone)?;
    let (_, until) = day_bounds(end, &runtime.config.time_zone)?;
    let label = format!(
        "{}…{}",
        format_date_key(start, DateFormat::YyyyMmDdHyphen),
        format_date_key(end, DateFormat::YyyyMmDdHyphen)
    );
    Ok(RuntimeMessage::Date {
        ticket,
        from,
        until,
        label,
    })
}
