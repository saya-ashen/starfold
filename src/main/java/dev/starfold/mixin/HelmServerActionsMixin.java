package dev.starfold.mixin;

import dev.starfold.ship.HelmActions;
import dev.starfold.ship.HelmServer;

import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Checks run after the vanilla thread fence, including for modified clients. Ack rejected predictions. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class HelmServerActionsMixin {
    @Shadow public ServerPlayer player;

    @Shadow
    public abstract void ackBlockChangesUpTo(int sequence);

    private boolean blocked() {
        return !HelmActions.allowed(HelmServer.active(player), HelmActions.Action.ATTACK);
    }

    @Inject(
            method = "handlePlayerAction",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void starfold$action(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (blocked()) {
            ackBlockChangesUpTo(packet.getSequence());
            player.connection.send(
                    new ClientboundBlockUpdatePacket(player.level(), packet.getPos()));
            ci.cancel();
        }
    }

    @Inject(
            method = "handleUseItemOn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void starfold$useOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (blocked()) {
            ackBlockChangesUpTo(packet.getSequence());
            var pos = packet.getHitResult().getBlockPos();
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
            player.connection.send(
                    new ClientboundBlockUpdatePacket(
                            player.level(), pos.relative(packet.getHitResult().getDirection())));
            ci.cancel();
        }
    }

    @Inject(
            method = "handleUseItem",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void starfold$use(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (blocked()) {
            ackBlockChangesUpTo(packet.getSequence());
            ci.cancel();
        }
    }

    @Inject(
            method = {
                "handlePickItem",
                "handleInteract",
                "handleAnimate",
                "handleContainerClick",
                "handleSetCreativeModeSlot"
            },
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void starfold$other(CallbackInfo ci) {
        if (blocked()) {
            player.containerMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }
}
