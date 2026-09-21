//! Behavior Contract:
//! Capability: bound application exchange reads before allocation; owner: lomo-application; P0.
//! Scenarios:
//! - Given an artifact larger than the 64 MiB per-file budget, when read, then `ResourceLimit` is returned.
//! - Given corrupt evidence, when verified, then the bytes remain available for diagnosis/recovery.
//! - Given valid bytes, when read, then the bytes are returned and the temporary artifact is reclaimed.
//!
//! Observable outcomes: error category, returned bytes and retained/reclaimed files.
//! TDD proof: `cargo test -p lomo-application --test exchange_budget_contract --locked`;
//! RED: oversize returned Corruption after reading and removed the artifact; GREEN: same command.
//! Excludes: platform-provider streaming limits and media decoding.

#[cfg(test)]
mod tests {
    use lomo_application::exchange_io::{read_artifact_content, stage_content};
    use lomo_core::{ErrorCategory, ExchangeArtifact, Sha256Digest};

    #[test]
    fn oversized_artifact_is_rejected_before_hashing_and_preserved()
    -> Result<(), Box<dyn std::error::Error>> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("oversized");
        let length = 64 * 1024 * 1024 + 1;
        std::fs::File::create(&path)?.set_len(length)?;
        let artifact =
            ExchangeArtifact::new("oversized", length, Sha256Digest::parse(&"0".repeat(64))?)?;
        let error = read_artifact_content(directory.path(), &artifact)
            .err()
            .ok_or("oversized read succeeded")?;
        if error.category() != ErrorCategory::ResourceLimit {
            return Err(format!("unexpected category: {error:?}").into());
        }
        if !path.exists() {
            return Err("oversized evidence was removed".into());
        }
        Ok(())
    }

    #[test]
    fn corrupt_artifact_remains_available_and_valid_artifact_is_reclaimed()
    -> Result<(), Box<dyn std::error::Error>> {
        let directory = tempfile::tempdir()?;
        std::fs::write(directory.path().join("corrupt"), b"bytes")?;
        let corrupt = ExchangeArtifact::new("corrupt", 5, Sha256Digest::parse(&"0".repeat(64))?)?;
        let failure = read_artifact_content(directory.path(), &corrupt)
            .err()
            .ok_or("corrupt artifact accepted")?;
        if failure.code() != "exchange_artifact_digest_mismatch" {
            return Err(format!("unexpected failure: {failure:?}").into());
        }
        if std::fs::read(directory.path().join("corrupt"))? != b"bytes" {
            return Err("corrupt evidence changed".into());
        }
        let valid = stage_content(directory.path(), b"valid")?;
        if read_artifact_content(directory.path(), &valid)? != b"valid" {
            return Err("valid artifact bytes changed".into());
        }
        if directory.path().join(valid.token().as_str()).exists() {
            return Err("valid artifact was not reclaimed".into());
        }
        Ok(())
    }
}
