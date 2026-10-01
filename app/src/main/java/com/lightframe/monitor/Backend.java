package com.lightframe.monitor;
import android.content.*;import android.content.pm.PackageManager;import android.os.*;import org.json.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;import rikka.shizuku.Shizuku;
public abstract class Backend implements Closeable {
 public String name; public int uid;
 public abstract JSONObject request(JSONObject r)throws Exception;
 public abstract void close();
 public static Backend connect(Context c,String mode)throws Exception{
  if(mode.equals("basic"))return new Local();
  if(mode.equals("shizuku")||mode.equals("auto")){try{if(Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)return new Remote(c);if(mode.equals("shizuku"))throw new IOException("请启动 Shizuku 并在主页授权");}catch(Exception e){if(mode.equals("shizuku"))throw e;}}
  if(mode.equals("root")||mode.equals("auto")&&hasSu()){try{return new Root(c);}catch(Exception e){if(mode.equals("root"))throw e;}}
  return new Local();
 }
 private static boolean hasSu(){String path=System.getenv("PATH");if(path!=null)for(String p:path.split(":"))if(new File(p,"su").canExecute())return true;for(String p:new String[]{"/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su","/debug_ramdisk/su"})if(new File(p).canExecute())return true;return false;}
 private static class Local extends Backend{final CoreSampler core=new CoreSampler();Local(){name="基础模式";uid=android.os.Process.myUid();}public JSONObject request(JSONObject r)throws Exception{return core.handle(r);}public void close(){core.close();}}
 private static class Remote extends Backend{
  volatile IBinder binder;final Shizuku.UserServiceArgs args;final ServiceConnection conn;
  Remote(Context c)throws Exception{
   args=new Shizuku.UserServiceArgs(new ComponentName(c,CollectorBinder.class)).daemon(false).processNameSuffix("collector").debuggable(false).version(Config.VERSION_CODE).tag("lightframe-collector");final CountDownLatch ready=new CountDownLatch(1);
   conn=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){binder=b;ready.countDown();}public void onServiceDisconnected(ComponentName n){binder=null;}};
   try{Shizuku.bindUserService(args,conn);if(!ready.await(12,TimeUnit.SECONDS)||binder==null)throw new IOException("Shizuku 采集进程连接超时");JSONObject hello=new JSONObject();hello.put("frames",false);hello.put("hardware",false);hello.put("temperatures",false);uid=request(hello).getInt("uid");name=uid==0?"Shizuku / Sui (Root)":"Shizuku (Shell)";}catch(Exception e){close();throw e;}
  }
  public JSONObject request(JSONObject r)throws Exception{IBinder b=binder;if(b==null||!b.isBinderAlive())throw new IOException("Shizuku 已断开");Parcel in=Parcel.obtain(),out=Parcel.obtain();try{in.writeInterfaceToken(CollectorBinder.TOKEN);in.writeString(r.toString());if(!b.transact(IBinder.FIRST_CALL_TRANSACTION,in,out,0))throw new IOException("采集器不支持事务");out.readException();return new JSONObject(out.readString());}finally{in.recycle();out.recycle();}}
  public void close(){try{Shizuku.unbindUserService(args,conn,true);}catch(Exception ignored){}binder=null;}
 }
 private static class Root extends Backend{
  final java.lang.Process process;final BufferedReader reader;final PrintWriter writer;final ExecutorService reads=Executors.newSingleThreadExecutor();
  Root(Context c)throws Exception{
   String apk=c.getApplicationInfo().sourceDir;String quoted="'"+apk.replace("'","'\\''")+"'";
   process=new ProcessBuilder("su","-c","CLASSPATH="+quoted+" /system/bin/app_process /system/bin com.lightframe.monitor.RootMain").start();reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));writer=new PrintWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8),true);
   Thread drain=new Thread(()->{try(InputStream in=process.getErrorStream()){byte[] b=new byte[1024];while(in.read(b)>=0){}}catch(Exception ignored){}},"root-stderr");drain.setDaemon(true);drain.start();
   try{JSONObject r=new JSONObject();r.put("frames",false);r.put("hardware",false);r.put("temperatures",false);uid=request(r).getInt("uid");if(uid!=0)throw new IOException("su 未取得 UID 0");name="Root";}catch(Exception e){close();throw e;}
  }
  public synchronized JSONObject request(JSONObject r)throws Exception{writer.println(r.toString());Future<String> f=reads.submit(()->reader.readLine());String s;try{s=f.get(15,TimeUnit.SECONDS);}catch(Exception e){f.cancel(true);process.destroy();throw new IOException("Root 采集超时",e);}if(s==null)throw new IOException("Root 进程退出");JSONObject o=new JSONObject(s);if(o.has("error"))throw new IOException(o.optString("error"));return o;}
  public void close(){writer.println("QUIT");process.destroy();writer.close();reads.shutdownNow();try{reader.close();}catch(Exception ignored){}}
 }
}
