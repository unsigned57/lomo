package com.lomo.domain.repository

interface AudioPlaybackResolverRepository {
    suspend fun resolve(source: String): String?
}
