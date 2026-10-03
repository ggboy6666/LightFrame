package com.lightframe.monitor;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import java.io.*;
/** Grants the system installer read-only access to the single verified package in private cache. */
public final class UpdateProvider extends ContentProvider{
 public boolean onCreate(){return true;}
 static File updateFile(Context c){return new File(new File(c.getCacheDir(),"updates"),"verified-update.apk");}
 public static Uri uri(Context c,File f)throws IOException{if(!f.getCanonicalFile().equals(updateFile(c).getCanonicalFile()))throw new SecurityException("非法更新路径");return new Uri.Builder().scheme("content").authority(c.getPackageName()+".updates").appendPath("verified-update.apk").build();}
 private File file(Uri u)throws FileNotFoundException{if(!u.getAuthority().equals(getContext().getPackageName()+".updates")||!u.getPath().equals("/verified-update.apk")||u.getQuery()!=null)throw new FileNotFoundException("非法更新文件");File f=updateFile(getContext());if(!f.isFile())throw new FileNotFoundException("更新文件不存在");return f;}
 public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException("仅允许读取更新");return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}
 public String getType(Uri u){return "application/vnd.android.package-archive";}
 public Cursor query(Uri u,String[] projection,String selection,String[] args,String order){try{File f=file(u);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols);Object[] v=new Object[cols.length];for(int i=0;i<cols.length;i++)v[i]=cols[i].equals(OpenableColumns.DISPLAY_NAME)?"LightFrame-update.apk":cols[i].equals(OpenableColumns.SIZE)?f.length():null;c.addRow(v);return c;}catch(Exception e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
