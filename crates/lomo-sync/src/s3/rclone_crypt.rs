//! rclone crypt compatibility (filename/directory/data) using audited `RustCrypto` primitives.
//!
//! Matches the Kotlin `S3RcloneCryptCompatCodec` / rclone wire formats used by Lomo S3 remotes.
//! `ETag` remains a revision token only — never content `SHA-256`.

// EME/filename/base32 codecs operate on fixed 16-byte AES blocks and bit streams; bound-checked
// `.get` is used at block boundaries. Intra-block index math is constant-width (AES_BLOCK = 16).
#![expect(
    clippy::indexing_slicing,
    clippy::cast_possible_truncation,
    clippy::cast_possible_wrap,
    clippy::cast_sign_loss,
    clippy::needless_range_loop,
    reason = "rclone crypt EME/GF codecs are constant-width block transforms; pedantic slice and cast lints obscure verified wire math"
)]

use aes::Aes256;
use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use crypto_secretbox::aead::{Aead, Payload};
use crypto_secretbox::{Nonce, XSalsa20Poly1305};
use scrypt::{Params, scrypt};

use crate::error::validation;
use lomo_core::LomoError;

const AES_BLOCK: usize = 16;
const DATA_KEY_LEN: usize = 32;
const NAME_KEY_LEN: usize = 32;
const TOTAL_KEY_LEN: usize = DATA_KEY_LEN + NAME_KEY_LEN + AES_BLOCK;
/// `Params::new` key-length field is capped at 64 in scrypt 0.11 (`PasswordHasher` path). The low-level
/// `scrypt()` API honors `output.len()` independently — rclone requires 80 bytes.
const SCRYPT_PARAMS_LEN: usize = 32;
const SCRYPT_N_LOG2: u8 = 14; // 16384
const SCRYPT_R: u32 = 8;
const SCRYPT_P: u32 = 1;
const DEFAULT_SALT: [u8; 16] = [
    0xa8, 0x0d, 0xf4, 0x3a, 0x8f, 0xbd, 0x03, 0x08, 0xa7, 0xca, 0xb8, 0x3e, 0x58, 0x1f, 0x86, 0xb1,
];
const FILE_MAGIC: &[u8; 8] = b"RCLONE\0\0";
const FILE_NONCE_LEN: usize = 24;
const FILE_HEADER_LEN: usize = 8 + FILE_NONCE_LEN;
const BLOCK_DATA_SIZE: usize = 64 * 1024;
const SECRETBOX_OVERHEAD: usize = 16;
const MAX_EME_BLOCKS: usize = 16 * 8;
const GF_MULTIPLIER: u8 = 135;

/// Filename encryption mode (rclone `filename_encryption`).
///
/// **Stage-5 host-proven product surface:** [`RcloneFilenameEncryption::Standard`] with
/// [`RcloneFilenameEncoding::Base32`] (+ directory-name encryption) locked by
/// `fixtures/remote/rclone-crypt-vectors.json` and `s3_adapter_contract` goldens.
/// `Obfuscate` / `Off` remain typed code paths for cutover parity with Kotlin settings enums;
/// they are **not** Stage-5 host residual OPEN for full rclone CLI goldens.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RcloneFilenameEncryption {
    Standard,
    Obfuscate,
    Off,
}

/// Filename encoding alphabet.
///
/// **Stage-5 host-proven product surface:** [`RcloneFilenameEncoding::Base32`] only (fixture
/// goldens). `Base64` / `Base32768` remain typed code paths for cutover parity; they are **not**
/// Stage-5 host residual OPEN for full CLI goldens (real base32768 alphabet fidelity is Kotlin-side
/// until a later package lands matching host goldens).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RcloneFilenameEncoding {
    Base32,
    Base64,
    Base32768,
}

/// rclone crypt configuration (non-secret).
///
/// Stage-5 host residual bound: fixture **standard + base32 + directory encryption + data seal**.
/// Other mode combinations are code-path only (not residual OPEN for full CLI matrix).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RcloneCryptConfig {
    pub filename_encryption: RcloneFilenameEncryption,
    pub directory_name_encryption: bool,
    pub filename_encoding: RcloneFilenameEncoding,
    pub data_encryption_enabled: bool,
    /// Empty / `none` → no suffix; otherwise normalized with leading `.`.
    pub encrypted_suffix: String,
}

