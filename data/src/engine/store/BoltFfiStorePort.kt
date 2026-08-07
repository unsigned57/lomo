package com.lomo.data.engine.store

import com.lomo.nativebridge.MediaPromotePlanDto as BridgePromotePlan
import com.lomo.nativebridge.MediaStagedDto as BridgeStaged
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommandKind as BridgeMemoCommandKind
import com.lomo.nativebridge.StoreMemoFilters as BridgeMemoFilters
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import java.util.UUID

/**
 * Production [StorePort] over [StoreNativeBridge] (ManagedEngineSession / BoltFFI).
 *
 * Requires a Direct workspace (store handle). Missing store handle fails closed from native.
 * Mapping logic is host-testable via fake bridges; real JNI stays behind the bridge only.
 */
internal class BoltFfiStorePort(
    private val bridge: StoreNativeBridge,
) : StorePort {
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
                            dateFromMs = query.filters.dateFromMs,
                            dateToMs = query.filters.dateToMs,
                            hasTodo = query.filters.hasTodo,
                            hasAttachment = query.filters.hasAttachment,
                            hasUrl = query.filters.hasUrl,
                            pinnedOnly = query.filters.pinnedOnly,
                            includeTrash = query.filters.includeTrash,
                            trashOnly = query.filters.trashOnly,
                        ),
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

    override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit {
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
        val result =
            bridge.applyMemoCommand(
                BridgeMemoCommand(
                    operationId = operationId,
                    kind = command.kind.toBridge(),
                    memoId = command.memoId,
                    expectedRevision = command.expectedRevision.toULong(),
                    expectedFingerprint = command.expectedFingerprint,
                    content = command.content,
                    tags = command.tags,
                    pin = command.pin,
                    pendingPromotes =
                        command.pendingPromotes.map { plan ->
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
                                        suggestedFinalRelativePath =
                                            plan.staged.suggestedFinalRelativePath,
                                    ),
                                finalRelativePath = plan.finalRelativePath,
                            )
                        },
                    chronologyEpochMs = command.chronologyEpochMs,
                ),
            )
        return StoreMemoCommit(
            operationId = result.operationId,
            memoId = result.memoId,
            coreRevision = result.coreRevision.toLong(),
            eventSequence = result.eventSequence.toLong(),
            contentRevision = result.contentRevision.toLong(),
            fileFingerprint = result.fileFingerprint,
            scopes = result.scopes,
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
            highWaterRevision = result.highWaterRevision.toLong(),
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

    private fun StoreMemoCommandKind.toBridge(): BridgeMemoCommandKind =
        when (this) {
            StoreMemoCommandKind.Create -> BridgeMemoCommandKind.CREATE
            StoreMemoCommandKind.Update -> BridgeMemoCommandKind.UPDATE
        StoreMemoCommandKind.Delete -> BridgeMemoCommandKind.DELETE
        StoreMemoCommandKind.PermanentDelete -> BridgeMemoCommandKind.PERMANENT_DELETE
            StoreMemoCommandKind.Restore -> BridgeMemoCommandKind.RESTORE
            StoreMemoCommandKind.Pin -> BridgeMemoCommandKind.PIN
            StoreMemoCommandKind.Unpin -> BridgeMemoCommandKind.UNPIN
            StoreMemoCommandKind.HistoryRestore -> BridgeMemoCommandKind.HISTORY_RESTORE
        }
}

private fun Long.toSidebarCount(field: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "$field is outside the Kotlin count range" }
    return toInt()
}
