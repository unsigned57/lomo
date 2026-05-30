package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationArchiveImportPlan
import com.lomo.domain.usecase.MigrationArchiveSummary
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal class MigrationArchiveDryRunPlanner(
    private val importBudgets: MigrationArchiveImportBudgets,
) {
    fun dryRunAllNotesArchive(
        input: InputStream,
        compressedArchiveBytes: Long,
    ): MigrationArchiveImportPlan {
        val state = MigrationArchiveDryRunState()
        val payloadEntries = mutableSetOf<String>()

        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) {
                    inspectArchiveEntry(
                        entry = entry,
                        zip = zip,
                        state = state,
                        payloadEntries = payloadEntries,
                    )
                }
                zip.closeEntry()
            }
        }
        validateCompressionRatio(
            uncompressedBytes = state.uncompressedBytes,
            compressedArchiveBytes = compressedArchiveBytes,
        )

        val summary = state.toSummary()
        val manifest = requireNotNull(state.manifest) { "Migration archive manifest is missing" }
        manifest.validateSummary(summary)
        return MigrationArchiveImportPlan(
            summary = summary,
            manifestVersion = manifest.version,
        )
    }

    private fun inspectArchiveEntry(
        entry: ZipEntry,
        zip: ZipInputStream,
        state: MigrationArchiveDryRunState,
        payloadEntries: MutableSet<String>,
    ) {
        state.recordEntry(importBudgets)
        when {
            entry.name == MANIFEST_ENTRY -> inspectManifestEntry(zip = zip, state = state)
            entry.name.startsWith(MAIN_NOTES_PREFIX) ->
                inspectMarkdownEntry(entry, zip, state, payloadEntries, MAIN_NOTES_PREFIX) {
                    state.noteCount += 1
                }
            entry.name.startsWith(TRASH_NOTES_PREFIX) ->
                inspectMarkdownEntry(entry, zip, state, payloadEntries, TRASH_NOTES_PREFIX) {
                    state.trashCount += 1
                }
            entry.name.startsWith(IMAGE_MEDIA_PREFIX) ->
                inspectMediaEntry(entry, zip, state, payloadEntries, IMAGE_MEDIA_PREFIX) {
                    state.imageCount += 1
                }
            entry.name.startsWith(VOICE_MEDIA_PREFIX) ->
                inspectMediaEntry(entry, zip, state, payloadEntries, VOICE_MEDIA_PREFIX) {
                    state.voiceCount += 1
                }
            else -> throw IllegalArgumentException("Unsupported migration archive entry: ${entry.name}")
        }
    }

    private fun inspectManifestEntry(
        zip: ZipInputStream,
        state: MigrationArchiveDryRunState,
    ) {
        require(state.manifest == null) { "Migration archive must contain only one manifest" }
        val manifestRead =
            zip.readBudgetedEntry(
                captureBytes = true,
                currentUncompressedBytes = state.uncompressedBytes,
                perEntryMaxBytes = importBudgets.maxManifestBytes.toLong(),
                perEntryBudgetName = "manifest",
            )
        state.uncompressedBytes += manifestRead.byteCount
        state.manifest = parseManifest(manifestRead)
    }

    private fun parseManifest(manifestRead: BudgetedArchiveEntryRead): MigrationArchiveManifest {
        val parsed =
            try {
                migrationJson.decodeFromString<MigrationArchiveManifest>(
                    checkNotNull(manifestRead.bytes).toString(Charsets.UTF_8),
                )
            } catch (exception: SerializationException) {
                throw IllegalArgumentException("Migration archive manifest is not valid", exception)
            }
        require(parsed.version == MIGRATION_ARCHIVE_VERSION) {
            "Unsupported migration archive version: ${parsed.version}"
        }
        return parsed
    }

    private fun inspectMarkdownEntry(
        entry: ZipEntry,
        zip: ZipInputStream,
        state: MigrationArchiveDryRunState,
        payloadEntries: MutableSet<String>,
        prefix: String,
        recordCount: () -> Unit,
    ) {
        entry.requireArchiveFilename(prefix)
        require(payloadEntries.add(entry.name)) { "Duplicate migration archive entry: ${entry.name}" }
        state.uncompressedBytes +=
            zip.readBudgetedEntry(
                captureBytes = false,
                currentUncompressedBytes = state.uncompressedBytes,
                perEntryMaxBytes = importBudgets.maxMarkdownEntryBytes.toLong(),
                perEntryBudgetName = "markdown",
            ).byteCount
        recordCount()
    }

    private fun inspectMediaEntry(
        entry: ZipEntry,
        zip: ZipInputStream,
        state: MigrationArchiveDryRunState,
        payloadEntries: MutableSet<String>,
        prefix: String,
        recordCount: () -> Unit,
    ) {
        val category =
            when (prefix) {
                IMAGE_MEDIA_PREFIX -> WorkspaceMediaCategory.IMAGE
                VOICE_MEDIA_PREFIX -> WorkspaceMediaCategory.VOICE
                else -> error("Unsupported migration archive media prefix: $prefix")
            }
        entry.requireArchiveMediaFilename(prefix = prefix, category = category)
        require(payloadEntries.add(entry.name)) { "Duplicate migration archive entry: ${entry.name}" }
        state.uncompressedBytes +=
            zip.readBudgetedEntry(
                captureBytes = false,
                currentUncompressedBytes = state.uncompressedBytes,
            ).byteCount
        recordCount()
    }

    private fun ZipInputStream.readBudgetedEntry(
        captureBytes: Boolean,
        currentUncompressedBytes: Long,
        perEntryMaxBytes: Long? = null,
        perEntryBudgetName: String? = null,
    ): BudgetedArchiveEntryRead {
        val output = if (captureBytes) ByteArrayOutputStream() else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) {
                break
            }
            entryBytes += read.toLong()
            validateEntryByteBudget(entryBytes, perEntryMaxBytes, perEntryBudgetName)
            validateTotalByteBudget(currentUncompressedBytes + entryBytes)
            output?.write(buffer, 0, read)
        }
        return BudgetedArchiveEntryRead(bytes = output?.toByteArray(), byteCount = entryBytes)
    }

    private fun validateEntryByteBudget(
        entryBytes: Long,
        perEntryMaxBytes: Long?,
        perEntryBudgetName: String?,
    ) {
        if (perEntryMaxBytes != null) {
            val budgetName = requireNotNull(perEntryBudgetName) { "Migration archive entry budget name is required" }
            require(entryBytes <= perEntryMaxBytes) {
                "Migration archive $budgetName byte budget exceeded: $entryBytes > $perEntryMaxBytes"
            }
        }
    }

    private fun validateTotalByteBudget(totalUncompressedBytes: Long) {
        require(totalUncompressedBytes <= importBudgets.maxUncompressedBytes) {
            "Migration archive uncompressed byte budget exceeded: " +
                "$totalUncompressedBytes > ${importBudgets.maxUncompressedBytes}"
        }
    }

    private fun validateCompressionRatio(
        uncompressedBytes: Long,
        compressedArchiveBytes: Long,
    ) {
        if (uncompressedBytes == 0L) {
            return
        }
        require(compressedArchiveBytes > 0L) { "Migration archive compressed byte count must be positive" }
        require(uncompressedBytes <= compressedArchiveBytes * importBudgets.maxCompressionRatio.toLong()) {
            "Migration archive compression ratio budget exceeded: " +
                "$uncompressedBytes uncompressed bytes from $compressedArchiveBytes compressed bytes"
        }
    }
}

