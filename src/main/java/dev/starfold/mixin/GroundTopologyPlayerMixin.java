package dev.starfold.mixin;

import dev.starfold.chart.PlanetChart;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundSeams;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Canonical menu/placement reach in the player's current upright chart, not plot separation. */
@Mixin(Player.class)
public abstract class GroundTopologyPlayerMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "canInteractWithEntity(Lnet/minecraft/world/entity/Entity;D)Z",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private AABB starfold$entityReach(
            net.minecraft.world.entity.Entity entity,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<AABB> original) {
        var p = (Player) (Object) this;
        var box = original.call(entity);
        if (!PlanetLevels.canonical(p.level())) return box;
        var image = GroundSeams.box(p.level(), box, p.getX(), p.getZ());
        return image == null ? box : image;
    }

    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeReach(
            BlockPos pos, double extra, CallbackInfoReturnable<Boolean> ci) {
        var p = (Player) (Object) this;
        var access = PlanetAccess.of(p.level());
        if (!PlanetLevels.canonical(p.level())
                || !access.inChart(pos)
                || !access.inChart(p.blockPosition())) return;
        if (!access.samePlanet(p.getX(), p.getZ(), pos.getX(), pos.getZ())) {
            ci.setReturnValue(false);
            return;
        }
        var owner = access.cell(access.address(pos).owner());
        var root = CubeSurfaceTopology.Face.values()[access.face(p.getX())];
        var tile = new PlanetChart(access.shape(p.getX(), p.getZ()), root).tile(owner.face());
        if (tile == null) {
            ci.setReturnValue(false);
            return;
        }
        var center =
                access.originAt(p.getX(), p.getZ())
                        .add(
                                tile.point(
                                        new BentStripGeometry.Point(
                                                owner.u() + .5, owner.h() + .5, owner.v() + .5)));
        double range = p.blockInteractionRange() + extra;
        ci.setReturnValue(
                new AABB(
                                        center.x() - .5,
                                        center.y() - .5,
                                        center.z() - .5,
                                        center.x() + .5,
                                        center.y() + .5,
                                        center.z() + .5)
                                .distanceToSqr(p.getEyePosition())
                        < range * range);
    }
}
