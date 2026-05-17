package com.lomo.domain.repository

interface DatabaseInitializationRepository {
    suspend fun ensureReady()
}
