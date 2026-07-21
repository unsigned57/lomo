use std::path::PathBuf;

use boltffi_verify::{Language, OutputFormat, Reporter, Verifier};

use crate::cli::{CliError, Result};

pub struct VerifyOptions {
    pub path: PathBuf,
    pub json: bool,
}

pub fn run_verify(options: VerifyOptions) -> Result<bool> {
    let path = &options.path;

    if !path.exists() {
        return Err(CliError::FileNotFound(path.clone()));
    }

    let language = Language::from_path(path).ok_or_else(|| {
        CliError::UnsupportedLanguage(
            path.extension()
                .and_then(|e| e.to_str())
                .unwrap_or("unknown")
                .to_string(),
        )
    })?;

    let mut verifier =
        Verifier::for_language(language).map_err(|e| CliError::VerifyError(e.to_string()))?;

    let result = verifier
        .verify_file(path)
        .map_err(|e| CliError::VerifyError(e.to_string()))?;

    let format = if options.json {
        OutputFormat::Json
    } else {
        OutputFormat::Human
    };

    let reporter = Reporter::new(format);
    println!("{}", reporter.report(&result));

    Ok(result.is_verified())
}
