package com.lightframe.monitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** A bounded, dependency-free reader for the two selected Perfetto ftrace events.
 * GPU work periods use RAW on Android 16. All published timestamps use MONOTONIC.
 * A normal process exit is not a completion fence: finish also needs a successful
 * final TraceStats flush, END FtraceStats, and read-tracing-buffers-completed.
 */
public final class GpuTraceData {
    // Perfetto BuiltinClock enum IDs, not Linux clockid_t constants.
    public static final int CLOCK_MONOTONIC=3, CLOCK_MONOTONIC_RAW=5, CLOCK_BOOTTIME=6;
    public static final int MAX_PACKET_BYTES = 1024 * 1024;
    public static final long SECOND_NS = 1_000_000_000L;
    // A period may cross a requested window by up to 1 s, then be reported 2 s
    // after its end. The extra 100 ms covers reads performed before END stats.
    private static final long COMPLETION_DELAY_NS = 3_100_000_000L;
    private static final int MAX_PERIODS = 16384, MAX_CPUS = 256;
    private static final long HISTORY_NS = 30 * SECOND_NS;

    public static final class Snapshot {
        public final double busyPct, frequencyMHz;
        public final long beginNs, endNs, frequencyNs, readNs, watermarkNs;
        public final boolean complete;
        public final String status, error;
        public final long workCount, fullWorkCount, partialWorkCount, packetCount;
        Snapshot(double busy, double frequency, long begin, long end, long freqNs,
                long read, long watermark, boolean ok, String state, String why,
                long work, long full, long partial, long packets) {
            busyPct=busy; frequencyMHz=frequency; beginNs=begin; endNs=end;
            frequencyNs=freqNs; readNs=read; watermarkNs=watermark; complete=ok;
            status=state; error=why; workCount=work; fullWorkCount=full;
            partialWorkCount=partial; packetCount=packets;
        }
    }

    private static final class Period {
        final long begin, end, active, uid;
        Period(long b, long e, long a, long u) { begin=b; end=e; active=a; uid=u; }
    }
    private static final class Cpu {
        long id=-1, now=-1, overrun=0, commitOverrun=0, dropped=0;
    }
    private final int workClockId;
    private final ArrayList<Period> periods = new ArrayList<Period>();
    private final Map<Long,Cpu> beforeCpus = new HashMap<Long,Cpu>();
    private byte[] pending = new byte[4096];
    private int pendingSize;
    private boolean finished, haveClock, haveEndStats, haveTraceStats;
    private long bootMinusMono, monoMinusRaw, firstClockMono;
    private long startNs, disabledNs, endStatsNs, readCompleteNs;
    private long endStatsPacket, readCompletePacket, traceStatsPacket;
    private long packetCount, workCount, fullCount, partialCount, frequencyCount;
    private long gpuId=-1, frequencyGpuId=-1, latestFrequencyNs, latestFrequencyKhz;
    private long latestPeriodNs, prunedBeforeNs;
    private long finalFlushOutcome, flushRequested, flushSucceeded, flushFailed;
    private final long[] bufferLossCounts=new long[6];
    private final boolean[] bufferLossSaturated=new boolean[6];
    private String problem="", fatal="";

    public GpuTraceData() { this(CLOCK_MONOTONIC_RAW); }
    public GpuTraceData(int workClockId) {
        if (workClockId!=CLOCK_MONOTONIC && workClockId!=CLOCK_MONOTONIC_RAW)
            throw new IllegalArgumentException("Perfetto work clock must be MONOTONIC (3) or RAW (5)");
        this.workClockId=workClockId;
    }

    /** Arbitrary file chunks are accepted; an incomplete packet is retained. */
    public void feed(byte[] bytes, int offset, int count) throws IOException {
        if (bytes==null || offset<0 || count<0 || offset>bytes.length-count)
            throw new IllegalArgumentException("invalid input range");
        if (finished || !fatal.isEmpty()) throw new IOException("reader is closed: "+fatal);
        try {
            while (count>0) {
                int take=Math.min(count,4096);
                ensurePending(pendingSize+take);
                System.arraycopy(bytes,offset,pending,pendingSize,take);
                pendingSize+=take; offset+=take; count-=take;
                consumePackets();
            }
        } catch (IOException ex) { fatal=ex.getMessage(); throw ex; }
    }

    /** Call only after successful normal capture termination and reading EOF. */
    public void finish() throws IOException {
        if (!fatal.isEmpty()) throw new IOException(fatal);
        if (pendingSize!=0) { fatal="truncated final TracePacket"; throw new IOException(fatal); }
        finished=true;
    }

