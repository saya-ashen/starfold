package dev.starfold;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The carry's atomic pose/feet update, but restoring a saved place rather than preserving the live one. */
public record NativeSableBoardingPacket(NativeSableCarry carry) implements CustomPacketPayload {
    public static final Type<NativeSableBoardingPacket> TYPE=new Type<>(ResourceLocation.parse("starfold:native_sable_boarding"));
    public static final StreamCodec<RegistryFriendlyByteBuf,NativeSableBoardingPacket> CODEC=StreamCodec.of((b,p)->NativeSableCarry.CODEC.encode(b,p.carry),b->new NativeSableBoardingPacket(NativeSableCarry.CODEC.decode(b)));
    @Override public Type<NativeSableBoardingPacket> type(){return TYPE;}
}
