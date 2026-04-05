package com.lomo.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.lomo.app.benchmark.ANDROID_EDIT_TEXT_CLASS_NAME
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.benchmark.BenchmarkMainScreenNavigationSnapshot
import com.lomo.app.benchmark.BenchmarkSetupContract
import com.lomo.app.benchmark.BenchmarkSetupReceiver
import com.lomo.app.benchmark.BenchmarkUiNodeSnapshot
import com.lomo.app.benchmark.editableInputPathOrSelf
import com.lomo.app.benchmark.isMainScreenReady
import com.lomo.app.benchmark.shouldCloseDrawerWithBack
import com.lomo.app.benchmark.shouldOpenDrawerFromButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: BaselineProfileGenerator
 * - Behavior focus: startup-critical baseline collection over deterministic, anchor-driven memo flows.
 * - Observable outcomes: successful anchor-based navigation across main, settings, drawer tag, sort, search,
 *   trash, create/edit/delete, and history-restore paths with deterministic benchmark seed data.
 * - Red phase: Fails before the fix because baseline generation depends on unstable text selectors, random list
 *   content, and user preference state such as snapshots or free-text copy.
 * - Excludes: visual styling, exact layout structure, and business-rule validation outside benchmark contracts.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val setup = prepareBenchmarkStorage(device)

        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 10,
            stableIterations = 3,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            dismissOptionalUpdateDialog()
            waitForMainScreenOrThrow()

            runLargeListScrollPath()
            val createdMemo = runCreateMemoPath()
            runEditMemoPath(createdMemo)
            runDeleteToTrashPath(
                deleteTargetMemoId = setup.deleteTargetMemoId,
                createdMarker = createdMemo.marker,
            )
            runTrashRestoreAndPermanentDeletePath(
                deleteTargetMemoId = setup.deleteTargetMemoId,
                createdMarker = createdMemo.marker,
            )
            runSettingsEnterBackPath()
            runDrawerTagPath(setup.tagPath)
            runSortSwitchPath()
            runHistoryRestorePath(setup)
            runSearchPath()
            returnToMainOrThrow()
        }
    }

    private fun prepareBenchmarkStorage(device: UiDevice): BenchmarkSetupData {
        val receiver = BenchmarkSetupReceiver::class.java.name
        val shell =
            "am broadcast -W " +
                "-a ${BenchmarkSetupContract.ACTION_PREPARE} " +
                "-n $TARGET_PACKAGE/$receiver " +
                "--ei ${BenchmarkSetupContract.EXTRA_SEED_COUNT} 80 " +
                "--ez ${BenchmarkSetupContract.EXTRA_RESET} true"
        val rawResult = device.executeShellCommand(shell)
        check(rawResult.contains("result=-1")) { "Benchmark prepare failed: $rawResult" }
        return BenchmarkSetupData.parse(rawResult)
    }

    private fun MacrobenchmarkScope.dismissOptionalUpdateDialog() {
        findObject(By.text(CANCEL_TEXT), SHORT_WAIT_MS)?.let {
            tapObjectCenter(it.clickableAncestorOrSelf())
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.runLargeListScrollPath() {
        returnToMainOrThrow()
        repeat(4) { swipeUp() }
        repeat(2) { swipeDown() }
    }

    private fun MacrobenchmarkScope.runCreateMemoPath(): CreatedMemoData {
        returnToMainOrThrow()
        scrollToTop()
        clickAnchorOrThrow(BenchmarkAnchorContract.MAIN_CREATE_FAB)

        val marker = "BPCREATE${System.currentTimeMillis().toString().takeLast(6)}"
        val content =
            buildString {
                append(marker)
                append("\n")
                append("- [ ] create path")
                append("\n")
                append("baseline-anchor")
            }
        setTextInAnchorOrThrow(BenchmarkAnchorContract.INPUT_EDITOR, content)
        clickAnchorOrThrow(BenchmarkAnchorContract.INPUT_SUBMIT)
        waitForAnchorGoneOrThrow(BenchmarkAnchorContract.INPUT_SHEET_ROOT)
        waitForTextContainsOrThrow(marker)
        return CreatedMemoData(marker = marker)
    }

    private fun MacrobenchmarkScope.runEditMemoPath(createdMemo: CreatedMemoData) {
        returnToMainOrThrow()
        scrollToTop()
        openMemoMenuByTextContainsOrThrow(createdMemo.marker)
        clickMenuActionAnchorOrThrow(BenchmarkAnchorContract.MEMO_ACTION_EDIT)

        val editMarker = "BPEDIT${System.currentTimeMillis().toString().takeLast(4)}"
        val updatedContent =
            buildString {
                append(createdMemo.marker)
                append("\n")
                append("- [x] create path")
                append("\n")
                append(editMarker)
            }
        setTextInAnchorOrThrow(BenchmarkAnchorContract.INPUT_EDITOR, updatedContent)
        clickAnchorOrThrow(BenchmarkAnchorContract.INPUT_SUBMIT)
        waitForAnchorGoneOrThrow(BenchmarkAnchorContract.INPUT_SHEET_ROOT)
        waitForTextContainsOrThrow(editMarker)
    }

    private fun MacrobenchmarkScope.runDeleteToTrashPath(
        deleteTargetMemoId: String,
        createdMarker: String,
    ) {
        returnToMainOrThrow()
        scrollToTop()
        openMemoMenuByAnchorOrThrow(BenchmarkAnchorContract.memoMenu(deleteTargetMemoId))
        clickMenuActionAnchorOrThrow(BenchmarkAnchorContract.MEMO_ACTION_DELETE)
        device.waitForIdle()
        shortPause()

        openMemoMenuByTextContainsOrThrow(createdMarker)
        clickMenuActionAnchorOrThrow(BenchmarkAnchorContract.MEMO_ACTION_DELETE)
        device.waitForIdle()
        shortPause()
    }

    private fun MacrobenchmarkScope.runTrashRestoreAndPermanentDeletePath(
        deleteTargetMemoId: String,
        createdMarker: String,
    ) {
        openDrawerDestinationOrTagOrThrow(BenchmarkAnchorContract.DRAWER_TRASH)
        waitForAnchorOrThrow(BenchmarkAnchorContract.TRASH_ROOT)

        openMemoMenuByAnchorOrThrow(BenchmarkAnchorContract.memoMenu(deleteTargetMemoId))
        clickAnchorOrThrow(BenchmarkAnchorContract.TRASH_ACTION_RESTORE)
        device.waitForIdle()
        shortPause()

        openMemoMenuByTextContainsOrThrow(createdMarker)
        clickAnchorOrThrow(BenchmarkAnchorContract.TRASH_ACTION_DELETE_PERMANENTLY)
        device.waitForIdle()
        shortPause()

        device.pressBack()
        device.waitForIdle()
        waitForMainScreenOrThrow()
        scrollToTop()
        waitForAnchorOrThrow(BenchmarkAnchorContract.memoMenu(deleteTargetMemoId))
    }

    private fun MacrobenchmarkScope.runSettingsEnterBackPath() {
        openDrawerDestinationOrTagOrThrow(BenchmarkAnchorContract.DRAWER_SETTINGS)
        waitForAnchorOrThrow(BenchmarkAnchorContract.SETTINGS_ROOT)

        repeat(2) { swipeUp() }
        swipeDown()

        clickAnchorOrThrow(BenchmarkAnchorContract.SETTINGS_BACK_BUTTON)
        waitForMainScreenOrThrow()
    }

    private fun MacrobenchmarkScope.runDrawerTagPath(tagPath: String) {
        openDrawerDestinationOrTagOrThrow(BenchmarkAnchorContract.drawerTag(tagPath))
        waitForAnchorOrThrow(BenchmarkAnchorContract.TAG_ROOT)
        waitForTextContainsOrThrow(BenchmarkSetupContract.BENCHMARK_TAG_MEMO_MARKER)
        device.pressBack()
        device.waitForIdle()
        waitForMainScreenOrThrow()
    }

    private fun MacrobenchmarkScope.runSortSwitchPath() {
        returnToMainOrThrow()
        clickAnchorOrThrow(BenchmarkAnchorContract.MAIN_FILTER_BUTTON)
        waitForAnchorOrThrow(BenchmarkAnchorContract.FILTER_SHEET_ROOT)

        if (!isAnchorVisible(BenchmarkAnchorContract.SORT_SELECTED_CREATED_TIME)) {
            clickAnchorOrThrow(BenchmarkAnchorContract.SORT_OPTION_CREATED_TIME)
            waitForAnchorOrThrow(BenchmarkAnchorContract.SORT_SELECTED_CREATED_TIME)
        }
        clickAnchorOrThrow(BenchmarkAnchorContract.SORT_OPTION_UPDATED_TIME)
        waitForAnchorOrThrow(BenchmarkAnchorContract.SORT_SELECTED_UPDATED_TIME)
        clickAnchorOrThrow(BenchmarkAnchorContract.SORT_OPTION_CREATED_TIME)
        waitForAnchorOrThrow(BenchmarkAnchorContract.SORT_SELECTED_CREATED_TIME)

        device.pressBack()
        device.waitForIdle()
        waitForAnchorGoneOrThrow(BenchmarkAnchorContract.FILTER_SHEET_ROOT)
        waitForMainScreenOrThrow()
    }

    private fun MacrobenchmarkScope.runHistoryRestorePath(setup: BenchmarkSetupData) {
        returnToMainOrThrow()
        scrollToTop()
        waitForTextContainsOrThrow(setup.historyCurrentMarker)

        openMemoMenuByAnchorOrThrow(BenchmarkAnchorContract.memoMenu(setup.historyMemoId))
        clickMenuActionAnchorOrThrow(BenchmarkAnchorContract.MEMO_ACTION_HISTORY)
        waitForAnchorOrThrow(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
        clickAnchorOrThrow(BenchmarkAnchorContract.versionHistoryRestore(setup.historyRestoreRevisionId))
        waitForAnchorGoneOrThrow(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
        waitForTextContainsOrThrow(setup.historyRestoreMarker)

        scrollToTop()
        openMemoMenuByAnchorOrThrow(BenchmarkAnchorContract.memoMenu(setup.historyMemoId))
        clickMenuActionAnchorOrThrow(BenchmarkAnchorContract.MEMO_ACTION_HISTORY)
        waitForAnchorOrThrow(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
        clickAnchorOrThrow(BenchmarkAnchorContract.versionHistoryRestore(setup.historyCurrentRevisionId))
        waitForAnchorGoneOrThrow(BenchmarkAnchorContract.VERSION_HISTORY_ROOT)
        waitForTextContainsOrThrow(setup.historyCurrentMarker)
    }

    private fun MacrobenchmarkScope.runSearchPath() {
        returnToMainOrThrow()
        clickAnchorOrThrow(BenchmarkAnchorContract.MAIN_SEARCH_BUTTON)
        waitForAnchorOrThrow(BenchmarkAnchorContract.SEARCH_ROOT)
        setTextInAnchorOrThrow(BenchmarkAnchorContract.SEARCH_INPUT, SEARCH_QUERY)
        device.pressEnter()
        device.waitForIdle()
        shortPause()

        waitForTextContainsOrThrow(SEARCH_QUERY)
        repeat(2) { swipeUp() }

        if (isAnchorVisible(BenchmarkAnchorContract.SEARCH_CLEAR)) {
            clickAnchorOrThrow(BenchmarkAnchorContract.SEARCH_CLEAR)
        }
        device.pressBack()
        device.waitForIdle()
        waitForMainScreenOrThrow()
    }

    private fun MacrobenchmarkScope.returnToMainOrThrow() {
        repeat(MAX_MAIN_RETURN_ATTEMPTS) {
            val navigation = readMainScreenNavigationSnapshot()
            if (navigation.shouldCloseDrawerWithBack()) {
                device.pressBack()
                device.waitForIdle()
                shortPause()
                return@repeat
            }
            if (navigation.isMainScreenReady()) {
                return
            }
            device.pressBack()
            device.waitForIdle()
            shortPause()
        }

        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        dismissOptionalUpdateDialog()
        waitForMainScreenOrThrow()
    }

    private fun MacrobenchmarkScope.waitForMainScreenOrThrow() {
        val deadline = System.currentTimeMillis() + LONG_WAIT_MS
        var lastSnapshot = readMainScreenNavigationSnapshot()
        while (System.currentTimeMillis() < deadline) {
            if (lastSnapshot.isMainScreenReady()) {
                return
            }
            shortPause()
            lastSnapshot = readMainScreenNavigationSnapshot()
        }
        error("Main screen did not become ready: $lastSnapshot")
    }

    private fun MacrobenchmarkScope.openDrawerDestinationOrTagOrThrow(anchor: String) {
        returnToMainOrThrow()
        val navigation = readMainScreenNavigationSnapshot()
        if (navigation.shouldOpenDrawerFromButton()) {
            clickAnchorOrThrow(BenchmarkAnchorContract.MAIN_DRAWER_BUTTON)
        }
        check(waitForDrawerAnchor(anchor) != null) {
            "Drawer destination anchor not found: $anchor"
        }
        clickDrawerAnchorOrThrow(anchor)
    }

    private fun MacrobenchmarkScope.clickDrawerAnchorOrThrow(anchor: String) {
        val target = requireNotNull(waitForDrawerAnchor(anchor)) { "Missing drawer anchor: $anchor" }
        tapObjectCenter(target.clickableAncestorOrSelf())
        device.waitForIdle()
        shortPause()
    }

    private fun MacrobenchmarkScope.waitForDrawerAnchor(anchor: String): UiObject2? {
        waitForAnchor(anchor, SHORT_WAIT_MS)?.let { return it }
        repeat(DRAWER_SCROLL_ATTEMPTS) {
            swipeDrawerUp()
            waitForAnchor(anchor, SHORT_WAIT_MS)?.let { return it }
        }
        return null
    }

    private fun MacrobenchmarkScope.openMemoMenuByAnchorOrThrow(anchor: String) {
        val target =
            waitForAnchor(anchor, LONG_WAIT_MS)
                ?: error("Memo menu anchor not found: $anchor")
        tapObjectCenter(target.clickableAncestorOrSelf())
        device.waitForIdle()
        shortPause()
        waitForAnchorOrThrow(BenchmarkAnchorContract.MEMO_MENU_ROOT)
    }

    private fun MacrobenchmarkScope.openMemoMenuByTextContainsOrThrow(marker: String) {
        repeat(TEXT_SEARCH_SCROLL_ATTEMPTS) { attempt ->
            val target = findObject(By.textContains(marker), SHORT_WAIT_MS)
            if (target != null) {
                longPressObjectCenter(target.clickableAncestorOrSelf())
                device.waitForIdle()
                shortPause()
                waitForAnchorOrThrow(BenchmarkAnchorContract.MEMO_MENU_ROOT)
                return
            }
            if (attempt < TEXT_SEARCH_SCROLL_ATTEMPTS - 1) {
                swipeUp()
            }
        }
        error("Memo text marker not found for menu open: $marker")
    }

    private fun MacrobenchmarkScope.clickMenuActionAnchorOrThrow(anchor: String) {
        waitForAnchorOrThrow(BenchmarkAnchorContract.MEMO_MENU_ROOT)
        repeat(ACTION_SHEET_SEARCH_ATTEMPTS) { attempt ->
            if (clickAnchorWithOptionalActionSheetScroll(anchor)) {
                return
            }
            if (attempt < ACTION_SHEET_SEARCH_ATTEMPTS - 1) {
                swipeActionSheetLeft()
            }
        }
        error("Action anchor not found in memo sheet: $anchor")
    }

    private fun MacrobenchmarkScope.clickAnchorWithOptionalActionSheetScroll(anchor: String): Boolean {
        val target = waitForActionableAnchor(anchor, SHORT_WAIT_MS) ?: return false
        tapObjectCenter(target.clickableAncestorOrSelf())
        device.waitForIdle()
        shortPause()
        return true
    }

    private fun MacrobenchmarkScope.setTextInAnchorOrThrow(
        anchor: String,
        value: String,
    ) {
        val anchorNode =
            waitForAnchor(anchor, LONG_WAIT_MS)
                ?: error("Text input anchor not found: $anchor")
        val inputTarget =
            anchorNode.editableInputTargetOrSelf()
                ?: error("Editable text target not found inside anchor: $anchor")
        tapObjectCenter(inputTarget)
        device.waitForIdle()
        inputTarget.text = value
        device.waitForIdle()
        shortPause()
    }

    private fun MacrobenchmarkScope.waitForTextContainsOrThrow(text: String) {
        val found = findObject(By.textContains(text), LONG_WAIT_MS)
        check(found != null) { "Text not found: $text" }
    }

    private fun MacrobenchmarkScope.waitForAnchorOrThrow(anchor: String): UiObject2 =
        requireNotNull(waitForActionableAnchor(anchor, LONG_WAIT_MS)) { "Anchor not found: $anchor" }

    private fun MacrobenchmarkScope.waitForAnchorGoneOrThrow(anchor: String) {
        val gone = device.wait(Until.gone(By.res(anchor)), LONG_WAIT_MS)
        check(gone) { "Anchor still visible after timeout: $anchor" }
    }

    private fun MacrobenchmarkScope.clickAnchorOrThrow(anchor: String) {
        val target = waitForAnchorOrThrow(anchor)
        tapObjectCenter(target.clickableAncestorOrSelf())
        device.waitForIdle()
        shortPause()
    }

    private fun MacrobenchmarkScope.waitForAnchor(
        anchor: String,
        timeoutMs: Long,
    ): UiObject2? = findObject(By.res(anchor), timeoutMs)

    private fun MacrobenchmarkScope.waitForActionableAnchor(
        anchor: String,
        timeoutMs: Long,
    ): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidate = waitForAnchor(anchor, SHORT_WAIT_MS / 2)
        while (candidate != null && System.currentTimeMillis() < deadline) {
            if (candidate.isEnabled) {
                return candidate
            }
            shortPause()
            candidate = waitForAnchor(anchor, SHORT_WAIT_MS / 2)
        }
        return candidate?.takeIf(UiObject2::isEnabled)
    }

    private fun MacrobenchmarkScope.isAnchorVisible(anchor: String): Boolean =
        waitForAnchor(anchor, SHORT_WAIT_MS / 2) != null

    private fun MacrobenchmarkScope.readMainScreenNavigationSnapshot(): BenchmarkMainScreenNavigationSnapshot =
        BenchmarkMainScreenNavigationSnapshot(
            hasMainRoot = waitForAnchor(BenchmarkAnchorContract.MAIN_ROOT, SHORT_WAIT_MS / 2) != null,
            hasSearchButton = waitForAnchor(BenchmarkAnchorContract.MAIN_SEARCH_BUTTON, SHORT_WAIT_MS / 2) != null,
            hasDrawerButton = waitForAnchor(BenchmarkAnchorContract.MAIN_DRAWER_BUTTON, SHORT_WAIT_MS / 2) != null,
            hasDrawerDestinations =
                waitForAnchor(BenchmarkAnchorContract.DRAWER_SETTINGS, SHORT_WAIT_MS / 2) != null ||
                    waitForAnchor(BenchmarkAnchorContract.DRAWER_TRASH, SHORT_WAIT_MS / 2) != null,
        )

    private fun MacrobenchmarkScope.findObject(
        selector: androidx.test.uiautomator.BySelector,
        timeoutMs: Long,
    ): UiObject2? = device.wait(Until.findObject(selector), timeoutMs)

    private fun MacrobenchmarkScope.swipeUp() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.82f).toInt()
        val endY = (device.displayHeight * 0.28f).toInt()
        device.swipe(x, startY, x, endY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.swipeDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.35f).toInt()
        val endY = (device.displayHeight * 0.82f).toInt()
        device.swipe(x, startY, x, endY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.swipeDrawerUp() {
        val x = (device.displayWidth * DRAWER_SWIPE_X_FRACTION).toInt()
        val startY = (device.displayHeight * 0.82f).toInt()
        val endY = (device.displayHeight * 0.26f).toInt()
        device.swipe(x, startY, x, endY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.scrollToTop() {
        repeat(5) { swipeDown() }
    }

    private fun MacrobenchmarkScope.swipeActionSheetLeft() {
        val y = (device.displayHeight * ACTION_SHEET_Y_FRACTION).toInt()
        val startX = (device.displayWidth * 0.88f).toInt()
        val endX = (device.displayWidth * 0.16f).toInt()
        device.swipe(startX, y, endX, y, ACTION_SHEET_SWIPE_STEPS)
        device.waitForIdle()
        shortPause()
    }

    private fun MacrobenchmarkScope.longPressObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        val x = bounds.centerX()
        val y = bounds.centerY()
        device.executeShellCommand("input touchscreen swipe $x $y $x $y $LONG_PRESS_DURATION_MS")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.tapObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        val x = bounds.centerX().coerceIn(1, device.displayWidth - 2)
        val y = bounds.centerY().coerceIn(1, device.displayHeight - 2)
        val tapped = device.click(x, y)
        if (!tapped) {
            target.click()
        }
    }

    private fun MacrobenchmarkScope.shortPause() {
        Thread.sleep(SHORT_PAUSE_MS)
    }

    private fun UiObject2.clickableAncestorOrSelf(): UiObject2 {
        var node: UiObject2? = this
        while (node != null) {
            if (node.isClickable) {
                return node
            }
            node = node.parent
        }
        return this
    }

    private fun UiObject2.editableInputTargetOrSelf(): UiObject2? {
        if (className == ANDROID_EDIT_TEXT_CLASS_NAME) {
            return this
        }
        val editablePath = toBenchmarkUiNodeSnapshot().editableInputPathOrSelf() ?: return null
        return nodeAtPath(editablePath)
    }

    private fun UiObject2.toBenchmarkUiNodeSnapshot(): BenchmarkUiNodeSnapshot =
        BenchmarkUiNodeSnapshot(
            className = className,
            children = children.map { child -> child.toBenchmarkUiNodeSnapshot() },
        )

    private fun UiObject2.nodeAtPath(path: List<Int>): UiObject2? {
        var current: UiObject2 = this
        path.forEach { index ->
            val currentChildren = current.children
            if (index !in currentChildren.indices) {
                return null
            }
            current = currentChildren[index]
        }
        return current
    }

    private data class CreatedMemoData(
        val marker: String,
    )

    private data class BenchmarkSetupData(
        val rootPath: String,
        val seedCount: Int,
        val tagPath: String,
        val historyMemoId: String,
        val historyRestoreRevisionId: String,
        val historyCurrentRevisionId: String,
        val historyRestoreMarker: String,
        val historyCurrentMarker: String,
        val deleteTargetMemoId: String,
        val deleteTargetMarker: String,
    ) {
        companion object {
            fun parse(rawResult: String): BenchmarkSetupData {
                val payload =
                    Regex("""prepared[^"\r\n]*""")
                        .find(rawResult)
                        ?.value
                        ?: error("Benchmark prepare payload missing: $rawResult")
                val parts = payload.split(BenchmarkSetupContract.RESULT_DELIMITER)
                check(parts.firstOrNull() == BenchmarkSetupContract.RESULT_PREFIX) {
                    "Unexpected benchmark prepare prefix: $payload"
                }
                val values =
                    parts
                        .drop(1)
                        .filter(String::isNotBlank)
                        .associate { part ->
                            val separatorIndex = part.indexOf('=')
                            check(separatorIndex > 0) { "Malformed benchmark result entry: $part" }
                            part.substring(0, separatorIndex) to part.substring(separatorIndex + 1)
                        }

                return BenchmarkSetupData(
                    rootPath = values.requireValue(BenchmarkSetupContract.RESULT_KEY_ROOT_PATH),
                    seedCount =
                        values
                            .requireValue(BenchmarkSetupContract.RESULT_KEY_SEED_COUNT)
                            .toInt(),
                    tagPath = values.requireValue(BenchmarkSetupContract.RESULT_KEY_TAG_PATH),
                    historyMemoId = values.requireValue(BenchmarkSetupContract.RESULT_KEY_HISTORY_MEMO_ID),
                    historyRestoreRevisionId =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_HISTORY_RESTORE_REVISION_ID),
                    historyCurrentRevisionId =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_HISTORY_CURRENT_REVISION_ID),
                    historyRestoreMarker =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_HISTORY_RESTORE_MARKER),
                    historyCurrentMarker =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_HISTORY_CURRENT_MARKER),
                    deleteTargetMemoId =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_DELETE_TARGET_MEMO_ID),
                    deleteTargetMarker =
                        values.requireValue(BenchmarkSetupContract.RESULT_KEY_DELETE_TARGET_MARKER),
                )
            }

            private fun Map<String, String>.requireValue(key: String): String =
                get(key) ?: error("Benchmark prepare result missing key: $key")
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.lomo.app"
        const val SEARCH_QUERY = "perf-path-1"
        const val CANCEL_TEXT = "Cancel"
        const val SHORT_WAIT_MS = 1_500L
        const val LONG_WAIT_MS = 5_000L
        const val SHORT_PAUSE_MS = 180L
        const val LONG_PRESS_DURATION_MS = 650L
        const val MAX_MAIN_RETURN_ATTEMPTS = 5
        const val DRAWER_SCROLL_ATTEMPTS = 4
        const val TEXT_SEARCH_SCROLL_ATTEMPTS = 5
        const val ACTION_SHEET_SEARCH_ATTEMPTS = 3
        const val SWIPE_STEPS = 28
        const val ACTION_SHEET_SWIPE_STEPS = 24
        const val ACTION_SHEET_Y_FRACTION = 0.78f
        const val DRAWER_SWIPE_X_FRACTION = 0.24f
    }
}
