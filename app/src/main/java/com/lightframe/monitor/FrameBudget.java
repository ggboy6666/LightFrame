package com.lightframe.monitor;

/** Measured display/vsync periods, independent of an application's measured FPS. */
public final class FrameBudget {
 private FrameBudget(){}
 public static long latencyPeriodNs(String latency){
  if(latency==null)return 0;int end=latency.indexOf('\n');
  String first=(end<0?latency:latency.substring(0,end)).trim();
  try{long value=Long.parseLong(first);return value>0&&value<Long.MAX_VALUE?value:0;}
  catch(NumberFormatException unavailable){return 0;}
 }
 public static long periodNs(double refreshHz){
  if(!Double.isFinite(refreshHz)||refreshHz<=0)return 0;
  double period=1e9/refreshHz;
  return Double.isFinite(period)&&period>=1&&period<Long.MAX_VALUE?Math.round(period):0;
 }
 public static double hz(long periodNs){return periodNs>0?1e9/periodNs:Double.NaN;}
 /** One microsecond avoids declaring integer-nanosecond rounding a missed budget. */
 public static boolean exceeds(double intervalMs,long periodNs,double multiple){
  return Double.isFinite(intervalMs)&&intervalMs>0&&periodNs>0&&
          intervalMs*1e6>periodNs*multiple+1000;
 }
 /** A changed header does not reveal which buffered frame crossed the mode switch. */
 public static final class Tracker {
  private long period,observed,previousPeriod,changeBegin,changeEnd;
  private String source="unavailable",previousSource="unavailable";
  public void observe(long nextPeriod,String nextSource,long atNs){
   if(atNs<=0||atNs<observed)return;
   if(nextPeriod<0||nextPeriod==Long.MAX_VALUE)nextPeriod=0;
   if(nextPeriod!=period){
    previousPeriod=period;previousSource=source;changeBegin=observed;changeEnd=atNs;
   }
   period=nextPeriod;observed=atNs;source=nextSource==null||nextSource.isEmpty()?"unavailable":nextSource;
  }
  public long periodFor(long intervalBegin,long intervalEnd){
   if(intervalBegin<=0||intervalEnd<=intervalBegin||observed<=0)return 0;
   if(changeBegin>0&&intervalEnd<=changeBegin)return previousPeriod;
   return period>0&&intervalBegin>=changeEnd?period:0;
  }
  public String sourceFor(long intervalBegin,long intervalEnd){
   long value=periodFor(intervalBegin,intervalEnd);
   if(value==0)return period>0?"refresh_transition":"unavailable";
   return changeBegin>0&&intervalEnd<=changeBegin?previousSource:source;
  }
  public long currentPeriodNs(){return period;}
 }
}
