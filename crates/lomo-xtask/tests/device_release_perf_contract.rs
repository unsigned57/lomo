//! Behavior Contract:
//! Capability: release A/B measurements use a workspace that survives an APK signature swap;
//! owner: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given a Direct corpus, when release A/B is requested, then parsing rejects it before adb:
//!   release cannot use run-as to reactivate its app-private debug corpus after uninstall.
//! - Given SAF, when release A/B is requested, then the release plane accepts the public corpus
//!   that the production `DocumentsUI` grant can reactivate in both installed variants.
//!
//! Observable outcomes: parsed options and actionable errors, without touching a device.
//! TDD proof: RED: this target accepts Direct release A/B; GREEN: same target after validation.
//! Excludes: actual profile installation, release signing and physical-device performance.

#[cfg(test)]
mod tests {
    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{DeviceBackend, parse_device_perf_args};

    fn args(backend: &str) -> Vec<String> {
        ["release", "--corpus", "device", "--backend", backend]
            .into_iter()
            .map(str::to_owned)
            .collect()
    }

    #[test]
    fn direct_workspace_is_rejected_before_a_release_swap() -> Result<()> {
        let error = parse_device_perf_args(&args("direct"))
            .err()
            .context("release A/B accepted a corpus that release cannot reactivate")?;
        ensure!(error.to_string().contains("--backend saf"), "{error:#}");
        Ok(())
    }

    #[test]
    fn saf_workspace_can_be_regranted_for_release_measurements() -> Result<()> {
        let options = parse_device_perf_args(&args("saf"))?;
        ensure!(options.backend == DeviceBackend::Saf);
        Ok(())
    }

    #[test]
    fn release_measurement_runs_without_recollecting_profiles() -> Result<()> {
        let options = parse_device_perf_args(
            &["release", "--corpus", "device", "--backend", "saf"].map(str::to_owned),
        )?;
        ensure!(
            options
                .planes
                .iter()
                .map(|plane| plane.name())
                .collect::<Vec<_>>()
                == ["release"]
        );
        Ok(())
    }
}
