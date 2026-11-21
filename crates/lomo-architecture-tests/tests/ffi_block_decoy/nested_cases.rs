use super::ffi::{ResolvedGraph, install_app_data_koin_runtime_edge, workspace_exports};
use super::write;

// ------------------------------------------------------------------
// block × inline-mod nesting: once a block-level inline mod pushed its
// dir onto `stack`, a deeper block inside it must keep resolving at the
// STACK (the enclosing item-level module dir) — `file_dir` only wins at
// an empty stack. Verified against rustc 1.98:
//
//   src/evil.rs:  fn f() { mod m { fn g() { mod n { #[path="x.rs"] mod i; } } } }
//   rustc compiles src/m/n/x.rs     (mod n bases at src/m — m's dir)
//
//   src/evil.rs:  fn f() { mod m { fn g() { #[path="y.rs"] mod i; } } }
//   rustc compiles src/m/y.rs       (#[path] inside the inner block
//                                  resolves in m's dir, not file_dir)
//
// The decoy layouts discriminate the pre-fix model (`src/evil/m/…`) from
// the correct one (`src/m/…`) the same way `ffi_block_decoy` did for `src/evil/m`.
// ------------------------------------------------------------------

/// `fn f() { mod m { fn g() { mod n { #[path=…] } } } }` inside
/// `src/evil.rs` — `mod n` bases at `src/m` under rustc (probe:
/// `src/m/n/probeA.rs`), so `n`'s `#[path]` children resolve at
/// `src/m/n/<rel>` — four `..` land at `crates/hidden/real.rs`. The
/// pre-fix model roots `n` at `src/evil/m/n`, where four `..` land at
/// `crates/lomo-native/hidden/real.rs` — the decoy below. A substituted
/// decoy swallows the compiled file's export obligation.
#[test]
fn a_nested_block_inside_a_block_inline_mod_must_not_substitute_a_decoy() {
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
        "fn f() {\n    mod m {\n        fn g() {\n            mod n {\n                \
         #[path = \"../../../../hidden/real.rs\"]\n                pub mod inner;\n            \
         }\n        }\n    }\n}\n",
    );
    // rustc: `src/m/n` + `../../../../hidden/real.rs` → `crates/hidden/real.rs`
    // — outside `src/` and outside `lomo-native`, so only the mod graph
    // can reach it. `src/m/n` must exist for rustc's traversal; the
    // scanner folds textually either way.
    write(
        root,
        "crates/lomo-native/src/m/n/keeper.rs",
        "pub fn keeper() {}\n",
    );
    write(
        root,
        "crates/hidden/real.rs",
        "#[export] pub fn hidden_export() {}\n",
    );
    // pre-fix model: `src/evil/m/n` + `../../../../` → `crates/lomo-native/hidden/real.rs`.
    write(
        root,
        "crates/lomo-native/hidden/real.rs",
        "#[export] pub fn decoy_export() {}\n",
    );
    let surface = workspace_exports(root);
    let surfaced = surface
        .as_ref()
        .is_ok_and(|surface| surface.iter().any(|export| export.rust == "hidden_export"));
    assert!(
        surfaced,
        "BLIND SPOT: `mod n` inside a block inside block-level `mod m` bases \
         at `src/m` under rustc (`src/m/n/…`) — if the scan still roots at \
         `mod_dir`/`file_dir` the compiled file escapes while a decoy is \
         scanned: {surface:?}"
    );
}

