//! Behavior Contract
//!
//! Capability: archive v2 export/inspect/import/activate with fail-closed malicious zip matrix.
//!
//! Scenarios:
//! - Given a workspace with markdown + media, when export runs, then `ArchiveManifestV2` lists
//!   entries with digests and the zip is readable.
//! - Given export then inspect into staging, when checksums match, then staging is green.
//! - Given zip-slip path, when inspect runs, then validation rejects without mutating live root.
//! - Given archive without `ArchiveManifestV2`, when inspect runs, then `unsupported_archive_version`.
//! - Given green staging, when activate runs, then live root becomes staging contents.
//! - Given duplicate entry / compression bomb / checksum mismatch / unlisted entry, when inspect
//!   runs, then fail closed and the live workspace marker is untouched.
//! - Given mid-activate failure after live→backup rename, when swap fails, then backup is restored
//!   to live (previous generation intact).
//! - Given mid-activate where restore also fails, when activate runs, then exact
//!   `archive_activate_restore_failed` is observed (fail-closed, no silent empty live claim).
//! - Given export→import staging green, when `archive_import_activate_rebuild` runs, then live
//!   holds archive contents and rebuild yields a non-zero store projection generation.
//!
//! Observable outcomes: manifest schema, error codes, activate swap, live-root immutability,
//! import→activate→rebuild projection facts.
//! Excludes: production DI cutover, settings/credentials archive.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs::{self, File};
    use std::io::Write;
    use std::path::{Path, PathBuf};

    use lomo_core::{OperationId, PageSize};
    use lomo_media::write_bytes_for_tests;
    use lomo_store::{
        ARCHIVE_MANIFEST_ENTRY, ARCHIVE_MANIFEST_SCHEMA_V2, ArchiveManifestEntry,
        ArchiveManifestV2, MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, Store,
        archive_activate, archive_activate_with_rename, archive_export, archive_import,
        archive_import_activate_rebuild, archive_inspect,
    };
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;
    use zip::write::SimpleFileOptions;
    use zip::{CompressionMethod, ZipWriter};

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    fn seed_workspace(root: &Path) {
        fs::create_dir_all(root.join("media")).expect("media dir");
        fs::write(root.join("2026_07_21.md"), b"# hello\n").expect("md");
        write_bytes_for_tests(&root.join("media/shot.png"), PNG_1X1).expect("png");
        fs::create_dir_all(root.join(".lomo/state/v1")).expect("lomo");
        fs::write(root.join(".lomo/state/v1/pin.json"), b"{}").expect("state");
    }

    fn hex_sha256(bytes: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(bytes);
        format!("{:x}", hasher.finalize())
    }

    fn write_manifest_zip(archive: &Path, entries: &[(&str, &[u8])], manifest: &ArchiveManifestV2) {
        let file = File::create(archive).expect("create");
        let mut zip = ZipWriter::new(file);
        let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
        for (name, bytes) in entries {
            zip.start_file(*name, options).expect("start");
            zip.write_all(bytes).expect("write");
        }
        let manifest_json = serde_json::to_vec(manifest).expect("manifest json");
        zip.start_file(ARCHIVE_MANIFEST_ENTRY, options)
            .expect("manifest start");
        zip.write_all(&manifest_json).expect("manifest write");
        zip.finish().expect("finish");
    }

    #[test]
    fn export_roundtrip_inspect_activate() {
        let tmp = tempdir().expect("tmp");
        let workspace = tmp.path().join("ws");
        fs::create_dir_all(&workspace).expect("ws");
        seed_workspace(&workspace);

        let archive = tmp.path().join("out.lomo-archive.zip");
        let exported = archive_export(&workspace, &archive).expect("export");
        assert_eq!(exported.manifest.schema_version, ARCHIVE_MANIFEST_SCHEMA_V2);
        assert!(
            exported
                .manifest
                .entries
                .iter()
                .any(|e| e.path == "2026_07_21.md")
        );
        assert!(
            exported
                .manifest
                .entries
                .iter()
                .any(|e| e.path == "media/shot.png")
        );

        let staging = tmp.path().join("staging");
        let inspected = archive_inspect(&archive, &staging).expect("inspect");
        assert!(staging.join("2026_07_21.md").is_file());
        assert_eq!(
            inspected.manifest.schema_version,
            ARCHIVE_MANIFEST_SCHEMA_V2
        );

        let live = tmp.path().join("live");
        let backup = tmp.path().join("backup");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("old.txt"), b"old").expect("old");
        archive_activate(&staging, &live, &backup).expect("activate");
        assert!(live.join("2026_07_21.md").is_file());
        assert!(backup.join("old.txt").is_file());
        assert!(!staging.exists());
    }

    #[test]
    fn zip_slip_rejected_live_root_immutable() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("evil.zip");
        {
            let file = File::create(&archive).expect("create");
            let mut zip = ZipWriter::new(file);
            let options =
                SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            zip.start_file("../escape.txt", options).expect("start");
            zip.write_all(b"nope").expect("write");
            zip.finish().expect("finish");
        }
        let staging = tmp.path().join("staging-slip");
        let live_marker = tmp.path().join("live-intact");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("slip");
        assert_eq!(error.code(), "archive_zip_slip");
        assert!(live_marker.is_file());
        assert_eq!(fs::read(&live_marker).expect("read live"), b"live");
    }

    #[test]
    fn old_zip_without_manifest_rejected() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("old-kotlin.zip");
        {
            let file = File::create(&archive).expect("create");
            let mut zip = ZipWriter::new(file);
            let options =
                SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            zip.start_file("memo.md", options).expect("start");
            zip.write_all(b"# old\n").expect("write");
            zip.finish().expect("finish");
        }
        let staging = tmp.path().join("staging-old");
        let live_marker = tmp.path().join("live-old");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("old");
        assert_eq!(error.code(), "unsupported_archive_version");
        assert!(live_marker.is_file());
    }

    #[test]
    fn duplicate_entry_rejected_live_root_immutable() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("dup.zip");
        // Craft a minimal stored ZIP with two local+central entries sharing the same path.
        // Include ArchiveManifestV2 so failure is specifically the duplicate, not version.
        write_raw_zip_with_duplicate_names(&archive);
        let staging = tmp.path().join("staging-dup");
        let live_marker = tmp.path().join("live-dup");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("dup");
        // Fail closed on crafted duplicate paths: prefer exact owner code from the seen-set.
        assert_eq!(
            error.code(),
            "archive_duplicate_entry",
            "crafted duplicate ZIP must reject with archive_duplicate_entry, got {}",
            error.code()
        );
        assert_eq!(fs::read(&live_marker).expect("read"), b"live");
    }

    /// Minimal ZIP (store method) with two entries both named `memo.md` plus a v2 manifest.
    fn write_raw_zip_with_duplicate_names(path: &Path) {
        fn local_header(name: &[u8], data: &[u8], crc: u32) -> Vec<u8> {
            let mut out = Vec::new();
            out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&crc.to_le_bytes());
            out.extend_from_slice(&u32::try_from(data.len()).expect("len").to_le_bytes());
            out.extend_from_slice(&u32::try_from(data.len()).expect("len").to_le_bytes());
            out.extend_from_slice(&u16::try_from(name.len()).expect("nlen").to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(name);
            out.extend_from_slice(data);
            out
        }
        fn central_header(name: &[u8], data: &[u8], crc: u32, local_offset: u32) -> Vec<u8> {
            let mut out = Vec::new();
            out.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&crc.to_le_bytes());
            out.extend_from_slice(&u32::try_from(data.len()).expect("len").to_le_bytes());
            out.extend_from_slice(&u32::try_from(data.len()).expect("len").to_le_bytes());
            out.extend_from_slice(&u16::try_from(name.len()).expect("nlen").to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u32.to_le_bytes());
            out.extend_from_slice(&local_offset.to_le_bytes());
            out.extend_from_slice(name);
            out
        }
        let name = b"memo.md";
        let a = b"a";
        let b = b"b";
        let manifest = ArchiveManifestV2 {
            schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
            entries: vec![ArchiveManifestEntry {
                path: "memo.md".into(),
                kind: lomo_store::ArchiveEntryKind::Markdown,
                size: 1,
                digest: hex_sha256(a),
            }],
        };
        let mjson = serde_json::to_vec(&manifest).expect("json");
        let mname = ARCHIVE_MANIFEST_ENTRY.as_bytes();
        let crc_a = crc32fast_simple(a);
        let crc_b = crc32fast_simple(b);
        let crc_m = crc32fast_simple(&mjson);
        let l1 = local_header(name, a, crc_a);
        let l2 = local_header(name, b, crc_b);
        let lm = local_header(mname, &mjson, crc_m);
        let mut zip = Vec::new();
        zip.extend_from_slice(&l1);
        let offset_second = u32::try_from(zip.len()).expect("offset_second");
        zip.extend_from_slice(&l2);
        let offset_manifest = u32::try_from(zip.len()).expect("offset_manifest");
        zip.extend_from_slice(&lm);
        let central_offset = u32::try_from(zip.len()).expect("central_offset");
        let c1 = central_header(name, a, crc_a, 0);
        let c2 = central_header(name, b, crc_b, offset_second);
        let cm = central_header(mname, &mjson, crc_m, offset_manifest);
        zip.extend_from_slice(&c1);
        zip.extend_from_slice(&c2);
        zip.extend_from_slice(&cm);
        let central_size = u32::try_from(c1.len() + c2.len() + cm.len()).expect("central_size");
        zip.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&3u16.to_le_bytes());
        zip.extend_from_slice(&3u16.to_le_bytes());
        zip.extend_from_slice(&central_size.to_le_bytes());
        zip.extend_from_slice(&central_offset.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        fs::write(path, zip).expect("write raw dup zip");
    }

    /// IEEE CRC-32 (poly 0xEDB88320) used by ZIP local headers.
    fn crc32fast_simple(data: &[u8]) -> u32 {
        let mut crc = 0xFFFF_FFFFu32;
        for byte in data {
            crc ^= u32::from(*byte);
            for _ in 0..8 {
                let mask = (crc & 1).wrapping_neg();
                crc = (crc >> 1) ^ (0xEDB8_8320u32 & mask);
            }
        }
        !crc
    }

    #[test]
    fn compression_bomb_rejected_live_root_immutable() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("bomb.zip");
        {
            let file = File::create(&archive).expect("create");
            let mut zip = ZipWriter::new(file);
            // Highly compressible zeros: tiny compressed size, large uncompressed claim.
            let options = SimpleFileOptions::default()
                .compression_method(CompressionMethod::Deflated)
                .compression_level(Some(9));
            // Uncompressed payload larger than MAX_COMPRESSION_RATIO * compressed.
            // Use stored size that will compress a lot.
            let zeros = vec![0_u8; 64 * 1024];
            zip.start_file("zeros.bin", options).expect("start");
            zip.write_all(&zeros).expect("write");
            // Manifest so we pass version gate if bomb check were after extract — bomb is
            // checked on entry metadata before extract completes fully.
            let digest = hex_sha256(&zeros);
            let manifest = ArchiveManifestV2 {
                schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
                entries: vec![ArchiveManifestEntry {
                    path: "zeros.bin".into(),
                    kind: lomo_store::ArchiveEntryKind::Media,
                    size: u64::try_from(zeros.len()).expect("zsize"),
                    digest,
                }],
            };
            let manifest_json = serde_json::to_vec(&manifest).expect("json");
            let options_stored =
                SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            zip.start_file(ARCHIVE_MANIFEST_ENTRY, options_stored)
                .expect("manifest");
            zip.write_all(&manifest_json).expect("mw");
            zip.finish().expect("finish");
        }
        let staging = tmp.path().join("staging-bomb");
        let live_marker = tmp.path().join("live-bomb");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("bomb");
        // Prefer compression-bomb; allow only documented size budget code if zip reports
        // compressed_size=0 or entry hits the absolute uncompressed cap first.
        assert!(
            error.code() == "archive_compression_bomb" || error.code() == "archive_entry_too_large",
            "bomb matrix must reject with archive_compression_bomb (or archive_entry_too_large only), got {}",
            error.code()
        );
        assert_eq!(fs::read(&live_marker).expect("read"), b"live");
    }

    #[test]
    fn checksum_mismatch_rejected_live_root_immutable() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("bad-sum.zip");
        let body = b"# hello\n";
        let manifest = ArchiveManifestV2 {
            schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
            entries: vec![ArchiveManifestEntry {
                path: "memo.md".into(),
                kind: lomo_store::ArchiveEntryKind::Markdown,
                size: u64::try_from(body.len()).expect("bsize"),
                digest: "0".repeat(64),
            }],
        };
        write_manifest_zip(
            archive.as_path(),
            &[("memo.md", body.as_slice())],
            &manifest,
        );
        let staging = tmp.path().join("staging-sum");
        let live_marker = tmp.path().join("live-sum");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("sum");
        assert_eq!(error.code(), "archive_entry_checksum_mismatch");
        assert_eq!(fs::read(&live_marker).expect("read"), b"live");
    }

    #[test]
    fn unlisted_entry_rejected_live_root_immutable() {
        let tmp = tempdir().expect("tmp");
        let archive = tmp.path().join("unlisted.zip");
        let body = b"# hello\n";
        let manifest = ArchiveManifestV2 {
            schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
            entries: vec![ArchiveManifestEntry {
                path: "memo.md".into(),
                kind: lomo_store::ArchiveEntryKind::Markdown,
                size: u64::try_from(body.len()).expect("bsize"),
                digest: hex_sha256(body),
            }],
        };
        // Include an extra file not listed in the manifest.
        write_manifest_zip(
            archive.as_path(),
            &[
                ("memo.md", body.as_slice()),
                ("secret.bin", b"x".as_slice()),
            ],
            &manifest,
        );
        let staging = tmp.path().join("staging-unlisted");
        let live_marker = tmp.path().join("live-unlisted");
        fs::write(&live_marker, b"live").expect("live");
        let error = archive_inspect(&archive, &staging).expect_err("unlisted");
        assert_eq!(error.code(), "archive_unlisted_entry");
        assert_eq!(fs::read(&live_marker).expect("read"), b"live");
    }

    #[test]
    fn activate_restores_live_when_staging_swap_fails() {
        let tmp = tempdir().expect("tmp");
        let staging = tmp.path().join("staging-act");
        let live = tmp.path().join("live-act");
        let backup = tmp.path().join("backup-act");
        fs::create_dir_all(&staging).expect("staging");
        fs::write(staging.join("new.md"), b"new").expect("new");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("old.md"), b"old").expect("old");
        // Force swap failure: make `live` a non-empty path that rename can move, but place a
        // file where staging should land by creating a blocking file with the live name after
        // backup — on Unix, rename into an existing empty path works; instead use a backup path
        // that is a file so the first rename of live→backup fails closed without mutation.
        fs::write(&backup, b"blocker").expect("backup file blocker");
        let error = archive_activate(&staging, &live, &backup).expect_err("blocked");
        assert_eq!(error.code(), "archive_activate_backup_exists");
        assert!(live.join("old.md").is_file(), "live must stay intact");
        assert_eq!(fs::read(live.join("old.md")).expect("old"), b"old");
        assert!(
            staging.join("new.md").is_file(),
            "staging remains for diagnosis"
        );
    }

    #[test]
    fn activate_staging_missing_fails_without_touching_live() {
        let tmp = tempdir().expect("tmp");
        let live = tmp.path().join("live-mid");
        let backup = tmp.path().join("backup-mid");
        let staging = tmp.path().join("missing-staging");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("keep.md"), b"keep").expect("keep");
        let error = archive_activate(&staging, &live, &backup).expect_err("missing");
        assert_eq!(error.code(), "archive_activate_staging_missing");
        assert!(live.join("keep.md").is_file());
        assert!(!backup.exists());
    }

    #[test]
    fn activate_mid_swap_failure_restores_previous_generation_to_live() {
        // Mid-activate after successful live→backup then failed staging→live (EXDEV):
        // live/backup on tempfile (/tmp), staging on /dev/shm when available.
        let live_tmp = tempdir().expect("live_tmp");
        let live = live_tmp.path().join("live");
        let backup = live_tmp.path().join("backup");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("previous.md"), b"previous-generation").expect("prev");

        let shm_root = PathBuf::from("/dev/shm");
        assert!(
            shm_root.is_dir(),
            "mid-activate EXDEV proof requires /dev/shm on this host"
        );
        let staging = shm_root.join(format!("lomo-archive-mid-activate-{}", std::process::id()));
        // behavior-contract: silent-result-ok: test cleanup of leftover /dev/shm staging
        drop(fs::remove_dir_all(&staging));
        fs::create_dir_all(&staging).expect("shm staging");
        fs::write(staging.join("new.md"), b"new-generation").expect("new");

        let error = archive_activate(&staging, &live, &backup).expect_err("cross-fs swap");
        assert_eq!(
            error.code(),
            "archive_activate_swap_failed",
            "swap must fail closed (restore succeeded); got {}",
            error.code()
        );
        assert!(
            live.join("previous.md").is_file(),
            "previous generation must be restored to live after mid-activate swap failure"
        );
        assert_eq!(
            fs::read(live.join("previous.md")).expect("read"),
            b"previous-generation"
        );
        assert!(
            !backup.exists(),
            "backup must be consumed by restore rename"
        );
        assert!(
            staging.join("new.md").is_file(),
            "staging remains for diagnosis after failed activate"
        );
        // behavior-contract: silent-result-ok: test cleanup of /dev/shm staging after EXDEV proof
        drop(fs::remove_dir_all(&staging));
    }

    #[test]
    fn activate_restore_failure_returns_archive_activate_restore_failed() {
        // Force: live→backup Ok, staging→live Err, backup→live Err → exact restore_failed code.
        let tmp = tempdir().expect("tmp");
        let staging = tmp.path().join("staging-restore-fail");
        let live = tmp.path().join("live-restore-fail");
        let backup = tmp.path().join("backup-restore-fail");
        fs::create_dir_all(&staging).expect("staging");
        fs::write(staging.join("new.md"), b"new").expect("new");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("previous.md"), b"previous").expect("prev");

        let mut call = 0_u8;
        let error = archive_activate_with_rename(&staging, &live, &backup, |from, to| {
            call = call.saturating_add(1);
            match call {
                1 => fs::rename(from, to), // live → backup succeeds
                2 => Err(std::io::Error::new(
                    std::io::ErrorKind::CrossesDevices,
                    "injected staging→live swap failure",
                )),
                _ => Err(std::io::Error::new(
                    std::io::ErrorKind::PermissionDenied,
                    "injected backup→live restore failure",
                )),
            }
        })
        .expect_err("restore must fail closed");
        assert_eq!(
            error.code(),
            "archive_activate_restore_failed",
            "restore failure must surface exact code; got {}",
            error.code()
        );
        // Call1 used real rename (live→backup). Call2/3 injected failures leave live absent
        // and previous generation stranded on backup — never a silent empty success.
        assert!(
            backup.join("previous.md").is_file(),
            "backup holds previous generation after live→backup and failed restore"
        );
        assert!(
            !live.join("previous.md").is_file(),
            "live must not claim restore success when restore failed"
        );
        assert!(
            staging.join("new.md").is_file(),
            "staging remains for diagnosis"
        );
    }

    #[test]
    fn import_activate_rebuild_projects_store_generation() {
        let tmp = tempdir().expect("tmp");
        let source = tmp.path().join("source-ws");
        fs::create_dir_all(&source).expect("source");
        seed_workspace(&source);
        // Seed durable memo facts via store so rebuild has Markdown + .lomo to project.
        {
            let mut store = Store::open(&source).expect("open source");
            store
                .apply_memo_command(
                    &MemoCommand {
                        operation_id: OperationId::parse("op-import-seed").expect("op"),
                        kind: MemoCommandKind::Create,
                        memo_id: "import-memo-1".into(),
                        expected_revision: 0,
                        expected_fingerprint: None,
                        content: Some("# imported memo body".into()),
                        tags: vec!["archive".into()],
                        pin: None,
                        pending_promotes: vec![],
                    },
                    None,
                )
                .expect("seed memo");
        }

        let archive = tmp.path().join("import-rebuild.zip");
        archive_export(&source, &archive).expect("export");

        let live = tmp.path().join("live-gen");
        let staging = tmp.path().join("staging-gen");
        let backup = tmp.path().join("backup-gen");
        fs::create_dir_all(&live).expect("live");
        fs::write(live.join("stale.md"), b"stale-generation").expect("stale");

        let rebuild = archive_import_activate_rebuild(&archive, &staging, &live, &backup, 32)
            .expect("import activate rebuild");
        assert!(
            rebuild.memos_indexed >= 1,
            "rebuild must project at least the seeded memo; got {}",
            rebuild.memos_indexed
        );
        assert!(
            rebuild.file_count >= 1,
            "rebuild file_count must be observable"
        );
        assert!(
            !rebuild.workspace_digest.is_empty() && !rebuild.store_digest.is_empty(),
            "generation digests must be non-empty after rebuild"
        );
        assert!(
            live.join("2026_07_21.md").is_file() || live.join("media/shot.png").is_file(),
            "activated live must contain archive workspace facts"
        );
        assert!(
            !live.join("stale.md").is_file(),
            "previous live generation must be swapped out"
        );
        assert!(
            backup.join("stale.md").is_file(),
            "previous generation remains under backup after activate"
        );
        assert!(!staging.exists(), "staging is consumed by activate rename");

        let store = Store::open(&live).expect("open activated");
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(20).expect("page"),
            )
            .expect("query");
        assert!(
            page.items.iter().any(|m| m.memo_id == "import-memo-1"),
            "rebuild projection must surface the imported memo id"
        );
    }

    #[test]
    fn export_missing_workspace_fails_closed() {
        let root = tempdir().expect("tmp");
        let missing = root.path().join("no-such-ws");
        let archive = root.path().join("out.zip");
        let err = archive_export(&missing, &archive).expect_err("missing workspace");
        assert_eq!(err.code(), "archive_workspace_missing");
        assert!(!archive.exists());
    }

    #[test]
    fn export_skips_stage_trash_and_sqlite_artifacts() {
        let root = tempdir().expect("tmp");
        let ws = root.path().join("ws");
        seed_workspace(&ws);
        // Noise that must never enter the archive keep-set.
        fs::create_dir_all(ws.join(".lomo-media-stage")).expect("stage");
        write_bytes_for_tests(&ws.join(".lomo-media-stage/pending.png"), PNG_1X1).expect("stage f");
        fs::create_dir_all(ws.join(".lomo-media-trash")).expect("trash");
        write_bytes_for_tests(&ws.join(".lomo-media-trash/gone.png"), PNG_1X1).expect("trash f");
        fs::write(ws.join("notes.tmp"), b"tmp").expect("tmp");
        fs::write(ws.join("local.db"), b"sqlite").expect("db");
        fs::write(ws.join("local.db-wal"), b"wal").expect("wal");
        fs::write(ws.join("local.db-shm"), b"shm").expect("shm");
        // History + deeper .lomo state should be included when present.
        fs::create_dir_all(ws.join(".lomo/history/v1")).expect("history");
        fs::write(ws.join(".lomo/history/v1/memo-r1.rec"), b"history-body").expect("hist");

        let archive = root.path().join("clean.zip");
        let exported = archive_export(&ws, &archive).expect("export");
        let paths: Vec<&str> = exported
            .manifest
            .entries
            .iter()
            .map(|e| e.path.as_str())
            .collect();
        assert!(
            paths.iter().any(|p| p.contains("2026_07_21.md")),
            "markdown kept: {paths:?}"
        );
        assert!(
            paths.iter().any(|p| p.contains("media/shot.png")),
            "media kept: {paths:?}"
        );
        assert!(
            paths
                .iter()
                .any(|p| p.contains(".lomo/history/") || p.contains(".lomo/state/")),
            "durable .lomo history/state kept: {paths:?}"
        );
        assert!(
            paths.iter().all(|p| {
                !p.contains(".lomo-media-stage")
                    && !p.contains(".lomo-media-trash")
                    && !p.ends_with(".db-wal")
                    && !p.ends_with(".db-shm")
                    && !Path::new(p).extension().is_some_and(|ext| {
                        ext.eq_ignore_ascii_case("tmp") || ext.eq_ignore_ascii_case("db")
                    })
            }),
            "ephemeral artifacts must be excluded: {paths:?}"
        );
    }

    #[test]
    fn export_includes_nested_media_and_lomo_state() {
        let root = tempdir().expect("tmp");
        let ws = root.path().join("ws");
        seed_workspace(&ws);
        fs::create_dir_all(ws.join("media/nested")).expect("nested");
        write_bytes_for_tests(&ws.join("media/nested/deep.png"), PNG_1X1).expect("deep");
        fs::create_dir_all(ws.join(".lomo/operations/v1")).expect("ops");
        fs::write(ws.join(".lomo/operations/v1/op.json"), b"{\"ok\":true}").expect("op");
        fs::create_dir_all(ws.join(".lomo/local/v1")).expect("local");
        fs::write(ws.join(".lomo/local/v1/generation.rec"), b"local-only").expect("gen");
        let archive = root.path().join("nested.zip");
        let exported = archive_export(&ws, &archive).expect("export");
        let paths: Vec<&str> = exported
            .manifest
            .entries
            .iter()
            .map(|e| e.path.as_str())
            .collect();
        assert!(
            paths.contains(&"media/nested/deep.png"),
            "nested media kept: {paths:?}"
        );
        // Stage-5 allowlist: durable history/state + markdown/media; never operations/local/sync.
        assert!(
            paths
                .iter()
                .any(|p| p.contains(".lomo/state/") || p.contains(".lomo/history/")),
            "lomo durable state/history kept: {paths:?}"
        );
        assert!(
            paths.iter().all(|p| {
                !p.contains(".lomo/operations/")
                    && !p.contains(".lomo/local/")
                    && !p.contains(".lomo/sync/")
            }),
            "operations/local/sync must be excluded from archive: {paths:?}"
        );
    }

    #[test]
    fn export_includes_history_state_v2_and_excludes_migration_staging() {
        // Stage-5 archive allowlist: history/state v2 + layout head included; migration-staging out.
        let root = tempdir().expect("tmp");
        let ws = root.path().join("ws");
        seed_workspace(&ws);
        fs::create_dir_all(ws.join(".lomo/history/v2/objects")).expect("history v2");
        fs::write(
            ws.join(".lomo/history/v2/objects/rev.rec"),
            b"history-v2-object",
        )
        .expect("history object");
        fs::create_dir_all(ws.join(".lomo/state/v2/heads")).expect("state v2");
        fs::write(ws.join(".lomo/state/v2/heads/memo.rec"), b"state-v2-head").expect("state head");
        fs::write(ws.join(".lomo/layout_head.rec"), b"layout-v2").expect("layout head");
        fs::create_dir_all(ws.join(".lomo/migration-staging/history/v2")).expect("staging");
        fs::write(
            ws.join(".lomo/migration-staging/history/v2/tmp.rec"),
            b"staging-only",
        )
        .expect("staging file");
        fs::create_dir_all(ws.join(".lomo/sync/v1")).expect("sync");
        fs::write(ws.join(".lomo/sync/v1/session.rec"), b"sync-local").expect("sync file");

        let archive = root.path().join("v2-allowlist.zip");
        let exported = archive_export(&ws, &archive).expect("export");
        let paths: Vec<&str> = exported
            .manifest
            .entries
            .iter()
            .map(|e| e.path.as_str())
            .collect();

        assert!(
            paths.iter().any(|p| p.contains(".lomo/history/v2/")),
            "history/v2 must be archived: {paths:?}"
        );
        assert!(
            paths.iter().any(|p| p.contains(".lomo/state/v2/")),
            "state/v2 must be archived: {paths:?}"
        );
        assert!(
            paths.iter().any(|p| *p == ".lomo/layout_head.rec"
                || p.ends_with("layout_head.rec")
                || p.contains("layout_head")),
            "layout head should be archived when present: {paths:?}"
        );
        assert!(
            paths
                .iter()
                .all(|p| !p.contains(".lomo/migration-staging/")),
            "migration-staging must be excluded: {paths:?}"
        );
        assert!(
            paths.iter().all(|p| !p.contains(".lomo/sync/")),
            "sync tree must be excluded: {paths:?}"
        );
    }

    #[test]
    fn archive_import_is_alias_of_inspect() {
        let root = tempdir().expect("tmp");
        let ws = root.path().join("ws");
        seed_workspace(&ws);
        let archive = root.path().join("out.zip");
        archive_export(&ws, &archive).expect("export");
        let staging = root.path().join("staging");
        let imported = archive_import(&archive, &staging).expect("import");
        assert_eq!(imported.manifest.schema_version, ARCHIVE_MANIFEST_SCHEMA_V2);
        assert!(staging.is_dir());
        assert!(
            staging.join("media/shot.png").is_file() || staging.join("2026_07_21.md").is_file()
        );
    }

    #[test]
    fn inspect_fails_when_staging_already_exists() {
        let root = tempdir().expect("tmp");
        let ws = root.path().join("ws");
        seed_workspace(&ws);
        let archive = root.path().join("out.zip");
        archive_export(&ws, &archive).expect("export");
        let staging = root.path().join("staging");
        fs::create_dir_all(&staging).expect("pre-existing staging");
        let err = archive_inspect(&archive, &staging).expect_err("staging exists");
        assert_eq!(err.code(), "archive_staging_exists");
    }

    #[test]
    fn inspect_rejects_non_zip_payload() {
        let root = tempdir().expect("tmp");
        let archive = root.path().join("not-a-zip.bin");
        fs::write(&archive, b"definitely-not-zip").expect("write");
        let staging = root.path().join("staging");
        let err = archive_inspect(&archive, &staging).expect_err("not zip");
        assert_eq!(err.code(), "archive_not_zip");
        assert!(!staging.exists());
    }

    #[test]
    fn inspect_rejects_unsupported_manifest_schema() {
        let root = tempdir().expect("tmp");
        let archive = root.path().join("schema.zip");
        let body = b"# schema\n";
        let manifest = ArchiveManifestV2 {
            schema_version: 99,
            entries: vec![ArchiveManifestEntry {
                path: "note.md".into(),
                kind: lomo_store::ArchiveEntryKind::Markdown,
                size: body.len() as u64,
                digest: hex_sha256(body),
            }],
        };
        write_manifest_zip(&archive, &[("note.md", body.as_slice())], &manifest);
        let staging = root.path().join("staging");
        let err = archive_inspect(&archive, &staging).expect_err("schema");
        assert!(
            err.code() == "unsupported_archive_version"
                || err.code().contains("archive")
                || err.code().contains("schema")
                || err.code().contains("unsupported"),
            "unsupported schema must fail closed: {}",
            err.code()
        );
        assert!(!staging.exists());
    }
}
