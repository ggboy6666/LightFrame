package com.lightframe.monitor;

/** The background and text have independent RGB colors and opacity. */
public final class OverlayStyle {
 public static final int DEFAULT_BACKGROUND_RGB=0x2A2937,DEFAULT_TEXT_RGB=0xFFFFFF;
 public final int backgroundArgb,textArgb;
 public OverlayStyle(int backgroundRgb,int backgroundAlpha,int textRgb,int textAlpha){
  backgroundArgb=argb(backgroundRgb,backgroundAlpha);textArgb=argb(textRgb,textAlpha);
 }
 public static int argb(int rgb,int opacityPercent){
  int bounded=Math.max(0,Math.min(100,opacityPercent));
  return ((int)Math.round(bounded*255d/100)<<24)|(rgb&0xFFFFFF);
 }
 public static int parseRgb(String hex){
  String value=hex==null?"":hex.trim();if(value.startsWith("#"))value=value.substring(1);
  if(!value.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("请输入六位 RGB 色值，例如 #FFFFFF");
  return Integer.parseInt(value,16);
 }
 public static String formatRgb(int rgb){return String.format(java.util.Locale.US,"#%06X",rgb&0xFFFFFF);}
}
