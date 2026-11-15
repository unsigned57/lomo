package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.FontVariationAxis
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Production scope: apps/android/app/src/feature/settings/FontVariationUiPolicy.kt
 * - Unit under test: FontVariationUiPolicy axis ordering, stepping, snapping, formatting, and
 *   override normalization for SettingsFontPage sliders.
 * - Owning layer: app feature/settings UI policy.
 * - Priority tier: P1.
 * - Capability: order visible OpenType variable axes deterministically, snap slider drags to
 *   ergonomic increments within declared bounds, format clean axis readout badges, and prune
 *   overrides equal to font defaults before persisting.
 *
 * Scenarios:
 * - Given mixed hidden, degenerate, custom, and standard OpenType axes, when orderVisibleAxes runs,
 *   then only editable non-hidden axes remain with wght/wdth/opsz/slnt/ital/GRAD ordered before
 *   custom tags.
 * - Given raw slider drag coordinates, when snapAxisValue runs, then values snap to ergonomic axis
 *   steps clamped inside [minValue, maxValue].
 * - Given an axis moved back to its defaultValue, when updateAxisOverride runs, then that key is
 *   removed from the persisted override map.
 *
 * Observable outcomes: ordered axis lists, snapped coordinates, formatted value strings, and
 * canonical normalized override maps.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=app --include-classes=com.lomo.app.feature.settings.FontVariationUiPolicyTest`
 *   fails compilation until FontVariationUiPolicy helpers are implemented.
 * - GREEN: same command passes with all assertions green.
 *
 * Excludes: Compose slider gesture handling, localization resource lookup, and DataStore persistence.
 */
class FontVariationUiPolicyTest : AppFunSpec() {
    init {
        test(
            "given mixed standard custom hidden and collapsed axes when orderVisibleAxes runs then visible axes are deterministically ordered",
        ) {
            val axes =
                listOf(
                    FontVariationAxis(tag = "CASL", name = "Casual", minValue = 0f, defaultValue = 0f, maxValue = 1f),
                    FontVariationAxis(tag = "ital", name = "Italic", minValue = 0f, defaultValue = 0f, maxValue = 1f),
                    FontVariationAxis(
                        tag = "HIDN",
                        name = "Hidden",
                        minValue = 0f,
                        defaultValue = 0f,
                        maxValue = 1f,
                        hidden = true,
                    ),
                    FontVariationAxis(
                        tag = "DEAD",
                        name = "Flat",
                        minValue = 100f,
                        defaultValue = 100f,
                        maxValue = 100f,
                    ),
                    FontVariationAxis(
                        tag = "opsz",
                        name = "Optical Size",
                        minValue = 8f,
                        defaultValue = 14f,
                        maxValue = 72f,
                    ),
                    FontVariationAxis(
                        tag = "wght",
                        name = "Weight",
                        minValue = 100f,
                        defaultValue = 400f,
                        maxValue = 900f,
                    ),
                    FontVariationAxis(
                        tag = "MONO",
                        name = "Monospace",
                        minValue = 0f,
                        defaultValue = 0f,
                        maxValue = 1f,
                    ),
                )

            val ordered = orderVisibleAxes(axes)

            ordered.map { it.tag } shouldContainExactly listOf("wght", "opsz", "ital", "CASL", "MONO")
        }

        test("given axis drags when snapAxisValue runs then coordinates snap to axis step within declared bounds") {
            val weightAxis = FontVariationAxis(
                tag = "wght",
                name = "Weight",
                minValue = 100f,
                defaultValue = 400f,
                maxValue = 900f,
            )
            val slantAxis = FontVariationAxis(
                tag = "slnt",
                name = "Slant",
                minValue = -12f,
                defaultValue = 0f,
                maxValue = 0f,
            )
            val opszAxis = FontVariationAxis(
                tag = "opsz",
                name = "Optical Size",
                minValue = 8f,
                defaultValue = 14f,
                maxValue = 72f,
            )

            snapAxisValue(weightAxis, 543.7f) shouldBe 540f
            snapAxisValue(weightAxis, 950f) shouldBe 900f
            snapAxisValue(weightAxis, 101f) shouldBe 100f
            snapAxisValue(slantAxis, -5.68f) shouldBe -5.5f
            snapAxisValue(opszAxis, 18.4f) shouldBe 18f
        }

        test(
            "given non-default vs default coordinate updates when updateAxisOverride runs then default values are pruned",
        ) {
            val weightAxis = FontVariationAxis(
                tag = "wght",
                name = "Weight",
                minValue = 100f,
                defaultValue = 400f,
                maxValue = 900f,
            )
            val opszAxis = FontVariationAxis(
                tag = "opsz",
                name = "Optical Size",
                minValue = 8f,
                defaultValue = 14f,
                maxValue = 72f,
            )
            val axes = listOf(weightAxis, opszAxis)

            val updated = updateAxisOverride(axes, emptyMap(), "wght", 624f)
            updated shouldBe mapOf("wght" to 620f)

            val resetWeight = updateAxisOverride(axes, updated, "wght", 402f)
            resetWeight.shouldBeEmpty()
        }

        test("given float axis values when formatAxisValue runs then integers tenths and hundredths format cleanly") {
            formatAxisValue(400f) shouldBe "400"
            formatAxisValue(-5.5f) shouldBe "-5.5"
            formatAxisValue(0.75f) shouldBe "0.75"
        }
    }
}
