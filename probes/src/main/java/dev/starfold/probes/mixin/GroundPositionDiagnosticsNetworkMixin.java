package dev.starfold.probes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.dev.DevPositionDiagnostics;

import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GroundPositionDiagnosticsNetworkMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "handleMovePlayer")
    private void starfold$diagnosticMove(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        // The Netty pass returns a null scope: ensureRunningOnSameThread keeps its original scheduling.
        var scope = DevPositionDiagnostics.begin(player, "handle-move",
                () -> DevPositionDiagnostics.move(packet), false);
        try {
            original.call(packet);
        } finally {
            DevPositionDiagnostics.end(scope);
        }
    }

    @WrapMethod(method = "handleAcceptTeleportPacket")
    private void starfold$diagnosticAck(ServerboundAcceptTeleportationPacket packet,
                                       Operation<Void> original) {
        var scope = DevPositionDiagnostics.begin(player, "handle-ack", () -> "ackId=" + packet.getId(), true);
        try {
            original.call(packet);
        } finally {
            DevPositionDiagnostics.end(scope);
        }
    }

    @WrapMethod(method = "teleport(DDDFFLjava/util/Set;)V")
    private void starfold$diagnosticTeleport(double x, double y, double z, float yaw, float pitch,
                                            Set<RelativeMovement> relative, Operation<Void> original) {
        var scope = DevPositionDiagnostics.begin(player, "server-teleport",
                () -> "xyz=" + java.util.List.of(x, y, z) + " yaw=" + yaw + " pitch=" + pitch
                        + " relative=" + relative, true);
        try {
            original.call(x, y, z, yaw, pitch, relative);
        } finally {
            DevPositionDiagnostics.end(scope);
        }
    }
}
