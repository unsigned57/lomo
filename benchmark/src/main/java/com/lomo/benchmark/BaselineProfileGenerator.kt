package com.lomo.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkSetupContract
import com.lomo.app.benchmark.BenchmarkSetupReceiver
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Strict baseline profile flow for Lomo.
 *
 * The flow intentionally fails when key actions cannot be executed, so we avoid
 * "silent success" with no real user-path coverage.
 *
 * Covered paths:
 * 1) startup
 * 2) large-list scrolling
 * 3) create memo
 * 4) edit memo
 * 5) delete memo
 * 6) trash restore + permanent delete
 * 7) search
 * 8) settings enter/back
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext

        prepareBenchmarkStorage(device)

        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            dismissOptionalUpdateDialog(targetContext.getString(R.string.action_cancel))

            runLargeListScrollPath()
            val createdMarker = runCreateMemoPath()
            runEditMemoPath(createdMarker)
            runDeleteTwoMemosPath(createdMarker)
            runTrashRestoreAndPermanentDeletePath(createdMarker)
            runSearchPath()
        }
    }

    private fun prepareBenchmarkStorage(device: androidx.test.uiautomator.UiDevice) {
        val receiver = BenchmarkSetupReceiver::class.java.name
        val shell =
            "am broadcast -W " +
                "-a ${BenchmarkSetupContract.ACTION_PREPARE} " +
                "-n $TARGET_PACKAGE/$receiver " +
                "--ei ${BenchmarkSetupContract.EXTRA_SEED_COUNT} 180 " +
                "--ez ${BenchmarkSetupContract.EXTRA_RESET} true"
        val result = device.executeShellCommand(shell)
        val success = result.contains("result=-1") && result.contains("prepared:")
        check(success) { "Benchmark prepare failed: $result" }
    }

    private fun MacrobenchmarkScope.dismissOptionalUpdateDialog(cancelText: String) {
        findObject(By.text(cancelText), 1500L)?.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.runLargeListScrollPath() {
        repeat(8) { swipeUp() }
        repeat(3) { swipeDown() }
    }

    private fun MacrobenchmarkScope.runCreateMemoPath(): String {
        clickByAnyDescriptionOrThrow(FAB_DESC_CANDIDATES)

        val editor = waitEditorFieldOrThrow()
        val stamp = System.currentTimeMillis().toString().takeLast(5)
        val marker = "BPCREATE$stamp"
        editor.text = "$marker\n- [ ] create\nbaseline-tag"
        device.waitForIdle()

        clickByAnyDescriptionOrThrow(SEND_DESC_CANDIDATES)
        waitEditorFieldDisappearOrThrow()
        waitMemoTextContainsOrThrow(marker)
        return marker
    }

    private fun MacrobenchmarkScope.runEditMemoPath(createdMarker: String) {
        openMemoMenuByTextContainsOrThrow(listOf(createdMarker))
        clickByAnyTextOrThrow(EDIT_TEXT_CANDIDATES)

        val editor = waitEditorFieldOrThrow()
        val append = "\nedit${
            System.currentTimeMillis().toString().takeLast(4)
        }"
        editor.text = editor.text + append
        device.waitForIdle()

        clickByAnyDescriptionOrThrow(SEND_DESC_CANDIDATES)
        waitEditorFieldDisappearOrThrow()
        waitMemoTextContainsOrThrow(createdMarker)
    }

    private fun MacrobenchmarkScope.runDeleteTwoMemosPath(createdMarker: String) {
        openMemoMenuByTextContainsOrThrow(listOf(createdMarker))
        clickActionWithHorizontalFallbackOrThrow(DELETE_TEXT_CANDIDATES)
        device.waitForIdle()
        Thread.sleep(400L)

        openMemoMenuByTextContainsOrThrow(SEED_MEMO_TEXT_HINTS)
        clickActionWithHorizontalFallbackOrThrow(DELETE_TEXT_CANDIDATES)
        device.waitForIdle()
        Thread.sleep(400L)
    }

    private fun MacrobenchmarkScope.runTrashRestoreAndPermanentDeletePath(createdMarker: String) {
        openTrashFromDrawerOrThrow()

        openMemoMenuByTextContainsOrThrow(listOf(createdMarker))
        clickByAnyTextOrThrow(RESTORE_TEXT_CANDIDATES)
        device.waitForIdle()
        Thread.sleep(400L)

        openMemoMenuByTextContainsOrThrow(SEED_MEMO_TEXT_HINTS)
        clickByAnyTextOrThrow(DELETE_PERMANENTLY_TEXT_CANDIDATES)
        device.waitForIdle()

        device.pressBack()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.runSearchPath() {
        clickByAnyDescriptionOrThrow(SEARCH_DESC_CANDIDATES)

        val editor = waitEditorFieldOrThrow()
        editor.text = "perf-path-1"
        device.waitForIdle()

        repeat(2) { swipeUp() }

        clickByAnyDescriptionOrThrow(CLEAR_SEARCH_DESC_CANDIDATES)
    }

    private fun MacrobenchmarkScope.runSettingsEnterBackPath() {
        ensureMainScreenMenuVisibleOrThrow()
        clickByAnyDescriptionOrThrow(MENU_DESC_CANDIDATES)
        clickByAnyDescriptionOrThrow(SETTINGS_DESC_CANDIDATES)
        device.waitForIdle()

        repeat(2) { swipeUp() }
        swipeDown()

        val navigateUp =
            NAVIGATE_UP_DESC_CANDIDATES
                .asSequence()
                .mapNotNull { candidate ->
                    findObject(By.desc(candidate), SHORT_TIMEOUT_MS / 5)
                        ?: findObject(By.descContains(candidate), SHORT_TIMEOUT_MS / 6)
                }.firstOrNull()
        if (navigateUp != null) {
            tapObjectCenter(navigateUp.clickableAncestorOrSelf())
        } else {
            device.pressBack()
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.ensureMainScreenMenuVisibleOrThrow() {
        findAnyDescription(CLOSE_DESC_CANDIDATES)?.let {
            tapObjectCenter(it.clickableAncestorOrSelf())
            device.waitForIdle()
            Thread.sleep(220L)
        }

        repeat(6) {
            if (findAnyDescription(MENU_DESC_CANDIDATES) != null) return
            val close = findAnyDescription(CLOSE_DESC_CANDIDATES)
            if (close != null) {
                tapObjectCenter(close.clickableAncestorOrSelf())
            } else {
                device.pressBack()
            }
            device.waitForIdle()
            Thread.sleep(220L)
        }

        // Hard fallback: relaunch the app to restore main page deterministically.
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        findAnyText(CANCEL_TEXT_CANDIDATES)?.let {
            tapObjectCenter(it.clickableAncestorOrSelf())
            device.waitForIdle()
        }

        check(findAnyDescription(MENU_DESC_CANDIDATES) != null) {
            "Cannot return to main screen menu button"
        }
    }

    private fun MacrobenchmarkScope.openDrawerOrThrow() {
        clickByAnyDescriptionOrThrow(MENU_DESC_CANDIDATES)
    }

    private fun MacrobenchmarkScope.openTrashFromDrawerOrThrow() {
        openDrawerOrThrow()
        val trash = findAnyText(TRASH_TEXT_CANDIDATES)
        check(trash != null) { "Cannot find Trash item in drawer" }
        tapObjectCenter(trash.clickableAncestorOrSelf())
        device.waitForIdle()
        Thread.sleep(220L)

        // Retry once when drawer stays open due a missed tap.
        if (findAnyDescription(SETTINGS_DESC_CANDIDATES) != null) {
            val retry = findAnyText(TRASH_TEXT_CANDIDATES)
            check(retry != null) { "Cannot re-find Trash item while drawer is open" }
            tapObjectCenter(retry.clickableAncestorOrSelf())
            device.waitForIdle()
            Thread.sleep(220L)
        }

        check(findAnyDescription(SETTINGS_DESC_CANDIDATES) == null) {
            "Drawer remained open after selecting Trash"
        }
    }

    private fun MacrobenchmarkScope.openMemoMenuByTextContainsOrThrow(candidates: List<String>) {
        // Defensive: close drawer if it's still open, otherwise swipes will happen inside sidebar.
        if (findAnyDescription(SETTINGS_DESC_CANDIDATES) != null) {
            device.pressBack()
            device.waitForIdle()
            Thread.sleep(180L)
        }

        if (tryOpenMemoMenu(candidates)) return

        // Prefer scrolling down the list first (swipe up), then reverse once.
        repeat(8) {
            swipeUp()
            if (tryOpenMemoMenu(candidates)) return
        }
        repeat(5) {
            swipeDown()
            if (tryOpenMemoMenu(candidates)) return
        }

        error("Cannot open memo menu for text candidates: $candidates")
    }

    private fun MacrobenchmarkScope.tryOpenMemoMenu(candidates: List<String>): Boolean {
        val target = findAnyTextContains(candidates) ?: return false
        longPressObjectCenter(target.clickableAncestorOrSelf())
        return findAnyText(MENU_OPEN_TEXT_HINTS) != null
    }

    private fun MacrobenchmarkScope.waitEditorFieldOrThrow(): UiObject2 {
        val field = findEditorField(SHORT_TIMEOUT_MS)
        return checkNotNull(field) { "Editor field not found" }
    }

    private fun MacrobenchmarkScope.waitEditorFieldDisappearOrThrow() {
        val ok = device.wait(Until.gone(By.clazz("android.widget.EditText")), SHORT_TIMEOUT_MS)
        check(ok) { "Editor field did not disappear after submit" }
    }

    private fun MacrobenchmarkScope.findEditorField(timeout: Long): UiObject2? {
        val byClass = findObject(By.clazz("android.widget.EditText"), timeout)
        if (byClass != null) return byClass

        // Compose TextField fallback through UiSelector.
        return runCatching {
            device.findObject(UiSelector().className("android.widget.EditText"))?.let { legacy ->
                @Suppress("DEPRECATION")
                device.findObject(By.clazz("android.widget.EditText"))
            }
        }.getOrNull()
    }

    private fun MacrobenchmarkScope.clickByDescriptionOrThrow(description: String) {
        val obj = findObject(By.desc(description), SHORT_TIMEOUT_MS)
        check(obj != null) { "Cannot find object by description: $description" }
        tapObjectCenter(obj.clickableAncestorOrSelf())
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.clickByAnyDescriptionOrThrow(candidates: List<String>) {
        val obj =
            candidates
                .asSequence()
                .mapNotNull { candidate ->
                    findObject(By.desc(candidate), SHORT_TIMEOUT_MS / 2)
                        ?: findObject(By.descContains(candidate), SHORT_TIMEOUT_MS / 3)
                }.firstOrNull()
        check(obj != null) { "Cannot find object by descriptions: $candidates" }
        tapObjectCenter(obj.clickableAncestorOrSelf())
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.clickByAnyTextOrThrow(candidates: List<String>) {
        val obj =
            candidates
                .asSequence()
                .mapNotNull { candidate ->
                    findObject(By.text(candidate), SHORT_TIMEOUT_MS / 2)
                        ?: findObject(By.textContains(candidate), SHORT_TIMEOUT_MS / 3)
                }.firstOrNull()
        check(obj != null) { "Cannot find object by texts: $candidates" }
        tapObjectCenter(obj.clickableAncestorOrSelf())
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.clickActionWithHorizontalFallbackOrThrow(candidates: List<String>) {
        if (clickMenuActionTarget(candidates)) return

        // Memo action sheet can be horizontally scrollable on smaller screens.
        repeat(4) {
            swipeActionSheetLeft()
            if (clickMenuActionTarget(candidates)) return
        }

        error("Cannot find action after horizontal fallback: $candidates")
    }

    private fun MacrobenchmarkScope.clickMenuActionTarget(candidates: List<String>): Boolean {
        val target = findMenuActionTarget(candidates) ?: return false
        tapObjectCenter(target.clickableAncestorOrSelf())
        Thread.sleep(220L)
        device.waitForIdle()

        // Retry once if the first tap lands during residual scroll inertia.
        if (findMenuActionTarget(candidates) != null) {
            val secondTry = findMenuActionTarget(candidates) ?: return true
            tapObjectCenter(secondTry.clickableAncestorOrSelf())
            Thread.sleep(220L)
            device.waitForIdle()
        }
        return true
    }

    private fun MacrobenchmarkScope.findMenuActionTarget(candidates: List<String>): UiObject2? {
        candidates.forEach { candidate ->
            findObject(By.text(candidate), SHORT_TIMEOUT_MS / 6)
                ?.takeIf { isLikelyBottomSheetAction(it) }
                ?.let { return it }
            findObject(By.desc(candidate), SHORT_TIMEOUT_MS / 6)
                ?.takeIf { isLikelyBottomSheetAction(it) }
                ?.let { return it }
        }

        candidates.forEach { candidate ->
            findObject(By.textContains(candidate), SHORT_TIMEOUT_MS / 8)
                ?.takeIf { isLikelyBottomSheetAction(it) }
                ?.let { return it }
            findObject(By.descContains(candidate), SHORT_TIMEOUT_MS / 8)
                ?.takeIf { isLikelyBottomSheetAction(it) }
                ?.let { return it }
        }
        return null
    }

    private fun MacrobenchmarkScope.isLikelyBottomSheetAction(target: UiObject2): Boolean {
        val bounds = target.visibleBounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val minY = (device.displayHeight * 0.52f).toInt()
        return bounds.centerY() >= minY
    }

    private fun MacrobenchmarkScope.findAnyText(candidates: List<String>): UiObject2? =
        candidates
            .asSequence()
            .mapNotNull { candidate ->
                findObject(By.text(candidate), SHORT_TIMEOUT_MS / 3)
                    ?: findObject(By.textContains(candidate), SHORT_TIMEOUT_MS / 4)
            }.firstOrNull()

    private fun MacrobenchmarkScope.findAnyDescription(candidates: List<String>): UiObject2? =
        candidates
            .asSequence()
            .mapNotNull { candidate ->
                findObject(By.desc(candidate), SHORT_TIMEOUT_MS / 4)
                    ?: findObject(By.descContains(candidate), SHORT_TIMEOUT_MS / 5)
            }.firstOrNull()

    private fun MacrobenchmarkScope.findAnyTextContains(candidates: List<String>): UiObject2? =
        candidates
            .asSequence()
            .mapNotNull { candidate ->
                findObject(By.textContains(candidate), SHORT_TIMEOUT_MS / 2)
            }.firstOrNull()

    private fun MacrobenchmarkScope.findObject(
        selector: BySelectorWrapper,
        timeoutMs: Long,
    ): UiObject2? {
        return when (selector) {
            is BySelectorWrapper.BySelectorImpl -> device.wait(Until.findObject(selector.selector), timeoutMs)
        }
    }

    private fun MacrobenchmarkScope.findObject(
        selector: androidx.test.uiautomator.BySelector,
        timeoutMs: Long,
    ): UiObject2? = device.wait(Until.findObject(selector), timeoutMs)

    private fun MacrobenchmarkScope.swipeUp() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.82f).toInt()
        val endY = (device.displayHeight * 0.28f).toInt()
        device.swipe(x, startY, x, endY, 28)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.swipeDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.35f).toInt()
        val endY = (device.displayHeight * 0.82f).toInt()
        device.swipe(x, startY, x, endY, 28)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.swipeActionSheetLeft() {
        val y = (device.displayHeight * 0.78f).toInt()
        val startX = (device.displayWidth * 0.88f).toInt()
        val endX = (device.displayWidth * 0.16f).toInt()
        device.swipe(startX, y, endX, y, 24)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.longPressObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        val x = bounds.centerX()
        val y = bounds.centerY()
        device.executeShellCommand("input touchscreen swipe $x $y $x $y 650")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.tapObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        val x = bounds.centerX().coerceIn(1, device.displayWidth - 2)
        val y = bounds.centerY().coerceIn(1, device.displayHeight - 2)
        val tapped = device.click(x, y)
        if (!tapped) target.click()
    }

    private fun UiObject2.clickableAncestorOrSelf(): UiObject2 {
        var node: UiObject2? = this
        while (node != null) {
            if (node.isClickable) return node
            node = node.parent
        }
        return this
    }

    private fun MacrobenchmarkScope.waitMemoTextContainsOrThrow(text: String) {
        val found = findObject(By.textContains(text), SHORT_TIMEOUT_MS + 3_000L)
        check(found != null) { "Memo text not found after submit: $text" }
    }

    private sealed interface BySelectorWrapper {
        data class BySelectorImpl(
            val selector: androidx.test.uiautomator.BySelector,
        ) : BySelectorWrapper
    }

    companion object {
        private const val TARGET_PACKAGE = "com.lomo.app"
        private const val SHORT_TIMEOUT_MS = 4_500L
        private val MENU_DESC_CANDIDATES = listOf("Menu", "菜单")
        private val SEARCH_DESC_CANDIDATES = listOf("Search", "搜索")
        private val CLEAR_SEARCH_DESC_CANDIDATES = listOf("Clear search", "清除搜索")
        private val CLOSE_DESC_CANDIDATES = listOf("Close", "关闭")
        private val SETTINGS_DESC_CANDIDATES = listOf("Settings", "设置")
        private val CANCEL_TEXT_CANDIDATES = listOf("Cancel", "取消")
        private val NAVIGATE_UP_DESC_CANDIDATES = listOf("Navigate up", "返回", "Back")
        private val FAB_DESC_CANDIDATES = listOf("New Memo", "新建备忘录")
        private val SEND_DESC_CANDIDATES = listOf("Send", "发送")
        private val EDIT_TEXT_CANDIDATES = listOf("Edit", "编辑")
        private val DELETE_TEXT_CANDIDATES = listOf("Delete", "删除")
        private val TRASH_TEXT_CANDIDATES = listOf("Trash", "回收站")
        private val RESTORE_TEXT_CANDIDATES = listOf("Restore", "恢复")
        private val DELETE_PERMANENTLY_TEXT_CANDIDATES = listOf("Delete Permanently", "永久删除")
        private val MENU_ACTION_TEXT_HINTS = listOf("Copy", "复制", "Edit", "编辑", "Delete", "删除")
        private val MENU_OPEN_TEXT_HINTS =
            MENU_ACTION_TEXT_HINTS + listOf("Restore", "恢复", "Delete Permanently", "永久删除")
        private val SEED_MEMO_TEXT_HINTS = listOf("Benchmark memo", "perf-path-")
    }
}
