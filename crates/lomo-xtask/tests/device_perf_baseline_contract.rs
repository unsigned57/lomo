//! Behavior Contract:
//! Capability: backend-specific, validated engine performance baselines; owner: lomo-xtask; P0.
//! Scenarios:
//! - Given alternating SAF and Direct runs, when compared, then each uses only its own prior
//!   metric, preserving the other backend and untouched metrics across updates.
//! - Given valid samples, when summarized, then nearest-rank p50/p95 and the 15%/five-sample
//!   policy produce diagnostic verdicts; first runs have no baseline.
//! - Given corrupt, unsupported or invalid baseline/sample input, when evaluated, then the
//!   error is surfaced and the previous baseline bytes remain intact.
//!
//! Observable outcomes: returned metric verdicts and persisted JSON bytes.
//! TDD proof: `cargo test -p lomo-xtask --test device_perf_baseline_contract --locked`;
//! RED/GREEN recorded in audit/performance/device-followup-2026-10-21.md.
//! Excludes: device execution, physical performance and cross-device/corpus comparability.

#[cfg(test)]
mod tests {
    use std::fs;

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{DeviceBackend, engine_noise_gate};
    use serde_json::{Value, json};

    fn probes(samples: &Value) -> Vec<Value> {
        vec![json!({"run": "engine-rebuild-idle", "result": {
            "metrics": {"reconcile_ms": samples}
        }})]
    }

    fn metric<'a>(gate: &'a Value, field: &str) -> Result<&'a Value> {
        gate.pointer(&format!(
            "/metrics/engine-rebuild-idle.reconcile_ms/{field}"
        ))
        .context("missing gate metric field")
    }

    #[test]
    fn alternating_backends_preserve_independent_baselines() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let path = temporary.path().join("baseline.json");
        let saf = engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([200, 200, 200, 200, 200])),
        )?;
        ensure!(metric(&saf, "verdict")? == "no_baseline");
        let direct = engine_noise_gate(
            &path,
            DeviceBackend::Direct,
            &probes(&json!([40, 40, 40, 40, 40])),
        )?;
        ensure!(metric(&direct, "verdict")? == "no_baseline");
        let saf = engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([210, 210, 210, 210, 210])),
        )?;
        ensure!(metric(&saf, "baseline_p50")? == 200.0);
        ensure!(metric(&saf, "verdict")? == "within_threshold");
        let direct = engine_noise_gate(
            &path,
            DeviceBackend::Direct,
            &probes(&json!([42, 42, 42, 42, 42])),
        )?;
        ensure!(metric(&direct, "baseline_p50")? == 40.0);
        ensure!(metric(&direct, "verdict")? == "within_threshold");
        let saved: Value = serde_json::from_slice(&fs::read(&path)?)?;
        ensure!(
            saved
                .pointer("/backends/saf/engine-rebuild-idle.reconcile_ms/p50")
                .context("missing persisted backend metric")?
                == 210.0
        );
        ensure!(
            saved
                .pointer("/backends/direct/engine-rebuild-idle.reconcile_ms/p50")
                .context("missing persisted backend metric")?
                == 42.0
        );
        Ok(())
    }

    #[test]
    fn partial_run_preserves_unmeasured_metrics() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let path = temporary.path().join("baseline.json");
        engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([200, 200, 200, 200, 200])),
        )?;
        let other = vec![json!({"run": "engine-search", "result": {
            "metrics": {"search_ms": [1, 1, 1, 1, 1]}
        }})];
        engine_noise_gate(&path, DeviceBackend::Saf, &other)?;
        let next = engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([205, 205, 205, 205, 205])),
        )?;
        ensure!(metric(&next, "baseline_p50")? == 200.0);
        Ok(())
    }

    #[test]
    fn percentiles_sample_minimum_and_large_deltas_stay_diagnostic() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let path = temporary.path().join("baseline.json");
        let first =
            engine_noise_gate(&path, DeviceBackend::Saf, &probes(&json!([10, 1, 3, 2, 4])))?;
        ensure!(metric(&first, "p50")? == 3.0);
        ensure!(metric(&first, "p95")? == 10.0);
        let sparse = engine_noise_gate(&path, DeviceBackend::Saf, &probes(&json!([3, 3, 3])))?;
        ensure!(metric(&sparse, "samples")? == 3);
        ensure!(metric(&sparse, "verdict")? == "inconclusive");
        let changed = engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([30, 30, 30, 30, 30])),
        )?;
        ensure!(metric(&changed, "verdict")? == "inconclusive");
        Ok(())
    }

    #[test]
    fn malformed_and_unsupported_baselines_are_errors_without_overwrite() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let path = temporary.path().join("baseline.json");
        for body in [
            "{broken".to_owned(),
            json!({"schema_version": 1, "kind": "device_perf_engine_baseline", "metrics": {}})
                .to_string(),
            json!({"schema_version": 99, "kind": "device_perf_engine_baseline", "backends": {}})
                .to_string(),
            json!({"schema_version": 2, "kind": "wrong", "backends": {}}).to_string(),
            json!({"schema_version": 2, "kind": "device_perf_engine_baseline", "backends": {
                "saf": {"engine-rebuild-idle.reconcile_ms": {"p50": -1, "p95": 1}}
            }})
            .to_string(),
        ] {
            fs::write(&path, &body)?;
            let result =
                engine_noise_gate(&path, DeviceBackend::Saf, &probes(&json!([3, 3, 3, 3, 3])));
            ensure!(result.is_err(), "invalid baseline accepted: {body}");
            ensure!(fs::read_to_string(&path)? == body);
        }
        Ok(())
    }

    #[test]
    fn invalid_probe_input_cannot_replace_history() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let path = temporary.path().join("baseline.json");
        engine_noise_gate(
            &path,
            DeviceBackend::Saf,
            &probes(&json!([200, 200, 200, 200, 200])),
        )?;
        let before = fs::read(&path)?;
        for invalid in [
            vec![],
            probes(&json!([])),
            probes(&json!([1, "invalid", 3])),
            probes(&json!([-1, 2, 3])),
            vec![json!({"run": "broken", "result": {}})],
            vec![json!({"result": {"metrics": {"ms": [1, 2, 3]}}})],
            [
                probes(&json!([1, 1, 1, 1, 1])),
                probes(&json!([2, 2, 2, 2, 2])),
            ]
            .concat(),
        ] {
            ensure!(engine_noise_gate(&path, DeviceBackend::Saf, &invalid).is_err());
            ensure!(fs::read(&path)? == before);
        }
        Ok(())
    }
}
