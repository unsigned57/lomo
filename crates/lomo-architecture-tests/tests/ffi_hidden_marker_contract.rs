// adversarial re-audit (round 6) of the P5-F2 gate-depth fixes landed by 16-修复-门禁纵深残留.
//
// Same harness as `ffi_nested_marker`.rs: the shipped gate code under test is
// `tests/policy/ffi.rs`, included verbatim via `#[path]`; `load_graph`/`workspace_exports`
// run against tempdir fixtures end to end. A RED result documents a live bypass or
// coverage hole that survived the R1–R7 repair round.
//
// Probed invariants (new adversarial surfaces, not re-runs of the R1–R7 shapes):
//  - R1 residual: `meta_is_export` recognises bare `no_mangle`/`export_name`, but
//    edition-2024 rustc only honours them *wrapped* — `#[unsafe(no_mangle)]` and
//    `#[unsafe(export_name)]` mint the same global symbol while reading as an
//    `unsafe` attribute the scanner does not open. `#[macro_use] extern crate`
//    re-extends the bare-macro trust boundary exactly like the `use` glob the
//    fix rejected. `#![export]` file inner attributes bypass `visit_item`.
//  - R2 residual: XML namespace semantics bind by URI, not literal prefix —
//    a renamed `tools:`/`android:` prefix (`xmlns:t`/`xmlns:a`) carries the same
//    merge/disable directives while `attribute()` matches the literal key.
//    `<manifest tools:node="remove">` drops the whole subtree the scanner still
//    registers. `android:enabled="@bool/x"` is an unverifiable enabled state.
//  - R3 residual: `kotlin_code_view` masks every literal *whole*, but Kotlin string
//    templates execute code — `"${ "forName(" }"` desynchronises the quote walk
//    so literal content lands in the `code` view and re-mints call shapes, and
//    `"${ /* needle */ }"` leaks a comment into the `evidence` view.
//  - R4 residual: `native_source_tree` enumerates mod-graph targets but drops
//    non-`.rs` `#[path]` targets from parsing — rustc compiles them as Rust
//    regardless of extension — and ignores `#[cfg_attr(_, path = "...")]`, so a
//    decoy `name.rs` can stand in for the real compile target.
#![cfg(test)]

use std::path::{Path, PathBuf};

#[derive(Debug)]
struct Violation {
    #[expect(dead_code, reason = "fields mirror the real policy Violation payload")]
    rule: &'static str,
    #[expect(dead_code, reason = "fields mirror the real policy Violation payload")]
    subject: String,
    #[expect(dead_code, reason = "fields mirror the real policy Violation payload")]
    detail: String,
}

impl Violation {
    fn new(rule: &'static str, subject: &str, detail: String) -> Self {
        Self {
            rule,
            subject: subject.to_owned(),
            detail,
        }
    }
}

/// `policy::inventory.rs` names `super::KOTLIN_MODULES`; the reaudit harness never calls
/// `owned_kotlin_module`, so the list content is inert — it only needs to resolve.
#[expect(
    dead_code,
    reason = "referenced by the verbatim-included inventory module"
)]
const KOTLIN_MODULES: &[&str] = &["apps/android/app"];

#[expect(
    dead_code,
    reason = "the included inventory ships the full surface; probes call a subset"
)]
#[path = "policy/inventory.rs"]
mod inventory;

/// The real git-backed source inventory — kept identical to `ffi_nested_marker` so `load_graph`
/// coverage checks exercise the shipped enumeration verbatim.
fn source_files(root: &Path, prefix: &str) -> Result<Vec<PathBuf>, String> {
    inventory::source_files(root, prefix)
}

#[path = "policy/ffi.rs"]
mod ffi;

#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures fail closed with explicit diagnostics"
)]
mod tests {
    use super::ffi::{
        Export, ResolvedGraph, contract_violations, exports, install_app_data_koin_runtime_edge,
        load_graph, workspace_exports,
    };
    use sha2::{Digest as _, Sha256};
    use std::collections::BTreeSet;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::process::Command;
    use tempfile::TempDir;

    fn surface(source: &str) -> BTreeSet<Export> {
        exports(source).expect("fixture parses")
    }

