package com.lomo.app.widget

/*
 * Behavior Contract:
 * - Unit under test: Widget receivers, LomoProjectionWidgetDispatcher helpers, and
 *   AppWidgetRepository / WidgetUpdater dispatch path
 * - Owning layer: app
 * - Priority tier: P0
 * - Production scope: verifies receiver class hierarchy, AppWidgetId validation, bound widget
 *   filtering, dispatcher broadcast routing, and explicit package-scoped broadcast dispatch across
 *   the main/:widget process boundary (paired with live emulator verification).
 * - Capability: Lomo widget updates run cleanly across processes without GlanceAppWidgetReceiver's
 *   single-process SessionWorker or GlanceAppWidgetManager DataStore contention.
 *
 * Scenarios:
 * - Given all four Lomo widget receivers, when inspecting their class hierarchy, then each extends
 *   AppWidgetProvider and none inherit GlanceAppWidgetReceiver; LomoProjectionWidgetDispatcher is a
 *   plain collaborator rather than a BroadcastReceiver subclass.
 * - Given candidate widget IDs containing invalid or unbound IDs, when filterBoundWidgetIds and
 *   resolveAppWidgetId inspect them, then invalid, duplicate, and unbound IDs are rejected.
 * - Given LomoProjectionWidgetDispatcher.onReceive receives ACTION_WIDGET_PROJECTION_UPDATED with
 *   no bound IDs, when invoked, then it queries AppWidgetManager without starting a session or
 *   invoking fallbackOnReceive.
 * - Given only a subset of Lomo widget providers have bound IDs, when
 *   AppWidgetRepository.updateAllWidgets() runs, then explicit package-scoped
 *   ACTION_WIDGET_PROJECTION_UPDATED broadcasts are sent solely for bound providers.
 * - Given a review cycle action for a bound vs unbound widget ID, when
 *   WidgetUpdater.updateReviewWidget runs, then it broadcasts only when that ID is bound to
 *   LomoReviewWidgetReceiver.
 * Observable outcomes: validated widget IDs and explicit broadcasts restricted to bound providers.
 * TDD proof: original RED execution is not recorded in this snapshot; verify the existing scenarios
 * with `./kotlin test --include-module=app --include-classes='com.lomo.app.widget.WidgetProjectionDispatchTest'`.
 * Excludes: actual launcher rendering, OS process scheduling, and Glance worker execution.
 */

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.usecase.DispatcherProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.EqMatcher
import io.mockk.OfTypeMatcher
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class WidgetProjectionDispatchTest : AppFunSpec() {
    init {
        afterEach {
            unmockkStatic(AppWidgetManager::class)
            unmockkConstructor(ComponentName::class)
            unmockkConstructor(Intent::class)
        }

        test("all Lomo widget receivers extend AppWidgetProvider without GlanceAppWidgetReceiver") {
            val receivers =
                listOf(
                    LomoWidgetReceiver::class.java,
                    LomoTasksWidgetReceiver::class.java,
                    LomoInsightsWidgetReceiver::class.java,
                    LomoReviewWidgetReceiver::class.java,
                )

            BroadcastReceiver::class.java.isAssignableFrom(
                LomoProjectionWidgetDispatcher::class.java,
            ) shouldBe false
            receivers.forEach { receiverClass ->
                AppWidgetProvider::class.java.isAssignableFrom(receiverClass) shouldBe true
                GlanceAppWidgetReceiver::class.java.isAssignableFrom(receiverClass) shouldBe false
            }
        }

        test("LomoProjectionWidgetDispatcher handles projection update broadcast without goAsync when unbound") {
            mockkStatic(AppWidgetManager::class)
            mockkConstructor(ComponentName::class)
            val context = mockk<Context>()
            val appWidgetManager = mockk<AppWidgetManager>()
            val receiver = mockk<BroadcastReceiver>()
            val intent = mockk<Intent>()
            var fallbackCalls = 0

            every { context.applicationContext } returns context
            every { AppWidgetManager.getInstance(context) } returns appWidgetManager
            every { appWidgetManager.getAppWidgetIds(any()) } returns intArrayOf()
            every { intent.action } returns ACTION_WIDGET_PROJECTION_UPDATED

            LomoProjectionWidgetDispatcher(
                receiverClass = LomoWidgetReceiver::class.java,
                glanceAppWidget = mockk<GlanceAppWidget>(),
            ).onReceive(
                receiver = receiver,
                context = context,
                intent = intent,
                fallbackOnReceive = { fallbackCalls += 1 },
            )

            fallbackCalls shouldBe 0
            verify(exactly = 1) { appWidgetManager.getAppWidgetIds(any()) }
            verify(exactly = 0) { receiver.goAsync() }
        }

        test("resolveAppWidgetId and filterBoundWidgetIds reject invalid, non-AppWidget, and unbound IDs") {
            resolveAppWidgetId(AppWidgetId(42)) shouldBe 42
            resolveAppWidgetId(AppWidgetId(AppWidgetManager.INVALID_APPWIDGET_ID)) shouldBe null
            resolveAppWidgetId(object : GlanceId {}) shouldBe null

            val filtered =
                filterBoundWidgetIds(
                    candidateIds = intArrayOf(AppWidgetManager.INVALID_APPWIDGET_ID, 3, 7, 3, 9),
                    hasBoundInfo = { id -> id == 3 || id == 7 },
                )
            filtered.shouldContainExactly(3, 7)
        }

        test("AppWidgetRepository queries bound AppWidgetManager IDs and skips broadcasts when none bound") {
            runTest {
                mockkStatic(AppWidgetManager::class)
                mockkConstructor(ComponentName::class)
                val context = mockk<Context>()
                val appWidgetManager = mockk<AppWidgetManager>()
                every { context.applicationContext } returns context
                every { context.packageName } returns "com.lomo.app"
                every { AppWidgetManager.getInstance(context) } returns appWidgetManager
                every { appWidgetManager.getAppWidgetIds(any()) } returns intArrayOf()
                every { context.sendBroadcast(any()) } just runs

                AppWidgetRepository(context).updateAllWidgets()

                verify(exactly = 0) { context.sendBroadcast(any()) }
            }
        }

        test("AppWidgetRepository sends explicit package broadcast only for bound widget providers") {
            runTest {
                mockkStatic(AppWidgetManager::class)
                mockkConstructor(ComponentName::class)
                mockkConstructor(Intent::class)
                val context = mockk<Context>()
                val appWidgetManager = mockk<AppWidgetManager>()
                stubReceiverComponents()

                val sentTargets = mutableListOf<String>()
                every {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setPackage(any())
                } answers { self as Intent }
                every {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setComponent(any())
                } answers {
                    val comp = firstArg<ComponentName>()
                    sentTargets += comp.className
                    self as Intent
                }
                every { context.applicationContext } returns context
                every { context.packageName } returns "com.lomo.app"
                every { AppWidgetManager.getInstance(context) } returns appWidgetManager
                every { appWidgetManager.getAppWidgetIds(any()) } answers {
                    when (firstArg<ComponentName>().className) {
                        LomoTasksWidgetReceiver::class.java.name,
                        LomoReviewWidgetReceiver::class.java.name,
                        -> intArrayOf(3)

                        else -> intArrayOf()
                    }
                }
                every { context.sendBroadcast(any()) } just runs

                AppWidgetRepository(context).updateAllWidgets()

                sentTargets.shouldContainExactly(
                    LomoTasksWidgetReceiver::class.java.name,
                    LomoReviewWidgetReceiver::class.java.name,
                )
                verify(exactly = 2) {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED))
                        .setPackage("com.lomo.app")
                }
                verify(exactly = 2) { context.sendBroadcast(any()) }
            }
        }

        test("WidgetUpdater.updateReviewWidget broadcasts only when requested appWidgetId is bound") {
            runTest {
                mockkStatic(AppWidgetManager::class)
                mockkConstructor(ComponentName::class)
                mockkConstructor(Intent::class)
                val dispatcher = StandardTestDispatcher(testScheduler)
                val dispatchers =
                    object : DispatcherProvider {
                        override val main = dispatcher
                        override val io = dispatcher
                        override val default = dispatcher
                        override val unconfined = dispatcher
                    }
                val context = mockk<Context>()
                val appWidgetManager = mockk<AppWidgetManager>()
                stubReceiverComponents()
                every {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setPackage(any())
                } answers { self as Intent }
                every {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setComponent(any())
                } answers { self as Intent }
                every { context.applicationContext } returns context
                every { context.packageName } returns "com.lomo.app"
                every { AppWidgetManager.getInstance(context) } returns appWidgetManager
                every { appWidgetManager.getAppWidgetIds(any()) } returns intArrayOf(9)
                every { context.sendBroadcast(any()) } just runs

                WidgetUpdater.updateReviewWidget(context, appWidgetId = 5, dispatcherProvider = dispatchers)
                testScheduler.advanceUntilIdle()
                verify(exactly = 0) { context.sendBroadcast(any()) }

                WidgetUpdater.updateReviewWidget(context, appWidgetId = 9, dispatcherProvider = dispatchers)
                testScheduler.advanceUntilIdle()
                verify(exactly = 1) { context.sendBroadcast(any()) }
            }
        }
    }
}

private fun stubReceiverComponents() {
    listOf(
        LomoWidgetReceiver::class.java,
        LomoTasksWidgetReceiver::class.java,
        LomoInsightsWidgetReceiver::class.java,
        LomoReviewWidgetReceiver::class.java,
    ).forEach { receiverClass ->
        every {
            constructedWith<ComponentName>(
                OfTypeMatcher<Context>(Context::class),
                EqMatcher(receiverClass),
            ).className
        } returns receiverClass.name
    }
}
