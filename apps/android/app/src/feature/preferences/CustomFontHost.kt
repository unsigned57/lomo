package com.lomo.app.feature.preferences

import android.graphics.Typeface as PlatformTypeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads a [FontFamily] for a stored font file path. Returns `null` when the bytes cannot be
 * constructed into a platform font — the host maps that to the documented system-font fallback.
 */
fun interface FontFamilyLoader {
    fun load(path: String): FontFamily?
}

class PlatformFontFamilyLoader : FontFamilyLoader {
    override fun load(path: String): FontFamily? =
        try {
            FontFamily(Font(file = File(path)))
        } catch (_: RuntimeException) {
            // behavior-contract: silent-result-ok: unparseable font bytes resolve to null, which
            // the host maps to the documented system-font fallback; the missing/corrupt fact
            // stays visible through CustomFontStatus
            null
        }
}

/**
 * Loads a raw [PlatformTypeface] for non-Compose render surfaces (the share-card bitmap).
 * Returns `null` when the bytes cannot be parsed — the host maps that to the platform default.
 */
fun interface CanvasTypefaceLoader {
    fun load(path: String): PlatformTypeface?
}

class PlatformCanvasTypefaceLoader : CanvasTypefaceLoader {
    override fun load(path: String): PlatformTypeface? =
        try {
            PlatformTypeface.createFromFile(File(path))
        } catch (_: RuntimeException) {
            // behavior-contract: silent-result-ok: unparseable font bytes resolve to null, which
            // the share-card renderer maps to the platform default typeface
            null
        }
}

/**
 * Single app-level owner of platform font construction. Both render surfaces — the Compose
 * [FontFamily] theme path and the canvas [PlatformTypeface] share-card path — parse through this
 * host, off the composition path (every entry point is `suspend` and runs on the IO dispatcher).
 * Results are cached by *content identity* (path + modification stamp + size, so a replaced file
 * under the same name is not a cache hit), and load failures are cached the same way so a corrupt
 * file is not re-parsed on every emission.
 */
class CustomFontHost(
    private val customFontStore: CustomFontStore,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val loader: FontFamilyLoader = PlatformFontFamilyLoader(),
    private val canvasLoader: CanvasTypefaceLoader = PlatformCanvasTypefaceLoader(),
) {
    private data class FontIdentity(
        val path: String,
        val lastModified: Long,
        val sizeBytes: Long,
    )

    private val families = ConcurrentHashMap<FontIdentity, FontFamily>()
    private val failedFamilies = ConcurrentHashMap.newKeySet<FontIdentity>()
    private val canvasTypefaces = ConcurrentHashMap<FontIdentity, PlatformTypeface>()
    private val failedCanvasTypefaces = ConcurrentHashMap.newKeySet<FontIdentity>()

    /**
     * Resolves the font backing [fontId], or `null` when the file is missing or its bytes cannot
     * be parsed. The null is the "missing or unusable" fact behind
     * [CustomFontStatus.MISSING] — callers that need a renderable family use [familyFor].
     */
    suspend fun familyOrNull(fontId: String): FontFamily? =
        withContext(dispatcherProvider.io) {
            val path = customFontStore.resolveFontPath(fontId) ?: return@withContext null
            val identity = File(path).identity()
            if (identity in failedFamilies) return@withContext null
            families[identity]?.let { return@withContext it }
            loader.load(path).also { family ->
                if (family == null) {
                    failedFamilies += identity
                } else {
                    families[identity] = family
                }
            }
        }

    /**
     * Resolves the font backing [fontId]. A missing file or unparseable bytes resolve to
     * [FontFamily.SansSerif] — the documented fallback; the missing/corrupt fact stays visible to
     * callers through [com.lomo.app.feature.preferences.CustomFontStatus].
     */
    suspend fun familyFor(fontId: String): FontFamily = familyOrNull(fontId) ?: FontFamily.SansSerif

    /**
     * Resolves a stored font file path to the raw platform typeface for canvas rendering, or
     * `null` when the path is blank, missing, or unparseable — the share-card renderer maps null
     * to the platform default typeface.
     */
    suspend fun canvasTypefaceOrNull(fontPath: String?): PlatformTypeface? =
        withContext(dispatcherProvider.io) {
            if (fontPath.isNullOrBlank()) return@withContext null
            val file = File(fontPath)
            if (!file.exists()) return@withContext null
            val identity = file.identity()
            if (identity in failedCanvasTypefaces) return@withContext null
            canvasTypefaces[identity]?.let { return@withContext it }
            canvasLoader.load(fontPath).also { typeface ->
                if (typeface == null) {
                    failedCanvasTypefaces += identity
                } else {
                    canvasTypefaces[identity] = typeface
                }
            }
        }

    private fun File.identity() = FontIdentity(path = path, lastModified = lastModified(), sizeBytes = length())
}
