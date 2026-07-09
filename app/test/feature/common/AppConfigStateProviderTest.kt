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
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppConfigStateProviderTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("root directory shares a single upstream subscription across collectors") {
            runTest {
                val upstreamCollectors = AtomicInteger(0)
                val rootDirectoryUpstream =
                    MutableSharedFlow<StorageLocation?>(replay = 1).apply {
                        tryEmit(StorageLocation("/root/one"))
                    }
                val appConfigRepository =
                    object : FakeAppConfigRepository() {
                        override fun observeLocation(area: StorageArea) =
                            when (area) {
                                StorageArea.ROOT ->
                                    rootDirectoryUpstream.onSubscription { upstreamCollectors.incrementAndGet() }
                                else -> super.observeLocation(area)
                            }
                    }

                val provider =
                    AppConfigStateProvider(
                        appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                        appPreferencesSnapshotRepository = appConfigRepository,
                        customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                        appScope = backgroundScope,
                    )

                turbineScope {
                    val firstCollector = provider.rootDirectory.testIn(backgroundScope)
                    (firstCollector.awaitItem()) shouldBe null
                    (firstCollector.awaitItem()) shouldBe ("/root/one")

                    val secondCollector = provider.rootDirectory.testIn(backgroundScope)
                    (secondCollector.awaitItem()) shouldBe ("/root/one")
                    (upstreamCollectors.get()) shouldBe (1)

                    rootDirectoryUpstream.emit(StorageLocation("/root/two"))
                    (firstCollector.awaitItem()) shouldBe ("/root/two")
                    (secondCollector.awaitItem()) shouldBe ("/root/two")

                    firstCollector.cancel()
                    secondCollector.cancel()
                }
            }
        }
    }
}
