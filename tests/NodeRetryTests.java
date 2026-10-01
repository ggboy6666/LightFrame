package com.lightframe.monitor;

public final class NodeRetryTests {
 public static void main(String[] args){
  NodeRetry retry=new NodeRetry();String p="/sys/module/ged/parameters/gpu_loading";long t=1_000_000_000L;
  check(retry.allowed(p,t),"first real read allowed");retry.failed(p,t);
  check(!retry.allowed(p,t+1),"known denial backed off");
  check(!retry.allowed(p,t+29_999_999_999L),"no repeated failed opens during window");
  check(retry.allowed(p,t+30_000_000_000L),"actual permission recheck remains possible");
  check(retry.allowed("/proc/stat",t+1),"other readable sources continue");
  retry.succeeded(p);check(retry.allowed(p,t+2),"success clears denial state");
  System.out.println("6 node retry checks passed");
 }
 private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
}
