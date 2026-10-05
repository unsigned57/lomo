// adversarial re-audit (round 7) of the P6-F2 three-layer gate fixes landed by
// 18-修复-门禁三层纵深 (N1–N8). Same verbatim `#[path]` harness as reaudit5/6:
// the shipped gate code under test is `tests/policy/ffi.rs`; `load_graph`,
// `workspace_exports` and `install_app_data_koin_runtime_edge` run against
// tempdir fixtures end to end. A RED result documents a live bypass or a
// coverage hole that survived the N1–N8 repair round.
//
// Probed invariants (cross-surfaces not covered by `ffi_hidden_marker`):
//  - N4 residual: `kotlin_code_view` now pushes `$name` shorthand interpolations into
//    the `code` view verbatim — a literal that ENDS in `$name` abuts the code
//    that follows the closing quote, so `"$forName"(0)` mints `forName(` with
//    zero calls (the `(` after a string literal is invoke-position, never a
//    call on the interpolated name). The `${}` fix reopened a narrower desync.
//  - N6 residual: `ModRefs` resolves every `mod x;`/`#[path]` under
//    `file.parent()` + inline-mod stack — but rustc gives a *non-mod.rs* file
//    its own module directory `name/`. `mod child;` inside `src/evil.rs`
//    compiles `src/evil/child.rs` (scanner looks at `src/child.rs`), and a
//    `#[path]` inside an inline `mod m` inside `src/evil.rs` compiles relative
//    to `src/evil/m/` — outside-`src/` targets substitute a decoy.
//  - N1/N2/N3 consistency probes: `unsafe`/`cfg_attr` mutual nesting, the same
//    wrappers around `macro_use`/`path`, inner-surface `extern crate`.
//  - N5 consistency probes: rebound/undeclared prefixes, directives declared
//    on the element itself, `enabled` edge values, disabled `<application>`.
#![cfg(test)]

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

/// The real git-backed source inventory — kept identical to `ffi_nested_marker`/`ffi_hidden_marker` so `load_graph`
/// coverage checks exercise the shipped enumeration verbatim.
fn source_files(root: &Path, prefix: &str) -> Result<Vec<PathBuf>, String> {
    inventory::source_files(root, prefix)
}

