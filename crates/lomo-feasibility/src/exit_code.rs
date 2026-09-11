/// Unified process exit codes for phase-0 feasibility tools.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum FeasibilityExitCode {
    /// Evidence generated and validated successfully.
    Success = 0,
    /// Input or schema validation failed.
    ValidationFailed = 1,
    /// A dependency or probe failed while collecting evidence.
    ProbeFailed = 2,
    /// Required host/tool/device environment is incomplete.
    EnvironmentIncomplete = 3,
    /// A report is missing environment, workload, or unit fields.
    ReportIncomplete = 4,
}

impl FeasibilityExitCode {
    #[must_use]
    pub const fn as_i32(self) -> i32 {
        self as i32
    }
}
