package com.lomo.data.engine.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Restricted Android capability: public identity export and pairing-transcript signing only. */
internal interface LanDeviceKey {
    fun publicIdentity(displayName: String): LanDeviceIdentity

    fun sign(challenge: LanSigningChallenge): ByteArray
}

/** Non-exportable installation identity backed by AndroidKeyStore. */
internal class AndroidLanDeviceKey(
    private val alias: String = KEY_ALIAS,
) : LanDeviceKey {
    override fun publicIdentity(displayName: String): LanDeviceIdentity {
        val keyPair = getOrCreateKeyPair()
        val publicKey = keyPair.public as? ECPublicKey
            ?: error("LAN device key public half is not an EC key")
        return LanDeviceIdentity(
            publicKey = encodeUncompressedP256PublicKey(publicKey),
            displayName = displayName,
        )
    }

    override fun sign(challenge: LanSigningChallenge): ByteArray {
        require(challenge.transcriptToSign.isNotEmpty()) {
            "LAN pairing challenge transcript must not be empty"
        }
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(getOrCreateKeyPair().private)
        signer.update(challenge.transcriptToSign)
        return signer.sign()
    }

    @Synchronized
    private fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingPrivate = keyStore.getKey(alias, null) as? PrivateKey
        val existingPublic = keyStore.getCertificate(alias)?.publicKey
        if (existingPrivate != null && existingPublic != null) {
            return KeyPair(existingPublic, existingPrivate)
        }

        val generator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
        generator.initialize(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.lomo.lan.device-signing-v2"
        const val P256_CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/** Canonical X9.62 uncompressed point accepted by `lomo-lan::DevicePublicKey`. */
internal fun encodeUncompressedP256PublicKey(publicKey: ECPublicKey): ByteArray {
    require(publicKey.params.curve.field.fieldSize == P256_FIELD_BITS) {
        "LAN device key must use the P-256 curve"
    }
    return byteArrayOf(UNCOMPRESSED_POINT_TAG) +
        publicKey.w.affineX.toFixedUnsigned(P256_COORDINATE_BYTES) +
        publicKey.w.affineY.toFixedUnsigned(P256_COORDINATE_BYTES)
}

internal fun BigInteger.toFixedUnsigned(width: Int): ByteArray {
    require(signum() >= 0) { "EC coordinate must be non-negative" }
    val signed = toByteArray()
    val unsigned =
        if (signed.size == width + 1 && signed.first() == 0.toByte()) {
            signed.copyOfRange(1, signed.size)
        } else {
            signed
        }
    require(unsigned.size <= width) { "EC coordinate exceeds the P-256 width" }
    return ByteArray(width).also { encoded ->
        unsigned.copyInto(encoded, destinationOffset = width - unsigned.size)
    }
}

private const val P256_FIELD_BITS = 256
private const val P256_COORDINATE_BYTES = 32
private const val UNCOMPRESSED_POINT_TAG: Byte = 0x04
