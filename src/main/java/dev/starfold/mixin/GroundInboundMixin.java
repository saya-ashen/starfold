package dev.starfold.mixin;

import dev.starfold.ground.PacketMap;

import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Handlers run twice (network thread, then rescheduled on the server thread): map once, on the server thread. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GroundInboundMixin {
    @Shadow public ServerPlayer player;

    @Unique
    private boolean starfold$server() {
        return player.getServer().isSameThread();
    }

    @ModifyVariable(method = "handleMovePlayer", at = @At("HEAD"), argsOnly = true)
    private ServerboundMovePlayerPacket starfold$nativeMoveFrame(
            ServerboundMovePlayerPacket packet) {
        return starfold$server()
                ? (ServerboundMovePlayerPacket) PacketMap.inbound(player, packet)
                : packet;
    }

    @ModifyVariable(method = "handleMoveVehicle", at = @At("HEAD"), argsOnly = true)
    private ServerboundMoveVehiclePacket starfold$nativeVehicleFrame(
            ServerboundMoveVehiclePacket packet) {
        return starfold$server()
                ? (ServerboundMoveVehiclePacket) PacketMap.inbound(player, packet)
                : packet;
    }

    @ModifyVariable(method = "handleUseItemOn", at = @At("HEAD"), argsOnly = true)
    private ServerboundUseItemOnPacket starfold$nativeUseOnFrame(
            ServerboundUseItemOnPacket packet) {
        return starfold$server()
                ? (ServerboundUseItemOnPacket) PacketMap.inbound(player, packet)
                : packet;
    }

    @ModifyVariable(method = "handlePlayerAction", at = @At("HEAD"), argsOnly = true)
    private ServerboundPlayerActionPacket starfold$nativeActionFrame(
            ServerboundPlayerActionPacket packet) {
        return starfold$server()
                ? (ServerboundPlayerActionPacket) PacketMap.inbound(player, packet)
                : packet;
    }

    @ModifyVariable(method = "handleUseItem", at = @At("HEAD"), argsOnly = true)
    private ServerboundUseItemPacket starfold$nativeUseFrame(ServerboundUseItemPacket packet) {
        return starfold$server()
                ? (ServerboundUseItemPacket) PacketMap.inbound(player, packet)
                : packet;
    }

    @ModifyVariable(method = "handleSignUpdate", at = @At("HEAD"), argsOnly = true)
    private ServerboundSignUpdatePacket starfold$nativeSignFrame(
            ServerboundSignUpdatePacket packet) {
        return starfold$server()
                ? (ServerboundSignUpdatePacket) PacketMap.inbound(player, packet)
                : packet;
    }
}
