//! Behavior Contract:
//! Capability: one quality producer per release commit; owning layer: CI orchestration; priority: P0.
//! Scenarios:
//! - Given branch and tag pushes for one commit, when release starts, then it waits for Quality
//!   Checks rather than invoking a second copy of the compiler/test graph.
//! - Given an active matching run, when it completes successfully, then release verifies that
//!   normal checks, coverage, native packaging and aggregation actually passed.
//! - Given failed/cancelled runs, wrong commits/repositories, PR-only evidence, missing/skipped
//!   delivery jobs, malformed responses or API failure, then release cannot proceed.
//!
//! Observable outcomes: real workflow Bash exit status and its recorded API/sleep calls.
//! TDD proof: `cargo test -p lomo-xtask --test ci_release_dedup_contract --locked`;
//! RED: the release gate is still a reusable workflow invocation with no evidence-waiting step.
//! GREEN: the same command after replacing the duplicate invocation with verified run evidence.
//! Excludes: GitHub event delivery latency, runner availability and signing/packaging internals.

#[cfg(test)]
mod tests {
    use std::{fs, process::Command};

    use anyhow::{Context, Result, ensure};
    use yaml_rust2::{Yaml, YamlLoader};

    fn workflow(file: &str) -> Result<Yaml> {
        let source = fs::read_to_string(lomo_xtask::repository_root()?.join(file))?;
        YamlLoader::load_from_str(&source)?
            .into_iter()
            .next()
            .context("workflow document")
    }

    #[test]
    fn release_consumes_quality_results_without_another_workflow_call() -> Result<()> {
        let release = workflow(".github/workflows/android_release.yml")?;
        let quality = workflow(".github/workflows/architecture_checks.yml")?;
        ensure!(
            release["jobs"]["gate"]["uses"].is_badvalue(),
            "release still invokes a second complete quality graph"
        );
        ensure!(
            quality["on"]["workflow_call"].is_badvalue(),
            "quality must have one event-owned producer, not another reusable execution entry"
        );
        ensure!(release["permissions"]["actions"].as_str() == Some("read"));
        ensure!(
            release["jobs"]["release"]["needs"]
                .as_vec()
                .context("release prerequisites")?
                .contains(&Yaml::String("gate".to_owned()))
        );
        Ok(())
    }

