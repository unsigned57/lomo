/*
 * Behavior Contract:
 * Capability: classify signed launcher Intents as accepted commands, ignored untrusted input, or
 * a typed trust-root failure; owning layer: app; priority: P0.
 * Scenarios:
 * - Given a signed widget create-memo payload and a working HMAC root, when extracted, then the
 *   command is Accepted with that action and source.
 * - Given the same signed payload and a Keystore import that fails, when extracted, then the result
 *   is TrustRootUnavailable rather than Ignored.
 * Observable outcomes: TrustedLaunchCommandExtraction variant and command identity.
 * TDD proof: RED `./kotlin test --include-module=app --include-classes='com.lomo.app.TrustedLaunchIntentsTest'`
 * expected TrustRootUnavailable but was Ignored. GREEN same command, 2 passed.
 * Excludes: Activity enqueue UI, live AndroidKeyStore, ShortcutManager publishing.
 */

package com.lomo.app

import android.content.Context
import android.content.Intent
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

class TrustedLaunchIntentsTest : AppFunSpec() {
    init {
        test("given a signed widget command when extracted with a working root then the command is accepted") {
            val fixture = signedWidgetCommand()

            val extraction =
                fixture.workingIntents.extractTrustedExternalAppCommand(
                    intent = fixture.intent,
                    nowMillis = fixture.nowMillis,
                )

            val accepted = extraction.shouldBeInstanceOf<TrustedLaunchCommandExtraction.Accepted>()
            accepted.command.action shouldBe ExternalAppCommandAction.CreateMemo
            accepted.command.source shouldBe ExternalAppCommandSource.Widget
            accepted.command.id shouldBe fixture.payload.commandId
        }

        test("given a signed command when the trust root cannot be imported then extraction is trust-root failure") {
            val fixture = signedWidgetCommand()

            val extraction =
                fixture.failingIntents.extractTrustedExternalAppCommand(
                    intent = fixture.intent,
                    nowMillis = fixture.nowMillis,
                )

            val failed = extraction.shouldBeInstanceOf<TrustedLaunchCommandExtraction.TrustRootUnavailable>()
            failed.error.code shouldBe "trusted_launch_hmac_import_failed"
        }
    }
}

private fun signedWidgetCommand(): SignedWidgetCommandFixture {
    val context = mockk<Context>()
    val nowMillis = 1_700_000_000_000L
    val payload =
        TrustedLaunchSignaturePayload(
            commandId = "command-1",
            action = ExternalAppCommandAction.CreateMemo,
            source = ExternalAppCommandSource.Widget,
            createdAtMillis = nowMillis,
            expiresAtMillis = nowMillis + EXTERNAL_APP_COMMAND_TTL_MILLIS,
        )
    val workingStore =
        TrustedLaunchSecretStore(
            TrustedLaunchTrustRoot(
                plaintext = MemoryInstallSecret("ab".repeat(INSTALL_SECRET_RANDOM_BYTES)),
                hmacKey = MemoryHmacKeyEntry(),
                processLock = ImmediateLock,
            ),
        )
    val signature =
        TrustedLaunchSignaturePolicy(hmacSha256 = workingStore::hmacSha256).sign(payload)
    val intent =
        ExtractionIntent(
            actionValue = MainActivity.ACTION_EXTERNAL_APP_COMMAND,
            strings =
                mapOf(
                    TrustedLaunchIntents.EXTRA_COMMAND_ID to payload.commandId,
                    TrustedLaunchIntents.EXTRA_COMMAND_ACTION to payload.action.name,
                    TrustedLaunchIntents.EXTRA_COMMAND_SOURCE to payload.source.name,
                    TrustedLaunchIntents.EXTRA_SIGNATURE_NONCE to signature.nonce,
                    TrustedLaunchIntents.EXTRA_SIGNATURE_VALUE to signature.value,
                ),
            longs =
                mapOf(
                    TrustedLaunchIntents.EXTRA_CREATED_AT_MILLIS to payload.createdAtMillis,
                    TrustedLaunchIntents.EXTRA_EXPIRES_AT_MILLIS to payload.expiresAtMillis,
                ),
        )
    val failingIntents =
        TrustedLaunchIntents(
            context = context,
            secretStore =
                TrustedLaunchSecretStore(
                    TrustedLaunchTrustRoot(
                        plaintext = MemoryInstallSecret("cd".repeat(INSTALL_SECRET_RANDOM_BYTES)),
                        hmacKey = FailingImportHmacKeyEntry(),
                        processLock = ImmediateLock,
                    ),
                ),
        )
    return SignedWidgetCommandFixture(
        payload = payload,
        nowMillis = nowMillis,
        intent = intent,
        workingIntents = TrustedLaunchIntents(context = context, secretStore = workingStore),
        failingIntents = failingIntents,
    )
}

private data class SignedWidgetCommandFixture(
    val payload: TrustedLaunchSignaturePayload,
    val nowMillis: Long,
    val intent: Intent,
    val workingIntents: TrustedLaunchIntents,
    val failingIntents: TrustedLaunchIntents,
)

private class ExtractionIntent(
    private val actionValue: String,
    private val strings: Map<String, String>,
    private val longs: Map<String, Long>,
) : Intent() {
    override fun getAction(): String = actionValue

    override fun getStringExtra(name: String): String? = strings[name]

    override fun getLongExtra(
        name: String,
        defaultValue: Long,
    ): Long = longs[name] ?: defaultValue
}

private object ImmediateLock : TrustedLaunchProcessLock {
    override fun <T> withLock(block: () -> T): T = block()
}

private class MemoryInstallSecret(
    initial: String?,
) : TrustedLaunchPlaintextInstallSecret {
    private var value: String? = initial

    override fun read(): String? = value

    override fun clear() {
        value = null
    }
}

private class MemoryHmacKeyEntry : TrustedLaunchHmacKeyEntry {
    private var key: ByteArray? = null

    override fun contains(): Boolean = key != null

    override fun importAsciiKey(asciiKeyBytes: ByteArray) {
        key = asciiKeyBytes.copyOf()
    }

    override fun sign(payload: ByteArray): ByteArray = softwareAsciiHmac(checkNotNull(key), payload)

    override fun delete() {
        key = null
    }
}

private class FailingImportHmacKeyEntry : TrustedLaunchHmacKeyEntry {
    override fun contains(): Boolean = false

    override fun importAsciiKey(asciiKeyBytes: ByteArray) {
        error("keystore import rejected")
    }

    override fun sign(payload: ByteArray): ByteArray =
        throw TrustedLaunchTrustRootUnavailable("trusted_launch_hmac_missing")

    override fun delete() = Unit
}
