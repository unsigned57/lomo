//! Application file/transaction budgets, enforced on the descriptor actually read.

use crate::error::{resource_limit, storage};
use lomo_core::LomoError;
use std::{fs::File, io::Read};

/// A workspace document or staged attachment may occupy at most 64 MiB in one operation.
pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
/// A frozen operation may retain at most 128 MiB across unique file payloads.
pub const MAX_TRANSACTION_BYTES: u64 = 128 * 1024 * 1024;
/// Bound both transaction metadata and recovery I/O fanout.
pub const MAX_TRANSACTION_FILES: usize = 128;

pub(crate) fn read_bounded(file: File, limit: u64) -> Result<Vec<u8>, LomoError> {
    let metadata = file
        .metadata()
        .map_err(|error| storage("file_metadata_failed", error.to_string()))?;
    if !metadata.is_file() || metadata.len() > limit {
        return Err(resource_limit(
            "file_read_budget_exceeded",
            format!(
                "file length {} exceeds the {limit} byte budget",
                metadata.len()
            ),
        ));
    }
    let capacity = usize::try_from(metadata.len())
        .map_err(|error| resource_limit("file_read_budget_exceeded", error.to_string()))?;
    let mut bytes = Vec::with_capacity(capacity);
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| storage("file_read_failed", error.to_string()))?;
    if u64::try_from(bytes.len())
        .map_err(|error| resource_limit("file_read_budget_exceeded", error.to_string()))?
        > limit
    {
        return Err(resource_limit(
            "file_read_budget_exceeded",
            "file grew past its read budget",
        ));
    }
    Ok(bytes)
}
