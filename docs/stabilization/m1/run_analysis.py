#!/usr/bin/env python3
"""Fresh pure-source M1 numerical probe. Uses cached compiler; never invokes Gradle/Android.

Run from repository root: python3 docs/stabilization/m1/run_analysis.py
Outputs classes in a temporary directory and a reproducible TSV next to this file.
"""
from pathlib import Path
import argparse
import hashlib
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'

def jar(group, name, version):
    return next((CACHE / group / name / version).glob(f'*/{name}-{version}.jar'))

compiler_jars = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.21'),
                 jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21'),
                 jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.21'),
                 jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.0.21'),
                 jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.4.21'),
                 jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
                 jar('org.jetbrains', 'annotations', '23.0.0')]
color = ROOT / 'core/color-engine/src/main/kotlin/com/akane/akastudio/core/color'
export = ROOT / 'core/export-pipeline/src/main/kotlin/com/akane/akastudio/core/export/process'
sources = [color / 'api/ColorTypes.kt', color / 'api/RenderScene.kt', color / 'math/ColorMath.kt',
           color / 'analysis/SkinMask.kt', color / 'harmonize/Harmonizer.kt']
sources += sorted((color / 'grade').glob('*.kt')) + sorted((color / 'look').glob('*.kt'))
sources += [export / name for name in ['Half.kt', 'HalfRows.kt', 'GainMapMath.kt', 'LumaSharpen.kt', 'Dither.kt', 'BlueNoise.kt']]
parser = argparse.ArgumentParser()
parser.add_argument('--baseline', action='store_true', help='Compile committed 299e568 sources in a temporary directory')
args = parser.parse_args()
prefix = 'baseline-' if args.baseline else ''
cp = os.pathsep.join(map(str, compiler_jars))
with tempfile.TemporaryDirectory(prefix='akastudio-m1-') as temp:
    if args.baseline:
        committed_sources = []
        for source in sources:
            relative = source.relative_to(ROOT)
            contents = subprocess.check_output(['git', 'show', f'299e568:{relative}'], cwd=ROOT)
            destination = Path(temp) / 'source' / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(contents)
            committed_sources.append(destination)
        source_pairs = list(zip(committed_sources, [p.relative_to(ROOT) for p in sources]))
        sources = committed_sources
    else:
        source_pairs = [(p, p.relative_to(ROOT)) for p in sources]
    hashes = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {relative}\n' for p, relative in source_pairs)
    (HERE / f'{prefix}source-sha256.txt').write_text(hashes)
    subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', cp, '-jvm-target', '17',
                    '-d', temp, *map(str, sources), str(HERE / 'M1Probe.kt')], check=True, cwd=ROOT)
    result = subprocess.run(['java', '-Xmx2g', '-cp', temp + os.pathsep + cp, 'M1ProbeKt'],
                            check=True, text=True, stdout=subprocess.PIPE, cwd=ROOT)
    (HERE / f'{prefix}results.tsv').write_text(result.stdout)
    print(result.stdout, end='')
