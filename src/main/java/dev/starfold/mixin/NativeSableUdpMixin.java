package dev.starfold.mixin;
import dev.starfold.NativePayloadMap;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Sable's datagrams do not pass the player's connection, where every other packet is relabelled for its observer. */
@Mixin(value=SableUDPServer.class,remap=false)
public abstract class NativeSableUdpMixin {
    @Inject(method="sendUDPPacket",at=@At("HEAD"),cancellable=true,remap=false)
    private void starfold$orderedCarry(ServerPlayer player,SableUDPPacket packet,boolean flush,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci){
        if(dev.starfold.NativeSableFrames.reliable(player)&&packet instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket snapshot){
            // The argument hook leaves this route alone; map once, then send behind the carry on TCP.
            dev.starfold.NativePacketMap.raw(player,new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(NativePayloadMap.relabel(player,snapshot)));ci.setReturnValue(true);
        }
    }
    @ModifyVariable(method="sendUDPPacket",at=@At("HEAD"),argsOnly=true,remap=false)
    private SableUDPPacket starfold$nativeFrame(SableUDPPacket packet,ServerPlayer player){return dev.starfold.NativeSableFrames.reliable(player)&&packet instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket?packet:NativePayloadMap.relabel(player,packet);}
}
