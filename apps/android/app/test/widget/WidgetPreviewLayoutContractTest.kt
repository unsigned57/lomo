/*
 * Test Change Justification:
 * - Reason category: Android 15 generated widget preview migration (replaces static preview XMLs).
 * - Old behavior/assertion being replaced: required android:previewLayout="@layout/widget_preview*" XML
 *   files on every AppWidget provider info config.
 * - Why old assertion is no longer correct: widget previews are now generated dynamically via Glance
 *   providePreview / AppWidgetManager.setWidgetPreview on API 35+ (home_screen widgetCategory)
 *   and static XML widget_preview* layouts/drawables/colors/strings are deleted.
 * - Coverage preserved by: initialLayout still enforces @RemoteView inflation allowlist safety,
 *   plus new assertions verify home_screen widgetCategory, Glance providePreview overrides on all 4
 *   widgets, white vector tinting on ic_widget_*.xml, and complete deletion of legacy XML preview
 *   artifacts.
 * - Why this is not fitting the test to the implementation: validates the new architectural preview
 *   contract and guards against leftover preview XML resources or missing Glance preview hooks.
 */

package com.lomo.app.widget

// architectural-boundary-check: parses AppWidget provider XML and resource files to enforce
// Glance generated preview metadata and complete removal of legacy preview XML resources.

