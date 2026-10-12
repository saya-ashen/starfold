package dev.starfold.ship;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The carry's atomic pose/feet update, but restoring a saved place rather than preserving the live one. */
public record SableBoardingPacket(SableCarry carry) implements CustomPacketPayload {
    public static final Type<SableBoardingPacket> TYPE =
            new Type<>(ResourceLocation.parse("starfold:native_sable_boarding"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SableBoardingPacket> CODEC =
            StreamCodec.of(
                    (b, p) -> SableCarry.CODEC.encode(b, p.carry),
                    b -> new SableBoardingPacket(SableCarry.CODEC.decode(b)));

    @Override
    public Type<SableBoardingPacket> type() {
        return TYPE;
    }
}
