// adversarial-audit: the manifest verified_entries hint trusts only
// path+size+mtime+64-hex shape; a same-path byte swap that preserves size and
// mtime keeps the stale content identity instead of forcing a rehash
//!
//! Hypothesis under audit:
//! - `ffi_query_media_manifest` reuses a host-held digest whenever
//!   `hint.size == size && hint.modified_ms == modified_ms && modified_ms != 0
//!   && hint.digest.len() == 64`. Byte content is never consulted on the reuse
//!   path, so replacing the file with different bytes of equal length and then
//!   restoring the original mtime (cp -p semantics, exFAT 2s granularity, or an
//!   explicit utimens call) resurrects the old content identity. Downstream the
//!   `#lomo-cid=` fragment stays unchanged, so Coil/dimension/thumbnail caches
//!   keep serving the previous image — violating "同一路径换内容后必须显示新内容"
//!   whenever the stat pair collides or is preserved.
//! - Control case: the same byte swap with a *fresh* mtime must rehash and
//!   produce a new digest, proving the guard is stat-only, not content-aware.
//!
//! If `preserved_stat_swap_keeps_stale_identity` fails, the invariant breach is
//! real: the digest no longer tracks bytes when the weak hint matches.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::ResultTestExt;
    use std::{
        fs,
        path::Path,
        time::{Duration, UNIX_EPOCH},
    };

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_media::write_bytes_for_tests;
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, MediaCommittedEntryDto, PlatformActionBatch,
        PlatformBatchHost, PlatformBatchResult, WorkspaceDescriptor,
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

    fn fixture() -> (
        tempfile::TempDir,
        tempfile::TempDir,
        LomoEngine,
        std::path::PathBuf,
    ) {
        let ws_dir = tempdir().expect("ws");
        let control_dir = tempdir().expect("control");
        let ws = ws_dir.path().to_path_buf();
        let control = control_dir.path().to_path_buf();
        let exchange = control.join("exchange");
        fs::create_dir_all(&exchange).expect("exchange");
        fs::create_dir_all(ws.join("media")).expect("media");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.to_string_lossy().into_owned(),
            exchange_root: exchange.to_string_lossy().into_owned(),
            bootstrap_deadline_millis: 30_000,
            workspace: Some(WorkspaceDescriptor::Direct {
                root_path: ws.to_string_lossy().into_owned(),
                capability_token: "notes-root".to_owned(),
            }),
        })
        .test_ok("open engine");
        let executor = FsPlatformActionExecutor::new(&exchange).test_ok("posix executor");
        let capability = CapabilityToken::parse("notes-root").test_ok("capability");
        executor.bind_root(capability, &ws).test_ok("bind root");
        engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                ws.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
        (ws_dir, control_dir, engine, ws)
    }

    fn manifest_entry(
        engine: &LomoEngine,
        ws: &Path,
        name: &str,
        hints: Vec<MediaCommittedEntryDto>,
    ) -> MediaCommittedEntryDto {
        engine
            .query_media_manifest(ws.to_string_lossy().into_owned(), hints)
            .test_ok("manifest")
            .entries
            .into_iter()
            .find(|entry| entry.absolute_path.ends_with(name))
            .expect("committed media entry")
    }

    /// Same byte length as `PNG_1X1`, different content.
    fn mutated_png() -> Vec<u8> {
        let mut bytes = PNG_1X1.to_vec();
        let last = bytes.len() - 5;
        *bytes.get_mut(last).expect("png tail") ^= 0xFF;
        bytes
    }

    #[test]
    fn preserved_stat_swap_keeps_stale_identity() {
        let (_ws_dir, _control_dir, engine, ws) = fixture();
        let image = ws.join("media").join("stable.png");
        write_bytes_for_tests(&image, PNG_1X1).expect("png");

        let original = manifest_entry(&engine, &ws, "stable.png", Vec::new());
        assert!(original.size > 0 && original.modified_ms > 0);

        // Swap bytes, then restore the witnessed stat pair: same size by
        // construction, same mtime via explicit set_modified — the shape a
        // timestamp-preserving copy or a coarse-granularity filesystem produces.
        write_bytes_for_tests(&image, &mutated_png()).expect("swap bytes");
        let witnessed_mtime = UNIX_EPOCH + Duration::from_millis(original.modified_ms);
        fs::File::options()
            .write(true)
            .open(&image)
            .expect("open swapped")
            .set_modified(witnessed_mtime)
            .expect("restore mtime");

        let refreshed = manifest_entry(
            &engine,
            &ws,
            "stable.png",
            vec![MediaCommittedEntryDto {
                digest: original.digest.clone(),
                absolute_path: original.absolute_path.clone(),
                size: original.size,
                modified_ms: original.modified_ms,
            }],
        );

        // Invariant: identical path + different bytes must mint a new content
        // identity. If the weak hint wins, the stale digest is served back.
        assert_ne!(
            refreshed.digest, original.digest,
            "stale digest reused after a stat-preserving byte swap: \
             the content identity no longer tracks the bytes"
        );
    }

    #[test]
    fn fresh_mtime_swap_forces_rehash() {
        let (_ws_dir, _control_dir, engine, ws) = fixture();
        let image = ws.join("media").join("fresh.png");
        write_bytes_for_tests(&image, PNG_1X1).expect("png");

        let original = manifest_entry(&engine, &ws, "fresh.png", Vec::new());
        write_bytes_for_tests(&image, &mutated_png()).expect("swap bytes");
        // Push mtime forward beyond the witnessed value so the hint misses.
        let later = UNIX_EPOCH + Duration::from_millis(original.modified_ms + 5_000);
        fs::File::options()
            .write(true)
            .open(&image)
            .expect("open swapped")
            .set_modified(later)
            .expect("bump mtime");

        let refreshed = manifest_entry(
            &engine,
            &ws,
            "fresh.png",
            vec![MediaCommittedEntryDto {
                digest: original.digest.clone(),
                absolute_path: original.absolute_path.clone(),
                size: original.size,
                modified_ms: original.modified_ms,
            }],
        );
        assert_ne!(
            refreshed.digest, original.digest,
            "mtime drift must invalidate the hint and rehash the new bytes"
        );
    }
}
