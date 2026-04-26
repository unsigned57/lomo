package com.lomo.data.repository

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.util.MemoTextProcessor
import com.lomo.data.di.ApplicationScope
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal const val MAX_OUTBOX_ERROR_LENGTH = 512
internal const val OUTBOX_CLAIM_STALE_MS = 2 * 60_000L

internal class StorageFormatSettings(
    val filenameFormat: String = StorageFilenameFormats.DEFAULT_PATTERN,
    val timestampFormat: String = StorageTimestampFormats.DEFAULT_PATTERN,
    val isReady: Boolean = false,
)

data class SaveDbResult(
    val savePlan: MemoSavePlan,
    val outboxId: Long,
)

interface MemoSaveMutationOperations {
    suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String? = null,
    )

    suspend fun saveMemoInDb(
        content: String,
        timestamp: Long,
        geoLocation: String? = null,
    ): SaveDbResult

    suspend fun flushSavedMemoToFile(savePlan: MemoSavePlan)
}

interface MemoUpdateMutationOperations {
    suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    )

    suspend fun updateMemoInDb(
        memo: Memo,
        newContent: String,
    ): Long?

    suspend fun flushMemoUpdateToFile(
        memo: Memo,
        newContent: String,
    ): Boolean
}

interface MemoTrashMutationOperations {
    suspend fun deleteMemo(memo: Memo)

    suspend fun deleteMemoInDb(memo: Memo): Long?

    suspend fun flushDeleteMemoToFile(memo: Memo): Boolean

    suspend fun restoreMemo(memo: Memo)

    suspend fun restoreMemoInDb(memo: Memo): Long?

    suspend fun flushRestoreMemoToFile(memo: Memo): Boolean

    suspend fun deletePermanently(memo: Memo)

    suspend fun clearTrash()
}

interface MemoOutboxMutationOperations {
    suspend fun hasPendingMemoFileOutbox(): Boolean

    suspend fun nextMemoFileOutbox(): MemoFileOutboxEntity?

    suspend fun acknowledgeMemoFileOutbox(id: Long)

    suspend fun markMemoFileOutboxFailed(
        id: Long,
        throwable: Throwable?,
    )

    suspend fun flushMemoFileOutbox(item: MemoFileOutboxEntity): Boolean
}

/**
 * Handles active memo mutations (save/update). Trash lifecycle is delegated to [MemoTrashMutationHandler].
 */
class MemoMutationHandler private constructor(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
) : MemoSaveMutationOperations by SaveMemoMutationDelegate(runtime, storageFormatProvider),
    MemoUpdateMutationOperations by UpdateMemoMutationDelegate(runtime, storageFormatProvider),
    MemoTrashMutationOperations by TrashMemoMutationDelegate(runtime),
    MemoOutboxMutationOperations by MemoOutboxMutationDelegate(runtime, storageFormatProvider) {
    constructor(
        markdownStorageDataSource: MarkdownStorageDataSource,
        mediaStorageDataSource: com.lomo.data.source.MediaStorageDataSource,
        daoBundle: MemoMutationDaoBundle,
        memoSearchDao: com.lomo.data.local.dao.MemoSearchDao,
        localFileStateDao: LocalFileStateDao,
        savePlanFactory: MemoSavePlanFactory,
        textProcessor: MemoTextProcessor,
        dataStore: LomoDataStore,
        trashMutationHandler: MemoTrashMutationHandler,
        memoIdentityPolicy: MemoIdentityPolicy,
        memoVersionJournal: MemoVersionJournal,
        s3LocalChangeRecorder: S3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
        webDavLocalChangeRecorder: WebDavLocalChangeRecorder = NoOpWebDavLocalChangeRecorder,
        mutationGate: MemoMutationGate = MemoMutationGate(),
        settingsScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        runtime =
            MemoMutationRuntime(
                markdownStorageDataSource = markdownStorageDataSource,
                mediaStorageDataSource = mediaStorageDataSource,
                daoBundle = daoBundle,
                memoSearchDao = memoSearchDao,
                localFileStateDao = localFileStateDao,
                savePlanFactory = savePlanFactory,
                textProcessor = textProcessor,
                trashMutationHandler = trashMutationHandler,
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
                s3LocalChangeRecorder = s3LocalChangeRecorder,
                webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                mutationGate = mutationGate,
            ),
        storageFormatProvider = MemoStorageFormatProvider(dataStore, settingsScope),
    )

    @Inject
    constructor(
        markdownStorageDataSource: MarkdownStorageDataSource,
        mediaStorageDataSource: com.lomo.data.source.MediaStorageDataSource,
        memoDao: MemoDao,
        memoWriteDao: MemoWriteDao,
        memoTagDao: MemoTagDao,
        memoImageDao: MemoImageDao,
        memoIdentityDao: MemoIdentityDao,
        memoTrashDao: MemoTrashDao,
        memoOutboxDao: MemoOutboxDao,
        memoSearchDao: com.lomo.data.local.dao.MemoSearchDao,
        database: MemoDatabase,
        localFileStateDao: LocalFileStateDao,
        savePlanFactory: MemoSavePlanFactory,
        textProcessor: MemoTextProcessor,
        dataStore: LomoDataStore,
        trashMutationHandler: MemoTrashMutationHandler,
        memoIdentityPolicy: MemoIdentityPolicy,
        memoVersionRecorder: AsyncMemoVersionRecorder,
        s3LocalChangeRecorder: S3LocalChangeRecorder,
        webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
        mutationGate: MemoMutationGate,
        @ApplicationScope settingsScope: CoroutineScope,
    ) : this(
        runtime =
            MemoMutationRuntime(
                markdownStorageDataSource = markdownStorageDataSource,
                mediaStorageDataSource = mediaStorageDataSource,
                daoBundle =
                    MemoMutationDaoBundle(
                        memoDao = memoDao,
                        memoWriteDao = memoWriteDao,
                        memoTagDao = memoTagDao,
                        memoImageDao = memoImageDao,
                        memoIdentityDao = memoIdentityDao,
                        memoTrashDao = memoTrashDao,
                        memoOutboxDao = memoOutboxDao,
                        runInTransaction = { block ->
                            database.withDriverTransaction {
                                block()
                            }
                        },
                    ),
                memoSearchDao = memoSearchDao,
                localFileStateDao = localFileStateDao,
                savePlanFactory = savePlanFactory,
                textProcessor = textProcessor,
                trashMutationHandler = trashMutationHandler,
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionRecorder = memoVersionRecorder,
                s3LocalChangeRecorder = s3LocalChangeRecorder,
                webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                mutationGate = mutationGate,
            ),
        storageFormatProvider = MemoStorageFormatProvider(dataStore, settingsScope),
    )
}

