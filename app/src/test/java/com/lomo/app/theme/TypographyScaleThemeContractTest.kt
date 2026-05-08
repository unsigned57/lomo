package com.lomo.app.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: app theme typography-scale propagation contract.
 * - Behavior focus: settings-backed typography scales, including letter spacing, must be provided
 *   through theme composition so memo text reads the current preference instead of stale global
 *   state.
 * - Observable outcomes: source-level contract that MainActivity passes preferences into LomoTheme
 *   and LomoTheme provides those scales to UI composition.
 * - Red phase: Fails before the fix because MainActivity mutates a global TypographyScales holder
 *   via SideEffect instead of passing scales through LomoTheme composition.
 * - Excludes: Compose rendering, DataStore persistence, and slider gesture dispatch.
 */
class TypographyScaleThemeContractTest {
    @Test
    fun `main activity provides app typography scales through theme`() {
        val mainActivity = File("src/main/java/com/lomo/app/MainActivity.kt").readText()
        val theme = File("../ui-components/src/main/java/com/lomo/ui/theme/Theme.kt").readText()
        val scales = File("../ui-components/src/main/java/com/lomo/ui/theme/TypographyScales.kt").readText()

        assertTrue(
            "MainActivity must pass the settings-backed TypographyScales into LomoTheme.",
            mainActivity.contains("typographyScales = typographyScales"),
        )
        assertTrue(
            "LomoTheme must expose typography scales through composition.",
            theme.contains("typographyScales: TypographyScales = TypographyScales()") &&
                theme.contains("ProvideTypographyScales(typographyScales)"),
        )
        assertTrue(
            "TypographyScales should be backed by a composition local instead of a global mutable holder.",
            scales.contains("staticCompositionLocalOf") &&
                scales.contains("LocalTypographyScales"),
        )
    }
}
