/*
 * Behavior Contract:
 * - Unit under test: lomo-xtask host dependency closure verification and CLI error propagation.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: verify Linux host packages are strictly pure and free from Android/FFI dependencies,
 *   and verify failure propagation across the CLI boundary.
 *
 * Scenarios:
 * - Given valid cargo metadata without forbidden dependencies, when verified, then succeeds.
 * - Given forbidden Android/FFI dependencies on any host root, including the production Linux
 *   executor and shared application service, verification returns a purity violation; no root
 *   may be omitted from traversal.
 * - Given malformed or incomplete cargo metadata (missing resolve nodes, non-string dependency entries, missing fields),
 *   when verified, then fails closed with a descriptive error.
 * - Given a child process (such as cargo fmt) fails with non-zero exit status, when check-linux runs,
 *   then the failure is propagated outward with non-zero exit status.
 * - Given invalid CLI arguments or subcommands, when xtask CLI is invoked, then fails closed with non-zero exit status.
 *
 * Observable outcomes:
 * - `parse_and_verify_host_dependencies` returns Ok(()) for valid pure closures.
 * - `parse_and_verify_host_dependencies` returns Err with explicit reason on any violation or malformed graph.
 * - Subcommand invocation through `CARGO_BIN_EXE_lomo-xtask` and `run_cli` exits non-zero and propagates failures.
 *
 * TDD proof:
 * - Contract expectations (host packages and forbidden dependencies) are defined independently from production constants
 *   so deletions of forbidden rules cannot silently pass this gate.
 * - Missing nodes, forbidden dependencies, child command execution failures, and invalid CLI commands are proven to fail closed.
 * - Adding lomo-application to the contract matrix first fails because its forbidden JNI/native
 *   dependencies are not traversed by the former host-root set.
 *
 * Excludes:
 * - APK packaging and runtime Android execution.
 */

#[cfg(test)]
mod tests {
    use std::process::Command;

    use lomo_xtask::{parse_and_verify_host_dependencies, run_cli};

    const CONTRACT_HOST_PACKAGES: [&str; 9] = [
        "lomo-application",
        "lomo-core",
        "lomo-workspace",
        "lomo-store",
        "lomo-media",
        "lomo-platform-fs",
        "lomo-tui",
        "lomo-architecture-tests",
        "lomo-xtask",
    ];

    const CONTRACT_FORBIDDEN_DEPENDENCIES: [&str; 7] = [
        "lomo-native",
        "boltffi",
        "jni",
        "ndk",
        "ndk-sys",
        "ndk-glue",
        "android-activity",
    ];

