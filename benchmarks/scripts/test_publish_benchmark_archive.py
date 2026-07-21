from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from publish_benchmark_archive import collect_incoming_paths


class PublishBenchmarkArchiveTests(unittest.TestCase):
    def test_collects_downloaded_benchmark_run_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            artifact_root = root / "downloaded"
            artifact_root.mkdir()
            swift_run = artifact_root / "swift-macos-benchmark_run.json"
            python_run = artifact_root / "nested" / "python-pyperf_benchmark_run.json"
            ignored = artifact_root / "notes.json"
            python_run.parent.mkdir()
            swift_run.write_text("{}")
            python_run.write_text("{}")
            ignored.write_text("{}")

            paths = collect_incoming_paths([artifact_root])

        self.assertEqual([python_run.resolve(), swift_run.resolve()], paths)


if __name__ == "__main__":
    unittest.main()
