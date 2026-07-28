//! Behavior Contract (P5-01 identity)
//!
//! Capability: real random durable `WorkspaceGenerationId` under `.lomo/local/v1/generation.rec`;
//! remote dataset id + config digest validation; corrupt/missing generation is not clean-slated.
//!
//! Scenarios:
//! - Given a fresh workspace, when generation is load-or-minted, then a 64-hex id is durable and
//!   reloads identically without reminting.
//! - Given archive activation mint, when `mint_new` runs, then the durable id changes.
//! - Given missing generation.rec, when load (not mint) runs, then validation fails.
//! - Given corrupt generation bytes, when load runs, then corruption (not empty default).
//! - Given invalid `RemoteDatasetId` / `RemoteIdentityDigest`, when parse runs, then validation fails.
//! - Given canonical config bytes, when digest is computed, then SHA-256 hex is stable.
//!
//! Observable outcomes: durable path, hex ids, structured error category/code.
//! Excludes: sync session, archive export inclusion, production dual-stack wiring.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        LomoPaths, RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId, decode_record,
        load_or_mint_workspace_generation, load_workspace_generation,
        mint_new_workspace_generation, persist_workspace_generation,
    };
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn mint_and_reload_generation_is_durable_and_stable() {
        let dir = tempdir().test_ok("tempdir");
        let first = load_or_mint_workspace_generation(dir.path()).test_ok("mint");
        assert_eq!(first.as_str().len(), 64);
        assert!(first.as_str().bytes().all(|b| b.is_ascii_hexdigit()));

        let path = LomoPaths::generation_record_path(dir.path());
        assert!(path.is_file(), "generation.rec must exist under local/v1");
        assert!(
            path.to_string_lossy()
                .contains(".lomo/local/v1/generation.rec"),
            "durable path must be .lomo/local/v1/generation.rec"
        );

        let reloaded = load_workspace_generation(dir.path()).test_ok("reload");
        assert_eq!(reloaded.as_str(), first.as_str());

        let second_mint_path = load_or_mint_workspace_generation(dir.path()).test_ok("no remint");
        assert_eq!(
            second_mint_path.as_str(),
            first.as_str(),
            "load_or_mint must not remint when generation.rec exists"
        );

        let framed = fs::read(&path).test_ok("read framed");
        let record = decode_record(&framed).test_ok("decode generation");
        assert_eq!(record.payload.record_id, "generation");
    }

    #[test]
    fn archive_activation_mints_new_generation() {
        let dir = tempdir().test_ok("tempdir");
        let original = load_or_mint_workspace_generation(dir.path()).test_ok("original");
        let next = mint_new_workspace_generation(dir.path()).test_ok("mint new");
        assert_ne!(original.as_str(), next.as_str());
        let reloaded = load_workspace_generation(dir.path()).test_ok("reload after mint");
        assert_eq!(reloaded.as_str(), next.as_str());
    }

    #[test]
    fn missing_generation_load_fails_validation_not_clean_slate() {
        let dir = tempdir().test_ok("tempdir");
        let err = load_workspace_generation(dir.path()).test_err("missing");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "workspace_generation_missing");
    }

    #[test]
    fn corrupt_generation_is_corruption_not_clean_slate() {
        let dir = tempdir().test_ok("tempdir");
        let id = WorkspaceGenerationId::mint().test_ok("mint");
        persist_workspace_generation(dir.path(), &id).test_ok("persist");
        let path = LomoPaths::generation_record_path(dir.path());
        fs::write(&path, b"not-a-lomo-record").test_ok("corrupt write");
        let err = load_workspace_generation(dir.path()).test_err("corrupt load");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        // File must still exist (no auto-delete / clean-slate).
        assert!(path.is_file());
    }

    #[test]
    fn workspace_generation_id_parse_rejects_invalid() {
        let err = WorkspaceGenerationId::parse("abc").test_err("short");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "invalid_workspace_generation_id");

        let err = WorkspaceGenerationId::parse(&"g".repeat(64)).test_err("non-hex");
        assert_eq!(err.code(), "invalid_workspace_generation_id");
    }

    #[test]
    fn remote_dataset_id_and_identity_digest_validate() {
        let ok = RemoteDatasetId::parse("provider-remote-1").test_ok("dataset");
        assert_eq!(ok.as_str(), "provider-remote-1");

        let empty = RemoteDatasetId::parse("").test_err("empty dataset");
        assert_eq!(empty.code(), "invalid_remote_dataset_id");
        let control = RemoteDatasetId::parse("bad\nid").test_err("control");
        assert_eq!(control.code(), "invalid_remote_dataset_id");

        let digest = RemoteIdentityDigest::from_canonical_config_bytes(b"endpoint=https://x");
        assert_eq!(digest.as_str().len(), 64);
        let same = RemoteIdentityDigest::from_canonical_config_bytes(b"endpoint=https://x");
        assert_eq!(digest.as_str(), same.as_str());

        let bad = RemoteIdentityDigest::parse("zz").test_err("short digest");
        assert_eq!(bad.code(), "invalid_remote_identity_digest");
    }
}
