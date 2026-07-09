package com.lomo.domain.usecase

import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/*
 * Behavior Contract:
 * - Unit under test: migration import/export use cases.
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: migrate all memo files/media and encrypted settings through stream-based archives.
 *
 * Scenarios:
 * - Given a workspace with notes and media, when all notes are exported, then the repository writes to the caller stream and returns item counts.
 * - Given a notes archive, when it is inspected before import, then the repository returns the manifest plan without rebuilding workspace state.
 * - Given a notes archive, when it is imported, then files are restored and current workspace state is rebuilt before returning.
 * - Given an encrypted settings file and a wrong password, when import runs, then the password failure remains observable.
 *
 * Observable outcomes:
 * - Bytes written to an output stream, returned migration summaries, returned dry-run plan, rebuild call count, and thrown exception type.
 *
 * TDD proof:
 * - RED: this spec fails to compile before migration repository and use cases exist.
 * - RED: dry-run plan spec fails before migration archive inspection use case and repository contract exist.
 *
 * Excludes:
 * - ZIP entry format, encryption primitives, Android URI picker wiring, and repository storage internals.
 */
class MigrationUseCaseTest : DomainFunSpec() {
    init {
        test("given notes and media when all notes are exported then archive bytes and counts are returned") {
            runTest {
                val repository = FakeMigrationArchiveRepository()
                repository.nextNotesExportSummary =
                    MigrationArchiveSummary(
                        noteCount = 2,
                        trashCount = 1,
                        imageCount = 1,
                        voiceCount = 1,
                    )
                repository.notesArchiveBytes = "archive".toByteArray()
                val output = ByteArrayOutputStream()
                val useCase = ExportAllNotesArchiveUseCase(repository)

                val summary = useCase(output)

                output.toString(Charsets.UTF_8.name()) shouldBe "archive"
                summary.noteCount shouldBe 2
                summary.trashCount shouldBe 1
                summary.imageCount shouldBe 1
                summary.voiceCount shouldBe 1
                repository.exportNotesCallCount shouldBe 1
            }
        }

        test("given a notes archive when imported then workspace state is rebuilt after restore") {
            runTest {
                val repository = FakeMigrationArchiveRepository()
                repository.nextNotesImportSummary =
                    MigrationArchiveSummary(
                        noteCount = 3,
                        trashCount = 1,
                        imageCount = 2,
                        voiceCount = 1,
                    )
                val workspaceStateResolver = FakeWorkspaceStateResolver()
                val input = ByteArrayInputStream("archive".toByteArray())
                val useCase =
                    ImportAllNotesArchiveUseCase(
                        repository = repository,
                        workspaceStateResolver = workspaceStateResolver,
                    )

                val summary = useCase(input)

                summary.noteCount shouldBe 3
                summary.trashCount shouldBe 1
                summary.imageCount shouldBe 2
                summary.voiceCount shouldBe 1
                repository.importNotesCallCount shouldBe 1
                workspaceStateResolver.rebuildCallCount shouldBe 1
                repository.importCompletedBeforeRebuild shouldBe true
            }
        }

        test("given a notes archive when inspected before import then manifest plan is returned without rebuild") {
            runTest {
                val repository = FakeMigrationArchiveRepository()
                repository.nextNotesImportPlan =
                    MigrationArchiveImportPlan(
                        summary =
                            MigrationArchiveSummary(
                                noteCount = 4,
                                trashCount = 1,
                                imageCount = 2,
                                voiceCount = 1,
                            ),
                        manifestVersion = 1,
                    )
                val workspaceStateResolver = FakeWorkspaceStateResolver()
                val input = ByteArrayInputStream("archive".toByteArray())
                val useCase = InspectAllNotesArchiveUseCase(repository)

                val plan = useCase(input)

                plan.summary.noteCount shouldBe 4
                plan.summary.trashCount shouldBe 1
                plan.summary.imageCount shouldBe 2
                plan.summary.voiceCount shouldBe 1
                plan.manifestVersion shouldBe 1
                repository.inspectNotesCallCount shouldBe 1
                repository.importNotesCallCount shouldBe 0
                workspaceStateResolver.rebuildCallCount shouldBe 0
            }
        }

        test("given encrypted settings and wrong password when imported then failure remains observable") {
            runTest {
                val failure = MigrationPasswordException()
                val repository =
                    FakeMigrationArchiveRepository(
                        settingsImportFailure = failure,
                    )
                val useCase = ImportEncryptedSettingsUseCase(repository)

                val thrown =
                    shouldThrow<MigrationPasswordException> {
                        useCase(ByteArrayInputStream("settings".toByteArray()), "wrong-password")
                    }

                thrown shouldBe failure
                repository.importSettingsCallCount shouldBe 1
            }
        }
    }
}

private class FakeMigrationArchiveRepository(
    private val settingsImportFailure: Throwable? = null,
) : MigrationArchiveRepository {
    var notesArchiveBytes: ByteArray = byteArrayOf()
    var nextNotesExportSummary = MigrationArchiveSummary()
    var nextNotesImportPlan = MigrationArchiveImportPlan(summary = MigrationArchiveSummary(), manifestVersion = 1)
    var nextNotesImportSummary = MigrationArchiveSummary()
    var exportNotesCallCount = 0
    var inspectNotesCallCount = 0
    var importNotesCallCount = 0
    var importSettingsCallCount = 0
    var importCompletedBeforeRebuild = false

    override suspend fun exportAllNotesArchive(output: OutputStream): MigrationArchiveSummary {
        exportNotesCallCount += 1
        output.write(notesArchiveBytes)
        return nextNotesExportSummary
    }

    override suspend fun inspectAllNotesArchive(input: InputStream): MigrationArchiveImportPlan {
        inspectNotesCallCount += 1
        input.readBytes()
        return nextNotesImportPlan
    }

    override suspend fun importAllNotesArchive(input: InputStream): MigrationArchiveSummary {
        importNotesCallCount += 1
        input.readBytes()
        importCompletedBeforeRebuild = true
        return nextNotesImportSummary
    }

    override suspend fun exportEncryptedSettings(
        output: OutputStream,
        password: String,
    ): MigrationSettingsSummary {
        output.write(password.toByteArray())
        return MigrationSettingsSummary(settingCount = 1, sensitiveSettingCount = 1)
    }

    override suspend fun importEncryptedSettings(
        input: InputStream,
        password: String,
    ): MigrationSettingsSummary {
        importSettingsCallCount += 1
        settingsImportFailure?.let { throw it }
        input.readBytes()
        return MigrationSettingsSummary(settingCount = 1, sensitiveSettingCount = 1)
    }
}

private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
    var rebuildCallCount = 0

    override suspend fun rebuildFromCurrentWorkspace() {
        rebuildCallCount += 1
    }
}
