package com.lomo.app

import android.content.ComponentCallbacks2
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.repository.AppBackgroundWorkRepository
import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: AppShutdownCoordinator
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: production-reachable Android memory lifecycle callbacks close app-scoped update HTTP transport through a domain-visible lifecycle contract.
 *
 * Scenarios:
 * - Given application shutdown starts, when app resources are closed, then the update transport lifecycle is closed once.
 * - Given application shutdown starts, when app resources are closed, then app background work is cancelled once.
 * - Given Android reports TRIM_MEMORY_UI_HIDDEN, when app trim memory is handled, then the update transport lifecycle is closed once.
 * - Given Android reports foreground memory pressure below UI-hidden, when app trim memory is handled, then the update transport lifecycle remains open.
 * - Given Android reports low memory, when app low memory is handled, then the update transport lifecycle is closed once.
 * - Given update transport close fails, when app resources are closed, then the failure is observable and shutdown continues.
 *
 * Observable outcomes:
 * - Fake lifecycle close count and captured close failures.
 *
 * TDD proof:
 * - Fails before the fix because the coordinator exposes only a shutdown/onTerminate close path and has no trim-memory or low-memory handler callable by production Android callbacks.
 *
 * Excludes:
 * - Ktor/OkHttp internals, release update download behavior, and Android framework dispatch internals beyond the ComponentCallbacks2 memory levels.
 */
class AppShutdownCoordinatorTest : AppFunSpec() {
    init {
        test("given app shutdown when resources close then update transport lifecycle is closed once") {
            val transportLifecycle = FakeAppUpdateTransportLifecycleRepository()
            val backgroundWork = FakeAppBackgroundWorkRepository()
            val coordinator = AppShutdownCoordinator(transportLifecycle, backgroundWork)

            coordinator.closeAppResources()

            transportLifecycle.closeCount shouldBe 1
            backgroundWork.cancelCount shouldBe 1
        }

        test("given ui hidden trim memory when app lifecycle is handled then update transport lifecycle is closed once") {
            val transportLifecycle = FakeAppUpdateTransportLifecycleRepository()
            val coordinator = AppShutdownCoordinator(transportLifecycle, FakeAppBackgroundWorkRepository())

            coordinator.closeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

            transportLifecycle.closeCount shouldBe 1
        }

        test("given foreground trim memory when app lifecycle is handled then update transport lifecycle remains open") {
            val transportLifecycle = FakeAppUpdateTransportLifecycleRepository()
            val coordinator = AppShutdownCoordinator(transportLifecycle, FakeAppBackgroundWorkRepository())

            coordinator.closeForTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN - 1)

            transportLifecycle.closeCount shouldBe 0
        }

        test("given low memory callback when app lifecycle is handled then update transport lifecycle is closed once") {
            val transportLifecycle = FakeAppUpdateTransportLifecycleRepository()
            val coordinator = AppShutdownCoordinator(transportLifecycle, FakeAppBackgroundWorkRepository())

            coordinator.closeForLowMemory()

            transportLifecycle.closeCount shouldBe 1
        }

        test("given transport close fails when resources close then failure is reported and shutdown continues") {
            val closeError = IllegalStateException("close failed")
            val transportLifecycle = FakeAppUpdateTransportLifecycleRepository(closeError = closeError)
            val coordinator = AppShutdownCoordinator(transportLifecycle, FakeAppBackgroundWorkRepository())
            val failures = mutableListOf<Throwable>()

            coordinator.closeAppResources(onCloseFailure = failures::add)

            transportLifecycle.closeCount shouldBe 1
            failures.single() shouldBe closeError
        }
    }
}

private class FakeAppBackgroundWorkRepository : AppBackgroundWorkRepository {
    var cancelCount: Int = 0
        private set

    override fun cancelAppBackgroundWork() {
        cancelCount += 1
    }
}

private class FakeAppUpdateTransportLifecycleRepository(
    private val closeError: Throwable? = null,
) : AppUpdateTransportLifecycleRepository {
    var closeCount: Int = 0
        private set

    override fun closeUpdateTransport() {
        closeCount += 1
        closeError?.let { throw it }
    }
}
