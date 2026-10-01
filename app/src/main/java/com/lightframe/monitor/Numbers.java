package com.lightframe.monitor;

import java.util.*;
import java.util.regex.*;

/** Unit-aware pure parsers. Missing and unreadable values are NaN, never invented zero. */
public final class Numbers {
 public static double first(String s) { if(s==null)return Double.NaN; Matcher m=Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?").matcher(s);try{return m.find()?Double.parseDouble(m.group()):Double.NaN;}catch(Exception e){return Double.NaN;} }
 public static double[] tokens(String s) { if(s==null)return new double[0];String[] t=s.trim().split("\\s+"); double[] a=new double[t.length];try{for(int i=0;i<a.length;i++)a[i]=Double.parseDouble(t[i]);return a;}catch(Exception e){return new double[0];} }
 public static double percent(String s) {double d=first(s);return d>=0&&d<=100?d:Double.NaN;}
 public static double busy(String s) {double[] a=tokens(s);return a.length>=2&&a[1]>0&&a[0]>=0&&a[0]<=a[1]?100*a[0]/a[1]:Double.NaN;}
 public static double temp(String s) {double d=first(s);if(Math.abs(d)>1000)d/=1000;return d>=-30&&d<=180?d:Double.NaN;}
 public static double gedFreq(String s) { if(s==null)return Double.NaN;Matcher m=Pattern.compile("(?i)(?:current_?freq(?:uency)?|freq(?:uency)?)\\s*[:=]\\s*(\\d+)").matcher(s);if(m.find())return Double.parseDouble(m.group(1))/1000;double[] a=tokens(s);return a.length==1?a[0]/1000:a.length==2?a[1]/1000:Double.NaN; }
 public static double mtkFreq(String s) {if(s==null)return Double.NaN;String[] lines=s.split("\\n");String[] ranks={"STACK OPP","GPU OPP","current_freq","cur_freq","frequency"};for(String r:ranks)for(String l:lines)if(l.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT))){Matcher m=Pattern.compile("(?i)(?:current_freq|cur_freq|freq(?:uency)?(?:\\s*\\(kHz\\))?)\\s*[:=]\\s*(\\d+)").matcher(l);if(m.find())return Double.parseDouble(m.group(1))/1000;}return Double.NaN;}
 public static long[] cpu(String s) {if(s==null)return null;String l=s.split("\\n")[0];String[] a=l.trim().split("\\s+");if(a.length<9||!a[0].equals("cpu"))return null;try{long total=0;for(int i=1;i<=8;i++)total+=Long.parseLong(a[i]);return new long[]{total,Long.parseLong(a[4])+Long.parseLong(a[5])};}catch(Exception e){return null;}}
 public static double cpuPct(long[] before,long[] now) {if(before==null||now==null)return Double.NaN;long t=now[0]-before[0],idle=now[1]-before[1];return t>0&&idle>=0&&idle<=t?100d*(t-idle)/t:Double.NaN;}
 public static double mem(String s,String key) {if(s==null)return Double.NaN;for(String l:s.split("\\n"))if(l.startsWith(key+":"))return first(l.substring(key.length()+1))/1024;return Double.NaN;}
 public static long[] net(String s) {if(s==null)return null;long rx=0,tx=0;try{for(String l:s.split("\\n")){int p=l.indexOf(':');if(p<0||l.substring(0,p).trim().equals("lo"))continue;String[] a=l.substring(p+1).trim().split("\\s+");if(a.length>=16){rx+=Long.parseLong(a[0]);tx+=Long.parseLong(a[8]);}}return new long[]{rx,tx};}catch(Exception e){return null;}}
 public static long[] present(String s) {if(s==null)return new long[0];TreeSet<Long> t=new TreeSet<>();String[] lines=s.split("\\n");for(int i=1;i<lines.length;i++){String[] a=lines[i].trim().split("\\s+");try{if(a.length>=3){long n=Long.parseLong(a[1]);if(n>0&&n<Long.MAX_VALUE)t.add(n);}}catch(Exception ignored){}}long[] out=new long[t.size()];int i=0;for(Long v:t)out[i++]=v;return out;}
 public static String display(double v,int digits) {return Double.isNaN(v)||Double.isInfinite(v)?"—":String.format(Locale.US,"%."+digits+"f",v);}
 public static boolean safeNode(String p) {return p!=null&&p.length()<500&&(p.startsWith("/sys/")||p.startsWith("/proc/"))&&!p.contains("..")&&!p.contains("\n");}
}
