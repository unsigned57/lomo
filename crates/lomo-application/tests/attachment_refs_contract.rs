// adversarial-audit: a live memo's attachment reference must protect the media object it
// resolves to, regardless of whether the destination string is in canonical form.
//!
//! Invariant under test:
//! - `lomo_workspace::canonical_attachment_path` is the single destination authority:
//!   equivalent local spellings (`media/./pic.png`, `media//pic.png`) collapse to one
//!   canonical key before they reach `attachment_destinations`, the store projection, or
//!   `require_referenced_attachments`. `AttachmentIndex.protects_path` therefore compares
//!   canonical-to-canonical and `media/./pic.png` protects the real file at `media/pic.png`,
//!   which the host resolves to the same bytes.
//! - A memo whose body fails render-budget parsing projects zero attachment refs, silently
//!   dropping protection for every object it references.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{fs, path::PathBuf, sync::Arc};

    use lomo_application::{CreateMemoRequest, WorkspaceSession, WorkspaceSessionConfig};
    use lomo_core::{CapabilityToken, OperationId, PlatformActionExecutor, RelativeWorkspacePath};
    use lomo_media::{ReferenceSource, write_bytes_for_tests};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{MemoId, WorkspaceRootId};
    use tempfile::tempdir;

    const PNG: &[u8] = &[
        0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, b'I', b'H', b'D',
        b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90,
        0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63, 0xf8,
        0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00, 0x00,
        0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    const NOW_MS: u64 = 1_757_500_000_000;
    const WINDOW_MS: u64 = 30 * 24 * 60 * 60 * 1000;

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

    fn seed_media(ctx: &Ctx, relative: &str, bytes: &[u8]) -> PathBuf {
        let path = ctx.workspace_path.join(relative);
        fs::create_dir_all(path.parent().expect("media parent")).expect("media dir");
        write_bytes_for_tests(&path, bytes).expect("seed media");
        path
    }

    fn create_memo(ctx: &Ctx, operation: &str, relative_path: &str, content: &str) -> MemoId {
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(operation).expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse(relative_path).expect("path")),
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

    /// A live memo referencing `media/./pic.png` resolves to `media/pic.png` on every host
    /// filesystem read, so the committed object must stay protected.
    #[test]
    fn dot_segment_destination_still_protects_the_resolved_file() {
        let ctx = open_session();
        let target = seed_media(&ctx, "media/pic.png", PNG);
        create_memo(
            &ctx,
            "memo-dot",
            "2026_09_10.md",
            "see ![[media/./pic.png]]",
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert!(
            report
                .protections
                .iter()
                .any(|item| item.relative_path == "media/pic.png"
                    && item.source == ReferenceSource::CurrentMemo),
            "canonical object a live memo resolves to must be protected; \
             protections={:?} moved={:?} failures={:?}",
            report.protections,
            report.moved_to_trash,
            report.failures
        );
        assert!(
            target.is_file(),
            "live-referenced media was collected through a noncanonical destination"
        );
    }

    /// `media//pic.png` carries an empty segment: parseable by the host filesystem, rejected by
    /// the canonical path type. The referenced object must still survive the sweep.
    #[test]
    fn empty_segment_destination_still_protects_the_resolved_file() {
        let ctx = open_session();
        let target = seed_media(&ctx, "media/dup.png", PNG);
        create_memo(
            &ctx,
            "memo-empty-seg",
            "2026_09_11.md",
            "see ![[media//dup.png]]",
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert!(
            report
                .protections
                .iter()
                .any(|item| item.relative_path == "media/dup.png"),
            "canonical object a live memo resolves to must be protected: {:?}",
            report.protections
        );
        assert!(target.is_file());
    }
}
