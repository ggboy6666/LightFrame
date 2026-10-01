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
 private final Map<String,RandomAccessFile> nodes=new LinkedHashMap<>();
 private final Map<String,String> errors=new LinkedHashMap<>();
 private final List<String> policies=new ArrayList<>(),cpuTemps=new ArrayList<>(),gpuTemps=new ArrayList<>(),socTemps=new ArrayList<>();
 private final List<String> gpuFreqs=new ArrayList<>(),gpuLoads=new ArrayList<>();
 private final ExecutorService dumps=Executors.newSingleThreadExecutor();
 private long[] lastCpu,lastNet;private long lastNetNs,lastLayerScan,lastFrameError;private String selectedLayer="",lastFilter="";private IBinder surface;
 public CoreSampler(){discover();}
 private String read(String path){if(!Numbers.safeNode(path))return null;try{RandomAccessFile f=nodes.get(path);if(f==null){f=new RandomAccessFile(path,"r");nodes.put(path,f);}f.seek(0);byte[] b=new byte[path.startsWith("/proc/")?32768:4096];int n=f.read(b);errors.remove(path);return n<0?"":new String(b,0,n,StandardCharsets.UTF_8);}catch(Exception e){errors.put(path,e.getClass().getSimpleName()+": "+e.getMessage());return null;}}
 private File[] children(String p){File[] a=new File(p).listFiles();if(a==null)return new File[0];Arrays.sort(a,Comparator.comparing(File::getName));return a;}
 private void discover(){
  for(File f:children("/sys/devices/system/cpu/cpufreq"))if(f.getName().startsWith("policy")&&policies.size()<16){String p=f+"/scaling_cur_freq";if(read(p)==null)p=f+"/cpuinfo_cur_freq";policies.add(p);}
  Collections.addAll(gpuFreqs,"/sys/class/kgsl/kgsl-3d0/gpuclk","/sys/kernel/ged/hal/current_freq","/sys/kernel/ged/hal/current_freqency","/sys/kernel/debug/ged/hal/current_freqency","/proc/gpufreqv2/gpufreq_status","/proc/gpufreqv2/gpufreq_var_dump","/proc/gpufreq/gpufreq_var_dump");
  Collections.addAll(gpuLoads,"/sys/class/kgsl/kgsl-3d0/gpubusy","/sys/kernel/ged/hal/gpu_utilization","/sys/kernel/ged/hal/gpu_loading","/sys/kernel/debug/ged/hal/gpu_utilization");
  for(File f:children("/sys/class/devfreq")){String n=f.getName().toLowerCase(Locale.ROOT);String name=read(f+"/name");if(name!=null)n+=name.toLowerCase(Locale.ROOT);if(n.contains("gpu")||n.contains("mali")||n.contains("kgsl")){gpuFreqs.add(f+"/cur_freq");gpuLoads.add(f+"/load");gpuLoads.add(f+"/gpu_load");gpuLoads.add(f+"/utilization");}}
  for(File f:children("/sys/class/thermal"))if(f.getName().startsWith("thermal_zone")){String type=read(f+"/type");if(type==null)continue;type=type.trim().toLowerCase(Locale.ROOT);List<String> a=type.contains("gpu")?gpuTemps:type.contains("cpu")?cpuTemps:(type.contains("soc")||type.contains("tsens")||type.equals("ap")||type.contains("mtktsap"))?socTemps:null;if(a!=null&&a.size()<8)a.add(f+"/temp");}
  // Type/name files are one-time probes. Retain only selected recurring sensor descriptors later.
  for(Iterator<Map.Entry<String,RandomAccessFile>> it=nodes.entrySet().iterator();it.hasNext();){Map.Entry<String,RandomAccessFile> e=it.next();if(e.getKey().endsWith("/type")||e.getKey().endsWith("/name")){try{e.getValue().close();}catch(Exception ignored){}it.remove();}}
 }
 public static void put(JSONObject o,String k,double v)throws JSONException{o.put(k,Double.isFinite(v)?v:JSONObject.NULL);}
 private double thermal(List<String> paths){double max=Double.NaN;for(String p:paths){double v=Numbers.temp(read(p));if(Double.isFinite(v))max=Double.isNaN(max)?v:Math.max(max,v);}return max;}
 private double gpu(JSONObject out,JSONObject cfg,boolean freq)throws JSONException{
  String custom=cfg.optString(freq?"gpuFreqPath":"gpuLoadPath","");List<String> a=Numbers.safeNode(custom)?Collections.singletonList(custom):freq?gpuFreqs:gpuLoads;
  for(String p:a){String s=read(p);double v;
   if(freq){if(p.equals(custom)){double unit=cfg.optString("gpuFreqUnit","kHz").equals("Hz")?1e6:cfg.optString("gpuFreqUnit","kHz").equals("MHz")?1:1000;v=Numbers.first(s)/unit;}else if(p.contains("/ged/"))v=Numbers.gedFreq(s);else if(p.contains("/gpufreq"))v=Numbers.mtkFreq(s);else v=Numbers.first(s)/1e6;if(!(v>0&&v<10000))v=Double.NaN;}
   else v=p.endsWith("gpubusy")||p.equals(custom)&&cfg.optString("gpuLoadFormat").equals("busy_total")?Numbers.busy(s):Numbers.percent(s);
   if(Double.isFinite(v)){out.put(freq?"gpuFrequencyStatus":"gpuLoadStatus",p);return v;}
  }out.put(freq?"gpuFrequencyStatus":"gpuLoadStatus","未读到可用节点（权限或机型差异）");return Double.NaN;
 }
 private String dump(String... args)throws Exception{
  if(surface==null)surface=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"SurfaceFlinger");
  if(surface==null)throw new IOException("SurfaceFlinger unavailable");
  final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();Future<String> f=dumps.submit(()->{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[4096];for(int n;(n=in.read(buf))!=-1;){if(b.size()+n>524288)throw new IOException("dump too large");b.write(buf,0,n);}return new String(b.toByteArray(),StandardCharsets.UTF_8);}});
  try{surface.dumpAsync(pipe[1].getFileDescriptor(),args);pipe[1].close();return f.get(1500,TimeUnit.MILLISECONDS);}finally{try{pipe[0].close();}catch(Exception ignored){}try{pipe[1].close();}catch(Exception ignored){}f.cancel(true);}
 }
 private List<String> layerList()throws Exception{String s=dump("--list");ArrayList<String> a=new ArrayList<>();for(String l:s.split("\n"))if(!l.trim().isEmpty())a.add(l.trim());return a;}
 private boolean useful(String s,String filter){String l=s.toLowerCase(Locale.ROOT);return (filter.isEmpty()||s.contains(filter))&&!l.contains("com.lightframe.monitor")&&!l.contains("systemui")&&!l.contains("launcher")&&!l.contains("inputmethod")&&!l.contains("shizuku")&&!l.contains("statusbar")&&!l.contains("navigationbar");}
 private void frames(JSONObject o,JSONObject cfg,long now)throws JSONException{
  if(Process.myUid()!=0&&Process.myUid()!=2000){o.put("frameStatus","FPS 需要已授权的 Shizuku / Root");o.put("present",new JSONArray());return;}
  String manual=cfg.optString("layer","");String filter=cfg.optString("package","");try{
   if(now-lastFrameError<5_000_000_000L){o.put("frameStatus","帧接口不可用，稍后重试");o.put("present",new JSONArray());return;}
   if(!filter.equals(lastFilter)){selectedLayer="";lastLayerScan=0;lastFilter=filter;}
   if(!manual.isEmpty())selectedLayer=manual;
   if(manual.isEmpty()&&(selectedLayer.isEmpty()||now-lastLayerScan>5_000_000_000L)){
    lastLayerScan=now;List<String> layers=layerList();layers.sort((a,b)->Boolean.compare(!a.contains("SurfaceView"),!b.contains("SurfaceView")));int probed=0;long newest=0;String candidate="";
    for(String l:layers)if(useful(l,filter)&&probed++<8){long[] t=Numbers.present(dump("--latency",l));if(t.length>0){long p=t[t.length-1];if(p<=now+100_000_000L&&p>now-2_000_000_000L&&p>newest){candidate=l;newest=p;}}}selectedLayer=candidate;
   }
   if(selectedLayer.isEmpty()){o.put("frameStatus","未发现活动图层；可指定包名或图层");o.put("present",new JSONArray());return;}
   long[] t=Numbers.present(dump("--latency",selectedLayer));JSONArray a=new JSONArray();for(long p:t)a.put(p);o.put("present",a);o.put("layer",selectedLayer);o.put("frameStatus",t.length==0?"图层未返回帧时间；请尝试其他图层":"SurfaceFlinger 实际呈现时间");
   if(t.length>0&&now-t[t.length-1]>3_000_000_000L&&manual.isEmpty())selectedLayer="";
  }catch(Exception e){lastFrameError=now;o.put("present",new JSONArray());o.put("frameStatus","帧接口: "+e.getClass().getSimpleName()+" "+e.getMessage());}
 }
 public synchronized JSONObject handle(JSONObject req)throws Exception{
  String op=req.optString("op","sample");JSONObject cfg=req.optJSONObject("config");if(cfg==null)cfg=new JSONObject();JSONObject o=new JSONObject();long now=System.nanoTime();o.put("uid",Process.myUid());o.put("nowNs",now);
  if(op.equals("layers")){JSONArray a=new JSONArray();for(String l:layerList())a.put(l);o.put("layers",a);return o;}
  if(req.optBoolean("hardware",true)||op.equals("diagnose")){
   long[] cpu=Numbers.cpu(read("/proc/stat"));put(o,"cpuPct",Numbers.cpuPct(lastCpu,cpu));lastCpu=cpu;
   JSONArray clocks=new JSONArray();for(int i=0;i<policies.size();i++){double v=Numbers.first(read(policies.get(i)))/1000;put(o,"cpu"+i+"MHz",v);clocks.put(Double.isFinite(v)?v:JSONObject.NULL);}o.put("cpuMHz",clocks);
   put(o,"gpuMHz",gpu(o,cfg,true));put(o,"gpuPct",gpu(o,cfg,false));
   String mem=read("/proc/meminfo");double total=Numbers.mem(mem,"MemTotal"),avail=Numbers.mem(mem,"MemAvailable");put(o,"ramTotalMB",total);put(o,"ramUsedMB",total-avail);
   long[] net=Numbers.net(read("/proc/net/dev"));double sec=(now-lastNetNs)/1e9;put(o,"rxKBs",net!=null&&lastNet!=null&&sec>0?Math.max(0,net[0]-lastNet[0])/1024/sec:Double.NaN);put(o,"txKBs",net!=null&&lastNet!=null&&sec>0?Math.max(0,net[1]-lastNet[1])/1024/sec:Double.NaN);lastNet=net;lastNetNs=now;o.put("hardwareSampleNs",System.nanoTime());
  }
  if(req.optBoolean("temperatures",true)||op.equals("diagnose")){put(o,"cpuC",thermal(cpuTemps));put(o,"socC",thermal(socTemps));String path=cfg.optString("gpuTempPath","");double t=Numbers.safeNode(path)?Numbers.temp(read(path)):thermal(gpuTemps);put(o,"gpuC",t);o.put("gpuTemperatureStatus",Double.isFinite(t)?Numbers.safeNode(path)?path:gpuTemps.toString():"未读到 GPU 温度；未用电池温度替代");o.put("temperatureSampleNs",System.nanoTime());}
  if(req.optBoolean("frames",true))frames(o,cfg,now);
  o.put("helperCpuMs",Process.getElapsedCpuTime());if(req.optBoolean("hardware",true)||op.equals("diagnose"))put(o,"helperRssMB",Numbers.mem(read("/proc/self/status"),"VmRSS"));
  if(op.equals("diagnose")){JSONObject detail=new JSONObject();Set<String> probes=new LinkedHashSet<>();probes.addAll(gpuFreqs);probes.addAll(gpuLoads);probes.addAll(cpuTemps);probes.addAll(gpuTemps);probes.addAll(socTemps);probes.addAll(policies);for(String p:probes){String v=read(p);detail.put(p,v==null?errors.get(p):v.substring(0,Math.min(v.length(),512)));}for(File f:children("/sys/class/thermal"))if(f.getName().startsWith("thermal_zone"))detail.put(f+"/type",read(f+"/type"));o.put("nodes",detail);try{o.put("layers",new JSONArray(layerList()));}catch(Exception e){o.put("layerError",e.toString());}Debug.MemoryInfo mi=new Debug.MemoryInfo();Debug.getMemoryInfo(mi);o.put("collectorPssMB",mi.getTotalPss()/1024d);}
  return o;
 }
 public synchronized void close(){for(RandomAccessFile f:nodes.values())try{f.close();}catch(Exception ignored){}nodes.clear();dumps.shutdownNow();}
}
