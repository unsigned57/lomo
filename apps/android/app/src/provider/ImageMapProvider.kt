package com.lomo.app.provider

import android.net.Uri
import androidx.core.net.toUri
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.MediaImageDescriptor
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
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
    repository: MediaRepository,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
        // behavior-contract: unmanaged-scope-ok: process-lifetime app-scoped image map
        private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

        open val imageMap: StateFlow<Map<String, Uri>> =
            repository
                .observeImageLocations()
                .map { locationMap ->
                    locationMap
                        .mapKeys { (entryId, _) -> entryId.raw }
                        .mapValues { (_, descriptor) -> descriptor.toContentKeyedUri() }
                }.stateIn(
                    scope = scope,
                    started = appWhileSubscribed(),
                    initialValue = emptyMap(),
                )
    }

/**
 * Content-keyed display URI: the fragment carries the Rust-witnessed digest so Coil, dimension
 * and thumbnail caches key on content identity while URI path resolution ignores the fragment
 * for file/content IO. Location alone is never treated as content identity.
 */
private const val CONTENT_ID_FRAGMENT_PREFIX = "lomo-cid="

private fun MediaImageDescriptor.toContentKeyedUri(): Uri {
    val base = location.raw.toUri()
    val identity = contentId?.takeIf { it.isNotBlank() } ?: return base
    return base.buildUpon().fragment("$CONTENT_ID_FRAGMENT_PREFIX$identity").build()
}
