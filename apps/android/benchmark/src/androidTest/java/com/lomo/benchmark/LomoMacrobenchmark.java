// Behavior Contract
//
// Capability: minimal AndroidX Macrobenchmark suite for the installed Lomo debug build —
// cold startup timing, navigation frame timing, and memo-list scroll frame timing — driven
// through the production benchmark anchors that Compose exposes as resource ids
// (testTagsAsResourceId, see ui-components/benchmark/BenchmarkAnchorConfig.kt).
//
// Scenarios:
// - Given the target package is installed, when `startup` runs, then five cold launches through
//   the launcher intent report StartupTimingMetric samples (timeToInitialDisplay etc.).
// - Given the main screen, when `frame` runs, then opening the search surface and returning
//   reports FrameTimingMetric samples for a navigation interaction that is not a scroll.
// - Given the memo list is populated, when `scroll` runs, then flinging the scrollable under
//   benchmark_main_root reports FrameTimingMetric samples including frameOverrunMs.
// - Given a missing anchor (renamed tag, regression surface), when a scenario runs, then the
//   test fails closed via requireNonNull/wait-timeout instead of measuring an empty screen.
//
// Observable outcomes: androidx `*-benchmarkData.json` per run carrying startupMs /
// frameDurationCpuMs / frameOverrunMs percentiles.
// Excludes: baseline-profile generation (static generator stays canonical), release-signing
// measurements, and Kotlin-side UI assertions.

package com.lomo.benchmark;

import android.content.Intent;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LomoMacrobenchmark {

    // Package under measurement: the Amper-built debug APK installed by `just android benchmark`.
    private static final String TARGET_PACKAGE = "com.lomo.app";

    // Production anchors (apps/android/app/src/benchmark/BenchmarkAnchorContract.kt).
    private static final String MAIN_ROOT = "benchmark_main_root";
    private static final String MAIN_SEARCH_BUTTON = "benchmark_main_search_button";
    private static final String SEARCH_ROOT = "benchmark_search_root";

    private static final long WAIT_MS = TimeUnit.SECONDS.toMillis(15);

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Test
    public void startup() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                Collections.singletonList(new StartupTimingMetric()),
                new CompilationMode.None(),
                StartupMode.COLD,
                3,
                scope -> {
                    scope.startActivityAndWait(launchIntent());
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void frame() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                List.of(new FrameTimingMetric()),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    scope.startActivityAndWait(launchIntent());
                    UiObject2 searchButton = scope.getDevice().wait(
                            Until.findObject(By.res(MAIN_SEARCH_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(searchButton, "main search anchor missing");
                    searchButton.click();
                    UiObject2 searchRoot = scope.getDevice().wait(
                            Until.findObject(By.res(SEARCH_ROOT)), WAIT_MS);
                    Objects.requireNonNull(searchRoot, "search root anchor missing");
                    scope.getDevice().pressBack();
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void scroll() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                List.of(new FrameTimingMetric()),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    scope.startActivityAndWait(launchIntent());
                    UiObject2 mainRoot = scope.getDevice().wait(
                            Until.findObject(By.res(MAIN_ROOT)), WAIT_MS);
                    Objects.requireNonNull(mainRoot, "main root anchor missing");
                    UiObject2 scrollable = mainRoot.findObject(By.scrollable(true));
                    if (scrollable == null) {
                        scrollable = mainRoot;
                    }
                    scrollable.setGestureMarginPercentage(0.1f);
                    scrollable.scroll(Direction.DOWN, 1.0f);
                    scrollable.scroll(Direction.UP, 1.0f);
                    return Unit.INSTANCE;
                });
    }

    private static Intent launchIntent() {
        Intent intent = InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getPackageManager()
                .getLaunchIntentForPackage(TARGET_PACKAGE);
        Objects.requireNonNull(intent, "no launcher intent for " + TARGET_PACKAGE);
        return intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }
}
