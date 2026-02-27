package com.lomo.domain.repository

import kotlinx.coroutines.flow.Flow

interface DirectorySettingsRepository {
    fun getRootDirectory(): Flow<String?>

    suspend fun getRootDirectoryOnce(): String?

    fun getRootDisplayName(): Flow<String?>

    fun getImageDirectory(): Flow<String?>

    fun getImageDisplayName(): Flow<String?>

    fun getVoiceDirectory(): Flow<String?>

    fun getVoiceDisplayName(): Flow<String?>

    suspend fun setRootDirectory(path: String)

    suspend fun setImageDirectory(path: String)

    suspend fun setVoiceDirectory(path: String)

    suspend fun updateRootUri(uri: String?)

    suspend fun updateImageUri(uri: String?)

    suspend fun updateVoiceUri(uri: String?)
}
