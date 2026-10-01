package com.lightframe.monitor;
import java.util.*;
/** Explicit metric ranges; values remain untouched and anomalous outliers are reported. */
public final class ChartAxis {
 public final double min,max,step;public final double[] ticks;public final boolean outOfRange;
 private ChartAxis(double min,double max,double step,double rawMin,double rawMax){
  this.min=min;this.max=max;this.step=step;outOfRange=Double.isFinite(rawMin)&&rawMin<min||Double.isFinite(rawMax)&&rawMax>max;
  int intervals=Math.max(1,Math.min(10,(int)Math.round((max-min)/step)));ticks=new double[intervals+1];for(int i=0;i<ticks.length;i++)ticks[i]=i==intervals?max:min+i*step;
 }
 public static ChartAxis of(String key,double rawMin,double rawMax){
  if(key.equals("cpuPct")||key.equals("gpuPct")||key.equals("batteryPct"))return new ChartAxis(0,100,25,rawMin,rawMax);
  boolean fps=key.equals("fps"),zero=fps||key.equals("monitorCpuPct")||key.endsWith("MHz")||key.endsWith("MB")||key.equals("frameMs")||key.equals("interval_ms")||key.endsWith("KBs")||key.equals("voltageMV")||key.equals("powerW")||key.equals("currentMA")||key.endsWith("AgeMs")||key.equals("cycleCostMs");
  double low=Double.isFinite(rawMin)?rawMin:0,high=Double.isFinite(rawMax)?rawMax:fps?60:1;
  if(low>high){double swap=low;low=high;high=swap;}
  if(fps||zero&&low>=0){low=0;high=high>0?high*1.08:fps?60:1;}
  else if(zero&&high<=0){high=0;low=low<0?low*1.08:-1;}
  else{double pad=(high-low)*.08;if(pad<=0)pad=Math.max(Math.abs(high)*.05,1);low-=pad;high+=pad;}
  if(!Double.isFinite(low)||!Double.isFinite(high)||!(high>low)){low=0;high=1;}
  double step=nice((high-low)/4),minimum=Math.floor(low/step)*step,maximum=Math.ceil(high/step)*step;
  if(fps)minimum=0;
  if(!(maximum>minimum)||!Double.isFinite(maximum-minimum))return new ChartAxis(0,1,.25,rawMin,rawMax);
  return new ChartAxis(minimum,maximum,step,rawMin,rawMax);
 }
 private static double nice(double value){double power=Math.pow(10,Math.floor(Math.log10(value))),normalized=value/power;double chosen=normalized<=1?1:normalized<=2?2:normalized<=2.5?2.5:normalized<=5?5:10;return chosen*power;}
 public double fraction(double value){return (value-min)/(max-min);}
 public String label(double value){double absolute=Math.abs(value);if(absolute>=1000000||absolute>0&&absolute<.001)return String.format(Locale.US,"%.2g",value);int digits=step>=1?0:Math.min(5,Math.max(1,(int)Math.ceil(-Math.log10(step))+1));if(step==2.5||step==25||step==250)digits=step==2.5?1:0;String s=String.format(Locale.US,"%."+digits+"f",value==0?0:value);if(s.indexOf('.')>=0){while(s.endsWith("0"))s=s.substring(0,s.length()-1);if(s.endsWith("."))s=s.substring(0,s.length()-1);}return s;}
}
