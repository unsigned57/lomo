//! Measured Android profile packaging inputs and their collection receipt.

use std::{fs, io::Read, path::Path, process::Command};

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::util;

pub const BASELINE_PROFILE: &str = "apps/android/app/src/main/baselineProfiles/generated.txt";
pub const STARTUP_PROFILE: &str = "apps/android/app/src/main/baselineProfiles/startup-prof.txt";
const RECEIPT: &str = "apps/android/app/baseline-profile.json";
const MAX_PROFILE_BYTES: u64 = 32 * 1024 * 1024;

/// Provenance of the unminified APK and workspace used by `AndroidX` profile collection.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ProfileCapture {
    pub apk_sha256: String,
    pub corpus_sha256: String,
    pub device_fingerprint: String,
    pub source_revision: String,
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct ProfileReceipt {
    schema_version: u32,
    baseline_sha256: String,
    startup_sha256: String,
    capture: ProfileCapture,
}

fn read_artifact(path: &Path, limit: u64) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    fs::File::open(path)
        .with_context(|| format!("open profile artifact {}", path.display()))?
        .take(limit + 1)
        .read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= limit,
        "profile artifact exceeds {limit} bytes: {}",
        path.display()
    );
    Ok(bytes)
}

fn digest(bytes: &[u8]) -> String {
    lomo_core::hex_encode(&Sha256::digest(bytes))
}

fn validate_capture(capture: &ProfileCapture) -> Result<()> {
    for hash in [&capture.apk_sha256, &capture.corpus_sha256] {
        ensure!(
            hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "invalid profile capture SHA-256"
        );
    }
    ensure!(
        !capture.device_fingerprint.trim().is_empty(),
        "profile capture has no device fingerprint"
    );
    ensure!(
        !capture.source_revision.trim().is_empty(),
        "profile capture has no source revision"
    );
    Ok(())
}

/// Validates HRF through the SDK's official parser, including diagnostics emitted with exit 0.
///
/// # Errors
/// Missing/empty/oversized rules, parser failures and parser diagnostics are rejected.
pub fn validate_profile_with_profgen(profgen: &Path, profile: &Path) -> Result<()> {
    let bytes = read_artifact(profile, MAX_PROFILE_BYTES)?;
    let text = std::str::from_utf8(&bytes).context("profile is not UTF-8")?;
    ensure!(
        text.lines()
            .any(|line| !line.trim().is_empty() && !line.trim().starts_with('#')),
        "profile has no rules: {}",
        profile.display()
    );
    let result = util::output(Command::new(profgen).arg("validate").arg(profile))?;
    // SDK profgen validate reports malformed HRF on stderr but can exit successfully.
    ensure!(
        result.stderr.iter().all(u8::is_ascii_whitespace),
        "profgen rejected {}: {}",
        profile.display(),
        String::from_utf8_lossy(&result.stderr)
    );
    Ok(())
}

/// Publishes both collected profiles, then the receipt that binds their complete bytes.
///
/// # Errors
/// Both profiles and capture provenance must validate before any packaging input is written.
/// A partial filesystem failure leaves a receipt mismatch, which fails the packaging gate.
pub fn publish_measured_profiles(
    root: &Path,
    profgen: &Path,
    baseline: &Path,
    startup: &Path,
    capture: ProfileCapture,
) -> Result<Value> {
    validate_capture(&capture)?;
    validate_profile_with_profgen(profgen, baseline)?;
    validate_profile_with_profgen(profgen, startup)?;
    let baseline_bytes = read_artifact(baseline, MAX_PROFILE_BYTES)?;
    let startup_bytes = read_artifact(startup, MAX_PROFILE_BYTES)?;
    let receipt = ProfileReceipt {
        schema_version: 1,
        baseline_sha256: digest(&baseline_bytes),
        startup_sha256: digest(&startup_bytes),
        capture,
    };
    fs::create_dir_all(
        root.join(BASELINE_PROFILE)
            .parent()
            .context("profile parent")?,
    )?;
    fs::write(root.join(BASELINE_PROFILE), baseline_bytes)?;
    fs::write(root.join(STARTUP_PROFILE), startup_bytes)?;
    fs::write(root.join(RECEIPT), serde_json::to_vec_pretty(&receipt)?)?;
    Ok(
        json!({"enabled": true, "outputs": [root.join(BASELINE_PROFILE), root.join(STARTUP_PROFILE), root.join(RECEIPT)]}),
    )
}

/// Verifies the measured inputs and provenance without expanding them into all runtime methods.
///
/// # Errors
/// Missing/modified artifacts, unknown receipt schemas and invalid official HRF fail closed.
pub fn validate_measured_profiles(root: &Path, profgen: &Path) -> Result<Value> {
    let receipt: ProfileReceipt =
        serde_json::from_slice(&read_artifact(&root.join(RECEIPT), 32 * 1024)?)?;
    ensure!(
        receipt.schema_version == 1,
        "unsupported measured profile receipt schema"
    );
    validate_capture(&receipt.capture)?;
    for (relative, expected) in [
        (BASELINE_PROFILE, &receipt.baseline_sha256),
        (STARTUP_PROFILE, &receipt.startup_sha256),
    ] {
        let path = root.join(relative);
        ensure!(
            digest(&read_artifact(&path, MAX_PROFILE_BYTES)?) == *expected,
            "measured profile receipt mismatch: {}; recollect with just perf device profile --write",
            path.display()
        );
        validate_profile_with_profgen(profgen, &path)?;
    }
    Ok(
        json!({"schema_version": 1, "status": "validated", "capture": receipt.capture, "baseline_sha256": receipt.baseline_sha256, "startup_sha256": receipt.startup_sha256}),
    )
}
