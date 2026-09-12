/*
 * Behavior Contract:
 * - Unit under test: canonicalize_binding.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: generated native-bindings Kotlin must keep the nativebridge package and drop
 *   suppression/unchecked-cast tails.
 *
 * Scenarios:
 * - Given a wrong package, when canonicalized, then the error names the required package.
 * - Given suppressions and an unused cast helper, when canonicalized, then both disappear.
 * - Given a redundant toInt on a wire-size expression, when canonicalized, then it is removed.
 * - Given a referenced unchecked helper, when canonicalized, then the helper is rejected.
 *
 * Observable outcomes:
 * - Canonical source text or a fail-closed error string.
 *
 * TDD proof:
 * - Relocated from crates/lomo-xtask/src/native.rs to keep production sources test-free.
 *
 * Excludes:
 * - BoltFFI generation and JNI loading.
 */

#[cfg(test)]
mod tests {
    use std::fmt::Display;

    use lomo_xtask::canonicalize_binding;

    trait ResultTestExt<T> {
        fn test_ok(self, context: &str) -> T;
        fn test_error(self, context: &str) -> String;
    }

    impl<T, E: Display> ResultTestExt<T> for Result<T, E> {
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
    fn canonicalize_rejects_wrong_package() {
        let error = canonicalize_binding("package com.lomo.rust\nclass X\n")
            .test_error("wrong package must fail");
        assert!(error.contains("com.lomo.nativebridge"));
    }

    #[test]
    fn canonicalize_strips_suppression_and_unused_helper() {
        let input = r#"
package com.lomo.nativebridge

@file:Suppress("UNCHECKED_CAST")
@Suppress("UNUSED")
class Demo

@Suppress("UNCHECKED_CAST")
internal fun boltffiUnsafeCast(value: Any?): Any? = value as Any?
"#;
        let out = canonicalize_binding(input).test_ok("canonical suppression removal");
        assert!(out.contains("package com.lomo.nativebridge"));
        assert!(out.contains("class Demo"));
        assert!(!out.contains("@Suppress"));
        assert!(!out.contains("boltffiUnsafeCast"));
    }

    #[test]
    fn canonicalize_removes_redundant_string_sequence_size_conversion() {
        let input = r"
package com.lomo.nativebridge

fun wireSize(values: List<String>): Int =
    values.sumOf { value -> (4 + Utf8Codec.maxBytes(value)).toInt() }
";

        let out = canonicalize_binding(input).test_ok("canonical wire size");

        assert!(out.contains("values.sumOf { value -> (4 + Utf8Codec.maxBytes(value)) }"));
        assert!(!out.contains("Utf8Codec.maxBytes(value)).toInt()"));
    }

    #[test]
    fn canonicalize_rejects_referenced_unsafe_cast_helper() {
        let input = r"
package com.lomo.nativebridge

fun use(): Any? = boltffiUnsafeCast(1)
internal fun boltffiUnsafeCast(value: Any?): Any? = value as Any?
";
        let error = canonicalize_binding(input).test_error("referenced helper must fail");
        assert!(error.contains("unchecked cast helper"));
    }
}
