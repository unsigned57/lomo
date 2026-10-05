// adversarial re-audit (round 8) of the P7-F2 four-layer gate fixes landed by
// 20-修复-门禁四层纵深 (F1–F7 + B1/B2). Same verbatim `#[path]` harness as
// reaudit5/6/7: the shipped gate code under test is `tests/policy/ffi.rs`;
// `workspace_exports` and `install_app_data_koin_runtime_edge` run against
// tempdir fixtures end to end. A RED result documents a live bypass or a
// coverage hole that survived the P7-F2 repair round.
//
// Probed invariants (cross-surfaces not covered by `ffi_module_path`):
//  - F2 residual: rustc gives a `mod` inside a *block* (`fn`/const bodies)
//    a different base directory than an item-level `mod` — inside a block the
//    chain roots at the *file's directory*, never at `name/`. Verified with
//    rustc 1.98: `fn f() { mod m { #[path="…"] mod i; } }` inside `src/evil.rs`
//    compiles `src/m/…`, not `src/evil/m/…`. The scanner roots the inline
//    stack at `mod_dir` (`src/evil/m/…`) — the same decoy-substitution channel
//    as F2 survives through the block surface.
//  - F3 residual: `parsed`'s dual-dir claim rejects `mod a;` +
//    `#[path="a.rs"] mod b;` — rustc-legal aliasing (verified: compiles clean)
//    — a fail-closed false positive on a real module shape.
//  - F1 cross-face: the `}` boundary sentinel must keep real `name(` shapes
//    minting while `$name}` can never reach the `(` — both directions.
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

