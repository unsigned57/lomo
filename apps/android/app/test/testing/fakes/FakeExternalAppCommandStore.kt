package com.lomo.app.testing.fakes

import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandQueuePolicy
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandStore
import com.lomo.app.ExternalAppCommandTerminalResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [ExternalAppCommandStore]. Delegates every mutation to the real
 * [ExternalAppCommandQueuePolicy] so specs observe the production dedupe, tombstone and expiry
 * semantics instead of a parallel simplified queue.
 */
class FakeExternalAppCommandStore : ExternalAppCommandStore {
    private val mutableCommands = MutableStateFlow<List<ExternalAppCommand>>(emptyList())
    override val commands: StateFlow<List<ExternalAppCommand>> = mutableCommands

    override fun enqueue(
        command: ExternalAppCommand,
        nowMillis: Long,
    ): ExternalAppCommand? {
        val result =
            ExternalAppCommandQueuePolicy.enqueue(
                commands = mutableCommands.value,
                command = command,
                nowMillis = nowMillis,
            )
        mutableCommands.value = result.commands
        return result.enqueuedCommand
    }

    override fun updateStatus(
        commandId: String,
        status: ExternalAppCommandStatus,
    ) {
        mutableCommands.value =
            ExternalAppCommandQueuePolicy.updateStatus(
                commands = mutableCommands.value,
                commandId = commandId,
                status = status,
            )
    }

    override fun complete(
        commandId: String,
        result: ExternalAppCommandTerminalResult,
    ) {
        mutableCommands.value =
            ExternalAppCommandQueuePolicy.complete(
                commands = mutableCommands.value,
                commandId = commandId,
            )
    }

    override fun expire(nowMillis: Long): List<String> {
        val result =
            ExternalAppCommandQueuePolicy.expire(
                commands = mutableCommands.value,
                nowMillis = nowMillis,
            )
        mutableCommands.value = result.commands
        return result.expiredCommandIds
    }
}
