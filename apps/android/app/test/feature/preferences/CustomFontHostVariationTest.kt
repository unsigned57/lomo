package com.lomo.app.feature.preferences

import androidx.compose.ui.text.font.FontFamily
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.domain.model.CustomFontVariationResolver
import com.lomo.domain.model.FontPreference
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

/*
 * Behavior Contract:
 * - Production scope: apps/android/app/src/feature/preferences/CustomFontHost.kt
 * - Production scope: apps/android/app/src/feature/preferences/AppPreferencesState.kt
 * - Unit under test: CustomFontHost variable-axis font resolution and AppPreferencesState flow mapping.
 * - Owning layer: app feature/preferences.
 * - Priority tier: P1.
 * - Capability: resolve declared OpenType variable-font axes and UserImported axis overrides into
 *   distinct multi-weight FontFamily requests and lightweight preview requests with axis-aware cache keys.
 *
 * Scenarios:
 * - Given a variable font with wght and ital axes, when familyOrNull resolves UserImported
 *   overrides, then FontLoadRequest carries resolved variationSettings and StandardWeightSlots.
 * - Given updated axis overrides on the same font file, when familyOrNull runs again, then the
 *   loader is re-invoked with the new axis settings while identical overrides hit the cache.
 * - Given live slider preview adjustments, when familyForPreview runs, then PreviewWeightSlots are
 *   requested so preview generation avoids building all ten weight slots.
 * - Given UserImported with axis overrides, when observeAppPreferences resolves state, then
 *   Compose FontFamily receives active axes.
 *
 * Observable outcomes: captured FontLoadRequest payloads, cache hit counts, and
 * AppPreferencesState customFontFamily.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-module=app --include-classes=com.lomo.app.feature.preferences.CustomFontHostVariationTest`
 *   fails compilation until FontLoadRequest, familyForPreview,
 *   and UserImported axis propagation are added to CustomFontHost.
 * - GREEN: same command passes with all four scenarios green.
 *
 * Excludes: real Android Typeface/FontVariation.Settings native rasterization (abstracted behind
 * FontFamilyLoader fake).
 */
class CustomFontHostVariationTest : AppFunSpec() {
    init {
        test(
            "given a variable font when familyOrNull resolves UserImported axis overrides then loader receives resolved settings and standard slots",
        ) {
            runTest {
                val fontFile = writeTempVariableFont(includeItal = true)
                try {
                    val store = FakeCustomFontStore().apply {
                        registerFontPath("var.ttf", fontFile.absolutePath)
                    }
                    val capturedRequests = mutableListOf<FontLoadRequest>()
                    val host = CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { request ->
                            capturedRequests += request
                            FontFamily.SansSerif
                        },
                    )

                    val resolved = host.familyOrNull(
                        FontPreference.UserImported(
                            id = "var.ttf",
                            axisOverrides = mapOf("wght" to 620f, "opsz" to 24f),
                        ),
                    )

                    resolved shouldNotBe null
                    capturedRequests.size shouldBe 1
                    val request = capturedRequests.single()
                    request.path shouldBe fontFile.absolutePath
                    request.variationSettings["wght"] shouldBe 620f
                    request.variationSettings["opsz"] shouldBe 24f
                    request.weightSlots shouldContainExactly CustomFontVariationResolver.StandardWeightSlots
                    request.variableAxes.map { it.tag } shouldContainExactly listOf("wght", "opsz", "ital")
                } finally {
                    fontFile.delete()
                }
            }
        }

