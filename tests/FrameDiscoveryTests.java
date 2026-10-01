package com.lightframe.monitor;
import java.util.*;

public final class FrameDiscoveryTests {
 static int checks;static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
 public static void main(String[] args){
  List<String> names=new ArrayList<>();for(int i=0;i<14;i++)names.add("game-buffer-"+i);
  FrameDiscovery discovery=new FrameDiscovery(names);long now=10_000_000_000L;int[] calls={0};
  FrameDiscovery.Reader reader=name->{calls[0]++;if(name.equals("game-buffer-2"))throw new java.io.IOException("permission denied");return name.equals("game-buffer-11")?new long[]{now-20_000_000L,now-10_000_000L}:new long[0];};
  FrameDiscovery.Result found=null;
  for(int poll=0;poll<4&&found==null;poll++)found=discovery.advance(reader,now,3,1_000_000_000L);
  ok(found!=null&&found.layer.equals("game-buffer-11"),"active candidate beyond first eight discovered");
  ok(discovery.failures==1&&calls[0]==12,"failed candidate isolated and prior empty probes not repeated");
  ok(found.present.length==2,"only measured presentation times returned");
  FrameDiscovery invalid=new FrameDiscovery(Arrays.asList("stale","future","empty","denied"));
  FrameDiscovery.Result rejected=invalid.advance(name->{if(name.equals("stale"))return new long[]{now-3_000_000_000L};if(name.equals("future"))return new long[]{now+2_000_000_000L};if(name.equals("denied"))throw new java.io.IOException("denied");return new long[0];},now,10,1_000_000_000L);
  ok(rejected==null&&invalid.finished(),"stale future empty and denied buffers do not invent FPS");
  int[] timedCalls={0};FrameDiscovery bounded=new FrameDiscovery(names);
  bounded.advance(name->{timedCalls[0]++;return new long[0];},now,3,0);
  ok(timedCalls[0]==1&&bounded.cursor==1,"budget limits work while preserving progress");
  bounded.advance(name->{timedCalls[0]++;return new long[0];},now,3,1_000_000_000L);
  ok(bounded.cursor==4,"next poll resumes candidate cursor");

  FrameStats frames=new FrameStats();frames.discontinuity(1_000_000_000L);
  ok(frames.updateSource("game-A",1_010_000_000L),"first source accepted");
  frames.add(1_020_000_000L,60);frames.add(1_040_000_000L,60);
  ok(frames.count==1&&!Double.isNaN(frames.frameMs()),"measured interval retained");
  ok(frames.updateSource("",1_050_000_000L),"lost source explicitly clears stream");
  ok(Double.isNaN(frames.frameMs())&&Double.isNaN(frames.fps(1_050_000_000L)),"unavailable source does not retain old frame duration or FPS");
  ok(!frames.updateSource("",1_060_000_000L),"continued missing source does not repeatedly reset");
  frames.updateSource("game-B",2_000_000_000L);
  ok(!frames.add(1_900_000_000L,60),"old buffered timestamps rejected on new source");
  frames.add(2_010_000_000L,60);frames.add(2_030_000_000L,60);
  ok(frames.count==2&&frames.longFrames==0,"gap between sources is not counted as a long frame");
  frames.updateSource("game-C",3_000_000_000L);frames.add(3_010_000_000L,60);
  ok(frames.count==2&&Double.isNaN(frames.frameMs()),"direct source switch starts a fresh interval baseline");
  System.out.println(checks+" frame discovery and source checks passed");
 }
}
