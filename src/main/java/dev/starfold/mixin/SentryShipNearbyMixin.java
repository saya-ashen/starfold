package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Sentry 1.3.7 sends its shot, charge and reload packets to players within 64 blocks of the arm's block position.
 * On a hull that position is in the plot, millions of blocks from anyone, so nobody aboard heard or saw the gun.
 * Measure from where the arm really is. Ordinary land turrets are untouched. */
@Pseudo
@Mixin(targets="euphy.upo.sentrymechanicalarm.network.NetworkHandler", remap=false)
public abstract class SentryShipNearbyMixin {
    @WrapOperation(method="sendToNearby", at=@At(value="INVOKE", target="Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(DDD)D"))
    private static double starfold$fromTheHull(ServerPlayer player, double x, double y, double z, Operation<Double> original) {
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return original.call(player, x, y, z);
        var at = dev.starfold.NativeSableFrames.world(player.level(), x, y, z);
        return original.call(player, at.x, at.y, at.z);
    }
}
