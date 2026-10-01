package com.lightframe.monitor;

import java.util.*;

/** Progress through every candidate without restarting failed probes each sample. */
public final class FrameDiscovery {
 public interface Reader { long[] read(String name) throws Exception; }
 public final List<String> candidates;
 public int cursor, failures;
 public String lastError="";
 public FrameDiscovery(List<String> candidates){this.candidates=new ArrayList<>(candidates);}
 public boolean finished(){return cursor>=candidates.size();}
 public static final class Result {
  public final String layer;public final long[] present;
  Result(String layer,long[] present){this.layer=layer;this.present=present;}
 }
 public Result advance(Reader reader,long now,int maxProbes,long budgetNs){
  long start=System.nanoTime();int probes=0;
  while(!finished()&&probes<maxProbes&&(probes==0||System.nanoTime()-start<budgetNs)){
   String name=candidates.get(cursor++);probes++;
   try{
    long[] t=reader.read(name);
    long newest=t==null||t.length==0?0:t[t.length-1];
    long sampledNow=now+(System.nanoTime()-start);
    if(newest>0&&newest<=sampledNow+100_000_000L&&newest>=sampledNow-2_000_000_000L)return new Result(name,t);
   }catch(Exception e){failures++;lastError=e.getClass().getSimpleName()+": "+e.getMessage();}
  }
  return null;
 }
}