/// `#[path]` on the `mod` inside the *inner* block resolves in `m`'s dir
/// under rustc — `src/m/<rel>` (probe: `src/m/probeB.rs`) — not `file_dir`.
/// The `stack.is_empty() → file_dir` rule only applies when no item-level
/// inline mod encloses the block.
#[test]
fn a_path_on_a_mod_inside_a_nested_block_must_not_substitute_a_decoy() {
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
        "fn f() {\n    mod m {\n        fn g() {\n            \
         #[path = \"../../../hidden/real.rs\"]\n            pub mod i;\n        \
         }\n    }\n}\n",
    );
    // rustc: `src/m` + `../../../hidden/real.rs` → `crates/hidden/real.rs`.
    write(
        root,
        "crates/hidden/real.rs",
        "#[export] pub fn hidden_export() {}\n",
    );
    // pre-fix model would resolve `src/evil/m/../../../` → `lomo-native/hidden`.
    write(
        root,
        "crates/lomo-native/hidden/real.rs",
        "#[export] pub fn decoy_export() {}\n",
    );
    write(
        root,
        "crates/lomo-native/src/m/keeper.rs",
        "pub fn keeper() {}\n",
    );
    let surface = workspace_exports(root);
    let surfaced = surface
        .as_ref()
        .is_ok_and(|surface| surface.iter().any(|export| export.rust == "hidden_export"));
    assert!(
        surfaced,
        "BLIND SPOT: `#[path]` on a `mod` inside a block inside `mod m` resolves \
         in `m`'s dir (`src/m`) under rustc — `file_dir` must not win while \
         the stack is non-empty: {surface:?}"
    );
}

/// Green controls — rustc-verified bases for the same nested shapes with
/// in-`src/` targets: `mod n` inside `m`'s items inside `f`'s block bases
/// at `src/m` (probe: `src/m/n/probeE.rs`); `#[path]` inside a block
/// inside `m`'s items resolves in `src/m` (probe: `src/m/probeB.rs`).
#[test]
fn nested_block_and_path_shapes_stay_consistent_with_rustc() {
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
        "fn f() {\n    mod m {\n        mod n {\n            #[path = \"x.rs\"]\n            \
         pub mod i;\n        }\n    }\n}\n\
         fn g() {\n    mod p {\n        fn h() {\n            #[path = \"y.rs\"]\n            \
         pub mod j;\n        }\n    }\n}\n",
    );
    write(
        root,
        "crates/lomo-native/src/m/n/x.rs",
        "#[export] pub fn nested_export() {}\n",
    );
    write(
        root,
        "crates/lomo-native/src/p/y.rs",
        "#[export] pub fn inner_block_export() {}\n",
    );
    let surface = workspace_exports(root).expect("mod graph resolves");
    for expected in ["nested_export", "inner_block_export"] {
        assert!(
            surface.iter().any(|export| export.rust == expected),
            "`{expected}` under `src/<inline>/<…>` must surface — the stack, \
             not the block flag, decides once an item-level inline mod is \
             on it"
        );
    }
}

/// Green control: `#[path]` on the block-level inline mod itself names
/// its directory under `file_dir` — `#[path="mdir"] mod m` inside a block
/// in `evil.rs` compiles `src/mdir/…` (probe: `src/mdir/n.rs`), and the
/// chain below it resolves under that named dir.
#[test]
fn a_path_named_dir_on_the_block_inline_mod_carries_the_chain() {
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
        "fn f() {\n    #[path = \"mdir\"]\n    mod m {\n        mod n {\n            \
         #[path = \"z.rs\"]\n            pub mod i;\n        }\n    }\n}\n",
    );
    write(
        root,
        "crates/lomo-native/src/mdir/n/z.rs",
        "#[export] pub fn named_dir_export() {}\n",
    );
    let surface = workspace_exports(root).expect("mod graph resolves");
    assert!(
        surface
            .iter()
            .any(|export| export.rust == "named_dir_export"),
        "`#[path=\"mdir\"] mod m` inside a block roots `src/mdir` and the \
         item-level chain below keeps resolving under it"
    );
}

