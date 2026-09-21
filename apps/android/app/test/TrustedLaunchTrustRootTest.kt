/*
 * Behavior Contract:
 * Capability: migrate the install HMAC trust root out of plaintext SharedPreferences into a
 * non-exportable HMAC key whose material is the hex secret's UTF-8/ASCII bytes; owning layer: app;
 * priority: P0.
 * Scenarios:
 * - Given a stored hex `install_secret`, when the first HMAC is produced, then the Keystore entry
 *   is imported from those exact ASCII bytes, a software HMAC over the same ASCII key matches, a
 *   HMAC over hex-decoded 32-byte material does not match, and plaintext `install_secret` is gone.
 * - Given the HMAC alias already exists, when leftover plaintext holds a different hex string, then
 *   import is not called again (no second trust root) and leftover plaintext is cleared.
 * - Given Keystore import fails and plaintext already exists, when HMAC is requested, then the
 *   failure is `TrustedLaunchTrustRootUnavailable`, plaintext is kept for retry, and no generated
 *   second secret is written.
 * Observable outcomes: imported key bytes, HMAC digest equality, plaintext presence, import count,
 * typed failure code.
 * TDD proof: RED same command, 3 failed: `hmacKey.contains() expected true was false`; leftover
 * plaintext `"bbbb…"`; expected `TrustedLaunchTrustRootUnavailable` but no exception. GREEN 5 passed.
 * Excludes: live AndroidKeyStore TEE persistence, Auto Backup XML (T22), widget process scheduling.
 */

package com.lomo.app

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TrustedLaunchTrustRootTest : AppFunSpec() {
    init {
        test("given hex install secret when first hmac runs then ascii bytes are imported and plaintext is cleared") {
            val hexSecret = "ab".repeat(INSTALL_SECRET_RANDOM_BYTES)
            val plaintext = FakePlaintextInstallSecret(hexSecret)
            val hmacKey = FakeHmacKeyEntry()
            val root =
                TrustedLaunchTrustRoot(
                    plaintext = plaintext,
                    hmacKey = hmacKey,
                    processLock = ImmediateTrustedLaunchProcessLock,
                )
            val payload = "widget-create".encodeToByteArray()

            val digest = root.hmacSha256(payload)

            hmacKey.contains() shouldBe true
            hmacKey.importedAsciiKey shouldBe trustedLaunchAsciiHmacKeyBytes(hexSecret)
            hmacKey.importCount shouldBe 1
            plaintext.read() shouldBe null
            MessageDigest.isEqual(digest, softwareHmac(trustedLaunchAsciiHmacKeyBytes(hexSecret), payload)) shouldBe true
            MessageDigest.isEqual(digest, softwareHmac(decodeHex(hexSecret), payload)) shouldBe false
        }

        test("given hex install secret when used as hmac key then ascii bytes differ from decoded 32-byte material") {
            val hexSecret = "cd".repeat(INSTALL_SECRET_RANDOM_BYTES)
            val payload = TRUSTED_LAUNCH_MIGRATION_PROBE
            val asciiDigest = softwareHmac(trustedLaunchAsciiHmacKeyBytes(hexSecret), payload)
            val decodedDigest = softwareHmac(decodeHex(hexSecret), payload)

            asciiDigest shouldNotBe decodedDigest
            trustedLaunchAsciiHmacKeyBytes(hexSecret).size shouldBe hexSecret.length
            decodeHex(hexSecret).size shouldBe INSTALL_SECRET_RANDOM_BYTES
        }

        test("given existing hmac alias when leftover plaintext differs then import is not repeated") {
            val originalAscii = trustedLaunchAsciiHmacKeyBytes("aa".repeat(INSTALL_SECRET_RANDOM_BYTES))
            val leftoverHex = "bb".repeat(INSTALL_SECRET_RANDOM_BYTES)
            val hmacKey = FakeHmacKeyEntry(importedAsciiKey = originalAscii)
            val plaintext = FakePlaintextInstallSecret(leftoverHex)
            val root =
                TrustedLaunchTrustRoot(
                    plaintext = plaintext,
                    hmacKey = hmacKey,
                    processLock = ImmediateTrustedLaunchProcessLock,
                )
            val payload = "shortcut".encodeToByteArray()

            val digest = root.hmacSha256(payload)

            hmacKey.importCount shouldBe 0
            plaintext.read() shouldBe null
            MessageDigest.isEqual(digest, softwareHmac(originalAscii, payload)) shouldBe true
            MessageDigest.isEqual(digest, softwareHmac(trustedLaunchAsciiHmacKeyBytes(leftoverHex), payload)) shouldBe false
        }

        test("given import failure and existing plaintext when hmac is requested then plaintext is kept and no second secret is written") {
            val hexSecret = "ef".repeat(INSTALL_SECRET_RANDOM_BYTES)
            val plaintext = FakePlaintextInstallSecret(hexSecret)
            val hmacKey = FakeHmacKeyEntry(failImport = true)
            val generated = ByteArray(INSTALL_SECRET_RANDOM_BYTES) { 0x11 }
            val root =
                TrustedLaunchTrustRoot(
                    plaintext = plaintext,
                    hmacKey = hmacKey,
                    processLock = ImmediateTrustedLaunchProcessLock,
                    randomBytes = { generated },
                )

            val error =
                shouldThrow<TrustedLaunchTrustRootUnavailable> {
                    root.hmacSha256("probe".encodeToByteArray())
                }

            error.code shouldBe "trusted_launch_hmac_import_failed"
            plaintext.read() shouldBe hexSecret
            plaintext.writeCount shouldBe 0
            hmacKey.contains() shouldBe false
            hmacKey.importCount shouldBe 1
        }

        test("given empty stores when first hmac runs then generated ascii key is imported and plaintext stays absent") {
            val plaintext = FakePlaintextInstallSecret()
            val hmacKey = FakeHmacKeyEntry()
            val generated = ByteArray(INSTALL_SECRET_RANDOM_BYTES) { 0x22 }
            val root =
                TrustedLaunchTrustRoot(
                    plaintext = plaintext,
                    hmacKey = hmacKey,
                    processLock = ImmediateTrustedLaunchProcessLock,
                    randomBytes = { generated },
                )
            val payload = "fresh-install".encodeToByteArray()
            val expectedHex = generated.toTrustedLaunchHexString()

            val digest = root.hmacSha256(payload)

            hmacKey.importedAsciiKey shouldBe trustedLaunchAsciiHmacKeyBytes(expectedHex)
            plaintext.read() shouldBe null
            plaintext.writeCount shouldBe 0
            MessageDigest.isEqual(
                digest,
                softwareHmac(trustedLaunchAsciiHmacKeyBytes(expectedHex), payload),
            ) shouldBe true
        }
    }
}

