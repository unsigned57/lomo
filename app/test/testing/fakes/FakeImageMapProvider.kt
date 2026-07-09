package com.lomo.app.provider

import android.net.Uri
import com.lomo.app.testing.fakes.FakeMediaRepository
import kotlinx.coroutines.flow.StateFlow

class FakeImageMapProvider(
    repository: FakeMediaRepository,
    private val overrideFlow: StateFlow<Map<String, Uri>>? = null
) : ImageMapProvider(repository) {
    override val imageMap: StateFlow<Map<String, Uri>>
        get() = overrideFlow ?: super.imageMap
}
