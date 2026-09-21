//! Bounded projection walks used by search, tasks, review, and statistics.

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoQuery, MemoSummary, PageCursor};

use crate::session::WorkspaceSession;

pub fn collect_summaries(
    session: &WorkspaceSession,
    query: &MemoQuery,
) -> Result<Vec<MemoSummary>, LomoError> {
    let page_size = PageSize::new(256)?;
    let mut cursor: Option<PageCursor> = None;
    let mut items = Vec::new();
    loop {
        let page =
            session.with_reader(|store| store.query_memos(query, cursor.as_ref(), page_size))?;
        items.extend(page.items);
        match page.next_cursor {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }
    Ok(items)
}

pub fn default_query() -> MemoQuery {
    MemoQuery {
        search_text: None,
        filters: lomo_store::MemoFilters::default(),
        sort: lomo_store::MemoSort::default(),
    }
}
