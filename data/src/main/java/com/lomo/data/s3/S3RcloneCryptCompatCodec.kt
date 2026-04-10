package com.lomo.data.s3

import android.annotation.SuppressLint
import com.lomo.domain.model.S3RcloneCryptConfig
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    ): String =
        encryptKey(
            key = key,
            password = password,
            password2 = "",
            config = defaultConfig(),
        )

    fun decryptKey(
        encryptedKey: String,
        password: String,
    ): String =
        decryptKey(
            encryptedKey = encryptedKey,
            password = password,
            password2 = "",
            config = defaultConfig(),
        )

    fun encryptKey(
        key: String,
        password: String,
        password2: String,
        config: S3RcloneCryptConfig,
    ): String {
        val keyMaterial = deriveKeyMaterial(password, password2)
        return when (config.filenameEncryption) {
            S3RcloneFilenameEncryption.STANDARD ->
                transformSegments(key, config.directoryNameEncryption) { segment ->
                    val padded = pkcs7Pad(segment.toByteArray(StandardCharsets.UTF_8), AES_BLOCK_SIZE)
                    val encrypted = EmeCipher(keyMaterial.nameKey).encrypt(keyMaterial.nameTweak, padded)
                    filenameEncoding(config.filenameEncoding).encode(encrypted)
                }

            S3RcloneFilenameEncryption.OBFUSCATE ->
                transformSegments(key, config.directoryNameEncryption) { segment ->
                    obfuscateSegment(segment, keyMaterial.nameKey)
                }

            S3RcloneFilenameEncryption.OFF -> key + normalizedSuffix(config.encryptedSuffix)
        }
    }

    fun decryptKey(
        encryptedKey: String,
        password: String,
        password2: String,
        config: S3RcloneCryptConfig,
    ): String {
        val keyMaterial = deriveKeyMaterial(password, password2)
        return when (config.filenameEncryption) {
            S3RcloneFilenameEncryption.STANDARD ->
                transformSegments(encryptedKey, config.directoryNameEncryption) { segment ->
                    decryptStandardSegment(
                        encryptedSegment = segment,
                        keyMaterial = keyMaterial,
                        filenameEncoding = config.filenameEncoding,
                    )
                }

            S3RcloneFilenameEncryption.OBFUSCATE ->
                transformSegments(encryptedKey, config.directoryNameEncryption) { segment ->
                    deobfuscateSegment(segment, keyMaterial.nameKey)
                }

            S3RcloneFilenameEncryption.OFF -> decryptOffFilename(encryptedKey, config.encryptedSuffix)
        }
    }

    fun encryptBytes(
        plaintext: ByteArray,
        password: String,
    ): ByteArray = encryptBytes(plaintext, password, password2 = "")

    fun encryptBytes(
        plaintext: ByteArray,
        password: String,
        password2: String,
    ): ByteArray {
        val keyMaterial = deriveKeyMaterial(password, password2)
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
    ): ByteArray = decryptBytes(encrypted, password, password2 = "")

    fun decryptBytes(
        encrypted: ByteArray,
        password: String,
        password2: String,
    ): ByteArray {
        require(encrypted.size >= FILE_HEADER_SIZE) {
            "Encrypted rclone payload is too short"
        }
        require(encrypted.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            "Encrypted payload does not use the rclone magic header"
        }

        val keyMaterial = deriveKeyMaterial(password, password2)
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

    fun encryptFile(
        input: File,
        output: File,
        password: String,
        password2: String,
    ) {
        input.inputStream().use { source ->
            output.outputStream().use { sink ->
                encryptStream(
                    input = source,
                    output = sink,
                    password = password,
                    password2 = password2,
                )
            }
        }
    }

    fun decryptFile(
        input: File,
        output: File,
        password: String,
        password2: String,
    ) {
        input.inputStream().use { source ->
            output.outputStream().use { sink ->
                decryptStream(
                    input = source,
                    output = sink,
                    password = password,
                    password2 = password2,
                )
            }
        }
    }

    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        password: String,
        password2: String,
    ) {
        val keyMaterial = deriveKeyMaterial(password, password2)
        val initialNonce = nonceGenerator()
        require(initialNonce.size == FILE_NONCE_SIZE_BYTES) {
            "Rclone-compatible nonce must be 24 bytes"
        }

        output.write(FILE_MAGIC)
        output.write(initialNonce)

        val blockNonce = initialNonce.copyOf()
        val buffer = ByteArray(BLOCK_DATA_SIZE_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
            val block = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
            output.write(secretBoxSeal(blockNonce, keyMaterial.dataKey, block))
            incrementNonce(blockNonce)
            }
            read = input.read(buffer)
        }
    }

    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        password: String,
        password2: String,
    ) {
        val header = readRequiredBytes(input, FILE_HEADER_SIZE, "Encrypted rclone payload is too short")
        require(header.copyOfRange(0, FILE_MAGIC.size).contentEquals(FILE_MAGIC)) {
            "Encrypted payload does not use the rclone magic header"
        }

        val keyMaterial = deriveKeyMaterial(password, password2)
        val blockNonce = header.copyOfRange(FILE_MAGIC.size, FILE_HEADER_SIZE)
        val buffer = ByteArray(ENCRYPTED_BLOCK_SIZE_BYTES)
        while (true) {
            val blockSize = readBlock(input, buffer)
            if (blockSize == 0) {
                break
            }
            require(blockSize > SECRETBOX_MAC_SIZE) {
                "Encrypted rclone block header is truncated"
            }
            output.write(secretBoxOpen(blockNonce, keyMaterial.dataKey, buffer.copyOf(blockSize)))
            incrementNonce(blockNonce)
        }
    }

    private fun deriveKeyMaterial(
        password: String,
        password2: String,
    ): RcloneDerivedKeyMaterial {
        val derived =
            if (password.isEmpty()) {
                ByteArray(TOTAL_KEY_MATERIAL_SIZE_BYTES)
            } else {
                SCrypt.generate(
                    password.toByteArray(StandardCharsets.UTF_8),
                    if (password2.isEmpty()) DEFAULT_SALT else password2.toByteArray(StandardCharsets.UTF_8),
                    SCRYPT_N,
                    SCRYPT_R,
                    SCRYPT_P,
                    TOTAL_KEY_MATERIAL_SIZE_BYTES,
                )
            }
        return RcloneDerivedKeyMaterial(
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

    private fun readRequiredBytes(
        input: InputStream,
        count: Int,
        message: String,
    ): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) {
                throw IOException(message)
            }
            offset += read
        }
        return buffer
    }

    private fun readBlock(
        input: InputStream,
        buffer: ByteArray,
    ): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) {
                return total
            }
            total += read
            if (read == 0) {
                break
            }
        }
        return total
    }
}