internal class MemoMutationRuntime(
    val markdownStorageDataSource: MarkdownStorageDataSource,
    val mediaStorageDataSource: com.lomo.data.source.MediaStorageDataSource,
    val daoBundle: MemoMutationDaoBundle,
    val memoSearchDao: com.lomo.data.local.dao.MemoSearchDao,
    val localFileStateDao: LocalFileStateDao,
    val savePlanFactory: MemoSavePlanFactory,
    val textProcessor: MemoTextProcessor,
    val trashMutationHandler: MemoTrashMutationHandler,
    val memoIdentityPolicy: MemoIdentityPolicy,
    val memoVersionRecorder: MemoVersionRecorder,
    val s3LocalChangeRecorder: S3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
    val webDavLocalChangeRecorder: WebDavLocalChangeRecorder = NoOpWebDavLocalChangeRecorder,
    val mutationGate: MemoMutationGate = MemoMutationGate(),
)

class MemoMutationDaoBundle(
    val memoDao: MemoDao,
    val memoWriteDao: MemoWriteDao,
    val memoTagDao: MemoTagDao,
    val memoImageDao: MemoImageDao,
    val memoIdentityDao: MemoIdentityDao,
    val memoTrashDao: MemoTrashDao,
    val memoOutboxDao: MemoOutboxDao,
    val runInTransaction: suspend (suspend () -> Unit) -> Unit,
)

internal class MemoStorageFormatProvider(
    private val dataStore: LomoDataStore,
    private val settingsScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val storageFormatSettings =
        combine(
            dataStore.storageFilenameFormat,
            dataStore.storageTimestampFormat,
        ) { filenameFormat, timestampFormat ->
            StorageFormatSettings(
                filenameFormat = filenameFormat,
                timestampFormat = timestampFormat,
                isReady = true,
            )
        }.stateIn(
            scope = settingsScope,
            started = SharingStarted.Eagerly,
            initialValue = StorageFormatSettings(),
        )

    suspend fun current(): StorageFormatSettings {
        val cached = storageFormatSettings.value
        if (cached.isReady) {
            return cached
        }
        return StorageFormatSettings(
            filenameFormat = dataStore.storageFilenameFormat.first(),
            timestampFormat = dataStore.storageTimestampFormat.first(),
            isReady = true,
        )
    }

    suspend fun formatTime(timestamp: Long): String {
        val timestampFormat = current().timestampFormat
        return StorageTimestampFormats
            .formatter(timestampFormat)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }
}
