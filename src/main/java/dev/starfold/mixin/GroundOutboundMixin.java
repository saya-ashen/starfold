package dev.starfold.mixin;

import dev.starfold.ground.PacketMap;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Storage coordinates never reach a native observer's client: see PacketMap. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class GroundOutboundMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    protected net.minecraft.network.Connection connection;

    // NeoForge's bundle filter emits zero messages for an empty bundle; Netty rejects that.
    // The Fabric delimiter-only bundle was a client no-op. Skip it after both HEAD mappings.
    @Inject(
            method =
                    "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/neoforged/neoforge/network/registration/NetworkRegistry;checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V"),
            cancellable = true)
    private void starfold$emptyImage(
            Packet<?> packet,
            net.minecraft.network.PacketSendListener listener,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle
                && !bundle.subPackets().iterator().hasNext()) {
            if (listener != null)
                connection.runOnceConnected(
                        c -> {
                            if (c.channel().eventLoop().inEventLoop()) listener.onSuccess();
                            else c.channel().eventLoop().execute(listener::onSuccess);
                        });
            ci.cancel();
        }
    }

    @ModifyVariable(
            method =
                    "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Packet<?> starfold$nativeImage(Packet<?> packet) {
        return (Object) this instanceof ServerGamePacketListenerImpl game
                ? PacketMap.outbound(game.player, packet)
                : packet;
    }
}
