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
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
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
            let before = text[..start].chars().next_back();
            let end = start + identifier.len();
            let after = text[end..].chars().next();
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
        assert!(native.contains("crate-type = [\"staticlib\", \"rlib\"]"));
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
        // Conversion-only native facade is required before and after cutover.
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

        // data must never import Rust crate names or raw owner IR type tokens (bridge DTOs only).
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

        let memo_module = read("data/src/di/MemoRepositoryModule.kt");
        if stage_two_markdown_cutover_complete() {
            // After P2-09 atomic cutover: sole production owner is Rust via workspace projector.
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
            // Kotlin may consume domain MarkdownRender* IR types; must not re-own parsers.
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
            return;
        }

        // Pre-cutover dark-build: production Kotlin outside data must not consume workspace IR yet,
        // and DI must still bind Kotlin MarkdownParser as the sole live authority.
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
            "rust/workspace/src",
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
