use std::fmt::Write as _;

use lomo_core::LomoError;
use lomo_workspace::MemoId;

use crate::error::storage;

/// Fills the buffer with cryptographically secure random bytes from the OS.
///
/// # Errors
/// Returns `Storage` error if the system entropy source fails.
pub fn fill_csprng(buf: &mut [u8]) -> Result<(), LomoError> {
    getrandom::fill(buf).map_err(|err| {
        storage(
            "csprng_read_failed",
            format!("system CSPRNG getrandom failed: {err}"),
        )
    })?;
    Ok(())
}

/// Generates a random lowercase hexadecimal string of the specified byte length.
///
/// # Errors
/// Returns `Storage` error if system random generation fails.
pub fn generate_hex_token(byte_count: usize) -> Result<String, LomoError> {
    let mut bytes = vec![0_u8; byte_count];
    fill_csprng(&mut bytes)?;
    let mut hex = String::with_capacity(byte_count.saturating_mul(2));
    for b in bytes {
        write!(hex, "{b:02x}").map_err(|err| storage("fmt_write_failed", format!("{err}")))?;
    }
    Ok(hex)
}

/// Generates a stable, CSPRNG-minted `MemoId` with standard prefix `m_`.
///
/// # Errors
/// Returns `Storage` or `Validation` error if entropy generation or ID parsing fails.
pub fn mint_memo_id() -> Result<MemoId, LomoError> {
    let hex = generate_hex_token(16)?;
    MemoId::parse(&format!("m_{hex}"))
}

/// Generates a private device identity token.
///
/// # Errors
/// Returns `Storage` error if entropy generation fails.
pub fn generate_device_id() -> Result<String, LomoError> {
    let hex = generate_hex_token(16)?;
    Ok(format!("device_{hex}"))
}
