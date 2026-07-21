use crate::analysis::Capacity;
use crate::ir::VarId;
use crate::source::SourceSpan;

#[derive(Debug)]
pub struct Violation {
    pub kind: ViolationKind,
    pub rule_id: &'static str,
    pub span: SourceSpan,
    pub related_spans: Vec<SourceSpan>,
}

impl Violation {
    pub fn new(kind: ViolationKind, rule_id: &'static str, span: SourceSpan) -> Self {
        Self {
            kind,
            rule_id,
            span,
            related_spans: Vec::new(),
        }
    }

    pub fn with_related(mut self, span: SourceSpan) -> Self {
        self.related_spans.push(span);
        self
    }

    pub fn severity(&self) -> Severity {
        self.kind.severity()
    }

    pub fn message(&self) -> String {
        self.kind.message()
    }

    pub fn code(&self) -> &'static str {
        self.rule_id
    }
}

#[derive(Debug, Clone)]
pub enum ViolationKind {
    MemoryLeak {
        pointer: VarId,
    },

    DoubleFree {
        pointer: VarId,
    },

    FreeUnallocated {
        pointer: VarId,
    },

    UseAfterFree {
        pointer: VarId,
    },

    DoubleAllocation {
        pointer: VarId,
    },

    RetainLeak {
        handle: VarId,
    },

    DoubleRelease {
        handle: VarId,
    },

    ReleaseUnretained {
        handle: VarId,
    },

    UseAfterRelease {
        handle: VarId,
    },

    CapacityMismatch {
        expected: Capacity,
        actual: Capacity,
    },

    BufferOverflow {
        pointer: VarId,
        capacity: Capacity,
        access_size: Capacity,
    },

    UncheckedStatus {
        status: VarId,
    },

    UseBeforeStatusCheck {
        out_param: VarId,
        status: VarId,
    },

    ContractViolation {
        description: String,
    },

    InconsistentFree {
        pointer: VarId,
    },

    InconsistentRelease {
        handle: VarId,
    },

    InconsistentAllocation {
        pointer: VarId,
    },

    InconsistentRetain {
        handle: VarId,
    },
}

impl ViolationKind {
    pub fn severity(&self) -> Severity {
        match self {
            Self::UncheckedStatus { .. } => Severity::Warning,
            _ => Severity::Error,
        }
    }

    pub fn message(&self) -> String {
        match self {
            Self::MemoryLeak { pointer } => {
                format!("memory leak: pointer {} allocated but never freed", pointer)
            }
            Self::DoubleFree { pointer } => {
                format!("double free: pointer {} freed multiple times", pointer)
            }
            Self::FreeUnallocated { pointer } => {
                format!("freeing unallocated pointer: {}", pointer)
            }
            Self::UseAfterFree { pointer } => {
                format!("use after free: accessing freed pointer {}", pointer)
            }
            Self::DoubleAllocation { pointer } => {
                format!(
                    "double allocation: pointer {} allocated multiple times",
                    pointer
                )
            }
            Self::RetainLeak { handle } => {
                format!("retain leak: handle {} retained but never released", handle)
            }
            Self::DoubleRelease { handle } => {
                format!("double release: handle {} released multiple times", handle)
            }
            Self::ReleaseUnretained { handle } => {
                format!("releasing unretained handle: {}", handle)
            }
            Self::UseAfterRelease { handle } => {
                format!("use after release: accessing released handle {}", handle)
            }
            Self::CapacityMismatch { expected, actual } => {
                format!(
                    "capacity mismatch: expected {:?}, got {:?}",
                    expected, actual
                )
            }
            Self::BufferOverflow {
                pointer,
                capacity,
                access_size,
            } => {
                format!(
                    "buffer overflow: pointer {} has capacity {:?} but accessed with size {:?}",
                    pointer, capacity, access_size
                )
            }
            Self::UncheckedStatus { status } => {
                format!(
                    "unchecked status: status {} not checked before using result",
                    status
                )
            }
            Self::UseBeforeStatusCheck { out_param, status } => {
                format!(
                    "use before status check: {} used before checking status {}",
                    out_param, status
                )
            }
            Self::ContractViolation { description } => {
                format!("contract violation: {}", description)
            }
            Self::InconsistentFree { pointer } => {
                format!(
                    "inconsistent free: pointer {} freed in one branch but not the other",
                    pointer
                )
            }
            Self::InconsistentRelease { handle } => {
                format!(
                    "inconsistent release: handle {} released in one branch but not the other",
                    handle
                )
            }
            Self::InconsistentAllocation { pointer } => {
                format!(
                    "inconsistent allocation: pointer {} allocated in one branch only",
                    pointer
                )
            }
            Self::InconsistentRetain { handle } => {
                format!(
                    "inconsistent retain: handle {} retained in one branch only",
                    handle
                )
            }
        }
    }

    pub fn is_memory_error(&self) -> bool {
        matches!(
            self,
            Self::MemoryLeak { .. }
                | Self::DoubleFree { .. }
                | Self::FreeUnallocated { .. }
                | Self::UseAfterFree { .. }
                | Self::DoubleAllocation { .. }
                | Self::BufferOverflow { .. }
        )
    }

    pub fn is_refcount_error(&self) -> bool {
        matches!(
            self,
            Self::RetainLeak { .. }
                | Self::DoubleRelease { .. }
                | Self::ReleaseUnretained { .. }
                | Self::UseAfterRelease { .. }
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Severity {
    Error,
    Warning,
}

impl Severity {
    pub fn is_error(&self) -> bool {
        matches!(self, Self::Error)
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Error => "error",
            Self::Warning => "warning",
        }
    }
}

impl std::fmt::Display for Severity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.as_str())
    }
}