/// The real git-backed source inventory — kept identical to `ffi_nested_marker`/`ffi_hidden_marker`/`ffi_module_path`.
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
    // F2 residual: inside a *block*, rustc roots inline-module directories
    // at the FILE's directory — the `name/` stem dir only exists at item
    // level. Verified against rustc 1.98 in this audit:
    //
    //   src/evil.rs:  fn f() { mod m { #[path="hidden/real.rs"] mod i; } }
    //   rustc compiles src/m/hidden/real.rs — NOT src/evil/m/hidden/real.rs
    //
    //   src/evil.rs:  mod n { fn f() { mod a { #[path="…"] mod i; } } }
    //   rustc compiles src/evil/n/a/… (the enclosing item-level inline
    //   chain still contributes — only the file's own stem is dropped)
    //
    // The scanner roots the first inline mod inside a block at `mod_dir`
    // (`src/evil/m/…`) whenever the stack is empty — the same one-level-up
    // desync F2 exploited, reached through the block surface.
    // ------------------------------------------------------------------

    /// A `#[path]` inside an inline `mod` inside a `fn` body inside
    /// `src/evil.rs` resolves relative to `src/m/` under rustc — verified
    /// against a real build. The scanner resolves `src/evil/m/<path>`
    /// instead: an outside-`src/` target swaps in a clean decoy while the
    /// compiled file escapes the inventory — the same F2 channel.
    #[test]
    fn path_inside_inline_mod_inside_a_fn_block_must_not_substitute_a_decoy() {
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
            "fn f() {\n    mod m {\n        #[path = \"../../../hidden/real.rs\"]\n        pub mod inner;\n    }\n}\n",
        );
        // rustc resolves `src/m/../../../hidden/real.rs` = `crates/hidden/real.rs`
        // (`src/m` up three: `src` -> `lomo-native` -> `crates`) — outside
        // `src/`, so the tree walk never sees it; the mod graph is the only
        // coverage. The scanner resolves `src/evil/m/../../../hidden/real.rs`
        // = `crates/lomo-native/hidden/real.rs` — the decoy below.
        write(
            root,
            "crates/hidden/real.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        // `src/m/` must exist for the kernel to resolve rustc's `..`
        // traversal; `src/evil/m/` is folded textually by the scanner.
        write(
            root,
            "crates/lomo-native/src/m/keeper.rs",
            "pub fn keeper() {}\n",
        );
        write(
            root,
            "crates/lomo-native/hidden/real.rs",
            "pub fn decoy() {}\n",
        );
        let surface = workspace_exports(root);
        let surfaced = surface
            .as_ref()
            .is_ok_and(|surface| surface.iter().any(|export| export.rust == "hidden_export"));
        assert!(
            surfaced,
            "BLIND SPOT: `#[path]` inside inline `mod m` inside a `fn` block \
             inside `src/evil.rs` resolves relative to `src/m/` under rustc \
             but `src/evil/m/` under the scanner — an outside-`src/` compiled \
             file escapes while a decoy is scanned: {surface:?}"
        );
    }

    /// The same block surface through a `const` initializer — rustc applies
    /// the identical `file_dir` reset to every block, not just `fn` bodies.
    #[test]
    fn path_inside_inline_mod_inside_a_const_block_must_not_substitute_a_decoy() {
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
            "const _C: () = {\n    mod m {\n        #[path = \"../../../hidden/real.rs\"]\n        pub mod inner;\n    }\n};\n",
        );
        write(
            root,
            "crates/hidden/real.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        write(
            root,
            "crates/lomo-native/src/m/keeper.rs",
            "pub fn keeper() {}\n",
        );
        write(
            root,
            "crates/lomo-native/hidden/real.rs",
            "pub fn decoy() {}\n",
        );
        let surface = workspace_exports(root);
        let surfaced = surface
            .as_ref()
            .is_ok_and(|surface| surface.iter().any(|export| export.rust == "hidden_export"));
        assert!(
            surfaced,
            "BLIND SPOT: the block-rooting rule applies to `const` initializer \
             blocks the same way — `src/m/` under rustc vs `src/evil/m/` \
             under the scanner: {surface:?}"
        );
    }

    /// Green control: `#[path]` directly inside a block inside `src/evil.rs`
    /// resolves at `file_dir` under BOTH rustc and the scanner (verified
    /// against rustc: `src/hidden/real.rs`) — the direct-block `#[path]`
    /// channel is consistent and its outside-`src/` targets stay enumerated.
    #[test]
    fn path_directly_inside_a_block_uses_the_file_dir_under_both_models() {
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
            "fn f() {\n    #[path = \"../hidden/real.rs\"]\n    mod inner;\n}\n",
        );
        // `src/../hidden/real.rs` = `crates/lomo-native/hidden/real.rs` —
        // `#[path]` inside a block resolves at `file_dir` under rustc too.
        write(
            root,
            "crates/lomo-native/hidden/real.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        let surface = workspace_exports(root).expect("mod graph resolves");
        assert!(
            surface.iter().any(|export| export.rust == "hidden_export"),
            "a `#[path]` directly inside a block resolves at `file_dir` under \
             rustc too (`src/../hidden/real.rs`) — the file must surface"
        );
    }

    /// Green control: an enclosing ITEM-level inline chain above the block
    /// still contributes under rustc — `mod n { fn f() { mod a { #[path] } } }`
    /// inside `evil.rs` compiles `src/evil/n/a/…` (verified against rustc),
    /// which the scanner's stack already models — only the file-stem reset
    /// at block entry is missing.
    #[test]
    fn item_level_inline_chain_above_a_block_stays_consistent() {
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
            "mod n {\n    fn f() {\n        mod a {\n            #[path = \"x.rs\"]\n            pub mod inner;\n        }\n    }\n}\n",
        );
        write(
            root,
            "crates/lomo-native/src/evil/n/a/x.rs",
            "#[export] pub fn nested_export() {}\n",
        );
        let surface = workspace_exports(root).expect("mod graph resolves");
        assert!(
            surface.iter().any(|export| export.rust == "nested_export"),
            "`mod n {{ fn f() {{ mod a {{ #[path=x.rs] }} }} }}` inside \
             `src/evil.rs` compiles `src/evil/n/a/x.rs` under both models"
        );
    }

    /// Green control: `#[path]` ON the inline mod inside a block resolves at
    /// `file_dir` under both models (rustc verified) — `#[path="m"] mod m`
    /// inside a `const` block in `evil.rs` compiles `src/m/…`, and the
    /// scanner's `path_dir = file_dir` at empty stack coincides.
    #[test]
    fn path_attribute_on_the_block_inline_mod_stays_consistent() {
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
            "const _C: () = {\n    #[path = \"m\"]\n    mod m {\n        #[path = \"deep.rs\"]\n        pub mod inner;\n    }\n};\n",
        );
        write(
            root,
            "crates/lomo-native/src/m/deep.rs",
            "#[export] pub fn deep_export() {}\n",
        );
        let surface = workspace_exports(root).expect("mod graph resolves");
        assert!(
            surface.iter().any(|export| export.rust == "deep_export"),
            "`#[path]` on the block-level inline mod resolves at `file_dir` \
             under both models — `src/m/deep.rs` must surface"
        );
    }

    // ------------------------------------------------------------------
    // F3 residual: `parsed`'s dual-dir claim treats any second graph edge
    // naming a file under a different module dir as rustc-invalid. But
    // `mod a;` + `#[path="a.rs"] mod b;` is LEGAL rustc — verified: the file
    // compiles as two modules, each with its own child-resolution dir. The
    // gate errors "module file claimed under two directories" instead of
    // modelling either interpretation — a fail-closed false positive on a
    // real shape (the strict direction, but doc≈rustc divergence).
    // ------------------------------------------------------------------

    /// `mod a;` claims `a.rs` under `dir/a`; `#[path="a.rs"] mod b;` claims
    /// the same canonical file under `dir`. rustc compiles both modules —
    /// the export obligation exists either way, so the scan must surface it
    /// rather than erroring.
    #[test]
    fn a_mod_file_aliased_by_path_must_not_error_out() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod a;\n#[path = \"a.rs\"]\nmod b;\n",
        );
        write(
            root,
            "crates/lomo-native/src/a.rs",
            "#[export] pub fn aliased_export() {}\n",
        );
        let surface = workspace_exports(root);
        let surfaced = surface
            .as_ref()
            .is_ok_and(|surface| surface.iter().any(|export| export.rust == "aliased_export"));
        assert!(
            surfaced,
            "rustc-legal aliasing: `mod a;` + `#[path=\"a.rs\"] mod b;` compiles \
             the same file as two modules — the scan errors `claimed under two \
             directories` instead of surfacing the obligation: {surface:?}"
        );
    }

    /// Green control: two `#[path]` declarations aliasing the same file DO
    /// pass — same canonical, same module dir (`target.parent()`), so the
    /// dedupe is consistent when the interpretation agrees.
    #[test]
    fn two_path_edges_to_one_file_under_the_same_dir_stay_consistent() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n#[path = \"a.rs\"]\nmod b;\n#[path = \"a.rs\"]\nmod c;\n",
        );
        write(
            root,
            "crates/lomo-native/src/a.rs",
            "#[export] pub fn aliased_export() {}\n",
        );
        let surface = workspace_exports(root).expect("same-dir aliases agree");
        assert!(
            surface.iter().any(|export| export.rust == "aliased_export"),
            "two `#[path]` edges at the same dir are the same interpretation — \
             the file surfaces once"
        );
    }

    /// Green control: `mod a;` where both `a.rs` and `a/mod.rs` exist is
    /// ambiguous — rustc errors "file for module found at both"; the scan
    /// must fail closed the same way.
    #[test]
    fn a_file_and_mod_rs_pair_stays_an_ambiguous_error() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod a;\n",
        );
        write(root, "crates/lomo-native/src/a.rs", "pub fn x() {}\n");
        write(root, "crates/lomo-native/src/a/mod.rs", "pub fn y() {}\n");
        assert!(
            workspace_exports(root).is_err(),
            "`a.rs` + `a/mod.rs` is ambiguous under rustc too — the scan must \
             keep failing closed"
        );
    }

    // ------------------------------------------------------------------
    // F1 cross-face: the `}` sentinel must not eat real call shapes that
    // follow a shorthand literal, and `${}`-contained names still cannot
    // glue. Both directions of the boundary must hold.
    // ------------------------------------------------------------------

    /// `forName(` after `"$x"` is a REAL call shape — the sentinel ends the
    /// shorthand name but ordinary code after the literal still mints.
    #[test]
    fn real_call_shapes_after_a_shorthand_literal_still_mint() {
        for source in [
            "val s = \"$x\" + forName(0)",
            "val s = \"$x\"\nval t = forName(0)",
            "val s = \"$x\".also { forName(0) }",
        ] {
            let code = lomo_xtask::kotlin_code_view(source).code;
            let position = code
                .find("forName")
                .unwrap_or_else(|| panic!("`forName` must stay visible in the code view: {code}"));
            let (_, after) = code.split_at(position + "forName".len());
            assert!(
                after.trim_start().starts_with('('),
                "a real `forName(` after a shorthand literal must keep minting: {code}"
            );
        }
    }

    /// `forName` carried by a shorthand — even adjacent to a real `(` after
    /// the literal — never glues, in every interleaving. Under the parser
    /// the shorthand is plain literal content: masked out of `code` whole.
    #[test]
    fn shorthand_names_keep_their_boundary_in_every_interleaving() {
        for source in [
            "val s = \"${x}$forName\"(0)",
            "val s = \"$x$forName\"(0)",
            "val s = \"$$forName\"(0)",
            "val s = \"$forName$x\"(0)",
        ] {
            let code = lomo_xtask::kotlin_code_view(source).code;
            let minted = code.find("forName").is_some_and(|position| {
                let (_, after) = code.split_at(position + "forName".len());
                after.trim_start().starts_with('(')
            });
            assert!(
                !minted,
                "a shorthand `forName` must never reach the `(`: {source} -> {code}"
            );
        }
    }

    /// The Koin edge still holds on a REAL installer that also uses `$name`
    /// literals — the sentinel must not break the genuine fact.
    #[test]
    fn a_real_installer_with_shorthand_literals_still_feeds_the_edge() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  val tag = \"$kind\"\n  \
             fun onCreate() {\n    \
             Class.forName(\"com.lomo.data.di.DataModulesKt\").getMethod(\"getDataModules\")\n  }\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_ok(),
            "a real `forName`/`getMethod` install with an unrelated `$name` \
             literal must still mint the edge"
        );
    }

    /// The Koin edge must not be minted by a `forName(` that lives *inside*
    /// an interpolation only as a name — `"${forName}"(0)` reads a variable
    /// and invokes the string, exactly like the shorthand form.
    #[test]
    fn the_koin_edge_must_not_be_satisfied_by_braced_name_then_invoke() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             val shapes = \"${forName}\"(0) + \"${getMethod}\"(0)\n  \
             val names = \"DataModulesKt\" + \"getDataModules\"\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
            "`\"${{forName}}\"(0)` mints no `forName(` — the `}}` boundary \
             separates the name from the invoke-position paren"
        );
    }

    // ------------------------------------------------------------------
    // N5 consistency (manifest layer is untouched this round — one probe
    // keeps the whole verbatim module exercised): a component must mint
    // only as a DIRECT child of a live `<application>`. A self-closing
    // `<application/>` leaves no open application for a following sibling,
    // and an `<activity>` nested inside a non-component element is never
    // registered by the platform — neither may mint.
    // ------------------------------------------------------------------

    /// Builds a minimal schema-3 fact tree over `root` — identical shape to
    /// the `ffi_nested_marker`/`ffi_hidden_marker`/`ffi_module_path` harness.
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

    /// Shared fixture body — identical to `ffi_nested_marker`/`ffi_hidden_marker`/`ffi_module_path`.
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

    /// `<application android:name="..."/>` self-closing registers the
    /// application class but leaves no open element — a following
    /// `<activity>` sibling sits under `<manifest>`, never under a live
    /// application, and an `<activity>` nested inside a non-component
    /// element is not a direct `<application>` child. Neither may mint.
    #[test]
    fn components_outside_a_live_application_never_mint() {
        let mut minted = Vec::new();
        for manifest in [
            // self-closing application — the following activity is a
            // manifest child, not an application child
            "<manifest>\n<application android:name=\".LomoApplication\"/>\n\
             <activity android:name=\".ZombieActivity\"/>\n</manifest>\n",
            // activity nested inside a non-component wrapper — the platform
            // only registers direct children of `<application>`
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <foo><activity android:name=\".ZombieActivity\"/></foo>\n\
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
            "only direct children of a live `<application>` register — \
             siblings after a self-closed application and elements nested \
             under non-components must not mint:\n{}",
            minted.join("\n---\n")
        );
    }
}
