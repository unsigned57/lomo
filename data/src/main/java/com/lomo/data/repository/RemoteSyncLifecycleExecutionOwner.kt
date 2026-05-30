package com.lomo.data.repository

import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.data.webdav.WebDavSmallRemoteFile
import com.lomo.domain.model.SyncBackendType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

internal interface SyncLifecycleExecutionOwner {
    fun begin(context: RemoteSyncLifecycleContext): RemoteSyncLifecycleSession
}

internal const val DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET = 100_000

internal data class RemoteSyncLifecycleContext(
    val backend: SyncBackendType,
    val budget: RemoteSyncBudgetPolicy,
)

internal sealed interface RemoteSyncBudgetPolicy {
    data object Unlimited : RemoteSyncBudgetPolicy

    data class Limited(
        val maxNetworkOperations: Int,
    ) : RemoteSyncBudgetPolicy {
        init {
            require(maxNetworkOperations >= 0) { "maxNetworkOperations must be non-negative" }
        }
    }
}

internal interface RemoteSyncLifecycleSession {
    val context: RemoteSyncLifecycleContext

    fun recordNetworkOperation(operation: RemoteSyncNetworkOperation)

    fun recordSnapshot(snapshot: RemoteSyncSnapshotTelemetry)

    fun recordPlan(actions: RemoteSyncActionTelemetry)

    fun recordVerification(actions: RemoteSyncActionTelemetry)

    fun recordRefresh(refresh: RemoteSyncRefreshTelemetry)

    suspend fun <T> measureMetadataCommit(block: suspend () -> T): T

    suspend fun <T> measureRefresh(block: suspend () -> T): T

    fun finish(result: RemoteSyncLifecycleResultTelemetry)

    fun meter(client: LomoS3Client): LomoS3Client = MeteredS3Client(client, this)

    fun meter(client: WebDavClient): WebDavClient = MeteredWebDavClient(client, this)
}

internal enum class RemoteSyncNetworkOperation {
    List,
    Head,
    Get,
    Put,
    Delete,
}

internal data class RemoteSyncSnapshotTelemetry(
    val localFileCount: Int,
    val remoteFileCount: Int,
    val metadataEntryCount: Int,
)

internal data class RemoteSyncActionTelemetry(
    val total: Int,
    val upload: Int = 0,
    val download: Int = 0,
    val deleteLocal: Int = 0,
    val deleteRemote: Int = 0,
    val conflict: Int = 0,
)

internal data class RemoteSyncNetworkTelemetry(
    val list: Int = 0,
    val head: Int = 0,
    val get: Int = 0,
    val put: Int = 0,
    val delete: Int = 0,
) {
    val total: Int
        get() = list + head + get + put + delete

    fun plus(operation: RemoteSyncNetworkOperation): RemoteSyncNetworkTelemetry =
        when (operation) {
            RemoteSyncNetworkOperation.List -> copy(list = list + 1)
            RemoteSyncNetworkOperation.Head -> copy(head = head + 1)
            RemoteSyncNetworkOperation.Get -> copy(get = get + 1)
            RemoteSyncNetworkOperation.Put -> copy(put = put + 1)
            RemoteSyncNetworkOperation.Delete -> copy(delete = delete + 1)
        }
}

internal data class RemoteSyncRefreshTelemetry(
    val durationMillis: Long,
)

internal data class RemoteSyncBudgetTelemetry(
    val decision: RemoteSyncBudgetDecision,
    val consumedNetworkOperations: Int,
    val remainingNetworkOperations: Int?,
)

internal enum class RemoteSyncBudgetDecision {
    Allowed,
    Exhausted,
}

internal enum class RemoteSyncLifecycleResultTelemetry {
    Success,
    Failure,
    Cancelled,
}

