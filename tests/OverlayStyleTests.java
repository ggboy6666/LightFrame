package com.lightframe.monitor;

public final class OverlayStyleTests {
 private static int checks;
 private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  check(OverlayStyle.argb(0x123456,0)==0x00123456,"zero opacity preserves RGB");
  check(OverlayStyle.argb(0x123456,100)==0xFF123456,"fully opaque");
  check(OverlayStyle.argb(0x123456,50)==0x80123456,"half opacity rounded");
  check(OverlayStyle.argb(0xA5123456,-10)==0x00123456,"input alpha masked and opacity bounded");
  check(OverlayStyle.argb(0x123456,110)==0xFF123456,"opacity upper bound");
  OverlayStyle first=new OverlayStyle(0x102030,25,0xAABBCC,80);
  OverlayStyle next=new OverlayStyle(0x405060,25,0xAABBCC,80);
  check(first.textArgb==next.textArgb,"background choice leaves text unchanged");
  check((first.backgroundArgb&0xFFFFFF)==0x102030,"background RGB");
  check((first.textArgb>>>24)==204,"text independent opacity");
  check(OverlayStyle.parseRgb(" #aBcD09 ")==0xABCD09,"RGB user input");
  check(OverlayStyle.formatRgb(0xABCD09).equals("#ABCD09"),"RGB canonical form");
  for(String invalid:new String[]{"","#","FFF","#12345678","rgb(0,0,0)","12345G",null}){
   boolean rejected=false;try{OverlayStyle.parseRgb(invalid);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid RGB "+invalid);
  }
  int previous=-1;for(int opacity=0;opacity<=100;opacity++){
   int alpha=OverlayStyle.argb(0,opacity)>>>24;check(alpha>=previous,"alpha monotonic");check(alpha>=0&&alpha<=255,"alpha byte");previous=alpha;
  }
  System.out.println("OverlayStyleTests: "+checks+" checks passed");
 }
}
