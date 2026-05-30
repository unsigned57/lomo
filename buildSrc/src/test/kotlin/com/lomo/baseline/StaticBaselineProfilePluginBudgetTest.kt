package com.lomo.baseline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

/*
 * Behavior Contract:
 * - Unit under test: StaticBaselineProfilePlugin release-task budget configuration in the build-system layer.
 * - Owning layer: build-system.
 * - Priority tier: P2.
 * - Capability: wire the static baseline profile entry budget from a Gradle property into the release task used by
 *   normal plugin registration.
 *
 * Scenarios:
 * - Given lomo.staticBaselineProfile.maxTotalEntries is configured, when the release task is registered, then
 *   maxTotalEntries is assigned to the task.
 * - Given the property is absent, when the release task is registered, then the task remains unbounded.
 * - Given the property is not a non-negative integer, when the release task is registered, then configuration fails
 *   with IllegalArgumentException that names the property and invalid value.
 * - Given task generation exceeds a valid budget, when the task executes, then budget overrun remains an
 *   IllegalStateException owned by StaticBaselineProfileTask.
 *
 * Observable outcomes:
 * - StaticBaselineProfileTask.maxTotalEntries value or IllegalArgumentException from invalid plugin input.
 *
 * TDD proof:
 * - RED: `./gradlew --no-daemon --no-configuration-cache --dependency-verification off --console=plain :buildSrc:test`
 *   failed before the fix with unresolved references to STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY and
 *   registerStaticBaselineProfileTask.
 * - Why RED proves the behavior was missing: plugin task registration had no testable property-to-task seam, so the
 *   normal release task path could not receive or validate the static baseline entry budget.
 *
 * Excludes:
 * - Android Gradle Plugin internals, ScopedArtifacts wiring, and profile generation output.
 */
class StaticBaselineProfilePluginBudgetTest : FunSpec({
    test("given maxTotalEntries Gradle property when release task is registered then budget is assigned") {
        val project = createProject("configured-budget")
        project.extensions.extraProperties.set(STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY, "123")

        val task = registerStaticBaselineProfileTask(project, "release").get()

        task.maxTotalEntries.get() shouldBe 123
    }

    test("given no maxTotalEntries Gradle property when release task is registered then budget is unbounded") {
        val project = createProject("unbounded-budget")

        val task = registerStaticBaselineProfileTask(project, "release").get()

        task.maxTotalEntries.orNull shouldBe null
    }

    test("given non numeric maxTotalEntries Gradle property when release task is registered then input fails clearly") {
        val project = createProject("invalid-budget")
        project.extensions.extraProperties.set(STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY, "many")

        val error =
            shouldThrow<IllegalArgumentException> {
                registerStaticBaselineProfileTask(project, "release").get()
            }

        error.message.orEmpty() shouldContain STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY
        error.message.orEmpty() shouldContain "many"
    }

    test("given negative maxTotalEntries Gradle property when release task is registered then input fails clearly") {
        val project = createProject("negative-budget")
        project.extensions.extraProperties.set(STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY, "-1")

        val error =
            shouldThrow<IllegalArgumentException> {
                registerStaticBaselineProfileTask(project, "release").get()
            }

        error.message.orEmpty() shouldContain STATIC_BASELINE_PROFILE_MAX_TOTAL_ENTRIES_PROPERTY
        error.message.orEmpty() shouldContain "-1"
    }
})

private fun createProject(name: String): Project {
    val projectDir =
        File("build/test-work/static-baseline-profile-plugin/$name").apply {
            deleteRecursively()
            mkdirs()
        }
    return ProjectBuilder.builder().withProjectDir(projectDir).build()
}
