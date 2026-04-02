package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.repository.S3SyncOperationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncOperationRepositoryImpl
    @Inject
    constructor(
        private val syncExecutor: S3SyncExecutor,
        private val statusTester: S3SyncStatusTester,
    ) : S3SyncOperationRepository {
        private val syncGuard = AtomicBoolean(false)

        override suspend fun sync(): S3SyncResult =
            withSyncGuard(inProgressMessage = "S3 sync already in progress") {
                syncExecutor.performSync()
            }

        override suspend fun getStatus(): S3SyncStatus = statusTester.getStatus()

        override suspend fun testConnection(): S3SyncResult = statusTester.testConnection()

        private suspend fun withSyncGuard(
            inProgressMessage: String,
            block: suspend () -> S3SyncResult,
        ): S3SyncResult {
            if (!syncGuard.compareAndSet(false, true)) {
                return S3SyncResult.Success(inProgressMessage)
            }
            return try {
                block()
            } finally {
                syncGuard.set(false)
            }
        }
    }

@Singleton
class S3SyncStatusTester
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val fileBridge: S3SyncFileBridge,
    ) {
        suspend fun getStatus(): S3SyncStatus {
            val config = support.resolveConfig() ?: return S3SyncStatus(0, 0, 0, null)
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return support.withClient(config) { client ->
                runtime.stateHolder.state.value = S3SyncState.Listing
                val (localFiles, remoteFiles, metadata) =
                    coroutineScope {
                        val localFilesDeferred = async { fileBridgeScope.localFiles(layout) }
                        val remoteFilesDeferred = async { fileBridgeScope.remoteFiles(client, layout, config) }
                        val metadataDeferred =
                            async {
                                runtime.metadataDao.getAll().associateBy { it.relativePath }
                            }
                        Triple(
                            localFilesDeferred.await(),
                            remoteFilesDeferred.await(),
                            metadataDeferred.await(),
                        )
                    }
                val plan = runtime.planner.plan(localFiles, remoteFiles, metadata)
                S3SyncStatus(
                    remoteFileCount = remoteFiles.size,
                    localFileCount = localFiles.size,
                    pendingChanges = plan.pendingChanges,
                    lastSyncTime = runtime.dataStore.s3LastSyncTime.first().takeIf { it > 0L },
                )
            }
        }

        suspend fun testConnection(): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    runtime.stateHolder.state.value = S3SyncState.Connecting
                    val prefix = encodingSupport.remoteKeyPrefix(config)
                    val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
                    val mode = resolveLocalSyncMode(runtime)
                    verifyAccessWithTimeout(client, prefix)
                    validateListingCompatibility(client, prefix, config, layout, mode)
                    S3SyncResult.Success("S3 connection successful")
                }
            }.getOrElse(support::mapConnectionTestError)
        }

        private suspend fun validateListingCompatibility(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
            config: S3ResolvedConfig,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ) {
            val sampledKeys = sampleRemoteKeys(client, prefix)
            val sampledAnalysis = analyzeRemoteKeys(sampledKeys, prefix, config, layout, mode)
            if (sampledAnalysis.hasCompatibleKey) {
                return
            }
            val (remoteKeys, analysis) =
                if (sampledKeys.size == S3_CONNECTION_TEST_SAMPLE_LIMIT) {
                    val allKeys = allRemoteKeys(client, prefix)
                    allKeys to analyzeRemoteKeys(allKeys, prefix, config, layout, mode)
                } else {
                    sampledKeys to sampledAnalysis
                }
            if (analysis.hasCompatibleKey) {
                return
            }
            if (analysis.firstInvalidKey != null) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.ENCRYPTION_FAILED,
                    message =
                        incompatibleEncryptedListingMessage(
                            config = config,
                            prefix = prefix,
                            sampleKey = analysis.firstInvalidKey,
                            cause = analysis.lastDecodeError,
                        ),
                    cause = analysis.lastDecodeError,
                )
            }
            if (remoteKeys.isNotEmpty()) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.ENCRYPTION_FAILED,
                    message = vaultRootMismatchMessage(prefix, analysis.ignoredExternalKeys),
                )
            }
        }

        private suspend fun analyzeRemoteKeys(
            remoteKeys: List<String>,
            prefix: String,
            config: S3ResolvedConfig,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): ListingCompatibilityAnalysis {
            var firstInvalidKey: String? = null
            var lastDecodeError: Throwable? = null
            val ignoredExternalKeys = mutableListOf<String>()
            for (remoteKey in remoteKeys) {
                val rawRelativePath = remoteKey.removePrefix(prefix)
                val decoded =
                    when (config.encryptionMode) {
                        S3EncryptionMode.NONE -> rawRelativePath
                        else ->
                            runNonFatalCatching {
                                encodingSupport.decodeRelativePath(remoteKey, config)
                            }.onFailure { error ->
                                val ignored =
                                    runNonFatalCatching {
                                        isObviousPlaintextExternalPathForEncryptedConnectionCheck(
                                            rawRelativePath,
                                            layout,
                                            mode,
                                        )
                                    }.getOrDefault(false)
                                if (ignored) {
                                    ignoredExternalKeys += rawRelativePath
                                } else if (firstInvalidKey == null) {
                                    firstInvalidKey = remoteKey
                                }
                                lastDecodeError = error
                            }.getOrNull()
                    }
                if (!decoded.isNullOrBlank() &&
                    !isIgnoredExternalPathForConnectionCheck(decoded, layout, mode)
                ) {
                    return ListingCompatibilityAnalysis(hasCompatibleKey = true)
                } else if (!decoded.isNullOrBlank()) {
                    ignoredExternalKeys += decoded
                }
            }
            return ListingCompatibilityAnalysis(
                hasCompatibleKey = false,
                firstInvalidKey = firstInvalidKey,
                lastDecodeError = lastDecodeError,
                ignoredExternalKeys = ignoredExternalKeys,
            )
        }

        private suspend fun sampleRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ): List<String> =
            listRemoteKeys(
                client = client,
                prefix = prefix,
                maxKeys = S3_CONNECTION_TEST_SAMPLE_LIMIT,
                phase = "listing sampled remote keys",
            )

        private suspend fun allRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ): List<String> =
            listRemoteKeys(
                client = client,
                prefix = prefix,
                maxKeys = null,
                phase = "scanning remote keys",
            )

        private suspend fun listRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
            maxKeys: Int?,
            phase: String,
        ): List<String> =
            withTimeoutOrNull(S3_CONNECTION_TEST_TIMEOUT_MS) {
                client.listKeys(prefix = prefix, maxKeys = maxKeys)
            } ?: throw S3SyncFailureException(
                code = S3SyncErrorCode.CONNECTION_FAILED,
                message = connectionTimeoutMessage(prefix, phase),
            )

        private suspend fun verifyAccessWithTimeout(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ) {
            val completed =
                withTimeoutOrNull(S3_CONNECTION_TEST_TIMEOUT_MS) {
                    client.verifyAccess(prefix)
                    true
                }
            if (completed != true) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.CONNECTION_FAILED,
                    message = connectionTimeoutMessage(prefix, "verifying bucket access"),
                )
            }
        }
    }

