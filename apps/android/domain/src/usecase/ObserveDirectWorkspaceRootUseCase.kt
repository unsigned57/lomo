package com.lomo.domain.usecase

import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Observes the mounted Direct filesystem workspace root path for writable Rust-backed sync surfaces.
 *
 * Non-writable mounts (Opening, ReadOnlyRecovery, Retained/Revalidating) and SAF / content roots
 * map to null so remote sync polls stay idle until the Direct workspace is verified ready.
 */
class ObserveDirectWorkspaceRootUseCase(private val engineReadinessRepository: EngineReadinessRepository) {
    fun current(): String? = directWorkspaceRootOf(engineReadinessRepository.mount.value)

    fun observe(): Flow<String?> = engineReadinessRepository.mount
        .map(::directWorkspaceRootOf)
        .distinctUntilChanged()

    private fun directWorkspaceRootOf(mount: WorkspaceMount): String? {
        val raw = mount.location?.raw?.takeIf { mount.admitsProjectionWrites }
        return when {
            raw.isNullOrBlank() -> null
            raw.startsWith("content:", ignoreCase = true) -> null
            else -> File(raw).absolutePath
        }
    }
}
