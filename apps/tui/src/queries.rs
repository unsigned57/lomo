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
        &runtime.config().time_zone,
        DateFormat::YyyyMmDdHyphen,
    )?;
    Ok(MemoCard {
        id: MemoId::parse(&summary.memo_id)?,
        date: stamp.filename.trim_end_matches(".md").to_owned(),
        time: stamp.time_token,
        summary: summary.body_preview,
        body: BodyState::Pending,
        tags: summary.tags,
        // Only destinations that name a workspace file become typed paths; external objects
        // and malformed spellings carry no attachment key and cannot poison the card.
        attachments: summary
            .image_urls
            .iter()
            .filter_map(|path| lomo_workspace::canonical_attachment_path(path))
            .map(|canonical| RelativeWorkspacePath::parse(&canonical))
            .collect::<Result<_, _>>()?,
        fingerprint: summary.file_fingerprint,
        revision: summary.content_revision,
        pinned: summary.is_pinned,
        trashed: summary.is_trashed,
        excerpt: None,
    })
}
/// # Errors
/// Projection or Markdown decoding errors. `Ok(None)` is a resolved lookup
/// naming a memo that no longer exists — a domain answer, not a failure.
pub fn load_body(runtime: &TuiRuntime, id: &MemoId) -> Result<Option<MemoCard>, TuiError> {
    let Some(snapshot) = runtime.session.projected_memo(id.as_str())? else {
        return Ok(None);
    };
    let mut memo = card(snapshot.summary, runtime)?;
    let body = crate::content::MemoBody::parse(snapshot.body)?;
    let document = body.document();
    memo.attachments = document
        .attachment_destinations()
        .iter()
        .filter_map(|dest| dest.local().cloned())
        .collect();
    memo.body = BodyState::Ready(Arc::new(body));
    Ok(Some(memo))
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
        .filter_map(|dest| dest.local().cloned())
        .collect();
    Ok(LoadedBody {
        body: Arc::new(body),
        attachments,
    })
}

/// Feed queries read one bounded page at a time.
const PAGE_CARDS: u32 = 48;
/// A refresh never reads more than three pages no matter how deep the loaded
/// feed goes — the reply is a window, not the old list.
const REFRESH_MAX_CARDS: usize = 3 * PAGE_CARDS as usize;

/// A refresh re-reads the loaded window plus one page of drift, capped — a
/// vanished anchor can never satisfy `covers`, so the loop must stop at the
/// window instead of draining the whole result set.
fn intent_window(request: &FeedRequest) -> usize {
    match &request.intent {
        PageIntent::Refresh { known, .. } => known
            .len()
            .saturating_add(usize::try_from(PAGE_CARDS).unwrap_or(usize::MAX))
            .min(REFRESH_MAX_CARDS),
        PageIntent::Initial | PageIntent::Append(_) => usize::MAX,
    }
}

