package com.lomo.domain.model

enum class QuickBallGesture {
    TAP,
    LONG_PRESS,
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

enum class QuickBallAction {
    NEW_MEMO,
    RECORD,
    LONG_TEXT,
    SCROLL_TOP,
    SEARCH,
    FILTER,
    CLEAR_FILTER,
    ACTION_MENU,
    DAILY_REVIEW,
    GALLERY,

    /** An explicit user choice to leave a gesture unassigned. */
    NONE,
}

/** Complete local UI preference. Tap always creates a memo; the other five bindings are editable. */
data class QuickBallConfig(
    val longPress: QuickBallAction,
    val up: QuickBallAction,
    val down: QuickBallAction,
    val left: QuickBallAction,
    val right: QuickBallAction,
) {
    fun action(gesture: QuickBallGesture): QuickBallAction = when (gesture) {
        QuickBallGesture.TAP -> QuickBallAction.NEW_MEMO
        QuickBallGesture.LONG_PRESS -> longPress
        QuickBallGesture.UP -> up
        QuickBallGesture.DOWN -> down
        QuickBallGesture.LEFT -> left
        QuickBallGesture.RIGHT -> right
    }

    fun withAction(gesture: QuickBallGesture, action: QuickBallAction): QuickBallConfig = when (gesture) {
        QuickBallGesture.TAP -> error("The quick-ball tap binding is fixed")
        QuickBallGesture.LONG_PRESS -> copy(longPress = action)
        QuickBallGesture.UP -> copy(up = action)
        QuickBallGesture.DOWN -> copy(down = action)
        QuickBallGesture.LEFT -> copy(left = action)
        QuickBallGesture.RIGHT -> copy(right = action)
    }

    val storageValue: String
        get() = "$STORAGE_PREFIX${longPress.name}|${up.name}|${down.name}|${left.name}|${right.name}"

    companion object {
        fun defaults(): QuickBallConfig = parse(PreferenceDefaults.QUICK_BALL_CONFIG)

        fun parse(value: String): QuickBallConfig {
            require(value.startsWith(STORAGE_PREFIX)) { "Invalid quick-ball configuration version" }
            val parts = value.removePrefix(STORAGE_PREFIX).split('|')
            require(parts.size == CONFIGURED_GESTURE_COUNT) { "Incomplete quick-ball configuration" }
            val actions = parts.map(QuickBallAction::valueOf).iterator()
            return QuickBallConfig(
                longPress = actions.next(),
                up = actions.next(),
                down = actions.next(),
                left = actions.next(),
                right = actions.next(),
            )
        }
    }
}

private const val STORAGE_PREFIX = "v1|"
private const val CONFIGURED_GESTURE_COUNT = 5
