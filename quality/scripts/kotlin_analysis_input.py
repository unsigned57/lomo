#!/usr/bin/env python3
"""Strict adapter for the pinned Toolchain's successful JVM compile-task state.

The compile task (not prepareAndroid's runtime graph) owns the classpath. This adapter
checks its schema/version/output identity and publishes one content-addressed analysis
model. Missing optional source roots are represented explicitly; missing binaries fail.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


SCHEMA = 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest_path(path):
    path = Path(path)
    digest = hashlib.sha256()
    if not path.exists():
        return "missing"
    files = sorted(path.rglob("*")) if path.is_dir() else [path]
    for item in files:
        if not item.is_file():
            continue
        digest.update(str(item.relative_to(path) if path.is_dir() else item.name).encode())
        digest.update(b"\0")
        with item.open("rb") as stream:
            for block in iter(lambda: stream.read(65536), b""):
                digest.update(block)
    return digest.hexdigest()


def toolchain_version(repo):
    lines = (repo / "apps/android/kotlin").read_text().splitlines()
    pins = [line.removeprefix("kotlin_cli_version=").strip('"\'') for line in lines if line.startswith("kotlin_cli_version=")]
    require(len(pins) == 1, "the Kotlin wrapper must own exactly one Toolchain version")
    return pins[0]


def task_identity(module):
    require(module in {"app", "data", "domain", "ui-components", "native-bindings", "detekt-rules"}, f"unknown analysis module: {module}")
    platform = "jvm" if module in {"domain", "detekt-rules"} else "android"
    task = "compileJvm" if platform == "jvm" else "compileAndroidDebug"
    variant = "main" if platform == "jvm" else "debug"
    fragment = f"{module}{platform}{'debug' if platform == 'android' else ''}"
    return platform, task, variant, fragment


def model_path(build, module):
    platform, _, variant, _ = task_identity(module)
    return build / "analysis-input" / f"{module}-{platform}-{variant}.json"


def aar_classpath(path, build):
    archive_digest = digest_path(path)
    output = build / "analysis-input" / "archives" / archive_digest
    result = []
    with zipfile.ZipFile(path) as archive:
        members = [name for name in archive.namelist() if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar"))]
        # An AAR with only resources legitimately has no bytecode.
        require(members or "AndroidManifest.xml" in archive.namelist(), f"invalid resource-only AAR: {path}")
        for name in members:
            require(".." not in Path(name).parts and not Path(name).is_absolute(), "AAR entry escapes output")
            target = output / name
            data = archive.read(name)  # CRC and archive errors are fatal, never skipped.
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists() or target.read_bytes() != data:
                target.write_bytes(data)
            with zipfile.ZipFile(target) as jar:
                require(jar.testzip() is None, f"corrupt AAR classes: {target}")
            result.append(str(target))
    return result


def export_model(repo, build, module):
    platform, task, variant, fragment = task_identity(module)
    candidates = list((build / "incremental.state").glob(f"_{module}_{task}-*"))
    require(len(candidates) == 1, f"analysis prerequisite: expected exactly one {module}/{task} compile state, found {len(candidates)}")
    state_path = candidates[0]
    state = json.loads(state_path.read_text())
    required_keys = {"codeVersion", "inputValues", "inputFiles", "inputFilesState", "outputValues", "outputFiles", "outputFilesState", "excludedOutputFiles", "dynamicInputs"}
    require(set(state) == required_keys, f"unsupported Toolchain compile-state schema: {state_path}")
    require(state["codeVersion"] == toolchain_version(repo), "analysis prerequisite: stale Toolchain compile model")
    values = state["inputValues"]
    expected_output = build / "artifacts/CompiledJvmArtifact" / fragment
    require(Path(values["task.output.root"]) == expected_output, "compile model module/variant mismatch")
    source_platforms = values["target.platforms"].split(", ")
    require(platform.upper() in source_platforms and set(source_platforms) <= {"JVM", "ANDROID"}, "compile model target mismatch")
    settings = json.loads(values["user.settings"])
    require(isinstance(settings["kotlin"]["compilerPlugins"], list), "compiler plugin model missing")
    require(json.loads(state["outputValues"]["jvmCompilerBuildProblems"]) == [], "compile model records unsuccessful compilation")
    require(state["outputFiles"] and all(Path(path).exists() for path in state["outputFiles"]), "compiled output missing")
    require(isinstance(state["inputFiles"], list) and len(state["inputFiles"]) == len(set(state["inputFiles"])), "invalid compile inputs")
    source_root = repo / "apps/android" / ("quality/detekt-rules" if module == "detekt-rules" else module) / "src"
    source_roots, generated_roots, absent_roots, classpath = [], [], [], []
    for raw in state["inputFiles"]:
        path = Path(raw)
        require(path.is_absolute(), f"relative compile input: {path}")
        is_source = path == source_root or path.is_relative_to(source_root)
        is_optional_source = path.parent == source_root.parent and path.name.startswith("src@")
        is_generated = path.is_relative_to(build / "generated" / module)
        is_resource = path.parent == source_root.parent and (path.name == "resources" or path.name.startswith("resources@"))
        if is_source or is_optional_source or is_generated or is_resource:
            if not path.exists():
                require(state["inputFilesState"].get(raw) == "MISSING" and not is_source, f"source input missing: {path}")
                absent_roots.append(raw)
            elif is_source or is_optional_source:
                source_roots.append(raw)
            elif is_generated:
                generated_roots.append(raw)
            continue
        require(path.exists(), f"analysis prerequisite: missing compile dependency {path}")
        if path.suffix == ".aar":
            classpath.extend(aar_classpath(path, build))
        elif path.suffix == ".jar" or path.is_dir():
            classpath.append(raw)
        else:
            raise ValueError(f"unknown compile input kind: {path}")
    require(source_roots and classpath, "analysis prerequisite: incomplete source/classpath model")
    # Runtime-only modules are absent from the compile task by construction; enforce the most
    # consequential boundary here as well so a wrong task model cannot widen app's authority.
    if module == "app":
        require(not any("/CompiledJvmArtifact/data" in path or "/CompiledJvmArtifact/native-bindings" in path for path in classpath), "runtime-only data/native module leaked into app compile scope")
    kotlin = settings["kotlin"]
    language = kotlin["languageVersion"] or ".".join(kotlin["compilerVersion"].split(".")[:2])
    inputs = {path: digest_path(path) for path in state["inputFiles"]}
    inputs[str(repo / "apps/android/kotlin")] = digest_path(repo / "apps/android/kotlin")
    for manifest in (repo / "apps/android").rglob("module.yaml"):
        inputs[str(manifest)] = digest_path(manifest)
    inputs[str(repo / "apps/android/project.yaml")] = digest_path(repo / "apps/android/project.yaml")
    for path in classpath:
        inputs[path] = digest_path(path)
    model = {
        "schema_version": SCHEMA, "toolchain_version": state["codeVersion"], "module": module,
        "platform": platform, "source_platforms": source_platforms, "variant": variant, "compile_task": f":{module}:{task}",
        "source_roots": source_roots, "generated_sources": generated_roots,
        "absent_source_roots": absent_roots, "compile_classpath": list(dict.fromkeys(classpath)),
        "friend_paths": [], "jdk_home": values["jdk.home"], "jdk_version": values["jdk.version"],
        "language_version": language, "api_version": kotlin["apiVersion"] or language,
        "jvm_target": str(settings["jvmRelease"]), "compiler_plugins": kotlin["compilerPlugins"],
        "compiler_arguments": kotlin["freeCompilerArgs"], "outputs": state["outputFiles"],
        "inputs": inputs,
    }
    require(Path(model["jdk_home"]).is_dir(), "analysis JDK missing")
    model["input_digest"] = hashlib.sha256(json.dumps(model, sort_keys=True).encode()).hexdigest()
    target = model_path(build, module)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".partial")
    temporary.write_text(json.dumps(model, indent=2) + "\n")
    temporary.replace(target)
    return model


def maven_cache_roots(classpath):
    roots, seen = [], set()
    for raw in classpath:
        normalized = raw.replace("\\", "/")
        for marker in ("/.m2.cache/", "/.m2/repository/"):
            index = normalized.find(marker)
            if index == -1:
                continue
            root = Path(normalized[: index + len(marker) - 1])
            key = str(root)
            if key in seen:
                continue
            seen.add(key)
            roots.append(root)
    return roots


def compiler_plugin_jar(plugin, classpath):
    require(isinstance(plugin, dict), "compiler plugin model entry is not an object")
    coordinates = plugin.get("coordinates") or {}
    group = coordinates.get("groupId")
    artifact = coordinates.get("artifactId")
    version = coordinates.get("version")
    require(group and artifact and version, "compiler plugin missing Maven coordinates")
    relative = Path(*group.split(".")) / artifact / version / f"{artifact}-{version}.jar"
    matches = [root / relative for root in maven_cache_roots(classpath) if (root / relative).is_file()]
    require(matches, f"analysis prerequisite: missing compiler plugin jar {relative}")
    return matches[0]


def detekt_compiler_arguments(model):
    classpath = list(model["compile_classpath"])
    generated = model["generated_sources"]
    if generated:
        require(model["outputs"], "analysis prerequisite: generated sources require compiled outputs")
        for path in generated:
            require(Path(path).exists(), f"analysis prerequisite: generated source missing {path}")
        # Generated Compose resource types are `internal` to this module. They cannot be
        # re-parsed as Detekt inputs (that lints generated code) and they cannot be seen
        # from a foreign classpath compilation, so the owning compile output is a friend.
        classpath = list(model["outputs"]) + classpath
    arguments = [
        "--analysis-mode",
        "full",
        "--classpath",
        os.pathsep.join(classpath),
        "--jdk-home",
        model["jdk_home"],
        "--language-version",
        model["language_version"],
        "--api-version",
        model["api_version"],
        "--jvm-target",
        model["jvm_target"],
    ]
    if generated:
        for output in model["outputs"]:
            arguments.append(f"-Xfriend-paths={output}")
    for plugin in model["compiler_plugins"]:
        arguments.append(f"-Xplugin={compiler_plugin_jar(plugin, model['compile_classpath'])}")
        plugin_id = plugin["id"]
        for option in plugin.get("options") or []:
            require(option.get("name") and "value" in option, "compiler plugin option missing name/value")
            arguments.extend(["-P", f"plugin:{plugin_id}:{option['name']}={option['value']}"])
    arguments.extend(model["compiler_arguments"])
    return arguments


def load_model(build, module):
    path = model_path(build, module)
    require(path.is_file(), f"analysis prerequisite: missing {path}; run the analysis-input task")
    model = json.loads(path.read_text())
    require(model["schema_version"] == SCHEMA and model["module"] == module, "analysis input schema/owner mismatch")
    recorded = model.pop("input_digest")
    require(hashlib.sha256(json.dumps(model, sort_keys=True).encode()).hexdigest() == recorded, "analysis input model digest mismatch")
    model["input_digest"] = recorded
    for path, expected in model["inputs"].items():
        require(digest_path(path) == expected, f"analysis prerequisite: stale input {path}")
    require(all(Path(path).exists() for path in model["outputs"]), "analysis prerequisite: compiled outputs missing")
    return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "detekt-args", "lint-classpath", "validate"])
    parser.add_argument("modules", nargs="+")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    build = Path(os.environ.get("LOMO_KOTLIN_BUILD_DIR", repo / ".kotlin/toolchain-build/shared")).resolve()
    if args.action == "prepare":
        tasks = [f":{module}:{task_identity(module)[1]}" for module in args.modules]
        wrapper = os.environ["LOMO_KOTLIN_WRAPPER"]
        subprocess.run([wrapper, "--log-level=warn", "task", *tasks, "--build-dir", str(build)], cwd=repo, check=True)
        for module in args.modules:
            model = export_model(repo, build, module)
            print(f"analysis-input: {module} {model['input_digest']} ({len(model['compile_classpath'])} compile entries)")
        return
    require(len(args.modules) == 1, "analysis consumption takes exactly one module")
    model = load_model(build, args.modules[0])
    if args.action == "detekt-args":
        for argument in detekt_compiler_arguments(model):
            sys.stdout.buffer.write(argument.encode() + b"\0")
    elif args.action == "lint-classpath":
        print(json.dumps(model["compile_classpath"]))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"analysis-input: {error}", file=sys.stderr)
        sys.exit(1)
