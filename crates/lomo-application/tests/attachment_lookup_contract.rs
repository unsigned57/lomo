//! Behavior Contract:
//! - Unit under test: `lomo_application::attachment_lookup::resolve_attachment_path`
//! - Owning layer: lomo-application
//! - Priority tier: P0
//! - Capability: resolves a canonical attachment destination against real
//!   workspace directory contents through the platform executor, delegating the
//!   basename/ambiguity law to `lomo-workspace`.
//!
//! Scenarios:
//! - Given a nested managed file and a bare basename, when resolved, then the
//!   canonical `RelativeWorkspacePath` answers and reads back the bytes.
//! - Given the same basename in two managed directories, when resolved, then the
//!   call fails `ambiguous_attachment` instead of choosing one.
//! - Given a missing basename, when resolved, then the authored path answers so
//!   the subsequent read surfaces `document_not_found` on that name.
//! - Given a qualified destination whose file lives elsewhere, when resolved,
//!   then the destination reads verbatim — never directory-searched.
//! - Given a same-named file only inside media trash/stage lanes, when resolved,
//!   then `Missing` — lifecycle lanes never claim attachments.
//!
//! Observable outcomes: resolved `RelativeWorkspacePath`, readable bytes, or a
//! typed `LomoError` (`ambiguous_attachment` / `document_not_found`).
//!
//! Excludes: SAF provider trees (capability surface is identical), Kotlin
//!   presentation resolvers, concurrent mutation during enumeration.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::{fs, path::PathBuf, sync::Arc};

    use lomo_application::WorkspaceSessionConfig;
    use lomo_core::{CapabilityToken, PlatformActionExecutor, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use tempfile::TempDir;

    struct Ctx {
        workspace: PathBuf,
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
        _workspace: TempDir,
        _state: TempDir,
        _cache: TempDir,
        _runtime: TempDir,
        _exchange: TempDir,
        _media_stage: TempDir,
    }

    fn open() -> Ctx {
        let workspace = TempDir::new().expect("ws");
        let state = TempDir::new().expect("st");
        let cache = TempDir::new().expect("ca");
        let runtime = TempDir::new().expect("rt");
        let exchange = TempDir::new().expect("ex");
        let media_stage = TempDir::new().expect("media stage");
        let capability = CapabilityToken::parse("notes").expect("cap");
        let fs_executor = FsPlatformActionExecutor::new(exchange.path()).expect("exec");
        fs_executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let executor: Arc<dyn PlatformActionExecutor> = Arc::new(fs_executor);
        let config = WorkspaceSessionConfig {
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
        };
        Ctx {
            workspace: workspace.path().to_path_buf(),
            config,
            executor,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
            _media_stage: media_stage,
        }
    }

    fn seed(ctx: &Ctx, relative: &str, bytes: &[u8]) {
        let absolute = ctx.workspace.join(relative);
        fs::create_dir_all(absolute.parent().expect("parent")).expect("mkdir");
        fs::write(absolute, bytes).expect("seed");
    }

    fn resolve(ctx: &Ctx, raw: &str) -> Result<RelativeWorkspacePath, lomo_core::LomoError> {
        lomo_application::attachment_lookup::resolve_attachment_path(
            &ctx.config,
            &ctx.executor,
            &RelativeWorkspacePath::parse(raw).expect("path"),
        )
    }

    #[test]
    fn bare_basename_resolves_nested_managed_file_and_reads_bytes() {
        let ctx = open();
        seed(&ctx, "media/photos/2026/pic.png", b"nested-png");

        let resolved = resolve(&ctx, "pic.png").expect("resolve");
        assert_eq!(resolved.as_str(), "media/photos/2026/pic.png");
        let bytes =
            lomo_application::rebuild::read_workspace_file(&ctx.config, &ctx.executor, &resolved)
                .expect("read");
        assert_eq!(bytes, b"nested-png");
    }

    #[test]
    fn same_basename_in_two_managed_directories_fails_closed() {
        let ctx = open();
        seed(&ctx, "media/pic.png", b"a");
        seed(&ctx, "images/pic.png", b"b");

        let error = resolve(&ctx, "pic.png").expect_err("ambiguity must error");
        assert_eq!(error.code(), "ambiguous_attachment");
        let diagnostic = error.to_string();
        assert!(diagnostic.contains("media/pic.png"), "{diagnostic}");
        assert!(diagnostic.contains("images/pic.png"), "{diagnostic}");
    }

    #[test]
    fn same_basename_at_root_and_managed_directory_fails_closed() {
        let ctx = open();
        seed(&ctx, "pic.png", b"root");
        seed(&ctx, "media/pic.png", b"managed");

        let error = resolve(&ctx, "pic.png").expect_err("ambiguity must error");
        assert_eq!(error.code(), "ambiguous_attachment");
    }

    #[test]
    fn missing_basename_returns_authored_path_for_downstream_not_found() {
        let ctx = open();
        seed(&ctx, "media/other.png", b"other");

        let resolved = resolve(&ctx, "pic.png").expect("missing resolves verbatim");
        assert_eq!(resolved.as_str(), "pic.png");
        let error =
            lomo_application::rebuild::read_workspace_file(&ctx.config, &ctx.executor, &resolved)
                .expect_err("missing file must stay not-found");
        assert_eq!(error.code(), "document_not_found");
    }

    #[test]
    fn qualified_destination_never_directory_searches() {
        let ctx = open();
        // The same-named file exists only under a different directory — a
        // qualified destination must still read verbatim and miss.
        seed(&ctx, "images/pic.png", b"wrong-dir");

        let resolved = resolve(&ctx, "media/pic.png").expect("qualified verbatim");
        assert_eq!(resolved.as_str(), "media/pic.png");
        let error =
            lomo_application::rebuild::read_workspace_file(&ctx.config, &ctx.executor, &resolved)
                .expect_err("qualified path must not relocate");
        assert_eq!(error.code(), "document_not_found");
    }

    #[test]
    fn media_lifecycle_lanes_never_claim_a_basename() {
        let ctx = open();
        seed(&ctx, "media/.lomo-media-trash/pic.png", b"trash");
        seed(&ctx, "media/.lomo-media-stage/pic.png", b"stage");

        let resolved = resolve(&ctx, "pic.png").expect("trash lane is not a hit");
        assert_eq!(resolved.as_str(), "pic.png");
    }

    #[test]
    fn absent_managed_directories_enumerate_as_empty() {
        let ctx = open();
        // No `media/`, `images/` or `voice/` directories at all — the lookup
        // treats them as empty evidence instead of surfacing list errors.
        let resolved = resolve(&ctx, "pic.png").expect("empty lookup dirs");
        assert_eq!(resolved.as_str(), "pic.png");
    }
}