    public Snapshot snapshot(long nowNs) {
        long watermark=haveEndStats && disabledNs>0 ? Math.min(endStatsNs,disabledNs) : 0;
        long begin=0,end=0;
        String why=availabilityProblem();
        // Trace integrity gates both channels; work-window eligibility gates
        // only busy load. A frequency event does not require a busy period.
        boolean traceComplete=why.isEmpty();
        double frequency=traceComplete && latestFrequencyNs>0
                ? latestFrequencyKhz/1000.0 : Double.NaN;
        if (traceComplete && fullCount==0)
            why="GPU work tracepoint has not produced a complete busy period";
        if (why.isEmpty()) {
            long safeEnd=watermark-COMPLETION_DELAY_NS;
            long wholeWindows=(safeEnd-startNs)/SECOND_NS;
            if (safeEnd<startNs+SECOND_NS || wholeWindows<1) why="capture does not contain a complete 1 s window";
            else {
                end=startNs+wholeWindows*SECOND_NS; begin=end-SECOND_NS;
                if (begin<prunedBeforeNs) why="window history was discarded";
                for (Period p:periods)
                    if (p.begin<end && p.end>begin && p.active!=p.end-p.begin)
                        why="window includes a coarse GPU work period; busy locations are unknown";
            }
        }
        boolean valid=why.isEmpty();
        double busy=valid ? unionNs(begin,end)*100.0/SECOND_NS : Double.NaN;
        return new Snapshot(busy,frequency,begin,end,Double.isFinite(frequency)?latestFrequencyNs:0,nowNs,
                watermark,valid,valid?"ready":finished?"unavailable":"collecting",why,
                workCount,fullCount,partialCount,packetCount);
    }

    private String availabilityProblem() {
        if (!fatal.isEmpty()) return fatal;
        if (!problem.isEmpty()) return problem;
        if (!haveClock) return "required clock snapshot is missing";
        if (!finished) return "waiting for normal capture completion";
        if (!haveTraceStats || finalFlushOutcome!=1 || flushFailed!=0 ||
                flushRequested==0 || flushSucceeded!=flushRequested)
            return "successful final flush is not confirmed";
        if (!haveEndStats || beforeCpus.isEmpty() || startNs<=0 || disabledNs<=0 ||
                readCompleteNs<disabledNs || readCompletePacket<=endStatsPacket ||
                traceStatsPacket<=endStatsPacket)
            return "complete capture fences are missing";
        return "";
    }

    private void ensurePending(int need) throws IOException {
        // A feed slice may follow an almost complete maximum-sized packet.
        if (need>MAX_PACKET_BYTES+4116) throw new IOException("pending packet exceeds byte limit");
        if (need>pending.length) {
            int size=Math.min(MAX_PACKET_BYTES+4116,Math.max(need,pending.length*2));
            byte[] next=new byte[size]; System.arraycopy(pending,0,next,0,pendingSize); pending=next;
        }
    }

    private void consumePackets() throws IOException {
        int consumed=0;
        while (consumed<pendingSize) {
            Cursor c=new Cursor(pending,consumed,pendingSize);
            Long tag=c.maybeVarint(); if (tag==null) break;
            if (tag!=10L) throw new IOException("unexpected root Trace field; expected packet");
            Long size=c.maybeVarint(); if (size==null) break;
            if (size<0 || size>MAX_PACKET_BYTES) throw new IOException("TracePacket exceeds 1 MiB");
            if (size>pendingSize-c.position) break;
            int end=c.position+size.intValue();
            parsePacket(new Cursor(pending,c.position,end)); consumed=end;
        }
        if (consumed>0) {
            System.arraycopy(pending,consumed,pending,0,pendingSize-consumed);
            pendingSize-=consumed;
        }
    }

    private void parsePacket(Cursor packet) throws IOException {
        ++packetCount;
        Cursor clock=null,bundle=null,stats=null,traceStats=null,service=null;
        long timestamp=0,clockId=CLOCK_BOOTTIME,seq=0; boolean drop=false,first=false;
        while (packet.next()) {
            switch(packet.field) {
                case 1: bundle=packet.message(); break;
                case 6: clock=packet.message(); break;
                case 8: timestamp=packet.number(); break;
                case 10: seq=packet.number(); break;
                case 34: stats=packet.message(); break;
                case 35: traceStats=packet.message(); break;
                case 42: drop=packet.number()!=0; break;
                case 58: clockId=packet.number(); break;
                case 69: service=packet.message(); break;
                case 87: first=packet.number()!=0; break;
                default: break;
            }
        }
        if (drop && !first) problem="TracePacket loss on sequence "+seq;
        if (clock!=null) parseClock(clock);
        if (service!=null) parseService(service,toMono(timestamp,(int)clockId));
        if (bundle!=null) parseBundle(bundle);
        if (stats!=null) parseFtraceStats(stats);
        if (traceStats!=null) parseTraceStats(traceStats);
    }

