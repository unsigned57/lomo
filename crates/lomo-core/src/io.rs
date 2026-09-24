//! Bounded byte reads (audit I5): callers prove a byte budget before bytes materialize.
//! Oversized input is rejected before it is fully read into memory.

use std::fmt;
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

/// Failure of a bounded read: transport error, or the file exceeded the caller's budget.
#[derive(Debug)]
pub enum BoundedReadError {
    /// The file could not be opened or read mid-stream.
    Io(io::Error),
    /// The file is larger than the caller's declared budget.
    ExceedsLimit { limit: u64 },
}

impl BoundedReadError {
    /// Whether the failure is the byte budget, not I/O.
    #[must_use]
    pub const fn is_limit(&self) -> bool {
        matches!(self, Self::ExceedsLimit { .. })
    }
}

impl fmt::Display for BoundedReadError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "{error}"),
            Self::ExceedsLimit { limit } => write!(f, "exceeds the {limit}-byte bound"),
        }
    }
}

impl std::error::Error for BoundedReadError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::ExceedsLimit { .. } => None,
        }
    }
}

/// Reads at most `limit` bytes from `path`.
///
/// The budget is enforced before the file is fully materialized: the read stops at
/// `limit + 1` bytes and reports [`BoundedReadError::ExceedsLimit`].
///
/// # Errors
///
/// [`BoundedReadError::Io`] when the file cannot be opened or read;
/// [`BoundedReadError::ExceedsLimit`] when the file is larger than `limit`.
pub fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>, BoundedReadError> {
    let file = File::open(path).map_err(BoundedReadError::Io)?;
    read_bounded_reader(file, limit)
}

/// Reads at most `limit` bytes from an already-open `reader` (socket, pipe, file).
///
/// # Errors
///
/// Same contract as [`read_bounded`].
pub fn read_bounded_reader<R: Read>(reader: R, limit: u64) -> Result<Vec<u8>, BoundedReadError> {
    let mut bytes = Vec::new();
    reader
        .take(limit.saturating_add(1))
        .read_to_end(&mut bytes)
        .map_err(BoundedReadError::Io)?;
    if u64::try_from(bytes.len()).unwrap_or(u64::MAX) > limit {
        return Err(BoundedReadError::ExceedsLimit { limit });
    }
    Ok(bytes)
}
