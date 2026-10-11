package dev.starfold.mixin;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ClientboundSableSnapshotDualPacket.class,remap=false)
public interface NativeSableSnapshotAccess {
    @Accessor(value="interpolationTick",remap=false) int starfold$interpolationTick();
}