/// Every syn block surface routes through `visit_block`: `unsafe {}`,
/// `async {}`, `loop {}`, `match` arm blocks, `static`/`const`
/// initializers and `const` items inside `fn` bodies all reset the
/// empty-stack base to `file_dir` — six shapes, one file, all must
/// surface under `src/<name>/…` (never `src/evil/<name>/…`).
#[test]
fn every_block_surface_resets_the_mod_base_to_file_dir() {
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
        "fn f() { unsafe { mod u { #[path = \"u.rs\"] mod i; } } }\n\
         fn g() { let _a = async { mod v { #[path = \"v.rs\"] mod i; } }; }\n\
         fn h() { loop { mod w { #[path = \"w.rs\"] mod i; } break; } }\n\
         static S: () = { mod x { #[path = \"x.rs\"] mod i; } };\n\
         fn k() { match 0 { _ => { mod y { #[path = \"y.rs\"] mod i; } } } }\n\
         fn l() { const C: () = { mod z { #[path = \"z.rs\"] mod i; } }; }\n",
    );
    for (segment, export) in [
        ("u", "u_export"),
        ("v", "v_export"),
        ("w", "w_export"),
        ("x", "x_export"),
        ("y", "y_export"),
        ("z", "z_export"),
    ] {
        write(
            root,
            &format!("crates/lomo-native/src/{segment}/{segment}.rs"),
            &format!("#[export] pub fn {export}() {{}}\n"),
        );
    }
    let surface = workspace_exports(root).expect("mod graph resolves");
    for export in [
        "u_export", "v_export", "w_export", "x_export", "y_export", "z_export",
    ] {
        assert!(
            surface.iter().any(|e| e.rust == export),
            "`{export}` must surface — every block surface drops the file \
             stem and roots inline `mod` dirs at `file_dir`"
        );
    }
}

/// rustc REJECTS an unloaded `mod n;` anywhere under a block — including
/// through inline-mod items (`fn f() { mod m { mod n; } }` errors "cannot
/// declare a file module inside a block", verified). The scanner resolves
/// `src/m/n.rs` anyway — over-scanning a crate rustc would reject, the
/// documented fail-closed direction (22号文 §6), here extended to the
/// nested-through-inline-mod case.
#[test]
fn a_file_mod_under_a_block_chain_is_resolved_not_rejected() {
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
        "fn f() {\n    mod m {\n        mod n;\n    }\n}\n",
    );
    write(
        root,
        "crates/lomo-native/src/m/n.rs",
        "#[export] pub fn over_scanned() {}\n",
    );
    let surface = workspace_exports(root).expect("over-scan resolves");
    assert!(
        surface.iter().any(|export| export.rust == "over_scanned"),
        "rustc rejects `mod n;` under a block chain; the scanner resolves \
         `src/m/n.rs` anyway — over-scan of a rustc-invalid crate is the \
         documented fail-closed direction"
    );
}

// ------------------------------------------------------------------
// alias model: `BTreeMap<canonical, BTreeSet<mod_dir>>` — the two module
// identities of `mod a;` + `#[path="a.rs"] mod b;` resolve children
// under DIFFERENT dirs (`a::c` → `src/a/c`, `b::c` → `src/c`, verified
// against rustc: both compile; each missing side errors E0583). The set
// must preserve both interpretations — and must not turn the alias into
// a license to clear real ambiguity.
// ------------------------------------------------------------------

/// `a.rs` declares `mod c;` — under module `a` it resolves `src/a/c.rs`,
/// under `#[path]` module `b` it resolves `src/c.rs`. rustc compiles
/// both; both export obligations must surface.
#[test]
fn aliased_file_children_resolve_under_each_module_identity() {
    let dir = tempfile::tempdir().expect("fixture");
    let root = dir.path();
    write(
        root,
        "crates/lomo-native/src/lib.rs",
        "#[export] pub fn legit() {}\nmod a;\n#[path = \"a.rs\"]\nmod b;\n",
    );
    write(root, "crates/lomo-native/src/a.rs", "mod c;\n");
    write(
        root,
        "crates/lomo-native/src/a/c.rs",
        "#[export] pub fn c_under_a() {}\n",
    );
    write(
        root,
        "crates/lomo-native/src/c.rs",
        "#[export] pub fn c_under_b() {}\n",
    );
    let surface = workspace_exports(root).expect("both identities parse");
    for expected in ["c_under_a", "c_under_b"] {
        assert!(
            surface.iter().any(|export| export.rust == expected),
            "`{expected}` must surface — rustc compiles `a.rs` as both \
             `crate::a` (children `src/a/…`) and `crate::b` (children \
             `src/…`); each interpretation contributes its own edges"
        );
    }
}

