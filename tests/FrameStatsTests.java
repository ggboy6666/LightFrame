package com.lightframe.monitor;

/** Regression tests use genuine synthetic presentation timestamps, not target-FPS placeholders. */
public final class FrameStatsTests {
 static int checks;
 static void ok(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
 static void eq(double actual,double expected,double tolerance,String name){ok(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,name+" actual="+actual+" expected="+expected);}
 static void missing(double actual,String name){ok(Double.isNaN(actual),name+" actual="+actual);}
 static long timestamp(long start,int frame,int rate){return start+frame*1_000_000_000L/rate;}
 static void addRange(FrameStats stats,long start,int first,int last,int rate){for(int i=first;i<=last;i++)stats.add(timestamp(start,i,rate),60);}

 static void constantRateAndDelay(int rate){
  long start=10_000_000_000L;
  FrameStats stats=new FrameStats(false);
  stats.updateSource("game",start);
  ok(!stats.add(start,165),"binding excludes boundary frame at "+rate);
  ok(!stats.add(start-1,165),"binding excludes pre-recording buffer at "+rate);
  addRange(stats,start,1,rate/2,rate);
  missing(stats.fps(timestamp(start,rate/2,rate)+80_000_000L),"half-second startup does not produce ramp at "+rate);
  ok(!stats.windowReady(timestamp(start,rate/2,rate)),"half-second source is warming at "+rate);
  addRange(stats,start,rate/2+1,rate+1,rate);
  long last=stats.lastFrameNs();
  ok(stats.windowReady(last),"complete actual window at "+rate);
  for(long lag:new long[]{0,5_000_000L,50_000_000L,250_000_000L,900_000_000L})
   eq(stats.fps(last+lag),rate,.0001,"poll completion delay is not frame duration at "+rate+" lag="+lag);
  eq(stats.frameDataAgeMs(last+250_000_000L),250,0,"age reports actual poll delay at "+rate);
  eq(stats.frameMs(last),1000d/rate,.00001,"actual last frame interval at "+rate);
  long count=stats.count;
  addRange(stats,start,1,rate+1,rate);
  ok(stats.count==count,"repeated latency buffer is deduplicated at "+rate);
  eq(stats.fps(last),rate,.0001,"duplicate buffer cannot inflate FPS at "+rate);
  missing(stats.fps(last+1_000_000_000L),"old presentation evidence is not a measured zero at "+rate);
  missing(stats.frameMs(last+1_000_000_000L),"expired frame duration is not shown as current at "+rate);
  ok(!stats.windowReady(last+1_000_000_000L),"expired presentation window is unavailable at "+rate);
  ok(stats.windowStale(last+1_000_000_000L),"expired presentation evidence is explicitly stale at "+rate);
  stats.updateSource("",last+1_100_000_000L);
  missing(stats.fps(last+1_100_000_000L),"unavailable source is missing rather than zero at "+rate);
  missing(stats.frameDataAgeMs(last+1_100_000_000L),"no age retained for unavailable source at "+rate);
 }

 static void jitteredPolls(int rate){
  long start=20_000_000_000L;
  FrameStats stats=new FrameStats(false);stats.updateSource("game",start);
  long elapsed=0;int greatestFrame=0,readyChecks=0;
  long[] periods={85_000_000L,160_000_000L,110_000_000L,280_000_000L,135_000_000L};
  long[] readDelays={4_000_000L,120_000_000L,30_000_000L,210_000_000L,65_000_000L};
  for(int poll=0;poll<100;poll++){
   elapsed+=periods[poll%periods.length];
   long sampled=elapsed-readDelays[poll%readDelays.length];
   int newest=(int)(sampled*rate/1_000_000_000L),oldest=newest-126;
   ok(!stats.beginBatch(timestamp(start,oldest,rate),timestamp(start,newest,rate),127),"overlapping jittered batch does not lose coverage at "+rate);
   addRange(stats,start,oldest,newest,rate);
   greatestFrame=Math.max(greatestFrame,newest);
   double value=stats.fps(start+elapsed);
   if(stats.windowReady(start+elapsed)){eq(value,rate,.0001,"jittered poll remains real presentation rate at "+rate);readyChecks++;}
   else missing(value,"unready jittered poll does not emit biased ramp at "+rate);
  }
  ok(readyChecks>80,"jittered poll supplies mature windows at "+rate);
  ok(stats.count==greatestFrame-1,"jittered overlap counts each captured interval once at "+rate);
  ok(stats.captureGapCount==0,"normal jitter is not declared lost coverage at "+rate);
 }

 static void sourceBoundaries(){
  long start=30_000_000_000L;
  FrameStats stats=new FrameStats();
  stats.discontinuity(start-2_000_000_000L);
  stats.updateSource("A",start);
  // The constructor's recording start can precede late discovery by seconds.
  for(int i=-126;i<=0;i++)ok(!stats.add(timestamp(start,i,120),60),"first 127 buffered frames precede source binding");
  missing(stats.fps(start),"first buffer cannot become a measured history");
  addRange(stats,start,1,121,120);
  eq(stats.fps(timestamp(start,121,120)+30_000_000L),120,.0001,"new source reaches measured rate after full real window");
  long before=stats.count,changed=start+3_000_000_000L;
  stats.updateSource("B",changed);
  ok(!stats.add(changed-1,60),"switch cannot ingest previous source buffer");
  addRange(stats,changed,1,31,60);
  missing(stats.fps(timestamp(changed,31,60)),"half-second source B does not inherit ready A window");
  addRange(stats,changed,32,61,60);
  eq(stats.fps(timestamp(changed,61,60)+200_000_000L),60,.0001,"B is independently measured at sixty");
  ok(stats.count==before+60&&stats.longFrames==0,"switching sources does not fabricate a long interval");
  long paused=changed+2_000_000_000L;stats.discontinuity(paused);
  ok(!stats.add(paused-1,60),"pause rejects pre-resume buffered frame");
  stats.add(paused+10_000_000L,60);
  missing(stats.frameMs(),"first post-pause timestamp is a new baseline");
  missing(stats.fps(paused+20_000_000L),"resume cannot display prior rolling FPS");
 }

 static void lostCoverage(){
  long start=40_000_000_000L;
  FrameStats stats=new FrameStats();stats.updateSource("game",start);
  addRange(stats,start,1,220,165);
  eq(stats.fps(timestamp(start,220,165)),165,.0001,"high-rate stream is measured before missed poll");
  long before=stats.count;
  ok(stats.beginBatch(timestamp(start,624,165),timestamp(start,750,165),127),"full disjoint response marks uncertain coverage");
  ok(stats.captureGapCount==1,"coverage break is counted explicitly");
  addRange(stats,start,624,750,165);
  ok(stats.count==before+126,"only intervals present inside replacement buffer are counted");
  ok(stats.longFrames==0,"missed polling does not invent an inter-buffer long frame");
  missing(stats.fps(timestamp(start,750,165)),"127 frames below one second cannot produce startup rise");
  ok(!stats.beginBatch(timestamp(start,640,165),timestamp(start,766,165),127),"next overlapping response preserves replacement history");
  addRange(stats,start,640,766,165);
  missing(stats.fps(timestamp(start,766,165)),"replacement window still waits for full coverage");
  addRange(stats,start,767,790,165);
  eq(stats.fps(timestamp(start,790,165)+70_000_000L),165,.0001,"recovered complete window has no gradual artificial ramp");
  ok(!stats.beginBatch(1,2,127),"old complete response does not reset current stream");
  ok(!stats.beginBatch(timestamp(start,800,165),timestamp(start,799,165),127),"invalid response range cannot reset stream");

  FrameStats slow=new FrameStats();slow.updateSource("slow",start);
  addRange(slow,start,1,7,5);
  long count=slow.count;
  ok(!slow.beginBatch(start+5_000_000_000L,start+5_200_000_000L,2),"unfilled low-rate buffer does not prove overwriting");
  slow.add(start+5_000_000_000L,60);slow.add(start+5_200_000_000L,60);
  ok(slow.count==count+2&&slow.captureGapCount==0,"observed actual low-rate intervals remain recordable");
  eq(slow.frameMs(),200,0,"low-rate real interval is not normalized to target");
 }

 static void liveOnlyAndChangingRate(){
  long start=50_000_000_000L;
  FrameStats live=new FrameStats(false);live.updateSource("game",start);
  addRange(live,start,1,121,120);
  missing(live.low(.01),"live collector does not scan full-session low histogram");
  missing(live.percentile(.95),"live collector does not scan full-session percentile histogram");
  ok(live.longFrames==0&&live.bigLongFrames==0,"disabled full aggregation leaves long-frame summaries offline");
  eq(live.average(),120,.0001,"cheap actual captured average remains available");
  long changed=live.lastFrameNs();
  for(int i=1;i<=61;i++)live.add(timestamp(changed,i,60),165);
  eq(live.fps(live.lastFrameNs()+80_000_000L),60,.0001,"FPS follows new real rate after a one-second transition");

  FrameStats aboveTarget=new FrameStats(false);aboveTarget.updateSource("uncapped",start);
  addRange(aboveTarget,start,1,241,240);
  eq(aboveTarget.fps(aboveTarget.lastFrameNs()+200_000_000L),240,.0001,"real FPS is never capped to configured sixty/165");
  FrameStats one=new FrameStats(false);one.updateSource("one",start);one.add(start+10_000_000L,60);
  missing(one.fps(start+500_000_000L),"one timestamp cannot estimate frame rate");
  missing(one.fps(start+1_010_000_000L),"one old timestamp cannot prove a zero frame rate");
 }

 static void slowPresentationAndStaleEvidence(){
  long start=60_000_000_000L;
  for(long interval:new long[]{1_000_000_000L,2_000_000_000L}){
   FrameStats slow=new FrameStats();slow.updateSource("slow",start);
   slow.add(start+interval,120);
   missing(slow.fps(start+interval),"one slow presentation has no rate");
   for(int i=2;i<=5;i++){
    long present=start+i*interval;slow.add(present,120);
    eq(slow.fps(present+200_000_000L),1e9/interval,0,"fresh slow interval measures positive actual FPS");
    eq(slow.frameMs(present+200_000_000L),interval/1e6,0,"slow interval remains an actual long frame");
    ok(slow.windowReady(present+200_000_000L)&&!slow.windowStale(present+200_000_000L),"fresh slow window is ready");
    missing(slow.fps(present+1_000_000_000L),"waiting for a slow frame never emits an invented zero");
    ok(!slow.windowReady(present+1_000_000_000L)&&slow.windowStale(present+1_000_000_000L),"slow old window is explicitly stale");
   }
   eq(slow.average(),1e9/interval,0,"captured average retains every real slow interval");
   ok(slow.count==4&&slow.longFrames==4&&slow.bigLongFrames==4,"slow frames are retained in full aggregation");
  }

  FrameStats stalled=new FrameStats();stalled.updateSource("game",start);addRange(stalled,start,1,121,120);
  long last=stalled.lastFrameNs(),before=stalled.count;
  for(long delay:new long[]{1_000_000_000L,1_500_000_000L,2_500_000_000L}){
   missing(stalled.fps(last+delay),"a repeated old high-rate window cannot manufacture zero FPS");
   ok(!stalled.windowReady(last+delay)&&stalled.windowStale(last+delay),"old high-rate window reports unavailable freshness");
  }
  ok(!stalled.add(last,120)&&stalled.count==before,"duplicate old frame changes no raw interval evidence");
  long resumed=last+2_500_000_000L;stalled.add(resumed,120);
  eq(stalled.fps(resumed),.4,0,"a newly observed real 2.5-second stall measures positive low FPS");
  eq(stalled.frameMs(resumed),2500,0,"real stall remains in raw frame duration");
  ok(stalled.count==before+1&&stalled.longFrames==1&&stalled.bigLongFrames==1,"real stall is not discarded by missing-window handling");
  ok(stalled.average()<120,"real stalled interval still lowers captured average");
  for(int i=1;i<=121;i++)stalled.add(timestamp(resumed,i,120),120);
  eq(stalled.fps(stalled.lastFrameNs()),120,.0001,"one complete new high-rate window recovers without a false startup ramp");

  FrameStats empty=new FrameStats(false);
  ok(!empty.windowStale(start)&&!empty.windowReady(start),"no presentation is distinct from stale presentation");
  missing(empty.fps(start),"no presentation is missing");
 }

 public static void main(String[] args){
  for(int rate:new int[]{1,5,30,60,120,165})constantRateAndDelay(rate);
  jitteredPolls(60);jitteredPolls(120);
  sourceBoundaries();lostCoverage();liveOnlyAndChangingRate();slowPresentationAndStaleEvidence();
  System.out.println(checks+" frame statistics checks passed");
 }
}
