//! Behavior Contract
//!
//! Capability: the durable identity journal is a bounded replay witness — history evicted
//! from the retained operation tail folds into a durable checkpoint, so the record's
//! fingerprint chain and allocation accounting still validate end to end after truncation.
//! Owning layer: lomo-workspace identity record; priority P0.
//!
//! Scenarios:
//! - Given more committed identity transitions than the retained window, when the map is
//!   encoded and decoded, then the operation tail stays bounded and round trips exactly.
//! - Given a truncated record whose checkpoint or tail chain is rewritten on the wire, when
//!   decoded, then validation rejects it instead of trusting fabricated history.
//! - Given an operation inside the retained window, when replayed with identical facts, then
//!   the map returns itself; replayed with different facts the operation id conflicts.
//! - Given a durable record whose unbounded journal predates the checkpoint field, when
//!   decoded, then the complete chain still validates, and the next proven transition folds
//!   the overflow into a checkpoint instead of growing further.
//!
//! Observable outcomes: bounded `operations()`, bounded record bytes, checksummed round
//! trips, `identity_operation_mismatch` and `invalid_identity_journal` rejections.
//! TDD proof: RED — `MemoIdentityMap.operations` is append-only today, so the bounded-tail
//! assertion fails and no `journal_checkpoint` field exists to preserve or corrupt.
//! Excludes: session-level expiry arbitration — lomo-application consults the projection's
//! committed-operation index once an operation is evicted from every durable witness window.

