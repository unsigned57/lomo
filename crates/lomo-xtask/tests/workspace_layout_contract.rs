/*
 * Behavior Contract:
 * - Unit under test: Workspace generated-artifact layout.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: every generated Lomo artifact lives under target/lomo, never a second root build/.
 *
 * Scenarios:
 * - Given a discovered workspace, when output directories are derived, then each path is under
 *   rust_target/lomo and not a repository-root build/ tree.
 *
 * Observable outcomes:
 * - `check_generated_artifact_layout` succeeds for the live repository pin.
 *
 * TDD proof:
 * - Relocated from crates/lomo-xtask/src/workspace.rs to keep production sources test-free.
 *
 * Excludes:
 * - Actual native/APK generation.
 */

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing layout facts"
)]
mod tests {
    #[test]
    fn generated_artifacts_live_under_lomo_namespace() {
        lomo_xtask::check_generated_artifact_layout()
            .expect("generated Lomo artifacts must stay under target/lomo");
    }
}
