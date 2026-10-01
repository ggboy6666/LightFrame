package com.lightframe.monitor;
import android.content.*;import android.graphics.*;import android.view.*;
public final class HistoryChart extends View{
 private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();public final ChartData data;public final HistoryViewport viewport;
 private double cursor=Double.NaN,cursorValue=Double.NaN;private float downX,downY,lastX;private boolean moved,scaling,viewportDirty;private long lastTap;private final ScaleGestureDetector scale;private final int slop;
 public java.util.function.DoubleConsumer onSelect;public Runnable onViewportChanged,onViewportFinished;
 public HistoryChart(Context c,ChartData d){this(c,d,new HistoryViewport(d.end));}
 public HistoryChart(Context c,ChartData d,HistoryViewport shared){super(c);data=d;viewport=shared;slop=ViewConfiguration.get(c).getScaledTouchSlop();setClickable(true);setContentDescription(Config.label(d.key)+"历史曲线。点选查看全部原始值，双指缩放，左右拖动，双击查看全部。");scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
  public boolean onScaleBegin(ScaleGestureDetector detector){scaling=moved=true;intercept(true);return true;}
  public boolean onScale(ScaleGestureDetector detector){double fraction=(detector.getFocusX()/Math.max(1,getWidth())-10/450d)/(430/450d);viewport.zoom(detector.getScaleFactor(),fraction);changed();return true;}
 });}
 public void select(double t,double value){cursor=t;cursorValue=value;invalidate();}
 private void intercept(boolean value){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
 private void changed(){viewportDirty=true;invalidate();if(onViewportChanged!=null)onViewportChanged.run();}
 private void finished(){if(onViewportFinished!=null)onViewportFinished.run();}
 @Override protected void onDraw(Canvas c){render(c,getWidth(),getHeight());}
 public void render(Canvas c,int width,int height){double begin=viewport.begin,end=viewport.end;c.save();c.scale(width/450f,height/190f);c.clipRect(0,0,450,190);p.setStyle(Paint.Style.FILL);p.setColor(0xFFE2DFED);c.drawRoundRect(0,0,450,190,6,6,p);p.setTypeface(Typeface.DEFAULT);p.setTextSize(12);p.setColor(0xFF676D9C);p.setTextAlign(Paint.Align.LEFT);c.drawText(Config.label(data.key)+" ("+Config.unit(data.key)+")",10,20,p);p.setColor(0xFFD3D0E1);c.drawLine(8,29,442,29,p);p.setTextSize(10);p.setColor(0xFF58566A);c.drawText("区间 min "+Numbers.display(data.min,1),10,44,p);p.setTextAlign(Paint.Align.CENTER);c.drawText("avg "+Numbers.display(data.avg,1),225,44,p);p.setTextAlign(Paint.Align.RIGHT);c.drawText("max "+Numbers.display(data.max,1),440,44,p);p.setTextAlign(Paint.Align.LEFT);
  for(int i=0;i<=4;i++){p.setColor(0xFFD8D4E4);c.drawLine(10,52+i*29,440,52+i*29,p);}double low=data.min,high=data.max;if(high==low){low-=1;high+=1;}
  if(!Double.isFinite(low)){p.setColor(0xFF767185);p.setTextAlign(Paint.Align.CENTER);c.drawText("此区间未取得指标；详见采样原因",225,104,p);p.setTextAlign(Paint.Align.LEFT);}else{double padding=(high-low)*.08;low-=padding;high+=padding;c.save();c.clipRect(10,50,440,170);path.reset();boolean pen=false;for(int i=0;i<data.times.length;i++){double t=data.times[i],v=data.values[i];if(!Double.isFinite(v)||!Double.isFinite(t)){pen=false;continue;}float x=(float)(10+(t-begin)/(end-begin)*430),y=(float)(168-(v-low)/(high-low)*114);if(pen)path.lineTo(x,y);else{path.moveTo(x,y);pen=true;}p.setColor(0xFF65709D);c.drawCircle(x,y,1.1f,p);}p.setColor(0xFF65709D);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.1f);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);c.restore();}
  if(Double.isFinite(cursor)&&cursor>=begin&&cursor<=end){float x=(float)(10+(cursor-begin)/(end-begin)*430);p.setColor(0xFF8E8AC2);p.setStrokeWidth(.8f);c.drawLine(x,50,x,170,p);if(Double.isFinite(cursorValue)&&Double.isFinite(low)){float y=(float)(168-(cursorValue-low)/(high-low)*114);c.drawCircle(x,y,3,p);}}
  p.setColor(0xFF6A6478);p.setTextSize(9);for(int i=0;i<5;i++){p.setTextAlign(i==0?Paint.Align.LEFT:i==4?Paint.Align.RIGHT:Paint.Align.CENTER);c.drawText(Config.elapsed(begin+(end-begin)*i/4),10+430*i/4f,183,p);}p.setTextAlign(Paint.Align.LEFT);if(Double.isFinite(cursor)){p.setColor(0xFF656A9D);p.setTextSize(10);p.setTextAlign(Paint.Align.RIGHT);c.drawText(Config.elapsed(cursor)+"  "+Config.format(data.key,cursorValue),440,20,p);p.setTextAlign(Paint.Align.LEFT);}c.restore();
 }
 @Override public boolean onTouchEvent(MotionEvent e){
  if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=lastX=e.getX();downY=e.getY();moved=scaling=viewportDirty=false;intercept(true);}
  scale.onTouchEvent(e);
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:return true;
   case MotionEvent.ACTION_POINTER_DOWN:scaling=moved=true;intercept(true);return true;
   case MotionEvent.ACTION_MOVE:
    if(!scaling&&e.getPointerCount()==1){float dx=e.getX()-downX,dy=e.getY()-downY;if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){moved=true;intercept(false);return true;}
     if(Math.abs(dx)>slop||moved){moved=true;intercept(true);viewport.pan(-(e.getX()-lastX)/Math.max(1,getWidth()*430/450f)*(viewport.end-viewport.begin));changed();}}
    lastX=e.getX();return true;
   case MotionEvent.ACTION_UP:intercept(false);if(!moved&&!scaling){long now=android.os.SystemClock.uptimeMillis();if(now-lastTap<320){viewport.reset();changed();finished();lastTap=0;}else{lastTap=now;double f=Math.max(0,Math.min(1,(e.getX()/Math.max(1,getWidth())-10/450d)/(430/450d)));if(onSelect!=null)onSelect.accept(viewport.begin+f*(viewport.end-viewport.begin));}performClick();}else if(viewportDirty)finished();return true;
   case MotionEvent.ACTION_CANCEL:intercept(false);if(viewportDirty)finished();return true;
   default:return true;
  }
 }
 @Override public boolean performClick(){super.performClick();return true;}
}
