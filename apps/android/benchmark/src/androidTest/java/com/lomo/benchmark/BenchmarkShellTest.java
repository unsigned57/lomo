package com.lomo.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/**
 * Behavior Contract: benchmark setup interprets shell syntax and rejects command failure.
 * Given a quoted filename and redirection, writing then reading preserves exact bytes.
 * Given nonzero exit, the boundary throws before any performance sample is admitted.
 * RED/GREEN: benchmark-shell-red.log / benchmark-shell-green.log on API 35 redroid.
 * Excludes: product file access and benchmark timing assertions.
 */
public class BenchmarkShellTest {
    @Test
    public void quotedPathAndRedirectionWriteRealBytes() throws IOException {
        String path = "/data/local/tmp/lomo-shell-contract-" + System.nanoTime() + " space.txt";
        try {
            LomoMacrobenchmark.shellText("printf 'corpus-ready\\n' > '" + path + "'");
            assertEquals("corpus-ready\n", LomoMacrobenchmark.shellText("cat '" + path + "'"));
        } finally {
            androidx.test.shell.Shell.INSTANCE.command("rm -f '" + path + "'").getStdOut();
        }
    }

    @Test
    public void failedCommandRejectsMeasurementSetup() {
        IOException failure = assertThrows(IOException.class,
                () -> LomoMacrobenchmark.shellText("exit 7"));
        assertTrue(failure.getMessage().contains("7"));
    }
}
