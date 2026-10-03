package com.lightframe.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Actual CSV analysis and dynamic refresh observations, without Android mocks. */
public final class FrameBudgetTests {
 private static int checks;
 private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 private static void near(double value,double wanted,String why){check(Double.isFinite(value)&&Math.abs(value-wanted)<1e-7*Math.max(1,Math.abs(wanted)),why+": "+value+" != "+wanted);}
 public static void main(String[] args)throws Exception{
  parsingAndRates();dynamicTransitions();automaticCsv();unknownBudgetAndRateOnly();
  System.out.println("Automatic frame-budget checks: "+checks);
 }
 private static void parsingAndRates(){
  for(double rate:new double[]{60,144,240,300,1000}){
   long period=FrameBudget.periodNs(rate);double actualRate=1e9/period;
   check(period>0,"current and future high refresh rates have no manual ceiling");
   check(FrameBudget.latencyPeriodNs(period+"\r\n0 123 0\n")==period,"latency header uses refresh period, not presented frame rate");
   near(FrameBudget.hz(period),actualRate,"refresh rate follows exact integer-nanosecond header");
   check(!FrameBudget.exceeds(period*1.5/1e6,period,1.5),"exact long-frame boundary is not exceeded");
   check(FrameBudget.exceeds(period*1.5/1e6+.002,period,1.5),"real budget overrun beyond rounding is detected");
   check(!FrameBudget.exceeds(period*3/1e6,period,3),"exact serious-frame boundary is not exceeded");
   check(FrameBudget.exceeds(period*3/1e6+.002,period,3),"serious overrun uses each refresh rate");
   SessionStats.Frames stats=new SessionStats.Frames();stats.addWithBudget(period/1e6,period);
   stats.addWithBudget(period*1.6/1e6,period);stats.addWithBudget(period*3.1/1e6,period);
   check(stats.count==3&&stats.budgetedCount==3&&stats.longFrames==2&&stats.bigLongFrames==1,
           "long-frame metrics adapt to "+rate+" Hz");
   FrameStats live=new FrameStats(false);long start=1_000_000_000L;live.discontinuity(start-1);
   int frames=(int)Math.ceil(1e9/period)+2;for(int i=0;i<=frames;i++)live.add(start+i*period,Double.NaN);
   near(live.fps(start+frames*period),actualRate,"actual FPS remains uncapped by budget/settings");
  }
  for(String raw:new String[]{"","0\n","-1\n","60.0\n","Permission denied\n",Long.MAX_VALUE+"\n"})
   check(FrameBudget.latencyPeriodNs(raw)==0,"invalid/absent latency header cannot invent a budget");
  check(FrameBudget.latencyPeriodNs(null)==0,"null response has no budget");
  for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
   check(FrameBudget.periodNs(value)==0,"invalid display rate cannot silently become sixty Hz");
  check(Double.isNaN(FrameBudget.hz(0)),"missing budget rate remains unavailable");
  check(!FrameBudget.exceeds(100,0,1.5),"unknown budget never declares a long frame");
  check(FrameBudget.periodNs(1)>0&&FrameBudget.periodNs(100_000)>0,"no fixed 60/165/300 upper cap");
 }
 private static void dynamicTransitions(){
  FrameBudget.Tracker tracker=new FrameBudget.Tracker();long sixty=FrameBudget.periodNs(60),fast=FrameBudget.periodNs(240);
  check(tracker.periodFor(1,2)==0,"frames before any display observation have unknown budget");
  tracker.observe(sixty,"display_refresh_rate",1_000_000_000L);
  check(tracker.periodFor(900_000_000L,990_000_000L)==0,"first observation does not relabel old buffered frames");
  check(tracker.periodFor(1_100_000_000L,1_120_000_000L)==sixty,"frames after first observation use observed period");
  tracker.observe(fast,"surfaceflinger_vsync",2_000_000_000L);
  check(tracker.periodFor(900_000_000L,990_000_000L)==sixty,"interval entirely before mode-change range retains previous period");
  check(tracker.periodFor(1_500_000_000L,1_510_000_000L)==0,"mode change location inside buffered range is not guessed");
  check(tracker.periodFor(1_999_000_000L,2_003_000_000L)==0,"crossing interval preserves actual FPS but has unknown budget");
  check(tracker.periodFor(2_100_000_000L,2_104_000_000L)==fast,"new period applies after mode observation");
  check(tracker.sourceFor(1_500_000_000L,1_510_000_000L).equals("refresh_transition"),"ambiguous transition carries explicit source");
  tracker.observe(fast,"surfaceflinger_vsync",2_500_000_000L);
  check(tracker.periodFor(2_100_000_000L,2_104_000_000L)==fast,"unchanged refresh does not create a new ambiguous range");
  tracker.observe(FrameBudget.periodNs(300),"surfaceflinger_vsync",3_000_000_000L);
  check(tracker.periodFor(3_100_000_000L,3_104_000_000L)==FrameBudget.periodNs(300),"future 300 Hz mode is accepted dynamically");
  tracker.observe(sixty,"display_refresh_rate",2_000_000_000L);
  check(tracker.currentPeriodNs()==FrameBudget.periodNs(300),"out-of-order observations cannot replace current mode");
  tracker.observe(0,"unavailable",4_000_000_000L);
  check(tracker.periodFor(4_100_000_000L,4_200_000_000L)==0,"missing refresh does not carry stale budget forward");
  tracker.observe(sixty,"display_refresh_rate",5_000_000_000L);
  check(tracker.periodFor(4_500_000_000L,4_510_000_000L)==0,"budget recovery does not fill unknown frames");
  check(tracker.periodFor(5_100_000_000L,5_120_000_000L)==sixty,"valid later display observation recovers budget");
 }
 private static void write(Path dir,String name,String text)throws IOException{Files.write(dir.resolve(name),text.getBytes(StandardCharsets.UTF_8));}
 private static void clean(Path dir)throws IOException{try(DirectoryStream<Path> files=Files.newDirectoryStream(dir)){for(Path file:files)Files.deleteIfExists(file);}Files.deleteIfExists(dir);}
 private static void automaticCsv()throws Exception{
  Path dir=Files.createTempDirectory("lightframe-auto-budget-");try{
   write(dir,"samples.csv","elapsed_s,fps,frameBudgetNs,frameBudgetHz,frameBudgetSource,frameBudgetSampleNs\n0,300,3333333,300,surfaceflinger_vsync,12345\n1,240,4166667,240,surfaceflinger_vsync,22345\n");
   StringBuilder csv=new StringBuilder("elapsed_s,present_ns,interval_ms,segment,layer,refresh_period_ns,refresh_rate_hz,refresh_source\n0,1000000000,,1,game,,,unavailable\n");
   double[] intervals={4.166667,5.5,12,27,12,100};double[] rates={240,300,144,60,300,Double.NaN};long stamp=1_000_000_000L;
   for(int i=0;i<intervals.length;i++){stamp+=Math.round(intervals[i]*1e6);long period=FrameBudget.periodNs(rates[i]);csv.append(CsvIndex.encode(new Object[]{(stamp-1_000_000_000L)/1e9,stamp,intervals[i],1,"game",period>0?period:"",period>0?FrameBudget.hz(period):"",period>0?"surfaceflinger_vsync":"refresh_transition"})).append('\n');}
   write(dir,"frames.csv",csv.toString());byte[] before=Files.readAllBytes(dir.resolve("frames.csv"));
   JSONObject seed=new JSONObject().put("status","complete").put("frameBudgetMode","automatic_display").put("targetFps",1000).put("config",new JSONObject().put("targetFps",1000));
   JSONObject summary=SessionAnalysis.analyze(dir.toFile(),seed,null);
   check(summary.getLong("capturedIntervals")==6&&summary.getLong("budgetedIntervals")==5&&summary.getLong("unknownBudgetIntervals")==1,"unknown budget retains frame interval with separate coverage");
   check(summary.getLong("longFramesEstimate")==4&&summary.getLong("bigLongFramesEstimate")==1,"per-frame 240/300/144/60 Hz budgets override obsolete manual target");
   near(summary.getDouble("capturedFrameAverageFps"),6000/160.666667,"unknown refresh does not discard actual measured interval");
   check(!summary.has("targetFps")&&!summary.getJSONObject("config").has("targetFps"),"new automatic summary contains no manual target setting");
   check(!summary.getJSONObject("statistics").has("frameBudgetNs")&&!summary.getJSONObject("statistics").has("frameBudgetHz"),"budget metadata is not mistaken for measured performance metrics");
   check(Arrays.equals(before,Files.readAllBytes(dir.resolve("frames.csv"))),"analysis preserves every original frame byte");
  }finally{clean(dir);}
 }
 private static void unknownBudgetAndRateOnly()throws Exception{
  for(boolean rateOnly:new boolean[]{false,true}){Path dir=Files.createTempDirectory("lightframe-budget-fallback-");try{
   write(dir,"samples.csv","elapsed_s,fps\n0,300\n");
   write(dir,"frames.csv","elapsed_s,present_ns,interval_ms,segment,layer,target_fps,refresh_rate_hz\n0,1000000000,,1,game,60,\n.012,1012000000,12,1,game,60,"+(rateOnly?"300":"")+"\n");
   JSONObject summary=SessionAnalysis.analyze(dir.toFile(),new JSONObject().put("status","complete").put("targetFps",60),null);
   check(summary.getLong("capturedIntervals")==1,"automatic budget schema preserves interval with or without refresh period");
   if(rateOnly)check(summary.getLong("budgetedIntervals")==1&&summary.getLong("longFramesEstimate")==1&&summary.getLong("bigLongFramesEstimate")==1,"explicit per-frame 300 Hz fallback supports future records");
   else check(summary.getLong("budgetedIntervals")==0&&summary.isNull("longFramesEstimate")&&summary.isNull("bigLongFramesEstimate"),"unknown automatic budget reports unavailable counts, never falls back to obsolete manual target");
  }finally{clean(dir);}}
 }
}
