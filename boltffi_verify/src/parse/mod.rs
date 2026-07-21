mod language;
mod patterns;
mod swift;

pub use language::{Language, LanguageParser};
pub use patterns::FfiPatterns;
pub use swift::SwiftParser;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum ParseError {
    #[error("failed to parse source: {message}")]
    SyntaxError { message: String },

    #[error("unsupported syntax: {description}")]
    UnsupportedSyntax { description: String },

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}