private class BudgetedArchiveEntryRead(
    val bytes: ByteArray?,
    val byteCount: Long,
)

private class MigrationArchiveDryRunState {
    var noteCount: Int = 0
    var trashCount: Int = 0
    var imageCount: Int = 0
    var voiceCount: Int = 0
    var entryCount: Int = 0
    var uncompressedBytes: Long = 0L
    var manifest: MigrationArchiveManifest? = null

    fun recordEntry(importBudgets: MigrationArchiveImportBudgets) {
        entryCount += 1
        require(entryCount <= importBudgets.maxEntries) {
            "Migration archive entry count budget exceeded: $entryCount > ${importBudgets.maxEntries}"
        }
    }

    fun toSummary(): MigrationArchiveSummary =
        MigrationArchiveSummary(
            noteCount = noteCount,
            trashCount = trashCount,
            imageCount = imageCount,
            voiceCount = voiceCount,
        )
}

private fun MigrationArchiveManifest.validateSummary(summary: MigrationArchiveSummary) {
    require(noteCount == summary.noteCount) {
        "Migration archive manifest note count $noteCount does not match payload ${summary.noteCount}"
    }
    require(trashCount == summary.trashCount) {
        "Migration archive manifest trash count $trashCount does not match payload ${summary.trashCount}"
    }
    require(imageCount == summary.imageCount) {
        "Migration archive manifest image count $imageCount does not match payload ${summary.imageCount}"
    }
    require(voiceCount == summary.voiceCount) {
        "Migration archive manifest voice count $voiceCount does not match payload ${summary.voiceCount}"
    }
}
