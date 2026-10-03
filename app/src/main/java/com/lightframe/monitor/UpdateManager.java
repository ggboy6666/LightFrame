package com.lightframe.monitor;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Explicit manual updates only. No polling, analytics, default request or silent installation. */
public final class UpdateManager implements Closeable {
 public interface Callback {void onProgress(String message);void onLatest(UpdateRelease release,boolean newer);void onReady(File apk);void onError(String message);}
 private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Object lock=new Object();
 private volatile boolean busy,closed;private int generation;private Future<?> task;private HttpsURLConnection connection;
 public UpdateManager(Context context){this.context=context.getApplicationContext();}
 public boolean busy(){return busy;}
 public void check(Callback callback){start(callback,null,false);}
 public void checkAndDownload(Callback callback){start(callback,null,true);}
 public void download(UpdateRelease release,Callback callback){start(callback,release,true);}
 private void start(Callback callback,UpdateRelease provided,boolean download){
  synchronized(lock){if(closed||busy){main.post(()->callback.onError(closed?"更新检查已关闭":"更新任务正在进行"));return;}if(MonitorService.running){main.post(()->callback.onError("请先停止性能采集，再检查或下载更新"));return;}busy=true;final int id=++generation;
   task=worker.submit(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);File partial=new File(new File(context.getCacheDir(),"updates"),"download.part");try{
    guard(id);UpdateRelease release=provided;if(release==null){post(id,()->callback.onProgress("正在检查正式版本…"));release=UpdateRelease.parse(new String(read(UpdateRelease.API_URL,1024*1024,id),StandardCharsets.UTF_8),Flavor.VARIANT);}
    if(!release.apkName.endsWith("-"+Flavor.VARIANT+".apk"))throw new IOException("更新安装包类型不匹配");boolean newer=release.newerThan(Config.VERSION);final UpdateRelease latest=release;post(id,()->callback.onLatest(latest,newer));if(!download||!newer)return;
    post(id,()->callback.onProgress("正在核对下载摘要…"));String expected=release.checksum(new String(read(release.sumsUrl,65536,id),StandardCharsets.UTF_8));guard(id);
    File directory=partial.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建更新缓存");if(!directory.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator))throw new IOException("更新缓存路径无效");
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0,start=System.nanoTime();int lastPercent=-1;HttpsURLConnection request=open(release.apkUrl,id);
    try{long length=request.getContentLengthLong();if(length>UpdateRelease.MAX_APK_BYTES||length>0&&length!=release.apkBytes)throw new IOException("安装包长度与发布信息不一致");
     try(InputStream in=request.getInputStream();OutputStream out=new FileOutputStream(partial)){byte[] buffer=new byte[32768];for(;;){guard(id);if(System.nanoTime()-start>180_000_000_000L)throw new IOException("更新下载超时");int n=in.read(buffer);if(n<0)break;if(n==0)continue;bytes+=n;if(bytes>release.apkBytes||bytes>UpdateRelease.MAX_APK_BYTES)throw new IOException("安装包超过大小限制");out.write(buffer,0,n);digest.update(buffer,0,n);int percent=(int)(bytes*100/release.apkBytes);if(percent!=lastPercent){lastPercent=percent;final int progress=percent;post(id,()->callback.onProgress("正在下载更新 "+progress+"%"));}}}}finally{disconnect(request);}
    if(bytes!=release.apkBytes||!hex(digest.digest()).equals(expected))throw new IOException("安装包 SHA-256 校验失败，请重新下载");guard(id);post(id,()->callback.onProgress("正在验证安装包与签名…"));verifyArchive(partial,release);guard(id);File verified=UpdateProvider.updateFile(context);if(verified.exists()&&!verified.delete())throw new IOException("无法替换更新缓存");if(!partial.renameTo(verified))throw new IOException("无法保存已验证更新");post(id,()->callback.onReady(verified));
   }catch(Exception e){if(current(id)){String message=e.getMessage();post(id,()->callback.onError(message==null?"更新失败，请稍后重试":message));}}
   finally{if(partial.exists())partial.delete();synchronized(lock){if(generation==id)busy=false;}}
   });
  }
 }
 private boolean current(int id){synchronized(lock){return !closed&&generation==id;}}
 private void post(int id,Runnable callback){main.post(()->{if(current(id))callback.run();});}
 private void guard(int id)throws IOException{if(!current(id)||Thread.currentThread().isInterrupted())throw new IOException("更新已取消");if(MonitorService.running)throw new IOException("性能采集已开始，更新任务已停止");}
 public void cancel(){Future<?> future;HttpsURLConnection request;synchronized(lock){generation++;busy=false;future=task;task=null;request=connection;connection=null;}if(future!=null)future.cancel(true);if(request!=null)request.disconnect();}
 public void close(){closed=true;cancel();worker.shutdownNow();}
 private void disconnect(HttpsURLConnection request){synchronized(lock){if(connection==request)connection=null;}request.disconnect();}
 private HttpsURLConnection open(String value,int id)throws IOException{
  URL url=new URL(value);for(int redirects=0;redirects<=5;redirects++){guard(id);String host=url.getHost();if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443||!Arrays.asList("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(host))throw new IOException("更新下载地址不受信任");HttpsURLConnection request=(HttpsURLConnection)url.openConnection();request.setConnectTimeout(15000);request.setReadTimeout(15000);request.setInstanceFollowRedirects(false);request.setRequestProperty("User-Agent","LightFrame/"+Config.VERSION);request.setRequestProperty("Accept",host.equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");request.setRequestProperty("Accept-Encoding","identity");synchronized(lock){if(!current(id)){request.disconnect();throw new IOException("更新已取消");}connection=request;}
   int code;try{code=request.getResponseCode();}catch(IOException e){disconnect(request);throw new IOException("无法连接 GitHub，请检查网络后重试",e);}if(code==200)return request;if(code==301||code==302||code==303||code==307||code==308){String location=request.getHeaderField("Location");disconnect(request);if(location==null)throw new IOException("更新重定向缺少地址");url=new URL(url,location);continue;}disconnect(request);throw new IOException(code==404?"还没有可下载的正式版本":code==403||code==429?"GitHub 暂时限制了更新请求，请稍后重试":"更新服务器返回 "+code);
  }throw new IOException("更新下载重定向过多");
 }
 private byte[] read(String url,int maximum,int id)throws IOException{HttpsURLConnection request=open(url,id);try{if(request.getContentLengthLong()>maximum)throw new IOException("更新信息超过大小限制");try(InputStream in=request.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[16384];long start=System.nanoTime();for(;;){guard(id);if(System.nanoTime()-start>60_000_000_000L)throw new IOException("更新检查超时");int n=in.read(bytes);if(n<0)break;if(n==0)continue;if(out.size()>maximum-n)throw new IOException("更新信息超过大小限制");out.write(bytes,0,n);}return out.toByteArray();}}finally{disconnect(request);}}
 private void verifyArchive(File apk,UpdateRelease release)throws Exception{
  PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageInfo downloaded=pm.getPackageArchiveInfo(apk.getPath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);if(downloaded==null||!context.getPackageName().equals(downloaded.packageName)||!release.version.equals(downloaded.versionName))throw new IOException("更新安装包类型或版本不匹配");long next=Build.VERSION.SDK_INT>=28?downloaded.getLongVersionCode():downloaded.versionCode,current=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;if(next<=current)throw new IOException("更新安装包版本编号没有增加");if(!fingerprints(downloaded).equals(fingerprints(installed)))throw new IOException("更新安装包签名与当前应用不匹配");
 }
 private static Set<String> fingerprints(PackageInfo info)throws Exception{Signature[] signatures=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;if(signatures==null||signatures.length==0)throw new IOException("无法验证安装包签名");Set<String> hashes=new TreeSet<>();for(Signature signature:signatures)hashes.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return hashes;}
 private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.US,"%02x",b&255));return text.toString();}
 /** Opens Android permission/installation confirmation; never installs silently. */
 public static boolean install(Activity activity,File apk)throws IOException{
  if(MonitorService.running)throw new IOException("请先停止性能采集再安装更新");if(!apk.isFile()||apk.length()==0)throw new IOException("已下载的更新不存在，请重新下载");Uri uri=UpdateProvider.uri(activity,apk);try{if(!activity.getPackageManager().canRequestPackageInstalls()){activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return false;}Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);intent.setDataAndType(uri,"application/vnd.android.package-archive");intent.setClipData(ClipData.newRawUri("LightFrame update",uri));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);return true;}catch(ActivityNotFoundException e){throw new IOException("系统没有可用安装程序",e);}
 }
}
