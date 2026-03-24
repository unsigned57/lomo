package com.lomo.data.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlaybackUriResolverImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AudioPlaybackResolverRepository {
        @Volatile
        private var rootLocation: StorageLocation? = null

        @Volatile
        private var voiceLocation: StorageLocation? = null

        override fun setRootLocation(location: StorageLocation?) {
            rootLocation = location
        }

        override fun setVoiceLocation(location: StorageLocation?) {
            voiceLocation = location
        }

        override suspend fun resolve(source: String): String? {
            val directSource = directSource(source)
            val baseDir = directSource?.let { null } ?: resolveBaseDir(source)

            val resolvedSource =
                when {
                    directSource != null -> directSource
                    baseDir == null -> missingBaseDir(source)

                    else ->
                        withContext(Dispatchers.IO) {
                            resolveRelativeSource(baseDir, source)
                        }
                }
            return resolvedSource
        }

        private fun directSource(source: String): String? =
            source.takeIf {
                it.startsWith("/") || it.startsWith("content:") || it.startsWith("http")
            }

        private fun missingBaseDir(source: String): String? {
            Timber.e("Cannot resolve relative uri because base directory is missing: %s", source)
            return null
        }

        private fun resolveBaseDir(source: String): String? {
            val isJustFilename = !source.contains("/")
            return if (isJustFilename && voiceLocation != null) {
                voiceLocation?.raw
            } else {
                rootLocation?.raw
            }
        }

        private fun resolveRelativeSource(
            baseDir: String,
            source: String,
        ): String? =
            if (baseDir.startsWith("content://")) {
                resolveSafUri(baseDir = baseDir, uri = source)
            } else {
                val file = File(baseDir, source)
                if (file.exists()) file.absolutePath else null
            }

        private fun resolveSafUri(
            baseDir: String,
            uri: String,
        ): String? =
            runNonFatalCatching {
                val rootUri = Uri.parse(baseDir)
                var document = DocumentFile.fromTreeUri(context, rootUri)
                if (document == null || !document.isDirectory) {
                    null
                } else {
                    val parts = uri.split('/').filter { it.isNotBlank() }
                    for (part in parts) {
                        document = document?.findFile(part) ?: return null
                    }
                    document.uri.toString()
                }
            }.getOrElse { error ->
                Timber.e(error, "Failed to resolve SAF uri=%s from baseDir=%s", uri, baseDir)
                null
            }
    }
