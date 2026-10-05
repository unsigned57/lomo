// adversarial-audit: staged-byte integrity must survive crash-truncated dedup paths and
// must never be deletable while a durable lease still claims them.
//!
//! Hypothesis under test:
//! - `stage_media` dedups on `staging_path.exists()` without re-hashing the existing file, so a
//!   crash-torn staged file at the digest-derived name is adopted as if it were verified bytes;
//!   the second stage returns a `MediaStaged` whose declared digest/size no longer match disk.
//! - `discard_staged` deletes staged bytes without consulting the durable ledger, so a leased
//!   artifact can lose its bytes while `StageLedger` still reports an active lease.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_media::{
        ArtifactId, ContentDigest, MediaSource, StageLease, StageLedger, StageOwnerKind,
        discard_staged, stage_directory_of, stage_media, write_bytes_for_tests,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    fn stage(root: &std::path::Path, bytes: &[u8], name: &str) -> lomo_media::MediaStaged {
        let incoming = root.join("incoming");
        fs::create_dir_all(&incoming).expect("create incoming temp root");
        let source = incoming.join(format!("{name}.png"));
        write_bytes_for_tests(&source, bytes).expect("write source");
        stage_media(root, MediaSource::StagedTemp { path: source }, name).expect("stage")
    }

    /// A crash that interrupts the stage copy leaves a torn file at the digest-derived name.
    /// The next stage of the same content must repair it instead of adopting it.
    #[test]
    fn torn_staged_file_is_not_adopted_by_digest_dedup() {
        let root = tempdir().expect("temp");
        let first = stage(root.path(), PNG_1X1, "IMG_0001");
        // Simulate crash-torn bytes: truncate the digest-named staged file in place.
        let truncated_len = first.staging_path.metadata().expect("meta").len() / 2;
        let file = fs::OpenOptions::new()
            .write(true)
            .open(&first.staging_path)
            .expect("open staged");
        file.set_len(truncated_len).expect("truncate");
        drop(file);

        let second = stage(root.path(), PNG_1X1, "IMG_0001");
        assert_eq!(
            second.digest, first.digest,
            "same content re-derives digest"
        );

        // The staged bytes must match the declared facts; a dedup hit must re-verify.
        let (on_disk_digest, on_disk_size) =
            ContentDigest::stream_from_path(&second.staging_path).expect("rehash staged");
        assert_eq!(
            on_disk_digest.as_str(),
            second.digest.as_str(),
            "dedup must not adopt torn bytes under the content-derived name"
        );
        assert_eq!(on_disk_size, second.size);
    }

    /// The durable ledger is the only byte-deletion authority: a leased artifact's bytes must
    /// not be removable through the raw discard path.
    #[test]
    fn leased_staged_bytes_survive_raw_discard() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0002");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        let lease = StageLease::new(
            ArtifactId::of_digest(&staged.digest),
            StageOwnerKind::Draft,
            "draft-1",
        )
        .expect("lease");
        ledger.record(None, &staged, lease).expect("record");

        discard_staged(&staged).expect("raw discard");

        assert!(
            staged.staging_path.is_file(),
            "leased staged bytes must survive a raw discard that bypasses the ledger"
        );
    }
}
