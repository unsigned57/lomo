from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from benchmarkdotnet_to_run import BenchmarkDotNetResults, build_benchmark_entries


def benchmark_report(title: str, benchmark_type: str, method_name: str) -> dict[str, object]:
    return {
        "Title": title,
        "HostEnvironmentInfo": {
            "BenchmarkDotNetVersion": "0.15.6",
            "DotNetCliVersion": "10.0.100",
            "RuntimeVersion": ".NET 10.0",
        },
        "Benchmarks": [
            {
                "Type": benchmark_type,
                "Method": method_name,
                "TargetFramework": "net10.0",
                "Statistics": {
                    "Mean": 1.0,
                    "StandardDeviation": 0.1,
                    "Min": 0.9,
                    "Max": 1.1,
                    "N": 10,
                    "Percentiles": {
                        "P50": 1.0,
                    },
                },
                "Properties": {
                    "WarmupCount": 1,
                    "IterationCount": 2,
                },
            }
        ],
    }


class BenchmarkDotNetResultsTests(unittest.TestCase):
    def test_reads_multiple_reports_as_one_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            boltffi_path = root / "BoltffiWireReaderBenchmarks-report-full.json"
            uniffi_path = root / "UniffiWireReaderBenchmarks-report-full.json"
            boltffi_path.write_text(
                json.dumps(
                    benchmark_report(
                        "BoltffiWireReaderBenchmarks",
                        "BoltFFIBench.BoltffiWireReaderBenchmarks",
                        "Add",
                    )
                )
            )
            uniffi_path.write_text(
                json.dumps(
                    benchmark_report(
                        "UniffiWireReaderBenchmarks",
                        "BoltFFIBench.UniffiWireReaderBenchmarks",
                        "Add",
                    )
                )
            )

            results = BenchmarkDotNetResults.read([boltffi_path, uniffi_path])
            entries = build_benchmark_entries(
                results.benchmarks,
                results.host_info,
                {"commit_sha": "local"},
                "release",
            )

        self.assertEqual("csharp-dotnet-benchmarkdotnet", results.title)
        self.assertEqual(
            ["BoltffiWireReaderBenchmarks", "UniffiWireReaderBenchmarks"],
            results.report_titles,
        )
        self.assertEqual(1, len(entries))
        self.assertEqual("add", entries[0]["descriptor"]["id"])
        self.assertEqual(2, len(entries[0]["variants"]))
        self.assertEqual(
            ["boltffi", "uniffi"],
            [
                variant["subject"]["tool"]["name"]
                for variant in entries[0]["variants"]
            ],
        )


if __name__ == "__main__":
    unittest.main()
