#!/usr/bin/env python3
"""Contract test: Android Lint resolves packaged runtime components without widening app
compilation, and analyzes Kotlin sources of every packaged first-party module at minSdk."""

import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

project, build, sdk, lint = map(Path, sys.argv[1:])
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kotlin_analysis_input import load_model

receiver = "com.lomo.data.reminder.ReminderAlarmReceiver"


def contains_class(paths, name):
    member = name.replace(".", "/") + ".class"
    for path in map(Path, paths):
        if path.is_dir() and (path / member).is_file():
            return True
        if path.is_file() and path.suffix == ".jar":
            with zipfile.ZipFile(path) as archive:
                if member in archive.namelist():
                    return True
    return False


model = load_model(build, "app")
assert not contains_class(model["compile_classpath"], receiver), "runtime class leaked into app compile scope"
document = ET.parse(project)
classpath = [node.attrib["jar"] for node in document.findall(".//classpath")]
assert contains_class(classpath, receiver), "packaged receiver absent from lint runtime inputs"

android_name = "{http://schemas.android.com/apk/res/android}name"
manifest_node = document.find(".//manifest")
assert manifest_node is not None
manifest = ET.parse(manifest_node.attrib["file"])
registered = [node for node in manifest.findall(".//receiver") if node.get(android_name) == receiver]
assert len(registered) == 1, "expected one real receiver registration"
missing = "com.lomo.data.reminder.MissingRuntimeReceiver"
registered[0].set(android_name, missing)
with tempfile.TemporaryDirectory(prefix="lomo-lint-contract-") as temp_dir:
    evidence = Path(temp_dir)
    negative_manifest = evidence / "AndroidManifest.xml"
    manifest.write(negative_manifest, encoding="utf-8", xml_declaration=True)
    negative_project = copy.deepcopy(document)
    negative_project.find(".//manifest").set("file", str(negative_manifest.resolve()))
    negative_xml = evidence / "project.xml"
    negative_project.write(negative_xml, encoding="utf-8", xml_declaration=True)
    report = evidence / "lint-report.xml"
    result = subprocess.run([
        str(lint), "--project", str(negative_xml), "--check", "MissingClass", "--exitcode",
        "--sdk-home", str(sdk), "--xml", str(report), "--offline",
    ], text=True, capture_output=True)
    (evidence / "lint.log").write_text(result.stdout + result.stderr)
    assert result.returncode == 1, f"invalid registration was not rejected: exit={result.returncode}"
    issues = ET.parse(report).findall("issue")
    errors = [item for item in issues if item.get("severity") in {"Error", "Fatal"}]
    assert len(errors) == 1 and errors[0].get("id") == "MissingClass" and missing in errors[0].get("message", ""), \
        "negative manifest must reject only the nonexistent runtime class"

    modules = {node.get("name"): node for node in document.findall("module")}
    assert {"app", "data", "domain", "ui-components"} <= set(modules), f"packaged modules missing from lint: {sorted(modules)}"
    for name, node in modules.items():
        sources = [Path(item.attrib["file"]) for item in node.findall("src")]
        assert sources, f"lint module {name} lists no sources"
        assert all(path.is_file() for path in sources), f"lint module {name} lists a non-file <src>"

    probe = evidence / "ApiFloorProbe.kt"
    probe.write_text(
        "package com.lomo.data.lintprobe\n\n"
        "internal fun popApiFloorProbe(stack: MutableList<Int>): Int = stack.removeLast()\n",
        encoding="utf-8",
    )
    api_project = copy.deepcopy(document)
    for node in api_project.findall("module"):
        for item in node.findall("src"):
            node.remove(item)
    probe_module = next(node for node in api_project.findall("module") if node.get("name") == "data")
    ET.SubElement(probe_module, "src", {"file": str(probe.resolve())})
    api_xml = evidence / "api-floor-project.xml"
    api_project.write(api_xml, encoding="utf-8", xml_declaration=True)
    api_report = evidence / "api-floor-lint-report.xml"
    api_result = subprocess.run([
        str(lint), "--project", str(api_xml), "--check", "NewApi", "--exitcode",
        "--sdk-home", str(sdk), "--xml", str(api_report), "--offline",
    ], text=True, capture_output=True)
    (evidence / "api-floor-lint.log").write_text(api_result.stdout + api_result.stderr)
    assert api_result.returncode == 1, f"API-35 call in a library module was not rejected: exit={api_result.returncode}"
    api_errors = [
        item for item in ET.parse(api_report).findall("issue")
        if item.get("severity") in {"Error", "Fatal"}
    ]
    assert len(api_errors) == 1 and api_errors[0].get("id") == "NewApi" \
        and "java.util.List#removeLast" in api_errors[0].get("message", "") \
        and api_errors[0].find("location").get("file", "").endswith("ApiFloorProbe.kt"), \
        "library-module probe must fail only NewApi on List#removeLast"
print(
    "android-lint-runtime-contract: runtime registration resolves; invalid class rejects; compile scope stays isolated; "
    "packaged module sources are analyzed at the minSdk floor"
)
