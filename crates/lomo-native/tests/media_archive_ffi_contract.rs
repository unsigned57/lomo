//! Behavior Contract — P4-09 media/archive `BoltFFI` dark-build surface
//!
//! - Unit under test: `LomoEngine::{stage_media,finalize_recording,
//!   query_media_manifest,media_orphan_sweep,archive_export,archive_inspect,
//!   archive_import,archive_activate,session_import_archive}` +
//!   `StoreMemoCommand.pending_promotes` wire through `session_create_memo`
//! - Owning layer: `lomo-native` (conversion only); rules in `lomo-media` / `lomo-store`
//! - Priority tier: P0
//! - Capability: path-only media/archive commands through the unique `BoltFFI` facade
//!   without production Kotlin DI dual-stack and without full media-byte FFI.
//!
//! Scenarios:
//! - Given a PNG path, when `stage_media(DirectPath)` runs, then staged digest/mime/path are set.
//! - Given staged media, when `session_create_memo` carries it via `pending_promotes`, then the
//!   final path exists and the stage lease is consumed under the same operation-id.
//! - Given promote plan on create via `pending_promotes`, when `session_create_memo` runs, then
//!   body attachment path is present after promote under the same operation-id.
//! - Given workspace with media, when `query_media_manifest` runs, then digests list without bytes.
//! - Given export→inspect→activate, when activate completes, then live holds staging contents.
//! - Given export of store-seeded memo, when `session_import_archive` runs on the bound
//!   workspace, then rebuild projects the memo id and the previous generation is kept.
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
    use std::{fs, path::Path};

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_media::write_bytes_for_tests;
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, MediaPromotePlanDto, MediaSourceKind,
        PlatformActionBatch, PlatformBatchHost, PlatformBatchResult, SessionCreateMemoRequest,
        WorkspaceDescriptor,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
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
                capability_token: "notes-root".to_owned(),
            }),
        })
        .test_ok("open engine")
    }

    fn attach_posix_session(engine: &LomoEngine, workspace: &Path, exchange: &Path) {
        let executor = FsPlatformActionExecutor::new(exchange).test_ok("posix executor");
        let capability = CapabilityToken::parse("notes-root").test_ok("direct capability");
        executor
            .bind_root(capability, workspace)
            .test_ok("bind workspace");
        engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
    }

    #[test]
    fn stage_session_promote_and_manifest_path_only() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        attach_posix_session(&engine, &ws, &control.join("exchange"));
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
        engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-promote-ffi".to_owned(),
                relative_path: None,
                time_token: Some("10:15:00".to_owned()),
                content: format!("see ![]({final_rel})"),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: vec![MediaPromotePlanDto {
                    operation_id: "op-promote-ffi".to_owned(),
                    staged: staged.clone(),
                    final_relative_path: final_rel.clone(),
                }],
                chronology_epoch_ms: None,
            })
            .test_ok("session promote");
        assert!(ws.join(&final_rel).is_file());
        assert!(
            !Path::new(&staged.staging_path).exists(),
            "stage consumed after promote"
        );

        let manifest = engine
            .query_media_manifest(ws.to_string_lossy().into_owned(), Vec::new())
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
    fn manifest_never_trusts_host_supplied_digest_hints() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        attach_posix_session(&engine, &ws, &control.join("exchange"));

        let media_dir = ws.join("media");
        fs::create_dir_all(&media_dir).expect("media dir");
        let image = media_dir.join("stable.png");
        write_bytes_for_tests(&image, PNG_1X1).expect("png");

        let first = engine
            .query_media_manifest(ws.to_string_lossy().into_owned(), Vec::new())
            .test_ok("manifest");
        let entry = first
            .entries
            .iter()
            .find(|e| e.absolute_path == image.to_string_lossy())
            .expect("committed entry")
            .clone();
        let true_digest = entry.digest.clone();
        assert!(entry.size > 0 && entry.modified_ms > 0);

        // `verified_entries` is retained on the wire for compatibility but is never
        // authoritative: identity is always re-derived from current bytes, so a sentinel
        // digest can never be echoed back even when size+mtime still match.
        let sentinel = "f".repeat(64);
        let hint = lomo_native::MediaCommittedEntryDto {
            digest: sentinel.clone(),
            absolute_path: entry.absolute_path.clone(),
            size: entry.size,
            modified_ms: entry.modified_ms,
        };
        let rehashed = engine
            .query_media_manifest(ws.to_string_lossy().into_owned(), vec![hint])
            .test_ok("manifest");
        assert_eq!(
            rehashed
                .entries
                .iter()
                .find(|e| e.absolute_path == entry.absolute_path)
                .map(|e| e.digest.as_str()),
            Some(true_digest.as_str()),
            "host hint is not authoritative: the digest always comes from current bytes",
        );

        // A same-size byte swap that preserves the original mtime (cp -p / exFAT host
        // shapes) must still produce a new digest — stat facts can never rescue a stale
        // identity.
        let swapped: Vec<u8> = PNG_1X1.iter().map(|b| b ^ 0xFF).collect();
        write_bytes_for_tests(&image, &swapped).expect("swapped png");
        fs::File::options()
            .write(true)
            .open(&image)
            .expect("reopen image")
            .set_modified(
                std::time::UNIX_EPOCH + std::time::Duration::from_millis(entry.modified_ms),
            )
            .expect("restore mtime");
        let preserved_stat_hint = lomo_native::MediaCommittedEntryDto {
            digest: true_digest.clone(),
            absolute_path: entry.absolute_path.clone(),
            size: entry.size,
            modified_ms: entry.modified_ms,
        };
        let after_swap = engine
            .query_media_manifest(ws.to_string_lossy().into_owned(), vec![preserved_stat_hint])
            .test_ok("manifest");
        let updated = after_swap
            .entries
            .iter()
            .find(|e| e.absolute_path == entry.absolute_path)
            .expect("updated entry");
        assert_ne!(updated.digest, sentinel);
        assert_ne!(updated.digest, true_digest);
    }

    #[test]
    fn pending_promotes_wire_through_session_create_memo() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        attach_posix_session(&engine, &ws, &control.join("exchange"));
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
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-ffi-promote-memo".to_owned(),
                relative_path: None,
                time_token: Some("10:15:00".to_owned()),
                content: body,
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: vec![MediaPromotePlanDto {
                    operation_id: "op-ffi-promote-memo".to_owned(),
                    staged,
                    final_relative_path: final_rel.clone(),
                }],
                chronology_epoch_ms: None,
            })
            .test_ok("session create with pending_promotes");
        assert!(
            commit.memo_id.starts_with("m_") && commit.memo_id.len() == 34,
            "session must mint CSPRNG memo ids, got {}",
            commit.memo_id
        );
        assert!(
            ws.join(&final_rel).is_file(),
            "pending promote must land final attachment under same operation-id"
        );
        let snap = engine
            .get_memo(commit.memo_id)
            .test_ok("get")
            .expect("memo present");
        assert!(
            snap.body.contains(&final_rel),
            "body must reference promoted path"
        );
    }

    #[test]
    fn archive_export_and_session_import_rebuild() {
        let tmp = tempdir().expect("tmp");
        let source = tmp.path().join("source");
        let control = tmp.path().join("control");
        let engine = open_engine(&source, &control);
        fs::create_dir_all(source.join("memos")).expect("memos");
        fs::write(source.join("memos/2026_07_21.md"), "# archive seed #t").expect("seed markdown");
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

        // Second path: session-owned import→activate→rebuild generation switch over the bound
        // workspace; the previous generation is kept at "<staging>.previous".
        attach_posix_session(&engine, &source, &control.join("exchange"));
        let staging2 = tmp.path().join("staging2");
        fs::write(source.join("memos/1999_01_01.md"), b"old generation").expect("old");
        let rebuild = engine
            .session_import_archive(
                source.to_string_lossy().into_owned(),
                archive.to_string_lossy().into_owned(),
                staging2.to_string_lossy().into_owned(),
            )
            .test_ok("session import archive");
        assert!(
            rebuild.memos_indexed >= 1,
            "rebuild must project imported memos"
        );
        assert!(!rebuild.store_digest.is_empty());
        let previous = tmp.path().join("staging2.previous");
        assert!(
            previous.join("memos/1999_01_01.md").is_file(),
            "previous generation remains under <staging>.previous after activate"
        );
        assert!(
            !source.join("memos/1999_01_01.md").is_file(),
            "previous live generation must be swapped out"
        );
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
    fn session_media_orphan_sweep_moves_unreferenced_and_reports() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        attach_posix_session(&engine, &ws, &control.join("exchange"));
        fs::create_dir_all(ws.join("media")).expect("media");
        write_bytes_for_tests(&ws.join("media/orphan.png"), PNG_1X1).expect("png");

        let sweep = engine
            .session_media_orphan_sweep_guarding(Some(20_000), 1_000, Vec::new())
            .test_ok("sweep");
        assert_eq!(sweep.candidates, 1);
        assert_eq!(sweep.moved_to_trash.len(), 1);
        assert!(sweep.protections.is_empty());
        assert!(sweep.failures.is_empty());
        assert!(!ws.join("media/orphan.png").exists());
        assert!(
            ws.join(".lomo-media-trash")
                .read_dir()
                .expect("trash dir")
                .count()
                == 1,
            "orphan must land in durable media-trash"
        );

        // A second run keeps nothing new: the trash entry still sits inside its window.
        let again = engine
            .session_media_orphan_sweep_guarding(Some(20_500), 1_000, Vec::new())
            .test_ok("second sweep");
        assert!(again.moved_to_trash.is_empty());
        assert!(again.permanently_deleted_digests.is_empty());
    }

    #[test]
    fn session_media_orphan_sweep_protects_referenced_media() {
        let tmp = tempdir().expect("tmp");
        let ws = tmp.path().join("ws");
        let control = tmp.path().join("control");
        let engine = open_engine(&ws, &control);
        attach_posix_session(&engine, &ws, &control.join("exchange"));
        fs::create_dir_all(ws.join("media")).expect("media");
        write_bytes_for_tests(&ws.join("media/keep.png"), PNG_1X1).expect("png");
        engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "sweep-protect".to_owned(),
                relative_path: None,
                time_token: None,
                content: "see ![[media/keep.png]]".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("create memo");

        let sweep = engine
            .session_media_orphan_sweep_guarding(Some(20_000), 1_000, Vec::new())
            .test_ok("sweep");
        assert!(sweep.moved_to_trash.is_empty());
        assert_eq!(sweep.protections.len(), 1);
        let protection = sweep.protections.first().expect("protection");
        assert_eq!(protection.relative_path, "media/keep.png");
        assert_eq!(protection.source, "current");
        assert!(ws.join("media/keep.png").exists());
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
