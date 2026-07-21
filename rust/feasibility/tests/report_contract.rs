//! Behavior Contract
//!
//! Capability: reject incomplete phase-0 baseline reports before they become evidence.
//!
//! Scenarios:
//! - Given a report missing environment, workload, or units, when validated, then validation fails.
//! - Given a complete report, when serialized, then JSON and human summary both succeed.
//!
//! Observable outcomes: `ReportValidationError::MissingField` names the incomplete field;
//! complete reports emit non-empty JSON and summaries.
//! TDD proof: incomplete fixtures fail before any implementation accepts them as baselines.
//! Excludes: real device measurement, dependency probe execution, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "feasibility contract harness fails closed with panics on missing probe facts"
)]
mod tests {
    use std::collections::BTreeMap;

    use lomo_feasibility::{
        BaselineConclusion, BaselineMetricV1, BaselineReportV1, BaselineSizesV1,
        DeviceFingerprintV1, FeasibilityExitCode, ReportValidationError, ToolchainFingerprintV1,
    };

    fn complete_report() -> BaselineReportV1 {
        BaselineReportV1 {
            schema_version: BaselineReportV1::SCHEMA_VERSION,
            git_revision: "deadbeef".to_owned(),
            toolchain: ToolchainFingerprintV1 {
                rustc: "1.96.0".to_owned(),
                ndk: "29.0.14206865".to_owned(),
                host: "x86_64-unknown-linux-gnu".to_owned(),
            },
            device: DeviceFingerprintV1 {
                api_level: 26,
                abi: "x86_64".to_owned(),
                kind: "emulator".to_owned(),
            },
            dependency_features: BTreeMap::from([("rusqlite".to_owned(), "bundled".to_owned())]),
            sample_count: 5,
            metrics: vec![BaselineMetricV1 {
                name: "cold_start".to_owned(),
                unit: "ms".to_owned(),
                p50: 120.0,
                p95: 180.0,
                peak_rss_bytes: Some(64 * 1024 * 1024),
                network_request_count: None,
                workload_summary: "empty_workspace".to_owned(),
                samples: Some(5),
                result_count: None,
                warm_path_p50_ms: None,
            }],
            sizes: BaselineSizesV1 {
                apk_compressed_bytes: 52_532_382,
                abi_so_bytes: BTreeMap::from([("arm64-v8a".to_owned(), 445_064)]),
            },
            conclusion: BaselineConclusion::Pass,
            notes: vec!["relative baseline only".to_owned()],
        }
    }

    #[test]
    fn complete_report_emits_json_and_human_summary() {
        let report = complete_report();
        let json = report.to_json().expect("complete report serializes");
        let summary = report
            .to_human_summary()
            .expect("complete report summarizes");
        assert!(json.starts_with(b"{"));
        assert!(summary.contains("cold_start"));
        assert!(summary.contains("unit=ms"));
        assert!(summary.contains("workload=empty_workspace"));
    }

    #[test]
    fn missing_environment_is_rejected() {
        let mut report = complete_report();
        report.toolchain.rustc.clear();
        assert_eq!(
            report.validate(),
            Err(ReportValidationError::MissingField {
                field: "toolchain.rustc",
            })
        );
    }

    #[test]
    fn missing_unit_is_rejected() {
        let mut report = complete_report();
        report.metrics.first_mut().expect("item").unit.clear();
        assert_eq!(
            report.validate(),
            Err(ReportValidationError::MissingField {
                field: "metrics.unit",
            })
        );
    }

    #[test]
    fn missing_workload_summary_is_rejected() {
        let mut report = complete_report();
        report
            .metrics
            .first_mut()
            .expect("item")
            .workload_summary
            .clear();
        assert_eq!(
            report.validate(),
            Err(ReportValidationError::MissingField {
                field: "metrics.workload_summary",
            })
        );
    }

    #[test]
    fn zero_sample_count_is_rejected() {
        let mut report = complete_report();
        report.sample_count = 0;
        assert_eq!(
            report.validate(),
            Err(ReportValidationError::MissingField {
                field: "sample_count",
            })
        );
    }

    #[test]
    fn feasibility_exit_codes_are_stable_integers() {
        assert_eq!(FeasibilityExitCode::Success.as_i32(), 0);
        assert_eq!(FeasibilityExitCode::ValidationFailed.as_i32(), 1);
        assert_eq!(FeasibilityExitCode::ProbeFailed.as_i32(), 2);
        assert_eq!(FeasibilityExitCode::EnvironmentIncomplete.as_i32(), 3);
        assert_eq!(FeasibilityExitCode::ReportIncomplete.as_i32(), 4);
        assert_eq!(FeasibilityExitCode::Success, FeasibilityExitCode::Success);
        assert_ne!(
            FeasibilityExitCode::Success,
            FeasibilityExitCode::ProbeFailed
        );
        assert!(format!("{:?}", FeasibilityExitCode::ProbeFailed).contains("ProbeFailed"));
    }
}
