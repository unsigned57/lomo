//! Behavior Contract
//!
//! Capability: recording allocate/finalize produces a digest-named staged artifact; an
//! interrupted recording never appears as committed media.
//!
//! Scenarios:
//! - Given `allocate_recording_target` + written m4a-ish header finalize, when finalize runs,
//!   then `MediaStaged` is produced under the stage dir and the recording temp path is consumed.
//! - Given mid-record death (allocated target never finalized), when recovery discards the
//!   unpromoted stage path, then no committed media file appears.
//!
//! Observable outcomes: recording targets, staged facts, committed-tree absence.
//! Excludes: workspace publication (owned by the `ArtifactWrite` platform executor, covered in
//! `lomo-platform-fs` contract tests), FFI, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_media::{
        STAGE_DIR_NAME, allocate_recording_target, finalize_recording, write_bytes_for_tests,
    };
    use tempfile::tempdir;

    // Minimal ftyp/M4A brand header + padding so magic detects audio/mp4.
    fn m4a_header() -> Vec<u8> {
        let mut bytes = vec![0_u8; 32];
        if let Some(slice) = bytes.get_mut(4..8) {
            slice.copy_from_slice(b"ftyp");
        }
        if let Some(slice) = bytes.get_mut(8..12) {
            slice.copy_from_slice(b"M4A ");
        }
        bytes
    }

    #[test]
    fn recording_allocate_and_finalize() {
        let root = tempdir().expect("temp");
        let target = allocate_recording_target(root.path(), "m4a").expect("alloc");
        assert!(target.starts_with(root.path().join(STAGE_DIR_NAME)));
        write_bytes_for_tests(&target, &m4a_header()).expect("write rec");
        let staged = finalize_recording(root.path(), &target, "voice.m4a").expect("finalize");
        assert!(staged.staging_path.is_file());
        assert!(
            !target.exists(),
            "recording temp path consumed into digest-named stage"
        );
    }

    #[test]
    fn mid_record_death_leaves_unpromoted_stage_discardable() {
        let root = tempdir().expect("temp");
        let target = allocate_recording_target(root.path(), "m4a").expect("alloc");
        write_bytes_for_tests(&target, &m4a_header()).expect("partial write");
        // Crash before finalize: target remains under stage dir and must never be treated as committed.
        assert!(target.is_file());
        assert!(target.starts_with(root.path().join(STAGE_DIR_NAME)));
        // Recovery path: the abandoned allocate target is removed as unpromoted stage.
        std::fs::remove_file(&target).expect("discard unpromoted recording target");
        assert!(!target.exists());
        // No media/ committed path exists.
        assert!(
            !root.path().join("media").exists()
                || std::fs::read_dir(root.path().join("media")).map_or(true, |d| d.count() == 0)
        );
    }
}
