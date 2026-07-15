//! CLI for phase-0 corpus generation and evidence tools.

use std::env;
use std::path::PathBuf;
use std::process::ExitCode;

use lomo_feasibility::{
    CorpusMode, FeasibilityExitCode, GenerateError, GenerateRequest, generate_corpus,
};

fn main() -> ExitCode {
    match run(&env::args().skip(1).collect::<Vec<_>>()) {
        Ok(()) => exit(FeasibilityExitCode::Success),
        Err(code) => exit(code),
    }
}

fn exit(code: FeasibilityExitCode) -> ExitCode {
    let value = match code {
        FeasibilityExitCode::Success => 0_u8,
        FeasibilityExitCode::ValidationFailed => 1,
        FeasibilityExitCode::ProbeFailed => 2,
        FeasibilityExitCode::EnvironmentIncomplete => 3,
        FeasibilityExitCode::ReportIncomplete => 4,
    };
    ExitCode::from(value)
}

fn run(arguments: &[String]) -> Result<(), FeasibilityExitCode> {
    let Some((command, rest)) = arguments.split_first() else {
        print_help();
        return Ok(());
    };
    match command.as_str() {
        "generate" => generate(rest),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        unknown => {
            eprintln!("unknown command `{unknown}`");
            print_help();
            Err(FeasibilityExitCode::ValidationFailed)
        }
    }
}

fn generate(arguments: &[String]) -> Result<(), FeasibilityExitCode> {
    let mut seed = 1_u64;
    let mut mode = CorpusMode::Quick;
    let mut output = PathBuf::from("build/corpora/quick");
    let mut fixture_root = PathBuf::from("fixtures");
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--seed" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                seed = value
                    .parse()
                    .map_err(|_parse| FeasibilityExitCode::ValidationFailed)?;
                index += 2;
            }
            "--mode" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                mode = CorpusMode::parse(value).map_err(|error| map_generate_error(&error))?;
                index += 2;
            }
            "--out" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                output = PathBuf::from(value);
                index += 2;
            }
            "--fixtures" => {
                let value = arguments
                    .get(index + 1)
                    .ok_or(FeasibilityExitCode::ValidationFailed)?;
                fixture_root = PathBuf::from(value);
                index += 2;
            }
            other => {
                eprintln!("unknown generate flag `{other}`");
                return Err(FeasibilityExitCode::ValidationFailed);
            }
        }
    }

    let request = GenerateRequest {
        seed,
        mode,
        output_dir: output,
        fixture_root,
    };
    let manifest = generate_corpus(&request).map_err(|error| map_generate_error(&error))?;
    let digest = manifest
        .canonical_digest()
        .map_err(|_report| FeasibilityExitCode::ReportIncomplete)?;
    eprintln!(
        "lomo-feasibility: wrote {} (seed={}, mode={}, digest={digest})",
        request.output_dir.join("corpus-manifest.v1.json").display(),
        request.seed,
        request.mode.as_str()
    );
    Ok(())
}

fn map_generate_error(error: &GenerateError) -> FeasibilityExitCode {
    eprintln!("lomo-feasibility: {error}");
    match error {
        GenerateError::UnknownMode { .. }
        | GenerateError::PathEscapesRoot { .. }
        | GenerateError::AbsolutePath { .. }
        | GenerateError::DuplicateIdentity { .. }
        | GenerateError::Report(_) => FeasibilityExitCode::ValidationFailed,
        GenerateError::MissingFixtureRoot { .. } => FeasibilityExitCode::EnvironmentIncomplete,
        GenerateError::Io { .. } => FeasibilityExitCode::ProbeFailed,
    }
}

fn print_help() {
    eprintln!(
        "lomo-feasibility\n\nCommands:\n  generate --mode quick|scale|capacity --seed N --out DIR [--fixtures DIR]\n  help"
    );
}
