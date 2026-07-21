#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
wheel_directory="$script_dir/dist/wheelhouse"
requested_python=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --python)
            requested_python="${2:-}"
            shift 2
            ;;
        *)
            printf 'Unknown argument: %s\n' "$1" >&2
            printf 'Usage: %s [--python <interpreter>]\n' "$0" >&2
            exit 2
            ;;
    esac
done

resolve_python() {
    if [[ -n "$requested_python" ]]; then
        printf '%s\n' "$requested_python"
        return
    fi

    if command -v python3 >/dev/null 2>&1; then
        printf 'python3\n'
        return
    fi

    if command -v python >/dev/null 2>&1; then
        printf 'python\n'
        return
    fi

    printf 'Missing python interpreter\n' >&2
    exit 1
}

python_command="$(resolve_python)"

if [[ ! -d "$wheel_directory" ]]; then
    printf 'Missing wheel directory: %s\n' "$wheel_directory" >&2
    printf 'Build the Python demo package first.\n' >&2
    exit 1
fi

wheel_path="$(find "$wheel_directory" -maxdepth 1 -name '*.whl' | sort | tail -n 1)"

if [[ -z "$wheel_path" ]]; then
    printf 'No Python demo wheel found in %s\n' "$wheel_directory" >&2
    printf 'Build the Python demo package first.\n' >&2
    exit 1
fi

"$python_command" - "$script_dir" "$wheel_path" <<'PY'
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

platform_directory = pathlib.Path(sys.argv[1]).resolve()
wheel_path = pathlib.Path(sys.argv[2]).resolve()
smoke_directory = pathlib.Path(tempfile.mkdtemp(prefix="boltffi-python-demo-"))

try:
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "--target", str(smoke_directory), str(wheel_path)],
        check=True,
    )

    python_path_entries = [str(smoke_directory), str(platform_directory)]
    existing_python_path = os.environ.get("PYTHONPATH")
    if existing_python_path:
        python_path_entries.append(existing_python_path)

    subprocess.run(
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            str(platform_directory / "tests"),
            "-t",
            str(platform_directory),
            "-v",
        ],
        check=True,
        env={
            **os.environ,
            "PYTHONPATH": os.pathsep.join(python_path_entries),
        },
    )
finally:
    for _ in range(20):
        try:
            shutil.rmtree(smoke_directory)
            break
        except PermissionError:
            time.sleep(0.25)
    else:
        shutil.rmtree(smoke_directory, ignore_errors=True)
PY
