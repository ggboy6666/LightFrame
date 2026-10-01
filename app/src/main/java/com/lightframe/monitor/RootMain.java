package com.lightframe.monitor;
import java.io.*;import java.nio.charset.StandardCharsets;import org.json.*;
public final class RootMain{
 public static void main(String[] args)throws Exception{try(CoreSampler core=new CoreSampler();BufferedReader r=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));PrintWriter w=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true)){String l;while((l=r.readLine())!=null&&!l.equals("QUIT")){if(l.length()>8192)break;try{w.println(core.handle(new JSONObject(l)));}catch(Throwable e){JSONObject j=new JSONObject();j.put("error",e.toString());w.println(j);}}}}
}
