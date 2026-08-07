//! Current-state architecture locks. These tests inspect source facts only; migration evidence
//! and dated stage records are deliberately not inputs.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "architecture checks fail closed with explicit diagnostics"
)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    fn root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("root")
    }

    fn read(path: &str) -> String {
        fs::read_to_string(root().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"))
    }

    fn files_under(path: &str) -> Vec<PathBuf> {
        let output = Command::new("git")
            .args(["ls-files", "--", path])
            .current_dir(root())
            .output()
            .expect("git");
        assert!(output.status.success());
        String::from_utf8(output.stdout)
            .expect("utf8")
            .lines()
            .map(|p| root().join(p))
            .filter(|p| p.exists())
            .collect()
    }

    #[test]
    fn workspace_and_owner_crates_are_current() {
        let manifest = read("rust/Cargo.toml");
        for member in [
            "core",
            "workspace",
            "store",
            "media",
            "sync",
            "git",
            "lan",
            "native",
            "xtask",
            "architecture-tests",
        ] {
            assert!(
                manifest.contains(&format!("\"{member}\"")),
                "missing workspace member {member}"
            );
        }
        for removed in ["feasibility-device", "sync-core", "rust-bindings"] {
            assert!(
                !manifest.contains(removed),
                "legacy member remains: {removed}"
            );
        }
        for (path, name) in [
            ("rust/core/Cargo.toml", "lomo-core"),
            ("rust/workspace/Cargo.toml", "lomo-workspace"),
            ("rust/store/Cargo.toml", "lomo-store"),
            ("rust/media/Cargo.toml", "lomo-media"),
            ("rust/sync/Cargo.toml", "lomo-sync"),
            ("rust/git/Cargo.toml", "lomo-git"),
            ("rust/lan/Cargo.toml", "lomo-lan"),
            ("rust/native/Cargo.toml", "lomo-native"),
        ] {
            assert!(
                read(path).contains(&format!("name = \"{name}\"")),
                "wrong owner identity in {path}"
            );
        }
        assert!(!root().join("rust/sync-core").exists());
        assert!(!root().join("rust/feasibility-device").exists());
    }

    #[test]
    fn ownership_and_dependency_direction_are_unique() {
        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("lomo-core")
                && native.contains("lomo-workspace")
                && native.contains("lomo-store")
                && native.contains("lomo-sync")
                && native.contains("lomo-lan")
        );
        for forbidden in [
            "lomo-xtask",
            "lomo-feasibility",
            "lomo-feasibility-device",
            "lomo-sync-core",
            "uniffi",
            "jna",
        ] {
            assert!(
                !native.contains(forbidden),
                "native facade has forbidden dependency {forbidden}"
            );
        }
        let workspace = read("rust/workspace/Cargo.toml");
        for forbidden in [
            "boltffi",
            "rusqlite",
            "reqwest",
            "git2",
            "lomo-sync-core",
            "lomo-xtask",
        ] {
            assert!(
                !workspace.contains(forbidden),
                "workspace owner has forbidden dependency {forbidden}"
            );
        }
        for source in files_under("data/src") {
            let text = fs::read_to_string(&source).expect("utf8");
            assert!(
                !text.contains("use_rust_sync") && !text.contains("use_rust_store"),
                "compatibility flag in {}",
                source.display()
            );
        }
    }

    #[test]
    fn generated_outputs_are_not_git_owned() {
        let output = Command::new("git")
            .args([
                "ls-files",
                "--",
                "native-bindings/src",
                "app/jniLibs",
                "native-smoke/jniLibs",
            ])
            .current_dir(root())
            .output()
            .expect("git");
        assert!(
            output.status.success() && output.stdout.is_empty(),
            "generated outputs are tracked"
        );
        let ignore = read(".gitignore");
        for path in [
            "/native-bindings/src/",
            "/app/jniLibs/",
            "/native-smoke/jniLibs/",
        ] {
            assert!(ignore.contains(path), "missing ignore rule {path}");
        }
    }

    #[test]
    fn active_contracts_and_baselines_exist_and_are_parseable() {
        for path in ["workspace.md", "store.md", "sync.md", "lan.md", "shell.md"] {
            let text = read(&format!("fixtures/contracts/{path}"));
            assert!(
                text.contains("Capability")
                    && text.contains("Given")
                    && text.contains("When")
                    && text.contains("Then"),
                "invalid contract {path}"
            );
        }
        for path in ["sync-safe-behavior.v1.json", "performance.v1.json"] {
            let value: serde_json::Value =
                serde_json::from_str(&read(&format!("fixtures/baselines/{path}")))
                    .expect("baseline json");
            assert!(
                value.get("schema_version").is_some(),
                "baseline lacks schema_version: {path}"
            );
        }
    }

    #[test]
    fn kotlin_module_dependencies_point_inward() {
        // Truth from ARCHITECTURE.md "Kotlin modules": domain is platform-neutral, data is the
        // sole native-bindings consumer, app composes domain contracts, ui-components owns
        // presentation only. Internal references use the `//module` coordinate form.
        let allowed: &[(&str, &str, &[&str])] = &[
            ("app", "app", &["domain", "data", "ui-components"]),
            ("data", "data", &["domain", "native-bindings"]),
            ("ui-components", "ui-components", &["domain"]),
            ("domain", "domain", &[]),
            ("native-bindings", "native-bindings", &[]),
            ("native-smoke", "native-smoke", &["native-bindings"]),
            ("detekt-rules", "quality/detekt-rules", &[]),
        ];
        for (module, path, allowed_deps) in allowed {
            let text = read(&format!("{path}/module.yaml"));
            for dep in internal_module_deps(&text) {
                assert!(
                    allowed_deps.contains(&dep.as_str()),
                    "{module} depends on //{dep}, which violates ARCHITECTURE.md ownership"
                );
            }
        }
    }

    #[test]
    fn module_yaml_files_are_owned_by_the_direction_lock() {
        // Every internal dependency must be declared by a module that owns it; a module.yaml
        // without a tracked owner is an unmodeled boundary.
        let output = Command::new("git")
            .args(["ls-files", "--", "*/module.yaml"])
            .current_dir(root())
            .output()
            .expect("git");
        assert!(output.status.success());
        let files = String::from_utf8(output.stdout).expect("utf8");
        for file in files.lines() {
            let module = file
                .trim_end_matches("/module.yaml")
                .rsplit('/')
                .next()
                .expect("module dir");
            let is_owned = matches!(
                module,
                "app"
                    | "data"
                    | "ui-components"
                    | "domain"
                    | "native-bindings"
                    | "native-smoke"
                    | "detekt-rules"
            );
            assert!(
                is_owned,
                "module.yaml {file} is not covered by kotlin_module_dependencies_point_inward"
            );
        }
    }

    fn internal_module_deps(module_yaml: &str) -> Vec<String> {
        let mut deps = Vec::new();
        for line in module_yaml.lines() {
            let trimmed = line.trim_start();
            if let Some(rest) = trimmed.strip_prefix("- //") {
                let name = rest
                    .split([':', ' ', '\t'])
                    .next()
                    .expect("dependency name")
                    .to_owned();
                if !name.is_empty() {
                    deps.push(name);
                }
            }
        }
        deps.sort_unstable();
        deps.dedup();
        deps
    }

    #[test]
    fn quality_entry_is_pinned_and_legacy_routes_are_absent() {
        let justfile = read("Justfile");
        assert!(justfile.contains("RUSTUP_TOOLCHAIN") && justfile.contains("rust-toolchain.toml"));
        for path in ["quality/testing/ai-rust-test-style.md", "quality/README.md"] {
            let text = read(path);
            for forbidden in [
                "lomo-sync-core",
                "rust-bindings",
                "feasibility-device",
                "sync_v1",
            ] {
                assert!(
                    !text.contains(forbidden),
                    "legacy route {forbidden} in {path}"
                );
            }
        }
    }
}