    private void parseClock(Cursor c) throws IOException {
        long boot=-1,mono=-1,raw=-1;
        while(c.next()) if(c.field==1) {
            Cursor clock=c.message(); long id=-1,ts=-1,multiplier=1; boolean incremental=false;
            while(clock.next()) {
                if(clock.field==1) id=clock.number();
                else if(clock.field==2) ts=clock.number();
                else if(clock.field==3) incremental=clock.number()!=0;
                else if(clock.field==4) multiplier=clock.number();
            }
            if(incremental || multiplier!=1) continue;
            if(id==CLOCK_BOOTTIME) boot=ts; else if(id==CLOCK_MONOTONIC) mono=ts; else if(id==CLOCK_MONOTONIC_RAW) raw=ts;
        }
        if(boot<=0 || mono<=0 || workClockId==CLOCK_MONOTONIC_RAW && raw<=0) return;
        long nextBoot=boot-mono, nextRaw=raw>0?mono-raw:0;
        if(haveClock && (Math.abs(nextBoot-bootMinusMono)>1_000_000 ||
                Math.abs(nextRaw-monoMinusRaw)>1_000_000))
            problem="clock mapping changed during capture; restart after suspend";
        haveClock=true; bootMinusMono=nextBoot; monoMinusRaw=nextRaw;
        if(firstClockMono==0) firstClockMono=mono;
    }

    private long toMono(long ts,int clock) {
        if(ts<=0 || !haveClock) return 0;
        if(clock==CLOCK_MONOTONIC) return ts;
        if(clock==CLOCK_MONOTONIC_RAW) return ts+monoMinusRaw;
        if(clock==CLOCK_BOOTTIME) return ts-bootMinusMono;
        problem="unsupported trace timestamp clock "+clock; return 0;
    }

    private void parseService(Cursor c,long mono) throws IOException {
        while(c.next()) {
            if(c.field==1 && c.number()!=0) startNs=Math.max(startNs,mono);
            else if(c.field==5 && c.number()!=0) disabledNs=Math.max(disabledNs,mono);
            else if(c.field==4 && c.number()!=0) {
                readCompleteNs=Math.max(readCompleteNs,mono); readCompletePacket=packetCount;
            } else if(c.field==6 && c.number()!=0) problem="trace was seized for bugreport";
            else if(c.field==7 || c.field==8) problem="tracing data source start or flush timed out";
        }
    }

    private void parseBundle(Cursor c) throws IOException {
        // Bundle clock is a field after the events in common writer output.
        Cursor scan=c.copy(); long clock=0;
        while(scan.next()) {
            if(scan.field==3 && scan.number()!=0) problem="ftrace kernel events were lost";
            else if(scan.field==5) clock=scan.number();
            else if(scan.field==8) problem="ftrace event parse error";
        }
        if(clock!=0) { problem="unsupported non-BOOTTIME ftrace clock"; return; }
        while(c.next()) if(c.field==2) parseEvent(c.message());
    }

    private void parseEvent(Cursor c) throws IOException {
        long eventTime=0; Cursor work=null,freq=null;
        while(c.next()) {
            if(c.field==1) eventTime=c.number();
            else if(c.field==488) work=c.message();
            else if(c.field==332) freq=c.message();
        }
        if(work!=null) {
            long id=-1,uid=-1,start=-1,end=-1,active=-1;
            while(work.next()) {
                if(work.field==1) id=work.number(); else if(work.field==2) uid=work.number();
                else if(work.field==3) start=work.number(); else if(work.field==4) end=work.number();
                else if(work.field==5) active=work.number();
            }
            ++workCount;
            if(id<0 || uid<0 || start<0 || end<=start || end-start>SECOND_NS ||
                    active<0 || active>end-start) { problem="invalid GPU work period"; return; }
            if(gpuId<0) gpuId=id; else if(gpuId!=id) problem="multiple GPU identifiers require separate metrics";
            if(active==end-start) ++fullCount; else ++partialCount;
            if(!haveClock) { problem="GPU work arrived before required clock snapshot"; return; }
            start=toMono(start,workClockId); end=toMono(end,workClockId);
            latestPeriodNs=Math.max(latestPeriodNs,end);
            prunePeriods();
            if(periods.size()>=MAX_PERIODS) { problem="GPU period storage limit exceeded"; return; }
            periods.add(new Period(start,end,active,uid));
        }
        if(freq!=null) {
            long id=-1,state=-1;
            while(freq.next()) {
                if(freq.field==1) id=freq.number(); else if(freq.field==2) state=freq.number();
            }
            ++frequencyCount;
            if(id<0 || state<0) { problem="invalid GPU frequency event"; return; }
            if(frequencyGpuId<0) frequencyGpuId=id;
            if(frequencyGpuId!=id) { problem="multiple GPU frequency identifiers"; return; }
            long mono=toMono(eventTime,6);
            if(mono>latestFrequencyNs) { latestFrequencyNs=mono; latestFrequencyKhz=state; }
        }
    }