    fn dead_export_surface() -> (BTreeSet<Export>, BTreeSet<String>) {
        (
            surface("#[export] pub fn dead_export() {}"),
            BTreeSet::from(["com.lomo.nativebridge.deadExport".to_owned()]),
        )
    }

    fn write(root: &Path, relative: &str, content: &str) -> PathBuf {
        let path = root.join(relative);
        fs::create_dir_all(path.parent().expect("fixture parent")).expect("fixture mkdir");
        fs::write(&path, content).expect("fixture file");
        path
    }

    fn git_init(root: &Path) {
        let output = Command::new("git")
            .args(["init", "-q"])
            .current_dir(root)
            .output()
            .expect("git init");
        assert!(output.status.success(), "git init must succeed");
    }

    // ------------------------------------------------------------------
    // R1 residual: the edition-2024 `#[unsafe(...)]` attribute wrapper.
    // rustc 2024 moved `no_mangle`/`export_name` into the unsafe-attribute
    // family — they are only legal as `#[unsafe(no_mangle)]`. `meta_is_export`
    // matches the *outer* path (`unsafe`), never the wrapped argument, so the
    // exact global-symbol marker the gate claims to model slips through at
    // every position the fix hardened.
    // ------------------------------------------------------------------

    /// `#[unsafe(no_mangle)]` / `#[unsafe(export_name = "...")]` emit unmanaged
    /// global symbols — the same obligation class as `#[export]` — yet parse as
    /// an `unsafe` list attribute the scanner never opens. Every surface the R1
    /// fix hardened (outer item, inner item, impl member, local) accepts them
    /// silently.
    #[test]
    fn unsafe_wrapped_symbol_markers_must_not_pass_silently() {
        let mut smuggled = Vec::new();
        for source in [
            "#[unsafe(no_mangle)]\npub fn hidden() {}",
            "#[unsafe(export_name = \"hidden_symbol\")]\npub fn hidden() {}",
            "#[cfg_attr(test, unsafe(no_mangle))]\npub fn hidden() {}",
            // the same wrapped marker on the inner surface the R1 fix added
            "fn wrapper() { #[unsafe(no_mangle)] pub fn hidden() {} }",
            "fn wrapper() { let _ = #[unsafe(no_mangle)] 0; }",
            "pub struct H;\nimpl H { #[unsafe(no_mangle)] pub fn hidden(&self) {} }",
        ] {
            let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
            if !accounted {
                smuggled.push(source);
            }
        }
        assert!(
            smuggled.is_empty(),
            "BLIND SPOT: `#[unsafe(no_mangle)]`/`#[unsafe(export_name)]` — the only \
             legal spellings of these markers under edition 2024 — read as an \
             `unsafe` attribute the scanner never opens, so the symbol-emitting \
             marker the gate claims to model escapes at every position:\n{}",
            smuggled.join("\n")
        );
    }

    /// `#[macro_use] extern crate` imports every `#[macro_export]` macro of that
    /// crate into textual scope — a foreign `panic!`/`format!` then resolves
    /// *instead of* the built-in the allow-list trusts. The fix rejected the
    /// `use`-tree shadowing channels but left this one: an `extern crate` is a
    /// non-callable item whose only check is the export-marker scan.
    #[test]
    fn macro_use_extern_crates_must_not_extend_the_trusted_macro_set() {
        assert!(
            exports(
                "#[macro_use]\nextern crate evil;\nfn f() { panic!(\"resolves to evil::panic\"); }"
            )
            .is_err(),
            "BLIND SPOT: `#[macro_use] extern crate` imports foreign `panic!`/`format!` \
             macros into textual scope — the bare-macro trust boundary the R1 fix \
             built becomes unverifiable, exactly like the rejected `use` glob"
        );
    }

