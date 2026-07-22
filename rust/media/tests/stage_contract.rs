//! Behavior Contract
//!
//! Capability: stage DirectPath/StagedTemp with bounded streaming digest; discard unpromoted stage.
//!
//! Scenarios:
//! - Given a PNG file, when `stage_media(DirectPath)` runs, then `MediaStaged` carries digest/size/mime
//!   and a file under `.lomo-media-stage`.
//! - Given `StagedTemp`, when staging completes, then the temp source is consumed.
//! - Given `discard_staged`, when called, then the staged file is removed.
//! - Given stream buffer capacity, when inspected, then it equals `DIGEST_STREAM_CHUNK_BYTES` (bounded).
//!
//! Observable outcomes: staged paths, digests, cleanup.
//! Excludes: memo promote, FFI, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_media::{
        ContentDigest, DIGEST_STREAM_CHUNK_BYTES, MediaSource, STAGE_DIR_NAME, discard_staged,
        stage_media, stream_buffer_capacity, write_bytes_for_tests,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn stream_buffer_is_bounded_chunk() {
        assert_eq!(stream_buffer_capacity(), DIGEST_STREAM_CHUNK_BYTES);
        assert_eq!(DIGEST_STREAM_CHUNK_BYTES, 16 * 1024);
        assert!(stream_buffer_capacity() <= 256 * 1024);
    }

    #[test]
    fn large_stream_uses_fixed_chunk_not_whole_file_vec() {
        // Property: staging a multi-chunk payload still reports digest of full bytes and keeps
        // the public buffer capacity fixed at 16 KiB (no whole-file allocation contract).
        let root = tempdir().expect("temp");
        let mut bytes = PNG_1X1.to_vec();
        // Pad past several 16 KiB chunks while keeping PNG magic prefix for mime.
        bytes.resize(DIGEST_STREAM_CHUNK_BYTES * 3 + 100, 0x5a);
        let src = root.path().join("big.png");
        write_bytes_for_tests(&src, &bytes).expect("write");
        // Magic still PNG so mime ok; digest is of full padded stream.
        let staged = stage_media(
            root.path(),
            MediaSource::DirectPath { path: src },
            "big.png",
        )
        .expect("stage large");
        assert_eq!(staged.size, bytes.len() as u64);
        assert_eq!(staged.digest, ContentDigest::of_slice(&bytes));
        assert_eq!(stream_buffer_capacity(), 16 * 1024);
    }

    #[test]
    fn stage_direct_path_png() {
        let root = tempdir().expect("temp");
        let src = root.path().join("in.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("write");
        let staged = stage_media(
            root.path(),
            MediaSource::DirectPath { path: src.clone() },
            "shot.png",
        )
        .expect("stage");
        assert_eq!(staged.size, PNG_1X1.len() as u64);
        assert_eq!(staged.digest, ContentDigest::of_slice(PNG_1X1));
        assert!(
            staged
                .staging_path
                .starts_with(root.path().join(STAGE_DIR_NAME))
        );
        assert!(staged.staging_path.is_file());
        assert!(src.is_file(), "DirectPath must not consume the source file");
    }

    #[test]
    fn stage_temp_consumes_source() {
        let root = tempdir().expect("temp");
        let src = root.path().join("tmp-upload.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("write");
        let staged = stage_media(
            root.path(),
            MediaSource::StagedTemp { path: src.clone() },
            "upload.png",
        )
        .expect("stage");
        assert!(staged.staging_path.is_file());
        assert!(!src.exists(), "StagedTemp source must be consumed");
        discard_staged(&staged).expect("discard");
        assert!(!staged.staging_path.exists());
    }

    #[test]
    fn stage_rejects_unknown_bytes() {
        let root = tempdir().expect("temp");
        let src = root.path().join("x.bin");
        write_bytes_for_tests(&src, b"hello").expect("write");
        let error = stage_media(root.path(), MediaSource::DirectPath { path: src }, "x.bin")
            .expect_err("unknown");
        assert_eq!(error.code(), "unsupported_media_magic");
        let stage_empty = !root.path().join(STAGE_DIR_NAME).exists()
            || fs::read_dir(root.path().join(STAGE_DIR_NAME)).map_or(true, |d| d.count() == 0);
        assert!(stage_empty);
    }

    #[test]
    fn stage_missing_source_fails_closed() {
        let root = tempdir().expect("temp");
        let missing = root.path().join("gone.png");
        let err = stage_media(
            root.path(),
            MediaSource::DirectPath { path: missing },
            "gone.png",
        )
        .expect_err("missing source");
        assert_eq!(err.code(), "media_source_not_file");
    }

    #[test]
    fn stage_same_digest_twice_reuses_pending_stage() {
        let root = tempdir().expect("temp");
        let src_a = root.path().join("a.png");
        let src_b = root.path().join("b.png");
        write_bytes_for_tests(&src_a, PNG_1X1).expect("a");
        write_bytes_for_tests(&src_b, PNG_1X1).expect("b");
        let first = stage_media(
            root.path(),
            MediaSource::DirectPath { path: src_a },
            "a.png",
        )
        .expect("first");
        let second = stage_media(
            root.path(),
            MediaSource::DirectPath { path: src_b },
            "b.png",
        )
        .expect("second");
        assert_eq!(first.digest, second.digest);
        assert_eq!(first.staging_path, second.staging_path);
        assert!(first.staging_path.is_file());
    }

    #[test]
    fn stage_temp_dedup_consumes_second_temp() {
        let root = tempdir().expect("temp");
        let first_src = root.path().join("t1.png");
        write_bytes_for_tests(&first_src, PNG_1X1).expect("t1");
        let first = stage_media(
            root.path(),
            MediaSource::StagedTemp { path: first_src },
            "t1.png",
        )
        .expect("first");
        let second_src = root.path().join("t2.png");
        write_bytes_for_tests(&second_src, PNG_1X1).expect("t2");
        let second = stage_media(
            root.path(),
            MediaSource::StagedTemp {
                path: second_src.clone(),
            },
            "t2.png",
        )
        .expect("second dedup");
        assert_eq!(first.staging_path, second.staging_path);
        assert!(
            !second_src.exists(),
            "StagedTemp must be consumed even on digest-dedup stage"
        );
    }

    #[test]
    fn stage_temp_cross_device_falls_back_to_copy_consume() {
        let root = tempdir().expect("temp");
        let shm =
            std::path::Path::new("/dev/shm").join(format!("lomo-stage-{}", std::process::id()));
        fs::create_dir_all(&shm).expect("shm");
        let src = shm.join("upload.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("write");
        let staged = stage_media(
            root.path(),
            MediaSource::StagedTemp { path: src.clone() },
            "upload.png",
        )
        .expect("stage cross device");
        assert!(staged.staging_path.is_file());
        assert!(
            !src.exists(),
            "StagedTemp must be consumed after cross-device copy fallback"
        );
        // Best-effort cleanup of the shm staging scratch dir.
        drop(fs::remove_dir_all(&shm));
    }

    #[test]
    fn discard_staged_is_idempotent_when_path_already_gone() {
        let root = tempdir().expect("temp");
        let src = root.path().join("in.png");
        write_bytes_for_tests(&src, PNG_1X1).expect("write");
        let staged = stage_media(
            root.path(),
            MediaSource::DirectPath { path: src },
            "shot.png",
        )
        .expect("stage");
        discard_staged(&staged).expect("discard once");
        assert!(!staged.staging_path.exists());
        discard_staged(&staged).expect("idempotent");
    }

    #[test]
    fn invalid_recording_extension_fails_closed() {
        use lomo_media::allocate_recording_target;
        let root = tempdir().expect("temp");
        let err = allocate_recording_target(root.path(), "../x").expect_err("bad ext");
        assert_eq!(err.code(), "invalid_recording_extension");
        let err2 = allocate_recording_target(root.path(), "").expect_err("empty");
        assert_eq!(err2.code(), "invalid_recording_extension");
    }
}
