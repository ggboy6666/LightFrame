package com.lightframe.monitor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Sparse on-disk index; every original row remains addressable without loading it all into RAM. */
public final class CsvIndex implements Closeable {
 public final File file; public final String[] header;public int count;private final RandomAccessFile raf;
 private final ArrayList<Long> offsets=new ArrayList<>();private final ArrayList<Double> times=new ArrayList<>();private static final int BLOCK=128;
 public CsvIndex(File f)throws IOException{file=f;raf=new RandomAccessFile(f,"r");String h=readLine();if(h==null)throw new IOException("空数据文件");header=parse(h.replace("\uFEFF",""));while(true){long p=raf.getFilePointer();String l=readLine();if(l==null)break;if(l.isEmpty())continue;if(count%BLOCK==0){offsets.add(p);times.add(time(parse(l)));}count++;}}
 private String readLine()throws IOException{String s=raf.readLine();return s==null?null:new String(s.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8);}
 public synchronized String[] row(int index)throws IOException{if(index<0||index>=count)throw new IndexOutOfBoundsException();int b=index/BLOCK;raf.seek(offsets.get(b));String s=null;for(int i=0;i<=index%BLOCK;i++){s=readLine();while(s!=null&&s.isEmpty())s=readLine();}if(s==null)throw new EOFException();return parse(s);}
 public synchronized int nearest(double t)throws IOException{if(count==0)return -1;int lo=0,hi=times.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(times.get(m)<=t)lo=m;else hi=m-1;}int start=Math.max(0,lo*BLOCK-1),end=Math.min(count,(lo+1)*BLOCK+1),best=start;double distance=Double.POSITIVE_INFINITY;for(int i=start;i<end;i++){double d=Math.abs(time(row(i))-t);if(d<distance){distance=d;best=i;}}return best;}
 public int column(String k){for(int i=0;i<header.length;i++)if(header[i].equals(k))return i;return -1;}
 public double value(String[] r,String k){int i=column(k);if(i<0||i>=r.length||r[i].isEmpty())return Double.NaN;try{return Double.parseDouble(r[i]);}catch(Exception e){return Double.NaN;}}
 public static double time(String[] r){try{return Double.parseDouble(r[0]);}catch(Exception e){return Double.NaN;}}
 public void scan(RowConsumer c)throws IOException{try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),32768)){r.readLine();int i=0;for(String l;(l=r.readLine())!=null;)if(!l.isEmpty())c.accept(i++,parse(l));}}
 public interface RowConsumer{void accept(int i,String[] row);}
 public synchronized void close()throws IOException{raf.close();}
 public static String encode(Object[] a){StringBuilder b=new StringBuilder();for(int i=0;i<a.length;i++){if(i>0)b.append(',');Object o=a[i];String s=o==null?"":o.toString().replace('\n',' ').replace('\r',' ');if(s.indexOf(',')>=0||s.indexOf('"')>=0)b.append('"').append(s.replace("\"","\"\"")).append('"');else b.append(s);}return b.toString();}
 public static String[] parse(String s){ArrayList<String> out=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(quoted&&i+1<s.length()&&s.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){out.add(cell.toString());cell.setLength(0);}else cell.append(c);}out.add(cell.toString());return out.toArray(new String[0]);}
}