    /// `exports` drives `visit_file`, which walks `file.attrs` through the
    /// default `visit_attribute` — no marker check runs on `#![...]` inner
    /// attributes. `#![export]`/`#![no_mangle]` at file level are unmodelable
    /// markers the contract says must fail closed; they pass silently.
    #[test]
    fn file_level_inner_attributes_must_not_pass_silently() {
        let mut smuggled = Vec::new();
        for source in [
            "#![export]\nfn f() {}",
            "#![no_mangle]\nfn f() {}",
            "#![cfg_attr(test, export)]\nfn f() {}",
            "mod m { #![export]\nfn f() {} }",
        ] {
            if exports(source).is_ok() {
                smuggled.push(source);
            }
        }
        assert!(
            smuggled.is_empty(),
            "BLIND SPOT: `#![...]` inner attributes never reach a marker check — \
             `visit_file` walks them through the default `visit_attribute`:\n{}",
            smuggled.join("\n")
        );
    }

    /// Green control: the built-in expression macros still pass at statement and
    /// expression position, shadowing `use` channels stay rejected, and an
    /// unknown macro still fails closed.
    #[test]
    fn expression_macro_boundary_stays_intact() {
        assert!(
            exports(
                "fn f() { panic!(); assert!(true); let v = vec![1]; std::println!(\"x\"); \
                 ::core::assert!(true); matches!(v.len(), 1); }"
            )
            .is_ok(),
            "trusted expression macros must still pass"
        );
        for source in [
            "use evil::*;\nfn f() { panic!(); }",
            "use evil::x as panic;\nfn f() { panic!(); }",
            "fn f() { wire!(); }",
            "fn f() { thread_local!(); }",
            "fn f() { include!(\"x.rs\"); }",
        ] {
            assert!(
                exports(source).is_err(),
                "unverifiable macro channel must stay rejected: {source}"
            );
        }
    }

    // ------------------------------------------------------------------
    // R3 residual: `kotlin_code_view` models literals as opaque — but `${}` inside a
    // Kotlin string executes *code*, and nested quotes inside the interpolation
    // desynchronise the single quote-to-quote walk. Literal content lands in
    // the `code` view (re-minting the exact call shapes R3 was fixing) and a
    // comment inside `${}` lands in the `evidence` view.
    // ------------------------------------------------------------------

    /// `"${ "forName(" }"` is a pure string payload — `forName(` is literal
    /// content of the nested string, never a call. The lexer ends the outer
    /// literal at the nested quote, so the payload lands in the `code` view and
    /// `contains_call_shape` mints a phantom call — the R3 hole one level down.
    #[test]
    fn template_interpolation_must_not_reintroduce_string_call_shapes() {
        let mut leaked = Vec::new();
        for (source, needle) in [
            ("val s = \"${ \"forName(\" }\"", "forName"),
            ("val s = \"${ \"getMethod(\" }\"", "getMethod"),
            ("val s = \"${ f(\"DeadUseCase\") }\"", "DeadUseCase"),
        ] {
            if lomo_xtask::kotlin_code_view(source).code.contains(needle) {
                leaked.push(source);
            }
        }
        assert!(
            leaked.is_empty(),
            "BLIND SPOT: nested literals inside `${{ }}` desync the lexer — string \
             payload lands in the `code` view and re-mints call shapes/identifiers \
             that never exist as code:\n{}",
            leaked.join("\n")
        );
    }

