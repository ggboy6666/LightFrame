package com.lightframe.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the read-only thermalservice dump; values are degrees Celsius. */
public final class ThermalSnapshot {
 public static final int TYPE_UNKNOWN=-1, TYPE_CPU=0, TYPE_GPU=1, TYPE_BATTERY=2,
   TYPE_SKIN=3, TYPE_SOC=13;
 private static final Pattern READING=Pattern.compile(
   "\\bTemperature\\s*\\{\\s*mValue\\s*=\\s*([^,{}]+)\\s*,\\s*mType\\s*=\\s*([+-]?\\d+)"
   +"\\s*,\\s*mName\\s*=\\s*(.*?)\\s*,\\s*mStatus\\s*=\\s*([+-]?\\d+)\\s*\\}");
 private static final Pattern GLOBAL_STATUS=Pattern.compile("(?m)^\\s*Thermal Status:\\s*([+-]?\\d+)\\s*$");
 private static final Pattern HAL_READY=Pattern.compile("(?m)^\\s*HAL Ready:\\s*(true|false)\\s*$",Pattern.CASE_INSENSITIVE);
 private static final Pattern OVERRIDE=Pattern.compile("(?m)^\\s*IsStatusOverride:\\s*(true|false)\\s*$",Pattern.CASE_INSENSITIVE);
 private static final Pattern NON_CHIP_NAME=Pattern.compile(
   "(?:battery|batt|bcl|ibat|vbat|skin|surface|shell|case|usb|ambient)",Pattern.CASE_INSENSITIVE);
 private static final Pattern CPU_NAME=Pattern.compile("(?:cpu\\d*(?:[-_].*)?|cpu[-_]?thermal)",Pattern.CASE_INSENSITIVE);
 private static final Pattern GPU_NAME=Pattern.compile("(?:gpu\\d*(?:[-_].*)?|mali(?:[-_].*)?)",Pattern.CASE_INSENSITIVE);
 private static final Pattern SOC_NAME=Pattern.compile("(?:soc(?:[-_].*)?|ap(?:[-_].*)?|mtktsap(?:[-_].*)?)",Pattern.CASE_INSENSITIVE);

 public static final class Sensor {
  public final int type, status;
  public final String name, source, category;
  /** Raw HAL value; types 6/7/8 represent battery voltage/current/percentage, not Celsius. */
  public final double value;
  private Sensor(int type,String name,double value,int status,String source) {
   this.type=type;this.name=name;this.value=value;this.status=status;this.source=source;
   this.category=category(type,name);
  }
 }

 public final double cpuC,gpuC,socC,batteryC,skinC;
 public final int thermalStatus;
 public final Boolean halReady,statusOverridden;
 public final boolean permissionDenied;
 public final String source,status;
 /** Selected section only; the current HAL section always takes precedence over cached data. */
 public final List<Sensor> sensors;

 private ThermalSnapshot(List<Sensor> selected,String source,int thermalStatus,Boolean halReady,
   Boolean statusOverridden,boolean permissionDenied,boolean emptyDump) {
  this.sensors=Collections.unmodifiableList(new ArrayList<>(selected));this.source=source;
  this.thermalStatus=thermalStatus;this.halReady=halReady;this.statusOverridden=statusOverridden;
  this.permissionDenied=permissionDenied;
  boolean usable=!permissionDenied&&!Boolean.FALSE.equals(halReady);
  cpuC=usable?maximum(selected,"cpu"):Double.NaN;
  gpuC=usable?maximum(selected,"gpu"):Double.NaN;
  socC=usable?maximum(selected,"soc"):Double.NaN;
  batteryC=usable?maximum(selected,"battery"):Double.NaN;
  skinC=usable?maximum(selected,"skin"):Double.NaN;
  if(permissionDenied)status="thermalservice 权限拒绝";
  else if(emptyDump)status="thermalservice 未返回数据";
  else if(Boolean.FALSE.equals(halReady))status="thermalservice HAL 未就绪；缓存未用作当前温度";
  else if(selected.isEmpty())status="thermalservice 未提供可解析传感器";
  else if(!Double.isFinite(cpuC)&&!Double.isFinite(gpuC)&&!Double.isFinite(socC))
   status="thermalservice 未报告 CPU/GPU/SoC 温度";
  else status=source.equals("current")?"thermalservice 当前 HAL 读数":"thermalservice 缓存读数";
 }

 public static ThermalSnapshot parse(String text) {
  if(text==null)text="";
  String lower=text.toLowerCase(Locale.ROOT);
  boolean denied=lower.contains("permission denial")||lower.contains("permission denied")
    ||lower.contains("securityexception")||lower.contains("not allowed to dump");
  List<Sensor> cached=new ArrayList<>(),current=new ArrayList<>();
  String section="";boolean hasCurrent=false;
  for(String line:text.split("\\r?\\n")) {
   String trimmed=line.trim();
   if(trimmed.equals("Cached temperatures:")){section="cached";continue;}
   if(trimmed.equals("Current temperatures from HAL:")){section="current";hasCurrent=true;continue;}
   if(trimmed.endsWith(":")){section="";continue;}
   if(section.isEmpty())continue;
   Matcher m=READING.matcher(trimmed);
   while(m.find())try {
    int type=Integer.parseInt(m.group(2)),status=Integer.parseInt(m.group(4));
    String name=m.group(3).trim();
    if(name.isEmpty()||status<0||status>6)continue;
    double value=Double.parseDouble(m.group(1).trim());
    Sensor sensor=new Sensor(type,name,value,status,section);
    (section.equals("current")?current:cached).add(sensor);
   }catch(NumberFormatException ignored){}
  }
  int status=-1;Matcher global=GLOBAL_STATUS.matcher(text);
  if(global.find())try {int s=Integer.parseInt(global.group(1));if(s>=0&&s<=6)status=s;}catch(NumberFormatException ignored){}
  return new ThermalSnapshot(hasCurrent?current:cached,hasCurrent?"current":cached.isEmpty()?"none":"cached",
    status,booleanField(HAL_READY,text),booleanField(OVERRIDE,text),denied,text.trim().isEmpty());
 }

 private static Boolean booleanField(Pattern pattern,String text) {
  Matcher m=pattern.matcher(text);return m.find()?Boolean.valueOf(m.group(1)):null;
 }
 private static String category(int type,String name) {
  switch(type){case TYPE_CPU:return "cpu";case TYPE_GPU:return "gpu";case TYPE_SOC:return "soc";
   case TYPE_BATTERY:return "battery";case TYPE_SKIN:return "skin";default:break;}
  // Never use names to reinterpret BCL_PERCENTAGE=8 (often named "soc") or any known non-chip type.
  if(type!=TYPE_UNKNOWN||NON_CHIP_NAME.matcher(name).find())return "other";
  if(CPU_NAME.matcher(name).matches())return "cpu";
  if(GPU_NAME.matcher(name).matches())return "gpu";
  if(SOC_NAME.matcher(name).matches())return "soc";
  return "other";
 }
 private static double maximum(List<Sensor> sensors,String category) {
  double max=Double.NaN;
  for(Sensor sensor:sensors)if(sensor.category.equals(category)&&Double.isFinite(sensor.value)
    &&sensor.value>=-30&&sensor.value<=180)max=Double.isNaN(max)?sensor.value:Math.max(max,sensor.value);
  return max;
 }
}