private data class ListingCompatibilityAnalysis(
    val hasCompatibleKey: Boolean,
    val firstInvalidKey: String? = null,
    val lastDecodeError: Throwable? = null,
    val ignoredExternalKeys: List<String> = emptyList(),
)

private fun connectionTimeoutMessage(
    prefix: String,
    phase: String,
): String =
    "S3 connection test timed out after " +
        "${S3_CONNECTION_TEST_TIMEOUT_MS / MILLIS_PER_SECOND}s while $phase for prefix '$prefix'. " +
        "Check the endpoint URL, region, addressing style, and TLS/network connectivity."

private fun incompatibleEncryptedListingMessage(
    config: S3ResolvedConfig,
    prefix: String,
    sampleKey: String?,
    cause: Throwable?,
): String {
    val sampleDetail = sampleKey?.let { " Sample key: '$it'." }.orEmpty()
    val decoderDetail = cause?.message?.takeIf(String::isNotBlank)?.let { " Decoder detail: $it." }.orEmpty()
    return "No ${config.encryptionMode.name}-compatible object names were found under prefix '$prefix'.$sampleDetail " +
        "Check the S3 prefix, encryption mode, and encryption password. " +
        "If this bucket contains plaintext objects or data written by another tool/config, point Lomo at the exact Remotely Save prefix.$decoderDetail"
}

private fun vaultRootMismatchMessage(
    prefix: String,
    ignoredExternalKeys: List<String>,
): String {
    val rootLabel = prefix.takeIf(String::isNotBlank)?.let { "'$it'" } ?: "the bucket root"
    val sample =
        ignoredExternalKeys
            .distinct()
            .take(IGNORED_EXTERNAL_SAMPLE_LIMIT)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString()
            ?.let { " Ignored samples: $it." }
            .orEmpty()
    return "The remote root $rootLabel does not appear to match Lomo's current content-only sync scope. " +
        "Only markdown files and supported attachments are considered syncable; hidden paths such as .obsidian/ are ignored.$sample"
}

private const val S3_CONNECTION_TEST_TIMEOUT_MS = 15_000L
private const val S3_CONNECTION_TEST_SAMPLE_LIMIT = 32
private const val IGNORED_EXTERNAL_SAMPLE_LIMIT = 3
private const val MILLIS_PER_SECOND = 1_000L
