package com.lightframe.monitor;

public final class SamplingCadenceTests {
 private static int checks;
 private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 private static void equal(long actual,long expected,String why){check(actual==expected,why+": "+actual);}
 public static void main(String[] args){
  equal(SamplingCadence.frameCycleMs(500,FrameBudget.periodNs(60),true),500,"60 Hz default");
  equal(SamplingCadence.frameCycleMs(500,FrameBudget.periodNs(144),true),500,"144 Hz remains500ms");
  equal(SamplingCadence.frameCycleMs(500,FrameBudget.periodNs(240),true),416,"240 Hz finite ring capacity");
  equal(SamplingCadence.frameCycleMs(500,FrameBudget.periodNs(300),true),333,"no refresh cap at240");
  equal(SamplingCadence.frameCycleMs(500,FrameBudget.periodNs(300),false),500,"disabled frames need no frame-capacity cadence");
  equal(SamplingCadence.frameCycleMs(500,0,true),500,"unknown refresh does not invent60Hz");
  equal(SamplingCadence.delayMs(500,FrameBudget.periodNs(240),true,25),391,"collector cost included in cycle");
  equal(SamplingCadence.delayMs(500,FrameBudget.periodNs(240),true,500),500,"slow request backs off instead of immediate catchup");
  equal(SamplingCadence.delayMs(500,FrameBudget.periodNs(240),true,2000),2000,"multi second timeout backs off");
  equal(SamplingCadence.delayMs(500,FrameBudget.periodNs(240),true,10000),5000,"bounded delay after severe blockage");
  check(SamplingCadence.capacityExceeded(FrameBudget.periodNs(240),550),"cannot promise ring coverage for slow collector");
  check(!SamplingCadence.capacityExceeded(FrameBudget.periodNs(240),25),"ordinary collector cost fits ring");
  for(int hz:new int[]{30,60,90,120,144,165,240,300,360}){
   long period=FrameBudget.periodNs(hz);
   for(int cost:new int[]{0,10,25,100,250,500,2000}){
    long delay=SamplingCadence.delayMs(500,period,true,cost);
    check(delay>=50,"no zero delay loops");check(delay>=Math.min(5000,cost),"no chasing slow requests");
    if(cost<SamplingCadence.frameCycleMs(500,period,true)/2)
     check((cost+delay)*1_000_000d<127d*period,"normal cost retains frame overlap at"+hz);
   }
  }
  System.out.println("SamplingCadenceTests: "+checks+" checks passed");
 }
}
