//! Behavior Contract — D10 clean-slate re-scan media manifest compare (host golden)
//!
//! Capability: before/after Wave A cutover, hosts compare a Kotlin-era basenames listing against
//! the Rust media owner manifest (path + digest). Compare is count + ordered digest set equality.
//!
//! Scenarios:
//! - Given matching digests (any basename), when compare runs, then Ok.
//! - Given count mismatch or missing digest, when compare runs, then fail closed.
//!
//! Observable outcomes: `MediaManifestCompare` result codes.
//! Excludes: production DI cutover orchestration, device re-scan job.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::collections::BTreeSet;
    use std::fs;
    use std::path::{Path, PathBuf};

    use lomo_media::{
        ContentDigest, MediaSource, stage_media, suggest_human_relative_path, write_bytes_for_tests,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    /// Host-side pure compare used at Wave A freeze/re-scan (D10).
    fn compare_digest_sets(
        kotlin_era_digests: &BTreeSet<String>,
        rust_manifest_digests: &BTreeSet<String>,
    ) -> Result<(), &'static str> {
        if kotlin_era_digests.len() != rust_manifest_digests.len() {
            return Err("media_manifest_count_mismatch");
        }
        if kotlin_era_digests != rust_manifest_digests {
            return Err("media_manifest_digest_mismatch");
        }
        Ok(())
    }

    fn digest_set_for_files(paths: &[PathBuf]) -> BTreeSet<String> {
        paths
            .iter()
            .map(|path| {
                ContentDigest::stream_from_path(path)
                    .expect("digest")
                    .0
                    .as_str()
                    .to_owned()
            })
            .collect()
    }

    fn stage_and_promote_digest(root: &Path, name: &str) -> String {
        let src = root.join(name);
        write_bytes_for_tests(&src, PNG_1X1).expect("write");
        let staged = stage_media(root, MediaSource::DirectPath { path: src }, name).expect("stage");
        let final_rel = suggest_human_relative_path(
            Path::new(name)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or(name),
            staged.mime,
        )
        .expect("suggest");
        assert_eq!(
            staged.suggested_final_relative_path,
            final_rel.as_str(),
            "stage must own suggested final relative path"
        );
        let dest = root.join(final_rel.as_str());
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).expect("media dir");
        }
        fs::rename(&staged.staging_path, &dest).expect("promote rename");
        staged.digest.as_str().to_owned()
    }

    #[test]
    fn matching_digests_pass_even_when_basenames_differ() {
        let root = tempdir().expect("tmp");
        let digest = stage_and_promote_digest(root.path(), "shot.png");
        // Kotlin-era list may have used a different basename; compare is digest-only.
        let kotlin = BTreeSet::from([digest.clone()]);
        let rust = BTreeSet::from([digest]);
        compare_digest_sets(&kotlin, &rust).expect("match");
    }

    #[test]
    fn count_mismatch_fails_closed() {
        let err = compare_digest_sets(
            &BTreeSet::from(["a".to_owned()]),
            &BTreeSet::from(["a".to_owned(), "b".to_owned()]),
        )
        .expect_err("count");
        assert_eq!(err, "media_manifest_count_mismatch");
    }

    #[test]
    fn digest_mismatch_fails_closed() {
        let err = compare_digest_sets(
            &BTreeSet::from(["aaa".to_owned()]),
            &BTreeSet::from(["bbb".to_owned()]),
        )
        .expect_err("digest");
        assert_eq!(err, "media_manifest_digest_mismatch");
    }

    #[test]
    fn workspace_media_dir_digest_set_matches_staged_owner() {
        let root = tempdir().expect("tmp");
        let d1 = stage_and_promote_digest(root.path(), "a.png");
        // Second file: same PNG bytes → same digest (dedup compare still count-unique digests).
        let d2 = stage_and_promote_digest(root.path(), "b.png");
        assert_eq!(d1, d2, "identical bytes share digest");
        let media_dir = root.path().join("media");
        let mut files = Vec::new();
        for entry in fs::read_dir(&media_dir).expect("media") {
            let entry = entry.expect("media entry");
            let path = entry.path();
            if path.is_file() {
                files.push(path);
            }
        }
        let on_disk = digest_set_for_files(&files);
        let owner = BTreeSet::from([d1]);
        // On-disk may have two files with same digest; compare uses unique digests.
        compare_digest_sets(&owner, &on_disk).expect("unique digests match");
    }
}
