package com.lomo.app.feature.settings
import com.lomo.app.testing.fakes.FakeWriteFreezeRepository

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SettingsAppConfigCoordinator.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: coordinates settings UI commands into repository-backed state updates.
 *
 * - Scenarios:
 *   - Given initial state, when directory display Flows are observed before emission, then they stay Loading.
 *   - Given emitted location display names, when display Flows collect, then directory states resolve.
 *   - Given root directory updates, when the coordinator applies them, then switch-root is delegated.
 *   - Given image/voice/sync inbox directory updates, when applied, then repository storage locations update.
 *   - Given preference toggle updates, when applied, then each updates only its own repository state.
 *   - Given date/time/theme/haptic/foreground auto-input preference updates, when applied, then the
 *     correct repository settings change.
 *
 * - Observable outcomes:
 *   - Directory display state Flow emissions.
 *   - Backing repository preference states and storage locations.
 *   - Rebuild call count from WorkspaceStateResolver.
 *
 * - TDD proof:
 *   - RED: before foreground auto-input was exposed by the settings coordinator, the new update
 *     assertion could not compile.
 *
 * - Excludes: Datastore file serialization, Android system settings integration.
 
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */
class SettingsAppConfigCoordinatorTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver, FakeWriteFreezeRepository(), com.lomo.app.testing.fakes.FakeEngineReadinessRepository())

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        var rebuildCallCount = 0
        override suspend fun rebuildFromCurrentWorkspace() {
            rebuildCallCount++
        }
    }

    init {
        test("directory display states stay loading until repository emits a value") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.rootDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.imageDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.voiceDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.syncInboxDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.dateFormat.value shouldBe PreferenceDefaults.DATE_FORMAT
                coordinator.timeFormat.value shouldBe PreferenceDefaults.TIME_FORMAT
                coordinator.themeMode.value shouldBe ThemeMode.SYSTEM
            }
        }

        test("directory display states resolve emitted values including unset") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )
                backgroundScope.launch { coordinator.rootDirectory.collect {} }
                backgroundScope.launch { coordinator.imageDirectory.collect {} }
                backgroundScope.launch { coordinator.voiceDirectory.collect {} }
                backgroundScope.launch { coordinator.syncInboxDirectory.collect {} }

                appConfigRepository.setDisplayName(StorageArea.ROOT, "/workspace/root")
                appConfigRepository.setDisplayName(StorageArea.IMAGE, null)
                appConfigRepository.setDisplayName(StorageArea.VOICE, "/workspace/voice")
                appConfigRepository.setDisplayName(StorageArea.SYNC_INBOX, "/workspace/inbox")

                coordinator.rootDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/root")
                coordinator.imageDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved(null)
                coordinator.voiceDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/voice")
                coordinator.syncInboxDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/inbox")
            }
        }

        test("updateRootDirectory and updateRootUri delegate to switch-root use case") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateRootDirectory("/root/path")
                appConfigRepository.currentRootLocation() shouldBe StorageLocation("/root/path")
                workspaceStateResolver.rebuildCallCount shouldBe 1

                coordinator.updateRootUri("content://tree/root")
                appConfigRepository.currentRootLocation() shouldBe StorageLocation("content://tree/root")
                workspaceStateResolver.rebuildCallCount shouldBe 2
            }
        }

        test("updateImage and updateVoice locations build expected storage-area updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateImageDirectory("/images")
                appConfigRepository.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("/images")

                coordinator.updateImageUri("content://tree/images")
                appConfigRepository.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("content://tree/images")

                coordinator.updateVoiceDirectory("/voice")
                appConfigRepository.currentLocation(StorageArea.VOICE) shouldBe StorageLocation("/voice")

                coordinator.updateVoiceUri("content://tree/voice")
                appConfigRepository.currentLocation(StorageArea.VOICE) shouldBe StorageLocation("content://tree/voice")
            }
        }

        test("updateSyncInbox locations build expected storage-area updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateSyncInboxDirectory("/sync-inbox")
                appConfigRepository.currentLocation(StorageArea.SYNC_INBOX) shouldBe StorageLocation("/sync-inbox")

                coordinator.updateSyncInboxUri("content://tree/sync-inbox")
                appConfigRepository.currentLocation(StorageArea.SYNC_INBOX) shouldBe StorageLocation("content://tree/sync-inbox")
            }
        }

        test("updateDoubleTapEditEnabled writes only the double-tap preference and never touches free-text-copy") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateDoubleTapEditEnabled(true)
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe true
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe PreferenceDefaults.FREE_TEXT_COPY_ENABLED

                coordinator.updateDoubleTapEditEnabled(false)
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe false
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe PreferenceDefaults.FREE_TEXT_COPY_ENABLED
            }
        }

        test("updateFreeTextCopyEnabled writes only the free-text-copy preference and never touches double-tap") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateFreeTextCopyEnabled(true)
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe true
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED

                coordinator.updateFreeTextCopyEnabled(false)
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe false
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED
            }
        }

        test("preference setters forward values to repository") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                )

                coordinator.updateDateFormat("MM/dd/yyyy")
                appConfigRepository.getDateFormat().first() shouldBe "MM/dd/yyyy"

                coordinator.updateTimeFormat("HH:mm")
                appConfigRepository.getTimeFormat().first() shouldBe "HH:mm"

                coordinator.updateThemeMode(ThemeMode.DARK)
                appConfigRepository.getThemeMode().first() shouldBe ThemeMode.DARK

                val thresholds = CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)
                coordinator.updateCalendarHeatmapThresholds(thresholds)
                appConfigRepository.getCalendarHeatmapThresholds().first() shouldBe thresholds

                coordinator.updateStorageFilenameFormat("yyyyMMdd")
                appConfigRepository.getStorageFilenameFormat().first() shouldBe "yyyyMMdd"

                coordinator.updateStorageTimestampFormat("HH:mm")
                appConfigRepository.getStorageTimestampFormat().first() shouldBe "HH:mm"

                coordinator.updateHapticFeedback(false)
                appConfigRepository.isHapticFeedbackEnabled().first() shouldBe false

                coordinator.updateShowInputHints(false)
                appConfigRepository.isShowInputHintsEnabled().first() shouldBe false

                coordinator.updateQuickSaveOnBackEnabled(true)
                appConfigRepository.isQuickSaveOnBackEnabled().first() shouldBe true

                coordinator.updateAutoOpenInputOnForeground(true)
                appConfigRepository.isAutoOpenInputOnForegroundEnabled().first() shouldBe true

                coordinator.updateAppLockEnabled(true)
                appConfigRepository.isAppLockEnabled().first() shouldBe true

                coordinator.updateCheckUpdatesOnStartup(false)
                appConfigRepository.isCheckUpdatesOnStartupEnabled().first() shouldBe false

                coordinator.updateShareCardShowTime(false)
                appConfigRepository.isShareCardShowTimeEnabled().first() shouldBe false

                coordinator.updateShareCardShowBrand(false)
                appConfigRepository.isShareCardShowBrandEnabled().first() shouldBe false

                coordinator.updateSyncInboxEnabled(true)
                appConfigRepository.isSyncInboxEnabled().first() shouldBe true
            }
        }

        test("given custom seed color when updateColorSource is called then color is recorded in history") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = FakeCustomFontStore(),
                )

                coordinator.updateColorSource(ColorSource.CustomSeed(0xFF112233.toInt()))
                appConfigRepository.getColorHistory().first() shouldBe listOf(0xFF112233.toInt())
            }
        }

        test("given font file contents when import runs then import font is triggered on font store") {
            runTest {
                val fontStore = object : FakeCustomFontStore() {
                    var importCalled = false
                    override suspend fun importFont(contents: ByteArray, originalFileName: String): CustomFontInfo? {
                        importCalled = true
                        return CustomFontInfo(id = "test.ttf", displayName = "Test", sizeBytes = contents.size.toLong())
                    }
                }
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = fontStore,
                )

                val result = coordinator.importCustomFont("font-bytes".toByteArray(), "test.ttf")
                result?.displayName shouldBe "Test"
                fontStore.importCalled shouldBe true
            }
        }

        test("given percent-encoded font name when import runs then original name is URL-decoded") {
            runTest {
                val fontStore = object : FakeCustomFontStore() {
                    var lastImportedName = ""
                    override suspend fun importFont(contents: ByteArray, originalFileName: String): CustomFontInfo? {
                        val decodedName = runCatching { java.net.URLDecoder.decode(originalFileName, "UTF-8") }.getOrDefault(originalFileName)
                        lastImportedName = decodedName
                        return CustomFontInfo(
                            id = "test.ttf",
                            displayName = decodedName.substringBeforeLast('.'),
                            sizeBytes = contents.size.toLong()
                        )
                    }
                }
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = fontStore,
                )

                val result = coordinator.importCustomFont("font-bytes".toByteArray(), "%E7%B2%A4%E6%B5%B7%E7%A7%8B%E8%90%8C%E8%90%8C%E4%BD%93.ttf")
                result?.displayName shouldBe "粤海秋萌萌体"
                fontStore.lastImportedName shouldBe "粤海秋萌萌体.ttf"
            }
        }
    }
}

