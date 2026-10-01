#!/usr/bin/env python3
import os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent
ecj=pathlib.Path(os.environ.get('ECJ_JAR',root.parent/'toolchain/ecj.jar'))
out=root/'build/tests';out.mkdir(parents=True,exist_ok=True)
names=['Numbers','FrameStats','CsvIndex','ChartData']
src=[root/f'app/src/main/java/com/lightframe/monitor/{n}.java' for n in names]+[root/'tests/CoreTests.java']
subprocess.run(['java','-jar',str(ecj),'-8','-encoding','UTF-8','-warn:none','-d',str(out)]+list(map(str,src)),check=True)
subprocess.run(['java','-Xmx256m','-cp',str(out),'com.lightframe.monitor.CoreTests'],check=True)
