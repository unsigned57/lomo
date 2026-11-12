package com.lomo.ui.component.stats

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Behavior Contract:
 * Capability: show the daily average in localized period reports; owner: ui-components; P1.
 * Scenarios:
 * - Given a formatted daily average, when the Compose resource formatter renders either locale,
 *   then the visible label contains that number rather than a literal format expression.
 * - Given counts and a period, when formatting the average, then division, rounding, zero days,
 *   and the locale's decimal separator are reflected in the number.
 * Observable outcomes: text returned by the actual Compose resource formatter.
 * TDD proof: RED `./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.component.stats.PeriodReportResourceTest'` returned literal
 * `%1$.1f / day` instead of `1.3 / day`; rerun the same command for GREEN.
 * Excludes: Compose layout and aggregation of memo counts.
 */
class PeriodReportResourceTest : UiComponentsFunSpec() {
    init {
        test("given an average when localized labels are rendered then the number is visible") {
            renderPeriodAverage("values", "1.3") shouldBe "1.3 / day"
            renderPeriodAverage("values-zh-rCN", "1.3") shouldBe "平均每天 1.3 条"
        }

        test("given counts and locale when formatting daily average then the rounded value is localized") {
            formatPeriodDailyAverage(4, 3, Locale.US) shouldBe "1.3"
            formatPeriodDailyAverage(3, 2, Locale.FRANCE) shouldBe "1,5"
            formatPeriodDailyAverage(0, 7, Locale.US) shouldBe "0.0"
            formatPeriodDailyAverage(5, 0, Locale.US) shouldBe "0.0"
        }
    }
}

private fun renderPeriodAverage(localeDirectory: String, number: String): String {
    val resource =
        listOf(
            File("ui-components/composeResources/$localeDirectory/strings.xml"),
            File("composeResources/$localeDirectory/strings.xml"),
        ).first(File::isFile)
    val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource)
        .getElementsByTagName("string")
    val template = (0 until strings.length).asSequence().map(strings::item)
        .first { node -> node.attributes.getNamedItem("name").nodeValue == "period_report_daily_average" }
        .textContent
    val formatter = Class.forName("org.jetbrains.compose.resources.StringResourcesUtilsKt")
        .getMethod("replaceWithArgs", String::class.java, List::class.java)
    return formatter.invoke(null, template, listOf(number)) as String
}
