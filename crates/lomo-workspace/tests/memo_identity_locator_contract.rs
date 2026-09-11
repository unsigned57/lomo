//! Behavior Contract
//!
//! Capability: separate durable opaque memo IDs from byte-exact, versioned document addresses.
//! Owning layer: lomo-workspace; priority P0.
//! Scenarios:
//! - Given three identical same-second memos, deletion of the middle memo preserves the other IDs.
//! - Given a retried operation, the same identity transition is replayed without allocating an ID.
//! - Given unique unchanged blocks after an external reorder, exact-byte evidence preserves IDs.
//! - Given duplicate or changed blocks after an external edit, conflicts retain old locators and
//!   current candidates; no order or similarity heuristic attaches an old identity.
//! - Given corrupt durable records or unvalidated wire values, decode rejects them at the edge.
//! - Given a filename-safe legacy ID containing spaces, dots or Unicode, durable round trips
//!   preserve it byte for byte; an occurrence index cannot exceed its document block index.
//! - Given a valid checksum around an inconsistent mapping or journal, semantic validation still
//!   rejects out-of-order, sparse or unaccounted identities before any update can renumber them.
//!
//! Observable outcomes: stable IDs, exact locators, retired-ID protection, conflict evidence,
//! checksummed record round trips and unchanged source bytes.
//! TDD proof: the first run fails because `MemoId`, `MemoLocator` and the durable identity map do not
//! exist; the previous date/time/ordinal model cannot satisfy stable deletion or replay.
//! Test Change Justification: contract correction. Rejecting filename-safe spaces/dots inferred
//! an ID format the product does not define. Positive opaque-ID round trips preserve coverage;
//! path segments, controls and impossible source coordinates still fail at the boundary.
//! Excludes: platform I/O, database projection, cryptographic random source and UI presentation.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use lomo_core::OperationId;
    use lomo_workspace::{
        DocumentPatchCommand, LomoPayload, LomoRecordKind, MemoId, MemoIdentityChange,
        MemoIdentityMap, MemoLocator, SourceBytes, WorkspaceDocument, WorkspaceRelativePath,
        WorkspaceRootId, encode_record, memo_identity_record_path, parse_workspace_document,
        plan_document_patch,
    };

    use super::support::{OptionTestExt, ResultTestExt};

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
    fn same_second_deletion_never_renumbers_or_reuses_persistent_ids() {
        let raw = "- 09:00:00\nsame\n\n- 09:00:00\nsame\n\n- 09:00:00\nsame\n";
        let (doc, map) = initialize(raw, &["random-a", "random-b", "random-c"]);
        let middle = map.locator(&id("random-b")).test_ok("middle locator");
        let memo = middle
            .resolve(WorkspaceRootId::Notes, &path(), &doc)
            .test_ok("middle memo");
        let patch = plan_document_patch(
            &doc,
            &DocumentPatchCommand::Remove {
                path: path(),
                expected_fingerprint: doc.source().fingerprint().clone(),
                identity: memo.identity().clone(),
            },
        )
        .test_ok("remove only middle memo");
        let changed = map
            .apply_patch(
                operation("delete-middle"),
                MemoIdentityChange::Remove(id("random-b")),
                &doc,
                &patch,
            )
            .test_ok("transition");
        assert_eq!(changed.memo_ids(), vec![id("random-a"), id("random-c")]);
        assert_eq!(
            changed
                .locator(&id("random-c"))
                .test_ok("surviving memo")
                .occurrence_index(),
            1
        );
        assert!(changed.retired_ids().contains(&id("random-b")));
        assert_eq!(doc.serialize_unedited(), raw.as_bytes());

        let after = document(std::str::from_utf8(patch.result_bytes()).test_ok("patch UTF-8"));
        let append = plan_document_patch(
            &after,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: after.source().fingerprint().clone(),
                time_part: "09:00:00".to_owned(),
                content: "same".to_owned(),
            },
        )
        .test_ok("new duplicate block");
        let error = changed
            .apply_patch(
                operation("create-again"),
                MemoIdentityChange::Append(id("random-b")),
                &after,
                &append,
            )
            .test_err("retired ID cannot be reused");
        assert_eq!(error.code(), "memo_id_reused");
    }

    #[test]
    fn restore_rebinds_a_retired_id_onto_the_appended_block() {
        let raw = "- 09:00:00\nsame\n\n- 09:00:00\nsame\n";
        let (doc, map) = initialize(raw, &["keep-a", "retired-b"]);
        let removed = map.locator(&id("retired-b")).test_ok("retired locator");
        let memo = removed
            .resolve(WorkspaceRootId::Notes, &path(), &doc)
            .test_ok("retired memo");
        let patch = plan_document_patch(
            &doc,
            &DocumentPatchCommand::Remove {
                path: path(),
                expected_fingerprint: doc.source().fingerprint().clone(),
                identity: memo.identity().clone(),
            },
        )
        .test_ok("remove");
        let after_delete = map
            .apply_patch(
                operation("delete-b"),
                MemoIdentityChange::Remove(id("retired-b")),
                &doc,
                &patch,
            )
            .test_ok("deleted");
        let remaining = document(std::str::from_utf8(patch.result_bytes()).test_ok("utf8"));
        let append = plan_document_patch(
            &remaining,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: remaining.source().fingerprint().clone(),
                time_part: "09:00:00".to_owned(),
                content: "same".to_owned(),
            },
        )
        .test_ok("restore append");
        let restored = after_delete
            .apply_patch(
                operation("restore-b"),
                MemoIdentityChange::Restore(id("retired-b")),
                &remaining,
                &append,
            )
            .test_ok("restored");
        assert_eq!(restored.memo_ids(), vec![id("keep-a"), id("retired-b")]);
        assert!(!restored.retired_ids().contains(&id("retired-b")));
        restored
            .locator(&id("retired-b"))
            .test_ok("restored locator");
    }

    #[test]
    fn operation_replay_survives_durable_round_trip() {
        let (doc, map) = initialize("", &[]);
        let patch = plan_document_patch(
            &doc,
            &DocumentPatchCommand::Append {
                path: path(),
                expected_fingerprint: doc.source().fingerprint().clone(),
                time_part: "09:00:00".to_owned(),
                content: "<!-- user comment -->\nbody".to_owned(),
            },
        )
        .test_ok("append");
        let change = MemoIdentityChange::Append(id("fresh-random-id"));
        let changed = map
            .apply_patch(operation("create"), change.clone(), &doc, &patch)
            .test_ok("first operation");
        let bytes = changed.encode().test_ok("durable record");
        let restored = MemoIdentityMap::decode(&bytes).test_ok("rebuild without SQLite");
        let replayed = restored
            .apply_patch(operation("create"), change, &doc, &patch)
            .test_ok("idempotent retry");
        assert_eq!(replayed, restored);
        assert_eq!(replayed.memo_ids(), vec![id("fresh-random-id")]);
        assert_eq!(replayed.operations().len(), 2);
        assert_eq!(
            patch.result_bytes(),
            b"- 09:00:00\n<!-- user comment -->\nbody\n"
        );
        let error = replayed
            .apply_patch(
                operation("create"),
                MemoIdentityChange::Append(id("another-id")),
                &doc,
                &patch,
            )
            .test_err("operation payload cannot change on retry");
        assert_eq!(error.code(), "identity_operation_mismatch");
    }

    #[test]
    fn unique_exact_blocks_keep_ids_after_external_reordering() {
        let (_, map) = initialize("- 09:00:00\nfirst\n- 10:00:00\nsecond\n", &["a", "b"]);
        let external = document("- 10:00:00\nsecond\n- 09:00:00\nfirst\n");
        let reconciled = map
            .reconcile_external(operation("external-reorder"), &external)
            .test_ok("exact matching");
        assert_eq!(reconciled.memo_ids(), vec![id("b"), id("a")]);
        assert!(reconciled.conflicts().is_empty());
        assert_eq!(reconciled.locator(&id("a")).test_ok("a").block_index(), 1);
    }

    #[test]
    fn duplicate_external_changes_produce_durable_conflicts_without_guessing() {
        let (_, map) = initialize("- 09:00:00\nsame\n- 09:00:00\nsame\n", &["a", "b"]);
        let external = document("<!-- external -->\n- 09:00:00\nsame\n- 09:00:00\nsame\n");
        let reconciled = map
            .reconcile_external(operation("external-edit"), &external)
            .test_ok("record ambiguity");
        assert!(reconciled.memo_ids().is_empty());
        assert_eq!(reconciled.conflicts().len(), 2);
        let conflict = reconciled.conflicts().first().test_ok("conflict");
        assert_eq!(conflict.previous().memo_id(), &id("a"));
        assert_eq!(conflict.candidates().len(), 2);
        assert_eq!(
            conflict.observed_fingerprint(),
            external.source().fingerprint()
        );
        assert_eq!(
            MemoIdentityMap::decode(&reconciled.encode().test_ok("encode conflicts"))
                .test_ok("decode conflicts"),
            reconciled
        );
        assert_eq!(
            external.serialize_unedited(),
            b"<!-- external -->\n- 09:00:00\nsame\n- 09:00:00\nsame\n"
        );
    }

    #[test]
    fn locator_rejects_other_roots_paths_versions_and_invalid_wire_fields() {
        let (doc, map) = initialize("\u{feff}- 09:00:00\r\nbody\r\n", &["legacy_09:00:00_7"]);
        let locator = map
            .locator(&id("legacy_09:00:00_7"))
            .test_ok("existing identity");
        let changed = document("- 09:00:00\nbody\n");
        assert_eq!(
            locator
                .resolve(WorkspaceRootId::Notes, &path(), &changed)
                .test_err("raw hash mismatch")
                .code(),
            "stale_memo_locator"
        );
        assert_eq!(
            locator
                .resolve(WorkspaceRootId::Images, &path(), &doc)
                .test_err("wrong root")
                .code(),
            "memo_locator_document_mismatch"
        );
        assert_eq!(
            locator
                .resolve(
                    WorkspaceRootId::Notes,
                    &WorkspaceRelativePath::parse("other.md").test_ok("path"),
                    &doc
                )
                .test_err("wrong path")
                .code(),
            "memo_locator_document_mismatch"
        );
        let wire = serde_json::to_value(locator).test_ok("wire");
        for (field, value) in [
            ("root_id", serde_json::json!("../notes")),
            ("path", serde_json::json!("../escape.md")),
            ("fingerprint", serde_json::json!("mtime-1")),
            ("time_token", serde_json::json!("25:99")),
            ("byte_start", serde_json::json!(u64::MAX)),
        ] {
            let mut malformed = wire.clone();
            *malformed.get_mut(field).test_ok("wire field") = value;
            let error = serde_json::from_value::<MemoLocator>(malformed)
                .test_err("wire boundary rejection");
            assert!(!error.to_string().is_empty());
        }
        let mut zero_len = wire;
        *zero_len.get_mut("byte_start").test_ok("byte_start") = serde_json::json!(10);
        *zero_len.get_mut("byte_end").test_ok("byte_end") = serde_json::json!(10);
        let zero_len_error = serde_json::from_value::<MemoLocator>(zero_len)
            .test_err("zero length locator rejection");
        assert!(zero_len_error.to_string().contains("ordered byte offsets"));

        for valid in [
            "2024_06_01_9:30_7",
            "legacy_09:00:00_7",
            "2026-07-18_09:41_0",
            "2026_07_18_09:41:00_0",
            "uuid-1234-5678",
        ] {
            assert_eq!(
                MemoId::parse(valid)
                    .test_ok("historical and opaque ID")
                    .as_str(),
                valid
            );
        }
        for invalid in [
            "",
            "..",
            ".",
            "bad\nidentity",
            "../unsafe",
            "slash/in/id",
            "backslash\\in\\id",
        ] {
            assert_eq!(
                MemoId::parse(invalid).test_err("invalid memo ID").code(),
                "invalid_memo_id"
            );
        }
    }

    #[test]
    fn filename_safe_opaque_ids_survive_identity_record_round_trips() {
        for opaque in [
            "legacy memo",
            ".hidden",
            "memo.",
            "review..draft",
            "记录 甲",
        ] {
            let (doc, map) = initialize("- 09:00:00\nbody\n", &[opaque]);
            let restored = MemoIdentityMap::decode(&map.encode().test_ok("encode opaque ID"))
                .test_ok("decode opaque ID");
            let restored_id = restored.memo_ids().pop().test_ok("single durable ID");
            assert_eq!(restored_id.as_str(), opaque);
            assert_eq!(
                restored
                    .locator(&restored_id)
                    .test_ok("opaque ID locator")
                    .resolve(WorkspaceRootId::Notes, &path(), &doc)
                    .test_ok("exact source resolution")
                    .content(),
                "body"
            );
        }
    }

    #[test]
    fn locator_rejects_occurrence_beyond_the_document_block_index() {
        let (_, map) = initialize("- 09:00:00\nbody\n", &["a"]);
        let mut wire =
            serde_json::to_value(map.locator(&id("a")).test_ok("locator")).test_ok("wire locator");
        *wire.get_mut("occurrence_index").test_ok("occurrence") = serde_json::json!(1);
        let failure = serde_json::from_value::<MemoLocator>(wire)
            .test_err("a first block cannot be the second occurrence of its timestamp");
        assert!(failure.to_string().contains("invalid_memo_locator"));
    }

    #[test]
    fn record_checksum_and_unique_assignment_are_enforced() {
        let (doc, map) = initialize("- 09:00:00\nbody\n", &["a"]);
        let mut encoded = map.encode().test_ok("encode");
        let last = encoded.last_mut().test_ok("nonempty envelope");
        *last ^= 1;
        assert!(
            !MemoIdentityMap::decode(&encoded)
                .test_err("tampered durable record")
                .code()
                .is_empty()
        );
        let error = MemoIdentityMap::initialize(
            operation("bad"),
            WorkspaceRootId::Notes,
            path(),
            &doc,
            vec![],
        )
        .test_err("each block needs an ID");
        assert_eq!(error.code(), "memo_identity_count_mismatch");
    }

    #[test]
    fn valid_json_cannot_bypass_mapping_or_journal_invariants() {
        let (_, map) = initialize("- 09:00:00\nfirst\n- 10:00:00\nsecond\n", &["a", "b"]);
        let original = serde_json::to_value(map).test_ok("wire");
        let mut reordered = original.clone();
        reordered
            .get_mut("bindings")
            .and_then(serde_json::Value::as_array_mut)
            .test_ok("bindings")
            .reverse();
        let mut sparse = original.clone();
        *sparse
            .pointer_mut("/bindings/1/locator/block_index")
            .test_ok("block index") = serde_json::json!(9);
        let mut unjournaled = original.clone();
        *unjournaled
            .pointer_mut("/allocated_ids")
            .test_ok("allocated IDs") = serde_json::json!(["a", "b", "lost-id"]);
        let mut wrong_journal = original.clone();
        *wrong_journal
            .pointer_mut("/operations/0/transition")
            .test_ok("transition") = serde_json::json!({"Initialize": ["a", "wrong-id"]});
        let mut future = original;
        *future.get_mut("schema").test_ok("schema") = serde_json::json!(999);
        for invalid in [reordered, sparse, unjournaled, wrong_journal, future] {
            let error = serde_json::from_value::<MemoIdentityMap>(invalid)
                .test_err("reject invalid identity graph at JSON boundary");
            assert!(!error.to_string().is_empty());
        }
    }

    #[test]
    fn updates_preserve_all_ids_and_reject_another_memos_patch() {
        let (doc, map) = initialize("- 09:00:00\nfirst\n- 10:00:00\nsecond\n", &["a", "b"]);
        let first = doc.memos().first().test_ok("first memo");
        let patch = plan_document_patch(
            &doc,
            &DocumentPatchCommand::Replace {
                path: path(),
                expected_fingerprint: doc.source().fingerprint().clone(),
                identity: first.identity().clone(),
                content: "changed body".to_owned(),
            },
        )
        .test_ok("replace");
        let wrong = map
            .apply_patch(
                operation("wrong-target"),
                MemoIdentityChange::Update(id("b")),
                &doc,
                &patch,
            )
            .test_err("cannot use b with a's patch");
        assert_eq!(wrong.code(), "identity_patch_target_mismatch");
        let updated = map
            .apply_patch(
                operation("update"),
                MemoIdentityChange::Update(id("a")),
                &doc,
                &patch,
            )
            .test_ok("update");
        assert_eq!(updated.memo_ids(), vec![id("a"), id("b")]);
        assert_eq!(
            updated.locator(&id("b")).test_ok("sibling").fingerprint(),
            patch.result_fingerprint()
        );
        assert_eq!(
            patch.result_bytes(),
            b"- 09:00:00\nchanged body\n- 10:00:00\nsecond\n"
        );
    }

    #[test]
    fn external_discovery_requires_fresh_ids_and_preserves_existing_evidence() {
        let (_, map) = initialize("- 09:00:00\nfirst\n", &["a"]);
        let doc = document("- 09:00:00\nfirst\n- 10:00:00\nsecond\n");
        let scanned = map
            .reconcile_external(operation("scan"), &doc)
            .test_ok("scan");
        assert_eq!(scanned.memo_ids(), vec![id("a")]);
        assert_eq!(scanned.unbound().len(), 1);
        let discovered = scanned
            .assign_discovered(operation("discover"), &doc, vec![id("new-random-id")])
            .test_ok("new ID");
        assert_eq!(discovered.memo_ids(), vec![id("a"), id("new-random-id")]);
        assert!(discovered.unbound().is_empty());
        let error = scanned
            .assign_discovered(operation("reuse"), &doc, vec![id("a")])
            .test_err("existing ID cannot be allocated");
        assert_eq!(error.code(), "memo_id_reused");
    }

    #[test]
    fn duplicate_identical_blocks_external_edit_produces_ambiguity_conflicts() {
        let (_doc0, map0) = initialize(
            "- 09:00:00\nsame\n- 09:00:00\nsame\n- 10:00:00\nunique\n",
            &["dup-a", "dup-b", "unique-c"],
        );

        // Scan 1: edit one of the duplicate blocks externally.
        let doc1 = document("- 09:00:00\nsame\n- 09:00:00\nsame modified\n- 10:00:00\nunique\n");
        let scan1 = map0
            .reconcile_external(operation("scan-1"), &doc1)
            .test_ok("scan 1");
        assert_eq!(scan1.memo_ids(), vec![id("unique-c")]);
        assert_eq!(scan1.conflicts().len(), 2);
        assert_eq!(scan1.unbound().len(), 2);
        for conflict in scan1.conflicts() {
            assert!(
                conflict.previous().memo_id() == &id("dup-a")
                    || conflict.previous().memo_id() == &id("dup-b")
            );
            assert_eq!(conflict.candidates().len(), 1);
            let first_candidate = conflict.candidates().first().test_ok("candidate");
            assert_eq!(first_candidate.block_index(), 0);
            assert_eq!(conflict.observed_fingerprint(), doc1.source().fingerprint());
        }
    }

    #[test]
    fn conflict_evidence_persists_when_blocks_shift_or_disappear() {
        let (doc0, map0) = initialize(
            "- 09:00:00\nsame\n- 09:00:00\nsame\n- 10:00:00\nunique\n",
            &["dup-a", "dup-b", "unique-c"],
        );
        let doc1 = document("- 09:00:00\nsame\n- 09:00:00\nsame modified\n- 10:00:00\nunique\n");
        let scan1 = map0
            .reconcile_external(operation("scan-1"), &doc1)
            .test_ok("scan 1");

        // Scan 2: edit comments/header outside; duplicate blocks shifted.
        let doc2 = document(
            "<!-- shifted -->\n- 09:00:00\nsame\n- 09:00:00\nsame modified again\n- 10:00:00\nunique\n",
        );
        let scan2 = scan1
            .reconcile_external(operation("scan-2"), &doc2)
            .test_ok("scan 2");
        assert_eq!(scan2.memo_ids(), vec![id("unique-c")]);
        assert_eq!(scan2.conflicts().len(), 2);
        assert_eq!(scan2.unbound().len(), 2);
        for conflict in scan2.conflicts() {
            assert_eq!(conflict.candidates().len(), 1);
            let first_candidate = conflict.candidates().first().test_ok("candidate");
            assert_eq!(first_candidate.block_index(), 0);
            assert_eq!(conflict.observed_fingerprint(), doc2.source().fingerprint());
            assert_eq!(
                conflict.previous().locator().fingerprint(),
                doc0.source().fingerprint()
            );
        }

        // Scan 3: both duplicate blocks edited, leaving no candidates matching "same".
        let doc3 = document(
            "<!-- shifted -->\n- 09:00:00\nchanged 1\n- 09:00:00\nchanged 2\n- 10:00:00\nunique\n",
        );
        let scan3 = scan2
            .reconcile_external(operation("scan-3"), &doc3)
            .test_ok("scan 3");
        assert_eq!(scan3.memo_ids(), vec![id("unique-c")]);
        assert_eq!(scan3.conflicts().len(), 2);
        for conflict in scan3.conflicts() {
            assert!(conflict.candidates().is_empty());
            assert_eq!(conflict.observed_fingerprint(), doc3.source().fingerprint());
        }
    }

    #[test]
    fn conflict_evidence_recovers_candidates_when_content_restored_and_roundtrips() {
        let (_doc0, map0) = initialize(
            "- 09:00:00\nsame\n- 09:00:00\nsame\n- 10:00:00\nunique\n",
            &["dup-a", "dup-b", "unique-c"],
        );
        // All duplicate blocks edited -> zero candidates.
        let doc_none = document(
            "<!-- shifted -->\n- 09:00:00\nchanged 1\n- 09:00:00\nchanged 2\n- 10:00:00\nunique\n",
        );
        let scan_none = map0
            .reconcile_external(operation("scan-none"), &doc_none)
            .test_ok("scan none");
        assert_eq!(scan_none.conflicts().len(), 2);
        for conflict in scan_none.conflicts() {
            assert!(conflict.candidates().is_empty());
        }

        // Restore one copy of "same". Candidate is restored and retained in conflicts without guessing.
        let doc_restored = document(
            "<!-- shifted -->\n- 09:00:00\nsame\n- 09:00:00\nchanged 2\n- 10:00:00\nunique\n",
        );
        let scan_restored = scan_none
            .reconcile_external(operation("scan-restored"), &doc_restored)
            .test_ok("scan restored");
        assert_eq!(scan_restored.memo_ids(), vec![id("unique-c")]);
        assert_eq!(scan_restored.conflicts().len(), 2);
        for conflict in scan_restored.conflicts() {
            assert_eq!(conflict.candidates().len(), 1);
            let first_candidate = conflict.candidates().first().test_ok("candidate");
            assert_eq!(first_candidate.block_index(), 0);
            assert_eq!(
                conflict.observed_fingerprint(),
                doc_restored.source().fingerprint()
            );
        }

        let encoded = scan_restored.encode().test_ok("encode scan restored");
        let restored = MemoIdentityMap::decode(&encoded).test_ok("decode scan restored");
        assert_eq!(restored, scan_restored);
    }

    #[test]
    fn external_reconciliation_replay_is_idempotent_and_rejects_mismatched_operations() {
        let (_doc0, map) = initialize("- 09:00:00\nfirst\n", &["a"]);
        let doc1 = document("- 09:00:00\nfirst\n- 10:00:00\nsecond\n");
        let reconciled = map
            .reconcile_external(operation("scan-1"), &doc1)
            .test_ok("reconcile");
        let replayed = reconciled
            .reconcile_external(operation("scan-1"), &doc1)
            .test_ok("idempotent replay of external scan");
        assert_eq!(replayed, reconciled);

        // Cannot reuse the initialize operation ID on an unchanged document.
        let mismatch = reconciled
            .reconcile_external(operation("import"), &doc1)
            .test_err("reused operation ID must be rejected");
        assert_eq!(mismatch.code(), "identity_operation_mismatch");

        // No-op scan with a fresh operation ID succeeds without mutating the map.
        let noop = reconciled
            .reconcile_external(operation("scan-fresh"), &doc1)
            .test_ok("noop scan");
        assert_eq!(noop, reconciled);
    }

    #[test]
    fn decode_rejects_unknown_manifest_version_and_non_manifest_records() {
        let (_, map) = initialize("- 09:00:00\nbody\n", &["a"]);
        let body_json = serde_json::to_string(&map).test_ok("map json");

        let unknown_version_record = encode_record(&LomoPayload {
            kind: LomoRecordKind::Manifest,
            record_id: "memo-identity-map-v2".to_owned(),
            body_json: body_json.clone(),
        })
        .test_ok("encode unknown version");
        let err_version = MemoIdentityMap::decode(&unknown_version_record)
            .test_err("reject unknown manifest version");
        assert_eq!(err_version.code(), "unsupported_identity_schema");

        let wrong_kind_record = encode_record(&LomoPayload {
            kind: LomoRecordKind::Trash,
            record_id: "memo-identity-map-v1".to_owned(),
            body_json,
        })
        .test_ok("encode wrong kind");
        let err_kind =
            MemoIdentityMap::decode(&wrong_kind_record).test_err("reject non-manifest record kind");
        assert_eq!(err_kind.code(), "invalid_identity_record");

        let rec_path =
            memo_identity_record_path(WorkspaceRootId::Notes, &path()).test_ok("record path");
        assert!(rec_path.as_str().starts_with(".lomo/identity/v1/"));
        assert!(
            std::path::Path::new(rec_path.as_str())
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("rec"))
        );
    }
}
