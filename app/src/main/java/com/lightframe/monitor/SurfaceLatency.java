package com.lightframe.monitor;

import java.util.Arrays;

/** Parse a complete SurfaceFlinger latency ring without per-row regex or boxed sets. */
public final class SurfaceLatency {
 private SurfaceLatency(){}
 public static long[] present(String raw){
  if(raw==null)return new long[0];
  int header=raw.indexOf('\n');if(header<0)return new long[0];
  long[] values=new long[128];int count=0,begin=header+1;
  while(begin<raw.length()){
   int end=raw.indexOf('\n',begin);if(end<0)end=raw.length();
   int left=begin,right=end;
   // Match String.trim() and the old parser's ASCII whitespace delimiters.
   while(left<right&&raw.charAt(left)<=32)left++;
   while(right>left&&raw.charAt(right-1)<=32)right--;
   int cursor=left;
   while(cursor<right&&!space(raw.charAt(cursor)))cursor++;
   while(cursor<right&&space(raw.charAt(cursor)))cursor++;
   int second=cursor;
   while(cursor<right&&!space(raw.charAt(cursor)))cursor++;
   int secondEnd=cursor;
   while(cursor<right&&space(raw.charAt(cursor)))cursor++;
   if(cursor<right){
    long value=positiveLong(raw,second,secondEnd);
    if(value>0){
     if(count==values.length)values=Arrays.copyOf(values,values.length*2);
     values[count++]=value;
    }
   }
   begin=end+1;
  }
  Arrays.sort(values,0,count);int unique=0;
  for(int i=0;i<count;i++)if(unique==0||values[i]!=values[unique-1])values[unique++]=values[i];
  return Arrays.copyOf(values,unique);
 }
 private static boolean space(char c){return c==' '||c=='\t'||c=='\r'||c=='\n'||c=='\f'||c=='\u000b';}
 private static long positiveLong(String raw,int begin,int end){
  if(begin>=end)return 0;
  if(raw.charAt(begin)=='+')begin++;
  if(begin>=end)return 0;
  long value=0;
  for(int i=begin;i<end;i++){
   // Long.parseLong accepts decimal Unicode digits too; preserve that behavior.
   int digit=Character.digit(raw.charAt(i),10);if(digit<0)return 0;
   if(value>(Long.MAX_VALUE-digit)/10)return 0;
   value=value*10+digit;
  }
  return value<Long.MAX_VALUE?value:0;
 }
}
