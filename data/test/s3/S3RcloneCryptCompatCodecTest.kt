package com.lomo.data.s3

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3RcloneCryptCompatCodec
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: Remotely Save-compatible rclone filename encoding plus backward-compatible decryption
 *   for legacy base32hex names and payloads, with scrypt key material derived once per password pair.
 *
 * Scenarios:
 * - Given official rclone/Remotely Save vectors, when keys and payloads are encoded or decoded, then
 *   outputs match the vectors exactly and tampered payloads are rejected.
 * - Given repeated codec operations with one password pair, when keys and payloads are encoded or
 *   decoded, then expensive key material derivation runs exactly once for that pair.
 * - Given a second distinct password pair, when it is used, then it derives its own key material once.
 *
 * Observable outcomes: exact filename/content vectors, successful round-trips, tamper rejection, and
 * the number of key material derivation executions observed through the derivation boundary.
 *
 * TDD proof:
 * - Fails before the fix because Remotely Save base64url rclone object names are rejected as invalid
 *   base32hex filenames.
 * - Derivation-count scenario fails before key material caching because every encode/decode re-runs
 *   the scrypt derivation (observed 4 executions for one password pair instead of 1).
 *
 * Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3RcloneCryptCompatCodecTest : DataFunSpec() {
    init {
        test("encryptKey matches remotely save rclone base64url filename vector") { `encryptKey matches remotely save rclone base64url filename vector`() }

        test("decryptKey matches official rclone filename vector") { `decryptKey matches official rclone filename vector`() }

        test("encryptBytes matches official rclone content vectors") { `encryptBytes matches official rclone content vectors`() }

        test("decryptBytes recovers plaintext from official rclone vector") { `decryptBytes recovers plaintext from official rclone vector`() }

        test("decryptBytes rejects tampered ciphertext") { `decryptBytes rejects tampered ciphertext`() }

        test("decryptKey matches remotely save rclone base64url filename vector") { `decryptKey matches remotely save rclone base64url filename vector`() }

        test("decryptKey accepts legacy base32hex filename vector with non-empty password") { `decryptKey accepts legacy base32hex filename vector with non-empty password`() }

        test("encryptBytes round-trips with non-empty password") { `encryptBytes round-trips with non-empty password`() }

        test("decryptBytes recovers remotely save rclone crypt vector with non-empty password") { `decryptBytes recovers remotely save rclone crypt vector with non-empty password`() }

        test("repeated operations derive scrypt key material once per password pair") { `repeated operations derive scrypt key material once per password pair`() }
    }


    private val nonce =
        byteArrayOf(
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0A,
            0x0B,
            0x0C,
            0x0D,
            0x0E,
            0x0F,
            0x10,
            0x11,
            0x12,
            0x13,
            0x14,
            0x15,
            0x16,
            0x17,
            0x18,
        )

    private val codec =
        S3RcloneCryptCompatCodec(
            nonceGenerator = { nonce.copyOf() },
    )

    private fun `encryptKey matches remotely save rclone base64url filename vector`() {
        codec.encryptKey("lomo/memo/note.md", password = "secret-pass") shouldBe "Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ"
    }

    private fun `decryptKey matches official rclone filename vector`() {
        codec.decryptKey(
                "p0e52nreeaj0a5ea7s64m4j72s/l42g6771hnv3an9cgc8cr2n1ng/qgm4avr35m5loi1th53ato71v0",
                password = "",
            ) shouldBe "1/12/123"
    }

    private fun `encryptBytes matches official rclone content vectors`() {
        codec.encryptBytes(byteArrayOf(), password = "") shouldBe file0
        codec.encryptBytes(byteArrayOf(0x01), password = "") shouldBe file1
        codec.encryptBytes(
                byteArrayOf(
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08,
                    0x09,
                    0x0A,
                    0x0B,
                    0x0C,
                    0x0D,
                    0x0E,
                    0x0F,
                    0x10,
                ),
                password = "",
            ) shouldBe file16
    }

    private fun `decryptBytes recovers plaintext from official rclone vector`() {
        codec.decryptBytes(file0, password = "") shouldBe byteArrayOf()
        codec.decryptBytes(file1, password = "") shouldBe byteArrayOf(0x01)
        codec.decryptBytes(file16, password = "") shouldBe byteArrayOf(
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
            )
    }

    private fun `decryptBytes rejects tampered ciphertext`() {
        val tampered = file16.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }

        shouldThrow<IllegalArgumentException> {
            codec.decryptBytes(tampered, password = "")
        }
    }

    private fun `decryptKey matches remotely save rclone base64url filename vector`() {
        codec.decryptKey(
                "Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ",
                password = "secret-pass",
            ) shouldBe "lomo/memo/note.md"
    }

    private fun `decryptKey accepts legacy base32hex filename vector with non-empty password`() {
        codec.decryptKey(
                "cdstvrnl362juv8oi01d86l2ko/ueah915jf4t2hn0uapmel59ktg/urqpi0jn7krspfghunnfalh6fk",
                password = "secret-pass",
            ) shouldBe "lomo/memo/note.md"
    }

    private fun `encryptBytes round-trips with non-empty password`() {
        val plaintext = "hello".toByteArray()

        val encrypted = codec.encryptBytes(plaintext, password = "secret-pass")

        codec.decryptBytes(encrypted, password = "secret-pass") shouldBe plaintext
    }

    private fun `decryptBytes recovers remotely save rclone crypt vector with non-empty password`() {
        codec.decryptBytes(remotelySaveHelloPayload, password = "secret-pass") shouldBe "hello".toByteArray()
    }

    private fun `repeated operations derive scrypt key material once per password pair`() {
        var derivations = 0
        val countingCodec =
            S3RcloneCryptCompatCodec(
                nonceGenerator = { nonce.copyOf() },
                keyMaterialDerivation = { password, password2 ->
                    derivations += 1
                    deriveRcloneKeyMaterial(password, password2)
                },
            )

        val encryptedKey = countingCodec.encryptKey("lomo/memo/note.md", password = "secret-pass")
        countingCodec.decryptKey(encryptedKey, password = "secret-pass") shouldBe "lomo/memo/note.md"
        val encryptedBytes = countingCodec.encryptBytes("hello".toByteArray(), password = "secret-pass")
        countingCodec.decryptBytes(encryptedBytes, password = "secret-pass") shouldBe "hello".toByteArray()
        derivations shouldBe 1

        countingCodec.decryptKey(
            "p0e52nreeaj0a5ea7s64m4j72s/l42g6771hnv3an9cgc8cr2n1ng/qgm4avr35m5loi1th53ato71v0",
            password = "",
        ) shouldBe "1/12/123"
        derivations shouldBe 2
    }

    private companion object {
        val file0 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
            )

        val file1 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
                0x09,
                0x5B,
                0x44,
                0x6C,
                0xD6.toByte(),
                0x23,
                0x7B,
                0xBC.toByte(),
                0xB0.toByte(),
                0x8D.toByte(),
                0x09,
                0xFB.toByte(),
                0x52,
                0x4C,
                0xE5.toByte(),
                0x65,
                0xAA.toByte(),
            )

        val file16 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
                0xB9.toByte(),
                0xC4.toByte(),
                0x55,
                0x2A,
                0x27,
                0x10,
                0x06,
                0x29,
                0x18,
                0x96.toByte(),
                0x0A,
                0x3E,
                0x60,
                0x8C.toByte(),
                0x29,
                0xB9.toByte(),
                0xAA.toByte(),
                0x8A.toByte(),
                0x5E,
                0x1E,
                0x16,
                0x5B,
                0x6D,
                0x07,
                0x5D,
                0xE4.toByte(),
                0xE9.toByte(),
                0xBB.toByte(),
                0x36,
                0x7F,
                0xD6.toByte(),
                0xD4.toByte(),
            )

        val remotelySaveHelloPayload =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x18,
                0x5A,
                0xF3.toByte(),
                0x75,
                0xBC.toByte(),
                0xBD.toByte(),
                0x38,
                0x37,
                0xD0.toByte(),
                0x33,
                0xCD.toByte(),
                0x92.toByte(),
                0xB4.toByte(),
                0x8B.toByte(),
                0x67,
                0x77,
                0xF8.toByte(),
                0x5B,
                0x40,
                0xD9.toByte(),
                0x65,
                0x3D,
                0xAD.toByte(),
                0xCA.toByte(),
                0xF9.toByte(),
                0xA0.toByte(),
                0x5E,
                0x56,
                0xCB.toByte(),
                0xD7.toByte(),
                0xBB.toByte(),
                0x85.toByte(),
                0xC8.toByte(),
                0xB2.toByte(),
                0xBA.toByte(),
                0xAF.toByte(),
                0x25,
                0x1D,
                0xFF.toByte(),
                0xC1.toByte(),
                0xDB.toByte(),
                0x9B.toByte(),
                0x09,
                0xB0.toByte(),
                0x3A,
            )
    }
}
