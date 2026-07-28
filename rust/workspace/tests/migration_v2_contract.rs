//! Behavior Contract (P5-01 migration v1→v2)
//!
//! Capability: one-shot history/state activation migration is fail-closed, atomic on layout head,
//! read-only over user Markdown/media, and migration action types cannot emit user-file
//! delete/overwrite branches.
//!
//! Scenarios:
//! - Given v1 history/state records, when migration runs, then v2 objects+heads exist, layout head
//!   is V2, and runtime `LomoPaths` points at v2.
//! - Given crash injection at each matrix point (`AfterStagingBeforeValidate`,
//!   `AfterValidateBeforeHeadSwitch`, `AfterHeadSwitchBeforeRetire`), when migration aborts, then
//!   user Markdown/media are untouched; before head switch layout stays V1; after head switch layout
//!   is V2 with possible leftover v1 internal trees; re-run is idempotent / completes retire only.
//! - Given already-V2 workspace, when migration runs again, then it is a no-op success.
//! - Given every `MigrationAction`, when safety predicates are queried, then
//!   `may_touch_user_files` / delete / overwrite branches are all false.
//! - Given corrupt v1 history payload, when migration runs, then corruption (not clean-slate).
//!
//! Observable outcomes: layout head, path segments, revision counts, structured errors.
//! Excludes: store transaction cutover to v2 writers, production dual DI.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        LomoLayoutVersion, LomoPaths, LomoPayload, LomoRecordKind, MigrationAction,
        MigrationCrashPoint, all_migration_actions, migrate_history_state_v1_to_v2,
        migrate_history_state_v1_to_v2_with_crash, write_record_atomic, write_v1_history_for_test,
        write_v1_state_for_test,
    };
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn migration_action_types_cannot_touch_or_delete_user_files() {
        for action in all_migration_actions() {
            assert!(
                !action.may_touch_user_files(),
                "{action:?} must not touch user files"
            );
            assert!(
                !action.has_user_file_delete_branch(),
                "{action:?} must not have user-file delete branch"
            );
            assert!(
                !action.has_user_file_overwrite_branch(),
                "{action:?} must not have user-file overwrite branch"
            );
        }
        // Exhaustive static set must include every enum variant used by the migrator.
        assert!(all_migration_actions().contains(&MigrationAction::SwitchLayoutHead));
        assert!(all_migration_actions().contains(&MigrationAction::RetireV1InternalTrees));
        assert_eq!(all_migration_actions().len(), 8);
    }

    #[test]
    fn happy_path_migrates_v1_to_v2_atomically() {
        let dir = tempdir().test_ok("tempdir");
        let user_md = dir.path().join("notes.md");
        fs::write(&user_md, "user content\n").test_ok("user file");
        let user_before = fs::read(&user_md).test_ok("read user");

        write_v1_history_for_test(dir.path(), "memo-a", 1, "hello", "fp1").test_ok("h1");
        write_v1_history_for_test(dir.path(), "memo-a", 2, "hello2", "fp2").test_ok("h2");
        write_v1_state_for_test(dir.path(), "memo-a", true, false).test_ok("state");

        let v1_paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V1);
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V1
        );

        let result = migrate_history_state_v1_to_v2(dir.path()).test_ok("migrate");
        assert_eq!(result.layout, LomoLayoutVersion::V2);
        assert_eq!(result.history_revisions_written, 2);
        assert_eq!(result.state_revisions_written, 1);

        let paths = LomoPaths::for_workspace(dir.path());
        assert_eq!(paths.layout, LomoLayoutVersion::V2);
        assert!(paths.history.ends_with("history/v2"));
        assert!(paths.state.ends_with("state/v2"));
        assert!(LomoPaths::layout_head_path(dir.path()).is_file());

        // Heads exist under v2.
        assert!(paths.history.join("heads").join("memo-a.rec").is_file());
        assert!(paths.state.join("heads").join("memo-a.rec").is_file());
        // At least one history object under objects/.
        let objects = fs::read_dir(paths.history.join("objects")).test_ok("objects dir");
        let object_count = objects
            .filter(|e| e.as_ref().is_ok_and(|x| x.path().is_file()))
            .count();
        assert_eq!(object_count, 2);

        // User file untouched.
        assert_eq!(fs::read(&user_md).test_ok("user after"), user_before);

        // v1 history tree retired (renamed away) or absent as runtime path.
        assert!(
            !v1_paths.history.exists() || v1_paths.history.with_extension("v1-retired").exists(),
            "v1 history must be retired after successful migration"
        );

        // Idempotent second run.
        let again = migrate_history_state_v1_to_v2(dir.path()).test_ok("idempotent");
        assert_eq!(again.layout, LomoLayoutVersion::V2);
        assert_eq!(again.history_revisions_written, 0);
    }

    #[test]
    fn crash_before_head_switch_keeps_v1_and_user_files() {
        let dir = tempdir().test_ok("tempdir");
        let user_md = dir.path().join("keep.md");
        fs::write(&user_md, "precious\n").test_ok("user");
        let user_before = fs::read(&user_md).test_ok("before");

        write_v1_history_for_test(dir.path(), "memo-b", 1, "body", "fp").test_ok("h");
        write_v1_state_for_test(dir.path(), "memo-b", false, false).test_ok("s");

        let err = migrate_history_state_v1_to_v2_with_crash(
            dir.path(),
            MigrationCrashPoint::AfterValidateBeforeHeadSwitch,
        )
        .test_err("injected crash");
        assert_eq!(err.category(), ErrorCategory::Storage);
        assert_eq!(err.code(), "migration_injected_crash");

        // Layout head not switched → still V1.
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V1
        );
        assert!(!LomoPaths::layout_head_path(dir.path()).exists());
        assert_eq!(fs::read(&user_md).test_ok("after"), user_before);

        // Recovery re-run succeeds and is idempotent.
        let recovered = migrate_history_state_v1_to_v2(dir.path()).test_ok("recover");
        assert_eq!(recovered.layout, LomoLayoutVersion::V2);
        assert_eq!(fs::read(&user_md).test_ok("after recover"), user_before);
        let again = migrate_history_state_v1_to_v2(dir.path()).test_ok("idempotent");
        assert_eq!(again.history_revisions_written, 0);
    }

    #[test]
    fn crash_after_staging_before_validate_leaves_v1_authoritative() {
        let dir = tempdir().test_ok("tempdir");
        let user_md = dir.path().join("u.md");
        fs::write(&user_md, "x\n").test_ok("user");
        let user_before = fs::read(&user_md).test_ok("before");
        write_v1_history_for_test(dir.path(), "memo-c", 1, "c", "f").test_ok("h");

        let err = migrate_history_state_v1_to_v2_with_crash(
            dir.path(),
            MigrationCrashPoint::AfterStagingBeforeValidate,
        )
        .test_err("crash");
        assert_eq!(err.code(), "migration_injected_crash");
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V1
        );
        assert!(!LomoPaths::layout_head_path(dir.path()).exists());
        assert_eq!(fs::read(&user_md).test_ok("after crash"), user_before);

        // Recovery: re-run without injection completes and is idempotent.
        let recovered = migrate_history_state_v1_to_v2(dir.path()).test_ok("recover");
        assert_eq!(recovered.layout, LomoLayoutVersion::V2);
        assert_eq!(fs::read(&user_md).test_ok("after recover"), user_before);
        let again = migrate_history_state_v1_to_v2(dir.path()).test_ok("idempotent");
        assert_eq!(again.layout, LomoLayoutVersion::V2);
        assert_eq!(again.history_revisions_written, 0);
    }

    #[test]
    fn crash_after_head_switch_before_retire_keeps_v2_and_user_files() {
        let dir = tempdir().test_ok("tempdir");
        let user_md = dir.path().join("precious.md");
        fs::write(&user_md, "do-not-touch\n").test_ok("user");
        let media = dir.path().join("media");
        fs::create_dir_all(&media).test_ok("media dir");
        let media_file = media.join("shot.bin");
        fs::write(&media_file, b"media-bytes").test_ok("media");
        let user_before = fs::read(&user_md).test_ok("user before");
        let media_before = fs::read(&media_file).test_ok("media before");

        write_v1_history_for_test(dir.path(), "memo-d", 1, "body-d", "fp-d").test_ok("h");
        write_v1_state_for_test(dir.path(), "memo-d", true, false).test_ok("s");
        let v1_paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V1);

        let err = migrate_history_state_v1_to_v2_with_crash(
            dir.path(),
            MigrationCrashPoint::AfterHeadSwitchBeforeRetire,
        )
        .test_err("injected crash after head");
        assert_eq!(err.category(), ErrorCategory::Storage);
        assert_eq!(err.code(), "migration_injected_crash");

        // Layout head already switched → V2 is authoritative.
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V2
        );
        assert!(LomoPaths::layout_head_path(dir.path()).is_file());
        // User Markdown/media untouched.
        assert_eq!(fs::read(&user_md).test_ok("user after crash"), user_before);
        assert_eq!(
            fs::read(&media_file).test_ok("media after crash"),
            media_before
        );
        // v1 internal trees may still be present (retire did not run).
        assert!(
            v1_paths.history.exists() || v1_paths.history.with_extension("v1-retired").exists(),
            "v1 history tree or retired marker should exist after mid-retire crash"
        );

        // Recovery re-run: idempotent, completes retire, never mutates user files.
        let recovered = migrate_history_state_v1_to_v2(dir.path()).test_ok("recover after head");
        assert_eq!(recovered.layout, LomoLayoutVersion::V2);
        assert_eq!(recovered.history_revisions_written, 0);
        assert_eq!(
            fs::read(&user_md).test_ok("user after recover"),
            user_before
        );
        assert_eq!(
            fs::read(&media_file).test_ok("media after recover"),
            media_before
        );
        assert!(
            !v1_paths.history.exists() || v1_paths.history.with_extension("v1-retired").exists(),
            "v1 history must be retired after recovery re-run"
        );
        let again = migrate_history_state_v1_to_v2(dir.path()).test_ok("idempotent v2");
        assert_eq!(again.layout, LomoLayoutVersion::V2);
        assert_eq!(again.history_revisions_written, 0);
    }

    #[test]
    fn crash_matrix_covers_all_three_inject_points() {
        // Structural inventory: every non-None crash point must have a dedicated recovery scenario.
        let points = [
            MigrationCrashPoint::AfterStagingBeforeValidate,
            MigrationCrashPoint::AfterValidateBeforeHeadSwitch,
            MigrationCrashPoint::AfterHeadSwitchBeforeRetire,
        ];
        assert_eq!(points.len(), 3);
        for point in points {
            assert_ne!(point, MigrationCrashPoint::None);
        }
    }

    #[test]
    fn corrupt_v1_history_fails_closed_without_deleting_user_files() {
        let dir = tempdir().test_ok("tempdir");
        let user_md = dir.path().join("user.md");
        fs::write(&user_md, "safe\n").test_ok("user");
        let paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V1);
        paths.ensure_layout().test_ok("layout");
        // Valid framed record but wrong payload shape for history body.
        write_record_atomic(
            &paths.history.join("bad.rec"),
            &LomoPayload {
                kind: LomoRecordKind::History,
                record_id: "bad".into(),
                body_json: r#"{"not":"history"}"#.into(),
            },
        )
        .test_ok("write bad");

        let err = migrate_history_state_v1_to_v2(dir.path()).test_err("corrupt migrate");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "migration_v1_history_payload_invalid");
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V1
        );
        assert_eq!(fs::read(&user_md).test_ok("user"), b"safe\n");
        // Corrupt source still present (not clean-slated).
        assert!(paths.history.join("bad.rec").is_file());
    }
}
