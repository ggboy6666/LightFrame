package com.lightframe.monitor;
import java.text.*;
import java.util.*;
/** Explicit elapsed/local time mode prevents ambiguous clock inputs. */
public final class HistoryTime {
 private HistoryTime(){}
 public static double parse(String input,boolean localTime,long startedUnixMs){
  String s=input.trim();if(s.isEmpty())throw new IllegalArgumentException("请输入时间");
  if(!localTime){String[] a=s.split(":",-1);if(a.length>3)throw new IllegalArgumentException("经过时间格式：秒 / 分:秒 / 时:分:秒");
   double result=0;for(int i=0;i<a.length;i++){double n;try{n=Double.parseDouble(a[i]);}catch(Exception e){throw new IllegalArgumentException("时间格式无效");}
    if(!Double.isFinite(n)||n<0||(i>0&&n>=60)||(i<a.length-1&&n!=Math.floor(n)))throw new IllegalArgumentException("分钟和秒需在 0—59 之间");result=result*60+n;
   }return result;
  }
  if(startedUnixMs<=0)throw new IllegalArgumentException("记录缺少开始时间，请使用录制经过时间");
  boolean onlyClock=s.indexOf(' ')==-1&&s.indexOf('T')==-1;
  if(onlyClock)s=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(startedUnixMs))+" "+s;
  s=s.replace('T',' ');int dot=s.lastIndexOf('.');if(dot>=0){String fraction=s.substring(dot+1);if(!fraction.matches("[0-9]{1,3}"))throw new IllegalArgumentException("当地时间最多输入 3 位毫秒");s=s.substring(0,dot+1)+(fraction+"000").substring(0,3);}Date parsed=null;
  for(String fmt:new String[]{"yyyy-MM-dd HH:mm:ss.SSS","yyyy-MM-dd HH:mm:ss"}){
   SimpleDateFormat f=new SimpleDateFormat(fmt,Locale.US);f.setLenient(false);ParsePosition pos=new ParsePosition(0);Date d=f.parse(s,pos);if(d!=null&&pos.getIndex()==s.length()){parsed=d;break;}
  }
  if(parsed==null)throw new IllegalArgumentException("当地时间格式：yyyy-MM-dd HH:mm:ss.SSS 或 HH:mm:ss.SSS");
  long millis=parsed.getTime();if(onlyClock&&millis<startedUnixMs){Calendar c=Calendar.getInstance();c.setTimeInMillis(millis);c.add(Calendar.DATE,1);millis=c.getTimeInMillis();}
  return (millis-startedUnixMs)/1000d;
 }
 /** Millisecond text may round either side of an actual CSV endpoint. Snap only within 0.5ms. */
 public static double normalizeBoundary(double value,double fullEnd){
  if(!Double.isFinite(value)||!Double.isFinite(fullEnd)||fullEnd<0)throw new IllegalArgumentException("时间边界无效");
  double tolerance=.0005+Math.ulp(fullEnd),fromStart=Math.abs(value),fromEnd=Math.abs(fullEnd-value);
  if(value < -tolerance||value>fullEnd+tolerance)throw new IllegalArgumentException("请输入记录范围内的时间：0 — "+elapsed(fullEnd));
  if(fromStart<=tolerance&&fromStart<=fromEnd)return 0;
  if(fromEnd<=tolerance)return fullEnd;
  return Math.max(0,Math.min(fullEnd,value));
 }
 public static String clock(long start,double elapsed){return start<=0?"开始时间未记录":new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.getDefault()).format(new Date(start+Math.round(elapsed*1000)));}
 public static String elapsed(double seconds){if(!Double.isFinite(seconds))return "—";long ms=Math.round(Math.max(0,seconds)*1000);return String.format(Locale.US,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
}
