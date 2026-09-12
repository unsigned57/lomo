/*
 * Behavior Contract:
 * - Unit under test: FFI method-name matching and live store/bridge reachability.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: reject unwired native bridge methods and keep store/FFI names aligned.
 *
 * Scenarios:
 * - Given snake/camel method names, when converted, then each form round-trips.
 * - Given Kotlin calls hidden in comments or strings, when scanned, then they are not callers.
 * - Given a plumbing forwarder invoked from production, when reachability runs, then the
 *   production path is the observable caller.
 * - Given the live workspace, when parity and reachability run, then they succeed.
 *
 * Observable outcomes:
 * - Converted identifiers, matched call sites, caller paths, and live-workspace Result.
 *
 * TDD proof:
 * - Relocated from crates/lomo-xtask/src/ffi_parity.rs to keep production sources test-free.
 *
 * Excludes:
 * - Kotlin compilation and JNI execution.
 */

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    use lomo_xtask::{
        camel_to_snake, check_ffi_parity, check_kotlin_bridge_reachability, contains_method_call,
        extract_kotlin_bridge_methods, find_transitive_production_caller, repository_root,
        snake_to_camel, strip_comments_and_strings,
    };

    trait ResultTestExt<T> {
        fn test_ok(self, context: &str) -> T;
        fn test_error(self, context: &str) -> String;
    }

    impl<T, E: std::fmt::Display> ResultTestExt<T> for Result<T, E> {
        fn test_ok(self, context: &str) -> T {
            self.unwrap_or_else(|error| panic!("{context}: {error}"))
        }

        fn test_error(self, context: &str) -> String {
            match self {
                Ok(_value) => panic!("{context}: expected an error"),
                Err(error) => error.to_string(),
            }
        }
    }

    #[test]
    fn test_snake_and_camel_conversions() {
        assert_eq!(snake_to_camel("query_memos"), "queryMemos");
        assert_eq!(snake_to_camel("get_memo"), "getMemo");
        assert_eq!(snake_to_camel("query_count"), "queryCount");
        assert_eq!(camel_to_snake("queryMemos"), "query_memos");
        assert_eq!(camel_to_snake("getMemo"), "get_memo");
        assert_eq!(camel_to_snake("queryCount"), "query_count");
    }

    #[test]
    fn test_contains_method_call_matching() {
        let code = r#"
            // val ignored = port.queryMemos()
            /* port.queryMemos() in comment */
            val text = "port.queryMemos() in string"
            val charLiteral = '"'
            port.queryMemos(query, cursor, 10u)
            port?.getMemo(id)
            port.
                startRebuild(100u)
        "#;
        let stripped = strip_comments_and_strings(code);
        assert!(contains_method_call(&stripped, "queryMemos"));
        assert!(contains_method_call(&stripped, "getMemo"));
        assert!(contains_method_call(&stripped, "startRebuild"));
        assert!(!contains_method_call(&stripped, "queryCount"));
    }

    #[test]
    fn test_contains_method_call_recognizes_implicit_receiver_calls() {
        let code = r"
            return selectMemoPromotePlans(content, pending)
            val plans = selectMemoPromotePlans(content, pending)
            with (adapter) { selectMemoPromotePlans(content, pending) }
        ";
        let stripped = strip_comments_and_strings(code);
        assert!(contains_method_call(&stripped, "selectMemoPromotePlans"));
    }

    #[test]
    fn test_contains_method_call_ignores_declarations_and_references() {
        let declaration = "    override fun selectMemoPromotePlans(content: String): Int";
        assert!(!contains_method_call(
            &strip_comments_and_strings(declaration),
            "selectMemoPromotePlans"
        ));
        let reference = "    val alias = bridge::selectMemoPromotePlans";
        assert!(!contains_method_call(
            &strip_comments_and_strings(reference),
            "selectMemoPromotePlans"
        ));
        let property_read = "    val count = queryCount";
        assert!(!contains_method_call(
            &strip_comments_and_strings(property_read),
            "queryCount"
        ));
    }

    #[test]
    fn test_transitive_production_caller_through_plumbing_forwarder() {
        let forwarders = vec![(
            String::from("data/src/engine/store/BoltFfiStorePort.kt"),
            strip_comments_and_strings(
                r"
                override fun commitDocumentMutation(mutation: Mutation): Commit {
                    val command = toBridgeCommand(mutation)
                    return bridge.commitWorkspaceDocumentFacts(command, projection).toStoreCommit()
                }
                ",
            ),
        )];
        let production = vec![(
            String::from("data/src/repository/StoreMemoRepositories.kt"),
            strip_comments_and_strings(
                "            val commit = port.commitDocumentMutation(mutation)",
            ),
        )];
        let callers = find_transitive_production_caller(
            &forwarders,
            &production,
            "commitWorkspaceDocumentFacts",
        );
        assert_eq!(
            callers,
            vec![String::from("data/src/repository/StoreMemoRepositories.kt")]
        );
    }

    #[test]
    fn test_transitive_caller_ignores_self_delegation_and_dead_plumbing() {
        let self_delegation = vec![(
            String::from("data/src/engine/store/EngineFailureConvertingStoreBridge.kt"),
            strip_comments_and_strings(
                "    override fun commitWorkspaceDocumentFacts(command: Command): Result =\n        withEngineFailureConversion { delegate.commitWorkspaceDocumentFacts(command) }",
            ),
        )];
        let production = vec![(
            String::from("data/src/repository/StoreMemoRepositories.kt"),
            strip_comments_and_strings(
                "            val commit = port.commitDocumentMutation(mutation)",
            ),
        )];
        assert!(
            find_transitive_production_caller(
                &self_delegation,
                &production,
                "commitWorkspaceDocumentFacts"
            )
            .is_empty()
        );

        let dead_plumbing = vec![(
            String::from("data/src/engine/store/BoltFfiStorePort.kt"),
            strip_comments_and_strings(
                r"
                override fun commitDocumentMutation(mutation: Mutation): Commit =
                    bridge.commitWorkspaceDocumentFacts(mutation, projection).toStoreCommit()
                ",
            ),
        )];
        let unrelated_production = vec![(
            String::from("data/src/repository/StoreMemoRepositories.kt"),
            strip_comments_and_strings("            val page = port.queryMemos(query)"),
        )];
        assert!(
            find_transitive_production_caller(
                &dead_plumbing,
                &unrelated_production,
                "commitWorkspaceDocumentFacts"
            )
            .is_empty()
        );
    }

    #[test]
    fn test_live_workspace_ffi_parity_passes() {
        check_ffi_parity().test_ok("live workspace FFI parity and reachability must pass");
    }

    #[test]
    fn test_live_workspace_bridge_reachability_passes() {
        let kotlin_bridge_path = repository_root()
            .test_ok("workspace discovery must succeed")
            .join("apps/android/data/src/engine/store/StoreNativeBridge.kt");
        let kotlin_bridge_methods = extract_kotlin_bridge_methods(&kotlin_bridge_path)
            .test_ok("extract bridge methods must succeed");
        check_kotlin_bridge_reachability(&kotlin_bridge_methods)
            .test_ok("live workspace bridge reachability must pass");
    }

    #[test]
    fn test_uninvoked_method_rejected_without_allowlist() {
        let mut mock_methods = BTreeSet::new();
        mock_methods.insert("nonExistentBridgeMethod".to_string());
        let err = check_kotlin_bridge_reachability(&mock_methods)
            .test_error("uninvoked bridge method must fail without allowlist");
        assert!(err.contains("nonExistentBridgeMethod"));
        assert!(err.contains("0 production callers"));
    }
}
