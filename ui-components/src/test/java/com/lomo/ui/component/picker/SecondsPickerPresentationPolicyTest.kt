/*
 * Behavior Contract:
 * - Unit under test: SecondsPickerPresentationPolicy
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: seconds wheel presentation policy formats displayed seconds
 *   while localized Android resources own the full selected-value TalkBack
 *   sentence; the policy also decides whether a centered-value change should
 *   emit haptic feedback or animated motion.
 *
 * Scenarios:
 * - Given a single-digit second and a Locale.ROOT digit policy, when display
 *   text is requested, then the value is rendered with two stable ASCII digits.
 * - Given a locale with non-Latin digits, when display text is requested, then
 *   the value is rendered with that locale's two-digit number system.
 * - Given an out-of-range second, when display text is requested, then the
 *   value is clamped to the wheel range before rendering.
 * - Given selected-state accessibility semantics are needed, when resource
 *   strings are inspected, then both supported locales provide a complete
 *   state-description format with the formatted second as a placeholder.
 * - Given selected-state accessibility semantics are needed, when the policy
 *   API is used, then it exposes no selected-state sentence builder that could
 *   own localized grammar, spacing, or word order.
 * - Given a centered second changes because the user scrolled, when the value
 *   differs from the externally supplied value, then the policy emits both a
 *   value change and one haptic tick.
 * - Given a centered second changes because external value sync scrolled the
 *   wheel, when policy is evaluated, then no haptic tick or duplicate value
 *   change is emitted.
 * - Given reduced motion is requested, when external sync behavior is resolved,
 *   then the wheel uses immediate scrolling instead of animation.
 * - Given TalkBack or another accessibility service requests a progress value,
 *   when the requested value is fractional or outside the wheel range, then the
 *   action clamps to 0..59, rounds to the nearest second, reports handled, and
 *   emits only when the target differs from the current value.
 *
 * Observable outcomes:
 * - formatted text, localized resource formats, public policy method surface,
 *   pure selection-effect values, and pure accessibility progress-action values.
 *
 * TDD proof:
 * - Locale formatting: production already satisfied; the retained test-only
 *   proof fails if Locale.ROOT no longer renders stable two-digit ASCII text.
 * - Non-Latin locale formatting: production already satisfied; the retained
 *   test-only proof fails if ar-EG digits are replaced by root digits.
 * - Clamp: production already satisfied; the retained test-only proof fails if
 *   display text formats unclamped inputs.
 * - Localized selected-state semantics: RED before the fix because the policy
 *   still exposes `selectedStateDescription(...)`, allowing Kotlin to own
 *   selected-state grammar, and the resource files do not yet provide
 *   `seconds_wheel_picker_state_description`.
 * - User-scroll haptic/value emission: production already satisfied; the
 *   retained test-only proof fails if user-origin center changes stop emitting
 *   both observable effects.
 * - External sync silence: production already satisfied; the retained
 *   test-only proof fails if externally driven wheel sync emits duplicate
 *   value changes or haptics.
 * - Reduced motion: production already satisfied; the retained test-only proof
 *   fails if reduced motion no longer resolves to immediate scrolling.
 * - Accessibility progress action: RED before the fix because the policy has
 *   no accessibilityProgressAction API for the Compose SetProgress semantics
 *   hook to share and test.
 *
 * Excludes:
 * - Compose LazyColumn rendering, TalkBack runtime behavior, platform haptic
 *   implementation, and Android framework resource lookup.
 */