impl Default for RcloneCryptConfig {
    fn default() -> Self {
        Self {
            filename_encryption: RcloneFilenameEncryption::Standard,
            directory_name_encryption: true,
            filename_encoding: RcloneFilenameEncoding::Base32,
            data_encryption_enabled: true,
            encrypted_suffix: String::new(),
        }
    }
}

/// Derived key material (data + name + tweak).
#[derive(Clone)]
pub struct RcloneKeyMaterial {
    data_key: [u8; DATA_KEY_LEN],
    name_key: [u8; NAME_KEY_LEN],
    name_tweak: [u8; AES_BLOCK],
}

impl std::fmt::Debug for RcloneKeyMaterial {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RcloneKeyMaterial")
            .field("data_key", &"<redacted>")
            .field("name_key", &"<redacted>")
            .field("name_tweak", &"<redacted>")
            .finish()
    }
}

impl RcloneKeyMaterial {
    /// Derives rclone key material via scrypt (`N=16384`, `r=8`, `p=1`, `dkLen=80`).
    ///
    /// # Errors
    ///
    /// Validation when scrypt parameters are rejected by the library.
    pub fn derive(password: &str, password2: &str) -> Result<Self, LomoError> {
        if password.is_empty() {
            return Ok(Self {
                data_key: [0; DATA_KEY_LEN],
                name_key: [0; NAME_KEY_LEN],
                name_tweak: [0; AES_BLOCK],
            });
        }
        let salt = if password2.is_empty() {
            DEFAULT_SALT.to_vec()
        } else {
            password2.as_bytes().to_vec()
        };
        // Params::len is capped at 64 in scrypt 0.11; rclone needs 80 output bytes. The low-level
        // scrypt() uses output.len() for the derived-key length independently of Params::len.
        let params = Params::new(SCRYPT_N_LOG2, SCRYPT_R, SCRYPT_P, SCRYPT_PARAMS_LEN).map_err(
            |_error| {
                validation(
                    "rclone_scrypt_params",
                    "rclone scrypt parameters are invalid",
                )
            },
        )?;
        let mut out = [0_u8; TOTAL_KEY_LEN];
        scrypt(password.as_bytes(), &salt, &params, &mut out).map_err(|_error| {
            validation(
                "rclone_scrypt_failed",
                "rclone scrypt key derivation failed",
            )
        })?;
        let mut data_key = [0_u8; DATA_KEY_LEN];
        let mut name_key = [0_u8; NAME_KEY_LEN];
        let mut name_tweak = [0_u8; AES_BLOCK];
        data_key.copy_from_slice(
            out.get(..DATA_KEY_LEN)
                .ok_or_else(|| validation("rclone_scrypt_split", "scrypt output too short"))?,
        );
        name_key.copy_from_slice(
            out.get(DATA_KEY_LEN..DATA_KEY_LEN + NAME_KEY_LEN)
                .ok_or_else(|| validation("rclone_scrypt_split", "scrypt output too short"))?,
        );
        name_tweak.copy_from_slice(
            out.get(DATA_KEY_LEN + NAME_KEY_LEN..)
                .ok_or_else(|| validation("rclone_scrypt_split", "scrypt output too short"))?,
        );
        Ok(Self {
            data_key,
            name_key,
            name_tweak,
        })
    }
}

/// Encrypts a workspace-relative path (segments).
///
/// # Errors
///
/// Validation on encode/cipher failures.
pub fn encrypt_filename_path(
    path: &str,
    material: &RcloneKeyMaterial,
    config: &RcloneCryptConfig,
) -> Result<String, LomoError> {
    transform_segments(
        path,
        config.directory_name_encryption,
        |segment| match config.filename_encryption {
            RcloneFilenameEncryption::Standard => {
                let padded = pkcs7_pad(segment.as_bytes(), AES_BLOCK);
                let encrypted = eme_encrypt(&material.name_key, &material.name_tweak, &padded)?;
                Ok(encode_filename(&encrypted, config.filename_encoding))
            }
            RcloneFilenameEncryption::Obfuscate => {
                Ok(obfuscate_segment(segment, &material.name_key))
            }
            RcloneFilenameEncryption::Off => Ok(format!(
                "{segment}{}",
                normalized_suffix(&config.encrypted_suffix)
            )),
        },
    )
}

