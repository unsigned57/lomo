// adversarial re-audit (round 5) of the P4-F3 gate fixes landed by 14-修复-门禁降维残留.
//
// Same harness as `ffi_marker_positions`.rs: the shipped gate code under test is
// `tests/policy/ffi.rs`, included verbatim via `#[path]`; `load_graph`/`workspace_exports`
// run against tempdir fixtures end to end. A RED result documents a live bypass or
// coverage hole that survived the N1–N9 repair round.
//
// Probed invariants:
//  - N1 residual: member-position macros were closed, but the *function-body item
//    surface* is still invisible — statement-position macros and inner items inside
//    `fn` bodies (and const/static initializer blocks) emit real `#[no_mangle]`
//    symbols (rustc-verified: `nm` shows `sneaky`/`direct_inner` as global `T`) while
//    the scanner never descends into bodies.
//  - N3 residual: the element scan records "element exists in file", not "platform
//    registers it" — `<activity>` outside `<application>`, `tools:node="remove"`,
//    and `android:enabled="false"` components are manifest-declared but never
//    instantiated by the platform.
//  - N4 residual: comments are stripped but string literals are preserved verbatim —
//    a retained `val note = "forName(...)…getMethod(...)"` satisfies every call-shape
//    needle without any reflective call surviving.
//  - N5 residual: FS enumeration covers `crates/lomo-native/src/**` but rustc's
//    compile surface is the mod graph — `#[path = "..."]` pulls files from outside
//    `src/` into the build while the inventory never sees them.
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