package com.lomo.ui.component.picker

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class SecondsPickerPresentationPolicyTest : FunSpec({
    test("given single digit second and root locale when display text requested then two ASCII digits are returned") {
        SecondsPickerPresentationPolicy.displayText(second = 5, locale = Locale.ROOT) shouldBe "05"
    }

    test("given Arabic Egypt locale when display text requested then localized two digit text is returned") {
        SecondsPickerPresentationPolicy.displayText(
            second = 5,
            locale = Locale.forLanguageTag("ar-EG"),
        ) shouldBe "\u0660\u0665"
    }

    test("given out of range second when display text requested then value is clamped before formatting") {
        SecondsPickerPresentationPolicy.displayText(second = 64, locale = Locale.ROOT) shouldBe "59"
    }

    test("given selected state semantics when resources inspected then each locale owns the full sentence pattern") {
        assertSoftly {
            pickerStringResource(
                localeDirectory = "values",
                name = "seconds_wheel_picker_state_description",
            ) shouldBe "%1\$s seconds selected"
            pickerStringResource(
                localeDirectory = "values-zh-rCN",
                name = "seconds_wheel_picker_state_description",
            ) shouldBe "\u5df2\u9009\u4e2d %1\$s \u79d2"
        }
    }

    test("given selected state semantics when policy api inspected then policy exposes no sentence builder") {
        SecondsPickerPresentationPolicy::class.java
            .declaredMethods
            .map { method -> method.name }
            .toSet()
            .shouldNotContain("selectedStateDescription")
    }

    test("given user scroll changes centered second when effect requested then value change and haptic are emitted") {
        SecondsPickerPresentationPolicy.selectionEffect(
            origin = SecondsPickerChangeOrigin.UserScroll,
            previousCenteredSecond = 4,
            centeredSecond = 5,
            externalValue = 4,
        ) shouldBe SecondsPickerSelectionEffect(
            emitValueChange = true,
            emitHaptic = true,
        )
    }

    test("given external sync changes centered second when effect requested then no value change or haptic is emitted") {
        SecondsPickerPresentationPolicy.selectionEffect(
            origin = SecondsPickerChangeOrigin.ExternalValueSync,
            previousCenteredSecond = 4,
            centeredSecond = 5,
            externalValue = 5,
        ) shouldBe SecondsPickerSelectionEffect(
            emitValueChange = false,
            emitHaptic = false,
        )
    }

    test("given user scroll keeps same centered second when effect requested then no duplicate haptic is emitted") {
        SecondsPickerPresentationPolicy.selectionEffect(
            origin = SecondsPickerChangeOrigin.UserScroll,
            previousCenteredSecond = 5,
            centeredSecond = 5,
            externalValue = 4,
        ) shouldBe SecondsPickerSelectionEffect(
            emitValueChange = false,
            emitHaptic = false,
        )
    }

    test("given reduced motion requested when scroll behavior resolved then external sync scrolls immediately") {
        SecondsPickerPresentationPolicy.scrollBehavior(reduceMotion = true) shouldBe
            SecondsPickerScrollBehavior.Immediate
    }

    test("given reduced motion not requested when scroll behavior resolved then external sync animates") {
        SecondsPickerPresentationPolicy.scrollBehavior(reduceMotion = false) shouldBe
            SecondsPickerScrollBehavior.Animated
    }

    test("given accessibility progress outside range when action resolved then target is rounded clamped and emitted") {
        SecondsPickerPresentationPolicy.accessibilityProgressAction(
            requestedProgress = 72.6f,
            currentValue = 12,
        ) shouldBe SecondsPickerAccessibilityProgressAction(
            handled = true,
            targetSecond = 59,
            emitValueChange = true,
        )
    }

    test("given accessibility progress equals current value when action resolved then action is handled without duplicate emit") {
        SecondsPickerPresentationPolicy.accessibilityProgressAction(
            requestedProgress = 12.4f,
            currentValue = 12,
        ) shouldBe SecondsPickerAccessibilityProgressAction(
            handled = true,
            targetSecond = 12,
            emitValueChange = false,
        )
    }
})

private fun pickerStringResource(
    localeDirectory: String,
    name: String,
): String {
    val stringsFile =
        File("ui-components/src/main/res/$localeDirectory/strings.xml")
            .takeIf(File::isFile)
            ?: File("src/main/res/$localeDirectory/strings.xml")
    val document =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(stringsFile)
    val strings = document.getElementsByTagName("string")
    return (0 until strings.length)
        .asSequence()
        .map { index -> strings.item(index) }
        .first { node -> node.attributes.getNamedItem("name").nodeValue == name }
        .textContent
}
