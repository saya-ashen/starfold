package dev.starfold;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLightPayloadTest {
    private static byte[] payload(boolean data,boolean empty,int value){
        var b=new FriendlyByteBuf(Unpooled.buffer());try{
            var present=new java.util.BitSet();if(data)present.set(3);var zero=new java.util.BitSet();if(empty)zero.set(3);
            b.writeBitSet(present);b.writeBitSet(new java.util.BitSet());b.writeBitSet(zero);b.writeBitSet(new java.util.BitSet());
            b.writeVarInt(data?1:0);if(data){var a=new byte[2048];java.util.Arrays.fill(a,(byte)value);b.writeByteArray(a);}b.writeVarInt(0);
            var bytes=new byte[b.writerIndex()];b.getBytes(0,bytes);return bytes;
        }finally{b.release();}
    }
    @Test void zeroArrayAndEmptyMaskHaveIdenticalLight(){assertTrue(NativeLightPayload.same(payload(true,false,0),payload(false,true,0)));}
    @Test void changedNibbleIsNotEquivalent(){assertFalse(NativeLightPayload.same(payload(true,false,0x11),payload(false,true,0)));assertFalse(NativeLightPayload.same(payload(true,false,0x11),payload(true,false,0x12)));}
    @Test void absentIsNotAnExplicitZeroUpdate(){assertFalse(NativeLightPayload.same(payload(false,false,0),payload(false,true,0)));}
    @Test void identicalNonzeroPayloadsAgree(){assertTrue(NativeLightPayload.same(payload(true,false,0xAB),payload(true,false,0xAB)));}
}
