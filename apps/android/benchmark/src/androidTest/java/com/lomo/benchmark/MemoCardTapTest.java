package com.lomo.benchmark;

import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Behavior Contract:
 * Capability: body expansion/collapse and double-tap editing; owner: Android UI; P1.
 * Given a committed long memo, when its body is tapped, then it expands; a later footer
 * action collapses it. Given two quick body taps across expansion, then its editor opens.
 * Observable outcomes: committed card affordances and editor visibility after real touches.
 * TDD proof: the original memo-tap-red.log records the legacy delayed expansion;
 * MemoCardTapSequenceTest protects immediate first-release state transitions and non-conflicting
 * editing. This device spec verifies the complete integration after snapshot loading settles.
 * Excludes: physical frame timing and storage latency bounds.
 * Test Change Justification:
 * Reason category: measurement endpoint correction.
 * Old behavior/assertion being replaced: a tight absolute or paired Collapse visibility deadline.
 * Why old assertion is no longer correct: Collapse requires asynchronous full-snapshot loading,
 * rendering, and an accessibility round trip; those are outside gesture recognition.
 * Coverage preserved by: real expansion/collapse and edit outcomes, plus deterministic
 * tap-sequence tests for immediate first-release handling.
 * Why this is not fitting the test to the implementation: recognition and presentation are
 * measured at their actual boundaries; the UI outcome deadline includes storage and rendering.
 */
public class MemoCardTapTest {
    @Test
    public void bodyTapExpandsAndFooterCollapsesCommittedMemo() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        long oldTimeout = Configurator.getInstance().getWaitForIdleTimeout();
        Configurator.getInstance().setWaitForIdleTimeout(0);
        try {
            String cardId = createLongMemo(device);
            android.graphics.Rect bounds = bodyBounds(device, cardId);
            tap(bounds.centerX(), bounds.top + 16);
            assertTrue("body tap did not expand its memo", Boolean.TRUE.equals(device.wait(
                    Until.hasObject(By.res(cardId).hasDescendant(By.text("Collapse"))), 10000)));
            collapse(device, cardId);
        } finally {
            Configurator.getInstance().setWaitForIdleTimeout(oldTimeout);
        }
    }

    @Test
    public void secondQuickReleaseOpensEditorAcrossBodyReplacement() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String cardId = createLongMemo(device);
        android.graphics.Rect bounds = bodyBounds(device, cardId);
        tap(bounds.centerX(), bounds.top + 16);
        SystemClock.sleep(90);
        tap(bounds.centerX(), bounds.top + 16);
        assertTrue("double tap did not open the editor across expansion",
                Boolean.TRUE.equals(device.wait(Until.hasObject(By.res("benchmark_input_editor")), 5000)));
        device.pressBack();
        device.pressBack();
    }

    private static String createLongMemo(UiDevice device) throws Exception {
        device.executeShellCommand("am force-stop com.lomo.app");
        device.executeShellCommand("am start -W -n com.lomo.app/.MainActivity -f 0x10008000");
        for (String title : new String[] {"Setup Image Storage Directory", "Setup Voice Storage Directory"}) {
            if (Boolean.TRUE.equals(device.wait(Until.hasObject(By.text(title)), 1500))) {
                Objects.requireNonNull(device.findObject(By.text("Auto Create"))).click();
                device.wait(Until.gone(By.text(title)), 5000);
            }
        }
        Objects.requireNonNull(device.wait(Until.findObject(By.res("benchmark_main_create_fab")), 15000)).click();
        UiObject2 editor = Objects.requireNonNull(device.wait(Until.findObject(By.res("benchmark_input_editor")), 5000));
        String prefix = "memo-tap-latency-probe-" + SystemClock.uptimeMillis();
        editor.setText(prefix + " " + "Long memo expansion must start immediately. ".repeat(22));
        Objects.requireNonNull(device.wait(Until.findObject(By.res("benchmark_input_submit")), 5000)).click();
        UiObject2 committedCard = Objects.requireNonNull(device.wait(Until.findObject(
                By.res(Pattern.compile("benchmark_memo_card_.*"))
                        .hasDescendant(By.textStartsWith(prefix))), 10000), "memo did not commit");
        String cardId = committedCard.getResourceName();
        Objects.requireNonNull(committedCard.findObject(By.text("Expand"))).click();
        assertTrue("footer did not load the full memo", Boolean.TRUE.equals(device.wait(
                Until.hasObject(By.res(cardId).hasDescendant(By.text("Collapse"))), 10000)));
        Objects.requireNonNull(device.findObject(By.res(cardId)).findObject(By.text("Collapse"))).click();
        assertTrue("footer did not collapse the memo", Boolean.TRUE.equals(device.wait(
                Until.hasObject(By.res(cardId).hasDescendant(By.text("Expand"))), 5000)));
        device.waitForIdle(1000);
        return cardId;
    }

    private static android.graphics.Rect bodyBounds(UiDevice device, String cardId) {
        return Objects.requireNonNull(device.findObject(By.res(cardId))
                .findObject(By.textStartsWith("memo-tap-latency-probe-"))).getVisibleBounds();
    }

    private static void collapse(UiDevice device, String cardId) {
        assertTrue("accepted expansion never presented its full body", Boolean.TRUE.equals(device.wait(
                Until.hasObject(By.res(cardId).hasDescendant(By.text("Collapse"))), 10000)));
        Objects.requireNonNull(device.findObject(By.res(cardId)).findObject(By.text("Collapse"))).click();
        assertTrue("card did not collapse", Boolean.TRUE.equals(device.wait(
                Until.hasObject(By.res(cardId).hasDescendant(By.text("Expand"))), 5000)));
        device.waitForIdle(1000);
    }

    private static void tap(float x, float y) {
        long downAt = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true);
            } finally {
                event.recycle();
            }
        }
    }
}
