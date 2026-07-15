//! Behavior Contract
//!
//! Capability: keep Rust, native packaging, and repository quality orchestration on one
//! source-derived build graph.
//!
//! Scenarios:
//! - Given the Rust workspace, when its manifests are inspected, then toolchain, license,
//!   lint, facade, and tooling-crate boundaries are explicit and inherited.
//! - Given generated Kotlin/native outputs, when Git ownership is inspected, then none of
//!   those outputs is tracked.
//! - Given public developer and CI entrypoints, when repository text is inspected, then one
//!   xtask owns orchestration and exactly one NDK version is named.
//! - Given production Rust sources, when their layout is inspected, then tests are physically
//!   separate and first-party unsafe code is absent.
//! - Given version-controlled Kotlin and resource sources, when their paths are inspected, then
//!   only Amper-native roots are used and Maven-style layout declarations are absent.
//! - Given meaningful-test fixtures, when their storage paths are inspected, then fixed phase and
//!   source buckets replace mirrored temporary-repository directory trees.
//! - Given repository Markdown, when local links are inspected, then every relative target exists.
//!
//! Observable outcomes: structural test failures name the missing invariant.
//! TDD proof: the first run fails against the pre-xtask tree because it still has the old
//! sync-only facade, dual NDKs, tracked-generation workflows, multiple public gates, and
//! Maven-style Kotlin source roots and repository-mirroring fixture paths.
//! Excludes: sync v1 behavior, Kotlin domain behavior, external links, anchors, and external tool
//! execution beyond Git file ownership queries.

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    const NDK_VERSION: &str = "29.0.14206865";

    fn repository_root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("repository root exists")
    }

    fn read(relative: &str) -> String {
        fs::read_to_string(repository_root().join(relative))
            .unwrap_or_else(|error| panic!("failed to read {relative}: {error}"))
    }

    fn repository_files() -> Vec<String> {
        let root = repository_root();
        let output = Command::new("git")
            .args([
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "--",
            ])
            .current_dir(root)
            .output()
            .expect("git ls-files runs");
        assert!(output.status.success(), "git ls-files must succeed");

        let root = repository_root();
        String::from_utf8(output.stdout)
            .expect("git paths are UTF-8")
            .lines()
            .filter(|relative| root.join(relative).exists())
            .map(str::to_owned)
            .collect()
    }

    #[test]
    fn workspace_inherits_pinned_governance() {
        let manifest = read("rust/Cargo.toml");
        let toolchain = read("rust/rust-toolchain.toml");

        for required in [
            "rust-version = \"1.96\"",
            "license = \"GPL-3.0-only\"",
            "warnings = \"deny\"",
            "unsafe_code = \"deny\"",
            "unused_must_use = \"deny\"",
            "all = \"deny\"",
            "pedantic = \"deny\"",
            "nursery = \"deny\"",
            "lto = \"fat\"",
            "codegen-units = 1",
            "panic = \"abort\"",
        ] {
            assert!(
                manifest.contains(required),
                "workspace is missing {required}"
            );
        }

        for required in [
            "channel = \"1.96\"",
            "rustfmt",
            "clippy",
            "llvm-tools-preview",
            "aarch64-linux-android",
            "armv7-linux-androideabi",
            "i686-linux-android",
            "x86_64-linux-android",
        ] {
            assert!(
                toolchain.contains(required),
                "toolchain is missing {required}"
            );
        }
    }

    #[test]
    fn native_facade_is_unique_and_tooling_is_not_a_production_dependency() {
        let workspace = read("rust/Cargo.toml");
        let native = read("rust/native/Cargo.toml");
        let sync_core = read("rust/sync-core/Cargo.toml");
        let feasibility = read("rust/feasibility/Cargo.toml");

        assert!(
            workspace.contains("\"native\""),
            "native facade is not a workspace member"
        );
        assert!(
            workspace.contains("\"xtask\""),
            "xtask is not a workspace member"
        );
        assert!(
            workspace.contains("\"architecture-tests\""),
            "architecture tests are not a workspace member"
        );
        assert!(
            workspace.contains("\"feasibility\""),
            "feasibility tooling crate is not a workspace member"
        );
        assert!(
            workspace.contains("\"feasibility-device\""),
            "feasibility-device linked tooling crate is not a workspace member"
        );
        assert!(
            !repository_root().join("rust/sync-ffi").exists(),
            "old sync-ffi facade remains"
        );
        assert!(
            !repository_root().join("rust/uniffi-bindgen").exists(),
            "standalone bindgen tooling tail remains"
        );
        assert!(native.contains("name = \"lomo-native\""));
        assert!(native.contains("crate-type = [\"cdylib\", \"rlib\"]"));
        assert!(native.contains("lomo-sync-core"));
        assert!(!native.contains("lomo-xtask"));
        assert!(!native.contains("lomo-architecture-tests"));
        assert!(!native.contains("lomo-feasibility"));
        assert!(!native.contains("lomo-feasibility-device"));
        assert!(!sync_core.contains("lomo-feasibility"));
        assert!(feasibility.contains("name = \"lomo-feasibility\""));
        assert!(feasibility.contains("publish = false"));
        let feasibility_device = read("rust/feasibility-device/Cargo.toml");
        assert!(feasibility_device.contains("name = \"lomo-feasibility-device\""));
        assert!(feasibility_device.contains("publish = false"));
        assert!(feasibility_device.contains("lomo-feasibility"));
    }

    #[test]
    fn generated_bindings_and_native_libraries_are_not_git_owned() {
        let root = repository_root();
        let output = Command::new("git")
            .args(["ls-files", "--", "rust-bindings/src", "app/jniLibs"])
            .current_dir(&root)
            .output()
            .expect("git ls-files runs");
        assert!(output.status.success());
        assert!(
            output.stdout.is_empty(),
            "generated bindings/native libraries must not be tracked: {}",
            String::from_utf8_lossy(&output.stdout)
        );

        let ignore = read(".gitignore");
        assert!(ignore.contains("/rust-bindings/src/"));
        assert!(ignore.contains("/app/jniLibs/"));
    }

    #[test]
    fn kotlin_toolchain_jvms_are_confined_to_the_repository_home() {
        let util = read("rust/xtask/src/util.rs");

        assert!(
            util.contains("GRADLE_OPTS") && util.contains("-Duser.home="),
            "Kotlin and Gradle JVMs must not write through the host user.home"
        );
    }

    #[test]
    fn nested_cargo_commands_do_not_inherit_the_outer_cargo_context() {
        let util = read("rust/xtask/src/util.rs");

        assert!(
            util.contains("env_remove(\"CARGO\")")
                && util.contains("env_remove(\"CARGO_TARGET_DIR\")"),
            "xtask cargo commands must not inherit cargo run's process or target context"
        );
    }

    #[test]
    fn justfile_exposes_only_the_canonical_command_surface() {
        let justfile = read("Justfile");
        let actual = justfile
            .lines()
            .filter(|line| !line.starts_with(char::is_whitespace) && line.ends_with(':'))
            .map(|line| {
                line.trim_end_matches(':')
                    .split_whitespace()
                    .next()
                    .expect("recipe name")
            })
            .collect::<BTreeSet<_>>();
        let expected = [
            "android",
            "bootstrap",
            "cache",
            "check",
            "ci",
            "default",
            "deps",
            "device-smoke",
            "fmt",
            "native",
            "perf",
            "preflight",
            "test",
        ]
        .into_iter()
        .collect::<BTreeSet<_>>();

        assert_eq!(actual, expected, "public Just recipes drifted");
        assert!(
            justfile
                .contains("cargo run --manifest-path rust/Cargo.toml --locked -p lomo-xtask --")
        );
    }

    #[test]
    fn feasibility_probe_is_isolated_from_production_kotlin() {
        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("feasibility-probe"),
            "native crate must declare the tooling-only feasibility-probe feature"
        );
        assert!(
            native.contains("default = []"),
            "feasibility-probe must not be on the default feature set"
        );
        let app_src = repository_root().join("app/src");
        let data_src = repository_root().join("data/src");
        let domain_src = repository_root().join("domain/src");
        let ui_src = repository_root().join("ui-components/src");
        for root in [app_src, data_src, domain_src, ui_src] {
            for source in rust_sources(&root) {
                // reuse walk helper for any text file with .kt
                if source.extension().is_some_and(|ext| ext == "kt") {
                    let text = fs::read_to_string(&source).expect("kotlin source");
                    assert!(
                        !text.contains("FeasibilityProbe"),
                        "production Kotlin must not import FeasibilityProbe: {}",
                        source.display()
                    );
                }
            }
        }
        let native_src = read("rust/xtask/src/native.rs");
        assert!(
            native_src.contains("feasibility-probe") && native_src.contains("native-smoke/jniLibs"),
            "xtask must package probe-enabled native only into native-smoke jniLibs"
        );
    }

    #[test]
    fn quality_gate_gradient_and_release_ci_profile_are_wired() {
        let cargo = read("rust/Cargo.toml");
        let pre_commit = read(".githooks/pre-commit");
        let pre_push = read(".githooks/pre-push");
        let workflow = read(".github/workflows/architecture_checks.yml");
        let quality = read("rust/xtask/src/quality.rs");
        let native = read("rust/xtask/src/native.rs");

        assert!(
            cargo.contains("[profile.release-ci]"),
            "release-ci profile missing from workspace Cargo.toml"
        );
        assert!(
            cargo.contains("lto = \"thin\""),
            "release-ci must use thin LTO for PR native builds"
        );
        assert!(
            native.contains("ReleaseCi") && native.contains("release-ci"),
            "native xtask must expose the release-ci profile"
        );
        assert!(
            pre_commit.contains("fmt staged") && pre_commit.contains("check_meaningful_tests"),
            "pre-commit must stay lightweight: format + staged meaningful-test contracts"
        );
        assert!(
            !pre_commit.contains("just preflight")
                && !pre_commit.contains("just check")
                && !pre_commit.contains("just ci"),
            "pre-commit must not run preflight/check/ci (those are push/handoff gates)"
        );
        assert!(
            pre_push.contains("just check") || pre_push.contains(" just check"),
            "pre-push must run just check"
        );
        assert!(
            !pre_push.contains("just ci"),
            "pre-push must not run full just ci"
        );
        assert!(
            workflow.contains("ci-rust fast")
                && workflow.contains("ci-android fast")
                && workflow.contains("release-ci"),
            "PR workflow must use fast coverage-off gates and release-ci native builds"
        );
        assert!(
            quality.contains("pub fn preflight"),
            "path-aware preflight gate missing"
        );
    }

    #[test]
    fn legacy_orchestrators_are_deleted_and_only_one_ndk_is_named() {
        for legacy in [
            "quality/scripts/kotlin_fast_quality_check.sh",
            "quality/scripts/kotlin_static_quality_check.sh",
            "quality/scripts/kotlin_quality_check.sh",
            "quality/scripts/rust_sync_core_check.sh",
            "quality/scripts/generate_rust_sync_bindings.sh",
            "quality/scripts/generate_rust_sync_android_libs.sh",
            "quality/scripts/check_rust_sync_apk_packaging.sh",
            "quality/scripts/ai_local_maintenance_check.sh",
            "quality/scripts/verified_batch_commit.sh",
        ] {
            assert!(
                !repository_root().join(legacy).exists(),
                "legacy orchestrator remains: {legacy}"
            );
        }

        let mut named_versions = BTreeSet::new();
        collect_ndk_versions(&repository_root(), &mut named_versions);
        assert_eq!(named_versions, BTreeSet::from([NDK_VERSION.to_owned()]));
    }

    #[test]
    fn production_rust_tests_are_physically_separate_and_safe() {
        for relative in [
            "rust/sync-core/src",
            "rust/native/src",
            "rust/feasibility/src",
        ] {
            for source in rust_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Rust source is UTF-8");
                assert!(
                    !text.contains("#[cfg(test)]"),
                    "tests must be physically separate from production source: {}",
                    source.display()
                );
                assert!(
                    !text.contains("unsafe {") && !text.contains("unsafe fn"),
                    "first-party unsafe code is forbidden: {}",
                    source.display()
                );
            }
        }
    }

    #[test]
    fn kotlin_and_resource_sources_use_amper_native_layout() {
        const FORBIDDEN_PATH_SEGMENTS: [&str; 13] = [
            "/src/main/java/",
            "/src/main/kotlin/",
            "/src/test/java/",
            "/src/test/kotlin/",
            "/src/androidTest/java/",
            "/src/androidTest/kotlin/",
            "/src/main/resources/",
            "/src/com/",
            "/src/org/",
            "/src/io/",
            "/test/com/",
            "/test/org/",
            "/test/io/",
        ];

        let files = repository_files();
        let mut offenders = files
            .iter()
            .filter(|file| {
                FORBIDDEN_PATH_SEGMENTS
                    .iter()
                    .any(|segment| file.contains(segment))
            })
            .cloned()
            .collect::<Vec<_>>();

        for module in files.iter().filter(|file| file.ends_with("module.yaml")) {
            if read(module).contains("layout: maven-like") {
                offenders.push(format!("{module}: layout: maven-like"));
            }
        }

        assert!(
            offenders.is_empty(),
            "version-controlled sources must use Amper-native roots:\n{}",
            offenders.join("\n")
        );
    }

    #[test]
    fn meaningful_test_fixtures_use_canonical_buckets() {
        const PREFIX: &str = "quality/scripts/test/check_meaningful_tests_fixtures/";
        const BUCKETS: [&str; 6] = [
            "base-src",
            "base-test",
            "base-gradle",
            "head-src",
            "head-test",
            "head-gradle",
        ];

        let offenders = repository_files()
            .into_iter()
            .filter_map(|file| {
                let relative = file.strip_prefix(PREFIX)?;
                let mut components = relative.split('/');
                let _case = components.next();
                let bucket = components.next();
                if bucket.is_some_and(|candidate| BUCKETS.contains(&candidate)) {
                    None
                } else {
                    Some(file)
                }
            })
            .collect::<Vec<_>>();

        assert!(
            offenders.is_empty(),
            "meaningful-test fixtures must use canonical buckets:\n{}",
            offenders.join("\n")
        );
    }

    #[test]
    fn markdown_local_links_resolve() {
        let root = repository_root();
        let mut broken = Vec::new();

        for relative in repository_files().into_iter().filter(|file| {
            // Format golden fixtures embed intentional relative media paths as product sample
            // content; they are not documentation links.
            !file.starts_with("fixtures/")
                && Path::new(file)
                    .extension()
                    .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
        }) {
            let document = root.join(&relative);
            let text = fs::read_to_string(&document)
                .unwrap_or_else(|error| panic!("failed to read {relative}: {error}"));
            let parent = document.parent().expect("Markdown file has a parent");

            for target in markdown_link_targets(&text) {
                if target.is_empty()
                    || target.starts_with('#')
                    || target.starts_with("https://")
                    || target.starts_with("http://")
                    || target.starts_with("mailto:")
                {
                    continue;
                }

                let path = target
                    .split('#')
                    .next()
                    .expect("split always returns one item")
                    .trim_matches(['<', '>']);
                if !parent.join(path).exists() {
                    broken.push(format!("{relative} -> {target}"));
                }
            }
        }

        assert!(
            broken.is_empty(),
            "Markdown local links must resolve:\n{}",
            broken.join("\n")
        );
    }

    fn markdown_link_targets(markdown: &str) -> Vec<&str> {
        let mut targets = Vec::new();
        let mut remaining = markdown;

        while let Some(start) = remaining.find("](") {
            let after_open = &remaining[start + 2..];
            let Some(end) = after_open.find(')') else {
                break;
            };
            targets.push(after_open[..end].trim());
            remaining = &after_open[end + 1..];
        }

        targets
    }

    fn rust_sources(root: &Path) -> Vec<PathBuf> {
        let mut sources = Vec::new();
        if !root.exists() {
            return sources;
        }
        let mut pending = vec![root.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in fs::read_dir(directory).expect("source directory is readable") {
                let path = entry.expect("directory entry is readable").path();
                if path.is_dir() {
                    pending.push(path);
                } else if path.extension().is_some_and(|extension| extension == "rs") {
                    sources.push(path);
                }
            }
        }
        sources
    }

    fn collect_ndk_versions(root: &Path, versions: &mut BTreeSet<String>) {
        let ignored = [
            ".git",
            ".cache",
            ".android",
            ".gradle",
            ".home",
            ".kotlin",
            ".kotlin-cli",
            ".local",
            ".android-sdk",
            "target",
        ];
        let mut pending = vec![root.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in fs::read_dir(directory).expect("repository directory is readable") {
                let path = entry.expect("directory entry is readable").path();
                if path.is_dir() {
                    if path
                        .file_name()
                        .and_then(|name| name.to_str())
                        .is_some_and(|name| ignored.contains(&name))
                    {
                        continue;
                    }
                    pending.push(path);
                    continue;
                }
                let Ok(text) = fs::read_to_string(&path) else {
                    continue;
                };
                let rejected = ["28", "2", "13676358"].join(".");
                for version in [rejected.as_str(), NDK_VERSION] {
                    if text.contains(version) {
                        versions.insert(version.to_owned());
                    }
                }
            }
        }
    }
}
