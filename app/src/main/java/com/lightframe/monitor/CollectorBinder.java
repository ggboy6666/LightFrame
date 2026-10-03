package com.lightframe.monitor;
import android.content.Context;
import android.os.*;
import org.json.*;
public final class CollectorBinder extends Binder {
 public static final String TOKEN="com.lightframe.monitor.Collector";
 private final CoreSampler sampler=new CoreSampler();
 public CollectorBinder(){attachInterface(null,TOKEN);}public CollectorBinder(Context c){this();}
 @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
  if(code==INTERFACE_TRANSACTION){reply.writeString(TOKEN);return true;}
  if(code==16777115){sampler.close();System.exit(0);return true;}
  if(code==FIRST_CALL_TRANSACTION){
   data.enforceInterface(TOKEN);int tid=android.os.Process.myTid(),previous=android.os.Process.getThreadPriority(tid);
   try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);String json=sampler.handle(new JSONObject(data.readString())).toString();reply.writeNoException();reply.writeString(json);}
   catch(Exception e){reply.writeException(new IllegalStateException(e.toString()));}
   finally{android.os.Process.setThreadPriority(tid,previous);}return true;
  }return super.onTransact(code,data,reply,flags);
 }
}
