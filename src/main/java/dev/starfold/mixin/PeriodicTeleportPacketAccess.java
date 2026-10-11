package dev.starfold.mixin;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientboundTeleportEntityPacket.class)
public interface PeriodicTeleportPacketAccess {
    @Mutable @Accessor("x") void starfold$coverX(double x);
    @Mutable @Accessor("z") void starfold$coverZ(double z);
    @Mutable @Accessor("yRot") void starfold$coverYaw(byte yaw);
}
