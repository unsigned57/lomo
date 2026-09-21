//! Behavior Contract:
//! Capability: check explicit Rust exports against generated declarations and resolved consumers;
//! owning layer: lomo-architecture-tests; priority: P0.
//! Scenarios:
//! - Given an internal public Store API, when exports are collected, then it adds no FFI obligation.
//! - Given moved exports, missing generation, dead forwarding chains or test-only calls, then the
//!   explicit foreign contract still rejects omissions and accepts reachable callbacks.
//! - Given the app reflectively installs data Koin modules, when the one module-level contract
//!   edge is applied, then a data-side FFI consumer becomes reachable from `LomoApplication.onCreate`.
//!
//! Observable outcomes: parsed export identities and precise generated/consumer violations.
//! TDD proof: the original live parity test failed on three internal projection methods;
//! these parser/graph controls replace its invalid public-Store-equals-FFI assertion.
//! Test Change Justification:
//! Reason category: domain contract correction.
//! Old behavior/assertion being replaced: every Store public method requires a foreign facade.
//! Why old assertion is no longer correct: internal projection collaboration is not a foreign capability.
//! Coverage preserved by: explicit-export AST and resolved graph negative/positive controls.
//! Why this is not fitting the test to the implementation: legal new internal APIs and dead exports
//! are tested independently of the live facade's current method names.
//! Excludes: arbitrary dynamic reachability and JNI execution.

#[cfg(test)]
mod tests {
    use crate::policy::ffi::{
        APP_RUNTIME_DATA_KOIN_INSTALLED, APP_RUNTIME_DATA_KOIN_INSTALLER, ResolvedGraph,
        contract_violations, exports, install_app_data_koin_runtime_edge,
    };
    use std::collections::BTreeSet;

    fn parsed(source: &str) -> BTreeSet<crate::policy::ffi::Export> {
        exports(source).unwrap_or_else(|error| panic!("invalid fixture: {error}"))
    }

    #[test]
    fn internal_projection_methods_do_not_create_foreign_obligations() {
        assert!(parsed("impl Store { pub fn another_internal_projection(&self) {} }").is_empty());
    }

    #[test]
    fn exported_impls_functions_callbacks_and_nested_modules_are_collected() {
        let surface = parsed(
            "#[boltffi::export] impl Engine { pub fn load_page(&self) {} pub fn open() -> Self { loop {} } } mod moved { #[export] pub fn decode() {} } #[export] pub trait Host { fn execute(&self); }",
        );
        let kotlin: BTreeSet<_> = surface
            .iter()
            .map(|export| export.kotlin.as_str())
            .collect();
        assert_eq!(
            kotlin,
            BTreeSet::from([
                "com.lomo.nativebridge.Engine.loadPage",
                "com.lomo.nativebridge.Engine.Companion.open",
                "com.lomo.nativebridge.decode",
                "com.lomo.nativebridge.Host.execute"
            ])
        );
    }

    #[test]
    fn dead_transitive_forwarders_and_missing_generated_symbols_are_rejected() {
        let surface = parsed("#[export] pub fn load_page() {}");
        let generated = BTreeSet::from(["com.lomo.nativebridge.loadPage".to_owned()]);
        let mut graph = ResolvedGraph::default();
        graph.edges.extend([
            ("adapter".to_owned(), "middle".to_owned()),
            (
                "middle".to_owned(),
                "com.lomo.nativebridge.loadPage".to_owned(),
            ),
        ]);
        assert_eq!(contract_violations(&surface, &generated, &graph).len(), 1);
        graph.roots.insert("host".to_owned());
        graph
            .edges
            .insert(("host".to_owned(), "adapter".to_owned()));
        assert!(contract_violations(&surface, &generated, &graph).is_empty());
        assert_eq!(
            contract_violations(&surface, &BTreeSet::new(), &graph).len(),
            1
        );
    }

    #[test]
    fn callback_requires_a_real_reachable_override() {
        let surface = parsed("#[export] pub trait Host { fn execute(&self); }");
        let generated = BTreeSet::from(["com.lomo.nativebridge.Host.execute".to_owned()]);
        let mut graph = ResolvedGraph::default();
        graph.roots.insert("platformHost.execute".to_owned());
        assert_eq!(contract_violations(&surface, &generated, &graph).len(), 1);
        graph.edges.insert((
            "com.lomo.nativebridge.Host.execute".to_owned(),
            "platformHost.execute".to_owned(),
        ));
        assert!(contract_violations(&surface, &generated, &graph).is_empty());
    }

    #[test]
    fn app_runtime_koin_install_edge_makes_data_export_reachable() {
        let surface = parsed("#[export] pub fn session_get_memo() {}");
        let generated = BTreeSet::from(["com.lomo.nativebridge.sessionGetMemo".to_owned()]);
        let mut graph = ResolvedGraph::default();
        graph
            .roots
            .insert(APP_RUNTIME_DATA_KOIN_INSTALLER.to_owned());
        graph.edges.insert((
            APP_RUNTIME_DATA_KOIN_INSTALLED.to_owned(),
            "com.lomo.data.engine.BoltFfiNativeEnginePort.sessionGetMemo".to_owned(),
        ));
        graph.edges.insert((
            "com.lomo.data.engine.BoltFfiNativeEnginePort.sessionGetMemo".to_owned(),
            "com.lomo.nativebridge.sessionGetMemo".to_owned(),
        ));
        assert_eq!(contract_violations(&surface, &generated, &graph).len(), 1);
        install_app_data_koin_runtime_edge(&mut graph);
        assert!(contract_violations(&surface, &generated, &graph).is_empty());
    }
}
