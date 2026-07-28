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
//! - Given the stage-1 application kernel, when ownership is inspected, then `lomo-core` is the
//!   only engine owner, the native facade depends inward on it, production Kotlin imports native
//!   bindings only from `data`, `BoltFFI` is the only transport, unused optional wire codecs are
//!   disabled, and the stage-0 probe protocol is gone.
//! - Given `lomo-store` sources and manifests, when layout is inspected, then schema stays a single
//!   live version surface, Room-style migration/dao/legacy trees are absent, `rusqlite` ownership is
//!   limited to the store owner (and tooling probes), native store FFI stays conversion-only, and
//!   required external behavior-contract tests exist so fail-closed/rebuild strategy cannot silently
//!   regress into device-side migration archaeology.
//! - Given version-controlled Kotlin and resource sources, when their paths are inspected, then
//!   only Amper-native roots are used and Maven-style layout declarations are absent.
//! - Given meaningful-test fixtures, when their storage paths are inspected, then fixed phase and
//!   source buckets replace mirrored temporary-repository directory trees.
//! - Given repository Markdown, when local links are inspected, then every relative target exists.
//!
//! Observable outcomes: structural test failures name the missing invariant.
//! TDD proof: the initial architecture work failed against the pre-xtask tree; the `BoltFFI` size
//! correction also failed while its default URL/UUID codecs were still enabled without a public
//! surface consumer.
//! Excludes: sync v1 behavior, Kotlin domain behavior, external links, anchors, and external tool
//! execution beyond Git file ownership queries.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    reason = "architecture gate harness fails closed with panics on missing repository structure"
)]
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

    fn contains_identifier(text: &str, identifier: &str) -> bool {
        text.match_indices(identifier).any(|(start, _)| {
            let before = text
                .get(..start)
                .and_then(|prefix| prefix.chars().next_back());
            let end = start + identifier.len();
            let after = text.get(end..).and_then(|suffix| suffix.chars().next());
            let is_identifier_char =
                |character: char| character == '_' || character.is_alphanumeric();
            before.is_none_or(|character| !is_identifier_char(character))
                && after.is_none_or(|character| !is_identifier_char(character))
        })
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

    fn read_toolchain_channel() -> String {
        let toolchain = read("rust/rust-toolchain.toml");
        let mut in_toolchain = false;
        for line in toolchain.lines() {
            let line = line.trim();
            if line.starts_with('[') {
                in_toolchain = line == "[toolchain]";
                continue;
            }
            if !in_toolchain {
                continue;
            }
            if let Some(value) = line.strip_prefix("channel") {
                let value = value
                    .trim()
                    .trim_start_matches('=')
                    .trim()
                    .trim_matches('"');
                return value.to_owned();
            }
        }
        panic!("rust/rust-toolchain.toml missing [toolchain] channel");
    }

    fn msrv_from_channel(channel: &str) -> String {
        let mut parts = channel.split('.');
        let major = parts.next().expect("major");
        let minor = parts.next().expect("minor");
        format!("{major}.{minor}")
    }

    #[test]
    fn workspace_inherits_pinned_governance() {
        let manifest = read("rust/Cargo.toml");
        let toolchain = read("rust/rust-toolchain.toml");
        let channel = read_toolchain_channel();
        let msrv = msrv_from_channel(&channel);
        let rust_version_line = format!("rust-version = \"{msrv}\"");
        let channel_line = format!("channel = \"{channel}\"");

        assert!(
            !matches!(channel.as_str(), "stable" | "beta" | "nightly")
                && !channel.starts_with("nightly-")
                && !channel.starts_with("beta-")
                && !channel.starts_with("stable-"),
            "channel must be an exact x.y or x.y.z pin, got {channel}"
        );
        assert!(
            manifest.contains(&rust_version_line),
            "workspace rust-version must match toolchain channel msrv: expected {rust_version_line}"
        );
        assert!(
            toolchain.contains(&channel_line),
            "toolchain missing {channel_line}"
        );

        for required in [
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

        let tools_rs = read("rust/xtask/src/tools.rs");
        assert!(
            tools_rs.contains("rust_pin::load"),
            "xtask tools.rs must load the channel via rust_pin::load"
        );
        assert!(
            !tools_rs.contains("cargo_plus_toolchain().as_str()")
                || tools_rs.contains("let plus = rust.cargo_plus_toolchain()"),
            "xtask tools.rs must keep cargo +toolchain strings owned long enough for Command::args"
        );
        let rust_pin = read("rust/xtask/src/rust_pin.rs");
        assert!(
            rust_pin.contains("rust-toolchain.toml") && rust_pin.contains("pub fn bump"),
            "rust_pin must own the toolchain channel source and bump entrypoint"
        );
    }

    #[test]
    fn native_facade_is_unique_and_tooling_is_not_a_production_dependency() {
        assert_workspace_lists_native_and_tooling_members();
        assert_native_manifest_is_conversion_facade_only();
        assert_feasibility_tooling_is_non_production();
    }

    fn assert_workspace_lists_native_and_tooling_members() {
        let workspace = read("rust/Cargo.toml");
        for member in [
            "\"native\"",
            "\"xtask\"",
            "\"architecture-tests\"",
            "\"feasibility\"",
            "\"feasibility-device\"",
        ] {
            assert!(
                workspace.contains(member),
                "workspace is missing member {member}"
            );
        }
        assert!(
            !repository_root().join("rust/sync-ffi").exists(),
            "old sync-ffi facade remains"
        );
        assert!(
            !repository_root().join("rust/uniffi-bindgen").exists(),
            "standalone bindgen tooling tail remains"
        );
    }

    fn assert_native_manifest_is_conversion_facade_only() {
        let native = read("rust/native/Cargo.toml");
        assert!(native.contains("name = \"lomo-native\""));
        assert!(native.contains("crate-type = [\"staticlib\", \"rlib\"]"));
        // P5-13: lomo-sync-core is absorbed; native conversion depends on lomo-sync only.
        assert!(
            !native.contains("lomo-sync-core"),
            "post-P5-13 native must not depend on absorbed lomo-sync-core"
        );
        assert!(
            native.contains("lomo-sync") || native.contains("path = \"../sync\""),
            "post-P5-13 native must depend on lomo-sync for conversion-only FFI"
        );
        for forbidden in [
            "lomo-xtask",
            "lomo-architecture-tests",
            "lomo-feasibility",
            "lomo-feasibility-device",
        ] {
            assert!(
                !native.contains(forbidden),
                "native facade must not depend on tooling crate {forbidden}"
            );
        }
        // Post-cutover Git composition: native may depend on production lomo-git (sole git2 owner).
        // Tooling feasibility remains forbidden above.
        if stage_five_sync_cutover_complete() {
            assert!(
                native.contains("lomo-git") || native.contains("path = \"../git\""),
                "post-cutover native must depend on lomo-git for Git composition"
            );
        }
        // P5-13: lomo-sync-core directory must be gone from production tree.
        assert!(
            !repository_root().join("rust/sync-core").exists(),
            "rust/sync-core must be deleted after P5-13 absorption"
        );
        let workspace = read("rust/Cargo.toml");
        assert!(
            !workspace.contains("\"sync-core\"") && !workspace.contains("\"sync-core\","),
            "workspace must drop sync-core member after P5-13 absorption"
        );
    }

    fn assert_feasibility_tooling_is_non_production() {
        let feasibility = read("rust/feasibility/Cargo.toml");
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
            .args(["ls-files", "--", "native-bindings/src", "app/jniLibs"])
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
        assert!(ignore.contains("/native-bindings/src/"));
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
            "rust-toolchain-bump",
            "sync-provider-smoke",
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
    fn boltffi_transport_pins_and_identities_are_repository_owned() {
        let tools = read("rust/tools.toml");
        assert!(
            tools.contains("[ffi.boltffi_cli]")
                && tools.contains("package = \"boltffi_cli\"")
                && tools.contains("binary = \"boltffi\"")
                && tools.contains("git = \"https://github.com/boltffi/boltffi\"")
                && tools.contains("rev = \"a2ad920ba39179effcc9e33f13661d038f7cdeea\"")
                && tools.contains("formal_tag = \"v0.28.0\"")
                && tools.contains("required_fix_in_latest_tag = true"),
            "BoltFFI CLI must be exact-pinned to formal tag v0.28.0 tip in rust/tools.toml"
        );
        let workspace = read("rust/Cargo.toml");
        assert!(
            workspace.contains("boltffi = { path = \"boltffi-facade\" }")
                && workspace.contains("package = \"boltffi_core\"")
                && workspace.contains("rev = \"a2ad920ba39179effcc9e33f13661d038f7cdeea\"")
                && workspace.contains("default-features = false"),
            "BoltFFI must use the repo facade over exact-pinned boltffi_core with default features off"
        );
        assert!(
            repository_root()
                .join("rust/boltffi-facade/src/lib.rs")
                .exists(),
            "repository-owned boltffi facade is required so macros resolve ::boltffi::__private"
        );
        let feature_tree = Command::new("cargo")
            .args(["tree", "-p", "lomo-native", "--locked", "-e", "features"])
            .current_dir(repository_root().join("rust"))
            .output()
            .expect("cargo tree runs");
        assert!(
            feature_tree.status.success(),
            "cargo feature tree must resolve"
        );
        let feature_tree = String::from_utf8(feature_tree.stdout).expect("cargo tree is UTF-8");
        for forbidden in [
            "boltffi_core feature \"default\"",
            "boltffi_core feature \"url\"",
            "boltffi_core feature \"uuid\"",
        ] {
            assert!(
                !feature_tree.contains(forbidden),
                "unused BoltFFI codec reached the production graph: {forbidden}"
            );
        }
        let config = read("rust/native/boltffi.toml");
        for required in [
            "name = \"lomo-native\"",
            "package = \"com.lomo.nativebridge\"",
            "library_name = \"lomo_native_jni\"",
            "min_sdk = 26",
            "module_name = \"LomoNativeBridge\"",
        ] {
            assert!(config.contains(required), "boltffi.toml missing {required}");
        }
        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("boltffi") && !native.contains("uniffi"),
            "lomo-native must depend on boltffi only"
        );
        assert!(
            native.contains("crate-type = [\"staticlib\", \"rlib\"]"),
            "lomo-native must be staticlib+rlib for BoltFFI packaging"
        );
        let native_src = read("rust/xtask/src/native.rs");
        assert!(
            native_src.contains("liblomo_native_jni.so")
                && native_src.contains("canonicalize_binding")
                && !native_src.contains("uniffi_bindgen"),
            "xtask must own BoltFFI canonicalize + liblomo_native_jni.so packaging"
        );
        assert!(
            !repository_root().join("rust/native/uniffi.toml").exists(),
            "UniFFI config tail remains"
        );
        assert!(
            !repository_root().join("rust-bindings/module.yaml").exists(),
            "legacy rust-bindings module must be deleted"
        );
        assert!(
            repository_root()
                .join("native-bindings/module.yaml")
                .exists(),
            "native-bindings module is required"
        );
        let baseline = read("fixtures/baseline/ffi-transport-baseline.v1.json");
        assert!(
            baseline.contains("uniffi_baseline_frozen")
                && baseline.contains("2de4597034e0e66dcdfd34191abbe9ae7de7b31e")
                && baseline.contains("\"formal_tag\": \"v0.28.0\"")
                && baseline.contains("a2ad920ba39179effcc9e33f13661d038f7cdeea"),
            "FFI transport baseline must freeze UniFFI numbers, historical pin, and production v0.28.0 pin"
        );
    }

    #[test]
    fn stage_one_application_kernel_has_one_owner_and_no_probe_tail() {
        let workspace = read("rust/Cargo.toml");
        let core_manifest = repository_root().join("rust/core/Cargo.toml");
        assert!(
            core_manifest.exists(),
            "stage 1 requires the real lomo-core owner"
        );
        let core = read("rust/core/Cargo.toml");
        let native = read("rust/native/Cargo.toml");

        assert!(
            workspace.contains("\"core\""),
            "lomo-core is not a workspace member"
        );
        assert!(
            core.contains("name = \"lomo-core\""),
            "core crate has the wrong identity"
        );
        assert!(
            native.contains("lomo-core"),
            "native facade must depend inward on lomo-core"
        );

        for forbidden in [
            "rust/native/src/feasibility_probe.rs",
            "rust/native/tests/feasibility_probe_ffi.rs",
        ] {
            assert!(
                !repository_root().join(forbidden).exists(),
                "stage-0 probe protocol tail remains: {forbidden}"
            );
        }
        assert!(
            !native.contains("feasibility-probe"),
            "stage-0 feasibility-probe feature remains on the native facade"
        );

        for relative in ["app/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                assert!(
                    !text.contains("com.lomo.rust") && !text.contains("com.lomo.nativebridge"),
                    "only data may import generated native bindings: {}",
                    source.display()
                );
            }
        }

        for manifest in [
            "app/module.yaml",
            "domain/module.yaml",
            "ui-components/module.yaml",
        ] {
            assert!(
                !read(manifest).contains("//rust-bindings")
                    && !read(manifest).contains("//native-bindings"),
                "only data may depend on native-bindings: {manifest}"
            );
        }
    }

    #[test]
    fn stage_two_contract_and_evidence_files_exist() {
        let contract = repository_root().join("fixtures/baseline/STAGE2-CONTRACT.md");
        let evidence = repository_root().join("fixtures/baseline/STAGE2-EVIDENCE.md");
        assert!(
            contract.exists(),
            "stage 2 requires versioned fixtures/baseline/STAGE2-CONTRACT.md"
        );
        assert!(
            evidence.exists(),
            "stage 2 requires versioned fixtures/baseline/STAGE2-EVIDENCE.md"
        );

        let contract_text = read("fixtures/baseline/STAGE2-CONTRACT.md");
        for required in [
            "Capability",
            "Given",
            "When",
            "Then",
            "Observable outcomes",
            "Excludes",
            "RED",
            "GREEN",
            "${dateKey}_${timePart}_${ordinal}",
            "RenderDocumentV1",
            "pulldown-cmark",
            "0.13.4",
            "default-features",
            "API ≥ 26",
            "arm64",
            "dark-build",
            "dual-stack",
        ] {
            assert!(
                contract_text.contains(required),
                "STAGE2-CONTRACT.md is missing required lock text: {required}"
            );
        }

        let evidence_text = read("fixtures/baseline/STAGE2-EVIDENCE.md");
        for required in [
            "P2-00",
            "Markdown consumer inventory",
            "RED command",
            "GREEN command",
        ] {
            assert!(
                evidence_text.contains(required),
                "STAGE2-EVIDENCE.md is missing required evidence text: {required}"
            );
        }
    }

    #[test]
    fn stage_two_requires_lomo_workspace_owner() {
        let workspace = read("rust/Cargo.toml");
        let owner_manifest = repository_root().join("rust/workspace/Cargo.toml");
        assert!(
            owner_manifest.exists(),
            "stage 2 requires the real lomo-workspace owner crate"
        );
        let owner = read("rust/workspace/Cargo.toml");
        assert!(
            workspace.contains("\"workspace\""),
            "lomo-workspace is not a workspace member"
        );
        assert!(
            owner.contains("name = \"lomo-workspace\""),
            "workspace crate has the wrong identity"
        );
        assert!(
            owner.contains("lomo-core"),
            "lomo-workspace must depend inward on lomo-core"
        );
        assert!(
            !owner.contains("boltffi")
                && !owner.contains("rusqlite")
                && !owner.contains("reqwest")
                && !owner.contains("git2")
                && !owner.contains("lomo-sync-core")
                && !owner.contains("lomo-feasibility")
                && !owner.contains("lomo-xtask"),
            "lomo-workspace must stay free of platform, sync-wire, and tooling dependencies"
        );

        let lib = repository_root().join("rust/workspace/src/lib.rs");
        assert!(
            lib.exists(),
            "lomo-workspace must expose real production sources (not an empty marker)"
        );
        let lib_text = read("rust/workspace/src/lib.rs");
        assert!(
            !lib_text.trim().is_empty(),
            "lomo-workspace lib.rs must not be empty"
        );
        assert!(
            repository_root().join("rust/workspace/tests").exists(),
            "lomo-workspace must ship behavior tests under tests/"
        );

        // Fail-closed if public foundation / document / render types are hollowed out.
        let required_type_tokens = [
            "struct WorkspaceRelativePath",
            "struct SourceBytes",
            "struct SourceFingerprint",
            "struct ByteSpan",
            "struct MemoIdentity",
            "struct WorkspaceDocument",
            "struct RenderDocumentV1",
            "enum RenderBlock",
            "enum RenderInline",
        ];
        let mut owner_sources = String::new();
        let src_root = repository_root().join("rust/workspace/src");
        for entry in fs::read_dir(&src_root).expect("workspace src") {
            let entry = entry.expect("src entry");
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
                owner_sources.push_str(&fs::read_to_string(&path).expect("utf-8 source"));
                owner_sources.push('\n');
            }
        }
        for token in required_type_tokens {
            assert!(
                owner_sources.contains(token),
                "lomo-workspace must expose real public type surface: missing {token}"
            );
        }

        // External behavior tests must exercise the public types (not empty tests/ dir).
        let tests_root = repository_root().join("rust/workspace/tests");
        let mut test_sources = String::new();
        let mut test_file_count = 0usize;
        for entry in fs::read_dir(&tests_root).expect("workspace tests") {
            let entry = entry.expect("tests entry");
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
                test_file_count += 1;
                test_sources.push_str(&fs::read_to_string(&path).expect("utf-8 test"));
                test_sources.push('\n');
            }
        }
        assert!(
            test_file_count >= 3,
            "lomo-workspace must ship multiple external behavior test files, found {test_file_count}"
        );
        for required_use in [
            "WorkspaceRelativePath",
            "SourceBytes",
            "SourceFingerprint",
            "ByteSpan",
            "MemoIdentity",
            "WorkspaceDocument",
            "RenderDocumentV1",
        ] {
            assert!(
                test_sources.contains(required_use),
                "external tests must exercise {required_use}"
            );
        }
        assert!(
            test_sources.contains("#[test]"),
            "external tests must contain real #[test] cases"
        );
    }

    #[test]
    fn only_lomo_workspace_and_approved_tooling_may_depend_on_pulldown_cmark() {
        let root = repository_root().join("rust");
        let mut offenders = Vec::new();
        for relative in cargo_manifests_under(&root) {
            let text = fs::read_to_string(&relative).expect("Cargo.toml is UTF-8");
            if !text.contains("pulldown-cmark") {
                continue;
            }
            let package_name = package_name_from_manifest(&text).unwrap_or_else(|| {
                relative
                    .file_name()
                    .and_then(|name| name.to_str())
                    .unwrap_or("unknown")
                    .to_owned()
            });
            let allowed = matches!(
                package_name.as_str(),
                // workspace root pins the approved version for members.
                "unknown" | "lomo-workspace" | "lomo-feasibility" | "lomo-feasibility-device"
            ) || relative == repository_root().join("rust/Cargo.toml");
            if !allowed {
                offenders.push(format!(
                    "{} ({package_name})",
                    relative
                        .strip_prefix(repository_root())
                        .unwrap_or(relative.as_path())
                        .display()
                ));
            }
        }
        assert!(
            offenders.is_empty(),
            "only lomo-workspace and approved tooling may depend on pulldown-cmark:\n{}",
            offenders.join("\n")
        );

        let workspace_pin = read("rust/Cargo.toml");
        assert!(
            workspace_pin
                .contains("pulldown-cmark = { version = \"=0.13.4\", default-features = false }")
                || workspace_pin.contains(
                    "pulldown-cmark = { version = \"=0.13.4\", default-features = false,"
                ),
            "workspace must pin pulldown-cmark 0.13.4 with default-features false"
        );
    }

    #[test]
    fn stage_one_formal_exit_is_recorded_before_stage_two_green_claims() {
        let stage1 = read("fixtures/baseline/STAGE1-EVIDENCE.md");
        assert!(
            stage1.contains("P1 formal exit closed for P2 entry")
                || stage1.contains("P1 closed for P2 entry"),
            "STAGE1-EVIDENCE.md must record formal P1 exit before stage-2 GREEN claims"
        );
        assert!(
            stage1.contains("B4e"),
            "STAGE1-EVIDENCE.md must name the formal exit package (B4e)"
        );

        let stage2_evidence = repository_root().join("fixtures/baseline/STAGE2-EVIDENCE.md");
        if stage2_evidence.exists() {
            let evidence = read("fixtures/baseline/STAGE2-EVIDENCE.md");
            let claims_green =
                evidence.contains("GREEN result") || evidence.contains("GREEN command");
            if claims_green {
                assert!(
                    stage1.contains("P1 closed for P2 entry")
                        || stage1.contains("P1 formal exit closed for P2 entry"),
                    "stage-2 evidence must not claim GREEN while stage-1 formal exit is unrecorded"
                );
            }
            let forbidden_claims = [
                "production dual-stack GREEN",
                "P2-09 GREEN",
                "Status: stage 2 closed",
                "stage 2 is closed",
                "stage 2 closed for stage 3",
            ];
            for claim in forbidden_claims {
                assert!(
                    !evidence.contains(claim),
                    "stage-2 evidence must not claim production dual-stack switch or stage close early: {claim}"
                );
            }
        }
    }

    #[test]
    fn stage_two_records_production_markdown_consumer_inventory() {
        let evidence = read("fixtures/baseline/STAGE2-EVIDENCE.md");
        let contract = read("fixtures/baseline/STAGE2-CONTRACT.md");
        let surface = format!("{evidence}\n{contract}");
        if stage_two_markdown_cutover_complete() {
            // Post-cutover inventory records deleted owners + live workspace/IR surfaces.
            for required in [
                "MarkdownParser",
                "MemoTextProcessor",
                "MemoBlockLocator",
                "DELETED",
                "MarkdownWorkspaceContentProjector",
                "MemoWorkspaceProjector",
                "MarkdownIrRenderer",
                "MarkdownCleanupFormatter",
                "lomo-workspace",
            ] {
                assert!(
                    surface.contains(required),
                    "post-cutover stage-2 inventory must record ownership fact: {required}"
                );
            }
            return;
        }
        for required in [
            "MarkdownParser",
            "MemoTextProcessor",
            "MemoBlockLocator",
            "org.intellij.markdown",
            "MarkdownRenderer",
            "createModernMarkdownRenderPlan",
            "parseMarkdownSemanticDocument",
            "MarkdownCleanupFormatter",
        ] {
            assert!(
                surface.contains(required),
                "stage-2 inventory must list production Markdown consumer: {required}"
            );
        }
    }

    /// True when primary Kotlin Markdown owners are deleted and production is past dark-build.
    fn stage_two_markdown_cutover_complete() -> bool {
        let root = repository_root();
        !root.join("data/src/parser/MarkdownParser.kt").exists()
            && !root.join("data/src/util/MemoTextProcessor.kt").exists()
            && !root
                .join("ui-components/src/component/markdown/ModernMarkdownRenderPlan.kt")
                .exists()
    }

    #[test]
    fn stage_two_dark_build_must_not_wire_production_dual_stack() {
        assert_native_markdown_facade_is_conversion_only();
        assert_data_does_not_import_workspace_owner_ir();
        let memo_module = read("data/src/di/MemoRepositoryModule.kt");
        if stage_two_markdown_cutover_complete() {
            assert_post_markdown_cutover_production_di(&memo_module);
        } else {
            assert_pre_markdown_cutover_dark_build_bounds(&memo_module);
        }
    }

    fn assert_native_markdown_facade_is_conversion_only() {
        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("lomo-workspace"),
            "P2-06 requires lomo-native conversion-only dependency on lomo-workspace"
        );
        let native_lib = read("rust/native/src/lib.rs");
        for forbidden in [
            "parse_workspace_document(",
            "plan_document_patch(",
            "render_markdown_core(",
            "pulldown_cmark",
            "pulldown-cmark",
        ] {
            assert!(
                !native_lib.contains(forbidden),
                "lomo-native must stay conversion-only; found forbidden Markdown re-interpretation: {forbidden}"
            );
        }
        assert!(
            native_lib.contains("workspace::render_markdown")
                || native_lib.contains("lomo_workspace::render_markdown")
                || native_lib.contains("render_markdown(&source)"),
            "lomo-native must call the workspace owner for render conversion"
        );
    }

    fn assert_data_does_not_import_workspace_owner_ir() {
        for source in kotlin_sources(&repository_root().join("data/src")) {
            let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
            assert!(
                !text.contains("lomo_workspace"),
                "data must not import Rust workspace crate names: {}",
                source.display()
            );
            for token in ["WorkspaceDocument", "RenderDocumentV1"] {
                assert!(
                    !contains_identifier(&text, token),
                    "data must not consume Rust owner IR types ({token}): {}",
                    source.display()
                );
            }
        }
    }

    fn assert_post_markdown_cutover_production_di(memo_module: &str) {
        for forbidden in ["MarkdownParser", "MemoTextProcessor", "markdownParser"] {
            assert!(
                !memo_module.contains(forbidden),
                "post-cutover production DI must not bind legacy Markdown authority: {forbidden}"
            );
        }
        assert!(
            memo_module.contains("MarkdownWorkspaceContentProjector")
                || memo_module.contains("MarkdownWorkspaceRepository"),
            "post-cutover production DI must bind the workspace content projector/repository"
        );
        for relative in ["app/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                assert!(
                    !text.contains("lomo_workspace"),
                    "production Kotlin must not import Rust crate names: {}",
                    source.display()
                );
                assert!(
                    !contains_identifier(&text, "RenderDocumentV1"),
                    "production Kotlin must use domain IR, not Rust RenderDocumentV1: {}",
                    source.display()
                );
            }
        }
    }

    fn assert_pre_markdown_cutover_dark_build_bounds(memo_module: &str) {
        for relative in ["app/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                assert!(
                    !text.contains("RenderDocumentV1")
                        && !text.contains("lomo_workspace")
                        && !text.contains("WorkspaceDocument"),
                    "production Kotlin outside data must not consume dark-build workspace IR yet: {}",
                    source.display()
                );
            }
        }
        assert!(
            memo_module.contains("MarkdownParser") || memo_module.contains("markdownParser"),
            "production Markdown DI must still bind Kotlin MarkdownParser until P2-09"
        );
    }

    #[test]
    fn stage_two_production_markdown_owner_is_unique_after_cutover() {
        let forbidden_sources = [
            "data/src/parser/MarkdownParser.kt",
            "data/src/util/MemoTextProcessor.kt",
            "data/src/util/MemoBlockLocator.kt",
            "data/src/repository/MemoFileContentAssembler.kt",
            "domain/src/usecase/MemoContentAnalyzer.kt",
            "domain/src/usecase/ParseRemindersUseCase.kt",
            "domain/src/usecase/RewriteReminderTokenUseCase.kt",
            "ui-components/src/component/markdown/MarkdownSemanticDocumentParser.kt",
            "ui-components/src/component/markdown/MarkdownSemanticInlineParser.kt",
            "ui-components/src/component/markdown/ModernMarkdownRenderPlan.kt",
            "ui-components/src/component/markdown/MarkdownSemanticDocument.kt",
        ];
        for relative in forbidden_sources {
            assert!(
                !repository_root().join(relative).exists(),
                "legacy production Markdown owner remains after P2-09 cutover: {relative}"
            );
        }

        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                for forbidden in [
                    "org.intellij.markdown",
                    "com.mikepenz.markdown",
                    "createModernMarkdownRenderPlan",
                    "ModernMarkdownRenderPlan",
                    "ParseRemindersUseCase",
                    "RewriteReminderTokenUseCase",
                    "MemoContentAnalyzer",
                    "WIKI_IMAGE_REGEX",
                    "MD_IMAGE_REGEX",
                    "MemoFileContentAssembler",
                    "MemoBlockLocator",
                    "MemoTextProcessor",
                    "MarkdownParser(",
                    "JetBrainsMarkdownParser",
                    "parseMarkdownSemanticDocument",
                    // Residual dual-authority bans (post-P2 cleanup)
                    "MarkdownBlockParser",
                    "MarkdownInlineScanner",
                    "ShareAttachmentMarkdownRemapSession",
                    "fun canonicalToken(",
                    "linkifyBareUrls(",
                    "contentFromRawMemoSource",
                    // A-RES-013: production must not re-own memo-block segmentation for conflict write-back
                    "fun splitMemoBlocks(",
                    "private fun splitMemoBlocks(",
                    "mergeSharedTimestampMemoBlocks(",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "legacy Markdown semantic consumer `{forbidden}` remains after P2-09 cutover: {}",
                        source.display()
                    );
                }
                // Production must not re-own wiki/markdown image structure via regex literals.
                for forbidden_regex in [r"!\[\[(.*?)", r"!\[(.*?)\]\((.*?)\)"] {
                    assert!(
                        !text.contains(forbidden_regex),
                        "production Markdown image regex residual `{forbidden_regex}` remains after cutover: {}",
                        source.display()
                    );
                }
            }
        }

        let ui_manifest = read("ui-components/module.yaml");
        for forbidden in [
            "markdown-compose",
            "intellij-markdown",
            "multiplatform-markdown-renderer",
        ] {
            assert!(
                !ui_manifest.contains(forbidden),
                "legacy Markdown parser/renderer dependency remains after P2-10: {forbidden}"
            );
        }

        let memo_module = read("data/src/di/MemoRepositoryModule.kt");
        for forbidden in ["MemoTextProcessor", "MarkdownParser"] {
            assert!(
                !memo_module.contains(forbidden),
                "legacy Markdown authority remains bound in production DI: {forbidden}"
            );
        }
        assert!(
            memo_module.contains("MarkdownWorkspaceContentProjector")
                || memo_module.contains("MarkdownWorkspaceRepository"),
            "post-cutover production DI must bind workspace Markdown owner adapter"
        );
    }

    #[test]
    fn stage_three_contract_and_evidence_files_exist() {
        let contract = repository_root().join("fixtures/baseline/STAGE3-CONTRACT.md");
        let evidence = repository_root().join("fixtures/baseline/STAGE3-EVIDENCE.md");
        assert!(
            contract.exists(),
            "stage 3 requires versioned fixtures/baseline/STAGE3-CONTRACT.md"
        );
        assert!(
            evidence.exists(),
            "stage 3 requires versioned fixtures/baseline/STAGE3-EVIDENCE.md"
        );

        let contract_text = read("fixtures/baseline/STAGE3-CONTRACT.md");
        for required in [
            "Capability",
            "Given",
            "When",
            "Then",
            "Observable outcomes",
            "Excludes",
            "RED",
            "GREEN",
            "Markdown",
            ".lomo",
            "SQLite",
            "rebuildable",
            "half-success",
            "rebuild",
            "write",
            "sync",
            "Kotlin never opens SQLite",
            "UnicodeBlock",
            "CJK",
            "unigram",
            "PageCursor",
            "stale",
            "dual-stack",
            "Room",
            "dark-build",
            "AlarmManager",
            "API ≥ 26",
            "arm64",
            "lomo-store",
            "stage-2 formal exit",
        ] {
            assert!(
                contract_text.contains(required),
                "STAGE3-CONTRACT.md is missing required lock text: {required}"
            );
        }

        let evidence_text = read("fixtures/baseline/STAGE3-EVIDENCE.md");
        for required in [
            "P3-00",
            "RED command",
            "GREEN command",
            "Stage 2 closed",
            "First principles",
        ] {
            assert!(
                evidence_text.contains(required),
                "STAGE3-EVIDENCE.md is missing required evidence text: {required}"
            );
        }
    }

    #[test]
    fn stage_three_requires_lomo_store_owner() {
        let workspace = read("rust/Cargo.toml");
        let owner_manifest = repository_root().join("rust/store/Cargo.toml");
        assert!(
            owner_manifest.exists(),
            "stage 3 requires the real lomo-store owner crate"
        );
        let owner = read("rust/store/Cargo.toml");
        assert!(
            workspace.contains("\"store\""),
            "lomo-store is not a workspace member"
        );
        assert!(
            owner.contains("name = \"lomo-store\""),
            "store crate has the wrong identity"
        );
        assert!(
            owner.contains("publish = false"),
            "lomo-store must not be published"
        );
        assert!(
            owner.contains("lomo-core") || owner.contains("lomo_core"),
            "lomo-store must depend inward on lomo-core"
        );
        assert!(
            !owner.contains("boltffi")
                && !owner.contains("reqwest")
                && !owner.contains("git2")
                && !owner.contains("lomo-sync-core")
                && !owner.contains("lomo-feasibility")
                && !owner.contains("lomo-xtask")
                && !owner.contains("lomo-native"),
            "lomo-store must stay free of platform, sync-wire, facade, and tooling dependencies"
        );

        let lib = repository_root().join("rust/store/src/lib.rs");
        assert!(
            lib.exists(),
            "lomo-store must expose real production sources (not an empty marker)"
        );
        let lib_text = read("rust/store/src/lib.rs");
        assert!(
            !lib_text.trim().is_empty(),
            "lomo-store lib.rs must not be empty"
        );
        assert!(
            lib_text.contains("STORE_SCHEMA_VERSION") || lib_text.contains("StoreOwnerIdentity"),
            "lomo-store must expose owner-identity surface (schema version or StoreOwnerIdentity)"
        );

        let tests_root = repository_root().join("rust/store/tests");
        assert!(
            tests_root.exists(),
            "lomo-store must ship behavior tests under tests/"
        );

        let mut test_sources = String::new();
        let mut test_file_count = 0usize;
        for entry in fs::read_dir(&tests_root).expect("store tests") {
            let entry = entry.expect("tests entry");
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
                test_file_count += 1;
                test_sources.push_str(&fs::read_to_string(&path).expect("utf-8 test"));
                test_sources.push('\n');
            }
        }
        assert!(
            test_file_count >= 1,
            "lomo-store must ship at least one external behavior test file, found {test_file_count}"
        );
        assert!(
            test_sources.contains("#[test]"),
            "external store tests must contain real #[test] cases"
        );
        assert!(
            test_sources.contains("StoreOwnerIdentity")
                || test_sources.contains("STORE_SCHEMA_VERSION")
                || test_sources.contains("CRATE_NAME"),
            "external store tests must exercise the shipped public owner-identity surface"
        );
    }

    #[test]
    fn stage_two_formal_exit_is_recorded_before_stage_three_green_claims() {
        let stage2 = read("fixtures/baseline/STAGE2-EVIDENCE.md");
        assert!(
            stage2.contains("Stage 2 closed") || stage2.contains("stage 2 closed"),
            "STAGE2-EVIDENCE.md must record formal stage-2 exit before stage-3 GREEN claims"
        );

        let stage3_evidence = repository_root().join("fixtures/baseline/STAGE3-EVIDENCE.md");
        if stage3_evidence.exists() {
            let evidence = read("fixtures/baseline/STAGE3-EVIDENCE.md");
            let claims_green =
                evidence.contains("GREEN result") || evidence.contains("GREEN command");
            if claims_green {
                assert!(
                    stage2.contains("Stage 2 closed") || stage2.contains("stage 2 closed"),
                    "stage-3 evidence must not claim GREEN while stage-2 formal exit is unrecorded"
                );
                assert!(
                    evidence.contains("Stage 2 closed")
                        || evidence.contains("stage-2 formal exit")
                        || evidence.contains("STAGE2-EVIDENCE"),
                    "stage-3 evidence must cite stage-2 formal exit as entry prerequisite"
                );
            }
            if stage_three_store_cutover_complete() {
                // Post cutover: stage-close claims remain forbidden until P3-11 exit is recorded.
                for claim in [
                    "Status: stage 3 closed",
                    "stage 3 is closed",
                    "stage 3 closed for stage 4",
                ] {
                    assert!(
                        !evidence.contains(claim),
                        "stage-3 evidence must not claim full stage close before P3-11 exit: {claim}"
                    );
                }
            } else {
                let forbidden_claims = [
                    "production dual-stack GREEN",
                    "P3-10 GREEN",
                    "Status: stage 3 closed",
                    "stage 3 is closed",
                    "stage 3 closed for stage 4",
                    "Room tail deletion GREEN",
                ];
                for claim in forbidden_claims {
                    assert!(
                        !evidence.contains(claim),
                        "stage-3 evidence must not claim production dual-stack switch, Room tail deletion, or stage close early: {claim}"
                    );
                }
            }
        }
    }

    /// True when Room production owners are deleted and DI binds store adapters (P3-10).
    fn stage_three_store_cutover_complete() -> bool {
        let root = repository_root();
        !root.join("data/src/local/MemoDatabase.kt").exists()
            && !root.join("data/src/util/SearchTokenizer.kt").exists()
            && !root
                .join("data/src/repository/MemoQueryRepositoryImpl.kt")
                .exists()
            && {
                let memo_module = read("data/src/di/MemoRepositoryModule.kt");
                memo_module.contains("StoreMemoQueryRepository")
                    || memo_module.contains("StorePort")
                    || memo_module.contains("BoltFfiStorePort")
            }
    }

    #[test]
    fn stage_three_dark_build_must_not_wire_production_dual_stack() {
        // Conversion-only native facade is required before and after cutover.
        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("lomo-store") || native.contains("path = \"../store\""),
            "P3-09+ requires lomo-native conversion-only dependency on lomo-store"
        );
        let native_lib = read("rust/native/src/lib.rs");
        assert!(
            native_lib.contains("query_memos")
                && native_lib.contains("get_memo")
                && native_lib.contains("apply_memo_command")
                && native_lib.contains("query_reminder_plan")
                && native_lib.contains("apply_reminder_command")
                && native_lib.contains("start_rebuild"),
            "store/reminder/rebuild methods required on the BoltFFI LomoEngine surface"
        );

        // Production Kotlin must not import Rust store crate names or dual-write feature flags.
        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                assert!(
                    !text.contains("lomo_store") && !text.contains("lomo-store"),
                    "production Kotlin must not import Rust store crate names: {}",
                    source.display()
                );
                for forbidden in [
                    "use_rust_store",
                    "USE_RUST_STORE",
                    "dualWriteStore",
                    "dual_write_store",
                    "RoomAndRustStore",
                    "rustStoreEnabled",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "production dual-stack / feature-flag store path forbidden: {forbidden} in {}",
                        source.display()
                    );
                }
            }
        }

        let database_module = read("data/src/di/DatabaseModule.kt");
        let memo_module = read("data/src/di/MemoRepositoryModule.kt");
        if stage_three_store_cutover_complete() {
            // After P3-10: sole production owner is Rust store via data adapters.
            for forbidden in [
                "Room.databaseBuilder",
                "androidx.room3",
                "MemoDatabase",
                "MemoQueryRepositoryImpl",
            ] {
                assert!(
                    !database_module.contains(forbidden) && !memo_module.contains(forbidden),
                    "post-cutover production DI must not bind Room residual: {forbidden}"
                );
            }
            assert!(
                memo_module.contains("StoreMemoQueryRepository")
                    || memo_module.contains("StorePort")
                    || memo_module.contains("BoltFfiStorePort"),
                "post-cutover production DI must bind store port / store memo repositories"
            );
            return;
        }

        // Pre-cutover dark-build: Room remains sole live production persistence.
        assert!(
            database_module.contains("Room.databaseBuilder")
                || database_module.contains("androidx.room3.Room"),
            "production DatabaseModule must still bind Room until P3-10 cutover"
        );
        assert!(
            !memo_module.contains("lomo_store")
                && !memo_module.contains("LomoStore")
                && !memo_module.contains("RustStore"),
            "production MemoRepositoryModule must not bind Rust store authority during dark-build"
        );
        assert!(
            memo_module.contains("MemoQueryRepositoryImpl")
                || database_module.contains("MemoDatabase"),
            "production query/persistence path must remain Room-backed until P3-10"
        );
    }

    #[test]
    fn stage_three_production_store_owner_is_unique_after_cutover() {
        if !stage_three_store_cutover_complete() {
            return;
        }

        let forbidden_sources = [
            "data/src/local/MemoDatabase.kt",
            "data/src/util/SearchTokenizer.kt",
            "data/src/util/IndexedTextLines.kt",
            "data/src/repository/MemoQueryRepositoryImpl.kt",
            "data/src/repository/MemoSearchRepositoryImpl.kt",
            "data/src/repository/MemoFtsQueryBuilder.kt",
            "data/src/repository/MemoVersionJournal.kt",
            "data/src/repository/MemoSynchronizer.kt",
            "data/src/engine/store/DarkBuildStorePort.kt",
        ];
        for relative in forbidden_sources {
            assert!(
                !repository_root().join(relative).exists(),
                "legacy Room/local-data owner remains after P3-10 cutover: {relative}"
            );
        }

        let data_manifest = read("data/module.yaml");
        for forbidden in [
            "androidx.room3",
            "room3-runtime",
            "room3-paging",
            "room3-compiler",
            "sqlite-bundled",
        ] {
            assert!(
                !data_manifest.contains(forbidden),
                "Room/SQLite Android dependency remains after P3-10: {forbidden}"
            );
        }

        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                for forbidden in [
                    "androidx.room3",
                    "Room.databaseBuilder",
                    "SearchTokenizer",
                    "MemoFtsQueryBuilder",
                    "MemoQueryRepositoryImpl",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "Room/tokenizer residual `{forbidden}` remains after P3-10 cutover: {}",
                        source.display()
                    );
                }
            }
        }
        // JVM UnicodeBlock must not re-own search tokenization in data (layout helpers in
        // ui-components may still use UnicodeBlock for presentation line-break/script rules).
        for source in kotlin_sources(&repository_root().join("data/src")) {
            let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
            assert!(
                !text.contains("Character.UnicodeBlock") && !text.contains("SearchTokenizer"),
                "data must not re-own JVM tokenizer authority after P3-10: {}",
                source.display()
            );
        }

        let memo_module = read("data/src/di/MemoRepositoryModule.kt");
        assert!(
            memo_module.contains("StoreMemoQueryRepository")
                || memo_module.contains("BoltFfiStorePort")
                || memo_module.contains("StorePort"),
            "post-cutover production DI must bind store owner adapter"
        );
    }

    #[test]
    fn stage_four_contract_and_evidence_files_exist() {
        let contract = repository_root().join("fixtures/baseline/STAGE4-CONTRACT.md");
        let evidence = repository_root().join("fixtures/baseline/STAGE4-EVIDENCE.md");
        assert!(
            contract.exists(),
            "stage 4 requires versioned fixtures/baseline/STAGE4-CONTRACT.md"
        );
        assert!(
            evidence.exists(),
            "stage 4 requires versioned fixtures/baseline/STAGE4-EVIDENCE.md"
        );

        let contract_text = read("fixtures/baseline/STAGE4-CONTRACT.md");
        for required in [
            "Capability",
            "Given",
            "When",
            "Then",
            "Observable outcomes",
            "Excludes",
            "RED",
            "GREEN",
            "lomo-media",
            "digest",
            "sha256",
            "magic",
            "stage",
            "orphan",
            "media-trash",
            "ArchiveManifestV2",
            "zip-slip",
            "dual-stack",
            "dark-build",
            "API ≥ 26",
            "arm64",
            "operation-id",
            "no full media bytes",
            "stage-3 store cutover",
        ] {
            assert!(
                contract_text.contains(required),
                "STAGE4-CONTRACT.md is missing required lock text: {required}"
            );
        }

        let evidence_text = read("fixtures/baseline/STAGE4-EVIDENCE.md");
        for required in [
            "P4-00",
            "RED command",
            "GREEN command",
            "First principles",
            "pending_env",
            "P3-10",
        ] {
            assert!(
                evidence_text.contains(required),
                "STAGE4-EVIDENCE.md is missing required evidence text: {required}"
            );
        }
    }

    #[test]
    fn stage_four_requires_lomo_media_owner() {
        let workspace = read("rust/Cargo.toml");
        let owner_manifest = repository_root().join("rust/media/Cargo.toml");
        assert!(
            owner_manifest.exists(),
            "stage 4 requires the real lomo-media owner crate"
        );
        let owner = read("rust/media/Cargo.toml");
        assert!(
            workspace.contains("\"media\""),
            "lomo-media is not a workspace member"
        );
        assert!(
            owner.contains("name = \"lomo-media\""),
            "media crate has the wrong identity"
        );
        assert!(
            owner.contains("publish = false"),
            "lomo-media must not be published"
        );
        assert!(
            owner.contains("lomo-core") || owner.contains("lomo_core"),
            "lomo-media must depend inward on lomo-core"
        );
        assert!(
            owner.contains("lomo-workspace") || owner.contains("lomo_workspace"),
            "lomo-media must depend inward on lomo-workspace for path policy"
        );
        assert!(
            !owner.contains("boltffi")
                && !owner.contains("reqwest")
                && !owner.contains("git2")
                && !owner.contains("lomo-sync-core")
                && !owner.contains("lomo-feasibility")
                && !owner.contains("lomo-xtask")
                && !owner.contains("lomo-native")
                && !owner.contains("lomo-store"),
            "lomo-media must stay free of platform, store, sync-wire, facade, and tooling dependencies"
        );

        let lib = repository_root().join("rust/media/src/lib.rs");
        assert!(
            lib.exists(),
            "lomo-media must expose real production sources (not an empty marker)"
        );
        let lib_text = read("rust/media/src/lib.rs");
        assert!(
            !lib_text.trim().is_empty(),
            "lomo-media lib.rs must not be empty"
        );
        assert!(
            lib_text.contains("MEDIA_CRATE_NAME")
                || lib_text.contains("MediaOwnerIdentity")
                || lib_text.contains("ContentDigest"),
            "lomo-media must expose owner-identity or content-digest surface"
        );

        let tests_root = repository_root().join("rust/media/tests");
        assert!(
            tests_root.exists(),
            "lomo-media must ship behavior tests under tests/"
        );

        let mut test_sources = String::new();
        let mut test_file_count = 0usize;
        for entry in fs::read_dir(&tests_root).expect("media tests") {
            let entry = entry.expect("tests entry");
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
                test_file_count += 1;
                test_sources.push_str(&fs::read_to_string(&path).expect("utf-8 test"));
                test_sources.push('\n');
            }
        }
        assert!(
            test_file_count >= 1,
            "lomo-media must ship at least one external behavior test file, found {test_file_count}"
        );
        assert!(
            test_sources.contains("#[test]"),
            "external media tests must contain real #[test] cases"
        );
        assert!(
            test_sources.contains("ContentDigest")
                || test_sources.contains("MediaOwnerIdentity")
                || test_sources.contains("MEDIA_CRATE_NAME"),
            "external media tests must exercise the shipped public identity surface"
        );
    }

    #[test]
    fn stage_three_store_cutover_is_recorded_before_stage_four_green_claims() {
        let stage3 = read("fixtures/baseline/STAGE3-EVIDENCE.md");
        assert!(
            stage3.contains("P3-10")
                && (stage3.contains("cutover GREEN")
                    || stage3.contains("production store cutover GREEN")
                    || stage3.contains("P3-10 production store cutover GREEN")),
            "STAGE3-EVIDENCE.md must record P3-10 store cutover before stage-4 GREEN claims"
        );

        let stage4_evidence = repository_root().join("fixtures/baseline/STAGE4-EVIDENCE.md");
        if stage4_evidence.exists() {
            let evidence = read("fixtures/baseline/STAGE4-EVIDENCE.md");
            if evidence.contains("GREEN result") || evidence.contains("GREEN command") {
                assert!(
                    evidence.contains("P3-10")
                        || evidence.contains("stage-3 store cutover")
                        || evidence.contains("STAGE3-EVIDENCE"),
                    "stage-4 evidence must cite stage-3 store cutover as entry prerequisite"
                );
            }
            if stage_four_media_cutover_complete() {
                for claim in [
                    "Status: stage 4 closed",
                    "stage 4 is closed",
                    "stage 4 closed for stage 5",
                ] {
                    assert!(
                        !evidence.contains(claim),
                        "stage-4 evidence must not claim full stage close before P4-11 exit: {claim}"
                    );
                }
            } else {
                for claim in [
                    "production dual-stack media GREEN",
                    "P4-10A GREEN",
                    "P4-10B GREEN",
                    "Status: stage 4 closed",
                    "stage 4 is closed",
                    "Wave A cutover GREEN",
                    "Wave B cutover GREEN",
                ] {
                    assert!(
                        !evidence.contains(claim),
                        "stage-4 evidence must not claim production media/archive cutover or stage close early: {claim}"
                    );
                }
            }
        }
    }

    /// True when Kotlin media identity / archive ZIP production owners are deleted (P4-10A/B).
    fn stage_four_media_cutover_complete() -> bool {
        let root = repository_root();
        !root
            .join("data/src/repository/MediaRepositoryImpl.kt")
            .exists()
            && !root
                .join("data/src/repository/MigrationArchiveRepositoryImpl.kt")
                .exists()
            && !root
                .join("data/src/repository/AttachmentOrphanCleaner.kt")
                .exists()
    }

    #[test]
    fn stage_four_dark_build_must_not_wire_production_dual_stack() {
        // Production Kotlin must not import Rust media crate names or dual-write feature flags.
        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                assert!(
                    !text.contains("lomo_media") && !text.contains("lomo-media"),
                    "production Kotlin must not import Rust media crate names: {}",
                    source.display()
                );
                for forbidden in [
                    "use_rust_media",
                    "USE_RUST_MEDIA",
                    "dualWriteMedia",
                    "dual_write_media",
                    "RustAndKotlinMedia",
                    "rustMediaEnabled",
                    "use_rust_archive",
                    "USE_RUST_ARCHIVE",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "production dual-stack / feature-flag media path forbidden: {forbidden} in {}",
                        source.display()
                    );
                }
            }
        }

        if stage_four_media_cutover_complete() {
            return;
        }

        // Pre-cutover dark-build: Kotlin remains sole live production media/archive authority.
        assert!(
            repository_root()
                .join("data/src/repository/MediaRepositoryImpl.kt")
                .exists(),
            "production MediaRepositoryImpl must remain until P4-10A cutover"
        );
        assert!(
            repository_root()
                .join("data/src/repository/MigrationArchiveRepositoryImpl.kt")
                .exists(),
            "production MigrationArchiveRepositoryImpl must remain until P4-10B cutover"
        );
    }

    #[test]
    fn stage_four_forbids_full_media_byte_public_api() {
        let media_root = repository_root().join("rust/media/src");
        if !media_root.exists() {
            return;
        }
        for source in rust_sources(&media_root) {
            let text = fs::read_to_string(&source).expect("utf-8 media source");
            // Public API must not accept ownership of full media bodies for stage/import.
            for forbidden in [
                "pub fn stage_media_bytes",
                "pub fn import_media_bytes",
                "pub fn digest_all_bytes(bytes: Vec<u8>",
                "pub fn stage_media(bytes: Vec<u8>",
                "pub fn stage_media(bytes: &[u8]",
            ] {
                assert!(
                    !text.contains(forbidden),
                    "lomo-media public surface must not take full media bytes: {forbidden} in {}",
                    source.display()
                );
            }
        }

        let native = repository_root().join("rust/native/src/lib.rs");
        if native.exists() {
            let text = read("rust/native/src/lib.rs");
            for forbidden in [
                "fn stage_media_bytes",
                "fn import_media_bytes",
                "media_bytes: Vec<u8>",
                "media_bytes: ByteArray",
            ] {
                assert!(
                    !text.contains(forbidden),
                    "native FFI must not expose full media-byte import surfaces: {forbidden}"
                );
            }
        }

        // Dark media/archive conversion module (P4-09) is path-only when present.
        let media_ffi = repository_root().join("rust/native/src/media_ffi.rs");
        if media_ffi.exists() {
            let text = read("rust/native/src/media_ffi.rs");
            for forbidden in [
                "fn stage_media_bytes",
                "fn import_media_bytes",
                "media_bytes: Vec<u8>",
                "media_bytes: ByteArray",
                "body: Vec<u8>",
            ] {
                assert!(
                    !text.contains(forbidden),
                    "media_ffi must not expose full media-byte surfaces: {forbidden}"
                );
            }
            for required in [
                "ffi_stage_media",
                "ffi_finalize_recording",
                "ffi_promote_media",
                "ffi_query_media_manifest",
                "ffi_media_orphan_sweep",
                "ffi_archive_export",
                "ffi_archive_inspect",
                "ffi_archive_import",
                "ffi_archive_activate",
            ] {
                assert!(
                    text.contains(required),
                    "P4-09 media_ffi dark surface missing {required}"
                );
            }
        }
    }

    #[test]
    fn stage_four_production_media_owner_is_unique_after_cutover() {
        if !stage_four_media_cutover_complete() {
            return;
        }

        let forbidden_sources = [
            "data/src/repository/MediaRepositoryImpl.kt",
            "data/src/repository/AttachmentOrphanCleaner.kt",
            "data/src/repository/MigrationArchiveRepositoryImpl.kt",
            "data/src/repository/MigrationArchiveStagingWorkspace.kt",
            "data/src/repository/MigrationArchiveDryRunPlanner.kt",
            "data/src/repository/MigrationArchiveSupport.kt",
            "domain/src/usecase/DiscardMemoDraftAttachmentsUseCase.kt",
        ];
        for relative in forbidden_sources {
            assert!(
                !repository_root().join(relative).exists(),
                "legacy media/archive owner remains after P4-10 cutover: {relative}"
            );
        }

        // Wave A depth: production media edge must not dual-own magic or invent digest basenames.
        let edge = fs::read_to_string(
            repository_root().join("data/src/repository/MediaEdgeRepository.kt"),
        )
        .expect("MediaEdgeRepository is UTF-8");
        for forbidden in [
            "ImageMagicByteValidator",
            "basenameForStaged",
            "media_$short",
            "digest.take(",
        ] {
            assert!(
                !edge.contains(forbidden),
                "MediaEdgeRepository dual media-identity residual `{forbidden}` remains after Wave A"
            );
        }
        assert!(
            edge.contains("suggestedFinalRelativePath"),
            "MediaEdgeRepository must promote Rust-suggested final relative paths"
        );
        // D6 delete law: committed media must not be permanently deleted via host File.delete.
        // Stage temps may still delete; permanent reclaim is mediaOrphanSweep / media-trash.
        assert!(
            edge.contains("mediaOrphanSweep") || edge.contains("runOrphanSweepAtOperationBoundary"),
            "MediaEdgeRepository must call orphan sweep at delete/maintenance boundary"
        );
        // Reject permanent delete of media/$basename or media/${...} patterns.
        let permanent_committed_delete = edge.lines().any(|line| {
            let trimmed = line.trim();
            if trimmed.starts_with("//") {
                return false;
            }
            let deletes = trimmed.contains(".delete()") || trimmed.contains(".deleteRecursively()");
            if !deletes {
                return false;
            }
            // Allow temp stage discard and File.createTempFile cleanup only.
            let is_temp = trimmed.contains("temp.")
                || trimmed.contains("staged")
                || trimmed.contains("MEDIA_STAGE")
                || trimmed.contains("listFiles");
            !is_temp && (trimmed.contains("media/") || trimmed.contains("\"media\""))
        });
        assert!(
            !permanent_committed_delete,
            "MediaEdgeRepository must not File.delete committed media/ paths; use media-trash orphan sweep"
        );

        // D6 history keep-set: edge must project store history attachment refs into orphan sweep.
        assert!(
            edge.contains("listHistoryAttachmentRefs"),
            "MediaEdgeRepository must collect history attachment refs for orphan keep-set"
        );
        assert!(
            edge.contains("\"history\"") || edge.contains("source = \"history\""),
            "MediaEdgeRepository must wire HistoryVersion source=history on orphan refs"
        );
        let store_history = repository_root().join("rust/store/src/history_refs.rs");
        assert!(
            store_history.exists(),
            "lomo-store must own history_refs projection for D6 orphan keep-set"
        );

        assert_wave_a_media_delete_law();
        assert_wave_a_sync_delete_reclaim();
        assert_wave_a_history_retention_modeled();
    }

    fn assert_wave_a_media_delete_law() {
        // Broader production scan: committed media delete tails outside MediaEdge must fail closed.
        // LocalMediaSyncStore was deleted with Kotlin sync media mirror ownership (P5-13).
        let media_delete_owners = [
            "data/src/source/DirectMediaStorageBackendDelegate.kt",
            "data/src/source/SafMediaStorageBackendDelegate.kt",
            "data/src/source/FileMediaStorageDataSourceDelegate.kt",
            "data/src/repository/WorkspaceMediaAccess.kt",
            "data/src/repository/WorkspaceMediaDirectAccess.kt",
            "data/src/repository/WorkspaceMediaSafAccess.kt",
        ];
        for relative in media_delete_owners {
            let text = read(relative);
            assert!(
                text.contains("retired after P4-10A")
                    || text.contains("media-trash")
                    || text.contains("UnsupportedOperationException"),
                "{relative} must fail-closed permanent media delete (D6 media-trash law)"
            );
            let active_hard_delete = text.lines().any(|line| {
                let trimmed = line.trim();
                if trimmed.starts_with("//") || trimmed.starts_with('*') {
                    return false;
                }
                (trimmed.contains(".delete()") || trimmed.contains("?.delete()"))
                    && (trimmed.contains("filename")
                        || trimmed.contains("located.")
                        || trimmed.contains("target.")
                        || trimmed.contains("findFile"))
                    && !trimmed.contains("throw")
            });
            assert!(
                !active_hard_delete,
                "{relative} still permanently deletes committed media outside orphan/trash law"
            );
        }

        // Breadth scan: every production data/src .kt for DocumentsContract.delete / File.delete
        // against committed media/ paths (excluding stage temps).
        for source in kotlin_sources(&repository_root().join("data/src")) {
            let text = fs::read_to_string(&source)
                .unwrap_or_else(|e| panic!("failed to read {}: {e}", source.display()));
            let rel = source
                .strip_prefix(repository_root())
                .unwrap_or(&source)
                .display()
                .to_string();
            let is_media_edge = rel.contains("MediaEdgeRepository.kt");
            for (line_no, line) in text.lines().enumerate() {
                let trimmed = line.trim();
                if trimmed.starts_with("//")
                    || trimmed.starts_with('*')
                    || trimmed.starts_with("/*")
                {
                    continue;
                }
                let deletes_doc = trimmed.contains("DocumentsContract.delete")
                    && (trimmed.contains("media") || trimmed.contains("Media"));
                let deletes_committed_media = (trimmed.contains(".delete()")
                    || trimmed.contains("?.delete()"))
                    && (trimmed.contains("media/")
                        || trimmed.contains("\"media/\"")
                        || trimmed.contains("'/media/")
                        || trimmed.contains("mediaRoot")
                        || trimmed.contains("committed media"));
                if !deletes_doc && !deletes_committed_media {
                    continue;
                }
                let is_temp = trimmed.contains("temp")
                    || trimmed.contains("staged")
                    || trimmed.contains("MEDIA_STAGE")
                    || trimmed.contains("createTempFile")
                    || trimmed.contains("cacheDir");
                if is_media_edge && is_temp {
                    continue;
                }
                if is_temp && !deletes_doc {
                    continue;
                }
                panic!(
                    "production {rel}:{} hard-deletes committed media outside D6 media-trash law: {trimmed}",
                    line_no + 1
                );
            }
        }
    }

    fn assert_wave_a_sync_delete_reclaim() {
        // P5-13 deleted Kotlin WebDAV/S3 sync appliers; media delete reclaim is owned by Rust
        // sync + MediaEdge orphan sweep. Gate only remaining production media edge + store keep-set.
        let edge = read("data/src/repository/MediaEdgeRepository.kt");
        assert!(
            edge.contains("runOrphanSweepAtOperationBoundary") || edge.contains("mediaOrphanSweep"),
            "MediaEdgeRepository must orphan-sweep after media delete journal"
        );
        assert!(
            !repository_root()
                .join("data/src/repository/WebDavSyncOperationRepositoryImpl.kt")
                .exists(),
            "legacy WebDavSyncOperationRepositoryImpl must remain deleted after P5-13"
        );
        assert!(
            !repository_root()
                .join("data/src/repository/S3SyncActionApplier.kt")
                .exists(),
            "legacy S3SyncActionApplier must remain deleted after P5-13"
        );
    }

    fn assert_wave_a_history_retention_modeled() {
        let history_refs = read("rust/store/src/history_refs.rs");
        assert!(
            history_refs.contains("DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS")
                && history_refs.contains("list_history_attachment_refs_with_retention"),
            "history_refs must expose explicit retention window for D6 keep-set"
        );
    }

    #[test]
    fn stage_five_contract_and_evidence_files_exist() {
        assert_stage_five_contract_and_evidence_core();
        assert_stage_five_scaffolding_fixtures();
        assert_stage_five_cutover_prep_inventory_locks();
        assert_stage_five_safe_behavior_and_inventory_locks();
        assert_stage_five_size_and_feasibility_locks();
    }

    fn assert_stage_five_contract_and_evidence_core() {
        let contract = repository_root().join("fixtures/baseline/STAGE5-CONTRACT.md");
        let evidence = repository_root().join("fixtures/baseline/STAGE5-EVIDENCE.md");
        assert!(
            contract.exists(),
            "stage 5 requires versioned fixtures/baseline/STAGE5-CONTRACT.md"
        );
        assert!(
            evidence.exists(),
            "stage 5 requires versioned fixtures/baseline/STAGE5-EVIDENCE.md"
        );

        let contract_text = read("fixtures/baseline/STAGE5-CONTRACT.md");
        for required in [
            "Capability",
            "Given",
            "When",
            "Then",
            "Observable outcomes",
            "Excludes",
            "RED",
            "GREEN",
            "Generation fence",
            "No unproven delete",
            "Verify before baseline",
            "Single production stack",
            "Hard APK gate",
            "dual-stack",
            "dark-build",
            "API ≥ 26",
            "arm64",
            "lomo-sync",
            "WorkspaceGenerationId",
            "RemoteDatasetId",
            "pending_env",
            "P5-13",
            "Stage-3",
            "Stage-4",
        ] {
            assert!(
                contract_text.contains(required),
                "STAGE5-CONTRACT.md is missing required lock text: {required}"
            );
        }

        let evidence_text = read("fixtures/baseline/STAGE5-EVIDENCE.md");
        for required in [
            "P5-00",
            "RED command",
            "GREEN command",
            "First principles",
            "pending_env",
            "P3-10",
            "P4-10A",
            "P4-10B",
        ] {
            assert!(
                evidence_text.contains(required),
                "STAGE5-EVIDENCE.md is missing required evidence text: {required}"
            );
        }
    }

    fn assert_stage_five_scaffolding_fixtures() {
        // P5-00 scaffolding fixtures: inventory, divergence, safe-behavior, feasibility, size.
        // Wave-12: P5-13 cutover prep inventory (prep-only; does not authorize cutover).
        for relative in [
            "fixtures/baseline/stage5-sync-owner-inventory.v1.md",
            "fixtures/baseline/stage5-divergence-manifest.v1.md",
            "fixtures/baseline/stage5-safe-behavior-fixtures.v1.json",
            "fixtures/baseline/stage5-feasibility-spike.v1.md",
            "fixtures/baseline/stage5-native-size-ceiling.v1.json",
            "fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md",
        ] {
            let path = repository_root().join(relative);
            assert!(
                path.exists(),
                "stage 5 requires non-empty scaffolding fixture: {relative}"
            );
            let text = read(relative);
            assert!(
                !text.trim().is_empty(),
                "stage 5 scaffolding fixture must not be empty: {relative}"
            );
        }
    }

    fn assert_stage_five_cutover_prep_inventory_locks() {
        // Cutover prep inventory: checklist only — must ban premature DI flip language and lock
        // dual-stack / workerOf / use_rust_sync fail-closed markers without claiming P5-13 GREEN.
        let cutover_prep = read("fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md");
        for required in [
            "PREP_ONLY",
            "Do not flip production DI",
            "SyncDataModule",
            "workerOf(::RustSyncWorker)",
            "use_rust_sync",
            "GitSyncEngine",
            "S3SyncRepositoryImpl",
            "WebDavSyncRepositoryImpl",
            "pending_env",
            "P5-13",
            "P5-14",
            "arm64",
            "dual-stack",
            "Atomic cutover checklist",
        ] {
            assert!(
                cutover_prep.contains(required),
                "stage5-p5-13-cutover-prep-inventory.v1.md missing lock text: {required}"
            );
        }
        assert!(
            cutover_prep.contains("FORBIDDEN")
                && (cutover_prep.contains("Does **not** flip")
                    || cutover_prep.contains("does **not** authorize")
                    || cutover_prep.contains("Does **not** mark P5-13")),
            "cutover prep inventory must remain prep-only and not claim production cutover GREEN"
        );
    }

    fn assert_stage_five_safe_behavior_and_inventory_locks() {
        // Safe-behavior fixtures: schema + SB-01…SB-10 (language-agnostic planner oracle).
        let safe_behavior = read("fixtures/baseline/stage5-safe-behavior-fixtures.v1.json");
        assert!(
            safe_behavior.contains("\"schema_version\""),
            "stage5-safe-behavior-fixtures.v1.json must declare schema_version"
        );
        assert!(
            safe_behavior.contains("\"cases\""),
            "stage5-safe-behavior-fixtures.v1.json must declare cases"
        );
        for sb in [
            "SB-01", "SB-02", "SB-03", "SB-04", "SB-05", "SB-06", "SB-07", "SB-08", "SB-09",
            "SB-10",
        ] {
            assert!(
                safe_behavior.contains(&format!("\"id\": \"{sb}\"")),
                "stage5-safe-behavior-fixtures.v1.json missing required case id {sb}"
            );
        }

        // Inventory: cutover tail markers, P5-13, and use_rust_sync ban language.
        let inventory = read("fixtures/baseline/stage5-sync-owner-inventory.v1.md");
        for required in [
            "P5-13",
            "use_rust_sync",
            "Tail deletion",
            "GitSyncEngine",
            "S3",
            "WebDAV",
            "lomo-sync",
            "lomo-git",
            "dual-write",
            "No",
        ] {
            assert!(
                inventory.contains(required),
                "stage5-sync-owner-inventory.v1.md missing cutover/inventory lock text: {required}"
            );
        }
        // Explicit ban language for progressive dual DI / feature flags.
        assert!(
            inventory.contains("use_rust_sync")
                && (inventory.contains("No** `use_rust_sync`")
                    || inventory.contains("**No** `use_rust_sync`")
                    || inventory.contains("No `use_rust_sync`")
                    || inventory.contains("no feature flag")),
            "inventory must ban use_rust_sync / dual DI feature flags"
        );
    }

    fn assert_stage_five_size_and_feasibility_locks() {
        // Size ceiling: hard APK gate frozen; may_raise must stay false.
        let size_ceiling = read("fixtures/baseline/stage5-native-size-ceiling.v1.json");
        assert!(
            size_ceiling.contains("\"may_raise\": false")
                || size_ceiling.contains("\"may_raise\":false"),
            "stage5-native-size-ceiling.v1.json hard_apk_gate.may_raise must be false"
        );
        assert!(
            size_ceiling.contains("129337975"),
            "stage5-native-size-ceiling.v1.json must freeze Stage-0 hard gate bytes 129337975"
        );

        // Feasibility spike: inherited four-ABI markers + AWS OPEN honesty.
        let feasibility = read("fixtures/baseline/stage5-feasibility-spike.v1.md");
        for marker in [
            "LOMO_LINK_MARKER_GIT2_v1",
            "LOMO_LINK_MARKER_REQWEST_RUSTLS_v1",
            "LOMO_LINK_MARKER_SQLITE_v1",
        ] {
            assert!(
                feasibility.contains(marker),
                "stage5-feasibility-spike.v1.md must name inherited marker {marker}"
            );
        }
        assert!(
            feasibility.contains("AWS")
                && (feasibility.contains("OPEN") || feasibility.contains("**OPEN")),
            "stage5-feasibility-spike.v1.md must explicitly record AWS as OPEN when not four-ABI linked"
        );
    }

    /// Minimum file-delete probe for P5-13 cutover. Full uniqueness (inventory tails, frozen
    /// planner absorption, dual-stack absence) is enforced by later P5-13 architecture gates;
    /// this helper only answers "have the three primary Kotlin sync engines been removed?".
    fn stage_five_sync_cutover_complete() -> bool {
        let root = repository_root();
        !root.join("data/src/git/GitSyncEngine.kt").exists()
            && !root
                .join("data/src/repository/S3SyncRepositoryImpl.kt")
                .exists()
            && !root
                .join("data/src/repository/WebDavSyncRepositoryImpl.kt")
                .exists()
    }

    /// P5-13 post-cutover uniqueness: single Rust stack, no dual DI flags, Sync Center wired once,
    /// frozen planner and provider engines gone from production graph.
    #[test]
    fn stage_five_production_sync_owner_is_unique_after_cutover() {
        if !stage_five_sync_cutover_complete() {
            return;
        }
        assert_post_cutover_legacy_sources_deleted();
        assert_post_cutover_sync_module_and_ui_wiring();
        assert_post_cutover_conflict_dialog_rust_only();
        assert_post_cutover_composed_cycle_surface();
        assert_post_cutover_graph_and_dependency_bans();
        assert_post_cutover_dual_stack_flags_absent();
    }

    fn assert_post_cutover_legacy_sources_deleted() {
        let forbidden_sources = [
            "data/src/git/GitSyncEngine.kt",
            "data/src/repository/S3SyncRepositoryImpl.kt",
            "data/src/repository/WebDavSyncRepositoryImpl.kt",
            "data/src/worker/GitSyncWorker.kt",
            "data/src/worker/S3SyncWorker.kt",
            "data/src/worker/WebDavSyncWorker.kt",
            "data/src/sync/RustSyncPlannerClient.kt",
            "data/src/sync/RustSyncRequestEncoder.kt",
            "data/src/sync/RustSyncPlanDecoder.kt",
            "data/src/sync/RustSyncPlannerProtocol.kt",
            "data/src/s3/AwsSdkS3ClientFactory.kt",
            "data/src/s3/LomoS3Client.kt",
            "data/src/s3/S3RcloneCryptCompatCodec.kt",
            "data/src/webdav/OkHttpWebDavClient.kt",
            "data/src/git/SafGitMirrorBridge.kt",
        ];
        for relative in forbidden_sources {
            assert!(
                !repository_root().join(relative).exists(),
                "legacy Kotlin sync owner remains after P5-13 cutover: {relative}"
            );
        }
    }

    fn assert_post_cutover_sync_module_and_ui_wiring() {
        let sync_module = read("data/src/di/SyncDataModule.kt");
        for required in [
            "workerOf(::RustSyncWorker)",
            "BoltFfiRemoteSyncRepository",
            "RemoteSyncCenterRepositoryAdapter",
            "FreeFunctionSyncNativeBridge",
        ] {
            assert!(
                sync_module.contains(required),
                "post-cutover SyncDataModule must wire Rust-only surface: {required}"
            );
        }
        for forbidden in [
            "workerOf(::GitSyncWorker)",
            "workerOf(::S3SyncWorker)",
            "workerOf(::WebDavSyncWorker)",
            "GitSyncEngine",
            "S3SyncRepositoryImpl",
            "WebDavSyncRepositoryImpl",
            "use_rust_sync",
            "USE_RUST_SYNC",
            "RustSyncPlannerClient",
            "BoltFfiRustSyncEnvelopePlanner",
        ] {
            assert!(
                !sync_module.contains(forbidden),
                "post-cutover SyncDataModule must not retain dual-stack residual: {forbidden}"
            );
        }

        let view_models = read("app/src/di/ViewModelModule.kt");
        assert!(
            view_models.contains("SyncCenterViewModel"),
            "post-cutover ViewModelModule must register SyncCenterViewModel"
        );
        // Original conflict dialog UX is retained over the Rust kernel (product decision).
        // ViewModels are allowed only when remote resolve is Rust-backed via
        // RemoteSyncConflictDialogUseCase / RemoteSyncCenterRepository — not dual Kotlin engines.
        assert!(
            view_models.contains("SyncConflictViewModel")
                && view_models.contains("SyncConflictStateViewModel"),
            "post-cutover ViewModelModule must register Rust-backed conflict dialog ViewModels"
        );
        let nav = read("app/src/navigation/NavRoute.kt");
        assert!(
            nav.contains("SyncCenter"),
            "post-cutover NavRoute must register Sync Center"
        );
    }

    fn assert_post_cutover_conflict_dialog_rust_only() {
        let conflict_vm = read("app/src/feature/conflict/SyncConflictViewModel.kt");
        assert!(
            conflict_vm.contains("RemoteSyncConflictDialogUseCase")
                && conflict_vm.contains("resolveRemoteConflictDialogState")
                && conflict_vm.contains("remoteSyncConflictDialogUseCase"),
            "post-cutover SyncConflictViewModel must resolve remote conflicts only via RemoteSyncConflictDialogUseCase"
        );
        assert!(
            !conflict_vm.contains("SyncConflictResolutionUseCase")
                && !conflict_vm.contains("resolveConflictDialogState("),
            "post-cutover SyncConflictViewModel must not call legacy registry-backed SyncConflictResolutionUseCase for remote resolve"
        );
        for forbidden_engine in [
            "GitSyncEngine",
            "S3SyncRepositoryImpl",
            "WebDavSyncRepositoryImpl",
            "use_rust_sync",
            "USE_RUST_SYNC",
        ] {
            assert!(
                !conflict_vm.contains(forbidden_engine),
                "post-cutover SyncConflictViewModel must not retain dual-stack engine residual: {forbidden_engine}"
            );
        }
        let conflict_state_vm = read("app/src/feature/conflict/SyncConflictStateViewModel.kt");
        assert!(
            conflict_state_vm.contains("RemoteSyncConflictDialogUseCase")
                && conflict_state_vm.contains("loadRemoteOpenSession")
                && conflict_state_vm.contains("loadOpenSession"),
            "post-cutover SyncConflictStateViewModel must load open remote sessions from RemoteSyncConflictDialogUseCase"
        );
        let domain_sync = read("app/src/di/DomainSyncBindingsModule.kt");
        assert!(
            domain_sync.contains("RemoteSyncConflictDialogUseCase"),
            "post-cutover DomainSyncBindingsModule must bind RemoteSyncConflictDialogUseCase"
        );
    }

    fn assert_post_cutover_composed_cycle_surface() {
        // Hollow residual ban: production work unit must compose real ports via runCycle, not
        // empty-port inspectCyclePlan as the sole production surface.
        let executor = read("data/src/worker/RemoteSyncRustWorkExecutor.kt");
        assert!(
            executor.contains("runCycle")
                && executor.contains("RemoteSyncCycleRequest")
                && !executor.contains("inspectCyclePlan"),
            "post-cutover RemoteSyncRustWorkExecutor must call runCycle (composed owner cycle), not inspectCyclePlan"
        );
        let work_request = read("data/src/worker/RustSyncWorkExecutor.kt");
        assert!(
            work_request.contains("INPUT_BACKEND_KIND") && work_request.contains("backendKind"),
            "post-cutover RustSyncWorkRequest must carry backend kind for port composition"
        );
        let bridge = read("data/src/engine/sync/SyncNativeBridge.kt");
        assert!(
            bridge.contains("fun runCycle"),
            "post-cutover SyncNativeBridge must expose runCycle conversion surface"
        );
        let native_sync = read("rust/native/src/sync_ffi.rs");
        assert!(
            native_sync.contains("pub fn sync_run_cycle")
                && native_sync.contains("run_composed_sync_cycle"),
            "post-cutover native must expose sync_run_cycle over run_composed_sync_cycle"
        );
    }

    fn assert_post_cutover_graph_and_dependency_bans() {
        let native = read("rust/native/Cargo.toml");
        assert!(
            !native.contains("lomo-sync-core"),
            "post-cutover native must not depend on lomo-sync-core"
        );
        assert!(
            native.contains("lomo-sync") || native.contains("path = \"../sync\""),
            "post-cutover native must depend on lomo-sync"
        );
        assert!(
            native.contains("lomo-git") || native.contains("path = \"../git\""),
            "post-cutover native must depend on lomo-git for Git composition"
        );
        let sync_cargo = read("rust/sync/Cargo.toml");
        assert!(
            !production_dep_mentions_crate(&sync_cargo, "lomo-git")
                && !production_dep_mentions_path(&sync_cargo, "../git"),
            "post-cutover lomo-sync production deps must not depend on lomo-git (avoids crate cycle; host tests may use lomo-git as dev-dep)"
        );
        let native_src = read("rust/native/src/sync_ffi.rs");
        assert!(
            native_src.contains("connect_workspace_git")
                && native_src.contains("run_composed_sync_cycle_with_remote_port")
                && !native_src.contains("sync_ffi_git_backend_not_composed"),
            "post-cutover native must compose Git via lomo-git (not fail-closed theater)"
        );

        let workspace = read("rust/Cargo.toml");
        assert!(
            !workspace.contains("\"sync-core\"") && !workspace.contains("\"sync-core\","),
            "post-cutover workspace must not list sync-core member"
        );

        let data_module = read("data/module.yaml");
        for forbidden in [
            "org.eclipse.jgit",
            "aws.sdk.kotlin:s3",
            "aws.sdk.kotlin:bom",
            "org.bouncycastle:bcprov",
        ] {
            assert!(
                !data_module.contains(forbidden),
                "post-cutover data/module.yaml must drop sync-only dependency: {forbidden}"
            );
        }
    }

    fn assert_post_cutover_dual_stack_flags_absent() {
        // Dual-stack flags remain forbidden (also enforced by dark-build test).
        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                for forbidden in [
                    "use_rust_sync",
                    "USE_RUST_SYNC",
                    "dualWriteSync",
                    "rustSyncEnabled",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "post-cutover dual-stack flag forbidden: {forbidden} in {}",
                        source.display()
                    );
                }
            }
        }
    }

    #[test]
    fn stage_five_provider_smoke_gate_stays_credential_fenced() {
        // plan3 §1 decision 15: a standalone `just sync-provider-smoke` owns the six locked lines.
        let justfile = read("Justfile");
        assert!(
            justfile.contains("sync-provider-smoke"),
            "Stage-5 requires a standalone `just sync-provider-smoke` recipe"
        );
        let cli = read("rust/xtask/src/cli.rs");
        assert!(
            cli.contains("\"sync-provider-smoke\""),
            "xtask CLI must route the sync-provider-smoke command"
        );

        let gate = read("rust/xtask/src/provider_smoke.rs");
        for line in [
            "nutstore",
            "nextcloud",
            "aws-s3",
            "cloudflare-r2",
            "github",
            "gitlab",
        ] {
            assert!(
                gate.contains(line),
                "provider smoke gate must enumerate the locked line: {line}"
            );
        }
        assert!(
            gate.contains("pending_env"),
            "provider smoke gate must report unsatisfied lines as pending_env"
        );

        // The smoke targets must stay `#[ignore]`d so credential-less `just check` / `just ci`
        // can neither execute real network calls nor report a silent pass for a provider line.
        for relative in [
            "rust/sync/tests/provider_smoke.rs",
            "rust/git/tests/provider_smoke.rs",
        ] {
            let text = read(relative);
            let tests = text.matches("#[test]").count();
            let ignored = text.matches("#[ignore =").count();
            assert!(
                tests > 0 && tests == ignored,
                "every provider smoke test in {relative} must stay #[ignore]d ({tests} tests, {ignored} ignored)"
            );
        }

        for (manifest, relative) in [
            ("rust/sync/Cargo.toml", "tests/provider_smoke.rs"),
            ("rust/git/Cargo.toml", "tests/provider_smoke.rs"),
        ] {
            assert!(
                read(manifest).contains(relative),
                "{manifest} must register the provider_smoke test target"
            );
        }
    }

    #[test]
    fn stage_five_dark_build_must_not_wire_production_dual_stack() {
        assert_production_kotlin_has_no_dual_stack_sync_identifiers();
        if stage_five_sync_cutover_complete() {
            assert_post_cutover_native_conversion_only_graph();
            return;
        }
        assert_pre_cutover_kotlin_sync_owners_present();
        assert_native_sync_dependency_policy_for_dark_build();
        assert_dark_lomo_sync_package_shape_when_present();
        assert_dark_lomo_git_package_shape_when_present();
    }

    fn assert_production_kotlin_has_no_dual_stack_sync_identifiers() {
        // Production Kotlin must not import Rust sync crate names or dual-write feature flags.
        for relative in ["app/src", "data/src", "domain/src", "ui-components/src"] {
            for source in kotlin_sources(&repository_root().join(relative)) {
                let text = fs::read_to_string(&source).expect("Kotlin source is UTF-8");
                // Identifier-aware: path strings like "lomo-sync-tables" must not false-positive.
                assert!(
                    !contains_identifier(&text, "lomo_sync")
                        && !text.contains("com.lomo.sync")
                        && !text.contains("package lomo_sync"),
                    "production Kotlin must not import Rust sync crate names: {}",
                    source.display()
                );
                for forbidden in [
                    "use_rust_sync",
                    "USE_RUST_SYNC",
                    "dualWriteSync",
                    "dual_write_sync",
                    "RustAndKotlinSync",
                    "rustSyncEnabled",
                    "use_rust_git",
                    "USE_RUST_GIT",
                    "dualWriteGit",
                    "dual_write_git",
                ] {
                    assert!(
                        !text.contains(forbidden),
                        "production dual-stack / feature-flag sync path forbidden: {forbidden} in {}",
                        source.display()
                    );
                }
            }
        }
    }

    fn assert_post_cutover_native_conversion_only_graph() {
        // Post-cutover: production graph is Rust-only; dual flags already banned above.
        // Native may depend on lomo-sync + lomo-git (Git composition at conversion edge only).
        // Native must not re-implement planner machines and must not depend on deleted lomo-sync-core.
        let native = read("rust/native/Cargo.toml");
        assert!(
            !native.contains("lomo-sync-core"),
            "post-cutover native must not depend on lomo-sync-core"
        );
        assert!(
            native.contains("lomo-sync") || native.contains("path = \"../sync\""),
            "post-cutover native must depend on lomo-sync"
        );
        // Git composition is production-legal: native links lomo-git (sole git2 owner).
        assert!(
            native.contains("lomo-git") || native.contains("path = \"../git\""),
            "post-cutover native must depend on lomo-git for Git composition"
        );
        // lomo-sync production deps must NOT depend on lomo-git (avoids crate cycle; composition is native-edge).
        let sync_cargo = read("rust/sync/Cargo.toml");
        assert!(
            !production_dep_mentions_crate(&sync_cargo, "lomo-git")
                && !production_dep_mentions_path(&sync_cargo, "../git"),
            "post-cutover lomo-sync production deps must not depend on lomo-git (composition is native-edge)"
        );
    }

    fn assert_pre_cutover_kotlin_sync_owners_present() {
        // Pre-cutover dark-build: Kotlin remains sole live production sync authority.
        assert!(
            repository_root()
                .join("data/src/git/GitSyncEngine.kt")
                .exists(),
            "production GitSyncEngine must remain until P5-13 cutover"
        );
        assert!(
            repository_root()
                .join("data/src/repository/S3SyncRepositoryImpl.kt")
                .exists(),
            "production S3SyncRepositoryImpl must remain until P5-13 cutover"
        );
        assert!(
            repository_root()
                .join("data/src/repository/WebDavSyncRepositoryImpl.kt")
                .exists(),
            "production WebDavSyncRepositoryImpl must remain until P5-13 cutover"
        );
        assert!(
            repository_root()
                .join("data/src/di/SyncDataModule.kt")
                .exists(),
            "production SyncDataModule must remain until P5-13 cutover"
        );
    }

    fn assert_native_sync_dependency_policy_for_dark_build() {
        // P5-09+: dark BoltFFI may depend on lomo-sync for conversion-only mapping.
        // Production dual-stack DI / feature flags remain forbidden until P5-13 cutover.
        // When STAGE5-EVIDENCE still claims P5-09 OPEN, native must not depend on lomo-sync yet.
        // After P5-09 lands and before cutover, native may depend on lomo-sync but not lomo-git.
        // After cutover (P5-13+), native may depend on lomo-git for Git composition.
        let evidence = read("fixtures/baseline/STAGE5-EVIDENCE.md");
        let p5_09_open = evidence.lines().any(|line| {
            line.contains("| P5-09 |") && (line.contains("**OPEN**") || line.contains("| OPEN |"))
        });
        let cutover = stage_five_sync_cutover_complete();
        let native = read("rust/native/Cargo.toml");
        if p5_09_open {
            for forbidden in [
                "lomo-sync =",
                "lomo_sync =",
                "name = \"lomo-sync\"",
                "path = \"../sync\"",
                "lomo-git =",
                "lomo_git =",
                "name = \"lomo-git\"",
                "path = \"../git\"",
            ] {
                assert!(
                    !native.contains(forbidden),
                    "pre-P5-09 dark-build: native must not depend on future sync/git crates ({forbidden})"
                );
            }
            return;
        }
        if cutover {
            // Post-cutover: Git composition requires lomo-git on native.
            assert!(
                native.contains("lomo-git") || native.contains("path = \"../git\""),
                "post-cutover native must depend on lomo-git for Git composition"
            );
            let sync_cargo = read("rust/sync/Cargo.toml");
            assert!(
                !production_dep_mentions_crate(&sync_cargo, "lomo-git")
                    && !production_dep_mentions_path(&sync_cargo, "../git"),
                "post-cutover lomo-sync production deps must not depend on lomo-git (avoids crate cycle)"
            );
        } else {
            // Pre-cutover dark FFI may depend on lomo-sync only (conversion). lomo-git stays out.
            for forbidden in [
                "lomo-git =",
                "lomo_git =",
                "name = \"lomo-git\"",
                "path = \"../git\"",
            ] {
                assert!(
                    !native.contains(forbidden),
                    "P5-09 dark FFI pre-cutover: native must not depend on lomo-git ({forbidden})"
                );
            }
        }
        // Conversion-only: native must not re-implement planner/session machines.
        let native_src = repository_root().join("rust/native/src");
        for source in rust_sources(&native_src) {
            let text = fs::read_to_string(&source).expect("native source is UTF-8");
            for forbidden in [
                "pub fn plan_intents",
                "pub fn run_sync_cycle",
                "force_push",
                "use_rust_sync",
            ] {
                assert!(
                    !text.contains(forbidden),
                    "native sync FFI must stay conversion-only (forbidden {forbidden} in {})",
                    source.display()
                );
            }
        }
    }

    fn assert_dark_lomo_sync_package_shape_when_present() {
        // When dark lomo-sync is present (P5-03+), it must be a real package (not hollow) and remain
        // off the production native graph. When absent, the path ban remains for pre-P5-03 trees.
        let sync_dir = repository_root().join("rust/sync");
        if !sync_dir.exists() {
            return;
        }
        let sync_cargo = read("rust/sync/Cargo.toml");
        assert!(
            sync_cargo.contains("name = \"lomo-sync\""),
            "rust/sync must package as lomo-sync"
        );
        assert!(
            sync_dir.join("src").exists(),
            "dark lomo-sync must ship real sources under rust/sync/src"
        );
        assert!(
            sync_dir.join("tests").exists(),
            "dark lomo-sync must ship external behavior tests under rust/sync/tests"
        );
        // Workspace must list the dark member so clippy/tests can target it.
        let workspace_cargo = read("rust/Cargo.toml");
        assert!(
            workspace_cargo.contains("\"sync\"") || workspace_cargo.contains("\"sync\","),
            "workspace must list sync member when rust/sync exists"
        );
    }

    fn assert_dark_lomo_git_package_shape_when_present() {
        // P5-07+: dark lomo-git may exist as a workspace member without native DI. When present it
        // must be real (src+tests) and the sole production-graph git2 owner (feasibility remains
        // tooling-only). When absent, the directory ban still holds for pre-P5-07 trees.
        let git_dir = repository_root().join("rust/git");
        if !git_dir.exists() {
            assert!(
                !git_dir.exists(),
                "pre-cutover: rust/git directory must not exist until P5-07 dark adapter lands"
            );
            return;
        }
        let git_cargo = read("rust/git/Cargo.toml");
        assert!(
            git_cargo.contains("name = \"lomo-git\""),
            "rust/git must package as lomo-git"
        );
        assert!(
            git_cargo.contains("git2"),
            "lomo-git is the sole git2 adapter and must declare the git2 dependency"
        );
        assert!(
            git_dir.join("src").exists(),
            "dark lomo-git must ship real sources under rust/git/src"
        );
        assert!(
            git_dir.join("tests").exists(),
            "dark lomo-git must ship external behavior tests under rust/git/tests"
        );
        let workspace_cargo = read("rust/Cargo.toml");
        assert!(
            workspace_cargo.contains("\"git\"") || workspace_cargo.contains("\"git\","),
            "workspace must list git member when rust/git exists"
        );
        // No other production-ish crate may depend on git2 (feasibility is tooling-only;
        // lomo-native / lomo-sync may depend on lomo-git but not git2 directly; tests may use
        // git2 as a dev-dep for hermetic bare-repo fixtures).
        for relative in [
            "rust/sync/Cargo.toml",
            "rust/store/Cargo.toml",
            "rust/workspace/Cargo.toml",
            "rust/core/Cargo.toml",
            "rust/media/Cargo.toml",
            "rust/native/Cargo.toml",
        ] {
            let path = repository_root().join(relative);
            if path.exists() {
                let text = read(relative);
                // Only production [dependencies] git2 is forbidden — allow [dev-dependencies].
                let production_git2 = production_dep_mentions_git2(&text);
                assert!(
                    !production_git2,
                    "{relative} production deps must not depend on git2; only lomo-git (and tooling feasibility) may"
                );
            }
        }
    }

    fn production_dep_mentions_git2(cargo_toml: &str) -> bool {
        production_dep_mentions_crate(cargo_toml, "git2")
    }

    fn production_dep_mentions_crate(cargo_toml: &str, crate_name: &str) -> bool {
        let mut in_deps = false;
        for line in cargo_toml.lines() {
            let trimmed = line.trim();
            if trimmed.starts_with('[') {
                in_deps = trimmed == "[dependencies]";
                continue;
            }
            if in_deps
                && (trimmed.starts_with(&format!("{crate_name} "))
                    || trimmed.starts_with(&format!("{crate_name}="))
                    || trimmed.contains(&format!("{crate_name} =")))
            {
                return true;
            }
        }
        false
    }

    fn production_dep_mentions_path(cargo_toml: &str, path: &str) -> bool {
        let mut in_deps = false;
        let needle = format!("path = \"{path}\"");
        for line in cargo_toml.lines() {
            let trimmed = line.trim();
            if trimmed.starts_with('[') {
                in_deps = trimmed == "[dependencies]";
                continue;
            }
            if in_deps && trimmed.contains(&needle) {
                return true;
            }
        }
        false
    }

    #[test]
    fn stage_three_and_four_cutover_prerequisites_before_stage_five_production_cutover_claims() {
        let stage3 = read("fixtures/baseline/STAGE3-EVIDENCE.md");
        assert!(
            stage3.contains("P3-10")
                && (stage3.contains("cutover GREEN")
                    || stage3.contains("production store cutover GREEN")
                    || stage3.contains("P3-10 production store cutover GREEN")),
            "STAGE3-EVIDENCE.md must record P3-10 store cutover before stage-5 production cutover claims"
        );

        let stage4 = read("fixtures/baseline/STAGE4-EVIDENCE.md");
        assert!(
            (stage4.contains("P4-10A") && stage4.contains("P4-10B"))
                && (stage4.contains("GREEN") || stage4.contains("host production cutover")),
            "STAGE4-EVIDENCE.md must record P4-10A/B media/archive host cutover before stage-5 production cutover claims"
        );

        let stage5_evidence = repository_root().join("fixtures/baseline/STAGE5-EVIDENCE.md");
        if stage5_evidence.exists() {
            let evidence = read("fixtures/baseline/STAGE5-EVIDENCE.md");
            if evidence.contains("GREEN result") || evidence.contains("GREEN command") {
                assert!(
                    evidence.contains("P3-10")
                        || evidence.contains("stage-3 store cutover")
                        || evidence.contains("STAGE3-EVIDENCE"),
                    "stage-5 evidence must cite stage-3 store cutover as entry prerequisite"
                );
                assert!(
                    evidence.contains("P4-10A")
                        || evidence.contains("P4-10B")
                        || evidence.contains("stage-4")
                        || evidence.contains("STAGE4-EVIDENCE"),
                    "stage-5 evidence must cite stage-4 media/archive host cutover as entry prerequisite"
                );
            }

            // Fail closed: table-form P5-13 GREEN claims without cutover completeness.
            // Meta text such as "no premature P5-13 GREEN claim" is not a cutover claim.
            let claims_p5_13_green = evidence.contains("| P5-13 | **GREEN**")
                || evidence.contains("P5-13 | **GREEN**")
                || evidence.contains("P5-13 production cutover GREEN")
                || evidence.contains("Status: stage 5 closed")
                || evidence.contains("formal Stage-5 exit GREEN")
                || evidence.contains("Stage 5 formal exit GREEN");
            if claims_p5_13_green {
                assert!(
                    stage_five_sync_cutover_complete(),
                    "STAGE5-EVIDENCE must not claim P5-13 / stage-5 close GREEN while Kotlin sync owners remain"
                );
                assert!(
                    stage3.contains("P3-10")
                        && (stage3.contains("cutover GREEN")
                            || stage3.contains("production store cutover GREEN")),
                    "P5-13 GREEN requires recorded Stage-3 P3-10 store cutover"
                );
                assert!(
                    stage4.contains("P4-10A") && stage4.contains("P4-10B"),
                    "P5-13 GREEN requires recorded Stage-4 P4-10A/B host cutover"
                );
            }

            if !stage_five_sync_cutover_complete() {
                for claim in [
                    "Status: stage 5 closed",
                    "stage 5 is closed",
                    "stage 5 closed for stage 6",
                    "production dual-stack sync GREEN",
                    "P5-13 | **GREEN**",
                    "| P5-13 | **GREEN**",
                    "P5-13 production cutover GREEN",
                    "formal Stage-5 exit GREEN",
                    "Stage 5 formal exit GREEN",
                ] {
                    assert!(
                        !evidence.contains(claim),
                        "stage-5 evidence must not claim production sync cutover or formal stage close early: {claim}"
                    );
                }
            }
        }
    }

    /// P5-04 host: local sync mutations are fenced through `lomo-store` expected-revision ports;
    /// dark `lomo-sync` must not write user Markdown/media; SAF projection is rebuildable cache only;
    /// no provider-specific user-file mirror authority may land as a Stage-5 write path in Rust.
    #[test]
    fn stage_five_local_sync_ports_forbid_bypass_user_file_writes() {
        let store_sync = read("rust/store/src/sync_local.rs");
        for required in [
            "LocalSyncMutationBatch",
            "prepare_sync_apply",
            "commit_sync_apply",
            "snapshot_sync_view",
            "SafProjectionBinding",
            "sync_local_write_authority",
            "WorkspaceGenerationId",
        ] {
            assert!(
                store_sync.contains(required),
                "lomo-store sync_local must expose P5-04 port surface: {required}"
            );
        }
        assert!(
            store_sync.contains("lomo-store expected-revision LocalSyncMutationBatch"),
            "sync_local_write_authority must name the sole local write authority string"
        );
        assert!(
            store_sync.contains("app-private")
                && store_sync.contains("rebuild_from_snapshot")
                && store_sync.contains("saf-projection"),
            "SAF projection must be app-private, generation-bound, and rebuildable from coarse facts"
        );
        // Projection is cache: rebuild must not claim authority over user Markdown bytes.
        assert!(
            !store_sync.contains("pub fn write_user_markdown_from_projection")
                && !store_sync.contains("pub fn apply_provider_user_file_mirror"),
            "SAF projection must not grow a user-file write authority API"
        );

        let store_lib = read("rust/store/src/lib.rs");
        assert!(
            store_lib.contains("snapshot_sync_view")
                && store_lib.contains("apply_local_sync_batch")
                && store_lib.contains("prepare_sync_apply")
                && store_lib.contains("commit_sync_apply"),
            "Store handle must expose unified Direct/SAF local sync ports"
        );

        // Dark lomo-sync must not own a second user-file write path (durable control only).
        let sync_root = repository_root().join("rust/sync/src");
        assert!(
            sync_root.exists(),
            "P5-04 assumes dark lomo-sync sources from P5-03"
        );
        for source in rust_sources(&sync_root) {
            let text = fs::read_to_string(&source).expect("sync source is UTF-8");
            for forbidden in [
                "memos/",
                "attachments/",
                "apply_local_sync_batch",
                "checkout",
                "force_push",
                "user_file_mirror",
                "GitSafMirror",
                "write_user_bytes",
            ] {
                // Allow comments / docs that name the forbidden path as a ban; ban executable API.
                if text.contains(&format!("pub fn {forbidden}"))
                    || text.contains(&format!("fn {forbidden}"))
                    || text.contains(&format!("\"{forbidden}"))
                {
                    // memos/ and attachments/ as path literals in production sync sources are
                    // user-file targets — fail closed if they appear as string writes.
                    if forbidden == "memos/" || forbidden == "attachments/" {
                        if text.contains(&format!("\"{forbidden}"))
                            || text.contains(&format!("join(\"{forbidden}"))
                        {
                            panic!(
                                "dark lomo-sync must not target user memo/media paths: {} in {}",
                                forbidden,
                                source.display()
                            );
                        }
                        continue;
                    }
                    panic!(
                        "dark lomo-sync must not implement user-file write/bypass API {forbidden} in {}",
                        source.display()
                    );
                }
            }
        }

        // No second Rust crate may claim provider-specific user-file mirror as write authority.
        for relative in [
            "rust/sync/src",
            "rust/store/src",
            "rust/workspace/src",
            "rust/core/src",
            "rust/native/src",
        ] {
            let root = repository_root().join(relative);
            if !root.exists() {
                continue;
            }
            for source in rust_sources(&root) {
                let text = fs::read_to_string(&source).expect("source is UTF-8");
                for banned in [
                    "ProviderUserFileMirror",
                    "GitUserFileMirror",
                    "S3UserFileMirror",
                    "WebDavUserFileMirror",
                    "checkout_user_workspace",
                    "force_checkout_user_files",
                ] {
                    assert!(
                        !contains_identifier(&text, banned),
                        "provider-specific user-file mirror write authority is forbidden: {banned} in {}",
                        source.display()
                    );
                }
            }
        }

        // Contract/evidence must keep the SAF exception language once P5-04 ports exist.
        let arch = read("ARCHITECTURE.md");
        assert!(
            arch.contains("SAF projection")
                && (arch.contains("LocalSyncMutationBatch") || arch.contains("expected-revision")),
            "ARCHITECTURE.md must document SAF projection exception and local commit boundary"
        );
        let contract = read("fixtures/baseline/STAGE5-CONTRACT.md");
        assert!(
            contract.contains("LocalSyncMutationBatch")
                || contract.contains("Local commit boundary"),
            "STAGE5-CONTRACT must lock local commit boundary / LocalSyncMutationBatch"
        );
    }

    /// Wave-12: P5-13 cutover prep inventory is host-closeable, but premature production DI /
    /// `workerOf(::RustSyncWorker)` / dual-stack flags remain fail-closed until atomic cutover.
    #[test]
    fn stage_five_cutover_prep_inventory_must_not_authorize_premature_production_wire() {
        let prep_path =
            repository_root().join("fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md");
        assert!(
            prep_path.exists(),
            "Wave-12 requires fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md"
        );
        let prep = read("fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md");
        assert!(
            prep.contains("PREP_ONLY") && prep.contains("Do not flip production DI"),
            "cutover prep inventory must declare PREP_ONLY / do-not-flip production DI"
        );

        // Evidence must keep P5-13 OPEN while prep inventory exists (prep ≠ cutover).
        let evidence = read("fixtures/baseline/STAGE5-EVIDENCE.md");
        let p5_13_open = evidence.lines().any(|line| {
            line.contains("| P5-13 |") && (line.contains("**OPEN**") || line.contains("| OPEN |"))
        });
        assert!(
            p5_13_open || stage_five_sync_cutover_complete(),
            "STAGE5-EVIDENCE must keep P5-13 OPEN until atomic cutover completes"
        );

        if stage_five_sync_cutover_complete() {
            let sync_module = read("data/src/di/SyncDataModule.kt");
            assert!(
                sync_module.contains("workerOf(::RustSyncWorker)"),
                "post-cutover SyncDataModule must register workerOf(::RustSyncWorker)"
            );
            let nav = read("app/src/navigation/NavRoute.kt");
            assert!(
                nav.contains("SyncCenter"),
                "post-cutover NavRoute must register Sync Center"
            );
            let view_models = read("app/src/di/ViewModelModule.kt");
            assert!(
                view_models.contains("SyncCenterViewModel"),
                "post-cutover ViewModelModule must register SyncCenterViewModel"
            );
            return;
        }

        // Pre-cutover: production SyncDataModule must not register dark RustSyncWorker.
        let sync_module = read("data/src/di/SyncDataModule.kt");
        for forbidden in [
            "workerOf(::RustSyncWorker)",
            "workerOf(RustSyncWorker)",
            "RustSyncWorker::class",
            "BoltFfiRemoteSyncRepository",
            "RemoteSyncCenterRepositoryAdapter",
            "use_rust_sync",
            "USE_RUST_SYNC",
        ] {
            assert!(
                !sync_module.contains(forbidden),
                "pre-cutover SyncDataModule must not wire dark cutover types ({forbidden})"
            );
        }
        // Production workers remain the sole registered provider workers.
        for required in [
            "workerOf(::GitSyncWorker)",
            "workerOf(::S3SyncWorker)",
            "workerOf(::WebDavSyncWorker)",
        ] {
            assert!(
                sync_module.contains(required),
                "pre-cutover SyncDataModule must still register Kotlin provider worker: {required}"
            );
        }

        // Dark worker body may exist unregistered; production nav must not dual-wire Sync Center.
        assert!(
            repository_root()
                .join("data/src/worker/RustSyncWorker.kt")
                .exists(),
            "dark RustSyncWorker must exist as unregistered host residual before P5-13"
        );
        let nav = read("app/src/navigation/NavRoute.kt");
        assert!(
            !nav.contains("SyncCenter") && !nav.contains("RemoteSyncCenter"),
            "pre-cutover NavRoute must not register Sync Center production route"
        );
        let view_models = read("app/src/di/ViewModelModule.kt");
        assert!(
            !view_models.contains("SyncCenterViewModel"),
            "pre-cutover ViewModelModule must not register SyncCenterViewModel"
        );
    }

    /// Blocks infinite device-side migration archaeology (Room `Migration_*` / dao / legacy trees).
    #[test]
    fn store_forbids_room_style_migration_layout() {
        let store_root = repository_root().join("rust/store");
        for forbidden_dir in [
            "src/migrations",
            "src/migration",
            "src/dao",
            "src/daos",
            "src/entities",
            "src/entity",
            "src/legacy",
        ] {
            assert!(
                !store_root.join(forbidden_dir).exists(),
                "Room-style layout is forbidden under lomo-store: {forbidden_dir}"
            );
        }

        // Filename bans apply to production sources only (tests may say owner_identity_*).
        // Use stem equality / suffix checks so `owner_identity_contract.rs` is not a false hit.
        let forbidden_stems = [
            "migration",
            "migrations",
            "dao",
            "entity",
            "entities",
            "legacy_schema",
            "schema_v2_compat",
            "schema_compat",
        ];
        for source in rust_sources(&store_root.join("src")) {
            let Some(stem_raw) = source.file_stem().and_then(|value| value.to_str()) else {
                panic!(
                    "store source path must have a UTF-8 file stem: {}",
                    source.display()
                );
            };
            let stem = stem_raw.to_ascii_lowercase();
            for forbidden in forbidden_stems {
                assert!(
                    stem != forbidden
                        && !stem.starts_with(&format!("{forbidden}_"))
                        && !stem.ends_with(&format!("_{forbidden}"))
                        && !stem.contains(&format!("_{forbidden}_")),
                    "Room-style production module stem `{forbidden}` is forbidden under lomo-store/src: {}",
                    source.display()
                );
            }
        }

        assert!(
            !store_root.join("store-legacy").exists()
                && !repository_root().join("rust/store-legacy").exists()
                && !repository_root().join("rust/store_v2").exists()
                && !repository_root().join("rust/store-v2").exists(),
            "parallel lomo-store crate trees (legacy/v2) invite dual persistence owners"
        );
    }

    /// Blocks parallel live schema documents that force forever-compat open paths.
    #[test]
    fn store_schema_has_single_live_version_surface() {
        let schema = read("rust/store/src/schema.rs");
        assert!(
            contains_identifier(&schema, "STORE_SCHEMA_VERSION"),
            "lomo-store must expose STORE_SCHEMA_VERSION as the single live schema anchor"
        );
        assert!(
            schema.contains("fn schema_v1_ddl") || schema.contains("fn schema_v1_ddl()"),
            "schema DDL must live as an explicit schema_v1_ddl (single DDL document style)"
        );

        let version_const_lines = schema
            .lines()
            .filter(|line| {
                let trimmed = line.trim_start();
                !trimmed.starts_with("//")
                    && trimmed.contains("STORE_SCHEMA_VERSION")
                    && trimmed.contains("const")
            })
            .count();
        assert_eq!(
            version_const_lines, 1,
            "exactly one STORE_SCHEMA_VERSION const is allowed in schema.rs (found {version_const_lines})"
        );

        // Live DDL helpers must not proliferate into an unbounded version zoo in one file.
        let schema_fn_count = schema
            .lines()
            .filter(|line| {
                let trimmed = line.trim_start();
                trimmed.starts_with("pub fn schema_v") || trimmed.starts_with("fn schema_v")
            })
            .count();
        assert!(
            (1..=2).contains(&schema_fn_count),
            "expected 1..=2 schema_v* DDL functions (single live schema + optional helper), found {schema_fn_count}"
        );

        let lib = read("rust/store/src/lib.rs");
        assert!(
            lib.contains("STORE_SCHEMA_VERSION") || lib.contains("schema::STORE_SCHEMA_VERSION"),
            "lomo-store lib must re-export or reference STORE_SCHEMA_VERSION for owner identity"
        );
    }

    /// Blocks a second `SQLite` owner via parallel rusqlite production consumers.
    #[test]
    fn store_sqlite_access_stays_inside_allowed_crates() {
        // Workspace pin is allowed; production consumers of the crate must stay narrow.
        let mut offenders = Vec::new();
        for relative in repository_files() {
            if !relative.ends_with("Cargo.toml") || !relative.starts_with("rust/") {
                continue;
            }
            // Workspace root only declares the shared pin.
            if relative == "rust/Cargo.toml" {
                continue;
            }
            // Sole production owner.
            if relative == "rust/store/Cargo.toml" {
                continue;
            }
            // Tooling-only hermetic probes (never production graph).
            if relative == "rust/feasibility/Cargo.toml" {
                continue;
            }
            let text = read(&relative);
            if text.contains("rusqlite") {
                offenders.push(relative);
            }
        }
        assert!(
            offenders.is_empty(),
            "rusqlite must not appear outside lomo-store (and tooling feasibility); offenders:\n{}",
            offenders.join("\n")
        );

        // Core / workspace / sync-core / native must not reverse-depend into owning SQL.
        for relative in [
            "rust/core/Cargo.toml",
            "rust/workspace/Cargo.toml",
            "rust/native/Cargo.toml",
        ] {
            let text = read(relative);
            assert!(
                !text.contains("rusqlite"),
                "{relative} must not depend on rusqlite (store owns SQLite)"
            );
        }

        let native = read("rust/native/Cargo.toml");
        assert!(
            native.contains("lomo-store") || native.contains("path = \"../store\""),
            "lomo-native must depend on lomo-store for conversion-only store FFI"
        );
        assert!(
            !native.contains("rusqlite"),
            "lomo-native must not take a direct rusqlite dependency"
        );
    }

    /// Blocks native re-implementation of schema/tokenizer/SQL (dual semantic owner).
    #[test]
    fn native_store_ffi_is_conversion_only() {
        let ffi_path = repository_root().join("rust/native/src/store_ffi.rs");
        assert!(
            ffi_path.exists(),
            "stage-3 requires rust/native/src/store_ffi.rs conversion surface"
        );
        let ffi = read("rust/native/src/store_ffi.rs");
        assert!(
            ffi.contains("lomo_store") || ffi.contains("use lomo_store"),
            "store_ffi must delegate into lomo_store"
        );

        for forbidden in [
            "CREATE TABLE",
            "CREATE VIRTUAL TABLE",
            "CREATE INDEX",
            "ALTER TABLE",
            "USING fts5",
            "pragma_update",
            "PRAGMA user_version",
            "UnicodeBlock",
        ] {
            assert!(
                !ffi.contains(forbidden),
                "lomo-native store_ffi must stay conversion-only; found business/SQL owner marker: {forbidden}"
            );
        }

        // Production native sources must not embed store DDL/tokenizer ownership outside store_ffi either.
        for source in rust_sources(&repository_root().join("rust/native/src")) {
            let text = fs::read_to_string(&source).expect("Rust source is UTF-8");
            if source.ends_with("store_ffi.rs") {
                continue;
            }
            for forbidden in ["CREATE VIRTUAL TABLE", "USING fts5", "schema_v1_ddl"] {
                assert!(
                    !text.contains(forbidden),
                    "native production source re-owns store schema surface `{forbidden}`: {}",
                    source.display()
                );
            }
        }
    }

    /// Blocks silent Dao-per-table file explosion under the store owner.
    #[test]
    fn store_src_module_count_stays_capability_shaped() {
        let src = repository_root().join("rust/store/src");
        let mut names = Vec::new();
        for entry in fs::read_dir(&src).expect("rust/store/src exists") {
            let entry = entry.expect("store src entry");
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
                let name = path
                    .file_name()
                    .and_then(|value| value.to_str())
                    .unwrap_or_else(|| {
                        panic!("store src file name must be UTF-8: {}", path.display())
                    });
                names.push(name.to_owned());
            }
        }
        names.sort();
        // Capability slices (schema/open/query/txn/rebuild/reminder/tokenizer/…) — not Entity×Dao.
        assert!(
            names.len() <= 20,
            "lomo-store/src has {} modules (budget ≤20 for capability shape): {names:?}",
            names.len()
        );
        assert!(
            names.len() >= 8,
            "lomo-store/src looks under-structured ({}); expected real capability modules, found {names:?}",
            names.len()
        );
        for required in [
            "schema.rs",
            "open.rs",
            "query.rs",
            "transaction.rs",
            "rebuild.rs",
            "lib.rs",
        ] {
            assert!(
                names.iter().any(|name| name == required),
                "lomo-store capability module missing: {required} (have {names:?})"
            );
        }
    }

    /// Ensures anti-Room evolution strategy stays covered by named external contracts.
    #[test]
    fn store_required_behavior_contract_tests_exist() {
        let required_tests = [
            (
                "rust/store/tests/open_schema_contract.rs",
                "unknown_higher_schema_version_fails_closed_without_downgrade",
            ),
            ("rust/store/tests/rebuild_contract.rs", "#[test]"),
            ("rust/store/tests/transaction_contract.rs", "#[test]"),
            ("rust/store/tests/query_cursor_contract.rs", "#[test]"),
            ("rust/store/tests/tokenizer_fts_contract.rs", "#[test]"),
            ("rust/store/tests/owner_identity_contract.rs", "#[test]"),
        ];
        for (relative, needle) in required_tests {
            let path = repository_root().join(relative);
            assert!(
                path.exists(),
                "required lomo-store behavior contract missing: {relative}"
            );
            let text = read(relative);
            assert!(
                text.contains(needle),
                "behavior contract {relative} must contain `{needle}` so fail-closed/rebuild strategy stays locked"
            );
        }

        let open = read("rust/store/tests/open_schema_contract.rs");
        assert!(
            open.contains("user_version") && open.contains("STORE_SCHEMA_VERSION"),
            "open_schema_contract must exercise user_version / STORE_SCHEMA_VERSION gate"
        );

        let manifest = read("rust/store/Cargo.toml");
        for harness in [
            "open_schema_contract",
            "rebuild_contract",
            "transaction_contract",
            "query_cursor_contract",
            "tokenizer_fts_contract",
            "owner_identity_contract",
        ] {
            assert!(
                manifest.contains(harness),
                "lomo-store Cargo.toml must register external test harness `{harness}` (autotests=false)"
            );
        }
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
            "rust/native/src",
            "rust/feasibility/src",
            "rust/workspace/src",
            "rust/store/src",
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
            let Some(after_open) = remaining.get(start + 2..) else {
                break;
            };
            let Some(end) = after_open.find(')') else {
                break;
            };
            let Some(target) = after_open.get(..end) else {
                break;
            };
            targets.push(target.trim());
            let Some(rest) = after_open.get(end + 1..) else {
                break;
            };
            remaining = rest;
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

    fn cargo_manifests_under(root: &Path) -> Vec<PathBuf> {
        let mut manifests = Vec::new();
        if !root.exists() {
            return manifests;
        }
        let ignored = ["target", ".cargo-size-exp"];
        let mut pending = vec![root.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in fs::read_dir(&directory).expect("source directory is readable") {
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
                if path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name == "Cargo.toml")
                {
                    manifests.push(path);
                }
            }
        }
        manifests
    }

    fn package_name_from_manifest(text: &str) -> Option<String> {
        let mut in_package = false;
        for line in text.lines() {
            let trimmed = line.trim();
            if trimmed.starts_with('[') {
                in_package = trimmed == "[package]";
                continue;
            }
            if in_package
                && let Some(value) = trimmed.strip_prefix("name = \"")
                && let Some(name) = value.strip_suffix('"')
            {
                return Some(name.to_owned());
            }
        }
        None
    }

    fn kotlin_sources(root: &Path) -> Vec<PathBuf> {
        files_with_extension(root, "kt")
    }

    fn files_with_extension(root: &Path, extension: &str) -> Vec<PathBuf> {
        let mut files = Vec::new();
        if !root.exists() {
            return files;
        }
        let mut pending = vec![root.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in fs::read_dir(directory).expect("source directory is readable") {
                let path = entry.expect("directory entry is readable").path();
                if path.is_dir() {
                    pending.push(path);
                } else if path
                    .extension()
                    .is_some_and(|candidate| candidate == extension)
                {
                    files.push(path);
                }
            }
        }
        files
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
