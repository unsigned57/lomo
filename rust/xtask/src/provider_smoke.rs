//! Stage-5 real provider smoke gate (`just sync-provider-smoke`).
//!
//! Six locked provider lines (plan3 §1 decision 14/15). Each line binds its required credential
//! environment keys to exactly one repository-owned `#[ignore]`d smoke test. Lines whose
//! credentials are absent stay `OPEN / pending_env` and the command exits non-zero, so a
//! credential-less environment can never be mistaken for GREEN.
//!
//! This command is deliberately **not** part of `just check` / `just ci`: those run without
//! credentials, and the smoke targets are `#[ignore]`d so they can never silently pass there.

use anyhow::{Result, bail};

use crate::util::{self, emit_stderr};
use crate::workspace::Workspace;

/// One locked provider line: selector, owning crate/test target, and required credential keys.
struct ProviderLine {
    /// CLI selector (`just sync-provider-smoke <selector>`).
    selector: &'static str,
    /// Cargo package owning the smoke test.
    package: &'static str,
    /// Cargo test target inside that package.
    test_target: &'static str,
    /// Fully qualified test path passed to `--exact`.
    test_name: &'static str,
    /// Environment keys that must all be present and non-blank before the line may run.
    required_env: &'static [&'static str],
}

const PROVIDER_LINES: &[ProviderLine] = &[
    ProviderLine {
        selector: "nutstore",
        package: "lomo-sync",
        test_target: "provider_smoke",
        test_name: "tests::nutstore_webdav_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_NUTSTORE_URL",
            "LOMO_SMOKE_NUTSTORE_USERNAME",
            "LOMO_SMOKE_NUTSTORE_PASSWORD",
        ],
    },
    ProviderLine {
        selector: "nextcloud",
        package: "lomo-sync",
        test_target: "provider_smoke",
        test_name: "tests::nextcloud_webdav_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_NEXTCLOUD_URL",
            "LOMO_SMOKE_NEXTCLOUD_USERNAME",
            "LOMO_SMOKE_NEXTCLOUD_PASSWORD",
        ],
    },
    ProviderLine {
        selector: "aws-s3",
        package: "lomo-sync",
        test_target: "provider_smoke",
        test_name: "tests::aws_s3_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_AWS_ENDPOINT",
            "LOMO_SMOKE_AWS_BUCKET",
            "LOMO_SMOKE_AWS_REGION",
            "LOMO_SMOKE_AWS_ACCESS_KEY_ID",
            "LOMO_SMOKE_AWS_SECRET_ACCESS_KEY",
        ],
    },
    ProviderLine {
        selector: "cloudflare-r2",
        package: "lomo-sync",
        test_target: "provider_smoke",
        test_name: "tests::cloudflare_r2_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_R2_ENDPOINT",
            "LOMO_SMOKE_R2_BUCKET",
            "LOMO_SMOKE_R2_REGION",
            "LOMO_SMOKE_R2_ACCESS_KEY_ID",
            "LOMO_SMOKE_R2_SECRET_ACCESS_KEY",
        ],
    },
    ProviderLine {
        selector: "github",
        package: "lomo-git",
        test_target: "provider_smoke",
        test_name: "tests::github_https_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_GITHUB_URL",
            "LOMO_SMOKE_GITHUB_USERNAME",
            "LOMO_SMOKE_GITHUB_TOKEN",
        ],
    },
    ProviderLine {
        selector: "gitlab",
        package: "lomo-git",
        test_target: "provider_smoke",
        test_name: "tests::gitlab_https_round_trip_publishes_verifies_and_deletes",
        required_env: &[
            "LOMO_SMOKE_GITLAB_URL",
            "LOMO_SMOKE_GITLAB_USERNAME",
            "LOMO_SMOKE_GITLAB_TOKEN",
        ],
    },
];

/// Runs the six locked provider lines, or the single selected line.
///
/// # Errors
///
/// Unknown selector, unsatisfied credentials (`pending_env`), or a real smoke failure.
pub fn run(workspace: &Workspace, selector: Option<&str>) -> Result<()> {
    let selected = select_lines(selector)?;
    let mut pending = Vec::new();
    let mut passed = Vec::new();

    for line in selected {
        let missing = missing_env(line);
        if missing.is_empty() {
            run_line(workspace, line)?;
            emit_stderr(format_args!("sync-provider-smoke: {} GREEN", line.selector));
            passed.push(line.selector);
        } else {
            emit_stderr(format_args!(
                "sync-provider-smoke: {} OPEN / pending_env (unset or blank: {})",
                line.selector,
                missing.join(", ")
            ));
            pending.push(line.selector);
        }
    }

    emit_stderr(format_args!(
        "sync-provider-smoke: {} GREEN [{}]; {} pending_env [{}]",
        passed.len(),
        passed.join(" "),
        pending.len(),
        pending.join(" ")
    ));

    if pending.is_empty() {
        Ok(())
    } else {
        bail!(
            "sync-provider-smoke is OPEN / pending_env for: {}. \
             Stage-5 provider evidence stays OPEN until every line runs with real credentials.",
            pending.join(", ")
        )
    }
}

fn select_lines(selector: Option<&str>) -> Result<Vec<&'static ProviderLine>> {
    match selector {
        None | Some("all") => Ok(PROVIDER_LINES.iter().collect()),
        Some(name) => PROVIDER_LINES
            .iter()
            .find(|line| line.selector == name)
            .map(|line| vec![line])
            .ok_or_else(|| {
                anyhow::anyhow!(
                    "unknown provider line `{name}`; expected one of: all, {}",
                    PROVIDER_LINES
                        .iter()
                        .map(|line| line.selector)
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            }),
    }
}

/// Returns the required keys that are unset or blank (blank never counts as configured).
fn missing_env(line: &ProviderLine) -> Vec<&'static str> {
    line.required_env
        .iter()
        .filter(|key| !std::env::var(key).is_ok_and(|value| !value.trim().is_empty()))
        .copied()
        .collect()
}

fn run_line(workspace: &Workspace, line: &ProviderLine) -> Result<()> {
    let mut command = util::cargo(workspace);
    command.args([
        "test",
        "-p",
        line.package,
        "--test",
        line.test_target,
        "--locked",
        "--",
        "--ignored",
        "--exact",
        line.test_name,
        "--nocapture",
    ]);
    util::run(&mut command)
}
