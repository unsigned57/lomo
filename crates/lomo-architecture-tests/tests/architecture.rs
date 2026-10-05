//! Current-state architecture locks. These tests inspect source facts only; migration evidence
//! and dated stage records are deliberately not inputs.
//!
//! Behavior Contract
//! Capability: enforce owner, dependency and source boundaries on the current worktree.
//! Scenarios: given new untracked source files, the same source locks apply before staging;
//! generated ignored outputs remain outside the first-party source inventory.
//! Observable outcomes: architecture violations fail with the offending source/dependency path.
//! TDD proof: `git ls-files -- crates` omitted all nine POSIX source files; including untracked,
//! nonignored paths exposes the actual owner sources without relying on staging state.
//! Excludes: migration status claims and behavioral tests owned by the runtime crates.

#[cfg(test)]
mod ffi_contracts;
#[cfg(test)]
mod policy;
#[cfg(test)]
mod policy_contracts;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "architecture checks fail closed with explicit diagnostics"
)]
mod tests {
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use crate::policy;

    fn root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("root")
    }

    #[test]
    fn ffi_surface_comes_from_explicit_native_exports() {
        let exports = policy::ffi::workspace_exports(&root()).expect("native exports must parse");
        assert!(!exports.is_empty());
        assert!(
            exports
                .iter()
                .all(|export| !export.rust.starts_with("Store::"))
        );
    }

    #[test]
    #[ignore = "requires generated bindings and full Kotlin symbol facts; the verification planner schedules this boundary explicitly"]
    fn ffi_generated_contract_has_reachable_adapters() {
        let root = root();
        let facts = std::env::var_os("LOMO_FFI_FACTS")
            .map(PathBuf::from)
            .expect("planner must provide current FFI symbol facts");
        let exports = policy::ffi::workspace_exports(&root).expect("explicit native exports");
        let (generated, graph) =
            policy::ffi::load_graph(&root, &facts).expect("complete current Kotlin symbol graph");
        let violations = policy::ffi::contract_violations(&exports, &generated, &graph);
        assert!(violations.is_empty(), "{violations:#?}");
    }

    fn read(path: &str) -> String {
        fs::read_to_string(root().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"))
    }

    fn files_under(path: &str) -> Vec<PathBuf> {
        policy::source_files(&root(), path).expect("source inventory must be complete")
    }

    /// The leading `lowercase-id` token at the start of `text` (`[a-z0-9-]+`).
    fn marker_id_prefix(text: &str) -> String {
        text.chars()
            .take_while(|c| c.is_ascii_lowercase() || *c == '-' || c.is_ascii_digit())
            .collect()
    }

    /// Collects every well-formed marker id out of a `(|id|id|)` alternation body.
    fn collect_marker_ids(declared: &mut std::collections::BTreeSet<String>, alternation: &str) {
        for id in alternation.split('|') {
            let id = id.trim();
            if !id.is_empty()
                && id
                    .chars()
                    .all(|c| c.is_ascii_lowercase() || c == '-' || c.is_ascii_digit())
            {
                declared.insert(id.to_owned());
            }
        }
    }

    #[test]
    fn workspace_and_owner_crates_are_current() {
        let manifest = read("Cargo.toml");
        for member in [
            "crates/lomo-application",
            "crates/lomo-core",
            "crates/lomo-workspace",
            "crates/lomo-store",
            "crates/lomo-media",
            "crates/lomo-platform-fs",
            "crates/lomo-sync",
            "crates/lomo-git",
            "crates/lomo-lan",
            "crates/lomo-native",
            "crates/lomo-xtask",
            "crates/lomo-architecture-tests",
            "apps/tui",
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
            ("crates/lomo-application/Cargo.toml", "lomo-application"),
            ("crates/lomo-core/Cargo.toml", "lomo-core"),
            ("crates/lomo-workspace/Cargo.toml", "lomo-workspace"),
            ("crates/lomo-store/Cargo.toml", "lomo-store"),
            ("crates/lomo-media/Cargo.toml", "lomo-media"),
            ("crates/lomo-platform-fs/Cargo.toml", "lomo-platform-fs"),
            ("crates/lomo-sync/Cargo.toml", "lomo-sync"),
            ("crates/lomo-git/Cargo.toml", "lomo-git"),
            ("crates/lomo-lan/Cargo.toml", "lomo-lan"),
            ("crates/lomo-native/Cargo.toml", "lomo-native"),
            ("apps/tui/Cargo.toml", "lomo-tui"),
        ] {
            assert!(
                read(path).contains(&format!("name = \"{name}\"")),
                "wrong owner identity in {path}"
            );
        }
        assert!(!root().join("rust").exists());
    }

    #[test]
    fn ownership_and_dependency_direction_are_unique() {
        let metadata = declared_cargo_metadata();
        let capabilities =
            policy::DependencyCapabilities::load(&root()).expect("dependency capability policy");
        let violations = policy::rust_dependency_violations(&root(), &metadata, &capabilities)
            .expect("Cargo metadata must describe every workspace owner");
        assert!(violations.is_empty(), "{violations:#?}");
        for source in files_under("apps/android/data/src") {
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
                "apps/android/native-bindings/src",
                "apps/android/app/jniLibs",
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
            "/apps/android/native-bindings/src/",
            "/apps/android/app/jniLibs/",
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
        let capabilities =
            policy::DependencyCapabilities::load(&root()).expect("dependency capability policy");
        for path in policy::KOTLIN_MODULES {
            let text = read(&format!("{path}/module.yaml"));
            let violations = policy::kotlin_dependency_violations(path, &text, &capabilities)
                .unwrap_or_else(|error| panic!("{path}: {error}"));
            assert!(violations.is_empty(), "{violations:#?}");
        }
    }

    #[test]
    fn module_yaml_files_are_owned_by_the_direction_lock() {
        // Every internal dependency must be declared by a module that owns it; a module.yaml
        // without a tracked owner is an unmodeled boundary.
        for file in files_under("apps") {
            if file.file_name().is_none_or(|name| name != "module.yaml") {
                continue;
            }
            let root = root();
            let relative = file
                .strip_prefix(&root)
                .expect("repository source")
                .to_str()
                .expect("UTF-8 module path");
            assert!(
                policy::owned_kotlin_module(relative),
                "module.yaml {relative} is not covered by kotlin_module_dependencies_point_inward"
            );
        }
    }

    #[test]
    fn android_has_no_native_smoke_composition_root() {
        assert!(
            !root().join("apps/android/native-smoke").exists(),
            "native-smoke is not a composition root"
        );
        let project = read("apps/android/project.yaml");
        assert!(
            !project.contains("native-smoke"),
            "apps/android/project.yaml must not list native-smoke"
        );
    }

    fn internal_module_deps(module_yaml: &str) -> Vec<String> {
        let mut deps: Vec<_> = policy::internal_module_dependencies(module_yaml)
            .expect("valid dependency YAML")
            .into_iter()
            .map(|dependency| dependency.name)
            .collect();
        deps.sort_unstable();
        deps.dedup();
        deps
    }

    fn declared_cargo_metadata() -> serde_json::Value {
        let output = Command::new("cargo")
            .args(["metadata", "--no-deps", "--format-version", "1", "--locked"])
            .current_dir(root())
            .output()
            .expect("cargo metadata");
        assert!(
            output.status.success(),
            "cargo metadata: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        serde_json::from_slice(&output.stdout).expect("Cargo metadata JSON")
    }

    #[test]
    fn rust_manifests_cannot_drop_workspace_lint_inheritance() {
        for file in files_under("crates")
            .into_iter()
            .chain(files_under("apps/tui"))
        {
            if file.file_name().is_none_or(|name| name != "Cargo.toml") {
                continue;
            }
            let source = fs::read_to_string(&file).expect("manifest source");
            let violations = policy::rust_manifest_violations(&file.display().to_string(), &source)
                .expect("valid manifest");
            assert!(violations.is_empty(), "{violations:#?}");
        }
    }

    #[test]
    fn rust_production_source_cannot_hide_unsafe_or_inline_tests() {
        let mut violations = Vec::new();
        for file in files_under("crates")
            .into_iter()
            .chain(files_under("apps/tui"))
        {
            if file.extension().is_none_or(|extension| extension != "rs") {
                continue;
            }
            if !file
                .components()
                .any(|component| component.as_os_str() == "src")
                && file.file_name().is_none_or(|name| name != "build.rs")
            {
                continue;
            }
            let source = fs::read_to_string(&file).expect("Rust source");
            violations.extend(
                policy::rust_source_violations(&file.display().to_string(), &source)
                    .expect("Rust policy must parse source"),
            );
        }
        assert!(violations.is_empty(), "{violations:#?}");
    }

    #[test]
    fn rust_audit_invariants_hold() {
        // Audit invariants I1/I2/I5/I7 as source policies: identities are owner-issued
        // (no literal/UUID/hash mints at call sites), failure disposition stays typed (no
        // error-text branching), boundary reads prove a byte budget, wire types enter
        // through a checked constructor, and remote transport calls stay out of loops.
        let mut violations = Vec::new();
        for file in files_under("crates")
            .into_iter()
            .chain(files_under("apps/tui"))
        {
            if file.extension().is_none_or(|extension| extension != "rs") {
                continue;
            }
            if !file
                .components()
                .any(|component| component.as_os_str() == "src")
            {
                continue;
            }
            let relative = file
                .strip_prefix(root())
                .expect("repository source")
                .to_string_lossy()
                .replace('\\', "/");
            let source = fs::read_to_string(&file).expect("Rust source");
            violations.extend(
                policy::rust_invariant_violations(&relative, &source)
                    .expect("Rust invariant policy must parse source"),
            );
        }
        assert!(violations.is_empty(), "{violations:#?}");
    }

    #[test]
    fn kotlin_single_exit_authority_holds() {
        // Audit invariant I3: a business fact has exactly one publishing/mutating exit.
        // The authority table maps call-site patterns to owning files; unknown or missing
        // owners fail closed inside the policy.
        let mut files = Vec::new();
        for file in files_under("apps/android") {
            if file.extension().is_none_or(|extension| extension != "kt") {
                continue;
            }
            let relative = file
                .strip_prefix(root())
                .expect("repository source")
                .to_string_lossy()
                .replace('\\', "/");
            if !relative.contains("/src/") {
                continue;
            }
            let source = fs::read_to_string(&file).expect("Kotlin source");
            files.push((relative, source));
        }
        let violations = policy::kotlin_authority_violations(&root(), &files)
            .expect("authority table must resolve");
        assert!(violations.is_empty(), "{violations:#?}");
    }

    #[test]
    fn detekt_rule_registry_matches_module_configs() {
        // Every rule registered in LomoArchitectureRuleSetProvider must be active in at
        // least one module config, and no config may reference an unregistered rule —
        // either drift mode silently ungates production.
        let provider =
            read("apps/android/quality/detekt-rules/src/LomoArchitectureRuleSetProvider.kt");
        let mut registered = Vec::new();
        let needle = "RuleName(\"";
        let mut rest = provider.as_str();
        while let Some(pos) = rest.find(needle) {
            rest = rest.get(pos + needle.len()..).unwrap_or("");
            let end = rest.find('"').expect("RuleName literal must terminate");
            registered.push(rest.get(..end).unwrap_or(rest).to_owned());
        }
        assert!(!registered.is_empty(), "no rules registered in provider");
        let mut active_anywhere = vec![false; registered.len()];
        for module in ["app", "data", "domain", "ui-components"] {
            let config = read(&format!("quality/detekt/config/{module}.yml"));
            let yaml = yaml_rust2::YamlLoader::load_from_str(&config)
                .expect("detekt config yaml")
                .into_iter()
                .next()
                .expect("one document");
            let section = yaml["lomo-architecture"]
                .as_hash()
                .unwrap_or_else(|| panic!("{module}: missing lomo-architecture section"));
            for (key, value) in section {
                let name = key.as_str().unwrap_or_else(|| {
                    panic!("{module}: lomo-architecture key is not a rule name")
                });
                if name == "active" {
                    continue;
                }
                let index = registered
                    .iter()
                    .position(|rule| rule == name)
                    .unwrap_or_else(|| {
                        panic!("{module}: config references unregistered rule {name}")
                    });
                let active = value["active"].as_bool().unwrap_or(false);
                if active && let Some(slot) = active_anywhere.get_mut(index) {
                    *slot = true;
                }
            }
        }
        for (rule, active) in registered.iter().zip(&active_anywhere) {
            assert!(
                active,
                "rule {rule} is registered but active in no module config"
            );
        }
    }

    #[test]
    fn policy_declarations_are_exercised_by_tests() {
        // A policy fn that no test invokes is dead enforcement surface.
        let test_sources = [
            read("crates/lomo-architecture-tests/tests/architecture.rs"),
            read("crates/lomo-architecture-tests/tests/policy_contracts.rs"),
            read("crates/lomo-architecture-tests/tests/ffi_contracts.rs"),
        ]
        .join("\n");
        for file in files_under("crates/lomo-architecture-tests/tests/policy") {
            let source = fs::read_to_string(&file).expect("policy source");
            for needle_start in source.match_indices("pub fn ") {
                let rest = source.get(needle_start.0 + 7..).unwrap_or("");
                let name: String = rest
                    .chars()
                    .take_while(|c| c.is_alphanumeric() || *c == '_')
                    .collect();
                if name == "new" {
                    continue;
                }
                assert!(
                    test_sources.contains(&format!("{name}(")),
                    "policy fn {name} ({}) is never invoked by tests",
                    file.display()
                );
            }
        }
        assert!(
            test_sources.contains("KOTLIN_MODULES"),
            "KOTLIN_MODULES export must be exercised"
        );
    }

    #[test]
    fn behavior_contract_markers_are_declared_and_reasoned() {
        // A `// behavior-contract: <id>: <reason>` opt-out is only meaningful if the id is
        // a declared gate vocabulary — inventing a marker cannot open a hole.
        let mut declared = std::collections::BTreeSet::new();
        let mut collect = |source: &str| {
            let mut rest = source;
            while let Some(pos) = rest.find("behavior-contract:") {
                rest = rest.get(pos + "behavior-contract:".len()..).unwrap_or("");
                // Skip literal regex artifacts like `\s*` between the prefix and the id.
                let mut trimmed = rest.trim_start();
                while let Some(next) = trimmed
                    .strip_prefix("\\s*")
                    .or_else(|| trimmed.strip_prefix("\\s"))
                    .or_else(|| trimmed.strip_prefix('\\'))
                {
                    trimmed = next.trim_start();
                }
                trimmed = trimmed.trim_start_matches('*');
                if let Some(inner) = trimmed.strip_prefix('(')
                    && let Some(end) = inner.find(')')
                {
                    collect_marker_ids(&mut declared, inner.get(..end).unwrap_or(""));
                    rest = inner.get(end..).unwrap_or("");
                    continue;
                }
                let id = marker_id_prefix(trimmed);
                if !id.is_empty() {
                    declared.insert(id);
                }
            }
        };
        for file in files_under("apps/android/quality/detekt-rules") {
            if file.extension().is_none_or(|extension| extension != "kt") {
                continue;
            }
            collect(&fs::read_to_string(&file).expect("rule source"));
        }
        for file in files_under("crates/lomo-architecture-tests/tests/policy") {
            let source = fs::read_to_string(&file).expect("policy source");
            // Rust marker constants: `const X_MARKER: &str = "marker-id";`
            for (pos, _) in source.match_indices("_MARKER: &str = \"") {
                let rest = source.get(pos + "_MARKER: &str = \"".len()..).unwrap_or("");
                if let Some(end) = rest.find('"') {
                    declared.insert(rest.get(..end).unwrap_or(rest).to_owned());
                }
            }
        }
        assert!(
            !declared.is_empty(),
            "no behavior-contract markers declared"
        );

        let mut violations = Vec::new();
        for root_dir in ["apps/android", "crates", "apps/tui"] {
            for file in files_under(root_dir) {
                let relative = file
                    .strip_prefix(root())
                    .expect("repository source")
                    .to_string_lossy()
                    .replace('\\', "/");
                if !relative.contains("/src/") {
                    continue;
                }
                if !matches!(
                    file.extension().and_then(|extension| extension.to_str()),
                    Some("kt" | "rs")
                ) {
                    continue;
                }
                // The rule-defining module contains marker literals (regexes/messages),
                // not marker usages.
                if relative.contains("/quality/detekt-rules/") {
                    continue;
                }
                let source = fs::read_to_string(&file).expect("source");
                let mut rest = source.as_str();
                while let Some(pos) = rest.find("behavior-contract:") {
                    rest = rest.get(pos + "behavior-contract:".len()..).unwrap_or("");
                    let trimmed = rest.trim_start();
                    let id = marker_id_prefix(trimmed);
                    let after = trimmed.get(id.len()..).unwrap_or("").trim_start();
                    let reason_missing =
                        !after.starts_with(':') || after.get(1..).unwrap_or("").trim().is_empty();
                    if id.is_empty() || reason_missing {
                        violations.push(format!("{relative}: marker '{id}' lacks ': <reason>'"));
                        continue;
                    }
                    if !declared.contains(&id) {
                        violations.push(format!(
                            "{relative}: undeclared behavior-contract marker '{id}'"
                        ));
                    }
                }
            }
        }
        assert!(violations.is_empty(), "{violations:#?}");
    }

    #[test]
    fn production_detekt_configs_keep_ownership_checks_active() {
        for module in ["app", "data", "domain", "ui-components"] {
            let source = read(&format!("quality/detekt/config/{module}.yml"));
            let violations = policy::detekt_config_violations(module, &source)
                .expect("valid Detekt policy config");
            assert!(violations.is_empty(), "{violations:#?}");
            assert!(
                !root()
                    .join(format!("apps/android/{module}/detekt-baseline.xml"))
                    .exists(),
                "architecture baselines are forbidden"
            );
        }
    }

    // Behavior Contract: dependency syntax must not weaken module ownership.
    // Scenarios: Given quoted, inline or platform-qualified YAML dependencies, when the
    // architecture lock reads them, then it sees the same internal edges as block scalars.
    // Observable outcomes: actual dependency names, without comment or string decoys.
    // TDD proof: this test fails against the former `strip_prefix("- //")` scanner.
    // Excludes: product behavior; graph-direction scenarios are in the policy contracts.
    #[test]
    fn module_dependency_syntax_cannot_hide_edges() {
        for yaml in [
            "dependencies:\n  - '//data'\n",
            "dependencies: [//data]\n",
            "dependencies@android:\n  - {\"//data\": runtime-only}\n",
        ] {
            assert_eq!(internal_module_deps(yaml), vec!["data"], "{yaml}");
        }
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

    #[test]
    fn host_dependency_closure_is_free_from_android_and_jni() {
        const HOST_PACKAGES: &[&str] = &[
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
        const FORBIDDEN: &[&str] = &[
            "lomo-native",
            "boltffi",
            "jni",
            "ndk",
            "ndk-sys",
            "ndk-glue",
            "android-activity",
        ];

        let output = Command::new("cargo")
            .args([
                "metadata",
                "--locked",
                "--format-version",
                "1",
                "--filter-platform",
                "x86_64-unknown-linux-gnu",
            ])
            .current_dir(root())
            .output()
            .expect("cargo metadata");
        assert!(output.status.success(), "cargo metadata failed");
        let meta: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("parse metadata");

        let (id_to_name, host_root_ids) = parse_architecture_packages(&meta, HOST_PACKAGES);
        let adj = parse_architecture_resolve_nodes(&meta);

        for root_id in &host_root_ids {
            let root_name = id_to_name.get(root_id).expect("root id in packages");
            let mut visited = std::collections::HashSet::new();
            let mut queue = std::collections::VecDeque::new();
            queue.push_back(root_id.clone());

            while let Some(curr) = queue.pop_front() {
                if !visited.insert(curr.clone()) {
                    continue;
                }
                let pkg_name = id_to_name.get(&curr).expect("dependency in packages");
                for &forbidden in FORBIDDEN {
                    assert!(
                        pkg_name != forbidden && !pkg_name.starts_with("boltffi_"),
                        "host package {root_name} depends on forbidden {pkg_name}"
                    );
                }
                let neighbors = adj.get(&curr).expect("dependency in resolve nodes");
                enqueue_unvisited(&mut queue, &visited, neighbors);
            }
        }
    }

    fn enqueue_unvisited(
        queue: &mut std::collections::VecDeque<String>,
        visited: &std::collections::HashSet<String>,
        neighbors: &[String],
    ) {
        for neighbor in neighbors {
            if !visited.contains(neighbor) {
                queue.push_back(neighbor.clone());
            }
        }
    }

    fn parse_architecture_packages(
        meta: &serde_json::Value,
        host_packages: &[&str],
    ) -> (std::collections::HashMap<String, String>, Vec<String>) {
        let packages = meta
            .get("packages")
            .and_then(serde_json::Value::as_array)
            .expect("packages");
        let mut id_to_name = std::collections::HashMap::new();
        let mut host_root_ids = Vec::new();
        for pkg in packages {
            let id = pkg
                .get("id")
                .and_then(serde_json::Value::as_str)
                .expect("package missing string id");
            let name = pkg
                .get("name")
                .and_then(serde_json::Value::as_str)
                .expect("package missing string name");
            id_to_name.insert(id.to_owned(), name.to_owned());
            if host_packages.contains(&name) {
                host_root_ids.push(id.to_owned());
            }
        }
        assert_eq!(
            host_root_ids.len(),
            host_packages.len(),
            "expected all host packages present"
        );
        (id_to_name, host_root_ids)
    }

    fn parse_architecture_resolve_nodes(
        meta: &serde_json::Value,
    ) -> std::collections::HashMap<String, Vec<String>> {
        let nodes = meta
            .get("resolve")
            .and_then(|r| r.get("nodes"))
            .and_then(serde_json::Value::as_array)
            .expect("nodes");
        let mut adj = std::collections::HashMap::new();
        for node in nodes {
            let id = node
                .get("id")
                .and_then(serde_json::Value::as_str)
                .expect("resolve node missing string id");
            let deps = node
                .get("dependencies")
                .and_then(serde_json::Value::as_array)
                .expect("resolve node missing dependencies array");
            let mut dep_ids = Vec::with_capacity(deps.len());
            for dep in deps {
                let dep_str = dep
                    .as_str()
                    .expect("dependency in resolve node is not a string");
                dep_ids.push(dep_str.to_owned());
            }
            adj.insert(id.to_owned(), dep_ids);
        }
        adj
    }

    #[test]
    fn ci_workflows_have_valid_root_jobs_and_new_root_ownership() {
        let text = read(".github/workflows/architecture_checks.yml");
        let mut top_level_keys = Vec::new();
        let mut in_jobs = false;
        let mut jobs = Vec::new();

        for line in text.lines() {
            if line.trim().is_empty() || line.trim_start().starts_with('#') {
                continue;
            }
            if !line.starts_with(' ') && line.ends_with(':') {
                let key = line.trim_end_matches(':').trim();
                top_level_keys.push(key.to_string());
                in_jobs = key == "jobs";
            } else if in_jobs
                && line.starts_with("  ")
                && !line.starts_with("   ")
                && line.ends_with(':')
            {
                let job_name = line.trim().trim_end_matches(':');
                jobs.push(job_name.to_string());
            }
        }

        assert!(
            top_level_keys.contains(&"jobs".to_string()),
            "jobs must be a root-level key in architecture_checks.yml"
        );
        assert_eq!(
            jobs,
            vec![
                "changes",
                "rust-host",
                "native",
                "android-kotlin",
                "quality"
            ],
            "expected root-level jobs in architecture_checks.yml"
        );
        assert!(
            !text.contains("  jobs:"),
            "jobs must not be nested under env or any other block"
        );
        assert!(
            !text.contains("- 'rust/**'"),
            "architecture_checks.yml still references legacy rust/** path"
        );
    }
}
