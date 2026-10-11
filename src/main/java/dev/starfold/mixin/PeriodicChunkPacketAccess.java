package dev.starfold.mixin;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Only fresh per-recipient packets may be rewritten; never mutate a broadcast packet. */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public interface PeriodicChunkPacketAccess {
    @Mutable @Accessor("x") void starfold$coverX(int x);
    @Mutable @Accessor("z") void starfold$coverZ(int z);
}
