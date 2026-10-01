package com.lightframe.monitor;

import android.content.ComponentName;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Read the focused task through the already-authorized collector; no per-poll shell. */
final class ForegroundTask {
 private Object service;private Method focused;private long checked,retryNs=1_000_000_000L;
 String packageName="",status="尚未确认前台应用",error="";
 String read(long now){
  if(checked>0&&now-checked<retryNs)return packageName;
  checked=now;
  try{
   if(service==null){
    boolean modern=android.os.Build.VERSION.SDK_INT>=29;
    String endpoint=modern?"activity_task":"activity";
    IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,endpoint);
    if(binder==null)throw new IllegalStateException(endpoint+" unavailable");
    Class<?> stub=Class.forName(modern?"android.app.IActivityTaskManager$Stub":"android.app.IActivityManager$Stub");
    service=stub.getMethod("asInterface",IBinder.class).invoke(null,binder);
    Class<?> api=Class.forName(modern?"android.app.IActivityTaskManager":"android.app.IActivityManager");
    try{focused=api.getMethod("getFocusedRootTaskInfo");}catch(NoSuchMethodException oldApi){focused=api.getMethod("getFocusedStackInfo");}
   }
   Object task=focused.invoke(service);
   Object top=task==null?null:task.getClass().getField("topActivity").get(task);
   packageName=top instanceof ComponentName?((ComponentName)top).getPackageName():"";
   status=packageName.isEmpty()?"系统未报告前台应用":"系统前台任务："+packageName;
   retryNs=1_000_000_000L;error="";
  }catch(Exception e){Throwable cause=e.getCause()==null?e:e.getCause();error=cause.getClass().getSimpleName()+": "+cause.getMessage();packageName="";status="无法确认前台应用，请填写目标包名";service=null;focused=null;retryNs=5_000_000_000L;}
  return packageName;
 }
}
