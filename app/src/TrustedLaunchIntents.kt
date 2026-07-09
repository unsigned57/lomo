package com.lomo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


data class TrustedLaunchSignaturePayload(
    val commandId: String,
    val action: ExternalAppCommandAction,
    val source: ExternalAppCommandSource,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
)

internal data class TrustedLaunchSignature(
    val nonce: String,
    val value: String,
)

internal class TrustedLaunchSignaturePolicy(
    private val secretProvider: () -> String,
    private val nonceProvider: () -> String = { UUID.randomUUID().toString() },
) {
    fun sign(payload: TrustedLaunchSignaturePayload): TrustedLaunchSignature {
        val nonce = nonceProvider()
        return TrustedLaunchSignature(
            nonce = nonce,
            value = hmac(payload.wirePayload(nonce)),
        )
    }

    fun verify(
        payload: TrustedLaunchSignaturePayload,
        nonce: String?,
        signature: String?,
    ): Boolean {
        if (nonce.isNullOrBlank() || signature.isNullOrBlank()) {
            return false
        }
        val expected = hmac(payload.wirePayload(nonce))
        return MessageDigest.isEqual(expected.encodeToByteArray(), signature.encodeToByteArray())
    }

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(secretProvider().encodeToByteArray(), HMAC_SHA_256))
        return mac.doFinal(payload.encodeToByteArray()).toHexString()
    }

    private fun TrustedLaunchSignaturePayload.wirePayload(nonce: String): String =
        listOf(
            commandId,
            action.name,
            source.name,
            createdAtMillis.toString(),
            expiresAtMillis.toString(),
            nonce,
        ).joinToString(separator = "\n")

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
    }
}

