package com.lomo.data.engine.lan

/*
 * Behavior Contract:
 * - Unit under test: encodeUncompressedP256PublicKey.
 * - Owning layer: data Android Keystore adapter.
 * - Priority tier: P0.
 * - Capability: export only the canonical public P-256 point Rust validates while the private key
 *   remains non-exportable in AndroidKeyStore.
 *
 * Scenarios:
 * - Given a generated P-256 public key, when exported, then it is exactly 65 bytes with the
 *   uncompressed-point tag and preserves both 32-byte affine coordinates.
 * - Given a key from another curve, when exported, then the platform boundary rejects it.
 *
 * Observable outcomes: canonical public bytes and explicit boundary rejection.
 * TDD proof: RED because the Keystore P-256 adapter and canonical encoder did not exist.
 * Excludes: AndroidKeyStore device persistence and biometric policy.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class AndroidLanDeviceKeyTest : DataFunSpec() {
    init {
        test("given a P-256 public key when exported then Rust receives one canonical point") {
            val key = generate("secp256r1")

            val encoded = encodeUncompressedP256PublicKey(key)

            encoded.size shouldBe 65
            encoded.first() shouldBe 0x04
            encoded.copyOfRange(1, 33) shouldBe key.w.affineX.toFixedUnsigned(32)
            encoded.copyOfRange(33, 65) shouldBe key.w.affineY.toFixedUnsigned(32)
        }

        test("given a foreign curve when exported then the platform boundary rejects it") {
            val key = generate("secp384r1")

            shouldThrow<IllegalArgumentException> {
                encodeUncompressedP256PublicKey(key)
            }
        }
    }
}

private fun generate(curve: String): ECPublicKey =
    KeyPairGenerator
        .getInstance("EC")
        .apply { initialize(ECGenParameterSpec(curve)) }
        .generateKeyPair()
        .public as ECPublicKey
