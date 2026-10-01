#!/usr/bin/env python3
"""Offline-capable small APK build using official SDK + ECJ; no emulator, no Gradle daemon."""
import os, pathlib, subprocess, zipfile, shutil, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',ROOT.parent/'toolchain/android-sdk'))
ECJ=pathlib.Path(os.environ.get('ECJ_JAR',ROOT.parent/'toolchain/ecj.jar'))
BT=SDK/'build-tools/35.0.0'; ANDROID=SDK/'platforms/android-35/android.jar'
B=ROOT/'build';B.mkdir(exist_ok=True); OUT=ROOT.parent/'dist';OUT.mkdir(exist_ok=True)
def run(*args):
 print('>',str(args[0]).split('/')[-1],flush=True)
 subprocess.run([str(x) for x in args],cwd=ROOT,check=True)
for name in ['api','provider','aidl','shared']:
 if not (ROOT/f'deps/{name}.jar').exists():
  with zipfile.ZipFile(ROOT/f'deps/{name}.aar') as z:(ROOT/f'deps/{name}.jar').write_bytes(z.read('classes.jar'))
GEN=B/'gen';CLASSES=B/'classes';DEX=B/'dex'
for p in [GEN,CLASSES,DEX]:
 if p.exists():shutil.rmtree(p)
 p.mkdir()
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml');ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
manifest.getroot().set('package','com.lightframe.monitor');manifest.getroot().set('{'+ns+'}versionCode','2');manifest.getroot().set('{'+ns+'}versionName','0.2.0')
uses=ET.Element('uses-sdk',{'{'+ns+'}minSdkVersion':'26','{'+ns+'}targetSdkVersion':'35'});manifest.getroot().insert(0,uses);manifest.write(B/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
run(BT/'aapt2','compile','--dir',ROOT/'app/src/main/res','-o',B/'resources.zip')
run(BT/'aapt2','link','-o',B/'base.apk','--manifest',B/'AndroidManifest.xml','-I',ANDROID,'--java',GEN,'-A',ROOT/'app/src/main/assets',B/'resources.zip')
deps=list((ROOT/'deps').glob('*.jar'));sources=list((ROOT/'app/src/main/java').rglob('*.java'))+list(GEN.rglob('*.java'))
run('java','-jar',ECJ,'-8','-encoding','UTF-8','-warn:none','-classpath',os.pathsep.join(map(str,[ANDROID]+deps)),'-d',CLASSES,*sources)
with zipfile.ZipFile(B/'classes.jar','w') as z:
 for f in CLASSES.rglob('*.class'):z.write(f,f.relative_to(CLASSES))
run(BT/'d8','--release','--min-api','26','--lib',ANDROID,'--output',DEX,B/'classes.jar',*deps)
shutil.copy(B/'base.apk',B/'unsigned.apk')
with zipfile.ZipFile(B/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for f in DEX.glob('*.dex'):z.write(f,f.name)
KEY=ROOT/'dev-signing/lightframe-test.jks';KEY.parent.mkdir(exist_ok=True)
if not KEY.exists():run('keytool','-genkeypair','-keystore',KEY,'-alias','lightframe','-storepass','changeit','-keypass','changeit','-keyalg','RSA','-keysize','2048','-validity','36500','-dname','CN=LightFrame Beta, O=LightFrame')
run(BT/'zipalign','-f','4',B/'unsigned.apk',B/'aligned.apk')
apk=OUT/'LightFrame-0.2.0-beta.apk'
run(BT/'apksigner','sign','--ks',KEY,'--ks-key-alias','lightframe','--ks-pass','pass:changeit','--key-pass','pass:changeit','--out',apk,B/'aligned.apk')
run(BT/'apksigner','verify','--verbose',apk)
print('APK:',apk,'bytes:',apk.stat().st_size,flush=True)
