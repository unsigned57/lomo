package com.lomo.data.engine.store

import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.data.reminder.toNativeSnoozeInterval
import com.lomo.domain.model.ReminderInterval
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionPinMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.SessionRestoreRevisionRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoQueryBoundary as BridgeMemoQueryBoundary
import com.lomo.nativebridge.StoreMemoSort as BridgeMemoSort
import com.lomo.nativebridge.StoreMemoSortField as BridgeMemoSortField
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreSortDirection as BridgeSortDirection

/**
 * Production [StorePort] over [StoreNativeBridge] and [com.lomo.data.engine.SessionNativeBridge].
 *
 * Memo writes, including staged media promote, go through the application session. Store reads stay
 * on the projection query surface, including reminder plans which are session-owned.
 * Mapping logic is host-testable via fake bridges; real JNI stays behind the bridge only.
 *
 * Every call goes through [EngineFailureConvertingStoreBridge] or [com.lomo.data.engine.withEngineFailureConversion],
 * so an engine rejection always leaves this port as a typed
 * [com.lomo.domain.model.EngineCommandFailureException] and never as the message-less generated carrier.
 */
internal class BoltFfiStorePort(
    nativeBridge: StoreNativeBridge,
    private val session: com.lomo.data.engine.SessionNativeBridge,
) : StorePort {
    private val bridge: StoreNativeBridge = EngineFailureConvertingStoreBridge(nativeBridge)

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage {
        val page =
            bridge.queryMemos(
                BridgeMemoQuery(
                    searchText = query.searchText,
                    filters =
                    query.filters.toNativeFilters(),
                    sort =
                    BridgeMemoSort(
                        seed = query.sort.seed,
                        timeZone = query.sort.timeZone,
                        field =
                        when (query.sort.field) {
                            StoreMemoSortField.CreatedAt -> BridgeMemoSortField.CREATED_AT
                            StoreMemoSortField.UpdatedAt -> BridgeMemoSortField.UPDATED_AT
                            StoreMemoSortField.ReviewMonth -> BridgeMemoSortField.REVIEW_MONTH
                            StoreMemoSortField.ReviewRandom -> BridgeMemoSortField.REVIEW_RANDOM
                        },
                        direction =
                        when (query.sort.direction) {
                            StoreSortDirection.Ascending -> BridgeSortDirection.ASCENDING
                            StoreSortDirection.Descending -> BridgeSortDirection.DESCENDING
                        },
                    ),
                    boundary =
                    query.boundary?.let { boundary ->
                        BridgeMemoQueryBoundary(
                            isPinned = boundary.isPinned,
                            primarySortMs = boundary.primarySortMs,
                            createdAtMs = boundary.createdAtMs,
                            memoId = boundary.memoId,
                        )
                    },
                ),
                cursor?.let { BridgePageCursor(encoded = it.encoded) },
                pageSize.toUInt(),
                startMemoId,
                backward,
            )
        return StoreMemoPage(
            items = page.items.map { it.toStoreSummary() },
            nextCursor = page.nextCursor?.let { StorePageCursor(encoded = it.encoded) },
            highWaterRevision = page.highWaterRevision.toLong(),
            queryFingerprint = page.queryFingerprint,
            prevCursor = page.prevCursor?.let { StorePageCursor(encoded = it.encoded) },
            itemsBefore = page.itemsBefore.toStoreLong("items_before"),
            itemsAfter = page.itemsAfter.toStoreLong("items_after"),
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? {
        val snap = bridge.getMemo(memoId) ?: return null
        return StoreMemoSnapshot(summary = snap.summary.toStoreSummary(), body = snap.body)
    }

    override fun queryCount(query: StoreMemoQuery): Long = bridge.queryCount(
        BridgeMemoQuery(
            searchText = query.searchText,
            filters =
            query.filters.toNativeFilters(),
            sort =
            BridgeMemoSort(
                seed = query.sort.seed,
                timeZone = query.sort.timeZone,
                field =
                when (query.sort.field) {
                    StoreMemoSortField.CreatedAt -> BridgeMemoSortField.CREATED_AT
                    StoreMemoSortField.UpdatedAt -> BridgeMemoSortField.UPDATED_AT
                    StoreMemoSortField.ReviewMonth -> BridgeMemoSortField.REVIEW_MONTH
                    StoreMemoSortField.ReviewRandom -> BridgeMemoSortField.REVIEW_RANDOM
                },
                direction =
                when (query.sort.direction) {
                    StoreSortDirection.Ascending -> BridgeSortDirection.ASCENDING
                    StoreSortDirection.Descending -> BridgeSortDirection.DESCENDING
                },
            ),
            boundary =
            query.boundary?.let { boundary ->
                BridgeMemoQueryBoundary(
                    isPinned = boundary.isPinned,
                    primarySortMs = boundary.primarySortMs,
                    createdAtMs = boundary.createdAtMs,
                    memoId = boundary.memoId,
                )
            },
        ),
    ).toStoreLong("query_count")

    override fun sidebarProjection(): StoreSidebarProjection {
        val projection = bridge.sidebarProjection()
        require(projection.schemaVersion == 1u) { "Unsupported sidebar projection schema ${projection.schemaVersion}" }
        return StoreSidebarProjection(
            schemaVersion = projection.schemaVersion,
            memoCount = projection.memoCount.toSidebarCount("memo_count"),
            dateCounts =
            projection.dateCounts.map {
                StoreSidebarDateCount(it.date, it.count.toSidebarCount("date_count"))
            },
            tagCounts =
            projection.tagCounts.map {
                StoreSidebarTagCount(it.name, it.count.toSidebarCount("tag_count"))
            },
        )
    }

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan {
        val plan =
            withEngineFailureConversion { session.sessionReminderPlan(nowUtcMs) }
        return StoreReminderPlan(
            alarms =
            plan.alarms.map { alarm ->
                StorePlannedAlarm(
                    occurrenceId = alarm.occurrenceId,
                    opaqueId = alarm.opaqueId,
                    memoIdentity = alarm.memoIdentity,
                    triggerAtUtcMs = alarm.triggerAtUtcMs,
                    isCatchUp = alarm.isCatchUp,
                )
            },
            droppedCount = plan.droppedCount.toInt(),
            workspaceGeneration = plan.workspaceGeneration,
        )
    }

    override fun snoozeReminder(opaqueId: String, interval: ReminderInterval) = withEngineFailureConversion {
        session.sessionSnoozeReminder(opaqueId, interval.toNativeSnoozeInterval())
    }

    override fun recoverReminderSnooze() = withEngineFailureConversion { session.sessionRecoverReminderSnooze() }

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommandPublication) -> Unit,
    ): StoreMemoCommit {
        require(command.operationId.isNotBlank()) {
            "Memo commands require a non-blank operationId frozen by their caller"
        }
        val operationId = command.operationId
        if (command.pendingPromotes.isNotEmpty() &&
            command.kind != StoreMemoCommandKind.Create &&
            command.kind != StoreMemoCommandKind.Update
        ) {
            error("pendingPromotes are only valid on create/update (session PlannedFile batch)")
        }
        val observer = ForwardingCreateObserver(onPublication)
        val result =
            try {
                withEngineFailureConversion {
                    applySessionMemoCommand(command, operationId, observer)
                }
            } catch (failure: Exception) {
                observer.failure?.let(failure::addSuppressed)
                throw failure
            }
        observer.failure?.let { throw it }
        return result.toStoreCommit()
    }

    /**
     * Forwards a create's mid-command publications from the engine thread. A Kotlin throwable must
     * not cross the JNI callback, so a forwarding failure is held and rethrown once the command
     * returns; the engine's own commit or withdrawal is unaffected by it.
     */
    private class ForwardingCreateObserver(private val onPublication: (StoreMemoCommandPublication) -> Unit) :
        com.lomo.nativebridge.MemoCreateObserver {
        @Volatile
        var failure: Exception? = null
            private set

        override fun accepted(publication: com.lomo.nativebridge.StoreMemoCommit) {
            forward(StoreMemoCommandPublication.Accepted(publication.toStoreCommit()))
        }

        override fun withdrawn(publication: com.lomo.nativebridge.StoreMemoCommit) {
            forward(StoreMemoCommandPublication.Withdrawn(publication.toStoreCommit()))
        }

        private fun forward(publication: StoreMemoCommandPublication) {
            if (failure != null) return
            try {
                onPublication(publication)
            } catch (error: Exception) {
                failure = error
            }
        }
    }

    private fun applySessionMemoCommand(
        command: StoreMemoCommand,
        operationId: String,
        observer: com.lomo.nativebridge.MemoCreateObserver,
    ): com.lomo.nativebridge.StoreMemoCommit = when (command.kind) {
        StoreMemoCommandKind.Create ->
            session.sessionCreateMemo(
                SessionCreateMemoRequest(
                    operationId = operationId,
                    relativePath = null,
                    timeToken = null,
                    content = command.content ?: error("session create requires content"),
                    expectedDocumentFingerprint = command.expectedFingerprint,
                    pinned = command.pin == true,
                    pendingPromotes = command.sessionPromotePlans(operationId),
                    chronologyEpochMs = command.chronologyEpochMs,
                    dateFormat = command.dateFormat,
                    timestampFormat = command.timestampFormat,
                ),
                observer,
            )

        StoreMemoCommandKind.Update ->
            session.sessionUpdateMemo(
                SessionUpdateMemoRequest(
                    operationId = operationId,
                    memoId = command.memoId,
                    content = command.content ?: error("session update requires content"),
                    expectedDocumentFingerprint =
                    command.expectedFingerprint?.takeIf { fingerprint -> fingerprint.isNotBlank() }
                        ?: error("session update requires expectedFingerprint"),
                    pendingPromotes = command.sessionPromotePlans(operationId),
                ),
            )

        StoreMemoCommandKind.Delete ->
            session.sessionDeleteMemo(
                SessionDeleteMemoRequest(
                    operationId = operationId,
                    memoId = command.memoId,
                    expectedDocumentFingerprint =
                    command.expectedFingerprint?.takeIf { fingerprint -> fingerprint.isNotBlank() }
                        ?: error("session delete requires expectedFingerprint"),
                ),
            )

        StoreMemoCommandKind.Pin,
        StoreMemoCommandKind.Unpin,
        ->
            session.sessionPinMemo(
                SessionPinMemoRequest(
                    operationId = operationId,
                    memoId = command.memoId,
                    pinned = command.kind == StoreMemoCommandKind.Pin || command.pin == true,
                ),
            )

        StoreMemoCommandKind.HistoryRestore -> {
            val revision =
                command.historyRevision
                    ?: error("HistoryRestore requires historyRevision")
            session.sessionRestoreRevision(
                SessionRestoreRevisionRequest(
                    operationId = operationId,
                    memoId = command.memoId,
                    revision = revision.toULong(),
                ),
            )
        }

        StoreMemoCommandKind.Restore ->
            session.sessionRestoreMemo(SessionRestoreRequest(operationId, command.memoId))

        StoreMemoCommandKind.PermanentDelete ->
            session.sessionPermanentlyDeleteMemo(SessionRestoreRequest(operationId, command.memoId))
    }

    private fun StoreMemoCommand.sessionPromotePlans(operationId: String): List<BridgePromotePlan> =
        pendingPromotes.map { plan ->
            val planOp = plan.operationId.trim()
            require(planOp.isNotEmpty()) {
                "pendingPromote.operationId must be non-blank (D4; never mint UUID)"
            }
            require(planOp == operationId) {
                "pendingPromote.operationId must match memo command operationId (D4)"
            }
            BridgePromotePlan(
                operationId = planOp,
                staged =
                BridgeStaged(
                    digest = plan.staged.digest,
                    size = plan.staged.size.toULong(),
                    mime = plan.staged.mime,
                    stagingPath = plan.staged.stagingPath,
                    humanNameHint = plan.staged.humanNameHint,
                    suggestedFinalRelativePath = plan.staged.suggestedFinalRelativePath,
                ),
                finalRelativePath = plan.finalRelativePath,
            )
        }

    override fun permanentDeleteMany(operationId: String, targets: List<StoreMemoDeleteTarget>): StoreMemoBatchCommit {
        require(operationId.isNotBlank()) { "permanent delete batch operationId must be non-blank" }
        require(targets.isNotEmpty()) { "permanent delete batch must contain at least one target" }
        require(targets.all { it.memoId.isNotBlank() }) {
            "permanent delete target memoId must be non-blank"
        }
        // One session call owns the whole target list: Rust splits it into durable child batches,
        // deletes the trash records, and commits each batch's projection rows in one transaction.
        // Reminder facts travel back on the batch receipt, so no per-memo re-read is needed.
        val result =
            withEngineFailureConversion {
                session.sessionPermanentlyDeleteMany(
                    com.lomo.nativebridge.StoreMemoBatchDelete(
                        operationId = operationId,
                        targets =
                        targets.sortedBy(StoreMemoDeleteTarget::memoId).map { target ->
                            com.lomo.nativebridge.StoreMemoDeleteTarget(
                                memoId = target.memoId,
                                sourcePath = target.sourcePath,
                                expectedRevision = target.expectedRevision.toULong(),
                                expectedFingerprint = target.expectedFingerprint,
                            )
                        },
                    ),
                )
            }
        return StoreMemoBatchCommit(
            operationId = result.operationId,
            deleted =
            result.deleted.map { deleted ->
                StoreMemoDeletedMemo(deleted.memoId, deleted.reminderIds)
            },
            coreRevision = result.coreRevision.toStoreLong("core_revision"),
            eventSequence = result.eventSequence.toStoreLong("event_sequence"),
            scopes = result.scopes.map { scope -> scope.toStoreInvalidationScope() },
            idempotentReplay = result.idempotentReplay,
        )
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        val result = bridge.startRebuild(batchSize.toUInt())
        return StoreRebuildResult(
            memosIndexed = result.memosIndexed.toLong(),
            fileCount = result.fileCount.toLong(),
            attachmentCount = result.attachmentCount.toLong(),
            workspaceDigest = result.workspaceDigest,
            storeDigest = result.storeDigest,
            corruptLomoIsolated = result.corruptLomoIsolated.toLong(),
            highWaterRevision = result.highWaterRevision.toStoreLong("high_water_revision"),
            rewritten = result.rewritten,
        )
    }
}

