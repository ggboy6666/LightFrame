package com.lightframe.monitor;

/** Treat an old zero as missing only when that same raw row proves its window was unavailable. */
public final class FpsSampleValidity {
 private final int available,ready,stale,age,layer;
 public FpsSampleValidity(String[] header){available=column(header,"frameAvailable");ready=column(header,"frameWindowReady");stale=column(header,"frameWindowStale");age=column(header,"frameDataAgeMs");layer=column(header,"layer");}
 private static int column(String[] header,String key){for(int i=0;i<header.length;i++)if(key.equals(header[i]))return i;return -1;}
 private static String cell(String[] row,int i){return i>=0&&i<row.length?row[i].trim():"";}
 public boolean invalidZero(double value,String[] row){
  if(value!=0)return false;
  if("false".equalsIgnoreCase(cell(row,available))||"false".equalsIgnoreCase(cell(row,ready))||"true".equalsIgnoreCase(cell(row,stale)))return true;
  if(layer>=0&&layer<row.length&&cell(row,layer).isEmpty())return true;
  try{return Double.parseDouble(cell(row,age))>=1000;}catch(NumberFormatException ignored){return false;}
 }
 public double value(double raw,String[] row){return invalidZero(raw,row)?Double.NaN:raw;}
 public static String reason(){return "旧记录的 0 FPS 无有效呈现窗口，未计入 FPS 统计；逐帧卡顿数据保留";}
}
