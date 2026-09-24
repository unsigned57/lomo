package com.lomo.app.feature.preferences

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
 * Single app-level owner of platform font construction. Parsing happens off the Compose
 * composition path (the entry point is `suspend` and runs on the IO dispatcher), results are
 * cached by *content identity* (path + modification stamp + size, so a replaced file under the
 * same name is not a cache hit), and both the theme and the settings preview read through this
 * same cache — they can never disagree about which font version is current.
 */
class CustomFontHost(
    private val customFontStore: CustomFontStore,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val loader: FontFamilyLoader = PlatformFontFamilyLoader(),
) {
    private data class FontIdentity(
        val path: String,
        val lastModified: Long,
        val sizeBytes: Long,
    )

    private val families = ConcurrentHashMap<FontIdentity, FontFamily>()

    /**
     * Resolves the font backing [fontId]. A missing file or unparseable bytes resolve to
     * [FontFamily.SansSerif] — the documented fallback; the missing/corrupt fact stays visible to
     * callers through [com.lomo.app.feature.preferences.CustomFontStatus].
     */
    suspend fun familyFor(fontId: String): FontFamily =
        withContext(dispatcherProvider.io) {
            val path = customFontStore.resolveFontPath(fontId) ?: return@withContext FontFamily.SansSerif
            val file = File(path)
            val identity = FontIdentity(path, file.lastModified(), file.length())
            families[identity]
                ?: (loader.load(path) ?: FontFamily.SansSerif).also { families[identity] = it }
        }
}
