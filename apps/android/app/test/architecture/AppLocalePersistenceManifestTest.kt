/*
 * Behavior Contract:
 * Capability: the manifest must wire AppCompat's locale persistence so an in-app language
 * choice survives process death on API 26-32; owning layer: app; priority: P1.
 * Scenarios:
 * - Given AndroidManifest.xml, when inspected, then the application declares
 *   android:localeConfig AND the disabled AppLocalesMetadataHolderService carrying
 *   autoStoreLocales=true.
 * Observable outcomes: manifest markup assertions.
 * TDD proof: RED while only localeConfig was declared — the holder service is what AppCompat
 * reads to decide whether to persist locales on pre-33 devices.
 * Excludes: actual runtime locale application; Compose locale resolution.
 * // architectural-boundary-check: pins the shipped manifest against silent locale loss.
 */

package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class AppLocalePersistenceManifestTest : AppFunSpec() {
    init {
        test("manifest declares localeConfig and the AppCompat locale storage opt-in service") {
            val manifest = manifestFile().readText()

            manifest shouldContain "android:localeConfig=\"@xml/locales_config\""
            manifest shouldContain "androidx.appcompat.app.AppLocalesMetadataHolderService"
            withClue("autoStoreLocales must opt in so API 26-32 survives process death") {
                Regex(
                    """android:name="autoStoreLocales"\s+android:value="true"""",
                    setOf(RegexOption.MULTILINE),
                ).containsMatchIn(manifest) shouldBe true
            }
        }
    }
}

private fun manifestFile(): File {
    val currentDir = File(System.getProperty("user.dir") ?: ".")
    val candidates =
        listOf(
            currentDir.resolve("src/AndroidManifest.xml"),
            currentDir.resolve("app/src/AndroidManifest.xml"),
        )
    return checkNotNull(candidates.firstOrNull(File::isFile)) {
        "AndroidManifest.xml not found from ${currentDir.absolutePath}"
    }
}
