// Behavior Contract:
// Capability: only a satisfied UIAutomator Boolean wait admits the next CUJ interaction.
// Owner: Android benchmark harness; priority: P0 measurement integrity.
// Scenarios:
// - Given true, when the wait boundary checks it, then the journey continues.
// - Given false (timeout) or null, when checked, then the journey fails with its scenario
//   message before interacting with a covered surface.
// Observable outcomes: continuation or IllegalStateException with the supplied message.
// TDD proof: JVM JUnit RED/GREEN and harness assembly recorded in
// audit/performance/device-followup-2026-10-21.md.
// Excludes: frame samples, navigation success and physical-device performance.

package com.lomo.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class BenchmarkWaitsTest {
    @Test
    public void satisfiedWaitContinues() {
        BenchmarkWaits.requireSatisfied(Boolean.TRUE, "sheet stayed open");
    }

    @Test
    public void timedOutWaitFailsWithScenarioMessage() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> BenchmarkWaits.requireSatisfied(Boolean.FALSE, "sheet stayed open"));
        assertEquals("sheet stayed open", error.getMessage());
    }

    @Test
    public void absentWaitFailsWithScenarioMessage() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> BenchmarkWaits.requireSatisfied(null, "drawer stayed open"));
        assertEquals("drawer stayed open", error.getMessage());
    }
}
