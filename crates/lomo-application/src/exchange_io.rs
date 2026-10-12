use std::path::Path;

use lomo_core::{ExchangeArtifact, ExchangeToken, LomoError, Sha256Digest};
use sha2::{Digest, Sha256};

use crate::{
    csprng::generate_hex_token, error::resource_limit, private_io::write_atomic,
    resource::MAX_FILE_BYTES,
};

/// Stages a bounded file into exchange with durable atomic publication.
///
/// # Errors
/// Rejects oversized data and surfaces all write/fsync failures.
pub fn stage_content(exchange_dir: &Path, content: &[u8]) -> Result<ExchangeArtifact, LomoError> {
    let length = u64::try_from(content.len())
        .map_err(|error| resource_limit("exchange_content_overflow", error.to_string()))?;
    if length > MAX_FILE_BYTES {
        return Err(resource_limit(
            "exchange_content_too_large",
            "exchange content exceeds the per-file budget",
        ));
    }
    let token = ExchangeToken::parse(&format!("ex_{}", generate_hex_token(16)?))?;
    write_atomic(&exchange_dir.join(token.as_str()), content)?;
    ExchangeArtifact::new(
        token.as_str(),
        length,
        Sha256Digest::parse(&format!("{:x}", Sha256::digest(content)))?,
    )
}
