package com.lightframe.monitor;

import java.util.HashMap;
import java.util.Map;

/** Back off unreadable nodes without caching sensor values or fabricating zeros. */
public final class NodeRetry {
 private final Map<String,Long> after=new HashMap<>();
 public boolean allowed(String path,long now){Long next=after.get(path);return next==null||now>=next;}
 public void failed(String path,long now){after.put(path,now+30_000_000_000L);}
 public void succeeded(String path){after.remove(path);}
}
