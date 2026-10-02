package com.lightframe.monitor;
import java.io.*;import java.util.*;
/** Min/max envelope for drawing only. Statistics and detail rows use the full original CSV. */
public final class ChartData{
 public String key;public double[] times,values;public long count;public double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,avg;public double end;
 // Two extrema and at most two gap markers per bucket keep the full view below 9003 points.
 private final ArrayList<Double> t=new ArrayList<>(),v=new ArrayList<>();private final int bucket;private int inBucket,rowId,minRow,maxRow,run,minRun,maxRun,lastRun=-1;private double btMin,btMax,bvMin=Double.POSITIVE_INFINITY,bvMax=Double.NEGATIVE_INFINITY,gapTime,minGapTime,maxGapTime;private boolean missing;
 private ChartData(String key,int count){this.key=key;bucket=Math.max(1,(int)((count+2249L)/2250));}
 public static ChartData[] load(CsvIndex source,String[] keys)throws IOException{return loadRows(source,keys,0,source.count);}
 public static ChartData[] load(CsvIndex source,String[] keys,double begin,double end)throws IOException{return loadRows(source,keys,source.lowerBound(begin),source.upperBound(end));}
 private static ChartData[] loadRows(CsvIndex source,String[] keys,int from,int to)throws IOException{ChartData[] a=new ChartData[keys.length];for(int i=0;i<a.length;i++)a[i]=new ChartData(keys[i],to-from);FpsSampleValidity fpsValidity=new FpsSampleValidity(source.header);int paused=source.column("paused");source.scan(from,to,(n,row)->{double time=CsvIndex.time(row);boolean p=paused>=0&&paused<row.length&&row[paused].equalsIgnoreCase("true");for(ChartData d:a){double value=p?Double.NaN:source.value(row,d.key);d.add(time,d.key.equals("fps")?fpsValidity.value(value,row):value);}});for(ChartData d:a)d.finish();return a;}
 private void add(double time,double value){int row=rowId++;end=Math.max(end,time);if(Double.isFinite(value)){missing=false;count++;avg+=(value-avg)/count;min=Math.min(min,value);max=Math.max(max,value);if(value<bvMin){bvMin=value;btMin=time;minRow=row;minRun=run;minGapTime=gapTime;}if(value>bvMax){bvMax=value;btMax=time;maxRow=row;maxRun=run;maxGapTime=gapTime;}}else if(!missing){missing=true;run++;gapTime=time;}if(++inBucket>=bucket)flush();}
 private void gapPoint(double time){if(v.isEmpty()||Double.isFinite(v.get(v.size()-1))){t.add(time);v.add(Double.NaN);}}
 // Extrema from different original valid runs must never be connected, even inside one bucket.
 private void point(double time,double value,int pointRun,double pointGapTime){if(pointRun!=lastRun&&pointRun>0)gapPoint(pointGapTime);t.add(time);v.add(value);lastRun=pointRun;}
 private void flush(){if(bvMin<=bvMax){if(minRow<=maxRow){point(btMin,bvMin,minRun,minGapTime);if(maxRow!=minRow)point(btMax,bvMax,maxRun,maxGapTime);}else{point(btMax,bvMax,maxRun,maxGapTime);point(btMin,bvMin,minRun,minGapTime);}}inBucket=0;bvMin=Double.POSITIVE_INFINITY;bvMax=Double.NEGATIVE_INFINITY;}
 private void finish(){if(inBucket>0)flush();if(missing)gapPoint(gapTime);times=new double[t.size()];values=new double[v.size()];for(int i=0;i<times.length;i++){times[i]=t.get(i);values[i]=v.get(i);}t.clear();v.clear();if(count==0)min=max=avg=Double.NaN;}
}
