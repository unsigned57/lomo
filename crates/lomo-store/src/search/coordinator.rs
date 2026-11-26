//! Lazy, zero-write-overhead Tantivy lifecycle coordinator bound to SQLite `search_generation`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, RwLock, Weak};

use rusqlite::{Connection, OptionalExtension, Rows, params};
use sha2::{Digest, Sha256};
use tantivy::directory::MmapDirectory;
use tantivy::merge_policy::LogMergePolicy;
use tantivy::query::{BooleanQuery, Occur, TermQuery};
use tantivy::schema::{IndexRecordOption, Schema, Value};
use tantivy::{Index, IndexReader, IndexWriter, ReloadPolicy, Searcher, TantivyDocument, Term};

use lomo_core::PageSize;

use crate::cursor::PageCursor;
use crate::error::{conflict, corruption, from_sqlite, storage, validation};
use crate::open::{compact_search_changelog, search_dir};
use crate::query::{
    MemoFilters, MemoPage, MemoQuery, MemoQueryBoundary, MemoQueryStart, MemoSort, MemoSummary,
    MemoWindowSides, hydrate_summaries_in_order, query_memo_page_tail,
};
use crate::schema::TOKENIZER_VERSION;

use super::collector::{collect_search_count, collect_search_page, collect_window_sides};
use super::query_builder::{
    BuiltTantivyQuery, StoreSearchMode, build_tantivy_query, search_fingerprint,
};
use super::schema::{
    DOC_KIND_WATERMARK, IndexableMemoRow, SEARCH_SCHEMA_VERSION, SearchFields, WATERMARK_DOC_ID,
    Watermark, build_memo_document, build_search_schema, build_watermark_document,
    register_tokenizers,
};

const WRITER_HEAP_BYTES: usize = 15_000_000;
const MERGE_MIN_NUM_SEGMENTS: usize = 4;
const CHANGELOG_COMPACTION_THRESHOLD: u64 = 1_024;
const SNAPSHOT_ALIGN_ATTEMPTS: usize = 3;
const DIRTY_BATCH_SIZE: usize = 200;

static COORDINATORS: LazyLock<Mutex<HashMap<PathBuf, Weak<SearchCoordinator>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Observable diagnostics for lazy initialization, sync count, and document totals.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchIndexStats {
    pub search_generation: Option<String>,
    pub is_loaded: bool,
    pub doc_count: u64,
    pub synced_seq: u64,
    pub high_water_revision: u64,
    pub incremental_syncs: u64,
    pub full_rebuilds: u64,
}

/// Request parameters for executing a paginated search inside [`SearchCoordinator`].
#[derive(Clone, Copy)]
pub struct SearchPageRequest<'a> {
    pub text: Option<&'a str>,
    pub mode: StoreSearchMode,
    pub filters: &'a MemoFilters,
    pub sort: &'a MemoSort,
    pub boundary: Option<&'a MemoQueryBoundary>,
    pub start: MemoQueryStart<'a>,
    pub page_size: PageSize,
}

struct LoadedSearchIndex {
    index: Index,
    reader: IndexReader,
    searcher: Searcher,
    watermark: Watermark,
    scoring_snapshot_key: String,
}

impl LoadedSearchIndex {
    fn new(index: Index, reader: IndexReader, searcher: Searcher, watermark: Watermark) -> Self {
        let scoring_snapshot_key = compute_scoring_snapshot_key(&watermark, &searcher);
        Self {
            index,
            reader,
            searcher,
            watermark,
            scoring_snapshot_key,
        }
    }
}

fn compute_scoring_snapshot_key(watermark: &Watermark, searcher: &Searcher) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"lomo-search-snapshot-v1|");
    let gen_len = u64::try_from(watermark.search_generation.len()).unwrap_or(u64::MAX);
    hasher.update(gen_len.to_le_bytes());
    hasher.update(watermark.search_generation.as_bytes());
    hasher.update(watermark.synced_seq.to_le_bytes());
    let segments = searcher.generation().segments();
    let seg_count = u64::try_from(segments.len()).unwrap_or(u64::MAX);
    hasher.update(seg_count.to_le_bytes());
    for (segment_id, delete_opstamp) in segments {
        let uuid = segment_id.uuid_string();
        let uuid_len = u64::try_from(uuid.len()).unwrap_or(u64::MAX);
        hasher.update(uuid_len.to_le_bytes());
        hasher.update(uuid.as_bytes());
        match delete_opstamp {
            Some(opstamp) => {
                hasher.update([1_u8]);
                hasher.update(opstamp.to_le_bytes());
            }
            None => hasher.update([0_u8]),
        }
    }
    lomo_core::hex_encode(&hasher.finalize())
}

struct SqliteSearchMeta {
    search_generation: String,
    high_water_revision: u64,
    max_seq: u64,
    min_seq: u64,
}

/// Coordinates lazy build, incremental changelog catch-up, generation swaps, and aligned queries.
pub struct SearchCoordinator {
    workspace_root: PathBuf,
    schema: Schema,
    fields: SearchFields,
    sync_mutex: Mutex<()>,
    loaded: RwLock<Option<Arc<LoadedSearchIndex>>>,
    compaction_pending: AtomicBool,
    incremental_syncs: AtomicU64,
    full_rebuilds: AtomicU64,
}

