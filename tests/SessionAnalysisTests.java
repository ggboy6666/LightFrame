package com.lightframe.monitor;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class SessionAnalysisTests {
 private static int checks;
 private static final List<File> temporary=new ArrayList<>();
 private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 private static void close(double actual,double expected,String message){check(Double.isFinite(actual)&&Math.abs(actual-expected)<=1e-8*Math.max(1,Math.abs(expected)),message+": "+actual+" != "+expected);}
 private static File dir()throws Exception{File file=Files.createTempDirectory("lightframe-analysis-").toFile();temporary.add(file);return file;}
 private static void write(File dir,String name,String content)throws Exception{Files.write(new File(dir,name).toPath(),content.getBytes(StandardCharsets.UTF_8));}
 private static byte[] digest(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))>0)hash.update(bytes,0,n);}return hash.digest();}
 private static JSONObject seed(String status)throws Exception{return new JSONObject().put("title","回归,记录").put("status",status).put("targetFps",100).put("config",new JSONObject().put("targetFps",100));}
 private static void clean(File file)throws IOException{File[] children=file.listFiles();if(children!=null)for(File child:children)clean(child);Files.deleteIfExists(file.toPath());}
 public static void main(String[] args)throws Exception{
  try{nonFiniteJson();metricsAndLegacyFrames();segmentsAndLongTails();recoveryAndCache();truncationAndFailures();streamingAndConcurrency();System.out.println("Session analysis checks: "+checks);}finally{for(File file:temporary)clean(file);}
 }
 private static void nonFiniteJson()throws Exception{
  for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
   boolean rejected=false;try{new JSONObject().put("value",invalid);}catch(JSONException expected){rejected=true;}check(rejected,"Android JSON rejects non-finite values");
   JSONObject safe=new JSONObject();SessionAnalysis.putFinite(safe,"value",invalid);check(safe.isNull("value"),"non-finite computed result is explicit null");
  }
  SessionStats.Metric huge=new SessionStats.Metric(false);huge.add(1e308);huge.add(-1e308);close(huge.mean,0,"opposite very large values have finite mean");check(!Double.isFinite(huge.deviation()),"overflowing variance is identifiable");
 }
 private static void metricsAndLegacyFrames()throws Exception{
  File dir=dir();write(dir,"samples.csv","\uFEFFelapsed_s,unix_ms,fps,cpuPct,gpuPct,paused,layer,foregroundPackage,foregroundStatus,frameSourceVerified\n0,1234567890123,60,10,,false,\"game,\"\"layer\"\"\",pkg,123,true\n1,1234567891123,60,20,NaN,false,game,pkg,123,true\n2,1234567892123,999,999,999,true,game,pkg,123,true\n3,1234567893123,,30,Infinity,false,game,pkg,123,true\n");
  StringBuilder frames=new StringBuilder("\uFEFFelapsed_s,present_ns,interval_ms,long_frame_estimate,big_long_frame_estimate\n0,1000000000,,false,false\n");long ns=1000000000;
  for(int i=1;i<=100;i++){double ms=i==100?100:10;ns+=(long)(ms*1e6);frames.append(i/100d).append(',').append(ns).append(',').append(ms).append(",false,false\n");}
  // An explicit blank baseline must not connect a long pause to the prior frame.
  frames.append("6,9000000000,,false,false\n6.01,9010000000,10,false,false\n");write(dir,"frames.csv",frames.toString());
  byte[] beforeSamples=digest(new File(dir,"samples.csv")),beforeFrames=digest(new File(dir,"frames.csv"));JSONObject summary=SessionAnalysis.analyze(dir,seed("complete"),null);
  check(summary.getLong("samples")==4,"all raw samples counted including paused marker");JSONObject stats=summary.getJSONObject("statistics");JSONObject cpu=stats.getJSONObject("cpuPct");check(cpu.getLong("count")==3,"paused samples excluded from metrics");close(cpu.getDouble("avg"),20,"all valid CPU samples averaged");close(cpu.getDouble("stdDev"),10,"sample standard deviation");close(cpu.getDouble("min"),10,"minimum");close(cpu.getDouble("max"),30,"maximum");check(!stats.has("gpuPct"),"missing/NaN/infinite metric remains unavailable");check(!stats.has("foregroundStatus"),"numeric-looking status excluded from metrics");close(stats.getJSONObject("fps").getDouble("p1"),60,"constant FPS percentile is unbiased");check(stats.getJSONObject("fps").getLong("count")==2,"all valid recorded live FPS samples used");
  check(summary.getLong("capturedIntervals")==101,"blank old-format interval is not reconstructed across pause");close(summary.getDouble("capturedFrameAverageFps"),1000*101/1100d,"average based on captured intervals");close(summary.getDouble("low1Pct"),1000*2/110d,"1 percent slowest-tail average");close(summary.getDouble("low01Pct"),10,"0.1 percent slowest-tail average");close(summary.getDouble("frameTimeP95Ms"),10,"p95 includes all rows");close(summary.getDouble("frameTimeP99Ms"),10,"p99 includes all rows");check(summary.getLong("longFramesEstimate")==1&&summary.getLong("bigLongFramesEstimate")==1,"long-frame counts use target budget");close(summary.getDouble("durationSeconds"),6.01,"duration includes all raw timestamps");check(Arrays.equals(beforeSamples,digest(new File(dir,"samples.csv")))&&Arrays.equals(beforeFrames,digest(new File(dir,"frames.csv"))),"analysis never changes raw CSV bytes");check(summary.getString("status").equals("complete"),"complete capture remains complete");
 }
 private static void segmentsAndLongTails()throws Exception{
  File dir=dir();write(dir,"samples.csv","elapsed_s,unix_ms,fps,paused,captureGapCount\n0,1234,60,false,0\n70,1334,60,false,1\n");
  write(dir,"frames.csv","elapsed_s,present_ns,interval_ms,long_frame_estimate,big_long_frame_estimate,segment,layer,target_fps\n0,1000000000,,,,1,a,100\n.01,1010000000,10,,,1,a,100\n10,11010000000,10000,,,2,a,100\n10.01,11020000000,10,,,2,a,100\n20,21020000000,10000,,,2,b,100\n28,29020000000,8000,,,2,b,100\n88,89020000000,60000,,,2,b,100\n88.01,89010000000,10,,,2,b,100\n");
  JSONObject summary=SessionAnalysis.analyze(dir,seed("complete"),null);check(summary.getLong("capturedIntervals")==4,"segment and source changes reject connecting intervals; backwards stamps rejected");check(summary.getLong("captureGapCount")==1,"capture gap is preserved");close(summary.getDouble("low01Pct"),1000/60000d,"very long tails retain ordering above 4096ms");close(summary.getDouble("frameTimeP99Ms"),60000,"very long p99 not lumped into low overflow bucket");check(summary.getLong("longFramesEstimate")==2,"long segments count actual frame intervals only");
  SessionStats.Frames direct=new SessionStats.Frames();direct.add(10,Double.NaN,true,false);check(direct.longFrames==1&&direct.bigLongFrames==0,"legacy flags retained if target unavailable");direct.add(Double.NaN,100,true,true);direct.add(0,100,true,true);check(direct.count==1,"invalid/zero frame interval excluded");
 }
 private static void recoveryAndCache()throws Exception{
  File dir=dir();write(dir,"samples.csv","elapsed_s,unix_ms,cpuPct\n0,1234567890123,12\n1,1234567891123,24\n");SessionAnalysis.writeAtomic(dir,"metadata.json",seed("recording"));write(dir,"summary.json","{invalid");
  JSONObject recovered=SessionAnalysis.ensure(dir);check(recovered.getString("title").equals("回归,记录"),"separate metadata recovers title after corrupt summary");check(recovered.getString("status").equals("partial")&&recovered.getBoolean("recovered"),"interrupted capture recovered explicitly partial");check(recovered.getString("analysisStatus").equals("complete"),"partial capture can have completed analysis");check(recovered.isNull("low1Pct")&&recovered.isNull("capturedFrameAverageFps"),"missing frame data remains missing instead of zero");check(!recovered.getBoolean("frameAnalysisAvailable"),"missing frame CSV is explicit");check(recovered.getLong("startedUnixMs")==1234567890123L,"integer timestamp survives precisely");
  JSONObject second=SessionAnalysis.ensure(dir,(s,f)->{throw new AssertionError("cached result should not re-scan");});check(second.getLong("analyzedUnixMs")==recovered.getLong("analyzedUnixMs"),"completed unchanged summary reused");
  try(Writer w=new FileWriter(new File(dir,"samples.csv"),true)){w.write("2,1234567892123,36\n");}JSONObject changed=SessionAnalysis.ensure(dir);check(changed.getLong("samples")==3,"changed raw fingerprint triggers complete re-analysis");close(changed.getJSONObject("statistics").getJSONObject("cpuPct").getDouble("avg"),24,"re-analysis uses whole raw record");
  for(int i=0;i<20;i++)SessionAnalysis.writeAtomic(dir,"summary.json",new JSONObject(changed.toString()).put("atomicGeneration",i));check(SessionAnalysis.read(dir).getInt("atomicGeneration")==19,"atomic replacement can overwrite existing summary repeatedly");File[] leftovers=dir.listFiles((d,name)->name.endsWith(".tmp"));check(leftovers!=null&&leftovers.length==0,"successful atomic writes leave no temporary files");
 }
 private static void truncationAndFailures()throws Exception{
  File truncated=dir();write(truncated,"samples.csv","elapsed_s,unix_ms,cpuPct,paused\n0,1234,12,false\n1,1334,24\n");JSONObject summary=SessionAnalysis.analyze(truncated,seed("complete"),null);check(summary.getLong("incompleteSampleRows")==1&&summary.getString("status").equals("partial"),"truncated row reported explicitly");check(summary.getLong("samples")==2,"usable values in truncated row preserved");
  for(String tail:new String[]{"1,1334,24,false","1,1334,2"}){
   File unfinished=dir();write(unfinished,"samples.csv","elapsed_s,unix_ms,cpuPct,paused\n0,1234,12,false\n"+tail);write(unfinished,"frames.csv","elapsed_s,present_ns,interval_ms\n0,1000000000,\n.01,1010000000,10");JSONObject repaired=SessionAnalysis.analyze(unfinished,seed("complete"),null);
   check(repaired.getLong("samples")==1&&repaired.getLong("frames")==1,"unterminated complete or partial tail excluded from effective row counts");check(repaired.getLong("discardedSampleTailBytes")==tail.length()&&repaired.getLong("discardedFrameTailBytes")>0,"unterminated tail bytes reported");check(repaired.getString("status").equals("partial")&&repaired.getLong("incompleteSampleRows")==1&&repaired.getLong("incompleteFrameRows")==1,"unterminated tails mark partial");close(repaired.getJSONObject("statistics").getJSONObject("cpuPct").getDouble("avg"),12,"unterminated sample excluded from summary metric");check(repaired.getLong("capturedIntervals")==0&&repaired.isNull("low1Pct"),"unterminated frame interval excluded");
  }
  File missing=dir();SessionAnalysis.writeAtomic(missing,"summary.json",seed("complete"));byte[] before=digest(new File(missing,"summary.json"));boolean failed=false;try{SessionAnalysis.ensure(missing);}catch(IOException expected){failed=true;}check(failed,"missing raw file is explicit error");check(Arrays.equals(before,digest(new File(missing,"summary.json"))),"failed analysis leaves old summary intact");
  File wrongHeader=dir();write(wrongHeader,"samples.csv","unix_ms,cpuPct\n1234,12\n");SessionAnalysis.writeAtomic(wrongHeader,"summary.json",seed("complete"));before=digest(new File(wrongHeader,"summary.json"));failed=false;try{SessionAnalysis.ensure(wrongHeader);}catch(IOException expected){failed=true;}check(failed&&Arrays.equals(before,digest(new File(wrongHeader,"summary.json"))),"invalid raw header cannot replace useful metadata");
  File mutating=dir();write(mutating,"samples.csv","elapsed_s,unix_ms,cpuPct\n0,1234,12\n");SessionAnalysis.writeAtomic(mutating,"summary.json",seed("complete"));before=digest(new File(mutating,"summary.json"));final boolean[] appended={false};failed=false;
  try{SessionAnalysis.ensure(mutating,(s,f)->{if(s>0&&!appended[0]){appended[0]=true;try(Writer w=new FileWriter(new File(mutating,"samples.csv"),true)){w.write("1,1334,24\n");}catch(IOException e){throw new RuntimeException(e);}}});}catch(IOException expected){failed=true;}check(failed&&Arrays.equals(before,digest(new File(mutating,"summary.json"))),"CSV change during analysis is rejected without losing summary");
  File blocked=dir();File destination=new File(blocked,"summary.json");check(destination.mkdir(),"create incompatible destination");write(destination,"keep","preserved");failed=false;try{SessionAnalysis.writeAtomic(blocked,"summary.json",seed("complete"));}catch(IOException expected){failed=true;}check(failed&&new File(destination,"keep").isFile(),"failed atomic move never deletes existing destination");
 }
 private static void streamingAndConcurrency()throws Exception{
  File dir=dir();final int count=200000;try(BufferedWriter writer=Files.newBufferedWriter(new File(dir,"samples.csv").toPath(),StandardCharsets.UTF_8)){
   writer.write("elapsed_s,unix_ms,fps,cpuPct,paused\n");for(int i=0;i<count;i++){writer.write(Double.toString(i/10d));writer.write(',');writer.write(Long.toString(1234567890000L+i*100));writer.write(",60,");writer.write(Integer.toString(i%100));writer.write(",false\n");}
  }
  SessionAnalysis.writeAtomic(dir,"metadata.json",seed("complete"));final int[] progress={0};JSONObject result=SessionAnalysis.ensure(dir,(s,f)->{progress[0]++;check(s>=0&&s<=count&&f==0,"streaming progress within raw row bounds");});check(result.getLong("samples")==count,"large raw record fully streamed");check(result.getJSONObject("statistics").getJSONObject("cpuPct").getLong("count")==count,"full record used beyond chart rendering limits");close(result.getJSONObject("statistics").getJSONObject("cpuPct").getDouble("avg"),49.5,"large streaming mean");check(progress[0]>40&&progress[0]<100,"progress callback bounded every 4096 rows");
  byte[] oldSummary=digest(new File(dir,"summary.json")),oldRaw=digest(new File(dir,"samples.csv"));boolean cancelled=false;try{SessionAnalysis.analyze(dir,result,(s,f)->{if(s>=4096)Thread.currentThread().interrupt();});}catch(InterruptedIOException expected){cancelled=true;}finally{Thread.interrupted();}check(cancelled,"analysis cancellation observed at progress boundary");check(Arrays.equals(oldSummary,digest(new File(dir,"summary.json")))&&Arrays.equals(oldRaw,digest(new File(dir,"samples.csv"))),"cancelled analysis preserves old summary and raw bytes");
  write(dir,"summary.json","invalid");final java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);try{
   Callable<JSONObject> action=()->SessionAnalysis.ensure(dir,(s,f)->{if(s==0&&f==0)starts.incrementAndGet();});Future<JSONObject> a=pool.submit(action),b=pool.submit(action);JSONObject first=a.get(),second=b.get();check(starts.get()==1,"same-directory concurrent repair scans once");check(first.getLong("analyzedUnixMs")==second.getLong("analyzedUnixMs"),"concurrent repair returns same completed summary");
  }finally{pool.shutdown();}
 }
}
