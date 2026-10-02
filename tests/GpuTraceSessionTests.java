package com.lightframe.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Uses real files and OS processes, with explicit completion/cancellation races. */
public final class GpuTraceSessionTests {
 private static int checks;
 private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 private interface Condition {boolean done()throws Exception;}
 private static void await(Condition condition,String why)throws Exception{long end=System.nanoTime()+2_000_000_000L;while(!condition.done()&&System.nanoTime()<end)Thread.sleep(5);check(condition.done(),why);}
 public static void main(String[] args)throws Exception{
  permissionsAndPaths();completedOnlyAndRenewal();failuresAndRetry();capacityRecovery();otherLossBackoff();limits();pauseAndLateStartup();timeout();realProcess();
  System.out.println("GPU trace session checks: "+checks);
 }
 private static final class Fixture implements Closeable {
  final File base=Files.createTempDirectory("lightframe-gpu-session-").toFile(),configs=new File(base,"configs"),traces=new File(base,"traces");
  final AtomicLong now=new AtomicLong(100_000_000_000L);final AtomicInteger starts=new AtomicInteger(),feeds=new AtomicInteger(),finishes=new AtomicInteger();
  final List<Integer> capacities=Collections.synchronizedList(new ArrayList<>());final List<FakeProcess> processes=Collections.synchronizedList(new ArrayList<>());final List<File> owned=Collections.synchronizedList(new ArrayList<>());final byte[] data=new byte[70000];
  Fixture()throws IOException{if(!configs.mkdir()||!traces.mkdir())throw new IOException("mkdir");for(int i=0;i<data.length;i++)data[i]=(byte)i;}
  FakeProcess start(String[] argv,int exit,boolean alive)throws IOException{
   check(argv.length==6&&argv[0].equals("/system/bin/perfetto")&&argv[1].equals("--txt")&&argv[2].equals("-c")&&argv[4].equals("-o"),"controlled argv has no shell/background process escape");
   File cfg=new File(argv[3]),trace=new File(argv[5]);String config=new String(Files.readAllBytes(cfg.toPath()),StandardCharsets.UTF_8);
   check(config.contains("duration_ms: 8000")&&(config.contains("size_kb: 2048 fill_policy: DISCARD")||config.contains("size_kb: 4096 fill_policy: DISCARD")),"eight-second and bounded central capacity configured");capacities.add(config.contains("size_kb: 4096 ")?4096:2048);
   check(!config.contains("write_into_file")&&!config.contains("flush_period")&&!config.contains("atrace"),"final capture never relies on streaming stats or gfx categories");
   check(config.contains("power/gpu_work_period")&&config.contains("power/gpu_frequency"),"only actual GPU work and frequency events requested");
   check(cfg.getParentFile().equals(configs)&&trace.getParentFile().equals(traces),"files remain within injected controlled directories");
   try(FileOutputStream output=new FileOutputStream(trace)){output.write(data);}owned.add(cfg);owned.add(trace);FakeProcess process=new FakeProcess(exit,alive,new byte[0]);processes.add(process);starts.incrementAndGet();return process;
  }
  GpuTraceSession.Decoder decoder(){return new GpuTraceSession.Decoder(){int bytes;boolean done;
   public void feed(byte[] data,int start,int count){check(!done,"no feed after final packet check");feeds.incrementAndGet();bytes+=count;}
   public void finish(){done=true;finishes.incrementAndGet();check(bytes==Fixture.this.data.length,"bounded read preserves every completed trace byte");}
   public GpuTraceSession.Snapshot snapshot(long ns){check(done,"snapshot requires final completion validation");return new GpuTraceSession.Snapshot(37.5,599,ns-5_000_000_000L,ns-4_000_000_000L,ns-1_000_000_000L,ns,"ready","");}};}
  GpuTraceSession.Decoder lossDecoder(String error){GpuTraceSession.Decoder delegate=decoder();return new GpuTraceSession.Decoder(){
   public void feed(byte[] data,int start,int count)throws IOException{delegate.feed(data,start,count);}public void finish()throws IOException{delegate.finish();}
   public GpuTraceSession.Snapshot snapshot(long ns){delegate.snapshot(ns);return GpuTraceSession.Snapshot.empty("unavailable",error,ns);}};}
  GpuTraceSession session(GpuTraceSession.Runner runner,GpuTraceSession.DecoderFactory decoders){return new GpuTraceSession(2000,now::get,runner,configs,traces,decoders,"123");}
  public void close(){for(FakeProcess process:processes)process.destroy();for(File f:configs.listFiles())f.delete();for(File f:traces.listFiles())f.delete();configs.delete();traces.delete();base.delete();}
 }
 private static void permissionsAndPaths()throws Exception{
  try(Fixture f=new Fixture()){
   GpuTraceSession session=new GpuTraceSession(10001,f.now::get,argv->{throw new AssertionError("unprivileged child launch");},f.configs,f.traces,f::decoder,"123");
   GpuTraceSession.Snapshot s=session.poll(f.now.get());check(s.status.equals("not_authorized")&&Double.isNaN(s.busyPct)&&Double.isNaN(s.frequencyMHz),"ordinary app UID cannot manufacture GPU readings");session.close();
   for(String name:Arrays.asList("../x","lightframe-gpu-123-../../outside.cfg","lightframe-gpu-user-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.cfg")){
    boolean rejected=false;try{GpuTraceSession.ownedPath(f.configs,name);}catch(IOException expected){rejected=true;}check(rejected,"unsafe generated filename rejected");
   }
   File safe=GpuTraceSession.ownedPath(f.configs,"lightframe-gpu-123-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.cfg");check(safe.getParentFile().equals(f.configs),"exact generated filename accepted");
  }
 }
 private static void completedOnlyAndRenewal()throws Exception{
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->f.start(argv,0,true),f::decoder);
   long started=System.nanoTime();for(int i=0;i<2000;i++)session.poll(f.now.get());check(System.nanoTime()-started<500_000_000L,"poll stays nonblocking while capture is running");
   await(()->f.starts.get()==1,"one capture launched despite repeated hardware polls");
   check(f.feeds.get()==0&&Double.isNaN(session.poll(f.now.get()).busyPct),"in-progress trace is never parsed or published as a zero reading");
   f.processes.get(0).alive=false;await(()->session.poll(f.now.get()).status.equals("ready"),"completed capture published");
   GpuTraceSession.Snapshot first=session.poll(f.now.get());check(first.busyPct==37.5&&first.frequencyMHz==599,"confirmed decoder values retained exactly");
   await(()->f.starts.get()==2,"next poll starts another bounded capture");
   f.now.addAndGet(1_000_000_000L);GpuTraceSession.Snapshot prior=session.poll(f.now.get());check(prior.readNs==first.readNs&&prior.endNs==first.endNs,"new capture preserves previous measured window and original read age");
   check(f.finishes.get()==1&&f.feeds.get()==3,"whole final file parsed once in bounded chunks");
   session.suspend();check(session.poll(f.now.get()).status.equals("starting"),"pause permits a fresh later capture");session.close();
   await(()->f.owned.stream().noneMatch(File::exists),"pause/close remove only each run's exact config and trace");
   for(FakeProcess p:f.processes)check(p.destroyed&&p.input.closed&&p.output.closed&&p.errors.closed,"all owned process handles closed");
   check(session.poll(f.now.get()).status.equals("closed"),"closed session cannot restart");
  }
 }
 private static void failuresAndRetry()throws Exception{
  try(Fixture f=new Fixture()){
   File unrelated=new File(f.traces,"another-owner.pftrace");Files.write(unrelated.toPath(),new byte[]{8,9});
   GpuTraceSession session=f.session(argv->f.start(argv,7,false),f::decoder);session.poll(f.now.get());
   await(()->session.poll(f.now.get()).status.equals("error"),"nonzero exit rejects even readable trace file");
   check(session.poll(f.now.get()).error.contains("退出码 7")&&f.feeds.get()==0,"nonzero capture error preserved, no parse");
   for(int i=0;i<100;i++)session.poll(f.now.get());check(f.starts.get()==1,"failure does not spawn retry loop during backoff");
   f.now.addAndGet(GpuTraceSession.RETRY_NS-1);session.poll(f.now.get());check(f.starts.get()==1,"retry deadline is not rounded down");
   f.now.incrementAndGet();session.poll(f.now.get());await(()->f.starts.get()==2,"retry occurs only after thirty seconds");session.close();
   check(unrelated.exists()&&Arrays.equals(Files.readAllBytes(unrelated.toPath()),new byte[]{8,9}),"cleanup preserves files outside exact run names");unrelated.delete();
  }
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->{f.starts.incrementAndGet();throw new IOException("EACCES test denial");},f::decoder);session.poll(f.now.get());
   await(()->session.poll(f.now.get()).status.equals("error"),"process-start permission denial reported");check(session.poll(f.now.get()).error.contains("EACCES"),"startup denial detail retained");
   await(()->f.configs.list().length==0,"config cleaned after process creation fails");session.close();
  }
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->f.start(argv,0,false),()->new GpuTraceSession.Decoder(){public void feed(byte[] b,int o,int n){}public void finish(){}public GpuTraceSession.Snapshot snapshot(long ns){return GpuTraceSession.Snapshot.empty("insufficient","缺少完整 GPU 时间窗口",ns);}});
   session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("insufficient"),"valid process without sufficient measurements remains unavailable");
   for(int i=0;i<50;i++)session.poll(f.now.get());check(f.starts.get()==1,"unsupported/incomplete event stream also backs off");check(Double.isNaN(session.poll(f.now.get()).busyPct),"unavailable is never fabricated zero");session.close();
  }
 }
 private static void capacityRecovery()throws Exception{
  check(GpuTraceSession.FILE_LIMIT_BYTES==8*1024*1024&&GpuTraceSession.FILE_LIMIT_BYTES>GpuTraceSession.MAX_BUFFER_KIB*1024,"completed trace file has bounded headroom beyond maximum central buffer");
  for(String counter:Arrays.asList("chunks_overwritten","bytes_overwritten","chunks_discarded"))try(Fixture f=new Fixture()){
   String error="Perfetto central buffer overwrite, discard, or packet loss: "+counter+"=2";
   GpuTraceSession session=f.session(argv->f.start(argv,0,false),()->f.lossDecoder(error));session.poll(f.now.get());
   await(()->session.poll(f.now.get()).status.equals("unavailable"),counter+" loss completed and withheld");check(f.capacities.equals(Arrays.asList(2048)),"initial capture requests only two MiB");check(Double.isNaN(session.poll(f.now.get()).busyPct)&&Double.isNaN(session.poll(f.now.get()).frequencyMHz),"failed capacity capture never publishes fabricated values");
   for(int i=0;i<100;i++)session.poll(f.now.get());check(f.starts.get()==1,"capacity change does not immediately spin up repeated captures");
   f.now.addAndGet(GpuTraceSession.CAPACITY_RETRY_NS-1);session.poll(f.now.get());check(f.starts.get()==1,"expanded retry waits the whole one-second deadline");f.now.incrementAndGet();session.poll(f.now.get());await(()->f.starts.get()==2,"capacity retry starts at bounded one-second deadline");check(f.capacities.equals(Arrays.asList(2048,4096)),"next run doubles once to the four-MiB maximum");
   await(()->{GpuTraceSession.Snapshot sample=session.poll(f.now.get());return sample.status.equals("unavailable")&&sample.readNs==f.now.get();},"maximum-capacity failure completed");
   for(int i=0;i<100;i++)session.poll(f.now.get());check(f.starts.get()==2,"failure at maximum capacity cannot spin in a fast loop");f.now.addAndGet(GpuTraceSession.RETRY_NS-1);session.poll(f.now.get());check(f.starts.get()==2,"maximum-capacity retry retains thirty-second backoff");f.now.incrementAndGet();session.poll(f.now.get());await(()->f.starts.get()==3,"maximum-capacity retry begins after thirty seconds");check(f.capacities.get(2)==4096,"capacity never exceeds four MiB");
   session.suspend();session.poll(f.now.get());await(()->f.starts.get()==4,"manual resume can start a fresh owned capture");check(f.capacities.get(3)==4096,"suspend preserves the confirmed needed capacity");session.close();await(()->f.configs.list().length==0&&f.traces.list().length==0,"adapted captures still clean exact owned files");
  }
  try(Fixture f=new Fixture()){
   AtomicInteger attempts=new AtomicInteger();String error="Perfetto central buffer overwrite, discard, or packet loss: chunks_discarded=1";
   GpuTraceSession session=f.session(argv->f.start(argv,0,f.starts.get()>0),()->attempts.incrementAndGet()==1?f.lossDecoder(error):f.decoder());session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("unavailable"),"recoverable loss recorded before successful retry");f.now.addAndGet(GpuTraceSession.CAPACITY_RETRY_NS);session.poll(f.now.get());await(()->f.starts.get()==2,"larger recovery capture started");check(Double.isNaN(session.poll(f.now.get()).busyPct),"running larger capture cannot make prior failure look successful");f.processes.get(1).alive=false;await(()->session.poll(f.now.get()).status.equals("ready"),"a fully decoded larger capture recovers measurements");GpuTraceSession.Snapshot recovered=session.poll(f.now.get());check(recovered.busyPct==37.5&&recovered.frequencyMHz==599,"recovered readings are decoder measurements, not interpolation");session.close();
  }
 }
 private static void otherLossBackoff()throws Exception{
  String prefix="Perfetto central buffer overwrite, discard, or packet loss:";
  for(String error:Arrays.asList(prefix+" trace_writer_packet_loss=7",prefix+" patches_failed=2",prefix+" abi_violations=1",prefix+" chunks_overwritten=0, bytes_overwritten=0, chunks_discarded=0",prefix+" trace_writer_chunks_discarded=2",prefix+" chunks_discarded=-1",prefix+" chunks_discarded=2garbage","unrelated "+prefix+" chunks_discarded=5","Perfetto central buffer overwrite, discard, or packet loss"))try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->f.start(argv,0,false),()->f.lossDecoder(error));session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("unavailable"),"non-capacity failure published");f.now.addAndGet(GpuTraceSession.CAPACITY_RETRY_NS);session.poll(f.now.get());check(f.starts.get()==1,"SMB, patch, unknown or nonpositive counters do not take capacity fast path");f.now.addAndGet(GpuTraceSession.RETRY_NS-GpuTraceSession.CAPACITY_RETRY_NS-1);session.poll(f.now.get());check(f.starts.get()==1,"non-capacity failure waits full thirty-second deadline");f.now.incrementAndGet();session.poll(f.now.get());await(()->f.starts.get()==2,"non-capacity failure retries only at normal deadline");check(f.capacities.equals(Arrays.asList(2048,2048)),"non-capacity errors do not increase memory capacity");session.close();
  }
 }
 private static void limits()throws Exception{
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->{FakeProcess p=f.start(argv,0,true);try(RandomAccessFile file=new RandomAccessFile(argv[5],"rw")){file.setLength(GpuTraceSession.FILE_LIMIT_BYTES+1L);}return p;},f::decoder);
   session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("error"),"trace file beyond eight MiB rejected");check(f.feeds.get()==0&&f.processes.get(0).destroyed,"oversized file never parsed and owned process killed");session.close();
  }
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->f.start(argv,0,false),()->new GpuTraceSession.Decoder(){public void feed(byte[] b,int o,int n)throws IOException{throw new IOException("packet exceeds one MiB");}public void finish(){}public GpuTraceSession.Snapshot snapshot(long ns){throw new AssertionError("malformed trace snapshot");}});
   session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("error"),"parser bound failure rejects entire trace");check(session.poll(f.now.get()).error.contains("one MiB"),"parser error exposed instead of partial values");session.close();
  }
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->{FakeProcess p=f.start(argv,0,true);FakeProcess flood=new FakeProcess(0,true,new byte[GpuTraceSession.OUTPUT_LIMIT_BYTES+1]);f.processes.add(flood);p.destroy();return flood;},f::decoder);
   session.poll(f.now.get());await(()->session.poll(f.now.get()).status.equals("error"),"stdout flooding bounded independently of trace file");check(session.poll(f.now.get()).error.contains("128 KiB"),"output overrun error explicit");session.close();
  }
 }
 private static void pauseAndLateStartup()throws Exception{
  try(Fixture f=new Fixture()){
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<FakeProcess> late=new AtomicReference<>();
   GpuTraceSession session=f.session(argv->{FakeProcess p=f.start(argv,0,true);entered.countDown();for(;;){try{release.await();break;}catch(InterruptedException ignored){}}late.set(p);return p;},f::decoder);
   session.poll(f.now.get());check(entered.await(1,TimeUnit.SECONDS),"startup race reached process creation");
   long before=System.nanoTime();session.suspend();check(System.nanoTime()-before<500_000_000L,"pause does not wait on blocked startup");session.close();release.countDown();
   await(()->late.get()!=null&&late.get().destroyed,"child created after pause/close is immediately terminated");
   await(()->f.configs.list().length==0&&f.traces.list().length==0,"late child output is also deleted using retained own names");
   check(session.poll(f.now.get()).status.equals("closed"),"late worker cannot overwrite newer closed state");
  }
 }
 private static void timeout()throws Exception{
  try(Fixture f=new Fixture()){
   GpuTraceSession session=f.session(argv->f.start(argv,0,true),f::decoder);session.poll(f.now.get());await(()->f.starts.get()==1,"timeout capture launched");
   f.now.addAndGet(GpuTraceSession.PROCESS_TIMEOUT_NS);await(()->session.poll(f.now.get()).status.equals("error"),"hung perfetto capture bounded by worker deadline");
   check(session.poll(f.now.get()).error.contains("18 秒")&&f.processes.get(0).destroyed,"timeout kills known child and states deadline");session.close();
  }
 }
 private static void realProcess()throws Exception{
  try(Fixture f=new Fixture()){
   AtomicReference<java.lang.Process> child=new AtomicReference<>();String java=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").getAbsolutePath();
   GpuTraceSession session=f.session(argv->{java.lang.Process p=new ProcessBuilder(java,"-Xmx16m","-cp",System.getProperty("java.class.path"),GpuTraceSessionTests.class.getName()+"$Child",argv[5]).redirectErrorStream(true).start();child.set(p);return p;},f::decoder);
   session.poll(f.now.get());await(()->child.get()!=null,"real OS capture child launched");
   check(Double.isNaN(session.poll(f.now.get()).busyPct),"real sleeping child cannot publish incomplete trace");session.suspend();
   check(child.get().waitFor(1,TimeUnit.SECONDS)&&!child.get().isAlive(),"pause terminates exact OS child promptly");session.close();
   await(()->f.configs.list().length==0&&f.traces.list().length==0,"OS pipe and exact file cleanup completes");
  }
 }
 public static final class Child {public static void main(String[] args)throws Exception{try(FileOutputStream out=new FileOutputStream(args[0])){out.write(new byte[100]);}System.out.println("running");System.out.flush();Thread.sleep(10000);}}
 private static final class FakeProcess extends java.lang.Process {
  volatile boolean alive,destroyed;final int code;final TrackingInput input,errors=new TrackingInput(new byte[0]);final TrackingOutput output=new TrackingOutput();
  FakeProcess(int code,boolean alive,byte[] bytes){this.code=code;this.alive=alive;input=new TrackingInput(bytes);}
  public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return errors;}public OutputStream getOutputStream(){return output;}
  public int exitValue(){if(alive)throw new IllegalThreadStateException();return code;}public int waitFor()throws InterruptedException{while(alive)Thread.sleep(1);return code;}
  public void destroy(){destroyed=true;alive=false;}public java.lang.Process destroyForcibly(){destroy();return this;}
 }
 private static final class TrackingInput extends ByteArrayInputStream {volatile boolean closed;TrackingInput(byte[] bytes){super(bytes);}public void close()throws IOException{closed=true;super.close();}}
 private static final class TrackingOutput extends ByteArrayOutputStream {volatile boolean closed;public void close()throws IOException{closed=true;super.close();}}
}
