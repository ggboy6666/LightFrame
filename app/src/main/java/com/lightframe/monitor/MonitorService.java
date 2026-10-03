package com.lightframe.monitor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MonitorService extends Service {
 public static volatile boolean running,paused,hidden,analyzing;
 public static volatile String latest="{}",status="尚未开始",sessionPath="";
 public static volatile MonitorService instance;
 private final Handler main=new Handler(Looper.getMainLooper());
 private HandlerThread thread;private Handler work;private volatile Backend backend;
 private SessionRecorder recorder;private FrameStats displayFrames;private Config config;
 private JSONObject cached=new JSONObject(),requestConfig;
 private WindowManager windows;private Overlay view;private WindowManager.LayoutParams layout;
 private volatile boolean stopping,overlayEnabled;private boolean screenPaused;
 private long hwNs,hwPollNs,tempNs,tempPollNs,cpuNs,appCpu,helperCpu,memoryPollNs,uiNs;
 private long previousCycleStartNs,previousCycleCostNs=-1,previousRecordWriteCostNs=-1;
 private int voltage,level;private double batteryC=Double.NaN;private boolean charging;
 private String notice="";private volatile String[] overlayKeys=new String[0];
 private volatile OverlayStyle overlayStyle=new OverlayStyle(OverlayStyle.DEFAULT_BACKGROUND_RGB,90,OverlayStyle.DEFAULT_TEXT_RGB,100);
 private volatile OverlayRows overlayRows=new OverlayRows(new String[0],new String[0]);
 private long lastOverlayDraw;
 private static final class OverlayRows {
  final String[] labels,values;
  OverlayRows(String[] labels,String[] values){this.labels=labels;this.values=values;}
 }
 private final BroadcastReceiver battery=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
  voltage=i.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0);int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
  level=100*i.getIntExtra(BatteryManager.EXTRA_LEVEL,0)/Math.max(1,scale);
  int t=i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);batteryC=t==Integer.MIN_VALUE?Double.NaN:t/10d;
  charging=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
 }};

 private final Runnable tick=new Runnable(){public void run(){
  if(stopping)return;long start=System.nanoTime();
  try{
   PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);boolean suspend=paused||!power.isInteractive();
   if(suspend){
    if(!screenPaused){
     JSONObject stopGpu=new JSONObject();stopGpu.put("op","suspend");backend.request(stopGpu);
     if(!Flavor.LITE&&recorder!=null)recorder.pause(paused?"手动暂停":"息屏暂停");
     if(displayFrames!=null)displayFrames.discontinuity(start);
     resetCycleTiming();
     screenPaused=true;status=paused?"已暂停":"息屏暂停";
     JSONObject p=new JSONObject();p.put("paused",true);p.put("frameStatus",status);latest=p.toString();
     refreshOverlayRows(p);main.post(()->updateUI(true));
    }
    work.postDelayed(this,1000);return;
   }
   if(screenPaused){
    if(!Flavor.LITE&&recorder!=null)recorder.resume();if(displayFrames!=null)displayFrames.discontinuity(start);
    screenPaused=false;hwNs=hwPollNs=tempNs=tempPollNs=cpuNs=memoryPollNs=0;
    resetCycleTiming();
   }
   boolean hw=start-hwPollNs>=config.hardwarePeriod*1_000_000L;
   boolean temp=start-tempPollNs>=config.tempPeriod*1_000_000L;
   refreshDisplayRate();
   boolean enabled=config.prefs.getBoolean("gpuWorkEnabled",false);
   if(enabled!=config.gpuWorkEnabled)hw=true;
   config.gpuWorkEnabled=enabled;
   requestConfig.put("gpuWorkEnabled",config.gpuWorkEnabled);
   requestConfig.put("displayRefreshHz",Double.isFinite(config.displayRefreshHz)&&config.displayRefreshHz>0?config.displayRefreshHz:JSONObject.NULL);
   JSONObject request=new JSONObject();request.put("op","sample");request.put("config",requestConfig);
   request.put("frames",config.frames);request.put("hardware",hw);request.put("temperatures",temp);
   JSONObject response=backend.request(request);JSONArray present=response.optJSONArray("present");response.remove("present");
   Iterator<String> keys=response.keys();while(keys.hasNext()){String key=keys.next();cached.put(key,response.get(key));}
   long now=System.nanoTime();
   if(hw){
    hwPollNs=now;hwNs=response.optLong("hardwareSampleNs",now);
    BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);int ua=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
    double ma=ua==Integer.MIN_VALUE?Double.NaN:ua/1000d;
    CoreSampler.put(cached,"currentMA",ma);CoreSampler.put(cached,"powerW",voltage>0?Math.abs(ma*voltage/1e6):Double.NaN);
    cached.put("batteryPct",level);cached.put("voltageMV",voltage>0?voltage:JSONObject.NULL);cached.put("charging",charging);
    long app=android.os.Process.getElapsedCpuTime(),helper=response.optLong("helperCpuMs");
    boolean separate=backend.uid!=android.os.Process.myUid();double elapsedMs=(now-cpuNs)/1e6;
    CoreSampler.put(cached,"monitorCpuPct",cpuNs>0&&elapsedMs>0?Math.max(0,app-appCpu+(separate?helper-helperCpu:0))*100/elapsedMs:Double.NaN);
    cpuNs=now;appCpu=app;helperCpu=helper;
    if(now-memoryPollNs>=10_000_000_000L){
     double rss=Double.NaN;
     try(BufferedReader rd=new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/status"),StandardCharsets.UTF_8))){
      String line;while((line=rd.readLine())!=null)if(line.startsWith("VmRSS:")){rss=Numbers.first(line.substring(6))/1024;break;}
     }
     CoreSampler.put(cached,"monitorRssMB",rss+(separate?response.optDouble("helperRssMB",0):0));
     cached.put("monitorMemorySampleNs",now);memoryPollNs=now;
    }
    if(Build.VERSION.SDK_INT>=29)cached.put("thermalStatus",power.getCurrentThermalStatus());
   }
   if(temp){tempPollNs=now;tempNs=response.optLong("temperatureSampleNs",now);CoreSampler.put(cached,"batteryC",batteryC);}
   if(cached.optLong("gpuWindowEndNs")>0)CoreSampler.put(cached,"gpuAgeMs",Math.max(0,(now-cached.optLong("gpuWindowEndNs"))/1e6));
   if(cached.optLong("gpuFrequencySampleNs")>0)CoreSampler.put(cached,"gpuFrequencyAgeMs",Math.max(0,(now-cached.optLong("gpuFrequencySampleNs"))/1e6));
   cached.put("hardwareAgeMs",Math.max(0,(now-hwNs)/1e6));cached.put("temperatureAgeMs",Math.max(0,(now-tempNs)/1e6));
   cached.put("cycleCostMs",(now-start)/1e6);
   // These completed measurements belong to the preceding tick. Measuring
   // the current CSV row requires finishing its write first; never backfill it.
   CoreSampler.put(cached,"previousCycleCostMs",previousCycleCostNs>=0?previousCycleCostNs/1e6:Double.NaN);
   CoreSampler.put(cached,"previousRecordWriteCostMs",previousRecordWriteCostNs>=0?previousRecordWriteCostNs/1e6:Double.NaN);
   cached.put("previousCycleSampleNs",previousCycleCostNs>=0?previousCycleStartNs:JSONObject.NULL);
   long recordCostNs=-1;
   if(Flavor.LITE)appendDisplaySample(cached,present,now);
   else{long recordStartNs=System.nanoTime();cached=recorder.append(cached,present,now,config);recordCostNs=Math.max(0,System.nanoTime()-recordStartNs);}
   latest=cached.toString();status=backend.name+(Flavor.LITE?" · 监测中":" · 记录中");
   if(now-uiNs>=1_000_000_000L){uiNs=now;if(overlayEnabled&&!hidden)refreshOverlayRows(cached);main.post(()->updateUI(false));}
   long budgetNs=cached.optLong("frameBudgetNs",0);if(budgetNs<=0)budgetNs=FrameBudget.periodNs(config.displayRefreshHz);
   long costNs=Math.max(0,System.nanoTime()-start),costMs=costNs/1_000_000L;
   previousCycleStartNs=start;previousCycleCostNs=costNs;previousRecordWriteCostNs=recordCostNs;
   work.postDelayed(this,SamplingCadence.delayMs(config.framePeriod,budgetNs,config.frames,costMs));
  }catch(Throwable error){stopRecording(error.toString());}
 }};

 private void resetCycleTiming(){previousCycleStartNs=0;previousCycleCostNs=previousRecordWriteCostNs=-1;}

 /** Lite computes presentation state without creating a recording store. */
 private void appendDisplaySample(JSONObject sample,JSONArray present,long now)throws JSONException{
  String layer=sample.optString("layer","");displayFrames.updateSource(layer,now);boolean gap=false;
  if(present!=null){
   long oldest=Long.MAX_VALUE,newest=0;int valid=0;
   for(int i=0;i<present.length();i++){long p=present.optLong(i);if(p>0&&p<=now+100_000_000L){oldest=Math.min(oldest,p);newest=Math.max(newest,p);valid++;}}
   if(valid>0)gap=displayFrames.beginBatch(oldest,newest,valid);
   double budgetHz=FrameBudget.hz(sample.optLong("frameBudgetNs",0));
   for(int i=0;i<present.length();i++){long p=present.optLong(i);if(p<=now+100_000_000L)displayFrames.add(p,budgetHz);}
  }
  boolean available=config.frames&&!layer.isEmpty()&&present!=null&&present.length()>0&&sample.optBoolean("frameAvailable",true);
  boolean ready=available&&displayFrames.windowReady(now),stale=available&&displayFrames.windowStale(now);
  CoreSampler.put(sample,"fps",available?displayFrames.fps(now):Double.NaN);
  CoreSampler.put(sample,"frameMs",available?displayFrames.frameMs(now):Double.NaN);
  sample.put("frameWindowReady",ready);sample.put("frameWindowStale",stale);
  if(available&&!ready)sample.put("frameStatus",stale?"最新呈现帧已过期，FPS 暂不可测":gap?"帧缓冲未衔接，正在重建窗口":"正在收集完整呈现窗口（约1秒）");
  CoreSampler.put(sample,"frameDataAgeMs",available?displayFrames.frameDataAgeMs(now):Double.NaN);
  CoreSampler.put(sample,"frameWindowSpanMs",available?displayFrames.windowSpanMs():Double.NaN);
  sample.put("captureGapCount",displayFrames.captureGapCount);sample.put("paused",false);
 }

 private void refreshDisplayRate(){
  double hz=Double.NaN;try{Display display=windows.getDefaultDisplay();if(display!=null)hz=display.getRefreshRate();}catch(Exception ignored){}
  config.displayRefreshHz=Double.isFinite(hz)&&hz>0?hz:Double.NaN;
 }

 @Override public void onCreate(){
  super.onCreate();instance=this;thread=new HandlerThread("lightframe-sampling",android.os.Process.THREAD_PRIORITY_BACKGROUND);
  thread.start();work=new Handler(thread.getLooper());windows=(WindowManager)getSystemService(WINDOW_SERVICE);
  registerReceiver(battery,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  nm.createNotificationChannel(new NotificationChannel("monitor",Flavor.LITE?"性能监测":"性能记录",NotificationManager.IMPORTANCE_LOW));
 }
 @Override public int onStartCommand(Intent intent,int flags,int id){
  String action=intent==null?"":intent.getAction();
  if("appearance".equals(action)){applyAppearance();if(!running)stopSelf();return START_NOT_STICKY;}
  if("stop".equals(action)){if(running)work.post(()->stopRecording(""));else stopSelf();return START_NOT_STICKY;}
  if("pause".equals(action)){if(running&&!stopping){paused=!paused;main.post(()->updateUI(true));}return START_NOT_STICKY;}
  if("hide".equals(action)){if(running&&!stopping){hidden=!hidden;main.post(()->updateUI(true));}return START_NOT_STICKY;}
  if(running)return START_NOT_STICKY;
  running=true;paused=hidden=analyzing=false;stopping=false;status="正在连接采集器…";config=new Config(this);
  recorder=null;displayFrames=Flavor.LITE?new FrameStats(false):null;
  if(displayFrames!=null)displayFrames.discontinuity(System.nanoTime());
  latest="{}";applyAppearance();sessionPath="";notice="";lastOverlayDraw=0;cached=new JSONObject();
  hwNs=hwPollNs=tempNs=tempPollNs=cpuNs=memoryPollNs=uiNs=0;
  resetCycleTiming();
  startForeground(1,notification());
  work.post(()->{try{
   refreshDisplayRate();requestConfig=config.json();backend=Backend.connect(this,config.mode);
   if(!Flavor.LITE){recorder=new SessionRecorder(new File(getFilesDir(),"sessions"),config,backend);sessionPath=recorder.dir.getAbsolutePath();}
   work.post(tick);
  }catch(Throwable error){stopRecording(error.toString());}});
  return START_NOT_STICKY;
 }

 /** Re-read appearance preferences and apply them to the existing window. */
 public void applyAppearance(){
  if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->applyAppearance());return;}
  Config appearance=new Config(this);overlayEnabled=appearance.overlay;overlayStyle=appearance.overlayStyle;
  LinkedHashSet<String> accepted=new LinkedHashSet<>();String selected=appearance.prefs.getString("overlayKeys",Config.DEFAULT_OVERLAY_KEYS);
  for(String key:selected.split(","))for(String metric:Config.METRICS)if(metric.equals(key)&&accepted.size()<12)accepted.add(key);
  if(accepted.isEmpty())accepted.addAll(Arrays.asList(Config.DEFAULT_OVERLAY_KEYS.split(",")));
  overlayKeys=accepted.toArray(new String[0]);
  try{refreshOverlayRows(new JSONObject(latest));}catch(JSONException ignored){}
  if(view!=null)view.requestLayout();if(running&&!stopping)updateUI(true);
 }
 private void refreshOverlayRows(JSONObject sample){
  String[] selected=overlayKeys,labels=new String[selected.length],values=new String[selected.length];
  for(int i=0;i<selected.length;i++){
   labels[i]=Config.label(selected[i]).replace("（系统）","").replace("（单核当量）","");values[i]=Config.formatSample(selected[i],sample);
  }
  overlayRows=new OverlayRows(labels,values);
 }
 private PendingIntent action(String name){
  return PendingIntent.getService(this,name.hashCode(),new Intent(this,MonitorService.class).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
 private Notification notification(){
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(this,"monitor").setSmallIcon(R.drawable.ic_stat).setContentTitle("轻帧 · "+status)
   .setContentText(Flavor.LITE?"仅实时显示；停止后不保留记录":"点此查看；停止后自动保存全部数据")
   .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
   .addAction(new Notification.Action.Builder(null,paused?"继续":"暂停",action("pause")).build())
   .addAction(new Notification.Action.Builder(null,hidden?"显示":"隐藏",action("hide")).build())
   .addAction(new Notification.Action.Builder(null,Flavor.LITE?"停止监测":"停止并保存",action("stop")).build()).build();
 }
 private void updateUI(boolean force){
  if(stopping)return;String message=status+paused+hidden;
  if(!message.equals(notice)){notice=message;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());}
  if(overlayEnabled&&!hidden&&Settings.canDrawOverlays(this)){
   if(view==null){
    view=new Overlay();layout=new WindowManager.LayoutParams(dp(205),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    layout.gravity=Gravity.TOP|Gravity.START;layout.x=dp(14);layout.y=dp(90);
    try{windows.addView(view,layout);}catch(Exception error){view=null;}
   }
   if(view!=null&&(force||SystemClock.uptimeMillis()-lastOverlayDraw>=1000)){lastOverlayDraw=SystemClock.uptimeMillis();view.invalidate();}
  }else removeOverlay();
 }
 private void removeOverlay(){if(view!=null){try{windows.removeView(view);}catch(Exception ignored){}view=null;}}

 private void stopRecording(String error){
  if(stopping)return;stopping=true;work.removeCallbacks(tick);paused=false;
  if(!Flavor.LITE){try{if(recorder!=null)recorder.finish(error);}catch(Exception ex){error+=" 保存失败: "+ex;}}
  if(backend!=null){backend.close();backend=null;}
  if(!Flavor.LITE&&recorder!=null){
   try{
    JSONObject last=new JSONObject(cached.toString());last.put("stoppedWhilePaused",new JSONObject(latest).optBoolean("paused",false));
    last.put("sessionPath",sessionPath);last.put("stoppedUnixMs",System.currentTimeMillis());
    java.nio.file.Path temporary=new File(getFilesDir(),"last-recording-sample.tmp").toPath();
    java.nio.file.Files.write(temporary,last.toString().getBytes(StandardCharsets.UTF_8));
    java.nio.file.Files.move(temporary,new File(getFilesDir(),"last-recording-sample.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
   }catch(Exception ignored){}
   analyzing=true;status="原始记录已保存，正在计算统计…";
   main.post(()->{removeOverlay();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());});
   try{
    final long[] reported={0};
    recorder.analyze((rows,frameRows)->{long now=SystemClock.uptimeMillis();if(now-reported[0]>=500){reported[0]=now;status="正在计算记录："+rows+" 次采样 / "+frameRows+" 帧";main.post(()->((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification()));}});
   }catch(Exception ex){error+=" 统计失败（原始记录已保留）: "+ex;}
  }
  running=paused=analyzing=false;
  status=error.isEmpty()?(Flavor.LITE?"监测已停止":"已保存记录并完成统计"):(Flavor.LITE?"监测已停止: ":"记录已保存: ")+error;
  main.post(()->{removeOverlay();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
 }
 public void diagnose(java.util.function.Consumer<String> done){
  if(stopping||backend==null){main.post(()->done.accept("采集已停止，请重新导出诊断"));return;}
  work.post(()->{try{if(stopping||backend==null)throw new IOException("采集已停止，请重新导出诊断");JSONObject request=new JSONObject();request.put("op","diagnose");request.put("frames",false);request.put("config",config.json());String value=backend.request(request).toString(2);main.post(()->done.accept(value));}catch(Exception error){main.post(()->done.accept(error.toString()));}});
 }
 public void layers(java.util.function.Consumer<String> done){
  if(stopping||backend==null){main.post(()->done.accept("采集已停止，请重新读取图层"));return;}
  work.post(()->{try{if(stopping||backend==null)throw new IOException("采集已停止，请重新读取图层");JSONObject request=new JSONObject();request.put("op","layers");String value=backend.request(request).toString();main.post(()->done.accept(value));}catch(Exception error){main.post(()->done.accept(error.toString()));}});
 }
 @Override public void onDestroy(){
  stopping=true;running=analyzing=false;instance=null;work.removeCallbacksAndMessages(null);
  work.post(()->{if(!Flavor.LITE){try{if(recorder!=null)recorder.finish("服务退出");}catch(Exception ignored){}}if(backend!=null){backend.close();backend=null;}thread.quitSafely();});
  removeOverlay();try{unregisterReceiver(battery);}catch(Exception ignored){}super.onDestroy();
 }
 @Override public IBinder onBind(Intent intent){return null;}
 private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private final class Overlay extends View {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float x,y;int ox,oy;long down,lastTap;boolean moved;
  Overlay(){super(MonitorService.this);setContentDescription("轻帧悬浮窗，可拖动，双击打开，长按隐藏");}
  @Override protected void onMeasure(int w,int h){setMeasuredDimension(dp(205),dp(34+overlayKeys.length*20));}
  @Override protected void onDraw(Canvas canvas){
   OverlayStyle style=overlayStyle;OverlayRows rows=overlayRows;
   paint.setColor(style.backgroundArgb);canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(10),dp(10),paint);
   paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(dp(12));paint.setColor(style.textArgb);
   canvas.drawText("轻帧  "+(paused?"已暂停":Flavor.LITE?"监测中":"记录中"),dp(12),dp(22),paint);
   for(int i=0;i<rows.labels.length;i++){
    paint.setTextSize(dp(10));canvas.drawText(rows.labels[i],dp(12),dp(43+i*20),paint);
    paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText(rows.values[i],getWidth()-dp(12),dp(43+i*20),paint);paint.setTextAlign(Paint.Align.LEFT);
   }
  }
  @Override public boolean onTouchEvent(MotionEvent event){
   if(event.getAction()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();ox=layout.x;oy=layout.y;down=SystemClock.uptimeMillis();moved=false;return true;}
   if(event.getAction()==MotionEvent.ACTION_MOVE){
    float dx=event.getRawX()-x,dy=event.getRawY()-y;if(Math.abs(dx)+Math.abs(dy)>dp(8))moved=true;
    android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
    layout.x=Math.max(0,Math.min(metrics.widthPixels-getWidth(),ox+(int)dx));layout.y=Math.max(0,Math.min(metrics.heightPixels-getHeight(),oy+(int)dy));
    try{windows.updateViewLayout(this,layout);}catch(Exception ignored){}return true;
   }
   if(event.getAction()==MotionEvent.ACTION_UP&&!moved){
    long now=SystemClock.uptimeMillis();if(now-down>650){hidden=true;updateUI(true);}
    else if(now-lastTap<350){startActivity(new Intent(MonitorService.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));lastTap=0;}else lastTap=now;
    performClick();return true;
   }
   return true;
  }
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