private fun defaultConfig() =
    S3RcloneCryptConfig(
        filenameEncryption = S3RcloneFilenameEncryption.STANDARD,
        directoryNameEncryption = true,
        filenameEncoding = S3RcloneFilenameEncoding.BASE64,
        dataEncryptionEnabled = true,
        encryptedSuffix = ".bin",
    )

private data class RcloneDerivedKeyMaterial(
    val dataKey: ByteArray,
    val nameKey: ByteArray,
    val nameTweak: ByteArray,
)

private fun transformSegments(
    value: String,
    encryptDirectories: Boolean,
    transform: (String) -> String,
): String {
    val segments = value.split(PATH_SEPARATOR)
    return segments
        .mapIndexed { index, segment ->
            if (segment.isEmpty()) {
                segment
            } else if (!encryptDirectories && index != segments.lastIndex) {
                segment
            } else {
                transform(segment)
            }
        }.joinToString(PATH_SEPARATOR)
}

private fun decryptStandardSegment(
    encryptedSegment: String,
    keyMaterial: RcloneDerivedKeyMaterial,
    filenameEncoding: S3RcloneFilenameEncoding,
): String {
    val failures = mutableListOf<IllegalArgumentException>()
    filenameDecoders(filenameEncoding).forEach { decoder ->
        runCatching { decoder(encryptedSegment) }
            .onSuccess { decoded ->
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
            }.onFailure { error ->
                (error as? IllegalArgumentException)?.let(failures::add)
            }
    }
    throw failures.firstOrNull() ?: IllegalArgumentException("Encrypted rclone filename is malformed")
}