impl SearchCoordinator {
    /// Returns the shared coordinator for `workspace_root` without performing any disk I/O.
    #[must_use]
    pub fn for_root(workspace_root: &Path) -> Arc<Self> {
        let key = std::fs::canonicalize(workspace_root)
            .unwrap_or_else(|_err| workspace_root.to_path_buf());
        let mut guard = match COORDINATORS.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.retain(|_path, weak| weak.strong_count() > 0);
        if let Some(existing) = guard.get(&key).and_then(Weak::upgrade) {
            return existing;
        }
        let (schema, fields) = build_search_schema();
        let created = Arc::new(Self {
            workspace_root: key.clone(),
            schema,
            fields,
            sync_mutex: Mutex::new(()),
            loaded: RwLock::new(None),
            compaction_pending: AtomicBool::new(false),
            incremental_syncs: AtomicU64::new(0),
            full_rebuilds: AtomicU64::new(0),
        });
        let _prev = guard.insert(key, Arc::downgrade(&created));
        created
    }

    /// Ensures the Tantivy index is materialized and synced up to current SQLite projection state.
    ///
    /// # Errors
    ///
    /// Propagates SQLite read, Tantivy build/sync, or changelog compaction errors.
    pub fn warmup_with_connection(
        &self,
        connection: &Connection,
        allow_compaction: bool,
    ) -> Result<SearchIndexStats, lomo_core::LomoError> {
        let _loaded = self.ensure_synced(connection, allow_compaction)?;
        Ok(self.stats())
    }

    /// Returns whether a prior read-only sync deferred `search_changelog` compaction.
    #[must_use]
    pub fn has_pending_changelog_compaction(&self) -> bool {
        self.compaction_pending.load(Ordering::Acquire)
    }

    /// Compacts consumed `search_changelog` entries using a writable `Store` connection when a
    /// prior read-only sync crossed the compaction threshold.
    ///
    /// # Errors
    ///
    /// Propagates SQLite read, delete, or commit errors after restoring the pending flag.
    pub fn compact_pending_changelog(
        &self,
        connection: &Connection,
    ) -> Result<bool, lomo_core::LomoError> {
        if !self.compaction_pending.swap(false, Ordering::AcqRel) {
            return Ok(false);
        }
        let Some(loaded) = self.read_loaded() else {
            return Ok(false);
        };
        let result = (|| -> Result<bool, lomo_core::LomoError> {
            let tx = connection
                .unchecked_transaction()
                .map_err(|error| from_sqlite(&error))?;
            let meta = read_sqlite_search_meta(&tx)?;
            let synced_seq = loaded.watermark.synced_seq;
            if meta.search_generation != loaded.watermark.search_generation
                || synced_seq > meta.max_seq
                || !should_compact_changelog(&meta, synced_seq)
            {
                return Ok(false);
            }
            compact_search_changelog(&tx, synced_seq)?;
            tx.commit().map_err(|error| from_sqlite(&error))?;
            Ok(true)
        })();
        if result.is_err() {
            self.compaction_pending.store(true, Ordering::Release);
        }
        result
    }

    /// Returns lock-free coordinator statistics (never triggers disk initialization on unread stores).
    #[must_use]
    pub fn stats(&self) -> SearchIndexStats {
        self.read_loaded().map_or_else(
            || SearchIndexStats {
                search_generation: None,
                is_loaded: false,
                doc_count: 0,
                synced_seq: 0,
                high_water_revision: 0,
                incremental_syncs: self.incremental_syncs.load(Ordering::Relaxed),
                full_rebuilds: self.full_rebuilds.load(Ordering::Relaxed),
            },
            |state| {
                let doc_count = state.searcher.num_docs().saturating_sub(1);
                SearchIndexStats {
                    search_generation: Some(state.watermark.search_generation.clone()),
                    is_loaded: true,
                    doc_count,
                    synced_seq: state.watermark.synced_seq,
                    high_water_revision: state.watermark.high_water_revision,
                    incremental_syncs: self.incremental_syncs.load(Ordering::Relaxed),
                    full_rebuilds: self.full_rebuilds.load(Ordering::Relaxed),
                }
            },
        )
    }

    /// Executes a snapshot-aligned search page and hydrates returned memo rows within the same
    /// SQLite read transaction.
    ///
    /// # Errors
    ///
    /// Propagates query/boundary validation, cursor staleness, SQLite/Tantivy sync, or snapshot conflict errors.
    pub fn search_memos(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        request: SearchPageRequest<'_>,
    ) -> Result<MemoPage, lomo_core::LomoError> {
        if let Some(boundary) = request.boundary {
            boundary.validate()?;
        }
        let BuiltTantivyQuery {
            has_query,
            query_fingerprint_key,
            query,
        } = build_tantivy_query(&self.fields, request.text, request.mode, request.filters)?;

        let page_limit = usize::try_from(request.page_size.get())
            .map_err(|error| validation("invalid_page_size", &error.to_string()))?;

        let (items, items_before, items_after, fingerprint, revision) = self
            .with_aligned_snapshot(
                connection,
                allow_compaction,
                |tx, _meta, loaded, revision| {
                    let fingerprint = search_fingerprint(
                        query_fingerprint_key.as_deref(),
                        request.mode,
                        request.filters,
                        request.sort,
                        request.boundary,
                        &loaded.scoring_snapshot_key,
                    );
                    let collected = collect_search_page(
                        &loaded.searcher,
                        &self.fields,
                        query.as_ref(),
                        request.sort,
                        has_query,
                        request.boundary,
                        request.start,
                        page_limit,
                        &fingerprint,
                        revision,
                    )?;
                    let items = hydrate_summaries_in_order(tx, &collected.items, has_query)?;
                    Ok((
                        items,
                        collected.items_before,
                        collected.items_after,
                        fingerprint,
                        revision,
                    ))
                },
            )?;

        query_memo_page_tail(
            items,
            items_before,
            items_after,
            request.sort,
            fingerprint,
            revision,
        )
    }

