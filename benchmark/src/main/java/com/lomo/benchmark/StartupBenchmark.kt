package com.lomo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup benchmark tests for the Lomo app.
 *
 * Run with: ./gradlew :benchmark:connectedBenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    /**
     * Cold startup benchmark - measures time from process creation to first frame. This is the most
     * realistic user experience measurement.
     */
    @Test
    fun startupCold() {
        benchmarkRule.measureRepeated(
            packageName = "com.lomo.app",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = 5,
            startupMode = StartupMode.COLD,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    /** Warm startup benchmark - app process exists but activity is recreated. */
    @Test
    fun startupWarm() {
        benchmarkRule.measureRepeated(
            packageName = "com.lomo.app",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = 5,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    /** Hot startup benchmark - activity is brought back to foreground. */
    @Test
    fun startupHot() {
        benchmarkRule.measureRepeated(
            packageName = "com.lomo.app",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = 5,
            startupMode = StartupMode.HOT,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }

    /** Scrolling benchmark - measures frame timing during list scrolling. */
    @Test
    fun scrollPerformance() {
        benchmarkRule.measureRepeated(
            packageName = "com.lomo.app",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.DEFAULT,
            iterations = 5,
            startupMode = StartupMode.WARM,
        ) {
            pressHome()
            startActivityAndWait()

            // Find scrollable content and scroll
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By
                        .scrollable(true),
                ),
                5000,
            )

            val scrollable =
                device.findObject(
                    androidx.test.uiautomator.By
                        .scrollable(true),
                )

            // Measure scroll performance
            scrollable?.let {
                repeat(3) {
                    scrollable.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
                    Thread.sleep(300)
                }
                repeat(3) {
                    scrollable.scroll(androidx.test.uiautomator.Direction.UP, 0.8f)
                    Thread.sleep(300)
                }
            }
        }
    }
}