        test(
            "given changed axis overrides when familyOrNull runs then cache differentiates variants while matching overrides reuse cache",
        ) {
            runTest {
                val fontFile = writeTempVariableFont(includeItal = false)
                try {
                    val store = FakeCustomFontStore().apply {
                        registerFontPath("var.ttf", fontFile.absolutePath)
                    }
                    var loadCount = 0
                    val host = CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { request ->
                            loadCount++
                            if ((request.variationSettings["wght"] ?: 0f) >= 600f) {
                                FontFamily.Serif
                            } else {
                                FontFamily.SansSerif
                            }
                        },
                    )

                    val first = host.familyOrNull(
                        FontPreference.UserImported("var.ttf", mapOf("wght" to 500f)),
                    )
                    val firstRepeat = host.familyOrNull(
                        FontPreference.UserImported("var.ttf", mapOf("wght" to 500f)),
                    )
                    val second = host.familyOrNull(
                        FontPreference.UserImported("var.ttf", mapOf("wght" to 700f)),
                    )

                    first shouldBe FontFamily.SansSerif
                    firstRepeat shouldBe FontFamily.SansSerif
                    second shouldBe FontFamily.Serif
                    loadCount shouldBe 2
                } finally {
                    fontFile.delete()
                }
            }
        }

        test("given live axis preview when familyForPreview is called then PreviewWeightSlots are requested") {
            runTest {
                val fontFile = writeTempVariableFont(includeItal = false)
                try {
                    val store = FakeCustomFontStore().apply {
                        registerFontPath("var.ttf", fontFile.absolutePath)
                    }
                    val capturedRequests = mutableListOf<FontLoadRequest>()
                    val host = CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { request ->
                            capturedRequests += request
                            FontFamily.Monospace
                        },
                    )

                    val preview = host.familyForPreview(
                        fontId = "var.ttf",
                        axisOverrides = mapOf("wght" to 550f),
                    )

                    preview shouldBe FontFamily.Monospace
                    capturedRequests.single().weightSlots shouldContainExactly
                        CustomFontVariationResolver.PreviewWeightSlots
                    capturedRequests.single().variationSettings["wght"] shouldBe 550f
                } finally {
                    fontFile.delete()
                }
            }
        }

        test(
            "given UserImported with axisOverrides when observeAppPreferences runs then host receives the full preference overrides",
        ) {
            runTest {
                val fontFile = writeTempVariableFont(includeItal = false)
                try {
                    val store = FakeCustomFontStore().apply {
                        registerFontPath("var.ttf", fontFile.absolutePath)
                    }
                    val capturedRequests = mutableListOf<FontLoadRequest>()
                    val host = CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { request ->
                            capturedRequests += request
                            FontFamily.Serif
                        },
                    )
                    val repo = FakeAppConfigRepository().apply {
                        setFontPreference(
                            FontPreference.UserImported(
                                id = "var.ttf",
                                axisOverrides = mapOf("wght" to 640f),
                            ),
                        )
                    }

                    val state = repo.observeAppPreferences(
                        store,
                        host,
                    ).first { it.customFontFamily == FontFamily.Serif }

                    state.customFontStatus shouldBe CustomFontStatus.READY
                    capturedRequests.last().variationSettings["wght"] shouldBe 640f
                } finally {
                    fontFile.delete()
                }
            }
        }
    }

    private fun writeTempVariableFont(includeItal: Boolean): File {
        val file = Files.createTempFile("lomo_var_host_test", ".ttf").toFile()
        file.writeBytes(buildMinimalVariableTtf(includeItal))
        return file
    }

    private fun buildMinimalVariableTtf(includeItal: Boolean): ByteArray {
        val fvarOut = ByteArrayOutputStream()
        val axes = buildList {
            add(Triple("wght", Triple(100f, 400f, 900f), 256))
            add(Triple("opsz", Triple(8f, 14f, 72f), 257))
            if (includeItal) {
                add(Triple("ital", Triple(0f, 0f, 1f), 258))
            }
        }
        fun writeU16(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun writeU32(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun writeFixed(out: ByteArrayOutputStream, v: Float) {
            writeU32(out, (v * 65536.0).roundToInt())
        }
        writeU16(fvarOut, 1)
        writeU16(fvarOut, 0)
        writeU16(fvarOut, 16)
        writeU16(fvarOut, 2)
        writeU16(fvarOut, axes.size)
        writeU16(fvarOut, 20)
        writeU16(fvarOut, 0)
        writeU16(fvarOut, 0)
        for ((tag, bounds, nameId) in axes) {
            fvarOut.write(tag.toByteArray(Charsets.US_ASCII))
            writeFixed(fvarOut, bounds.first)
            writeFixed(fvarOut, bounds.second)
            writeFixed(fvarOut, bounds.third)
            writeU16(fvarOut, 0)
            writeU16(fvarOut, nameId)
        }
        val fvar = fvarOut.toByteArray()
        val headerAndDirectoryBytes = 12 + 16
        val sfntOut = ByteArrayOutputStream()
        writeU32(sfntOut, 0x00010000)
        writeU16(sfntOut, 1)
        writeU16(sfntOut, 16)
        writeU16(sfntOut, 0)
        writeU16(sfntOut, 0)
        sfntOut.write("fvar".toByteArray(Charsets.US_ASCII))
        writeU32(sfntOut, 0)
        writeU32(sfntOut, headerAndDirectoryBytes)
        writeU32(sfntOut, fvar.size)
        sfntOut.write(fvar)
        return sfntOut.toByteArray()
    }
}
