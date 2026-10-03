package com.lightframe.monitor;
import java.io.*;import java.nio.file.*;
/** Deletes only a selected direct child of the private sessions folder. */
public final class RecordDeletion {
 private RecordDeletion(){}
 public static boolean delete(File root,File selected,File active)throws IOException{
  File base=root.toPath().toRealPath().toFile(),target=selected.toPath().toRealPath().toFile();
  if(!target.getParentFile().equals(base)||!new File(base,selected.getName()).equals(target)||!target.isDirectory())throw new IOException("记录路径无效");
  if(active!=null&&target.equals(active.toPath().toRealPath().toFile()))throw new IOException("正在使用的记录不能删除");
  validate(base,target);return tree(base,target);
 }
 private static void verifyPath(File base,File file)throws IOException{
  Path declared=file.toPath().toAbsolutePath().normalize();if(Files.isSymbolicLink(declared))throw new IOException("记录包含链接路径，已停止删除");Path resolved=declared.toRealPath();
  if(!resolved.startsWith(base.toPath())||resolved.equals(base.toPath())||!resolved.equals(declared))throw new IOException("记录包含外部路径，已停止删除");
 }
 private static void validate(File base,File file)throws IOException{
  verifyPath(base,file);
  if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法读取记录目录");for(File child:children)validate(base,child);}
 }
 private static boolean tree(File base,File file)throws IOException{
  verifyPath(base,file);
  if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("无法读取记录目录");for(File child:children)if(!tree(base,child))return false;}
  return file.delete();
 }
}