/// Where a bounded page begins in the current query order.
#[derive(Clone, Copy)]
enum Start<'a> {
    Head,
    After(&'a lomo_application::PageCursor),
    Before(&'a lomo_application::PageCursor),
    /// Inclusive start at this memo, resolved against the current revision —
    /// a missing identity falls back to head, never to an error.
    AtMemo(&'a MemoId),
}

/// One fetched slice: the cards plus the cursors and rank counts that locate
/// it inside the query — enough to compose windows without re-querying counts.
struct PageSlice {
    cards: Vec<MemoCard>,
    next: Option<lomo_application::PageCursor>,
    /// A fetchable lookbehind cursor — search pages cannot be walked
    /// backwards, so only the store path mints one.
    prev: Option<lomo_application::PageCursor>,
    /// The ordering bound strictly above this slice's head card — evidence
    /// for the refresh merge's above-window side. `None` when the slice head
    /// is rank 0; fuzzy pages carry hit positions instead of a bound.
    head: Option<lomo_application::PageCursor>,
    items_before: u64,
    items_after: u64,
    /// The query total on a non-continuation page; continuation pages carry
    /// none so an established total is never overwritten by a partial window.
    total: Option<u64>,
}

/// # Errors
/// Search, cursor or projection failures.
pub fn query_feed(runtime: &TuiRuntime, request: &FeedRequest) -> Result<RuntimeMessage, TuiError> {
    if request.kind == FeedKind::Review {
        let cards = review_cards(runtime)?;
        let total =
            u64::try_from(cards.len()).map_err(|error| TuiError::config(error.to_string()))?;
        // The candidate list is the complete membership, already in its own
        // order — a loaded card absent from it left the review set.
        let order = cards.iter().map(|card| card.id.clone()).collect();
        return Ok(RuntimeMessage::Page {
            req: request.req,
            append: false,
            cards,
            next: None,
            order,
            total: Some(total),
        });
    }
    match &request.intent {
        // An anchored refresh re-reads the anchor's neighborhood: the window
        // starts at the visually earliest anchor, extends forward until every
        // anchor is covered (or the bound hits), then one lookbehind page
        // restores the rows above it. The reply then carries the live-order
        // sequence over `reply ∪ (loaded ∩ live)` so the merge replays
        // evidence — never a stale slot.
        PageIntent::Refresh { anchors, known } => {
            let window = intent_window(request);
            let start = anchors.first().map_or(Start::Head, Start::AtMemo);
            let first = query_page(runtime, request, start)?;
            let mut cards = first.cards;
            let mut next = first.next;
            let mut items_before = first.items_before;
            let mut items_after = first.items_after;
            while !request.intent.covers(&cards) && cards.len() < window {
                let Some(cursor) = next.clone() else {
                    break;
                };
                let page = query_page(runtime, request, Start::After(&cursor))?;
                cards.extend(page.cards);
                next = page.next;
                items_after = page.items_after;
            }
            let mut head_bound = first.head;
            if let Some(cursor) = first.prev {
                let behind = query_page(runtime, request, Start::Before(&cursor))?;
                if !behind.cards.is_empty() {
                    head_bound = behind.head;
                }
                let mut merged = behind.cards;
                merged.append(&mut cards);
                cards = merged;
                items_before = behind.items_before;
            }
            let (above, below) = refresh_window_sides(
                runtime,
                request,
                known,
                head_bound.as_ref(),
                next.as_ref(),
                items_before,
                cards.len(),
            )?;
            // The frontier cursor always mints under this reply's revision:
            // survivors below the window continue from their own live tail,
            // not from the window's edge (which sits inside loaded cards) and
            // never from the pre-refresh cursor (which names a dead revision).
            let next = match below.last() {
                Some(tail) => {
                    let minted = query_page_once(runtime, request, Start::AtMemo(tail), 1)?;
                    // `AtMemo` resolves a departed identity to `Head`, so a first
                    // card that is not the tail boundary means the anchor left the
                    // result set between the sides scan and this checkout — keep
                    // the reply's own frontier rather than minting a rank-0 rewind.
                    if minted.cards.first().is_some_and(|card| card.id == *tail) {
                        minted.next
                    } else {
                        next
                    }
                }
                None => next,
            };
            let mut order = Vec::with_capacity(above.len() + cards.len() + below.len());
            order.extend(above);
            order.extend(cards.iter().map(|card| card.id.clone()));
            order.extend(below);
            let total = items_before
                .saturating_add(u64::try_from(cards.len()).unwrap_or(u64::MAX))
                .saturating_add(items_after);
            Ok(RuntimeMessage::Page {
                req: request.req,
                append: false,
                cards,
                next,
                order,
                total: Some(total),
            })
        }
        PageIntent::Initial | PageIntent::Append(_) => {
            let start = request.intent.cursor().map_or(Start::Head, Start::After);
            let page = query_page(runtime, request, start)?;
            let order = if matches!(request.intent, PageIntent::Initial) {
                // A first page is the whole ordering claim it can carry.
                page.cards.iter().map(|card| card.id.clone()).collect()
            } else {
                Vec::new()
            };
            Ok(RuntimeMessage::Page {
                req: request.req,
                append: matches!(request.intent, PageIntent::Append(_)),
                cards: page.cards,
                next: page.next,
                order,
                total: page.total,
            })
        }
    }
}

/// Placement evidence for a refresh reply: of the loaded ids the intent
/// carried (`known`), which still match the live query and sort strictly
/// above the reply's head bound / strictly below its tail bound — each side
/// in live query order. An id absent from all three segments left the result
/// set — membership evidence — and each survivor's side position is its live
/// rank, not its stale loading-side slot.
fn refresh_window_sides(
    runtime: &TuiRuntime,
    request: &FeedRequest,
    known: &[MemoId],
    head: Option<&lomo_application::PageCursor>,
    tail: Option<&lomo_application::PageCursor>,
    window_start: u64,
    window_len: usize,
) -> Result<(Vec<MemoId>, Vec<MemoId>), TuiError> {
    if known.is_empty() {
        return Ok((Vec::new(), Vec::new()));
    }
    let ids: Vec<String> = known.iter().map(|id| id.as_str().to_owned()).collect();
    let mut filters = request.query.filters.clone();
    filters.trash_only = request.kind == FeedKind::Trash;
    let sides = if request.query.text.trim().is_empty() {
        runtime.session.matching_memo_window(
            &MemoQuery {
                search_text: None,
                filters,
                sort: MemoSort::default(),
            },
            &ids,
            head,
            tail,
        )?
    } else {
        match request.query.mode {
            lomo_application::SearchMode::Fulltext => runtime.session.matching_memo_window(
                &MemoQuery {
                    search_text: Some(request.query.text.trim().to_owned()),
                    filters,
                    sort: MemoSort::default(),
                },
                &ids,
                head,
                tail,
            )?,
            lomo_application::SearchMode::Fuzzy => runtime.session.fuzzy_window_sides(
                &request.query.text,
                &filters,
                &ids,
                window_start,
                window_start.saturating_add(u64::try_from(window_len).unwrap_or(u64::MAX)),
            )?,
        }
    };
    let parse = |ids: Vec<String>| -> Result<Vec<MemoId>, TuiError> {
        ids.iter()
            .map(|id| MemoId::parse(id).map_err(TuiError::from))
            .collect()
    };
    Ok((parse(sides.above)?, parse(sides.below)?))
}

/// A cursor minted under a superseded publication is not a dead end: the
/// cursor itself names its boundary memo, so `After` re-anchors on that
/// identity's *live* rank — an inclusive start, so the boundary card itself
/// comes back out of the slice. When the boundary has left the query
/// entirely the original `stale_cursor` propagates — the frontier honestly
/// died, and the reply must say so instead of silently reading the head.
fn query_page(
    runtime: &TuiRuntime,
    request: &FeedRequest,
    start: Start<'_>,
) -> Result<PageSlice, TuiError> {
    match query_page_once(runtime, request, start, PAGE_CARDS) {
        Err(error) if matches!(&error, TuiError::Session { code, .. } if code == "stale_cursor") => {
            let Start::After(cursor) = start else {
                return Err(error);
            };
            let Ok(boundary) = MemoId::parse(&cursor.sort_memo_id) else {
                return Err(error);
            };
            let mut page = query_page_once(runtime, request, Start::AtMemo(&boundary), PAGE_CARDS)?;
            if page.cards.first().is_none_or(|card| card.id != boundary) {
                return Err(error);
            }
            page.cards.remove(0);
            // The dropped boundary row still sits in `items_before`; the
            // trimmed slice starts one rank deeper.
            page.items_before = page.items_before.saturating_add(1);
            Ok(page)
        }
        result => result,
    }
}

fn query_page_once(
    runtime: &TuiRuntime,
    request: &FeedRequest,
    start: Start<'_>,
    page_size: u32,
) -> Result<PageSlice, TuiError> {
    let mut filters = request.query.filters.clone();
    filters.trash_only = request.kind == FeedKind::Trash;
    if request.query.text.trim().is_empty() {
        let query = MemoQuery {
            search_text: None,
            filters,
            sort: MemoSort::default(),
        };
        let page = runtime.session.query_memos_starting_at(
            &query,
            None,
            match start {
                Start::Head => lomo_application::MemoQueryStart::Head,
                Start::After(cursor) => lomo_application::MemoQueryStart::After(cursor),
                Start::Before(cursor) => lomo_application::MemoQueryStart::Before(cursor),
                Start::AtMemo(id) => lomo_application::MemoQueryStart::AtMemo(id.as_str()),
            },
            PageSize::new(page_size)?,
        )?;
        // The store already counts before/after for cursor production, so the
        // page's own rank carries the total — no second COUNT round-trip.
        let total = match start {
            Start::After(_) => None,
            Start::Head | Start::Before(_) | Start::AtMemo(_) => Some(
                page.items_before
                    .saturating_add(u64::try_from(page.items.len()).unwrap_or(u64::MAX))
                    .saturating_add(page.items_after),
            ),
        };
        Ok(PageSlice {
            cards: page
                .items
                .into_iter()
                .map(|summary| card(summary, runtime))
                .collect::<Result<_, _>>()?,
            next: page.next_cursor,
            prev: page.prev_cursor.clone(),
            head: page.prev_cursor,
            items_before: page.items_before,
            items_after: page.items_after,
            total,
        })
    } else {
        let (cursor, anchor) = match start {
            Start::After(cursor) => (Some(cursor.clone()), None),
            // Scored order still carries identity starts: the refresh anchor
            // resolves to the memo's own rank so the re-read window covers
            // the reading position, not the ranked head.
            Start::AtMemo(id) => (None, Some(id.as_str().to_owned())),
            Start::Head => (None, None),
            // Search slices never mint a backward cursor (`prev` stays
            // `None`), so a `Before` start is unreachable — reject it rather
            // than silently reading the head.
            Start::Before(_) => {
                return Err(TuiError::config("search pages carry no backward cursor"));
            }
        };
        let result = runtime.session.search(&SearchRequest {
            query_epoch: request.req.0,
            mode: request.query.mode,
            text: request.query.text.clone(),
            filters,
            cursor,
            anchor,
            page_size: PageSize::new(page_size)?,
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
        Ok(PageSlice {
            cards,
            next: page.next_cursor,
            prev: None,
            head: page.prev_cursor,
            items_before: page.items_before,
            items_after: page.items_after,
            total: Some(page.total),
        })
    }
}
/// # Errors
/// Query errors.
pub fn load_feed(runtime: &TuiRuntime, kind: FeedKind) -> Result<FeedState, TuiError> {
    let mut feed = FeedState::new(kind);
    if kind == FeedKind::Review {
        feed.memos = review_cards(runtime)?;
        feed.total = Some(
            u64::try_from(feed.memos.len()).map_err(|error| TuiError::config(error.to_string()))?,
        );
    } else {
        let reply = query_feed(
            runtime,
            &FeedRequest {
                // Synchronous bootstrap load: no reply is delivered, so the
                // request identity is nominal only.
                req: crate::model::Req(0),
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
/// Review candidates are summary cards; bodies hydrate through the visible-window
/// `Bodies` effect like every other feed, never eagerly per candidate.
fn review_cards(runtime: &TuiRuntime) -> Result<Vec<MemoCard>, TuiError> {
    let date = local_date(now_ms()?, &runtime.config().time_zone)?;
    let candidates = runtime
        .session
        .review_candidates(&runtime.config().time_zone, date)?;
    // One batched projection read for the whole candidate set — the store
    // chunks the id list internally instead of a round-trip per candidate.
    let ids: Vec<String> = candidates
        .iter()
        .map(|candidate| candidate.memo_id.clone())
        .collect();
    let snapshots = runtime.session.projected_memos(&ids)?;
    let by_id: BTreeMap<&str, &lomo_application::MemoSnapshot> = snapshots
        .iter()
        .map(|snapshot| (snapshot.summary.memo_id.as_str(), snapshot))
        .collect();
    candidates
        .iter()
        .map(|candidate| {
            let snapshot = by_id.get(candidate.memo_id.as_str()).ok_or_else(|| {
                TuiError::config(format!(
                    "review candidate disappeared: {}",
                    candidate.memo_id
                ))
            })?;
            card(snapshot.summary.clone(), runtime)
        })
        .collect()
}

/// # Errors
/// Session data or configuration failures.
pub fn load_screen(runtime: &TuiRuntime, screen: Screen) -> Result<View, TuiError> {
    match screen {
        Screen::Timeline => Ok(View::Feed(Box::new(load_feed(
            runtime,
            FeedKind::Timeline,
        )?))),
        Screen::Review => Ok(View::Feed(Box::new(load_feed(runtime, FeedKind::Review)?))),
        Screen::Trash => Ok(View::Feed(Box::new(load_feed(runtime, FeedKind::Trash)?))),
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
                        date: day_label(runtime, &task.source_path),
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
        Screen::Settings => {
            let strings = crate::i18n::UiStrings::detect();
            let info = vec![
                format!(
                    "{}: {}",
                    strings.text("language", "语言"),
                    match strings.language {
                        crate::i18n::UiLanguage::English => "English (LOMO_LANG / LANG)",
                        crate::i18n::UiLanguage::ChineseSimplified => {
                            "简体中文（LOMO_LANG / LANG）"
                        }
                    }
                ),
                format!(
                    "{}: {}",
                    strings.text("device", "设备"),
                    runtime.session.device_id()
                ),
            ];
            Ok(View::Settings(crate::settings::SettingsView::new(
                // The Settings screen projects the file's last-validated
                // truth — a pending restart-required edit shows its file
                // value (marked "restart"), not the stale session value.
                &runtime.file_config(),
                crate::config::config_file(&runtime.paths),
                runtime.paths.home_dir.clone(),
                info,
            )))
        }
    }
}
/// Daily-note sources display as the same `YYYY-MM-DD` day label as memo cards. A source
/// whose file stem is not a day key in the workspace date format is shown by its stem, since
/// that stem is the only identity the task carries.
fn day_label(runtime: &TuiRuntime, source_path: &str) -> String {
    let stem = source_path
        .rsplit('/')
        .next()
        .unwrap_or(source_path)
        .trim_end_matches(".md");
    parse_date_key_with_format(stem, runtime.config().date_format).map_or_else(
        |_| stem.to_owned(),
        |date| format_date_key(date, DateFormat::YyyyMmDdHyphen),
    )
}
fn load_statistics(runtime: &TuiRuntime) -> Result<View, TuiError> {
    let zone = runtime.config().time_zone;
    let date = local_date(now_ms()?, &zone)?;
    let stats = runtime
        .session
        .statistics(&StatisticsSnapshot::new(zone.clone(), date))?;
    Ok(View::Statistics(StatsView {
        zone,
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
    req: crate::model::Req,
    text: &str,
) -> Result<RuntimeMessage, TuiError> {
    let today = local_date(now_ms()?, &runtime.config().time_zone)?;
    let (start, end) = match text.trim() {
        "today" | "今天" => (today, today),
        "yesterday" | "昨天" => {
            let (start, _) = day_bounds(today, &runtime.config().time_zone)?;
            let previous = start
                .checked_sub(1)
                .ok_or_else(|| TuiError::config("date is outside the supported range"))?;
            let date = local_date(previous, &runtime.config().time_zone)?;
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
    let (from, _) = day_bounds(start, &runtime.config().time_zone)?;
    let (_, until) = day_bounds(end, &runtime.config().time_zone)?;
    let label = format!(
        "{}…{}",
        format_date_key(start, DateFormat::YyyyMmDdHyphen),
        format_date_key(end, DateFormat::YyyyMmDdHyphen)
    );
    Ok(RuntimeMessage::Date {
        req,
        from,
        until,
        label,
    })
}
