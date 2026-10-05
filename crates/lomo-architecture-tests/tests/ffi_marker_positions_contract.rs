// adversarial re-audit (round 3) of the schema-3 FFI gate landed by 12-修复-工程门禁.
//
// The shipped gate code under test is `tests/policy/ffi.rs`, included verbatim via `#[path]`
// (same technique as `ffi_export_smuggling`.rs). Unlike `ffi_export_smuggling` — which stubbed `source_files` to fail
// closed — this suite wires the REAL git-backed `policy::inventory::source_files` so
// `workspace_exports`/`load_graph` run against tempdir git repositories end to end, manifest
// parsing and digest checks included. A RED result documents a live bypass or coverage hole
// in the current gate.
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

/// The real git-backed source inventory — `load_graph` coverage and `workspace_exports`
/// enumeration both stand on `git ls-files` semantics, so probes exercise it verbatim.
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
    // F2 residual: `collect_items` rejects `Item::Macro` at item position, but the
    // same macro-expansion opacity applies at impl/trait/extern member position —
    // those members are silently `continue`d instead of failing closed.
    // ------------------------------------------------------------------

    /// `impl Holder { wire!() }` where `wire!` expands to `#[export] pub fn sneaky(&self)`
    /// is legal Rust: the macro member is an `ImplItem::Macro`, which `collect_impl`
    /// skips via `let ImplItem::Fn = member else { continue }` — no error, no obligation.
    /// The F2 fix made item-position macros fail closed but left member-position ones
    /// silent.
    #[test]
    fn macro_inside_an_impl_block_must_not_smuggle_an_export() {
        let source = "pub struct Holder;\nimpl Holder { wire!{} }\n";
        let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
        assert!(
            accounted,
            "BLIND SPOT: an `ImplItem::Macro` is silently skipped — a macro emitting \
             `#[export] pub fn` inside an impl block carries no obligation while \
             item-position macros are rejected"
        );
    }

    /// `#[export] trait T { wire!{} }` — a `TraitItem::Macro` is skipped by the
    /// `if let TraitItem::Fn` filter, so a macro-generated callback method escapes the
    /// callback obligation the exported trait is supposed to carry.
    #[test]
    fn macro_inside_an_exported_trait_must_not_smuggle_a_callback_member() {
        let source = "#[export] pub trait BatchHost { wire!{} }\n";
        let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
        assert!(
            accounted,
            "BLIND SPOT: a `TraitItem::Macro` inside an exported trait is silently \
             skipped — expanded callback methods carry no obligation"
        );
    }

    /// `Item::ForeignMod` is matched by the silent-skip arm: `extern "C" { ... }` blocks
    /// are never inspected, so an attribute or a `ForeignItem::Macro` inside one is
    /// invisible to the scanner instead of rejected.
    #[test]
    fn extern_block_items_must_not_hide_attributes_or_macros() {
        for source in [
            "extern \"C\" { #[export] fn sneaky(); }",
            "extern \"C\" { wire!{} }",
        ] {
            let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
            assert!(
                accounted,
                "BLIND SPOT: extern-block contents are skipped entirely — `{source}` \
                 neither contributes obligations nor fails closed"
            );
        }
    }

    /// `#[export]` on a non-callable item (struct/enum/type/static) is silently ignored:
    /// no `Export` obligation, no error. If `boltffi::export` ever honours the attribute
    /// on such an item, its generated surface carries zero obligation. A fail-closed
    /// scanner should reject an export marker it cannot model instead of dropping it.
    #[test]
    fn export_marker_on_a_non_callable_item_must_not_pass_silently() {
        for source in [
            "#[export] pub struct Ghost;",
            "#[export] pub enum Ghost { A }",
            "#[export] pub static GHOST: i32 = 0;",
        ] {
            let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
            assert!(
                accounted,
                "BLIND SPOT: `#[export]` on a non-callable item is silently ignored — \
                 `{source}` produces neither an obligation nor an error"
            );
        }
    }

    /// Green control: item-position macros — including ones nested inside a `mod` —
    //  are still rejected outright.
    #[test]
    fn item_position_macros_stay_rejected_even_inside_modules() {
        for source in [
            "wire!{}",
            "mod nested { wire!{} }",
            "mod a { mod b { macro_rules! wire { () => {} } } }",
        ] {
            assert!(
                exports(source).is_err(),
                "item-position macros must keep failing closed: {source}"
            );
        }
    }

    // ------------------------------------------------------------------
    // F1 residual: cfg_attr argument parsing.
    // ------------------------------------------------------------------

    /// Green control: `cfg_attr` re-emitting a *nested* `cfg_attr` still resolves
    /// recursively to the export.
    #[test]
    fn nested_cfg_attr_reemission_still_surfaces_the_export() {
        let source = "#[cfg_attr(test, cfg_attr(unix, boltffi::export))] pub fn gated() {}";
        assert!(
            !surface(source).is_empty(),
            "cfg_attr(cfg_attr(..., export)) must resolve transitively"
        );
    }

    /// Green control: a `cfg_attr` carrying only a condition emits nothing and stays
    /// inert — `#[cfg_attr(test)]` is legal and re-emits zero attributes.
    #[test]
    fn condition_only_cfg_attr_is_inert() {
        assert!(
            surface("#[cfg_attr(test)] pub fn plain() {}").is_empty(),
            "a cfg_attr with no re-emitted attributes is not an export"
        );
    }

    /// Green control: `cfg_attr` arguments that are not meta-shaped fail closed instead
    /// of silently dropping the item.
    #[test]
    fn non_meta_cfg_attr_arguments_fail_closed() {
        assert!(
            exports("#[cfg_attr(test, \"literal\")] pub fn x() {}").is_err(),
            "unparseable cfg_attr arguments must error"
        );
    }

    // ------------------------------------------------------------------
    // F4 residual: instantiation evidence is computed *flat* — every edge target and
    // every reference target counts, regardless of whether the producing site is
    // itself reachable. A constructor call or factory registration inside dead code
    // mints a live platform root.
    // ------------------------------------------------------------------

    /// `class ZombieVm : ViewModel()` is never wired, but `class Never { fun dead() {
    /// ZombieVm() } }` emits an edge `Never.dead → ZombieVm` whose *source* is
    /// unreachable. `instantiated` collects edge targets globally, so the zombie still
    /// counts as instantiated — one line of dead code defeats the F4 check.
    #[test]
    fn construction_evidence_in_unreachable_code_must_not_validate_a_root() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph.classes.extend(
            ["com.lomo.app.Never", "com.lomo.app.feature.ZombieViewModel"].map(str::to_owned),
        );
        graph
            .roots
            .insert("com.lomo.app.feature.ZombieViewModel".to_owned());
        graph.edges.extend([
            // `Never.dead` is dead code: no reachable root leads to it.
            (
                "com.lomo.app.Never".to_owned(),
                "com.lomo.app.Never.dead".to_owned(),
            ),
            (
                "com.lomo.app.Never.dead".to_owned(),
                "com.lomo.app.feature.ZombieViewModel".to_owned(),
            ),
            (
                "com.lomo.app.feature.ZombieViewModel".to_owned(),
                "com.lomo.app.feature.ZombieViewModel.leak".to_owned(),
            ),
            (
                "com.lomo.app.feature.ZombieViewModel.leak".to_owned(),
                "com.lomo.nativebridge.deadExport".to_owned(),
            ),
        ]);
        assert!(
            !contract_violations(&surface, &generated, &graph).is_empty(),
            "BLIND SPOT: a constructor call inside unreachable code still counts as \
             instantiation evidence — evidence must come from a live path, not from \
             any edge target in the graph"
        );
    }

    /// Same shape through the references channel: `fun dead() { sink(::ZombieVm) }`
    /// hands a constructor reference to a callee inside dead code — `references`
    /// targets count flat, so the zombie is validated anyway.
    #[test]
    fn reference_evidence_in_unreachable_code_must_not_validate_a_root() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph.classes.extend(
            ["com.lomo.app.Never", "com.lomo.app.feature.ZombieViewModel"].map(str::to_owned),
        );
        graph
            .roots
            .insert("com.lomo.app.feature.ZombieViewModel".to_owned());
        graph.edges.extend([
            (
                "com.lomo.app.feature.ZombieViewModel".to_owned(),
                "com.lomo.app.feature.ZombieViewModel.leak".to_owned(),
            ),
            (
                "com.lomo.app.feature.ZombieViewModel.leak".to_owned(),
                "com.lomo.nativebridge.deadExport".to_owned(),
            ),
        ]);
        graph.references.insert((
            "com.lomo.app.Never.dead".to_owned(),
            "com.lomo.app.feature.ZombieViewModel".to_owned(),
        ));
        assert!(
            !contract_violations(&surface, &generated, &graph).is_empty(),
            "BLIND SPOT: `::ZombieVm` handed to a call inside dead code still mints \
             instantiation evidence — the references channel is flat too"
        );
    }

    // ------------------------------------------------------------------
    // F4/F7 residual: `load_graph` end-to-end — manifest parsing and the Koin edge
    // source check are text-level, so XML comments and Kotlin comments mint real
    // evidence.
    // ------------------------------------------------------------------

    /// Builds a minimal schema-3 fact tree over `root` covering `app` (the only module
    /// with sources) plus empty `data`/`domain`/`ui-components` inventories.
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

    /// Shared fixture body: a git-inited repo with `LomoApplication` carrying the real
    /// reflective-install source strings, a `ZombieActivity` root-candidate source file,
    /// and a manifest whose content the caller controls.
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

    /// Green control: a genuinely manifest-declared component satisfies instantiation
    /// evidence — proves the fixture wiring is faithful before the bypass probes run.
    #[test]
    fn a_real_manifest_component_still_validates() {
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
    }

    /// `manifest_components` scans tag text with `split_once` and never strips XML
    /// comments: `<!-- <activity android:name=".ZombieActivity"/> -->` still mints
    /// instantiation evidence for the class. Commenting a component out of the
    /// manifest must not keep its root alive.
    #[test]
    fn a_commented_out_manifest_component_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <!-- <activity android:name=\".ZombieActivity\"/> -->\n</application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: a component name inside an XML comment still counts as \
             manifest registration — commented-out manifest entries must not mint \
             instantiation evidence"
        );
    }

    /// `split_once("<activity")` also matches `<activity-alias`: the alias's
    /// `android:name` is an alias identifier, not the instantiated class — yet it lands
    /// in `manifest` verbatim and can validate a same-named root candidate.
    #[test]
    fn an_activity_alias_name_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity-alias android:name=\".ZombieActivity\" \
             android:targetActivity=\".LomoApplication\"/>\n</application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `<activity-alias` matches the `<activity` scan — an alias \
             name is not a class the platform instantiates"
        );
    }

    /// `install_app_data_koin_runtime_edge` requires the three source strings
    /// `forName`/`DataModulesKt`/`getDataModules` in `LomoApplication.kt` — but the
    /// check is `source.contains`, so a comment preserves all three after the
    /// reflective install is deleted: the phantom edge keeps minting reachability.
    #[test]
    fn koin_edge_source_facts_inside_a_comment_must_not_satisfy_the_check() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             // reflective install removed: Class.forName(\"com.lomo.data.di.DataModulesKt\")\
             .getMethod(\"getDataModules\")\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
            "BLIND SPOT: a comment mentioning forName/DataModulesKt/getDataModules \
             satisfies the source-fact check — the modeled Koin edge must be re-derived \
             from live code, not lexical presence"
        );
    }

    /// Green control: deleting the installer source still fails closed.
    #[test]
    fn a_missing_installer_source_still_fails_closed() {
        let dir = tempfile::tempdir().expect("fixture");
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
            "a missing LomoApplication.kt must still fail closed"
        );
    }

    // ------------------------------------------------------------------
    // Inventory escape: `workspace_exports` enumerates sources through
    // `git ls-files --others --exclude-standard` — `.gitignore` rules apply. The
    // committed .gitignore already ignores directory names `bin/`/`gen/`/`out/`/
    // `build/`, so `crates/lomo-native/src/gen/mod.rs` (a legal module dir for
    // `mod gen;`) is invisible to the export scan while still compiled by rustc.
    // ------------------------------------------------------------------

    #[test]
    fn gitignored_module_directories_must_not_escape_the_export_inventory() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(root, ".gitignore", "bin/\ngen/\nout/\nbuild/\n");
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod gen;\n",
        );
        write(
            root,
            "crates/lomo-native/src/gen/mod.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        git_init(root);
        let surface = workspace_exports(root).expect("surface computes");
        assert!(
            surface.iter().any(|export| export.rust == "hidden_export"),
            "BLIND SPOT: `git ls-files --exclude-standard` skips .gitignore'd module \
             dirs (`gen/` is already in the committed .gitignore) — a compiled module \
             carrying `#[export]` vanishes from the obligation surface"
        );
    }
}
