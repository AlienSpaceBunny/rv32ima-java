import com.alienspacebunny.emu.*;
import java.lang.foreign.*;
import java.nio.ByteOrder;

/** Standalone review probe, outside Gradle source sets. Findings are printed;
 * only the positive smoke checks assert. Exit zero does not mean ISA conformance.
 * Run command and expected observations: ../CPU_INTEGRATION_REVIEW.md.
 */
public class CoreFeatureProbe {
    static final ValueLayout.OfInt WORD = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static class Bus implements MemoryBus {
        final MemorySegment memory = MemorySegment.ofArray(new byte[1024]);
        boolean faultSc;
        AccessContext last;
        public byte readByte(int a) { return memory.get(ValueLayout.JAVA_BYTE,a); }
        public short readShort(int a) { return memory.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),a); }
        public int readInt(int a) { return memory.get(WORD,a); }
        public void writeByte(int a,byte v) { memory.set(ValueLayout.JAVA_BYTE,a,v); }
        public void writeShort(int a,short v) { memory.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),a,v); }
        public void writeInt(int a,int v) { memory.set(WORD,a,v); }
        public int readInt(int a,AccessContext c) { last=c; return readInt(a); }
        public int tryScAndStore(int h,int a,int v,AccessContext c) { if(faultSc) throw new IndexOutOfBoundsException(); return MemoryBus.super.tryScAndStore(h,a,v,c); }
    }
    static RV32IMAState state() { var s=new RV32IMAState(); s.extraflags=3; s.mtvec=0x80; s.mcause=-1; s.hartId=7; return s; }
    static int amo(int op,int width,int rs2) { return (op<<27)|(rs2<<20)|(1<<15)|(width<<12)|(3<<7)|0x2f; }
    static void step(RV32IMACore c,RV32IMAState s,Bus b,int n) { c.step(s,b,0,1024,0,n,null,null); }
    static void report(String label,RV32IMAState s) { System.out.printf("%s: pc=0x%x cause=%d mtval=0x%x privilege=%d reservation=%s x3=%d%n",label,s.pc,s.mcause,s.mtval,s.extraflags&3,s.reservationValid,s.regs[3]); }
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); System.out.println("PASS: "+label); }
    public static void main(String[] args) {
        var core=new RV32IMACore(IsaConfig.RV32IMFC_ZBA_ZBB_ZICSR);
        var b=new Bus(); var s=state(); b.writeInt(0,0x30200073); s.extraflags=0; s.mstatus=3<<11; s.mepc=0x40; step(core,s,b,1); report("U-mode MRET (expected illegal cause 2)",s);
        b=new Bus(); s=state(); b.writeInt(0,0x10500073); s.mie=1<<3; RV32IMACore.injectInterrupt(s,3); step(core,s,b,1); int result=core.step(s,b,0,1024,0,1,null,null); report("MSIP pending before WFI (expected wake/delivery)",s); System.out.println("second step result="+result+" WFI="+((s.extraflags&4)!=0));
        b=new Bus(); s=state(); b.writeInt(0,amo(2,2,0)); s.regs[1]=0x101; step(core,s,b,1); report("Misaligned LR.W (expected cause 4 or 5)",s);
        b=new Bus(); s=state(); b.writeInt(0,amo(0,2,2)); s.regs[1]=0x101; s.regs[2]=1; step(core,s,b,1); report("Misaligned AMOADD.W (no misaligned atomicity PMA)",s); System.out.println("memory[0x101]="+b.readInt(0x101));
        b=new Bus(); s=state(); b.writeInt(0,amo(2,2,2)); s.regs[1]=0x100; step(core,s,b,1); report("LR.W with rs2 != 0 (expected illegal cause 2)",s);
        b=new Bus(); s=state(); b.writeInt(0,amo(2,2,0)); b.writeInt(4,amo(3,2,2)); s.regs[1]=0x100; step(core,s,b,1); b.faultSc=true; step(core,s,b,1); report("Faulting SC.W (reservation lifecycle)",s);
        b=new Bus(); s=state(); b.writeInt(0,amo(3,2,2)); s.regs[1]=0x10000; b.faultSc=true; step(core,s,b,1); report("SC.W without reservation to denied address (expected permission fault)",s);
        b=new Bus(); s=state(); b.writeInt(0,0x300021f3); s.extraflags=0; step(core,s,b,1); check(s.mcause==2,"U-mode machine CSR read rejected");
        b=new Bus(); s=state(); b.writeInt(0,0x73); s.extraflags=0; step(core,s,b,1); check(s.mcause==8 && (s.extraflags&3)==3,"U-mode ECALL enters M-mode on same hart");
        b=new Bus(); s=state(); s.mie=1<<3; s.mstatus=8; RV32IMACore.injectInterrupt(s,3); step(core,s,b,1); check(s.mcause==0x80000003,"enabled MSIP delivered");
        b=new Bus(); s=state(); b.writeInt(0,amo(2,2,0)); s.regs[1]=0x100; step(core,s,b,1); check(b.last.hartId()==7 && b.last.kind()==AccessKind.AMO && b.last.atomicOp()==2,"LR access context");
        b=new Bus(); s=state(); b.writeShort(0,(short)0x0085); step(core,s,b,1); check(s.regs[1]==1 && s.pc==2,"C.ADDI");
        b=new Bus(); s=state(); b.writeInt(0,(0x10<<25)|(2<<20)|(1<<15)|(2<<12)|(3<<7)|0x33); s.regs[1]=3; s.regs[2]=4; step(core,s,b,1); check(s.regs[3]==10,"Zba SH1ADD");
        b=new Bus(); s=state(); b.writeInt(0,(0x600<<20)|(1<<15)|(1<<12)|(3<<7)|0x13); s.regs[1]=1; step(core,s,b,1); check(s.regs[3]==31,"Zbb CLZ");
        b=new Bus(); s=state(); b.writeInt(0,(2<<20)|(1<<15)|(3<<7)|0x53); s.fregs[1]=0xffffffff3f800000L; s.fregs[2]=0xffffffff40000000L; step(core,s,b,1); check((int)s.fregs[3]==0x40400000,"FADD.S");
        b=new Bus(); s=state(); b.writeInt(0,amo(0,0,2)); b.writeInt(0x100,0x1234567f); s.regs[1]=0x100; s.regs[2]=1; step(new RV32IMACore(new IsaConfig(false,false,false,false,true)),s,b,1); check(b.readInt(0x100)==0x12345680 && s.regs[3]==127,"Zabha AMOADD.B preserves neighbors");
    }
}
