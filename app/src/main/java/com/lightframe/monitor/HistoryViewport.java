package com.lightframe.monitor;
/** Shared interval for all charts. Focus-preserving zoom and bounded panning. */
public final class HistoryViewport {
 public final double fullEnd;public double begin,end;
 public HistoryViewport(double fullEnd){this.fullEnd=Double.isFinite(fullEnd)?Math.max(.001,fullEnd):.001;reset();}
 public void reset(){begin=0;end=fullEnd;}
 public void set(double from,double to){if(!Double.isFinite(from)||!Double.isFinite(to)||to<=from)throw new IllegalArgumentException("结束时间必须大于开始时间");begin=from;end=to;clamp();}
 public void zoom(double factor,double fraction){if(!Double.isFinite(factor)||factor<=0)return;fraction=Math.max(0,Math.min(1,fraction));double span=end-begin,focus=begin+span*fraction;double next=Math.max(Math.min(.001,fullEnd),Math.min(fullEnd,span/factor));begin=focus-next*fraction;end=begin+next;clamp();}
 public void pan(double seconds){if(!Double.isFinite(seconds))return;begin+=seconds;end+=seconds;clamp();}
 private void clamp(){double span=Math.min(fullEnd,end-begin);if(begin<0)begin=0;if(begin+span>fullEnd)begin=Math.max(0,fullEnd-span);end=begin+span;}
}
