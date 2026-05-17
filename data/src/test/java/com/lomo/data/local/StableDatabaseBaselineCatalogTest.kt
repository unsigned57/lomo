/*
 * Test Contract:
 * - Unit under test: StableDatabaseBaselineCatalog
 * - Behavior focus: mapping of baseline schema versions to SQL scripts.
 * - Observable outcomes: correct script resolution, validation of version ranges.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: SQLite execution, Room internals.
 */
package com.lomo.data.local


import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Test Contract:
 * - Unit under test: StableDatabaseBaselineCatalog
 * - Behavior focus: released app versions collapse into a deduplicated stable DB-baseline support window
 *   that excludes the current target schema from direct-migration source baselines.
 * - Observable outcomes: previous stable baseline lists, oldest retained baseline selection, and released
 *   baseline membership checks.
 * - Red phase: Fails before the fix because StableDatabaseBaselineCatalog and its release-window helpers do
 *   not exist yet.
 * - Excludes: Room SQL execution, actual database open/migration behavior, and Android filesystem concerns.
 */
class StableDatabaseBaselineCatalogTest : DataFunSpec() {
    init {
        test("supportedSourceDatabaseVersions_returnsDistinctPreviousStableBaselines_forCurrentRelease") { supportedSourceDatabaseVersions_returnsDistinctPreviousStableBaselines_forCurrentRelease() }

        test("retainedPreviousStableBaselineVersions_deduplicatesPatchReleases_and_limitsHistory") { retainedPreviousStableBaselineVersions_deduplicatesPatchReleases_and_limitsHistory() }

        test("oldestSupportedDatabaseVersion_returnsOldestRetainedStableBaseline") { oldestSupportedDatabaseVersion_returnsOldestRetainedStableBaseline() }

        test("isStableReleaseDatabaseVersion_matchesReleasedBaselines_only") { isStableReleaseDatabaseVersion_matchesReleasedBaselines_only() }
    }


    private fun supportedSourceDatabaseVersions_returnsDistinctPreviousStableBaselines_forCurrentRelease() {
        StableDatabaseBaselineCatalog.supportedSourceDatabaseVersions() shouldBe listOf(54, 46, 45, 44)
    }

    private fun retainedPreviousStableBaselineVersions_deduplicatesPatchReleases_and_limitsHistory() {
        val releasesNewestFirst =
            listOf(
                StableDatabaseRelease("2.0.0", 60),
                StableDatabaseRelease("1.9.1", 59),
                StableDatabaseRelease("1.9.0", 59),
                StableDatabaseRelease("1.8.0", 58),
                StableDatabaseRelease("1.7.0", 57),
                StableDatabaseRelease("1.6.2", 56),
                StableDatabaseRelease("1.6.1", 56),
                StableDatabaseRelease("1.5.0", 55),
                StableDatabaseRelease("1.4.0", 54),
            )

        StableDatabaseBaselineCatalog.retainedPreviousStableBaselineVersions(
                releasesNewestFirst = releasesNewestFirst,
                targetDatabaseVersion = 60,
                maxRetainedSourceBaselines = 5,
            ) shouldBe listOf(59, 58, 57, 56, 55)
    }

    private fun oldestSupportedDatabaseVersion_returnsOldestRetainedStableBaseline() {
        StableDatabaseBaselineCatalog.oldestSupportedDatabaseVersion() shouldBe 44
    }

    private fun isStableReleaseDatabaseVersion_matchesReleasedBaselines_only() {
        (StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(55)).shouldBeTrue()
        (StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(54)).shouldBeTrue()
        (StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(45)).shouldBeTrue()
        (StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(53)).shouldBeFalse()
    }
}