internal data class RemoteSyncLifecycleTelemetry(
    val backend: SyncBackendType,
    val snapshot: RemoteSyncSnapshotTelemetry,
    val plannedActions: RemoteSyncActionTelemetry,
    val verifiedActions: RemoteSyncActionTelemetry,
    val network: RemoteSyncNetworkTelemetry,
    val refreshDurationMillis: Long,
    val metadataCommitDurationMillis: Long,
    val budget: RemoteSyncBudgetTelemetry,
    val result: RemoteSyncLifecycleResultTelemetry,
)

internal class RemoteSyncBudgetExceededException(
    backend: SyncBackendType,
) : IllegalStateException("Remote sync network operation budget exhausted for $backend")

@Singleton
internal class TimberSyncLifecycleExecutionOwner
    @Inject
    constructor() : SyncLifecycleExecutionOwner {
        override fun begin(context: RemoteSyncLifecycleContext): RemoteSyncLifecycleSession =
            DefaultRemoteSyncLifecycleSession(
                context = context,
                clock = System::currentTimeMillis,
                emit = ::emit,
            )

        private fun emit(telemetry: RemoteSyncLifecycleTelemetry) {
            Timber.i(
                "Remote sync lifecycle telemetry backend=%s result=%s snapshot=%s planned=%s " +
                    "verified=%s network=%s refreshMs=%d metadataCommitMs=%d budget=%s",
                telemetry.backend,
                telemetry.result,
                telemetry.snapshot,
                telemetry.plannedActions,
                telemetry.verifiedActions,
                telemetry.network,
                telemetry.refreshDurationMillis,
                telemetry.metadataCommitDurationMillis,
                telemetry.budget,
            )
        }
    }

internal class DefaultRemoteSyncLifecycleSession(
    override val context: RemoteSyncLifecycleContext,
    private val clock: () -> Long,
    private val emit: (RemoteSyncLifecycleTelemetry) -> Unit,
) : RemoteSyncLifecycleSession {
    private var snapshot = RemoteSyncSnapshotTelemetry(0, 0, 0)
    private var plannedActions = RemoteSyncActionTelemetry(total = 0)
    private var verifiedActions = RemoteSyncActionTelemetry(total = 0)
    private var network = RemoteSyncNetworkTelemetry()
    private var refreshDurationMillis = 0L
    private var metadataCommitDurationMillis = 0L
    private var budgetDecision = RemoteSyncBudgetDecision.Allowed

    override fun recordNetworkOperation(operation: RemoteSyncNetworkOperation) {
        val currentBudget = context.budget
        if (currentBudget is RemoteSyncBudgetPolicy.Limited && network.total >= currentBudget.maxNetworkOperations) {
            budgetDecision = RemoteSyncBudgetDecision.Exhausted
            throw RemoteSyncBudgetExceededException(context.backend)
        }
        network = network.plus(operation)
    }

    override fun recordSnapshot(snapshot: RemoteSyncSnapshotTelemetry) {
        this.snapshot = snapshot
    }

    override fun recordPlan(actions: RemoteSyncActionTelemetry) {
        plannedActions = actions
    }

    override fun recordVerification(actions: RemoteSyncActionTelemetry) {
        verifiedActions = actions
    }

    override fun recordRefresh(refresh: RemoteSyncRefreshTelemetry) {
        refreshDurationMillis += refresh.durationMillis
    }

    override suspend fun <T> measureMetadataCommit(block: suspend () -> T): T {
        val startedAt = clock()
        return try {
            block()
        } finally {
            metadataCommitDurationMillis += (clock() - startedAt).coerceAtLeast(0)
        }
    }

    override suspend fun <T> measureRefresh(block: suspend () -> T): T {
        val startedAt = clock()
        return try {
            block()
        } finally {
            refreshDurationMillis += (clock() - startedAt).coerceAtLeast(0)
        }
    }

    override fun finish(result: RemoteSyncLifecycleResultTelemetry) {
        emit(
            RemoteSyncLifecycleTelemetry(
                backend = context.backend,
                snapshot = snapshot,
                plannedActions = plannedActions,
                verifiedActions = verifiedActions,
                network = network,
                refreshDurationMillis = refreshDurationMillis,
                metadataCommitDurationMillis = metadataCommitDurationMillis,
                budget = budgetTelemetry(),
                result = result,
            ),
        )
    }

    private fun budgetTelemetry(): RemoteSyncBudgetTelemetry {
        val remaining =
            when (val currentBudget = context.budget) {
                RemoteSyncBudgetPolicy.Unlimited -> null
                is RemoteSyncBudgetPolicy.Limited ->
                    (currentBudget.maxNetworkOperations - network.total).coerceAtLeast(0)
            }
        return RemoteSyncBudgetTelemetry(
            decision = budgetDecision,
            consumedNetworkOperations = network.total,
            remainingNetworkOperations = remaining,
        )
    }
}

