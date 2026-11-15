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
 * Test Change Justification:
 * Reason category: behavior-preserving organization of Android boundary fixtures.
 * Old behavior/assertion being replaced: inline platform stubbing; scenario names and outcomes stay unchanged.
 * Why old assertion is no longer correct: existing outcomes remain correct; platform setup belongs
 * in a scenario fixture, and successful validation/broadcast delivery needs an explicit outcome assertion.
 * Coverage preserved by: the same selected-directory, validation, or widget routing scenarios,
 * with all former success and rejection expectations retained.
 * Why this is not fitting the test to the implementation: fixtures expose provider results or
 * recorded broadcast payloads; production code and the behavior contract are unchanged.
 * Regression preservation: initial CI verification passed app 952/data 855 tests before this
 * fixture cleanup. After cleanup, data passed 855 tests and the widget dispatch target passed
 * all six scenarios; the final CI gate rechecks the complete workspace.
 * Evidence: reports/manual/directory-tidy-2026-11-14/test-fixtures.stdout.log and widget-fixture.stdout.log.
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
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
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
            val receivers = listOf(
                LomoWidgetReceiver::class.java,
                LomoTasksWidgetReceiver::class.java,
                LomoInsightsWidgetReceiver::class.java,
                LomoReviewWidgetReceiver::class.java,
            )
            BroadcastReceiver::class.java.isAssignableFrom(LomoProjectionWidgetDispatcher::class.java) shouldBe false
            receivers.forEach { receiverClass ->
                AppWidgetProvider::class.java.isAssignableFrom(receiverClass) shouldBe true
                GlanceAppWidgetReceiver::class.java.isAssignableFrom(receiverClass) shouldBe false
            }
        }

        test("LomoProjectionWidgetDispatcher handles projection update broadcast without goAsync when unbound") {
            val fixture = WidgetDispatchFixture()
            val receiver = mockk<BroadcastReceiver>()
            val intent = mockk<Intent>()
            var fallbackCalls = 0
            every { intent.action } returns ACTION_WIDGET_PROJECTION_UPDATED

            LomoProjectionWidgetDispatcher(
                receiverClass = LomoWidgetReceiver::class.java,
                glanceAppWidget = mockk<GlanceAppWidget>(),
            ).onReceive(
                receiver = receiver,
                context = fixture.context,
                intent = intent,
                fallbackOnReceive = { fallbackCalls += 1 },
            )

            fallbackCalls shouldBe 0
            fixture.queriedReceivers shouldBe listOf(LomoWidgetReceiver::class.java.name)
            verify(exactly = 0) { receiver.goAsync() }
        }

        test("resolveAppWidgetId and filterBoundWidgetIds reject invalid, non-AppWidget, and unbound IDs") {
            resolveAppWidgetId(AppWidgetId(42)) shouldBe 42
            resolveAppWidgetId(AppWidgetId(AppWidgetManager.INVALID_APPWIDGET_ID)) shouldBe null
            resolveAppWidgetId(object : GlanceId {}) shouldBe null
            filterBoundWidgetIds(
                candidateIds = intArrayOf(AppWidgetManager.INVALID_APPWIDGET_ID, 3, 7, 3, 9),
                hasBoundInfo = { id -> id == 3 || id == 7 },
            ).shouldContainExactly(3, 7)
        }

        test("AppWidgetRepository queries bound AppWidgetManager IDs and skips broadcasts when none bound") {
            runTest {
                val fixture = WidgetDispatchFixture()
                AppWidgetRepository(fixture.context).updateAllWidgets()
                fixture.broadcasts shouldBe emptyList()
            }
        }

        test("AppWidgetRepository sends explicit package broadcast only for bound widget providers") {
            runTest {
                val fixture = WidgetDispatchFixture(
                    mapOf(
                        LomoTasksWidgetReceiver::class.java to intArrayOf(3),
                        LomoReviewWidgetReceiver::class.java to intArrayOf(3),
                    ),
                )
                AppWidgetRepository(fixture.context).updateAllWidgets()
                fixture.broadcasts.shouldContainExactly(
                    WidgetBroadcast(
                        ACTION_WIDGET_PROJECTION_UPDATED,
                        "com.lomo.app",
                        LomoTasksWidgetReceiver::class.java.name,
                    ),
                    WidgetBroadcast(
                        ACTION_WIDGET_PROJECTION_UPDATED,
                        "com.lomo.app",
                        LomoReviewWidgetReceiver::class.java.name,
                    ),
                )
                verify(exactly = 2) {
                    constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setPackage("com.lomo.app")
                }
            }
        }

        test("WidgetUpdater.updateReviewWidget broadcasts only when requested appWidgetId is bound") {
            runTest {
                val fixture = WidgetDispatchFixture(mapOf(LomoReviewWidgetReceiver::class.java to intArrayOf(9)))
                val dispatcher = StandardTestDispatcher(testScheduler)
                val dispatchers = object : DispatcherProvider {
                    override val main = dispatcher
                    override val io = dispatcher
                    override val default = dispatcher
                    override val unconfined = dispatcher
                }
                WidgetUpdater.updateReviewWidget(fixture.context, appWidgetId = 5, dispatcherProvider = dispatchers)
                testScheduler.advanceUntilIdle()
                fixture.broadcasts shouldBe emptyList()

                WidgetUpdater.updateReviewWidget(fixture.context, appWidgetId = 9, dispatcherProvider = dispatchers)
                testScheduler.advanceUntilIdle()
                fixture.broadcasts.shouldContainExactly(
                    WidgetBroadcast(
                        ACTION_WIDGET_PROJECTION_UPDATED,
                        "com.lomo.app",
                        LomoReviewWidgetReceiver::class.java.name,
                    ),
                )
            }
        }
    }
}

