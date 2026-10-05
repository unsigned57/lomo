// adversarial re-audit (round 2) of the schema-2 FFI gate landed by 05-F8.
//
// The shipped gate code under test is `tests/policy/ffi.rs`, included verbatim via `#[path]`
// (same technique as `ffi_reachability_contract`.rs): the crate-root `Violation`/`source_files` items
// satisfy that file's `super::` references. Probes replay the exact fact shapes
// `ResolvedCallGraphRule` emits or feed the syn surface scanner real attribute forms.
// A RED result documents a live bypass or coverage hole in the current gate.
#![cfg(test)]

use std::path::{Path, PathBuf};

#[derive(Debug)]
struct Violation {
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

/// Fail-closed stand-in for `policy::source_files`; these probes exercise
/// `exports`/`contract_violations` only, so any accidental inventory call must not pass.
fn source_files(_root: &Path, _prefix: &str) -> Result<Vec<PathBuf>, String> {
    Err("adversarial shim: source inventory is out of scope for these probes".to_owned())
}

#[expect(
    dead_code,
    reason = "the included policy file ships the full gate surface; probes call a subset"
)]
#[path = "policy/ffi.rs"]
mod ffi;

#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures fail closed with explicit diagnostics"
)]
mod tests {
    use super::ffi::{Export, ResolvedGraph, contract_violations, exports};
    use std::collections::BTreeSet;

    fn surface(source: &str) -> BTreeSet<Export> {
        exports(source).expect("fixture parses")
    }

    fn dead_export_surface() -> (BTreeSet<Export>, BTreeSet<String>) {
        (
            surface("#[export] pub fn dead_export() {}"),
            BTreeSet::from(["com.lomo.nativebridge.deadExport".to_owned()]),
        )
    }

    // ------------------------------------------------------------------
    // Export surface scan: cfg_attr conditions beyond the flat `path`/`path = value`
    // forms evade detection.
    // ------------------------------------------------------------------

    /// `cfg_attr` takes `<condition>, <attrs...>`. The standard cfg combinators
    /// `all(...)`/`any(...)`/`not(...)` are list-shaped nested meta items; syn's
    /// `parse_nested_meta` hands them to the closure with `meta.input` parked at the
    /// unconsumed `(...)`, the parse errors out, `drop()` swallows it, and the trailing
    /// `boltffi::export` item is never visited — the export is invisible to the surface.
    #[test]
    fn cfg_attr_combinator_conditions_must_not_hide_exports() {
        let mut hidden = Vec::new();
        for condition in [
            "all(unix, feature = \"jni\")",
            "any(windows, unix)",
            "not(test)",
        ] {
            let surface = exports(&format!(
                "#[cfg_attr({condition}, boltffi::export)] pub fn sneaky_export() {{}}"
            ))
            .expect("fixture parses");
            if surface.is_empty() {
                hidden.push(condition);
            }
        }
        assert!(
            hidden.is_empty(),
            "BLIND SPOT: #[cfg_attr(<condition>, boltffi::export)] is invisible to \
             workspace_exports for combinator conditions {hidden:?} — parse_nested_meta \
             dies on the combinator's list before the export item is inspected"
        );
    }

    /// Control: flat `cfg_attr` conditions (`feature = "x"`, `test`, `target_arch = "…"`)
    /// do surface the wrapped export — the gap is specific to list-shaped combinators.
    #[test]
    fn flat_cfg_attr_conditions_still_surface_the_export() {
        for condition in ["feature = \"jni\"", "test", "target_arch = \"aarch64\""] {
            let surface = exports(&format!(
                "#[cfg_attr({condition}, boltffi::export)] pub fn gated_export() {{}}"
            ))
            .expect("fixture parses");
            assert!(
                !surface.is_empty(),
                "flat cfg_attr conditions must keep exporting the surface"
            );
        }
    }

    // ------------------------------------------------------------------
    // Macro-hidden exports: only macro invocations whose NAME contains "export" are
    // rejected; a `macro_rules!` payload (or any other invocation name) is skipped
    // silently, carrying no declaration or consumer obligation.
    // ------------------------------------------------------------------

    #[test]
    fn macro_invocation_smuggling_an_export_must_not_pass_silently() {
        // `macro_rules! wire` expands to an `#[export]` item at each `wire!()` call site.
        // The invocation's path segment is `wire` — it does not contain "export", so
        // `unverifiable_export_macro` does not fire and the item contributes nothing.
        let source = "macro_rules! wire { () => { #[export] pub fn hidden() {} } } wire!{}";
        // A fail-closed rejection is also an acceptable accounting of the payload.
        let accounted = exports(source).map_or(true, |surface| !surface.is_empty());
        assert!(
            accounted,
            "BLIND SPOT: a macro invocation not named *export* is skipped silently — its \
             expanded #[export] items carry no generated-declaration or consumer obligation"
        );
    }

    /// Control: the fail-closed macro rejection still fires when the invocation name
    /// contains "export".
    #[test]
    fn export_named_macro_invocations_are_still_rejected() {
        assert!(
            exports("boltffi_export! { pub fn hidden() {} }").is_err(),
            "export-named macro invocations must keep failing closed"
        );
    }

    // ------------------------------------------------------------------
    // Schema-3 reachability: callable references are recorded on a separate
    // `references` channel — `caller → target` only where the reference is handed to a
    // call as a value argument (the DI/factory registration shape). References evidence
    // instantiation for entry-point validation but never seed reachability, so a
    // referenced-but-never-invoked target cannot discharge a consumer obligation.
    // ------------------------------------------------------------------

