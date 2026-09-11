package com.lomo.data.engine.store

import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionPinMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.SessionRestoreRevisionRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommandKind as BridgeMemoCommandKind
import com.lomo.nativebridge.StoreMemoFilters as BridgeMemoFilters
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoQueryBoundary as BridgeMemoQueryBoundary
import com.lomo.nativebridge.StoreMemoSort as BridgeMemoSort
import com.lomo.nativebridge.StoreMemoSortField as BridgeMemoSortField
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreSafMemoProjection as BridgeSafMemoProjection
import com.lomo.nativebridge.StoreReminderQuery as BridgeReminderQuery
import com.lomo.nativebridge.StoreReminderSession as BridgeReminderSession
import com.lomo.nativebridge.StoreTimeZoneContext as BridgeTimeZoneContext
import com.lomo.nativebridge.StoreZoneTransition as BridgeZoneTransition
import com.lomo.nativebridge.StoreSortDirection as BridgeSortDirection
import com.lomo.nativebridge.WorkspaceReminderReference as BridgeReminderReference
import java.util.UUID

/**
 * Production [StorePort] over [StoreNativeBridge] and [com.lomo.data.engine.SessionNativeBridge].
 *
 * Memo writes, including staged media promote, go through the application session. Store reads stay
 * on the projection query surface.
 *
 * Requires a Direct or SAF workspace (store handle). Missing store handle fails closed from native.
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
    ): StoreMemoPage {
        val page =
            bridge.queryMemos(
                BridgeMemoQuery(
                    searchText = query.searchText,
                    filters =
                        BridgeMemoFilters(
                            tag = query.filters.tag,
                            tagSubtree = query.filters.tagSubtree,
                            dateFromInclusiveMs = query.filters.dateFromInclusiveMs,
                            dateUntilExclusiveMs = query.filters.dateUntilExclusiveMs,
                            hasTodo = query.filters.hasTodo,
                            hasAttachment = query.filters.hasAttachment,
                            hasUrl = query.filters.hasUrl,
                            pinnedOnly = query.filters.pinnedOnly,
                            includeTrash = query.filters.includeTrash,
                            trashOnly = query.filters.trashOnly,
                        ),
                    sort =
                        BridgeMemoSort(
                            field =
                                when (query.sort.field) {
                                    StoreMemoSortField.CreatedAt -> BridgeMemoSortField.CREATED_AT
                                    StoreMemoSortField.UpdatedAt -> BridgeMemoSortField.UPDATED_AT
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
            )
        return StoreMemoPage(
            items = page.items.map { it.toSummary() },
            nextCursor = page.nextCursor?.let { StorePageCursor(encoded = it.encoded) },
            highWaterRevision = page.highWaterRevision.toLong(),
            queryFingerprint = page.queryFingerprint,
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? {
        val snap = bridge.getMemo(memoId) ?: return null
        return StoreMemoSnapshot(summary = snap.summary.toSummary(), body = snap.body)
    }

    override fun queryCount(query: StoreMemoQuery): Long =
        bridge.queryCount(
            BridgeMemoQuery(
                searchText = query.searchText,
                filters =
                    BridgeMemoFilters(
                        tag = query.filters.tag,
                        tagSubtree = query.filters.tagSubtree,
                        dateFromInclusiveMs = query.filters.dateFromInclusiveMs,
                        dateUntilExclusiveMs = query.filters.dateUntilExclusiveMs,
                        hasTodo = query.filters.hasTodo,
                        hasAttachment = query.filters.hasAttachment,
                        hasUrl = query.filters.hasUrl,
                        pinnedOnly = query.filters.pinnedOnly,
                        includeTrash = query.filters.includeTrash,
                        trashOnly = query.filters.trashOnly,
                    ),
                sort =
                    BridgeMemoSort(
                        field =
                            when (query.sort.field) {
                                StoreMemoSortField.CreatedAt -> BridgeMemoSortField.CREATED_AT
                                StoreMemoSortField.UpdatedAt -> BridgeMemoSortField.UPDATED_AT
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

    override fun memoStatisticsRows(): List<StoreMemoStatisticsRow> =
        bridge.memoStatisticsRows().map { row ->
            StoreMemoStatisticsRow(
                createdAtMs = row.createdAtMs,
                wordCount = row.wordCount,
                charCount = row.charCount,
            )
        }

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

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> =
        bridge.listHistoryAttachmentRefs().map { ref ->
            StoreHistoryAttachmentRef(
                memoId = ref.memoId,
                revision = ref.revision.toLong(),
                relativePath = ref.relativePath,
                ownerKey = ref.ownerKey,
            )
        }

    override fun listMemoHistory(memoId: String, cursor: String?, limit: Int): StoreMemoHistoryPage {
        val page = bridge.listMemoHistory(memoId, cursor, limit.coerceIn(1, 256).toUInt())
        return StoreMemoHistoryPage(
            items = page.items.map {
                StoreMemoHistoryRevision(
                    it.revision.toLong(),
                    it.createdAtMs,
                    it.content,
                    it.fileFingerprint,
                )
            },
            nextCursor = page.nextCursor,
        )
    }

    override fun queryReminderPlan(query: StoreReminderQuery): StoreReminderPlan {
        val plan =
            bridge.queryReminderPlan(
                BridgeReminderQuery(
                    nowUtcMs = query.nowUtcMs,
                    zone =
                        BridgeTimeZoneContext(
                            zoneId = query.zone.zoneId,
                            baseOffsetSecs = query.zone.baseOffsetSecs,
                            transitions =
                                query.zone.transitions.map { transition ->
                                    BridgeZoneTransition(
                                        transitionUtcMs = transition.transitionUtcMs,
                                        offsetBeforeSecs = transition.offsetBeforeSecs,
                                        offsetAfterSecs = transition.offsetAfterSecs,
                                    )
                                },
                        ),
                    sessions =
                        query.sessions.map { session ->
                            BridgeReminderSession(
                                opaqueId = session.opaqueId,
                                memoIdentity = session.memoIdentity,
                                memoRevision = session.memoRevision,
                                token = session.token,
                                dueAtLocal = session.dueAtLocal,
                                repeatCount = session.repeatCount.toUInt(),
                                firedCount = session.firedCount.toUInt(),
                                done = session.done,
                                intervalMinutes = session.intervalMinutes.toUInt(),
                                recurrenceCode = session.recurrenceCode,
                            )
                        },
                    rollingWindow = query.rollingWindow.toUInt(),
                    workspaceGeneration = query.workspaceGeneration.toULong(),
                ),
            )
        return StoreReminderPlan(
            alarms =
                plan.alarms.map { alarm ->
                    StorePlannedAlarm(
                        opaqueId = alarm.opaqueId,
                        memoIdentity = alarm.memoIdentity,
                        triggerAtUtcMs = alarm.triggerAtUtcMs,
                        isCatchUp = alarm.isCatchUp,
                    )
                },
            workspaceGeneration = plan.workspaceGeneration.toString(),
        )
    }

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit {
        // D4: same-operation promote requires a real operationId. Never mint when promotes are
        // present (blank mint would desync plan.operationId from the memo command).
        val operationId =
            command.operationId.trim().ifEmpty {
                if (command.pendingPromotes.isNotEmpty()) {
                    error(
                        "applyMemoCommand requires non-blank operationId when pendingPromotes " +
                            "are present (D4; never mint UUID under promote)",
                    )
                }
                UUID.randomUUID().toString()
            }
        if (command.pendingPromotes.isNotEmpty() &&
            command.kind != StoreMemoCommandKind.Create &&
            command.kind != StoreMemoCommandKind.Update
        ) {
            error("pendingPromotes are only valid on create/update (session PlannedFile batch)")
        }
        val result =
            withEngineFailureConversion {
                applySessionMemoCommand(command, operationId)
            }
        val commit = result.toStoreCommit()
        onPublication(commit)
        return commit
    }

    private fun applySessionMemoCommand(
        command: StoreMemoCommand,
        operationId: String,
    ): com.lomo.nativebridge.StoreMemoCommit =
        when (command.kind) {
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
                    ),
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
                restoreResultToCommit(
                    operationId = operationId,
                    memoId = command.memoId,
                    result = session.sessionRestoreMemo(SessionRestoreRequest(operationId, command.memoId)),
                )
            StoreMemoCommandKind.PermanentDelete ->
                restoreResultToCommit(
                    operationId = operationId,
                    memoId = command.memoId,
                    result =
                        session.sessionPermanentlyDeleteMemo(
                            SessionRestoreRequest(operationId, command.memoId),
                        ),
                )
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

    private fun restoreResultToCommit(
        operationId: String,
        memoId: String,
        result: com.lomo.nativebridge.SessionRestoreResult,
    ): com.lomo.nativebridge.StoreMemoCommit =
        com.lomo.nativebridge.StoreMemoCommit(
            operationId = operationId,
            memoId = memoId,
            coreRevision = result.eventSequence,
            eventSequence = result.eventSequence,
            contentRevision = 0uL,
            fileFingerprint = result.fileFingerprint,
            scopes = listOf("full"),
            idempotentReplay = false,
        )

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit {
        require(operationId.isNotBlank()) { "permanent delete batch operationId must be non-blank" }
        require(targets.isNotEmpty()) { "permanent delete batch must contain at least one target" }
        return withEngineFailureConversion {
            val deleted = mutableListOf<StoreMemoDeletedMemo>()
            var lastEventSequence = 0uL
            for (target in targets.sortedBy(StoreMemoDeleteTarget::memoId)) {
                require(target.memoId.isNotBlank()) {
                    "permanent delete target memoId must be non-blank"
                }
                val reminderIds =
                    // behavior-contract: loop-io-ok: no bulk reminder lookup; each target is one memo
                    bridge.getMemo(target.memoId)
                        ?.summary
                        ?.reminders
                        ?.map { reminder -> reminder.opaqueId }
                        .orEmpty()
                val itemOperationId = "$operationId/${target.memoId}"
                val result =
                    session.sessionPermanentlyDeleteMemo(
                        SessionRestoreRequest(itemOperationId, target.memoId),
                    )
                deleted += StoreMemoDeletedMemo(target.memoId, reminderIds)
                lastEventSequence = result.eventSequence
            }
            StoreMemoBatchCommit(
                operationId = operationId,
                deleted = deleted,
                coreRevision = lastEventSequence.toLong(),
                eventSequence = lastEventSequence.toLong(),
                scopes = listOf(StoreInvalidationScope.Full),
                idempotentReplay = false,
            )
        }
    }

    override fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ): StoreMemoCommit {
        val facts = mutation.facts
        require(mutation.operationId.isNotBlank()) { "document mutation operationId must be non-blank" }
        require(mutation.expectedRevision >= 0L) { "document mutation expected revision must be non-negative" }
        require(mutation.expectedFingerprint.isNotBlank()) {
            "document mutation expected fingerprint must be non-blank"
        }
        require(facts.memoId == mutation.facts.memoId) { "document mutation facts identity mismatch" }
        val command =
            BridgeMemoCommand(
                operationId = mutation.operationId,
                kind = BridgeMemoCommandKind.UPDATE,
                memoId = facts.memoId,
                expectedRevision = mutation.expectedRevision.toULong(),
                expectedFingerprint = mutation.expectedFingerprint,
                content = facts.content,
                tags = facts.tags,
                pin = null,
                pendingPromotes = emptyList(),
                chronologyEpochMs = facts.chronologyEpochMs,
            )
        val projection =
            BridgeSafMemoProjection(
                memoId = facts.memoId,
                sourcePath = facts.sourcePath,
                fileFingerprint = facts.fileFingerprint,
                chronologyEpochMs = facts.chronologyEpochMs,
                body = facts.content,
                tags = facts.tags,
                attachmentPaths = facts.attachmentPaths,
                hasTodo = facts.hasTodo,
                hasUrl = facts.hasUrl,
                reminders = facts.reminders.map(::toBridgeReminder),
                trashedAtMs = null,
            )
        return bridge.commitWorkspaceDocumentFacts(command, projection).toStoreCommit()
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
        )
    }

    private fun com.lomo.nativebridge.StoreMemoSummary.toSummary(): StoreMemoSummary =
        StoreMemoSummary(
            memoId = memoId,
            sourcePath = sourcePath,
            fileFingerprint = fileFingerprint,
            updatedAtMs = updatedAtMs,
            createdAtMs = createdAtMs,
            hasTodo = hasTodo,
            hasUrl = hasUrl,
            hasAttachment = hasAttachment,
            isPinned = isPinned,
            isTrashed = isTrashed,
            bodyPreview = bodyPreview,
            contentRevision = contentRevision.toLong(),
            rank = rank,
            tags = tags,
            imageUrls = imageUrls,
            reminders = reminders.map { reminder -> reminder.toDomainMarker() },
            isPending = isPending,
        )

    private fun com.lomo.nativebridge.WorkspaceReminderReference.toDomainMarker():
        com.lomo.domain.model.ReminderMarker =
        com.lomo.domain.model.ReminderMarker(
            dueAt =
                java.time.LocalDateTime.parse(
                    dueAtLocal,
                    com.lomo.domain.model.ReminderMarker.TIMESTAMP_FORMAT,
                ),
            repeatCount = repeatCount.toInt(),
            firedCount = firedCount.toInt(),
            done = done,
            intervalMinutes = intervalMinutes.toInt(),
            recurrence = com.lomo.domain.model.Recurrence.fromCode(recurrenceCode),
            reference =
                com.lomo.domain.model.ReminderReference(
                    opaqueId = opaqueId,
                    revision = revision,
                    memoIdentity = memoIdentity,
                    sourceSpan =
                        com.lomo.domain.model.markdown.MarkdownSourceSpan(
                            startByte = sourceStart,
                            endByte = sourceEnd,
                        ),
                    tokenFingerprint = tokenFingerprint,
                ),
            token = token,
        )

    private fun toBridgeReminder(
        marker: com.lomo.domain.model.ReminderMarker,
    ): BridgeReminderReference =
        BridgeReminderReference(
            opaqueId = marker.reference.opaqueId,
            revision = marker.reference.revision,
            memoIdentity = marker.reference.memoIdentity,
            sourceStart = marker.reference.sourceSpan.startByte,
            sourceEnd = marker.reference.sourceSpan.endByte,
            tokenFingerprint = marker.reference.tokenFingerprint,
            token = marker.token,
            dueAtLocal = marker.dueAt.format(com.lomo.domain.model.ReminderMarker.TIMESTAMP_FORMAT),
            repeatCount = marker.repeatCount.toUInt(),
            firedCount = marker.firedCount.toUInt(),
            done = marker.done,
            intervalMinutes = marker.intervalMinutes.toUInt(),
            recurrenceCode = marker.recurrence.code,
        )
}

private fun com.lomo.nativebridge.StoreMemoCommit.toStoreCommit(): StoreMemoCommit =
    StoreMemoCommit(
        operationId = operationId,
        memoId = memoId,
        coreRevision = coreRevision.toStoreLong("core_revision"),
        eventSequence = eventSequence.toStoreLong("event_sequence"),
        contentRevision = contentRevision.toStoreLong("content_revision"),
        fileFingerprint = fileFingerprint,
        scopes = scopes.map(String::toStoreInvalidationScope),
        idempotentReplay = idempotentReplay,
    )

internal fun String.toStoreInvalidationScope(): StoreInvalidationScope =
    when (this) {
        "memo_list" -> StoreInvalidationScope.MemoList
        "search" -> StoreInvalidationScope.Search
        "trash" -> StoreInvalidationScope.Trash
        "pin" -> StoreInvalidationScope.Pin
        "tags" -> StoreInvalidationScope.Tags
        "stats" -> StoreInvalidationScope.Stats
        "reminder" -> StoreInvalidationScope.Reminder
        "full" -> StoreInvalidationScope.Full
        else -> error("Unknown Rust store invalidation scope: $this")
    }

internal fun ULong.toStoreLong(field: String): Long {
    require(this <= Long.MAX_VALUE.toULong()) { "$field exceeds the Kotlin signed revision range" }
    return toLong()
}

private fun Long.toSidebarCount(field: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "$field is outside the Kotlin count range" }
    return toInt()
}
