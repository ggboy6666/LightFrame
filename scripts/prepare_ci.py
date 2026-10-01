#!/usr/bin/env python3
"""Prepare pinned JVM dependencies using the Android SDK installed on the CI runner."""
from pathlib import Path
import os
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TC = ROOT.parent / 'toolchain'
TC.mkdir(exist_ok=True)

def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        return
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if not data:
        raise RuntimeError('Empty dependency download')
    path.write_bytes(data)

download('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.38.0/ecj-3.38.0.jar', TC / 'ecj.jar')
download('https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar', TC / 'test-deps/android-json-0.0.20131108.vaadin1.jar')
sdk_value = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
if not sdk_value:
    raise SystemExit('CI runner must provide ANDROID_HOME or ANDROID_SDK_ROOT')
sdk = Path(sdk_value)
if not (sdk / 'platforms/android-35/android.jar').is_file() or not (sdk / 'build-tools/35.0.0').is_dir():
    candidates = list((sdk / 'cmdline-tools').glob('*/bin/sdkmanager'))
    if not candidates:
        raise SystemExit('Android SDK manager unavailable')
    subprocess.run([str(candidates[0]), '--sdk_root=' + str(sdk), 'platforms;android-35', 'build-tools;35.0.0'], input='y\n' * 100, text=True, check=True, timeout=600)
print('CI toolchain prepared')
