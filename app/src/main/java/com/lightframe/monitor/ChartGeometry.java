package com.lightframe.monitor;
import java.util.*;
/** Pixel geometry shared by drawing and input. Text dimensions are measured, never stretched. */
public final class ChartGeometry {
 public final double width,height,padding,titleBaseline,cursorBaseline,statsTop,statsLine,plotLeft,plotRight,plotTop,plotBottom,tickBaseline;public final int statsColumns,statsRows;
 private ChartGeometry(double width,double height,double density,double textDensity,double labelWidth,double statWidth){
  this.width=width;this.height=height;padding=12*density;double available=Math.max(1,width-2*padding),gap=10*density;
  statsColumns=Math.max(1,Math.min(3,(int)Math.floor((available+gap)/(Math.max(1,statWidth)+gap))));statsRows=(3+statsColumns-1)/statsColumns;
  titleBaseline=padding+14*textDensity;cursorBaseline=titleBaseline+18*textDensity;statsTop=cursorBaseline+8*density;statsLine=16*textDensity;
  plotTop=statsTop+statsRows*statsLine+9*density;
  plotLeft=padding+Math.min(Math.max(24*density,labelWidth+7*density),Math.max(24*density,available*.36));plotRight=Math.max(plotLeft+1,width-padding-2*density);
  plotBottom=Math.max(plotTop+1,height-padding-22*textDensity);tickBaseline=plotBottom+16*textDensity;
 }
 public static ChartGeometry layout(int width,int height,float density,float textDensity,double labelWidth,double statWidth){return new ChartGeometry(Math.max(1,width),Math.max(1,height),Math.max(.1,density),Math.max(.1,textDensity),labelWidth,statWidth);}
 public static int recommendedHeight(int width,int windowHeight,float density,float textDensity,double statWidth){
  ChartGeometry header=layout(width,100,density,textDensity,0,statWidth);double bottom=12*density+22*textDensity;
  double minPlot=Math.max(116*density,6*14*textDensity),min=header.plotTop+minPlot+bottom;
  double ideal=header.plotTop+Math.max(minPlot,Math.min(280*density,width/3.3))+bottom;
  double cap=windowHeight>0?Math.max(min,windowHeight*.72):ideal;
  return (int)Math.ceil(Math.max(min,Math.min(ideal,cap)));
 }
 public double plotWidth(){return plotRight-plotLeft;}public double plotHeight(){return plotBottom-plotTop;}
 public boolean inPlot(double x,double y){return x>=plotLeft&&x<=plotRight&&y>=plotTop&&y<=plotBottom;}
 public double fraction(double x){return Math.max(0,Math.min(1,(x-plotLeft)/plotWidth()));}
 public double x(double fraction){return plotLeft+fraction*plotWidth();}
 public double y(double fraction){return plotBottom-fraction*plotHeight();}
 public double panSeconds(double deltaPixels,double span){return -deltaPixels/plotWidth()*span;}
 public static int tickCount(double plotWidth,double labelWidth,double gap){return Math.max(2,Math.min(7,(int)Math.floor((plotWidth+gap)/Math.max(1,labelWidth+gap))));}
 public static String timeLabel(double seconds,double step){
  if(!Double.isFinite(seconds))return "—";int digits=step<.001?6:step<1?3:0;double scale=Math.pow(10,digits);long total=(long)Math.round(Math.max(0,seconds)*scale),whole=total/(long)scale,fraction=total%(long)scale;
  String prefix=whole>=3600?String.format(Locale.US,"%d:%02d:%02d",whole/3600,(whole/60)%60,whole%60):String.format(Locale.US,"%d:%02d",whole/60,whole%60);
  return digits==0?prefix:prefix+String.format(Locale.US,".%0"+digits+"d",fraction);
 }
}
