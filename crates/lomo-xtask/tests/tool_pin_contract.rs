/*
 * Behavior Contract:
 * - Unit under test: tools.toml pin resolution for quality/diagnostics cargo tools.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: a required tool resolves its own pin by exact package name; an unknown
 *   package fails closed instead of borrowing another tool's pin.
 *
 * Scenarios:
 * - Given the repository tools.toml, when a gated tool is resolved, then its own pin returns.
 * - Given a package with no pin, when resolved, then the lookup errors.
 *
 * Observable outcomes:
 * - Resolved version strings and lookup errors.
 *
 * TDD proof:
 * - cargo-mutants --in-diff reported `replace == with !=` in the pin lookup as MISSED
 *   before this contract existed; with the mutant the unknown-package assertion below
 *   resolves a wrong pin and fails.
 *
 * Excludes:
 * - Binary installation state (checked separately by ensure_tool).
 */

#[cfg(test)]
mod tests {
    use anyhow::{Context, Result, ensure};
    use lomo_xtask::pinned_tool_version;

    #[test]
    fn pins_resolve_by_exact_package_name_and_fail_closed_on_unknown() -> Result<()> {
        let root = lomo_xtask::repository_root()?;
        for package in [
            "cargo-mutants",
            "cargo-nextest",
            "cargo-deny",
            "cargo-machete",
            "cargo-ndk",
            "cargo-llvm-cov",
        ] {
            let version = pinned_tool_version(&root, package)
                .with_context(|| format!("{package} must have an exact pin"))?;
            ensure!(!version.is_empty(), "{package} pin must be nonempty");
        }
        ensure!(
            pinned_tool_version(&root, "cargo-not-a-real-tool").is_err(),
            "an unpinned package must fail closed"
        );
        Ok(())
    }
}
