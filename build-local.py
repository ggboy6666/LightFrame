#!/usr/bin/env python3
"""Build signed Full/Lite Android APKs with the official SDK and ECJ."""
import argparse, os, pathlib, subprocess, zipfile, shutil, re, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',ROOT.parent/'toolchain/android-sdk'))
ECJ=pathlib.Path(os.environ.get('ECJ_JAR',ROOT.parent/'toolchain/ecj.jar'))
BT=SDK/'build-tools/35.0.0'; ANDROID=SDK/'platforms/android-35/android.jar'
def sdktool(name):
 suffix=('.bat' if name in ['d8','apksigner'] else '.exe') if os.name=='nt' else ''
 return BT/(name+suffix)
def run(*args):
 print('>',str(args[0]).split('/')[-1],flush=True)
 subprocess.run([str(x) for x in args],cwd=ROOT,check=True)
def fresh(path):
 if not path.resolve().is_relative_to((ROOT/'build').resolve()):raise RuntimeError('Generated path escapes build directory')
 if path.exists():shutil.rmtree(path)
 path.mkdir(parents=True)
def signing(variant):
 # The historic test key is public. Stable releases require the private release key.
 private_value=None if args.development_signing else os.environ.get('LIGHTFRAME_SIGNING_KEYSTORE')
 common=['--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false']
 if not private_value:return ['--ks',KEY,'--ks-key-alias','lightframe','--ks-pass','pass:changeit','--key-pass','pass:changeit']+common
 private=pathlib.Path(private_value)
 if not private.is_file() or not os.environ.get('LIGHTFRAME_SIGNING_STORE_PASSWORD'):raise RuntimeError('Private release keystore/password configuration is incomplete')
 if not os.environ.get('LIGHTFRAME_SIGNING_KEY_PASSWORD'):os.environ['LIGHTFRAME_SIGNING_KEY_PASSWORD']=os.environ['LIGHTFRAME_SIGNING_STORE_PASSWORD']
 latest=['--ks',private,'--ks-key-alias',os.environ.get('LIGHTFRAME_SIGNING_ALIAS','lightframe-release'),'--ks-pass','env:LIGHTFRAME_SIGNING_STORE_PASSWORD','--key-pass','env:LIGHTFRAME_SIGNING_KEY_PASSWORD']
 if variant=='lite':return latest+common
 lineage=pathlib.Path(os.environ.get('LIGHTFRAME_SIGNING_LINEAGE',''))
 if not lineage.is_file():raise RuntimeError('Full release requires the old-to-private signing lineage')
 original=['--ks',KEY,'--ks-key-alias','lightframe','--ks-pass','pass:changeit','--key-pass','pass:changeit']
 return original+['--next-signer']+latest+['--lineage',lineage,'--rotation-min-sdk-version','28']+common
