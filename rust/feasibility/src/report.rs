use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Machine-readable phase-0 baseline report.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct BaselineReportV1 {
    pub schema_version: u32,
    pub git_revision: String,
    pub toolchain: ToolchainFingerprintV1,
    pub device: DeviceFingerprintV1,
    pub dependency_features: BTreeMap<String, String>,
    pub sample_count: u32,
    pub metrics: Vec<BaselineMetricV1>,
    pub sizes: BaselineSizesV1,
    pub conclusion: BaselineConclusion,
    pub notes: Vec<String>,
}

/// Toolchain and host software fingerprint.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ToolchainFingerprintV1 {
    pub rustc: String,
    pub ndk: String,
    pub host: String,
}

/// Device or emulator identity used for measurements.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DeviceFingerprintV1 {
    pub api_level: u32,
    pub abi: String,
    pub kind: String,
}

/// One measured baseline metric with explicit units and workload.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct BaselineMetricV1 {
    pub name: String,
    pub unit: String,
    pub p50: f64,
    pub p95: f64,
    pub peak_rss_bytes: Option<u64>,
    pub network_request_count: Option<u64>,
    pub workload_summary: String,
    /// Samples used for this metric (may differ from report-level defaults for heavy workloads).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub samples: Option<u32>,
    /// Observable result cardinality (e.g. memo files successfully parsed).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_count: Option<u64>,
    /// Single-item warm-path p50 in milliseconds when measured in the same isolated run.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub warm_path_p50_ms: Option<f64>,
}

/// APK and per-ABI native library sizes.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct BaselineSizesV1 {
    pub apk_compressed_bytes: u64,
    pub abi_so_bytes: BTreeMap<String, u64>,
}

/// Overall report conclusion.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BaselineConclusion {
    Pass,
    Fail,
    Inconclusive,
}

/// Report or corpus validation failures.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum ReportValidationError {
    #[error("unsupported schema version {found}, expected {expected}")]
    UnsupportedSchema { found: u32, expected: u32 },
    #[error("missing required field `{field}`")]
    MissingField { field: &'static str },
    #[error("invalid field `{field}`")]
    InvalidField { field: &'static str },
    #[error("serialization failed: {detail}")]
    Serialize { detail: String },
}

impl BaselineReportV1 {
    pub const SCHEMA_VERSION: u32 = 1;

    /// Validate environment, workload, and unit completeness.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when the report is incomplete or malformed.
    pub fn validate(&self) -> Result<(), ReportValidationError> {
        if self.schema_version != Self::SCHEMA_VERSION {
            return Err(ReportValidationError::UnsupportedSchema {
                found: self.schema_version,
                expected: Self::SCHEMA_VERSION,
            });
        }
        if self.git_revision.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "git_revision",
            });
        }
        if self.toolchain.rustc.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "toolchain.rustc",
            });
        }
        if self.toolchain.ndk.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "toolchain.ndk",
            });
        }
        if self.toolchain.host.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "toolchain.host",
            });
        }
        if self.device.abi.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "device.abi",
            });
        }
        if self.device.kind.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "device.kind",
            });
        }
        if self.device.api_level == 0 {
            return Err(ReportValidationError::InvalidField {
                field: "device.api_level",
            });
        }
        if self.sample_count == 0 {
            return Err(ReportValidationError::MissingField {
                field: "sample_count",
            });
        }
        if self.metrics.is_empty() {
            return Err(ReportValidationError::MissingField { field: "metrics" });
        }
        for metric in &self.metrics {
            if metric.name.trim().is_empty() {
                return Err(ReportValidationError::MissingField {
                    field: "metrics.name",
                });
            }
            if metric.unit.trim().is_empty() {
                return Err(ReportValidationError::MissingField {
                    field: "metrics.unit",
                });
            }
            if metric.workload_summary.trim().is_empty() {
                return Err(ReportValidationError::MissingField {
                    field: "metrics.workload_summary",
                });
            }
            if !metric.p50.is_finite() || !metric.p95.is_finite() {
                return Err(ReportValidationError::InvalidField {
                    field: "metrics.percentile",
                });
            }
        }
        if self.sizes.apk_compressed_bytes == 0 {
            return Err(ReportValidationError::MissingField {
                field: "sizes.apk_compressed_bytes",
            });
        }
        if self.sizes.abi_so_bytes.is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "sizes.abi_so_bytes",
            });
        }
        Ok(())
    }

    /// Serialize to pretty JSON after validation.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when validation or serialization fails.
    pub fn to_json(&self) -> Result<Vec<u8>, ReportValidationError> {
        self.validate()?;
        serde_json::to_vec_pretty(self).map_err(|error| ReportValidationError::Serialize {
            detail: error.to_string(),
        })
    }

    /// Render a human-readable summary suitable for CI logs.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when the report is incomplete.
    pub fn to_human_summary(&self) -> Result<String, ReportValidationError> {
        self.validate()?;
        let mut lines = Vec::new();
        lines.push("Lomo Feasibility Baseline Report".to_owned());
        lines.push(format!("schema: {}", self.schema_version));
        lines.push(format!("git: {}", self.git_revision));
        lines.push(format!(
            "toolchain: rustc={} ndk={} host={}",
            self.toolchain.rustc, self.toolchain.ndk, self.toolchain.host
        ));
        lines.push(format!(
            "device: api={} abi={} kind={}",
            self.device.api_level, self.device.abi, self.device.kind
        ));
        lines.push(format!("samples: {}", self.sample_count));
        lines.push(format!(
            "apk_compressed_bytes: {}",
            self.sizes.apk_compressed_bytes
        ));
        for (abi, bytes) in &self.sizes.abi_so_bytes {
            lines.push(format!("so[{abi}]: {bytes}"));
        }
        for metric in &self.metrics {
            lines.push(format!(
                "metric[{}]: p50={} p95={} unit={} workload={}",
                metric.name, metric.p50, metric.p95, metric.unit, metric.workload_summary
            ));
        }
        lines.push(format!("conclusion: {:?}", self.conclusion));
        for note in &self.notes {
            lines.push(format!("note: {note}"));
        }
        Ok(lines.join("\n"))
    }
}
