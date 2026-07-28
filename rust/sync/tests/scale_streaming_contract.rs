//! Behavior Contract — P5-11 host scale streaming contracts (+ Wave-11 cycle + Wave-12 apply)
//!
//! - Unit under test: `plan_intents_streaming` + `run_sync_cycle_streaming` + page/path ceilings
//! - Owning layer: `lomo-sync` (planner + residual cycle entry; no production DI)
//! - Priority tier: P0 (P5-11 scale residual host close + cycle integration + multi-page apply)
//! - Capability: hermetic 10k-class streaming snapshot/plan with bounded page buffers,
//!   path-key working set limits, intent page splits, intermediate intent ceiling, and
//!   fail-closed oversize — never full multi-page remote payload materialize. Host residual
//!   cycle consumes paged listings via `RemoteSyncPort::list_remote_pages` (not `BoltFFI` empty
//!   ports). Wave-12: multi-page apply under residual cycle (each intent page publish+verify;
//!   mid-stream verify failure stops further pages).
//!
//! Scenarios:
//! - Given `SCALE_HOST_PATH_COUNT` remote-only paths paged at `MAX_ACTION_PAGE_ITEMS`, when
//!   `plan_intents_streaming` runs Complete, then every intent page ≤ 512, peak page buffer
//!   ≤ 512, remote key count == 10k, `PullPresent` totals 10k, and no `EnsureAbsent` without
//!   baseline.
//! - Given 10k local-only paths and empty remote pages, when streamed `FirstTakeover`, then
//!   `EnsurePresent` pages are all ≤ 512, `ensure_absent` 0, and total `EnsurePresent` == 10k.
//! - Given Incomplete overall completeness + baseline path missing from stream, when planned,
//!   then `ensure_absent_count` 0 (partial listing never deletes).
//! - Given a remote page with `MAX_ACTION_PAGE_ITEMS`+1 entries, when streamed, then
//!   `resource_limit` `remote_snapshot_page_too_large`.
//! - Given path-key working set would exceed `MAX_STREAMING_REMOTE_PATH_KEYS`, when streamed,
//!   then `resource_limit` `streaming_remote_path_keys_too_large`.
//! - Given duplicate path across two pages, when streamed, then validation
//!   `streaming_remote_duplicate_path`.
//! - Given multi-page remote listing on hermetic fake port, when `run_sync_cycle_streaming`
//!   plan-only, then `list_pages` is consumed, intent pages ≤ 512, peak page ≤ 512, and no
//!   single-shot multi-page materialize into `RemoteSnapshot`.
//! - Given Intermediate intent accumulation bound equals path-key ceiling (budget lock).
//! - Given multi-page local-only `EnsurePresent` + `apply_remote`, when streaming cycle runs, then
//!   every intent page is published/verified in order (`pages_applied` == intent page count).
//! - Given multi-page apply where first page verify fails, when streaming cycle runs, then
//!   subsequent pages are not published and baseline does not advance.
//! - Given multi-page `OpenConflict` intents past the first intent page, when streaming cycle
//!   runs, then validation `streaming_open_conflict_outside_first_page` (permanent product law;
//!   never full-materialize multi-page conflict view — not a deferred design residual).
//! - Given multi-page `EnsurePresent` + canned multi-path receipt fixture, when each page
//!   publishes, then `FakeRemotePort` returns only page-scoped receipt/verify rows (honesty).
//!
//! Observable outcomes: intent page sizes, `peak_remote_page_entries`, `remote_path_key_count`,
//! ensure_* counts, `pages_applied`, `ErrorCategory::ResourceLimit` / Validation codes,
//! `list_pages` / publish call counts, page-scoped receipt lengths.
//!
//! TDD proof: fails before `plan_intents_streaming` exists (compile) / before page splits
//! (single oversize batch) / before key ceiling (silent growth) / before cycle wiring /
//! before multi-page apply loop (first-page-only residual) / before conflict outside-first-page
//! reject / before page-scoped fake receipt filter.
//!
//! Excludes: formal APK×1.15 measurement, 100k-path production matrix claim, real provider
//! list pagination production wire, production DI, body-byte streaming of media payloads, arm64.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_sync::{
        BaselineHead, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry, LocalSnapshot,
        MAX_ACTION_PAGE_ITEMS, MAX_STREAMING_INTERMEDIATE_INTENTS, MAX_STREAMING_REMOTE_PATH_KEYS,
        PublishReceipt, RemoteListingStream, RemotePathEntry, RemoteSnapshot,
        SCALE_HOST_PATH_COUNT, SessionKind, SnapshotCompleteness, SyncIdentityFence, SyncPath,
        SyncSession, TombstoneSet, VerifiedRemoteState, error_category, plan_intents_streaming,
        run_sync_cycle_streaming,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn remote_entry(index: usize, seed: u8) -> RemotePathEntry {
        RemotePathEntry {
            path: path(&format!("memo/scale-{index:05}.md")),
            digest: dig(seed),
            revision_token: format!("tok-{index}"),
        }
    }

    fn local_entry(index: usize, seed: u8) -> LocalPathEntry {
        LocalPathEntry {
            path: path(&format!("memo/local-{index:05}.md")),
            digest: dig(seed),
        }
    }

    /// Yields remote pages of at most `page_size` entries from a total count (lazy per page).
    fn remote_pages(
        total: usize,
        page_size: usize,
        seed: u8,
    ) -> impl Iterator<Item = Result<Vec<RemotePathEntry>, lomo_core::LomoError>> {
        let mut start = 0usize;
        std::iter::from_fn(move || {
            if start >= total {
                return None;
            }
            let end = (start + page_size).min(total);
            let page: Vec<_> = (start..end).map(|i| remote_entry(i, seed)).collect();
            start = end;
            Some(Ok(page))
        })
    }

    #[test]
    fn scale_host_path_count_budget_is_locked_at_10k() {
        // Budget lock: residual contracts must not silently shrink the 10k-class gate.
        assert_eq!(SCALE_HOST_PATH_COUNT, 10_000);
        assert_eq!(MAX_ACTION_PAGE_ITEMS, 512);
        assert_eq!(MAX_STREAMING_REMOTE_PATH_KEYS, 100_000);
        const {
            assert!(
                SCALE_HOST_PATH_COUNT > MAX_ACTION_PAGE_ITEMS,
                "scale count must exceed one action page so multi-page streaming is required"
            );
        }
    }

    #[test]
    fn streaming_10k_remote_only_pages_within_limit_and_no_full_materialize() {
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let outcome = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            remote_pages(SCALE_HOST_PATH_COUNT, MAX_ACTION_PAGE_ITEMS, 1),
            SnapshotCompleteness::Complete,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("stream plan");

        assert_eq!(outcome.remote_path_key_count, SCALE_HOST_PATH_COUNT);
        assert!(
            outcome.peak_remote_page_entries <= MAX_ACTION_PAGE_ITEMS,
            "peak page buffer must not exceed action page: {}",
            outcome.peak_remote_page_entries
        );
        assert!(
            outcome.pages_within_limit(),
            "every intent page must be ≤ {MAX_ACTION_PAGE_ITEMS}"
        );
        assert_eq!(outcome.pull_present_count(), SCALE_HOST_PATH_COUNT);
        assert_eq!(outcome.ensure_absent_count(), 0);
        assert_eq!(outcome.ensure_present_count(), 0);
        // Intent pages: ceil(10000 / 512) = 20 pages of 512 + remainder on last page.
        let expected_pages = SCALE_HOST_PATH_COUNT.div_ceil(MAX_ACTION_PAGE_ITEMS);
        assert_eq!(outcome.intent_pages.len(), expected_pages);
        for (idx, page) in outcome.intent_pages.iter().enumerate() {
            assert!(
                page.intents.len() <= MAX_ACTION_PAGE_ITEMS,
                "page {idx} oversized: {}",
                page.intents.len()
            );
            if idx + 1 < expected_pages {
                assert_eq!(page.intents.len(), MAX_ACTION_PAGE_ITEMS);
            } else {
                assert_eq!(
                    page.intents.len(),
                    SCALE_HOST_PATH_COUNT % MAX_ACTION_PAGE_ITEMS
                );
            }
        }
    }

    #[test]
    fn streaming_10k_local_only_first_takeover_pages_and_no_deletes() {
        let local_entries: Vec<_> = (0..SCALE_HOST_PATH_COUNT)
            .map(|i| local_entry(i, 2))
            .collect();
        let local = LocalSnapshot {
            entries: local_entries,
            workspace_generation: None,
        };
        // Empty remote stream (zero pages).
        let empty_stream = std::iter::empty::<Result<Vec<RemotePathEntry>, lomo_core::LomoError>>();
        let outcome = plan_intents_streaming(
            SessionKind::FirstTakeover,
            &local,
            empty_stream,
            SnapshotCompleteness::Complete,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("stream plan");

        assert_eq!(outcome.remote_path_key_count, 0);
        assert_eq!(outcome.peak_remote_page_entries, 0);
        assert!(outcome.pages_within_limit());
        assert_eq!(outcome.ensure_absent_count(), 0);
        assert_eq!(outcome.ensure_present_count(), SCALE_HOST_PATH_COUNT);
        assert_eq!(
            outcome.intent_pages.len(),
            SCALE_HOST_PATH_COUNT.div_ceil(MAX_ACTION_PAGE_ITEMS)
        );
    }

    #[test]
    fn streaming_incomplete_never_emits_ensure_absent_for_missing_baseline() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(SyncIdentityFence {
            workspace_generation: "ab".repeat(32),
            remote_dataset_id: "ds".to_owned(),
            remote_identity_digest: "cd".repeat(32),
        });
        // Baseline tracks a path that does not appear in the remote stream.
        baseline.upsert(&path("memo/missing.md"), &dig(9), "tok-miss".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        // Stream a few other paths so keys exist but missing.md does not.
        let outcome = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            remote_pages(64, 32, 3),
            SnapshotCompleteness::Incomplete,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("stream plan");
        assert_eq!(outcome.ensure_absent_count(), 0);
        assert_eq!(outcome.completeness, SnapshotCompleteness::Incomplete);
    }

    #[test]
    fn streaming_oversized_page_fails_closed() {
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let oversized: Vec<_> = (0..=MAX_ACTION_PAGE_ITEMS)
            .map(|i| remote_entry(i, 4))
            .collect();
        let oversized_stream = std::iter::once(Ok(oversized));
        let err = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            oversized_stream,
            SnapshotCompleteness::Complete,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect_err("oversized page");
        assert_eq!(error_category(&err), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "remote_snapshot_page_too_large");
    }

    #[test]
    fn streaming_path_key_ceiling_fails_closed() {
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        // Page at MAX_ACTION_PAGE_ITEMS; stop at ceiling+1 keys via lazy page iterator
        // (only one page of entries is materialized at a time).
        let total = MAX_STREAMING_REMOTE_PATH_KEYS + 1;
        let err = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            remote_pages(total, MAX_ACTION_PAGE_ITEMS, 5),
            SnapshotCompleteness::Complete,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect_err("key ceiling");
        assert_eq!(error_category(&err), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "streaming_remote_path_keys_too_large");
    }

    #[test]
    fn streaming_duplicate_path_across_pages_fails_closed() {
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let first_chunk = vec![remote_entry(0, 6)];
        let second_chunk = vec![remote_entry(0, 6)]; // same path again
        let duplicate_stream = [Ok(first_chunk), Ok(second_chunk)].into_iter();
        let err = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            duplicate_stream,
            SnapshotCompleteness::Complete,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect_err("duplicate path");
        assert_eq!(error_category(&err), ErrorCategory::Validation);
        assert_eq!(err.code(), "streaming_remote_duplicate_path");
    }

    #[test]
    fn streaming_complete_with_baseline_may_emit_ensure_absent_paged() {
        // Small scale (not 10k) proving Complete + established baseline + missing remote path
        // still yields EnsureAbsent under streaming (gates, not materialize).
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(SyncIdentityFence {
            workspace_generation: "ab".repeat(32),
            remote_dataset_id: "ds".to_owned(),
            remote_identity_digest: "cd".repeat(32),
        });
        baseline.upsert(&path("memo/gone.md"), &dig(7), "tok-gone".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let outcome = plan_intents_streaming(
            SessionKind::Incremental,
            &local,
            remote_pages(0, MAX_ACTION_PAGE_ITEMS, 7),
            SnapshotCompleteness::Complete,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("stream plan");
        assert_eq!(outcome.ensure_absent_count(), 1);
        assert!(outcome.pages_within_limit());
    }

    // --- Wave-11: host residual cycle integration (paged port → plan_intents_streaming) ---

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    #[test]
    fn intermediate_intent_ceiling_matches_path_key_ceiling() {
        // Budget lock: intermediate intent accumulation must not silently grow past path keys.
        assert_eq!(
            MAX_STREAMING_INTERMEDIATE_INTENTS,
            MAX_STREAMING_REMOTE_PATH_KEYS
        );
    }

    #[test]
    fn run_sync_cycle_streaming_consumes_paged_listing_plan_only() {
        // Multi-page remote listing (3 pages × 64) via FakeRemotePort::list_remote_pages.
        // Single-shot snapshot field stays empty (page-bounded; never multi-page materialize).
        let page_size = 64usize;
        let total = page_size * 3;
        let mut pages: Vec<Vec<RemotePathEntry>> = Vec::new();
        let mut start = 0usize;
        while start < total {
            let end = (start + page_size).min(total);
            pages.push((start..end).map(|i| remote_entry(i, 1)).collect());
            start = end;
        }
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Complete, pages).expect("stream");
        let remote = FakeRemotePort::new(
            // Single-shot snapshot deliberately empty / not multi-page materialize.
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_listing_pages(stream);
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "stream-cycle").expect("session");
        let result = run_sync_cycle_streaming(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("stream cycle");

        assert_eq!(remote.list_pages_call_count(), 1);
        assert_eq!(remote.publish_call_count(), 0);
        assert_eq!(result.pages_applied, 0);
        assert_eq!(result.plan.remote_path_key_count, total);
        assert!(result.plan.peak_remote_page_entries <= page_size);
        assert!(result.plan.pages_within_limit());
        assert_eq!(result.plan.pull_present_count(), total);
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert!(!result.baseline_advanced);
        assert!(result.receipt.is_none());
        // First page batch is the first intent page (≤ page action ceiling).
        assert!(result.first_page_batch.intents.len() <= MAX_ACTION_PAGE_ITEMS);
        assert_eq!(
            result.first_page_batch.intents.len(),
            result
                .plan
                .intent_pages
                .first()
                .map_or(0, |p| p.intents.len())
        );
    }

    #[test]
    fn run_sync_cycle_streaming_first_takeover_local_only_no_deletes() {
        let local = FakeLocalPort {
            entries: vec![
                LocalPathEntry {
                    path: path("memo/local-a.md"),
                    digest: dig(2),
                },
                LocalPathEntry {
                    path: path("memo/local-b.md"),
                    digest: dig(3),
                },
            ],
        };
        // Empty multi-page stream (zero pages) under FirstTakeover.
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Complete, Vec::new()).expect("s");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_listing_pages(stream);
        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "stream-ft").expect("session");
        let result = run_sync_cycle_streaming(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("stream cycle");
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert_eq!(result.plan.ensure_present_count(), 2);
        assert_eq!(result.first_page_batch.ensure_absent_count(), 0);
        assert!(!result.baseline_advanced);
        assert_eq!(result.pages_applied, 0);
        assert_eq!(remote.list_pages_call_count(), 1);
    }

    #[test]
    fn run_sync_cycle_streaming_incomplete_never_deletes_missing_baseline() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/missing.md"), &dig(9), "tok-miss".to_owned());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let pages = vec![vec![remote_entry(0, 3), remote_entry(1, 3)]];
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Incomplete, pages).expect("s");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Incomplete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_listing_pages(stream);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "stream-inc").expect("session");
        let result =
            run_sync_cycle_streaming(&session, &local, &remote, baseline, None, false, None)
                .expect("stream cycle");
        assert_eq!(result.plan.ensure_absent_count(), 0);
        assert_eq!(result.plan.completeness, SnapshotCompleteness::Incomplete);
        assert_eq!(result.first_page_batch.ensure_absent_count(), 0);
        assert_eq!(result.pages_applied, 0);
    }

    // --- Wave-12: multi-page apply residual under run_sync_cycle_streaming ---

    #[test]
    fn run_sync_cycle_streaming_applies_all_intent_pages_in_order() {
        // Local-only EnsurePresent past one action page: 512+64 → 2 intent pages, 2 publishes.
        let total = MAX_ACTION_PAGE_ITEMS + 64;
        let local = FakeLocalPort {
            entries: (0..total).map(|i| local_entry(i, 4)).collect(),
        };
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Complete, Vec::new()).expect("s");

        // Canned receipt/verify cover every path; FakeRemotePort returns the same fixture each publish.
        let path_results: Vec<_> = (0..total)
            .map(|i| {
                (
                    path(&format!("memo/local-{i:05}.md")),
                    lomo_sync::PathPublishStatus::Applied {
                        new_token: format!("tok-{i}"),
                    },
                )
            })
            .collect();
        let verify_results: Vec<_> = (0..total)
            .map(|i| lomo_sync::VerifyStatus::Verified {
                path: path(&format!("memo/local-{i:05}.md")),
                digest: dig(4),
                remote_token: format!("tok-{i}"),
            })
            .collect();
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt { path_results },
            VerifiedRemoteState {
                results: verify_results,
            },
        )
        .with_listing_pages(stream);

        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "stream-apply").expect("session");
        let result = run_sync_cycle_streaming(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            true,
            None,
        )
        .expect("stream apply");

        assert_eq!(result.plan.ensure_present_count(), total);
        assert_eq!(result.plan.ensure_absent_count(), 0);
        let expected_pages = total.div_ceil(MAX_ACTION_PAGE_ITEMS);
        assert_eq!(expected_pages, 2);
        assert_eq!(result.plan.intent_pages.len(), expected_pages);
        let expected_pages_u32 = u32::try_from(expected_pages).expect("page count fits u32");
        assert_eq!(result.pages_applied, expected_pages_u32);
        assert_eq!(remote.publish_call_count(), expected_pages_u32);
        assert_eq!(remote.verify_call_count(), expected_pages_u32);
        assert!(result.baseline_advanced);
        assert!(result.receipt.is_some());
        // Page-scoped honesty: each publish returns only paths in that intent page.
        // Combined receipt is the concatenation of per-page slices (total paths, not canned × pages).
        assert_eq!(
            result.receipt.as_ref().map(|r| r.path_results.len()),
            Some(total)
        );
    }

    #[test]
    fn run_sync_cycle_streaming_stops_apply_after_verify_failure_mid_stream() {
        // Two intent pages (local-only EnsurePresent). Verify fails → stop after first page.
        // page split: put 600 locals so intent pages = 2 (512 + 88).
        let total = MAX_ACTION_PAGE_ITEMS + 88;
        let local = FakeLocalPort {
            entries: (0..total).map(|i| local_entry(i, 5)).collect(),
        };
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Complete, Vec::new()).expect("s");
        let path_results: Vec<_> = (0..total)
            .map(|i| {
                (
                    path(&format!("memo/local-{i:05}.md")),
                    lomo_sync::PathPublishStatus::Applied {
                        new_token: format!("tok-{i}"),
                    },
                )
            })
            .collect();
        // Verify reports Failed for every path → all_verified() false on first page.
        let verify_results: Vec<_> = (0..total)
            .map(|i| lomo_sync::VerifyStatus::Failed {
                path: path(&format!("memo/local-{i:05}.md")),
                code: "verify_failed".to_owned(),
            })
            .collect();
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt { path_results },
            VerifiedRemoteState {
                results: verify_results,
            },
        )
        .with_listing_pages(stream);
        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "stream-stop").expect("session");
        let result = run_sync_cycle_streaming(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            true,
            None,
        )
        .expect("stream apply with verify fail");

        assert_eq!(result.plan.intent_pages.len(), 2);
        // Stops after first page verify failure — no second publish.
        assert_eq!(result.pages_applied, 1);
        assert_eq!(remote.publish_call_count(), 1);
        assert_eq!(remote.verify_call_count(), 1);
        assert!(!result.baseline_advanced);
        // First page verify is page-scoped: only first-page paths appear in verify results.
        let verified = result.verified.as_ref().expect("verified");
        assert_eq!(verified.results.len(), MAX_ACTION_PAGE_ITEMS);
    }

    // --- Wave-13: streaming conflict first-page residual (fail-closed) ---

    #[test]
    fn run_sync_cycle_streaming_rejects_open_conflict_outside_first_intent_page() {
        // Both-modified paths past one action page (512+64) → OpenConflict intents split into
        // two intent pages. Materialize only sees the first intent page; later OpenConflict
        // must fail closed rather than silent drop or multi-page full materialize.
        let page_size = 64usize;
        let total = MAX_ACTION_PAGE_ITEMS + 64;
        let local = FakeLocalPort {
            entries: (0..total)
                .map(|i| LocalPathEntry {
                    path: path(&format!("memo/c-{i:05}.md")),
                    digest: dig(1),
                })
                .collect(),
        };
        let page_count = total.div_ceil(page_size);
        let mut pages = Vec::with_capacity(page_count);
        for page_idx in 0..page_count {
            let start = page_idx * page_size;
            let end = (start + page_size).min(total);
            let page: Vec<_> = (start..end)
                .map(|i| RemotePathEntry {
                    path: path(&format!("memo/c-{i:05}.md")),
                    digest: dig(2),
                    revision_token: format!("tok-{i}"),
                })
                .collect();
            pages.push(page);
        }
        let stream =
            RemoteListingStream::from_pages(SnapshotCompleteness::Complete, pages).expect("stream");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_listing_pages(stream);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        for i in 0..total {
            baseline.upsert(
                &path(&format!("memo/c-{i:05}.md")),
                &dig(3),
                format!("base-tok-{i}"),
            );
        }
        let session = SyncSession::new(fence(), SessionKind::Incremental, "stream-conflict")
            .expect("session");
        let err = run_sync_cycle_streaming(&session, &local, &remote, baseline, None, false, None)
            .expect_err("open conflict outside first page must fail closed");
        assert_eq!(err.code(), "streaming_open_conflict_outside_first_page");
        assert_eq!(error_category(&err), ErrorCategory::Validation);
    }

    #[test]
    fn run_sync_cycle_streaming_first_page_open_conflict_still_allowed() {
        // Single intent page of OpenConflict stays on the first-page materialize path.
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/only.md"),
                digest: dig(1),
            }],
        };
        let remote_entries = vec![RemotePathEntry {
            path: path("memo/only.md"),
            digest: dig(2),
            revision_token: "tok-r".to_owned(),
        }];
        let stream = RemoteListingStream::from_pages(
            SnapshotCompleteness::Complete,
            vec![remote_entries.clone()],
        )
        .expect("stream");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, remote_entries).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_listing_pages(stream);
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/only.md"), &dig(3), "base-tok".to_owned());
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "stream-first").expect("session");
        let result =
            run_sync_cycle_streaming(&session, &local, &remote, baseline, None, false, None)
                .expect("first-page open conflict allowed without paths");
        assert_eq!(result.plan.open_conflict_count(), 1);
        assert_eq!(result.plan.intent_pages.len(), 1);
        assert_eq!(result.first_page_batch.open_conflict_count(), 1);
        assert!(result.conflict_session.is_none());
    }
}
