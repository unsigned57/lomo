package com.lomo.app.feature.preferences

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lomo.domain.model.CustomFontVariationResolver
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.FontVariationAxis
import com.lomo.domain.model.OpenTypeVariableFontParser
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

private const val NORMAL_FONT_WEIGHT = 400
private const val MAX_CACHED_FAMILIES = 24
private const val MAX_CACHED_AXES = 32
private const val MAX_FAILED_CACHE_ENTRIES = 64

data class FontLoadRequest(
    val path: String,
    val variationSettings: Map<String, Float> = emptyMap(),
    val variableAxes: List<FontVariationAxis> = emptyList(),
    val weightSlots: List<Int> = CustomFontVariationResolver.StandardWeightSlots,
)

/**
 * Loads a [FontFamily] for a stored font file and optional variable-axis configuration. Returns
 * `null` when the bytes cannot be constructed into a platform font — the host maps that to the
 * documented system-font fallback.
 */
fun interface FontFamilyLoader {
    fun load(request: FontLoadRequest): FontFamily?
}

class PlatformFontFamilyLoader : FontFamilyLoader {
    override fun load(request: FontLoadRequest): FontFamily? = try {
        val file = File(request.path)
        if (request.variableAxes.isEmpty()) {
            FontFamily(Font(file = file))
        } else {
            val includeItalic = CustomFontVariationResolver.supportsItalicAxis(request.variableAxes)
            val fonts = buildList {
                for (weight in request.weightSlots) {
                    add(
                        buildVariationFont(
                            file = file,
                            axes = request.variableAxes,
                            baseOverrides = request.variationSettings,
                            weight = weight,
                            isItalic = false,
                        ),
                    )
                    if (includeItalic) {
                        add(
                            buildVariationFont(
                                file = file,
                                axes = request.variableAxes,
                                baseOverrides = request.variationSettings,
                                weight = weight,
                                isItalic = true,
                            ),
                        )
                    }
                }
            }
            FontFamily(fonts)
        }
    } catch (_: RuntimeException) {
        // behavior-contract: silent-result-ok: unparseable font bytes resolve to null, which
        // the host maps to the documented system-font fallback; the missing/corrupt fact
        // stays visible through CustomFontStatus
        null
    }

    private fun buildVariationFont(
        file: File,
        axes: List<FontVariationAxis>,
        baseOverrides: Map<String, Float>,
        weight: Int,
        isItalic: Boolean,
    ): Font {
        val resolved =
            CustomFontVariationResolver.resolveVariationSettings(
                axes = axes,
                axisOverrides = baseOverrides,
                targetWeight = weight,
                isItalic = isItalic,
            )
        val variationSettings =
            resolved.entries
                .sortedBy { it.key }
                .fold(FontVariation.Empty) { acc, (tag, value) ->
                    acc.merge(FontVariation.Setting(tag, value))
                }
        return Font(
            file = file,
            weight = FontWeight(weight),
            style = if (isItalic) FontStyle.Italic else FontStyle.Normal,
            variationSettings = variationSettings,
        )
    }
}

/**
 * Single app-level owner of Compose font construction. Calls parse off the composition path (on the IO
 * dispatcher) and cache by content identity (path + modification stamp + size) plus normalized
 * variable-axis settings so edited axes invalidate without stale hits and replaced files under the same
 * name never reuse old bytes.
 */
class CustomFontHost(
    private val customFontStore: CustomFontStore,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val loader: FontFamilyLoader = PlatformFontFamilyLoader(),
) {
    private data class FontIdentity(val path: String, val lastModified: Long, val sizeBytes: Long)

    private data class FamilyCacheKey(
        val identity: FontIdentity,
        val variationSettings: Map<String, Float>,
        val weightSlots: List<Int>,
    )

    private val axesByIdentity = BoundedCache<FontIdentity, List<FontVariationAxis>>(MAX_CACHED_AXES)
    private val families = BoundedCache<FamilyCacheKey, FontFamily>(MAX_CACHED_FAMILIES)
    private val failedFamilies = BoundedCache<FamilyCacheKey, Unit>(MAX_FAILED_CACHE_ENTRIES)

    /**
     * Resolves the font backing [preference], or `null` when the file is missing or its bytes
     * cannot be parsed. The null is the "missing or unusable" fact behind
     * [CustomFontStatus.MISSING] — callers that need a renderable family use [familyFor].
     */
    suspend fun familyOrNull(preference: FontPreference.UserImported): FontFamily? = loadFamilyOrNull(
        fontId = preference.id,
        axisOverrides = preference.axisOverrides,
        weightSlots = CustomFontVariationResolver.StandardWeightSlots,
    )

    /**
     * Resolves a lightweight preview [FontFamily] for [fontId] with transient [axisOverrides] so
     * live slider adjustments remain responsive without mutating persisted settings.
     */
    suspend fun familyForPreview(fontId: String, axisOverrides: Map<String, Float> = emptyMap()): FontFamily =
        loadFamilyOrNull(
            fontId = fontId,
            axisOverrides = axisOverrides,
            weightSlots = CustomFontVariationResolver.PreviewWeightSlots,
        ) ?: FontFamily.SansSerif

    /**
     * Resolves the font backing [fontId]. A missing file or unparseable bytes resolve to
     * [FontFamily.SansSerif] — the documented fallback; the missing/corrupt fact stays visible to
     * callers through [com.lomo.app.feature.preferences.CustomFontStatus].
     */
    suspend fun familyFor(fontId: String, axisOverrides: Map<String, Float> = emptyMap()): FontFamily =
        familyOrNull(FontPreference.UserImported(id = fontId, axisOverrides = axisOverrides))
            ?: FontFamily.SansSerif

    private suspend fun loadFamilyOrNull(
        fontId: String,
        axisOverrides: Map<String, Float>,
        weightSlots: List<Int>,
    ): FontFamily? = withContext(dispatcherProvider.io) {
        val path = customFontStore.resolveFontPath(fontId) ?: return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        val identity = file.identity()
        val axes = axesFor(file, identity)
        val variationSettings =
            CustomFontVariationResolver.resolveVariationSettings(
                axes = axes,
                axisOverrides = axisOverrides,
                targetWeight = NORMAL_FONT_WEIGHT,
                isItalic = false,
            )
        val key =
            FamilyCacheKey(
                identity = identity,
                variationSettings = variationSettings,
                weightSlots = if (axes.isEmpty()) emptyList() else weightSlots,
            )
        if (failedFamilies.get(key) != null) return@withContext null
        families.get(key)?.let { return@withContext it }
        loader.load(
            FontLoadRequest(
                path = path,
                variationSettings = variationSettings,
                variableAxes = axes,
                weightSlots = weightSlots,
            ),
        ).also { family ->
            if (family == null) {
                failedFamilies.put(key, Unit)
            } else {
                families.put(key, family)
            }
        }
    }

    private fun axesFor(file: File, identity: FontIdentity): List<FontVariationAxis> =
        axesByIdentity.get(identity) ?: OpenTypeVariableFontParser.parse(file).also { parsed ->
            axesByIdentity.put(identity, parsed)
        }

    private fun File.identity() = FontIdentity(path = path, lastModified = lastModified(), sizeBytes = length())

    private class BoundedCache<K, V>(maxEntries: Int) {
        private val map =
            object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean = size > maxEntries
            }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }
    }
}
