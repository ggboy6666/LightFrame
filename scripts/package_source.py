#!/usr/bin/env python3
"""Package project source with one stable top-level folder; never include local device uploads."""
from pathlib import Path
import hashlib
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def version():
    return re.search(r"versionName\s+['\"]([^'\"]+)['\"]", (ROOT / 'app/build.gradle').read_text(encoding='utf-8')).group(1)

def package():
    output = ROOT.parent / 'dist'
    output.mkdir(exist_ok=True)
    archive = output / ('LightFrame-' + version() + '-source.zip')
    excluded = {'.git', 'build', '.gradle', '.idea', '__pycache__', 'toolchain', 'dist'}
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(ROOT.rglob('*')):
            relative = path.relative_to(ROOT)
            if not path.is_file() or any(part in excluded for part in relative.parts):
                continue
            if path.suffix in ('.pyc', '.apk', '.idsig', '.tmp') or path.name in ('.env', 'local.properties'):
                continue
            if not path.resolve().is_relative_to(ROOT.resolve()):
                raise ValueError('Source path escapes project')
            z.write(path, Path('lightframe') / relative)
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None:
            raise ValueError('Source archive CRC failed')
    return archive

if __name__ == '__main__':
    archive = package()
    print(archive)
    print(hashlib.sha256(archive.read_bytes()).hexdigest())
