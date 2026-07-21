using System;
using System.Linq;
using BenchmarkDotNet.Configs;
using BenchmarkDotNet.Diagnosers;
using BenchmarkDotNet.Exporters.Json;
using BenchmarkDotNet.Jobs;
using BenchmarkDotNet.Toolchains.InProcess.Emit;

namespace BoltFFIBench;

// Runs benchmarks in-process so the P/Invoke'd native library loaded from
// the bench project's output directory is visible to the benchmark methods.
// A spawned-child toolchain would run from BenchmarkDotNet.Artifacts/... and
// not see the copied libbench_boltffi.{dylib,so,dll}.
internal sealed class BoltffiBenchConfig : ManualConfig
{
    public BoltffiBenchConfig()
    {
        var artifactsPath = Environment.GetEnvironmentVariable("BOLTFFI_BENCH_ARTIFACTS");

        AddJob(Job.Default
            .WithToolchain(InProcessEmitToolchain.Instance)
            .WithWarmupCount(3)
            .WithIterationCount(5));
        AddDiagnoser(MemoryDiagnoser.Default);
        AddLogger(DefaultConfig.Instance.GetLoggers().ToArray());
        AddExporter(DefaultConfig.Instance.GetExporters().ToArray());
        AddExporter(JsonExporter.Full);
        AddColumnProvider(DefaultConfig.Instance.GetColumnProviders().ToArray());

        if (!string.IsNullOrWhiteSpace(artifactsPath))
        {
            WithArtifactsPath(artifactsPath);
        }
    }
}
