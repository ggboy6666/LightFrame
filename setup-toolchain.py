#!/usr/bin/env python3
"""Download pinned official SDK/compiler/dependencies. Requires internet + Java 17."""
import pathlib,urllib.request,zipfile,os,subprocess,shutil
root=pathlib.Path(__file__).resolve().parent;tc=root.parent/'toolchain';tc.mkdir(exist_ok=True)
sdk=tc/'android-sdk';tools=sdk/'cmdline-tools/latest';deps=root/'deps';deps.mkdir(exist_ok=True)
def get(url,p):
 if not p.exists(): print('Downloading',p.name,flush=True);urllib.request.urlretrieve(url,p)
get('https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip',tc/'cmdtools.zip')
if not (tools/'bin/sdkmanager').exists():
 with zipfile.ZipFile(tc/'cmdtools.zip') as z:
  for i in z.infolist():
   rel=i.filename.removeprefix('cmdline-tools/')
   if rel and not i.is_dir():
    p=tools/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(i))
 for p in (tools/'bin').iterdir():p.chmod(0o755)
get('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.38.0/ecj-3.38.0.jar',tc/'ecj.jar')
for n in ['api','provider','aidl','shared']:
 get(f'https://repo.maven.apache.org/maven2/dev/rikka/shizuku/{n}/13.1.5/{n}-13.1.5.aar',deps/f'{n}.aar')
 with zipfile.ZipFile(deps/f'{n}.aar') as z:(deps/f'{n}.jar').write_bytes(z.read('classes.jar'))
get('https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar',deps/'annotation.jar')
manager=tools/'bin/sdkmanager'
print('Review and accept Android SDK licences in the following prompt.',flush=True)
subprocess.run([str(manager),'--sdk_root='+str(sdk),'--licenses'],check=True)
subprocess.run([str(manager),'--sdk_root='+str(sdk),'platforms;android-35','build-tools;35.0.0'],check=True)
print('Toolchain ready:',sdk)
