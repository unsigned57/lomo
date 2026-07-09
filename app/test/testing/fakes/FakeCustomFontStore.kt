package com.lomo.app.testing.fakes

import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.repository.CustomFontStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [CustomFontStore]. Backing state is fully in-memory; no real file IO.
 *
 * `resolveFontPath` returns the path that was associated with the id via [registerFontPath]; null
 * otherwise. This mirrors the production "missing file → null" contract so AppPreferencesState
 * fallback behaviour can be exercised in tests.
 */
open class FakeCustomFontStore : CustomFontStore {
    private val fonts: MutableStateFlow<List<CustomFontInfo>> = MutableStateFlow(emptyList())
    private val paths: MutableMap<String, String> = mutableMapOf()

    fun registerFontPath(id: String, path: String?) {
        if (path == null) paths.remove(id) else paths[id] = path
    }

    fun setFonts(value: List<CustomFontInfo>) {
        fonts.value = value
    }

    override fun observeFonts(): Flow<List<CustomFontInfo>> = fonts.asStateFlow()

    override suspend fun importFont(contents: ByteArray, originalFileName: String): CustomFontInfo? = null

    override suspend fun deleteFont(id: String) {
        paths.remove(id)
        fonts.value = fonts.value.filterNot { it.id == id }
    }

    override suspend fun resolveFontPath(id: String): String? = paths[id]
}
