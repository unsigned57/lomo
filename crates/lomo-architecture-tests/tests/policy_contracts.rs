//! Behavior Contract
//! Capability: architectural enforcement survives syntax variations, inactive edges and source evasion.
//! Scenarios: given aliased/optional/target/build dependencies, real package identity is checked;
//! given quoted/inline/anchored YAML, direction and dependency scope are preserved;
//! given macro/cfg lint bypasses or unstaged source, the same source policy applies;
//! given ordinary strings/comments and a legal dependency, no violation is invented.
//! Observable outcomes: structured rule IDs, offending owners/paths and parser errors.
//! TDD proof: `architecture::module_dependency_syntax_cannot_hide_edges` failed before the parser
//! replacement with `left=[]` and `right=["data"]`. Runtime rule RED/GREEN evidence is in audit-09.
//! Excludes: product transactions, platform drivers, compiler type inference and performance timing.

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::Path;
    use std::process::Command;

    use serde_json::{Value, json};

    use crate::policy::{self, Violation};

    fn checked<T>(result: Result<T, String>) -> T {
        result.unwrap_or_else(|error| panic!("invalid fixture or policy failure: {error}"))
    }

    fn rejected<T>(result: Result<T, String>) -> String {
        match result {
            Err(error) => error,
            Ok(_) => panic!("invalid policy input was accepted"),
        }
    }

    fn module_rules(module: &str, yaml: &str) -> Vec<Violation> {
        checked(policy::kotlin_dependency_violations(module, yaml))
    }

    #[test]
    fn kotlin_runtime_composition_cannot_become_a_compile_dependency() {
        for yaml in [
            "dependencies:\n  - //data\n",
            "dependencies: ['//data']",
            "dependencies@android: [{\"//data\": compile-only}]",
            "test-dependencies: [{\"//data\": exported}]",
        ] {
            let violations = module_rules("apps/android/app", yaml);
            assert_eq!(violations.len(), 1, "{yaml}");
            assert_eq!(
                violations.first().map(|violation| violation.rule),
                Some("kotlin-owner-dependency")
            );
        }
        assert!(
            module_rules(
                "apps/android/app",
                "dependencies: [{\"//data\": runtime-only}, //domain, //ui-components]"
            )
            .is_empty()
        );
    }

    #[test]
    fn binding_capability_cannot_escape_through_an_exported_dependency() {
        assert!(module_rules("apps/android/data", "dependencies: [//native-bindings]").is_empty());
        for (module, yaml) in [
            (
                "apps/android/data",
                "dependencies: [{\"//native-bindings\": exported}]",
            ),
            ("apps/android/app", "dependencies: [//native-bindings]"),
            ("apps/android/domain", "dependencies: [//data]"),
            ("apps/android/ui-components", "dependencies: [//app]"),
        ] {
            assert_eq!(module_rules(module, yaml).len(), 1, "{module}: {yaml}");
        }
    }

    #[test]
    fn aliases_and_platform_sections_do_not_hide_module_edges() {
        let yaml = "shared: &edge ['//data']\ndependencies@jvmAndAndroid: *edge\n";
        assert_eq!(module_rules("apps/android/domain", yaml).len(), 1);
        let dependencies = checked(policy::internal_module_dependencies(yaml));
        assert_eq!(
            dependencies
                .first()
                .map(|dependency| dependency.name.as_str()),
            Some("data")
        );
        assert_eq!(
            dependencies
                .first()
                .map(|dependency| dependency.section.as_str()),
            Some("dependencies@jvmAndAndroid")
        );
    }

    #[test]
    fn malformed_ambiguous_and_duplicate_yaml_is_rejected() {
        for yaml in [
            "dependencies: [",
            "dependencies: []\ndependencies: [//data]",
            "dependencies: [{\"//data\": {scope: runtime-only}}]",
            "dependencies: [{\"//data\": invented}]",
            "defaults: &defaults {dependencies: [//data]}\n<<: *defaults",
            "dependencies: []\n---\ndependencies: [//data]",
        ] {
            assert!(
                policy::internal_module_dependencies(yaml).is_err(),
                "ambiguous input admitted: {yaml}"
            );
        }
    }

    #[test]
    fn documentation_cannot_invent_an_internal_dependency() {
        let yaml =
            "description: |\n  - //data\n# dependencies: [//data]\ndependencies: [//domain]\n";
        let dependencies = checked(policy::internal_module_dependencies(yaml));
        assert_eq!(
            dependencies
                .iter()
                .map(|dependency| dependency.name.as_str())
                .collect::<Vec<_>>(),
            ["domain"]
        );
    }

    #[test]
    fn domain_external_capabilities_are_admitted_by_identity() {
        assert!(
            module_rules(
                "apps/android/domain",
                "dependencies: [org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0]"
            )
            .is_empty()
        );
        assert_eq!(
            module_rules(
                "apps/android/domain",
                "dependencies@jvm: [io.insert-koin:koin-core:4.0.0]"
            )
            .len(),
            1
        );
    }

    #[test]
    fn module_ownership_is_an_exact_path_not_a_leaf_directory_name() {
        assert!(policy::owned_kotlin_module("apps/android/data/module.yaml"));
        for path in [
            "apps/android/rogue/data/module.yaml",
            "apps/android/extra/module.yaml",
        ] {
            assert!(!policy::owned_kotlin_module(path), "{path}");
        }
        assert!(
            rejected(policy::kotlin_dependency_violations(
                "apps/android/extra",
                "dependencies: []"
            ))
            .contains("unowned Kotlin module")
        );
    }

    fn metadata(owner: &str, path: &str, dependencies: &[Value]) -> Value {
        json!({"workspace_members": ["subject"], "packages": [{
            "id": "subject", "name": owner, "manifest_path": format!("/repo/{path}/Cargo.toml"),
            "dependencies": dependencies,
        }]})
    }

    #[test]
    fn cargo_aliases_optional_edges_and_non_host_targets_cannot_admit_storage_to_core() {
        for kind in [Value::Null, json!("build")] {
            let input = metadata(
                "lomo-core",
                "crates/lomo-core",
                &[json!({
                    "name": "rusqlite", "rename": "innocent", "kind": kind,
                    "optional": true, "target": "cfg(target_os = \"android\")",
                })],
            );
            let violations = checked(policy::rust_dependency_violations(
                Path::new("/repo"),
                &input,
            ));
            assert_eq!(violations.len(), 1);
            assert_eq!(
                violations.first().map(|violation| violation.rule),
                Some("rust-owner-dependency")
            );
            assert!(
                violations
                    .iter()
                    .any(|violation| violation.detail.contains("rusqlite"))
            );
        }
    }

    #[test]
    fn driver_owners_and_test_only_support_keep_their_legal_edges() {
        for (owner, path, kind) in [
            ("lomo-store", "crates/lomo-store", Value::Null),
            ("lomo-core", "crates/lomo-core", json!("dev")),
        ] {
            let input = metadata(owner, path, &[json!({"name": "rusqlite", "kind": kind})]);
            assert!(
                checked(policy::rust_dependency_violations(
                    Path::new("/repo"),
                    &input
                ))
                .is_empty()
            );
        }
    }

    #[test]
    fn a_new_owner_or_a_spoofed_local_package_path_is_rejected() {
        let new_owner = metadata("lomo-rogue", "crates/lomo-rogue", &[]);
        let unknown = checked(policy::rust_dependency_violations(
            Path::new("/repo"),
            &new_owner,
        ));
        assert_eq!(
            unknown.first().map(|violation| violation.rule),
            Some("rust-unowned-package")
        );
        let renamed = metadata("lomo-core", "crates/pretend-core", &[]);
        assert_eq!(
            checked(policy::rust_dependency_violations(
                Path::new("/repo"),
                &renamed
            ))
            .len(),
            1
        );
        let spoofed = metadata(
            "lomo-workspace",
            "crates/lomo-workspace",
            &[json!({
                "name": "lomo-core", "kind": null, "path": "/repo/crates/unreviewed-core"
            })],
        );
        let violations = checked(policy::rust_dependency_violations(
            Path::new("/repo"),
            &spoofed,
        ));
        assert_eq!(
            violations.first().map(|violation| violation.rule),
            Some("rust-owner-path")
        );
    }

    #[test]
    fn an_incomplete_cargo_inventory_is_an_error_not_an_empty_graph() {
        for input in [
            json!({}),
            json!({"packages": [], "workspace_members": []}),
            json!({"packages": [], "workspace_members": ["absent"]}),
        ] {
            assert!(
                !rejected(policy::rust_dependency_violations(
                    Path::new("/repo"),
                    &input
                ))
                .is_empty()
            );
        }
    }

    #[test]
    fn a_manifest_comment_cannot_replace_workspace_lint_inheritance() {
        let valid = "[package]\nname = 'fixture'\n[lints]\nworkspace = true";
        assert!(checked(policy::rust_manifest_violations("Cargo.toml", valid)).is_empty());
        for source in [
            "# [lints]\n# workspace = true\n[package]\nname = 'fixture'",
            "[lints]\nworkspace = false",
            "[lints]\nworkspace = true\n[lints.rust]\nunsafe_code = 'allow'",
        ] {
            assert_eq!(
                checked(policy::rust_manifest_violations("Cargo.toml", source)).len(),
                1
            );
        }
    }

    fn source_rules(source: &str) -> Vec<Violation> {
        checked(policy::rust_source_violations(
            "crates/lomo-core/src/fixture.rs",
            source,
        ))
    }

    #[test]
    fn conditional_attributes_and_macro_templates_cannot_weaken_lints() {
        for source in [
            "#[cfg_attr(target_os = \"android\", allow(unsafe_code))] fn f() {}",
            "#[expect(unsafe_code, reason = \"generated\")] fn f() {}",
            "#[expect(clippy::all, reason = \"convenience\")] fn f() {}",
            "macro_rules! escape { () => { #[allow(unsafe_code)] fn f() {} }; }",
        ] {
            assert!(!source_rules(source).is_empty(), "{source}");
        }
    }

    #[test]
    fn first_party_unsafe_is_rejected_even_in_inactive_target_source() {
        for source in [
            "#[cfg(target_os = \"android\")] unsafe fn f() {}",
            "fn f() { unsafe { std::ptr::read(std::ptr::null::<u8>()); } }",
            "unsafe trait Driver {}",
            "unsafe impl Send for Driver {}",
            "unsafe extern \"C\" { fn boundary(); }",
            "macro_rules! escape { () => { unsafe fn boundary() {} }; }",
        ] {
            let violations = source_rules(source);
            assert!(
                violations
                    .iter()
                    .any(|violation| violation.rule == "rust-first-party-unsafe"),
                "{source}"
            );
        }
    }

    #[test]
    fn ordinary_comments_and_literals_are_not_policy_attributes() {
        let source = r##"
            // #[allow(unsafe_code)] and #[cfg(test)] are examples, not source attributes.
            const EXAMPLE: &str = r#"unsafe { } #[allow(unsafe_code)]"#;
            fn safe() { println!("unsafe #[allow(unsafe_code)]"); }
        "##;
        assert!(source_rules(source).is_empty());
        assert!(source_rules("#[expect(clippy::too_many_lines, reason = \"one bounded protocol table\")] fn f() {}").is_empty());
    }

    #[test]
    fn test_code_in_production_and_reasonless_expectations_are_reported() {
        for source in [
            "#[cfg(test)] mod tests { #[test] fn f() {} }",
            "#[cfg(any(test, feature = \"escape\"))] mod tests {}",
            "#[test] fn f() {}",
        ] {
            assert!(
                source_rules(source)
                    .iter()
                    .any(|violation| violation.rule == "rust-tests-in-production")
            );
        }
        assert!(
            source_rules("#[expect(clippy::too_many_lines, reason = \" \" )] fn f() {}")
                .iter()
                .any(|violation| violation.rule == "rust-expect-reason")
        );
        assert!(
            rejected(policy::rust_source_violations("fixture.rs", "fn broken("))
                .contains("invalid Rust syntax")
        );
    }

    #[test]
    fn current_detekt_configs_cannot_disable_or_exclude_the_hard_boundaries() {
        let source = include_str!("../../../quality/detekt/config/app.yml");
        assert!(checked(policy::detekt_config_violations("app", source)).is_empty());
        for modified in [
            source.replace(
                "NoHandwrittenNativeDeclaration:\n    active: true",
                "NoHandwrittenNativeDeclaration:\n    active: false",
            ),
            source.replace(
                "NoSourceSuppressions:\n    active: true",
                "NoSourceSuppressions:\n    active: true\n    excludes: ['**/*.kt']",
            ),
            source.replace(
                "NoSourceSuppressions:\n    active: true",
                "NoSourceSuppressions:\n    active: true\n    severity: warning",
            ),
        ] {
            assert!(!checked(policy::detekt_config_violations("app", &modified)).is_empty());
        }
    }

    #[test]
    fn unstaged_unicode_paths_are_checked_and_generated_ignored_files_are_excluded() {
        let directory = tempfile::tempdir().unwrap_or_else(|error| panic!("tempdir: {error}"));
        let root = directory.path();
        let status = Command::new("git")
            .args(["init", "--quiet"])
            .current_dir(root)
            .status()
            .unwrap_or_else(|error| panic!("git: {error}"));
        assert!(status.success());
        let source = root.join("crates/unknown/src/含 空格.rs");
        fs::create_dir_all(source.parent().unwrap_or_else(|| panic!("fixture parent")))
            .unwrap_or_else(|error| panic!("mkdir: {error}"));
        fs::write(&source, "fn f() {}\n").unwrap_or_else(|error| panic!("source: {error}"));
        fs::write(root.join(".gitignore"), "generated.rs\n")
            .unwrap_or_else(|error| panic!("ignore: {error}"));
        fs::write(root.join("crates/unknown/src/generated.rs"), "ignored")
            .unwrap_or_else(|error| panic!("generated fixture: {error}"));
        assert_eq!(checked(policy::source_files(root, "crates")), vec![source]);
    }
}
