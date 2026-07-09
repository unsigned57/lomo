package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ExportEncryptedSettingsUseCase
import com.lomo.domain.usecase.ImportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ImportEncryptedSettingsUseCase
import com.lomo.domain.usecase.MigrationArchiveSummary
import com.lomo.domain.usecase.MigrationPasswordException
import com.lomo.domain.usecase.MigrationSettingsSummary
import io.kotest.assertions.asClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/*
 * Behavior Contract:
 * - Unit under test: SettingsMigrationFeatureViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: expose settings-screen commands for note zip and encrypted settings migration.
 *
 * Scenarios:
 * - Given an export target stream, when notes export succeeds, then success state reports exported note/media counts.
 * - Given an import source stream, when notes import succeeds, then success state reports imported note/media counts after domain rebuild.
 * - Given encrypted settings and a wrong password, when settings import fails, then the user-visible operation state becomes an error.
 *
 * Observable outcomes:
 * - operation state values, output stream bytes, repository call counts, and rebuild call count.
 *
 * TDD proof:
 * - RED: this spec fails to compile before the app migration feature view-model and operation state exist.
 *
 * Excludes:
 * - Android ActivityResult launchers, ContentResolver streams, Material rendering, and data-layer ZIP/encryption details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMigrationFeatureViewModelTest : AppFunSpec() {
    init {
        test("given output stream when notes export succeeds then success state reports counts") {
            runTest {
                val repository = FakeMigrationArchiveRepository()
                repository.notesExportSummary =
                    MigrationArchiveSummary(
                        noteCount = 2,
                        trashCount = 1,
                        imageCount = 1,
                        voiceCount = 1,
                    )
                repository.notesArchiveBytes = "zip".toByteArray()
                val feature = createFeature(repository = repository, scope = this)
                val output = ByteArrayOutputStream()

                feature.exportNotesArchive { output }
                advanceUntilIdle()

                output.toString(Charsets.UTF_8.name()) shouldBe "zip"
                repository.exportNotesCallCount shouldBe 1
                val success = feature.operationState.value.shouldBeInstanceOf<SettingsMigrationOperationState.Success>()
                success.kind shouldBe SettingsMigrationOperationKind.EXPORT_NOTES
                success.summary.asClue { summary ->
                    summary.noteCount shouldBe 2
                    summary.trashCount shouldBe 1
                    summary.imageCount shouldBe 1
                    summary.voiceCount shouldBe 1
                }
            }
        }

        test("given input stream when notes import succeeds then success state follows rebuild") {
            runTest {
                val repository = FakeMigrationArchiveRepository()
                repository.notesImportSummary =
                    MigrationArchiveSummary(
                        noteCount = 3,
                        trashCount = 1,
                        imageCount = 2,
                        voiceCount = 1,
                    )
                val workspaceStateResolver = FakeWorkspaceStateResolver()
                val feature =
                    createFeature(
                        repository = repository,
                        workspaceStateResolver = workspaceStateResolver,
                        scope = this,
                    )

                feature.importNotesArchive { ByteArrayInputStream("zip".toByteArray()) }
                advanceUntilIdle()

                repository.importNotesCallCount shouldBe 1
                workspaceStateResolver.rebuildCallCount shouldBe 1
                val success = feature.operationState.value.shouldBeInstanceOf<SettingsMigrationOperationState.Success>()
                success.kind shouldBe SettingsMigrationOperationKind.IMPORT_NOTES
                success.summary.noteCount shouldBe 3
            }
        }

        test("given wrong settings password when import fails then operation state is error") {
            runTest {
                val repository =
                    FakeMigrationArchiveRepository(
                        settingsImportFailure = MigrationPasswordException(),
                    )
                val feature = createFeature(repository = repository, scope = this)

                feature.importEncryptedSettings(
                    password = "wrong-password",
                    openInput = { ByteArrayInputStream("settings".toByteArray()) },
                )
                advanceUntilIdle()

                repository.importSettingsCallCount shouldBe 1
                val error = feature.operationState.value.shouldBeInstanceOf<SettingsMigrationOperationState.Error>()
                error.kind shouldBe SettingsMigrationOperationKind.IMPORT_SETTINGS
                error.message.isNotBlank() shouldBe true
            }
        }
    }
}

private fun createFeature(
    repository: FakeMigrationArchiveRepository,
    workspaceStateResolver: WorkspaceStateResolver = FakeWorkspaceStateResolver(),
    scope: kotlinx.coroutines.CoroutineScope,
): SettingsMigrationFeatureViewModel =
    SettingsMigrationFeatureViewModel(
        scope = scope,
        exportAllNotesArchiveUseCase = ExportAllNotesArchiveUseCase(repository),
        importAllNotesArchiveUseCase =
            ImportAllNotesArchiveUseCase(
                repository = repository,
                workspaceStateResolver = workspaceStateResolver,
            ),
        exportEncryptedSettingsUseCase = ExportEncryptedSettingsUseCase(repository),
        importEncryptedSettingsUseCase = ImportEncryptedSettingsUseCase(repository),
    )

private class FakeMigrationArchiveRepository(
    private val settingsImportFailure: Throwable? = null,
) : MigrationArchiveRepository {
    var notesArchiveBytes: ByteArray = byteArrayOf()
    var notesExportSummary = MigrationArchiveSummary()
    var notesImportSummary = MigrationArchiveSummary()
    var exportNotesCallCount = 0
    var importNotesCallCount = 0
    var importSettingsCallCount = 0

    override suspend fun exportAllNotesArchive(output: OutputStream): MigrationArchiveSummary {
        exportNotesCallCount += 1
        output.write(notesArchiveBytes)
        return notesExportSummary
    }

    override suspend fun importAllNotesArchive(input: InputStream): MigrationArchiveSummary {
        importNotesCallCount += 1
        input.readBytes()
        return notesImportSummary
    }

    override suspend fun exportEncryptedSettings(
        output: OutputStream,
        password: String,
    ): MigrationSettingsSummary {
        output.write(password.toByteArray())
        return MigrationSettingsSummary(settingCount = 2, sensitiveSettingCount = 1)
    }

    override suspend fun importEncryptedSettings(
        input: InputStream,
        password: String,
    ): MigrationSettingsSummary {
        importSettingsCallCount += 1
        settingsImportFailure?.let { throw it }
        input.readBytes()
        return MigrationSettingsSummary(settingCount = 2, sensitiveSettingCount = 1)
    }
}

private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
    var rebuildCallCount = 0

    override suspend fun rebuildFromCurrentWorkspace() {
        rebuildCallCount += 1
    }
}
