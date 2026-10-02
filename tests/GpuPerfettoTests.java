package com.lightframe.monitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Runs without Android or a device. Synthetic packets exercise validity gates. */
public final class GpuPerfettoTests {
    private static int checks;
    private static final long S=1_000_000_000L,B=100*S,M=10*S,R=M-2_000_000L;
    private static void check(boolean ok,String why) { ++checks; if(!ok) throw new AssertionError(why); }
    private static void near(double actual,double wanted,String why) {
        check(Math.abs(actual-wanted)<1e-7,why+": "+actual+" wanted "+wanted);
    }
    private static byte[] bytes(byte[]... parts) {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); for(byte[] p:parts) out.write(p,0,p.length);
        return out.toByteArray();
    }
    private static byte[] var(long n) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        do { int b=(int)(n&127); n>>>=7; out.write(n==0?b:b|128); } while(n!=0);
        return out.toByteArray();
    }
    private static byte[] num(int field,long n) { return bytes(var(field*8L),var(n)); }
    private static byte[] msg(int field,byte[] b) { return bytes(var(field*8L+2),var(b.length),b); }
    private static byte[] dbl(int field,double d) {
        long bits=Double.doubleToLongBits(d); byte[] b=new byte[8];
        for(int i=0;i<8;++i) b[i]=(byte)(bits>>>(8*i)); return bytes(var(field*8L+1),b);
    }
    private static byte[] packet(byte[]... fields) { return msg(1,bytes(fields)); }
    private static byte[] clock() {
        return packet(msg(6,bytes(msg(1,bytes(num(1,6),num(2,B))),
                msg(1,bytes(num(1,3),num(2,M))),msg(1,bytes(num(1,4),num(2,R))))));
    }
    private static byte[] service(int event,long at) {
        return packet(num(8,B+at),msg(69,num(event,1)));
    }
    private static byte[] fstats(int phase,long at,long drop) {
        return packet(num(10,2),msg(34,bytes(num(1,phase),msg(2,bytes(num(1,0),
                num(3,0),num(4,0),num(8,drop),dbl(7,(B+at)/1e9))))));
    }
    private static byte[] work(long uid,long begin,long end,long active) {
        return packet(num(10,2),msg(1,msg(2,bytes(num(1,B+end),
                msg(488,bytes(num(1,0),num(2,uid),num(3,R+begin),num(4,R+end),num(5,active)))))));
    }
    private static byte[] freq(long at,long khz) {
        return packet(num(10,2),msg(1,msg(2,bytes(num(1,B+at),msg(332,bytes(num(1,0),num(2,khz)))))));
    }
    private static byte[] traceStats(long outcome,long loss) {
        return packet(msg(35,bytes(msg(1,num(13,loss)),num(12,1),num(13,1),num(14,0),num(15,outcome))));
    }
    private static byte[] prefix() { return bytes(clock(),service(1,0),fstats(1,0,0)); }
    private static byte[] suffix(long loss) {
        return bytes(service(5,8*S),fstats(2,8*S,0),service(4,8*S),traceStats(1,loss));
    }
    private static GpuTraceData read(byte[] file,int chunk,boolean finish) throws Exception {
        GpuTraceData reader=new GpuTraceData(4);
        for(int off=0;off<file.length;) {
            int n=Math.min(chunk,file.length-off); reader.feed(file,off,n); off+=n;
        }
        if(finish) reader.finish(); return reader;
    }
    private static void fails(byte[] data,boolean finish,String why) throws Exception {
        boolean failed=false;
        try { read(data,7,finish); } catch(IOException ex) { failed=true; }
        check(failed,why);
    }
    public static void main(String[] args) throws Exception {
        byte[] good=bytes(prefix(),work(100,3*S,3*S+500_000_000,500_000_000),freq(7*S,338000),suffix(0));
        for(int chunk:new int[]{1,2,3,7,17,64,4096}) {
            GpuTraceData reader=read(good,chunk,true);
            GpuTraceData.Snapshot s=reader.snapshot(M+9*S);
            check(s.complete,"complete final capture "+chunk); near(s.busyPct,50,"true busy union");
            check(s.beginNs==M+3*S && s.endNs==M+4*S,"complete 1s window with 3.1s margin");
            near(s.frequencyMHz,338,"frequency kHz conversion"); check(s.frequencyNs==M+7*S,"BOOT->MONO");
            check(s.workCount==1 && s.fullWorkCount==1,"counts");
        }
        GpuTraceData streaming=read(good,13,false);
        check(!streaming.snapshot(M+9*S).complete,"final packet without external EOF not committed");
        check(Double.isNaN(streaming.snapshot(M+9*S).frequencyMHz),"frequency also requires normal capture completion");
        byte[] overlap=bytes(prefix(),work(101,3*S,3*S+700_000_000,700_000_000),
                work(102,3*S+400_000_000,4*S,600_000_000),work(102,3*S+400_000_000,4*S,600_000_000),suffix(0));
        near(read(overlap,19,true).snapshot(M+9*S).busyPct,100,"cross UID overlaps and duplicate do not double count");
        byte[] partial=bytes(prefix(),work(100,3*S,4*S,500_000_000),suffix(0));
        check(Double.isNaN(read(partial,11,true).snapshot(M+9*S).busyPct),"coarse interval is unknown, not assumed 50%");
        byte[] mixed=bytes(prefix(),work(100,S,2*S,S),work(101,3*S,4*S,0),suffix(0));
        check(Double.isNaN(read(mixed,11,true).snapshot(M+9*S).busyPct),"non-full active period is withheld");
        byte[] idle=bytes(prefix(),work(100,S,S+100_000_000,100_000_000),suffix(0));
        near(read(idle,5,true).snapshot(M+9*S).busyPct,0,"proven enabled trace plus complete empty window allows 0");
        check(!read(bytes(prefix(),freq(7*S,338000),suffix(0)),21,true).snapshot(M+9*S).complete,
                "no work ever observed is unsupported, not zero");
        GpuTraceData.Snapshot frequencyOnly=read(bytes(prefix(),freq(7*S,338000),suffix(0)),21,true).snapshot(M+9*S);
        check(Double.isNaN(frequencyOnly.busyPct),"frequency-only capture does not invent busy load");
        near(frequencyOnly.frequencyMHz,338,"completed frequency-only capture remains useful");
        check(frequencyOnly.frequencyNs==M+7*S,"frequency-only capture has its actual event time");
        GpuTraceData.Snapshot coarseFrequency=read(bytes(prefix(),work(100,3*S,4*S,500_000_000),freq(7*S,338000),suffix(0)),11,true).snapshot(M+9*S);
        check(Double.isNaN(coarseFrequency.busyPct),"coarse busy load remains unavailable with frequency present");
        near(coarseFrequency.frequencyMHz,338,"coarse busy interval does not invalidate verified frequency");
        GpuTraceData.Snapshot lostFrequency=read(bytes(prefix(),freq(7*S,338000),suffix(1)),8,true).snapshot(M+9*S);
        check(Double.isNaN(lostFrequency.frequencyMHz)&&lostFrequency.frequencyNs==0,"lost trace rejects frequency and its timestamp");
        GpuTraceData.Snapshot missingFences=read(bytes(prefix(),freq(7*S,338000)),8,true).snapshot(M+9*S);
        check(Double.isNaN(missingFences.frequencyMHz),"frequency requires complete final fences");
        check(!read(bytes(prefix(),work(100,3*S,4*S,S),suffix(1)),8,true).snapshot(M+9*S).complete,
                "central buffer loss");
        byte[] cpuDrop=bytes(prefix(),work(100,3*S,4*S,S),service(5,8*S),fstats(2,8*S,1),service(4,8*S),traceStats(1,0));
        check(!read(cpuDrop,11,true).snapshot(M+9*S).complete,"kernel buffer loss");
        byte[] badFlush=bytes(prefix(),work(100,3*S,4*S,S),service(5,8*S),fstats(2,8*S,0),service(4,8*S),traceStats(0,0));
        check(!read(badFlush,11,true).snapshot(M+9*S).complete,"early TraceStats cannot confirm final flush");
        byte[] safeFirst=bytes(prefix(),packet(num(10,2),num(42,1),num(87,1)),work(100,3*S,4*S,S),suffix(0));
        check(read(safeFirst,1,true).snapshot(M+9*S).complete,"first packet flag is not loss");
        byte[] unsafeDrop=bytes(prefix(),packet(num(10,2),num(42,1)),work(100,3*S,4*S,S),suffix(0));
        check(!read(unsafeDrop,1,true).snapshot(M+9*S).complete,"real sequence loss");
        byte[] crossed=bytes(prefix(),work(100,3*S+900_000_000,4*S+400_000_000,500_000_000),suffix(0));
        near(read(crossed,9,true).snapshot(M+9*S).busyPct,10,"cross-window full busy clipped at end");
        fails(Arrays.copyOf(good,good.length-1),true,"EOF partial packet");
        fails(bytes(var(10),var(GpuTraceData.MAX_PACKET_BYTES+1)),false,"oversized packet rejected before payload");
        fails(new byte[]{0},false,"invalid root tag");
        fails(new byte[]{10,1,0},false,"invalid nested tag");
        fails(new byte[]{10,2,2,0},false,"nested field zero with length wire rejected");
        fails(new byte[]{10,1,11},false,"group wire rejected");
        fails(new byte[]{10,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,2},false,
                "varint overflow");
        for(int cut=0;cut<good.length;++cut) {
            GpuTraceData reader=new GpuTraceData(); reader.feed(good,0,cut);
            reader.feed(good,cut,good.length-cut); reader.finish();
            check(reader.snapshot(M+9*S).complete,"every split boundary "+cut);
        }
        System.out.println("GpuPerfettoTests: "+checks+" checks passed");
        for(String path:args) {
            byte[] raw=Files.readAllBytes(Paths.get(path));
            GpuTraceData reader=read(raw,17,true); GpuTraceData.Snapshot s=reader.snapshot(System.nanoTime());
            long begin=reader.workCoverageBegin(),end=reader.workCoverageEnd();
            System.out.println(path+" bytes="+raw.length+" packets="+s.packetCount+" work="+s.workCount+
                    " full="+s.fullWorkCount+" partial="+s.partialWorkCount+" frequencies="+reader.frequencyCount()+
                    " complete="+s.complete+" busyPct="+s.busyPct+" frequencyMHz="+s.frequencyMHz+
                    " beginNs="+s.beginNs+" endNs="+s.endNs+" freqNs="+s.frequencyNs+
                    " watermarkNs="+s.watermarkNs+" status="+s.status+" error="+s.error+
                    " rawCoverageNs="+(end-begin)+" rawBusyNs="+reader.unionNs(begin,end));
        }
    }
}
