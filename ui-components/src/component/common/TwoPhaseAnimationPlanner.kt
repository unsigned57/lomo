package com.lomo.ui.component.common

/**
 * Direction of a two-phase lazy-list item animation.
 */
enum class TwoPhaseDirection { ENTER, EXIT }

/**
 * Which animated property a [TwoPhaseAnimationStep] drives. The phase modifier
 * maps this to the corresponding [androidx.compose.animation.core.Animatable].
 */
enum class TwoPhaseProperty { ALPHA, HEIGHT_FRACTION }

/**
 * A single step in a two-phase animation sequence.
 *
 * The planner owns only the ordering, target, and finality; the modifier resolves
 * the [androidx.compose.animation.core.FiniteAnimationSpec] from [LomoListItemMotionSpecs]
 * based on the property and direction. This keeps the planner free of Compose types
 * and fully testable in pure JVM.
 */
data class TwoPhaseAnimationStep(
    val property: TwoPhaseProperty,
    val targetValue: Float,
    val isFinal: Boolean,
)

/**
 * Plans the ordered steps for a two-phase enter or exit animation.
 *
 * Behavior contract:
 * - ENTER: HEIGHT_FRACTION 0→1 first (open the gap so neighbors shift), then ALPHA 0→1 (reveal).
 * - EXIT: ALPHA 1→0 first (fade), then HEIGHT_FRACTION 1→0 (collapse the gap).
 * - Exactly one step per plan is [TwoPhaseAnimationStep.isFinal]; settle fires after it.
 *
 * This model — not [androidx.compose.animation.AnimatedVisibility] — owns the phase
 * ordering so no SubcomposeLayout / reuse-group boundary is introduced inside a
 * LazyColumn item slot.
 *
 * The planner also owns the start value for each direction, so the
 * modifier can reset its [androidx.compose.animation.core.Animatable] to the correct
 * pre-animation state whenever an enter or exit is requested — even if the trigger
 * arrives after first composition (the scroll-to-top race where the item is already
 * composed and visible before `beginEnter` fires).
 */
object TwoPhaseAnimationPlanner {
    fun activeDirection(isEntering: Boolean, isExiting: Boolean): TwoPhaseDirection? = when {
        isExiting -> TwoPhaseDirection.EXIT
        isEntering -> TwoPhaseDirection.ENTER
        else -> null
    }

    fun plan(direction: TwoPhaseDirection): List<TwoPhaseAnimationStep> = when (direction) {
        TwoPhaseDirection.ENTER -> listOf(
            TwoPhaseAnimationStep(TwoPhaseProperty.HEIGHT_FRACTION, targetValue = 1f, isFinal = false),
            TwoPhaseAnimationStep(TwoPhaseProperty.ALPHA, targetValue = 1f, isFinal = true),
        )
        TwoPhaseDirection.EXIT -> listOf(
            TwoPhaseAnimationStep(TwoPhaseProperty.ALPHA, targetValue = 0f, isFinal = false),
            TwoPhaseAnimationStep(TwoPhaseProperty.HEIGHT_FRACTION, targetValue = 0f, isFinal = true),
        )
    }

    fun startValue(direction: TwoPhaseDirection): Float = when (direction) {
        TwoPhaseDirection.ENTER -> 0f
        TwoPhaseDirection.EXIT -> 1f
    }
}

/**
 * Owns the settle-once, rollback, and dispose-settle contract for a two-phase
 * lazy-list item animation.
 *
 * Behavior contract:
 * - Given a cycle starts, when [markStarted] is called, then settled is cleared.
 * - Given the final phase completes, when [markSettled] is called, then [onSettled]
 *   fires exactly once.
 * - Given [markSettled] already fired, when [markSettled] is called again, then
 *   [onSettled] does not fire.
 * - Given the animation is active and not settled, when [markDisposed] is called
 *   with isActive=true, then [onSettled] fires exactly once.
 * - Given the animation already settled, when [markDisposed] is called, then
 *   [onSettled] does not fire.
 * - Given a rollback is requested, when [markRollback] is called, then [onSettled]
 *   does not fire and [isSettled] is false.
 */
class TwoPhaseSettlePolicy {
    private var settled = false

    fun markStarted() {
        settled = false
    }

    fun markSettled(onSettled: () -> Unit) {
        if (!settled) {
            settled = true
            onSettled()
        }
    }

    fun markDisposed(isActive: Boolean, onSettled: () -> Unit) {
        if (isActive && !settled) {
            settled = true
            onSettled()
        }
    }

    fun markRollback() {
        settled = false
    }

    val isSettled: Boolean get() = settled
}
