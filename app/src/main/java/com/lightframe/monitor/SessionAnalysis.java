package com.lightframe.monitor;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Streams immutable raw CSVs, writes a completed summary, and repairs older records. */
public final class SessionAnalysis {
 public static final int ANALYSIS_VERSION=2;
 public interface Progress { void onProgress(long sampleRows,long frameRows); }
 private static final Object[] LOCKS=new Object[16];
 static{for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object();}
 private static final Set<String> NON_METRICS=new HashSet<>(Arrays.asList(
  "elapsed_s","unix_ms","cpuMHz","thermalStatus","charging","layer","paused","frameStatus","frameAvailable",
  "frameWindowReady","frameWindowStale","frameProbeCount","frameCandidateCount","frameProbeFailures","frameDataAgeMs","frameWindowSpanMs","captureGapCount",
  "foregroundPackage","foregroundStatus","frameSourceVerified","gpuLoadStatus","gpuFrequencyStatus","cpuTemperatureStatus",
  "gpuTemperatureStatus","socTemperatureStatus","thermalServiceStatus","thermalServiceSource","thermalServiceReadNs","thermalServiceAgeMs",
  "longFramesEstimate","bigLongFramesEstimate"));
 private SessionAnalysis(){}
 private static Object lock(File dir){String path;try{path=dir.getCanonicalPath();}catch(IOException e){path=dir.getAbsolutePath();}return LOCKS[(path.hashCode()&0x7fffffff)%LOCKS.length];}
 public static JSONObject read(File dir){
  for(String name:new String[]{"summary.json","metadata.json","summary.tmp"}){
   File file=new File(dir,name);if(!file.isFile()||file.length()>1048576)continue;
   try{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
  }
  return new JSONObject();
 }
 public static JSONObject ensure(File dir)throws Exception{return ensure(dir,null);}
 public static JSONObject ensure(File dir,Progress progress)throws Exception{
  synchronized(lock(dir)){checkInterrupted();JSONObject seed=read(dir);if(completeAndUnchanged(dir,seed))return seed;return analyzeLocked(dir,seed,progress);}
 }
 public static JSONObject analyze(File dir,Progress progress)throws Exception{return analyze(dir,null,progress);}
 public static JSONObject analyze(File dir,JSONObject seed,Progress progress)throws Exception{
  synchronized(lock(dir)){checkInterrupted();return analyzeLocked(dir,seed==null?read(dir):seed,progress);}
 }
 private static JSONObject analyzeLocked(File dir,JSONObject seed,Progress progress)throws Exception{
  File samples=new File(dir,"samples.csv"),framesFile=new File(dir,"frames.csv");
  if(!samples.isFile())throw new IOException("找不到原始 samples.csv，无法恢复统计");
  long sampleLength=samples.length(),sampleModified=samples.lastModified(),frameLength=framesFile.isFile()?framesFile.length():-1,frameModified=framesFile.isFile()?framesFile.lastModified():-1;
  long sampleCompleteBytes=completeBytes(samples),frameCompleteBytes=frameLength>0?completeBytes(framesFile):0;
  long sampleTailBytes=sampleLength-sampleCompleteBytes,frameTailBytes=frameLength>0?frameLength-frameCompleteBytes:0;
  JSONObject result=new JSONObject(seed.toString());
  Map<String,SessionStats.Metric> metrics=new LinkedHashMap<>();
  long sampleRows=0,frameRows=0,incompleteSamples=sampleTailBytes>0?1:0,incompleteFrames=frameTailBytes>0?1:0,captureGaps=0,firstUnixMs=0,invalidFpsZeros=0;
  double duration=0;String layer=seed.optString("layer","");notify(progress,0,0);
  try(BufferedReader reader=reader(samples,sampleCompleteBytes)){
   String[] header=header(reader);int elapsed=column(header,"elapsed_s"),unix=column(header,"unix_ms"),layerCol=column(header,"layer"),paused=column(header,"paused"),gaps=column(header,"captureGapCount");
   if(elapsed<0)throw new IOException("原始采样缺少 elapsed_s 列，保留原始文件待检查");
   FpsSampleValidity fpsValidity=new FpsSampleValidity(header);boolean[] numeric=new boolean[header.length];for(int i=0;i<header.length;i++)numeric[i]=!NON_METRICS.contains(header[i])&&!header[i].isEmpty();
   String line;while((line=reader.readLine())!=null){
    if(line.isEmpty())continue;String[] row=CsvIndex.parse(line);sampleRows++;if(row.length<header.length)incompleteSamples++;
    double seconds=number(cell(row,elapsed));if(Double.isFinite(seconds)&&seconds>=0)duration=Math.max(duration,seconds);
    long unixMs=integer(cell(row,unix));if(firstUnixMs==0&&unixMs>0)firstUnixMs=unixMs;
    String nextLayer=cell(row,layerCol);if(!nextLayer.isEmpty())layer=nextLayer;
    captureGaps=Math.max(captureGaps,integer(cell(row,gaps)));
    if(!Boolean.parseBoolean(cell(row,paused)))for(int i=0;i<Math.min(row.length,header.length);i++)if(numeric[i]){
     double value=number(row[i]);if(header[i].equals("fps")&&fpsValidity.invalidZero(value,row)){invalidFpsZeros++;value=Double.NaN;}if(Double.isFinite(value)){SessionStats.Metric stat=metrics.get(header[i]);if(stat==null){stat=new SessionStats.Metric(header[i].equals("fps"));metrics.put(header[i],stat);}stat.add(value);}
    }
    if((sampleRows&4095)==0)notify(progress,sampleRows,0);
   }
  }
  notify(progress,sampleRows,0);
  JSONObject config=seed.optJSONObject("config");double target=seed.optDouble("targetFps",config==null?Double.NaN:config.optDouble("targetFps",Double.NaN));
  SessionStats.Frames frames=new SessionStats.Frames();boolean frameAnalysisAvailable=false;
  if(frameCompleteBytes>0){
   try(BufferedReader reader=reader(framesFile,frameCompleteBytes)){
    String[] header=header(reader);int elapsed=column(header,"elapsed_s"),present=column(header,"present_ns"),interval=column(header,"interval_ms"),segment=column(header,"segment"),layerCol=column(header,"layer"),targetCol=column(header,"target_fps"),longCol=column(header,"long_frame_estimate"),bigCol=column(header,"big_long_frame_estimate");
    frameAnalysisAvailable=interval>=0;String previousSegment=null,previousLayer=null;long previousPresent=0;
    String line;while((line=reader.readLine())!=null){
     if(line.isEmpty())continue;String[] row=CsvIndex.parse(line);frameRows++;if(row.length<header.length)incompleteFrames++;
     double seconds=number(cell(row,elapsed));if(Double.isFinite(seconds)&&seconds>=0)duration=Math.max(duration,seconds);
     String nextSegment=cell(row,segment),nextLayer=cell(row,layerCol);
     boolean changed=(segment>=0&&!Objects.equals(nextSegment,previousSegment))||(layerCol>=0&&!Objects.equals(nextLayer,previousLayer));
     long stamp=integer(cell(row,present));double ms=number(cell(row,interval)),rowTarget=number(cell(row,targetCol));if(!Double.isFinite(rowTarget)||rowTarget<=0)rowTarget=target;
     boolean monotonic=present<0||(stamp>0&&(previousPresent==0||stamp>previousPresent));
     if(frameAnalysisAvailable&&!changed&&monotonic&&Double.isFinite(ms)&&ms>0)frames.add(ms,rowTarget,Boolean.parseBoolean(cell(row,longCol)),Boolean.parseBoolean(cell(row,bigCol)));
     previousPresent=stamp>0?stamp:0;previousSegment=nextSegment;previousLayer=nextLayer;
     if((frameRows&4095)==0)notify(progress,sampleRows,frameRows);
    }
   }
  }
  notify(progress,sampleRows,frameRows);
  if(sampleLength!=samples.length()||sampleModified!=samples.lastModified()||frameLength!=(framesFile.isFile()?framesFile.length():-1)||frameModified!=(framesFile.isFile()?framesFile.lastModified():-1))throw new IOException("原始数据仍在写入，请在记录停止后分析");
  JSONObject statistics=new JSONObject();for(Map.Entry<String,SessionStats.Metric> entry:metrics.entrySet()){
   SessionStats.Metric value=entry.getValue();JSONObject stat=new JSONObject();stat.put("count",value.count);putFinite(stat,"min",value.min);putFinite(stat,"avg",value.mean);putFinite(stat,"max",value.max);putFinite(stat,"stdDev",value.deviation());
   if(entry.getKey().equals("fps")){putFinite(stat,"p1",value.percentile(.01));putFinite(stat,"p5",value.percentile(.05));stat.put("percentileBinFps",.5);}statistics.put(entry.getKey(),stat);
  }
  String captureStatus=seed.optString("captureStatus",seed.optString("status","partial"));boolean interrupted=!captureStatus.equals("complete")&&!captureStatus.equals("error");
  result.put("status",interrupted||incompleteSamples>0||incompleteFrames>0?"partial":captureStatus);result.put("analysisStatus","complete");result.put("analysisVersion",ANALYSIS_VERSION);
  result.put("statistics",statistics);result.put("samples",sampleRows);result.put("frames",frameRows);result.put("excludedUnavailableFpsZeros",invalidFpsZeros);result.put("fpsZeroPolicy","zero excluded only with same-row unavailable/stale window evidence; raw frames unchanged");double oldDuration=seed.optDouble("durationSeconds",Double.NaN);if(Double.isFinite(oldDuration)&&oldDuration>=0)duration=Math.max(duration,oldDuration);putFinite(result,"durationSeconds",duration);
  result.put("layer",layer);result.put("capturedIntervals",frames.count);putFinite(result,"capturedFrameAverageFps",frames.averageFps());putFinite(result,"low1Pct",frames.low(.01));putFinite(result,"low01Pct",frames.low(.001));putFinite(result,"frameTimeP95Ms",frames.percentile(.95));putFinite(result,"frameTimeP99Ms",frames.percentile(.99));
  result.put("longFramesEstimate",frames.longFrames);result.put("bigLongFramesEstimate",frames.bigLongFrames);result.put("frameAnalysisAvailable",frameAnalysisAvailable);result.put("captureGapCount",Math.max(captureGaps,seed.optLong("captureGapCount",0)));result.put("incompleteSampleRows",incompleteSamples);result.put("incompleteFrameRows",incompleteFrames);
  result.put("discardedSampleTailBytes",sampleTailBytes);result.put("discardedFrameTailBytes",frameTailBytes);
  result.put("framePercentileMethod","0.5ms bins through 4096ms; logarithmic above; full CSV streamed");
  if(!result.has("title"))result.put("title",dir.getName());if(!result.has("startedUnixMs")&&firstUnixMs>0)result.put("startedUnixMs",firstUnixMs);if(!result.has("targetFps"))putFinite(result,"targetFps",target);if(interrupted)result.put("recovered",true);
  result.put("analysisSampleBytes",sampleLength);result.put("analysisSampleModifiedMs",sampleModified);result.put("analysisFrameBytes",frameLength);result.put("analysisFrameModifiedMs",frameModified);result.put("analyzedUnixMs",System.currentTimeMillis());
  writeAtomic(dir,"summary.json",result);return result;
 }
 private static boolean completeAndUnchanged(File dir,JSONObject json){
  File samples=new File(dir,"samples.csv"),frames=new File(dir,"frames.csv");return samples.isFile()&&json.optInt("analysisVersion",0)==ANALYSIS_VERSION&&json.optString("analysisStatus").equals("complete")&&json.optLong("analysisSampleBytes",-2)==samples.length()&&json.optLong("analysisSampleModifiedMs",-2)==samples.lastModified()&&json.optLong("analysisFrameBytes",-2)==(frames.isFile()?frames.length():-1)&&json.optLong("analysisFrameModifiedMs",-2)==(frames.isFile()?frames.lastModified():-1);
 }
 private static long completeBytes(File file)throws IOException{
  try(RandomAccessFile input=new RandomAccessFile(file,"r")){
   long end=input.length();byte[] bytes=new byte[8192];
   while(end>0){checkInterrupted();int count=(int)Math.min(bytes.length,end);long start=end-count;input.seek(start);input.readFully(bytes,0,count);for(int i=count-1;i>=0;i--)if(bytes[i]=='\n')return start+i+1;end=start;}
   return 0;
  }
 }
 private static BufferedReader reader(File file,long completeBytes)throws IOException{
  InputStream input=new FilterInputStream(new FileInputStream(file)){
   private long remaining=completeBytes;
   @Override public int read()throws IOException{if(remaining==0)return -1;int value=super.read();if(value>=0)remaining--;return value;}
   @Override public int read(byte[] bytes,int start,int count)throws IOException{if(remaining==0)return -1;int read=super.read(bytes,start,(int)Math.min(count,remaining));if(read>0)remaining-=read;return read;}
  };
  return new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8),32768);
 }
 private static String[] header(BufferedReader reader)throws IOException{String line=reader.readLine();if(line==null)throw new IOException("原始 CSV 为空");return CsvIndex.parse(line.startsWith("\uFEFF")?line.substring(1):line);}
 private static int column(String[] header,String name){for(int i=0;i<header.length;i++)if(header[i].equals(name))return i;return -1;}
 private static String cell(String[] row,int index){return index>=0&&index<row.length?row[index]:"";}
 private static double number(String value){try{return Double.parseDouble(value);}catch(Exception e){return Double.NaN;}}
 private static long integer(String value){try{return Long.parseLong(value);}catch(Exception e){return 0;}}
 private static void checkInterrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消记录分析，原始数据和摘要保留");}
 private static void notify(Progress progress,long samples,long frames)throws InterruptedIOException{checkInterrupted();if(progress!=null)progress.onProgress(samples,frames);checkInterrupted();}
 public static void putFinite(JSONObject json,String key,double value)throws JSONException{json.put(key,Double.isFinite(value)?value:JSONObject.NULL);}
 /** The previous summary stays intact unless a fully written replacement can be moved atomically. */
 public static void writeAtomic(File dir,String name,JSONObject json)throws Exception{
  synchronized(lock(dir)){
   checkInterrupted();
   File temporary=File.createTempFile(name+"-",".tmp",dir);
   try{
    try(FileOutputStream stream=new FileOutputStream(temporary);Writer writer=new OutputStreamWriter(stream,StandardCharsets.UTF_8)){
     String content=json.toString(2);if(content==null)throw new IOException("摘要包含无效数值");writer.write(content);writer.flush();stream.getFD().sync();
    }
    checkInterrupted();Files.move(temporary.toPath(),new File(dir,name).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   }finally{if(temporary.exists())temporary.delete();}
  }
 }
}
