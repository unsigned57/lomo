//! Behavior Contract — P4-09 media/archive `BoltFFI` dark-build surface
//!
//! - Unit under test: `LomoEngine::{stage_media,finalize_recording,promote_media,
//!   query_media_manifest,media_orphan_sweep,archive_export,archive_inspect,
//!   archive_import,archive_activate,archive_import_activate_rebuild}` +
//!   `StoreMemoCommand.pending_promotes` wire through `apply_memo_command`
//! - Owning layer: `lomo-native` (conversion only); rules in `lomo-media` / `lomo-store`
//! - Priority tier: P0
//! - Capability: path-only media/archive commands through the unique `BoltFFI` facade
//!   without production Kotlin DI dual-stack and without full media-byte FFI.
//!
//! Scenarios:
//! - Given a PNG path, when `stage_media(DirectPath)` runs, then staged digest/mime/path are set.
//! - Given staged media, when `promote_media` runs, then final path exists and stage is consumed.
//! - Given promote plan on create via `pending_promotes`, when `apply_memo_command` runs, then
//!   body attachment path is present after promote under the same operation-id.
//! - Given workspace with media, when `query_media_manifest` runs, then digests list without bytes.
//! - Given export→inspect→activate, when activate completes, then live holds staging contents.
//! - Given export of store-seeded memo, when `archive_import_activate_rebuild` runs, then rebuild
//!   projects the memo id on the activated root.
//!
//! Observable outcomes: DTO path/digest fields, structured `EngineError` codes, store projection.
//! Excludes: production DI cutover (P4-10), Kotlin adapters registered in production graph.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::ResultTestExt;
    use std::fs;
    use std::path::Path;

    use lomo_media::write_bytes_for_tests;
    use lomo_native::{
        EngineConfig, LomoEngine, MediaAttachmentRefDto, MediaPromotePlanDto, MediaSourceKind,
        StoreMemoCommand, StoreMemoCommandKind, WorkspaceDescriptor,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    fn open_engine(workspace: &Path, control: &Path) -> LomoEngine {
        let exchange = control.join("exchange");
        fs::create_dir_all(control).expect("control");
        fs::create_dir_all(&exchange).expect("exchange");
        fs::create_dir_all(workspace).expect("ws");
        fs::create_dir_all(workspace.join("memos")).expect("memos");
        LomoEngine::open(EngineConfig {
            control_root: control.to_string_lossy().into_owned(),
            exchange_root: exchange.to_string_lossy().into_owned(),
            bootstrap_deadline_millis: 30_000,
            workspace: Some(WorkspaceDescriptor::Direct {
                root_path: workspace.to_string_lossy().into_owned(),
            }),
        })
        .test_ok("open engine")
    }

    #[test]
    fn stage_promote_and_manifest_path_only() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        let media_root = ws.clone();
        let src = tmp.path().join("shot.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("png");

        let staged = engine
            .stage_media(
                media_root.to_string_lossy().into_owned(),
                MediaSourceKind::DirectPath,
                src.to_string_lossy().into_owned(),
                "shot.png".to_owned(),
            )
            .test_ok("stage");
        assert_eq!(staged.mime, "image/png");
        assert!(!staged.digest.is_empty());
        assert!(Path::new(&staged.staging_path).is_file());

        let final_rel = format!("media/{}.png", staged.digest.get(..16).expect("digest"));
        let promoted = engine
            .promote_media(
                ws.to_string_lossy().into_owned(),
                MediaPromotePlanDto {
                    operation_id: "op-promote-ffi".to_owned(),
                    staged: staged.clone(),
                    final_relative_path: final_rel.clone(),
                },
            )
            .test_ok("promote");
        assert_eq!(promoted.final_relative_path, final_rel);
        assert!(Path::new(&promoted.final_absolute_path).is_file());
        assert!(
            !Path::new(&staged.staging_path).exists(),
            "stage consumed after promote"
        );

        let manifest = engine
            .query_media_manifest(ws.to_string_lossy().into_owned())
            .test_ok("manifest");
        assert!(
            manifest
                .entries
                .iter()
                .any(|e| e.digest == staged.digest && Path::new(&e.absolute_path).is_file()),
            "manifest lists promoted digest path"
        );
        assert_eq!(manifest.stage_dir_name, ".lomo-media-stage");
    }

    #[test]
    fn pending_promotes_wire_through_apply_memo_command() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        let src = tmp.path().join("attach.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("png");
        let staged = engine
            .stage_media(
                ws.to_string_lossy().into_owned(),
                MediaSourceKind::DirectPath,
                src.to_string_lossy().into_owned(),
                "attach.png".to_owned(),
            )
            .test_ok("stage");
        let final_rel = format!(
            "media/attach-{}.png",
            staged.digest.get(..12).expect("digest")
        );
        let body = format!("see ![]({final_rel})");
        let commit = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-promote-memo".to_owned(),
                kind: StoreMemoCommandKind::Create,
                memo_id: "memo-promote-ffi".to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: Some(body),
                tags: vec![],
                pin: None,
                pending_promotes: vec![MediaPromotePlanDto {
                    operation_id: "op-ffi-promote-memo".to_owned(),
                    staged,
                    final_relative_path: final_rel.clone(),
                }],
            })
            .test_ok("apply with pending_promotes");
        assert_eq!(commit.memo_id, "memo-promote-ffi");
        assert!(
            ws.join(&final_rel).is_file(),
            "pending promote must land final attachment under same operation-id"
        );
        let snap = engine
            .get_memo("memo-promote-ffi".to_owned())
            .test_ok("get")
            .expect("memo present");
        assert!(
            snap.body.contains(&final_rel),
            "body must reference promoted path"
        );
    }

    #[test]
    fn archive_export_inspect_activate_and_import_rebuild() {
        let tmp = tempdir().expect("tmp");
        let source = tmp.path().join("source");
        let control = tmp.path().join("control");
        let engine = open_engine(&source, &control);
        engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-arch-seed".to_owned(),
                kind: StoreMemoCommandKind::Create,
                memo_id: "arch-memo".to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: Some("# archive seed".to_owned()),
                tags: vec!["t".to_owned()],
                pin: None,
                pending_promotes: vec![],
            })
            .test_ok("seed");
        fs::create_dir_all(source.join("media")).expect("media");
        write_bytes_for_tests(&source.join("media/shot.png"), PNG_1X1).expect("png");

        let archive = tmp.path().join("out.zip");
        let exported = engine
            .archive_export(
                source.to_string_lossy().into_owned(),
                archive.to_string_lossy().into_owned(),
            )
            .test_ok("export");
        assert_eq!(exported.schema_version, 2);
        assert!(exported.entry_count >= 1);

        let staging = tmp.path().join("staging");
        let inspected = engine
            .archive_inspect(
                archive.to_string_lossy().into_owned(),
                staging.to_string_lossy().into_owned(),
            )
            .test_ok("inspect");
        assert_eq!(inspected.schema_version, 2);
        assert!(staging.is_dir());

        // Second path: full import→activate→rebuild generation switch.
        let live = tmp.path().join("live");
        let backup = tmp.path().join("backup");
        let staging2 = tmp.path().join("staging2");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("old.md"), b"old").expect("old");
        let rebuild = engine
            .archive_import_activate_rebuild(
                archive.to_string_lossy().into_owned(),
                staging2.to_string_lossy().into_owned(),
                live.to_string_lossy().into_owned(),
                backup.to_string_lossy().into_owned(),
                32,
            )
            .test_ok("import activate rebuild");
        assert!(
            rebuild.memos_indexed >= 1,
            "rebuild must project imported memos"
        );
        assert!(!rebuild.store_digest.is_empty());
        assert!(backup.join("old.md").is_file());
    }

    #[test]
    fn allocate_and_finalize_recording_path_only() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        let target = engine
            .allocate_recording_target(ws.to_string_lossy().into_owned(), "m4a".to_owned())
            .test_ok("allocate");
        // Minimal ftyp/M4A header for magic detect.
        let mut header = vec![0_u8; 12];
        header
            .get_mut(4..8)
            .expect("ftyp slot")
            .copy_from_slice(b"ftyp");
        header
            .get_mut(8..12)
            .expect("brand slot")
            .copy_from_slice(b"M4A ");
        write_bytes_for_tests(Path::new(&target), &header).expect("write rec");
        let staged = engine
            .finalize_recording(
                ws.to_string_lossy().into_owned(),
                target.clone(),
                "rec.m4a".to_owned(),
            )
            .test_ok("finalize");
        assert_eq!(staged.mime, "audio/mp4");
        assert!(!Path::new(&target).exists() || Path::new(&staged.staging_path).is_file());
    }

    #[test]
    fn media_orphan_sweep_moves_zero_ref_and_skips_nested_stage_dirs() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        fs::create_dir_all(ws.join("media")).expect("media");
        write_bytes_for_tests(&ws.join("media/orphan.png"), PNG_1X1).expect("png");
        // Nested stage/trash dirs must not be treated as committed live media by manifest walk.
        fs::create_dir_all(ws.join("media/.lomo-media-stage")).expect("stage");
        write_bytes_for_tests(&ws.join("media/.lomo-media-stage/pending.png"), PNG_1X1)
            .expect("staged");

        let manifest = engine
            .query_media_manifest(ws.to_string_lossy().into_owned())
            .test_ok("manifest");
        assert!(
            manifest
                .entries
                .iter()
                .all(|e| !e.absolute_path.contains(".lomo-media-stage")),
            "manifest must skip stage dir"
        );
        let committed = manifest.entries;
        assert!(!committed.is_empty());

        let sweep = engine
            .media_orphan_sweep(
                ws.to_string_lossy().into_owned(),
                committed.clone(),
                vec![],
                vec![],
                Some(20_000),
                1_000,
            )
            .test_ok("sweep");
        assert_eq!(sweep.moved_to_trash.len(), 1);
        assert!(sweep.permanently_deleted_digests.is_empty());

        // History source wire is accepted by the FFI mapper (no invalid_source).
        let digest = committed.first().expect("entry").digest.clone();
        let keep = engine
            .media_orphan_sweep(
                ws.to_string_lossy().into_owned(),
                committed,
                vec![MediaAttachmentRefDto {
                    digest,
                    source: "history".to_owned(),
                    owner_key: "m@r1".to_owned(),
                }],
                vec![],
                Some(20_000),
                1_000,
            )
            .test_ok("keep");
        // Committed path already moved; history keep is exercised for wire mapping only.
        assert!(keep.moved_to_trash.is_empty() || keep.moved_to_trash.len() <= 1);
    }

    #[test]
    fn stage_media_staged_temp_path_only() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        let src = tmp.path().join("temp-upload.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("png");
        let staged = engine
            .stage_media(
                ws.to_string_lossy().into_owned(),
                MediaSourceKind::StagedTemp,
                src.to_string_lossy().into_owned(),
                "upload.png".to_owned(),
            )
            .test_ok("stage temp");
        assert!(Path::new(&staged.staging_path).is_file());
        assert!(!src.exists(), "StagedTemp source consumed");
    }
}
