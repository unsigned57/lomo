pub mod analysis;
pub mod contract;
pub mod ir;
pub mod parse;
pub mod report;
pub mod rules;
pub mod source;
pub mod verifier;

pub use analysis::{Capacity, Effect, EffectCollector, EffectEntry, EffectTrace, MemoryState};
pub use contract::{ContractLoader, FfiClass, FfiContract, FfiFunction};
pub use ir::{Expression, Statement, UnitKind, VarId, VarIdGenerator, VarName, VerifyUnit};
pub use parse::{FfiPatterns, Language, LanguageParser, ParseError, SwiftParser};
pub use report::{OutputFormat, Reporter, VerificationResult};
pub use rules::{Rule, RuleRegistry, Severity, Violation, ViolationKind};
pub use source::{
    ByteLength, ByteOffset, ColumnNumber, LineNumber, SourceFile, SourcePosition, SourceSpan,
};
pub use verifier::{Verifier, VerifyError};