    private void prunePeriods() {
        long cutoff=latestPeriodNs-HISTORY_NS;
        for(int i=periods.size()-1;i>=0;--i) if(periods.get(i).end<cutoff) {
            prunedBeforeNs=Math.max(prunedBeforeNs,periods.get(i).end); periods.remove(i);
        }
    }

    private void parseFtraceStats(Cursor c) throws IOException {
        int phase=0; Map<Long,Cpu> cpus=new HashMap<Long,Cpu>();
        while(c.next()) {
            if(c.field==1) phase=(int)c.number();
            else if(c.field==2) {
                Cursor p=c.message(); Cpu cpu=new Cpu();
                while(p.next()) {
                    if(p.field==1) cpu.id=p.number();
                    else if(p.field==3) cpu.overrun=p.number();
                    else if(p.field==4) cpu.commitOverrun=p.number();
                    else if(p.field==7) {
                        double seconds=p.fixedDouble();
                        if(!Double.isFinite(seconds) || seconds<=0 || seconds>Long.MAX_VALUE/1e9)
                            problem="invalid ftrace stats timestamp";
                        else cpu.now=Math.round(seconds*1e9);
                    } else if(p.field==8) cpu.dropped=p.number();
                }
                if(cpu.id<0 || cpu.now<=0 || cpus.put(cpu.id,cpu)!=null || cpus.size()>MAX_CPUS)
                    problem="invalid or duplicate ftrace CPU stats";
            } else if(c.field==5 && c.length>0 || c.field==6 || c.field==7 || c.field==9)
                problem="ftrace event configuration or parsing failed";
        }
        if(phase==1) {
            if(beforeCpus.isEmpty()) beforeCpus.putAll(cpus);
            for(Cpu cpu:cpus.values()) startNs=Math.max(startNs,toMono(cpu.now,6));
        } else if(phase==2) {
            if(cpus.isEmpty() || !cpus.keySet().equals(beforeCpus.keySet()))
                problem="END ftrace stats do not cover all initial CPUs";
            long minimum=Long.MAX_VALUE;
            for(Cpu cpu:cpus.values()) {
                Cpu before=beforeCpus.get(cpu.id);
                if(before==null || cpu.overrun!=before.overrun || cpu.commitOverrun!=before.commitOverrun ||
                        cpu.dropped!=before.dropped) problem="ftrace CPU buffer lost events during capture";
                minimum=Math.min(minimum,toMono(cpu.now,6));
            }
            if(minimum!=Long.MAX_VALUE && minimum>0) {
                endStatsNs=Math.max(endStatsNs,minimum); endStatsPacket=packetCount; haveEndStats=true;
            }
        }
    }

