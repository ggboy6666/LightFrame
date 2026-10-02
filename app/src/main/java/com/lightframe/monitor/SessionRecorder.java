package com.lightframe.monitor;

import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class SessionRecorder implements Closeable {
 public static final String[] COLUMNS;
 static{
  ArrayList<String> a=new ArrayList<>(Arrays.asList("elapsed_s","unix_ms"));Collections.addAll(a,Config.METRICS);
  Collections.addAll(a,"cpuMHz","thermalStatus","charging","layer","frameStatus","frameAvailable","frameWindowReady","frameWindowStale","frameDataAgeMs","frameWindowSpanMs","captureGapCount","foregroundPackage","foregroundStatus","frameSourceVerified","frameProbeCount","frameCandidateCount","frameProbeFailures","gpuLoadStatus","gpuFrequencyStatus","cpuTemperatureStatus","gpuTemperatureStatus","socTemperatureStatus","thermalServiceStatus","thermalServiceSource","thermalServiceReadNs","thermalServiceAgeMs","paused","longFramesEstimate","bigLongFramesEstimate");
  Collections.addAll(a,"gpuLoadKind","gpuWindowBeginNs","gpuWindowEndNs","gpuAgeMs","gpuFrequencySampleNs","gpuFrequencyAgeMs","gpuTraceStatus","gpuTraceError");
  for(int i=0;i<16;i++)a.add("cpu"+i+"MHz");COLUMNS=a.toArray(new String[0]);
 }
 public final File dir;
 public final FrameStats frames=new FrameStats(false);
 public final long startNs=System.nanoTime(),startMs=System.currentTimeMillis();
 public int rows;
 private long lastFrame,lastFlush,segment;
 private String layer="";
 private final BufferedWriter samples,frameFile;
 private final JSONObject metadata=new JSONObject();
 private boolean closed;
 public SessionRecorder(File root,Config c,Backend backend)throws Exception{
  dir=new File(root,new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+"_"+UUID.randomUUID().toString().substring(0,6));if(!dir.mkdirs())throw new IOException("创建记录目录失败");
  samples=writer(new File(dir,"samples.csv"));frameFile=writer(new File(dir,"frames.csv"));samples.write("\uFEFF"+CsvIndex.encode(COLUMNS)+"\n");
  frameFile.write("\uFEFFelapsed_s,present_ns,interval_ms,long_frame_estimate,big_long_frame_estimate,segment,layer,target_fps\n");
  metadata.put("version",Config.VERSION);metadata.put("title",c.title);metadata.put("package",c.packageName);metadata.put("device",Build.MANUFACTURER+" "+Build.MODEL);metadata.put("android",Build.VERSION.RELEASE);metadata.put("backend",backend.name);metadata.put("uid",backend.uid);metadata.put("startedUnixMs",startMs);metadata.put("targetFps",c.targetFps);metadata.put("config",c.json());
  metadata.put("definitions","录制时保存原始采样和实际呈现帧，仅计算约 1 秒实时 FPS；停止后流式计算全部统计。CPU/RAM: 系统；功率: 电池侧 |电流×电压|；长帧: >1.5 倍目标帧预算估算，严重长帧 >3 倍；1%/0.1% Low: 最慢帧时间均值的倒数，4096ms 内 0.5ms 分桶，以上对数分桶近似。暂停、切换来源和采集缺口不连接成帧间隔。空值代表未取得；数据距今标记异步指标年龄。RSS 含共享页；开销不含 Shizuku 管理器、Perfetto 子进程 / 系统服务、系统采集处理和 GPU 绘制。网络汇总可能重复计入 VPN。与商业工具的口径不等同。");
  metadata.put("gpuDefinitions","GPU 负载来源随原始行保存。work_activity 为系统 gpu_work_period 报告的完整 GPU 工作区间并集占窗口墙钟时间的比例，跨应用重叠只计算一次；保护内容可能不报告。不是着色器核心利用率。异步窗口起止、窗口距今和频率事件距今单独记录；缺失、部分活跃区间或追踪丢失不填零。");
  metadata.put("startedMonotonicNs",startNs);
  metadata.put("analysisStatus","pending");frames.discontinuity(startNs);SessionAnalysis.writeAtomic(dir,"metadata.json",metadata);summary("recording","");
 }
 private static BufferedWriter writer(File file)throws IOException{return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8),32768);}
 public synchronized JSONObject append(JSONObject sample,JSONArray present,long now,Config c)throws Exception{
  if(closed)throw new IOException("记录已关闭");String current=sample.optString("layer","");
  if(frames.updateSource(current,now)){lastFrame=0;segment++;layer=current;}sample.put("layer",layer);
  boolean captureGap=false;
  if(present!=null){
   long oldest=Long.MAX_VALUE,newest=0;int valid=0;
   for(int i=0;i<present.length();i++){long p=present.optLong(i);if(p>0&&p<=now+100_000_000L){oldest=Math.min(oldest,p);newest=Math.max(newest,p);valid++;}}
   if(valid>0&&frames.beginBatch(oldest,newest,valid)){lastFrame=0;segment++;captureGap=true;}
   for(int i=0;i<present.length();i++){
    long p=present.optLong(i);if(p>now+100_000_000L)continue;
    if(frames.add(p,c.targetFps)){
     double ms=lastFrame==0?Double.NaN:(p-lastFrame)/1e6;
     frameFile.write(CsvIndex.encode(new Object[]{(p-startNs)/1e9,p,Double.isFinite(ms)?ms:"","","",segment,layer,c.targetFps})+"\n");lastFrame=p;
    }
   }
  }
  boolean ready=c.frames&&!current.isEmpty()&&present!=null&&present.length()>0&&sample.optBoolean("frameAvailable",true);
  SessionAnalysis.putFinite(sample,"fps",ready?frames.fps(now):Double.NaN);SessionAnalysis.putFinite(sample,"frameMs",ready?frames.frameMs(now):Double.NaN);
  boolean windowReady=ready&&frames.windowReady(now),windowStale=ready&&frames.windowStale(now);sample.put("frameWindowReady",windowReady);sample.put("frameWindowStale",windowStale);
  if(ready&&!windowReady)sample.put("frameStatus",windowStale?"最新呈现帧已过期，FPS 暂不可测；实际长帧仍保留在逐帧数据":captureGap?"帧缓冲未衔接，正在重建窗口；可减小帧采样间隔":"正在收集完整呈现窗口（约1秒）");
  SessionAnalysis.putFinite(sample,"frameDataAgeMs",ready?frames.frameDataAgeMs(now):Double.NaN);SessionAnalysis.putFinite(sample,"frameWindowSpanMs",ready?frames.windowSpanMs():Double.NaN);sample.put("captureGapCount",frames.captureGapCount);
  sample.put("longFramesEstimate",JSONObject.NULL);sample.put("bigLongFramesEstimate",JSONObject.NULL);sample.put("paused",false);writeRow(sample,now);
  if(now-lastFlush>2_000_000_000L){samples.flush();frameFile.flush();lastFlush=now;}return sample;
 }
 private void writeRow(JSONObject json,long now)throws Exception{
  json.put("elapsed_s",(now-startNs)/1e9);json.put("unix_ms",System.currentTimeMillis());Object[] row=new Object[COLUMNS.length];
  for(int i=0;i<row.length;i++){Object value=json.opt(COLUMNS[i]);row[i]=value==null||value==JSONObject.NULL||value instanceof Number&&!Double.isFinite(((Number)value).doubleValue())?"":value;}
  samples.write(CsvIndex.encode(row)+"\n");rows++;
 }
 public synchronized void pause(String why)throws Exception{
  long now=System.nanoTime();frames.discontinuity(now);lastFrame=0;segment++;JSONObject paused=new JSONObject();paused.put("paused",true);paused.put("frameStatus",why);writeRow(paused,now);samples.flush();frameFile.flush();
 }
 public synchronized void resume(){frames.discontinuity(System.nanoTime());lastFrame=0;segment++;}
 /** Only lightweight metadata is written while capture is active. */
 public synchronized void summary(String status,String error)throws Exception{
  metadata.put("status",status);metadata.put("error",error==null?"":error);metadata.put("samples",rows);metadata.put("durationSeconds",(System.nanoTime()-startNs)/1e9);metadata.put("layer",layer);metadata.put("captureGapCount",frames.captureGapCount);metadata.put("statistics",new JSONObject());
  for(String key:new String[]{"capturedIntervals","capturedFrameAverageFps","low1Pct","low01Pct","frameTimeP95Ms","frameTimeP99Ms","longFramesEstimate","bigLongFramesEstimate"})metadata.put(key,JSONObject.NULL);
  SessionAnalysis.writeAtomic(dir,"summary.json",metadata);
 }
 /** Close raw files first; expensive statistics are explicitly deferred to analyze(). */
 public synchronized void finish(String error)throws Exception{
  if(closed)return;closed=true;IOException failure=null;
  try{samples.close();}catch(IOException e){failure=e;}try{frameFile.close();}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
  String captureError=error==null?"":error;if(failure!=null)captureError+=(captureError.isEmpty()?"":"; ")+failure.toString();
  metadata.put("captureStatus",captureError.isEmpty()?"complete":"error");metadata.put("analysisStatus","pending");metadata.put("stoppedUnixMs",System.currentTimeMillis());summary("analyzing",captureError);
  if(failure!=null)throw failure;
 }
 public JSONObject analyze(SessionAnalysis.Progress progress)throws Exception{
  JSONObject seed;synchronized(this){if(!closed)throw new IOException("记录仍在采集，无法分析");seed=new JSONObject(metadata.toString());}
  return SessionAnalysis.analyze(dir,seed,progress);
 }
 public void close()throws IOException{try{finish("");}catch(Exception e){throw new IOException(e);}}
}