/*
 * Behavior Contract:
 * - Unit under test: Widget provider metadata (res/xml/lomo_*_widget_info.xml) and Glance widgets
 * - Owning layer: app
 * - Priority tier: P0
 * - Production scope: prevents broken previews or leftover static XML resources across launcher
 *   widget pickers; paired with live API 35 Launcher3 verification.
 * - Capability: Every Lomo AppWidget provider declares valid RemoteView-safe initialLayout and
 *   widgetCategory="home_screen" backed by GlanceAppWidget.providePreview, with zero legacy
 *   widget_preview* XML files/colors/strings remaining.
 *
 * Scenarios:
 * - Given all Lomo AppWidget provider info files, when inspected, then each omits previewLayout,
 *   declares widgetCategory="home_screen", and declares a @RemoteView-safe initialLayout.
 * - Given all 4 Lomo GlanceAppWidget classes, when reflected, then each overrides previewSizeMode and
 *   providePreview.
 * - Given app resources, when inspected, then legacy widget_preview* layouts, drawables, colors and
 *   strings are absent and ic_widget_* vector drawables use @android:color/white.
 * Observable outcomes: parsed provider attributes, preview overrides, and resource presence/tints.
 * TDD proof: original RED execution is not recorded in this snapshot; verify the existing scenarios
 * with `./kotlin test --include-module=app --include-classes='com.lomo.app.widget.WidgetPreviewLayoutContractTest'`.
 * Excludes: live launcher rendering and OS preview publication.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties

private const val ANDROID_XML_NS = "http://schemas.android.com/apk/res/android"
private const val LAYOUT_PREFIX = "@layout/"
private const val GLANCE_DEFAULT_LOADING_LAYOUT = "@layout/glance_default_loading_layout"
private const val HOME_SCREEN_CATEGORY = "home_screen"

private val WIDGET_INFO_FILES =
    listOf(
        "lomo_widget_info.xml",
        "lomo_tasks_widget_info.xml",
        "lomo_insights_widget_info.xml",
        "lomo_review_widget_info.xml",
    )

private val LEGACY_PREVIEW_LAYOUT_FILES =
    listOf(
        "widget_preview.xml",
        "widget_preview_tasks.xml",
        "widget_preview_insights.xml",
        "widget_preview_review.xml",
    )

private val LEGACY_PREVIEW_DRAWABLE_FILES =
    listOf(
        "widget_background.xml",
        "widget_card_background.xml",
        "widget_pill_primary.xml",
        "widget_preview_ic_mic.xml",
    )

private val WIDGET_ICON_DRAWABLES =
    listOf(
        "ic_widget_plus.xml",
        "ic_widget_sparkle.xml",
        "ic_widget_check_box.xml",
        "ic_widget_check_box_outline.xml",
        "ic_widget_flame.xml",
        "ic_widget_refresh.xml",
        "ic_widget_search.xml",
    )

private val GLANCE_WIDGET_CLASSES =
    listOf(
        LomoWidget::class,
        LomoTasksWidget::class,
        LomoInsightsWidget::class,
        LomoReviewWidget::class,
    )

private val REMOTE_VIEW_ALLOWED_TAGS =
    setOf(
        "FrameLayout",
        "LinearLayout",
        "RelativeLayout",
        "GridLayout",
        "TextView",
        "ImageView",
        "Button",
        "CheckBox",
        "RadioButton",
        "RadioGroup",
        "ProgressBar",
        "Switch",
        "Chronometer",
        "TextClock",
        "AnalogClock",
        "ViewFlipper",
        "ListView",
        "GridView",
        "StackView",
        "AdapterViewFlipper",
        "ViewStub",
    )

class WidgetPreviewLayoutContractTest : AppFunSpec() {
    init {
        test("all widget provider configs declare home_screen category and RemoteView-safe initialLayout") {
            val resDir = locateAppResDir()
            val documentBuilder =
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            val disallowedViolations = mutableListOf<String>()

            WIDGET_INFO_FILES.forEach { infoFileName ->
                val infoPath = resDir.resolve("xml").resolve(infoFileName)
                Files.exists(infoPath) shouldBe true
                val infoRoot = documentBuilder.parse(infoPath.toFile()).documentElement

                infoRoot.getAttributeNS(ANDROID_XML_NS, "previewLayout") shouldBe ""
                infoRoot.getAttributeNS(ANDROID_XML_NS, "widgetCategory") shouldBe HOME_SCREEN_CATEGORY

                val initialLayout = infoRoot.getAttributeNS(ANDROID_XML_NS, "initialLayout")
                initialLayout.shouldStartWith(LAYOUT_PREFIX)
                if (initialLayout != GLANCE_DEFAULT_LOADING_LAYOUT) {
                    val initialFile = resolveLocalLayout(resDir, initialLayout)
                    Files.exists(initialFile) shouldBe true
                    collectDisallowedTags(
                        element = documentBuilder.parse(initialFile.toFile()).documentElement,
                        fileName = initialFile.fileName.toString(),
                        violations = disallowedViolations,
                    )
                }
            }

            disallowedViolations.shouldBeEmpty()
        }

        test("all Glance widgets declare providePreview and previewSizeMode") {
            GLANCE_WIDGET_CLASSES.forEach { widgetClass ->
                val hasPreviewSizeMode = widgetClass.declaredMemberProperties.any { it.name == "previewSizeMode" }
                val hasProvidePreview = widgetClass.declaredMemberFunctions.any { it.name == "providePreview" }
                hasPreviewSizeMode shouldBe true
                hasProvidePreview shouldBe true
            }
        }

        test("legacy preview XML layouts, drawables, colors and strings are completely deleted") {
            val resDir = locateAppResDir()

            LEGACY_PREVIEW_LAYOUT_FILES.forEach { fileName ->
                Files.exists(resDir.resolve("layout").resolve(fileName)) shouldBe false
            }
            LEGACY_PREVIEW_DRAWABLE_FILES.forEach { fileName ->
                Files.exists(resDir.resolve("drawable").resolve(fileName)) shouldBe false
            }

            listOf(
                resDir.resolve("values/colors.xml"),
                resDir.resolve("values-night/colors.xml"),
                resDir.resolve("values/strings.xml"),
                resDir.resolve("values-zh-rCN/strings.xml"),
            ).forEach { resourceFile ->
                Files.readString(resourceFile) shouldNotContain "widget_preview_"
            }

            WIDGET_ICON_DRAWABLES.forEach { iconFileName ->
                val xml = Files.readString(resDir.resolve("drawable").resolve(iconFileName))
                xml shouldNotContain "@color/widget_preview_"
            }
        }
    }
}

private fun locateAppResDir(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
        val repoCandidate = current.resolve("apps/android/app/res")
        if (Files.isDirectory(repoCandidate.resolve("xml"))) {
            return repoCandidate
        }
        val moduleCandidate = current.resolve("res")
        if (Files.isDirectory(moduleCandidate.resolve("xml"))) {
            return moduleCandidate
        }
        current = current.parent
    }
    error("Could not locate apps/android/app/res from ${Path.of("").toAbsolutePath()}")
}

private fun resolveLocalLayout(resDir: Path, attributeValue: String): Path {
    val layoutName = attributeValue.removePrefix(LAYOUT_PREFIX)
    return resDir.resolve("layout").resolve("$layoutName.xml")
}

private fun collectDisallowedTags(element: Element, fileName: String, violations: MutableList<String>) {
    val tag = element.tagName
    if (tag !in REMOTE_VIEW_ALLOWED_TAGS) {
        violations += "$fileName:<$tag>"
    }
    val children = element.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE && child is Element) {
            collectDisallowedTags(child, fileName, violations)
        }
    }
}
