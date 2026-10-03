#!/usr/bin/env python3
import os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent
ecj=pathlib.Path(os.environ.get('ECJ_JAR',root.parent/'toolchain/ecj.jar'))
json_jar=pathlib.Path(os.environ.get('ANDROID_JSON_JAR',root.parent/'toolchain/test-deps/android-json-0.0.20131108.vaadin1.jar'))
if not json_jar.is_file():raise SystemExit('Missing Android JSON test runtime. Run setup-toolchain.py or set ANDROID_JSON_JAR.')
out=root/'build/tests';out.mkdir(parents=True,exist_ok=True)
names=['Numbers', 'FrameBudget', 'FrameStats', 'CsvIndex', 'ChartData', 'SurfaceLayers', 'SurfaceLatency', 'FrameDiscovery', 'ThermalSnapshot', 'NodeRetry', 'HistoryTime', 'HistoryRange', 'HistoryViewport', 'HistorySelection', 'SessionStats', 'SessionAnalysis', 'FpsSampleValidity', 'ChartAxis', 'ChartGeometry', 'GpuTraceProbe', 'GpuTraceData', 'GpuTraceSession', 'SamplingIntervals', 'UpdateRelease', 'RecordDeletion', 'OverlayStyle', 'SamplingCadence', 'FrameCsvWriter', 'RecordingFlushSchedule']
tests=['CoreTests', 'SurfaceLayerTests', 'SurfaceLatencyTests', 'FrameDiscoveryTests', 'ThermalSnapshotTests', 'FrameStatsTests', 'NodeRetryTests', 'HistoryTests', 'SessionAnalysisTests', 'FpsSampleValidityTests', 'ChartLayoutTests', 'GpuTraceProbeTests', 'GpuPerfettoTests', 'GpuTraceSessionTests', 'SamplingIntervalsTests', 'FrameBudgetTests', 'UpdateReleaseTests', 'RecordDeletionTests', 'OverlayStyleTests', 'SamplingCadenceTests', 'RecordingHotPathTests']
src=[root/f'app/src/main/java/com/lightframe/monitor/{n}.java' for n in names]+[root/f'tests/{name}.java' for name in tests]
subprocess.run(['java','-jar',str(ecj),'-8','-encoding','UTF-8','-warn:none','-classpath',str(json_jar),'-d',str(out)]+list(map(str,src)),check=True)
for name in tests:
 subprocess.run(['java','-Xmx32m' if name=='SessionAnalysisTests' else '-Xmx256m','-cp',os.pathsep.join([str(out),str(json_jar)]),'com.lightframe.monitor.'+name],cwd=root,check=True)
