//! Bounded projection walks used by search, tasks, review, reminders and
//! statistics, plus the revision-keyed projection those walks feed.
//!
//! Every cached entry is a pure function of `(query fingerprint,
//! high_water_revision)`: each committed projection write bumps the revision
//! in the same transaction, so an entry is only ever served while it still
//! describes the mounted projection. Screen re-entry therefore lands on a
//! memoized walk instead of re-running SQL; a commit invalidates by key,
//! never by clock or flag.

use std::num::NonZeroUsize;
use std::sync::Arc;
use std::time::Instant;

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoQuery, MemoSummary, PageCursor, ReminderSessionInput};

use crate::{
    error::{cancelled, storage, validation},
    session::WorkspaceSession,
    tasks::TaskItem,
};

/// Cancellation source for collectors that carry no supersede epoch of their
/// own — a shared walk never invents a cancel the caller did not ask for.
pub static NEVER_CANCELLED: &(dyn Fn() -> bool + Sync) = &|| false;

/// The shared-walk bound: room for every screen's distinct query shape while
/// superseded revisions evict least-recently-used first.
const SUMMARY_WALK_ENTRIES: NonZeroUsize = match NonZeroUsize::new(8) {
    Some(entries) => entries,
    None => unreachable!(),
};

/// Observable counters behind `WorkspaceSession::projection_query_stats` — the
/// receipt a screen re-entry leaves when it reuses a cached walk or rebuilds
/// after a commit.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ProjectionQueryStats {
    /// Paged summary walks actually executed against the store.
    pub summary_walks: u64,
    /// Walks served from the cached `(query, revision)` entry.
    pub summary_hits: u64,
    /// Microseconds spent inside paged summary walks.
    pub summary_walk_micros: u64,
    /// Task-list hydrate+parse builds performed.
    pub task_builds: u64,
    /// Task lists served at an unchanged revision.
    pub task_hits: u64,
    /// Microseconds spent building task views.
    pub task_build_micros: u64,
    /// Reminder-session extractions performed.
    pub reminder_builds: u64,
    /// Reminder session sets served at an unchanged revision.
    pub reminder_hits: u64,
}

/// One collected summary walk plus the projection revision it was read at.
/// Downstream derivations (corpora, task views, reminder sessions) must key
/// themselves by `revision`, not by a fresh read — only the walked revision is
/// proven self-consistent.
pub struct SummaryWalk {
    pub items: Arc<[MemoSummary]>,
    pub revision: u64,
}

/// The projection layer every `collect_summaries` consumer shares.
///
/// Entries are immutable snapshots keyed by the revision they were proven
/// against, so a committed write is the only invalidator and a torn read can
/// never be cached — the walk fails closed before install.
pub struct QueryProjection {
    summaries: lru::LruCache<(String, u64), Arc<[MemoSummary]>>,
    tasks: Option<(u64, Arc<[TaskItem]>)>,
    reminder_sessions: Option<(u64, Arc<[ReminderSessionInput]>)>,
    pub stats: ProjectionQueryStats,
}

impl QueryProjection {
    pub fn new() -> Self {
        Self {
            summaries: lru::LruCache::new(SUMMARY_WALK_ENTRIES),
            tasks: None,
            reminder_sessions: None,
            stats: ProjectionQueryStats::default(),
        }
    }

    fn summaries(&mut self, key: &(String, u64)) -> Option<Arc<[MemoSummary]>> {
        let hit = self.summaries.get(key).cloned()?;
        self.stats.summary_hits += 1;
        Some(hit)
    }

    fn record_summaries(&mut self, key: (String, u64), items: Arc<[MemoSummary]>, micros: u64) {
        self.stats.summary_walks += 1;
        self.stats.summary_walk_micros += micros;
        let _evicted = self.summaries.put(key, items);
    }

    pub fn tasks_at(&mut self, revision: u64) -> Option<Arc<[TaskItem]>> {
        let (cached_revision, items) = self.tasks.as_ref()?;
        if *cached_revision != revision {
            return None;
        }
        self.stats.task_hits += 1;
        Some(Arc::clone(items))
    }