    /// Walks one aligned page expecting an exact SQLite `expected_revision` (matching `StoreReader`).
    ///
    /// # Errors
    ///
    /// Returns `stale_projection` if the projection moved away from `expected_revision`, or propagates
    /// query/storage errors.
    pub fn search_walk_page_at(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        query_spec: &MemoQuery,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
        expected_revision: u64,
    ) -> Result<(Vec<MemoSummary>, Option<PageCursor>), lomo_core::LomoError> {
        let BuiltTantivyQuery {
            has_query,
            query_fingerprint_key,
            query,
        } = build_tantivy_query(
            &self.fields,
            query_spec.search_text.as_deref(),
            StoreSearchMode::Fulltext,
            &query_spec.filters,
        )?;
        let start = cursor.map_or(MemoQueryStart::Head, MemoQueryStart::After);
        let page_limit = usize::try_from(page_size.get())
            .map_err(|error| validation("invalid_page_size", &error.to_string()))?;

        let (items, items_after, fingerprint, revision) = self.with_aligned_snapshot(
            connection,
            allow_compaction,
            |tx, _meta, loaded, revision| {
                if revision != expected_revision {
                    return Err(conflict(
                        "stale_projection",
                        "projection moved while reading a search walk page",
                    ));
                }
                let fingerprint = search_fingerprint(
                    query_fingerprint_key.as_deref(),
                    StoreSearchMode::Fulltext,
                    &query_spec.filters,
                    &query_spec.sort,
                    None,
                    &loaded.scoring_snapshot_key,
                );
                let collected = collect_search_page(
                    &loaded.searcher,
                    &self.fields,
                    query.as_ref(),
                    &query_spec.sort,
                    has_query,
                    None,
                    start,
                    page_limit,
                    &fingerprint,
                    revision,
                )?;
                let items = hydrate_summaries_in_order(tx, &collected.items, has_query)?;
                Ok((items, collected.items_after, fingerprint, revision))
            },
        )?;

        let page = query_memo_page_tail(
            items,
            0,
            items_after,
            &query_spec.sort,
            fingerprint,
            revision,
        )?;
        Ok((page.items, page.next_cursor))
    }

    /// Counts matching memos under aligned SQLite + Tantivy snapshot semantics.
    ///
    /// # Errors
    ///
    /// Propagates query validation or SQLite/Tantivy sync errors.
    pub fn search_count(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        query: &MemoQuery,
        mode: StoreSearchMode,
    ) -> Result<u64, lomo_core::LomoError> {
        let built = build_tantivy_query(
            &self.fields,
            query.search_text.as_deref(),
            mode,
            &query.filters,
        )?;
        self.with_aligned_snapshot(connection, allow_compaction, |_tx, _meta, loaded, _rev| {
            collect_search_count(
                &loaded.searcher,
                &self.fields,
                built.query.as_ref(),
                &query.sort,
                None,
            )
        })
    }

