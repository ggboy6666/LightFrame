package com.lightframe.monitor;
import java.io.*;
import java.util.*;
/** Full original rows in an inclusive interval. Chart envelopes never enter statistics. */
public final class HistoryRange {
 public static final class Metric {
  public long valid,missing,paused;public double min=Double.NaN,max=Double.NaN,avg=Double.NaN,stdDev=Double.NaN;private double mean,m2;
  void add(double v,boolean pause){if(pause){paused++;return;}if(!Double.isFinite(v)){missing++;return;}valid++;double delta=v-mean;mean+=delta/valid;m2+=delta*(v-mean);avg=mean;min=valid==1?v:Math.min(min,v);max=valid==1?v:Math.max(max,v);stdDev=valid==1?0:Math.sqrt(Math.max(0,m2/(valid-1)));}
 }
 public final double begin,end;public final int from,to;public long rows,pausedRows,frameRows;
 public final LinkedHashMap<String,Metric> metrics=new LinkedHashMap<>();public final LinkedHashMap<String,String> missingReasons=new LinkedHashMap<>();
 public final LinkedHashMap<String,LinkedHashMap<String,Long>> reasonCounts=new LinkedHashMap<>();
 private HistoryRange(double b,double e,int f,int t){begin=b;end=e;from=f;to=t;}
 public static HistoryRange load(CsvIndex source,CsvIndex frames,double begin,double end,String[] keys)throws IOException {
  if(!Double.isFinite(begin)||!Double.isFinite(end)||end<begin)throw new IllegalArgumentException("起止时间无效");
  HistoryRange range=new HistoryRange(begin,end,source.lowerBound(begin),source.upperBound(end));for(String key:keys)range.metrics.put(key,new Metric());
  int paused=source.column("paused");source.scan(range.from,range.to,(n,row)->{
   boolean p=paused>=0&&paused<row.length&&"true".equalsIgnoreCase(row[paused]);range.rows++;if(p)range.pausedRows++;
   for(Map.Entry<String,Metric> entry:range.metrics.entrySet()){
    String key=entry.getKey();double v=source.value(row,key);entry.getValue().add(v,p);
    if(!Double.isFinite(v)&&!p){String reason=missingReason(source,row,key);if(!range.missingReasons.containsKey(key))range.missingReasons.put(key,reason);LinkedHashMap<String,Long> counts=range.reasonCounts.get(key);if(counts==null){counts=new LinkedHashMap<>();range.reasonCounts.put(key,counts);}counts.put(reason,counts.getOrDefault(reason,0L)+1);}
   }
  });
  if(frames!=null){Metric intervals=new Metric();range.metrics.put("interval_ms",intervals);frames.scan(frames.lowerBound(begin),frames.upperBound(end),(n,row)->{range.frameRows++;intervals.add(frames.value(row,"interval_ms"),false);});}
  return range;
 }
 public static String missingReason(CsvIndex source,String[] row,String key){
  if(source.column(key)<0)return "此版本记录未保存该指标";
  String status=null;if(key.equals("fps")||key.equals("frameMs"))status="frameStatus";else if(key.equals("gpuPct"))status="gpuLoadStatus";else if(key.equals("gpuMHz"))status="gpuFrequencyStatus";else if(key.equals("cpuC"))status="cpuTemperatureStatus";else if(key.equals("gpuC"))status="gpuTemperatureStatus";else if(key.equals("socC"))status="socTemperatureStatus";
  int i=status==null?-1:source.column(status);if(i>=0&&i<row.length&&!row[i].trim().isEmpty())return row[i];return "原始记录为空；未保存具体读取原因";
 }
}
