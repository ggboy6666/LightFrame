package com.lightframe.monitor;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Manual, read-only Perfetto capability query. It never configures or starts tracing. */
public final class GpuTraceProbe {
 public static final long TIMEOUT_MS=3000;
 public static final int OUTPUT_LIMIT_BYTES=131072;
 private static final String[] COMMAND={"/system/bin/perfetto","--query","--long"};
 private static final String NOTE="仅查询当前已注册的数据源；已注册不等于能取得有效 GPU 利用率，频率和渲染阶段也不等于利用率。未列出、查询失败或输出不完整均不能断言设备不支持所有 GPU 采集方式。未启动追踪、未修改 GPU 采样值。";
 private static final Pattern ANSI=Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
 private static final Pattern SOURCE=Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_.-]*)(?:\\s+.*)?$");
 private GpuTraceProbe(){}
 interface Runner { java.lang.Process start(String[] arguments)throws Exception; }
 public static JSONObject query(int uid)throws JSONException{
  return query(uid,arguments->new ProcessBuilder(arguments).redirectErrorStream(true).start(),TIMEOUT_MS,OUTPUT_LIMIT_BYTES);
 }
 static JSONObject query(int uid,Runner runner,long timeoutMs,int outputLimit)throws JSONException{
  if(timeoutMs<=0||timeoutMs>TIMEOUT_MS||outputLimit<=0||outputLimit>OUTPUT_LIMIT_BYTES)throw new IllegalArgumentException("Invalid query bounds");
  long started=System.nanoTime();JSONObject json=new JSONObject();json.put("command",new JSONArray(Arrays.asList(COMMAND)));json.put("uid",uid);json.put("timeoutMs",timeoutMs);json.put("outputLimitBytes",outputLimit);json.put("note",NOTE);
  boolean authorized=uid==0||uid==2000,attempted=false,interrupted=false;Integer exitCode=null;String status="not_authorized",error="仅在 Root 或已授权的 Shizuku Shell 采集器中查询";
  Capture capture=new Capture(outputLimit);
  ExecutorService executor=null;Future<Integer> future=null;
  try{
   if(authorized){
    if(Thread.currentThread().isInterrupted()){status="cancelled";error="查询已取消";}
    else{
     attempted=true;executor=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"lightframe-gpu-query");thread.setDaemon(true);return thread;});
     future=executor.submit(()->{
      java.lang.Process child=runner.start(COMMAND.clone());
      if(!capture.register(child))throw new InterruptedIOException("查询已取消");
      try{
       child.getOutputStream().close();byte[] buffer=new byte[8192];InputStream input=child.getInputStream();
       while(true){
        if(Thread.currentThread().isInterrupted()||capture.cancelled())throw new InterruptedIOException("查询已取消");
        int read=input.read(buffer,0,Math.min(buffer.length,capture.remaining()+1));if(read==-1)break;if(read==0)continue;
        if(!capture.append(buffer,read))throw new OutputLimitException();
       }
       return child.waitFor();
      }finally{capture.closeChild();}
     });
     long remaining=timeoutMs*1_000_000L-(System.nanoTime()-started);
     if(remaining<=0)throw new TimeoutException();exitCode=future.get(remaining,TimeUnit.NANOSECONDS);
     status=exitCode==0?"queried":"error";error=exitCode==0?"":"Perfetto 查询退出码 "+exitCode;
    }
   }
  }catch(TimeoutException e){status="timeout";error="Perfetto 能力查询超时（上限 "+timeoutMs+"ms）；结果可能不完整";}
  catch(InterruptedException e){interrupted=true;status="cancelled";error="查询已取消";}
  catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof OutputLimitException){status="output_limit";error="Perfetto 查询输出超过 "+outputLimit+" 字节；保留有界原始片段";}else{status="error";error=describe(cause);}}
  catch(Exception e){status="error";error=describe(e);}
  finally{capture.cancel();if(future!=null)future.cancel(true);if(executor!=null)executor.shutdownNow();if(interrupted)Thread.currentThread().interrupt();}
  String raw=capture.raw();boolean succeeded=status.equals("queried");JSONObject listed=registrations(raw,succeeded);
  json.put("queryAttempted",attempted);json.put("querySucceeded",succeeded);json.put("status",status);json.put("queryError",error);json.put("exitCode",exitCode==null?JSONObject.NULL:exitCode);json.put("durationMs",Math.max(0,(System.nanoTime()-started)/1_000_000L));json.put("raw",raw);json.put("capturedBytes",capture.size());json.put("truncated",capture.truncated());json.put("outputComplete",succeeded&&!capture.truncated());
  json.put("formatRecognized",listed.getBoolean("formatRecognized"));json.put("dataSourceCandidates",listed.getJSONArray("dataSourceCandidates"));json.put("gpuUtilizationVerified",false);
  return json;
 }
 /** The human-readable query format is unstable: extraction is deliberately only a clue. */
 static JSONObject registrations(String raw,boolean succeeded)throws JSONException{
  JSONArray candidates=new JSONArray();Set<String> names=new LinkedHashSet<>();boolean inTable=false,recognized=false;
  String clean=ANSI.matcher(raw==null?"":raw).replaceAll("");
  for(String line:clean.split("\\r?\\n")){
   String trimmed=line.trim();if(trimmed.equalsIgnoreCase("DATA SOURCES REGISTERED:")){inTable=true;recognized=true;continue;}
   if(!inTable)continue;if(trimmed.matches("[A-Z][A-Z ]+:")){inTable=false;continue;}
   Matcher matcher=SOURCE.matcher(trimmed);if(!matcher.matches())continue;String name=matcher.group(1);
   String kind=kind(name);if(kind.isEmpty()||!names.add(name))continue;
   JSONObject candidate=new JSONObject();candidate.put("name",name);candidate.put("kind",kind);candidate.put("registrationListed",true);candidate.put("registered",succeeded?true:JSONObject.NULL);candidate.put("gpuUtilizationVerified",false);
   candidate.put("note",kind.equals("ftrace")?"已列出 ftrace；此查询未验证 power/gpu_frequency 事件是否存在或可产生数据":kind.equals("gpu_counters")?"GPU 计数器来源候选；尚未验证计数器 ID、单位、利用率含义或实际数据":"此类数据源不是 GPU 利用率的可用性证明");candidates.put(candidate);
  }
  JSONObject result=new JSONObject();result.put("formatRecognized",recognized);result.put("dataSourceCandidates",candidates);return result;
 }
 private static String kind(String name){
  if(name.equals("gpu.counters")||name.startsWith("gpu.counters."))return "gpu_counters";
  if(name.equals("gpu.renderstages")||name.startsWith("gpu.renderstages."))return "gpu_renderstages";
  if(name.equals("gpu.log")||name.startsWith("gpu.log."))return "gpu_log";
  if(name.equals("linux.ftrace"))return "ftrace";
  if(name.equals("vulkan.memory_tracker"))return "vulkan_memory";
  return "";
 }
 private static String describe(Throwable error){if(error==null)return "未知查询错误";String message=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());return message.substring(0,Math.min(message.length(),1024));}
 private static final class OutputLimitException extends IOException{}
 private static final class Capture {
  private final ByteArrayOutputStream bytes;private final int limit;private boolean cancelled,truncated;private java.lang.Process child;
  Capture(int limit){this.limit=limit;bytes=new ByteArrayOutputStream(Math.min(limit,8192));}
  boolean register(java.lang.Process process){boolean reject;synchronized(this){reject=cancelled;if(!reject)child=process;}if(reject)close(process);return !reject;}
  synchronized boolean append(byte[] source,int count)throws InterruptedIOException{if(cancelled)throw new InterruptedIOException("查询已取消");int accepted=Math.min(count,limit-bytes.size());bytes.write(source,0,accepted);if(accepted<count)truncated=true;return !truncated;}
  synchronized int remaining(){return limit-bytes.size();}
  synchronized boolean cancelled(){return cancelled;}
  synchronized boolean truncated(){return truncated;}
  synchronized int size(){return bytes.size();}
  synchronized String raw(){return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
  void cancel(){synchronized(this){cancelled=true;}closeChild();}
  void closeChild(){java.lang.Process process;synchronized(this){process=child;child=null;}if(process!=null)close(process);}
  private static void close(java.lang.Process process){
   // Kill before closing a blocking pipe; waitFor is confined to the cancellable worker.
   try{process.destroyForcibly();}catch(Exception ignored){try{process.destroy();}catch(Exception ignoredAgain){}}
   try{process.getInputStream().close();}catch(Exception ignored){}
   try{process.getErrorStream().close();}catch(Exception ignored){}
   try{process.getOutputStream().close();}catch(Exception ignored){}
  }
 }
}
