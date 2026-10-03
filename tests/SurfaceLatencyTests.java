package com.lightframe.monitor;

import java.util.Arrays;
import java.util.Random;

/** Differential checks retain full-ring timestamps and the existing missing-data rules. */
public final class SurfaceLatencyTests {
 private static int checks;
 private static void same(String raw,String why){
  checks++;if(!Arrays.equals(SurfaceLatency.present(raw),Numbers.present(raw)))throw new AssertionError(why);
 }
 public static void main(String[] args){
  for(String raw:new String[]{null,"","8333333","8333333\n","8333333\r\n1 300 3\r\n1 100 3\r\n1 300 3\r\n1 9223372036854775807 3\r\n1 0 3\r\ncorrupt",
   "1\n1 +123 0\n1 -123 0\n1 9223372036854775808 0\n1 -9223372036854775808 0\n1 + 0\n1 01 0\n1 99\n1 1e2 0\n1 ١٢٣ 0\n1 १२४ 0\n",
   "1\n\u0001\t1\u000b123\f3\r\u0001\n1\u00a0124 3\n1 125\u0001 3\n1\t126\r3\n"})same(raw,"missing, pending, malformed or whitespace rules");
  StringBuilder ring=new StringBuilder("3333333\n");
  for(int i=126;i>=0;i--)ring.append("0 ").append(1_000_000_000L+i*3_333_333L).append(" 0\n");
  same(ring.toString(),"all 127 high-refresh ring entries survive sorting");
  if(SurfaceLatency.present(ring.toString()).length!=127)throw new AssertionError("full ring");checks++;
  Random random=new Random(63845);String[] separators={" ","\t","  ","\r\t","\u000b","\f"};
  for(int trial=0;trial<500;trial++){
   StringBuilder raw=new StringBuilder("16666667\n");int rows=random.nextInt(260);
   for(int i=0;i<rows;i++){
    String value;
    switch(random.nextInt(12)){
     case 0:value="0";break;case 1:value="-1";break;case 2:value="9223372036854775807";break;
     case 3:value="9223372036854775808";break;case 4:value="corrupt";break;
     case 5:value="+"+random.nextInt(1000);break;case 6:value="1.5";break;
     default:value=Long.toString(random.nextInt(1000));
    }
    raw.append(separators[random.nextInt(separators.length)]).append("0").append(separators[random.nextInt(separators.length)]).append(value);
    if(random.nextInt(6)!=0)raw.append(separators[random.nextInt(separators.length)]).append(random.nextInt(5)==0?"invalid":"0");
    raw.append(random.nextBoolean()?"\r\n":"\n");
   }
   same(raw.toString(),"random malformed, duplicate and expanded ring #"+trial);
  }
  System.out.println("Surface latency parser checks: "+checks);
 }
}
