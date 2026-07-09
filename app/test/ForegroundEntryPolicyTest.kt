package com.lomo.app

import androidx.lifecycle.Lifecycle
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: ForegroundEntryPolicy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: converts Activity resume lifecycle edges into monotonic foreground entry ids.
 *
 * Scenarios:
 * - Given the Activity is already resumed when observation starts, when state is initialized, then
 *   the first foreground entry is already available for cold-start UI commands.
 * - Given the Activity observer receives the lifecycle's synchronized initial resume event, when
 *   the app is already resumed, then the cold-start entry is not counted twice.
 * - Given the Activity is paused and later resumed, when the resume event arrives, then the next
 *   foreground entry id is emitted.
 * - Given non-resume lifecycle events arrive, when state is updated, then the entry id is stable.
 *
 * Observable outcomes:
 * - ForegroundEntryPolicy.State entryId and ignoreNextResume values.
 *
 * TDD proof:
 * - RED: before foreground entry generation was modeled as Activity resume state, release builds
 *   could rely on ProcessLifecycleOwner ON_START and miss later foreground returns after cold start.
 *
 * Excludes:
 * - Compose recomposition, ProcessLifecycleOwner internals, keyboard rendering, and editor effects.
 */
class ForegroundEntryPolicyTest : AppFunSpec() {
    init {
        test("given activity already resumed when initialized then cold start entry is available once") {
            val initialState = ForegroundEntryPolicy.initialState(Lifecycle.State.RESUMED)
            val afterSynchronizedResume =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = initialState,
                    event = Lifecycle.Event.ON_RESUME,
                )

            initialState shouldBe ForegroundEntryPolicy.State(entryId = 1L, ignoreNextResume = true)
            afterSynchronizedResume shouldBe ForegroundEntryPolicy.State(entryId = 1L, ignoreNextResume = false)
        }

        test("given activity is not resumed when initialized then first resume emits first entry") {
            val state =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = ForegroundEntryPolicy.initialState(Lifecycle.State.STARTED),
                    event = Lifecycle.Event.ON_RESUME,
                )

            state shouldBe ForegroundEntryPolicy.State(entryId = 1L, ignoreNextResume = false)
        }

        test("given activity paused after cold start when resumed again then next foreground entry is emitted") {
            val resumedState =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = ForegroundEntryPolicy.initialState(Lifecycle.State.RESUMED),
                    event = Lifecycle.Event.ON_RESUME,
                )
            val pausedState =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = resumedState,
                    event = Lifecycle.Event.ON_PAUSE,
                )
            val foregroundState =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = pausedState,
                    event = Lifecycle.Event.ON_RESUME,
                )

            foregroundState shouldBe ForegroundEntryPolicy.State(entryId = 2L, ignoreNextResume = false)
        }

        test("given non resume events when state updates then foreground entry id stays stable") {
            val initialState = ForegroundEntryPolicy.initialState(Lifecycle.State.CREATED)
            val startedState =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = initialState,
                    event = Lifecycle.Event.ON_START,
                )
            val stoppedState =
                ForegroundEntryPolicy.applyLifecycleEvent(
                    state = startedState,
                    event = Lifecycle.Event.ON_STOP,
                )

            stoppedState shouldBe ForegroundEntryPolicy.State(entryId = 0L, ignoreNextResume = false)
        }
    }
}
