// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
//! Behavior Contract:
//! Capability: verified document absence retires identities while preserving allocation and replay;
//! owning layer: lomo-workspace; priority: P0.
//! Scenarios:
//! - Given a missing source, when retired and recreated, then new identities append and old IDs cannot be reused.
//! - Given a repeated absence operation, when replayed, then the identity map is unchanged.
//! - Given an existing operation ID, when used for absence, then the transition is rejected.
//! - Given ordinary empty external content, when reconciled without absence proof, then conflicts remain.
//!
//! Observable outcomes: retired IDs, conflict evidence, checksummed round trips and append rejection.
//!
//! TDD proof: application `external_document_deletion_contract` first failed with `stale_identity_map`;
//! this suite verifies the new identity transition's boundary and journal behavior.
//!
//! Excludes: platform absence proof and SQLite projection ownership.

#[cfg(test)]
#[path = "support/result_failure.rs"]
mod result_failure;
#[cfg(test)]
#[path = "support/result_success.rs"]
mod result_success;
#[cfg(test)]
mod tests {
    use lomo_core::OperationId;
    use lomo_workspace::{
        DocumentPatchCommand, MemoId, MemoIdentityChange, MemoIdentityMap, SourceBytes,
        WorkspaceDocument, WorkspaceRelativePath, WorkspaceRootId, parse_workspace_document,
        plan_document_patch,
    };

    use super::result_failure::ResultFailureTestExt;
    use super::result_success::ResultTestExt;

    fn document(raw: &str) -> WorkspaceDocument {
        parse_workspace_document(
            &SourceBytes::try_from_str(raw).test_ok("UTF-8 source"),
            "2026_09_08",
        )
        .test_ok("document")
    }

    fn path() -> WorkspaceRelativePath {
        WorkspaceRelativePath::parse("journal/2026_09_08.md").test_ok("path")
    }

    fn operation(raw: &str) -> OperationId {
        OperationId::parse(raw).test_ok("operation")
    }

    fn id(raw: &str) -> MemoId {
        MemoId::parse(raw).test_ok("opaque memo ID")
    }

    fn initialize(raw: &str, ids: &[&str]) -> (WorkspaceDocument, MemoIdentityMap) {
        let doc = document(raw);
        let map = MemoIdentityMap::initialize(
            operation("import"),
            WorkspaceRootId::Notes,
            path(),
            &doc,
            ids.iter().map(|raw| id(raw)).collect(),
        )
        .test_ok("initialize mapping from physical document");
        (doc, map)
    }

    #[test]
    fn absent_source_can_be_recreated_without_reusing_retired_identities() {
        let (_, map) = initialize("- 09:00:00\nfirst\n", &["old-id"]);
        let empty = document("");
        let retired = map
            .retire_missing_document(operation("absent"))
            .test_ok("retire");
        assert_eq!(retired.memo_ids(), Vec::<MemoId>::new());
        assert_eq!(retired.conflicts(), []);
        assert!(retired.retired_ids().contains(&id("old-id")));
        let decoded =
            MemoIdentityMap::decode(&retired.encode().test_ok("encode")).test_ok("decode");
        assert_eq!(retired, decoded);
        assert_eq!(
            retired
                .retire_missing_document(operation("absent"))
                .test_ok("replay"),
            retired
        );
        let patch = plan_document_patch(
            &empty,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: empty.source().fingerprint().clone(),
                time_part: "10:00:00".to_owned(),
                content: "new memo".to_owned(),
            },
        )
        .test_ok("append plan");
        let changed = retired
            .apply_patch(
                operation("append-new"),
                MemoIdentityChange::Append(id("new-id")),
                &empty,
                &patch,
            )
            .test_ok("append");
        assert_eq!(changed.map.memo_ids(), vec![id("new-id")]);
        assert_eq!(
            retired
                .apply_patch(
                    operation("reuse-old"),
                    MemoIdentityChange::Append(id("old-id")),
                    &empty,
                    &patch
                )
                .test_err("reuse rejected")
                .code(),
            "memo_id_reused"
        );
        assert_eq!(
            retired
                .retire_missing_document(operation("import"))
                .test_err("operation reuse rejected")
                .code(),
            "identity_operation_mismatch"
        );
    }

    #[test]
    fn an_empty_external_edit_without_absence_proof_preserves_conflict_evidence() {
        let (_, map) = initialize("- 09:00:00\nfirst\n", &["old-id"]);
        let observed = map
            .reconcile_external(operation("external-empty"), &document(""))
            .test_ok("external edit");
        assert_eq!(observed.conflicts().len(), 1);
        assert!(observed.retired_ids().is_empty());
    }

    #[test]
    fn repeated_absence_observations_survive_journal_checkpointing() {
        let (_, mut map) = initialize("- 09:00:00\nfirst\n", &["old-id"]);
        for index in 0..300 {
            map = map
                .retire_missing_document(operation(&format!("absent-{index}")))
                .test_ok("retire");
        }
        let decoded = MemoIdentityMap::decode(&map.encode().test_ok("encode")).test_ok("decode");
        assert_eq!(decoded, map);
        assert!(decoded.retired_ids().contains(&id("old-id")));
    }
}