private data class WidgetBroadcast(val action: String, val packageName: String, val receiverClassName: String)

private class WidgetDispatchFixture(boundWidgets: Map<Class<out AppWidgetProvider>, IntArray> = emptyMap()) {
    val context = mockk<Context>()
    val queriedReceivers = mutableListOf<String>()
    val broadcasts = mutableListOf<WidgetBroadcast>()
    private val manager = mockk<AppWidgetManager>()
    private var packageName: String? = null
    private var component: ComponentName? = null

    init {
        val receivers = listOf(
            LomoWidgetReceiver::class.java,
            LomoTasksWidgetReceiver::class.java,
            LomoInsightsWidgetReceiver::class.java,
            LomoReviewWidgetReceiver::class.java,
        )
        require(boundWidgets.keys.all { it in receivers })
        val bindings = receivers.associate { it.name to (boundWidgets[it] ?: intArrayOf()) }
        mockkStatic(AppWidgetManager::class)
        mockkConstructor(ComponentName::class)
        mockkConstructor(Intent::class)
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.lomo.app"
        every { AppWidgetManager.getInstance(context) } returns manager
        receivers.forEach { receiverClass ->
            every {
                constructedWith<ComponentName>(
                    OfTypeMatcher<Context>(Context::class),
                    EqMatcher(receiverClass),
                ).className
            } returns receiverClass.name
        }
        every { manager.getAppWidgetIds(any()) } answers {
            val receiver = firstArg<ComponentName>().className
            queriedReceivers += receiver
            bindings.getValue(receiver)
        }
        every {
            constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setPackage(any())
        } answers {
            packageName = firstArg()
            self as Intent
        }
        every {
            constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).setComponent(any())
        } answers {
            component = firstArg()
            self as Intent
        }
        every { constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).action } returns
            ACTION_WIDGET_PROJECTION_UPDATED
        every { constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).`package` } answers { packageName }
        every { constructedWith<Intent>(EqMatcher(ACTION_WIDGET_PROJECTION_UPDATED)).component } answers { component }
        every { context.sendBroadcast(any()) } answers {
            val intent = firstArg<Intent>()
            broadcasts += WidgetBroadcast(
                checkNotNull(intent.action) { "Broadcast must carry an action" },
                checkNotNull(intent.`package`) { "Broadcast must target the app package" },
                checkNotNull(intent.component) { "Broadcast must target an explicit receiver" }.className,
            )
        }
    }
}
