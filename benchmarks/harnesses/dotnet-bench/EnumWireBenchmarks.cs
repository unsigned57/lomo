using BenchmarkDotNet.Attributes;
using BoltffiDirection = Demo.Direction;
using BoltffiTaskStatus = Demo.TaskStatus;
using BoltffiBindings = Demo.Demo;
using UniffiDirection = uniffi.demo.Direction;
using UniffiTaskStatus = uniffi.demo.TaskStatus;
using UniffiBindings = uniffi.demo.DemoMethods;

namespace BoltFFIBench;

// Isolates the two enum marshaling paths each backend produces:
//   - EchoDirection_*    : C-style enum. BoltFFI marshals the enum as
//                          its backing int via direct P/Invoke; UniFFI
//                          routes it through its RustBuffer lift/lower
//                          (FfiConverterTypeDirection). This prices the
//                          cheapest enum path in each backend.
//   - EchoTaskStatus_*   : data enum. Each call wire-encodes the
//                          variant (tag + payload) on the way in,
//                          allocates an FfiBuf/RustBuffer on the way
//                          out, and wire-decodes on return. Measures
//                          the overhead of variable-payload wire codec
//                          against the direct-marshaling baseline.
//
// The TaskStatus cases cover three payload sizes so the reader can see
// how wire cost scales with variant body: Pending (unit, tag only),
// InProgress (tag + one i32), Completed (same). Adding a large-payload
// variant would exercise buffer growth — defer until fixtures include
// one.
public interface IEnumBindings<TDirection, TTaskStatus>
{
    TDirection EchoDirection(TDirection value);
    TTaskStatus EchoTaskStatus(TTaskStatus value);
    TDirection North();
    TDirection West();
    TTaskStatus Pending();
    TTaskStatus InProgress(int progress);
    TTaskStatus Completed(int progress);
}

public sealed class BoltffiEnumBindings : IEnumBindings<BoltffiDirection, BoltffiTaskStatus>
{
    public static readonly BoltffiEnumBindings Instance = new();

    private BoltffiEnumBindings() {}

    public BoltffiDirection EchoDirection(BoltffiDirection value) => BoltffiBindings.EchoDirection(value);

    public BoltffiTaskStatus EchoTaskStatus(BoltffiTaskStatus value) => BoltffiBindings.EchoTaskStatus(value);

    public BoltffiDirection North() => BoltffiDirection.North;

    public BoltffiDirection West() => BoltffiDirection.West;

    public BoltffiTaskStatus Pending() => new BoltffiTaskStatus.Pending();

    public BoltffiTaskStatus InProgress(int progress) => new BoltffiTaskStatus.InProgress(progress);

    public BoltffiTaskStatus Completed(int progress) => new BoltffiTaskStatus.Completed(progress);
}

public sealed class UniffiEnumBindings : IEnumBindings<UniffiDirection, UniffiTaskStatus>
{
    public static readonly UniffiEnumBindings Instance = new();

    private UniffiEnumBindings() {}

    public UniffiDirection EchoDirection(UniffiDirection value) => UniffiBindings.EchoDirection(value);

    public UniffiTaskStatus EchoTaskStatus(UniffiTaskStatus value) => UniffiBindings.EchoTaskStatus(value);

    public UniffiDirection North() => UniffiDirection.North;

    public UniffiDirection West() => UniffiDirection.West;

    public UniffiTaskStatus Pending() => new UniffiTaskStatus.Pending();

    public UniffiTaskStatus InProgress(int progress) => new UniffiTaskStatus.InProgress(progress);

    public UniffiTaskStatus Completed(int progress) => new UniffiTaskStatus.Completed(progress);
}

public abstract class EnumWireBenchmarks<TDirection, TTaskStatus>
{
    private TDirection _north = default!;
    private TDirection _west = default!;
    private TTaskStatus _pending = default!;
    private TTaskStatus _inProgress = default!;
    private TTaskStatus _completed = default!;

    protected abstract IEnumBindings<TDirection, TTaskStatus> Bindings { get; }

    [GlobalSetup]
    public void Setup()
    {
        _north = Bindings.North();
        _west = Bindings.West();
        _pending = Bindings.Pending();
        _inProgress = Bindings.InProgress(42);
        _completed = Bindings.Completed(100);
    }

    // --- C-style enum ---

    [Benchmark]
    public TDirection EchoDirection_North() => Bindings.EchoDirection(_north);

    [Benchmark]
    public TDirection EchoDirection_West() => Bindings.EchoDirection(_west);

    // --- Data enum (wire encode + decode) ---

    [Benchmark]
    public TTaskStatus EchoTaskStatus_UnitVariant() => Bindings.EchoTaskStatus(_pending);

    [Benchmark]
    public TTaskStatus EchoTaskStatus_SmallPayload() => Bindings.EchoTaskStatus(_inProgress);

    [Benchmark]
    public TTaskStatus EchoTaskStatus_CompletedPayload() => Bindings.EchoTaskStatus(_completed);
}

[MemoryDiagnoser]
public class BoltffiEnumWireBenchmarks : EnumWireBenchmarks<BoltffiDirection, BoltffiTaskStatus>
{
    protected override IEnumBindings<BoltffiDirection, BoltffiTaskStatus> Bindings =>
        BoltffiEnumBindings.Instance;
}

[MemoryDiagnoser]
public class UniffiEnumWireBenchmarks : EnumWireBenchmarks<UniffiDirection, UniffiTaskStatus>
{
    protected override IEnumBindings<UniffiDirection, UniffiTaskStatus> Bindings =>
        UniffiEnumBindings.Instance;
}
