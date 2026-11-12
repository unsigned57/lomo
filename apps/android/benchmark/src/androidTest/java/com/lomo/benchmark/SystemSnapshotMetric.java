package com.lomo.benchmark;

import androidx.benchmark.Outputs;
import androidx.benchmark.macro.Metric;
import androidx.benchmark.macro.TraceMetric;
import androidx.benchmark.traceprocessor.TraceProcessor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Unit;

/**
 * Captures the live target after its trace closes and before AndroidX's phase teardown.
 * Given a completed journey, system evidence must belong to that still-live process;
 * a missing process or malformed numeric fact fails the requested measurement.
 * Files describe the process lifetime through the last measured iteration, including setup.
 */
public final class SystemSnapshotMetric extends TraceMetric {
    private final String scenario;

    public SystemSnapshotMetric(String scenario) {
        if (!scenario.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
            throw new IllegalArgumentException("invalid system scenario: " + scenario);
        }
        this.scenario = scenario;
    }

    @Override
    public List<Metric.Measurement> getMeasurements$benchmark_macro(
            Metric.CaptureInfo captureInfo, TraceProcessor.Session traceSession) {
        // Kotlin emits this JVM override for Kotlin subclasses. The Java-only harness
        // must implement the same abstract Metric entry declared by AndroidX 1.5.
        return getMeasurements(captureInfo, traceSession);
    }

    @Override
    public List<Metric.Measurement> getMeasurements(
            Metric.CaptureInfo captureInfo, TraceProcessor.Session traceSession) {
        try {
            String target = captureInfo.getTargetPackageName();
            String gfx = LomoMacrobenchmark.shellText("dumpsys gfxinfo " + target);
            String frames = LomoMacrobenchmark.shellText("dumpsys gfxinfo " + target + " framestats");
            String memory = LomoMacrobenchmark.shellText("dumpsys meminfo " + target);
            double pss = requiredNumber(memory, "TOTAL PSS:\\s*(\\d+)", "total PSS");
            double frameCount = requiredNumber(gfx, "Total frames rendered:\\s*(\\d+)", "rendered frames");
            double janky = requiredNumber(gfx, "Janky frames:\\s*(\\d+)", "janky frames");
            if (pss <= 0 || frameCount <= 0) {
                throw new IllegalStateException("system snapshot has no live process/frame evidence");
            }
            write("gfxinfo", gfx);
            write("framestats", frames);
            write("meminfo", memory);
            return List.of(new Metric.Measurement("systemTotalPssKb", pss),
                    new Metric.Measurement("systemRenderedFrames", frameCount),
                    new Metric.Measurement("systemJankyFrames", janky));
        } catch (IOException error) {
            throw new IllegalStateException("system snapshot failed for " + scenario, error);
        }
    }

    private void write(String kind, String content) {
        Outputs.INSTANCE.writeFile(scenario + "-" + kind + ".txt", false, file -> {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new UncheckedIOException("cannot publish system snapshot " + file, error);
            }
            return Unit.INSTANCE;
        });
    }

    private static double requiredNumber(String text, String expression, String label) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        if (!matcher.find()) throw new IllegalStateException("system snapshot missing " + label);
        return Double.parseDouble(matcher.group(1));
    }
}
