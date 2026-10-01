package com.lightframe.monitor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Immutable file snapshot with sparse offsets; details always use original rows. */
public final class CsvIndex implements Closeable {
 public final File file;public final String[] header;public final int count;public final double firstTime,lastTime;
 private final RandomAccessFile raf;private final ByteLines reader;private final long snapshotSize;private final HashMap<String,Integer> columns=new HashMap<>();private final ArrayList<Long> offsets=new ArrayList<>();private static final int BLOCK=128;
 public CsvIndex(File f)throws IOException{
  file=f;raf=new RandomAccessFile(f,"r");int rows=0;double first=Double.NaN,last=Double.NaN;
  try{snapshotSize=raf.length();boolean complete=true;if(snapshotSize>0){raf.seek(snapshotSize-1);int b=raf.read();complete=b=='\n'||b=='\r';}raf.seek(0);reader=new ByteLines(raf,snapshotSize);String h=reader.line();if(h==null)throw new IOException("空数据文件");header=parse(h.startsWith("\uFEFF")?h.substring(1):h);for(int i=0;i<header.length;i++)columns.put(header[i],i);
   while(reader.position()<snapshotSize){if((rows&255)==0&&Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消旧会话索引");long pos=reader.position();String line=reader.line();if(line==null)break;if(reader.position()>=snapshotSize&&!complete)break;
    if(line.isEmpty())continue;if(rows%BLOCK==0)offsets.add(pos);double t=time(parse(line));if(Double.isFinite(t)){if(!Double.isFinite(first))first=t;last=t;}rows++;
   }
  }catch(IOException|RuntimeException e){raf.close();throw e;}count=rows;firstTime=first;lastTime=last;
 }
 /** Bulk byte reads, preserving real file offsets; no native read call per byte. */
 private static final class ByteLines{
  private final RandomAccessFile file;private final long limit;private final byte[] buffer=new byte[65536];private int at,length;private long base;
  ByteLines(RandomAccessFile f,long limit)throws IOException{file=f;this.limit=limit;base=f.getFilePointer();}
  long position(){return base+at;}
  void seek(long pos)throws IOException{if(pos>=base&&pos<base+length){at=(int)(pos-base);return;}file.seek(pos);base=pos;at=length=0;}
  private boolean fill()throws IOException{if(at<length)return true;long pos=position();if(pos>=limit)return false;file.seek(pos);base=pos;at=0;length=file.read(buffer,0,(int)Math.min(buffer.length,limit-pos));if(length<0)length=0;return length>0;}
  String line()throws IOException{if(!fill())return null;ByteArrayOutputStream joined=null;
   for(;;){int start=at;while(at<length&&buffer[at]!='\n')at++;int size=at-start;
    if(at<length){at++;if(joined==null){if(size>0&&buffer[start+size-1]=='\r')size--;return new String(buffer,start,size,StandardCharsets.UTF_8);}joined.write(buffer,start,size);byte[] bytes=joined.toByteArray();int n=bytes.length;if(n>0&&bytes[n-1]=='\r')n--;return new String(bytes,0,n,StandardCharsets.UTF_8);}
    if(joined==null)joined=new ByteArrayOutputStream(size+256);joined.write(buffer,start,size);if(!fill()){byte[] bytes=joined.toByteArray();int n=bytes.length;if(n>0&&bytes[n-1]=='\r')n--;return new String(bytes,0,n,StandardCharsets.UTF_8);}
   }
  }
  String next()throws IOException{String s;do{s=line();}while(s!=null&&s.isEmpty());return s;}
 }
 public synchronized String[] row(int index)throws IOException{if(index<0||index>=count)throw new IndexOutOfBoundsException("row "+index+" / "+count);reader.seek(offsets.get(index/BLOCK));String s=null;for(int i=0;i<=index%BLOCK;i++)s=reader.next();if(s==null)throw new EOFException("原始数据已变化，请刷新快照");return parse(s);}
 public int lowerBound(double t)throws IOException{return bound(t,false);}public int upperBound(double t)throws IOException{return bound(t,true);}
 private int bound(double t,boolean upper)throws IOException{if(!Double.isFinite(t))throw new IllegalArgumentException("时间必须为有限数值");int lo=0,hi=count;while(lo<hi){int mid=(lo+hi)>>>1;double v=time(row(mid));if(!Double.isFinite(v))throw new IOException("第 "+(mid+1)+" 行的时间无效");if(v<t||(upper&&v==t))lo=mid+1;else hi=mid;}return lo;}
 public int nearest(double t)throws IOException{if(count==0)return -1;int r=lowerBound(t);if(r==0)return 0;if(r==count)return count-1;return t-time(row(r-1))<=time(row(r))-t?r-1:r;}
 public int column(String k){Integer i=columns.get(k);return i==null?-1:i;}
 public double value(String[] r,String k){int i=column(k);return i<0||i>=r.length?Double.NaN:number(r[i]);}
 private static double number(String s){try{double v=Double.parseDouble(s);return Double.isFinite(v)?v:Double.NaN;}catch(Exception e){return Double.NaN;}}
 public static double time(String[] r){return r.length==0?Double.NaN:number(r[0]);}
 public void scan(RowConsumer c)throws IOException{scan(0,count,c);}
 /** Appends after indexing cannot change chart/detail counts mid-view. */
 public void scan(int from,int to,RowConsumer c)throws IOException{from=Math.max(0,from);to=Math.min(count,to);if(from>=to)return;try(RandomAccessFile r=new RandomAccessFile(file,"r")){int first=from/BLOCK*BLOCK;ByteLines lines=new ByteLines(r,snapshotSize);lines.seek(offsets.get(from/BLOCK));for(int n=first;n<to;n++){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消旧页面读取");String s=lines.next();if(s==null)throw new EOFException("原始数据已变化，请刷新快照");if(n>=from)c.accept(n,parse(s));}}}
 public interface RowConsumer{void accept(int i,String[] row);}public synchronized void close()throws IOException{raf.close();}
 public static String encode(Object[] a){StringBuilder b=new StringBuilder();for(int i=0;i<a.length;i++){if(i>0)b.append(',');Object o=a[i];String s=o==null?"":o.toString().replace('\n',' ').replace('\r',' ');if(s.indexOf(',')>=0||s.indexOf('"')>=0)b.append('"').append(s.replace("\"","\"\"")).append('"');else b.append(s);}return b.toString();}
 public static String[] parse(String s){ArrayList<String> out=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(quoted&&i+1<s.length()&&s.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){out.add(cell.toString());cell.setLength(0);}else cell.append(c);}out.add(cell.toString());return out.toArray(new String[0]);}
}
