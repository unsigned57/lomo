//! Behavior Contract
//! Capability: a single memo-level patch commits the host document's identity record with
//! block-granular billing — sibling `block_fingerprint` evidence is carried from the previous
//! durable record instead of being recomputed, while every locator still advances to the new
//! host source version; owning layer: lomo-application; priority P1.
//! Scenarios: a committed three-memo document updated through `update_memo` publishes a
//! durable identity record whose sibling bindings keep their previous block fingerprints
//! under the new locator version, the patched block's fingerprint differs, and the record
//! carries no conflict or unbound evidence.
//! Observable outcomes: durable `.lomo/identity` record bindings, carried block fingerprints,
//! advanced locator versions, empty conflict evidence.
//! TDD proof: regression preservation — the billing counter is pinned in the workspace
//! contract `single_memo_patch_refingerprints_only_the_touched_block`, where a whole-document
//! rebind refingerprinted 3 blocks against the expected 1 before the position-proof carry.
//! Excludes: numeric wall-clock performance claims and journal payload sizing; the
//! durable-write envelope still carries full host bytes by design.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::{fs, path::PathBuf, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, PlatformActionExecutor, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{
        MemoId, MemoIdentityMap, SourceFingerprint, WorkspaceRelativePath, WorkspaceRootId,
        memo_identity_record_path,
    };
    use tempfile::tempdir;

    struct Ctx {
        session: WorkspaceSession,
        workspace_path: PathBuf,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
        _stage: tempfile::TempDir,
    }

    fn open_session() -> Ctx {
        let workspace = tempdir().expect("ws");
        let state = tempdir().expect("st");
        let cache = tempdir().expect("ca");
        let runtime = tempdir().expect("rt");
        let exchange = tempdir().expect("ex");
        let media_stage = tempdir().expect("stage");
        let real = Arc::new(FsPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        real.bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let executor: Arc<dyn PlatformActionExecutor> = real;
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                    .expect("workspace generation"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: state.path().to_path_buf(),
                cache_dir: cache.path().to_path_buf(),
                runtime_dir: runtime.path().to_path_buf(),
                exchange_dir: exchange.path().to_path_buf(),
                media_stage_root: media_stage.path().to_path_buf(),
            },
            executor,
        )
        .expect("open");
        Ctx {
            workspace_path: workspace.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
            _stage: media_stage,
        }
    }

    const DOCUMENT: &str = "2026_09_12.md";

    fn create_memo(ctx: &Ctx, operation: &str, content: &str) -> MemoId {
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(operation).expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse(DOCUMENT).expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create")
            .memo_id
    }

    /// Reads the durable identity record the document transaction published.
    fn identity_record(ctx: &Ctx) -> MemoIdentityMap {
        let record_path = memo_identity_record_path(
            WorkspaceRootId::Notes,
            &WorkspaceRelativePath::parse(DOCUMENT).expect("path"),
        )
        .expect("record path");
        let bytes = fs::read(ctx.workspace_path.join(record_path.as_str())).expect("record bytes");
        MemoIdentityMap::decode(&bytes).expect("decode durable identity record")
    }

    fn binding<'a>(map: &'a MemoIdentityMap, memo_id: &MemoId) -> &'a lomo_workspace::MemoBinding {
        map.bindings()
            .iter()
            .find(|binding| binding.memo_id() == memo_id)
            .expect("binding must exist")
    }

    /// One memo update must commit the identity record with sibling fingerprints carried —
    /// the durable evidence proves the host document was billed at block granularity, not
    /// re-fingerprinted wholesale.
    #[test]
    fn single_memo_update_carries_sibling_block_fingerprints_in_the_durable_record() {
        let ctx = open_session();
        let memo_a = create_memo(&ctx, "mk-a", "alpha body");
        let memo_b = create_memo(&ctx, "mk-b", "beta body");
        let memo_c = create_memo(&ctx, "mk-c", "gamma body");
        let before = identity_record(&ctx);
        assert_eq!(
            before.memo_ids(),
            vec![memo_a.clone(), memo_b.clone(), memo_c.clone()]
        );

        let snapshot = ctx.session.get_memo(&memo_b).expect("get").expect("memo");
        ctx.session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("upd-b").expect("op"),
                memo_id: memo_b.clone(),
                content: "beta rewritten".to_owned(),
                expected_document_fingerprint: snapshot.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .expect("update");

        let after = identity_record(&ctx);
        assert_eq!(
            after.memo_ids(),
            vec![memo_a.clone(), memo_b.clone(), memo_c.clone()],
            "the memo-level patch must not disturb the binding order"
        );
        assert!(after.conflicts().is_empty(), "no conflict evidence");
        assert!(after.unbound().is_empty(), "no unbound blocks");

        let new_version = SourceFingerprint::parse(
            &ctx.session
                .get_memo(&memo_a)
                .expect("get")
                .expect("memo")
                .file_fingerprint,
        )
        .expect("new host version");
        for sibling_id in [&memo_a, &memo_c] {
            let carried = binding(&after, sibling_id);
            assert_eq!(
                carried.block_fingerprint(),
                binding(&before, sibling_id).block_fingerprint(),
                "a byte-identical sibling carries its previous block fingerprint"
            );
            assert_eq!(
                carried.locator().fingerprint(),
                &new_version,
                "the sibling locator still advances to the new host version"
            );
        }
        assert_ne!(
            binding(&after, &memo_b).block_fingerprint(),
            binding(&before, &memo_b).block_fingerprint(),
            "only the patched block's fingerprint actually changed"
        );
    }
}