/// If either identity's `mod c;` cannot resolve, rustc errors (E0583 —
/// verified for both directions) and the scan must fail the same way:
/// the alias model must not let one interpretation cover the other.
#[test]
fn a_missing_child_under_one_alias_identity_fails_closed() {
    for present in ["src/c.rs only", "src/a/c.rs only"] {
        let dir = tempfile::tempdir().expect("fixture");
        let root = dir.path();
        write(
            root,
            "crates/lomo-native/src/lib.rs",
            "#[export] pub fn legit() {}\nmod a;\n#[path = \"a.rs\"]\nmod b;\n",
        );
        write(root, "crates/lomo-native/src/a.rs", "mod c;\n");
        match present {
            "src/c.rs only" => {
                write(root, "crates/lomo-native/src/c.rs", "pub fn c() {}\n");
            }
            _ => {
                write(root, "crates/lomo-native/src/a/c.rs", "pub fn c() {}\n");
            }
        }
        assert!(
            workspace_exports(root).is_err(),
            "with only {present} present, `mod c;` under the other module \
             identity cannot resolve — rustc errors and so must the scan"
        );
    }
}

/// `mod a;` whose only candidate is `a/mod.rs` claims it under `src/a`;
/// `#[path="a/mod.rs"] mod b;` names the same file with child dir `src/a`
/// — the same interpretation, deduped — legal rustc aliasing.
#[test]
fn a_path_alias_to_mod_rs_is_the_same_interpretation() {
    let dir = tempfile::tempdir().expect("fixture");
    let root = dir.path();
    write(
        root,
        "crates/lomo-native/src/lib.rs",
        "#[export] pub fn legit() {}\nmod a;\n#[path = \"a/mod.rs\"]\nmod b;\n",
    );
    write(
        root,
        "crates/lomo-native/src/a/mod.rs",
        "#[export] pub fn aliased_mod_rs() {}\n",
    );
    let surface = workspace_exports(root).expect("same interpretation dedupes");
    assert!(
        surface.iter().any(|export| export.rust == "aliased_mod_rs"),
        "`mod a;` + `#[path=\"a/mod.rs\"] mod b;` is rustc-legal — the same \
         (file, dir) interpretation parses once and the export surfaces"
    );
}

/// The alias model must not soften real ambiguity: `mod a;` with BOTH
/// `a.rs` and `a/mod.rs` present is still `resolves ambiguously` — rustc
/// errors the same way regardless of the `#[path]` alias nearby.
#[test]
fn a_path_alias_does_not_clear_the_real_ambiguity() {
    let dir = tempfile::tempdir().expect("fixture");
    let root = dir.path();
    write(
        root,
        "crates/lomo-native/src/lib.rs",
        "#[export] pub fn legit() {}\nmod a;\n#[path = \"a.rs\"]\nmod b;\n",
    );
    write(root, "crates/lomo-native/src/a.rs", "pub fn x() {}\n");
    write(root, "crates/lomo-native/src/a/mod.rs", "pub fn y() {}\n");
    assert!(
        workspace_exports(root).is_err(),
        "`a.rs` + `a/mod.rs` stays ambiguous under rustc — the alias must \
         not turn the dual-candidate check into a dedupe"
    );
}

