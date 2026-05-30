package com.lomo.data.testing

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.Memo

internal fun projectedMemoEntity(memo: Memo): MemoEntity =
    MemoProjectionProjector.projectActive(memo).entity

internal fun projectedTrashMemoEntity(memo: Memo): TrashMemoEntity =
    MemoProjectionProjector.projectTrash(memo.copy(isDeleted = true)).entity