/// Decrypts an encrypted object key path.
///
/// # Errors
///
/// Validation when ciphertext is malformed.
pub fn decrypt_filename_path(
    path: &str,
    material: &RcloneKeyMaterial,
    config: &RcloneCryptConfig,
) -> Result<String, LomoError> {
    transform_segments(
        path,
        config.directory_name_encryption,
        |segment| match config.filename_encryption {
            RcloneFilenameEncryption::Standard => {
                decrypt_standard_segment(segment, material, config.filename_encoding)
            }
            RcloneFilenameEncryption::Obfuscate => deobfuscate_segment(segment, &material.name_key),
            RcloneFilenameEncryption::Off => {
                decrypt_off_filename(segment, &config.encrypted_suffix)
            }
        },
    )
}

/// Encrypts data payload (`RCLONE\\0\\0` + 24-byte nonce + secretbox blocks).
///
/// # Errors
///
/// Validation on AEAD failures.
pub fn encrypt_payload(
    plaintext: &[u8],
    material: &RcloneKeyMaterial,
    nonce: &[u8; FILE_NONCE_LEN],
) -> Result<Vec<u8>, LomoError> {
    let cipher = XSalsa20Poly1305::new_from_slice(&material.data_key).map_err(|_error| {
        validation(
            "rclone_data_key_invalid",
            "rclone data key must be 32 bytes",
        )
    })?;
    let mut out = Vec::with_capacity(FILE_HEADER_LEN + plaintext.len() + SECRETBOX_OVERHEAD);
    out.extend_from_slice(FILE_MAGIC);
    out.extend_from_slice(nonce);
    if plaintext.is_empty() {
        return Ok(out);
    }
    let mut block_nonce = *nonce;
    let mut offset = 0;
    while offset < plaintext.len() {
        let end = (offset + BLOCK_DATA_SIZE).min(plaintext.len());
        let block = plaintext.get(offset..end).ok_or_else(|| {
            validation(
                "rclone_encrypt_range",
                "rclone plaintext range is out of bounds",
            )
        })?;
        let nonce_arr = Nonce::from(block_nonce);
        let sealed = cipher
            .encrypt(
                &nonce_arr,
                Payload {
                    msg: block,
                    aad: b"",
                },
            )
            .map_err(|_error| {
                validation(
                    "rclone_encrypt_block_failed",
                    "rclone secretbox seal failed",
                )
            })?;
        out.extend_from_slice(&sealed);
        increment_nonce(&mut block_nonce);
        offset = end;
    }
    Ok(out)
}

/// Decrypts a data payload.
///
/// # Errors
///
/// Validation on magic/auth/size failures.
pub fn decrypt_payload(
    ciphertext: &[u8],
    material: &RcloneKeyMaterial,
) -> Result<Vec<u8>, LomoError> {
    if ciphertext.len() < FILE_HEADER_LEN {
        return Err(validation(
            "rclone_payload_too_short",
            "encrypted rclone payload is too short",
        ));
    }
    let magic = ciphertext.get(..8).ok_or_else(|| {
        validation(
            "rclone_payload_too_short",
            "encrypted rclone payload is too short",
        )
    })?;
    if magic != FILE_MAGIC {
        return Err(validation(
            "rclone_magic_mismatch",
            "encrypted payload does not use the rclone magic header",
        ));
    }
    if ciphertext.len() == FILE_HEADER_LEN {
        return Ok(Vec::new());
    }
    let cipher = XSalsa20Poly1305::new_from_slice(&material.data_key).map_err(|_error| {
        validation(
            "rclone_data_key_invalid",
            "rclone data key must be 32 bytes",
        )
    })?;
    let mut block_nonce = [0_u8; FILE_NONCE_LEN];
    let nonce_bytes = ciphertext.get(8..FILE_HEADER_LEN).ok_or_else(|| {
        validation(
            "rclone_payload_too_short",
            "encrypted rclone payload is too short",
        )
    })?;
    block_nonce.copy_from_slice(nonce_bytes);
    let mut out = Vec::new();
    let mut offset = FILE_HEADER_LEN;
    while offset < ciphertext.len() {
        let remaining = ciphertext.len() - offset;
        let block_size = remaining.min(BLOCK_DATA_SIZE + SECRETBOX_OVERHEAD);
        if block_size <= SECRETBOX_OVERHEAD {
            return Err(validation(
                "rclone_block_truncated",
                "encrypted rclone block header is truncated",
            ));
        }
        let block = ciphertext.get(offset..offset + block_size).ok_or_else(|| {
            validation(
                "rclone_block_truncated",
                "encrypted rclone block header is truncated",
            )
        })?;
        let nonce_arr = Nonce::from(block_nonce);
        let opened = cipher
            .decrypt(
                &nonce_arr,
                Payload {
                    msg: block,
                    aad: b"",
                },
            )
            .map_err(|_error| {
                validation(
                    "rclone_decrypt_auth_failed",
                    "failed to authenticate rclone encrypted block",
                )
            })?;
        out.extend_from_slice(&opened);
        increment_nonce(&mut block_nonce);
        offset += block_size;
    }
    Ok(out)
}

