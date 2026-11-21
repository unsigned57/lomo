#!/usr/bin/env bash
set -euo pipefail
# Contract test: successful Amper task states select only active compiled outputs and
# runtime archives while rejecting stale/unrelated caches and missing declared inputs.
repo_root="$(git rev-parse --show-toplevel)"
python3 - "$repo_root" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

repo = Path(sys.argv[1])
sys.path.insert(0, str(repo / 'quality/scripts'))
from kotlin_analysis_input import runtime_inputs, toolchain_version

with tempfile.TemporaryDirectory(prefix='lomo-profile-contract-') as temporary:
    root = Path(temporary)
    jars = []
    for method in ['current', 'obsolete']:
        source = root / method / 'sample/Probe.java'
        source.parent.mkdir(parents=True)
        source.write_text(f'package sample; public class Probe {{ public void {method}() {{}} }}')
        subprocess.run(['javac', '-d', str(source.parent.parent), str(source)], check=True)
        jar = root / f'{method}.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.write(source.with_suffix('.class'), 'sample/Probe.class')
        jars.append(jar)
    build = root / 'build'
    output_root = build / 'artifacts/CompiledJvmArtifact/appandroiddebug'
    output = output_root / 'kotlin-output'
    (output / 'sample').mkdir(parents=True)
    (output / 'sample/Probe.class').write_bytes((root / 'current/sample/Probe.class').read_bytes())
    # A stale archive and a release/test archive must have no influence on debug generation.
    stale = build / 'tasks/stale-release-and-test.jar'
    stale.parent.mkdir(parents=True)
    stale.write_bytes(jars[1].read_bytes())
    generated_r = build / 'R.jar'
    with zipfile.ZipFile(generated_r, 'w') as archive:
        archive.writestr('META-INF/NOTICE', 'resource compilation output')
    state_dir = build / 'incremental.state'
    state_dir.mkdir()
    def state(inputs, outputs, values, results):
        return dict(codeVersion=toolchain_version(repo),
                    inputValues=values, inputFiles=[str(p) for p in inputs], inputFilesState={},
                    outputValues=results, outputFiles=[str(p) for p in outputs],
                    outputFilesState={}, excludedOutputFiles=[], dynamicInputs={})
    compile_state = state([], [output], {'task.output.root': str(output_root)}, {'jvmCompilerBuildProblems': '[]'})
    prepare_state = state([], [generated_r], {}, {})
    (state_dir / '_app_compileAndroidDebug-fixture').write_text(json.dumps(compile_state))
    (state_dir / '_app_prepareAndroidDebug-fixture').write_text(json.dumps(prepare_state))
    selected = runtime_inputs(build, ['app'])
    assert output in selected and generated_r in selected
    assert stale not in selected and jars[1] not in selected
    stale.write_bytes(b'foreign cache entry is not a valid JAR')
    assert runtime_inputs(build, ['app']) == selected
    # Missing declared inputs fail before publishing and preserve the last complete output.
    prepare_state['inputFiles'] = [str(root / 'missing.jar')]
    (state_dir / '_app_prepareAndroidDebug-fixture').write_text(json.dumps(prepare_state))
    try:
        runtime_inputs(build, ['app'])
    except ValueError as error:
        assert 'missing Android runtime archive' in str(error), error
    else:
        raise AssertionError('missing runtime archive accepted')
print('android-runtime-input: runtime input contract passed')
PY