    /// Computes live `above` and `below` ids for bounded window refresh across both search modes.
    ///
    /// # Errors
    ///
    /// Propagates query/cursor validation or SQLite/Tantivy sync errors.
    #[expect(
        clippy::too_many_arguments,
        reason = "mirrors Store::matching_memo_window arguments plus StoreSearchMode and compaction"
    )]
    pub fn search_window_sides(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        query: &MemoQuery,
        mode: StoreSearchMode,
        memo_ids: &[String],
        head: Option<&PageCursor>,
        tail: Option<&PageCursor>,
    ) -> Result<MemoWindowSides, lomo_core::LomoError> {
        if memo_ids.is_empty() || (head.is_none() && tail.is_none()) {
            return Ok(MemoWindowSides::default());
        }
        let built = build_tantivy_query(
            &self.fields,
            query.search_text.as_deref(),
            mode,
            &query.filters,
        )?;
        self.with_aligned_snapshot(connection, allow_compaction, |_tx, _meta, loaded, _rev| {
            collect_window_sides(
                &loaded.searcher,
                &self.fields,
                built.query.as_ref(),
                &query.sort,
                built.has_query,
                memo_ids,
                head,
                tail,
            )
        })
    }

    fn with_aligned_snapshot<F, T>(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        mut action: F,
    ) -> Result<T, lomo_core::LomoError>
    where
        F: FnMut(
            &Connection,
            &SqliteSearchMeta,
            &LoadedSearchIndex,
            u64,
        ) -> Result<T, lomo_core::LomoError>,
    {
        for _attempt in 0..SNAPSHOT_ALIGN_ATTEMPTS {
            let loaded = self.ensure_synced(connection, allow_compaction)?;
            let tx = connection
                .unchecked_transaction()
                .map_err(|error| from_sqlite(&error))?;
            let meta = read_sqlite_search_meta(&tx)?;
            if meta.search_generation == loaded.watermark.search_generation
                && meta.max_seq == loaded.watermark.synced_seq
            {
                let revision = meta.high_water_revision;
                let result = action(&tx, &meta, &loaded, revision)?;
                drop(tx);
                return Ok(result);
            }
            drop(tx);
        }
        Err(conflict(
            "stale_snapshot",
            "search index alignment did not stabilize after concurrent writes",
        ))
    }

    fn ensure_synced(
        &self,
        connection: &Connection,
        allow_compaction: bool,
    ) -> Result<Arc<LoadedSearchIndex>, lomo_core::LomoError> {
        if let Some(existing) = self.read_loaded() {
            let meta = read_sqlite_search_meta(connection)?;
            if meta.search_generation == existing.watermark.search_generation
                && meta.max_seq == existing.watermark.synced_seq
                && generation_dir(&self.workspace_root, &existing.watermark.search_generation)
                    .join("meta.json")
                    .is_file()
            {
                self.handle_post_sync_compaction(
                    connection,
                    allow_compaction,
                    &meta,
                    existing.watermark.synced_seq,
                )?;
                return Ok(existing);
            }
        }

        let _sync_guard = match self.sync_mutex.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };

        let tx = connection
            .unchecked_transaction()
            .map_err(|error| from_sqlite(&error))?;
        let meta = read_sqlite_search_meta(&tx)?;

        if let Some(existing) = self.read_loaded() {
            let exists_on_disk =
                generation_dir(&self.workspace_root, &existing.watermark.search_generation)
                    .join("meta.json")
                    .is_file();
            if !exists_on_disk {
                self.write_loaded(None);
            } else if existing.watermark.search_generation == meta.search_generation
                && existing.watermark.synced_seq <= meta.max_seq
                && !changelog_has_gap(&meta, existing.watermark.synced_seq)
            {
                if meta.max_seq == existing.watermark.synced_seq {
                    drop(tx);
                    self.handle_post_sync_compaction(
                        connection,
                        allow_compaction,
                        &meta,
                        existing.watermark.synced_seq,
                    )?;
                    return Ok(existing);
                }
                let updated = self.apply_incremental_sync(&tx, &meta, &existing)?;
                drop(tx);
                self.write_loaded(Some(Arc::clone(&updated)));
                self.handle_post_sync_compaction(
                    connection,
                    allow_compaction,
                    &meta,
                    updated.watermark.synced_seq,
                )?;
                return Ok(updated);
            }
        }

        if let Some(opened) = self.try_open_existing(&meta)? {
            if meta.max_seq == opened.watermark.synced_seq {
                drop(tx);
                let shared = Arc::new(opened);
                self.cleanup_stale_generations(&meta.search_generation);
                self.write_loaded(Some(Arc::clone(&shared)));
                self.handle_post_sync_compaction(
                    connection,
                    allow_compaction,
                    &meta,
                    shared.watermark.synced_seq,
                )?;
                return Ok(shared);
            }
            if !changelog_has_gap(&meta, opened.watermark.synced_seq) {
                let updated = self.apply_incremental_sync(&tx, &meta, &Arc::new(opened))?;
                drop(tx);
                self.cleanup_stale_generations(&meta.search_generation);
                self.write_loaded(Some(Arc::clone(&updated)));
                self.handle_post_sync_compaction(
                    connection,
                    allow_compaction,
                    &meta,
                    updated.watermark.synced_seq,
                )?;
                return Ok(updated);
            }
        }

        let rebuilt = Arc::new(self.rebuild_generation(&tx, &meta)?);
        drop(tx);
        self.cleanup_stale_generations(&meta.search_generation);
        self.write_loaded(Some(Arc::clone(&rebuilt)));
        self.handle_post_sync_compaction(
            connection,
            allow_compaction,
            &meta,
            rebuilt.watermark.synced_seq,
        )?;
        Ok(rebuilt)
    }

    fn handle_post_sync_compaction(
        &self,
        connection: &Connection,
        allow_compaction: bool,
        meta: &SqliteSearchMeta,
        synced_seq: u64,
    ) -> Result<(), lomo_core::LomoError> {
        if !should_compact_changelog(meta, synced_seq) {
            return Ok(());
        }
        if allow_compaction {
            compact_search_changelog(connection, synced_seq)?;
            self.compaction_pending.store(false, Ordering::Release);
        } else {
            self.compaction_pending.store(true, Ordering::Release);
        }
        Ok(())
    }

    fn try_open_existing(
        &self,
        meta: &SqliteSearchMeta,
    ) -> Result<Option<LoadedSearchIndex>, lomo_core::LomoError> {
        let gen_dir = generation_dir(&self.workspace_root, &meta.search_generation);
        if !gen_dir.join("meta.json").is_file() {
            return Ok(None);
        }
        let Ok(dir) = MmapDirectory::open(&gen_dir) else {
            return Ok(None);
        };
        let Ok(index) = Index::open(dir) else {
            return Ok(None);
        };
        if index.schema() != self.schema {
            return Ok(None);
        }
        register_tokenizers(&index);
        let Ok(reader) = index
            .reader_builder()
            .reload_policy(ReloadPolicy::Manual)
            .try_into()
        else {
            return Ok(None);
        };
        let searcher = reader.searcher();
        let Some(watermark) = self.read_watermark(&searcher)? else {
            return Ok(None);
        };
        if watermark.search_generation != meta.search_generation
            || watermark.schema_version != SEARCH_SCHEMA_VERSION
            || watermark.tokenizer_version != TOKENIZER_VERSION
            || watermark.synced_seq > meta.max_seq
        {
            return Ok(None);
        }
        Ok(Some(LoadedSearchIndex::new(
            index, reader, searcher, watermark,
        )))
    }

    fn read_watermark(
        &self,
        searcher: &Searcher,
    ) -> Result<Option<Watermark>, lomo_core::LomoError> {
        let query = BooleanQuery::new(vec![
            (
                Occur::Must,
                Box::new(TermQuery::new(
                    Term::from_field_u64(self.fields.doc_kind, DOC_KIND_WATERMARK),
                    IndexRecordOption::Basic,
                )),
            ),
            (
                Occur::Must,
                Box::new(TermQuery::new(
                    Term::from_field_text(self.fields.memo_id, WATERMARK_DOC_ID),
                    IndexRecordOption::Basic,
                )),
            ),
        ]);
        let docs = searcher
            .search(
                &query,
                &tantivy::collector::TopDocs::with_limit(1).order_by_score(),
            )
            .map_err(|error| {
                storage(
                    "tantivy_watermark_read_failed",
                    &format!("failed querying watermark: {error}"),
                )
            })?;
        let Some((_score, addr)) = docs.into_iter().next() else {
            return Ok(None);
        };
        let doc: TantivyDocument = searcher.doc(addr).map_err(|error| {
            storage(
                "tantivy_watermark_doc_failed",
                &format!("failed loading watermark doc: {error}"),
            )
        })?;
        Ok(self.parse_watermark_doc(&doc))
    }

    fn parse_watermark_doc(&self, doc: &TantivyDocument) -> Option<Watermark> {
        let gen_str = doc
            .get_first(self.fields.wm_search_generation)
            .and_then(|val| val.as_str())?;
        let schema_v_raw = doc
            .get_first(self.fields.wm_schema_version)
            .and_then(|val| val.as_u64())?;
        let schema_version = match u32::try_from(schema_v_raw) {
            Ok(v) => v,
            Err(_err) => return None,
        };
        let tok_v_raw = doc
            .get_first(self.fields.wm_tokenizer_version)
            .and_then(|val| val.as_u64())?;
        let tokenizer_version = match u32::try_from(tok_v_raw) {
            Ok(v) => v,
            Err(_err) => return None,
        };
        let synced_seq = doc
            .get_first(self.fields.wm_synced_seq)
            .and_then(|val| val.as_u64())?;
        let high_water_revision = doc
            .get_first(self.fields.wm_high_water_revision)
            .and_then(|val| val.as_u64())?;
        Some(Watermark {
            search_generation: gen_str.to_owned(),
            schema_version,
            tokenizer_version,
            synced_seq,
            high_water_revision,
        })
    }

    fn apply_incremental_sync(
        &self,
        connection: &Connection,
        meta: &SqliteSearchMeta,
        current: &Arc<LoadedSearchIndex>,
    ) -> Result<Arc<LoadedSearchIndex>, lomo_core::LomoError> {
        let dirty_ids =
            read_dirty_memo_ids(connection, current.watermark.synced_seq, meta.max_seq)?;
        let writer = create_writer(&current.index)?;
        for chunk in dirty_ids.chunks(DIRTY_BATCH_SIZE) {
            for memo_id in chunk {
                let _op = writer.delete_term(Term::from_field_text(self.fields.memo_id, memo_id));
            }
            stream_indexable_rows_by_ids(connection, chunk, |row| {
                let doc = build_memo_document(&self.fields, &row);
                let _op = writer.add_document(doc).map_err(|error| {
                    storage(
                        "tantivy_add_doc_failed",
                        &format!("failed adding updated memo doc: {error}"),
                    )
                })?;
                Ok(())
            })?;
        }
        let watermark = Watermark {
            search_generation: meta.search_generation.clone(),
            schema_version: SEARCH_SCHEMA_VERSION,
            tokenizer_version: TOKENIZER_VERSION,
            synced_seq: meta.max_seq,
            high_water_revision: meta.high_water_revision,
        };
        let _del_wm =
            writer.delete_term(Term::from_field_text(self.fields.memo_id, WATERMARK_DOC_ID));
        let _add_wm = writer
            .add_document(build_watermark_document(&self.fields, &watermark))
            .map_err(|error| {
                storage(
                    "tantivy_watermark_write_failed",
                    &format!("failed writing search watermark: {error}"),
                )
            })?;
        commit_and_drop(writer)?;
        current.reader.reload().map_err(|error| {
            storage(
                "tantivy_reload_failed",
                &format!("failed reloading Tantivy reader: {error}"),
            )
        })?;
        let searcher = current.reader.searcher();
        let _count = self.incremental_syncs.fetch_add(1, Ordering::Relaxed);
        Ok(Arc::new(LoadedSearchIndex::new(
            current.index.clone(),
            current.reader.clone(),
            searcher,
            watermark,
        )))
    }

    fn rebuild_generation(
        &self,
        connection: &Connection,
        meta: &SqliteSearchMeta,
    ) -> Result<LoadedSearchIndex, lomo_core::LomoError> {
        let root_search_dir = search_dir(&self.workspace_root);
        std::fs::create_dir_all(&root_search_dir).map_err(|error| {
            storage(
                "search_dir_create_failed",
                &format!(
                    "cannot create search directory {}: {error}",
                    root_search_dir.display()
                ),
            )
        })?;

        let nonce = random_hex_nonce()?;
        let tmp_dir = root_search_dir.join(format!("gen-{}.tmp-{nonce}", meta.search_generation));
        let final_dir = generation_dir(&self.workspace_root, &meta.search_generation);
        std::fs::create_dir_all(&tmp_dir).map_err(|error| {
            storage(
                "search_staging_create_failed",
                &format!(
                    "cannot create staging search dir {}: {error}",
                    tmp_dir.display()
                ),
            )
        })?;

        let watermark = match self.write_staged_generation(connection, meta, &tmp_dir) {
            Ok(wm) => wm,
            Err(error) => {
                let _rm = std::fs::remove_dir_all(&tmp_dir);
                return Err(error);
            }
        };

        self.swap_and_open_generation(&tmp_dir, &final_dir, &root_search_dir, &nonce, watermark)
    }

    fn write_staged_generation(
        &self,
        connection: &Connection,
        meta: &SqliteSearchMeta,
        tmp_dir: &Path,
    ) -> Result<Watermark, lomo_core::LomoError> {
        let dir = MmapDirectory::open(tmp_dir).map_err(|error| {
            storage(
                "tantivy_dir_open_failed",
                &format!("cannot open staging MmapDirectory: {error}"),
            )
        })?;
        let index = Index::create(dir, self.schema.clone(), tantivy::IndexSettings::default())
            .map_err(|error| {
                storage(
                    "tantivy_index_create_failed",
                    &format!("cannot create Tantivy index: {error}"),
                )
            })?;
        register_tokenizers(&index);
        let writer = create_writer(&index)?;

        stream_all_indexable_rows(connection, |row| {
            let _op = writer
                .add_document(build_memo_document(&self.fields, &row))
                .map_err(|error| {
                    storage(
                        "tantivy_add_doc_failed",
                        &format!("failed indexing memo row: {error}"),
                    )
                })?;
            Ok(())
        })?;
        let watermark = Watermark {
            search_generation: meta.search_generation.clone(),
            schema_version: SEARCH_SCHEMA_VERSION,
            tokenizer_version: TOKENIZER_VERSION,
            synced_seq: meta.max_seq,
            high_water_revision: meta.high_water_revision,
        };
        let _op = writer
            .add_document(build_watermark_document(&self.fields, &watermark))
            .map_err(|error| {
                storage(
                    "tantivy_watermark_write_failed",
                    &format!("failed adding watermark doc: {error}"),
                )
            })?;
        commit_and_drop(writer)?;
        Ok(watermark)
    }

    fn swap_and_open_generation(
        &self,
        tmp_dir: &Path,
        final_dir: &Path,
        root_search_dir: &Path,
        nonce: &str,
        watermark: Watermark,
    ) -> Result<LoadedSearchIndex, lomo_core::LomoError> {
        self.write_loaded(None);
        let displaced_dir = root_search_dir.join(format!("old-{nonce}"));
        if final_dir.exists() {
            std::fs::rename(final_dir, &displaced_dir).map_err(|error| {
                let _rm = std::fs::remove_dir_all(tmp_dir);
                storage(
                    "search_swap_failed",
                    &format!(
                        "cannot retire previous search index {}: {error}",
                        final_dir.display()
                    ),
                )
            })?;
        }
        if let Err(error) = std::fs::rename(tmp_dir, final_dir) {
            if displaced_dir.exists() {
                let _restore = std::fs::rename(&displaced_dir, final_dir);
            }
            let _rm = std::fs::remove_dir_all(tmp_dir);
            return Err(storage(
                "search_swap_failed",
                &format!(
                    "cannot install search generation {}: {error}",
                    final_dir.display()
                ),
            ));
        }
        if displaced_dir.exists() {
            let _rm = std::fs::remove_dir_all(&displaced_dir);
        }

        let dir = MmapDirectory::open(final_dir).map_err(|error| {
            storage(
                "tantivy_dir_open_failed",
                &format!(
                    "cannot open committed Tantivy index {}: {error}",
                    final_dir.display()
                ),
            )
        })?;
        let index = Index::open(dir).map_err(|error| {
            storage(
                "tantivy_index_open_failed",
                &format!("cannot open committed Tantivy index: {error}"),
            )
        })?;
        register_tokenizers(&index);
        let reader = index
            .reader_builder()
            .reload_policy(ReloadPolicy::Manual)
            .try_into()
            .map_err(|error| {
                storage(
                    "tantivy_reader_failed",
                    &format!("cannot open Tantivy reader: {error}"),
                )
            })?;
        let searcher = reader.searcher();
        let _count = self.full_rebuilds.fetch_add(1, Ordering::Relaxed);
        Ok(LoadedSearchIndex::new(index, reader, searcher, watermark))
    }

    fn read_loaded(&self) -> Option<Arc<LoadedSearchIndex>> {
        match self.loaded.read() {
            Ok(guard) => guard.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        }
    }

    fn write_loaded(&self, next: Option<Arc<LoadedSearchIndex>>) {
        let mut guard = match self.loaded.write() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        *guard = next;
    }

    fn cleanup_stale_generations(&self, active_generation: &str) {
        let dir = search_dir(&self.workspace_root);
        let Ok(entries) = std::fs::read_dir(&dir) else {
            return;
        };
        let keep_name = format!("gen-{active_generation}");
        for entry in entries.flatten() {
            let name = entry.file_name();
            if name.to_string_lossy() != keep_name && entry.path().is_dir() {
                let _rm = std::fs::remove_dir_all(entry.path());
            }
        }
    }
}

