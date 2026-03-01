package com.lomo.app.provider

import android.net.Uri
import androidx.core.net.toUri
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared provider for image URI mapping.
 *
 * App-scoped so multiple ViewModels can reuse one StateFlow instead of rebuilding
 * identical mapping pipelines.
 */
@Singleton
class ImageMapProvider
    @Inject
    constructor(
        private val repository: MediaRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val imageMap: StateFlow<Map<String, Uri>> =
            repository
                .observeImageLocations()
                .map { locationMap ->
                    locationMap
                        .mapKeys { (entryId, _) -> entryId.raw }
                        .mapValues { (_, location) -> location.raw.toUri() }
                }.stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = emptyMap(),
                )
    }