use std::path::{Path, PathBuf};

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
    // N4 residual: `push_shorthand` writes `$name` into `code` verbatim, so a
    // literal ENDING in `$name` glues the identifier to whatever real code
    // follows the closing quote. `"$forName"(0)` contains no call to
    // `forName` — Kotlin evaluates the variable inside the string and then
    // attempts `invoke` on the *string value* — yet the `code` view spells
    // `forName(` and `contains_call_shape` mints it.
    // ------------------------------------------------------------------

    /// Direct lexical probe: `forName` inside `"$forName"(0)` is a variable
    /// read, never a call shape — the `(` that follows the literal cannot turn
    /// a shorthand interpolation into a call on the name.
    #[test]
    fn shorthand_template_names_must_not_mint_call_shapes() {
        let mut minted = Vec::new();
        for source in [
            // shorthand at literal end + `(` immediately after the literal
            "val s = \"$forName\"(0)",
            "val s = \"$getMethod\" (0)",
            // shorthand mid-literal still abuts the post-literal `(`
            "val s = \"x$forName\"(0)",
            // raw-string shorthand — `"""` interpolates the same way
            "val s = \"\"\"$forName\"\"\"(0)",
        ] {
            let code = lomo_xtask::kotlin_code_view(source).code;
            let needle = if code.contains("forName") {
                "forName"
            } else {
                "getMethod"
            };
            let mut rest = code.as_str();
            while let Some(position) = rest.find(needle) {
                let (_, after) = rest.split_at(position + needle.len());
                if after.trim_start().starts_with('(') {
                    minted.push((source, code.clone()));
                    break;
                }
                rest = after;
            }
        }
        assert!(
            minted.is_empty(),
            "BLIND SPOT: a literal ending in `$name` glues the identifier to the \
             `(` after the closing quote — `$forName\"(0)` mints `forName(` in \
             the `code` view although no call to `forName` exists (the `(` is \
             invoke-position on the string value):\n{}",
            minted
                .iter()
                .map(|(src, code)| format!("{src}\n  code view: {code}"))
                .collect::<Vec<_>>()
                .join("\n")
        );
    }

    /// Green control: the `${}` form is safe — the `}` it contributes separates
    /// the name from the following `(`, and a `$name` *inside* `${}` keeps its
    /// shorthand boundary too.
    #[test]
    fn braced_templates_keep_the_call_shape_boundary() {
        for source in [
            "val s = \"${forName}\"(0)",
            "val s = \"${ \"$forName\" }\"(0)",
        ] {
            let code = lomo_xtask::kotlin_code_view(source).code;
            let minted = code.find("forName").is_some_and(|position| {
                let (_, after) = code.split_at(position + "forName".len());
                after.trim_start().starts_with('(')
            });
            assert!(
                !minted,
                "`${{forName}}` must keep `forName` away from the following `(`: {code}"
            );
        }
    }

    /// End to end: two `$shorthand`-glued `(` shapes plus two plain string
    /// payloads satisfy every needle of `install_app_data_koin_runtime_edge`
    /// — zero reflective calls, same phantom edge class as the N4 desync.
    #[test]
    fn the_koin_edge_must_not_be_satisfied_by_shorthand_glued_parens() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             val shapes = \"$forName\"(0) + \"$getMethod\"(0)\n  \
             val names = \"DataModulesKt\" + \"getDataModules\"\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
            "BLIND SPOT: `\"$forName\"(0)`/`\"$getMethod\"(0)` mint the call shapes \
             in `code` — a file with zero reflective calls keeps the phantom \
             app→data Koin edge alive through the `$name` shorthand channel"
        );
    }

    // ------------------------------------------------------------------
    // N6 residual: `ModRefs` models every module file as if it were `mod.rs`
    // — `mod x;` and inline-module children resolve under `file.parent()`.
    // rustc gives a non-`mod.rs` file its own module directory `name/`:
    // `mod child;` inside `src/evil.rs` compiles `src/evil/child.rs`, and a
    // `#[path]` inside inline `mod m` inside `src/evil.rs` resolves relative
    // to `src/evil/m/` (both verified against rustc). The scanner resolves
    // `src/child.rs` / `src/m/<path>` — decoy substitution the moment the
    // rustc target lives outside `src/`.
    // ------------------------------------------------------------------

    /// `mod child;` inside `src/evil.rs` compiles `src/evil/child.rs` under
    /// rustc (module-directory convention), but the scanner looks for
    /// `src/child.rs` — a rustc-valid module shape either resolves to the
    /// wrong file or fails as "resolves to no file".
    #[test]
    fn module_children_of_non_mod_rs_files_must_resolve_under_the_module_dir() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod evil;\n",
        );
        write(root, "crates/lomo-native/src/evil.rs", "mod child;\n");
        write(
            root,
            "crates/lomo-native/src/evil/child.rs",
            "#[export] pub fn child_export() {}\n",
        );
        let surface = workspace_exports(root);
        let surfaced = surface
            .as_ref()
            .is_ok_and(|surface| surface.iter().any(|export| export.rust == "child_export"));
        assert!(
            surfaced,
            "BLIND SPOT: `mod child;` inside `src/evil.rs` compiles \
             `src/evil/child.rs` under rustc — the scanner resolves \
             `src/child.rs` instead: {surface:?}"
        );
    }

    /// A `#[path]` inside an inline `mod m` inside `src/evil.rs` resolves
    /// relative to `src/evil/m/` under rustc — verified with a real build.
    /// The scanner resolves `src/m/<path>` instead: a target outside `src/`
    /// swaps in a clean decoy while the compiled file escapes the inventory.
    #[test]
    fn path_inside_inline_mod_in_a_named_file_must_not_substitute_a_decoy() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod evil;\n",
        );
        write(
            root,
            "crates/lomo-native/src/evil.rs",
            "mod m {\n    #[path = \"../../../hidden/real.rs\"]\n    pub mod inner;\n}\n",
        );
        // rustc resolves `src/evil/m/../../../hidden/real.rs` =
        // `crates/lomo-native/hidden/real.rs` — outside `src/`, so the tree
        // walk never sees it; the mod graph is the only coverage.
        write(
            root,
            "crates/lomo-native/hidden/real.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        // `src/m/` must exist for the kernel to resolve `..` through it —
        // with it in place the scanner silently reads the decoy at
        // `src/m/../../../hidden/real.rs` = `crates/hidden/real.rs`.
        write(
            root,
            "crates/lomo-native/src/m/keeper.rs",
            "pub fn keeper() {}\n",
        );
        write(root, "crates/hidden/real.rs", "pub fn decoy() {}\n");
        let surface = workspace_exports(root);
        let surfaced = surface
            .as_ref()
            .is_ok_and(|surface| surface.iter().any(|export| export.rust == "hidden_export"));
        assert!(
            surfaced,
            "BLIND SPOT: a `#[path]` inside inline `mod m` inside `src/evil.rs` \
             resolves relative to `src/evil/m/` under rustc but `src/m/` under \
             the scanner — an outside-`src/` compiled file escapes while a decoy \
             is scanned: {surface:?}"
        );
    }

    /// Green control: children of a `#[path]`-loaded file DO resolve in the
    /// file's own directory (rustc treats the path file like `mod.rs` there) —
    /// verified against rustc — so the file-dir model itself is correct for
    /// path-loaded modules.
    #[test]
    fn children_of_path_loaded_files_stay_in_the_file_dir() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n#[path = \"../shared/evil.txt\"]\nmod evil;\n",
        );
        write(root, "crates/lomo-native/shared/evil.txt", "mod child;\n");
        write(
            root,
            "crates/lomo-native/shared/child.rs",
            "#[export] pub fn child_export() {}\n",
        );
        let surface = workspace_exports(root).expect("mod graph resolves");
        assert!(
            surface.iter().any(|export| export.rust == "child_export"),
            "children of a `#[path]` file resolve in the file's own directory — \
             `shared/child.rs` must surface"
        );
    }

    // ------------------------------------------------------------------
    // N1/N2/N3 consistency: the `unsafe`/`cfg_attr` mutual recursion must hold
    // for every wrapper-sensitive marker, on both surfaces.
    // ------------------------------------------------------------------

    /// Cross-wrapper combinations still carry the marker — `unsafe` wrapping
    /// `cfg_attr`, `cfg_attr` wrapping `unsafe`, and the same nesting around
    /// `macro_use`/`path` — every form must stay accounted.
    #[test]
    fn nested_wrapper_combinations_stay_accounted() {
        let mut smuggled = Vec::new();
        // `#[unsafe(cfg_attr(c, no_mangle))]` — rustc-invalid, but the scanner
        // must still see the marker rather than silently pass.
        for source in [
            "#[unsafe(cfg_attr(test, no_mangle))]\npub fn hidden() {}",
            "#[cfg_attr(test, unsafe(cfg_attr(unix, no_mangle)))]\npub fn hidden() {}",
            "#[unsafe(no_mangle, export_name = \"aliased\")]\npub fn hidden() {}",
            "#[unsafe(unsafe(no_mangle))]\npub fn hidden() {}",
        ] {
            let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
            if !accounted {
                smuggled.push(source);
            }
        }
        assert!(
            smuggled.is_empty(),
            "BLIND SPOT: `unsafe`/`cfg_attr` cross-nesting drops the marker:\n{}",
            smuggled.join("\n")
        );
    }

    /// `macro_use` and `path` wrapped by the same `unsafe`/`cfg_attr` stack —
    /// `meta_is_macro_use`/`meta_wraps_path` must recurse identically.
    #[test]
    fn wrapper_recursion_stays_consistent_for_macro_use_and_path() {
        for source in [
            "#[cfg_attr(test, macro_use)]\nextern crate evil;",
            "#[unsafe(macro_use)]\nextern crate evil;",
            "#[cfg_attr(test, unsafe(macro_use))]\nextern crate evil;",
            // inner surface — `extern crate` is legal inside a fn body too
            "fn f() { #[macro_use] extern crate evil; }",
            "fn f() { #[cfg_attr(test, macro_use)] extern crate evil; }",
        ] {
            assert!(
                exports(source).is_err(),
                "BLIND SPOT: wrapped `macro_use extern crate` escapes the textual-\
                 macro trust boundary: {source}"
            );
        }
    }

    /// `#[unsafe(path = "x")]`/`#[cfg_attr(c, unsafe(path = "x"))]` on a module —
    /// `meta_wraps_path` treats them as carrying `path` (fail closed) rather
    /// than resolving a decoy.
    #[test]
    fn wrapped_path_attributes_stay_fail_closed() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        for attr in [
            "#[unsafe(path = \"hidden.rs\")]",
            "#[cfg_attr(unix, unsafe(path = \"hidden.rs\"))]",
            "#[unsafe(cfg_attr(unix, path = \"hidden.rs\"))]",
        ] {
            write(
                root,
                "crates/lomo-native/src/lib.rs",
                &format!("#[export] pub fn legit() {{}}\n{attr}\nmod m;\n"),
            );
            write(root, "crates/lomo-native/src/m.rs", "pub fn decoy() {}\n");
            write(
                root,
                "crates/lomo-native/src/hidden.rs",
                "#[export] pub fn hidden_export() {}\n",
            );
            let result = workspace_exports(root);
            let leaked = result
                .is_ok_and(|surface| !surface.iter().any(|export| export.rust == "hidden_export"));
            assert!(
                !leaked,
                "BLIND SPOT: `{attr}` lets `mod m` resolve `m.rs` while the real \
                 path target escapes — cfg-conditional paths must fail closed"
            );
        }
    }

    /// Textual-macro and foreign-item surfaces stay fail-closed: `include!`,
    /// `extern "C"` blocks, `global_asm!`, `macro_rules!`, and `#[macro_use]`
    /// in any arg shape are all unverifiable surfaces and must error, while a
    /// real attribute like `#[link]` on `extern crate` carries no marker.
    #[test]
    fn unverifiable_surfaces_stay_rejected_and_real_attrs_pass() {
        for source in [
            "include!(\"generated.rs\");",
            "global_asm!(\"nop\");",
            "macro_rules! sneaky { () => {} }",
            "#[macro_use(surprise)] extern crate evil;",
            "#[macro_use] extern crate evil;",
            "extern \"C\" { #[export] fn smuggle(); }",
        ] {
            assert!(
                exports(source).is_err(),
                "unverifiable surfaces must fail closed: {source}"
            );
        }
        for source in [
            "#[link(name = \"ssl\")] extern crate evil;",
            "extern \"C\" { fn imported(); }",
        ] {
            assert!(
                exports(source).is_ok(),
                "`#[link]`/`extern` import declarations are honest surfaces — \
                 no marker, no obligations: {source}"
            );
        }
    }

    /// `#[path]` declarations inside a `fn` body join the compile surface —
    /// inner items are still module declarations under rustc.
    #[test]
    fn path_modules_inside_fn_bodies_stay_enumerated() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nfn f() { #[path = \"../hidden.rs\"] mod m; }\n",
        );
        write(
            root,
            "crates/lomo-native/hidden.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        let surface = workspace_exports(root).expect("mod graph resolves");
        assert!(
            surface.iter().any(|export| export.rust == "hidden_export"),
            "a `#[path]` module inside a fn body is a compile unit — its exports \
             must surface"
        );
    }

    /// Green control: `#[macro_use] mod` is safe-by-construction — the only
    /// macros it can import live in the scanned module itself, where a
    /// `macro_rules!` is already rejected. The asymmetry with `extern crate`
    /// is sound and must stay open.
    #[test]
    fn macro_use_on_an_inline_mod_is_not_a_foreign_channel() {
        assert!(
            exports("#[macro_use]\nmod m { pub fn f() {} }").is_ok(),
            "`#[macro_use]` on a scanned module cannot import uninspectable \
             macros — the module's own `macro_rules!` is already rejected"
        );
        assert!(
            exports("mod m { macro_rules! sneaky { () => {} } }").is_err(),
            "the macro source inside the module stays rejected"
        );
    }

    /// File-level `#![unsafe(...)]` and `cfg_attr`-wrapped inner attributes
    /// hit the same `file.attrs` check the N3 fix added.
    #[test]
    fn file_level_wrapped_inner_attributes_stay_rejected() {
        for source in [
            "#![unsafe(no_mangle)]\nfn f() {}",
            "#![cfg_attr(test, unsafe(export))]\nfn f() {}",
            "#![unsafe(export)]\nfn f() {}",
        ] {
            assert!(
                exports(source).is_err(),
                "wrapped file-level markers must hit the `file.attrs` check: {source}"
            );
        }
    }

    // ------------------------------------------------------------------
    // N5 consistency: URI binding must behave like the merger in both
    // directions — rebound-to-foreign prefixes carry no directive (mints),
    // element-local declarations bind (strips), unproven enabled states stay
    // unproven (no mint).
    // ------------------------------------------------------------------

    /// Builds a minimal schema-3 fact tree over `root` — identical shape to the
    /// reaudit5/6 harness.
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

    /// Shared fixture body — identical to `ffi_nested_marker`/`ffi_hidden_marker`.
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

    /// A prefix declared on the element itself — `xmlns:t` on `<activity>` —
    /// binds for that element's own attributes (XML scoping), so `t:node` and
    /// `a:enabled` declared locally strip/disable exactly like `tools:`/
    /// `android:` would.
    #[test]
    fn element_local_namespace_declarations_still_strip_and_disable() {
        let mut minted = Vec::new();
        for manifest in [
            // `xmlns:t` declared on the activity itself — `t:node` binds locally
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" \
             xmlns:t=\"http://schemas.android.com/tools\" t:node=\"remove\"/>\n\
             </application>\n</manifest>\n",
            // `xmlns:t` on `<manifest>` reaching a `<manifest>`-level remove
            // through a renamed prefix
            "<manifest xmlns:t=\"http://schemas.android.com/tools\" t:node=\"remove\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\"/>\n\
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
            "namespace declarations apply to their own element and inherit — \
             element-local `t:node=\"remove\"` and manifest-level renamed \
             removes must strip:\n{}",
            minted.join("\n---\n")
        );
    }

    /// A prefix rebound to a foreign URI carries no directive — `tools:` bound
    /// to a non-tools URI makes `tools:node="remove"` an ordinary attribute,
    /// exactly like the merger reads it: the component still instantiates.
    /// Both directions of the URI model must hold (consistency, not strictness).
    #[test]
    fn prefixes_bound_to_foreign_uris_carry_no_directive() {
        let mut dropped = Vec::new();
        for manifest in [
            // `tools:` rebound to a foreign URI — `tools:node` is not the
            // merge directive, the activity must still mint
            "<manifest xmlns:tools=\"http://evil.example/ns\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" tools:node=\"remove\"/>\n\
             </application>\n</manifest>\n",
            // `xmlns:t` never declared — `t:node` is an unbound prefix, not a
            // directive (the seeded `tools`/`android` defaults do not cover `t`)
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" t:node=\"remove\"/>\n\
             </application>\n</manifest>\n",
        ] {
            let (dir, facts_root) = ffi_fixture(manifest);
            write_facts(dir.path(), &facts_root, true);
            let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
            let (exports, _) = dead_export_surface();
            if !contract_violations(&exports, &generated, &graph).is_empty() {
                dropped.push(manifest);
            }
        }
        assert!(
            dropped.is_empty(),
            "a prefix bound to a foreign URI — or never bound — is not the tools \
             directive: the merger would ignore it too, so the component must \
             still mint:\n{}",
            dropped.join("\n---\n")
        );
    }

    /// `android:enabled` on `<application>` disables the whole subtree the
    /// same way `tools:node="remove"` does — children of a disabled
    /// application are never instantiated. Non-literal values (`""`, `"True"`,
    /// `"@dimen/x"`) stay unproven the same direction.
    #[test]
    fn disabled_applications_and_unproven_enabled_values_never_mint() {
        let mut minted = Vec::new();
        for manifest in [
            // disabled application — children must not mint
            "<manifest>\n<application android:name=\".LomoApplication\" \
             android:enabled=\"false\">\n\
             <activity android:name=\".ZombieActivity\"/>\n\
             </application>\n</manifest>\n",
            // empty enabled value — unproven, not "true"
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" android:enabled=\"\"/>\n\
             </application>\n</manifest>\n",
            // non-literal resource indirection at the application level
            "<manifest>\n<application android:name=\".LomoApplication\" \
             android:enabled=\"@bool/enabled\">\n\
             <activity android:name=\".ZombieActivity\"/>\n\
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
            "a disabled `<application>` disables its subtree and unproven enabled \
             states must not mint:\n{}",
            minted.join("\n---\n")
        );
    }

    /// Strict-boundary locks: unmodeled merge directives (`removeAll` and
    /// friends) and `activity-alias` names fail closed rather than being
    /// silently modeled.
    #[test]
    fn unmodeled_manifest_shapes_stay_fail_closed() {
        // `tools:node="removeAll"` is merge semantics the scanner does not
        // model — it must surface an error, not guess a mint or a strip.
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" \
             xmlns:tools=\"http://schemas.android.com/tools\" \
             tools:node=\"removeAll\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        assert!(
            load_graph(dir.path(), &facts_root).is_err(),
            "an unmodeled `tools:node` value must fail closed"
        );

        // `<activity-alias>` names are not `<activity>` names — the alias's
        // target element is the component that instantiates.
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity-alias android:name=\".ZombieActivity\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "`<activity-alias>` is not a component element — it must not mint \
             an entry root"
        );
    }
}