fn transform_segments<F>(
    path: &str,
    encrypt_dirs: bool,
    mut transform: F,
) -> Result<String, LomoError>
where
    F: FnMut(&str) -> Result<String, LomoError>,
{
    let segments: Vec<&str> = path.split('/').collect();
    let last = segments.len().saturating_sub(1);
    let mut out = Vec::with_capacity(segments.len());
    for (index, segment) in segments.iter().enumerate() {
        if segment.is_empty() {
            out.push(String::new());
        } else if !encrypt_dirs && index != last {
            out.push((*segment).to_owned());
        } else {
            out.push(transform(segment)?);
        }
    }
    Ok(out.join("/"))
}

fn decrypt_standard_segment(
    encrypted: &str,
    material: &RcloneKeyMaterial,
    encoding: RcloneFilenameEncoding,
) -> Result<String, LomoError> {
    let candidates: Vec<RcloneFilenameEncoding> = match encoding {
        RcloneFilenameEncoding::Base64 => {
            vec![
                RcloneFilenameEncoding::Base64,
                RcloneFilenameEncoding::Base32,
            ]
        }
        RcloneFilenameEncoding::Base32 | RcloneFilenameEncoding::Base32768 => vec![encoding],
    };
    let mut last_err = validation(
        "rclone_filename_malformed",
        "encrypted rclone filename is malformed",
    );
    for enc in candidates {
        match decode_filename(encrypted, enc) {
            Ok(decoded) if !decoded.is_empty() && decoded.len().is_multiple_of(AES_BLOCK) => {
                match eme_decrypt(&material.name_key, &material.name_tweak, &decoded) {
                    Ok(decrypted) => match pkcs7_unpad(&decrypted, AES_BLOCK) {
                        Ok(plain) => {
                            return String::from_utf8(plain).map_err(|_error| {
                                validation(
                                    "rclone_filename_not_utf8",
                                    "decrypted rclone filename is not UTF-8",
                                )
                            });
                        }
                        Err(error) => last_err = error,
                    },
                    Err(error) => last_err = error,
                }
            }
            Ok(_) => {
                last_err = validation(
                    "rclone_filename_malformed",
                    "encrypted rclone filename is malformed",
                );
            }
            Err(error) => last_err = error,
        }
    }
    Err(last_err)
}

fn decrypt_off_filename(encrypted: &str, configured_suffix: &str) -> Result<String, LomoError> {
    let suffix = normalized_suffix(configured_suffix);
    if suffix.is_empty() {
        if encrypted.is_empty() {
            return Err(validation(
                "rclone_filename_malformed",
                "encrypted rclone filename is malformed",
            ));
        }
        return Ok(encrypted.to_owned());
    }
    if encrypted.ends_with(&suffix) && encrypted.len() > suffix.len() {
        Ok(encrypted
            .get(..encrypted.len() - suffix.len())
            .unwrap_or("")
            .to_owned())
    } else {
        Err(validation(
            "rclone_filename_malformed",
            "encrypted rclone filename is malformed",
        ))
    }
}

