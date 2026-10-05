use std::{fmt, io::Write as _};

use anyhow::Result;
use serde_json::{Value, json};

/// A failed operation can retain its completed diagnostic evidence without reporting success.
#[derive(Debug)]
pub struct EvidenceError {
    pub message: String,
    pub evidence: Value,
}

impl fmt::Display for EvidenceError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.message.fmt(formatter)
    }
}

impl std::error::Error for EvidenceError {}

/// Emit one result, including command/discovery errors. Null failure data means no evidence was
/// produced; success data is command-specific. Logs and child output never belong on stdout.
pub fn finish(arguments: &[String], result: Result<Value>) -> Result<()> {
    let command = arguments.first().map_or("commands", String::as_str);
    let document = match &result {
        Ok(data) => json!({
            "schema_version": 1, "command": command, "status": "succeeded", "data": data,
        }),
        Err(error) => json!({
            "schema_version": 1, "command": command, "status": "failed",
            "data": error.downcast_ref::<EvidenceError>().map(|failure| &failure.evidence),
            "error": {
                "message": format!("{error:#}"),
                "causes": error.chain().map(ToString::to_string).collect::<Vec<_>>(),
            },
        }),
    };
    let mut stdout = std::io::stdout().lock();
    serde_json::to_writer(&mut stdout, &document)?;
    writeln!(stdout)?;
    result.map(|_| ())
}
