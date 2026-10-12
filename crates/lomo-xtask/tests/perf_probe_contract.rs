//! Behavior Contract:
//! Capability: `just perf` carries the four P-61 owner-path probes —
//! `reconcile_idle_ms`, `io_read_ms`, `paging_append_ms` (lomo-application
//! `session_path_probe`) and `ffi_roundtrip_ms` (lomo-native `ffi_roundtrip_probe`) —
//! as required metrics that pass the existing two-round stability gate.
//! Owning layer: lomo-xtask perf command; priority: P0 (measurement-facility wiring).
//!
//! Scenarios:
//! - Given the probe examples, when their sources are inspected, then each emits its marker
//!   line with the p50/p95 field names the xtask parser requires, and measures the production
//!   owners (`WorkspaceSession::rebuild_projection`, verified `io.read` envelope, cursor
//!   continuation fetches, `LomoEngine::query_memos`) rather than synthetic loops.
//! - Given `perf.rs`, when the metric surface is inspected, then all four names sit in
//!   `REQUIRED_BASELINE_METRICS`, so a missing probe forces Inconclusive and a non-zero exit.
//! - Given the wiring, when `measure_required_non_scale_metrics` runs, then both probes are
//!   invoked via `run_owner_probe` inside the same quiet two-round path as the other
//!   required metrics — one isolated owner process per round so peak RSS is attributable.
//!
//! Observable outcomes: presence and shape of the probe markers/fields and the perf wiring.
//!
//! TDD proof: `cargo test -p lomo-xtask --test perf_probe_contract --locked`.
//! RED: before P-61 the probe examples and the four required names did not exist; dropping
//! any probe, marker or required-name entry fails this contract.
//! Excludes: numeric thresholds and device runs (the live `just perf` evidence owns those).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::repository_root;

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn session_probe_measures_owner_paths_and_reports_required_fields() -> Result<()> {
        let source = read(
            &repository_root()?.join("crates/lomo-application/examples/session_path_probe.rs"),
        )?;
        for pin in [
            "SESSION_PATH_BENCH",
            "rebuild_projection",
            "reconcile_idle_p50_ms",
            "reconcile_idle_p95_ms",
            "io_read_p50_ms",
            "io_read_p95_ms",
            "paging_append_p50_ms",
            "paging_append_p95_ms",
            "peak_rss_bytes",
            "memo_count",
            "samples",
        ] {
            ensure!(source.contains(pin), "session_path_probe.rs missing {pin}");
        }
        // The paging probe must continue through the returned cursor, not re-issue page one.
        ensure!(
            source.contains("Some(&cursor)"),
            "paging_append_ms must fetch continuation pages through the cursor"
        );
        Ok(())
    }

    #[test]
    fn ffi_probe_measures_facade_round_trip_and_reports_fields() -> Result<()> {
        let source =
            read(&repository_root()?.join("crates/lomo-native/examples/ffi_roundtrip_probe.rs"))?;
        for pin in [
            "FFI_ROUNDTRIP_BENCH",
            "open_workspace_session",
            "query_memos",
            "ffi_roundtrip_p50_ms",
            "ffi_roundtrip_p95_ms",
            "peak_rss_bytes",
            "memo_count",
            "samples",
        ] {
            ensure!(source.contains(pin), "ffi_roundtrip_probe.rs missing {pin}");
        }
        Ok(())
    }

    #[test]
    fn perf_surface_requires_all_four_metrics_through_owner_probes() -> Result<()> {
        let perf = read(&repository_root()?.join("crates/lomo-xtask/src/perf.rs"))?;
        for name in [
            "reconcile_idle_ms",
            "io_read_ms",
            "paging_append_ms",
            "ffi_roundtrip_ms",
        ] {
            ensure!(
                perf.contains(&format!("\"{name}\"")),
                "perf.rs must carry {name} as a metric name"
            );
        }
        // The four names must live in the required list — missing required metrics force
        // Inconclusive, so a probe that cannot report can never masquerade as a pass.
        let required_start = perf
            .find("REQUIRED_BASELINE_METRICS")
            .context("REQUIRED_BASELINE_METRICS must exist")?;
        let required_block = perf
            .get(required_start..required_start + 600)
            .context("REQUIRED_BASELINE_METRICS block truncated")?;
        for name in [
            "reconcile_idle_ms",
            "io_read_ms",
            "paging_append_ms",
            "ffi_roundtrip_ms",
        ] {
            ensure!(
                required_block.contains(&format!("\"{name}\"")),
                "{name} must be inside REQUIRED_BASELINE_METRICS"
            );
        }
        for pin in [
            "session_path_probe",
            "ffi_roundtrip_probe",
            "SESSION_PATH_BENCH",
            "FFI_ROUNDTRIP_BENCH",
            "run_owner_probe",
            "measure_session_path_metrics",
            "measure_ffi_roundtrip_metric",
        ] {
            ensure!(perf.contains(pin), "perf.rs missing probe wiring: {pin}");
        }
        Ok(())
    }
}
