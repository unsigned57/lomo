package com.lomo.data.media

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.lomo.data.engine.ContentResolverPlatformDocumentsGateway
import com.lomo.data.engine.SnapshotDigestMode
import com.lomo.data.engine.isValidWorkspacePath
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.AudioPlaybackResolverRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files

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
    private val workspaceMount: () -> WorkspaceMount,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : AudioPlaybackResolverRepository {
    private val documents by lazy { ContentResolverPlatformDocumentsGateway(context.contentResolver) }

    /**
     * A dedicated voice root owns voice/ recordings and selected bare names.
     * Other qualified paths retain workspace identity and bare names
     * require a unique workspace claim. Missing selected files never borrow an old root.
     */
    override suspend fun resolve(source: String): String? {
        directSource(source)?.let { return it }
        if (!isValidWorkspacePath(source)) {
            Timber.e("Cannot resolve invalid relative audio path: %s", source)
            return null
        }
        val mount = workspaceMount()
        val resolved = if (PATH_SEPARATOR in source) {
            resolveQualifiedSource(source, mount)
        } else {
            resolveBareSource(source, mount)
        }
        val installed = workspaceMount()
        return resolved.takeIf {
            installed.authority?.generation == mount.authority?.generation &&
                installed.location == mount.location && installed.attachments == mount.attachments
        }
    }

    private fun directSource(source: String): String? = source.takeIf {
        it.startsWith("/") || it.startsWith("content:") || it.startsWith("http")
    }

    private suspend fun resolveQualifiedSource(source: String, mount: WorkspaceMount): String? =
        withContext(dispatcherProvider.io) {
            val selected = mount.attachments.recordings?.takeIf { source.startsWith("voice/") }
            val baseDir = selected?.raw ?: mount.location?.raw ?: return@withContext missingBaseDir(source)
            val path = if (selected != null) source.removePrefix("voice/") else source
            resolveUnderBase(baseDir, path)
        }

    private suspend fun resolveBareSource(source: String, mount: WorkspaceMount): String? = withContext(
        dispatcherProvider.io,
    ) {
        val claims = LinkedHashSet<String>()
        val selectedVoice = mount.attachments.recordings
        if (selectedVoice != null) {
            collectVoiceClaims(selectedVoice.raw, source, claims)
        } else {
            mount.location?.let { location ->
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
    private fun collectBaseRootClaim(baseDir: String, name: String, out: MutableSet<String>) {
        val fileBase = usableFileBase(baseDir)
        if (fileBase != null) {
            File(
                fileBase,
                name,
            ).takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.let { out += it.absolutePath }
        } else {
            resolveSafUri(baseDir = baseDir, uri = name)?.let(out::add)
        }
    }

    /** Files named [name] at any depth under [rootDir], lifecycle lanes excluded. */
    private fun collectDescendantClaims(rootDir: File, name: String, out: MutableSet<String>) {
        if (!rootDir.isDirectory) return
        rootDir
            .walkTopDown()
            .onEnter { entry ->
                !Files.isSymbolicLink(entry.toPath()) && (entry == rootDir || entry.name !in MEDIA_LIFECYCLE_LANE_NAMES)
            }
            .onFail { _, failure -> throw failure }
            .filter { entry -> entry.isFile && entry.name == name && !Files.isSymbolicLink(entry.toPath()) }
            .forEach { entry -> out += entry.absolutePath }
    }

    /**
     * The configured voice base is itself one managed attachment root, so a bare
     * name claims it at any depth; SAF trees verify only the root claim
     * (enumeration is not available here).
     */
    private fun collectVoiceClaims(baseDir: String, name: String, out: MutableSet<String>) {
        val fileBase = usableFileBase(baseDir)
        if (fileBase != null) {
            collectDescendantClaims(fileBase, name, out)
        } else {
            resolveSafUri(baseDir = baseDir, uri = name)?.let(out::add)
        }
    }

    private fun resolveUnderBase(baseDir: String, source: String): String? = if (baseDir.startsWith("content://")) {
        resolveSafUri(baseDir = baseDir, uri = source)
    } else {
        val file = File(baseDir, source)
        file.takeIf {
            it.isFile && it.canonicalFile.toPath().startsWith(
                File(baseDir).canonicalFile.toPath(),
            )
        }?.absolutePath
    }

    /** A non-content base reduced to a usable `File` root, else null. */
    private fun usableFileBase(baseDir: String): File? = baseDir
        .takeUnless { it.startsWith("content://") }
        ?.let(::File)

    private fun resolveSafUri(baseDir: String, uri: String): String? = runNonFatalCatching {
        val snapshot = documents.stat(baseDir, WorkspaceTarget.Relative(uri), SnapshotDigestMode.METADATA_ONLY)
        snapshot?.takeIf { it.kind == DocumentKind.FILE }?.let {
            DocumentsContract.buildDocumentUriUsingTree(baseDir.toUri(), it.documentId).toString()
        }
    }.getOrElse { error ->
        Timber.e(error, "Failed to resolve SAF uri=%s from baseDir=%s", uri, baseDir)
        null
    }
}
