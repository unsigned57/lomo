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
 * - Given a manifest with a non-string version or nested tables inside [quality],
 *   when resolved, then the parse fails closed instead of accepting a malformed pin.
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

    #[test]
    fn manifest_ignores_rich_tables_and_rejects_malformed_pins() -> Result<()> {
        let root = tempfile::tempdir()?;
        // Sibling tables carrying booleans and free-form fields must not disturb the
        // flat [quality]/[diagnostics] pins.
        std::fs::write(
            root.path().join("tools.toml"),
            "[quality]\ncargo-deny = \"0.19.9\"\n\n\
             [diagnostics]\ncargo-bloat = \"0.12.1\"\n\n\
             [ffi.boltffi_cli]\npackage = \"boltffi_cli\"\nrev = \"abc\"\n\
             required_fix_in_latest_tag = true\n",
        )?;
        ensure!(pinned_tool_version(root.path(), "cargo-deny")? == "0.19.9");
        ensure!(pinned_tool_version(root.path(), "cargo-bloat")? == "0.12.1");

        // A version that is not a string fails closed instead of being coerced.
        std::fs::write(
            root.path().join("tools.toml"),
            "[quality]\ncargo-deny = 0.19\n",
        )?;
        ensure!(pinned_tool_version(root.path(), "cargo-deny").is_err());

        // A table nested under [quality] is malformed manifest data, not a pin.
        std::fs::write(
            root.path().join("tools.toml"),
            "[quality]\ncargo-deny = \"0.19.9\"\n[quality.extra]\nnested = \"1.0\"\n",
        )?;
        ensure!(pinned_tool_version(root.path(), "cargo-deny").is_err());
        Ok(())
    }
}
