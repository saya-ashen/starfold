package dev.starfold.mixin;

import dev.starfold.flight.GroundFlightServer;

import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flight metadata owns high-altitude movement, not a second vanilla movement packet stream. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GroundFlightNetworkMixin {
    @Shadow public ServerPlayer player;

    private boolean starfold$owns() {
        return player.getServer().isSameThread() && GroundFlightServer.controlled(player);
    }

    // Vanilla accepts absMoveTo(packet coordinates) after Entity.move, even in creative mode.
    // Clip the proposed packet too, so that path cannot bypass the server's sun wall.
    @ModifyVariable(method = "handleMovePlayer", at = @At("HEAD"), argsOnly = true)
    private ServerboundMovePlayerPacket starfold$sunPacket(ServerboundMovePlayerPacket packet) {
        if (!player.getServer().isSameThread()
                || !dev.starfold.sky.Space.in(player)
                || player.isPassenger()) return packet;
        var motion =
                new net.minecraft.world.phys.Vec3(
                        packet.getX(player.getX()) - player.getX(),
                        packet.getY(player.getY()) - player.getY(),
                        packet.getZ(player.getZ()) - player.getZ());
        // Let vanilla reject non-finite packets rather than passing them into geometry.
        if (!Double.isFinite(motion.x) || !Double.isFinite(motion.y) || !Double.isFinite(motion.z))
            return packet;
        var limited = dev.starfold.space.SpaceServer.sunMotion(player, motion);
        if (limited.distanceToSqr(motion) < 1e-16) return packet;
        return new ServerboundMovePlayerPacket.PosRot(
                player.getX() + limited.x,
                player.getY() + limited.y,
                player.getZ() + limited.z,
                packet.getYRot(player.getYRot()),
                packet.getXRot(player.getXRot()),
                packet.isOnGround());
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (starfold$owns()) ci.cancel();
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void starfold$nativePlace(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (starfold$owns()) ci.cancel();
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeBreak(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!starfold$owns()) return;
        switch (packet.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> ci.cancel();
            default -> {}
        }
    }
}
