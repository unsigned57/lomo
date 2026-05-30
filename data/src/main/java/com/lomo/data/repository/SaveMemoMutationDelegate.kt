package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.projection.ActiveMemoProjection
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId

internal class SaveMemoMutationDelegate(
    private val runtime: MemoMutationRuntime,
    private val storageFormatProvider: MemoStorageFormatProvider,
) : MemoSaveMutationOperations {
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
                    memoProjection = MemoProjectionProjector.projectActive(savePlan.memo),
                    outbox = buildCreateOutbox(savePlan),
                )
            SaveDbResult(savePlan = savePlan, outboxId = outboxId)
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

internal suspend fun persistMainMemoProjection(
    daoBundle: MemoMutationDaoBundle,
    projection: ActiveMemoProjection,
) {
    daoBundle.memoWriteDao.insertMemo(projection.entity)
    daoBundle.memoTagDao.replaceTagRefsForMemo(projection)
    daoBundle.memoImageDao.replaceImageRefsForMemo(projection)
}

internal fun buildCreateOutbox(savePlan: MemoSavePlan): MemoFileOutboxEntity =
    MemoLifecycleCommand.createMemo(savePlan.memo).toOutboxEntity()
