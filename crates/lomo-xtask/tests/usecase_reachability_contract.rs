/*
 * Behavior Contract:
 * - Unit under test: domain use case reachability verification.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: reject domain code without a reachable production consumer after directory moves.
 *
 * Scenarios:
 * - Given two use cases reference each other without an app/data entry point, verification rejects both.
 * - Given an app consumer invokes that graph, verification accepts the reachable transitive dependency.
 * - Given only DI, tests, imports, strings, or comments name a use case, it remains unreachable.
 * - Given the current repository with the unused use cases removed, verification succeeds.
 *
 * Observable outcomes:
 * - Results identify unreachable declarations and their source paths, or accept a rooted graph.
 *
 * TDD proof:
 * - The original live-workspace test fails because it requires already-deleted declarations.
 * - Before correcting the moved path, the isolated cycle incorrectly passes verification.
 *
 * Test Change Justification:
 * - Reason category: stale repository assumption and mechanical test migration.
 * - Old assertion: the live repository must contain GitSyncErrorUseCase and InspectAllNotesArchiveUseCase.
 * - Those declarations were deleted; an isolated unrooted graph preserves rejection coverage independently.
 * - Both positive and negative graph behavior remain required; no production exception is introduced.
 *
 * Excludes:
 * - Kotlin compiler type resolution and Android runtime execution.
 */

#[cfg(test)]
mod tests {
    use std::{fs, path::Path};

    use anyhow::{Context, Result, bail, ensure};
    use lomo_xtask::check_usecase_reachability;
    use tempfile::{TempDir, tempdir};

    fn source(root: &Path, relative: &str, text: &str) -> Result<()> {
        let path = root.join("apps/android").join(relative);
        fs::create_dir_all(path.parent().context("source parent")?)?;
        fs::write(path, text)?;
        Ok(())
    }

    fn cycle() -> Result<TempDir> {
        let fixture = tempdir()?;
        source(
            fixture.path(),
            "domain/src/usecase/FirstUseCase.kt",
            "open class FirstUseCase(private val second: SecondUseCase)",
        )?;
        source(
            fixture.path(),
            "domain/src/usecase/SecondUseCase.kt",
            "class SecondUseCase(private val first: FirstUseCase)",
        )?;
        Ok(fixture)
    }

    fn rejection(root: &Path) -> Result<String> {
        match check_usecase_reachability(root) {
            Ok(()) => bail!("an unrooted domain graph must be rejected"),
            Err(error) => Ok(error.to_string()),
        }
    }

    #[test]
    fn an_unrooted_cycle_is_rejected_after_directory_migration() -> Result<()> {
        let fixture = cycle()?;
        let error = rejection(fixture.path())?;
        for name in ["FirstUseCase", "SecondUseCase"] {
            ensure!(error.contains(name), "missing unreachable {name}: {error}");
            ensure!(error.contains(&format!("apps/android/domain/src/usecase/{name}.kt")));
        }
        Ok(())
    }

    #[test]
    fn a_production_consumer_roots_transitive_dependencies() -> Result<()> {
        let fixture = cycle()?;
        source(
            fixture.path(),
            "app/src/Screen.kt",
            "class Screen(private val first: FirstUseCase)",
        )?;
        check_usecase_reachability(fixture.path())
    }

    #[test]
    fn non_consumers_do_not_make_an_isolated_declaration_reachable() -> Result<()> {
        let fixture = tempdir()?;
        source(
            fixture.path(),
            "domain/src/usecase/UnusedUseCase.kt",
            "class UnusedUseCase\n// class CommentedUseCase\ndata class DateSnapshot(val day: Int)",
        )?;
        for relative in ["app/src/di/Bindings.kt", "app/test/ConsumerTest.kt"] {
            source(fixture.path(), relative, "val unused = UnusedUseCase()")?;
        }
        source(
            fixture.path(),
            "app/src/Screen.kt",
            "import com.lomo.domain.usecase.UnusedUseCase\n// UnusedUseCase()\nval text = \"UnusedUseCase()\"\nval other: SomeUnusedUseCaseSuffix? = null",
        )?;
        let error = rejection(fixture.path())?;
        ensure!(error.contains("UnusedUseCase"));
        ensure!(!error.contains("CommentedUseCase") && !error.contains("DateSnapshot"));
        Ok(())
    }

    #[test]
    fn live_workspace_has_no_unreachable_usecases() -> Result<()> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        check_usecase_reachability(&root)
    }
}