private fun filenameEncoding(filenameEncoding: S3RcloneFilenameEncoding): FilenameEncoding =
    when (filenameEncoding) {
        S3RcloneFilenameEncoding.BASE32 -> Base32HexLowercaseNoPadding
        S3RcloneFilenameEncoding.BASE64 -> Base64UrlNoPadding
        S3RcloneFilenameEncoding.BASE32768 -> Base32768FilenameEncoding
    }

private fun filenameDecoders(filenameEncoding: S3RcloneFilenameEncoding): List<(String) -> ByteArray> =
    when (filenameEncoding) {
        S3RcloneFilenameEncoding.BASE32 -> listOf(Base32HexLowercaseNoPadding::decode)
        S3RcloneFilenameEncoding.BASE64 ->
            listOf(
                Base64UrlNoPadding::decode,
                Base32HexLowercaseNoPadding::decode,
            )

        S3RcloneFilenameEncoding.BASE32768 -> listOf(Base32768FilenameEncoding::decode)
    }

private fun obfuscateSegment(
    plaintext: String,
    nameKey: ByteArray,
): String {
    if (plaintext.isEmpty()) {
        return ""
    }
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(plaintext)) {
        return "$OBFUSCATE_QUOTE.$plaintext"
    }
    var direction = 0
    plaintext.forEachCodePoint { codePoint -> direction += codePoint }
    direction %= 256

    val result = StringBuilder()
    result.append(direction).append('.')
    nameKey.forEach { byte -> direction += byte.toInt() and BYTE_MASK }

    plaintext.forEachCodePoint { codePoint ->
        when {
            codePoint == OBFUSCATE_QUOTE.code -> {
                result.append(OBFUSCATE_QUOTE).append(OBFUSCATE_QUOTE)
            }

            codePoint in '0'.code..'9'.code -> {
                val offset = (direction % 9) + 1
                result.appendCodePoint('0'.code + (codePoint - '0'.code + offset) % 10)
            }

            codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code -> {
                val offset = direction % 25 + 1
                var position = codePoint - 'A'.code
                if (position >= 26) {
                    position -= 6
                }
                position = (position + offset) % 52
                if (position >= 26) {
                    position += 6
                }
                result.appendCodePoint('A'.code + position)
            }

            codePoint in 0xA0..0xFF -> {
                val offset = (direction % 95) + 1
                result.appendCodePoint(0xA0 + (codePoint - 0xA0 + offset) % 96)
            }

            codePoint >= 0x100 -> {
                val offset = (direction % 127) + 1
                val base = codePoint - codePoint % 256
                val rotated = base + (codePoint - base + offset) % 256
                if (!Character.isValidCodePoint(rotated) || rotated in 0xD800..0xDFFF) {
                    result.append(OBFUSCATE_QUOTE)
                    result.appendCodePoint(codePoint)
                } else {
                    result.appendCodePoint(rotated)
                }
            }

            else -> result.appendCodePoint(codePoint)
        }
    }
    return result.toString()
}