private class MeteredS3Client(
    private val delegate: LomoS3Client,
    private val session: RemoteSyncLifecycleSession,
) : LomoS3Client {
    override suspend fun verifyAccess(prefix: String) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        delegate.verifyAccess(prefix)
    }

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Head)
        return delegate.getObjectMetadata(key)
    }

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        return delegate.listPage(prefix, continuationToken, maxKeys)
    }

    override suspend fun listKeys(
        prefix: String,
        maxKeys: Int?,
    ): List<String> {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        return delegate.listKeys(prefix, maxKeys)
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        return delegate.list(prefix, maxKeys)
    }

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getObjectToFile(key, destination)
    }

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getSmallObject(key)
    }

    override suspend fun getSmallObject(
        key: String,
        maxBytes: Long,
    ): S3SmallObjectPayload {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getSmallObject(key, maxBytes)
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        return delegate.putSmallObject(key, bytes, contentType, metadata)
    }

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        return delegate.putObjectFile(key, file, contentType, metadata)
    }

    override suspend fun deleteObject(key: String) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Delete)
        delegate.deleteObject(key)
    }

    override suspend fun deleteObjects(keys: List<String>) {
        keys.forEach { session.recordNetworkOperation(RemoteSyncNetworkOperation.Delete) }
        delegate.deleteObjects(keys)
    }

    override fun close() {
        delegate.close()
    }
}

private class MeteredWebDavClient(
    private val delegate: WebDavClient,
    private val session: RemoteSyncLifecycleSession,
) : WebDavClient {
    override fun ensureDirectory(path: String) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        delegate.ensureDirectory(path)
    }

    override fun list(path: String): List<WebDavRemoteResource> {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        return delegate.list(path)
    }

    override fun getToFile(
        path: String,
        destination: File,
    ): WebDavRemoteResource {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getToFile(path, destination)
    }

    override fun getSmallFile(path: String): WebDavSmallRemoteFile {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getSmallFile(path)
    }

    override fun getSmallFile(
        path: String,
        maxBytes: Long,
    ): WebDavSmallRemoteFile {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Get)
        return delegate.getSmallFile(path, maxBytes)
    }

    override fun putSmallFile(
        path: String,
        bytes: ByteArray,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        delegate.putSmallFile(path, bytes, contentType, lastModifiedHint, expectedEtag, requireAbsent)
    }

    override fun putFile(
        path: String,
        file: File,
        contentType: String,
        lastModifiedHint: Long?,
        expectedEtag: String?,
        requireAbsent: Boolean,
    ) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        delegate.putFile(path, file, contentType, lastModifiedHint, expectedEtag, requireAbsent)
    }

    override fun delete(
        path: String,
        expectedEtag: String?,
    ) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Delete)
        delegate.delete(path, expectedEtag)
    }

    override fun move(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean,
    ) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        delegate.move(sourcePath, targetPath, overwrite)
    }

    override fun copy(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean,
    ) {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.Put)
        delegate.copy(sourcePath, targetPath, overwrite)
    }

    override fun testConnection() {
        session.recordNetworkOperation(RemoteSyncNetworkOperation.List)
        delegate.testConnection()
    }
}
