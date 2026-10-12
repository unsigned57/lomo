//! Behavior Contract (P-26 / R-09 workspace layout single dispatch)
//!
//! Capability: `lomo-workspace` is the sole dispatch point for every path under
//! `<workspace>/.lomo/` and for every name minted inside a session-private state
//! directory. Consumers never join `.lomo` segments themselves.
//!
//! Scenarios:
//! - Given a workspace root, when `WorkspaceLayout::for_workspace` resolves, then every
//!   `.lomo` subtree is the canonical segment minted here (manifest, layout head,
//!   operations, history/state for the head-selected version, local + generation
//!   record, identity, trash, purged, sync, migration staging).
//! - Given no layout head, when paths resolve, then history/state bind `v1`; after
//!   `write_layout_head_v2` they bind `v2`.
//! - Given a session-private state dir, when `SessionStateLayout::for_state_dir`
//!   resolves, then `device_id`/`drafts`/`capture`/`intents`/`intent-payloads`/
//!   `baselines`/`exchange` carry the canonical names shared by Android control
//!   roots and the TUI.
//! - Given a control root and workspace identity, when `SessionLayout` resolves,
//!   then `session/<identity>/{state,cache,runtime}` are minted.
//! - Given the forward-slash `relative` constants, when projected onto a root,
//!   then they equal the same layout's absolute fields (text classifiers and path
//!   joiners can never diverge).
//!
//! Observable outcomes: canonical path values, head-selected version binding.
//! Excludes: migration crash matrix (covered by `migration_v2_contract`), record
//! codec framing (`lomo_record_contract`).

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use lomo_workspace::{
        LomoLayoutVersion, SessionLayout, SessionStateLayout, WorkspaceLayout, layout,
        write_layout_head_v2,
    };

    use super::support::ResultTestExt;
    use tempfile::tempdir;

    #[test]
    fn workspace_layout_mints_the_complete_lomo_inventory() {
        let dir = tempdir().test_ok("tempdir");
        let layout = WorkspaceLayout::for_workspace(dir.path());
        let root = dir.path();
        let lomo = root.join(".lomo");

        assert_eq!(layout.workspace_root, root);
        assert_eq!(layout.root, lomo);
        assert_eq!(layout.manifest, lomo.join("manifest.v1"));
        assert_eq!(layout.layout_head, lomo.join("layout_head.rec"));
        assert_eq!(layout.operations, lomo.join("operations").join("v1"));
        assert_eq!(layout.local, lomo.join("local").join("v1"));
        assert_eq!(
            layout.generation_record,
            lomo.join("local").join("v1").join("generation.rec")
        );
        assert_eq!(layout.identity, lomo.join("identity").join("v1"));
        assert_eq!(layout.trash, lomo.join("trash").join("v1"));
        assert_eq!(layout.purged, lomo.join("purged").join("v1"));
        assert_eq!(layout.sync, lomo.join("sync").join("v1"));
        assert_eq!(layout.migration_staging, lomo.join("migration-staging"));
    }

    #[test]
    fn versioned_record_trees_follow_the_layout_head() {
        let dir = tempdir().test_ok("tempdir");

        let v1 = WorkspaceLayout::for_workspace(dir.path());
        assert_eq!(v1.layout, LomoLayoutVersion::V1);
        assert_eq!(v1.history, dir.path().join(".lomo/history/v1"));
        assert_eq!(v1.state, dir.path().join(".lomo/state/v1"));

        write_layout_head_v2(dir.path()).test_ok("write layout head");
        let v2 = WorkspaceLayout::for_workspace(dir.path());
        assert_eq!(v2.layout, LomoLayoutVersion::V2);
        assert_eq!(v2.history, dir.path().join(".lomo/history/v2"));
        assert_eq!(v2.state, dir.path().join(".lomo/state/v2"));

        // The explicit-version constructor performs no head read; staging callers use it.
        let staged = WorkspaceLayout::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V1);
        assert_eq!(staged.layout, LomoLayoutVersion::V1);
        assert_eq!(staged.history, dir.path().join(".lomo/history/v1"));
    }

    #[test]
    fn session_state_layout_mints_canonical_names() {
        let dir = tempdir().test_ok("tempdir");
        let state = SessionStateLayout::for_state_dir(dir.path());

        assert_eq!(state.root, dir.path());
        assert_eq!(state.device_id, dir.path().join("device_id"));
        assert_eq!(state.drafts, dir.path().join("drafts"));
        assert_eq!(state.capture, dir.path().join("capture"));
        assert_eq!(state.intents, dir.path().join("intents"));
        assert_eq!(state.intent_payloads, dir.path().join("intent-payloads"));
        assert_eq!(state.baselines, dir.path().join("baselines"));
        assert_eq!(state.exchange, dir.path().join("exchange"));
    }

    #[test]
    fn session_layout_mints_control_root_tree() {
        let dir = tempdir().test_ok("tempdir");
        let control = dir.path().join("control");
        let session = SessionLayout::for_control_root(&control, "workspace-42");
        let base = control.join("session").join("workspace-42");

        assert_eq!(session.root, base);
        assert_eq!(session.state, base.join("state"));
        assert_eq!(session.cache, base.join("cache"));
        assert_eq!(session.runtime, base.join("runtime"));
        assert_eq!(session.state_layout().root, base.join("state"));
        assert_eq!(
            session.state_layout().intents,
            base.join("state").join("intents")
        );
    }

    #[test]
    fn relative_segments_project_the_same_inventory() {
        let dir = tempdir().test_ok("tempdir");
        let layout = WorkspaceLayout::for_workspace(dir.path());
        let root: &Path = dir.path();

        let rel = |text: &str| -> PathBuf { root.join(Path::new(text)) };
        assert_eq!(rel(layout::relative::ROOT).as_path(), layout.root.as_path());
        assert_eq!(
            rel(layout::relative::LAYOUT_HEAD).as_path(),
            layout.layout_head.as_path()
        );
        assert_eq!(
            rel(layout::relative::TRASH_V1).as_path(),
            layout.trash.as_path()
        );
        assert_eq!(
            rel(layout::relative::PURGED_V1).as_path(),
            layout.purged.as_path()
        );
        assert_eq!(
            rel(layout::relative::SYNC_V1).as_path(),
            layout.sync.as_path()
        );
        assert_eq!(
            rel(layout::relative::HISTORY_V1).as_path(),
            root.join(".lomo").join("history").join("v1")
        );

        // Prefixes used by archive/rebuild classifiers are the same segments.
        for prefix in [
            layout::relative::HISTORY_PREFIX,
            layout::relative::STATE_PREFIX,
            layout::relative::TRASH_PREFIX,
            layout::relative::PURGED_PREFIX,
            layout::relative::IDENTITY_V1_PREFIX,
            layout::relative::LOCAL_PREFIX,
            layout::relative::OPERATIONS_PREFIX,
            layout::relative::SYNC_PREFIX,
            layout::relative::MIGRATION_STAGING_PREFIX,
        ] {
            assert!(
                prefix.starts_with(layout::relative::ROOT_PREFIX),
                "{prefix} must live under .lomo/"
            );
        }
        // `.lomo-remote`/`".lomo-sqlite"` are reserved sibling roots beside `.lomo`,
        // not children of it — their spellings still live in this one inventory.
        assert_eq!(
            layout::relative::REMOTE_PREFIX,
            format!("{}/", lomo_workspace::LOMO_REMOTE_ROOT_NAME)
        );
        assert_eq!(
            layout::relative::SQLITE_ROOT_PREFIX,
            lomo_workspace::LOMO_SQLITE_ROOT_NAME
        );
    }
}
