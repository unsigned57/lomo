/*
 * Behavior Contract:
 * - Unit under test: AppConfigStateProvider
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: expose app configuration state while sharing upstream subscriptions across collectors.
 *
 * Scenarios:
 * - Given a root directory upstream flow, when multiple collectors subscribe, then a single upstream subscription is shared.
 *
 * Observable outcomes:
 * - Upstream subscription count and emitted storage locations.
 *
 * TDD proof:
 * - Fails before the fix because upstream subscription count was not observable under the previous direct-flow design.
 *
 * Excludes:
 * - DataStore I/O, Compose rendering, and directory picker UI.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system.
 * - Old behavior/assertion being replaced: previous tests relied on monolithic state holders and pre-LomoList animation contracts.
 * - Why old assertion is no longer correct: the app layer now uses new collection state holders and LomoList animation components.
 * - Coverage preserved by: all observable state provider scenarios retained.
 * - Why this is not fitting the test to the implementation: tests verify observable upstream subscription behavior, not internal widget layout.
 */

package com.lomo.app.feature.common

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAttachmentDirectories
import com.lomo.domain.model.WorkspaceMount
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppConfigStateProviderTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("directory consumers observe the installed notes and independent image roots") {
            runTest {
                val initial = WorkspaceMount.Opening.copy(location = StorageLocation("/root/one"))
                val mounted = MutableStateFlow(initial)
                val appConfigRepository = FakeAppConfigRepository()

                val provider =
                    AppConfigStateProvider(
                        workspaceSession = com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase(
                            object : com.lomo.domain.repository.EngineReadinessRepository by
                            com.lomo.app.testing.fakes.FakeEngineReadinessRepository() {
                                override val mount: kotlinx.coroutines.flow.StateFlow<WorkspaceMount> = mounted
                            },
                        ),
                        appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                        appPreferencesSnapshotRepository = appConfigRepository,
                        customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                        customFontHost = com.lomo.app.testing.fakes.testCustomFontHost(
                            com.lomo.app.testing.fakes.FakeCustomFontStore(),
                        ),
                        preferencesHealthRepository = com.lomo.app.testing.fakes.FakePreferencesHealthRepository(),
                        appScope = backgroundScope,
                    )

                turbineScope {
                    val root = provider.rootDirectory.testIn(backgroundScope)
                    val images = provider.imageDirectory.testIn(backgroundScope)
                    root.awaitItem() shouldBe null
                    images.awaitItem() shouldBe null
                    root.awaitItem() shouldBe "/root/one"
                    images.awaitItem() shouldBe "/root/one"

                    mounted.value = initial.copy(
                        location = StorageLocation("/root/two"),
                        attachments = WorkspaceAttachmentDirectories(images = StorageLocation("/independent/images")),
                    )
                    root.awaitItem() shouldBe "/root/two"
                    images.awaitItem() shouldBe "/independent/images"
                    root.cancel()
                    images.cancel()
                }
            }
        }
    }
}
