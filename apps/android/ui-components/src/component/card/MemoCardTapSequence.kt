package com.lomo.ui.component.card

/** The first release acts immediately; the second release edits instead of toggling again. */
internal class MemoCardTapSequence(private val doubleTapMinMillis: Long, private val doubleTapTimeoutMillis: Long) {
    private var firstReleaseMillis: Long? = null

    init {
        require(doubleTapMinMillis >= 0 && doubleTapTimeoutMillis >= doubleTapMinMillis)
    }

    fun release(nowMillis: Long): MemoCardTap {
        val firstRelease = firstReleaseMillis
        if (firstRelease != null) {
            require(nowMillis >= firstRelease) { "Memo tap timestamps must be monotonic" }
            if (nowMillis - firstRelease in doubleTapMinMillis..doubleTapTimeoutMillis) {
                reset()
                return MemoCardTap.Edit
            }
        }
        firstReleaseMillis = nowMillis
        return MemoCardTap.Click
    }

    fun reset() {
        firstReleaseMillis = null
    }
}

internal enum class MemoCardTap { Click, Edit }
