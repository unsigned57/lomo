/*
 * Test Contract:
 * - Unit under test: StableDatabaseBaselineCatalog
 * - Behavior focus: mapping of baseline schema versions to SQL scripts.
 * - Observable outcomes: correct script resolution, validation of version ranges.
 * - Red phase: Not applicable - unit tests for baseline schema management.
 * - Excludes: SQLite execution, Room internals.
 */
package com.lomo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
class StableDatabaseBaselineCatalogTest {
    @Test
    fun supportedSourceDatabaseVersions_returnsDistinctPreviousStableBaselines_forCurrentRelease() {
        assertEquals(
            listOf(46, 45, 44),
            StableDatabaseBaselineCatalog.supportedSourceDatabaseVersions(),
        )
    }

    @Test
    fun retainedPreviousStableBaselineVersions_deduplicatesPatchReleases_and_limitsHistory() {
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

        assertEquals(
            listOf(59, 58, 57, 56, 55),
            StableDatabaseBaselineCatalog.retainedPreviousStableBaselineVersions(
                releasesNewestFirst = releasesNewestFirst,
                targetDatabaseVersion = 60,
                maxRetainedSourceBaselines = 5,
            ),
        )
    }

    @Test
    fun oldestSupportedDatabaseVersion_returnsOldestRetainedStableBaseline() {
        assertEquals(44, StableDatabaseBaselineCatalog.oldestSupportedDatabaseVersion())
    }

    @Test
    fun isStableReleaseDatabaseVersion_matchesReleasedBaselines_only() {
        assertTrue(StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(53))
        assertTrue(StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(45))
        assertFalse(StableDatabaseBaselineCatalog.isStableReleaseDatabaseVersion(52))
    }
}
