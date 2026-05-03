package com.lomo.data.local

internal data class StableDatabaseRelease(
    val appVersion: String,
    val databaseVersion: Int,
)

internal object StableDatabaseBaselineCatalog {
    // Keep only the most recent stable release window in runtime migration support. Patch releases may share the
    // same DB version, so callers must deduplicate by databaseVersion rather than appVersion count.
    private const val MAX_RETAINED_SOURCE_BASELINES = 5

    // Newest stable release first. Update this list when cutting a new stable build, then prune anything that
    // falls outside the retained window.
    private val stableReleasesNewestFirst =
        listOf(
            StableDatabaseRelease(appVersion = "1.4.0", databaseVersion = 53),
            StableDatabaseRelease(appVersion = "1.3.0", databaseVersion = 46),
            StableDatabaseRelease(appVersion = "1.2.1", databaseVersion = 45),
            StableDatabaseRelease(appVersion = "1.2.0", databaseVersion = 45),
            StableDatabaseRelease(appVersion = "1.1.0", databaseVersion = 44),
            StableDatabaseRelease(appVersion = "1.0.1", databaseVersion = 44),
        )

    fun supportedSourceDatabaseVersions(): List<Int> =
        retainedPreviousStableBaselineVersions(
            releasesNewestFirst = stableReleasesNewestFirst,
            targetDatabaseVersion = MEMO_DATABASE_VERSION,
            maxRetainedSourceBaselines = MAX_RETAINED_SOURCE_BASELINES,
        )

    fun retainedPreviousStableBaselineVersions(
        releasesNewestFirst: List<StableDatabaseRelease>,
        targetDatabaseVersion: Int,
        maxRetainedSourceBaselines: Int,
    ): List<Int> =
        releasesNewestFirst
            .asSequence()
            .map(StableDatabaseRelease::databaseVersion)
            .filter { it in 1 until targetDatabaseVersion }
            .distinct()
            .take(maxRetainedSourceBaselines)
            .toList()

    fun oldestSupportedDatabaseVersion(): Int = supportedSourceDatabaseVersions().lastOrNull() ?: MEMO_DATABASE_VERSION

    fun isStableReleaseDatabaseVersion(version: Int): Boolean =
        stableReleasesNewestFirst.any { it.databaseVersion == version }
}
