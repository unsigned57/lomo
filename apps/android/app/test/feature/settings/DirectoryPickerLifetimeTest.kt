package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.WorkspaceMount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/*
 * Behavior Contract:
 * Capability: picker callbacks admit every directory selection before navigation; owner: app; priority: P1.
 * Scenarios:
 * - Given notes, image or voice selection, when settings closes before dispatch, then the selected URI commits.
 * Observable outcomes: the operation finishes under its transition owner after the screen scope closes.
 * TDD proof: target/lomo/reports/manual/directory-selection/app-red.log and verification-green.log.
 * Excludes: provider validation and durable journal behavior, covered in domain/data specifications.
 */
class DirectoryPickerLifetimeTest : AppFunSpec() {
    init {
        for (area in listOf("notes", "images", "voice")) {
            test("given $area selection when settings closes before dispatch then the admitted operation finishes") {
                runTest {
                    val coordinator = mockk<SettingsAppConfigCoordinator>()
                    val release = CompletableDeferred<Unit>()
                    val committed = CompletableDeferred<String>()
                    val operation: suspend (String) -> Unit = { uri ->
                        withContext(NonCancellable) {
                            release.await()
                            committed.complete(uri)
                        }
                    }
                    coEvery { coordinator.updateRootUri(any()) } coAnswers { operation(firstArg()) }
                    coEvery { coordinator.updateImageUri(any()) } coAnswers { operation(firstArg()) }
                    coEvery { coordinator.updateVoiceUri(any()) } coAnswers { operation(firstArg()) }
                    val screen = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
                    val model = SettingsStorageFeatureViewModel(
                        screen,
                        coordinator,
                        MutableStateFlow(WorkspaceMount.Opening),
                        { throw it },
                    )
                    val selected = "content://provider/tree/$area"
                    when (area) {
                        "notes" -> model.updateRootUri(selected)
                        "images" -> model.updateImageUri(selected)
                        "voice" -> model.updateVoiceUri(selected)
                    }
                    screen.cancel()
                    release.complete(Unit)
                    testScheduler.runCurrent()
                    committed.isCompleted shouldBe true
                    committed.await() shouldBe selected
                }
            }
        }
    }
}
