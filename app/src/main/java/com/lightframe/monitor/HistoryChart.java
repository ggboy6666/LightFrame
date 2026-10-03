package com.lightframe.monitor;
import android.content.*;import android.graphics.*;import android.text.TextUtils;import android.text.TextPaint;import android.view.*;
/** Actual-pixel renderer: scalable text and strokes never inherit an anisotropic Canvas transform. */
public final class HistoryChart extends View{
 private final TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path(),marker=new Path();public final ChartData data;public final HistoryViewport viewport;private final ChartAxis axis;
 private double cursor=Double.NaN,cursorValue=Double.NaN;private float downX,downY,lastX,lastFocus,panCenter,lastTapX,lastTapY;private boolean moved,scaling,viewportDirty,vertical;private long lastTap,lastSelection;private final ScaleGestureDetector scale;private final int slop;private ThemeColors colors=new ThemeColors(false);
 public java.util.function.DoubleConsumer onSelect,onScrub;public Runnable onViewportChanged,onViewportFinished;
 public HistoryChart(Context c,ChartData d){this(c,d,new HistoryViewport(d.end));}
 public HistoryChart(Context c,ChartData d,HistoryViewport shared){super(c);data=d;viewport=shared;axis=ChartAxis.of(d.key,d.min,d.max);slop=ViewConfiguration.get(c).getScaledTouchSlop();setClickable(true);setContentDescription(Config.label(d.key)+"历史曲线。纵轴 "+axis.label(axis.min)+" 至 "+axis.label(axis.max)+" "+Config.unit(d.key)+"。单指滑动查看原始值，双指缩放或平移。");scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
  public boolean onScaleBegin(ScaleGestureDetector detector){scaling=moved=true;lastFocus=detector.getFocusX();intercept(true);return true;}
  public boolean onScale(ScaleGestureDetector detector){ChartGeometry g=viewGeometry();double b=viewport.begin,e=viewport.end;lastFocus=detector.getFocusX();viewport.zoom(detector.getScaleFactor(),g.fraction(detector.getFocusX()));if(b!=viewport.begin||e!=viewport.end)changed();return true;}
 });}
 private float density(){return getResources().getDisplayMetrics().density;}private float textDensity(){return getResources().getDisplayMetrics().scaledDensity;}
 private String[] stats(){return new String[]{"最低 "+Numbers.display(data.min,1),"平均 "+Numbers.display(data.avg,1),"最高 "+Numbers.display(data.max,1)};}
 private double statsWidth(float textDensity){p.setTextSize(11*textDensity);double max=0;for(String s:stats())max=Math.max(max,p.measureText(s));return max;}
 private ChartGeometry geometry(int width,int height,float density,float textDensity){p.setTypeface(Typeface.DEFAULT);p.setTextSize(10.5f*textDensity);double labels=0;for(double tick:axis.ticks)labels=Math.max(labels,p.measureText(axis.label(tick)));return ChartGeometry.layout(width,height,density,textDensity,labels,statsWidth(textDensity));}
 private ChartGeometry viewGeometry(){return geometry(getWidth(),getHeight(),density(),textDensity());}
 public int recommendedHeight(int windowWidth,int availableHeight){return recommendedHeight(windowWidth,availableHeight,density(),textDensity());}
 public int recommendedHeight(int windowWidth,int availableHeight,float density,float textDensity){return ChartGeometry.recommendedHeight(windowWidth,availableHeight,density,textDensity,statsWidth(textDensity));}
 public void select(double t,double value){cursor=t;cursorValue=value;invalidate();}
 public void setColors(ThemeColors colors){this.colors=colors;invalidate();}
 private void scrub(float x,boolean finalPoint){ChartGeometry g=viewGeometry();cursor=viewport.begin+g.fraction(x)*(viewport.end-viewport.begin);cursorValue=Double.NaN;postInvalidateOnAnimation();long now=android.os.SystemClock.uptimeMillis();if(finalPoint){if(onSelect!=null)onSelect.accept(cursor);}else if(now-lastSelection>=80){lastSelection=now;if(onScrub!=null)onScrub.accept(cursor);}}
 private void intercept(boolean value){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
 private void changed(){viewportDirty=true;invalidate();if(onViewportChanged!=null)onViewportChanged.run();}
 private void finished(){if(onViewportFinished!=null)onViewportFinished.run();}
 @Override protected void onDraw(Canvas c){render(c,getWidth(),getHeight());}
 public void render(Canvas c,int width,int height){render(c,width,height,density(),textDensity());}
 /** Export densities are explicit; this local geometry never changes a View's touch coordinates. */
 public void render(Canvas c,int width,int height,float density,float textDensity){
  if(width<=0||height<=0)return;ChartGeometry g=geometry(width,height,density,textDensity);double begin=viewport.begin,end=viewport.end,span=end-begin;c.save();c.clipRect(0,0,width,height);p.setStyle(Paint.Style.FILL);p.setColor(colors.card);c.drawRoundRect(0,0,width,height,6*density,6*density,p);p.setTypeface(Typeface.DEFAULT);p.setTextAlign(Paint.Align.LEFT);
  p.setTextSize(13*textDensity);p.setColor(colors.accent);String unit=Config.unit(data.key);drawFit(c,Config.label(data.key)+(unit.isEmpty()?"":" ("+unit+")"),g.padding,g.titleBaseline,width-2*g.padding);
  p.setTextSize(11*textDensity);p.setColor(colors.accent);String selection=Double.isFinite(cursor)?Config.format(data.key,cursorValue)+" · 选中 "+Config.elapsed(cursor):"点选曲线查看全部原始数值";
  if(axis.outOfRange)selection=Double.isFinite(cursor)?selection+" · 有超范围原值": "有超出 "+axis.label(axis.min)+"—"+axis.label(axis.max)+" 的原值，点选查看";
  drawFit(c,selection,g.padding,g.cursorBaseline,width-2*g.padding);
  p.setColor(colors.line);p.setStrokeWidth(.6f*density);c.drawLine((float)g.padding,(float)(g.statsTop-3*density),(float)(width-g.padding),(float)(g.statsTop-3*density),p);
  p.setTextSize(11*textDensity);p.setColor(colors.ink);String[] labels=stats();double columnWidth=(width-2*g.padding)/g.statsColumns;
  for(int i=0;i<labels.length;i++)drawFit(c,labels[i],g.padding+(i%g.statsColumns)*columnWidth,g.statsTop+(i/g.statsColumns)*g.statsLine+12*textDensity,columnWidth-6*density);
  p.setTextSize(10.5f*textDensity);Paint.FontMetrics fm=p.getFontMetrics();float baselineCenter=-(fm.ascent+fm.descent)/2;
  for(double tick:axis.ticks){float y=(float)g.y(axis.fraction(tick));p.setColor(tick==0?colors.axis:colors.grid);p.setStrokeWidth((tick==0?1:.6f)*density);c.drawLine((float)g.plotLeft,y,(float)g.plotRight,y,p);p.setColor(colors.muted);p.setTextAlign(Paint.Align.RIGHT);String label=axis.label(tick);label=TextUtils.ellipsize(label,p,(float)Math.max(1,g.plotLeft-g.padding-5*density),TextUtils.TruncateAt.END).toString();c.drawText(label,(float)(g.plotLeft-7*density),y+baselineCenter,p);}
  p.setTextAlign(Paint.Align.LEFT);p.setColor(colors.axis);p.setStrokeWidth(.8f*density);c.drawLine((float)g.plotLeft,(float)g.plotTop,(float)g.plotLeft,(float)g.plotBottom,p);
  c.save();c.clipRect((float)g.plotLeft,(float)g.plotTop,(float)g.plotRight,(float)g.plotBottom);path.reset();boolean pen=false;
  for(int i=0;i<data.times.length;i++){double t=data.times[i],value=data.values[i];if(!Double.isFinite(value)||!Double.isFinite(t)){pen=false;continue;}float x=(float)g.x((t-begin)/span),y=(float)g.y(axis.fraction(value));if(pen)path.lineTo(x,y);else{path.moveTo(x,y);pen=true;}p.setColor(colors.curve);p.setStyle(Paint.Style.FILL);if(value>=axis.min&&value<=axis.max)c.drawCircle(x,y,1.1f*density,p);else drawOutlier(c,x,value>axis.max,g,density);}
  p.setColor(colors.curve);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.35f*density);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
  if(Double.isFinite(cursor)&&cursor>=begin&&cursor<=end){float x=(float)g.x((cursor-begin)/span);p.setColor(colors.cursor);p.setStrokeWidth(density);c.drawLine(x,(float)g.plotTop,x,(float)g.plotBottom,p);if(Double.isFinite(cursorValue)){if(cursorValue>=axis.min&&cursorValue<=axis.max)c.drawCircle(x,(float)g.y(axis.fraction(cursorValue)),3*density,p);else drawOutlier(c,x,cursorValue>axis.max,g,density);}}
  c.restore();if(data.count==0){p.setColor(colors.muted);p.setTextSize(11*textDensity);p.setTextAlign(Paint.Align.CENTER);drawCenteredFit(c,"此区间未取得指标",g.x(.5),g.y(.5)-5*textDensity,g.plotWidth()-12*density);drawCenteredFit(c,"原因见原始采样详情",g.x(.5),g.y(.5)+12*textDensity,g.plotWidth()-12*density);}
  drawTimeAxis(c,g,begin,end,density,textDensity);p.setTextAlign(Paint.Align.LEFT);c.restore();
 }
 private void drawFit(Canvas c,String s,double x,double baseline,double maxWidth){p.setTextAlign(Paint.Align.LEFT);String fit=TextUtils.ellipsize(s,p,(float)Math.max(1,maxWidth),TextUtils.TruncateAt.END).toString();c.drawText(fit,(float)x,(float)baseline,p);}
 private void drawCenteredFit(Canvas c,String s,double x,double baseline,double maxWidth){String fit=TextUtils.ellipsize(s,p,(float)Math.max(1,maxWidth),TextUtils.TruncateAt.END).toString();c.drawText(fit,(float)x,(float)baseline,p);}
 private void drawOutlier(Canvas c,float x,boolean above,ChartGeometry g,float density){float y=(float)(above?g.plotTop+2*density:g.plotBottom-2*density),base=y+(above?5:-5)*density;marker.reset();marker.moveTo(x,y);marker.lineTo(x-3*density,base);marker.lineTo(x+3*density,base);marker.close();c.drawPath(marker,p);}
 private void drawTimeAxis(Canvas c,ChartGeometry g,double begin,double end,float density,float textDensity){
  p.setTextSize(10*textDensity);p.setColor(colors.muted);int ticks=7;for(;;){double step=(end-begin)/(ticks-1),max=0;for(int i=0;i<ticks;i++)max=Math.max(max,p.measureText(ChartGeometry.timeLabel(begin+step*i,step)));int fit=ChartGeometry.tickCount(g.plotWidth(),max,8*density);if(ticks<=fit||ticks==2)break;ticks=Math.max(2,Math.min(ticks-1,fit));}
  double step=(end-begin)/(ticks-1),cell=g.plotWidth()/(ticks-1);for(int i=0;i<ticks;i++){float x=(float)g.x(i/(double)(ticks-1));p.setStrokeWidth(.7f*density);c.drawLine(x,(float)g.plotBottom,x,(float)(g.plotBottom+3*density),p);p.setTextAlign(i==0?Paint.Align.LEFT:i==ticks-1?Paint.Align.RIGHT:Paint.Align.CENTER);double maxWidth=ticks==2?g.plotWidth()/2-4*density:cell-6*density;String label=TextUtils.ellipsize(ChartGeometry.timeLabel(begin+step*i,step),p,(float)Math.max(1,maxWidth),TextUtils.TruncateAt.END).toString();c.drawText(label,x,(float)g.tickBaseline,p);}p.setTextAlign(Paint.Align.LEFT);
 }
 @Override public boolean onTouchEvent(MotionEvent e){
  ChartGeometry g=viewGeometry();if(e.getActionMasked()==MotionEvent.ACTION_DOWN){if(!g.inPlot(e.getX(),e.getY()))return false;downX=lastX=e.getX();downY=e.getY();moved=scaling=viewportDirty=vertical=false;intercept(true);}
  if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){panCenter=focus(e,-1);vertical=false;}
  if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()>=2){float center=focus(e,-1);double before=viewport.begin;viewport.pan(g.panSeconds(center-panCenter,viewport.end-viewport.begin));panCenter=center;if(before!=viewport.begin)changed();}
  if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP)panCenter=focus(e,e.getActionIndex());
  scale.onTouchEvent(e);
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:return true;
   case MotionEvent.ACTION_POINTER_DOWN:scaling=moved=true;intercept(true);return true;
   case MotionEvent.ACTION_MOVE:
    if(!scaling&&e.getPointerCount()==1){float dx=e.getX()-downX,dy=e.getY()-downY;if(vertical||!moved&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){vertical=moved=true;intercept(false);return true;}
     if(Math.abs(dx)>slop||moved){moved=true;intercept(true);scrub(e.getX(),false);}}
    lastX=e.getX();return true;
   case MotionEvent.ACTION_UP:intercept(false);if(!moved&&!scaling&&g.inPlot(e.getX(),e.getY())){long now=android.os.SystemClock.uptimeMillis();if(now-lastTap<320&&Math.hypot(e.getX()-lastTapX,e.getY()-lastTapY)<=2*slop){viewport.reset();changed();finished();lastTap=0;}else{lastTap=now;lastTapX=e.getX();lastTapY=e.getY();scrub(e.getX(),true);}performClick();}else if(viewportDirty)finished();else if(moved&&!scaling&&!vertical){lastTap=0;scrub(e.getX(),true);}return true;
   case MotionEvent.ACTION_CANCEL:intercept(false);if(viewportDirty)finished();return true;
   default:return true;
  }
 }
 private static float focus(MotionEvent e,int omitted){float x=0;int count=0;for(int n=0;n<e.getPointerCount();n++)if(n!=omitted){x+=e.getX(n);count++;}return count==0?e.getX():x/count;}
 @Override public boolean performClick(){super.performClick();return true;}
}
