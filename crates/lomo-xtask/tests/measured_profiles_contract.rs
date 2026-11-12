//! Behavior Contract:
//! Capability: device-collected profile bytes and provenance form one packaging input;
//! owner: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given legal collected subsets, publication and validation retain exactly those bytes.
//! - Given modified files, missing provenance or comment-only profiles, validation rejects them.
//! - Given the SDK parser reports an error with exit 0, validation still rejects publication.
//!
//! Observable outcomes: packaged bytes, receipt validation and classified diagnostic errors.
//! TDD proof: real SDK probe returned exit 0 for `invalid profile` with an error diagnostic;
//! this target locks the adapter that rejects that outcome. Release admission RED is recorded
//! in `device_release_perf_contract` and reports/manual/performance-mechanism/.
//! Excludes: ART hotness, correctness of the SDK parser and measured startup/scroll benefit.

#[cfg(test)]
#[cfg(unix)]
mod tests {
    use std::{fs, os::unix::fs::PermissionsExt as _, path::PathBuf};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{ProfileCapture, publish_measured_profiles, validate_measured_profiles};

    fn fixture() -> Result<(tempfile::TempDir, PathBuf, PathBuf, PathBuf)> {
        let dir = tempfile::tempdir()?;
        let parser = dir.path().join("profgen");
        fs::write(
            &parser,
            "#!/bin/sh\nset -eu\ntest \"$1\" = validate\ncase \"$(cat \"$2\")\" in *invalid*) echo 'profile:1:1 error: Illegal token' >&2 ;; esac\n",
        )?;
        fs::set_permissions(&parser, fs::Permissions::from_mode(0o755))?;
        let baseline = dir.path().join("collected-baseline.txt");
        let startup = dir.path().join("collected-startup.txt");
        fs::write(&baseline, "HSPLsample/Probe;->current()V\n")?;
        fs::write(&startup, "Lsample/Probe;\n")?;
        Ok((dir, parser, baseline, startup))
    }

    fn capture() -> ProfileCapture {
        ProfileCapture {
            apk_sha256: "a".repeat(64),
            corpus_sha256: "b".repeat(64),
            device_fingerprint: "test/device".to_owned(),
            source_revision: "test-source-dirty".to_owned(),
        }
    }

    #[test]
    fn measured_subsets_round_trip_without_static_expansion() -> Result<()> {
        let (dir, parser, baseline, startup) = fixture()?;
        publish_measured_profiles(dir.path(), &parser, &baseline, &startup, capture())?;
        let evidence = validate_measured_profiles(dir.path(), &parser)?;
        ensure!(evidence.get("status").and_then(serde_json::Value::as_str) == Some("validated"));
        ensure!(
            fs::read(
                dir.path()
                    .join("apps/android/app/src/main/baselineProfiles/generated.txt")
            )? == fs::read(baseline)?
        );
        ensure!(
            fs::read(
                dir.path()
                    .join("apps/android/app/src/main/baselineProfiles/startup-prof.txt")
            )? == fs::read(startup)?
        );
        Ok(())
    }

    #[test]
    fn changing_packaged_rules_invalidates_the_collection_receipt() -> Result<()> {
        let (dir, parser, baseline, startup) = fixture()?;
        publish_measured_profiles(dir.path(), &parser, &baseline, &startup, capture())?;
        fs::write(
            dir.path()
                .join("apps/android/app/src/main/baselineProfiles/generated.txt"),
            "Lsample/Other;\n",
        )?;
        let error = validate_measured_profiles(dir.path(), &parser)
            .err()
            .context("tampered rules were accepted")?;
        ensure!(error.to_string().contains("receipt mismatch"), "{error:#}");
        Ok(())
    }

    #[test]
    fn zero_exit_parser_diagnostics_reject_the_entire_publication() -> Result<()> {
        let (dir, parser, baseline, startup) = fixture()?;
        fs::write(&startup, "invalid profile\n")?;
        let error = publish_measured_profiles(dir.path(), &parser, &baseline, &startup, capture())
            .err()
            .context("zero-exit parser error was accepted")?;
        ensure!(error.to_string().contains("Illegal token"), "{error:#}");
        ensure!(
            !dir.path()
                .join("apps/android/app/baseline-profile.json")
                .exists()
        );
        ensure!(
            !dir.path()
                .join("apps/android/app/src/main/baselineProfiles/generated.txt")
                .exists()
        );
        Ok(())
    }

    #[test]
    fn empty_rules_and_missing_provenance_cannot_publish() -> Result<()> {
        let (dir, parser, baseline, startup) = fixture()?;
        fs::write(&startup, "# comments only\n")?;
        let error = publish_measured_profiles(dir.path(), &parser, &baseline, &startup, capture())
            .err()
            .context("comment-only profile was accepted")?;
        ensure!(error.to_string().contains("no rules"), "{error:#}");
        let mut missing = capture();
        missing.device_fingerprint.clear();
        let error = publish_measured_profiles(dir.path(), &parser, &baseline, &baseline, missing)
            .err()
            .context("missing capture identity was accepted")?;
        ensure!(error.to_string().contains("fingerprint"), "{error:#}");
        Ok(())
    }
}
