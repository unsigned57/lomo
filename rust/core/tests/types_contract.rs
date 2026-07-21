//! Behavior Contract
//!
//! Capability: reject invalid identifiers, paths, workspace descriptors, and resource limits
//! before they can enter the application-kernel state machine.
//!
//! Scenarios:
//! - Given an opaque identifier, when it is blank, oversized, contains controls/path syntax, or
//!   uses unsupported characters, then construction fails with validation instead of normalizing.
//! - Given a workspace-relative path, when it is absolute, ambiguous, escaped, or oversized, then
//!   construction fails; a bounded canonical relative path is preserved byte-for-byte.
//! - Given direct aliases to the same canonical directory or a SAF capability identity, when a
//!   workspace identity is derived, then aliases converge and access modes cannot collide.
//! - Given a page size outside 1..=256, when it crosses the boundary, then it is rejected rather
//!   than clamped or replaced with a default.
//!
//! Observable outcomes: constrained values, stable workspace ids, and structured error fields.
//! TDD proof: RED on 2026-07-15 with unresolved lomo-core imports because the constrained types
//! and constructors did not exist; GREEN is recorded in STAGE1-EVIDENCE.md.
//! Excludes: engine actor behavior, journal persistence, Android URI resolution, and FFI mapping.

#[cfg(test)]
#[path = "support/failure.rs"]
mod failure_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
mod tests {

    use std::fs;

    use lomo_core::{
        CapabilityToken, ErrorCategory, JobId, PageSize, RelativeWorkspacePath, WorkspaceDescriptor,
    };
    use tempfile::tempdir;

    use super::failure_support::ResultFailureTestExt;
    use super::support::ResultTestExt;

    #[test]
    fn opaque_ids_reject_ambiguous_or_unbounded_values() {
        for invalid in [
            "", "   ", "job/1", "job\\1", "job\n1", "../job", "job 1", "任务-1",
        ] {
            let error = JobId::parse(invalid).must_fail("invalid id must be rejected");
            assert_eq!(error.category(), ErrorCategory::Validation);
            assert_eq!(error.code(), "invalid_job_id");
        }

        let oversized = "a".repeat(129);
        let error = JobId::parse(&oversized).must_fail("129-byte id must be rejected");
        assert_eq!(error.code(), "invalid_job_id");

        let boundary = format!("job-{}", "a".repeat(124));
        let id = JobId::parse(&boundary).must_succeed("128-byte id is valid");
        assert_eq!(id.as_str(), boundary);
    }

    #[test]
    fn workspace_paths_are_canonical_relative_values() {
        for invalid in [
            "",
            "/memo.md",
            "memo\\file.md",
            "memo//file.md",
            "memo/./file.md",
            "memo/../file.md",
            "../memo.md",
        ] {
            let error = RelativeWorkspacePath::parse(invalid)
                .must_fail("ambiguous or escaped path must be rejected");
            assert_eq!(error.category(), ErrorCategory::Validation);
            assert_eq!(error.code(), "invalid_workspace_path");
        }

        let boundary_segment = format!("{}.md", "a".repeat(252));
        RelativeWorkspacePath::parse(&boundary_segment).must_succeed("255-byte segment is valid");
        let oversized_segment = format!("{}.md", "a".repeat(253));
        RelativeWorkspacePath::parse(&oversized_segment)
            .must_fail("256-byte segment must be rejected");
        let oversized_path = (0..18)
            .map(|index| format!("{index:02}{}", "a".repeat(240)))
            .collect::<Vec<_>>()
            .join("/");
        RelativeWorkspacePath::parse(&oversized_path)
            .must_fail("path longer than 4096 bytes must be rejected");

        let path = RelativeWorkspacePath::parse("memos/2026-07-15.md").must_succeed("valid path");
        assert_eq!(path.as_str(), "memos/2026-07-15.md");
    }

    #[cfg(unix)]
    #[test]
    fn workspace_identity_uses_canonical_root_and_access_mode() {
        use std::os::unix::fs::symlink;

        let temporary = tempdir().must_succeed("temporary directory");
        let direct_root = temporary.path().join("workspace");
        fs::create_dir(&direct_root).must_succeed("workspace directory");
        let alias = temporary.path().join("workspace-alias");
        symlink(&direct_root, &alias).must_succeed("workspace symlink");

        let direct = WorkspaceDescriptor::direct(&direct_root).must_succeed("direct workspace");
        let aliased = WorkspaceDescriptor::direct(&alias).must_succeed("canonical alias");
        assert_eq!(direct.identity(), aliased.identity());

        let capability =
            CapabilityToken::parse("root-capability-1").must_succeed("capability token");
        let saf = WorkspaceDescriptor::saf(capability);
        assert_ne!(direct.identity(), saf.identity());
    }

    #[test]
    fn page_size_is_rejected_instead_of_clamped() {
        for invalid in [0, 257, u32::MAX] {
            let error = PageSize::new(invalid).must_fail("out-of-range page size");
            assert_eq!(error.category(), ErrorCategory::ResourceLimit);
            assert_eq!(error.code(), "invalid_page_size");
        }
        assert_eq!(PageSize::new(256).must_succeed("upper bound").get(), 256);
    }

    #[test]
    fn event_sequence_gap_requires_full_invalidate_scope() {
        use lomo_core::{
            EventSequence, InvalidationScope, event_sequence_requires_full_invalidate,
        };

        let last = EventSequence::from_raw(3);
        assert!(!event_sequence_requires_full_invalidate(
            last,
            EventSequence::from_raw(4)
        ));
        assert!(!event_sequence_requires_full_invalidate(
            last,
            EventSequence::from_raw(3)
        ));
        assert!(event_sequence_requires_full_invalidate(
            last,
            EventSequence::from_raw(6)
        ));
        assert!(event_sequence_requires_full_invalidate(
            last,
            EventSequence::from_raw(2)
        ));
        // Bounded scope set is part of the public contract surface.
        assert_ne!(InvalidationScope::Full, InvalidationScope::MemoList);
        let scopes = [
            InvalidationScope::MemoList,
            InvalidationScope::Search,
            InvalidationScope::Trash,
            InvalidationScope::Pin,
            InvalidationScope::Tags,
            InvalidationScope::Stats,
            InvalidationScope::Reminder,
            InvalidationScope::Full,
        ];
        assert_eq!(scopes.len(), 8);
    }

    #[test]
    fn core_revision_checked_next_advances_monotonically() {
        use lomo_core::CoreRevision;
        let initial = CoreRevision::initial();
        assert_eq!(initial.get(), 0);
        let Some(next) = initial.checked_next() else {
            panic!("next revision must exist from initial")
        };
        assert_eq!(next.get(), 1);
        assert_eq!(CoreRevision::from_raw(42).get(), 42);
    }
}
