/*
 * Test Contract:
 * - Unit under test: AppConfigStateProviderTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for AppConfigStateProviderTest.
 * - Boundary: boundary and edge cases for AppConfigStateProviderTest.
 * - Failure: failure and error scenarios for AppConfigStateProviderTest.
 * - Must-not-happen: invariants are never violated for AppConfigStateProviderTest.
 *
 * - Behavior focus: test behavioral outcomes of AppConfigStateProviderTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
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
                        appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository, com.lomo.app.testing.fakes.FakeCustomFontStore()),
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
