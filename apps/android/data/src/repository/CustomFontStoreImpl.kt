package com.lomo.data.repository

import android.content.Context
import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontNameState
import com.lomo.domain.model.CustomFontRejection
import com.lomo.domain.model.CustomFontSource
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val CUSTOM_FONT_DIR_NAME = "custom_fonts"
internal const val MAX_FONT_FILE_BYTES = 200L * 1024L * 1024L // 200 MiB import budget
private const val MAX_DISPLAY_NAME_CHARS = 64
private const val STREAM_CHUNK_BYTES = 64 * 1024
private val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf")
private val SFNT_MAGICS =
    listOf(
        byteArrayOf(0x00, 0x01, 0x00, 0x00), // TrueType
        byteArrayOf(0x4F, 0x54, 0x54, 0x4F), // "OTTO" OpenType/CFF
        byteArrayOf(0x74, 0x72, 0x75, 0x65), // "true" legacy TrueType
        byteArrayOf(0x74, 0x74, 0x63, 0x66), // "ttcf" TrueType collection
    )
private val UUID_FILE_NAME =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Validates that a staged file parses as a font. Production uses the platform typeface parser;
 * tests inject a fake so the store contract stays verifiable on the JVM.
 */
fun interface FontFileValidator {
    fun validate(file: File): Boolean
}

class PlatformFontFileValidator : FontFileValidator {
    override fun validate(file: File): Boolean =
        try {
            android.graphics.Typeface.createFromFile(file) != null
        } catch (_: RuntimeException) {
            // behavior-contract: silent-result-ok: a file the platform cannot parse is the
            // documented rejection — the importer surfaces it as INVALID_FONT, never silence
            false
        }
}