internal fun com.lomo.nativebridge.StoreMemoCommit.toStoreCommit(): StoreMemoCommit = StoreMemoCommit(
    operationId = operationId,
    memoId = memoId,
    coreRevision = coreRevision.toStoreLong("core_revision"),
    eventSequence = eventSequence.toStoreLong("event_sequence"),
    contentRevision = contentRevision.toStoreLong("content_revision"),
    fileFingerprint = fileFingerprint,
    scopes = scopes.map { scope -> scope.toStoreInvalidationScope() },
    idempotentReplay = idempotentReplay,
)

internal fun com.lomo.nativebridge.StoreInvalidationScope.toStoreInvalidationScope(): StoreInvalidationScope =
    when (this) {
        com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST -> StoreInvalidationScope.MemoList
        com.lomo.nativebridge.StoreInvalidationScope.SEARCH -> StoreInvalidationScope.Search
        com.lomo.nativebridge.StoreInvalidationScope.TRASH -> StoreInvalidationScope.Trash
        com.lomo.nativebridge.StoreInvalidationScope.PIN -> StoreInvalidationScope.Pin
        com.lomo.nativebridge.StoreInvalidationScope.TAGS -> StoreInvalidationScope.Tags
        com.lomo.nativebridge.StoreInvalidationScope.STATS -> StoreInvalidationScope.Stats
        com.lomo.nativebridge.StoreInvalidationScope.REMINDER -> StoreInvalidationScope.Reminder
        com.lomo.nativebridge.StoreInvalidationScope.FULL -> StoreInvalidationScope.Full
    }

internal fun ULong.toStoreLong(field: String): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "$field exceeds the Kotlin signed revision range" }
    return toLong()
}

private fun Long.toSidebarCount(field: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "$field is outside the Kotlin count range" }
    return toInt()
}
