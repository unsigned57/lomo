package com.lomo.app.testing.fakes

import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandStore
import com.lomo.app.ExternalAppCommandTerminalResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeExternalAppCommandStore : ExternalAppCommandStore {
    private val mutableCommands = MutableStateFlow<List<ExternalAppCommand>>(emptyList())
    override val commands: StateFlow<List<ExternalAppCommand>> = mutableCommands

    override fun enqueue(
        command: ExternalAppCommand,
        nowMillis: Long,
    ): ExternalAppCommand {
        mutableCommands.value = mutableCommands.value + command
        return command
    }

    override fun updateStatus(
        commandId: String,
        status: ExternalAppCommandStatus,
    ) {
        mutableCommands.value =
            mutableCommands.value.map { command ->
                if (command.id == commandId) {
                    command.copy(status = status)
                } else {
                    command
                }
            }
    }

    override fun complete(
        commandId: String,
        result: ExternalAppCommandTerminalResult,
    ) {
        mutableCommands.value = mutableCommands.value.filterNot { command -> command.id == commandId }
    }

    override fun expire(nowMillis: Long): List<String> {
        val expired = mutableCommands.value.filter { command -> command.expiresAtMillis <= nowMillis }
        mutableCommands.value = mutableCommands.value - expired.toSet()
        return expired.map(ExternalAppCommand::id)
    }
}
