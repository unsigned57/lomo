use std::io;

use lomo_core::LomoError;

/// Fail-closed TUI composition-root errors. Missing tools are reported, never forged as success.
#[derive(Clone, Debug, Eq, PartialEq, thiserror::Error)]
pub enum TuiError {
    #[error("config: {diagnostic}")]
    Config { diagnostic: String },
    #[error("config: $XDG_RUNTIME_DIR is required for process locks")]
    MissingRuntimeDir,
    #[error("editor: set editor in lomo config, $VISUAL, or $EDITOR; vim is not assumed")]
    EditorNotConfigured,
    #[error("io: {diagnostic}")]
    Io { diagnostic: String },
    #[error("session {code}: {diagnostic}")]
    Session { code: String, diagnostic: String },
    #[error("terminal: {diagnostic}")]
    Terminal { diagnostic: String },
    #[error("clipboard: {diagnostic}")]
    Clipboard { diagnostic: String },
    #[error("player: {diagnostic}")]
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
