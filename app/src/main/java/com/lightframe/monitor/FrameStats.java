package com.lightframe.monitor;
import java.util.*;
/** Bounded online frame statistics. Long-frame thresholds are estimates, not official jank. */
public final class FrameStats {
 private final ArrayDeque<Long> recent=new ArrayDeque<>();
 private final long[] counts=new long[8192]; private final double[] sums=new double[8192];
 private long last,floor; public long count,longFrames,bigLongFrames; private double totalMs,lastMs=Double.NaN;
 public final double[] tail=new double[60]; public int tailCount,tailPos;
 public void discontinuity(long ns){last=0;recent.clear();floor=Math.max(floor,ns);lastMs=Double.NaN;tailCount=tailPos=0;}
 public boolean add(long ns,double targetFps){if(ns<=floor||ns<=last)return false;if(last>0){double ms=(ns-last)/1e6;if(ms>0){count++;totalMs+=ms;int b=(int)Math.min(8191,ms*2);counts[b]++;sums[b]+=ms;lastMs=ms;tail[tailPos++%60]=ms;tailCount=Math.min(60,tailCount+1);double budget=1000/Math.max(1,targetFps);if(ms>budget*1.5)longFrames++;if(ms>budget*3)bigLongFrames++;}}last=ns;recent.add(ns);while(recent.size()>2048)recent.removeFirst();return true;}
 public double fps(long now){while(recent.size()>2){Iterator<Long> it=recent.iterator();it.next();if(it.next()<now-1_000_000_000L)recent.removeFirst();else break;}if(recent.size()<2)return Double.NaN;if(now-recent.peekLast()>1_000_000_000L)return 0;return (recent.size()-1)*1e9/(Math.max(now,recent.peekLast())-recent.peekFirst());}
 public double frameMs(){return lastMs;}
 public double average(){return count==0?Double.NaN:1000*count/totalMs;}
 public double low(double portion){if(count==0)return Double.NaN;long n=Math.max(1,(long)Math.ceil(count*portion)),remaining=n;double sum=0;for(int i=8191;i>=0&&remaining>0;i--){long take=Math.min(remaining,counts[i]);if(take>0){sum+=sums[i]*take/counts[i];remaining-=take;}}return sum>0?1000*n/sum:Double.NaN;}
 public double percentile(double portion){if(count==0)return Double.NaN;long rank=Math.max(1,(long)Math.ceil(count*Math.max(0,Math.min(1,portion)))),seen=0;for(int i=0;i<counts.length;i++){seen+=counts[i];if(seen>=rank)return sums[i]/counts[i];}return Double.NaN;}
 public double[] tail(){double[] a=new double[tailCount];for(int i=0;i<a.length;i++)a[i]=tail[Math.floorMod(tailPos-tailCount+i,60)];return a;}
}