    /// End to end: four pure string payloads — two inside `${ }` interpolations
    /// for the call shapes, two ordinary literals for the names — satisfy every
    /// needle of `install_app_data_koin_runtime_edge` with zero real calls.
    #[test]
    fn the_koin_edge_must_not_be_satisfied_by_template_desync_payloads() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             val shapes = \"${ \"forName(\" }\" + \"${ \"getMethod(\" }\"\n  \
             val names = \"DataModulesKt\" + \"getDataModules\"\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
            "BLIND SPOT: template-desynced string payloads re-mint `forName(`/`getMethod(` \
             in the `code` view — a file with zero reflective calls keeps the \
             phantom app→data Koin edge alive"
        );
    }

    /// A comment inside `"${ /* ... */ }"` is a *comment* (the interpolation is
    /// expression position), but the lexer treats it as literal content — it
    /// survives into the `evidence` view and can carry the name needles.
    #[test]
    fn comments_inside_template_interpolation_must_not_leak_into_evidence() {
        let text =
            lomo_xtask::kotlin_code_view("val s = \"${ /* DataModulesKt getDataModules */ x }\"");
        assert!(
            !(text.evidence.contains("DataModulesKt") || text.evidence.contains("getDataModules")),
            "BLIND SPOT: a comment inside a `${{ }}` interpolation is preserved as \
             literal payload in the `evidence` view — comment-carried names can \
             satisfy the installer needles"
        );
    }

    /// Strict direction, corrected semantics: a *real* `Class.forName(...)`
    /// reflective install written inside a `${ }` interpolation is executable
    /// code — Kotlin evaluates it — so the modeled edge must see the call
    /// shapes, not mask them. Asserting the old "masked" outcome would lock
    /// the interpolation blind spot back in as expected behavior.
    #[test]
    fn a_real_install_inside_a_template_is_masked_too() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {\n    \
             val m = \"${Class.forName(\"com.lomo.data.di.DataModulesKt\").getMethod(\"getDataModules\")}\"\n  }\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_ok(),
            "real calls inside `${{ }}` are code — the edge must see them like \
             top-level calls, not mask them"
        );
    }

    // ------------------------------------------------------------------
    // R4 residual: `native_source_tree` resolves `#[path]` targets but then
    // filters the inventory on `.rs` — rustc compiles a `#[path]` target as
    // Rust whatever its extension — and `#[cfg_attr(_, path = ...)]` is not
    // modelled at all, so a clean decoy `name.rs` can stand in for the real
    // compile target.
    // ------------------------------------------------------------------

    /// `#[path = "../shared/evil.txt"] mod evil;` — rustc reads the target as
    /// Rust source regardless of extension; the inventory pushes it, then skips
    //  it for parsing because it is not `.rs` — its `#[export]` items vanish.
    #[test]
    fn path_targets_with_non_rs_extensions_must_not_escape_the_inventory() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n#[path = \"../shared/evil.txt\"]\nmod evil;\n",
        );
        write(
            root,
            "crates/lomo-native/shared/evil.txt",
            "#[export] pub fn hidden_export() {}\n",
        );
        let result = workspace_exports(root);
        let surfaced = result.map_or(true, |surface| {
            surface.iter().any(|export| export.rust == "hidden_export")
        });
        assert!(
            surfaced,
            "BLIND SPOT: a `#[path]` target that is not `.rs` still enters rustc's \
             compile surface but is dropped from export scanning — the mod-graph \
             fix filtered on extension, not on compilation"
        );
    }

    /// `#[cfg_attr(unix, path = "../shared/evil.rs")] mod evil;` — the scanner
    /// ignores `cfg_attr`-wrapped `path`, resolves `mod evil` to a decoy
    /// `src/evil.rs`, and never enumerates the file rustc actually compiles.
    #[test]
    fn cfg_attr_path_targets_must_not_substitute_a_decoy() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n\
             #[cfg_attr(unix, path = \"../shared/evil.rs\")]\nmod evil;\n",
        );
        write(
            root,
            "crates/lomo-native/src/evil.rs",
            "pub fn decoy() {}\n",
        );
        write(
            root,
            "crates/lomo-native/shared/evil.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        let result = workspace_exports(root);
        let surfaced = result.map_or(true, |surface| {
            surface.iter().any(|export| export.rust == "hidden_export")
        });
        assert!(
            surfaced,
            "BLIND SPOT: `#[cfg_attr(_, path = ...)]` swaps the module target the \
             scanner resolves — a clean decoy `name.rs` is scanned while the real \
             `#[path]` file escapes the inventory"
        );
    }

    /// Green control: a `#[path]` target inside `src/` still enumerates, and a
    /// `#[path]` escape outside the repository still fails closed.
    #[test]
    fn mod_graph_controls_stay_intact() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n#[path = \"sub/extra.rs\"]\nmod extra;\n",
        );
        write(
            root,
            "crates/lomo-native/src/sub/extra.rs",
            "#[export] pub fn extra_export() {}\n",
        );
        let surface = workspace_exports(root).expect("surface computes");
        assert!(
            surface.iter().any(|export| export.rust == "extra_export"),
            "a `#[path]` target inside src/ must still mint its obligation"
        );

        let outside_dir = tempfile::tempdir().expect("outside fixture");
        write(
            outside_dir.path(),
            "evil.rs",
            "#[export] pub fn ghost() {}\n",
        );
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "crates/lomo-native/src/lib.rs",
            &format!(
                "#[export] pub fn legit() {{}}\n#[path = \"{}\"]\nmod evil;\n",
                outside_dir.path().join("evil.rs").display()
            ),
        );
        assert!(
            workspace_exports(dir.path()).is_err(),
            "a `#[path]` escape outside the repository must still fail closed"
        );
    }

    // ------------------------------------------------------------------
    // R2 residual: namespace semantics are URI-bound, not literal-prefix-bound
    // — a renamed `tools:`/`android:` prefix carries the same directives the
    // literal `attribute()` key match cannot see; `<manifest tools:node>` drops
    // the subtree; a resource-indirected `enabled` is unverifiable state.
    // ------------------------------------------------------------------

    /// Builds a minimal schema-3 fact tree over `root` — identical shape to the
    /// reaudit5 harness.
    fn write_facts(root: &Path, facts_root: &Path, zombie_rooted: bool) {
        let sha = |path: &Path| -> String {
            format!(
                "{:x}",
                Sha256::digest(fs::read(path).expect("fixture read"))
            )
        };
        let app_dir = facts_root.join("app");
        fs::create_dir_all(&app_dir).expect("facts dir");

        let app_kt = root.join("apps/android/app/src/LomoApplication.kt");
        let zombie_kt = root.join("apps/android/app/src/Zombie.kt");
        let bridge_kt = root.join("apps/android/native-bindings/src/LomoNativeBridge.kt");

        let app_facts = format!(
            "{{\"schema_version\":3,\"path\":\"{}\",\"source_digest\":\"{}\",\
             \"declarations\":[\"com.lomo.app.LomoApplication\",\"com.lomo.app.LomoApplication.onCreate\"],\
             \"classes\":[\"com.lomo.app.LomoApplication\"],\
             \"roots\":[\"com.lomo.app.LomoApplication\"],\
             \"edges\":[[\"com.lomo.app.LomoApplication\",\"com.lomo.app.LomoApplication.onCreate\"]],\
             \"references\":[]}}",
            app_kt.display(),
            sha(&app_kt)
        );
        fs::write(app_dir.join("a.json"), app_facts).expect("facts");

        let zombie_facts = format!(
            "{{\"schema_version\":3,\"path\":\"{}\",\"source_digest\":\"{}\",\
             \"declarations\":[\"com.lomo.app.ZombieActivity\",\"com.lomo.app.ZombieActivity.leak\"],\
             \"classes\":[\"com.lomo.app.ZombieActivity\"],\
             \"roots\":[{}],\
             \"edges\":[[\"com.lomo.app.ZombieActivity\",\"com.lomo.app.ZombieActivity.leak\"],\
             [\"com.lomo.app.ZombieActivity.leak\",\"com.lomo.nativebridge.deadExport\"]],\
             \"references\":[]}}",
            zombie_kt.display(),
            sha(&zombie_kt),
            if zombie_rooted {
                "\"com.lomo.app.ZombieActivity\""
            } else {
                ""
            }
        );
        fs::write(app_dir.join("z.json"), zombie_facts).expect("facts");

        let generated = format!(
            "{{\"schema_version\":3,\"path\":\"{}\",\"source_digest\":\"{}\",\
             \"declarations\":[\"com.lomo.nativebridge.deadExport\"]}}",
            bridge_kt.display(),
            sha(&bridge_kt)
        );
        fs::write(app_dir.join("generated.json"), generated).expect("facts");

        for module in ["app", "data", "domain", "ui-components"] {
            let directory = facts_root.join(module);
            fs::create_dir_all(&directory).expect("module facts dir");
            fs::write(
                facts_root.join(format!("index-{module}.json")),
                format!(
                    "{{\"schema_version\":1,\"directory\":\"{}\"}}",
                    directory.display()
                ),
            )
            .expect("index");
        }
    }

    /// Shared fixture body — identical to `ffi_nested_marker`.
    fn ffi_fixture(manifest_inner: &str) -> (TempDir, PathBuf) {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "apps/android/app/module.yaml",
            "product: android/app\nsettings:\n  android:\n    namespace: com.lomo.app\n",
        );
        write(
            root,
            "apps/android/app/src/AndroidManifest.xml",
            manifest_inner,
        );
        write(
            root,
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {\n    \
             Class.forName(\"com.lomo.data.di.DataModulesKt\").getMethod(\"getDataModules\")\n  }\n}\n",
        );
        write(
            root,
            "apps/android/app/src/Zombie.kt",
            "package com.lomo.app\nclass ZombieActivity { fun leak() = Unit }\n",
        );
        write(
            root,
            "apps/android/native-bindings/src/LomoNativeBridge.kt",
            "package com.lomo.nativebridge\nfun deadExport() = Unit\n",
        );
        git_init(root);
        let facts_root = root.join("facts");
        fs::create_dir_all(&facts_root).expect("facts root");
        (dir, facts_root)
    }

    /// The merger and platform bind attributes by *namespace URI*, not by the
    /// literal prefix — `xmlns:t="…/tools"` makes `t:node="remove"` the same
    /// merge directive, and `xmlns:a="…/apk/res/android"` makes `a:enabled` the
    /// same disable. The scanner matches the literal key, so renamed prefixes
    /// carry live directives it never sees.
    #[test]
    fn renamed_namespace_prefixes_must_not_evade_merge_semantics() {
        let mut minted = Vec::new();
        for manifest in [
            // renamed tools prefix — `t:node="remove"` is `tools:node="remove"`
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    \
             xmlns:t=\"http://schemas.android.com/tools\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" t:node=\"remove\"/>\n\
             </application>\n</manifest>\n",
            // second alias for the android URI — `a:enabled` is `android:enabled`
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    \
             xmlns:a=\"http://schemas.android.com/apk/res/android\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" a:enabled=\"false\"/>\n\
             </application>\n</manifest>\n",
        ] {
            let (dir, facts_root) = ffi_fixture(manifest);
            write_facts(dir.path(), &facts_root, true);
            let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
            let (exports, _) = dead_export_surface();
            if contract_violations(&exports, &generated, &graph).is_empty() {
                minted.push(manifest);
            }
        }
        assert!(
            minted.is_empty(),
            "BLIND SPOT: namespace prefixes are aliases — `t:node=\"remove\"` and \
             `a:enabled=\"false\"` are the same merge/disable directives under a \
             renamed prefix, yet literal-key matching mints the component:\n{}",
            minted.join("\n---\n")
        );
    }

    /// `tools:node="remove"` on `<manifest>` removes the merged manifest's
    /// entire node set — every child the scanner still registers is dropped
    /// from the packaged manifest.
    #[test]
    fn a_manifest_level_remove_must_not_leave_registered_children() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest xmlns:tools=\"http://schemas.android.com/tools\" tools:node=\"remove\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `tools:node=\"remove\"` on `<manifest>` drops the whole \
             component set at merge — children are still minted as instantiated"
        );
    }

    /// `android:enabled="@bool/enabled"` delegates the enabled state to a
    /// resource the scanner cannot read — the component may never be
    /// instantiated. Literal `="false"` was fixed; indirection was left minting
    /// unverifiable state.
    #[test]
    fn a_resource_indirected_enabled_state_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" android:enabled=\"@bool/enabled\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `android:enabled=\"@bool/…\"` is an unverifiable enabled \
             state — the gate cannot prove the component is instantiated, yet \
             mints it"
        );
    }

    /// Strict-direction controls: an `<activity>` under a non-application
    /// parent still does not mint, and a normal registered component still
    /// validates.
    #[test]
    fn manifest_semantics_stay_intact() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\"/>\n</application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            contract_violations(&exports, &generated, &graph).is_empty(),
            "a manifest-registered activity is a legitimate entry point"
        );

        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<wrapper><application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\"/>\n</application></wrapper>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "a component nested under a non-application parent must not mint"
        );
    }
}