    pub fn record_tasks(&mut self, revision: u64, items: Arc<[TaskItem]>, micros: u64) {
        self.stats.task_builds += 1;
        self.stats.task_build_micros += micros;
        self.tasks = Some((revision, items));
    }

    pub fn reminder_sessions_at(&mut self, revision: u64) -> Option<Arc<[ReminderSessionInput]>> {
        let (cached_revision, sessions) = self.reminder_sessions.as_ref()?;
        if *cached_revision != revision {
            return None;
        }
        self.stats.reminder_hits += 1;
        Some(Arc::clone(sessions))
    }

    pub fn record_reminder_sessions(
        &mut self,
        revision: u64,
        sessions: Arc<[ReminderSessionInput]>,
    ) {
        self.stats.reminder_builds += 1;
        self.reminder_sessions = Some((revision, sessions));
    }
}

fn projection_lock(
    session: &WorkspaceSession,
) -> Result<std::sync::MutexGuard<'_, QueryProjection>, LomoError> {
    session
        .query_projection
        .lock()
        .map_err(|error| storage("query_projection_poisoned", error.to_string()))
}

/// The collect entry every screen walk shares. `cancel` is polled at each page
/// boundary — `true` abandons the walk fail-closed before a partial result can
/// be served or cached.
///
/// # Errors
///
/// `query_walk_cancelled` (category `Cancelled`) when `cancel` fires;
/// `stale_projection` when a commit lands mid-walk, so the collected set never
/// describes one revision; projection read failures propagate.
pub fn collect_summaries(
    session: &WorkspaceSession,
    query: &MemoQuery,
    cancel: &dyn Fn() -> bool,
) -> Result<SummaryWalk, LomoError> {
    let revision = session.with_store(|store| Ok(store.high_water_revision()))?;
    let key = (walk_fingerprint(query), revision);
    let cached = projection_lock(session)?.summaries(&key);
    if let Some(items) = cached {
        return Ok(SummaryWalk { items, revision });
    }

    let start = Instant::now();
    let page_size = PageSize::new(256)?;
    let mut cursor: Option<PageCursor> = None;
    let mut items = Vec::new();
    loop {
        if cancel() {
            return Err(cancelled(
                "query_walk_cancelled",
                "the paged projection walk was superseded before it completed",
            ));
        }
        let page =
            session.with_reader(|store| store.query_memos(query, cursor.as_ref(), page_size))?;
        items.extend(page.items);
        match page.next_cursor {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }
    // A commit landing mid-walk makes the collected set describe no single
    // revision — reject it rather than cache a torn read.
    if revision != session.with_store(|store| Ok(store.high_water_revision()))? {
        return Err(validation(
            "stale_projection",
            "projection changed while collecting; retry the query",
        ));
    }
    let micros = u64::try_from(start.elapsed().as_micros()).unwrap_or(u64::MAX);
    let items: Arc<[MemoSummary]> = Arc::from(items);
    projection_lock(session)?.record_summaries(key, Arc::clone(&items), micros);
    Ok(SummaryWalk { items, revision })
}

/// The cache key covers everything that changes the walk's row set or order —
/// filters, fulltext term, sort — so two different queries can never alias.
fn walk_fingerprint(query: &MemoQuery) -> String {
    format!(
        "walk|{}|{}|{:?}",
        query.search_text.as_deref().unwrap_or(""),
        query.filters.fingerprint(),
        query.sort,
    )
}

/// Fail-closed drift guard shared by body-hydrating collectors.
///
/// A collected summary must still describe the hydrated snapshot. A content
/// rewrite between the two reads rejects the derived set instead of caching a
/// cross-revision mix; metadata-only writes (pin) leave the collected summary
/// authoritative.
pub fn verify_summary_version(
    expected: &MemoSummary,
    actual: &MemoSummary,
) -> Result<(), LomoError> {
    if expected.content_revision != actual.content_revision
        || expected.file_fingerprint != actual.file_fingerprint
    {
        return Err(validation(
            "stale_projection",
            "memo changed while collecting; retry the query",
        ));
    }
    Ok(())
}

pub fn default_query() -> MemoQuery {
    MemoQuery {
        search_text: None,
        filters: lomo_store::MemoFilters::default(),
        sort: lomo_store::MemoSort::default(),
    }
}