/// The real git-backed source inventory — kept identical to `ffi_marker_positions` so `load_graph`
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
    // N1 residual: the fix rejected macros at item position and impl/trait/extern
    // member position — but the scan still never descends into `fn` bodies or
    // const/static initializers, which hold a second, full item surface.
    // rustc emits global symbols for `#[no_mangle]` items inside `fn` bodies and
    // expands statement-position macros to items (verified: `nm -g` lists
    // `sneaky`/`direct_inner` as `T`), so anything boltffi would honour there
    // carries zero obligation.
    // ------------------------------------------------------------------

    /// `fn f() { wire!() }` — a `Stmt::Macro` expands to items in place. The scanner
    /// only walks item position, so a macro emitting `#[export] pub fn` inside a
    /// function body is invisible instead of rejected like its item/member-position
    /// siblings.
    #[test]
    fn statement_position_macros_must_not_hide_export_items() {
        let mut invisible = Vec::new();
        for source in [
            "fn wrapper() { wire!(); }",
            "fn wrapper() { macro_rules! wire { () => {} } }",
            "fn outer() { fn inner() { wire!(); } }",
        ] {
            if exports(source).is_ok() {
                invisible.push(source);
            }
        }
        assert!(
            invisible.is_empty(),
            "BLIND SPOT: statement-position macros inside fn bodies are never \
             inspected — `wire!()` expands to items the obligation scan cannot \
             see:\n{}",
            invisible.join("\n")
        );
    }

    /// An inner item inside a `fn` body carrying `#[export]` is silently dropped:
    /// the scan checks only `Item::Fn`'s own attributes, never the statements in its
    /// block. `#[no_mangle]` on the same inner-fn shape emits a global symbol
    /// (rustc-verified), so a boltffi `#[export]` analogue is a real export with
    /// no obligation — the fix's own "unmodelable export marker must fail closed"
    /// rule is violated one level down.
    #[test]
    fn export_markers_on_inner_items_must_not_pass_silently() {
        let mut smuggled = Vec::new();
        for source in [
            "fn wrapper() { #[export] pub fn sneaky() {} }",
            "pub struct Holder;\nfn f() { impl Holder { #[export] pub fn sneaky(&self) {} } }",
            "fn f() { #[export] pub trait Callback { fn on_event(); } }",
            "fn f() { extern \"C\" { #[export] fn sneaky(); } }",
            // const/static initializer blocks hold the same inner-item surface
            "const C: () = { #[export] pub fn sneaky() {} };",
            "static S: () = { wire!(); () };",
        ] {
            let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
            if !accounted {
                smuggled.push(source);
            }
        }
        assert!(
            smuggled.is_empty(),
            "BLIND SPOT: export markers inside bodies the scanner never enters — \
             the N1 fix closed member positions but not the inner-item surface of \
             `fn`/const/static bodies:\n{}",
            smuggled.join("\n")
        );
    }

    /// Green control: item-position macros are still rejected and a plain
    /// `#[export] pub fn` still mints its obligation.
    #[test]
    fn item_surface_semantics_stay_intact() {
        assert!(exports("wire!{}").is_err(), "item macros stay rejected");
        assert_eq!(
            surface("#[export] pub fn legit() {}").len(),
            1,
            "top-level exports must still mint obligations"
        );
    }

    // ------------------------------------------------------------------
    // N3 residual: `manifest_components` still mints "element present", not
    // "platform instantiates". Three declaration shapes are in the file but
    // never reach the packaged component set.
    // ------------------------------------------------------------------

    /// Builds a minimal schema-3 fact tree over `root` — identical shape to the
    /// reaudit3 harness.
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

    /// Shared fixture body — identical to `ffi_marker_positions`.
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

    /// Green control (mirrors reaudit3): a genuinely registered component validates.
    #[test]
    fn a_component_registered_under_application_still_validates() {
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

    /// The manifest merger only registers component elements *inside*
    /// `<application>` — an `<activity>` directly under `<manifest>` is ignored by
    /// the platform (it is not a valid manifest construct). The scan tracks no
    /// parent context, so the same element still mints instantiation evidence.
    #[test]
    fn a_component_outside_the_application_element_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\"/>\n\
             <activity android:name=\".ZombieActivity\"/>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `<activity>` outside `<application>` still mints \
             instantiation evidence — the platform registers only application \
             children, not bare manifest elements"
        );
    }

    /// `tools:node="remove"` is a merge instruction (the committed manifest already
    /// binds `xmlns:tools` and uses `tools:node` on meta-data): the element is
    /// stripped from the packaged manifest, so the platform never instantiates
    /// it — yet the scanner still records the component.
    #[test]
    fn a_tools_node_remove_component_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest xmlns:tools=\"http://schemas.android.com/tools\">\n\
             <application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" tools:node=\"remove\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `tools:node=\"remove\"` components are dropped at manifest \
             merge — declaring one must not mint instantiation evidence for the \
             packaged component set"
        );
    }

    /// `android:enabled="false"` components are registered-but-disabled: the
    /// platform does not instantiate a disabled activity/service/receiver, so
    /// declaring one disabled must not stand in for entry-point evidence.
    #[test]
    fn a_disabled_component_must_not_mint_instantiation() {
        let (dir, facts_root) = ffi_fixture(
            "<manifest>\n<application android:name=\".LomoApplication\">\n\
             <activity android:name=\".ZombieActivity\" android:enabled=\"false\"/>\n\
             </application>\n</manifest>\n",
        );
        write_facts(dir.path(), &facts_root, true);
        let (generated, graph) = load_graph(dir.path(), &facts_root).expect("graph loads");
        let (exports, _) = dead_export_surface();
        assert!(
            !contract_violations(&exports, &generated, &graph).is_empty(),
            "BLIND SPOT: `android:enabled=\"false\"` components are never \
             instantiated by the platform — a disabled declaration must not mint \
             entry-point evidence"
        );
    }

    // ------------------------------------------------------------------
    // N4 residual: comments are stripped but *string literals are preserved
    // verbatim* — so a retained string echoing the shapes satisfies every needle
    // (`forName(`/`getMethod(` as call shapes, `DataModulesKt`/`getDataModules`
    // as raw substrings) after the reflective install is deleted.
    // ------------------------------------------------------------------

    /// After deleting the reflective install, a doc/retention string like
    /// `val note = "Class.forName(\"…DataModulesKt\").getMethod(\"getDataModules\")"`
    /// still contains `forName(` and `getMethod(` (call shapes match inside string
    /// text — the check does not know it is inside a literal) plus both names.
    /// The phantom app→data edge stays alive.
    #[test]
    fn koin_edge_source_facts_inside_a_string_literal_must_not_satisfy_the_check() {
        let mut satisfied = Vec::new();
        for source in [
            // escaped-quote string mimicking the deleted call
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             val note = \"Class.forName(\\\"com.lomo.data.di.DataModulesKt\\\").getMethod(\\\"getDataModules\\\")\"\n}\n",
            // raw string carrying all four shapes
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
             val note = \"\"\"forName(DataModulesKt) getMethod(getDataModules)\"\"\"\n}\n",
        ] {
            let dir = tempfile::tempdir().expect("fixture");
            write(
                dir.path(),
                "apps/android/app/src/LomoApplication.kt",
                source,
            );
            let mut graph = ResolvedGraph::default();
            if install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_ok() {
                satisfied.push(source);
            }
        }
        assert!(
            satisfied.is_empty(),
            "BLIND SPOT: call shapes inside a *string literal* satisfy the \
             source-fact check — the N4 fix stripped comments but preserved \
             strings verbatim, so a retained doc string keeps the phantom edge:\n{}",
            satisfied.join("\n")
        );
    }

    /// Green control: the real reflective call *as code* still mints the edge.
    #[test]
    fn real_call_shapes_still_satisfy_the_installer_check() {
        let dir = tempfile::tempdir().expect("fixture");
        write(
            dir.path(),
            "apps/android/app/src/LomoApplication.kt",
            "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {\n    \
             Class.forName(\"com.lomo.data.di.DataModulesKt\").getMethod(\"getDataModules\")\n  }\n}\n",
        );
        let mut graph = ResolvedGraph::default();
        assert!(
            install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_ok(),
            "the live reflective install must still mint the modeled edge"
        );
    }

    // ------------------------------------------------------------------
    // N5 residual: `native_source_tree` enumerates `crates/lomo-native/src/**`
    // — but rustc's compile surface is the *mod graph*, not the directory tree.
    // `#[path = "..."]` pulls a file from outside `src/` into compilation; the
    // inventory never sees it, so its `#[export]` items vanish exactly like the
    // gitignored `gen/` module did.
    // ------------------------------------------------------------------

    #[test]
    fn path_attribute_modules_must_not_escape_the_export_inventory() {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n#[path = \"../shared/evil.rs\"]\nmod evil;\n",
        );
        write(
            root,
            "crates/lomo-native/shared/evil.rs",
            "#[export] pub fn hidden_export() {}\n",
        );
        let surface = workspace_exports(root).expect("surface computes");
        assert!(
            surface.iter().any(|export| export.rust == "hidden_export"),
            "BLIND SPOT: `#[path]` joins a file outside `src/` into the compile \
             surface — the directory-tree enumeration misses it, so the N5 fix \
             swapped gitignore-blindness for mod-graph-blindness"
        );
    }

    /// Green control: a symlinked file inside `src/` whose canonical path leaves
    /// the repository is still rejected by the boundary check.
    #[cfg(unix)]
    #[test]
    fn symlinked_sources_outside_the_repo_still_fail_closed() {
        let dir = tempfile::tempdir().expect("fixture");
        let outside_dir = tempfile::tempdir().expect("outside fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\n",
        );
        let outside = outside_dir.path().join("outside.rs");
        fs::write(&outside, "#[export] pub fn ghost() {}\n").expect("outside file");
        std::os::unix::fs::symlink(&outside, root.join("crates/lomo-native/src/ghost.rs"))
            .expect("symlink");
        assert!(
            workspace_exports(root).is_err(),
            "a source escaping repository ownership must still fail closed"
        );
    }

    // ------------------------------------------------------------------
    // N2 residual (model-granularity boundary): reachability is per-declaration,
    // so a construction inside an `if (false)`/dead branch of a *reachable*
    // function still mints. Documenting the boundary — the fact model cannot
    // express intra-declaration dead code.
    // ------------------------------------------------------------------

    /// `class Live` is reachable (manifest seed), but the construction edge to
    /// `ZombieVm` comes from a dead branch *inside* a live function: at
    /// declaration granularity the source is reachable, so the zombie still
    /// validates. The worklist fix stopped cross-declaration dead evidence;
    /// intra-declaration dead evidence is unexpressible in the current facts —
    /// schema-3 facts carry declaration-granular edges and no branch liveness.
    /// The probe is kept as standing RED evidence of the documented modeling
    /// boundary (audit-15 R6): run it with `--ignored` to confirm the hole is
    /// still open; it must go GREEN the day the fact model carries branch
    /// liveness.
    #[test]
    #[ignore = "documented modeling boundary (audit-15 R6): declaration-granular \
                facts cannot express intra-declaration dead branches"]
    fn construction_in_a_dead_branch_of_live_code_still_validates_a_root() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph.classes.extend(
            ["com.lomo.app.Live", "com.lomo.app.feature.ZombieViewModel"].map(str::to_owned),
        );
        graph.roots.extend(
            ["com.lomo.app.Live", "com.lomo.app.feature.ZombieViewModel"].map(str::to_owned),
        );
        // `Live` is manifest-declared → seeded reachable.
        graph.manifest.insert("com.lomo.app.Live".to_owned());
        graph.edges.extend([
            (
                "com.lomo.app.Live".to_owned(),
                "com.lomo.app.Live.run".to_owned(),
            ),
            // `if (false) { ZombieVm() }` inside `run` — dead branch, live declaration.
            (
                "com.lomo.app.Live.run".to_owned(),
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
            "MODEL BOUNDARY (documented): a construction inside a dead branch of a \
             reachable function still mints a root — reachability is \
             declaration-granular, not branch-granular"
        );
    }
}
