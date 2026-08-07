//! `PageCursor` encoding: query fingerprint + sort key + high-water revision + tokenizer version.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::validation;
use crate::schema::TOKENIZER_VERSION;
use crate::tokenizer::QueryPlan;

/// Opaque page cursor for bounded, stable pagination.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageCursor {
    pub query_fingerprint: String,
    pub sort_rank_bits: Option<u64>,
    pub sort_updated_at_ms: i64,
    pub sort_memo_id: String,
    pub high_water_revision: u64,
    pub tokenizer_version: u32,
}

impl PageCursor {
    /// Builds a cursor for the last row of a page under the given query fingerprint.
    #[must_use]
    pub fn new(
        query_fingerprint: String,
        sort_rank: Option<f64>,
        sort_updated_at_ms: i64,
        sort_memo_id: String,
        high_water_revision: u64,
    ) -> Self {
        Self {
            query_fingerprint,
            sort_rank_bits: sort_rank.map(f64::to_bits),
            sort_updated_at_ms,
            sort_memo_id,
            high_water_revision,
            tokenizer_version: TOKENIZER_VERSION,
        }
    }

    /// Resolves and validates the leading FTS sort key for this query shape.
    ///
    /// # Errors
    ///
    /// Rejects missing FTS rank, rank on a non-FTS cursor, and non-finite rank encodings.
    pub fn validated_sort_rank(
        &self,
        requires_rank: bool,
    ) -> Result<Option<f64>, lomo_core::LomoError> {
        let rank = self.sort_rank_bits.map(f64::from_bits);
        if rank.is_some_and(|value| !value.is_finite()) {
            return Err(validation(
                "invalid_page_cursor",
                "page cursor rank must be finite",
            ));
        }
        match (requires_rank, rank) {
            (true, None) => Err(validation(
                "invalid_page_cursor",
                "FTS page cursor must include rank",
            )),
            (false, Some(_)) => Err(validation(
                "invalid_page_cursor",
                "non-FTS page cursor must not include rank",
            )),
            (_, value) => Ok(value),
        }
    }

    /// Encodes the cursor as a compact JSON string (opaque to Kotlin consumers).
    ///
    /// # Errors
    ///
    /// Returns a validation error when serialization fails (should not happen for valid cursors).
    pub fn encode(&self) -> Result<String, lomo_core::LomoError> {
        serde_json::to_string(self).map_err(|err| {
            validation(
                "cursor_encode_failed",
                &format!("cannot encode page cursor: {err}"),
            )
        })
    }

    /// Decodes an opaque cursor.
    ///
    /// # Errors
    ///
    /// Fail closed on malformed input (no silent defaults).
    pub fn decode(raw: &str) -> Result<Self, lomo_core::LomoError> {
        serde_json::from_str(raw).map_err(|err| {
            validation(
                "invalid_page_cursor",
                &format!("cannot decode page cursor: {err}"),
            )
        })
    }

    /// Validates this cursor against the current query/revision/tokenizer.
    ///
    /// # Errors
    ///
    /// Returns `stale_cursor` on fingerprint, high-water revision, or tokenizer mismatch.
    /// Never falls back to offset full-table scan.
    pub fn validate_against(
        &self,
        query_fingerprint: &str,
        high_water_revision: u64,
    ) -> Result<(), lomo_core::LomoError> {
        if self.tokenizer_version != TOKENIZER_VERSION {
            return Err(validation(
                "stale_cursor",
                "page cursor tokenizer_version does not match current tokenizer",
            ));
        }
        if self.query_fingerprint != query_fingerprint {
            return Err(validation(
                "stale_cursor",
                "page cursor query fingerprint does not match current query",
            ));
        }
        if self.high_water_revision != high_water_revision {
            return Err(validation(
                "stale_cursor",
                "page cursor high_water_revision does not match store revision",
            ));
        }
        Ok(())
    }
}

/// Stable fingerprint of a query plan + filter set for cursor coupling.
#[must_use]
pub fn fingerprint_query(
    match_expr: Option<&str>,
    filter_fingerprint: &str,
    tokenizer_version: u32,
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(tokenizer_version.to_le_bytes());
    hasher.update(b"|");
    hasher.update(match_expr.unwrap_or("").as_bytes());
    hasher.update(b"|");
    hasher.update(filter_fingerprint.as_bytes());
    let digest = hasher.finalize();
    hex_encode(&digest)
}

/// Fingerprint helper when a full plan is available.
#[must_use]
pub fn fingerprint_plan(plan: &QueryPlan, filter_fingerprint: &str) -> String {
    fingerprint_query(
        plan.match_expr.as_deref(),
        filter_fingerprint,
        TOKENIZER_VERSION,
    )
}

fn hex_encode(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        match write!(out, "{byte:02x}") {
            Ok(()) | Err(_) => {}
        }
    }
    out
}
