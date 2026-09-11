package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: ColorSource sealed model + storage value round-trip.
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: ColorSource is the single source of truth for where the app's M3 ColorScheme hues come from
 *   (Material You wallpaper, a curated preset, or a user-picked seed). Persistence is a stable opaque string.
 *
 * Scenarios:
 * - Given each ColorSource variant, when serialised then deserialised, then the original value is recovered.
 * - Given a null or blank storage value, when parsed, then default() is returned (treated as fresh install).
 * - Given an unknown/corrupt storage value, when parsed, then default() is returned (no exception).
 * - Given a preset id with an unknown identifier, when parsed, then default() is returned.
 * - Given a custom seed value, when serialised, then the persisted form is uppercase hex with the 'seed:#'
 *   prefix (no alpha leakage).
 *
 * Observable outcomes:
 * - ColorSource.fromStorageValue / ColorSource.storageValue parity and graceful corruption handling.
 *
 * TDD proof:
 * - Compiles after ColorSource + ColorPresetId are introduced; fails before because the symbols don't exist.
 *
 * Excludes:
 * - HCT/material-color-utilities scheme derivation (covered at the ui-components layer).
 */
class ColorSourceTest : DomainFunSpec() {
    init {
        test("given DynamicWallpaper variant when round-tripped through storage value then it is preserved") {
            val original: ColorSource = ColorSource.DynamicWallpaper
            ColorSource.fromStorageValue(original.storageValue) shouldBe original
        }

        test("given each preset id when round-tripped then the preset is preserved") {
            ColorPresetId.entries.forEach { id ->
                val original: ColorSource = ColorSource.Preset(id)
                ColorSource.fromStorageValue(original.storageValue) shouldBe original
            }
        }

        test("given a custom seed when round-tripped then the opaque ARGB form is preserved") {
            val original = ColorSource.CustomSeed(asOpaqueArgb(0x4F63D6))
            val recovered = ColorSource.fromStorageValue(original.storageValue)
            recovered.shouldBeInstanceOf<ColorSource.CustomSeed>()
            recovered.argb shouldBe original.argb
            original.storageValue shouldBe "seed:#4F63D6"
        }

        test("given null or blank storage value when parsed then default is returned") {
            ColorSource.fromStorageValue(null) shouldBe ColorSource.default()
            ColorSource.fromStorageValue("") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("   ") shouldBe ColorSource.default()
        }

        test("given an unknown prefix when parsed then default is returned without throwing") {
            ColorSource.fromStorageValue("unknown:value") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("preset:") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("preset:does-not-exist") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("seed:#XYZ") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("seed:#12345") shouldBe ColorSource.default()
            ColorSource.fromStorageValue("seed:#1234567") shouldBe ColorSource.default()
        }

        test("given default() when queried then DynamicWallpaper is returned") {
            ColorSource.default() shouldBe ColorSource.DynamicWallpaper
        }

        test("given preset storage value when generated then it embeds the preset's stable id value") {
            ColorSource.Preset(ColorPresetId.FOREST).storageValue shouldBe "preset:forest"
            ColorSource.Preset(ColorPresetId.OCEAN).storageValue shouldBe "preset:ocean"
        }

        test("given a non-opaque ARGB when normalised then asOpaqueArgb forces alpha to 0xFF") {
            val transparentArgb = 0x00112233
            val opaque = asOpaqueArgb(transparentArgb)
            opaque shouldBe 0xFF112233.toInt()
        }
    }
}