    private void parseTraceStats(Cursor c) throws IOException {
        int buffers=0;
        long[] losses=new long[6]; boolean[] saturated=new boolean[6];
        while(c.next()) {
            if(c.field==1) {
                ++buffers; Cursor b=c.message();
                while(b.next()) {
                    int index=b.field==3?0:b.field==13?1:b.field==18?2:
                            b.field==6?3:b.field==9?4:b.field==19?5:-1;
                    if(index<0) continue;
                    long value=b.number();
                    if(value<0 || value>Long.MAX_VALUE-losses[index]) {
                        losses[index]=Long.MAX_VALUE; saturated[index]=true;
                    } else losses[index]+=value;
                }
            } else if((c.field==8 || c.field==9 || c.field==10 || c.field==14) && c.number()!=0)
                problem="Perfetto trace or flush failure";
            else if(c.field==12) flushRequested=c.number();
            else if(c.field==13) flushSucceeded=c.number();
            else if(c.field==14) flushFailed=c.number();
            else if(c.field==15) finalFlushOutcome=c.number();
        }
        if(buffers==0) problem="TraceStats does not describe the capture buffer";
        String[] names={"chunks_overwritten","bytes_overwritten","chunks_discarded",
                "patches_failed","abi_violations","trace_writer_packet_loss"};
        StringBuilder evidence=new StringBuilder(); boolean lowerBounds=false;
        for(int i=0;i<losses.length;++i) {
            // Counters are cumulative within this trace buffer. Do not sum
            // repeated snapshots, but preserve any loss already observed.
            bufferLossCounts[i]=Math.max(bufferLossCounts[i],losses[i]);
            bufferLossSaturated[i]|=saturated[i];
            if(bufferLossCounts[i]>0) {
                if(evidence.length()>0) evidence.append(", ");
                evidence.append(names[i]).append('=').append(bufferLossCounts[i]);
                lowerBounds|=bufferLossSaturated[i];
            }
        }
        if(evidence.length()>0) problem="Perfetto central buffer overwrite, discard, or packet loss: "+
                evidence+(lowerBounds?" (saturated counters are lower bounds)":"");
        haveTraceStats=true; traceStatsPacket=packetCount;
    }

    /** Diagnostic-only actual union of reported full periods, without fences. */
    long unionNs(long begin,long end) {
        ArrayList<Period> selected=new ArrayList<Period>();
        for(Period p:periods) if(p.active==p.end-p.begin && p.end>begin && p.begin<end) selected.add(p);
        Collections.sort(selected,new Comparator<Period>() {
            public int compare(Period a,Period b) { return Long.compare(a.begin,b.begin); }
        });
        long sum=0,left=-1,right=-1;
        for(Period p:selected) {
            long b=Math.max(begin,p.begin),e=Math.min(end,p.end);
            if(left<0) { left=b; right=e; }
            else if(b<=right) right=Math.max(right,e);
            else { sum+=right-left; left=b; right=e; }
        }
        return left<0?0:sum+right-left;
    }

    long frequencyCount() { return frequencyCount; }
    long workCoverageBegin() {
        long value=Long.MAX_VALUE; for(Period p:periods) value=Math.min(value,p.begin);
        return value==Long.MAX_VALUE?0:value;
    }
    long workCoverageEnd() { return latestPeriodNs; }

    private static final class Cursor {
        final byte[] data; final int end; int position, field,wire,begin,length,fields;
        long value;
        Cursor(byte[] d,int start,int end) { data=d; position=start; this.end=end; }
        Cursor copy() { return new Cursor(data,position,end); }
        Long maybeVarint() throws IOException {
            int p=position; long result=0;
            for(int i=0;i<10;++i) {
                if(position==end) { position=p; return null; }
                int b=data[position++]&255;
                if(i==9 && b>1) throw new IOException("protobuf varint overflow");
                result|=(long)(b&127)<<(7*i);
                if((b&128)==0) return result;
            }
            throw new IOException("protobuf varint overflow");
        }
        long varint() throws IOException {
            Long number=maybeVarint(); if(number==null) throw new IOException("truncated protobuf varint");
            return number;
        }
        boolean next() throws IOException {
            if(position==end) return false;
            if(++fields>65536) throw new IOException("protobuf field count limit");
            long tag=varint();
            if(tag<=0 || tag>>>3==0 || tag>>>3>536870911) throw new IOException("invalid protobuf field number");
            field=(int)(tag>>>3); wire=(int)(tag&7); length=0; begin=position; value=0;
            if(wire==0) value=varint();
            else if(wire==2) {
                long n=varint(); if(n<0 || n>end-position) throw new IOException("truncated protobuf message");
                begin=position; length=(int)n; position+=length;
            } else if(wire==1 || wire==5) {
                length=wire==1?8:4;
                if(length>end-position) throw new IOException("truncated protobuf fixed field");
                begin=position; position+=length;
            } else throw new IOException("unsupported protobuf wire type");
            return true;
        }
        long number() throws IOException {
            if(wire!=0) throw new IOException("protobuf number wire mismatch"); return value;
        }
        Cursor message() throws IOException {
            if(wire!=2) throw new IOException("protobuf message wire mismatch");
            return new Cursor(data,begin,begin+length);
        }
        double fixedDouble() throws IOException {
            if(wire!=1) throw new IOException("protobuf double wire mismatch");
            long bits=0; for(int i=0;i<8;++i) bits|=(long)(data[begin+i]&255)<<(8*i);
            return Double.longBitsToDouble(bits);
        }
    }
}
