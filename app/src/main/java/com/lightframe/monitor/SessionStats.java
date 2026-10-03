package com.lightframe.monitor;

/** Fixed-memory statistics used only after raw recording has stopped. */
public final class SessionStats {
 private SessionStats() {}
 public static final class Metric {
  public long count;
  public double mean, m2, min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY;
  private final long[] bins;
  private final double[] sums;
  public Metric(boolean fps) { bins=fps?new long[8192]:null; sums=fps?new double[8192]:null; }
  public void add(double value) {
   if(!Double.isFinite(value))return;
   count++;double previous=mean;
   mean=mean*((count-1d)/count)+value/count;
   m2+=(value-previous)*(value-mean);
   min=Math.min(min,value);max=Math.max(max,value);
   if(bins!=null){int b=(int)Math.max(0,Math.min(bins.length-1,value*2));bins[b]++;sums[b]+=value;}
  }
  public double deviation(){return count==0?Double.NaN:count==1?0:Math.sqrt(Math.max(0,m2/(count-1)));}
  public double percentile(double fraction){
   if(bins==null||count==0)return Double.NaN;
   long rank=Math.max(1,(long)Math.ceil(count*fraction)),seen=0;
   for(int i=0;i<bins.length;i++)if((seen+=bins[i])>=rank)return sums[i]/bins[i];
   return Double.NaN;
  }
 }
 public static final class Frames {
  private final long[] bins=new long[16384];
  private final double[] sums=new double[16384];
  public long count,longFrames,bigLongFrames,budgetedCount;
  public double totalMs;
  private boolean record(double ms){
   if(!Double.isFinite(ms)||ms<=0)return false;
   int bin=ms<4096?(int)(ms*2):8192+(int)(Math.log(ms/4096)*512);
   bin=Math.max(0,Math.min(bins.length-1,bin));bins[bin]++;sums[bin]+=ms;count++;totalMs+=ms;return true;
  }
  public void addWithBudget(double ms,long periodNs){
   if(!record(ms))return;
   if(periodNs>0){budgetedCount++;if(FrameBudget.exceeds(ms,periodNs,1.5))longFrames++;if(FrameBudget.exceeds(ms,periodNs,3))bigLongFrames++;}
  }
  public void add(double ms,double targetFps,boolean recordedLong,boolean recordedBig){
   if(!record(ms))return;
   if(Double.isFinite(targetFps)&&targetFps>0){budgetedCount++;double budget=1000/targetFps;if(ms>1.5*budget)longFrames++;if(ms>3*budget)bigLongFrames++;}
   else{if(recordedLong)longFrames++;if(recordedBig)bigLongFrames++;}
  }
  public double averageFps(){return count>0&&totalMs>0?1000*count/totalMs:Double.NaN;}
  public double low(double fraction){
   if(count==0)return Double.NaN;
   long need=Math.max(1,(long)Math.ceil(count*fraction)),left=need;double sum=0;
   for(int i=bins.length-1;i>=0&&left>0;i--){long take=Math.min(left,bins[i]);if(take>0){sum+=sums[i]*((double)take/bins[i]);left-=take;}}
   return sum>0?1000*need/sum:Double.NaN;
  }
  public double percentile(double fraction){
   if(count==0)return Double.NaN;
   long rank=Math.max(1,(long)Math.ceil(count*fraction)),seen=0;
   for(int i=0;i<bins.length;i++)if((seen+=bins[i])>=rank)return sums[i]/bins[i];
   return Double.NaN;
  }
 }
}
