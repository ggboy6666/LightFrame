package com.lightframe.monitor;

/** Scheduling uses presentation capacity, never a clamp on measured FPS. */
public final class SamplingCadence {
 private SamplingCadence(){}
 public static long frameCycleMs(int requestedMs,long refreshPeriodNs,boolean frames){
  long requested=Math.max(50,Math.min(5000,requestedMs));
  if(!frames||refreshPeriodNs<=0)return requested;
  // SurfaceFlinger exposes at most 127 entries; leave 27 entries for jitter.
  double capacityMs=refreshPeriodNs*100d/1_000_000d;
  if(!Double.isFinite(capacityMs)||capacityMs<=0)return requested;
  return Math.max(50,Math.min(requested,(long)Math.floor(capacityMs)));
 }
 public static long delayMs(int requestedMs,long refreshPeriodNs,boolean frames,long costMs){
  long cycle=frameCycleMs(requestedMs,refreshPeriodNs,frames);
  // Do not catch up after a slow or blocked request. Leave at least as much
  // idle time as collector time (bounded to 5 s); frame gaps remain explicit.
  long cost=Math.max(0,costMs);
  return Math.max(Math.max(50,cycle-cost),Math.min(5000,cost));
 }
 public static boolean capacityExceeded(long refreshPeriodNs,long cycleCostMs){
  return refreshPeriodNs>0&&cycleCostMs*1_000_000d>=127d*refreshPeriodNs;
 }
}
