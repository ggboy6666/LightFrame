package com.lightframe.monitor;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class GpuTraceProbeTests {
 private static int checks;
 private static void check(boolean condition,String description){checks++;if(!condition)throw new AssertionError(description);}
 private static final String LIST="\u001b[31mNot meant for machine consumption.\u001b[0m\nService: v50\nPRODUCERS:\nID PID UID NAME SDK\n1 33 2000 gpu.counters.fake\nDATA SOURCES REGISTERED:\nNAME                                     PRODUCER                     DETAILS\n===                                      ========                     ========\nlinux.ftrace                             traced_probes (1)            gfx,view\ngpu.counters.mali                        gpu-producer (2)\ngpu.renderstages.mali                    gpu-producer (2)\nvulkan.memory_tracker                    gpu-producer (2)\ngpu.log                                  gpu-producer (2)\ngpu.counters.mali                        gpu-producer (2)\nTRACING SESSIONS:\n1 2000 STARTED gpu.counters.fake2\n";
 public static void main(String[] args)throws Exception{
  registrationSemantics();successAndPermissions();limitsAndFailures();cancellation();lateStartupCleanup();realPipes();System.out.println("GPU trace query checks: "+checks);
 }
 private static void registrationSemantics()throws Exception{
  JSONObject parsed=GpuTraceProbe.registrations(LIST,true);check(parsed.getBoolean("formatRecognized"),"official query table recognized with ANSI");JSONArray candidates=parsed.getJSONArray("dataSourceCandidates");check(candidates.length()==5,"only table source names recognized and duplicates removed");Set<String> names=new HashSet<>();for(int i=0;i<candidates.length();i++){JSONObject candidate=candidates.getJSONObject(i);names.add(candidate.getString("name"));check(candidate.getBoolean("registered")&&candidate.getBoolean("registrationListed"),"successful table row marks registration");check(!candidate.getBoolean("gpuUtilizationVerified"),"registration never verifies utilization");}
  check(!names.contains("gpu.counters.fake")&&!names.contains("gpu.counters.fake2"),"producer and tracing session mentions are not source registration");check(candidates.getJSONObject(0).getString("note").contains("未验证 power/gpu_frequency"),"ftrace registration does not prove GPU frequency event exists");
  JSONArray incomplete=GpuTraceProbe.registrations(LIST,false).getJSONArray("dataSourceCandidates");for(int i=0;i<incomplete.length();i++)check(incomplete.getJSONObject(i).isNull("registered"),"failed/partial output cannot confirm registration");
  parsed=GpuTraceProbe.registrations("permission denied: gpu.counters.mali\n",true);check(!parsed.getBoolean("formatRecognized")&&parsed.getJSONArray("dataSourceCandidates").length()==0,"unknown format and source-like text are only raw evidence");
  parsed=GpuTraceProbe.registrations("DATA SOURCES REGISTERED:\nlinux.ftrace p (1) gpu.counters.false\n",true);check(parsed.getJSONArray("dataSourceCandidates").length()==1,"table details cannot impersonate registered source");
 }
 private static void successAndPermissions()throws Exception{
  final String[][] argv={null};FakeProcess process=new FakeProcess(LIST.getBytes(StandardCharsets.UTF_8),0,false);
  JSONObject result=GpuTraceProbe.query(2000,args->{argv[0]=args;return process;},1000,GpuTraceProbe.OUTPUT_LIMIT_BYTES);
  check(Arrays.equals(argv[0],new String[]{"/system/bin/perfetto","--query","--long"}),"fixed argv only; no shell or recording argument");check(result.getBoolean("querySucceeded")&&result.getBoolean("queryAttempted")&&result.getBoolean("outputComplete"),"successful query marked complete");check(result.getString("queryError").isEmpty()&&result.getInt("exitCode")==0,"successful exit status retained");check(result.getString("raw").equals(LIST),"raw output retained without stripping ANSI");check(result.getInt("capturedBytes")==LIST.getBytes(StandardCharsets.UTF_8).length&&!result.getBoolean("truncated"),"output byte accounting");check(!result.getBoolean("gpuUtilizationVerified")&&result.getString("note").contains("已注册不等于"),"top-level statement does not promise valid utilization");check(process.input.closed&&process.errors.closed&&process.output.closed&&!process.alive,"all process resources closed on success");
  AtomicInteger starts=new AtomicInteger();result=GpuTraceProbe.query(10043,args->{starts.incrementAndGet();return new FakeProcess(new byte[0],0,false);},1000,1024);check(starts.get()==0&&!result.getBoolean("queryAttempted")&&result.getString("status").equals("not_authorized"),"ordinary app UID cannot launch query");check(!result.getBoolean("querySucceeded")&&result.getJSONArray("dataSourceCandidates").length()==0,"skipped query makes no unsupported conclusion");
  result=GpuTraceProbe.query(0,args->new FakeProcess(LIST.getBytes(StandardCharsets.UTF_8),0,false),1000,GpuTraceProbe.OUTPUT_LIMIT_BYTES);check(result.getBoolean("querySucceeded"),"root identity is eligible");
 }
 private static void limitsAndFailures()throws Exception{
  byte[] output=new byte[GpuTraceProbe.OUTPUT_LIMIT_BYTES+10000];Arrays.fill(output,(byte)'a');FakeProcess over=new FakeProcess(output,0,false);
  JSONObject result=GpuTraceProbe.query(2000,args->over,1000,GpuTraceProbe.OUTPUT_LIMIT_BYTES);check(result.getInt("capturedBytes")==GpuTraceProbe.OUTPUT_LIMIT_BYTES&&result.getString("raw").length()==GpuTraceProbe.OUTPUT_LIMIT_BYTES,"capture never exceeds 128KB");check(result.getBoolean("truncated")&&result.getString("status").equals("output_limit")&&!result.getBoolean("querySucceeded"),"output limit gives explicit partial error");check(over.input.position<=GpuTraceProbe.OUTPUT_LIMIT_BYTES+1,"drain stops and kills child immediately after limit");check(over.destroyed&&over.input.closed&&over.errors.closed&&over.output.closed,"oversized process cleaned up before return");
  byte[] exact=new byte[1024];Arrays.fill(exact,(byte)'b');result=GpuTraceProbe.query(2000,args->new FakeProcess(exact,0,false),1000,1024);check(result.getBoolean("querySucceeded")&&!result.getBoolean("truncated"),"exact output cap followed by EOF is complete");
  FakeProcess blocked=new FakeProcess(new byte[0],0,true);long started=System.nanoTime();result=GpuTraceProbe.query(2000,args->blocked,80,1024);long elapsed=(System.nanoTime()-started)/1_000_000L;check(result.getString("status").equals("timeout")&&elapsed<1000,"blocking pipe cannot make timeout unbounded");check(blocked.destroyed&&blocked.input.closed&&blocked.errors.closed&&blocked.output.closed,"timeout closes and destroys child");
  result=GpuTraceProbe.query(2000,args->{throw new IOException("permission denied");},1000,1024);check(result.getString("status").equals("error")&&result.getString("queryError").contains("permission denied"),"startup denial is preserved as query error");check(!result.getBoolean("formatRecognized")&&!result.getBoolean("gpuUtilizationVerified"),"failed startup remains unknown capability");
  result=GpuTraceProbe.query(2000,args->new FakeProcess(LIST.getBytes(StandardCharsets.UTF_8),7,false),1000,GpuTraceProbe.OUTPUT_LIMIT_BYTES);check(result.getInt("exitCode")==7&&result.getString("status").equals("error"),"nonzero exit is explicit");check(result.getJSONArray("dataSourceCandidates").getJSONObject(1).isNull("registered"),"nonzero query exit leaves registration unconfirmed");
 }
 private static void cancellation()throws Exception{
  AtomicInteger starts=new AtomicInteger();Thread.currentThread().interrupt();JSONObject result;try{result=GpuTraceProbe.query(2000,args->{starts.incrementAndGet();return new FakeProcess(new byte[0],0,false);},1000,1024);check(Thread.currentThread().isInterrupted(),"preexisting interruption preserved");}finally{Thread.interrupted();}check(starts.get()==0&&result.getString("status").equals("cancelled"),"cancelled query cannot start child");
  FakeProcess process=new FakeProcess(new byte[0],0,true);CountDownLatch entered=new CountDownLatch(1);AtomicReference<JSONObject> response=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean preserved=new AtomicBoolean();Thread thread=new Thread(()->{try{response.set(GpuTraceProbe.query(2000,args->{entered.countDown();return process;},1000,1024));preserved.set(Thread.currentThread().isInterrupted());}catch(Throwable e){failure.set(e);}});
  thread.start();check(entered.await(1,TimeUnit.SECONDS),"cancel test child started");thread.interrupt();thread.join(1000);check(!thread.isAlive()&&failure.get()==null&&response.get().getString("status").equals("cancelled"),"running cancellation returns promptly");check(preserved.get()&&process.destroyed&&process.input.closed&&process.output.closed&&process.errors.closed,"cancellation preserves interrupt and closes child resources");
 }
 private static void lateStartupCleanup()throws Exception{
  FakeProcess process=new FakeProcess(new byte[0],0,false);CountDownLatch release=new CountDownLatch(1),returned=new CountDownLatch(1);JSONObject response=GpuTraceProbe.query(2000,args->{while(true){try{release.await();break;}catch(InterruptedException ignored){}}returned.countDown();return process;},50,1024);
  check(response.getString("status").equals("timeout"),"blocked process startup still bounded by caller deadline");release.countDown();check(returned.await(1,TimeUnit.SECONDS),"late startup returns");long until=System.nanoTime()+1_000_000_000L;while(!(process.destroyed&&process.input.closed&&process.output.closed&&process.errors.closed)&&System.nanoTime()<until)Thread.yield();check(process.destroyed&&process.input.closed&&process.output.closed&&process.errors.closed,"child created after timeout is immediately cleaned up");
 }
 private static void realPipes()throws Exception{
  String java=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").getAbsolutePath();AtomicReference<java.lang.Process> child=new AtomicReference<>();
  JSONObject result=GpuTraceProbe.query(2000,args->{java.lang.Process process=new ProcessBuilder(java,"-Xmx16m","-cp",System.getProperty("java.class.path"),GpuTraceProbeTests.class.getName()+"$Child","bulk").redirectErrorStream(true).start();child.set(process);return process;},1000,GpuTraceProbe.OUTPUT_LIMIT_BYTES);
  check(result.getString("status").equals("output_limit")&&result.getBoolean("truncated"),"real OS stdout/stderr pipes reach output cap without deadlock");check(result.getString("raw").contains("AAAA")&&result.getString("raw").contains("BBBB"),"combined stdout and stderr are drained");check(child.get()!=null&&child.get().waitFor(1,TimeUnit.SECONDS)&&!child.get().isAlive(),"output-limit OS child is terminated");
  child.set(null);result=GpuTraceProbe.query(2000,args->{java.lang.Process process=new ProcessBuilder(java,"-Xmx16m","-cp",System.getProperty("java.class.path"),GpuTraceProbeTests.class.getName()+"$Child","hang").redirectErrorStream(true).start();child.set(process);return process;},150,1024);
  check(result.getString("status").equals("timeout"),"real OS sleeping child is bounded by query deadline");check(child.get()!=null&&child.get().waitFor(1,TimeUnit.SECONDS)&&!child.get().isAlive(),"timeout OS child is terminated");
  for(boolean time:new boolean[]{true,false}){boolean rejected=false;try{GpuTraceProbe.query(2000,args->{throw new AssertionError("invalid bounds cannot launch process");},time?GpuTraceProbe.TIMEOUT_MS+1:1000,time?1024:GpuTraceProbe.OUTPUT_LIMIT_BYTES+1);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"test bounds cannot exceed production hard limits");}
 }
 public static final class Child {
  public static void main(String[] args)throws Exception{
   if(args[0].equals("hang")){System.out.print("waiting\n");System.out.flush();Thread.sleep(10000);return;}
   byte[] a=new byte[8192],b=new byte[8192];Arrays.fill(a,(byte)'A');Arrays.fill(b,(byte)'B');for(int i=0;i<1024;i++){System.out.write(a);System.out.flush();System.err.write(b);System.err.flush();}
  }
 }
 private static final class FakeProcess extends java.lang.Process {
  final FakeInput input,errors;final FakeOutput output=new FakeOutput();final int code;volatile boolean alive,destroyed;
  FakeProcess(byte[] bytes,int code,boolean blocked){this.code=code;alive=blocked;input=new FakeInput(bytes,blocked);errors=new FakeInput(new byte[0],false);}
  public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return errors;}public OutputStream getOutputStream(){return output;}
  public int waitFor()throws InterruptedException{while(alive)Thread.sleep(1);return code;}public int exitValue(){if(alive)throw new IllegalThreadStateException();return code;}
  public void destroy(){destroyed=true;alive=false;try{input.close();}catch(Exception ignored){}}public java.lang.Process destroyForcibly(){destroy();return this;}public boolean isAlive(){return alive;}
 }
 private static final class FakeInput extends InputStream {
  final byte[] bytes;final boolean blocked;int position;volatile boolean closed;
  FakeInput(byte[] bytes,boolean blocked){this.bytes=bytes;this.blocked=blocked;}
  public synchronized int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
  public synchronized int read(byte[] buffer,int start,int count)throws IOException{
   while(blocked&&!closed){try{wait();}catch(InterruptedException e){throw new InterruptedIOException();}}if(closed)throw new IOException("closed");if(position>=bytes.length)return -1;int n=Math.min(count,bytes.length-position);System.arraycopy(bytes,position,buffer,start,n);position+=n;return n;
  }
  public synchronized void close(){closed=true;notifyAll();}
 }
 private static final class FakeOutput extends OutputStream {volatile boolean closed;public void write(int value){}public void close(){closed=true;}}
}
