package com.lomo.domain.provider

import android.net.Uri
import com.lomo.domain.repository.MemoRepository
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
 * Eliminates duplicate image map loading code across ViewModels.
 * Previously, MainViewModel, SearchViewModel, and TagFilterViewModel each had
 * their own _imageMap StateFlow with identical initialization logic.
 *
 * This provider is scoped as Singleton and shares a single image map Flow
 * for the entire application, improving efficiency and reducing code duplication.
 */
@Singleton
class ImageMapProvider
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        // Application-scoped coroutine scope for sharing the StateFlow
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Shared StateFlow of image filename -> Uri mapping.
         *
         * Usage in ViewModels:
         * ```kotlin
         * @Inject lateinit var imageMapProvider: ImageMapProvider
         *
         * val imageMap: StateFlow<Map<String, Uri>> = imageMapProvider.imageMap
         * ```
         */
        val imageMap: StateFlow<Map<String, Uri>> =
            repository
                .getImageUriMap()
                .map { stringMap ->
                    stringMap.mapValues { (_, uriString) -> Uri.parse(uriString) }
                }.stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = emptyMap(),
                )
    }
