//! Behavior Contract
//!
//! Capability: Store Direct must not allocate or write received memos as `memos/<id>.md`.
//! Owning layer: `lomo-store`; priority P0.
//!
//! Scenarios:
//!
//! - Given a LAN-shaped received create, when `Store::create_received_memo` runs, then it fails
//!   closed with `session_owns_document_writes` and writes no sidecar Markdown.
//!
//! Observable outcomes: error code, absence of `memos/` files.
//! TDD proof: Direct received create previously allocated `${timestamp}_epochms_${ordinal}` files.
//! Excludes: `WorkspaceSession` chronology create (covered by `history_trash_media_contract`).

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed when observable store facts are missing"
)]
mod tests {
    use lomo_core::OperationId;
    use lomo_store::Store;
    use lomo_workspace::load_or_mint_workspace_generation;
    use tempfile::tempdir;

    #[test]
    fn received_create_fails_closed_so_session_owns_the_document_write() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open store");
        let generation =
            load_or_mint_workspace_generation(dir.path()).expect("workspace generation");
        let error = store
            .create_received_memo(
                OperationId::parse("lan-item-a").expect("operation id"),
                generation.as_str(),
                1_700_000_000_123,
                "same body".to_owned(),
                Vec::new(),
            )
            .expect_err("direct received create is refused");
        assert_eq!(error.code(), "session_owns_document_writes");
        let memos = dir.path().join("memos");
        assert!(
            !memos.exists()
                || std::fs::read_dir(&memos)
                    .expect("list memos")
                    .next()
                    .is_none(),
            "refused received create must not write memos/<id>.md"
        );
    }
}