/// A `#[path]` inside a block participates in the same alias model:
/// `mod a;` + `fn f() { #[path="a.rs"] mod b; }` — the block-level
/// `#[path]` resolves at `file_dir` (stack empty) and claims `a.rs` under
/// `src`, while `mod a;` claims it under `src/a` — both interpretations
/// are rustc-legal and the file surfaces.
#[test]
fn a_path_alias_declared_inside_a_block_still_uses_file_dir() {
    let dir = tempfile::tempdir().expect("fixture");
    let root = dir.path();
    write(
        root,
        "crates/lomo-native/src/lib.rs",
        "#[export] pub fn legit() {}\nmod a;\nmod evil;\n",
    );
    write(
        root,
        "crates/lomo-native/src/evil.rs",
        "fn f() {\n    #[path = \"a.rs\"]\n    mod b;\n}\n",
    );
    write(
        root,
        "crates/lomo-native/src/a.rs",
        "#[export] pub fn block_alias_export() {}\n",
    );
    let surface = workspace_exports(root).expect("block-level alias is legal");
    assert!(
        surface
            .iter()
            .any(|export| export.rust == "block_alias_export"),
        "`#[path]` inside a block resolves at `file_dir` — `a.rs` from \
         `src/` names `src/a.rs`, the same file `mod a;` claims under \
         `src/a` — two rustc-legal module identities, one surfaced export"
    );
}

// ------------------------------------------------------------------
// Koin edge cross: `${ }` bodies are executable Kotlin — calls inside
// them mint (the InterpolationBoundary token only separates `$name`
// shorthands, it does not mask `${ }` content). A `$`-shorthand name
// followed by a member call still cannot glue into `forName(`.
// ------------------------------------------------------------------

/// `"${ forName("…") }"` — the call inside the interpolation is real code
/// and must keep minting; `forName`/`getMethod` call shapes inside `${ }`
/// legitimately satisfy the edge.
#[test]
fn calls_inside_a_braced_interpolation_still_feed_the_edge() {
    let dir = tempfile::tempdir().expect("fixture");
    write(
        dir.path(),
        "apps/android/app/src/LomoApplication.kt",
        "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {\n    \
         val x = \"${ forName(\"com.lomo.data.di.DataModulesKt\") }\"\n    \
         val y = \"${ getMethod(\"getDataModules\") }\"\n  }\n}\n",
    );
    let mut graph = ResolvedGraph::default();
    assert!(
        install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_ok(),
        "a call INSIDE `${{ }}` is executable Kotlin — the edge must mint on \
         genuine reflective shapes"
    );
}

/// `"$forName".getMethod("getDataModules")` — `getMethod(` mints as a
/// member call but `forName` is a shorthand read: no `forName(` exists,
/// the edge must stay broken.
#[test]
fn a_shorthand_then_member_call_never_satisfies_the_edge() {
    let dir = tempfile::tempdir().expect("fixture");
    write(
        dir.path(),
        "apps/android/app/src/LomoApplication.kt",
        "package com.lomo.app\nclass LomoApplication {\n  fun onCreate() {}\n  \
         val s = \"$forName\".getMethod(\"getDataModules\")\n  \
         val t = \"DataModulesKt\"\n}\n",
    );
    let mut graph = ResolvedGraph::default();
    assert!(
        install_app_data_koin_runtime_edge(dir.path(), &mut graph).is_err(),
        "a `$forName` shorthand followed by `.getMethod(` mints `getMethod(` \
         but never `forName(` — the edge requires both call shapes"
    );
}

/// The `code` view itself: a `$name` shorthand is literal content and is
/// masked outright — `forName` after it can never glue to a following
/// `(`, while names INSIDE `${ }` stay visible with their `}` boundary.
#[test]
fn the_boundary_marker_separates_shorthands_in_the_code_view() {
    for (source, mints) in [
        ("val s = \"$forName\"(0)", false),
        ("val s = \"${forName}\"(0)", false),
        ("val s = \"${ forName(0) }\"", true),
        ("val s = \"x\" + forName(0)", true),
    ] {
        let code = lomo_xtask::kotlin_code_view(source).code;
        let minted = code.find("forName").is_some_and(|position| {
            let (_, after) = code.split_at(position + "forName".len());
            after.trim_start().starts_with('(')
        });
        assert_eq!(minted, mints, "call-shape mint on {source:?} -> {code:?}");
    }
}

// ------------------------------------------------------------------
// Manifest layer — same N5 consistency probe as `ffi_block_decoy` keeps the whole
// verbatim module exercised: only direct children of a live
// `<application>` may mint.
// ------------------------------------------------------------------
