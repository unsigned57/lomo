package com.lomo.app

import android.content.Context
import androidx.core.content.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

const val EXTERNAL_APP_COMMAND_TTL_MILLIS: Long = 10 * 60 * 1_000L

@Serializable
enum class ExternalAppCommandAction {
    CreateMemo,
    StartRecording,
    StopRecording,
}

@Serializable
enum class ExternalAppCommandSource {
    DynamicShortcut,
    QuickSettingsTile,
    Widget,
}

@Serializable
enum class ExternalAppCommandStatus {
    Pending,
    WaitingForRoot,
    WaitingForVoiceDirectory,
    WaitingForEditor,
    WaitingForRecordAudioPermission,
}

enum class ExternalAppCommandTerminalResult {
    Executed,
    AlreadySatisfied,
    Failed,
    Expired,
    Canceled,
}

@Serializable
data class ExternalAppCommand(
    val id: String,
    val action: ExternalAppCommandAction,
    val source: ExternalAppCommandSource,
    val status: ExternalAppCommandStatus,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    val dedupeKey: String
        get() = "${source.name}:${action.name}"

    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis <= nowMillis
}

data class ExternalAppCommandEnqueueResult(
    val commands: List<ExternalAppCommand>,
    val enqueuedCommand: ExternalAppCommand?,
)

data class ExternalAppCommandExpirationResult(
    val commands: List<ExternalAppCommand>,
    val expiredCommandIds: List<String>,
)

object ExternalAppCommandQueuePolicy {
    fun enqueue(
        commands: List<ExternalAppCommand>,
        command: ExternalAppCommand,
        nowMillis: Long,
    ): ExternalAppCommandEnqueueResult {
        val activeCommands =
            expire(
                commands = commands,
                nowMillis = nowMillis,
            ).commands
        if (command.isExpired(nowMillis)) {
            return ExternalAppCommandEnqueueResult(
                commands = activeCommands,
                enqueuedCommand = null,
            )
        }
        val normalizedCommand = command.copy(status = ExternalAppCommandStatus.Pending)
        return ExternalAppCommandEnqueueResult(
            commands =
                (activeCommands.filterNot { queued -> queued.dedupeKey == normalizedCommand.dedupeKey } +
                    normalizedCommand).takeLast(MAX_EXTERNAL_APP_COMMANDS),
            enqueuedCommand = normalizedCommand,
        )
    }

    fun updateStatus(
        commands: List<ExternalAppCommand>,
        commandId: String,
        status: ExternalAppCommandStatus,
    ): List<ExternalAppCommand> =
        commands.map { command ->
            if (command.id == commandId) {
                command.copy(status = status)
            } else {
                command
            }
        }

    fun complete(
        commands: List<ExternalAppCommand>,
        commandId: String,
    ): List<ExternalAppCommand> = commands.filterNot { command -> command.id == commandId }

    fun expire(
        commands: List<ExternalAppCommand>,
        nowMillis: Long,
    ): ExternalAppCommandExpirationResult {
        val expired = commands.filter { command -> command.isExpired(nowMillis) }
        return ExternalAppCommandExpirationResult(
            commands = commands - expired.toSet(),
            expiredCommandIds = expired.map(ExternalAppCommand::id),
        )
    }

    private const val MAX_EXTERNAL_APP_COMMANDS = 64
}

interface ExternalAppCommandStore {
    val commands: StateFlow<List<ExternalAppCommand>>

    fun enqueue(
        command: ExternalAppCommand,
        nowMillis: Long = System.currentTimeMillis(),
    ): ExternalAppCommand?

    fun updateStatus(
        commandId: String,
        status: ExternalAppCommandStatus,
    )

    fun complete(
        commandId: String,
        result: ExternalAppCommandTerminalResult,
    )

    fun expire(nowMillis: Long = System.currentTimeMillis()): List<String>
}

class AndroidExternalAppCommandStore(
    context: Context,
) : ExternalAppCommandStore {
        private val preferences =
            context.getSharedPreferences(EXTERNAL_APP_COMMAND_PREFS_NAME, Context.MODE_PRIVATE)
        private val lock = Any()
        private val _commands = MutableStateFlow(readCommands())
        override val commands: StateFlow<List<ExternalAppCommand>> = _commands.asStateFlow()

        override fun enqueue(
            command: ExternalAppCommand,
            nowMillis: Long,
        ): ExternalAppCommand? =
            mutate {
                val result =
                    ExternalAppCommandQueuePolicy.enqueue(
                        commands = it,
                        command = command,
                        nowMillis = nowMillis,
                    )
                CommandStoreMutation(
                    commands = result.commands,
                    output = result.enqueuedCommand,
                )
            }

        override fun updateStatus(
            commandId: String,
            status: ExternalAppCommandStatus,
        ) {
            mutate {
                CommandStoreMutation(
                    commands =
                        ExternalAppCommandQueuePolicy.updateStatus(
                            commands = it,
                            commandId = commandId,
                            status = status,
                        ),
                    output = Unit,
                )
            }
        }

        override fun complete(
            commandId: String,
            result: ExternalAppCommandTerminalResult,
        ) {
            mutate {
                CommandStoreMutation(
                    commands =
                        ExternalAppCommandQueuePolicy.complete(
                            commands = it,
                            commandId = commandId,
                        ),
                    output = Unit,
                )
            }
        }

        override fun expire(nowMillis: Long): List<String> =
            mutate {
                val result =
                    ExternalAppCommandQueuePolicy.expire(
                        commands = it,
                        nowMillis = nowMillis,
                    )
                CommandStoreMutation(
                    commands = result.commands,
                    output = result.expiredCommandIds,
                )
            }

        private fun <T> mutate(block: (List<ExternalAppCommand>) -> CommandStoreMutation<T>): T =
            synchronized(lock) {
                val mutation = block(_commands.value)
                writeCommands(mutation.commands)
                _commands.value = mutation.commands
                mutation.output
            }

        private fun readCommands(): List<ExternalAppCommand> {
            val json = preferences.getString(KEY_COMMANDS_JSON, null)?.takeIf(String::isNotBlank)
                ?: return emptyList()
            return try {
                commandJson.decodeFromString<ExternalAppCommandEnvelope>(json).commands
            } catch (error: SerializationException) {
                // behavior-contract: invalid persisted external commands are rejected at the
                // persistence boundary because short-lived launch commands cannot be safely rebuilt.
                Timber.w(error, "Clearing invalid external app command queue")
                preferences.edit { remove(KEY_COMMANDS_JSON) }
                emptyList()
            }
        }

        private fun writeCommands(commands: List<ExternalAppCommand>) {
            preferences.edit {
                if (commands.isEmpty()) {
                    remove(KEY_COMMANDS_JSON)
                } else {
                    putString(
                        KEY_COMMANDS_JSON,
                        commandJson.encodeToString(ExternalAppCommandEnvelope(commands = commands)),
                    )
                }
            }
        }

        private data class CommandStoreMutation<T>(
            val commands: List<ExternalAppCommand>,
            val output: T,
        )

        private companion object {
            const val EXTERNAL_APP_COMMAND_PREFS_NAME = "external_app_commands"
            const val KEY_COMMANDS_JSON = "commands_json"
        }
    }

@Serializable
private data class ExternalAppCommandEnvelope(
    val version: Int = 1,
    val commands: List<ExternalAppCommand>,
)

private val commandJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
