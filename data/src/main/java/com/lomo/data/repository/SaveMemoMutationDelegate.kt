package com.lomo.data.repository

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId

internal class SaveMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
    private val storageFormatProvider: MemoStorageFormatProvider,
) : MemoSaveMutationOperations {
    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ) = runtime.mutationGate.withLock {
        val savePlan = createSavePlan(runtime, storageFormatProvider, content, timestamp, geoLocation)
        persistMainMemoEntity(runtime.daoBundle, MemoEntity.fromDomain(savePlan.memo))
        flushSavedMemoToFileLocked(savePlan)
        runtime.memoVersionRecorder.enqueueLocalRevision(
            memo = savePlan.memo,
            lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
            origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_CREATE,
        )
    }

    override suspend fun saveMemoInDb(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): SaveDbResult =
        runtime.mutationGate.withLock {
            val savePlan = createSavePlan(runtime, storageFormatProvider, content, timestamp, geoLocation)
            val outboxId =
                persistMemoWithOutbox(
                    daoBundle = runtime.daoBundle,
                    memo = MemoEntity.fromDomain(savePlan.memo),
                    outbox = buildCreateOutbox(savePlan),
                )
            runtime.memoVersionRecorder.enqueueLocalRevision(
                memo = savePlan.memo,
                lifecycleState = com.lomo.domain.model.MemoRevisionLifecycleState.ACTIVE,
                origin = com.lomo.domain.model.MemoRevisionOrigin.LOCAL_CREATE,
            )
            SaveDbResult(savePlan = savePlan, outboxId = outboxId)
        }

    override suspend fun flushSavedMemoToFile(savePlan: MemoSavePlan) =
        runtime.mutationGate.withLock {
            flushSavedMemoToFileLocked(savePlan)
        }

    private suspend fun flushSavedMemoToFileLocked(savePlan: MemoSavePlan) {
        appendMainMemoContentAndUpdateState(
            runtime = runtime,
            filename = savePlan.filename,
            rawContent = savePlan.rawContent,
        )
    }
}

internal suspend fun createSavePlan(
    runtime: MemoMutationRuntime,
    storageFormatProvider: MemoStorageFormatProvider,
    content: String,
    timestamp: Long,
    geoLocation: String? = null,
): MemoSavePlan {
    val settings = storageFormatProvider.current()
    val filenameFormat = settings.filenameFormat
    val timestampFormat = settings.timestampFormat
    val zoneId = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(timestamp)
    val dateString =
        StorageFilenameFormats
            .formatter(filenameFormat)
            .withZone(zoneId)
            .format(instant)
    val timeString =
        StorageTimestampFormats
            .formatter(timestampFormat)
            .withZone(zoneId)
            .format(instant)
    val baseId = runtime.memoIdentityPolicy.buildBaseId(dateString, timeString, content)
    val precomputedCollisionCount =
        runtime.daoBundle.memoIdentityDao.countMemoIdCollisions(
            baseId = baseId,
            globPattern = "${baseId}_*",
        )
    val precomputedSameTimestampCount =
        runtime.daoBundle.memoIdentityDao.countMemosByIdGlob("${dateString}_${timeString}_*")
    return runtime.savePlanFactory.create(
        content = content,
        timestamp = timestamp,
        filenameFormat = filenameFormat,
        timestampFormat = timestampFormat,
        existingFileContent = "",
        precomputedSameTimestampCount = precomputedSameTimestampCount,
        precomputedCollisionCount = precomputedCollisionCount,
        geoLocation = geoLocation,
    )
}

internal suspend fun persistMainMemoEntity(
    daoBundle: MemoMutationDaoBundle,
    entity: MemoEntity,
) {
    daoBundle.memoWriteDao.insertMemo(entity)
    daoBundle.memoTagDao.replaceTagRefsForMemo(entity)
    daoBundle.memoImageDao.replaceImageRefsForMemo(entity)
}

internal fun buildCreateOutbox(savePlan: MemoSavePlan): MemoFileOutboxEntity =
    MemoFileOutboxEntity(
        operation = MemoFileOutboxOp.CREATE,
        memoId = savePlan.memo.id,
        memoDate = savePlan.memo.dateKey,
        memoTimestamp = savePlan.memo.timestamp,
        memoRawContent = savePlan.memo.rawContent,
        newContent = savePlan.memo.content,
        createRawContent = savePlan.rawContent,
    )