def build(variant,version_code,version_name,resources):
 application_id='com.lightframe.monitor'+('.lite' if variant=='lite' else '')
 b=ROOT/'build'/variant;b.mkdir(parents=True,exist_ok=True)
 gen=b/'gen';classes=b/'classes';dex=b/'dex'
 for path in [gen,classes,dex]:fresh(path)
 manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml');ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
 tree=manifest.getroot();tree.set('package',application_id);tree.set('{'+ns+'}versionCode',version_code);tree.set('{'+ns+'}versionName',version_name)
 for element in tree.iter():
  for key,value in list(element.attrib.items()):element.set(key,value.replace('${applicationId}',application_id))
 app=tree.find('application');app.set('{'+ns+'}label','轻帧精简版' if variant=='lite' else '轻帧')
 uses=ET.Element('uses-sdk',{'{'+ns+'}minSdkVersion':'26','{'+ns+'}targetSdkVersion':'35'});tree.insert(0,uses);manifest.write(b/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
 run(sdktool('aapt2'),'link','-o',b/'base.apk','--manifest',b/'AndroidManifest.xml','-I',ANDROID,'--custom-package','com.lightframe.monitor','--java',gen,'-A',ROOT/'app/src/main/assets',resources)
 flavor=gen/'com/lightframe/monitor/Flavor.java';flavor.parent.mkdir(parents=True,exist_ok=True)
 flavor.write_text('package com.lightframe.monitor; public final class Flavor { public static final boolean LITE = '+str(variant=='lite').lower()+'; public static final String APPLICATION_ID = "'+application_id+'"; public static final String VARIANT = "'+variant+'"; public static final String APK_SUFFIX = "'+variant+'"; private Flavor() {} }',encoding='utf-8')
 deps=list((ROOT/'deps').glob('*.jar'));sources=list((ROOT/'app/src/main/java').rglob('*.java'))+list(gen.rglob('*.java'))
 run('java','-jar',ECJ,'-8','-encoding','UTF-8','-warn:none','-classpath',os.pathsep.join(map(str,[ANDROID]+deps)),'-d',classes,*sources)
 with zipfile.ZipFile(b/'classes.jar','w') as z:
  for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes))
 run(sdktool('d8'),'--release','--min-api','26','--lib',ANDROID,'--output',dex,b/'classes.jar',*deps)
 # Normalize Windows aapt2 backslash names in both ZIP headers, keeping compiled data intact.
 with zipfile.ZipFile(b/'base.apk') as original,zipfile.ZipFile(b/'unsigned.apk','w') as z:
  for entry in original.infolist():
   clean=zipfile.ZipInfo(entry.filename,entry.date_time);clean.compress_type=entry.compress_type;clean.external_attr=entry.external_attr;clean.create_system=entry.create_system
   z.writestr(clean,original.read(entry))
  for f in dex.glob('*.dex'):z.write(f,f.name,compress_type=zipfile.ZIP_DEFLATED)
 with zipfile.ZipFile(b/'unsigned.apk') as z:
  if z.testzip() is not None:raise RuntimeError('APK ZIP integrity check failed')
 run(sdktool('zipalign'),'-f','4',b/'unsigned.apk',b/'aligned.apk')
 apk=OUT/f'LightFrame-{version_name}-{variant}{"-dev" if args.development_signing else ""}.apk'
 run(sdktool('apksigner'),'sign',*signing(variant),'--out',apk,b/'aligned.apk')
 run(sdktool('apksigner'),'verify','--verbose',apk)
 print('APK:',apk,'bytes:',apk.stat().st_size,flush=True)
parser=argparse.ArgumentParser();parser.add_argument('--variant',choices=['full','lite','all'],default='all');parser.add_argument('--development-signing',action='store_true',help='Build explicitly marked local development APKs using the public test key');args=parser.parse_args()
OUT=ROOT.parent/'dist';OUT.mkdir(exist_ok=True);B=ROOT/'build';B.mkdir(exist_ok=True)
for name in ['api','provider','aidl','shared']:
 if not (ROOT/f'deps/{name}.jar').exists():
  with zipfile.ZipFile(ROOT/f'deps/{name}.aar') as z:(ROOT/f'deps/{name}.jar').write_bytes(z.read('classes.jar'))
KEY=ROOT/'dev-signing/lightframe-test.jks';KEY.parent.mkdir(exist_ok=True)
if not KEY.exists():run('keytool','-genkeypair','-keystore',KEY,'-alias','lightframe','-storepass','changeit','-keypass','changeit','-keyalg','RSA','-keysize','2048','-validity','36500','-dname','CN=LightFrame Beta, O=LightFrame')
gradle=(ROOT/'app/build.gradle').read_text(encoding='utf-8');version_code=re.search(r'\bversionCode\s+(\d+)',gradle).group(1);version_name=re.search(r"\bversionName\s+['\"]([^'\"]+)['\"]",gradle).group(1)
if int(version_name.split('.')[0])>=1 and not os.environ.get('LIGHTFRAME_SIGNING_KEYSTORE') and not args.development_signing:raise SystemExit('Stable builds require LIGHTFRAME_SIGNING_KEYSTORE/password/lineage; use --development-signing only for local test APKs')
resources=B/'resources.zip';run(sdktool('aapt2'),'compile','--dir',ROOT/'app/src/main/res','-o',resources)
for variant in (['full','lite'] if args.variant=='all' else [args.variant]):build(variant,version_code,version_name,resources)
