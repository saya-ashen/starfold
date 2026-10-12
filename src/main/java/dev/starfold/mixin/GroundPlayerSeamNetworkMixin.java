package dev.starfold.mixin;

import dev.starfold.ground.GroundSeams;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GroundPlayerSeamNetworkMixin {
    @Shadow public ServerPlayer player;
    @Shadow private net.minecraft.world.phys.Vec3 awaitingPositionFromClient;

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void starfold$nativePlayerSeam(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        starfold$finishSeam();
    }

    @Inject(method = "handleAcceptTeleportPacket", at = @At("RETURN"))
    private void starfold$nativeAcknowledgedSeam(
            net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet,
            CallbackInfo ci) {
        starfold$finishSeam();
    }

    @Shadow
    private double vehicleFirstGoodX,
            vehicleFirstGoodY,
            vehicleFirstGoodZ,
            vehicleLastGoodX,
            vehicleLastGoodY,
            vehicleLastGoodZ;

    @Inject(method = "handleMoveVehicle", at = @At("RETURN"))
    private void starfold$nativeVehicleSeam(
            net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet,
            CallbackInfo ci) {
        starfold$finishVehicleSeam();
    }

    @Unique
    private void starfold$finishVehicleSeam() {
        // The vehicle a client steers is where that client put it, as the player's own body is: its
        // chart changes
        // once vanilla has finished with the packet, and vanilla's baseline for the next one moves
        // with it.
        var vehicle = player.getRootVehicle();
        if (awaitingPositionFromClient != null
                || vehicle == player
                || vehicle.getControllingPassenger() != player
                || !GroundSeams.moved(vehicle)) return;
        vehicleFirstGoodX = vehicleLastGoodX = vehicle.getX();
        vehicleFirstGoodY = vehicleLastGoodY = vehicle.getY();
        vehicleFirstGoodZ = vehicleLastGoodZ = vehicle.getZ();
    }

    @Unique
    private void starfold$finishSeam() {
        // Vanilla's ACK restores its saved destination and lastGood coordinates. Re-rooting before
        // that
        // leaves the new frame paired with the old destination, producing a chart-sized collision
        // sweep.
        if (awaitingPositionFromClient == null) GroundSeams.moved(player);
    }
}