fn normalized_suffix(value: &str) -> String {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("none") {
        String::new()
    } else if trimmed.starts_with('.') {
        trimmed.to_owned()
    } else {
        format!(".{trimmed}")
    }
}

fn encode_filename(input: &[u8], encoding: RcloneFilenameEncoding) -> String {
    match encoding {
        // Stage-5 product bound: host-proven surface is base32 only. Base32768 is typed for cutover
        // parity with Kotlin settings but deliberately reuses base32hex here — not a residual OPEN
        // claiming real rclone base32768 alphabet goldens.
        RcloneFilenameEncoding::Base32 | RcloneFilenameEncoding::Base32768 => {
            base32_hex_encode(input)
        }
        RcloneFilenameEncoding::Base64 => base64_url_encode(input),
    }
}

fn decode_filename(input: &str, encoding: RcloneFilenameEncoding) -> Result<Vec<u8>, LomoError> {
    match encoding {
        RcloneFilenameEncoding::Base32 | RcloneFilenameEncoding::Base32768 => {
            base32_hex_decode(input)
        }
        RcloneFilenameEncoding::Base64 => base64_url_decode(input),
    }
}

fn base32_hex_encode(input: &[u8]) -> String {
    const ALPHABET: &[u8; 32] = b"0123456789abcdefghijklmnopqrstuv";
    if input.is_empty() {
        return String::new();
    }
    let mut out = String::new();
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;
    for byte in input {
        buffer = (buffer << 8) | u32::from(*byte);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            let idx = ((buffer >> bits) & 0x1f) as usize;
            if let Some(ch) = ALPHABET.get(idx) {
                out.push(char::from(*ch));
            }
        }
    }
    if bits > 0 {
        let idx = ((buffer << (5 - bits)) & 0x1f) as usize;
        if let Some(ch) = ALPHABET.get(idx) {
            out.push(char::from(*ch));
        }
    }
    out
}