    /// Replay of the facts emitted for
    /// `class DeadHolder { fun use() { deadExport() } }` plus `register(::DeadHolder)`
    /// inside `App.onCreate` — the strongest escape the schema records for a reference.
    #[test]
    fn a_never_invoked_constructor_reference_must_not_count_as_construction() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph
            .classes
            .extend(["com.lomo.app.App", "com.lomo.app.DeadHolder"].map(str::to_owned));
        graph.roots.insert("com.lomo.app.App".to_owned());
        graph.manifest.insert("com.lomo.app.App".to_owned());
        graph.edges.extend([
            (
                "com.lomo.app.App".to_owned(),
                "com.lomo.app.App.onCreate".to_owned(),
            ),
            (
                "com.lomo.app.DeadHolder".to_owned(),
                "com.lomo.app.DeadHolder.use".to_owned(),
            ),
            (
                "com.lomo.app.DeadHolder.use".to_owned(),
                "com.lomo.nativebridge.deadExport".to_owned(),
            ),
        ]);
        // `register(::DeadHolder)` — a factory value handed to a call, not an instance.
        graph.references.insert((
            "com.lomo.app.App.onCreate".to_owned(),
            "com.lomo.app.DeadHolder".to_owned(),
        ));
        assert!(
            !contract_violations(&surface, &generated, &graph).is_empty(),
            "BLIND SPOT: registering `::DeadHolder` without invoking it must not mark the \
             class constructed — the references channel records registration evidence, \
             not reachability"
        );
    }

    /// Replay of `register(DeadHolder::use)` — the member reference lands on the
    /// `references` channel and must not reach `DeadHolder.use` as a call.
    #[test]
    fn a_never_invoked_member_reference_must_not_count_as_a_call() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph
            .classes
            .extend(["com.lomo.app.App", "com.lomo.app.DeadHolder"].map(str::to_owned));
        graph.roots.insert("com.lomo.app.App".to_owned());
        graph.manifest.insert("com.lomo.app.App".to_owned());
        graph.edges.extend([
            (
                "com.lomo.app.App".to_owned(),
                "com.lomo.app.App.onCreate".to_owned(),
            ),
            (
                "com.lomo.app.DeadHolder.use".to_owned(),
                "com.lomo.nativebridge.deadExport".to_owned(),
            ),
        ]);
        // `register(DeadHolder::use)` — an unbound reference handed off, never invoked.
        graph.references.insert((
            "com.lomo.app.App.onCreate".to_owned(),
            "com.lomo.app.DeadHolder.use".to_owned(),
        ));
        assert!(
            !contract_violations(&surface, &generated, &graph).is_empty(),
            "BLIND SPOT: `DeadHolder::use` as a call argument is registration evidence \
             only — it must not satisfy ffi-production-consumer without constructing \
             DeadHolder or dispatching `use`"
        );
    }

    /// `roots ⊆ classes` only proves the entry point names a *declared* class. A declared
    /// subtype of `PLATFORM_HOST_SUPERTYPES` (e.g. `class ZombieVm : ViewModel()`) that is
    /// never registered in the manifest, injected, or dispatched is still emitted as a root —
    /// declared subtype is an instantiability heuristic, not an instantiation proof.
    #[test]
    fn a_declared_but_never_instantiated_host_subtype_must_not_be_an_entry_point() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph
            .classes
            .insert("com.lomo.app.feature.ZombieViewModel".to_owned());
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
        assert!(
            !contract_violations(&surface, &generated, &graph).is_empty(),
            "BLIND SPOT: a platform-subtype class nothing can construct or dispatch still \
             roots the graph — `roots ⊆ classes` proves declaration, not instantiation"
        );
    }

    /// Green control: a genuinely constructed holder still satisfies the contract under
    /// the schema-3 edge model — `App` is manifest-declared, `Holder` is constructed by
    /// a real call edge `caller → ClassFQN`, `holder → member` containment applies.
    #[test]
    fn a_constructed_holder_still_satisfies_the_consumer_contract() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph
            .classes
            .extend(["com.lomo.app.App", "com.lomo.app.Holder"].map(str::to_owned));
        graph.roots.insert("com.lomo.app.App".to_owned());
        graph.manifest.insert("com.lomo.app.App".to_owned());
        graph.edges.extend([
            (
                "com.lomo.app.App".to_owned(),
                "com.lomo.app.App.onCreate".to_owned(),
            ),
            (
                "com.lomo.app.App.onCreate".to_owned(),
                "com.lomo.app.Holder".to_owned(),
            ),
            (
                "com.lomo.app.Holder".to_owned(),
                "com.lomo.app.Holder.use".to_owned(),
            ),
            (
                "com.lomo.app.Holder.use".to_owned(),
                "com.lomo.nativebridge.deadExport".to_owned(),
            ),
        ]);
        assert!(contract_violations(&surface, &generated, &graph).is_empty());
    }

    /// Green control: member-level roots are still rejected (`ffi-graph-entry`) — the
    /// original C16 forging shape stays closed.
    #[test]
    fn member_level_entry_points_are_still_rejected() {
        let (surface, generated) = dead_export_surface();
        let mut graph = ResolvedGraph::default();
        graph
            .roots
            .insert("com.lomo.app.NeverBuilt.toString".to_owned());
        graph.edges.insert((
            "com.lomo.app.NeverBuilt.toString".to_owned(),
            "com.lomo.nativebridge.deadExport".to_owned(),
        ));
        let violations = contract_violations(&surface, &generated, &graph);
        assert!(
            violations.iter().any(|v| v.rule == "ffi-graph-entry"),
            "member identities must stay out of `roots`: {violations:?}"
        );
    }
}
