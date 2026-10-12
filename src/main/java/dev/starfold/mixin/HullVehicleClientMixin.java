package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.starfold.ship.HullVehicles;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sable 2.0.5 pins a living entity that stands on a hull to the hull on the client, but only eases any other entity towards
 * the place its last packet named ("TODO: Finish for non living entities"): a parked boat or aircraft trails a moving ship and
 * catches up when it stops. Applied after Sable's own wrapper, which does not call on, so this one is the outer. */
@Mixin(value = ClientPacketListener.class, priority = 1500)
public abstract class HullVehicleClientMixin {
    @WrapOperation(
            method = "handleTeleportEntity",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"))
    private void starfold$parkedTeleport(
            Entity entity,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            int steps,
            Operation<Void> original,
            @Local(argsOnly = true) ClientboundTeleportEntityPacket packet) {
        original.call(entity, x, y, z, yRot, xRot, steps);
        HullVehicles.placed(entity, x, y, z, packet);
    }

    @WrapOperation(
            method = "handleMoveEntity",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V",
                            ordinal = 0))
    private void starfold$parkedMove(
            Entity entity,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            int steps,
            Operation<Void> original,
            @Local(argsOnly = true) ClientboundMoveEntityPacket packet) {
        original.call(entity, x, y, z, yRot, xRot, steps);
        HullVehicles.placed(entity, x, y, z, packet);
    }
}
