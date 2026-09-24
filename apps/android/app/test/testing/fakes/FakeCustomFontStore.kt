package com.lomo.app.testing.fakes

import com.lomo.app.feature.preferences.CustomFontHost
import com.lomo.app.feature.preferences.FontFamilyLoader
import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontRejection
import com.lomo.domain.model.CustomFontSource
import com.lomo.domain.model.PreferencesCorruptionNotice
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.PreferencesHealthRepository
import com.lomo.domain.usecase.SingleDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    var importResult: CustomFontImportResult =
        CustomFontImportResult.Rejected(CustomFontRejection.INVALID_FONT)

    fun registerFontPath(id: String, path: String?) {
        if (path == null) paths.remove(id) else paths[id] = path
    }

    fun setFonts(value: List<CustomFontInfo>) {
        fonts.value = value
    }

    override fun observeFonts(): Flow<List<CustomFontInfo>> = fonts.asStateFlow()

    override suspend fun importFont(
        source: CustomFontSource,
        originalFileName: String,
    ): CustomFontImportResult = importResult

    override suspend fun deleteFont(id: String) {
        paths.remove(id)
        fonts.value = fonts.value.filterNot { it.id == id }
    }

    override suspend fun resolveFontPath(id: String): String? = paths[id]
}

/** [CustomFontHost] for tests: no platform font construction; every id resolves to the fallback. */
fun testCustomFontHost(store: CustomFontStore): CustomFontHost =
    CustomFontHost(
        customFontStore = store,
        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
        loader = FontFamilyLoader { null },
    )

/** In-memory [PreferencesHealthRepository] double for tests. */
class FakePreferencesHealthRepository : PreferencesHealthRepository {
    private val published = MutableStateFlow<PreferencesCorruptionNotice?>(null)

    override val corruptionNotice: StateFlow<PreferencesCorruptionNotice?> = published.asStateFlow()

    fun publish(notice: PreferencesCorruptionNotice) {
        published.value = notice
    }

    override fun acknowledgeCorruptionNotice() {
        published.value = null
    }
}
