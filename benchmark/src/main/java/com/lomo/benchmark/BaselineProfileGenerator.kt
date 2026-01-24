package com.lomo.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile Generator for the Lomo app.
 *
 * This generates a baseline profile that optimizes app startup and critical user paths. Run with:
 * ./gradlew :benchmark:pixel6Api31BenchmarkAndroidTest -P
 * android.testInstrumentationRunnerArguments.class=com.lomo.benchmark.BaselineProfileGenerator
 *
 * Expected improvement: 15-30% faster cold start.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        baselineRule.collect(
            packageName = "com.lomo.app",
            includeInStartupProfile = true,
            profileBlock = {
                // Cold start - most important for startup optimization
                pressHome()
                startActivityAndWait()

                // Scroll through memo list - common user action
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
                scrollable?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)

                // Wait for any animations/loading
                Thread.sleep(500)

                // Scroll back up
                scrollable?.scroll(androidx.test.uiautomator.Direction.UP, 1f)
            },
        )
    }
}