class CustomFontStoreImpl
    internal constructor(
        private val fontDir: File,
        private val fontFileValidator: FontFileValidator,
        private val dispatcherProvider: DispatcherProvider,
        private val maxFontFileBytes: Long = MAX_FONT_FILE_BYTES,
    ) : CustomFontStore {
        constructor(
            context: Context,
            dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
        ) : this(
            fontDir = File(context.filesDir, CUSTOM_FONT_DIR_NAME),
            fontFileValidator = PlatformFontFileValidator(),
            dispatcherProvider = dispatcherProvider,
        )

        private val state: MutableStateFlow<List<CustomFontInfo>> = MutableStateFlow(scanFonts())

        override fun observeFonts(): Flow<List<CustomFontInfo>> = state.asStateFlow()

        override suspend fun importFont(
            source: CustomFontSource,
            originalFileName: String,
        ): CustomFontImportResult =
            withContext(dispatcherProvider.io) {
                val decodedName = decodeFileName(originalFileName)
                val extension = decodedName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (extension !in SUPPORTED_FONT_EXTENSIONS) {
                    return@withContext CustomFontImportResult.Rejected(CustomFontRejection.UNSUPPORTED_TYPE)
                }
                fontDir.mkdirs()
                val stream =
                    try {
                        source.openStream()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        timber.log.Timber.w(error, "Custom font source stream failed to open")
                        return@withContext CustomFontImportResult.Rejected(CustomFontRejection.UNREADABLE)
                    } ?: return@withContext CustomFontImportResult.Rejected(CustomFontRejection.UNREADABLE)

                val staged = File(fontDir, ".import-${UUID.randomUUID()}.tmp")
                try {
                    val outcome =
                        stream.use { input ->
                            stageAndValidate(input, staged)
                        }
                    if (outcome != null) {
                        staged.delete()
                        return@withContext CustomFontImportResult.Rejected(outcome)
                    }
                    val displayName = sanitizeBaseName(decodedName.substringBeforeLast('.'))
                    val target = uniqueTarget(displayName, extension)
                    if (!staged.renameTo(target)) {
                        staged.delete()
                        return@withContext CustomFontImportResult.Rejected(CustomFontRejection.UNREADABLE)
                    }
                    val info =
                        CustomFontInfo(
                            id = target.name,
                            displayName = displayName,
                            sizeBytes = target.length(),
                            nameState = CustomFontNameState.ORIGINAL,
                        )
                    refreshState()
                    CustomFontImportResult.Imported(info)
                } finally {
                    if (staged.exists()) staged.delete()
                }
            }

        /**
         * Copies [input] to [staged] with the budget enforced on the stream itself: the copy
         * stops one byte past the limit, so oversized input never materializes on disk.
         * Returns the typed rejection or `null` when the staged file is publishable.
         */
        private fun stageAndValidate(
            input: java.io.InputStream,
            staged: File,
        ): CustomFontRejection? {
            val stagedRejection =
                try {
                    staged.outputStream().use { output -> stageBytes(input, output) }
                } catch (_: java.io.IOException) {
                    CustomFontRejection.UNREADABLE
                }
            if (stagedRejection != null) return stagedRejection
            return if (fontFileValidator.validate(staged)) {
                null
            } else {
                CustomFontRejection.INVALID_FONT
            }
        }

        private fun stageBytes(
            input: java.io.InputStream,
            output: java.io.OutputStream,
        ): CustomFontRejection? {
            val buffer = ByteArray(STREAM_CHUNK_BYTES)
            val header = ByteArray(SFNT_MAGIC_BYTES)
            var headerSize = 0
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxFontFileBytes) {
                    return CustomFontRejection.OVERSIZED
                }
                output.write(buffer, 0, read)
                if (headerSize < SFNT_MAGIC_BYTES) {
                    val copied = minOf(read, SFNT_MAGIC_BYTES - headerSize)
                    buffer.copyInto(header, headerSize, 0, copied)
                    headerSize += copied
                    if (headerSize == SFNT_MAGIC_BYTES && !hasSupportedMagic(header)) {
                        return CustomFontRejection.INVALID_FONT
                    }
                }
            }
            return if (headerSize < SFNT_MAGIC_BYTES) {
                CustomFontRejection.INVALID_FONT
            } else {
                null
            }
        }

        private fun hasSupportedMagic(header: ByteArray): Boolean =
            SFNT_MAGICS.any { magic -> magic.indices.all { index -> header[index] == magic[index] } }

        private fun uniqueTarget(
            baseName: String,
            extension: String,
        ): File {
            var candidate = File(fontDir, "$baseName.$extension")
            var suffix = 2
            while (candidate.exists()) {
                candidate = File(fontDir, "$baseName-$suffix.$extension")
                suffix += 1
            }
            return candidate
        }

        private fun sanitizeBaseName(raw: String): String {
            val sanitized =
                raw
                    .map { char -> if (char.isLetterOrDigit() || char in " ._-()[]") char else '_' }
                    .joinToString("")
                    .replace(Regex("\\.{2,}"), ".")
                    .trim()
                    .trimStart('.')
                    .take(MAX_DISPLAY_NAME_CHARS)
                    .trim()
            return sanitized.ifBlank { "font" }
        }

        private fun decodeFileName(name: String): String =
            try {
                java.net.URLDecoder.decode(name, "UTF-8")
            } catch (_: Exception) {
                name
            }

        override suspend fun deleteFont(id: String) {
            withContext(dispatcherProvider.io) {
                resolveSafeFontFile(id)?.takeIf(File::exists)?.delete()
                refreshState()
            }
        }

        override suspend fun resolveFontPath(id: String): String? =
            withContext(dispatcherProvider.io) {
                resolveSafeFontFile(id)?.takeIf(File::exists)?.absolutePath
            }

        private fun resolveSafeFontFile(id: String): File? {
            if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
            return File(fontDir, id)
        }

        private fun scanFonts(): List<CustomFontInfo> =
            fontDir
                .listFiles { file ->
                    file.isFile &&
                        file.extension.lowercase(java.util.Locale.ROOT) in SUPPORTED_FONT_EXTENSIONS
                }.orEmpty()
                .sortedBy(File::lastModified)
                .map { file ->
                    val legacy = UUID_FILE_NAME.matches(file.nameWithoutExtension)
                    CustomFontInfo(
                        id = file.name,
                        displayName = file.nameWithoutExtension,
                        sizeBytes = file.length(),
                        nameState =
                            if (legacy) {
                                CustomFontNameState.LEGACY_IMPORT
                            } else {
                                CustomFontNameState.ORIGINAL
                            },
                    )
                }

        private fun refreshState() {
            state.value = scanFonts()
        }

        private companion object {
            private const val SFNT_MAGIC_BYTES = 4
        }
    }
