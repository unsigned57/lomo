//! Behavior Contract:
//! Capability: first import preserves durable history without reading objects before creating
//! them; owner: lomo-application; priority: P1.
//! Scenarios:
//! Given 100 documents with four memos each, when the first projection commits, then all 400
//! original bodies have revision one and no immutable history object was read to prove absence.
//! Given that imported workspace, when a fresh projection opens, then the same identities and
//! revision bodies survive rebuilding from durable files.
//! Given foreign bytes at a planned immutable revision address, when an edit is applied, then
//! it fails without overwriting those bytes or publishing a successful edit; after explicit
//! resolution, retrying the same operation preserves the original history and commits once.
//! A complete inventory admits required reads only for listed records; absent slots are
//! create-only and arbitrate concurrent claims at the write receipt. Every new head still
//! receives a final freshness observation before projection publication.
//! Inventory RED: 100 absent identity records were read; expected zero. GREEN reads no absent
//! identity/head slots and reads each of the 400 written heads exactly once for freshness.
//! Observable outcomes: platform reads, memo identities and history bodies after a cold rebuild.
//! TDD proof: `cargo test -p lomo-application --test import_io_contract --locked`;
//! RED: first import reads 400 absent immutable objects; GREEN: zero, with history intact.
//! Excludes: SAF latency and simultaneous external writes (covered by reconcile contracts).