class TrustedLaunchSecretStore(
    context: Context,
) {
        private val preferences =
            context.getSharedPreferences(TRUSTED_LAUNCH_PREFS_NAME, Context.MODE_PRIVATE)
        private val lock = Any()

        fun getOrCreateSecret(): String =
            synchronized(lock) {
                preferences.getString(KEY_INSTALL_SECRET, null)?.takeIf(String::isNotBlank)
                    ?: generateSecret().also { secret ->
                        preferences.edit {
                            putString(KEY_INSTALL_SECRET, secret)
                        }
                    }
            }

        private fun generateSecret(): String {
            val bytes = ByteArray(INSTALL_SECRET_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.toHexString()
        }

        private companion object {
            const val TRUSTED_LAUNCH_PREFS_NAME = "trusted_launch_intents"
            const val KEY_INSTALL_SECRET = "install_secret"
            const val INSTALL_SECRET_BYTES = 32
        }
    }

class TrustedLaunchIntents(
    private val context: Context,
    secretStore: TrustedLaunchSecretStore,
) {
        private val signaturePolicy =
            TrustedLaunchSignaturePolicy(secretProvider = secretStore::getOrCreateSecret)

        fun trustedShortcutCreateMemoIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.CreateMemo,
                source = ExternalAppCommandSource.DynamicShortcut,
            )

        fun trustedShortcutStartRecordingIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.StartRecording,
                source = ExternalAppCommandSource.DynamicShortcut,
            )

        fun trustedQuickSettingsCreateMemoIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.CreateMemo,
                source = ExternalAppCommandSource.QuickSettingsTile,
            )

        fun trustedQuickSettingsStartRecordingIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.StartRecording,
                source = ExternalAppCommandSource.QuickSettingsTile,
            )

        fun trustedQuickSettingsStopRecordingIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.StopRecording,
                source = ExternalAppCommandSource.QuickSettingsTile,
            )

        fun trustedWidgetCreateMemoIntent(): Intent =
            trustedCommandIntent(
                action = ExternalAppCommandAction.CreateMemo,
                source = ExternalAppCommandSource.Widget,
            )

        fun extractTrustedExternalAppCommand(
            intent: Intent?,
            nowMillis: Long = System.currentTimeMillis(),
        ): ExternalAppCommand? {
            if (intent?.action != MainActivity.ACTION_EXTERNAL_APP_COMMAND) {
                return null
            }
            val payload = intent.toTrustedLaunchSignaturePayload() ?: return null
            if (payload.expiresAtMillis <= nowMillis || payload.createdAtMillis >= payload.expiresAtMillis) {
                return null
            }
            val isTrusted =
                signaturePolicy.verify(
                    payload = payload,
                    nonce = intent.getStringExtra(EXTRA_SIGNATURE_NONCE),
                    signature = intent.getStringExtra(EXTRA_SIGNATURE_VALUE),
                )
            if (!isTrusted) {
                return null
            }
            return ExternalAppCommand(
                id = payload.commandId,
                action = payload.action,
                source = payload.source,
                status = ExternalAppCommandStatus.Pending,
                createdAtMillis = payload.createdAtMillis,
                expiresAtMillis = payload.expiresAtMillis,
            )
        }

        private fun trustedCommandIntent(
            action: ExternalAppCommandAction,
            source: ExternalAppCommandSource,
        ): Intent {
            val nowMillis = System.currentTimeMillis()
            val payload =
                TrustedLaunchSignaturePayload(
                    commandId = UUID.randomUUID().toString(),
                    action = action,
                    source = source,
                    createdAtMillis = nowMillis,
                    expiresAtMillis = nowMillis + EXTERNAL_APP_COMMAND_TTL_MILLIS,
                )
            val signature =
                signaturePolicy.sign(payload)
            return Intent(context, MainActivity::class.java).apply {
                this.action = MainActivity.ACTION_EXTERNAL_APP_COMMAND
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_COMMAND_ID, payload.commandId)
                putExtra(EXTRA_COMMAND_ACTION, payload.action.name)
                putExtra(EXTRA_COMMAND_SOURCE, payload.source.name)
                putExtra(EXTRA_CREATED_AT_MILLIS, payload.createdAtMillis)
                putExtra(EXTRA_EXPIRES_AT_MILLIS, payload.expiresAtMillis)
                putExtra(EXTRA_SIGNATURE_NONCE, signature.nonce)
                putExtra(EXTRA_SIGNATURE_VALUE, signature.value)
                data =
                    "lomo://external-command/${Uri.encode(payload.source.name)}/${Uri.encode(payload.action.name)}/${payload.commandId}"
                        .toUri()
            }
        }

        private fun Intent.toTrustedLaunchSignaturePayload(): TrustedLaunchSignaturePayload? {
            val commandId = getStringExtra(EXTRA_COMMAND_ID)?.takeIf(String::isNotBlank) ?: return null
            val action = getStringExtra(EXTRA_COMMAND_ACTION)?.toEnumOrNull<ExternalAppCommandAction>() ?: return null
            val source = getStringExtra(EXTRA_COMMAND_SOURCE)?.toEnumOrNull<ExternalAppCommandSource>() ?: return null
            val createdAtMillis = getLongExtra(EXTRA_CREATED_AT_MILLIS, INVALID_TIMESTAMP)
            val expiresAtMillis = getLongExtra(EXTRA_EXPIRES_AT_MILLIS, INVALID_TIMESTAMP)
            if (createdAtMillis == INVALID_TIMESTAMP || expiresAtMillis == INVALID_TIMESTAMP) {
                return null
            }
            return TrustedLaunchSignaturePayload(
                commandId = commandId,
                action = action,
                source = source,
                createdAtMillis = createdAtMillis,
                expiresAtMillis = expiresAtMillis,
            )
        }

        companion object {
            fun create(context: Context): TrustedLaunchIntents =
                TrustedLaunchIntents(
                    context = context.applicationContext,
                    secretStore = TrustedLaunchSecretStore(context.applicationContext),
                )

            private const val EXTRA_COMMAND_ID = "com.lomo.app.extra.EXTERNAL_COMMAND_ID"
            private const val EXTRA_COMMAND_ACTION = "com.lomo.app.extra.EXTERNAL_COMMAND_ACTION"
            private const val EXTRA_COMMAND_SOURCE = "com.lomo.app.extra.EXTERNAL_COMMAND_SOURCE"
            private const val EXTRA_CREATED_AT_MILLIS = "com.lomo.app.extra.EXTERNAL_COMMAND_CREATED_AT"
            private const val EXTRA_EXPIRES_AT_MILLIS = "com.lomo.app.extra.EXTERNAL_COMMAND_EXPIRES_AT"
            private const val EXTRA_SIGNATURE_NONCE = "com.lomo.app.extra.TRUSTED_SIGNATURE_NONCE"
            private const val EXTRA_SIGNATURE_VALUE = "com.lomo.app.extra.TRUSTED_SIGNATURE_VALUE"
            private const val INVALID_TIMESTAMP = Long.MIN_VALUE
        }
    }

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { value -> value.name == this }

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
