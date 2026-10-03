package com.lightframe.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Exact legacy row equivalence and real-file, staggered complete-row snapshots. */
public final class RecordingHotPathTests {
 private static int checks;
 private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 private static String old(double elapsed,long present,double interval,long segment,String layer,long period,String source){
  return CsvIndex.encode(new Object[]{elapsed,present,Double.isFinite(interval)?interval:"","","",segment,layer,
          period>0?period:"",period>0?FrameBudget.hz(period):"",source})+"\n";
 }
 private static void same(FrameCsvWriter csv,double elapsed,long present,double interval,long segment,String layer,long period,String source)throws Exception{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();Writer writer=new OutputStreamWriter(bytes,StandardCharsets.UTF_8);
  csv.write(writer,elapsed,present,interval,segment,layer,period,source);writer.flush();
  String expected=old(elapsed,present,interval,segment,layer,period,source);
  check(Arrays.equals(bytes.toByteArray(),expected.getBytes(StandardCharsets.UTF_8)),"all ten fields and UTF-8 match original encoder");
  String[] row=CsvIndex.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8).trim());
  check(row.length==10,"single complete ten-column row remains parseable");
 }
 public static void main(String[] args)throws Exception{
  FrameCsvWriter csv=new FrameCsvWriter();
  StringWriter literal=new StringWriter();csv.write(literal,.5,500000000,Double.NaN,2,"game",0,"unavailable");
  check(literal.toString().equals("0.5,500000000,,,,2,game,,,unavailable\n"),"known unavailable row retains empty flags, interval and refresh fields");
  for(double interval:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,0,-1,.000001,1000d/300,1e7})
   for(long period:new long[]{0,-1,1,3333333,Long.MAX_VALUE})same(csv,.5,70000000000000L,interval,2,"游戏,\"surface\"\r\n层",period,"source\nwith \"quotes\"");
  Random random=new Random(93174);String[] cells={"game","",null,"列,逗号","\"引号\"","两\r\n行","surface/游戏😀","unavailable","refresh_transition"};
  for(int i=0;i<1000;i++)same(csv,random.nextDouble()*3600,1_000_000_000L+random.nextInt(1_000_000_000),
          i%5==0?Double.NaN:random.nextDouble()*10000,random.nextInt(100),cells[random.nextInt(cells.length)],
          i%3==0?0:Math.max(1,random.nextInt(20000000)),cells[random.nextInt(cells.length)]);
  StringBuilder huge=new StringBuilder();for(int i=0;i<2000;i++)huge.append("长字段,\"引号\"");
  same(csv,60,Long.MAX_VALUE-1,8.3,10,huge.toString(),8333333,"surfaceflinger_vsync");
  same(csv,61,Long.MAX_VALUE-2,8.4,10,"short",8333333,"surfaceflinger_vsync");
  try{csv.write(new Writer(){public void write(char[] c,int o,int n)throws IOException{throw new IOException("disk write failure");}public void flush(){}public void close(){}},0,1,1,1,"game",1,"vsync");throw new AssertionError("write failure swallowed");}
  catch(IOException expected){check(expected.getMessage().equals("disk write failure"),"write errors propagate to record stop");}
  schedule();snapshots(csv);completedTiming();System.out.println("Recording hot-path checks: "+checks);
 }
 private static void schedule(){
  RecordingFlushSchedule s=new RecordingFlushSchedule(0);
  check(s.due(0,true,true)==RecordingFlushSchedule.NONE,"headers were already published; no first-sample double flush");
  check(s.due(1_000_000_000L,true,true)==RecordingFlushSchedule.FRAMES,"frame flush first after one second");s.flushed(RecordingFlushSchedule.FRAMES,1_000_000_000L);
  check(s.due(2_000_000_000L,true,true)==RecordingFlushSchedule.SAMPLES,"sample flush offset by one second");s.flushed(RecordingFlushSchedule.SAMPLES,2_000_000_000L);
  check(s.due(3_000_000_000L,true,true)==RecordingFlushSchedule.FRAMES,"frame cadence remains two seconds");s.flushed(RecordingFlushSchedule.FRAMES,3_000_000_000L);
  check(s.due(4_000_000_000L,true,true)==RecordingFlushSchedule.SAMPLES,"sample cadence remains two seconds");s.flushed(RecordingFlushSchedule.SAMPLES,4_000_000_000L);
  check(s.due(10_000_000_000L,true,true)==RecordingFlushSchedule.FRAMES,"late batch flushes only earlier overdue file");s.flushed(RecordingFlushSchedule.FRAMES,10_000_000_000L);
  check(s.due(10_500_000_000L,true,true)==RecordingFlushSchedule.SAMPLES,"remaining overdue file flushes on following batch");s.flushed(RecordingFlushSchedule.SAMPLES,10_500_000_000L);
  check(s.due(11_000_000_000L,true,true)==RecordingFlushSchedule.NONE,"no immediate catch-up repeat flush");
  check(s.due(100_000_000_000L,false,false)==RecordingFlushSchedule.NONE,"clean files never flush periodically");
  check(s.due(100_000_000_000L,true,false)==RecordingFlushSchedule.SAMPLES,"FPS disabled still persists hardware samples");
 }
 private static void snapshots(FrameCsvWriter csv)throws Exception{
  Path root=Files.createTempDirectory("lightframe-staggered-record-");Path samples=root.resolve("samples.csv"),frames=root.resolve("frames.csv");
  try{
   try(Writer a=new BufferedWriter(Files.newBufferedWriter(samples,StandardCharsets.UTF_8),32768);Writer b=new BufferedWriter(Files.newBufferedWriter(frames,StandardCharsets.UTF_8),32768)){
    a.write("elapsed_s,fps\n");b.write("elapsed_s,present_ns,interval_ms,long_frame_estimate,big_long_frame_estimate,segment,layer,refresh_period_ns,refresh_rate_hz,refresh_source\n");a.flush();b.flush();
    try(CsvIndex sa=new CsvIndex(samples.toFile());CsvIndex fr=new CsvIndex(frames.toFile())){check(sa.count==0&&fr.count==0,"startup headers allow reliable empty snapshots");}
    RecordingFlushSchedule s=new RecordingFlushSchedule(0);a.write("0.5,300\n");csv.write(b,.5,500000000,3.333333,0,"game",3333333,"vsync");
    check(s.due(1_000_000_000L,true,true)==RecordingFlushSchedule.FRAMES,"first active snapshot publishes frame file only");b.flush();s.flushed(RecordingFlushSchedule.FRAMES,1_000_000_000L);
    try(CsvIndex sa=new CsvIndex(samples.toFile());CsvIndex fr=new CsvIndex(frames.toFile())){check(sa.count==0&&fr.count==1&&fr.row(0).length==10,"files can have different complete-row snapshots without fabricated samples");}
    check(s.due(2_000_000_000L,true,false)==RecordingFlushSchedule.SAMPLES,"sample file publishes independently");a.flush();s.flushed(RecordingFlushSchedule.SAMPLES,2_000_000_000L);
    try(CsvIndex sa=new CsvIndex(samples.toFile());CsvIndex fr=new CsvIndex(frames.toFile())){check(sa.count==1&&fr.count==1&&sa.lastTime==.5&&fr.lastTime==.5,"next phase exposes matching original times");}
    for(int i=1;i<=300;i++)csv.write(b,.5+i/300d,500000000+i*3333333L,3.333333,0,"game",3333333,"vsync");
   }
   try(CsvIndex sa=new CsvIndex(samples.toFile());CsvIndex fr=new CsvIndex(frames.toFile())){
    check(sa.count==1&&fr.count==301,"finish/close persists every buffered high-refresh frame");
    check(fr.row(300)[1].equals(Long.toString(500000000+300*3333333L)),"last presentation timestamp is preserved");
   }
  }finally{Files.deleteIfExists(samples);Files.deleteIfExists(frames);Files.delete(root);}
 }
 private static void completedTiming()throws Exception{
  Path root=Files.createTempDirectory("lightframe-completed-write-timing-");Path samples=root.resolve("samples.csv");
  try{
   byte[] raw=("elapsed_s,unix_ms,fps,previousCycleCostMs,previousRecordWriteCostMs,previousCycleSampleNs,paused\n"+
       "0,1000,60,,,,false\n0.5,1500,60,4.25,1.5,1000000000,false\n"+
       "1,2000,60,40.25,12.5,1500000000,false\n1.5,2500,,,,,true\n").getBytes(StandardCharsets.UTF_8);
   Files.write(samples,raw);JSONObject summary=SessionAnalysis.analyze(root.toFile(),new JSONObject().put("recordTimingDefinition","previous completed tick, never the current row"),null);
   JSONObject stats=summary.getJSONObject("statistics"),cycle=stats.getJSONObject("previousCycleCostMs"),write=stats.getJSONObject("previousRecordWriteCostMs");
   check(cycle.getLong("count")==2,"first/resumed missing timings are not synthetic zero");
   check(cycle.getDouble("avg")==22.25,"completed whole-cycle cost retains real slow batches");
   check(write.getLong("count")==2&&write.getDouble("avg")==7&&write.getDouble("max")==12.5,"record handling cost includes real observed write/flush peak");
   check(!stats.has("previousCycleSampleNs"),"previous tick identity is metadata, not an enormous numeric metric");
   check(Arrays.equals(raw,Files.readAllBytes(samples)),"completed-timing analysis never backfills or edits raw rows");
   check(summary.getString("recordTimingDefinition").equals("previous completed tick, never the current row"),"timing origin survives later analysis");
  }finally{try(DirectoryStream<Path> files=Files.newDirectoryStream(root)){for(Path file:files)Files.deleteIfExists(file);}Files.delete(root);}
 }
}
