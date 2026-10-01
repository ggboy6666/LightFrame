#!/usr/bin/env python3
"""Download pinned SDK/compiler/dependencies for Windows, Linux, or macOS.

Requires internet access and Java 17. SDK licence acceptance is interactive.
"""
import os
import pathlib
import platform
import shutil
import subprocess
import urllib.request
import zipfile


root = pathlib.Path(__file__).resolve().parent
tc = root.parent / 'toolchain'
sdk = tc / 'android-sdk'
tools = sdk / 'cmdline-tools/latest'
deps = root / 'deps'
system = platform.system()
hosts = {'Windows': 'win', 'Linux': 'linux', 'Darwin': 'mac'}
if system not in hosts:
    raise SystemExit('Unsupported operating system: ' + system)
host = hosts[system]
manager = tools / 'bin' / ('sdkmanager.bat' if system == 'Windows' else 'sdkmanager')
tc.mkdir(parents=True, exist_ok=True)
deps.mkdir(parents=True, exist_ok=True)


def get(url, destination):
    if destination.exists():
        return
    print('Downloading', destination.name, flush=True)
    partial = destination.with_name(destination.name + '.part')
    try:
        with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        partial.replace(destination)
    finally:
        if partial.exists():
            partial.unlink()


def run_manager(*arguments, timeout):
    command = [str(manager), '--sdk_root=' + str(sdk), *arguments]
    if system == 'Windows':
        # sdkmanager is a batch file; cmd.exe also handles SDK paths with spaces.
        command_line = subprocess.list2cmdline(command)
        shell = subprocess.list2cmdline([os.environ.get('COMSPEC', 'cmd.exe')])
        command = shell + ' /d /s /c "' + command_line + '"'
    subprocess.run(command, check=True, timeout=timeout)


if not manager.exists():
    archive = tc / ('commandlinetools-' + host + '-11076708.zip')
    get('https://dl.google.com/android/repository/commandlinetools-' + host
        + '-11076708_latest.zip', archive)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if not entry.filename.startswith('cmdline-tools/') or entry.is_dir():
                continue
            relative = pathlib.PurePosixPath(entry.filename.removeprefix('cmdline-tools/'))
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Unsafe SDK archive path: ' + entry.filename)
            destination = tools.joinpath(*relative.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(z.read(entry))
    if system != 'Windows':
        for executable in (tools / 'bin').iterdir():
            executable.chmod(0o755)

get('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.38.0/ecj-3.38.0.jar',
    tc / 'ecj.jar')
for name in ['api', 'provider', 'aidl', 'shared']:
    archive = deps / (name + '.aar')
    get('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/' + name
        + '/13.1.5/' + name + '-13.1.5.aar', archive)
    with zipfile.ZipFile(archive) as z:
        (deps / (name + '.jar')).write_bytes(z.read('classes.jar'))
get('https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/'
    'annotation-1.3.0.jar', deps / 'annotation.jar')

print('Review and accept Android SDK licences in the following prompt.', flush=True)
run_manager('--licenses', timeout=900)
run_manager('platforms;android-35', 'build-tools;35.0.0', timeout=1800)
print('Toolchain ready:', sdk)