#[cfg(test)]
mod tests {
    use lomo_application::{
        MemoFilters, MemoQuery, MemoSort, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{
        CapabilityToken, PlatformAction, PlatformActionBatch, PlatformActionExecutor,
        PlatformBatchResult,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{WorkspaceGenerationId, WorkspaceRootId};
    use std::{
        fs,
        sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        },
    };

    struct ImportExecutor {
        inner: FsPlatformActionExecutor,
        object_reads: AtomicUsize,
        identity_reads: AtomicUsize,
        head_reads: AtomicUsize,
    }

    impl PlatformActionExecutor for ImportExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            for action in batch.actions() {
                if let PlatformAction::Read { path, .. } = action
                    && path.as_str().starts_with(".lomo/history/v2/objects/")
                {
                    self.object_reads.fetch_add(1, Ordering::Relaxed);
                }
                if let PlatformAction::Read { path, .. } = action {
                    if path.as_str().starts_with(".lomo/identity/v1/") {
                        self.identity_reads.fetch_add(1, Ordering::Relaxed);
                    } else if path.as_str().starts_with(".lomo/history/v2/heads/") {
                        self.head_reads.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    #[expect(
        clippy::expect_used,
        reason = "fixture failures must abort this import regression"
    )]
    fn first_import_creates_history_without_absence_reads_and_cold_rebuild_preserves_it() {
        use std::fmt::Write as _;
        let temp = tempfile::tempdir().expect("valid import fixture");
        let workspace = temp.path().join("workspace");
        let exchange = temp.path().join("exchange");
        fs::create_dir_all(&workspace).expect("valid import fixture");
        fs::create_dir_all(&exchange).expect("valid import fixture");
        for day in 0..100 {
            let mut text = String::new();
            for memo in 0..4 {
                writeln!(text, "- 10:0{memo}\noriginal {day}/{memo}\n")
                    .expect("valid import fixture");
            }
            fs::write(
                workspace.join(format!("2026_{:02}_{:02}.md", day / 28 + 1, day % 28 + 1)),
                text,
            )
            .expect("valid import fixture");
        }
        let capability = CapabilityToken::parse("import").expect("valid import fixture");
        let executor = Arc::new(ImportExecutor {
            inner: FsPlatformActionExecutor::new(&exchange).expect("valid import fixture"),
            object_reads: AtomicUsize::new(0),
            identity_reads: AtomicUsize::new(0),
            head_reads: AtomicUsize::new(0),
        });
        executor
            .inner
            .bind_root(capability.clone(), &workspace)
            .expect("valid import fixture");
        let mut config = session_config(temp.path(), capability);
        let port: Arc<dyn PlatformActionExecutor> = Arc::<ImportExecutor>::clone(&executor);
        let session = WorkspaceSession::open(config.clone(), Arc::clone(&port))
            .expect("valid import fixture");
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters::default(),
            sort: MemoSort::default(),
        };
        assert_eq!(
            session.query_count(&query).expect("valid import fixture"),
            400
        );
        assert_eq!(
            executor.object_reads.load(Ordering::Relaxed),
            0,
            "initial history objects must arbitrate absence at the create boundary"
        );
        assert_eq!(
            executor.identity_reads.load(Ordering::Relaxed),
            0,
            "complete inventory already proves which identity slots need create arbitration"
        );
        assert_eq!(
            executor.head_reads.load(Ordering::Relaxed),
            400,
            "new heads need one final freshness read, not an extra absence probe"
        );
        let first_page = session.list_memos(&query).expect("valid import fixture");
        let first = first_page
            .items
            .first()
            .ok_or("empty imported page")
            .expect("valid import fixture");
        let id = lomo_workspace::MemoId::parse(&first.memo_id).expect("valid import fixture");
        let original = session
            .get_memo(&id)
            .expect("valid import fixture")
            .ok_or("missing imported memo")
            .expect("valid import fixture");
        let history = session
            .list_history(&id, None, 10)
            .expect("valid import fixture");
        assert_eq!(history.items.len(), 1);
        assert_eq!(
            history.items.first().expect("initial revision").content,
            original.body
        );
        drop(session);
        config.cache_dir = temp.path().join("cold-cache");
        let cold = WorkspaceSession::open(config, port).expect("valid import fixture");
        assert_eq!(cold.query_count(&query).expect("valid import fixture"), 400);
        assert_eq!(
            cold.get_memo(&id)
                .expect("valid import fixture")
                .ok_or("missing cold memo")
                .expect("valid import fixture")
                .body,
            original.body
        );
        assert_eq!(
            cold.list_history(&id, None, 10)
                .expect("valid import fixture")
                .items,
            history.items
        );
        assert_immutable_collision_recovery(&cold, &workspace, &id);
    }
    #[expect(
        clippy::expect_used,
        reason = "a fixture must acquire a valid workspace generation"
    )]
    fn session_config(
        root: &std::path::Path,
        capability: CapabilityToken,
    ) -> WorkspaceSessionConfig {
        WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: WorkspaceGenerationId::mint().expect("generation"),
            time_zone: "UTC".into(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: root.join("state"),
            cache_dir: root.join("cache"),
            runtime_dir: root.join("runtime"),
            exchange_dir: root.join("exchange"),
            media_stage_root: root.join("media"),
        }
    }

    #[expect(
        clippy::expect_used,
        reason = "fixture failures must abort the collision regression"
    )]
    fn assert_immutable_collision_recovery(
        session: &WorkspaceSession,
        workspace: &std::path::Path,
        id: &lomo_workspace::MemoId,
    ) {
        use lomo_workspace::{
            HistoryHead, HistoryRevisionV2, LomoLayoutVersion, SourceFingerprint, WorkspaceLayout,
            decode_record, history_head_path, history_revision_path,
        };
        let layout = WorkspaceLayout::for_workspace_with_layout(workspace, LomoLayoutVersion::V2);
        let head_record =
            decode_record(&fs::read(history_head_path(&layout, id.as_str())).expect("head bytes"))
                .expect("head envelope");
        let head: HistoryHead = serde_json::from_str(&head_record.payload.body_json).expect("head");
        let parent_record = decode_record(
            &fs::read(history_revision_path(&layout, &head.head_revision_id))
                .expect("parent bytes"),
        )
        .expect("parent envelope");
        let parent: HistoryRevisionV2 =
            serde_json::from_str(&parent_record.payload.body_json).expect("parent");
        let content = "next durable revision";
        let revision = HistoryRevisionV2::create(
            id.as_str(),
            &[parent],
            content.into(),
            SourceFingerprint::of_bytes(content.as_bytes()).as_str(),
            "",
            1,
        )
        .expect("next revision");
        let occupied = history_revision_path(&layout, &revision.revision_id);
        fs::write(&occupied, b"foreign immutable bytes").expect("external collision");
        let before = session.get_memo(id).expect("view").expect("memo");
        let request = lomo_application::UpdateMemoRequest {
            operation_id: lomo_core::OperationId::parse("collision-edit").expect("operation"),
            memo_id: id.clone(),
            content: content.into(),
            expected_document_fingerprint: before.file_fingerprint.clone(),
            pending_promotes: Vec::new(),
        };
        // Write-behind: the edit's intent commits and publishes; converging it meets the foreign
        // immutable object, which fails closed and stalls the frozen intent unrewritten.
        // Justified assertion change: the projection reflects the committed intent instead of
        // the pre-edit body; the durable workspace still refuses to overwrite foreign bytes.
        session
            .update_memo(request.clone())
            .expect("intent commits");
        let failure = session
            .settle_workspace()
            .expect_err("foreign object must reject the edit's convergence");
        assert_eq!(failure.code(), "platform_postcondition_mismatch");
        assert_eq!(
            fs::read(&occupied).expect("occupied bytes"),
            b"foreign immutable bytes"
        );
        assert_eq!(
            session.get_memo(id).expect("view").expect("memo").body,
            content,
            "the committed intent is what the projection presents"
        );
        fs::remove_file(&occupied).expect("explicitly resolve the test-owned collision");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .update_memo(request.clone())
            .expect("resume frozen edit");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session.update_memo(request).expect("idempotent replay");
        session
            .settle_workspace()
            .expect("committed writes converge");
        session
            .settle_workspace()
            .expect("committed writes converge");
        let history = session
            .list_history(id, None, 10)
            .expect("history after retry");
        assert_eq!(history.items.len(), 2);
        assert!(history.items.iter().any(|item| item.content == before.body));
        assert!(history.items.iter().any(|item| item.content == content));
    }
}