private object ImmediateTrustedLaunchProcessLock : TrustedLaunchProcessLock {
    override fun <T> withLock(block: () -> T): T = block()
}

private class FakePlaintextInstallSecret(
    initial: String? = null,
) : TrustedLaunchPlaintextInstallSecret {
    private var value: String? = initial
    var writeCount: Int = 0
        private set

    override fun read(): String? = value

    override fun clear() {
        value = null
    }
}

private class FakeHmacKeyEntry(
    importedAsciiKey: ByteArray? = null,
    private val failImport: Boolean = false,
) : TrustedLaunchHmacKeyEntry {
    var importedAsciiKey: ByteArray? = importedAsciiKey?.copyOf()
        private set
    var importCount: Int = 0
        private set

    override fun contains(): Boolean = importedAsciiKey != null

    override fun importAsciiKey(asciiKeyBytes: ByteArray) {
        importCount += 1
        if (failImport) {
            error("keystore import rejected")
        }
        importedAsciiKey = asciiKeyBytes.copyOf()
    }

    override fun sign(payload: ByteArray): ByteArray {
        val key =
            importedAsciiKey
                ?: throw TrustedLaunchTrustRootUnavailable("trusted_launch_hmac_missing")
        return softwareHmac(key, payload)
    }

    override fun delete() {
        importedAsciiKey = null
    }
}

private fun softwareHmac(
    keyBytes: ByteArray,
    payload: ByteArray,
): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA_256)
    mac.init(SecretKeySpec(keyBytes, HMAC_SHA_256))
    return mac.doFinal(payload)
}

private fun decodeHex(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex install secret must have even length" }
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}
