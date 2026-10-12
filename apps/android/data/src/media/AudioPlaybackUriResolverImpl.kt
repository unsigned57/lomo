package com.lomo.data.media

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Managed attachment roots a bare basename may claim — Kotlin mirror of the
 * `lomo-workspace` `ATTACHMENT_LOOKUP_DIRS` vocabulary (media/images/voice). */
private val MANAGED_ATTACHMENT_ROOTS = listOf("media", "images", "voice")

/** Media lifecycle lanes enumerated out of attachment claims, mirroring the
 * workspace owner (`.lomo-media-stage` / `-trash` / `-delete-intents`). */
private val MEDIA_LIFECYCLE_LANE_NAMES =
    setOf(".lomo-media-stage", ".lomo-media-trash", ".lomo-media-delete-intents")

private const val PATH_SEPARATOR = '/'

class AudioPlaybackUriResolverImpl(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : AudioPlaybackResolverRepository {
        @Volatile
        // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
        private var rootLocation: StorageLocation? = null

        @Volatile
        // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
        private var voiceLocation: StorageLocation? = null

        override fun setRootLocation(location: StorageLocation?) {
            rootLocation = location
        }

        override fun setVoiceLocation(location: StorageLocation?) {
            voiceLocation = location
        }

        /**
         * Workspace directory law: qualified destinations are identities joined
         * verbatim under the workspace root; bare basenames pool every verified
         * claimant — the root file plus same-named files under the managed
         * attachment roots — and resolve only on a unique claim. Zero claims
         * miss; two or more fail closed with a Resolve failure, never a default.
         */
        override suspend fun resolve(source: String): String? {
            directSource(source)?.let { return it }
            return if (PATH_SEPARATOR in source) {
                resolveQualifiedSource(source)
            } else {
                resolveBareSource(source)
            }
        }

        private fun directSource(source: String): String? =
            source.takeIf {
                it.startsWith("/") || it.startsWith("content:") || it.startsWith("http")
            }

        private suspend fun resolveQualifiedSource(source: String): String? {
            val baseDir = rootLocation?.raw ?: return missingBaseDir(source)
            return withContext(dispatcherProvider.io) {
                resolveUnderBase(baseDir, source)
            }
        }

        private suspend fun resolveBareSource(source: String): String? =
            withContext(dispatcherProvider.io) {
                val claims = LinkedHashSet<String>()
                rootLocation?.let { location ->
                    val base = location.raw
                    // The bare spelling is itself the root claim.
                    collectBaseRootClaim(base, source, claims)
                    val baseDir = usableFileBase(base)
                    if (baseDir != null) {
                        for (dir in MANAGED_ATTACHMENT_ROOTS) {
                            collectDescendantClaims(File(baseDir, dir), source, claims)
                        }
                    }
                }
                voiceLocation?.let { location ->
                    collectVoiceClaims(location.raw, source, claims)
                }
                when (claims.size) {
                    1 -> claims.single()
                    0 -> null
                    else -> {
                        Timber.e("Ambiguous audio attachment basename=%s claims=%s", source, claims)
                        null
                    }
                }
            }

        private fun missingBaseDir(source: String): String? {
            Timber.e("Cannot resolve relative uri because base directory is missing: %s", source)
            return null
        }

        /** One verified claim: `baseDir/name`, either a real file or a SAF root hit. */
        private fun collectBaseRootClaim(
            baseDir: String,
            name: String,
            out: MutableSet<String>,
        ) {
            val fileBase = usableFileBase(baseDir)
            if (fileBase != null) {
                File(fileBase, name).takeIf { it.isFile }?.let { out += it.absolutePath }
            } else {
                resolveSafUri(baseDir = baseDir, uri = name)?.let(out::add)
            }
        }

        /** Files named [name] at any depth under [rootDir], lifecycle lanes excluded. */
        private fun collectDescendantClaims(
            rootDir: File,
            name: String,
            out: MutableSet<String>,
        ) {
            if (!rootDir.isDirectory) return
            rootDir
                .walkTopDown()
                .onEnter { entry -> entry == rootDir || entry.name !in MEDIA_LIFECYCLE_LANE_NAMES }
                .filter { entry -> entry.isFile && entry.name == name }
                .forEach { entry -> out += entry.absolutePath }
        }

        /**
         * The configured voice base is itself one managed attachment root, so a bare
         * name claims it at any depth; SAF trees verify only the root claim
         * (enumeration is not available here).
         */
        private fun collectVoiceClaims(
            baseDir: String,
            name: String,
            out: MutableSet<String>,
        ) {
            val fileBase = usableFileBase(baseDir)
            if (fileBase != null) {
                collectDescendantClaims(fileBase, name, out)
            } else {
                resolveSafUri(baseDir = baseDir, uri = name)?.let(out::add)
            }
        }

        private fun resolveUnderBase(
            baseDir: String,
            source: String,
        ): String? =
            if (baseDir.startsWith("content://")) {
                resolveSafUri(baseDir = baseDir, uri = source)
            } else {
                val file = File(baseDir, source)
                if (file.exists()) file.absolutePath else null
            }

        /** A non-content base reduced to a usable `File` root, else null. */
        private fun usableFileBase(baseDir: String): File? =
            baseDir
                .takeUnless { it.startsWith("content://") }
                ?.let(::File)

        private fun resolveSafUri(
            baseDir: String,
            uri: String,
        ): String? =
            runNonFatalCatching {
                val rootUri = baseDir.toUri()
                var document = DocumentFile.fromTreeUri(context, rootUri)
                if (document == null || !document.isDirectory) {
                    null
                } else {
                    val parts = uri.split(PATH_SEPARATOR).filter { it.isNotBlank() }
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
