/*
 * Behavior Contract:
 * - Unit under test: WidgetLaunchTarget resolution and tap-time intent minting helpers.
 * - Owning layer: app
 * - Priority tier: P0
 * - Capability: classify home-screen widget trampoline intents and mint fresh single-use
 *   TrustedLaunchIntents / MainActivity destination intents only at tap time, eliminating
 *   composition-time PendingIntent expiry and tombstone collisions.
 *
 * Scenarios:
 * - Given each WidgetLaunchTarget encoded in a trampoline intent, when parsed, then the exact
 *   typed target (CreateMemo, StartRecording, Search, Statistics, DailyReview, OpenMemo) resolves.
 * - Given a missing or invalid trampoline extra, when parsed, then DefaultMain is returned safely.
 * - Given distinct WidgetLaunchTarget values, when building trampoline route keys, then every
 *   action and memo identity maps to a unique PendingIntent filter key.
 * - Given CreateMemo or StartRecording target, when resolving final launch intent, then a fresh
 *   trusted factory lambda runs at tap time.
 *
 * Observable outcomes: WidgetLaunchTarget variant, route key uniqueness, selected Intent.
 * TDD proof:
 * - RED before WidgetLaunchActivity / WidgetLaunchTarget exists.
 * Excludes: Android Activity lifecycle and Keystore crypto execution.
 */
package com.lomo.app.widget

import android.content.Intent
import com.lomo.app.WidgetDestination
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class WidgetLaunchPolicyTest : AppFunSpec() {
    init {
        test("distinct widget launch targets produce distinct PendingIntent route keys") {
            val keys =
                listOf(
                    WidgetLaunchTarget.CreateMemo,
                    WidgetLaunchTarget.StartRecording,
                    WidgetLaunchTarget.OpenDestination(WidgetDestination.SEARCH),
                    WidgetLaunchTarget.OpenDestination(WidgetDestination.STATISTICS),
                    WidgetLaunchTarget.OpenDestination(WidgetDestination.DAILY_REVIEW),
                    WidgetLaunchTarget.OpenMemo("memo-1"),
                    WidgetLaunchTarget.OpenMemo("memo-2"),
                ).map { it.routeKey }

            keys.distinct().size shouldBe keys.size
        }

        test("resolveWidgetLaunchTarget parses action kinds and degrades invalid input to DefaultMain") {
            val createIntent = FakeLaunchIntent(kind = WIDGET_TARGET_CREATE_MEMO)
            resolveWidgetLaunchTarget(createIntent) shouldBe WidgetLaunchTarget.CreateMemo

            val recordIntent = FakeLaunchIntent(kind = WIDGET_TARGET_START_RECORDING)
            resolveWidgetLaunchTarget(recordIntent) shouldBe WidgetLaunchTarget.StartRecording

            val searchIntent =
                FakeLaunchIntent(
                    kind = WIDGET_TARGET_OPEN_DESTINATION,
                    destination = WidgetDestination.SEARCH.name,
                )
            resolveWidgetLaunchTarget(searchIntent) shouldBe
                WidgetLaunchTarget.OpenDestination(WidgetDestination.SEARCH)

            val memoIntent =
                FakeLaunchIntent(
                    kind = WIDGET_TARGET_OPEN_MEMO,
                    memoId = "memo-42",
                )
            resolveWidgetLaunchTarget(memoIntent) shouldBe WidgetLaunchTarget.OpenMemo("memo-42")

            resolveWidgetLaunchTarget(null) shouldBe WidgetLaunchTarget.DefaultMain
            resolveWidgetLaunchTarget(FakeLaunchIntent(kind = "unknown")) shouldBe
                WidgetLaunchTarget.DefaultMain
            resolveWidgetLaunchTarget(FakeLaunchIntent(kind = WIDGET_TARGET_OPEN_MEMO, memoId = " ")) shouldBe
                WidgetLaunchTarget.DefaultMain
        }

        test("buildWidgetTargetMainIntent mints fresh intents per target at tap time") {
            var createCalls = 0
            var recordCalls = 0
            val createResult = FakeLaunchIntent(kind = "fresh-create")
            val recordResult = FakeLaunchIntent(kind = "fresh-record")
            val destResult = FakeLaunchIntent(kind = "dest")
            val memoResult = FakeLaunchIntent(kind = "memo")
            val defaultResult = FakeLaunchIntent(kind = "default")

            val first =
                buildWidgetTargetMainIntent(
                    target = WidgetLaunchTarget.CreateMemo,
                    createMemo = {
                        createCalls += 1
                        createResult
                    },
                    startRecording = { recordResult },
                    openDestination = { destResult },
                    openMemo = { memoResult },
                    defaultMain = { defaultResult },
                )
            val second =
                buildWidgetTargetMainIntent(
                    target = WidgetLaunchTarget.CreateMemo,
                    createMemo = {
                        createCalls += 1
                        createResult
                    },
                    startRecording = { recordResult },
                    openDestination = { destResult },
                    openMemo = { memoResult },
                    defaultMain = { defaultResult },
                )
            val rec =
                buildWidgetTargetMainIntent(
                    target = WidgetLaunchTarget.StartRecording,
                    createMemo = { createResult },
                    startRecording = {
                        recordCalls += 1
                        recordResult
                    },
                    openDestination = { destResult },
                    openMemo = { memoResult },
                    defaultMain = { defaultResult },
                )

            first shouldBe createResult
            second shouldBe createResult
            createCalls shouldBe 2
            rec shouldBe recordResult
            recordCalls shouldBe 1
        }
    }

    private class FakeLaunchIntent(
        private val kind: String? = null,
        private val destination: String? = null,
        private val memoId: String? = null,
    ) : Intent() {
        override fun getStringExtra(name: String): String? = when (name) {
            EXTRA_WIDGET_LAUNCH_KIND -> kind
            EXTRA_WIDGET_LAUNCH_DESTINATION -> destination
            EXTRA_WIDGET_LAUNCH_MEMO_ID -> memoId
            else -> null
        }
    }
}
