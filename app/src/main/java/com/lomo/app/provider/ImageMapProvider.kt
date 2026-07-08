package com.lomo.app.provider

import android.net.Uri
import androidx.core.net.toUri
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn


/**
 * Shared provider for image URI mapping.
 *
 * App-scoped so multiple ViewModels can reuse one StateFlow instead of rebuilding
 * identical mapping pipelines.
 */
open class ImageMapProvider(
    private val repository: MediaRepository,
) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        open val imageMap: StateFlow<Map<String, Uri>> =
            repository
                .observeImageLocations()
                .map { locationMap ->
                    locationMap
                        .mapKeys { (entryId, _) -> entryId.raw }
                        .mapValues { (_, location) -> location.raw.toUri() }
                }.stateIn(
                    scope = scope,
                    started = appWhileSubscribed(),
                    initialValue = emptyMap(),
                )
    }