#[cfg(test)]
#[path = "support/option_success.rs"]
mod option_success;
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

    use super::option_success::OptionTestExt;
    use super::result_failure::ResultFailureTestExt;
    use super::result_success::ResultTestExt;

    /// The retained durable witness window this contract pins for one document.
    const WITNESS_BUDGET: usize = 64;
    /// Upper bound for the serialized record: the tail plus checkpoint plus live
    /// bindings must fit a bounded byte budget that never scales with history depth.
    const RECORD_BYTE_BUDGET: usize = WITNESS_BUDGET * 512;

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

    /// Applies one proven `Update` transition to the memo `id` and hands back the
    /// advanced map together with the post-patch document the transition was
    /// proven against.
    fn update_memo_body(
        map: &MemoIdentityMap,
        doc: &WorkspaceDocument,
        memo: &MemoId,
        op: &str,
        content: &str,
    ) -> (MemoIdentityMap, WorkspaceDocument) {
        let target = map
            .locator(memo)
            .test_ok("live locator")
            .resolve(WorkspaceRootId::Notes, &path(), doc)
            .test_ok("resolve memo");
        let patch = plan_document_patch(
            doc,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: doc.source().fingerprint().clone(),
                identity: target.identity().clone(),
                content: content.to_owned(),
            },
        )
        .test_ok("plan memo update");
        let outcome = map
            .apply_patch(
                operation(op),
                MemoIdentityChange::Update(memo.clone()),
                doc,
                &patch,
            )
            .test_ok("apply proven update");
        (outcome.map, outcome.document)
    }

    /// Commits `count` proven memo updates on one document, returning the final
    /// map and document. Operation ids are `op-0..op-{count-1}`.
    fn commit_updates(count: usize) -> (WorkspaceDocument, MemoIdentityMap) {
        let (mut doc, mut map) = initialize("- 09:00:00\nbody v0\n", &["a"]);
        for index in 0..count {
            let (next_map, next_doc) = update_memo_body(
                &map,
                &doc,
                &id("a"),
                &format!("op-{index}"),
                &format!("body v{}", index + 1),
            );
            map = next_map;
            doc = next_doc;
        }
        (doc, map)
    }

    #[test]
    fn retained_witness_tail_stays_bounded_and_round_trips() {
        let (_, map) = commit_updates(WITNESS_BUDGET + 20);
        assert!(
            map.operations().len() <= WITNESS_BUDGET,
            "durable identity journal must be a bounded tail, not the full {}-operation history",
            WITNESS_BUDGET + 20
        );
        let bytes = map.encode().test_ok("encode bounded record");
        assert!(
            bytes.len() <= RECORD_BYTE_BUDGET,
            "identity record grew to {} bytes; per-commit write cost must not scale with history",
            bytes.len()
        );
        let decoded = MemoIdentityMap::decode(&bytes).test_ok("decode truncated record");
        assert_eq!(decoded, map);
        assert_eq!(decoded.memo_ids(), vec![id("a")]);
        assert_eq!(decoded.operations().len(), map.operations().len());
    }

    #[test]
    fn truncated_record_rejects_rewritten_checkpoint_and_tail_chain() {
        let (_, map) = commit_updates(WITNESS_BUDGET + 10);
        let wire = serde_json::to_value(&map).test_ok("wire");
        assert!(
            wire.get("journal_checkpoint").is_some(),
            "evicted history must fold into a durable checkpoint"
        );

        // A checkpoint that does not anchor the retained tail is fabricated history.
        let mut wrong_anchor = wire.clone();
        *wrong_anchor
            .pointer_mut("/journal_checkpoint/after")
            .test_ok("checkpoint anchor") = serde_json::json!("0".repeat(64));
        let error = serde_json::from_value::<MemoIdentityMap>(wrong_anchor)
            .test_err("checkpoint must anchor the retained tail");
        assert!(error.to_string().contains("invalid_identity_journal"));

        // A tail whose first link does not cite the checkpoint is a broken chain.
        let mut broken_chain = wire.clone();
        *broken_chain
            .pointer_mut("/operations/0/before")
            .test_ok("first retained op") = serde_json::json!("1".repeat(64));
        let error = serde_json::from_value::<MemoIdentityMap>(broken_chain)
            .test_err("tail must continue the checkpoint chain");
        assert!(error.to_string().contains("invalid_identity_journal"));

        // Dropping the checkpoint makes the headless tail invalid: the first
        // retained operation is not the Initialize genesis.
        let mut headless = wire;
        headless
            .as_object_mut()
            .test_ok("record object")
            .remove("journal_checkpoint");
        let error = serde_json::from_value::<MemoIdentityMap>(headless)
            .test_err("tail without checkpoint loses its genesis anchor");
        assert!(error.to_string().contains("invalid_identity_journal"));
    }

    #[test]
    fn in_window_replay_is_idempotent_and_mismatch_is_rejected() {
        // Drive the tail past the window, then replay the most recent operation
        // with its exact proven inputs.
        let (before_doc, before_map) = commit_updates(WITNESS_BUDGET + 9);
        let last = format!("op-{}", WITNESS_BUDGET + 9);
        let target = before_map
            .locator(&id("a"))
            .test_ok("locator")
            .resolve(WorkspaceRootId::Notes, &path(), &before_doc)
            .test_ok("memo");
        let patch = plan_document_patch(
            &before_doc,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: before_doc.source().fingerprint().clone(),
                identity: target.identity().clone(),
                content: format!("body v{}", WITNESS_BUDGET + 10),
            },
        )
        .test_ok("replan the last proven transition");
        let map = before_map
            .apply_patch(
                operation(&last),
                MemoIdentityChange::Update(id("a")),
                &before_doc,
                &patch,
            )
            .test_ok("apply the final transition")
            .map;
        assert!(
            map.operations().len() <= WITNESS_BUDGET,
            "setup requires a truncated tail"
        );

        let replayed = map
            .apply_patch(
                operation(&last),
                MemoIdentityChange::Update(id("a")),
                &before_doc,
                &patch,
            )
            .test_ok("in-window replay is idempotent");
        assert_eq!(replayed.map, map);
        assert_eq!(replayed.refingerprinted_blocks, 0);

        let mismatched = map
            .apply_patch(
                operation(&last),
                MemoIdentityChange::Remove(id("a")),
                &before_doc,
                &patch,
            )
            .test_err("same operation id with other facts conflicts");
        assert_eq!(mismatched.code(), "identity_operation_mismatch");
    }

    #[test]
    fn pre_window_unbounded_records_still_decode_and_fold_on_next_write() {
        let (doc, map) = commit_updates(1);
        let mut wire = serde_json::to_value(&map).test_ok("wire");
        let fingerprint = wire
            .get("fingerprint")
            .test_ok("record fingerprint")
            .clone();
        // Shape a pre-window durable record: an over-long journal with no
        // checkpoint field, as older app versions wrote it.
        let operations = wire
            .pointer_mut("/operations")
            .test_ok("operations")
            .as_array_mut()
            .test_ok("operations array");
        for index in 0..90 {
            operations.push(serde_json::json!({
                "operation_id": format!("legacy-{index}"),
                "before": fingerprint,
                "after": fingerprint,
                "transition": {"Patch": {"Update": "a"}},
            }));
        }
        let legacy = serde_json::from_value::<MemoIdentityMap>(wire)
            .test_ok("unbounded pre-window record still validates its complete chain");
        assert_eq!(legacy.operations().len(), 92);
        let (folded, _) = update_memo_body(&legacy, &doc, &id("a"), "op-fold", "body final");
        assert!(
            folded.operations().len() <= WITNESS_BUDGET,
            "the next proven transition folds evicted history into the checkpoint"
        );
        let round_trip =
            MemoIdentityMap::decode(&folded.encode().test_ok("encode")).test_ok("decode");
        assert_eq!(round_trip, folded);
    }
}