    #[test]
    fn default_branch_produces_the_complete_evidence_a_release_requires() -> Result<()> {
        let quality = workflow(".github/workflows/architecture_checks.yml")?;
        let script = quality["jobs"]["changes"]["steps"]
            .as_vec()
            .context("changes steps")?
            .iter()
            .find(|step| step["id"].as_str() == Some("flags"))
            .context("flags step")?["run"]
            .as_str()
            .context("flags script")?;
        for branch in ["main", "trunk"] {
            let directory = tempfile::tempdir()?;
            let flags = directory.path().join("flags");
            let output = Command::new("bash")
                .args(["-euo", "pipefail", "-c", script])
                .env("GITHUB_EVENT_NAME", "push")
                .env("GITHUB_REF", format!("refs/heads/{branch}"))
                .env("DEFAULT_BRANCH", branch)
                .env("GITHUB_OUTPUT", &flags)
                .output()?;
            ensure!(
                output.status.success(),
                "{branch}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
            let actual = fs::read_to_string(flags)?;
            for flag in ["run_check", "run_rust", "run_native", "run_android"] {
                ensure!(
                    actual.lines().any(|line| line == format!("{flag}=true")),
                    "default branch omitted {flag}: {actual}"
                );
            }
        }
        Ok(())
    }

    const GH: &str = r"#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
root = Path(os.environ['FIXTURE'])
mode = os.environ['SCENARIO']
args = sys.argv[1:]
with (root / 'calls').open('a') as log:
    log.write(json.dumps(args) + '\n')
if args[0] != 'api':
    sys.exit('release attempted to start/watch another workflow instead of reading evidence')
if mode == 'api-error':
    sys.exit(17)
if mode == 'bad-json':
    print('not-json')
    sys.exit(0)
if any('/actions/workflows/architecture_checks.yml/runs' in a for a in args):
    calls = len((root / 'calls').read_text().splitlines())
    run = dict(id=42, run_number=9, head_sha='1' * 40, head_branch='main',
               head_repository=dict(full_name='unsigned57/lomo'), event='push',
               path='.github/workflows/architecture_checks.yml',
               status='completed', conclusion='success')
    if mode == 'pending' and calls == 1:
        run.update(status='in_progress', conclusion=None)
    if mode in ('failure', 'cancelled'):
        run['conclusion'] = mode
    if mode == 'wrong-sha':
        run['head_sha'] = '2' * 40
    if mode == 'foreign-repository':
        run['head_repository']['full_name'] = 'fork/lomo'
    if mode == 'feature-push':
        run['head_branch'] = 'feature'
    if mode == 'pull-request':
        run['event'] = 'pull_request'
    if mode == 'unknown-status':
        run['status'] = 'unrecognized'
    print(json.dumps(dict(workflow_runs=[] if mode == 'missing' else [run])))
elif any('/actions/runs/42/jobs' in a for a in args):
    jobs = [dict(name=n, status='completed', conclusion='success') for n in
            ('changes', 'check', 'rust-host', 'native (arm64-v8a)', 'native (armeabi-v7a)',
             'native (x86_64)', 'native (x86)', 'android-kotlin', 'quality')]
    if mode == 'partial':
        jobs = [j for j in jobs if j['name'] != 'rust-host']
    if mode == 'skipped':
        jobs[2]['conclusion'] = 'skipped'
    if mode == 'native-failure':
        jobs[3]['conclusion'] = 'failure'
    print(json.dumps([dict(jobs=jobs)]))
else:
    sys.exit('unexpected API endpoint: ' + repr(args))
";

    #[cfg(unix)]
    fn fixture() -> Result<(tempfile::TempDir, std::ffi::OsString)> {
        use std::os::unix::fs::PermissionsExt as _;
        let directory = tempfile::tempdir()?;
        let bin = directory.path().join("bin");
        fs::create_dir(&bin)?;
        for (name, source) in [("gh", GH), ("sleep", "#!/bin/sh\nexit 0\n")] {
            let file = bin.join(name);
            fs::write(&file, source)?;
            fs::set_permissions(file, fs::Permissions::from_mode(0o755))?;
        }
        let path = std::env::join_paths(std::iter::once(bin).chain(std::env::split_paths(
            &std::env::var_os("PATH").context("PATH")?,
        )))?;
        Ok((directory, path))
    }

    fn wait_script() -> Result<String> {
        let release = workflow(".github/workflows/android_release.yml")?;
        let steps = release["jobs"]["gate"]["steps"].as_vec().context(
            "release still starts another quality graph instead of waiting for evidence",
        )?;
        Ok(steps
            .iter()
            .find(|step| step["id"].as_str() == Some("quality-result"))
            .context("quality-result step")?["run"]
            .as_str()
            .context("quality wait script")?
            .to_owned())
    }

    #[cfg(unix)]
    #[test]
    fn completed_and_pending_matching_runs_are_consumed_without_new_builds() -> Result<()> {
        let script = wait_script()?;
        for mode in ["success", "pending"] {
            let (directory, path) = fixture()?;
            let output = Command::new("bash")
                .args(["-euo", "pipefail", "-c", &script])
                .env("PATH", path)
                .env("FIXTURE", directory.path())
                .env("SCENARIO", mode)
                .env("REPOSITORY", "unsigned57/lomo")
                .env("RELEASE_SHA", "1".repeat(40))
                .env("DEFAULT_BRANCH", "main")
                .env("GITHUB_OUTPUT", directory.path().join("outputs"))
                .output()?;
            ensure!(
                output.status.success(),
                "{mode}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
            let calls = fs::read_to_string(directory.path().join("calls"))?;
            ensure!(
                calls.contains("/actions/runs/42/jobs"),
                "delivery evidence was not inspected"
            );
            ensure!(calls.lines().count() == if mode == "pending" { 3 } else { 2 });
        }
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn invalid_or_incomplete_evidence_blocks_release() -> Result<()> {
        let script = wait_script()?;
        for mode in [
            "failure",
            "cancelled",
            "wrong-sha",
            "foreign-repository",
            "feature-push",
            "pull-request",
            "missing",
            "unknown-status",
            "partial",
            "skipped",
            "native-failure",
            "api-error",
            "bad-json",
        ] {
            let (directory, path) = fixture()?;
            let output = Command::new("bash")
                .args(["-euo", "pipefail", "-c", &script])
                .env("PATH", path)
                .env("FIXTURE", directory.path())
                .env("SCENARIO", mode)
                .env("REPOSITORY", "unsigned57/lomo")
                .env("RELEASE_SHA", "1".repeat(40))
                .env("DEFAULT_BRANCH", "main")
                .env("GITHUB_OUTPUT", directory.path().join("outputs"))
                .output()?;
            ensure!(
                !output.status.success(),
                "{mode} incorrectly authorized release"
            );
        }
        Ok(())
    }
}
