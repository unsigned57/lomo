package com.lomo.app.feature.review

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DailyReviewScreen pager-page layout policy.
 * - Behavior focus: short random-review cards should be vertically centered while long cards remain scrollable.
 * - Observable outcomes: source uses BoxWithConstraints, applies a viewport minimum height to the scroll content, and centers child arrangement within that minimum-height column.
 * - Red phase: Fails before the fix because the pager page scroll column top-aligns short content without reserving viewport-height space for vertical centering.
 * - Excludes: runtime Compose measurement details, gesture physics, and memo-card content rendering.
 */
class DailyReviewLayoutPolicyTest {
    private val sourceText =
        File("src/main/java/com/lomo/app/feature/review/DailyReviewScreen.kt").readText()

    @Test
    fun pagerPage_centersShortContentWhileKeepingScrollableOverflow() {
        assertTrue(
            "Daily review pager pages should opt into viewport-aware measurement when deciding whether to center content.",
            sourceText.contains("BoxWithConstraints("),
        )
        assertTrue(
            "Daily review pager pages should reserve at least the viewport height so short cards can sit in the vertical center.",
            sourceText.contains(".heightIn(min = maxHeight)"),
        )
        assertTrue(
            "Daily review pager pages should center the memo card within the viewport-sized column when there is spare height.",
            sourceText.contains("verticalArrangement = Arrangement.Center"),
        )
    }
}
