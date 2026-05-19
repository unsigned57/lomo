package com.lomo.app.feature.settings

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and memo snapshot settings.
 * - Scenarios:
 *   - Given emitted memo snapshot preference values, coordinator state flows expose them correctly.
 *   - Given disabling memo snapshots, coordinator turns off snapshotting and clears rollback history.
 * - Observable outcomes:
 *   - Coordinator StateFlow values (memoSnapshotsEnabled, memoSnapshotMaxCount, memoSnapshotMaxAgeDays).
 *   - Backing repositories' state (snapshotsEnabled, clearAllSnapshotsCallCount).
 * - TDD proof: Ensures coordinator exposes state flow and forwards toggle/cleanup calls accurately.
 * - Excludes: DataStore persistence internals, Compose rendering, day-file snapshot UI.
 */
class SettingsAppConfigCoordinatorSnapshotTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver)
    private val memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository()
    private val memoVersionRepository = FakeMemoVersionRepository()

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    private class FakeMemoSnapshotPreferencesRepository : MemoSnapshotPreferencesRepository {
        val snapshotsEnabled = MutableStateFlow(false)
        val maxCount = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)
        val maxAgeDays = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

        override fun isMemoSnapshotsEnabled(): Flow<Boolean> = snapshotsEnabled.asStateFlow()
        override suspend fun setMemoSnapshotsEnabled(enabled: Boolean) {
            snapshotsEnabled.value = enabled
        }

        override fun getMemoSnapshotMaxCount(): Flow<Int> = maxCount.asStateFlow()
        override suspend fun setMemoSnapshotMaxCount(count: Int) {
            maxCount.value = count
        }

        override fun getMemoSnapshotMaxAgeDays(): Flow<Int> = maxAgeDays.asStateFlow()
        override suspend fun setMemoSnapshotMaxAgeDays(days: Int) {
            maxAgeDays.value = days
        }
    }

    private class FakeMemoVersionRepository : MemoVersionRepository {
        var clearAllSnapshotsCallCount = 0

        override suspend fun listMemoRevisions(
            memo: Memo,
            cursor: MemoRevisionCursor?,
            limit: Int,
        ): MemoRevisionPage {
            TODO("Not needed for coordinator test")
        }

        override suspend fun restoreMemoRevision(currentMemo: Memo, revisionId: String) {
            TODO("Not needed for coordinator test")
        }

        override suspend fun clearAllMemoSnapshots() {
            clearAllSnapshotsCallCount++
        }
    }

    init {
        test("memo snapshot flows expose repository values") {
            runTest {
                memoSnapshotPreferencesRepository.snapshotsEnabled.value = false
                memoSnapshotPreferencesRepository.maxCount.value = 50
                memoSnapshotPreferencesRepository.maxAgeDays.value = 90

                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    memoVersionRepository = memoVersionRepository,
                )

                coordinator.memoSnapshotsEnabled.first { it == false } shouldBe false
                coordinator.memoSnapshotMaxCount.first { it == 50 } shouldBe 50
                coordinator.memoSnapshotMaxAgeDays.first { it == 90 } shouldBe 90
            }
        }

        test("disabling memo snapshots turns off recording and clears rollback history") {
            runTest {
                memoSnapshotPreferencesRepository.snapshotsEnabled.value = true
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    memoVersionRepository = memoVersionRepository,
                )

                coordinator.updateMemoSnapshotsEnabled(false)

                memoSnapshotPreferencesRepository.snapshotsEnabled.value shouldBe false
                memoVersionRepository.clearAllSnapshotsCallCount shouldBe 1
            }
        }
    }
}
