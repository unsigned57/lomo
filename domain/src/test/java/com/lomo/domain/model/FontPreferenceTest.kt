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
 *   renders text in. Persistence is a stable opaque string consumed by the data layer.
 *
 * Scenarios:
 * - Given each FontPreference variant, when serialised then deserialised, then the original value
 *   is recovered.
 * - Given a null or blank storage value, when parsed, then default() (SystemDefault) is returned.
 * - Given a corrupt storage value (unknown prefix or path separators in id), when parsed, then
 *   default() is returned — guards against directory traversal via persisted ids.
 *
 * Observable outcomes:
 * - FontPreference.fromStorageValue / FontPreference.storageValue parity.
 *
 * TDD proof:
 * - Compiles after FontPreference is introduced; fails before because the symbol does not exist.
 *
 * Excludes:
 * - File system operations (covered by CustomFontStoreImplTest in data layer).
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
            original.storageValue shouldBe "custom:abc-123.ttf"
        }

        test("null or blank storage value collapses to default") {
            FontPreference.fromStorageValue(null) shouldBe FontPreference.default()
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

        test("default is SystemDefault") {
            FontPreference.default() shouldBe FontPreference.SystemDefault
        }
    }
}
