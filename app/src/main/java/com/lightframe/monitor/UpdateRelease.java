package com.lightframe.monitor;

import java.io.IOException;
import java.util.regex.*;
import org.json.*;

/** Strict stable-release metadata and checksum selection; no Android or network activity. */
public final class UpdateRelease {
 public static final String REPOSITORY="ggboy6666/LightFrame";
 public static final String API_URL="https://api.github.com/repos/"+REPOSITORY+"/releases/latest";
 public static final int MAX_APK_BYTES=32*1024*1024;
 public final String version,tag,apkName,apkUrl,sumsUrl,releaseUrl,apiDigest;
 public final long apkBytes;
 private static final Pattern VERSION=Pattern.compile("(?:0|[1-9][0-9]{0,6})\\.(?:0|[1-9][0-9]{0,6})\\.(?:0|[1-9][0-9]{0,6})");
 private UpdateRelease(String version,String variant,long size,String digest){this.version=version;tag="v"+version;apkName="LightFrame-"+version+"-"+variant+".apk";String base="https://github.com/"+REPOSITORY+"/releases/download/"+tag+"/";apkUrl=base+apkName;sumsUrl=base+"SHA256SUMS.txt";releaseUrl="https://github.com/"+REPOSITORY+"/releases/tag/"+tag;apkBytes=size;apiDigest=digest;}
 public static UpdateRelease parse(String json,String variant)throws IOException{
  if(!variant.equals("full")&&!variant.equals("lite"))throw new IOException("未知安装包类型");
  try{
   JSONObject release=new JSONObject(json);String tag=release.getString("tag_name");if(!tag.startsWith("v")||!VERSION.matcher(tag.substring(1)).matches()||release.optBoolean("draft",true)||release.optBoolean("prerelease",true))throw new IOException("更新源没有有效正式版本");
   String version=tag.substring(1),name="LightFrame-"+version+"-"+variant+".apk",base="https://github.com/"+REPOSITORY+"/releases/download/"+tag+"/";JSONArray assets=release.getJSONArray("assets");if(assets.length()>128)throw new IOException("更新资产列表过长");long size=0;String digest="";boolean found=false,sums=false;
   for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);String assetName=asset.optString("name");if(!assetName.equals(name)&&!assetName.equals("SHA256SUMS.txt"))continue;if(!asset.optString("browser_download_url").equals(base+assetName)||!asset.optString("state").equals("uploaded"))throw new IOException("更新下载地址或状态不匹配");
    if(assetName.equals(name)){if(found)throw new IOException("重复安装包资产");found=true;size=asset.optLong("size",0);if(size<=0||size>MAX_APK_BYTES)throw new IOException("更新安装包大小超出限制");digest=asset.isNull("digest")?"":asset.optString("digest","");if(!digest.isEmpty()&&!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("更新摘要格式不受支持");}
    else{if(sums)throw new IOException("重复摘要文件");sums=true;long bytes=asset.optLong("size",0);if(bytes<=0||bytes>65536)throw new IOException("更新摘要文件大小超出限制");}
   }
   if(!found||!sums)throw new IOException("正式版本缺少当前类型安装包或校验摘要");return new UpdateRelease(version,variant,size,digest.toLowerCase(java.util.Locale.US));
  }catch(JSONException e){throw new IOException("更新版本信息无法解析",e);}
 }
 public boolean newerThan(String installedVersion)throws IOException{return compare(version,installedVersion)>0;}
 public static int compare(String a,String b)throws IOException{if(!VERSION.matcher(a).matches()||!VERSION.matcher(b).matches())throw new IOException("版本号格式无效");String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<3;i++){int n=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(n!=0)return n;}return 0;}
 public String checksum(String manifest)throws IOException{
  String result=null;Pattern row=Pattern.compile("([a-fA-F0-9]{64})[ \\t]+\\*?([^\\r\\n]+)");
  for(String line:manifest.split("\\r?\\n")){if(line.trim().isEmpty())continue;Matcher match=row.matcher(line);if(!match.matches())throw new IOException("校验摘要文件格式无效");if(match.group(2).equals(apkName)){if(result!=null)throw new IOException("安装包摘要重复");result=match.group(1).toLowerCase(java.util.Locale.US);}}
  if(result==null)throw new IOException("校验文件缺少当前安装包摘要");if(!apiDigest.isEmpty()&&!apiDigest.equals("sha256:"+result))throw new IOException("发布资产摘要与校验文件不一致");return result;
 }
}
