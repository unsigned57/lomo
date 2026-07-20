//! Behavior Contract
//!
//! Capability: keep phase-0 corpus manifests versioned, path-safe, and byte-stable.
//!
//! Scenarios:
//! - Given the same logical corpus, when canonicalized twice, then JSON bytes are identical.
//! - Given a path escape or absolute path entry, when validated, then the boundary rejects it.
//!
//! Observable outcomes: digests match across runs; invalid paths produce validation errors.
//! TDD proof: unstable ordering or absolute paths fail the contract before generators land.
//! Excludes: large corpus generation, remote fixture bytes, production storage.

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use lomo_feasibility::{
        CorpusFileEntryV1, CorpusManifestV1, CorpusWorkloadV1, LogicalAttachmentEntryV1,
        ReportValidationError,
    };

    fn sample_manifest() -> CorpusManifestV1 {
        CorpusManifestV1 {
            schema_version: CorpusManifestV1::SCHEMA_VERSION,
            corpus_version: "format-v1".to_owned(),
            seed: 42,
            workload: CorpusWorkloadV1 {
                memo_count: 100,
                remote_change_count: 10,
                attachment_logical_bytes: 1_024,
            },
            files: vec![
                CorpusFileEntryV1 {
                    relative_path: "b/memo.md".to_owned(),
                    sha256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        .to_owned(),
                    byte_length: 2,
                },
                CorpusFileEntryV1 {
                    relative_path: "a/memo.md".to_owned(),
                    sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        .to_owned(),
                    byte_length: 1,
                },
            ],
            logical_attachments: vec![LogicalAttachmentEntryV1 {
                identity: "img-1".to_owned(),
                logical_bytes: 1_024,
                sha256: "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                    .to_owned(),
            }],
        }
    }

    #[test]
    fn same_manifest_is_byte_stable_across_canonicalization() {
        let first = sample_manifest()
            .to_canonical_json()
            .expect("first canonicalization");
        let second = sample_manifest()
            .to_canonical_json()
            .expect("second canonicalization");
        assert_eq!(first, second);
        let digest = sample_manifest().canonical_digest().expect("digest exists");
        assert_eq!(digest.len(), 64);
        assert!(
            std::str::from_utf8(&first)
                .expect("utf8")
                .contains("a/memo.md")
        );
    }

    #[test]
    fn absolute_path_entries_are_rejected() {
        let mut manifest = sample_manifest();
        manifest.files[0].relative_path = "/tmp/escape.md".to_owned();
        assert_eq!(
            manifest.validate(),
            Err(ReportValidationError::InvalidField {
                field: "files.relative_path",
            })
        );
    }

    #[test]
    fn parent_directory_escape_is_rejected() {
        let mut manifest = sample_manifest();
        manifest.files[0].relative_path = "../secret.md".to_owned();
        assert_eq!(
            manifest.validate(),
            Err(ReportValidationError::InvalidField {
                field: "files.relative_path",
            })
        );
    }
}
