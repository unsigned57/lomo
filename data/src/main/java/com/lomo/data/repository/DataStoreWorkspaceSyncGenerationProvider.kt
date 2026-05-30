package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.readStorageRootConfig
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreWorkspaceSyncGenerationProvider
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : WorkspaceSyncGenerationProvider {
        override suspend fun activeGeneration(): WorkspaceSyncGeneration {
            val root = dataStore.readStorageRootConfig(StorageRootType.MAIN)
            val identity = root.configuredUri ?: root.configuredPath ?: UNCONFIGURED_WORKSPACE_IDENTITY
            return WorkspaceSyncGeneration("sha256:${identity.sha256Hex()}")
        }
    }

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        String.format(Locale.US, "%02x", byte.toInt() and BYTE_HEX_MASK)
    }
}

private const val UNCONFIGURED_WORKSPACE_IDENTITY = "workspace:unconfigured"
private const val BYTE_HEX_MASK = 0xff