    fn valid_metadata_json() -> String {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();

        for &pkg in &CONTRACT_HOST_PACKAGES {
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            nodes.push(format!(r#"{{"id":"{id}","dependencies":["serde 1.0.0"]}}"#));
        }

        packages.push(r#"{"id":"serde 1.0.0","name":"serde"}"#.to_owned());
        nodes.push(r#"{"id":"serde 1.0.0","dependencies":[]}"#.to_owned());

        format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        )
    }

    #[test]
    fn valid_host_closure_succeeds() {
        let json_str = valid_metadata_json();
        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => {}
            Err(err) => panic!("expected valid host closure to succeed, got: {err}"),
        }
    }

    fn metadata_with_forbidden_dependency(host: &str, forbidden: &str) -> String {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();
        let forbidden_id = format!("{forbidden} 0.1.0");
        for package in CONTRACT_HOST_PACKAGES {
            let id = format!("{package} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{package}"}}"#));
            let dependencies = if package == host {
                format!(r#""{forbidden_id}""#)
            } else {
                String::new()
            };
            nodes.push(format!(
                r#"{{"id":"{id}","dependencies":[{dependencies}]}}"#
            ));
        }
        packages.push(format!(r#"{{"id":"{forbidden_id}","name":"{forbidden}"}}"#));
        nodes.push(format!(r#"{{"id":"{forbidden_id}","dependencies":[]}}"#));
        format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        )
    }

    #[test]
    fn forbidden_dependency_is_rejected_with_violation_details() {
        for host in CONTRACT_HOST_PACKAGES {
            for forbidden in CONTRACT_FORBIDDEN_DEPENDENCIES {
                let json = metadata_with_forbidden_dependency(host, forbidden);
                let Err(error) = parse_and_verify_host_dependencies(&json) else {
                    panic!("expected {host} to reject forbidden dependency {forbidden}");
                };
                let message = error.to_string();
                assert!(
                    message.contains("forbidden Android/FFI package")
                        && message.contains(forbidden),
                    "unexpected violation: {message}"
                );
            }
        }
    }

    #[test]
    fn prefixed_boltffi_dependency_is_rejected() {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();
        let forbidden_id = "boltffi_core 0.30.1";

        for &pkg in &CONTRACT_HOST_PACKAGES {
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            if pkg == "lomo-core" {
                nodes.push(format!(
                    r#"{{"id":"{id}","dependencies":["{forbidden_id}"]}}"#
                ));
            } else {
                nodes.push(format!(r#"{{"id":"{id}","dependencies":[]}}"#));
            }
        }

        packages.push(format!(
            r#"{{"id":"{forbidden_id}","name":"boltffi_core"}}"#
        ));
        nodes.push(format!(r#"{{"id":"{forbidden_id}","dependencies":[]}}"#));

        let json_str = format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        );

        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => panic!("expected error for boltffi_core"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("forbidden Android/FFI package `boltffi_core`"),
                    "expected error mentioning boltffi_core, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn missing_resolve_node_is_rejected() {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();

        for &pkg in &CONTRACT_HOST_PACKAGES {
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            if pkg == "lomo-core" {
                nodes.push(format!(
                    r#"{{"id":"{id}","dependencies":["ghost-dep 0.1.0"]}}"#
                ));
            } else {
                nodes.push(format!(r#"{{"id":"{id}","dependencies":[]}}"#));
            }
        }
        packages.push(r#"{"id":"ghost-dep 0.1.0","name":"ghost-dep"}"#.to_owned());

        let json_str = format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        );

        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => panic!("expected error for missing resolve node"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("dependency id `ghost-dep 0.1.0` not found in resolve nodes"),
                    "expected missing resolve node error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn non_string_dependency_entry_is_rejected() {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();

        for &pkg in &CONTRACT_HOST_PACKAGES {
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            if pkg == "lomo-core" {
                nodes.push(format!(r#"{{"id":"{id}","dependencies":[12345]}}"#));
            } else {
                nodes.push(format!(r#"{{"id":"{id}","dependencies":[]}}"#));
            }
        }

        let json_str = format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        );

        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => panic!("expected error for non-string dependency entry"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("is not a string"),
                    "expected non-string dependency error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn missing_package_id_is_rejected() {
        let json_str = r#"{"packages":[{"name":"lomo-core"}],"resolve":{"nodes":[]}}"#;
        match parse_and_verify_host_dependencies(json_str) {
            Ok(()) => panic!("expected error for missing package id"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("missing string id"),
                    "expected missing string id error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn missing_resolve_node_dependencies_is_rejected() {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();

        for &pkg in &CONTRACT_HOST_PACKAGES {
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            if pkg == "lomo-core" {
                nodes.push(format!(r#"{{"id":"{id}"}}"#));
            } else {
                nodes.push(format!(r#"{{"id":"{id}","dependencies":[]}}"#));
            }
        }

        let json_str = format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        );

        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => panic!("expected error for missing dependencies array"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("missing dependencies array"),
                    "expected missing dependencies array error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn missing_host_package_is_rejected() {
        let mut packages = Vec::new();
        let mut nodes = Vec::new();

        for &pkg in &CONTRACT_HOST_PACKAGES {
            if pkg == "lomo-xtask" {
                continue;
            }
            let id = format!("{pkg} 0.1.0");
            packages.push(format!(r#"{{"id":"{id}","name":"{pkg}"}}"#));
            nodes.push(format!(r#"{{"id":"{id}","dependencies":[]}}"#));
        }

        let json_str = format!(
            r#"{{"packages":[{}],"resolve":{{"nodes":[{}]}}}}"#,
            packages.join(","),
            nodes.join(",")
        );

        match parse_and_verify_host_dependencies(&json_str) {
            Ok(()) => panic!("expected error for missing host package"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("missing required host packages in metadata: lomo-xtask"),
                    "expected missing host package error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn invalid_json_is_rejected() {
        match parse_and_verify_host_dependencies("{invalid json") {
            Ok(()) => panic!("expected error for malformed json"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("failed to parse cargo metadata JSON"),
                    "expected JSON parse error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn cli_propagates_subcommand_failure_outward() {
        use std::os::unix::fs::PermissionsExt as _;

        let temp_dir =
            std::env::temp_dir().join(format!("lomo_cargo_mock_fail_{}", std::process::id()));
        let _pre_clean: std::io::Result<()> = std::fs::remove_dir_all(&temp_dir);
        if let Err(err) = std::fs::create_dir_all(&temp_dir) {
            panic!("create temp dir for cargo mock failed: {err}");
        }

        let mock_cargo = temp_dir.join("cargo");
        let script = r#"#!/bin/sh
if [ "$1" = "fmt" ]; then
    echo "mock cargo: intentional fmt subcommand failure" >&2
    exit 42
fi
exec cargo "$@"
"#;
        if let Err(err) = std::fs::write(&mock_cargo, script) {
            panic!("write mock cargo script failed: {err}");
        }
        if let Err(err) =
            std::fs::set_permissions(&mock_cargo, std::fs::Permissions::from_mode(0o755))
        {
            panic!("set mock cargo executable failed: {err}");
        }

        let current_path = std::env::var("PATH").unwrap_or_else(|_| String::new());
        let new_path = format!("{}:{}", temp_dir.display(), current_path);

        let output = match Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("check-linux")
            .env("PATH", &new_path)
            .output()
        {
            Ok(out) => out,
            Err(err) => panic!("execute lomo-xtask binary with mock cargo failed: {err}"),
        };

        let _post_clean: std::io::Result<()> = std::fs::remove_dir_all(&temp_dir);

        assert!(
            !output.status.success(),
            "check-linux must propagate non-zero exit status when a child command fails"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert!(
            stderr.contains("intentional fmt subcommand failure")
                || stderr.contains("exit")
                || stderr.contains("failed"),
            "stderr should reflect child process failure, got: {stderr}"
        );
    }

    #[test]
    fn cli_rejects_extraneous_command_arguments() {
        let args = vec![
            "check-linux".to_string(),
            "--unexpected-argument".to_string(),
        ];
        match run_cli(&args) {
            Ok(()) => panic!("expected run_cli to fail on invalid check-linux arguments"),
            Err(err) => {
                let msg = err.to_string();
                assert!(
                    msg.contains("command does not accept arguments"),
                    "expected unexpected arguments error, got: {msg}"
                );
            }
        }
    }

    #[test]
    fn cli_binary_exits_nonzero_on_invalid_command() {
        let output = match Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("nonexistent-command-xyz")
            .output()
        {
            Ok(out) => out,
            Err(err) => panic!("execute lomo-xtask binary failed: {err}"),
        };

        assert!(
            !output.status.success(),
            "expected non-zero exit status for invalid subcommand"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert!(
            stderr.contains("unknown xtask command `nonexistent-command-xyz`"),
            "expected unknown command error in stderr, got: {stderr}"
        );
    }
}
