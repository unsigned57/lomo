package com.lomo.data.repository

import android.content.Context
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.repository.CustomFontStore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID


private const val CUSTOM_FONT_DIR_NAME = "custom_fonts"
private val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf")

class CustomFontStoreImpl(
    private val context: Context,
) : CustomFontStore {
        private val fontDir: File by lazy {
            File(context.filesDir, CUSTOM_FONT_DIR_NAME).apply { if (!exists()) mkdirs() }
        }
        private val state: MutableStateFlow<List<CustomFontInfo>> = MutableStateFlow(scanFonts())

        override fun observeFonts(): Flow<List<CustomFontInfo>> = state.asStateFlow()

        override suspend fun importFont(
            contents: ByteArray,
            originalFileName: String,
        ): CustomFontInfo? =
            withContext(Dispatchers.IO) {
                val decodedName =
                    runCatching { java.net.URLDecoder.decode(originalFileName, "UTF-8") }
                        .getOrDefault(originalFileName)
                val extension = decodedName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                if (extension !in SUPPORTED_FONT_EXTENSIONS) return@withContext null
                val baseName = decodedName.substringBeforeLast('.').take(MAX_DISPLAY_NAME_CHARS)
                val id = UUID.randomUUID().toString() + "." + extension
                val target = File(fontDir, id)
                target.writeBytes(contents)
                val info = CustomFontInfo(
                    id = id,
                    displayName = baseName.takeIf(String::isNotBlank) ?: id,
                    sizeBytes = target.length(),
                )
                refreshState()
                info
            }

        override suspend fun deleteFont(id: String) {
            withContext(Dispatchers.IO) {
                resolveSafeFontFile(id)?.takeIf(File::exists)?.delete()
                refreshState()
            }
        }

        override suspend fun resolveFontPath(id: String): String? =
            withContext(Dispatchers.IO) {
                resolveSafeFontFile(id)?.takeIf(File::exists)?.absolutePath
            }

        private fun resolveSafeFontFile(id: String): File? {
            if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
            return File(fontDir, id)
        }

        private fun scanFonts(): List<CustomFontInfo> =
            fontDir
                .listFiles { file -> file.isFile && file.extension.lowercase() in SUPPORTED_FONT_EXTENSIONS }
                .orEmpty()
                .sortedBy(File::lastModified)
                .map { file ->
                    CustomFontInfo(
                        id = file.name,
                        displayName = file.nameWithoutExtension,
                        sizeBytes = file.length(),
                    )
                }

        private fun refreshState() {
            state.value = scanFonts()
        }

        companion object {
            private const val MAX_DISPLAY_NAME_CHARS = 64
        }
    }
