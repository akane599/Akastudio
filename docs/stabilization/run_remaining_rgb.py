#!/usr/bin/env python3
"""Resume only the three outstanding physical RGB4 fixture exports; no connection setup or uninstall."""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import pathlib
import re
import shutil
import socket
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SERIAL = '100.70.96.59:5555'
PACKAGE = 'com.akane.akastudio.core.export.test'
APK_SHA = '4315a4b5580bf739b469fb585ea35afc77cb8181411db209501ed8027ef8ad83'
FIXTURES = {'coffee', 'rocket', 'clouds'}


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', default='rgb_remaining_' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d_%H%M%S'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.run):
        parser.error('Run ID must contain only letters, digits, underscore or hyphen')
    if socket.gethostname() != 'build-server':
        raise SystemExit('Run on device owner host build-server; another host lock cannot coordinate this phone')
    adb_path = shutil.which('adb')
    if not adb_path:
        raise SystemExit('Existing adb unavailable; do not install or reconfigure it automatically')
    adb = [adb_path, '-s', SERIAL]
    out = ROOT / 'docs/stabilization/evidence/akadroid-m1-completion/rgb-gain' / args.run
    out.mkdir(parents=True, exist_ok=False)
    lock = pathlib.Path.home() / '.cache/akastudio-akadroid.lock'
    with lock.open('a') as held:
        try:
            fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit(f'Owner-host device lock occupied: {lock}; queue instead of bypassing')

        def call(*parts, **kwargs):
            return subprocess.run(adb + list(parts), check=True, **kwargs)

        def read(*parts):
            return call(*parts, capture_output=True, text=True, timeout=15).stdout.strip()

        model = read('shell', 'getprop', 'ro.product.model')
        sdk = read('shell', 'getprop', 'ro.build.version.sdk')
        hardware = read('shell', 'getprop', 'ro.serialno')
        if model != 'SM-S938B' or sdk != '37' or hardware != 'R5CYA0WKL6Y':
            raise SystemExit(f'Unexpected physical target: {model}, API{sdk}, serial {hardware}')
        package_path = read('shell', 'pm', 'path', PACKAGE)
        if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=.+~-]+/base\.apk', package_path):
            raise SystemExit('Expected test package path unavailable; inspect before a reversible install -r; never uninstall/clear')
        installed_sha = read('shell', 'sha256sum', package_path.removeprefix('package:')).split()[0]
        if installed_sha != APK_SHA:
            raise SystemExit('Installed test APK differs. Preserve it, inspect source/artifact identity; no automatic replacement')
        identity = {'recordedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'serial': SERIAL,
                    'model': model, 'sdk': sdk, 'hardwareSerial': hardware, 'installedTestApkSha256': installed_sha,
                    'testSource': 'RgbGainComparisonDeviceTest fixture filter; scalar comparator explicitly4, RGB4 rejected control',
                    'fixtures': sorted(FIXTURES), 'run': args.run}
        (out / 'run-identity.json').write_text(json.dumps(identity, indent=2) + '\n')
        with (out / 'instrument.txt').open('w') as log:
            call('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 'com.akane.akastudio.core.export.device.RgbGainComparisonDeviceTest',
                 '-e', 'qaRgbGainRun', args.run, '-e', 'qaRgbFixtures', 'coffee.png,rocket.jpg,clouds.jpeg',
                 '-e', 'qaScalarGainDownscale', '4', PACKAGE + '/androidx.test.runner.AndroidJUnitRunner',
                 stdout=log, stderr=subprocess.STDOUT, timeout=600)
        raw = (out / 'instrument.txt').read_text()
        passed = bool(re.search(r'OK \(1 test\)', raw)) and 'FAILURES!!!' not in raw
        archive = out / 'originals.tar'
        with archive.open('wb') as stream:
            call('exec-out', 'run-as', PACKAGE, 'tar', '-C', 'files/akadroidqa', '-cf', '-', args.run,
                 stdout=stream, timeout=120)
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                path = pathlib.PurePosixPath(member.name)
                if not path.parts or path.parts[0] != args.run or path.is_absolute() or '..' in path.parts or member.issym() or member.islnk():
                    raise SystemExit('Unexpected archive member; no extraction or device cleanup')
            tar.extractall(out, filter='data')
        data = out / args.run
        actual = {f.name.removesuffix('-settings.json') for f in data.glob('*-settings.json')}
        if not passed or actual != FIXTURES:
            raise SystemExit('Incomplete/failed capture retained; private QA run left untouched for diagnosis')
        for name in sorted(actual):
            settings = json.loads((data / f'{name}-settings.json').read_text())
            if not settings.get('decodedPrimaryBaseArgbIdentical') or not settings.get('codecStatus', '').startswith('PASS:'):
                raise SystemExit('Codec/base control failed; artifacts retained, no cleanup')
        hashes = [{'path': str(f.relative_to(out)), 'sha256': sha(f), 'bytes': f.stat().st_size}
                  for f in sorted(out.rglob('*')) if f.is_file()]
        (out / 'artifact-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
        call('shell', 'run-as', PACKAGE, 'rm', '-rf', 'files/akadroidqa/' + args.run,
             capture_output=True, timeout=15)
    # The lock is released before host-only replay. This produces measurements, not a new approval of RGB4.
    replay = ROOT / 'docs/stabilization/evidence/akadroid-m1-completion/rgb-gain/cell-guards.py'
    natural = ROOT / 'docs/stabilization/evidence/natural-session/natural_final_02'
    with (out / 'guard-replay.txt').open('w') as log:
        subprocess.run(['python3', str(replay), str(data), str(natural), str(out / 'cell-guards.json')],
                       check=True, stdout=log, stderr=subprocess.STDOUT)
    print(f'Captured all3 outstanding RGB4 fixtures: {out}')
    print('Independently inspect evidence, then append originals/licenses/settings/hashes to the comparison ZIP and close the delivery remainder.')


if __name__ == '__main__':
    main()
