package com.lomo.app.benchmark

data class BenchmarkMainScreenNavigationSnapshot(
    val hasMainRoot: Boolean,
    val hasSearchButton: Boolean,
    val hasDrawerButton: Boolean,
    val hasDrawerDestinations: Boolean,
)

enum class BenchmarkDrawerPresentation {
    CLOSED_MODAL,
    OPEN_MODAL,
    PERMANENT_SIDEBAR,
    UNKNOWN,
}

fun BenchmarkMainScreenNavigationSnapshot.drawerPresentation(): BenchmarkDrawerPresentation =
    when {
        hasDrawerDestinations && hasDrawerButton -> BenchmarkDrawerPresentation.OPEN_MODAL
        hasDrawerDestinations -> BenchmarkDrawerPresentation.PERMANENT_SIDEBAR
        hasDrawerButton -> BenchmarkDrawerPresentation.CLOSED_MODAL
        else -> BenchmarkDrawerPresentation.UNKNOWN
    }

fun BenchmarkMainScreenNavigationSnapshot.isMainScreenReady(): Boolean =
    hasMainRoot && hasSearchButton && drawerPresentation() != BenchmarkDrawerPresentation.UNKNOWN

fun BenchmarkMainScreenNavigationSnapshot.shouldCloseDrawerWithBack(): Boolean =
    drawerPresentation() == BenchmarkDrawerPresentation.OPEN_MODAL

fun BenchmarkMainScreenNavigationSnapshot.shouldOpenDrawerFromButton(): Boolean =
    drawerPresentation() == BenchmarkDrawerPresentation.CLOSED_MODAL
