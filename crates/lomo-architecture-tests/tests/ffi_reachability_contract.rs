// adversarial-audit: the ffi-contract reachability gate must reject exports whose
// only "consumer" is a root the detekt rule emits unconditionally (override of a non-`com.lomo.`
// member on a never-instantiated class, or an unwired generated-callback implementation), and
// the syn surface scan must see every attribute form BoltFFI honours.
//
// Probes replay the exact graph shapes `ResolvedCallGraphRule` emits into
// `contract_violations`; a RED result means the gate accepts the evasion shape.
// Live-facts evidence (target/lomo/reports/detekt/symbols, 2026-09 audit): domain roots already
// include `CalendarHeatmapThresholds.{equals,hashCode,toString}` and data roots include
// `*.close` overrides of `AutoCloseable`; `LomoEngine.close`/`shutdown` reachability bottoms
// out on the `BoltFfiNativeEnginePort.close` override root alone.
//
// The real `tests/policy/ffi.rs` is included verbatim via `#[path]` so the probes exercise
// shipped gate code, not a copy. The crate-root `Violation`/`source_files` items satisfy the
// two `super::` references that file makes into its sibling `policy` module.
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

/// Fail-closed stand-in for `policy::source_files`; the adversarial probes below exercise
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

    /// `ResolvedCallGraphRule.visitNamedFunction` roots `id` whenever any overridden base is
    /// outside `com.lomo.` — that covers `kotlin.Any.toString/equals/hashCode`,
    /// `AutoCloseable.close`, `Runnable.run`, `Comparable.compareTo`, `() -> Unit.invoke`, etc.
    /// The class owning the override is never required to be constructed, so a dead export can
    /// be "consumed" from a method nothing can dispatch to.
    #[test]
    fn uninstantiated_override_must_not_satisfy_the_consumer_contract() {
        let surface = surface("#[export] pub fn dead_export() {}");
        let generated = BTreeSet::from(["com.lomo.nativebridge.deadExport".to_owned()]);
        let mut graph = ResolvedGraph::default();
        // Shape the rule emits for `class NeverBuilt { override fun toString() = ...deadExport() }`.
        graph
            .roots
            .insert("com.lomo.app.NeverBuilt.toString".to_owned());
        graph.edges.insert((
            "com.lomo.app.NeverBuilt.toString".to_owned(),
            "com.lomo.nativebridge.deadExport".to_owned(),
        ));
        let violations = contract_violations(&surface, &generated, &graph);
        assert!(
            !violations.is_empty(),
            "BLIND SPOT: a call inside an override of a never-instantiated class satisfies \
             ffi-production-consumer; the rule roots every non-com.lomo override"
        );
    }

    /// For callbacks the rule also roots every implementation of a `com.lomo.nativebridge.*`
    /// interface (`base.startsWith("com.lomo.nativebridge.")`). `contract_violations` then only
    /// requires a `base -> impl` override edge with a reachable impl — assembly (the impl being
    /// constructed and handed to `session_open`/`EngineModule`) is never verified, so an orphan
    /// implementation class satisfies the contract.
    #[test]
    fn unwired_callback_implementation_must_not_satisfy_the_consumer_contract() {
        let surface = surface("#[export] pub trait BatchHost { fn execute(&self); }");
        let generated = BTreeSet::from(["com.lomo.nativebridge.BatchHost.execute".to_owned()]);
        let mut graph = ResolvedGraph::default();
        // `class OrphanBatchHost : BatchHost { override fun execute(...) }` — never constructed.
        graph
            .roots
            .insert("com.lomo.data.OrphanBatchHost.execute".to_owned());
        graph.edges.insert((
            "com.lomo.nativebridge.BatchHost.execute".to_owned(),
            "com.lomo.data.OrphanBatchHost.execute".to_owned(),
        ));
        let violations = contract_violations(&surface, &generated, &graph);
        assert!(
            !violations.is_empty(),
            "BLIND SPOT: an unwired callback implementation satisfies ffi-production-consumer; \
             the override edge plus auto-root prove existence, not assembly"
        );
    }

    /// `exports()` recognises an attribute only when the LAST path segment is `export`. An
    /// export hidden behind `cfg_attr` (a form rustc and the `#[boltffi::export]` proc macro
    /// both honour) is invisible to the surface scan: no `ffi-generated-declaration` obligation
    /// and no consumer check apply to it.
    #[test]
    fn cfg_attr_wrapped_export_must_appear_in_the_surface() {
        let surface =
            surface("#[cfg_attr(feature = \"hidden\", boltffi::export)] pub fn sneaky_export() {}");
        assert!(
            !surface.is_empty(),
            "BLIND SPOT: a cfg_attr-gated export is invisible to workspace_exports"
        );
    }

    /// Documents a tolerated ambiguity rather than a failure: `camel()` is non-injective
    /// (`foo_bar` and `foo__bar` both map to `fooBar`), so two distinct Rust exports can share
    /// one Kotlin contract identity. One reachable `fooBar` satisfies both. In practice the
    /// generated Kotlin would collide and fail compilation, so this is recorded as PASS-level
    /// semantics, not a gate bypass.
    #[test]
    fn distinct_rust_exports_sharing_a_camel_identity_share_one_contract() {
        let surface = surface("#[export] pub fn foo_bar() {} #[export] pub fn foo__bar() {}");
        assert_eq!(surface.len(), 2, "both rust exports are collected");
        assert!(
            surface
                .iter()
                .all(|export| export.kotlin == "com.lomo.nativebridge.fooBar"),
            "both map to the same Kotlin symbol"
        );
        let generated = BTreeSet::from(["com.lomo.nativebridge.fooBar".to_owned()]);
        let mut graph = ResolvedGraph::default();
        // Schema-3 shape: entry points are declared holder classes with instantiation
        // evidence (manifest registration here); members become reachable through
        // `holder → member` containment edges.
        graph.classes.insert("com.lomo.data.Consumer".to_owned());
        graph.roots.insert("com.lomo.data.Consumer".to_owned());
        graph.manifest.insert("com.lomo.data.Consumer".to_owned());
        graph.edges.insert((
            "com.lomo.data.Consumer".to_owned(),
            "com.lomo.data.Consumer.call".to_owned(),
        ));
        graph.edges.insert((
            "com.lomo.data.Consumer.call".to_owned(),
            "com.lomo.nativebridge.fooBar".to_owned(),
        ));
        assert!(
            contract_violations(&surface, &generated, &graph).is_empty(),
            "documented: one Kotlin identity discharges two distinct Rust exports"
        );
    }
}
