package com.lightframe.monitor;
import java.io.*;import java.util.*;
/** Min/max envelope for drawing only. Statistics and detail rows use the full original CSV. */
public final class ChartData{
 public String key;public double[] times,values;public long count;public double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,avg;public double end;
 private final ArrayList<Double> t=new ArrayList<>(),v=new ArrayList<>();private final int bucket;private int inBucket;private double btMin,btMax,bvMin=Double.POSITIVE_INFINITY,bvMax=Double.NEGATIVE_INFINITY;private boolean gap;
 private ChartData(String key,int count){this.key=key;bucket=Math.max(1,(count+2999)/3000);}
 public static ChartData[] load(CsvIndex source,String[] keys)throws IOException{return loadRows(source,keys,0,source.count);}
 public static ChartData[] load(CsvIndex source,String[] keys,double begin,double end)throws IOException{return loadRows(source,keys,source.lowerBound(begin),source.upperBound(end));}
 private static ChartData[] loadRows(CsvIndex source,String[] keys,int from,int to)throws IOException{ChartData[] a=new ChartData[keys.length];for(int i=0;i<a.length;i++)a[i]=new ChartData(keys[i],to-from);int paused=source.column("paused");source.scan(from,to,(n,row)->{double time=CsvIndex.time(row);boolean p=paused>=0&&paused<row.length&&row[paused].equalsIgnoreCase("true");for(ChartData d:a)d.add(time,p?Double.NaN:source.value(row,d.key));});for(ChartData d:a)d.finish();return a;}
 private void add(double time,double value){end=Math.max(end,time);if(Double.isFinite(value)){count++;avg+=(value-avg)/count;min=Math.min(min,value);max=Math.max(max,value);if(value<bvMin){bvMin=value;btMin=time;}if(value>bvMax){bvMax=value;btMax=time;}}else gap=true;if(++inBucket>=bucket)flush(time);}
 private void point(double time,double value){t.add(time);v.add(value);}
 private void flush(double time){if(bvMin<=bvMax){if(btMin<=btMax){point(btMin,bvMin);if(btMax!=btMin)point(btMax,bvMax);}else{point(btMax,bvMax);point(btMin,bvMin);}}if(gap)point(time,Double.NaN);inBucket=0;bvMin=Double.POSITIVE_INFINITY;bvMax=Double.NEGATIVE_INFINITY;gap=false;}
 private void finish(){if(inBucket>0)flush(end);times=new double[t.size()];values=new double[v.size()];for(int i=0;i<times.length;i++){times[i]=t.get(i);values[i]=v.get(i);}t.clear();v.clear();if(count==0)min=max=avg=Double.NaN;}
}
