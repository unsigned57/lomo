package com.lomo.domain.usecase

import com.lomo.domain.repository.DirectorySettingsRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Observes the Direct filesystem workspace root path for Rust-backed sync surfaces.
 *
 * SAF / content roots are not Direct paths and map to null (remote conflict poll stays idle).
 */
class ObserveDirectWorkspaceRootUseCase(
    private val directorySettingsRepository: DirectorySettingsRepository,
) {
    fun observe(): Flow<String?> =
        directorySettingsRepository
            .observeRootLocation()
            .map { location ->
                val raw = location?.raw
                when {
                    raw.isNullOrBlank() -> null
                    raw.startsWith("content:", ignoreCase = true) -> null
                    else -> File(raw).absolutePath
                }
            }.distinctUntilChanged()
}