fn base32_hex_decode(input: &str) -> Result<Vec<u8>, LomoError> {
    if input.ends_with('=') {
        return Err(validation(
            "rclone_base32_padding",
            "encrypted rclone filename is not valid base32hex",
        ));
    }
    if input.is_empty() {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;
    for ch in input.chars() {
        let value = match ch {
            '0'..='9' => u32::from(ch as u8 - b'0'),
            'a'..='v' => u32::from(ch as u8 - b'a' + 10),
            'A'..='V' => u32::from(ch as u8 - b'A' + 10),
            _ => {
                return Err(validation(
                    "rclone_base32_invalid",
                    "encrypted rclone filename is not valid base32hex",
                ));
            }
        };
        buffer = (buffer << 5) | value;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push(((buffer >> bits) & 0xff) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    Ok(out)
}

fn base64_url_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(input.len().div_ceil(3).saturating_mul(4));
    let mut index = 0;
    while index < input.len() {
        let remaining = input.len() - index;
        let b0 = input.get(index).copied().unwrap_or(0);
        let b1 = if remaining > 1 {
            input.get(index + 1).copied().unwrap_or(0)
        } else {
            0
        };
        let b2 = if remaining > 2 {
            input.get(index + 2).copied().unwrap_or(0)
        } else {
            0
        };
        if let Some(ch) = TABLE.get(usize::from(b0 >> 2)) {
            out.push(char::from(*ch));
        }
        if let Some(ch) = TABLE.get(usize::from(((b0 & 0x03) << 4) | (b1 >> 4))) {
            out.push(char::from(*ch));
        }
        if remaining > 1
            && let Some(ch) = TABLE.get(usize::from(((b1 & 0x0f) << 2) | (b2 >> 6)))
        {
            out.push(char::from(*ch));
        }
        if remaining > 2
            && let Some(ch) = TABLE.get(usize::from(b2 & 0x3f))
        {
            out.push(char::from(*ch));
        }
        index += 3;
    }
    out
}

fn base64_url_decode(input: &str) -> Result<Vec<u8>, LomoError> {
    if input.ends_with('=') {
        return Err(validation(
            "rclone_base64_padding",
            "encrypted rclone filename is not valid base64url",
        ));
    }
    let pad = (4 - (input.len() % 4)) % 4;
    let mut padded = input.to_owned();
    padded.extend(std::iter::repeat_n('=', pad));
    let standard = padded.replace('-', "+").replace('_', "/");
    let mut out = Vec::new();
    let bytes = standard.as_bytes();
    let mut index = 0;
    while index + 3 < bytes.len() {
        let decode_val = |b: u8| -> Result<u8, LomoError> {
            match b {
                b'A'..=b'Z' => Ok(b - b'A'),
                b'a'..=b'z' => Ok(b - b'a' + 26),
                b'0'..=b'9' => Ok(b - b'0' + 52),
                b'+' => Ok(62),
                b'/' => Ok(63),
                b'=' => Ok(0),
                _ => Err(validation(
                    "rclone_base64_invalid",
                    "encrypted rclone filename is not valid base64url",
                )),
            }
        };
        let a = decode_val(*bytes.get(index).unwrap_or(&0))?;
        let b = decode_val(*bytes.get(index + 1).unwrap_or(&0))?;
        let c = decode_val(*bytes.get(index + 2).unwrap_or(&0))?;
        let d = decode_val(*bytes.get(index + 3).unwrap_or(&0))?;
        out.push((a << 2) | (b >> 4));
        if *bytes.get(index + 2).unwrap_or(&b'=') != b'=' {
            out.push((b << 4) | (c >> 2));
        }
        if *bytes.get(index + 3).unwrap_or(&b'=') != b'=' {
            out.push((c << 6) | d);
        }
        index += 4;
    }
    Ok(out)
}

fn pkcs7_pad(input: &[u8], block: usize) -> Vec<u8> {
    let padding = block - (input.len() % block);
    let mut out = input.to_vec();
    out.extend(std::iter::repeat_n(padding as u8, padding));
    out
}

fn pkcs7_unpad(input: &[u8], block: usize) -> Result<Vec<u8>, LomoError> {
    if input.is_empty() || !input.len().is_multiple_of(block) {
        return Err(validation(
            "rclone_pkcs7_invalid",
            "encrypted rclone filename padding is invalid",
        ));
    }
    let padding = usize::from(*input.last().unwrap_or(&0));
    if padding == 0 || padding > block {
        return Err(validation(
            "rclone_pkcs7_invalid",
            "encrypted rclone filename padding is invalid",
        ));
    }
    let pad_start = input.len().saturating_sub(padding);
    let pad_bytes = input.get(pad_start..).ok_or_else(|| {
        validation(
            "rclone_pkcs7_invalid",
            "encrypted rclone filename padding is invalid",
        )
    })?;
    for byte in pad_bytes {
        if usize::from(*byte) != padding {
            return Err(validation(
                "rclone_pkcs7_invalid",
                "encrypted rclone filename padding is invalid",
            ));
        }
    }
    Ok(input.get(..pad_start).unwrap_or(&[]).to_vec())
}

fn eme_encrypt(key: &[u8; 32], tweak: &[u8; 16], plaintext: &[u8]) -> Result<Vec<u8>, LomoError> {
    eme_transform(key, tweak, plaintext, true)
}

fn eme_decrypt(key: &[u8; 32], tweak: &[u8; 16], ciphertext: &[u8]) -> Result<Vec<u8>, LomoError> {
    eme_transform(key, tweak, ciphertext, false)
}

fn eme_transform(
    key: &[u8; 32],
    tweak: &[u8; 16],
    input: &[u8],
    encrypt: bool,
) -> Result<Vec<u8>, LomoError> {
    if input.is_empty() || !input.len().is_multiple_of(AES_BLOCK) {
        return Err(validation(
            "rclone_eme_input",
            "EME input must be a non-empty multiple of 16 bytes",
        ));
    }
    let block_count = input.len() / AES_BLOCK;
    if block_count > MAX_EME_BLOCKS {
        return Err(validation(
            "rclone_eme_too_large",
            "EME input exceeds the rclone filename block limit",
        ));
    }
    let cipher = Aes256::new_from_slice(key)
        .map_err(|_error| validation("rclone_aes_key", "AES-256 key material is invalid"))?;
    let l_table = tabulate_l(&cipher, block_count);
    let mut output = vec![0_u8; input.len()];
    let mut tmp = [0_u8; AES_BLOCK];
    for index in 0..block_count {
        let off = index * AES_BLOCK;
        let src = input
            .get(off..off + AES_BLOCK)
            .ok_or_else(|| validation("rclone_eme_input", "EME input block out of range"))?;
        let l_block = l_table
            .get(index)
            .ok_or_else(|| validation("rclone_eme_input", "EME L-table index out of range"))?;
        xor_into(&mut tmp, src, l_block);
        let transformed = aes_block(&cipher, &tmp, encrypt);
        if let Some(dest) = output.get_mut(off..off + AES_BLOCK) {
            dest.copy_from_slice(&transformed);
        }
    }
    let mut mp = [0_u8; AES_BLOCK];
    if let Some(first) = output.get(..AES_BLOCK) {
        mp.copy_from_slice(first);
    }
    xor_in_place(&mut mp, tweak);
    for index in 1..block_count {
        let off = index * AES_BLOCK;
        if let Some(block) = output.get(off..off + AES_BLOCK) {
            xor_in_place_slice(&mut mp, block);
        }
    }
    let mc = aes_block(&cipher, &mp, encrypt);
    let mut m = xor_bytes(&mp, &mc);
    for index in 1..block_count {
        multiply_by_two_in_place(&mut m);
        let off = index * AES_BLOCK;
        if let Some(block) = output.get(off..off + AES_BLOCK) {
            xor_into(&mut tmp, block, &m);
        }
        if let Some(dest) = output.get_mut(off..off + AES_BLOCK) {
            dest.copy_from_slice(&tmp);
        }
    }
    let mut ccc1 = xor_bytes(&mc, tweak);
    for index in 1..block_count {
        let off = index * AES_BLOCK;
        if let Some(block) = output.get(off..off + AES_BLOCK) {
            xor_in_place_slice(&mut ccc1, block);
        }
    }
    if let Some(dest) = output.get_mut(..AES_BLOCK) {
        dest.copy_from_slice(&ccc1);
    }
    for index in 0..block_count {
        let off = index * AES_BLOCK;
        let mut block = [0_u8; AES_BLOCK];
        if let Some(src) = output.get(off..off + AES_BLOCK) {
            block.copy_from_slice(src);
        }
        let transformed = aes_block(&cipher, &block, encrypt);
        let l_block = l_table
            .get(index)
            .ok_or_else(|| validation("rclone_eme_input", "EME L-table index out of range"))?;
        let final_block = xor_bytes(&transformed, l_block);
        if let Some(dest) = output.get_mut(off..off + AES_BLOCK) {
            dest.copy_from_slice(&final_block);
        }
    }
    Ok(output)
}

fn tabulate_l(cipher: &Aes256, block_count: usize) -> Vec<[u8; AES_BLOCK]> {
    let mut l_value = aes_block(cipher, &[0_u8; AES_BLOCK], true);
    let mut table = Vec::with_capacity(block_count);
    for _ in 0..block_count {
        multiply_by_two_in_place(&mut l_value);
        table.push(l_value);
    }
    table
}

fn aes_block(cipher: &Aes256, block: &[u8; 16], encrypt: bool) -> [u8; 16] {
    let mut b = aes::Block::from(*block);
    if encrypt {
        cipher.encrypt_block(&mut b);
    } else {
        cipher.decrypt_block(&mut b);
    }
    let mut out = [0_u8; 16];
    out.copy_from_slice(&b);
    out
}

fn xor_bytes(left: &[u8; 16], right: &[u8; 16]) -> [u8; 16] {
    let mut out = [0_u8; 16];
    for index in 0..16 {
        out[index] = left[index] ^ right[index];
    }
    out
}

fn xor_into(dest: &mut [u8; 16], source: &[u8], other: &[u8; 16]) {
    for index in 0..16 {
        dest[index] = source.get(index).copied().unwrap_or(0) ^ other[index];
    }
}

fn xor_in_place(target: &mut [u8; 16], other: &[u8; 16]) {
    for index in 0..16 {
        target[index] ^= other[index];
    }
}

fn xor_in_place_slice(target: &mut [u8; 16], other: &[u8]) {
    for index in 0..16 {
        target[index] ^= other.get(index).copied().unwrap_or(0);
    }
}

fn multiply_by_two_in_place(block: &mut [u8; 16]) {
    let input = *block;
    // Match Kotlin GF(2^128) doubling used by rclone EME.
    let first_i = (i32::from(input[0]) & 0xff) << 1;
    let high = (i32::from(input[15]) & 0xff) >> 7;
    block[0] = ((first_i ^ (i32::from(GF_MULTIPLIER) & -high)) & 0xff) as u8;
    for index in 1..16 {
        let value = (i32::from(input[index]) & 0xff) << 1;
        let carry = (i32::from(input[index - 1]) & 0xff) >> 7;
        block[index] = ((value + carry) & 0xff) as u8;
    }
}

fn increment_nonce(nonce: &mut [u8; FILE_NONCE_LEN]) {
    for byte in nonce.iter_mut() {
        let current = *byte;
        let next = current.wrapping_add(1);
        *byte = next;
        if next >= current {
            break;
        }
    }
}

fn obfuscate_segment(plaintext: &str, name_key: &[u8; 32]) -> String {
    if plaintext.is_empty() {
        return String::new();
    }
    let mut direction: i32 = 0;
    for ch in plaintext.chars() {
        direction = direction.wrapping_add(ch as i32);
    }
    direction %= 256;
    let mut result = String::new();
    result.push_str(&direction.to_string());
    result.push('.');
    for byte in name_key {
        direction = direction.wrapping_add(i32::from(*byte));
    }
    for ch in plaintext.chars() {
        let code = ch as u32;
        if ch == '!' {
            result.push('!');
            result.push('!');
        } else if ch.is_ascii_digit() {
            let offset = (direction % 9) + 1;
            let rotated = b'0' + ((code as u8 - b'0' + offset as u8) % 10);
            result.push(char::from(rotated));
        } else if ch.is_ascii_alphabetic() {
            let offset = direction % 25 + 1;
            let mut position = (code as i32) - i32::from(b'A');
            if position >= 26 {
                position -= 6;
            }
            position = (position + offset) % 52;
            if position >= 26 {
                position += 6;
            }
            result.push(char::from((i32::from(b'A') + position) as u8));
        } else {
            result.push(ch);
        }
    }
    result
}

fn deobfuscate_segment(ciphertext: &str, name_key: &[u8; 32]) -> Result<String, LomoError> {
    if ciphertext.is_empty() {
        return Ok(String::new());
    }
    let Some(dot) = ciphertext.find('.') else {
        return Err(validation(
            "rclone_obfuscate_invalid",
            "encrypted rclone filename is not a valid obfuscated segment",
        ));
    };
    let prefix = ciphertext.get(..dot).unwrap_or("");
    let encoded = ciphertext.get(dot + 1..).unwrap_or("");
    if prefix == "!" {
        return Ok(encoded.to_owned());
    }
    let mut direction: i32 = prefix.parse().map_err(|_error| {
        validation(
            "rclone_obfuscate_invalid",
            "encrypted rclone filename is not a valid obfuscated segment",
        )
    })?;
    for byte in name_key {
        direction = direction.wrapping_add(i32::from(*byte));
    }
    let mut result = String::new();
    let mut in_quote = false;
    for ch in encoded.chars() {
        let code = ch as u32;
        if in_quote {
            result.push(ch);
            in_quote = false;
        } else if ch == '!' {
            in_quote = true;
        } else if ch.is_ascii_digit() {
            let offset = (direction % 9) + 1;
            let mut rotated = i32::from(b'0') + (code as i32) - i32::from(b'0') - offset;
            if rotated < i32::from(b'0') {
                rotated += 10;
            }
            result.push(char::from(rotated as u8));
        } else if ch.is_ascii_alphabetic() {
            let offset = direction % 25 + 1;
            let mut position = (code as i32) - i32::from(b'A');
            if position >= 26 {
                position -= 6;
            }
            position -= offset;
            if position < 0 {
                position += 52;
            }
            if position >= 26 {
                position += 6;
            }
            result.push(char::from((i32::from(b'A') + position) as u8));
        } else {
            result.push(ch);
        }
    }
    Ok(result)
}
