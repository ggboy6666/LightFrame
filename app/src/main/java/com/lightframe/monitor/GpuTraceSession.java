package com.lightframe.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** One bounded, read-only GPU work trace owned by this collector process. */
public final class GpuTraceSession implements Closeable {
 public static final long RETRY_NS=30_000_000_000L;
 public static final int FILE_LIMIT_BYTES=4*1024*1024;
 static final int READ_CHUNK_BYTES=32*1024,OUTPUT_LIMIT_BYTES=128*1024;
 static final long DURATION_NS=8_000_000_000L,PROCESS_TIMEOUT_NS=18_000_000_000L;
 static final String CONFIG="buffers { size_kb: 512 fill_policy: DISCARD }\n"
  +"duration_ms: 8000\n"
  +"data_sources { config { name: \"linux.ftrace\" ftrace_config { "
  +"ftrace_events: \"power/gpu_work_period\" ftrace_events: \"power/gpu_frequency\" drain_period_ms: 250 } } }\n";
 public static final class Snapshot {
  public final double busyPct,frequencyMHz;
  public final long beginNs,endNs,frequencyNs,readNs;
  public final String status,error;
  public Snapshot(double busyPct,double frequencyMHz,long beginNs,long endNs,long frequencyNs,long readNs,String status,String error){
   this.busyPct=busyPct;this.frequencyMHz=frequencyMHz;this.beginNs=beginNs;this.endNs=endNs;this.frequencyNs=frequencyNs;this.readNs=readNs;this.status=status;this.error=error==null?"":error;
  }
  static Snapshot empty(String status,String error,long readNs){return new Snapshot(Double.NaN,Double.NaN,0,0,0,readNs,status,error);}
 }
 interface Clock {long now();}
 interface Runner {java.lang.Process start(String[] argv)throws IOException;}
 interface Decoder {void feed(byte[] bytes,int start,int count)throws IOException;void finish()throws IOException;Snapshot snapshot(long readNs);}
 interface DecoderFactory {Decoder create();}
 private final int uid;private final Clock clock;private final Runner runner;private final DecoderFactory decoders;
 private final File configDirectory,traceDirectory;private final ExecutorService worker;private final String testPid;
 private final Object lock=new Object();private volatile Snapshot latest;
 private Run active;private boolean closed;private long retryAtNs;private int generation;

