//! Behavior Contract
//!
//! Capability: establish `lomo-store` as the stage-3 local-data owner identity without opening
//! `SQLite` (open/schema is P3-01).
//!
//! Scenarios:
//! - Given the shipped owner constants, when `StoreOwnerIdentity::current` is read, then crate name
//!   is `lomo-store` and schema version is the scaffold `STORE_SCHEMA_VERSION`.
//! - Given a forged identity with the wrong crate name or schema version, when `validate` runs,
//!   then validation fails closed with a structured store-owner error code.
//!
//! Observable outcomes: identity constants and structured validation errors.
//! TDD proof: architecture checks fail when the owner crate or identity is absent.
//! Excludes: `SQLite` open, FTS, query, transactions, rebuild, reminder product logic, FFI, Room
//! cutover, and production dual-stack wiring.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_store::{CRATE_NAME, STORE_SCHEMA_VERSION, StoreOwnerIdentity};

    #[test]
    fn current_owner_identity_matches_shipped_scaffold() {
        let identity = StoreOwnerIdentity::current();
        assert_eq!(identity.crate_name, "lomo-store");
        assert_eq!(identity.crate_name, CRATE_NAME);
        assert_eq!(identity.schema_version, STORE_SCHEMA_VERSION);
        identity
            .validate()
            .expect("shipped owner identity must validate");
    }

    #[test]
    fn forged_owner_identity_fails_closed() {
        let wrong_name = StoreOwnerIdentity {
            crate_name: "not-lomo-store",
            schema_version: STORE_SCHEMA_VERSION,
        };
        let error = wrong_name
            .validate()
            .expect_err("forged crate name must fail");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "invalid_store_owner");

        let wrong_schema = StoreOwnerIdentity {
            crate_name: CRATE_NAME,
            schema_version: STORE_SCHEMA_VERSION.wrapping_add(1),
        };
        let error = wrong_schema
            .validate()
            .expect_err("forged schema version must fail");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "invalid_store_schema_version");
    }
}
