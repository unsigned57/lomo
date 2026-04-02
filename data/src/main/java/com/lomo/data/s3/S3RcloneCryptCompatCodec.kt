package com.lomo.data.s3

import android.annotation.SuppressLint
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class S3RcloneCryptCompatCodec(
    private val nonceGenerator: () -> ByteArray = ::generateNonce,
) {
    fun encryptKey(
        key: String,
        password: String,
    ): String {
        val keyMaterial = deriveKeyMaterial(password)
        return key
            .split('/')
            .joinToString(PATH_SEPARATOR) { segment ->
                if (segment.isEmpty()) {
                    segment
                } else {
                    val padded = pkcs7Pad(segment.toByteArray(StandardCharsets.UTF_8), AES_BLOCK_SIZE)
                    val encrypted = EmeCipher(keyMaterial.nameKey).encrypt(keyMaterial.nameTweak, padded)
                    Base64UrlNoPadding.encode(encrypted)
                }
            }
    }

    fun decryptKey(
        encryptedKey: String,
        password: String,
    ): String {
        val keyMaterial = deriveKeyMaterial(password)
        return encryptedKey
            .split('/')
            .joinToString(PATH_SEPARATOR) { segment ->
                if (segment.isEmpty()) {
                    segment
                } else {
                    decryptFilenameSegment(
                        encryptedSegment = segment,
                        keyMaterial = keyMaterial,
                    )
                }
            }
    }

    fun encryptBytes(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        val keyMaterial = deriveKeyMaterial(password)
        val initialNonce = nonceGenerator()
        require(initialNonce.size == FILE_NONCE_SIZE_BYTES) {
            "Rclone-compatible nonce must be 24 bytes"
        }

        val output = ByteArrayOutputStream(FILE_HEADER_SIZE + plaintext.size + SECRETBOX_MAC_SIZE)
        output.write(FILE_MAGIC)
        output.write(initialNonce)

        if (plaintext.isEmpty()) {
            return output.toByteArray()
        }

        val blockNonce = initialNonce.copyOf()
        var offset = 0
        while (offset < plaintext.size) {
            val endExclusive = minOf(offset + BLOCK_DATA_SIZE_BYTES, plaintext.size)
            val block = plaintext.copyOfRange(offset, endExclusive)
            output.write(secretBoxSeal(blockNonce, keyMaterial.dataKey, block))
            incrementNonce(blockNonce)
            offset = endExclusive
        }
        return output.toByteArray()
    }

    fun decryptBytes(
        encrypted: ByteArray,
        password: String,
    ): ByteArray {
        require(encrypted.size >= FILE_HEADER_SIZE) {
            "Encrypted rclone payload is too short"
        }
        require(encrypted.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            "Encrypted payload does not use the rclone magic header"
        }

        val keyMaterial = deriveKeyMaterial(password)
        if (encrypted.size == FILE_HEADER_SIZE) {
            return ByteArray(0)
        }

        val blockNonce = encrypted.copyOfRange(FILE_MAGIC.size, FILE_HEADER_SIZE)
        val output = ByteArrayOutputStream(encrypted.size - FILE_HEADER_SIZE)
        var offset = FILE_HEADER_SIZE
        while (offset < encrypted.size) {
            val remaining = encrypted.size - offset
            val blockSize = minOf(remaining, ENCRYPTED_BLOCK_SIZE_BYTES)
            require(blockSize > SECRETBOX_MAC_SIZE) {
                "Encrypted rclone block header is truncated"
            }
            val block = encrypted.copyOfRange(offset, offset + blockSize)
            output.write(secretBoxOpen(blockNonce, keyMaterial.dataKey, block))
            incrementNonce(blockNonce)
            offset += blockSize
        }
        return output.toByteArray()
    }

    private fun deriveKeyMaterial(password: String): DerivedKeyMaterial {
        val derived =
            if (password.isEmpty()) {
                ByteArray(TOTAL_KEY_MATERIAL_SIZE_BYTES)
            } else {
                SCrypt.generate(
                    password.toByteArray(StandardCharsets.UTF_8),
                    DEFAULT_SALT,
                    SCRYPT_N,
                    SCRYPT_R,
                    SCRYPT_P,
                    TOTAL_KEY_MATERIAL_SIZE_BYTES,
                )
            }
        return DerivedKeyMaterial(
            dataKey = derived.copyOfRange(0, DATA_KEY_SIZE_BYTES),
            nameKey = derived.copyOfRange(DATA_KEY_SIZE_BYTES, DATA_KEY_SIZE_BYTES + NAME_KEY_SIZE_BYTES),
            nameTweak = derived.copyOfRange(DATA_KEY_SIZE_BYTES + NAME_KEY_SIZE_BYTES, derived.size),
        )
    }

    private fun secretBoxSeal(
        nonce: ByteArray,
        key: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val xsalsa20 = XSalsa20Engine()
        val poly1305 = Poly1305()
        xsalsa20.init(true, ParametersWithIV(KeyParameter(key), nonce))

        val macKey = ByteArray(SECRETBOX_KEY_SIZE_BYTES)
        xsalsa20.processBytes(macKey, 0, macKey.size, macKey, 0)

        val output = ByteArray(plaintext.size + SECRETBOX_MAC_SIZE)
        xsalsa20.processBytes(plaintext, 0, plaintext.size, output, SECRETBOX_MAC_SIZE)

        poly1305.init(KeyParameter(macKey))
        poly1305.update(output, SECRETBOX_MAC_SIZE, plaintext.size)
        poly1305.doFinal(output, 0)
        return output
    }

    private fun secretBoxOpen(
        nonce: ByteArray,
        key: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val xsalsa20 = XSalsa20Engine()
        val poly1305 = Poly1305()
        xsalsa20.init(false, ParametersWithIV(KeyParameter(key), nonce))

        val macKey = ByteArray(SECRETBOX_KEY_SIZE_BYTES)
        xsalsa20.processBytes(macKey, 0, macKey.size, macKey, 0)

        val payloadSize = ciphertext.size - SECRETBOX_MAC_SIZE
        require(payloadSize >= 0) {
            "Encrypted rclone block is too short"
        }

        val calculatedMac = ByteArray(SECRETBOX_MAC_SIZE)
        poly1305.init(KeyParameter(macKey))
        poly1305.update(ciphertext, SECRETBOX_MAC_SIZE, payloadSize)
        poly1305.doFinal(calculatedMac, 0)
        require(MessageDigest.isEqual(calculatedMac, ciphertext.copyOfRange(0, SECRETBOX_MAC_SIZE))) {
            "Failed to authenticate rclone encrypted block"
        }

        return ByteArray(payloadSize).also { plaintext ->
            xsalsa20.processBytes(ciphertext, SECRETBOX_MAC_SIZE, payloadSize, plaintext, 0)
        }
    }

    private data class DerivedKeyMaterial(
        val dataKey: ByteArray,
        val nameKey: ByteArray,
        val nameTweak: ByteArray,
    )

    private fun decryptFilenameSegment(
        encryptedSegment: String,
        keyMaterial: DerivedKeyMaterial,
    ): String {
        val failures = mutableListOf<IllegalArgumentException>()
        decodeFilenameCandidates(encryptedSegment, failures).forEach { decoded ->
            try {
                require(decoded.isNotEmpty() && decoded.size % AES_BLOCK_SIZE == 0) {
                    "Encrypted rclone filename is malformed"
                }
                val decrypted = EmeCipher(keyMaterial.nameKey).decrypt(keyMaterial.nameTweak, decoded)
                val unpadded = pkcs7Unpad(decrypted, AES_BLOCK_SIZE)
                return unpadded.toString(StandardCharsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                failures += error
            }
        }
        throw failures.firstOrNull() ?: IllegalArgumentException(
            "Encrypted rclone filename is neither valid base64url nor legacy base32hex",
        )
    }

    private fun decodeFilenameCandidates(
        encryptedSegment: String,
        failures: MutableList<IllegalArgumentException>,
    ): List<ByteArray> {
        val candidates = mutableListOf<ByteArray>()
        decodeFilenameCandidate(
            decode = Base64UrlNoPadding::decode,
            input = encryptedSegment,
            failures = failures,
            candidates = candidates,
        )
        decodeFilenameCandidate(
            decode = Base32HexLowercaseNoPadding::decode,
            input = encryptedSegment,
            failures = failures,
            candidates = candidates,
        )
        return candidates
    }

    private fun decodeFilenameCandidate(
        decode: (String) -> ByteArray,
        input: String,
        failures: MutableList<IllegalArgumentException>,
        candidates: MutableList<ByteArray>,
    ) {
        runCatching { decode(input) }
            .onSuccess { decoded ->
                if (candidates.none(decoded::contentEquals)) {
                    candidates += decoded
                }
            }.onFailure { error ->
                (error as? IllegalArgumentException)?.let(failures::add)
            }
    }
}

@SuppressLint("GetInstance")
private class EmeCipher(
    key: ByteArray,
) {
    private val encryptCipher =
        Cipher.getInstance(AES_ECB_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM))
        }
    private val decryptCipher =
        Cipher.getInstance(AES_ECB_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM))
        }

    fun encrypt(
        tweak: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = transform(tweak, plaintext, encrypt = true)

    fun decrypt(
        tweak: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = transform(tweak, ciphertext, encrypt = false)

    private fun transform(
        tweak: ByteArray,
        input: ByteArray,
        encrypt: Boolean,
    ): ByteArray {
        require(tweak.size == AES_BLOCK_SIZE) { "EME tweak must be 16 bytes" }
        require(input.isNotEmpty() && input.size % AES_BLOCK_SIZE == 0) {
            "EME input must be a non-empty multiple of 16 bytes"
        }
        val blockCount = input.size / AES_BLOCK_SIZE
        require(blockCount <= MAX_EME_BLOCKS) {
            "EME input exceeds the rclone filename block limit"
        }

        val output = ByteArray(input.size)
        val lTable = tabulateL(blockCount)
        val tmp = ByteArray(AES_BLOCK_SIZE)
        for (index in 0 until blockCount) {
            val inputOffset = index * AES_BLOCK_SIZE
            xorInto(tmp, input, inputOffset, lTable[index])
            transformBlock(tmp, output, inputOffset, encrypt)
        }

        val mp = output.copyOfRange(0, AES_BLOCK_SIZE)
        xorInPlace(mp, tweak)
        for (index in 1 until blockCount) {
            xorInPlace(mp, output, index * AES_BLOCK_SIZE)
        }

        val mc = transformBlock(mp, encrypt)
        val m = xor(mp, mc)
        for (index in 1 until blockCount) {
            multiplyByTwoInPlace(m)
            val offset = index * AES_BLOCK_SIZE
            xorInto(tmp, output, offset, m)
            System.arraycopy(tmp, 0, output, offset, AES_BLOCK_SIZE)
        }

        val ccc1 = xor(mc, tweak)
        for (index in 1 until blockCount) {
            xorInPlace(ccc1, output, index * AES_BLOCK_SIZE)
        }
        System.arraycopy(ccc1, 0, output, 0, AES_BLOCK_SIZE)

        for (index in 0 until blockCount) {
            val offset = index * AES_BLOCK_SIZE
            val transformed = transformBlock(output.copyOfRange(offset, offset + AES_BLOCK_SIZE), encrypt)
            val finalBlock = xor(transformed, lTable[index])
            System.arraycopy(finalBlock, 0, output, offset, AES_BLOCK_SIZE)
        }

        return output
    }

    private fun tabulateL(blockCount: Int): List<ByteArray> {
        val lValue = transformBlock(ByteArray(AES_BLOCK_SIZE), encrypt = true)
        return List(blockCount) {
            multiplyByTwoInPlace(lValue)
            lValue.copyOf()
        }
    }

    private fun transformBlock(
        block: ByteArray,
        encrypt: Boolean,
    ): ByteArray {
        require(block.size == AES_BLOCK_SIZE) { "AES block input must be 16 bytes" }
        return if (encrypt) {
            synchronized(encryptCipher) {
                encryptCipher.doFinal(block)
            }
        } else {
            synchronized(decryptCipher) {
                decryptCipher.doFinal(block)
            }
        }
    }

    private fun transformBlock(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int,
        encrypt: Boolean,
    ) {
        val transformed = transformBlock(source, encrypt)
        System.arraycopy(transformed, 0, destination, destinationOffset, AES_BLOCK_SIZE)
    }
}

private object Base32HexLowercaseNoPadding {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"
    private val decodeTable =
        IntArray(ASCII_TABLE_SIZE) { INVALID_BASE32_VALUE }.apply {
            ALPHABET.forEachIndexed { index, char ->
                this[char.code] = index
                this[char.uppercaseChar().code] = index
            }
        }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val output = StringBuilder((input.size * Byte.SIZE_BITS + BASE32_OUTPUT_ROUNDING) / BASE32_BITS)
        var buffer = 0
        var bits = 0
        input.forEach { byte ->
            buffer = (buffer shl Byte.SIZE_BITS) or (byte.toInt() and BYTE_MASK)
            bits += Byte.SIZE_BITS
            while (bits >= BASE32_BITS) {
                bits -= BASE32_BITS
                output.append(ALPHABET[(buffer shr bits) and BASE32_MASK])
            }
        }
        if (bits > 0) {
            output.append(ALPHABET[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
        }
        return output.toString()
    }

    fun decode(input: String): ByteArray {
        require(!input.endsWith('=')) { "Encrypted rclone filename is not valid base32hex" }
        if (input.isEmpty()) return ByteArray(0)

        val output = ByteArrayOutputStream((input.length * BASE32_BITS) / Byte.SIZE_BITS)
        var buffer = 0
        var bits = 0
        input.forEach { char ->
            val value = char.code.takeIf { it < decodeTable.size }?.let(decodeTable::get) ?: -1
            require(value >= 0) { "Encrypted rclone filename is not valid base32hex" }
            buffer = (buffer shl BASE32_BITS) or value
            bits += BASE32_BITS
            while (bits >= Byte.SIZE_BITS) {
                bits -= Byte.SIZE_BITS
                output.write((buffer shr bits) and BYTE_MASK)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return output.toByteArray()
    }
}

private object Base64UrlNoPadding {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(input: ByteArray): String = encoder.encodeToString(input)

    fun decode(input: String): ByteArray {
        require(!input.endsWith('=')) { "Encrypted rclone filename is not valid base64url" }
        val normalized =
            input +
                "=".repeat(
                    (BASE64_QUANTUM_SIZE - input.length % BASE64_QUANTUM_SIZE) %
                        BASE64_QUANTUM_SIZE,
                )
        return try {
            decoder.decode(normalized)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted rclone filename is not valid base64url", error)
        }
    }
}

private fun pkcs7Pad(
    input: ByteArray,
    blockSize: Int,
): ByteArray {
    require(blockSize in MIN_PKCS7_BLOCK_SIZE..MAX_PKCS7_BLOCK_SIZE) { "Invalid PKCS7 block size" }
    val padding = blockSize - (input.size % blockSize)
    return input + ByteArray(padding) { padding.toByte() }
}

private fun pkcs7Unpad(
    input: ByteArray,
    blockSize: Int,
): ByteArray {
    require(input.isNotEmpty() && input.size % blockSize == 0) {
        "Encrypted rclone filename padding is invalid"
    }
    val padding = input.last().toInt() and BYTE_MASK
    require(padding in MIN_PKCS7_PADDING_SIZE..blockSize) {
        "Encrypted rclone filename padding is invalid"
    }
    for (index in input.size - padding until input.size) {
        require((input[index].toInt() and BYTE_MASK) == padding) {
            "Encrypted rclone filename padding is invalid"
        }
    }
    return input.copyOf(input.size - padding)
}

private fun xor(
    left: ByteArray,
    right: ByteArray,
): ByteArray = ByteArray(left.size).also { output -> xorInto(output, left, 0, right) }

private fun xorInto(
    destination: ByteArray,
    source: ByteArray,
    sourceOffset: Int,
    other: ByteArray,
) {
    require(destination.size == other.size) { "XOR buffer sizes must match" }
    for (index in destination.indices) {
        destination[index] = (source[sourceOffset + index].toInt() xor other[index].toInt()).toByte()
    }
}

private fun xorInPlace(
    target: ByteArray,
    other: ByteArray,
) {
    require(target.size == other.size) { "XOR buffer sizes must match" }
    for (index in target.indices) {
        target[index] = (target[index].toInt() xor other[index].toInt()).toByte()
    }
}

private fun xorInPlace(
    target: ByteArray,
    source: ByteArray,
    sourceOffset: Int,
) {
    for (index in target.indices) {
        target[index] = (target[index].toInt() xor source[sourceOffset + index].toInt()).toByte()
    }
}

private fun multiplyByTwoInPlace(block: ByteArray) {
    require(block.size == AES_BLOCK_SIZE) { "GF block must be 16 bytes" }
    val input = block.copyOf()
    val first = (input[0].toInt() and BYTE_MASK) shl 1
    val highBit = (input[LAST_AES_BLOCK_INDEX].toInt() and BYTE_MASK) ushr BYTE_HIGH_BIT_SHIFT
    block[0] = ((first xor (GF_MULTIPLIER and -highBit))).toByte()
    for (index in PREVIOUS_BYTE_OFFSET until input.size) {
        val value = (input[index].toInt() and BYTE_MASK) shl 1
        val carry = (input[index - PREVIOUS_BYTE_OFFSET].toInt() and BYTE_MASK) ushr BYTE_HIGH_BIT_SHIFT
        block[index] = (value + carry).toByte()
    }
}

private fun incrementNonce(nonce: ByteArray) {
    for (index in nonce.indices) {
        val current = nonce[index].toInt() and BYTE_MASK
        val next = (current + 1) and BYTE_MASK
        nonce[index] = next.toByte()
        if (next >= current) {
            break
        }
    }
}

private fun generateNonce(): ByteArray = ByteArray(FILE_NONCE_SIZE_BYTES).also(SecureRandom()::nextBytes)

private const val AES_ALGORITHM = "AES"
private const val AES_ECB_TRANSFORMATION = "AES/ECB/NoPadding"
private const val AES_BLOCK_SIZE = 16
private const val ASCII_TABLE_SIZE = 128
private const val BASE32_BITS = 5
private const val BASE32_MASK = 0x1F
private const val BASE64_QUANTUM_SIZE = 4
private const val BASE32_OUTPUT_ROUNDING = 4
private const val BLOCK_DATA_SIZE_BYTES = 64 * 1024
private const val BYTE_MASK = 0xFF
private const val BYTE_HIGH_BIT_SHIFT = 7
private const val DATA_KEY_SIZE_BYTES = 32
private const val FILE_MAGIC_SIZE_BYTES = 8
private const val FILE_NONCE_SIZE_BYTES = 24
private const val GF_MULTIPLIER = 135
private const val HEX_CHARS_PER_BYTE = 2
private const val HEX_RADIX = 16
private const val INVALID_BASE32_VALUE = -1
private const val LAST_AES_BLOCK_INDEX = AES_BLOCK_SIZE - 1
private const val MAX_EME_BLOCKS = 16 * 8
private const val MAX_PKCS7_BLOCK_SIZE = 255
private const val MIN_PKCS7_BLOCK_SIZE = 2
private const val MIN_PKCS7_PADDING_SIZE = 1
private const val NAME_KEY_SIZE_BYTES = 32
private const val PATH_SEPARATOR = "/"
private const val PREVIOUS_BYTE_OFFSET = 1
private const val SCRYPT_N = 16_384
private const val SCRYPT_P = 1
private const val SCRYPT_R = 8
private const val SECRETBOX_KEY_SIZE_BYTES = 32
private const val SECRETBOX_MAC_SIZE = 16
private const val TOTAL_KEY_MATERIAL_SIZE_BYTES = DATA_KEY_SIZE_BYTES + NAME_KEY_SIZE_BYTES + AES_BLOCK_SIZE
private const val ENCRYPTED_BLOCK_SIZE_BYTES = BLOCK_DATA_SIZE_BYTES + SECRETBOX_MAC_SIZE
private const val FILE_HEADER_SIZE = FILE_MAGIC_SIZE_BYTES + FILE_NONCE_SIZE_BYTES
private const val DEFAULT_SALT_HEX = "a80df43a8fbd0308a7cab83e581f86b1"
private val DEFAULT_SALT = DEFAULT_SALT_HEX.hexToByteArray()
private val FILE_MAGIC = "RCLONE\u0000\u0000".toByteArray(StandardCharsets.UTF_8)

private fun String.hexToByteArray(): ByteArray =
    chunked(HEX_CHARS_PER_BYTE)
        .map { chunk -> chunk.toInt(HEX_RADIX).toByte() }
        .toByteArray()
