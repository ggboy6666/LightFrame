package com.lightframe.monitor;

/** Stagger two dirty files, performing at most one periodic flush per collection batch. */
public final class RecordingFlushSchedule {
 public static final int NONE=0,SAMPLES=1,FRAMES=2;
 private static final long PERIOD_NS=2_000_000_000L;
 private long samplesAt,framesAt;
 public RecordingFlushSchedule(long startedNs){samplesAt=startedNs+PERIOD_NS;framesAt=startedNs+PERIOD_NS/2;}
 public int due(long nowNs,boolean samplesDirty,boolean framesDirty){
  boolean samples=samplesDirty&&nowNs>=samplesAt,frames=framesDirty&&nowNs>=framesAt;
  if(samples&&frames)return samplesAt<=framesAt?SAMPLES:FRAMES;
  return samples?SAMPLES:frames?FRAMES:NONE;
 }
 public void flushed(int file,long nowNs){
  if(file==SAMPLES)samplesAt=nowNs+PERIOD_NS;
  else if(file==FRAMES)framesAt=nowNs+PERIOD_NS;
 }
}
