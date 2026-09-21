package com.lomo.app.feature.common

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MemoOperationId
import java.util.UUID

/**
 * Mints the frozen operation identity for one logical app command.
 *
 * The command owner mints this token; an adapter must never substitute one, so a retry of the same
 * logical command replays the frozen payload instead of becoming a second command.
 */
internal fun newMemoOperationId(): MemoOperationId = MemoOperationId(UUID.randomUUID().toString())

/**
 * Mints the durable holder identity of one editing draft.
 *
 * The draft owner mints this token once and reuses it for every media it stages, so its leases can
 * be transferred to the frozen operation on submit and released on discard without touching another
 * draft's staged bytes.
 */
internal fun newDraftId(): DraftId = DraftId(UUID.randomUUID().toString())
