package com.lightframe.monitor;

import java.io.IOException;
import java.io.Writer;

/** Reuses frame-row storage; all ten fields retain the original CSV representation. */
public final class FrameCsvWriter {
 private final StringBuilder row=new StringBuilder(256);
 private char[] chars=new char[256];
 public void write(Writer output,double elapsed,long present,double intervalMs,long segment,
                   String layer,long periodNs,String source)throws IOException{
  row.setLength(0);row.append(elapsed).append(',').append(present).append(',');
  if(Double.isFinite(intervalMs))row.append(intervalMs);
  row.append(",,,").append(segment).append(',');cell(layer);row.append(',');
  if(periodNs>0)row.append(periodNs);row.append(',');
  if(periodNs>0)row.append(FrameBudget.hz(periodNs));row.append(',');cell(source);row.append('\n');
  int length=row.length();if(chars.length<length)chars=new char[Math.max(length,chars.length*2)];
  row.getChars(0,length,chars,0);output.write(chars,0,length);
 }
 private void cell(String value){
  if(value==null)return;boolean quote=value.indexOf(',')>=0||value.indexOf('"')>=0;
  if(quote)row.append('"');
  for(int i=0;i<value.length();i++){
   char c=value.charAt(i);if(c=='\n'||c=='\r')c=' ';
   if(c=='"')row.append('"');row.append(c);
  }
  if(quote)row.append('"');
 }
}
