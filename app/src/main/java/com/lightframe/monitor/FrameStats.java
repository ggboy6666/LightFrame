package com.lightframe.monitor;

/** Actual presentation timestamps; sampling delay is not part of a frame interval. */
public final class FrameStats {
 private static final long WINDOW_NS=1_000_000_000L;
 // Integer nanosecond periods can fall a few ns short of exactly one second.
 private static final long WINDOW_TOLERANCE_NS=1_000L;
 private static final int RECENT_CAPACITY=4096,SURFACE_BUFFER_CAPACITY=127;
 private final long[] recent=new long[RECENT_CAPACITY];
 private int recentStart,recentSize;
 private final boolean aggregate;
 private final long[] counts;
 private final double[] sums;
 private long last,floor;
 public long count,longFrames,bigLongFrames,captureGapCount;
 private double totalMs,lastMs=Double.NaN;
 public final double[] tail=new double[60];
 public int tailCount,tailPos;
 private String source="";

 public FrameStats(){this(true);}
 /** Recording only needs the bounded live window; full statistics can be computed offline. */
 public FrameStats(boolean aggregate){this.aggregate=aggregate;counts=aggregate?new long[8192]:null;sums=aggregate?new double[8192]:null;}

 /** A first binding also excludes frames presented before that source was observed. */
 public boolean updateSource(String next,long now){
  if(next==null)next="";
  if(next.equals(source))return false;
  discontinuity(now);source=next;return true;
 }

 public void discontinuity(long ns){last=0;recentStart=recentSize=0;floor=Math.max(floor,ns);lastMs=Double.NaN;tailCount=tailPos=0;}

 /**
  * Call before adding a complete sorted SurfaceFlinger response. A full ring with
  * no overlap cannot establish the interval connecting it to the previous poll.
  * Keep the actual new batch, but do not turn missing coverage into a long frame.
  * A true return also tells a raw-frame writer to clear its interval baseline.
  */
 public boolean beginBatch(long oldest,long newest,int bufferedFrameCount){
  if(last==0||bufferedFrameCount<SURFACE_BUFFER_CAPACITY||oldest<=last||newest<oldest)return false;
  discontinuity(oldest-1);captureGapCount++;return true;
 }

 public boolean add(long ns,double targetFps){
  if(ns<=floor||ns<=last)return false;
  if(last>0){
   double ms=(ns-last)/1e6;
   count++;totalMs+=ms;lastMs=ms;
   tail[tailPos++%60]=ms;tailCount=Math.min(60,tailCount+1);
   if(aggregate){
    int b=(int)Math.min(8191,ms*2);counts[b]++;sums[b]+=ms;
    double budget=1000/Math.max(1,targetFps);
    if(ms>budget*1.5)longFrames++;
    if(ms>budget*3)bigLongFrames++;
   }
  }
  last=ns;
  if(recentSize==recent.length){recentStart=(recentStart+1)%recent.length;recentSize--;}
  recent[(recentStart+recentSize)%recent.length]=ns;recentSize++;
  // Retain one boundary frame immediately before the latest-present one-second
  // window. The window follows presentation time, never the poll completion time.
  long cutoff=ns-WINDOW_NS;
  while(recentSize>2&&recentAt(1)<=cutoff){recentStart=(recentStart+1)%recent.length;recentSize--;}
  return true;
 }

 private long recentAt(int index){return recent[(recentStart+index)%recent.length];}
 private boolean fullWindow(){return recentSize>=2&&last-recentAt(0)>=WINDOW_NS-WINDOW_TOLERANCE_NS;}

 /** Complete same-source history, or a full second with no new actual presentation. */
 public boolean windowReady(long now){return last>0&&(fullWindow()||now-last>=WINDOW_NS);}
 public long lastFrameNs(){return last;}
 public double frameDataAgeMs(long now){return last==0?Double.NaN:Math.max(0,(now-last)/1e6);}
 public double windowSpanMs(){return recentSize<2?Double.NaN:(last-recentAt(0))/1e6;}

 /**
  * Measured presentation rate in the latest approximately one-second window.
  * Wait for a complete window instead of displaying a biased startup ramp.
  * The caller must separately mark an unavailable frame source as unavailable.
  */
 public double fps(long now){
  if(last==0)return Double.NaN;
  if(now-last>=WINDOW_NS)return 0;
  if(!fullWindow())return Double.NaN;
  return (recentSize-1)*1e9/(last-recentAt(0));
 }
 public double frameMs(){return lastMs;}
 public double frameMs(long now){return last==0||now-last>=WINDOW_NS?Double.NaN:lastMs;}
 public double average(){return count==0?Double.NaN:1000*count/totalMs;}
 public double low(double portion){if(!aggregate||count==0)return Double.NaN;long n=Math.max(1,(long)Math.ceil(count*portion)),remaining=n;double sum=0;for(int i=8191;i>=0&&remaining>0;i--){long take=Math.min(remaining,counts[i]);if(take>0){sum+=sums[i]*take/counts[i];remaining-=take;}}return sum>0?1000*n/sum:Double.NaN;}
 public double percentile(double portion){if(!aggregate||count==0)return Double.NaN;long rank=Math.max(1,(long)Math.ceil(count*Math.max(0,Math.min(1,portion)))),seen=0;for(int i=0;i<counts.length;i++){seen+=counts[i];if(seen>=rank)return sums[i]/counts[i];}return Double.NaN;}
 public double[] tail(){double[] a=new double[tailCount];for(int i=0;i<a.length;i++)a[i]=tail[Math.floorMod(tailPos-tailCount+i,60)];return a;}
}
