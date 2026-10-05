package com.lomo.app.feature.common

import com.lomo.domain.model.MemoOperationId
import java.util.UUID

/**
 * Mints the frozen operation identity for one logical app command.
 *
 * The command owner mints this token; an adapter must never substitute one, so a retry of the same
 * logical command replays the frozen payload instead of becoming a second command.
 */
internal fun newMemoOperationId(): MemoOperationId = MemoOperationId(UUID.randomUUID().toString())
