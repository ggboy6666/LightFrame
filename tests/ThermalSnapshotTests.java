package com.lightframe.monitor;

public final class ThermalSnapshotTests {
 static int checks;
 static void ok(boolean b,String name){checks++;if(!b)throw new AssertionError(name);}
 static void eq(double actual,double expected,String name){ok(Math.abs(actual-expected)<1e-6,name+" got="+actual);}
 static void missing(double value,String name){ok(Double.isNaN(value),name);}
 static String reading(double value,int type,String name,int status) {
  return "\tTemperature{mValue="+value+", mType="+type+", mName="+name+", mStatus="+status+"}\n";
 }
 static String current(String readings) {
  return "IsStatusOverride: false\nThermal Status: 2\nCached temperatures:\n"
    +reading(99,0,"CPU0",0)+"HAL Ready: true\nHAL connection:\n\tThermalHAL AIDL 3 connected: yes\n"
    +"Current temperatures from HAL:\n"+readings
    +"Current cooling devices from HAL:\n\tCoolingDevice{mValue=100, mType=0, mName=cpu}\n"
    +"Temperature static thresholds from HAL:\n"
    +"\tTemperatureThreshold{mType=0, mName=cpu, mHotThrottlingThresholds=[NaN, 90.0], mColdThrottlingThresholds=[NaN]}\n";
 }
 public static void main(String[] args) {
  ThermalSnapshot s=ThermalSnapshot.parse(current(reading(41,0,"CPU0",1)+reading(44,0,"CPU1",2)
    +reading(39.5,1,"graphics",0)+reading(43,13,"chip",0)+reading(31,2,"battery",0)+reading(29,3,"skin",0)));
  eq(s.cpuC,44,"maximum current CPU sensor excludes stale cache");eq(s.gpuC,39.5,"GPU identified by type");
  eq(s.socC,43,"Android SOC type 13");eq(s.batteryC,31,"battery has its own channel");eq(s.skinC,29,"skin has its own channel");
  ok(s.thermalStatus==2,"global throttling status");ok(Boolean.TRUE.equals(s.halReady),"HAL ready");
  ok(Boolean.FALSE.equals(s.statusOverridden),"status override retained");ok(s.source.equals("current"),"current source retained");
  ok(s.sensors.size()==6,"cache/cooling devices/thresholds excluded");ok(s.sensors.get(1).status==2,"sensor throttling retained");
  ok(s.sensors.get(2).type==1&&s.sensors.get(2).category.equals("gpu"),"structured type retained");
  try{s.sensors.clear();throw new AssertionError("mutable sensors");}catch(UnsupportedOperationException expected){checks++;}

  s=ThermalSnapshot.parse(current(reading(85,8,"soc",0)+reading(35,2,"cpu-battery",0)+reading(36,3,"cpu-skin",0)
    +reading(4.3,6,"cpu-voltage",0)+reading(2.2,7,"gpu-current",0)));
  missing(s.cpuC,"battery/skin/voltage cannot become CPU");missing(s.gpuC,"current cannot become GPU");
  missing(s.socC,"BCL state of charge soc is not chip temperature");
  ok(s.sensors.get(0).category.equals("other"),"BCL excluded despite soc name");
  ok(s.status.contains("未报告"),"no core sensors is explicit");

  s=ThermalSnapshot.parse(current(reading(48,-1,"cpu0",0)+reading(40,-1,"gpu-thermal",0)+reading(47,-1,"mtktsap",0)
    +reading(95,-1,"cpu-skin",0)+reading(95,-1,"soc-battery",0)+reading(99,3,"soc",0)));
  eq(s.cpuC,48,"unknown type explicit CPU name");eq(s.gpuC,40,"unknown type explicit GPU name");eq(s.socC,47,"unknown type explicit AP name");
  ok(s.sensors.get(3).category.equals("other")&&s.sensors.get(4).category.equals("other"),"ambiguous unknown names excluded");

  s=ThermalSnapshot.parse(current(reading(Double.NaN,0,"cpu",0)+reading(Double.POSITIVE_INFINITY,1,"gpu",0)
    +reading(45000,13,"soc",0)+reading(-127,0,"cpu-sentinel",0)));
  missing(s.cpuC,"HAL sentinel/NaN invalid");missing(s.gpuC,"infinite invalid");missing(s.socC,"HAL Celsius is never silently divided by 1000");
  ok(s.sensors.size()==4,"raw invalid readings retained for diagnosis");
  s=ThermalSnapshot.parse(current(""));missing(s.cpuC,"empty current section cannot revive cached data");
  s=ThermalSnapshot.parse("Cached temperatures:\n"+reading(42,0,"cpu",0)+"HAL Ready: false\n");
  missing(s.cpuC,"HAL unavailable cannot revive cache");ok(s.status.contains("未就绪"),"HAL unavailable explicit");
  s=ThermalSnapshot.parse("Thermal Status: 1\nCached temperatures:\n"+reading(42,0,"cpu",0));
  eq(s.cpuC,42,"cached section supported when current section absent");ok(s.source.equals("cached")&&s.status.contains("缓存"),"cache source explicit");

  s=ThermalSnapshot.parse("Permission Denial: can't dump ThermalManagerService from pid=3 uid=10100 due to missing android.permission.DUMP permission\n");
  ok(s.permissionDenied,"AOSP permission denial recognized");missing(s.cpuC,"permission rejection missing");ok(s.sensors.isEmpty(),"no invented sensors");
  s=ThermalSnapshot.parse(null);missing(s.cpuC,"null missing");ok(s.thermalStatus==-1&&s.halReady==null,"null status stays unknown");
  s=ThermalSnapshot.parse(current(reading(44,0,"cpu",9)+"\tTemperature{mValue=bad, mType=1, mName=gpu, mStatus=0}\n"));
  missing(s.cpuC,"invalid sensor status rejected");missing(s.gpuC,"malformed value rejected");
  s=ThermalSnapshot.parse(current("  Temperature { mValue = 4.1E1 , mType = 0 , mName = cpu,cluster0 , mStatus = 0 }\r\n"));
  eq(s.cpuC,41,"spaces/exponents accepted");ok(s.sensors.get(0).name.equals("cpu,cluster0"),"name comma retained");
  s=ThermalSnapshot.parse("Thermal Status: 9\nHAL Ready: true\n");ok(s.thermalStatus==-1,"invalid global status unknown");
  s=ThermalSnapshot.parse(current(reading(0,0,"cpu",0)));eq(s.cpuC,0,"reported zero not confused with missing");
  System.out.println("PASS "+checks+" thermal snapshot checks");
 }
}