private fun deobfuscateSegment(
    ciphertext: String,
    nameKey: ByteArray,
): String {
    if (ciphertext.isEmpty()) {
        return ""
    }
    val dotIndex = ciphertext.indexOf('.')
    require(dotIndex >= 0) { "Encrypted rclone filename is not a valid obfuscated segment" }
    val prefix = ciphertext.substring(0, dotIndex)
    val encoded = ciphertext.substring(dotIndex + 1)
    if (prefix == OBFUSCATE_QUOTE.toString()) {
        return encoded
    }
    var direction = prefix.toIntOrNull()
        ?: throw IllegalArgumentException("Encrypted rclone filename is not a valid obfuscated segment")
    nameKey.forEach { byte -> direction += byte.toInt() and BYTE_MASK }

    val result = StringBuilder()
    var inQuote = false
    encoded.forEachCodePoint { codePoint ->
        when {
            inQuote -> {
                result.appendCodePoint(codePoint)
                inQuote = false
            }

            codePoint == OBFUSCATE_QUOTE.code -> inQuote = true
            codePoint in '0'.code..'9'.code -> {
                val offset = (direction % 9) + 1
                var rotated = '0'.code + codePoint - '0'.code - offset
                if (rotated < '0'.code) {
                    rotated += 10
                }
                result.appendCodePoint(rotated)
            }

            codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code -> {
                val offset = direction % 25 + 1
                var position = codePoint - 'A'.code
                if (position >= 26) {
                    position -= 6
                }
                position -= offset
                if (position < 0) {
                    position += 52
                }
                if (position >= 26) {
                    position += 6
                }
                result.appendCodePoint('A'.code + position)
            }

            codePoint in 0xA0..0xFF -> {
                val offset = (direction % 95) + 1
                var rotated = 0xA0 + codePoint - 0xA0 - offset
                if (rotated < 0xA0) {
                    rotated += 96
                }
                result.appendCodePoint(rotated)
            }

            codePoint >= 0x100 -> {
                val offset = (direction % 127) + 1
                val base = codePoint - codePoint % 256
                var rotated = base + (codePoint - base - offset)
                if (rotated < base) {
                    rotated += 256
                }
                result.appendCodePoint(rotated)
            }

            else -> result.appendCodePoint(codePoint)
        }
    }
    return result.toString()
}

private fun decryptOffFilename(
    encryptedKey: String,
    configuredSuffix: String,
): String {
    val suffix = normalizedSuffix(configuredSuffix)
    return if (suffix.isEmpty()) {
        require(encryptedKey.isNotEmpty()) { "Encrypted rclone filename is malformed" }
        encryptedKey
    } else {
        require(encryptedKey.endsWith(suffix) && encryptedKey.length > suffix.length) {
            "Encrypted rclone filename is malformed"
        }
        encryptedKey.removeSuffix(suffix)
    }
}

private fun normalizedSuffix(value: String): String =
    when {
        value.isBlank() -> ""
        value.equals("none", ignoreCase = true) -> ""
        value.startsWith(".") -> value
        else -> ".$value"
    }

private interface FilenameEncoding {
    fun encode(input: ByteArray): String

    fun decode(input: String): ByteArray
}

private object Base32768FilenameEncoding : FilenameEncoding {
    override fun encode(input: ByteArray): String = Base32768SafeEncoding.encode(input)

    override fun decode(input: String): ByteArray = Base32768SafeEncoding.decode(input)
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

private object Base32HexLowercaseNoPadding : FilenameEncoding {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"
    private val decodeTable =
        IntArray(ASCII_TABLE_SIZE) { INVALID_BASE32_VALUE }.apply {
            ALPHABET.forEachIndexed { index, char ->
                this[char.code] = index
                this[char.uppercaseChar().code] = index
            }
        }

    override fun encode(input: ByteArray): String {
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

    override fun decode(input: String): ByteArray {
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

private object Base64UrlNoPadding : FilenameEncoding {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun encode(input: ByteArray): String = encoder.encodeToString(input)

    override fun decode(input: String): ByteArray {
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

private fun String.forEachCodePoint(block: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        block(codePoint)
        index += Character.charCount(codePoint)
    }
}

private fun StringBuilder.appendCodePoint(codePoint: Int): StringBuilder {
    append(String(Character.toChars(codePoint)))
    return this
}

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
private const val OBFUSCATE_QUOTE = '!'
private val DEFAULT_SALT = DEFAULT_SALT_HEX.hexToByteArray()
private val FILE_MAGIC = "RCLONE\u0000\u0000".toByteArray(StandardCharsets.UTF_8)

private fun String.hexToByteArray(): ByteArray =
    chunked(HEX_CHARS_PER_BYTE)
        .map { chunk -> chunk.toInt(HEX_RADIX).toByte() }
        .toByteArray()
