use std::fmt;
use std::io;

use lomo_core::LomoError;

/// Fail-closed TUI composition-root errors. Missing tools are reported, never forged as success.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TuiError {
    Config { diagnostic: String },
    MissingRuntimeDir,
    EditorNotConfigured,
    Io { diagnostic: String },
    Session { code: String, diagnostic: String },
    Terminal { diagnostic: String },
    Clipboard { diagnostic: String },
    Player { diagnostic: String },
}

impl TuiError {
    #[must_use]
    pub fn config(diagnostic: impl Into<String>) -> Self {
        Self::Config {
            diagnostic: diagnostic.into(),
        }
    }

    #[must_use]
    pub fn io(diagnostic: impl Into<String>) -> Self {
        Self::Io {
            diagnostic: diagnostic.into(),
        }
    }
}

impl fmt::Display for TuiError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Config { diagnostic } => write!(formatter, "config: {diagnostic}"),
            Self::MissingRuntimeDir => write!(
                formatter,
                "config: $XDG_RUNTIME_DIR is required for process locks"
            ),
            Self::EditorNotConfigured => write!(
                formatter,
                "editor: set editor in lomo config, $VISUAL, or $EDITOR; vim is not assumed"
            ),
            Self::Io { diagnostic } => write!(formatter, "io: {diagnostic}"),
            Self::Session { code, diagnostic } => {
                write!(formatter, "session {code}: {diagnostic}")
            }
            Self::Terminal { diagnostic } => write!(formatter, "terminal: {diagnostic}"),
            Self::Clipboard { diagnostic } => write!(formatter, "clipboard: {diagnostic}"),
            Self::Player { diagnostic } => write!(formatter, "player: {diagnostic}"),
        }
    }
}

impl std::error::Error for TuiError {}

impl From<LomoError> for TuiError {
    fn from(error: LomoError) -> Self {
        Self::Session {
            code: error.code().to_owned(),
            diagnostic: error.diagnostic().to_owned(),
        }
    }
}

impl From<io::Error> for TuiError {
    fn from(error: io::Error) -> Self {
        Self::io(error.to_string())
    }
}
