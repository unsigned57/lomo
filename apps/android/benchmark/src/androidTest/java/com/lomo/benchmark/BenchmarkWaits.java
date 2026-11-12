package com.lomo.benchmark;

/** Boolean UIAutomator conditions must complete before the next interaction starts. */
final class BenchmarkWaits {
    private BenchmarkWaits() {}

    static void requireSatisfied(Boolean result, String message) {
        if (!Boolean.TRUE.equals(result)) {
            throw new IllegalStateException(message);
        }
    }
}