 public GpuTraceSession(int uid){this(uid,4);}
 public GpuTraceSession(int uid,int workClockId){this(uid,System::nanoTime,argv->new ProcessBuilder(argv).redirectErrorStream(true).start(),
  new File("/data/misc/perfetto-configs"),new File("/data/misc/perfetto-traces"),()->newDecoder(workClockId),null);}
 GpuTraceSession(int uid,Clock clock,Runner runner,File configDirectory,File traceDirectory,DecoderFactory decoders,String testPid){
  this.uid=uid;this.clock=clock;this.runner=runner;this.configDirectory=configDirectory;this.traceDirectory=traceDirectory;this.decoders=decoders;this.testPid=testPid;
  latest=Snapshot.empty(uid==0||uid==2000?"idle":"not_authorized",uid==0||uid==2000?"":"GPU 工作追踪需要已授权的 Shizuku Shell / Root",0);
  worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"lightframe-gpu-trace");t.setDaemon(true);return t;});
 }
 /** This method never opens files, launches a child, waits, or parses trace bytes. */
 public Snapshot poll(long nowNs){
  synchronized(lock){
   if(closed||uid!=0&&uid!=2000)return latest;
   if(active==null&&(retryAtNs==0||nowNs-retryAtNs>=0)){
    Run run=new Run(++generation);active=run;
    if(!Double.isFinite(latest.busyPct)&&!Double.isFinite(latest.frequencyMHz))latest=Snapshot.empty("starting","",nowNs);
    try{run.task=worker.submit(()->collect(run));}catch(RejectedExecutionException e){active=null;latest=Snapshot.empty("closed","追踪已关闭",nowNs);}
   }
   return latest;
  }
 }
 /** Stop this trace; a later poll can create a fresh, independently owned trace. */
 public void suspend(){stop(false);}
 public void close(){stop(true);worker.shutdownNow();}
 private void stop(boolean permanently){
  Run run;
  synchronized(lock){if(closed)return;closed=permanently;generation++;run=active;active=null;retryAtNs=0;latest=Snapshot.empty(permanently?"closed":"suspended",permanently?"追踪已关闭":"GPU 工作追踪已暂停",clock.now());}
  if(run!=null)run.cancel();
 }
 private boolean current(Run run){synchronized(lock){return !closed&&active==run&&run.generation==generation&&!run.cancelled;}}
 private void collect(Run run){
  boolean failed=true;String status="error",error="";Snapshot result=null;long started=clock.now();
  try{
   if(!current(run))return;
   String pid=testPid==null?ownerPid():testPid;String name="lightframe-gpu-"+pid+"-"+UUID.randomUUID().toString();
   File cfg=ownedPath(configDirectory,name+".cfg"),trace=ownedPath(traceDirectory,name+".pftrace");
   if(!configDirectory.isDirectory()||!traceDirectory.isDirectory())throw new IOException("系统 Perfetto 配置或追踪目录不可用");
   if(trace.exists())throw new IOException("追踪文件名冲突");
   if(!run.createConfig(cfg,trace))return;
   if(!current(run))return;
   java.lang.Process child=runner.start(new String[]{"/system/bin/perfetto","--txt","-c",cfg.getAbsolutePath(),"-o",trace.getAbsolutePath()});
   if(!run.register(child))return;
   child.getOutputStream().close();run.startDrain();
   while(current(run)&&!Thread.currentThread().isInterrupted()){
    long now=clock.now();if(now-started>=PROCESS_TIMEOUT_NS)throw new IOException("GPU 追踪未在 18 秒内结束");
    if(run.outputError!=null)throw new IOException(run.outputError);
    boolean exited;int exit=0;try{exit=child.exitValue();exited=true;}catch(IllegalThreadStateException running){exited=false;}
    if(trace.exists()&&trace.length()>FILE_LIMIT_BYTES)throw new IOException("GPU 追踪超过 4 MiB 文件上限");
    if(exited){
     if(exit!=0)throw new IOException("Perfetto 退出码 "+exit+run.outputSuffix());
     if(!trace.isFile()||trace.length()==0)throw new IOException("Perfetto 未返回 GPU 追踪数据"+run.outputSuffix());
     RandomAccessFile file=run.openTrace();if(file==null)return;
     long length=file.length();if(length>FILE_LIMIT_BYTES)throw new IOException("GPU 追踪超过 4 MiB 文件上限");
     Decoder decoder=decoders.create();byte[] bytes=new byte[READ_CHUNK_BYTES];
     while(run.readBytes<length&&current(run)&&!Thread.currentThread().isInterrupted()){
      int count=file.read(bytes,0,(int)Math.min(bytes.length,length-run.readBytes));
      if(count<=0)throw new IOException("GPU 追踪文件读取不完整");run.readBytes+=count;decoder.feed(bytes,0,count);
     }
     if(!current(run)||Thread.currentThread().isInterrupted())return;
     if(file.length()!=length)throw new IOException("GPU 追踪结束后文件仍在变化");
     decoder.finish();result=decoder.snapshot(clock.now());
     if(result==null)throw new IOException("GPU 追踪解析没有返回状态");
     failed=false;status=result.status;error=result.error;break;
    }
    Thread.sleep(250);
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();status="cancelled";error="GPU 工作追踪已取消";}
  catch(Exception e){error=describe(e);}
  finally{
   run.cleanup();
   synchronized(lock){
    if(!closed&&active==run&&run.generation==generation&&!run.cancelled){
     active=null;long now=clock.now();
     boolean valid=result!=null&&(Double.isFinite(result.busyPct)||Double.isFinite(result.frequencyMHz));
     retryAtNs=failed||!valid?now+RETRY_NS:0;latest=result==null?Snapshot.empty(status,error,now):result;
    }
   }
  }
 }
 private static String ownerPid()throws IOException{
  String pid=new File("/proc/self").getCanonicalFile().getName();
  if(!pid.matches("[0-9]{1,10}"))throw new IOException("无法确定追踪所属进程 PID");return pid;
 }
 static File ownedPath(File directory,String name)throws IOException{
  if(!name.matches("lightframe-gpu-[0-9]{1,10}-[a-f0-9-]{36}\\.(cfg|pftrace)"))throw new IOException("无效追踪文件名");
  File base=directory.getCanonicalFile(),path=new File(base,name);
  if(!path.getCanonicalFile().getParentFile().equals(base))throw new IOException("追踪路径超出固定目录");return path;
 }
 private static String describe(Throwable error){String text=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());return text.substring(0,Math.min(text.length(),1024));}
 private static void kill(java.lang.Process child){
  try{child.destroyForcibly();}catch(Exception ignored){try{child.destroy();}catch(Exception ignoredAgain){}}
  try{child.getInputStream().close();}catch(Exception ignored){}try{child.getErrorStream().close();}catch(Exception ignored){}try{child.getOutputStream().close();}catch(Exception ignored){}
 }
 private static Decoder newDecoder(int workClockId){
  // The parser validates packet bounds, clocks, flush completion and event loss.
  return new Decoder(){final GpuTraceData reader=new GpuTraceData(workClockId);public void feed(byte[] bytes,int start,int count)throws IOException{reader.feed(bytes,start,count);}public void finish()throws IOException{reader.finish();}
   public Snapshot snapshot(long readNs){return readerSnapshot(reader,readNs);}};
 }
 private static Snapshot readerSnapshot(GpuTraceData reader,long readNs){
  GpuTraceData.Snapshot s=reader.snapshot(readNs);
  // The reader withholds both values for integrity failures. A verified
  // frequency-only trace can still be useful when its busy window is unknown.
  return new Snapshot(s.busyPct,s.frequencyMHz,s.beginNs,s.endNs,s.frequencyNs,s.readNs,s.status,s.error);
 }
 private static final class Run {
  final int generation;volatile boolean cancelled;volatile String outputError;volatile Future<?> task;
  private java.lang.Process child;private RandomAccessFile input;private File config,trace;private boolean configOwned,traceOwned;
  private final ByteArrayOutputStream output=new ByteArrayOutputStream();long readBytes;
  Run(int generation){this.generation=generation;}
  synchronized boolean createConfig(File cfg,File pftrace)throws IOException{
   if(cancelled)return false;if(!cfg.createNewFile())throw new IOException("追踪配置文件名冲突");config=cfg;configOwned=true;trace=pftrace;traceOwned=true;
   try(FileOutputStream out=new FileOutputStream(cfg)){out.write(CONFIG.getBytes(StandardCharsets.UTF_8));}return true;
  }
  synchronized boolean register(java.lang.Process process){if(cancelled){kill(process);return false;}child=process;return true;}
  synchronized RandomAccessFile openTrace()throws IOException{if(cancelled)return null;if(input==null)input=new RandomAccessFile(trace,"r");return input;}
  void startDrain(){
   final java.lang.Process process;synchronized(this){process=child;if(process==null||cancelled)return;}
   Thread thread=new Thread(()->{try(InputStream in=process.getInputStream()){byte[] bytes=new byte[4096];int total=0;
    while(!cancelled){int n=in.read(bytes);if(n<0)break;if(n==0)continue;total+=n; synchronized(output){int keep=Math.min(n,8192-output.size());if(keep>0)output.write(bytes,0,keep);}
     if(total>OUTPUT_LIMIT_BYTES){outputError="Perfetto 输出超过 128 KiB 上限";kill(process);break;}}
   }catch(IOException ignored){}},"lightframe-gpu-stderr");thread.setDaemon(true);thread.start();
  }
  String outputSuffix(){synchronized(output){String text=new String(output.toByteArray(),StandardCharsets.UTF_8).trim();return text.isEmpty()?"":": "+text.substring(0,Math.min(1024,text.length()));}}
  void cancel(){cancelled=true;Future<?> future=task;if(future!=null)future.cancel(true);cleanup();}
  void cleanup(){
   java.lang.Process process;RandomAccessFile file;File cfg,pf;boolean cfgOwned,pfOwned;
   synchronized(this){process=child;child=null;file=input;input=null;cfg=config;pf=trace;cfgOwned=configOwned;pfOwned=traceOwned;}
   if(process!=null)kill(process);if(file!=null)try{file.close();}catch(IOException ignored){}
   // These are exact names created for this run, never a directory/glob cleanup.
   if(cfgOwned&&cfg!=null)cfg.delete();if(pfOwned&&pf!=null)pf.delete();
  }
 }
}
