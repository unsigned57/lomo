//! Behavior Contract — T33 `KeepRemote` local pull through `WorkspaceSession`
//!
//! - Unit under test: native composition of `sync_run_cycle` / `LomoEngine::sync_run_cycle`
//!   with durable `KeepRemote` artifacts
//! - Owning layer: `lomo-native` (session write composition); bodies/status in `lomo-sync`;
//!   document CAS in `lomo-application::WorkspaceSession`
//! - Priority tier: P0
//! - Capability: pending `KeepRemote`/Merged local pulls are applied only through an open
//!   `WorkspaceSession` (`update_memo` + planning-time document fingerprint). The session-less
//!   free-function must fail closed rather than skip or Direct-write.
//!
//! Scenarios:
//! - Given a session-created dated memo and a durable `KeepRemote` resolution, when the
//!   session-less `sync_run_cycle` runs with `apply_remote`, then the boundary returns
//!   `sync_local_pull_requires_workspace_session` and the memo body is unchanged.
//! - Given the same durable `KeepRemote` resolution and an open POSIX session, when
//!   `LomoEngine::sync_run_cycle` runs with `apply_remote`, then `session_get_memo` returns
//!   the remote candidate body and the dated Markdown contains those bytes.
//!
//! Observable outcomes: `EngineError` code, session memo body, workspace Markdown bytes.
//! TDD proof: RED `free_function_apply_cycle_fails_closed_when_keep_remote_pull_lacks_session`
//! panicked `session-less apply must not skip KeepRemote: unexpectedly succeeded` because
//! `sync_run_cycle` completed without a session write. GREEN same command after
//! `sync_local_pull_requires_workspace_session` + `LomoEngine::sync_run_cycle` session apply.
//! Excludes: Kotlin DI cutover, SAF session, real provider transport, Kotlin LCS merge,
//! multi-memo dated documents.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::{fs, path::PathBuf};

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, PlatformActionBatch, PlatformBatchHost,
        PlatformBatchResult, SessionCreateMemoRequest, SyncBackendConfigDto, WorkspaceDescriptor,
        sync_run_cycle,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_sync::{
        ConflictPathStatus, ContentDigest, SyncBackendConfig, SyncIdentityFence, SyncPath,
        SyncPaths, conflict_path_from_open, write_conflict_artifact, write_conflict_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest};
    use tempfile::tempdir;

    struct PosixBatchHost {
        executor: FsPlatformActionExecutor,
    }

    impl PlatformBatchHost for PosixBatchHost {
        fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError> {
            let core_batch = lomo_native::batch_from_ffi(batch)?;
            let result = self
                .executor
                .execute(&core_batch)
                .map_err(EngineError::from)?;
            Ok(lomo_native::result_to_ffi(&result))
        }
    }

    struct DirectSession {
        _temporary: tempfile::TempDir,
        workspace: PathBuf,
        exchange: PathBuf,
        engine: LomoEngine,
    }

    fn open_direct_engine() -> DirectSession {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let workspace = temporary.path().join("workspace");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        fs::create_dir_all(&workspace).test_ok("workspace");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Direct {
                root_path: workspace.display().to_string(),
                capability_token: "notes-root".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("direct engine");
        DirectSession {
            _temporary: temporary,
            workspace,
            exchange,
            engine,
        }
    }

    fn attach_posix_session(session: &DirectSession) {
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        let capability = CapabilityToken::parse("notes-root").test_ok("direct capability");
        executor
            .bind_root(capability, &session.workspace)
            .test_ok("bind workspace");
        session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
    }

    /// The durable fence must match the live (generation, dataset, canonical identity)
    /// triple — seeding an arbitrary fence is rejected as `sync_identity_mismatch`.
    fn fence(workspace: &std::path::Path, dataset: &str) -> SyncIdentityFence {
        let generation = lomo_workspace::load_or_mint_workspace_generation(workspace)
            .test_ok("workspace generation");
        let canonical = SyncBackendConfig::HermeticFake {
            remote_dataset_id: dataset.to_owned(),
        }
        .canonical_identity();
        let identity = RemoteIdentityDigest::from_canonical_config_bytes(canonical.as_bytes());
        SyncIdentityFence::from_parts(
            &generation,
            &RemoteDatasetId::parse(dataset).test_ok("ds"),
            &identity,
        )
    }

    fn seed_keep_remote_on_created_memo(
        session: &DirectSession,
        local_body: &str,
        remote_body: &str,
    ) -> (String, String) {
        let commit = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-keep-remote-create".to_owned(),
                relative_path: None,
                time_token: Some("10:15:00".to_owned()),
                content: local_body.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("create memo");
        let view = session
            .engine
            .get_memo(commit.memo_id.clone())
            .test_ok("get created")
            .test_ok("created present");
        let source_path = view.summary.source_path;
        let local_bytes = fs::read(session.workspace.join(&source_path)).test_ok("read local file");
        let local_markdown = String::from_utf8(local_bytes.clone()).test_ok("local utf8");
        assert!(
            local_markdown.contains(local_body),
            "created document must contain the local body: {local_markdown}"
        );
        let remote_markdown = local_markdown.replace(local_body, remote_body);
        assert_ne!(
            remote_markdown, local_markdown,
            "remote candidate must differ from local document"
        );
        let local_digest = ContentDigest::from_bytes(&local_bytes);
        let remote_digest = ContentDigest::from_bytes(remote_markdown.as_bytes());
        let paths = SyncPaths::for_workspace(&session.workspace);
        let mut record = conflict_path_from_open(
            &SyncPath::parse(&source_path).test_ok("sync path"),
            Some(&local_digest),
            Some(&remote_digest),
            None,
            Some("tok-keep-remote"),
        )
        .test_ok("conflict record");
        let session_id = "keep-remote-session";
        record.local_artifact_ref = Some(
            write_conflict_artifact(&paths, session_id, "local", &source_path, &local_bytes)
                .test_ok("local artifact"),
        );
        record.remote_artifact_ref = Some(
            write_conflict_artifact(
                &paths,
                session_id,
                "remote",
                &source_path,
                remote_markdown.as_bytes(),
            )
            .test_ok("remote artifact"),
        );
        record.status = ConflictPathStatus::ResolvedKeepRemote;
        let conflict = lomo_sync::ConflictSession::open(
            fence(&session.workspace, "ds-keep-remote"),
            session_id,
            vec![record],
        )
        .test_ok("open conflict session");
        write_conflict_session(&paths, &conflict).test_ok("write conflict session");
        (commit.memo_id, source_path)
    }

    #[test]
    fn free_function_apply_cycle_fails_closed_when_keep_remote_pull_lacks_session() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let (memo_id, _path) =
            seed_keep_remote_on_created_memo(&session, "local-keep-remote", "remote-keep-remote");

        let error = sync_run_cycle(
            session.workspace.display().to_string(),
            SyncBackendConfigDto {
                backend_kind: "hermetic_fake".to_owned(),
                remote_dataset_id: "ds-keep-remote".to_owned(),
                ..SyncBackendConfigDto::default()
            },
            String::new(),
            true,
        )
        .test_err("session-less apply must not skip KeepRemote");
        assert_eq!(error.code(), "sync_local_pull_requires_workspace_session");

        let after = session
            .engine
            .get_memo(memo_id)
            .test_ok("get after refused cycle")
            .test_ok("memo still present");
        assert_eq!(
            after.body, "local-keep-remote",
            "refused free-function cycle must not rewrite the session memo"
        );
    }

    #[test]
    fn engine_apply_cycle_writes_keep_remote_body_through_workspace_session() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let (memo_id, source_path) =
            seed_keep_remote_on_created_memo(&session, "local-keep-remote", "remote-keep-remote");

        session
            .engine
            .sync_run_cycle(
                session.workspace.display().to_string(),
                SyncBackendConfigDto {
                    backend_kind: "hermetic_fake".to_owned(),
                    remote_dataset_id: "ds-keep-remote".to_owned(),
                    ..SyncBackendConfigDto::default()
                },
                String::new(),
                true,
            )
            .test_ok("engine apply cycle");

        let after = session
            .engine
            .get_memo(memo_id)
            .test_ok("get after engine apply")
            .test_ok("memo still present");
        assert_eq!(
            after.body, "remote-keep-remote",
            "KeepRemote must replace the session memo body via WorkspaceSession"
        );
        let markdown =
            fs::read_to_string(session.workspace.join(source_path)).test_ok("read dated");
        assert!(
            markdown.contains("remote-keep-remote"),
            "dated document must contain the remote body: {markdown}"
        );
        assert!(
            !markdown.contains("local-keep-remote"),
            "dated document must drop the displaced local body: {markdown}"
        );
    }
}
