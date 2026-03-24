package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo

internal fun buildUpdateOutbox(
    sourceMemo: Memo,
    newContent: String,
): MemoFileOutboxEntity =
    MemoFileOutboxEntity(
        operation = MemoFileOutboxOp.UPDATE,
        memoId = sourceMemo.id,
        memoDate = sourceMemo.dateKey,
        memoTimestamp = sourceMemo.timestamp,
        memoRawContent = sourceMemo.rawContent,
        newContent = newContent,
        createRawContent = null,
    )

internal fun buildDeleteOutbox(sourceMemo: Memo): MemoFileOutboxEntity =
    MemoFileOutboxEntity(
        operation = MemoFileOutboxOp.DELETE,
        memoId = sourceMemo.id,
        memoDate = sourceMemo.dateKey,
        memoTimestamp = sourceMemo.timestamp,
        memoRawContent = sourceMemo.rawContent,
        newContent = null,
        createRawContent = null,
    )

internal fun buildRestoreOutbox(sourceMemo: Memo): MemoFileOutboxEntity =
    MemoFileOutboxEntity(
        operation = MemoFileOutboxOp.RESTORE,
        memoId = sourceMemo.id,
        memoDate = sourceMemo.dateKey,
        memoTimestamp = sourceMemo.timestamp,
        memoRawContent = sourceMemo.rawContent,
        newContent = sourceMemo.content,
        createRawContent = null,
    )

internal fun outboxSourceMemo(item: MemoFileOutboxEntity): Memo =
    Memo(
        id = item.memoId,
        timestamp = item.memoTimestamp,
        content = item.newContent.orEmpty(),
        rawContent = item.memoRawContent,
        dateKey = item.memoDate,
        localDate = MemoLocalDateResolver.resolve(item.memoDate),
    )
