package com.lightframe.monitor;
public final class SamplingIntervals {
 private SamplingIntervals(){}
 public static int parse(String text,int minimum,int maximum){
  if(text==null||!text.trim().matches("[0-9]+"))throw new IllegalArgumentException("请输入整数毫秒");
  try{int value=Integer.parseInt(text.trim());if(value<minimum||value>maximum)throw new IllegalArgumentException("请输入 "+minimum+" — "+maximum+" ms");return value;}catch(NumberFormatException e){throw new IllegalArgumentException("请输入 "+minimum+" — "+maximum+" ms");}
 }
}
