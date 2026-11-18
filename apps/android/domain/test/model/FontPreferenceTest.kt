package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: FontPreference sealed model + storage value round-trip.
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: FontPreference is the single source of truth for the typeface family the app
 *   renders text in, including optional OpenType variable-axis overrides. Persistence is a
 *   stable opaque string consumed by the data layer.
 *
 * Scenarios:
 * - Given each FontPreference variant (including UserImported with axis overrides), when serialised
 *   then deserialised, then the original value is recovered canonically.
 * - Given a null or blank storage value, when parsed, then default() (SystemDefault) is returned.
 * - Given a corrupt storage value (path separators, invalid axis tags, non-finite axis floats),
 *   when parsed, then default() is returned — guards against directory traversal and bad configs.
 *
 * Observable outcomes:
 * - FontPreference.fromStorageValue / FontPreference.storageValue parity.
 *
 * TDD proof:
 * - New axis-override scenarios fail before FontPreference.UserImported supports axisOverrides.
 *
 * Production scope: apps/android/domain/src/model/FontPreference.kt
 *
 * Excludes:
 * - File system operations (covered by CustomFontStoreImplTest in data layer).
 *
 * Test Change Justification:
 * - Reason category: Feature extension for variable-font axis overrides inside FontPreference.
 * - Old behavior/assertion being replaced: existing assertions only exercised bare custom ids.
 * - Why old assertion is no longer correct: UserImported now optionally carries variable axis
 *   overrides that serialize/deserialize alongside the font id.
 * - Coverage preserved by: all existing SystemDefault, bare UserImported, blank, unknown-prefix,
 *   and path-traversal assertions remain verbatim.
 * - Why this is not fitting the test to the implementation: tests assert the storage round-trip
 *   and malformed-input rejection contract required by SettingsCatalog and FontPreferencesRepository.
 */
class FontPreferenceTest : DomainFunSpec() {
    init {
        test("SystemDefault round-trip is identity") {
            val original: FontPreference = FontPreference.SystemDefault
            FontPreference.fromStorageValue(original.storageValue) shouldBe original
        }

        test("UserImported round-trip preserves the id") {
            val original = FontPreference.UserImported("abc-123.ttf")
            val recovered = FontPreference.fromStorageValue(original.storageValue)
            recovered.shouldBeInstanceOf<FontPreference.UserImported>()
            recovered.id shouldBe "abc-123.ttf"
            recovered.axisOverrides shouldBe emptyMap()
            original.storageValue shouldBe "custom:abc-123.ttf"
        }

        test("UserImported round-trip preserves sorted variable axis overrides") {
            val original =
                FontPreference.UserImported(
                    id = "LXGW-VF.ttf",
                    axisOverrides = mapOf("wght" to 450f, "wdth" to 92.5f),
                )

            original.storageValue shouldBe "custom:LXGW-VF.ttf?wdth=92.5&wght=450"
            val recovered = FontPreference.fromStorageValue(original.storageValue)
            recovered shouldBe
                FontPreference.UserImported(
                    id = "LXGW-VF.ttf",
                    axisOverrides = mapOf("wdth" to 92.5f, "wght" to 450f),
                )
        }

        test("malformed axis queries or traversal characters in id are rejected") {
            FontPreference.fromStorageValueOrNull("custom:abc.ttf?") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:abc.ttf?bad=400") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:abc.ttf?wght=NaN") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:abc.ttf?wght=Infinity") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:abc.ttf?wght=400&wght=500") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:a?b.ttf") shouldBe null
            FontPreference.fromStorageValueOrNull("custom:abc.ttf#frag") shouldBe null
        }

        test("null or blank storage value collapses to default") {
            FontPreference.fromStorageValue(null) shouldBe FontPreference.SystemDefault
            FontPreference.fromStorageValue("") shouldBe FontPreference.default()
            FontPreference.fromStorageValue("   ") shouldBe FontPreference.default()
        }

        test("ids containing path separators are rejected for safety") {
            FontPreference.fromStorageValue("custom:../escape.ttf") shouldBe FontPreference.default()
            FontPreference.fromStorageValue("custom:dir/sub.ttf") shouldBe FontPreference.default()
            FontPreference.fromStorageValue("custom:\\\\escape.ttf") shouldBe FontPreference.default()
        }

        test("unknown prefix collapses to default") {
            FontPreference.fromStorageValue("unknown:value") shouldBe FontPreference.default()
            FontPreference.fromStorageValue("custom:") shouldBe FontPreference.default()
        }
    }
}