fn generation_dir(workspace_root: &Path, generation: &str) -> PathBuf {
    search_dir(workspace_root).join(format!("gen-{generation}"))
}

fn create_writer(index: &Index) -> Result<IndexWriter, lomo_core::LomoError> {
    let writer = index
        .writer_with_num_threads(1, WRITER_HEAP_BYTES)
        .map_err(|error| {
            storage(
                "tantivy_writer_failed",
                &format!("cannot open Tantivy writer: {error}"),
            )
        })?;
    let mut policy = LogMergePolicy::default();
    policy.set_min_num_segments(MERGE_MIN_NUM_SEGMENTS);
    writer.set_merge_policy(Box::new(policy));
    Ok(writer)
}

fn commit_and_drop(mut writer: IndexWriter) -> Result<(), lomo_core::LomoError> {
    let _op_stamp = writer.commit().map_err(|error| {
        storage(
            "tantivy_commit_failed",
            &format!("cannot commit Tantivy index: {error}"),
        )
    })?;
    drop(writer);
    Ok(())
}

fn read_sqlite_search_meta(
    connection: &Connection,
) -> Result<SqliteSearchMeta, lomo_core::LomoError> {
    let search_generation: String = connection
        .query_row(
            "SELECT value FROM store_meta WHERE key = 'search_generation'",
            [],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?
        .ok_or_else(|| {
            corruption(
                "missing_search_generation",
                "store_meta is missing search_generation",
            )
        })?;
    let high_water_str: Option<String> = connection
        .query_row(
            "SELECT value FROM store_meta WHERE key = 'high_water_revision'",
            [],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    let high_water_revision = match high_water_str {
        Some(raw) => raw.parse::<u64>().map_err(|error| {
            corruption(
                "invalid_high_water_revision",
                &format!("store_meta high_water_revision is not u64: {error}"),
            )
        })?,
        None => 0,
    };
    let (max_seq_i64, min_seq_i64): (i64, i64) = connection
        .query_row(
            "SELECT COALESCE(MAX(seq), 0), COALESCE(MIN(seq), 0) FROM search_changelog",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| from_sqlite(&error))?;
    let max_seq = u64::try_from(max_seq_i64).map_err(|error| {
        corruption(
            "invalid_changelog_seq",
            &format!("search_changelog MAX(seq) is negative: {error}"),
        )
    })?;
    let min_seq = u64::try_from(min_seq_i64).map_err(|error| {
        corruption(
            "invalid_changelog_seq",
            &format!("search_changelog MIN(seq) is negative: {error}"),
        )
    })?;
    Ok(SqliteSearchMeta {
        search_generation,
        high_water_revision,
        max_seq,
        min_seq,
    })
}

const fn changelog_has_gap(meta: &SqliteSearchMeta, synced_seq: u64) -> bool {
    synced_seq < meta.max_seq && synced_seq.saturating_add(1) < meta.min_seq
}

const fn should_compact_changelog(meta: &SqliteSearchMeta, synced_seq: u64) -> bool {
    meta.min_seq > 0
        && synced_seq >= meta.min_seq
        && synced_seq.saturating_sub(meta.min_seq).saturating_add(1)
            >= CHANGELOG_COMPACTION_THRESHOLD
}

fn read_dirty_memo_ids(
    connection: &Connection,
    after_seq: u64,
    through_seq: u64,
) -> Result<Vec<String>, lomo_core::LomoError> {
    let after = i64::try_from(after_seq).map_err(|error| {
        corruption(
            "invalid_changelog_seq",
            &format!("synced_seq exceeds i64: {error}"),
        )
    })?;
    let through = i64::try_from(through_seq).map_err(|error| {
        corruption(
            "invalid_changelog_seq",
            &format!("through_seq exceeds i64: {error}"),
        )
    })?;
    let mut stmt = connection
        .prepare(
            "SELECT DISTINCT memo_id FROM search_changelog WHERE seq > ?1 AND seq <= ?2 ORDER BY memo_id ASC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = stmt
        .query_map(params![after, through], |row| row.get::<_, String>(0))
        .map_err(|error| from_sqlite(&error))?;
    let mut ids = Vec::new();
    for row in rows {
        ids.push(row.map_err(|error| from_sqlite(&error))?);
    }
    Ok(ids)
}

fn stream_all_indexable_rows(
    connection: &Connection,
    visit: impl FnMut(IndexableMemoRow) -> Result<(), lomo_core::LomoError>,
) -> Result<(), lomo_core::LomoError> {
    let mut tag_stmt = connection
        .prepare(
            "SELECT mt.memo_id, t.name \
             FROM memo_tag mt JOIN tag t ON t.id = mt.tag_id \
             ORDER BY mt.memo_id ASC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let mut memo_stmt = connection
        .prepare(
            "SELECT memo_id, source_path, COALESCE(body, body_preview), \
             has_todo, has_attachment, has_url, is_pinned, is_trashed, \
             pending_operation_id IS NOT NULL, created_at_ms, updated_at_ms \
             FROM memo \
             ORDER BY memo_id ASC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let mut tag_rows = tag_stmt.query([]).map_err(|error| from_sqlite(&error))?;
    let mut memo_rows = memo_stmt.query([]).map_err(|error| from_sqlite(&error))?;
    stream_merge_joined_rows(&mut memo_rows, &mut tag_rows, visit)
}

fn stream_indexable_rows_by_ids(
    connection: &Connection,
    memo_ids: &[String],
    visit: impl FnMut(IndexableMemoRow) -> Result<(), lomo_core::LomoError>,
) -> Result<(), lomo_core::LomoError> {
    if memo_ids.is_empty() {
        return Ok(());
    }
    let ids_json = serde_json::to_string(memo_ids).map_err(|error| {
        corruption(
            "invalid_memo_id_batch",
            &format!("cannot encode dirty memo id batch: {error}"),
        )
    })?;
    let mut tag_stmt = connection
        .prepare(
            "SELECT mt.memo_id, t.name \
             FROM memo_tag mt JOIN tag t ON t.id = mt.tag_id \
             WHERE mt.memo_id IN (SELECT value FROM json_each(?1)) \
             ORDER BY mt.memo_id ASC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let mut memo_stmt = connection
        .prepare(
            "SELECT memo_id, source_path, COALESCE(body, body_preview), \
             has_todo, has_attachment, has_url, is_pinned, is_trashed, \
             pending_operation_id IS NOT NULL, created_at_ms, updated_at_ms \
             FROM memo \
             WHERE memo_id IN (SELECT value FROM json_each(?1)) \
             ORDER BY memo_id ASC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let mut tag_rows = tag_stmt
        .query(params![&ids_json])
        .map_err(|error| from_sqlite(&error))?;
    let mut memo_rows = memo_stmt
        .query(params![&ids_json])
        .map_err(|error| from_sqlite(&error))?;
    stream_merge_joined_rows(&mut memo_rows, &mut tag_rows, visit)
}

fn stream_merge_joined_rows(
    memo_rows: &mut Rows<'_>,
    tag_rows: &mut Rows<'_>,
    mut visit: impl FnMut(IndexableMemoRow) -> Result<(), lomo_core::LomoError>,
) -> Result<(), lomo_core::LomoError> {
    let mut pending_tag = next_tag_pair(tag_rows)?;
    while let Some(memo_row) = memo_rows.next().map_err(|error| from_sqlite(&error))? {
        let mut row = row_to_indexable(memo_row).map_err(|error| from_sqlite(&error))?;
        while let Some((ref tag_memo_id, _)) = pending_tag {
            match tag_memo_id.as_str().cmp(row.memo_id.as_str()) {
                std::cmp::Ordering::Less => {
                    pending_tag = next_tag_pair(tag_rows)?;
                }
                std::cmp::Ordering::Equal => {
                    if let Some((_id, tag)) = pending_tag.take() {
                        row.tags.push(tag);
                    }
                    pending_tag = next_tag_pair(tag_rows)?;
                }
                std::cmp::Ordering::Greater => break,
            }
        }
        row.tags.sort();
        visit(row)?;
    }
    Ok(())
}

fn next_tag_pair(
    tag_rows: &mut Rows<'_>,
) -> Result<Option<(String, String)>, lomo_core::LomoError> {
    let Some(row) = tag_rows.next().map_err(|error| from_sqlite(&error))? else {
        return Ok(None);
    };
    let memo_id: String = row.get(0).map_err(|error| from_sqlite(&error))?;
    let tag: String = row.get(1).map_err(|error| from_sqlite(&error))?;
    Ok(Some((memo_id, tag)))
}

fn row_to_indexable(row: &rusqlite::Row<'_>) -> rusqlite::Result<IndexableMemoRow> {
    Ok(IndexableMemoRow {
        memo_id: row.get(0)?,
        source_path: row.get(1)?,
        body: row.get(2)?,
        tags: Vec::new(),
        has_todo: row.get::<_, i64>(3)? != 0,
        has_attachment: row.get::<_, i64>(4)? != 0,
        has_url: row.get::<_, i64>(5)? != 0,
        is_pinned: row.get::<_, i64>(6)? != 0,
        is_trashed: row.get::<_, i64>(7)? != 0,
        is_pending: row.get::<_, i64>(8)? != 0,
        created_at_ms: row.get(9)?,
        updated_at_ms: row.get(10)?,
    })
}

fn random_hex_nonce() -> Result<String, lomo_core::LomoError> {
    let mut bytes = [0_u8; 8];
    getrandom::fill(&mut bytes).map_err(|error| {
        storage(
            "search_nonce_entropy_failed",
            &format!("cannot mint search staging nonce: {error}"),
        )
    })?;
    Ok(lomo_core::hex_encode(&bytes))
}
