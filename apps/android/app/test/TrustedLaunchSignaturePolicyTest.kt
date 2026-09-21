/*
 * Behavior Contract:
 * - Unit under test: TrustedLaunchSignaturePolicy
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: sign persistent sensitive launcher commands so static/public Intents cannot start
 *   microphone actions without app-owned trust material.
 *
 * Scenarios:
 * - Given a signed external command payload, when the same command identity and expiry are verified,
 *   then the signature is accepted.
 * - Given the action, source, command ID, expiry, nonce, or signature changes, when verified, then
 *   trust is rejected.
 *
 * Observable outcomes: Boolean signature verification result.
 *
 * TDD proof:
 * - RED before implementation because signatures cover only action/purpose rather than the full
 *   external command payload.
 *
 * Excludes: Android ShortcutManager publishing, SharedPreferences storage, Activity routing, and HMAC provider internals.
 *
 * Test Change Justification:
 * Reason category: Domain contract change
 * Old behavior/assertion being replaced: TrustedLaunchSignaturePolicy(secretProvider = { String })
 * Why old assertion is no longer correct: the install trust root is a non-exportable HMAC key;
 * callers supply hmacSha256(ByteArray) and never receive the secret string.
 * Coverage preserved by: the same sign/verify payload matrix against ASCII-byte HMAC material.
 * Why this is not fitting the test to the implementation: verification still asserts the
 * observable accept/reject results of the signed command identity.
 */
package com.lomo.app

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TrustedLaunchSignaturePolicyTest : AppFunSpec() {
    init {
        test("given signed command when verifying same payload then signature is accepted") {
            val policy = policy()
            val payload = payload()

            val signature = policy.sign(payload)

            policy.verify(
                payload = payload,
                nonce = signature.nonce,
                signature = signature.value,
            ) shouldBe true
        }

        test("given signed command when trust material changes then signature is rejected") {
            val policy = policy()
            val payload = payload()
            val signature = policy.sign(payload)

            policy.verify(
                payload = payload.copy(action = ExternalAppCommandAction.StopRecording),
                nonce = signature.nonce,
                signature = signature.value,
            ) shouldBe false
            policy.verify(
                payload = payload.copy(source = ExternalAppCommandSource.Widget),
                nonce = signature.nonce,
                signature = signature.value,
            ) shouldBe false
            policy.verify(
                payload = payload.copy(commandId = "other-command"),
                nonce = signature.nonce,
                signature = signature.value,
            ) shouldBe false
            policy.verify(
                payload = payload.copy(expiresAtMillis = payload.expiresAtMillis + 1L),
                nonce = signature.nonce,
                signature = signature.value,
            ) shouldBe false
            policy.verify(
                payload = payload,
                nonce = "other-nonce",
                signature = signature.value,
            ) shouldBe false
            policy.verify(
                payload = payload,
                nonce = signature.nonce,
                signature = "bad-signature",
            ) shouldBe false
        }
    }
}

private fun policy(): TrustedLaunchSignaturePolicy =
    TrustedLaunchSignaturePolicy(
        hmacSha256 = { payload ->
            val mac = Mac.getInstance(HMAC_SHA_256)
            mac.init(SecretKeySpec("install-secret".encodeToByteArray(), HMAC_SHA_256))
            mac.doFinal(payload)
        },
        nonceProvider = { "nonce-1" },
    )

private fun payload(): TrustedLaunchSignaturePayload =
    TrustedLaunchSignaturePayload(
        commandId = "command-1",
        action = ExternalAppCommandAction.StartRecording,
        source = ExternalAppCommandSource.DynamicShortcut,
        createdAtMillis = 1_000L,
        expiresAtMillis = 601_000L,
    )
