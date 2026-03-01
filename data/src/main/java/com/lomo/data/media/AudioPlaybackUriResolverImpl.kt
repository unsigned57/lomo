package com.lomo.data.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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
            if (source.startsWith("/") || source.startsWith("content:") || source.startsWith("http")) {
                return source
            }

            val isJustFilename = !source.contains("/")
            val baseDir =
                if (isJustFilename && voiceLocation != null) {
                    voiceLocation?.raw
                } else {
                    rootLocation?.raw
                }
            if (baseDir == null) {
                Timber.e("Cannot resolve relative uri because base directory is missing: %s", source)
                return null
            }

            return withContext(Dispatchers.IO) {
                if (baseDir.startsWith("content://")) {
                    resolveSafUri(baseDir = baseDir, uri = source)
                } else {
                    val file = File(baseDir, source)
                    if (file.exists()) file.absolutePath else null
                }
            }
        }

        private fun resolveSafUri(
            baseDir: String,
            uri: String,
        ): String? =
            try {
                val rootUri = Uri.parse(baseDir)
                var document = DocumentFile.fromTreeUri(context, rootUri)
                if (document == null || !document.isDirectory) {
                    null
                } else {
                    val parts = uri.split('/').filter { it.isNotBlank() }
                    for (part in parts) {
                        document = document?.findFile(part) ?: return null
                    }
                    document?.uri?.toString()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Failed to resolve SAF uri=%s from baseDir=%s", uri, baseDir)
                null
            }
    }
