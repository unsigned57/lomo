package com.lomo.domain.repository

import com.lomo.domain.model.StorageLocation

interface AudioPlaybackResolverRepository {
    fun setRootLocation(location: StorageLocation?)

    fun setVoiceLocation(location: StorageLocation?)

    suspend fun resolve(source: String): String?
}
