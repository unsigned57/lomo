package com.lomo.app.feature.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: app-layer LazyColumn motion adoption contract.
 * - Behavior focus: app features must route lazy-list row movement through the shared
 *   ui-components LazyListMotion framework instead of composing ad hoc animateItem policies.
 * - Observable outcomes: app source files contain no direct Modifier.animateItem calls and use
 *   the shared lazyListMotionItem modifier for row movement.
 * - Red phase: Fails before the fix because main memo lists, shared memo lists, trash, and
 *   device discovery still call animateItem directly from app code.
 * - Excludes: ui-components internal implementation, exact easing curves, and runtime rendering.
 */
class LazyListMotionAdoptionContractTest {
    @Test
    fun `app feature code does not call animateItem directly`() {
        val featureSources = appFeatureSourceFiles()
        val offenders =
            featureSources
                .filter { file -> file.readText().contains(".animateItem(") }
                .map { file -> file.relativeTo(appSourceRoot()).path }

        assertTrue(
            """
            App feature code must use the shared LazyListMotion framework instead of direct animateItem calls.
            Offending files:
            ${offenders.joinToString(separator = "\n")}
            """.trimIndent(),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `app feature code adopts lazyListMotionItem for row movement`() {
        val content = appFeatureSourceFiles().joinToString(separator = "\n") { file -> file.readText() }

        assertTrue(
            "Expected app feature lists to use Modifier.lazyListMotionItem from ui-components.",
            content.contains(".lazyListMotionItem("),
        )
    }

    private fun appFeatureSourceFiles(): List<File> =
        appSourceRoot()
            .resolve("com/lomo/app/feature")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun appSourceRoot(): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidates =
            listOf(
                currentDir.resolve("src/main/java"),
                currentDir.resolve("app/src/main/java"),
            )
        return checkNotNull(candidates.firstOrNull(File::exists)) {
            "Failed to resolve app source root from ${currentDir.path}"
        }
    }
}
