package com.lightframe.monitor;

import android.os.*;
import android.os.Process;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Shared collector for app UID, shell UID (Shizuku), or root UID. No shell per poll. */
public final class CoreSampler implements Closeable {
 private static final String GED_MODULE="/sys/module/ged/parameters/",GED_LOAD=GED_MODULE+"gpu_loading",GED_ENABLE=GED_MODULE+"gpu_dvfs_enable";
 private final Map<String,RandomAccessFile> nodes=new LinkedHashMap<>();
 // handle() serializes collection, so node reads can reuse one bounded buffer.
 private final byte[] nodeBytes=new byte[32768];
 private final Map<String,String> errors=new LinkedHashMap<>();
 private final NodeRetry retries=new NodeRetry();private boolean forceNodeReads;
 private final ForegroundTask foreground=new ForegroundTask();
 private final List<String> policies=new ArrayList<>(),cpuTemps=new ArrayList<>(),gpuTemps=new ArrayList<>(),socTemps=new ArrayList<>();
 private final List<String> gpuFreqs=new ArrayList<>(),gpuLoads=new ArrayList<>();
 private final ExecutorService dumps=Executors.newSingleThreadExecutor(task->new Thread(()->{
  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();
 },"lightframe-dump"));
 private final GpuTraceSession gpuTrace=new GpuTraceSession(Process.myUid(),GpuTraceData.CLOCK_MONOTONIC_RAW);
 private GpuTraceSession.Snapshot lastGpuTrace;private boolean gpuWorkStarted;
 private long[] lastCpu,lastNet;private long lastNetNs,lastLayerScan,lastFrameError;private String selectedLayer="",lastFilter="",lastManual="",frameDetail="尚未读取帧时间";private IBinder surface,thermalService;private FrameDiscovery frameDiscovery;
 private String thermalDump="",thermalError="";private long thermalDumpNs;
 private ThermalSnapshot cachedThermal=ThermalSnapshot.parse("");
 private JSONObject lastFrameSample=new JSONObject();
 private final Map<String,String> latencySnapshots=new LinkedHashMap<>();
 public CoreSampler(){discover();}
 private String read(String path){if(!Numbers.safeNode(path)||!forceNodeReads&&!retries.allowed(path,System.nanoTime()))return null;try{RandomAccessFile f=nodes.get(path);if(f==null){f=new RandomAccessFile(path,"r");nodes.put(path,f);}f.seek(0);int n=f.read(nodeBytes,0,path.startsWith("/proc/")?nodeBytes.length:4096);errors.remove(path);retries.succeeded(path);return n<0?"":new String(nodeBytes,0,n,StandardCharsets.UTF_8);}catch(Exception e){errors.put(path,e.getClass().getSimpleName()+": "+e.getMessage());retries.failed(path,System.nanoTime());RandomAccessFile failed=nodes.remove(path);if(failed!=null)try{failed.close();}catch(Exception ignored){}return null;}}
 private File[] children(String p){File[] a=new File(p).listFiles();if(a==null)return new File[0];Arrays.sort(a,Comparator.comparing(File::getName));return a;}
 private void discover(){
  for(File f:children("/sys/devices/system/cpu/cpufreq"))if(f.getName().startsWith("policy")&&policies.size()<16){String p=f+"/scaling_cur_freq";if(read(p)==null)p=f+"/cpuinfo_cur_freq";policies.add(p);}
  Collections.addAll(gpuFreqs,"/sys/class/kgsl/kgsl-3d0/gpuclk","/sys/kernel/ged/hal/current_freq","/sys/kernel/ged/hal/current_freqency","/sys/kernel/debug/ged/hal/current_freqency","/proc/gpufreqv2/gpufreq_status","/proc/gpufreqv2/gpufreq_var_dump","/proc/gpufreq/gpufreq_var_dump");
  Collections.addAll(gpuLoads,"/sys/class/kgsl/kgsl-3d0/gpubusy","/sys/kernel/ged/hal/gpu_utilization","/sys/kernel/ged/hal/gpu_loading",GED_LOAD,"/sys/kernel/debug/ged/hal/gpu_utilization");
  for(File f:children("/sys/class/devfreq")){String n=f.getName().toLowerCase(Locale.ROOT);String name=read(f+"/name");if(name!=null)n+=name.toLowerCase(Locale.ROOT);if(n.contains("gpu")||n.contains("mali")||n.contains("kgsl")){gpuFreqs.add(f+"/cur_freq");gpuLoads.add(f+"/load");gpuLoads.add(f+"/gpu_load");gpuLoads.add(f+"/utilization");}}
  for(File f:children("/sys/class/thermal"))if(f.getName().startsWith("thermal_zone")){String type=read(f+"/type");if(type==null)continue;type=type.trim().toLowerCase(Locale.ROOT);List<String> a=type.contains("gpu")?gpuTemps:type.contains("cpu")?cpuTemps:(type.contains("soc")||type.contains("tsens")||type.equals("ap")||type.contains("mtktsap"))?socTemps:null;if(a!=null&&a.size()<8)a.add(f+"/temp");}
  // Type/name files are one-time probes. Retain only selected recurring sensor descriptors later.
  for(Iterator<Map.Entry<String,RandomAccessFile>> it=nodes.entrySet().iterator();it.hasNext();){Map.Entry<String,RandomAccessFile> e=it.next();if(e.getKey().endsWith("/type")||e.getKey().endsWith("/name")){try{e.getValue().close();}catch(Exception ignored){}it.remove();}}
 }
 public static void put(JSONObject o,String k,double v)throws JSONException{o.put(k,Double.isFinite(v)?v:JSONObject.NULL);}
 private double thermal(List<String> paths){double max=Double.NaN;for(String p:paths){double v=Numbers.temp(read(p));if(Double.isFinite(v))max=Double.isNaN(max)?v:Math.max(max,v);}return max;}
 private double gpu(JSONObject out,JSONObject cfg,boolean freq)throws JSONException{
  String custom=cfg.optString(freq?"gpuFreqPath":"gpuLoadPath","");List<String> a=Numbers.safeNode(custom)?Collections.singletonList(custom):freq?gpuFreqs:gpuLoads;
  boolean disabled=false;for(String p:a){String s=read(p);double v;
   if(freq){if(p.equals(custom)){double unit=cfg.optString("gpuFreqUnit","kHz").equals("Hz")?1e6:cfg.optString("gpuFreqUnit","kHz").equals("MHz")?1:1000;v=Numbers.first(s)/unit;}else if(p.contains("/ged/"))v=Numbers.gedFreq(s);else if(p.contains("/gpufreq"))v=Numbers.mtkFreq(s);else v=Numbers.first(s)/1e6;if(!(v>0&&v<10000))v=Double.NaN;}
   else if(p.equals(GED_LOAD)){String enabled=read(GED_ENABLE);double[] flags=Numbers.tokens(enabled);disabled|=s!=null&&flags.length==1&&flags[0]==0;v=Numbers.gedModuleLoad(s,enabled);}
   else v=p.endsWith("gpubusy")||p.equals(custom)&&cfg.optString("gpuLoadFormat").equals("busy_total")?Numbers.busy(s):Numbers.percent(s);
   if(Double.isFinite(v)){out.put(freq?"gpuFrequencyStatus":"gpuLoadStatus",p);return v;}
  }boolean denied=false;for(String p:a){String error=errors.get(p);if(error!=null&&(error.contains("EACCES")||error.contains("Permission denied")))denied=true;}
  out.put(freq?"gpuFrequencyStatus":"gpuLoadStatus",disabled?"联发科 GPU 负载接口未启用"+(denied?"；其他节点读取受限":""):denied?"系统拒绝当前授权读取 GPU 节点":"系统未提供可读取的 GPU 节点");return Double.NaN;
 }
 private void gpuValues(JSONObject out,JSONObject cfg,long now,boolean sampling)throws JSONException{
  double frequency=gpu(out,cfg,true),load=gpu(out,cfg,false);
  out.put("gpuLoadKind",Double.isFinite(load)?"node_utilization":"");
  for(String key:new String[]{"gpuWindowBeginNs","gpuWindowEndNs","gpuAgeMs","gpuFrequencySampleNs","gpuFrequencyAgeMs"})out.put(key,JSONObject.NULL);
  boolean traceLoad=!Double.isFinite(load)&&cfg.optString("gpuLoadPath","").isEmpty();
  boolean traceFrequency=!Double.isFinite(frequency)&&cfg.optString("gpuFreqPath","").isEmpty();
  boolean workEnabled=cfg.optBoolean("gpuWorkEnabled",false);
  if(!workEnabled){
   if(gpuWorkStarted){gpuTrace.suspend();gpuWorkStarted=false;}
   lastGpuTrace=null;out.put("gpuTraceStatus","disabled");out.put("gpuTraceError","");
   if(traceLoad)out.put("gpuLoadStatus","GPU 工作追踪已关闭（低开销）；可在设置中单独启用");
   if(traceFrequency)out.put("gpuFrequencyStatus","GPU 频率追踪已关闭（低开销）；可在设置中单独启用");
  }else if((traceLoad||traceFrequency)&&sampling){lastGpuTrace=gpuTrace.poll(now);gpuWorkStarted=true;}
  GpuTraceSession.Snapshot trace=workEnabled?lastGpuTrace:null;
  long freshnessNs=Math.max(12_000_000_000L,Math.max(100,Math.min(60000,cfg.optInt("hardwarePeriod",1000)))*1_000_000L+10_000_000_000L);
  if(trace!=null&&(traceLoad||traceFrequency)){
   out.put("gpuTraceStatus",trace.status);out.put("gpuTraceError",trace.error);
   if(traceLoad){
    if(Double.isFinite(trace.busyPct)&&trace.endNs>0&&now>=trace.endNs&&now-trace.readNs<freshnessNs){
     load=trace.busyPct;out.put("gpuLoadKind","work_activity");out.put("gpuWindowBeginNs",trace.beginNs);out.put("gpuWindowEndNs",trace.endNs);put(out,"gpuAgeMs",(now-trace.endNs)/1e6);
     out.put("gpuLoadStatus","系统 GPU 工作忙碌率 · 完整区间并集 / 1 秒窗口 · 约 8 秒更新 · 窗口结束于 "+Numbers.display((now-trace.endNs)/1e9,1)+" 秒前；保护内容可能不报告");
    }else out.put("gpuLoadStatus",trace.error.isEmpty()?"GPU 工作采样中；正在等待完整窗口":"GPU 工作接口不可用："+trace.error);
   }
   if(traceFrequency){
    if(Double.isFinite(trace.frequencyMHz)&&trace.frequencyNs>0&&now>=trace.frequencyNs&&now-trace.readNs<freshnessNs){
     frequency=trace.frequencyMHz;out.put("gpuFrequencySampleNs",trace.frequencyNs);put(out,"gpuFrequencyAgeMs",(now-trace.frequencyNs)/1e6);
     out.put("gpuFrequencyStatus","系统 GPU 频率事件 · 最新已报告频率 · "+Numbers.display((now-trace.frequencyNs)/1e9,1)+" 秒前");
    }else out.put("gpuFrequencyStatus",trace.error.isEmpty()?"GPU 频率采样中；等待系统频率事件":"GPU 频率事件不可用："+trace.error);
   }
  }
  put(out,"gpuMHz",frequency);put(out,"gpuPct",load);
 }
 private String dump(String... args)throws Exception{return serviceDump("SurfaceFlinger",args);}
 private String serviceDump(String service,String... args)throws Exception{
  IBinder binder;
  if(service.equals("SurfaceFlinger")){if(surface==null)surface=findService(service);binder=surface;}
  else{if(thermalService==null)thermalService=findService(service);binder=thermalService;}
  if(binder==null)throw new IOException(service+" unavailable");
  final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();Future<String> f=dumps.submit(()->{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;){if(b.size()+n>524288)throw new IOException("dump too large");b.write(buf,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);}});
  try{binder.dumpAsync(pipe[1].getFileDescriptor(),args);pipe[1].close();return f.get(args.length>0&&args[0].equals("--latency")?350:1500,TimeUnit.MILLISECONDS);}finally{try{pipe[0].close();}catch(Exception ignored){}try{pipe[1].close();}catch(Exception ignored){}f.cancel(true);}
 }
 private IBinder findService(String name)throws Exception{return (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);}
 private List<String> layerList()throws Exception{return SurfaceLayers.parse(dump("--list"));}
 private long[] readPresent(String layer)throws Exception{
  String raw;try{raw=dump("--latency",layer);}catch(Exception e){rememberLatency(layer,e.toString());throw e;}
  rememberLatency(layer,raw);String lower=raw.toLowerCase(Locale.ROOT);
  if(lower.contains("permission denial")||lower.contains("permission denied"))throw new SecurityException("系统拒绝读取帧时间");
  return SurfaceLatency.present(raw);
 }
 private void rememberLatency(String layer,String raw){latencySnapshots.remove(layer);latencySnapshots.put(layer,raw.substring(0,Math.min(raw.length(),2048)));while(latencySnapshots.size()>12)latencySnapshots.remove(latencySnapshots.keySet().iterator().next());}
 private boolean useful(String s,String filter){String l=s.toLowerCase(Locale.ROOT);return (filter.isEmpty()||s.contains(filter))&&!l.contains("com.lightframe.monitor")&&!l.contains("systemui")&&!l.contains("launcher")&&!l.contains("inputmethod")&&!l.contains("shizuku")&&!l.contains("statusbar")&&!l.contains("navigationbar");}
 private void temperatures(JSONObject o,JSONObject cfg,long now,boolean diagnose)throws JSONException{
  double cpu=thermal(cpuTemps),soc=thermal(socTemps);String path=cfg.optString("gpuTempPath","");
  double gpu=Numbers.safeNode(path)?Numbers.temp(read(path)):thermal(gpuTemps);
  boolean missing=!Double.isFinite(cpu)||!Double.isFinite(gpu)||!Double.isFinite(soc);
  if((missing||diagnose)&&(diagnose||thermalDumpNs==0||now-thermalDumpNs>=5_000_000_000L)){
   try{thermalDump=serviceDump("thermalservice");thermalError="";}catch(Exception e){thermalDump="";thermalError=e.getClass().getSimpleName()+": "+e.getMessage();}thermalDumpNs=System.nanoTime();cachedThermal=ThermalSnapshot.parse(thermalDump);
  }
  ThermalSnapshot snapshot=cachedThermal;
  long readAgeMs=Math.max(0,(System.nanoTime()-thermalDumpNs)/1_000_000L);
  String source=(snapshot.source.equals("current")?"系统温度服务":"系统温度服务缓存（原始更新时间未提供）")+" · 读取于 "+Numbers.display(readAgeMs/1000d,1)+" 秒前";
  String unavailable=snapshot.permissionDenied?"系统拒绝温度读取":!thermalError.isEmpty()?"温度服务读取失败":"系统未报告此类芯片温度";
  String cpuStatus=Double.isFinite(cpu)?"系统传感器":unavailable;
  String gpuStatus=Double.isFinite(gpu)?Numbers.safeNode(path)?path:"系统传感器":Numbers.safeNode(path)?"指定 GPU 温度节点读取失败":unavailable;
  String socStatus=Double.isFinite(soc)?"系统传感器":unavailable;
  boolean usedService=false;
  if(!Double.isFinite(cpu)&&Double.isFinite(snapshot.cpuC)){cpu=snapshot.cpuC;cpuStatus=source;usedService=true;}
  if(!Double.isFinite(gpu)&&Double.isFinite(snapshot.gpuC)&&!Numbers.safeNode(path)){gpu=snapshot.gpuC;gpuStatus=source;usedService=true;}
  if(!Double.isFinite(soc)&&Double.isFinite(snapshot.socC)){soc=snapshot.socC;socStatus=source;usedService=true;}
  put(o,"cpuC",cpu);put(o,"gpuC",gpu);put(o,"socC",soc);
  o.put("cpuTemperatureStatus",cpuStatus);o.put("gpuTemperatureStatus",gpuStatus);o.put("socTemperatureStatus",socStatus);
  o.put("thermalServiceStatus",thermalError.isEmpty()?snapshot.status:thermalError);
  o.put("thermalServiceSource",snapshot.source);o.put("thermalServiceReadNs",thermalDumpNs);
  o.put("thermalServiceAgeMs",thermalDumpNs==0?JSONObject.NULL:readAgeMs);
  o.put("temperatureSampleNs",usedService?thermalDumpNs:System.nanoTime());
  if(diagnose){
   o.put("thermalServiceRaw",thermalDump.substring(0,Math.min(thermalDump.length(),65536)));
   if(snapshot.halReady!=null)o.put("thermalHalReady",snapshot.halReady);
   if(snapshot.thermalStatus>=0)o.put("thermalServiceStatusCode",snapshot.thermalStatus);
   JSONArray sensors=new JSONArray();for(ThermalSnapshot.Sensor s:snapshot.sensors){JSONObject sensor=new JSONObject();sensor.put("name",s.name);sensor.put("type",s.type);put(sensor,"value",s.value);sensor.put("status",s.status);sensor.put("source",s.source);sensor.put("category",s.category);sensors.put(sensor);}o.put("thermalSensors",sensors);
  }
 }
 private void frames(JSONObject o,JSONObject cfg,long now)throws JSONException{
  o.put("present",new JSONArray());o.put("frameAvailable",false);o.put("layer","");o.put("frameSourceVerified",false);
  long fallbackPeriod=FrameBudget.periodNs(cfg.optDouble("displayRefreshHz",Double.NaN));
  o.put("frameBudgetNs",fallbackPeriod>0?fallbackPeriod:JSONObject.NULL);put(o,"frameBudgetHz",FrameBudget.hz(fallbackPeriod));
  o.put("frameBudgetSource",fallbackPeriod>0?"display_refresh_rate":"unavailable");o.put("frameBudgetSampleNs",now);
  o.put("foregroundPackage","");o.put("foregroundStatus","");o.put("foregroundError","");o.put("frameProbeCount",0);o.put("frameCandidateCount",0);o.put("frameProbeFailures",0);
  if(Process.myUid()!=0&&Process.myUid()!=2000){o.put("frameStatus","FPS 需要已授权的 Shizuku / Root");return;}
  String manual=SurfaceLayers.name(cfg.optString("layer",""));String filter=cfg.optString("package","").trim();
  if(filter.isEmpty()&&manual.isEmpty()){
   filter=foreground.read(now);o.put("foregroundPackage",filter);o.put("foregroundStatus",foreground.status);if(!foreground.error.isEmpty())o.put("foregroundError",foreground.error);
   if(filter.isEmpty()||filter.equals("com.lightframe.monitor")||filter.equals("com.android.systemui")){
    selectedLayer="";frameDiscovery=null;lastLayerScan=lastFrameError=0;lastFilter=filter;
    o.put("frameStatus",filter.isEmpty()?foreground.status:"当前前台为轻帧或系统界面，等待目标应用");return;
   }
  }
  try{
   if(!filter.equals(lastFilter)||!manual.equals(lastManual)){selectedLayer="";frameDiscovery=null;lastLayerScan=lastFrameError=0;lastFilter=filter;lastManual=manual;}
   if(lastFrameError>0&&now-lastFrameError<5_000_000_000L){o.put("frameStatus",frameDetail+"；稍后重试");return;}
   long[] t=null;
   if(!manual.isEmpty())selectedLayer=manual;
   if(!selectedLayer.isEmpty()){
    t=readPresent(selectedLayer);long current=System.nanoTime();
    if(t.length==0||t[t.length-1]<current-3_000_000_000L||t[t.length-1]>current+100_000_000L){
     if(!manual.isEmpty()){frameDetail="指定图层没有返回最近的帧时间";o.put("frameStatus",frameDetail);lastFrameError=now;return;}
     selectedLayer="";t=null;frameDiscovery=null;lastLayerScan=0;
    }
   }
   if(selectedLayer.isEmpty()){
    if(frameDiscovery==null&&(lastLayerScan==0||now-lastLayerScan>=5_000_000_000L)){
     lastLayerScan=now;List<String> a=SurfaceLayers.rankDump(dump("--list"),filter);for(Iterator<String> it=a.iterator();it.hasNext();)if(!useful(it.next(),""))it.remove();frameDiscovery=new FrameDiscovery(a);
    }
    if(frameDiscovery!=null){
     FrameDiscovery.Result found=frameDiscovery.advance(this::readPresent,System.nanoTime(),3,150_000_000L);
     o.put("frameProbeCount",frameDiscovery.cursor);o.put("frameCandidateCount",frameDiscovery.candidates.size());o.put("frameProbeFailures",frameDiscovery.failures);
     if(found!=null){selectedLayer=found.layer;t=found.present;frameDiscovery=null;}
     else if(frameDiscovery.finished()){frameDetail=frameDiscovery.failures==frameDiscovery.candidates.size()&&frameDiscovery.failures>0?"候选图层读取失败："+frameDiscovery.lastError:"未发现返回最近帧时间的图层；可填写目标游戏包名";frameDiscovery=null;lastLayerScan=System.nanoTime();}
     else frameDetail="正在查找活动图层（"+frameDiscovery.cursor+" / "+frameDiscovery.candidates.size()+"）";
    }
    if(selectedLayer.isEmpty()){o.put("frameStatus",frameDetail);return;}
   }
   long period=FrameBudget.latencyPeriodNs(latencySnapshots.get(selectedLayer));
   if(period>0){o.put("frameBudgetNs",period);put(o,"frameBudgetHz",FrameBudget.hz(period));o.put("frameBudgetSource","surfaceflinger_vsync");o.put("frameBudgetSampleNs",System.nanoTime());}
   JSONArray a=new JSONArray();for(long p:t)a.put(p);o.put("present",a);o.put("layer",selectedLayer);o.put("frameAvailable",true);o.put("frameSourceVerified",!filter.isEmpty()||!manual.isEmpty());frameDetail="已读取实际呈现帧时间";o.put("frameStatus",frameDetail);
  }catch(Exception e){lastFrameError=now;selectedLayer="";frameDiscovery=null;frameDetail="帧接口读取失败："+e.getClass().getSimpleName()+" "+e.getMessage();o.put("frameStatus",frameDetail);}
 }
 public synchronized JSONObject handle(JSONObject req)throws Exception{
  forceNodeReads=req.optString("op","sample").equals("diagnose");
  try{return collect(req);}finally{forceNodeReads=false;}
 }
 private JSONObject collect(JSONObject req)throws Exception{
  String op=req.optString("op","sample");JSONObject cfg=req.optJSONObject("config");if(cfg==null)cfg=new JSONObject();JSONObject o=new JSONObject();long now=System.nanoTime();o.put("uid",Process.myUid());o.put("nowNs",now);
  if(op.equals("suspend")){gpuTrace.suspend();lastGpuTrace=null;gpuWorkStarted=false;return o;}
  if(op.equals("layers")){JSONArray a=new JSONArray();for(String l:layerList())a.put(l);o.put("layers",a);return o;}
  if(req.optBoolean("hardware",true)||op.equals("diagnose")){
   long[] cpu=Numbers.cpu(read("/proc/stat"));put(o,"cpuPct",Numbers.cpuPct(lastCpu,cpu));lastCpu=cpu;
   JSONArray clocks=new JSONArray();for(int i=0;i<policies.size();i++){double v=Numbers.first(read(policies.get(i)))/1000;put(o,"cpu"+i+"MHz",v);clocks.put(Double.isFinite(v)?v:JSONObject.NULL);}o.put("cpuMHz",clocks);
   gpuValues(o,cfg,now,op.equals("sample"));
   String mem=read("/proc/meminfo");double total=Numbers.mem(mem,"MemTotal"),avail=Numbers.mem(mem,"MemAvailable");put(o,"ramTotalMB",total);put(o,"ramUsedMB",total-avail);
   long[] net=Numbers.net(read("/proc/net/dev"));double sec=(now-lastNetNs)/1e9;put(o,"rxKBs",net!=null&&lastNet!=null&&sec>0?Math.max(0,net[0]-lastNet[0])/1024/sec:Double.NaN);put(o,"txKBs",net!=null&&lastNet!=null&&sec>0?Math.max(0,net[1]-lastNet[1])/1024/sec:Double.NaN);lastNet=net;lastNetNs=now;o.put("hardwareSampleNs",System.nanoTime());
  }
  if(req.optBoolean("temperatures",true)||op.equals("diagnose"))temperatures(o,cfg,now,op.equals("diagnose"));
  if(op.equals("diagnose"))o.put("lastFrameSample",lastFrameSample);
  if(req.optBoolean("frames",true)||op.equals("diagnose")){
   frames(o,cfg,now);
   if(op.equals("sample")){lastFrameSample=new JSONObject();for(String k:new String[]{"nowNs","layer","frameStatus","frameAvailable","frameSourceVerified","foregroundPackage","foregroundStatus","foregroundError","frameProbeCount","frameCandidateCount","frameProbeFailures","frameBudgetNs","frameBudgetHz","frameBudgetSource","frameBudgetSampleNs"})if(o.has(k))lastFrameSample.put(k,o.get(k));lastFrameSample.put("returnedFrameTimes",o.optJSONArray("present").length());}
  }
  o.put("helperCpuMs",Process.getElapsedCpuTime());if(req.optBoolean("hardware",true)||op.equals("diagnose"))put(o,"helperRssMB",Numbers.mem(read("/proc/self/status"),"VmRSS"));
  if(op.equals("diagnose")){o.put("version",Config.VERSION);o.put("config",cfg);o.put("latencyProbeSamples",new JSONObject(latencySnapshots));JSONObject detail=new JSONObject();Set<String> probes=new LinkedHashSet<>();probes.addAll(gpuFreqs);probes.addAll(gpuLoads);probes.add(GED_ENABLE);probes.add(GED_MODULE+"gpu_block");probes.add(GED_MODULE+"gpu_idle");for(String key:new String[]{"gpuFreqPath","gpuLoadPath","gpuTempPath"}){String path=cfg.optString(key,"");if(Numbers.safeNode(path))probes.add(path);}probes.addAll(cpuTemps);probes.addAll(gpuTemps);probes.addAll(socTemps);probes.addAll(policies);for(String p:probes){String v=read(p);detail.put(p,v==null?errors.get(p):v.substring(0,Math.min(v.length(),512)));}for(File f:children("/sys/class/thermal"))if(f.getName().startsWith("thermal_zone"))detail.put(f+"/type",read(f+"/type"));o.put("nodes",detail);try{String raw=dump("--list");o.put("layersRaw",raw);o.put("layers",new JSONArray(SurfaceLayers.parse(raw)));}catch(Exception e){o.put("layerError",e.toString());}Debug.MemoryInfo mi=new Debug.MemoryInfo();Debug.getMemoryInfo(mi);o.put("collectorPssMB",mi.getTotalPss()/1024d);}
  if(op.equals("diagnose"))o.put("gpuTraceProbe",GpuTraceProbe.query(Process.myUid()));
  return o;
 }
 public synchronized void close(){gpuTrace.close();lastGpuTrace=null;for(RandomAccessFile f:nodes.values())try{f.close();}catch(Exception ignored){}nodes.clear();dumps.shutdownNow();}
}
