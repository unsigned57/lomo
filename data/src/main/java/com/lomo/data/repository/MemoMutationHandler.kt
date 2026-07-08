package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

internal const val MAX_OUTBOX_ERROR_LENGTH = 512
internal const val OUTBOX_CLAIM_STALE_MS = 2 * 60_000L

/**
 * Number of flush attempts after which an outbox item is considered a dead letter: it is no longer
 * claimed by the live drain and no longer counts as pending work that blocks reconciliation. This is
 * shared by the DAO claim/pending queries and the drain coordinator so "live" has a single meaning.
 */
internal const val MAX_OUTBOX_RETRIES = 5

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
    suspend fun saveMemoInDb(
        content: String,
        timestamp: Long,
        geoLocation: String? = null,
    ): SaveDbResult
}

interface MemoUpdateMutationOperations {
    suspend fun updateMemoInDb(
        memo: Memo,
        newContent: String,
    ): Long?
}

interface MemoTrashMutationOperations {
    suspend fun deleteMemo(memo: Memo)

    suspend fun deleteMemoInDb(memo: Memo): Long?

    suspend fun flushDeleteMemoToFile(memo: Memo): Boolean

    suspend fun restoreMemo(memo: Memo)

    suspend fun restoreMemoInDb(memo: Memo): Long?

    suspend fun flushRestoreMemoToFile(memo: Memo): Boolean

    suspend fun deletePermanentlyInDb(memo: Memo): Long?

    suspend fun clearTrash(): Int
}

interface MemoRevisionRestoreMutationOperations {
    suspend fun restoreMemoRevisionInDb(
        currentMemo: Memo,
        revisionId: String,
    ): Long
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
    MemoRevisionRestoreMutationOperations by RestoreMemoRevisionMutationDelegate(runtime),
    MemoOutboxMutationOperations by MemoOutboxMutationDelegate(runtime, storageFormatProvider) {
    constructor(
        markdownStorageDataSource: MarkdownStorageDataSource,
        mediaStorageDataSource: com.lomo.data.source.MediaStorageDataSource,
        daoBundle: MemoMutationDaoBundle,
        memoStatisticsDao: MemoStatisticsDao,
        localFileStateDao: LocalFileStateDao,
        workspaceStore: MemoWorkspaceStore,
        workspaceMediaAccess: WorkspaceMediaAccess,
        savePlanFactory: MemoSavePlanFactory,
        textProcessor: MemoTextProcessor,
        dataStore: LomoDataStore,
        trashMutationHandler: MemoTrashMutationHandler,
        memoIdentityPolicy: MemoIdentityPolicy,
        memoVersionJournal: MemoVersionJournal,
        mediaRepository: MediaRepository,
        s3LocalChangeRecorder: S3LocalChangeRecorder,
        webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
        mutationGate: MemoMutationGate = MemoMutationGate(),
        backgroundScope: CoroutineScope,
    ) : this(
        runtime =
            MemoMutationRuntime(
                markdownStorageDataSource = markdownStorageDataSource,
                mediaStorageDataSource = mediaStorageDataSource,
                daoBundle = daoBundle,
                memoStatisticsDao = memoStatisticsDao,
                localFileStateDao = localFileStateDao,
                workspaceStore = workspaceStore,
                workspaceMediaAccess = workspaceMediaAccess,
                savePlanFactory = savePlanFactory,
                textProcessor = textProcessor,
                trashMutationHandler = trashMutationHandler,
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionJournal = memoVersionJournal,
                memoVersionRestoreSupport = JournalMemoVersionRestoreSupport(memoVersionJournal),
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, backgroundScope),
                mediaRepository = mediaRepository,
                s3LocalChangeRecorder = s3LocalChangeRecorder,
                webDavLocalChangeRecorder = webDavLocalChangeRecorder,
                mutationGate = mutationGate,
            ),
        storageFormatProvider = MemoStorageFormatProvider(dataStore, backgroundScope),
    )

}

internal class MemoMutationRuntime(
    val markdownStorageDataSource: MarkdownStorageDataSource,
    val mediaStorageDataSource: com.lomo.data.source.MediaStorageDataSource,
    val daoBundle: MemoMutationDaoBundle,
    val memoStatisticsDao: MemoStatisticsDao,
    val localFileStateDao: LocalFileStateDao,
    val workspaceStore: MemoWorkspaceStore,
    val workspaceMediaAccess: WorkspaceMediaAccess,
    val savePlanFactory: MemoSavePlanFactory,
    val textProcessor: MemoTextProcessor,
    val trashMutationHandler: MemoTrashMutationHandler,
    val memoIdentityPolicy: MemoIdentityPolicy,
    val memoVersionJournal: MemoVersionJournal,
    val memoVersionRestoreSupport: MemoVersionRestoreSupport,
    val memoVersionRecorder: MemoVersionRecorder,
    val mediaRepository: MediaRepository,
    val s3LocalChangeRecorder: S3LocalChangeRecorder,
    val webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
    val mutationGate: MemoMutationGate,
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
    private val settingsScope: CoroutineScope,
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
